from __future__ import annotations

import logging
from dataclasses import dataclass, field
from uuid import UUID

from .config import BackendSettings
from .models import OfferEvent
from .sqlite_storage import SQLiteStorage
from .structured_logging import log_event, safe_principal


class ProjectionUnavailable(RuntimeError):
    pass


@dataclass
class FakeGoogleSink:
    available: bool = True
    projected: dict[tuple[str, UUID], OfferEvent] = field(default_factory=dict)

    def project(self, owner_id: str, event: OfferEvent) -> None:
        if not self.available:
            raise ProjectionUnavailable("fake projector unavailable")
        self.projected.setdefault((owner_id, event.event_id), event)


class FakeProjectionWorker:
    def __init__(
        self,
        storage: SQLiteStorage,
        sink: FakeGoogleSink,
        settings: BackendSettings,
        clock,
    ):
        self.storage = storage
        self.sink = sink
        self.settings = settings
        self.clock = clock
        self.logger = logging.getLogger("tvde_backend.projection")

    def run_once(
        self,
        *,
        crash_after_claim: bool = False,
        crash_after_project: bool = False,
    ) -> str:
        now = self.clock()
        item = self.storage.claim_projection(
            now,
            self.settings.projection_processing_timeout_seconds,
        )
        if item is None:
            return "IDLE"
        if crash_after_claim:
            raise RuntimeError("simulated crash after projection claim")
        try:
            self.sink.project(item.owner_id, item.event)
            if crash_after_project:
                raise RuntimeError("simulated crash after external projection")
            self.storage.complete_projection(item.outbox_id, self.clock())
            log_event(
                self.logger,
                logging.INFO,
                "projection_completed",
                principal=safe_principal(item.owner_id),
                event_id=str(item.event_id),
                attempt=item.attempts,
            )
            return "DONE"
        except ProjectionUnavailable:
            self.storage.fail_projection(
                item.outbox_id,
                self.clock(),
                self.settings.projection_retry_seconds,
                "PROJECTOR_UNAVAILABLE",
            )
            log_event(
                self.logger,
                logging.WARNING,
                "projection_retry_scheduled",
                principal=safe_principal(item.owner_id),
                event_id=str(item.event_id),
                attempt=item.attempts,
                code="PROJECTOR_UNAVAILABLE",
            )
            return "RETRY"
