from __future__ import annotations

import json
from pathlib import Path

from openapi_spec_validator import validate

from tvde_contract.harness import create_contract_app


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
