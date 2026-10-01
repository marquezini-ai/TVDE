from __future__ import annotations

import secrets
from uuid import uuid4

from cryptography.hazmat.primitives.asymmetric import ec

from conftest import (
    VALID_LICENSE,
    json_bytes,
    register_device,
    registration_payload,
    signed_headers,
)
from tvde_contract.models import Role
from tvde_contract.security import jwk_thumbprint, public_jwk_from_key


def post_registration(client, clock, private_key, payload, **header_overrides):
    body = json_bytes(payload)
    key_id = jwk_thumbprint(public_jwk_from_key(private_key.public_key()))
    headers = signed_headers(
        private_key=private_key,
        method="POST",
        path="/v1/installations/register",
        principal=key_id,
        body=body,
        timestamp=header_overrides.pop("timestamp", clock()),
        nonce=header_overrides.pop("nonce", None),
        idempotency_key=header_overrides.pop("idempotency_key", None),
    )
    headers["X-TVDE-Key-Id"] = key_id
    headers.update(header_overrides)
    return client.post("/v1/installations/register", content=body, headers=headers)


def test_valid_registration_returns_server_role_and_policy(client, clock, device_key):
    response = register_device(client, clock, device_key)

    assert response.status_code == 200
    assert response.json()["role"] == "CLIENT"
    assert response.json()["policy_version"] == 1
    assert response.json()["sync_policy"]["max_events_per_sync"] == 100


def test_invalid_expired_and_revoked_licenses_are_rejected(app, client, clock, device_key):
    expired = "test.activation.expired.client.00001"
    revoked = "test.activation.revoked.client.00001"
    app.state.harness.add_license(expired, expires_at=clock() - 1)
    app.state.harness.add_license(revoked, revoked=True)

    invalid_response = post_registration(client, clock, device_key, registration_payload(device_key, "test.activation.unknown.client.00001"))
    assert (invalid_response.status_code, invalid_response.json()["error"]["code"]) == (403, "LICENSE_INVALID")

    expired_key = ec.generate_private_key(ec.SECP256R1())
    expired_response = post_registration(client, clock, expired_key, registration_payload(expired_key, expired))
    assert (expired_response.status_code, expired_response.json()["error"]["code"]) == (403, "LICENSE_EXPIRED")

    revoked_key = ec.generate_private_key(ec.SECP256R1())
    revoked_response = post_registration(client, clock, revoked_key, registration_payload(revoked_key, revoked))
    assert (revoked_response.status_code, revoked_response.json()["error"]["code"]) == (403, "LICENSE_REVOKED")


def test_same_key_registration_is_stable_but_new_key_requires_reset(client, clock, device_key):
    first = register_device(client, clock, device_key)
    second = register_device(client, clock, device_key)
    assert second.status_code == 200
    assert second.json()["installation_id"] == first.json()["installation_id"]

    replacement_key = ec.generate_private_key(ec.SECP256R1())
    conflict = register_device(client, clock, replacement_key)
    assert (conflict.status_code, conflict.json()["error"]["code"]) == (409, "LICENSE_ALREADY_BOUND")


def test_registration_idempotency_returns_original_response(client, clock, device_key):
    payload = registration_payload(device_key)
    key = str(uuid4())
    first = post_registration(client, clock, device_key, payload, idempotency_key=key)
    second = post_registration(client, clock, device_key, payload, idempotency_key=key)
    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()


def test_registration_replay_and_clock_skew_are_rejected(client, clock, device_key):
    payload = registration_payload(device_key)
    nonce = secrets.token_urlsafe(16)
    first = post_registration(client, clock, device_key, payload, nonce=nonce)
    replay = post_registration(client, clock, device_key, payload, nonce=nonce)
    stale_key = ec.generate_private_key(ec.SECP256R1())
    stale = post_registration(
        client,
        clock,
        stale_key,
        registration_payload(stale_key, "test.activation.stale.client.0000001"),
        timestamp=clock() - 301,
    )
    assert first.status_code == 200
    assert (replay.status_code, replay.json()["error"]["code"]) == (409, "REPLAY_DETECTED")
    assert (stale.status_code, stale.json()["error"]["code"]) == (401, "TIMESTAMP_OUT_OF_RANGE")


def test_invalid_signature_body_mutation_and_role_spoof_are_rejected(client, clock, device_key):
    payload = registration_payload(device_key)
    body = json_bytes(payload)
    key_id = jwk_thumbprint(public_jwk_from_key(device_key.public_key()))
    other_key = ec.generate_private_key(ec.SECP256R1())
    headers = signed_headers(
        private_key=other_key,
        method="POST",
        path="/v1/installations/register",
        principal=key_id,
        body=body,
        timestamp=clock(),
    )
    headers["X-TVDE-Key-Id"] = key_id
    invalid = client.post("/v1/installations/register", content=body, headers=headers)
    assert (invalid.status_code, invalid.json()["error"]["code"]) == (401, "INVALID_SIGNATURE")

    spoofed = registration_payload(ec.generate_private_key(ec.SECP256R1()))
    spoofed["role"] = Role.ADMIN.value
    spoof_key = ec.generate_private_key(ec.SECP256R1())
    # The key and body must agree; the unknown field is what is under test.
    spoofed = registration_payload(spoof_key)
    spoofed["role"] = Role.ADMIN.value
    response = post_registration(client, clock, spoof_key, spoofed)
    assert (response.status_code, response.json()["error"]["code"]) == (422, "VALIDATION_FAILED")


def test_revoked_installation_cannot_register_again(app, client, clock, device_key):
    registered = register_device(client, clock, device_key)
    installation_id = registered.json()["installation_id"]
    app.state.harness.revoke_installation(__import__("uuid").UUID(installation_id))
    response = register_device(client, clock, device_key)
    assert (response.status_code, response.json()["error"]["code"]) == (403, "INSTALLATION_REVOKED")
