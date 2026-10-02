from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from conftest import MutableClock, aggregate_query, offer_event, register_device, sync_request
from tvde_contract.cloud_app import create_cloud_app
from tvde_contract.cloud_config import CloudBackendSettings
from tvde_contract.firestore_storage import FirestoreStorage


pytestmark = pytest.mark.skipif(
    os.getenv("TVDE_RUN_CLOUD_TESTS") != "1",
    reason="set TVDE_RUN_CLOUD_TESTS=1 for isolated Firestore integration tests",
)


def settings() -> CloudBackendSettings:
    return CloudBackendSettings(
        environment="cloud-test",
        project_id=os.environ["GOOGLE_CLOUD_PROJECT"],
        log_level="CRITICAL",
        projection_retry_seconds=1,
        projection_processing_timeout_seconds=1,
    )


def payload(*events, cursor=None):
    return {"cursor": cursor, "events": list(events), "aggregate_query": aggregate_query()}


def test_cloud_restart_idempotency_cursor_replay_and_concurrency():
    config = settings()
    clock = MutableClock()
    storage = FirestoreStorage(config.project_id, config.database_id)
    storage.assert_schema()
    activation_key = f"test.cloud.{uuid4()}.synthetic"
    storage.put_license(activation_key, "CLIENT", None, False, clock())
    key = ec.generate_private_key(ec.SECP256R1())

    first_app = create_cloud_app(config, clock=clock, storage=storage)
    first_client = TestClient(first_app)
    assert first_client.get("/_health").json() == {"status": "ok"}
    registration = register_device(first_client, clock, key, activation_key)
    assert registration.status_code == 200
    installation_id = registration.json()["installation_id"]

    event = offer_event()
    idempotency_key = str(uuid4())
    first = sync_request(
        first_client,
        clock,
        key,
        installation_id,
        payload(event),
        idempotency_key=idempotency_key,
    )
    assert first.status_code == 200
    assert first.json()["event_results"][0]["status"] == "ACCEPTED"
    cursor = first.json()["next_cursor"]

    restarted = TestClient(create_cloud_app(config, clock=clock))
    repeated = sync_request(
        restarted,
        clock,
        key,
        installation_id,
        payload(event),
        idempotency_key=idempotency_key,
    )
    assert repeated.status_code == 200
    assert repeated.json() == first.json()
    delta = sync_request(restarted, clock, key, installation_id, payload(cursor=cursor))
    assert delta.status_code == 200

    replay_nonce = "cloudReplayNonce0000000001"
    replay_first = sync_request(
        restarted, clock, key, installation_id, payload(), nonce=replay_nonce
    )
    replay_second = sync_request(
        restarted, clock, key, installation_id, payload(), nonce=replay_nonce
    )
    assert replay_first.status_code == 200
    assert replay_second.status_code == 409
    assert replay_second.json()["error"]["code"] == "REPLAY_DETECTED"

    concurrent_event = offer_event()
    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(
            executor.map(
                lambda _: sync_request(
                    restarted,
                    clock,
                    key,
                    installation_id,
                    payload(concurrent_event),
                ),
                range(2),
            )
        )
    assert sorted(response.status_code for response in responses) == [200, 200]
    statuses = sorted(response.json()["event_results"][0]["status"] for response in responses)
    assert statuses == ["ACCEPTED", "DUPLICATE"]

    other_activation_key = f"test.cloud.other.{uuid4()}.synthetic"
    storage.put_license(other_activation_key, "CLIENT", None, False, clock())
    other_key = ec.generate_private_key(ec.SECP256R1())
    other_registration = register_device(restarted, clock, other_key, other_activation_key)
    assert other_registration.status_code == 200
    other_installation_id = other_registration.json()["installation_id"]
    other_view = sync_request(
        restarted,
        clock,
        other_key,
        other_installation_id,
        payload(),
    )
    assert other_view.status_code == 200
    assert other_view.json()["own_changes"] == []
    assert "raw_events" not in other_view.json()["global_aggregates"]
    assert "pickup_address" not in other_view.json()["global_aggregates"]


def test_cloud_license_states_and_binding_are_enforced():
    config = settings()
    clock = MutableClock()
    storage = FirestoreStorage(config.project_id, config.database_id)
    storage.assert_schema()
    client = TestClient(create_cloud_app(config, clock=clock, storage=storage))

    invalid_key = ec.generate_private_key(ec.SECP256R1())
    invalid = register_device(
        client,
        clock,
        invalid_key,
        f"test.cloud.invalid.{uuid4()}.synthetic",
    )
    assert (invalid.status_code, invalid.json()["error"]["code"]) == (403, "LICENSE_INVALID")

    expired_license = f"test.cloud.expired.{uuid4()}.synthetic"
    storage.put_license(expired_license, "CLIENT", clock() - 1, False, clock())
    expired = register_device(
        client, clock, ec.generate_private_key(ec.SECP256R1()), expired_license
    )
    assert (expired.status_code, expired.json()["error"]["code"]) == (403, "LICENSE_EXPIRED")

    revoked_license = f"test.cloud.revoked.{uuid4()}.synthetic"
    storage.put_license(revoked_license, "CLIENT", None, True, clock())
    revoked = register_device(
        client, clock, ec.generate_private_key(ec.SECP256R1()), revoked_license
    )
    assert (revoked.status_code, revoked.json()["error"]["code"]) == (403, "LICENSE_REVOKED")

    bound_license = f"test.cloud.bound.{uuid4()}.synthetic"
    storage.put_license(bound_license, "CLIENT", None, False, clock())
    first = register_device(
        client, clock, ec.generate_private_key(ec.SECP256R1()), bound_license
    )
    second = register_device(
        client, clock, ec.generate_private_key(ec.SECP256R1()), bound_license
    )
    assert first.status_code == 200
    assert (second.status_code, second.json()["error"]["code"]) == (
        409,
        "LICENSE_ALREADY_BOUND",
    )


def test_cloud_rate_limit_is_atomic_and_shared():
    config = settings()
    storage = FirestoreStorage(config.project_id, config.database_id)
    principal = f"test-rate-{uuid4()}"
    now = 1_800_000_000

    with ThreadPoolExecutor(max_workers=4) as executor:
        accepted = list(
            executor.map(
                lambda _: storage.consume_rate_limit(
                    "sync",
                    principal,
                    maximum=2,
                    window_seconds=60,
                    now=now,
                ),
                range(4),
            )
        )

    assert accepted.count(True) == 2
    assert accepted.count(False) == 2
    assert storage.consume_rate_limit(
        "sync",
        principal,
        maximum=2,
        window_seconds=60,
        now=now + 60,
    )
