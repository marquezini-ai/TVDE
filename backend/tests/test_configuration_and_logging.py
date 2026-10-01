from __future__ import annotations

import json
import logging
from pathlib import Path

import pytest

from tvde_contract.config import BackendSettings
from tvde_contract.structured_logging import JsonFormatter, safe_principal


def test_environment_configuration_has_no_secret_default(monkeypatch, tmp_path):
    monkeypatch.setenv("TVDE_BACKEND_ENV", "test")
    monkeypatch.setenv("TVDE_DATABASE_PATH", str(tmp_path / "configured.sqlite3"))
    monkeypatch.setenv("TVDE_LOG_LEVEL", "WARNING")
    settings = BackendSettings.from_env()
    assert settings.environment == "test"
    assert settings.database_path == tmp_path / "configured.sqlite3"
    assert settings.log_level == "WARNING"
    assert "password" not in settings.__dict__
    assert "token" not in settings.__dict__
    assert "secret" not in settings.__dict__


def test_unsupported_environment_is_rejected(tmp_path):
    with pytest.raises(ValueError, match="test or development"):
        BackendSettings(
            environment="production",
            database_path=tmp_path / "db.sqlite3",
            migrations_path=Path("migrations"),
        )


def test_structured_log_redacts_protected_fields():
    record = logging.LogRecord("test", logging.INFO, __file__, 1, "request", (), None)
    record.event_fields = {
        "principal": safe_principal("installation-id"),
        "status": 200,
        "activation_key": "must-not-appear",
        "signature": "must-not-appear",
        "address": "must-not-appear",
        "pickup_address": "must-not-appear",
        "current_coordinates": "must-not-appear",
        "payload": "must-not-appear",
    }
    document = json.loads(JsonFormatter().format(record))
    assert document["status"] == 200
    assert document["principal"] != "installation-id"
    assert "must-not-appear" not in json.dumps(document)
    assert not (
        {
            "activation_key",
            "signature",
            "address",
            "pickup_address",
            "current_coordinates",
            "payload",
        }
        & document.keys()
    )
