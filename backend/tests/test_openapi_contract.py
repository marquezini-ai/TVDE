from __future__ import annotations

import json
from pathlib import Path

from openapi_spec_validator import validate

from tvde_contract.config import BackendSettings
from tvde_contract.harness import create_contract_app
from tvde_contract.persistent import create_persistent_app


ROOT = Path(__file__).resolve().parents[1]


def test_committed_openapi_is_valid_and_contains_only_approved_endpoints():
    specification = json.loads((ROOT / "openapi.json").read_text(encoding="utf-8"))
    validate(specification)
    assert set(specification["paths"]) == {"/v1/installations/register", "/v1/sync"}
    assert specification["x-tvde-signature-protocol"]["algorithm"] == "ECDSA P-256 with SHA-256"


def test_committed_openapi_matches_the_contract_harness():
    committed = json.loads((ROOT / "openapi.json").read_text(encoding="utf-8"))
    generated = create_contract_app(clock=lambda: 0).openapi()
    assert committed["paths"] == generated["paths"]
    assert committed["components"] == generated["components"]


def test_persistent_backend_preserves_the_committed_openapi(tmp_path: Path):
    committed = json.loads((ROOT / "openapi.json").read_text(encoding="utf-8"))
    settings = BackendSettings(
        environment="test",
        database_path=tmp_path / "openapi.sqlite3",
        migrations_path=ROOT / "migrations",
    )
    generated = create_persistent_app(settings=settings, clock=lambda: 0).openapi()
    assert committed["paths"] == generated["paths"]
    assert committed["components"] == generated["components"]
