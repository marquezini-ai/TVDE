from __future__ import annotations

from typing import Protocol
from uuid import UUID

from .domain import IdempotentResponse, Installation, LicenseRecord, StoredCursor
from .models import AggregateQuery, GlobalAggregates, OfferEvent


class LicenseRepository(Protocol):
    def put_license(
        self, activation_key: str, role: str, expires_at: int | None, revoked: bool, now: int
    ) -> None: ...
    def get_license(self, activation_key: str) -> LicenseRecord | None: ...


class InstallationRepository(Protocol):
    def get_installation(self, installation_id: UUID) -> Installation | None: ...
    def get_installation_for_license(self, activation_key: str) -> Installation | None: ...


class ReplayRepository(Protocol):
    def consume_nonce(self, principal: str, nonce: str, expires_at: int, now: int) -> bool: ...


class IdempotencyRepository(Protocol):
    def get_idempotency(
        self, endpoint: str, principal: str, key: str, now: int
    ) -> IdempotentResponse | None: ...


class EventRepository(Protocol):
    def get_event(self, owner_id: str, event_id: UUID) -> tuple[str, OfferEvent, int] | None: ...


class CursorRepository(Protocol):
    def get_cursor(self, token: str) -> StoredCursor | None: ...


class AggregateRepository(Protocol):
    def calculate_aggregates(self, query: AggregateQuery, now: int) -> GlobalAggregates: ...


class ProjectionOutboxRepository(Protocol):
    def pending_projection_count(self) -> int: ...
