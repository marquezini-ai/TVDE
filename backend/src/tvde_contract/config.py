from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from .limits import LIMITS, ContractLimits


@dataclass(frozen=True)
class BackendSettings:
    environment: str
    database_path: Path
    migrations_path: Path
    log_level: str = "INFO"
    timezone: str = "Europe/Lisbon"
    projection_retry_seconds: int = 30
    projection_processing_timeout_seconds: int = 300
    limits: ContractLimits = LIMITS

    def __post_init__(self) -> None:
        if self.environment not in {"test", "development"}:
            raise ValueError("environment must be test or development in Phase 3B.2")
        if self.projection_retry_seconds < 0:
            raise ValueError("projection_retry_seconds must not be negative")
        if self.projection_processing_timeout_seconds < 0:
            raise ValueError("projection_processing_timeout_seconds must not be negative")

    @classmethod
    def from_env(cls) -> BackendSettings:
        backend_root = Path(__file__).resolve().parents[2]
        environment = os.getenv("TVDE_BACKEND_ENV", "development")
        database_value = os.getenv("TVDE_DATABASE_PATH", str(backend_root / "var" / "tvde-local.sqlite3"))
        return cls(
            environment=environment,
            database_path=Path(database_value),
            migrations_path=backend_root / "migrations",
            log_level=os.getenv("TVDE_LOG_LEVEL", "INFO").upper(),
            timezone=os.getenv("TVDE_TIMEZONE", "Europe/Lisbon"),
            projection_retry_seconds=int(os.getenv("TVDE_PROJECTION_RETRY_SECONDS", "30")),
            projection_processing_timeout_seconds=int(
                os.getenv("TVDE_PROJECTION_PROCESSING_TIMEOUT_SECONDS", "300")
            ),
        )
