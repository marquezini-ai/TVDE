from __future__ import annotations

import hashlib
import logging
import secrets
import time
from collections.abc import Callable
from typing import Any
from uuid import UUID, uuid4

from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI, Request

from .aggregates import calculate_global_aggregates
from .config import BackendSettings
from .domain import Installation
from .errors import (
    CURSOR_EXPIRED,
    IDEMPOTENCY_CONFLICT,
    INSTALLATION_REVOKED,
    INVALID_CURSOR,
    INVALID_SIGNATURE,
    LICENSE_ALREADY_BOUND,
    LICENSE_EXPIRED,
    LICENSE_INVALID,
    LICENSE_REVOKED,
    REPLAY_DETECTED,
    TIMESTAMP_OUT_OF_RANGE,
)
from .harness import create_contract_app
from .models import (
    EventResult,
    EventStatus,
    OfferEvent,
    OwnChange,
    RegistrationRequest,
    RegistrationResponse,
    Role,
    SyncRequest,
    SyncResponse,
)
from .security import SignedHeaders, canonical_request, public_key_from_jwk, verify_request
from .sqlite_storage import SQLiteStorage
from .structured_logging import configure_logging, log_event, safe_principal
from .service_base import BackendServiceBase


FailureInjector = Callable[[str], None]


class PersistentBackend(BackendServiceBase):
    """Local persistent implementation behind the unchanged API v1 contract."""

    def __init__(
        self,
        settings: BackendSettings | Any,
        *,
        clock: Callable[[], int] | None = None,
        failure_injector: FailureInjector | None = None,
        storage: Any | None = None,
    ):
        super().__init__(clock=clock, limits=settings.limits)
        self.settings = settings
        self.storage = storage or SQLiteStorage(settings.database_path, settings.migrations_path)
        self.failure_injector = failure_injector or (lambda _: None)
        self.logger = logging.getLogger("tvde_backend.persistence")

    def add_license(
        self,
        activation_key: str,
        *,
        role: Role = Role.CLIENT,
        expires_at: int | None = None,
        revoked: bool = False,
    ) -> None:
        self.storage.put_license(activation_key, role.value, expires_at, revoked, self.clock())

    def revoke_installation(self, installation_id: UUID) -> None:
        self.storage.revoke_installation(installation_id)

    def expire_cursor(self, cursor: str) -> None:
        self.storage.expire_cursor(cursor, self.clock())

    def get_installation(self, installation_id: UUID) -> Installation | None:
        return self.storage.get_installation(installation_id)

    def rate_limit(self, scope: str, principal: str, *, maximum: int, window_seconds: int) -> None:
        persistent_limiter = getattr(self.storage, "consume_rate_limit", None)
        if persistent_limiter is None:
            super().rate_limit(
                scope,
                principal,
                maximum=maximum,
                window_seconds=window_seconds,
            )
            return
        if not persistent_limiter(
            scope,
            principal,
            maximum=maximum,
            window_seconds=window_seconds,
            now=self.clock(),
        ):
            from .errors import RATE_LIMITED

            raise RATE_LIMITED(window_seconds)

    def validate_installation_license(self, installation: Installation) -> None:
        license_record = self.storage.get_license(installation.activation_key)
        if license_record is None:
            raise LICENSE_INVALID()
        if license_record.revoked:
            raise LICENSE_REVOKED()
        if license_record.expires_at is not None and license_record.expires_at < self.clock():
            raise LICENSE_EXPIRED()

    def authenticate(
        self,
        *,
        request: Request,
        body: bytes,
        principal: str,
        public_key: ec.EllipticCurvePublicKey,
        headers: SignedHeaders,
    ) -> None:
        now = self.clock()
        if abs(now - headers.timestamp) > self.limits.timestamp_skew_seconds:
            raise TIMESTAMP_OUT_OF_RANGE()
        canonical = canonical_request(
            method=request.method,
            path=request.url.path,
            principal=principal,
            timestamp=headers.timestamp,
            nonce=headers.nonce,
            idempotency_key=headers.idempotency_key,
            body_hash=headers.body_hash,
        )
        if not verify_request(public_key, canonical, headers.signature):
            log_event(
                self.logger,
                logging.WARNING,
                "authentication_failed",
                principal=safe_principal(principal),
                code="INVALID_SIGNATURE",
            )
            raise INVALID_SIGNATURE()
        consumed = self.storage.consume_nonce(
            principal,
            headers.nonce,
            now + self.limits.nonce_retention_seconds,
            now,
        )
        if not consumed:
            raise REPLAY_DETECTED()
        log_event(
            self.logger,
            logging.INFO,
            "authentication_succeeded",
            principal=safe_principal(principal),
        )

    @staticmethod
    def _idempotent(existing, headers: SignedHeaders) -> tuple[int, dict] | None:
        if existing is None:
            return None
        if existing.body_hash != headers.body_hash:
            raise IDEMPOTENCY_CONFLICT()
        return existing.status, existing.body

    def execute_register(
        self,
        payload: RegistrationRequest,
        key_thumbprint: str,
        headers: SignedHeaders,
    ) -> tuple[int, dict]:
        now = self.clock()
        self.failure_injector("registration_before_transaction")

        def operation(connection):
            existing_response = self.storage.get_idempotency(
                connection, "register", key_thumbprint, headers.idempotency_key, now
            )
            remembered = self._idempotent(existing_response, headers)
            if remembered is not None:
                return remembered
            license_record = self.storage.get_license(payload.activation_key, connection)
            if license_record is None:
                raise LICENSE_INVALID()
            if license_record.revoked:
                raise LICENSE_REVOKED()
            if license_record.expires_at is not None and license_record.expires_at < now:
                raise LICENSE_EXPIRED()
            installation = self.storage.get_installation_for_license(payload.activation_key, connection)
            if installation is not None:
                if installation.revoked:
                    raise INSTALLATION_REVOKED()
                if installation.key_thumbprint != key_thumbprint:
                    raise LICENSE_ALREADY_BOUND()
            else:
                installation = Installation(
                    installation_id=uuid4(),
                    owner_id=hashlib.sha256(payload.activation_key.encode("utf-8")).hexdigest(),
                    activation_key=payload.activation_key,
                    public_key=public_key_from_jwk(payload.device_public_key),
                    key_thumbprint=key_thumbprint,
                    role=license_record.role,
                )
                self.storage.insert_installation(connection, installation, payload.device_public_key, now)
            response = RegistrationResponse(
                installation_id=installation.installation_id,
                role=installation.role,
                policy_version=1,
                server_time=now,
                sync_policy=self.policy(),
            )
            content = response.model_dump(mode="json")
            self.storage.insert_idempotency(
                connection,
                "register",
                key_thumbprint,
                headers.idempotency_key,
                headers.body_hash,
                200,
                content,
                now + self.limits.request_idempotency_retention_seconds,
                now,
            )
            self.failure_injector("registration_before_commit")
            return 200, content

        result = self.storage.run_transaction(operation)
        self.failure_injector("registration_after_commit")
        return result

    def execute_sync(
        self,
        installation: Installation,
        payload: SyncRequest,
        headers: SignedHeaders,
    ) -> tuple[int, dict]:
        now = self.clock()
        principal = str(installation.installation_id)
        self.failure_injector("sync_before_transaction")

        def operation(connection):
            existing_response = self.storage.get_idempotency(
                connection, "sync", principal, headers.idempotency_key, now
            )
            remembered = self._idempotent(existing_response, headers)
            if remembered is not None:
                log_event(
                    self.logger,
                    logging.INFO,
                    "idempotent_response_reused",
                    principal=safe_principal(principal),
                    endpoint="sync",
                )
                return remembered, []
            self.failure_injector("sync_during_processing")
            event_results: list[EventResult] = []
            for event in payload.events:
                result = self._persist_event(connection, installation.owner_id, event, now)
                event_results.append(result)
            self.failure_injector("sync_after_events")

            after_sequence = self._cursor_sequence(connection, payload.cursor, installation.owner_id, now)
            rows, has_more = self.storage.list_changes(
                connection,
                installation.owner_id,
                after_sequence,
                self.limits.own_changes_page_size,
            )
            own_changes = [OwnChange(sequence=sequence, event=event) for sequence, event in rows]
            next_sequence = own_changes[-1].sequence if own_changes else after_sequence
            next_cursor = secrets.token_urlsafe(32)
            self.storage.insert_cursor(
                connection,
                next_cursor,
                installation.owner_id,
                next_sequence,
                now + self.limits.cursor_retention_seconds,
                now,
            )
            aggregates = calculate_global_aggregates(
                self.storage.aggregate_rows(connection),
                payload.aggregate_query,
                now=now,
                timezone=self.settings.timezone,
            )
            response = SyncResponse(
                event_results=event_results,
                own_changes=own_changes,
                next_cursor=next_cursor,
                has_more=has_more,
                global_aggregates=aggregates,
                aggregate_version=1,
                policy_version=1,
                server_time=now,
            )
            content = response.model_dump(mode="json")
            self.storage.insert_idempotency(
                connection,
                "sync",
                principal,
                headers.idempotency_key,
                headers.body_hash,
                200,
                content,
                now + self.limits.request_idempotency_retention_seconds,
                now,
            )
            self.failure_injector("sync_before_commit")
            return (200, content), event_results

        result, event_results = self.storage.run_transaction(operation)
        self.failure_injector("sync_after_commit")
        log_event(
            self.logger,
            logging.INFO,
            "sync_committed",
            principal=safe_principal(principal),
            accepted=sum(item.status == EventStatus.ACCEPTED for item in event_results),
            duplicates=sum(item.status == EventStatus.DUPLICATE for item in event_results),
            rejected=sum(item.status == EventStatus.REJECTED for item in event_results),
        )
        self.failure_injector("sync_before_response")
        return result

    def _persist_event(
        self,
        connection: Any,
        owner_id: str,
        event: OfferEvent,
        now: int,
    ) -> EventResult:
        if (
            event.recorded_at_epoch_ms < self.limits.earliest_event_epoch_ms
            or event.recorded_at_epoch_ms > (now + self.limits.max_event_future_skew_seconds) * 1000
        ):
            return EventResult(
                event_id=event.event_id,
                status=EventStatus.REJECTED,
                code="EVENT_TIMESTAMP_OUT_OF_RANGE",
            )
        event_hash = hashlib.sha256(event.model_dump_json().encode("utf-8")).hexdigest()
        existing = self.storage.get_event(connection, owner_id, event.event_id)
        if existing is not None:
            if existing[0] == event_hash:
                return EventResult(event_id=event.event_id, status=EventStatus.DUPLICATE)
            return EventResult(
                event_id=event.event_id,
                status=EventStatus.REJECTED,
                code="EVENT_CONFLICT",
            )
        self.storage.insert_event_bundle(connection, owner_id, event, event_hash, now)
        return EventResult(event_id=event.event_id, status=EventStatus.ACCEPTED)

    def _cursor_sequence(
        self,
        connection: Any,
        cursor: str | None,
        owner_id: str,
        now: int,
    ) -> int:
        if cursor is None:
            return 0
        stored = self.storage.get_cursor(connection, cursor)
        if stored is None or stored.owner_id != owner_id:
            raise INVALID_CURSOR()
        if stored.expires_at < now:
            raise CURSOR_EXPIRED()
        return stored.sequence


def create_persistent_app(
    settings: BackendSettings,
    *,
    clock: Callable[[], int] | None = None,
    failure_injector: FailureInjector | None = None,
) -> FastAPI:
    configure_logging(settings.log_level)
    backend = PersistentBackend(
        settings,
        clock=clock or (lambda: int(time.time())),
        failure_injector=failure_injector,
    )
    app = create_contract_app(clock=backend.clock, limits=settings.limits, backend=backend)

    @app.middleware("http")
    async def structured_request_log(request: Request, call_next):
        started = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            installation_id = request.headers.get("X-TVDE-Installation-Id")
            log_event(
                backend.logger,
                logging.INFO,
                "request_completed",
                method=request.method,
                path=request.url.path,
                status=status,
                duration_ms=round((time.perf_counter() - started) * 1000, 2),
                principal=safe_principal(installation_id),
            )

    app.state.persistent_backend = backend
    return app
