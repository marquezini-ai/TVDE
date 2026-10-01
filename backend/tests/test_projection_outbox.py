from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import MutableClock, aggregate_query, offer_event, register_device, sync_request
from tvde_contract.config import BackendSettings
from tvde_contract.persistent import create_persistent_app
from tvde_contract.projector import FakeGoogleSink, FakeProjectionWorker


MIGRATIONS = Path(__file__).resolve().parents[1] / "migrations"


def make_settings(tmp_path, *, retry=10, timeout=30):
    return BackendSettings(
        environment="test",
        database_path=tmp_path / "projection.sqlite3",
        migrations_path=MIGRATIONS,
        log_level="CRITICAL",
        projection_retry_seconds=retry,
        projection_processing_timeout_seconds=timeout,
    )


def seed_event(settings, clock, device_key):
    app = create_persistent_app(settings, clock=clock)
    app.state.harness.add_license("test.activation.valid.client.00000001")
    client = TestClient(app)
    registration = register_device(client, clock, device_key)
    installation_id = registration.json()["installation_id"]
    event = offer_event()
    response = sync_request(
        client,
        clock,
        device_key,
        installation_id,
        {"cursor": None, "events": [event], "aggregate_query": aggregate_query()},
    )
    assert response.status_code == 200
    return app, event


def test_projection_success_is_exactly_once_per_outbox_item(tmp_path, clock, device_key):
    settings = make_settings(tmp_path)
    app, event = seed_event(settings, clock, device_key)
    sink = FakeGoogleSink()
    worker = FakeProjectionWorker(app.state.harness.storage, sink, settings, clock)

    assert worker.run_once() == "DONE"
    assert worker.run_once() == "IDLE"
    assert len(sink.projected) == 1
    assert app.state.harness.storage.projection_row(event["event_id"])["state"] == "DONE"
    assert app.state.harness.storage.count("events") == 1


def test_projection_failure_retry_and_restart_never_remove_source_event(tmp_path, clock, device_key):
    settings = make_settings(tmp_path, retry=10)
    app, event = seed_event(settings, clock, device_key)
    sink = FakeGoogleSink(available=False)
    worker = FakeProjectionWorker(app.state.harness.storage, sink, settings, clock)

    assert worker.run_once() == "RETRY"
    failed = app.state.harness.storage.projection_row(event["event_id"])
    assert failed["state"] == "PENDING"
    assert failed["attempts"] == 1
    assert app.state.harness.storage.count("events") == 1
    assert worker.run_once() == "IDLE"

    clock.advance(10)
    restarted = create_persistent_app(settings, clock=clock)
    sink.available = True
    restarted_worker = FakeProjectionWorker(
        restarted.state.harness.storage, sink, settings, clock
    )
    assert restarted_worker.run_once() == "DONE"
    assert restarted.state.harness.storage.projection_row(event["event_id"])["attempts"] == 2
    assert restarted.state.harness.storage.count("events") == 1


def test_crash_after_processing_claim_is_recovered_without_outbox_duplication(
    tmp_path, clock, device_key
):
    settings = make_settings(tmp_path, timeout=0)
    app, event = seed_event(settings, clock, device_key)
    sink = FakeGoogleSink()
    worker = FakeProjectionWorker(app.state.harness.storage, sink, settings, clock)

    with pytest.raises(RuntimeError, match="simulated crash"):
        worker.run_once(crash_after_claim=True)
    assert app.state.harness.storage.projection_row(event["event_id"])["state"] == "PROCESSING"

    restarted = create_persistent_app(settings, clock=clock)
    recovered = FakeProjectionWorker(restarted.state.harness.storage, sink, settings, clock)
    assert recovered.run_once() == "DONE"
    assert recovered.run_once() == "IDLE"
    assert len(sink.projected) == 1
    assert restarted.state.harness.storage.count("projection_outbox") == 1
    assert restarted.state.harness.storage.count("events") == 1


def test_crash_after_external_projection_retries_idempotently(tmp_path, clock, device_key):
    settings = make_settings(tmp_path, timeout=0)
    app, event = seed_event(settings, clock, device_key)
    sink = FakeGoogleSink()
    worker = FakeProjectionWorker(app.state.harness.storage, sink, settings, clock)

    with pytest.raises(RuntimeError, match="after external projection"):
        worker.run_once(crash_after_project=True)
    assert len(sink.projected) == 1
    assert app.state.harness.storage.projection_row(event["event_id"])["state"] == "PROCESSING"

    restarted = create_persistent_app(settings, clock=clock)
    recovered = FakeProjectionWorker(restarted.state.harness.storage, sink, settings, clock)
    assert recovered.run_once() == "DONE"
    assert len(sink.projected) == 1
    assert restarted.state.harness.storage.projection_row(event["event_id"])["state"] == "DONE"
