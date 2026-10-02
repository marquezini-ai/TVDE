from __future__ import annotations

import argparse
import json
import secrets
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from uuid import uuid4

import httpx
from cryptography.hazmat.primitives.asymmetric import ec

from tvde_contract.firestore_storage import FirestoreStorage
from tvde_contract.security import (
    body_sha256,
    canonical_request,
    jwk_thumbprint,
    public_jwk_from_key,
    sign_request,
)


def encoded(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")


def headers(key, path: str, principal: str, body: bytes, *, nonce=None, idem=None) -> dict[str, str]:
    timestamp = int(time.time())
    nonce = nonce or secrets.token_urlsafe(16)
    idem = idem or str(uuid4())
    digest = body_sha256(body)
    canonical = canonical_request(
        method="POST",
        path=path,
        principal=principal,
        timestamp=timestamp,
        nonce=nonce,
        idempotency_key=idem,
        body_hash=digest,
    )
    return {
        "Content-Type": "application/json",
        "X-TVDE-Timestamp": str(timestamp),
        "X-TVDE-Nonce": nonce,
        "Idempotency-Key": idem,
        "X-TVDE-Body-SHA256": digest,
        "X-TVDE-Signature": sign_request(key, canonical),
    }


def aggregate_query() -> dict:
    today = datetime.now(timezone.utc).date().isoformat()
    return {
        "platforms": ["UBER", "BOLT"],
        "metric": "VALUE_PER_KM",
        "value_mode": "FREE",
        "shift": "ALL",
        "card_color": "ALL",
        "category": None,
        "start_date": today,
        "end_date": today,
    }


def event(event_id: str | None = None) -> dict:
    return {
        "event_id": event_id or str(uuid4()),
        "recorded_at_epoch_ms": int(time.time() * 1000),
        "platform": "UBER",
        "category": "CloudTest",
        "decision": "ACEITAR",
        "trip_value_cents": 750,
        "net_trip_value_cents": 700,
        "toll_amount_cents": 0,
        "value_per_km_cents": 55,
        "gross_value_per_km_cents": 65,
        "value_per_hour_cents": 2200,
        "pickup_distance_meters": 2300,
        "pickup_duration_seconds": 420,
        "destination_distance_meters": 7700,
        "destination_duration_seconds": 660,
        "pickup_address": "Synthetic Test Address",
        "pickup_municipality": "Porto",
        "destination_address": "Synthetic Test Destination",
        "current_location_address": None,
        "current_latitude_microdegrees": None,
        "current_longitude_microdegrees": None,
        "vehicle_cost_applied": True,
        "active_criteria": ["RECOLHA", "KM", "HORA"],
        "criterion_decisions": [
            {"criterion": "RECOLHA", "decision": "ACEITAR"},
            {"criterion": "KM", "decision": "ACEITAR"},
            {"criterion": "HORA", "decision": "ACEITAR"},
        ],
        "stop_rejection": False,
    }


def expect(response: httpx.Response, status: int, code: str | None = None) -> dict:
    data = response.json()
    assert response.status_code == status, (response.status_code, data)
    if code is not None:
        assert data["error"]["code"] == code, data
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--test-rate-limit", action="store_true")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    storage = FirestoreStorage(args.project)
    activation_key = f"cloud.test.active.{uuid4()}.synthetic"
    storage.put_license(activation_key, "CLIENT", None, False, int(time.time()))
    key = ec.generate_private_key(ec.SECP256R1())
    jwk = public_jwk_from_key(key.public_key())
    key_id = jwk_thumbprint(jwk)
    registration = {
        "activation_key": activation_key,
        "device_public_key": jwk.model_dump(),
        "client": {"app_id": "com.daniel.tvdeinsight.cloudtest", "app_version": "cloud-test", "api_version": 1},
    }
    registration_body = encoded(registration)
    registration_idem = str(uuid4())

    with httpx.Client(base_url=base_url, timeout=30) as client:
        registration_headers = headers(
            key,
            "/v1/installations/register",
            key_id,
            registration_body,
            idem=registration_idem,
        )
        registration_headers["X-TVDE-Key-Id"] = key_id
        first_registration = expect(
            client.post("/v1/installations/register", content=registration_body, headers=registration_headers),
            200,
        )
        repeat_headers = headers(
            key,
            "/v1/installations/register",
            key_id,
            registration_body,
            idem=registration_idem,
        )
        repeat_headers["X-TVDE-Key-Id"] = key_id
        repeated_registration = expect(
            client.post("/v1/installations/register", content=registration_body, headers=repeat_headers),
            200,
        )
        assert repeated_registration == first_registration
        installation_id = first_registration["installation_id"]

        first_event = event()
        sync_payload = {"cursor": None, "events": [first_event], "aggregate_query": aggregate_query()}
        sync_body = encoded(sync_payload)
        sync_idem = str(uuid4())
        sync_headers = headers(key, "/v1/sync", installation_id, sync_body, idem=sync_idem)
        sync_headers["X-TVDE-Installation-Id"] = installation_id
        first_sync = expect(client.post("/v1/sync", content=sync_body, headers=sync_headers), 200)
        assert first_sync["event_results"][0]["status"] == "ACCEPTED"
        assert first_sync["own_changes"][0]["event"]["event_id"] == first_event["event_id"]
        assert "pickup_address" not in first_sync["global_aggregates"]

        repeat_sync_headers = headers(key, "/v1/sync", installation_id, sync_body, idem=sync_idem)
        repeat_sync_headers["X-TVDE-Installation-Id"] = installation_id
        assert expect(client.post("/v1/sync", content=sync_body, headers=repeat_sync_headers), 200) == first_sync

        delta_payload = {"cursor": first_sync["next_cursor"], "events": [], "aggregate_query": aggregate_query()}
        delta_body = encoded(delta_payload)
        delta_headers = headers(key, "/v1/sync", installation_id, delta_body)
        delta_headers["X-TVDE-Installation-Id"] = installation_id
        delta = expect(client.post("/v1/sync", content=delta_body, headers=delta_headers), 200)
        assert delta["own_changes"] == []

        invalid_signature_headers = headers(key, "/v1/sync", installation_id, delta_body)
        invalid_signature_headers["X-TVDE-Installation-Id"] = installation_id
        signature = invalid_signature_headers["X-TVDE-Signature"]
        invalid_signature_headers["X-TVDE-Signature"] = (
            ("A" if signature[0] != "A" else "B") + signature[1:]
        )
        expect(
            client.post("/v1/sync", content=delta_body, headers=invalid_signature_headers),
            401,
            "INVALID_SIGNATURE",
        )

        replay_payload = {"cursor": None, "events": [], "aggregate_query": aggregate_query()}
        replay_body = encoded(replay_payload)
        replay_nonce = secrets.token_urlsafe(16)
        replay_headers = headers(key, "/v1/sync", installation_id, replay_body, nonce=replay_nonce)
        replay_headers["X-TVDE-Installation-Id"] = installation_id
        expect(client.post("/v1/sync", content=replay_body, headers=replay_headers), 200)
        expect(client.post("/v1/sync", content=replay_body, headers=replay_headers), 409, "REPLAY_DETECTED")

    concurrent_event = event()
    concurrent_payload = {"cursor": None, "events": [concurrent_event], "aggregate_query": aggregate_query()}
    concurrent_body = encoded(concurrent_payload)

    def send_concurrent(_: int) -> httpx.Response:
        request_headers = headers(key, "/v1/sync", installation_id, concurrent_body)
        request_headers["X-TVDE-Installation-Id"] = installation_id
        return httpx.post(f"{base_url}/v1/sync", content=concurrent_body, headers=request_headers, timeout=30)

    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(send_concurrent, range(2)))
    assert [response.status_code for response in responses] == [200, 200]
    statuses = sorted(response.json()["event_results"][0]["status"] for response in responses)
    assert statuses == ["ACCEPTED", "DUPLICATE"]

    same_idempotency_event = event()
    same_idempotency_payload = {
        "cursor": None,
        "events": [same_idempotency_event],
        "aggregate_query": aggregate_query(),
    }
    same_idempotency_body = encoded(same_idempotency_payload)
    same_idempotency_key = str(uuid4())

    def send_same_idempotency(_: int) -> httpx.Response:
        request_headers = headers(
            key,
            "/v1/sync",
            installation_id,
            same_idempotency_body,
            idem=same_idempotency_key,
        )
        request_headers["X-TVDE-Installation-Id"] = installation_id
        return httpx.post(
            f"{base_url}/v1/sync",
            content=same_idempotency_body,
            headers=request_headers,
            timeout=30,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        same_idempotency_responses = list(executor.map(send_same_idempotency, range(2)))
    assert [response.status_code for response in same_idempotency_responses] == [200, 200]
    assert same_idempotency_responses[0].json() == same_idempotency_responses[1].json()

    rate_limit_result = "SKIPPED"
    if args.test_rate_limit:
        rate_activation_key = f"cloud.test.rate.{uuid4()}.synthetic"
        storage.put_license(rate_activation_key, "CLIENT", None, False, int(time.time()))
        rate_key = ec.generate_private_key(ec.SECP256R1())
        rate_jwk = public_jwk_from_key(rate_key.public_key())
        rate_key_id = jwk_thumbprint(rate_jwk)
        rate_registration = {
            "activation_key": rate_activation_key,
            "device_public_key": rate_jwk.model_dump(),
            "client": {
                "app_id": "com.daniel.tvdeinsight.cloudtest",
                "app_version": "cloud-test",
                "api_version": 1,
            },
        }
        rate_registration_body = encoded(rate_registration)
        rate_registration_headers = headers(
            rate_key,
            "/v1/installations/register",
            rate_key_id,
            rate_registration_body,
        )
        rate_registration_headers["X-TVDE-Key-Id"] = rate_key_id
        with httpx.Client(base_url=base_url, timeout=30) as client:
            rate_registration_response = expect(
                client.post(
                    "/v1/installations/register",
                    content=rate_registration_body,
                    headers=rate_registration_headers,
                ),
                200,
            )
            rate_installation_id = rate_registration_response["installation_id"]
            empty_payload = {"cursor": None, "events": [], "aggregate_query": aggregate_query()}
            empty_body = encoded(empty_payload)
            statuses = []
            for _ in range(31):
                request_headers = headers(
                    rate_key,
                    "/v1/sync",
                    rate_installation_id,
                    empty_body,
                )
                request_headers["X-TVDE-Installation-Id"] = rate_installation_id
                statuses.append(
                    client.post("/v1/sync", content=empty_body, headers=request_headers).status_code
                )
        assert statuses[:30] == [200] * 30, statuses
        assert statuses[30] == 429, statuses
        rate_limit_result = "PASS"

    print(json.dumps({
        "registration": "PASS",
        "signature_and_authorization": "PASS",
        "idempotency": "PASS",
        "concurrent_same_idempotency": "PASS",
        "replay": "PASS",
        "event_and_aggregates": "PASS",
        "cursor_and_delta": "PASS",
        "concurrency": "PASS",
        "rate_limit": rate_limit_result,
    }, sort_keys=True))


if __name__ == "__main__":
    main()
