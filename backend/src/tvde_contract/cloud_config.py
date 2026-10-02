from __future__ import annotations

import os
from dataclasses import dataclass

from .limits import LIMITS, ContractLimits


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"{name} is required")
    return value


@dataclass(frozen=True)
class CloudBackendSettings:
    environment: str
    project_id: str
    database_id: str = "(default)"
    log_level: str = "INFO"
    timezone: str = "Europe/Lisbon"
    projection_retry_seconds: int = 60
    projection_processing_timeout_seconds: int = 300
    projection_batch_size: int = 20
    spreadsheet_id: str | None = None
    spreadsheet_tab: str = "Events"
    limits: ContractLimits = LIMITS

    def __post_init__(self) -> None:
        if self.environment != "cloud-test":
            raise ValueError("cloud backend is restricted to cloud-test")
        if not self.project_id.strip():
            raise ValueError("project_id is required")
        if self.projection_retry_seconds < 1:
            raise ValueError("projection_retry_seconds must be positive")
        if self.projection_processing_timeout_seconds < 1:
            raise ValueError("projection_processing_timeout_seconds must be positive")
        if not 1 <= self.projection_batch_size <= 100:
            raise ValueError("projection_batch_size must be between 1 and 100")

    @classmethod
    def from_env(cls, *, require_spreadsheet: bool = False) -> CloudBackendSettings:
        spreadsheet_id = os.getenv("TVDE_GOOGLE_SHEET_ID", "").strip() or None
        if require_spreadsheet and spreadsheet_id is None:
            raise ValueError("TVDE_GOOGLE_SHEET_ID is required for the projector")
        return cls(
            environment=_required("TVDE_BACKEND_ENV"),
            project_id=_required("GOOGLE_CLOUD_PROJECT"),
            database_id=os.getenv("TVDE_FIRESTORE_DATABASE", "(default)"),
            log_level=os.getenv("TVDE_LOG_LEVEL", "INFO").upper(),
            timezone=os.getenv("TVDE_TIMEZONE", "Europe/Lisbon"),
            projection_retry_seconds=int(os.getenv("TVDE_PROJECTION_RETRY_SECONDS", "60")),
            projection_processing_timeout_seconds=int(
                os.getenv("TVDE_PROJECTION_PROCESSING_TIMEOUT_SECONDS", "300")
            ),
            projection_batch_size=int(os.getenv("TVDE_PROJECTION_BATCH_SIZE", "20")),
            spreadsheet_id=spreadsheet_id,
            spreadsheet_tab=os.getenv("TVDE_GOOGLE_SHEET_TAB", "Events").strip() or "Events",
        )
