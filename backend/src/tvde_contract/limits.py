from dataclasses import dataclass


@dataclass(frozen=True)
class ContractLimits:
    """Single source of truth for configurable v1 protocol limits."""

    max_events_per_sync: int = 100
    max_request_bytes: int = 256 * 1024
    max_address_chars: int = 500
    max_category_chars: int = 80
    timestamp_skew_seconds: int = 300
    nonce_retention_seconds: int = 600
    request_idempotency_retention_seconds: int = 7 * 24 * 60 * 60
    cursor_retention_seconds: int = 30 * 24 * 60 * 60
    own_changes_page_size: int = 100
    sync_requests_per_minute: int = 30
    sync_burst: int = 10
    registration_attempts_per_15_minutes: int = 5
    worker_attempts_per_run: int = 5
    max_event_future_skew_seconds: int = 24 * 60 * 60
    earliest_event_epoch_ms: int = 1_577_836_800_000


LIMITS = ContractLimits()
