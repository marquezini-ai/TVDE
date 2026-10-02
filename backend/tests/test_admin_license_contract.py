from __future__ import annotations

import base64
from uuid import uuid4

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from conftest import ADMIN_LICENSE, json_bytes, register_device, signed_headers


PATH = "/v1/admin/client-licenses/issue"


def issue(client, clock, private_key, installation_id, payload, *, idempotency_key=None):
    body = json_bytes(payload)
    headers = signed_headers(
        private_key=private_key,
        method="POST",
        path=PATH,
        principal=installation_id,
        body=body,
        timestamp=clock(),
        idempotency_key=idempotency_key,
    )
    headers["X-TVDE-Installation-Id"] = installation_id
    return client.post(PATH, content=body, headers=headers)


def test_admin_issues_verifiable_client_license(client, clock, device_key, license_signing_key):
    registration = register_device(client, clock, device_key, ADMIN_LICENSE)
    installation_id = registration.json()["installation_id"]
    expiry = (clock() + 86_400) * 1000
    response = issue(
        client,
        clock,
        device_key,
        installation_id,
        {"android_id": "abcdef1234567890", "expires_at_epoch_ms": expiry, "license_type": "CUSTOM"},
    )

    assert response.status_code == 200
    result = response.json()
    assert result["android_id"] == "abcdef1234567890"
    payload_part, signature_part = result["activation_key"].split(".")
    decode = lambda value: base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    signed_payload = decode(payload_part)
    assert signed_payload == f"v1|abcdef1234567890|{expiry}|CUSTOM".encode()
    license_signing_key.public_key().verify(decode(signature_part), signed_payload, ec.ECDSA(hashes.SHA256()))
    assert client.app.state.harness.licenses[result["activation_key"]].role.value == "CLIENT"


def test_issue_is_admin_only_and_idempotent(client, clock, device_key):
    client_registration = register_device(client, clock, device_key)
    payload = {"android_id": "abcdef1234567890", "expires_at_epoch_ms": (clock() + 86_400) * 1000}
    forbidden = issue(client, clock, device_key, client_registration.json()["installation_id"], payload)
    assert (forbidden.status_code, forbidden.json()["error"]["code"]) == (403, "ROLE_FORBIDDEN")

    admin_key = ec.generate_private_key(ec.SECP256R1())
    admin_registration = register_device(client, clock, admin_key, ADMIN_LICENSE)
    request_id = str(uuid4())
    first = issue(client, clock, admin_key, admin_registration.json()["installation_id"], payload, idempotency_key=request_id)
    second = issue(client, clock, admin_key, admin_registration.json()["installation_id"], payload, idempotency_key=request_id)
    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()


def test_issue_rejects_expired_or_excessive_expiry(client, clock, device_key):
    registration = register_device(client, clock, device_key, ADMIN_LICENSE)
    installation_id = registration.json()["installation_id"]
    for expiry in ((clock() - 1) * 1000, (clock() + 3_651 * 86_400) * 1000):
        response = issue(
            client,
            clock,
            device_key,
            installation_id,
            {"android_id": "abcdef1234567890", "expires_at_epoch_ms": expiry},
        )
        assert (response.status_code, response.json()["error"]["code"]) == (422, "VALIDATION_FAILED")
