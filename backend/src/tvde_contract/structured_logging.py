from __future__ import annotations

import hashlib
import json
import logging
from datetime import UTC, datetime


PROTECTED_KEYS = {
    "activation_key",
    "signature",
    "token",
    "public_key",
    "private_key",
    "address",
    "coordinates",
    "payload",
    "ocr",
}


def safe_principal(value: str | None) -> str | None:
    if not value:
        return None
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        fields = getattr(record, "event_fields", {})
        safe_fields = {
            key: value
            for key, value in fields.items()
            if not any(protected in key.lower() for protected in PROTECTED_KEYS)
        }
        document = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            **safe_fields,
        }
        return json.dumps(document, separators=(",", ":"), sort_keys=True, default=str)


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger("tvde_backend")
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level)
    root.propagate = False


def log_event(logger: logging.Logger, level: int, message: str, **fields: object) -> None:
    logger.log(level, message, extra={"event_fields": fields})
