from __future__ import annotations

import logging
import socket
import time
from collections.abc import Callable

import google.auth
import httplib2
from google_auth_httplib2 import AuthorizedHttp
from google.auth.transport.requests import Request as GoogleAuthRequest
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from .cloud_config import CloudBackendSettings
from .firestore_storage import CloudOutboxItem, FirestoreStorage
from .structured_logging import log_event, safe_principal


class ProjectionFailure(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _http_error_code(error: HttpError) -> str:
    status = getattr(error.resp, "status", 0)
    if status == 429:
        return "SHEETS_QUOTA"
    if status in {401, 403}:
        return "SHEETS_AUTH"
    if 500 <= status <= 599:
        return "SHEETS_UNAVAILABLE"
    return "SHEETS_HTTP_ERROR"


class GoogleSheetsProjector:
    """Idempotent projection: every event owns one deterministic sheet row."""

    HEADERS = [
        "event_id",
        "owner_hash",
        "recorded_at_epoch_ms",
        "platform",
        "category",
        "decision",
        "trip_value_cents",
        "net_trip_value_cents",
        "value_per_km_cents",
        "gross_value_per_km_cents",
        "value_per_hour_cents",
        "pickup_municipality",
    ]

    def __init__(self, spreadsheet_id: str, tab: str, *, service=None, timeout_seconds: int = 15):
        self.spreadsheet_id = spreadsheet_id
        self.tab = tab.replace("'", "''")
        if service is None:
            credentials, _ = google.auth.default(
                scopes=["https://www.googleapis.com/auth/spreadsheets"]
            )
            # Refresh with the requests transport, which honors its timeout.
            # The actual Sheets calls then use httplib2's configured socket timeout.
            credentials.refresh(GoogleAuthRequest())
            authorized_http = AuthorizedHttp(
                credentials,
                http=httplib2.Http(timeout=timeout_seconds),
            )
            service = build("sheets", "v4", http=authorized_http, cache_discovery=False)
        self.service = service

    def ensure_headers(self) -> None:
        self._update("A1:L1", [self.HEADERS])

    def project(self, item: CloudOutboxItem) -> None:
        event = item.event
        values = [[
            str(event.event_id),
            item.owner_id,
            event.recorded_at_epoch_ms,
            event.platform.value,
            event.category or "",
            event.decision.value,
            event.trip_value_cents,
            event.net_trip_value_cents if event.net_trip_value_cents is not None else "",
            event.value_per_km_cents,
            event.gross_value_per_km_cents,
            event.value_per_hour_cents,
            event.pickup_municipality or "",
        ]]
        self._update(f"A{item.target_row}:L{item.target_row}", values)

    def _update(self, cell_range: str, values: list[list[object]]) -> None:
        try:
            response = (
                self.service.spreadsheets()
                .values()
                .update(
                    spreadsheetId=self.spreadsheet_id,
                    range=f"'{self.tab}'!{cell_range}",
                    valueInputOption="RAW",
                    body={"values": values},
                )
                .execute(num_retries=0)
            )
        except HttpError as error:
            raise ProjectionFailure(_http_error_code(error)) from error
        except (TimeoutError, socket.timeout, OSError) as error:
            raise ProjectionFailure("SHEETS_NETWORK") from error
        if not isinstance(response, dict) or response.get("updatedRows") != 1:
            raise ProjectionFailure("SHEETS_INVALID_RESPONSE")


class CloudProjectionWorker:
    def __init__(
        self,
        storage: FirestoreStorage,
        projector: GoogleSheetsProjector,
        settings: CloudBackendSettings,
        *,
        clock: Callable[[], int] | None = None,
    ):
        self.storage = storage
        self.projector = projector
        self.settings = settings
        self.clock = clock or (lambda: int(time.time()))
        self.logger = logging.getLogger("tvde_backend.google_projection")

    def run_once(self) -> str:
        item = self.storage.claim_projection(
            self.clock(), self.settings.projection_processing_timeout_seconds
        )
        if item is None:
            return "IDLE"
        try:
            self.projector.project(item)
        except ProjectionFailure as error:
            self.storage.fail_projection(
                item.document_id,
                self.clock(),
                self.settings.projection_retry_seconds,
                error.code,
            )
            log_event(
                self.logger,
                logging.WARNING,
                "projection_retry_scheduled",
                principal=safe_principal(item.owner_id),
                event_id=str(item.event_id),
                attempt=item.attempts,
                code=error.code,
            )
            return "RETRY"
        self.storage.complete_projection(item.document_id, self.clock())
        log_event(
            self.logger,
            logging.INFO,
            "projection_completed",
            principal=safe_principal(item.owner_id),
            event_id=str(item.event_id),
            attempt=item.attempts,
        )
        return "DONE"

    def drain(self) -> dict[str, int]:
        result = {"done": 0, "retry": 0, "idle": 0}
        for _ in range(self.settings.projection_batch_size):
            status = self.run_once()
            result[status.lower()] += 1
            if status == "IDLE":
                break
        return result
