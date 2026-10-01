from __future__ import annotations

import hashlib
import re
import secrets
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import timedelta
from typing import Callable
from uuid import UUID, uuid4

from cryptography.hazmat.primitives.asymmetric import ec
from typing import Annotated

from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .errors import (
    CURSOR_EXPIRED,
    IDEMPOTENCY_CONFLICT,
    INSTALLATION_NOT_FOUND,
    INSTALLATION_REVOKED,
    INVALID_CURSOR,
    INVALID_REQUEST,
    INVALID_SIGNATURE,
    LICENSE_ALREADY_BOUND,
    LICENSE_EXPIRED,
    LICENSE_INVALID,
    LICENSE_REVOKED,
    PAYLOAD_TOO_LARGE,
    RATE_LIMITED,
    REPLAY_DETECTED,
    TIMESTAMP_OUT_OF_RANGE,
    VALIDATION_FAILED,
    ContractError,
)
from .limits import LIMITS, ContractLimits
from .models import (
    DailyAggregate,
    EventResult,
    EventStatus,
    ErrorEnvelope,
    GlobalAggregates,
    HeatmapAggregate,
    OfferEvent,
    OwnChange,
    RegistrationRequest,
    RegistrationResponse,
    Role,
    SyncPolicy,
    SyncRequest,
    SyncResponse,
)
from .security import (
    SignedHeaders,
    body_sha256,
    canonical_request,
    jwk_thumbprint,
    public_key_from_jwk,
    verify_request,
)


NONCE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{22,64}$")


@dataclass
class FakeLicense:
    activation_key: str
    role: Role = Role.CLIENT
    expires_at: int | None = None
    revoked: bool = False


@dataclass
class Installation:
    installation_id: UUID
    owner_id: str
    activation_key: str
    public_key: ec.EllipticCurvePublicKey
    key_thumbprint: str
    role: Role
    revoked: bool = False


@dataclass
class IdempotentResponse:
    body_hash: str
    status: int
    body: dict
    expires_at: int


@dataclass
class StoredCursor:
    owner_id: str
    sequence: int
    expires_at: int


class ContractHarness:
    """In-memory executable specification. It is not a production backend."""

    def __init__(self, *, clock: Callable[[], int] | None = None, limits: ContractLimits = LIMITS):
        self.clock = clock or (lambda: int(time.time()))
        self.limits = limits
        self.licenses: dict[str, FakeLicense] = {}
        self.installations: dict[UUID, Installation] = {}
        self.installation_by_license: dict[str, UUID] = {}
        self.nonces: dict[tuple[str, str], int] = {}
        self.idempotency: dict[tuple[str, str, str], IdempotentResponse] = {}
        self.events: dict[str, dict[UUID, tuple[str, OfferEvent, int]]] = defaultdict(dict)
        self.cursors: dict[str, StoredCursor] = {}
        self.rate_windows: dict[tuple[str, str], deque[int]] = defaultdict(deque)
        self.lock = threading.RLock()

    def add_license(
        self,
        activation_key: str,
        *,
        role: Role = Role.CLIENT,
        expires_at: int | None = None,
        revoked: bool = False,
    ) -> None:
        self.licenses[activation_key] = FakeLicense(activation_key, role, expires_at, revoked)

    def revoke_installation(self, installation_id: UUID) -> None:
        self.installations[installation_id].revoked = True

    def expire_cursor(self, cursor: str) -> None:
        self.cursors[cursor].expires_at = self.clock() - 1

    def policy(self) -> SyncPolicy:
        return SyncPolicy(
            max_events_per_sync=self.limits.max_events_per_sync,
            max_request_bytes=self.limits.max_request_bytes,
            timestamp_skew_seconds=self.limits.timestamp_skew_seconds,
            nonce_retention_seconds=self.limits.nonce_retention_seconds,
            request_idempotency_retention_seconds=self.limits.request_idempotency_retention_seconds,
            cursor_retention_seconds=self.limits.cursor_retention_seconds,
            own_changes_page_size=self.limits.own_changes_page_size,
            sync_requests_per_minute=self.limits.sync_requests_per_minute,
            sync_burst=self.limits.sync_burst,
            worker_attempts_per_run=self.limits.worker_attempts_per_run,
            max_event_future_skew_seconds=self.limits.max_event_future_skew_seconds,
        )

    def rate_limit(self, scope: str, principal: str, *, maximum: int, window_seconds: int) -> None:
        with self.lock:
            now = self.clock()
            entries = self.rate_windows[(scope, principal)]
            while entries and entries[0] <= now - window_seconds:
                entries.popleft()
            if len(entries) >= maximum:
                raise RATE_LIMITED(window_seconds)
            entries.append(now)

    def signed_headers(self, request: Request, body: bytes) -> SignedHeaders:
        try:
            timestamp = int(request.headers["X-TVDE-Timestamp"])
            nonce = request.headers["X-TVDE-Nonce"]
            idempotency_key = request.headers["Idempotency-Key"]
            supplied_hash = request.headers["X-TVDE-Body-SHA256"]
            signature = request.headers["X-TVDE-Signature"]
            UUID(idempotency_key)
        except (KeyError, TypeError, ValueError):
            raise INVALID_REQUEST()
        if not NONCE_PATTERN.fullmatch(nonce):
            raise INVALID_REQUEST()
        calculated_hash = body_sha256(body)
        if supplied_hash != calculated_hash:
            raise INVALID_SIGNATURE()
        return SignedHeaders(timestamp, nonce, idempotency_key, supplied_hash, signature)

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
            raise INVALID_SIGNATURE()
        with self.lock:
            self._purge_expired()
            nonce_key = (principal, headers.nonce)
            if nonce_key in self.nonces:
                raise REPLAY_DETECTED()
            self.nonces[nonce_key] = now + self.limits.nonce_retention_seconds

    def idempotent_result(self, endpoint: str, principal: str, headers: SignedHeaders) -> IdempotentResponse | None:
        existing = self.idempotency.get((endpoint, principal, headers.idempotency_key))
        if existing is None:
            return None
        if existing.body_hash != headers.body_hash:
            raise IDEMPOTENCY_CONFLICT()
        return existing

    def remember_response(
        self,
        endpoint: str,
        principal: str,
        headers: SignedHeaders,
        status: int,
        body: dict,
    ) -> None:
        self.idempotency[(endpoint, principal, headers.idempotency_key)] = IdempotentResponse(
            body_hash=headers.body_hash,
            status=status,
            body=body,
            expires_at=self.clock() + self.limits.request_idempotency_retention_seconds,
        )

    def register(self, payload: RegistrationRequest, key_thumbprint: str) -> RegistrationResponse:
        now = self.clock()
        license_record = self.licenses.get(payload.activation_key)
        if license_record is None:
            raise LICENSE_INVALID()
        if license_record.revoked:
            raise LICENSE_REVOKED()
        if license_record.expires_at is not None and license_record.expires_at < now:
            raise LICENSE_EXPIRED()
        existing_id = self.installation_by_license.get(payload.activation_key)
        if existing_id is not None:
            existing = self.installations[existing_id]
            if existing.revoked:
                raise INSTALLATION_REVOKED()
            if existing.key_thumbprint != key_thumbprint:
                raise LICENSE_ALREADY_BOUND()
            return RegistrationResponse(
                installation_id=existing.installation_id,
                role=existing.role,
                policy_version=1,
                server_time=now,
                sync_policy=self.policy(),
            )
        owner_id = hashlib.sha256(payload.activation_key.encode("utf-8")).hexdigest()
        installation = Installation(
            installation_id=uuid4(),
            owner_id=owner_id,
            activation_key=payload.activation_key,
            public_key=public_key_from_jwk(payload.device_public_key),
            key_thumbprint=key_thumbprint,
            role=license_record.role,
        )
        self.installations[installation.installation_id] = installation
        self.installation_by_license[payload.activation_key] = installation.installation_id
        return RegistrationResponse(
            installation_id=installation.installation_id,
            role=installation.role,
            policy_version=1,
            server_time=now,
            sync_policy=self.policy(),
        )

    def sync(self, installation: Installation, payload: SyncRequest) -> SyncResponse:
        now = self.clock()
        event_results: list[EventResult] = []
        owner_events = self.events[installation.owner_id]
        for event in payload.events:
            if (
                event.recorded_at_epoch_ms < self.limits.earliest_event_epoch_ms
                or event.recorded_at_epoch_ms
                > (now + self.limits.max_event_future_skew_seconds) * 1000
            ):
                event_results.append(
                    EventResult(
                        event_id=event.event_id,
                        status=EventStatus.REJECTED,
                        code="EVENT_TIMESTAMP_OUT_OF_RANGE",
                    )
                )
                continue
            event_hash = hashlib.sha256(event.model_dump_json().encode("utf-8")).hexdigest()
            existing = owner_events.get(event.event_id)
            if existing is None:
                sequence = 1 + max((item[2] for item in owner_events.values()), default=0)
                owner_events[event.event_id] = (event_hash, event, sequence)
                event_results.append(EventResult(event_id=event.event_id, status=EventStatus.ACCEPTED))
            elif existing[0] == event_hash:
                event_results.append(EventResult(event_id=event.event_id, status=EventStatus.DUPLICATE))
            else:
                event_results.append(
                    EventResult(event_id=event.event_id, status=EventStatus.REJECTED, code="EVENT_CONFLICT")
                )

        after_sequence = self._cursor_sequence(payload.cursor, installation.owner_id)
        changes = sorted(
            (item for item in owner_events.values() if item[2] > after_sequence),
            key=lambda item: item[2],
        )
        page = changes[: self.limits.own_changes_page_size]
        own_changes = [OwnChange(sequence=item[2], event=item[1]) for item in page]
        next_sequence = own_changes[-1].sequence if own_changes else after_sequence
        next_cursor = self._new_cursor(installation.owner_id, next_sequence)
        aggregates = self._empty_aggregates(payload, now)
        return SyncResponse(
            event_results=event_results,
            own_changes=own_changes,
            next_cursor=next_cursor,
            has_more=len(changes) > len(page),
            global_aggregates=aggregates,
            aggregate_version=1,
            policy_version=1,
            server_time=now,
        )

    def _cursor_sequence(self, cursor: str | None, owner_id: str) -> int:
        if cursor is None:
            return 0
        stored = self.cursors.get(cursor)
        if stored is None or stored.owner_id != owner_id:
            raise INVALID_CURSOR()
        if stored.expires_at < self.clock():
            raise CURSOR_EXPIRED()
        return stored.sequence

    def _new_cursor(self, owner_id: str, sequence: int) -> str:
        token = secrets.token_urlsafe(32)
        self.cursors[token] = StoredCursor(
            owner_id=owner_id,
            sequence=sequence,
            expires_at=self.clock() + self.limits.cursor_retention_seconds,
        )
        return token

    def _empty_aggregates(self, payload: SyncRequest, now: int) -> GlobalAggregates:
        heatmap = [
            HeatmapAggregate(day_of_week=day, shift=shift, median_cents=None, event_count=0)
            for shift in ("MORNING", "AFTERNOON", "NIGHT", "DAWN")
            for day in ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
        ]
        start = payload.aggregate_query.end_date - timedelta(days=29)
        calendar = [
            DailyAggregate(date=start + timedelta(days=offset), average_cents=None, event_count=0)
            for offset in range(30)
        ]
        return GlobalAggregates(
            query=payload.aggregate_query,
            pickup_municipalities=[],
            heatmap=heatmap,
            daily_calendar=calendar,
            recorded_dates=[],
            generated_at=now,
        )

    def _purge_expired(self) -> None:
        now = self.clock()
        self.nonces = {key: expiry for key, expiry in self.nonces.items() if expiry >= now}
        self.idempotency = {key: item for key, item in self.idempotency.items() if item.expires_at >= now}


def create_contract_app(
    *, clock: Callable[[], int] | None = None, limits: ContractLimits = LIMITS
) -> FastAPI:
    harness = ContractHarness(clock=clock, limits=limits)
    app = FastAPI(
        title="TVDE Insight Secure Sync Contract Harness",
        version="1.0.0-contract",
        description="Executable specification only. No Google, Firestore or production credentials.",
    )
    app.state.harness = harness

    @app.middleware("http")
    async def reject_declared_oversize_body(request: Request, call_next):
        declared = request.headers.get("Content-Length")
        if declared is not None and declared.isdigit() and int(declared) > harness.limits.max_request_bytes:
            request_id = str(uuid4())
            error = PAYLOAD_TOO_LARGE()
            return JSONResponse(
                status_code=error.status,
                content={
                    "error": {
                        "code": error.code,
                        "message": error.message,
                        "retryable": error.retryable,
                        "request_id": request_id,
                        "server_time": harness.clock(),
                    }
                },
                headers={"X-Request-Id": request_id},
            )
        return await call_next(request)

    @app.exception_handler(ContractError)
    async def contract_error_handler(request: Request, error: ContractError) -> JSONResponse:
        request_id = str(uuid4())
        content = {
            "error": {
                "code": error.code,
                "message": error.message,
                "retryable": error.retryable,
                "request_id": request_id,
                "server_time": harness.clock(),
            }
        }
        headers = {"X-Request-Id": request_id}
        if error.retry_after is not None:
            headers["Retry-After"] = str(error.retry_after)
        return JSONResponse(status_code=error.status, content=content, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, error: RequestValidationError) -> JSONResponse:
        malformed = any(
            item.get("type") == "json_invalid" or tuple(item.get("loc", ()))[:1] == ("header",)
            for item in error.errors()
        )
        return await contract_error_handler(request, INVALID_REQUEST() if malformed else VALIDATION_FAILED())

    async def read_body(request: Request) -> bytes:
        body = await request.body()
        if len(body) > harness.limits.max_request_bytes:
            raise PAYLOAD_TOO_LARGE()
        if not body:
            raise INVALID_REQUEST()
        return body

    error_responses = {
        status: {"model": ErrorEnvelope, "description": description}
        for status, description in {
            400: "Malformed request",
            401: "Authentication failed",
            403: "License or authorization denied",
            404: "Installation not found",
            409: "Replay, binding or idempotency conflict",
            410: "Cursor expired",
            413: "Payload too large",
            422: "Contract validation failed",
            429: "Rate limited",
            500: "Internal failure",
            502: "Upstream failure",
            503: "Temporarily unavailable",
        }.items()
    }

    @app.post(
        "/v1/installations/register",
        response_model=RegistrationResponse,
        responses=error_responses,
    )
    async def register(
        request: Request,
        payload: RegistrationRequest,
        x_tvde_key_id: Annotated[str, Header(alias="X-TVDE-Key-Id")],
        x_tvde_timestamp: Annotated[str, Header(alias="X-TVDE-Timestamp")],
        x_tvde_nonce: Annotated[str, Header(alias="X-TVDE-Nonce")],
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
        x_tvde_body_sha256: Annotated[str, Header(alias="X-TVDE-Body-SHA256")],
        x_tvde_signature: Annotated[str, Header(alias="X-TVDE-Signature")],
    ) -> JSONResponse:
        body = await read_body(request)
        key_thumbprint = jwk_thumbprint(payload.device_public_key)
        if x_tvde_key_id != key_thumbprint:
            raise INVALID_SIGNATURE()
        headers = harness.signed_headers(request, body)
        harness.authenticate(
            request=request,
            body=body,
            principal=key_thumbprint,
            public_key=public_key_from_jwk(payload.device_public_key),
            headers=headers,
        )
        harness.rate_limit(
            "register",
            key_thumbprint,
            maximum=harness.limits.registration_attempts_per_15_minutes,
            window_seconds=15 * 60,
        )
        with harness.lock:
            remembered = harness.idempotent_result("register", key_thumbprint, headers)
            if remembered is not None:
                return JSONResponse(status_code=remembered.status, content=remembered.body)
            response = harness.register(payload, key_thumbprint)
            content = response.model_dump(mode="json")
            harness.remember_response("register", key_thumbprint, headers, 200, content)
        return JSONResponse(status_code=200, content=content)

    @app.post("/v1/sync", response_model=SyncResponse, responses=error_responses)
    async def sync(
        request: Request,
        payload: SyncRequest,
        x_tvde_installation_id: Annotated[str, Header(alias="X-TVDE-Installation-Id")],
        x_tvde_timestamp: Annotated[str, Header(alias="X-TVDE-Timestamp")],
        x_tvde_nonce: Annotated[str, Header(alias="X-TVDE-Nonce")],
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
        x_tvde_body_sha256: Annotated[str, Header(alias="X-TVDE-Body-SHA256")],
        x_tvde_signature: Annotated[str, Header(alias="X-TVDE-Signature")],
    ) -> JSONResponse:
        body = await read_body(request)
        try:
            installation_id = UUID(x_tvde_installation_id)
        except ValueError:
            raise INSTALLATION_NOT_FOUND()
        installation = harness.installations.get(installation_id)
        if installation is None:
            raise INSTALLATION_NOT_FOUND()
        if installation.revoked:
            raise INSTALLATION_REVOKED()
        license_record = harness.licenses[installation.activation_key]
        if license_record.revoked:
            raise LICENSE_REVOKED()
        if license_record.expires_at is not None and license_record.expires_at < harness.clock():
            raise LICENSE_EXPIRED()
        headers = harness.signed_headers(request, body)
        harness.authenticate(
            request=request,
            body=body,
            principal=str(installation_id),
            public_key=installation.public_key,
            headers=headers,
        )
        harness.rate_limit(
            "sync",
            str(installation_id),
            maximum=harness.limits.sync_requests_per_minute,
            window_seconds=60,
        )
        with harness.lock:
            remembered = harness.idempotent_result("sync", str(installation_id), headers)
            if remembered is not None:
                return JSONResponse(status_code=remembered.status, content=remembered.body)
            response = harness.sync(installation, payload)
            content = response.model_dump(mode="json")
            harness.remember_response("sync", str(installation_id), headers, 200, content)
        return JSONResponse(status_code=200, content=content)

    return app
