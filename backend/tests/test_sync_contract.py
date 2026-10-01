from __future__ import annotations

import json
import secrets
from concurrent.futures import ThreadPoolExecutor
from uuid import UUID, uuid4

from cryptography.hazmat.primitives.asymmetric import ec

from conftest import (
    aggregate_query,
    json_bytes,
    offer_event,
    register_device,
    signed_headers,
    sync_request,
)
from tvde_contract.harness import create_contract_app
from tvde_contract.limits import ContractLimits


def registered(client, clock, device_key):
    response = register_device(client, clock, device_key)
    assert response.status_code == 200
    return response.json()["installation_id"]


def payload(*events, cursor=None):
    return {"cursor": cursor, "events": list(events), "aggregate_query": aggregate_query()}


def test_empty_sync_returns_cursor_and_privacy_safe_aggregates(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    response = sync_request(client, clock, device_key, installation_id, payload())

    assert response.status_code == 200
    document = response.json()
    assert document["event_results"] == []
    assert document["own_changes"] == []
    assert document["next_cursor"]
    assert len(document["global_aggregates"]["heatmap"]) == 28
    assert len(document["global_aggregates"]["daily_calendar"]) == 30
    assert "raw_events" not in document["global_aggregates"]


def test_one_event_and_batch_are_accepted_and_returned_as_own_changes(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    events = [offer_event(), offer_event(), offer_event()]
    response = sync_request(client, clock, device_key, installation_id, payload(*events))

    assert response.status_code == 200
    assert [item["status"] for item in response.json()["event_results"]] == ["ACCEPTED"] * 3
    assert {item["event"]["event_id"] for item in response.json()["own_changes"]} == {
        event["event_id"] for event in events
    }


def test_same_request_returns_exact_stored_response(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    request_key = str(uuid4())
    document = payload(offer_event())
    first = sync_request(
        client, clock, device_key, installation_id, document, idempotency_key=request_key
    )
    second = sync_request(
        client, clock, device_key, installation_id, document, idempotency_key=request_key
    )
    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()


def test_timeout_after_commit_is_recovered_with_same_idempotency_key(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    request_key = str(uuid4())
    document = payload(offer_event())

    # The first response is deliberately ignored, simulating a transport timeout
    # after the backend committed the event and idempotency result.
    sync_request(client, clock, device_key, installation_id, document, idempotency_key=request_key)
    recovered = sync_request(
        client, clock, device_key, installation_id, document, idempotency_key=request_key
    )

    assert recovered.status_code == 200
    assert recovered.json()["event_results"][0]["status"] == "ACCEPTED"
    owner_id = next(iter(client.app.state.harness.events))
    assert len(client.app.state.harness.events[owner_id]) == 1


def test_idempotency_key_with_different_body_is_conflict(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    request_key = str(uuid4())
    first = sync_request(
        client, clock, device_key, installation_id, payload(offer_event()), idempotency_key=request_key
    )
    conflict = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        payload(offer_event()),
        idempotency_key=request_key,
    )
    assert first.status_code == 200
    assert (conflict.status_code, conflict.json()["error"]["code"]) == (409, "IDEMPOTENCY_CONFLICT")


def test_same_event_in_different_requests_is_duplicate_but_changed_event_conflicts(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    event_id = str(uuid4())
    original = offer_event(event_id)
    accepted = sync_request(client, clock, device_key, installation_id, payload(original))
    duplicate = sync_request(client, clock, device_key, installation_id, payload(original))
    changed = sync_request(
        client, clock, device_key, installation_id, payload(offer_event(event_id, trip_value_cents=999))
    )

    assert accepted.json()["event_results"][0]["status"] == "ACCEPTED"
    assert duplicate.json()["event_results"][0]["status"] == "DUPLICATE"
    assert changed.json()["event_results"][0] == {
        "event_id": event_id,
        "status": "REJECTED",
        "code": "EVENT_CONFLICT",
    }


def test_concurrent_requests_create_one_remote_event(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    event = offer_event()

    def send_once(_):
        return sync_request(client, clock, device_key, installation_id, payload(event))

    with ThreadPoolExecutor(max_workers=8) as executor:
        responses = list(executor.map(send_once, range(8)))

    statuses = [response.json()["event_results"][0]["status"] for response in responses]
    assert statuses.count("ACCEPTED") == 1
    assert statuses.count("DUPLICATE") == 7
    owner_id = next(iter(client.app.state.harness.events))
    assert len(client.app.state.harness.events[owner_id]) == 1


def test_cursor_returns_only_delta_and_rejects_invalid_or_expired_cursor(app, client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    first = sync_request(client, clock, device_key, installation_id, payload(offer_event()))
    cursor = first.json()["next_cursor"]
    delta = sync_request(client, clock, device_key, installation_id, payload(offer_event(), cursor=cursor))
    assert len(delta.json()["own_changes"]) == 1

    invalid = sync_request(client, clock, device_key, installation_id, payload(cursor="not-a-real-cursor"))
    assert (invalid.status_code, invalid.json()["error"]["code"]) == (409, "INVALID_CURSOR")

    app.state.harness.expire_cursor(delta.json()["next_cursor"])
    expired = sync_request(
        client, clock, device_key, installation_id, payload(cursor=delta.json()["next_cursor"])
    )
    assert (expired.status_code, expired.json()["error"]["code"]) == (410, "CURSOR_EXPIRED")


def test_security_mutations_and_unknown_installation_are_rejected(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    document = payload(offer_event())
    wrong_path = sync_request(
        client, clock, device_key, installation_id, document, signed_path="/v1/not-sync"
    )
    wrong_principal = sync_request(
        client, clock, device_key, installation_id, document, signed_principal=str(uuid4())
    )
    unknown = sync_request(client, clock, device_key, str(uuid4()), document)
    assert (wrong_path.status_code, wrong_path.json()["error"]["code"]) == (401, "INVALID_SIGNATURE")
    assert (wrong_principal.status_code, wrong_principal.json()["error"]["code"]) == (401, "INVALID_SIGNATURE")
    assert (unknown.status_code, unknown.json()["error"]["code"]) == (404, "INSTALLATION_NOT_FOUND")


def test_body_change_after_signature_and_other_installation_signature_are_rejected(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    document = payload(offer_event())
    signed_body = json_bytes(document)
    changed_document = payload(offer_event())
    body_changed = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        changed_document,
        signed_body=signed_body,
    )
    assert (body_changed.status_code, body_changed.json()["error"]["code"]) == (401, "INVALID_SIGNATURE")

    other_key = ec.generate_private_key(ec.SECP256R1())
    wrong_key = sync_request(client, clock, other_key, installation_id, document)
    assert (wrong_key.status_code, wrong_key.json()["error"]["code"]) == (401, "INVALID_SIGNATURE")


def test_reused_nonce_stale_timestamp_and_revoked_installation_are_rejected(app, client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    document = payload()
    nonce = secrets.token_urlsafe(16)
    first = sync_request(client, clock, device_key, installation_id, document, nonce=nonce)
    replay = sync_request(client, clock, device_key, installation_id, document, nonce=nonce)
    stale = sync_request(client, clock, device_key, installation_id, document, timestamp=clock() - 301)
    app.state.harness.revoke_installation(UUID(installation_id))
    revoked = sync_request(client, clock, device_key, installation_id, document)
    assert first.status_code == 200
    assert (replay.status_code, replay.json()["error"]["code"]) == (409, "REPLAY_DETECTED")
    assert (stale.status_code, stale.json()["error"]["code"]) == (401, "TIMESTAMP_OUT_OF_RANGE")
    assert (revoked.status_code, revoked.json()["error"]["code"]) == (403, "INSTALLATION_REVOKED")


def test_payload_validation_size_and_role_spoof(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    invalid = payload(offer_event())
    invalid["events"][0]["pickup_address"] = "x" * 501
    invalid_response = sync_request(client, clock, device_key, installation_id, invalid)
    assert (invalid_response.status_code, invalid_response.json()["error"]["code"]) == (422, "VALIDATION_FAILED")

    spoof = payload()
    spoof["role"] = "ADMIN"
    spoof_response = sync_request(client, clock, device_key, installation_id, spoof)
    assert (spoof_response.status_code, spoof_response.json()["error"]["code"]) == (422, "VALIDATION_FAILED")

    huge = payload()
    huge["ignored"] = "x" * (256 * 1024)
    huge_response = sync_request(client, clock, device_key, installation_id, huge)
    assert (huge_response.status_code, huge_response.json()["error"]["code"]) == (413, "PAYLOAD_TOO_LARGE")


def test_event_count_limit_is_enforced(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    too_many = payload(*(offer_event() for _ in range(101)))
    response = sync_request(client, clock, device_key, installation_id, too_many)
    assert (response.status_code, response.json()["error"]["code"]) == (422, "VALIDATION_FAILED")


def test_out_of_range_event_timestamps_are_rejected_per_event(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    too_old = offer_event()
    too_old["recorded_at_epoch_ms"] = 1_577_836_799_999
    too_new = offer_event()
    too_new["recorded_at_epoch_ms"] = (clock() + 24 * 60 * 60) * 1000 + 1

    response = sync_request(client, clock, device_key, installation_id, payload(too_old, too_new))

    assert response.status_code == 200
    assert [item["code"] for item in response.json()["event_results"]] == [
        "EVENT_TIMESTAMP_OUT_OF_RANGE",
        "EVENT_TIMESTAMP_OUT_OF_RANGE",
    ]
    assert response.json()["own_changes"] == []


def test_aggregate_query_cannot_exceed_366_inclusive_days(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    document = payload()
    document["aggregate_query"]["start_date"] = "2026-01-01"
    document["aggregate_query"]["end_date"] = "2027-01-02"

    response = sync_request(client, clock, device_key, installation_id, document)

    assert (response.status_code, response.json()["error"]["code"]) == (422, "VALIDATION_FAILED")


def test_malformed_json_and_missing_signed_header_are_invalid_requests(client, clock, device_key):
    installation_id = registered(client, clock, device_key)
    malformed_body = b'{"events":'
    headers = signed_headers(
        private_key=device_key,
        method="POST",
        path="/v1/sync",
        principal=installation_id,
        body=malformed_body,
        timestamp=clock(),
    )
    headers["X-TVDE-Installation-Id"] = installation_id
    malformed = client.post("/v1/sync", content=malformed_body, headers=headers)

    missing_headers = client.post("/v1/sync", json=payload())

    assert (malformed.status_code, malformed.json()["error"]["code"]) == (400, "INVALID_REQUEST")
    assert (missing_headers.status_code, missing_headers.json()["error"]["code"]) == (
        400,
        "INVALID_REQUEST",
    )


def test_rate_limit_returns_retry_after(clock, device_key):
    limits = ContractLimits(sync_requests_per_minute=2)
    app = create_contract_app(clock=clock, limits=limits)
    app.state.harness.add_license("test.activation.valid.client.00000001")
    from fastapi.testclient import TestClient

    client = TestClient(app)
    installation_id = registered(client, clock, device_key)
    assert sync_request(client, clock, device_key, installation_id, payload()).status_code == 200
    assert sync_request(client, clock, device_key, installation_id, payload()).status_code == 200
    limited = sync_request(client, clock, device_key, installation_id, payload())
    assert (limited.status_code, limited.json()["error"]["code"]) == (429, "RATE_LIMITED")
    assert limited.headers["Retry-After"] == "60"


def test_no_admin_endpoint_is_exposed(client):
    assert client.post("/v1/admin/installations").status_code == 404
