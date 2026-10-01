from __future__ import annotations

import json
import secrets
from dataclasses import dataclass
from datetime import date
from uuid import uuid4

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from tvde_contract.harness import create_contract_app
from tvde_contract.models import Role
from tvde_contract.security import (
    body_sha256,
    canonical_request,
    jwk_thumbprint,
    public_jwk_from_key,
    sign_request,
)


VALID_LICENSE = "test.activation.valid.client.00000001"
ADMIN_LICENSE = "test.activation.valid.admin.00000001"


@dataclass
class MutableClock:
    value: int = 1_800_000_000

    def __call__(self) -> int:
        return self.value

    def advance(self, seconds: int) -> None:
        self.value += seconds


def json_bytes(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")


def signed_headers(
    *,
    private_key: ec.EllipticCurvePrivateKey,
    method: str,
    path: str,
    principal: str,
    body: bytes,
    timestamp: int,
    nonce: str | None = None,
    idempotency_key: str | None = None,
) -> dict[str, str]:
    nonce = nonce or secrets.token_urlsafe(16)
    idempotency_key = idempotency_key or str(uuid4())
    digest = body_sha256(body)
    canonical = canonical_request(
        method=method,
        path=path,
        principal=principal,
        timestamp=timestamp,
        nonce=nonce,
        idempotency_key=idempotency_key,
        body_hash=digest,
    )
    return {
        "Content-Type": "application/json",
        "X-TVDE-Timestamp": str(timestamp),
        "X-TVDE-Nonce": nonce,
        "Idempotency-Key": idempotency_key,
        "X-TVDE-Body-SHA256": digest,
        "X-TVDE-Signature": sign_request(private_key, canonical),
    }


def registration_payload(private_key: ec.EllipticCurvePrivateKey, activation_key: str = VALID_LICENSE) -> dict:
    return {
        "activation_key": activation_key,
        "device_public_key": public_jwk_from_key(private_key.public_key()).model_dump(),
        "client": {
            "app_id": "com.daniel.tvdeinsight",
            "app_version": "0.5.69-unified",
            "api_version": 1,
        },
    }


def aggregate_query() -> dict:
    return {
        "platforms": ["UBER", "BOLT"],
        "metric": "VALUE_PER_KM",
        "value_mode": "FREE",
        "shift": "ALL",
        "card_color": "ALL",
        "category": None,
        "start_date": str(date(2027, 1, 1)),
        "end_date": str(date(2027, 1, 30)),
    }


def offer_event(event_id: str | None = None, *, trip_value_cents: int = 750) -> dict:
    return {
        "event_id": event_id or str(uuid4()),
        "recorded_at_epoch_ms": 1_800_000_000_000,
        "platform": "UBER",
        "category": "UberX",
        "decision": "ACEITAR",
        "trip_value_cents": trip_value_cents,
        "net_trip_value_cents": 700,
        "toll_amount_cents": 0,
        "value_per_km_cents": 55,
        "gross_value_per_km_cents": 65,
        "value_per_hour_cents": 2200,
        "pickup_distance_meters": 2300,
        "pickup_duration_seconds": 420,
        "destination_distance_meters": 7700,
        "destination_duration_seconds": 660,
        "pickup_address": "Rua de Teste 1, Porto",
        "pickup_municipality": "Porto",
        "destination_address": "Rua de Destino 2, Matosinhos",
        "current_location_address": "Avenida de Origem 3, Porto",
        "current_latitude_microdegrees": 41157000,
        "current_longitude_microdegrees": -8629000,
        "vehicle_cost_applied": True,
        "active_criteria": ["RECOLHA", "KM", "HORA"],
        "criterion_decisions": [
            {"criterion": "RECOLHA", "decision": "ACEITAR"},
            {"criterion": "KM", "decision": "ACEITAR"},
            {"criterion": "HORA", "decision": "ACEITAR"},
        ],
        "stop_rejection": False,
    }


@pytest.fixture
def clock() -> MutableClock:
    return MutableClock()


@pytest.fixture
def app(clock: MutableClock):
    instance = create_contract_app(clock=clock)
    instance.state.harness.add_license(VALID_LICENSE)
    instance.state.harness.add_license(ADMIN_LICENSE, role=Role.ADMIN)
    return instance


@pytest.fixture
def client(app) -> TestClient:
    return TestClient(app)


@pytest.fixture
def device_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def register_device(client: TestClient, clock: MutableClock, private_key, activation_key: str = VALID_LICENSE):
    payload = registration_payload(private_key, activation_key)
    body = json_bytes(payload)
    key_id = jwk_thumbprint(public_jwk_from_key(private_key.public_key()))
    headers = signed_headers(
        private_key=private_key,
        method="POST",
        path="/v1/installations/register",
        principal=key_id,
        body=body,
        timestamp=clock(),
    )
    headers["X-TVDE-Key-Id"] = key_id
    return client.post("/v1/installations/register", content=body, headers=headers)


def sync_request(client: TestClient, clock: MutableClock, private_key, installation_id: str, payload: dict, **overrides):
    body = json_bytes(payload)
    headers = signed_headers(
        private_key=private_key,
        method=overrides.pop("signed_method", "POST"),
        path=overrides.pop("signed_path", "/v1/sync"),
        principal=overrides.pop("signed_principal", installation_id),
        body=overrides.pop("signed_body", body),
        timestamp=overrides.pop("timestamp", clock()),
        nonce=overrides.pop("nonce", None),
        idempotency_key=overrides.pop("idempotency_key", None),
    )
    headers["X-TVDE-Installation-Id"] = installation_id
    headers.update(overrides)
    return client.post("/v1/sync", content=body, headers=headers)
