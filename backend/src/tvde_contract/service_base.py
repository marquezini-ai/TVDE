from __future__ import annotations

import re
import threading
import time
from collections import defaultdict, deque
from typing import Callable
from uuid import UUID

from fastapi import Request

from .errors import INVALID_REQUEST, INVALID_SIGNATURE, RATE_LIMITED
from .limits import LIMITS, ContractLimits
from .models import SyncPolicy
from .security import SignedHeaders, body_sha256


NONCE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{22,64}$")


class BackendServiceBase:
    def __init__(self, *, clock: Callable[[], int] | None = None, limits: ContractLimits = LIMITS):
        self.clock = clock or (lambda: int(time.time()))
        self.limits = limits
        self.rate_windows: dict[tuple[str, str], deque[int]] = defaultdict(deque)
        self.lock = threading.RLock()

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
