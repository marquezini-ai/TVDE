from __future__ import annotations

import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from threading import Barrier
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from conftest import (
    ADMIN_LICENSE,
    VALID_LICENSE,
    MutableClock,
    aggregate_query,
    offer_event,
    register_device,
    sync_request,
)
from tvde_contract.config import BackendSettings
from tvde_contract.limits import ContractLimits
from tvde_contract.migrations import MigrationRunner, SchemaIncompatibleError
from tvde_contract.models import Role
from tvde_contract.persistent import create_persistent_app


MIGRATIONS = Path(__file__).resolve().parents[1] / "migrations"


class FailureSwitch:
    point: str | None = None

    def __call__(self, point: str) -> None:
        if point == self.point:
            raise RuntimeError(f"simulated failure at {point}")


def settings(tmp_path, *, limits: ContractLimits | None = None, retry_seconds: int = 0):
    return BackendSettings(
        environment="test",
        database_path=tmp_path / "backend.sqlite3",
        migrations_path=MIGRATIONS,
        log_level="CRITICAL",
        projection_retry_seconds=retry_seconds,
        projection_processing_timeout_seconds=0,
        limits=limits or ContractLimits(),
    )


def start(settings, clock, *, failure=None, seed=True):
    app = create_persistent_app(settings, clock=clock, failure_injector=failure)
    if seed:
        app.state.harness.add_license(VALID_LICENSE)
        app.state.harness.add_license(ADMIN_LICENSE, role=Role.ADMIN)
    return app, TestClient(app)


def register(client, clock, key, activation_key=VALID_LICENSE):
    response = register_device(client, clock, key, activation_key)
    assert response.status_code == 200
    return response.json()["installation_id"]


def request_payload(*events, cursor=None, query=None):
    return {
        "cursor": cursor,
        "events": list(events),
        "aggregate_query": query or aggregate_query(),
    }


def test_migrations_create_reopen_and_reject_unknown_schema(tmp_path):
    database = tmp_path / "migration.sqlite3"
    runner = MigrationRunner(MIGRATIONS)
    with sqlite3.connect(database, isolation_level=None) as connection:
        runner.apply(connection)
        runner.apply(connection)
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 1
        tables = {
            row[0]
            for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        assert {
            "licenses",
            "installations",
            "nonces",
            "idempotency_records",
            "events",
            "changes",
            "cursors",
            "projection_outbox",
        } <= tables
        connection.execute(
            "INSERT INTO schema_migrations(version, name, checksum) VALUES (99, 'future.sql', 'x')"
        )
    with sqlite3.connect(database, isolation_level=None) as connection:
        with pytest.raises(SchemaIncompatibleError, match="unknown migration"):
            runner.apply(connection)


def test_registration_request_idempotency_and_delta_survive_restart(tmp_path, clock, device_key):
    config = settings(tmp_path)
    app, client = start(config, clock)
    installation_id = register(client, clock, device_key)
    event = offer_event()
    request_key = str(uuid4())
    first = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        request_payload(event),
        idempotency_key=request_key,
    )
    assert first.status_code == 200
    cursor = first.json()["next_cursor"]

    restarted_app, restarted_client = start(config, clock, seed=False)
    repeated_registration = register_device(restarted_client, clock, device_key)
    repeated_request = sync_request(
        restarted_client,
        clock,
        device_key,
        installation_id,
        request_payload(event),
        idempotency_key=request_key,
    )
    delta = sync_request(
        restarted_client,
        clock,
        device_key,
        installation_id,
        request_payload(offer_event(), cursor=cursor),
    )

    assert repeated_registration.status_code == 200
    assert repeated_registration.json()["installation_id"] == installation_id
    assert repeated_request.json() == first.json()
    assert len(delta.json()["own_changes"]) == 1
    assert restarted_app.state.harness.storage.count("events") == 2
    assert app.state.harness.storage.count("events") == 2


def test_persistent_test_licenses_cover_invalid_expired_revoked_and_bound(
    tmp_path, clock, device_key
):
    config = settings(tmp_path)
    app, client = start(config, clock)
    expired_key = "test.activation.expired.client.00000004"
    revoked_key = "test.activation.revoked.client.00000005"
    app.state.harness.add_license(expired_key, expires_at=clock() - 1)
    app.state.harness.add_license(revoked_key, revoked=True)

    invalid = register_device(
        client, clock, ec.generate_private_key(ec.SECP256R1()), "test.activation.invalid.client.00000006"
    )
    expired = register_device(client, clock, ec.generate_private_key(ec.SECP256R1()), expired_key)
    revoked = register_device(client, clock, ec.generate_private_key(ec.SECP256R1()), revoked_key)
    installation_id = register(client, clock, device_key)
    bound = register_device(client, clock, ec.generate_private_key(ec.SECP256R1()), VALID_LICENSE)

    assert (invalid.status_code, invalid.json()["error"]["code"]) == (403, "LICENSE_INVALID")
    assert (expired.status_code, expired.json()["error"]["code"]) == (403, "LICENSE_EXPIRED")
    assert (revoked.status_code, revoked.json()["error"]["code"]) == (403, "LICENSE_REVOKED")
    assert (bound.status_code, bound.json()["error"]["code"]) == (409, "LICENSE_ALREADY_BOUND")

    restarted_app, _ = start(config, clock, seed=False)
    assert str(restarted_app.state.harness.get_installation(UUID(installation_id)).installation_id) == installation_id


@pytest.mark.parametrize(
    "failure_point",
    ["sync_before_transaction", "sync_during_processing", "sync_after_events", "sync_before_commit"],
)
def test_failure_before_commit_rolls_back_event_change_idempotency_and_outbox(
    tmp_path, clock, device_key, failure_point
):
    switch = FailureSwitch()
    config = settings(tmp_path)
    app, client = start(config, clock, failure=switch)
    installation_id = register(client, clock, device_key)
    baseline_idempotency = app.state.harness.storage.count("idempotency_records")
    switch.point = failure_point

    with pytest.raises(RuntimeError, match="simulated failure"):
        sync_request(client, clock, device_key, installation_id, request_payload(offer_event()))

    storage = app.state.harness.storage
    assert storage.count("events") == 0
    assert storage.count("changes") == 0
    assert storage.count("projection_outbox") == 0
    assert storage.count("idempotency_records") == baseline_idempotency


@pytest.mark.parametrize("failure_point", ["sync_after_commit", "sync_before_response"])
def test_failure_after_commit_is_recovered_exactly_without_duplication(
    tmp_path, clock, device_key, failure_point
):
    switch = FailureSwitch()
    config = settings(tmp_path)
    app, client = start(config, clock, failure=switch)
    installation_id = register(client, clock, device_key)
    event = offer_event()
    body = request_payload(event)
    request_key = str(uuid4())
    switch.point = failure_point

    with pytest.raises(RuntimeError, match="simulated failure"):
        sync_request(
            client,
            clock,
            device_key,
            installation_id,
            body,
            idempotency_key=request_key,
        )

    switch.point = None
    restarted_app, restarted_client = start(config, clock, seed=False)
    recovered = sync_request(
        restarted_client,
        clock,
        device_key,
        installation_id,
        body,
        idempotency_key=request_key,
    )
    assert recovered.status_code == 200
    assert recovered.json()["event_results"][0]["status"] == "ACCEPTED"
    assert restarted_app.state.harness.storage.count("events") == 1
    assert restarted_app.state.harness.storage.count("changes") == 1
    assert restarted_app.state.harness.storage.count("projection_outbox") == 1


def test_concurrent_same_event_same_request_and_nonce_are_atomic(tmp_path, clock, device_key):
    config = settings(tmp_path)
    app, client = start(config, clock)
    installation_id = register(client, clock, device_key)
    event = offer_event()

    def same_event(_):
        return sync_request(client, clock, device_key, installation_id, request_payload(event))

    with ThreadPoolExecutor(max_workers=8) as executor:
        event_responses = list(executor.map(same_event, range(8)))
    statuses = [item.json()["event_results"][0]["status"] for item in event_responses]
    assert statuses.count("ACCEPTED") == 1
    assert statuses.count("DUPLICATE") == 7

    other_event = offer_event()
    same_idempotency = str(uuid4())

    def same_request(_):
        return sync_request(
            client,
            clock,
            device_key,
            installation_id,
            request_payload(other_event),
            idempotency_key=same_idempotency,
        )

    with ThreadPoolExecutor(max_workers=6) as executor:
        request_responses = list(executor.map(same_request, range(6)))
    documents = [response.json() for response in request_responses]
    assert all(document == documents[0] for document in documents)

    replay_event = offer_event()
    nonce = "A" * 22
    replay_key = str(uuid4())
    barrier = Barrier(2)

    def same_nonce(_):
        barrier.wait()
        return sync_request(
            client,
            clock,
            device_key,
            installation_id,
            request_payload(replay_event),
            nonce=nonce,
            idempotency_key=replay_key,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        nonce_responses = list(executor.map(same_nonce, range(2)))
    assert sorted(response.status_code for response in nonce_responses) == [200, 409]
    assert app.state.harness.storage.count("events") == 3


def test_nonce_replay_survives_restart_and_expires(tmp_path, clock, device_key):
    config = settings(tmp_path)
    _, client = start(config, clock)
    installation_id = register(client, clock, device_key)
    nonce = "B" * 22
    first = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        request_payload(),
        nonce=nonce,
    )
    assert first.status_code == 200

    _, restarted_client = start(config, clock, seed=False)
    replay = sync_request(
        restarted_client,
        clock,
        device_key,
        installation_id,
        request_payload(),
        nonce=nonce,
    )
    assert (replay.status_code, replay.json()["error"]["code"]) == (409, "REPLAY_DETECTED")

    clock.advance(601)
    after_expiry = sync_request(
        restarted_client,
        clock,
        device_key,
        installation_id,
        request_payload(),
        nonce=nonce,
    )
    assert after_expiry.status_code == 200


def test_different_events_and_delta_reads_are_safe_under_concurrency(tmp_path, clock, device_key):
    config = settings(tmp_path)
    app, client = start(config, clock)
    installation_id = register(client, clock, device_key)
    events = [offer_event() for _ in range(12)]
    barrier = Barrier(len(events))

    def send(event):
        barrier.wait()
        return sync_request(client, clock, device_key, installation_id, request_payload(event))

    with ThreadPoolExecutor(max_workers=len(events)) as executor:
        responses = list(executor.map(send, events))
    assert all(response.status_code == 200 for response in responses)
    assert all(response.json()["event_results"][0]["status"] == "ACCEPTED" for response in responses)
    assert app.state.harness.storage.count("events") == len(events)
    assert app.state.harness.storage.count("changes") == len(events)

    snapshot = sync_request(client, clock, device_key, installation_id, request_payload())
    assert len(snapshot.json()["own_changes"]) == len(events)
    assert len({item["sequence"] for item in snapshot.json()["own_changes"]}) == len(events)


def test_cursor_pagination_owner_binding_and_new_changes_are_consistent(tmp_path, clock, device_key):
    config = settings(tmp_path, limits=ContractLimits(own_changes_page_size=2))
    app, client = start(config, clock)
    installation_id = register(client, clock, device_key)
    first = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        request_payload(offer_event(), offer_event(), offer_event()),
    )
    assert first.json()["has_more"] is True
    assert len(first.json()["own_changes"]) == 2
    cursor = first.json()["next_cursor"]

    continued = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        request_payload(offer_event(), cursor=cursor),
    )
    assert len(continued.json()["own_changes"]) == 2
    assert continued.json()["has_more"] is False

    other_key = ec.generate_private_key(ec.SECP256R1())
    other_license = "test.activation.second.client.00000002"
    app.state.harness.add_license(other_license)
    other_installation = register(client, clock, other_key, other_license)
    foreign = sync_request(client, clock, other_key, other_installation, request_payload(cursor=cursor))
    assert (foreign.status_code, foreign.json()["error"]["code"]) == (409, "INVALID_CURSOR")

    app.state.harness.expire_cursor(continued.json()["next_cursor"])
    expired = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        request_payload(cursor=continued.json()["next_cursor"]),
    )
    assert (expired.status_code, expired.json()["error"]["code"]) == (410, "CURSOR_EXPIRED")

    restarted_app, restarted_client = start(config, clock, seed=False)
    after_restart = sync_request(
        restarted_client,
        clock,
        device_key,
        installation_id,
        request_payload(cursor=cursor),
    )
    assert after_restart.status_code == 200
    assert len(after_restart.json()["own_changes"]) == 2
    assert restarted_app.state.harness.storage.count("events") == 4


def epoch_ms(year, month, day, hour):
    return int(datetime(year, month, day, hour, tzinfo=ZoneInfo("Europe/Lisbon")).timestamp() * 1000)


def test_aggregates_use_persisted_global_events_without_exposing_foreign_rows(
    tmp_path, clock, device_key
):
    clock.value = epoch_ms(2027, 1, 17, 0) // 1000
    config = settings(tmp_path)
    app, client = start(config, clock)
    installation_id = register(client, clock, device_key)
    first = offer_event(trip_value_cents=800)
    first.update(
        recorded_at_epoch_ms=epoch_ms(2027, 1, 15, 7),
        pickup_municipality="Porto",
        value_per_km_cents=50,
    )
    second = offer_event(trip_value_cents=1000)
    second.update(
        recorded_at_epoch_ms=epoch_ms(2027, 1, 15, 13),
        pickup_municipality="Porto",
        value_per_km_cents=70,
    )
    sync_request(client, clock, device_key, installation_id, request_payload(first, second))

    other_key = ec.generate_private_key(ec.SECP256R1())
    other_license = "test.activation.global.client.00000003"
    app.state.harness.add_license(other_license)
    other_installation = register(client, clock, other_key, other_license)
    foreign_event = offer_event(trip_value_cents=1200)
    foreign_event.update(
        recorded_at_epoch_ms=epoch_ms(2027, 1, 16, 20),
        pickup_municipality="Matosinhos",
        value_per_km_cents=90,
    )
    sync_request(client, clock, other_key, other_installation, request_payload(foreign_event))

    own_cursor = sync_request(client, clock, device_key, installation_id, request_payload()).json()
    aggregates = own_cursor["global_aggregates"]
    assert {item["municipality"]: item for item in aggregates["pickup_municipalities"]} == {
        "Matosinhos": {"municipality": "Matosinhos", "median_cents": 90, "event_count": 1},
        "Porto": {"municipality": "Porto", "median_cents": 60, "event_count": 2},
    }
    assert len(aggregates["heatmap"]) == 28
    assert sum(item["event_count"] for item in aggregates["heatmap"]) == 3
    assert len(aggregates["daily_calendar"]) == 30
    assert aggregates["recorded_dates"] == ["2027-01-15", "2027-01-16"]
    own_event_ids = {item["event"]["event_id"] for item in own_cursor["own_changes"]}
    assert foreign_event["event_id"] not in own_event_ids
    assert app.state.harness.storage.count("events") == 3
