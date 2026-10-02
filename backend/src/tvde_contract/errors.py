from __future__ import annotations

from dataclasses import dataclass


@dataclass
class ContractError(Exception):
    code: str
    status: int
    message: str
    retryable: bool = False
    retry_after: int | None = None


INVALID_REQUEST = lambda: ContractError("INVALID_REQUEST", 400, "The request could not be parsed.")
VALIDATION_FAILED = lambda: ContractError("VALIDATION_FAILED", 422, "The request does not satisfy the API contract.")
INVALID_SIGNATURE = lambda: ContractError("INVALID_SIGNATURE", 401, "The request signature is invalid.")
TIMESTAMP_OUT_OF_RANGE = lambda: ContractError("TIMESTAMP_OUT_OF_RANGE", 401, "The request timestamp is outside the accepted window.")
REPLAY_DETECTED = lambda: ContractError("REPLAY_DETECTED", 409, "The request nonce has already been used.")
INSTALLATION_NOT_FOUND = lambda: ContractError("INSTALLATION_NOT_FOUND", 404, "The installation was not found.")
INSTALLATION_REVOKED = lambda: ContractError("INSTALLATION_REVOKED", 403, "The installation is revoked.")
LICENSE_INVALID = lambda: ContractError("LICENSE_INVALID", 403, "The activation license is invalid.")
LICENSE_EXPIRED = lambda: ContractError("LICENSE_EXPIRED", 403, "The activation license has expired.")
LICENSE_REVOKED = lambda: ContractError("LICENSE_REVOKED", 403, "The activation license is revoked.")
LICENSE_ALREADY_BOUND = lambda: ContractError("LICENSE_ALREADY_BOUND", 409, "The license is bound to another device key.")
ROLE_FORBIDDEN = lambda: ContractError("ROLE_FORBIDDEN", 403, "The installation is not authorized for this operation.")
SIGNING_UNAVAILABLE = lambda: ContractError("SIGNING_UNAVAILABLE", 503, "License signing is temporarily unavailable.", True)
IDEMPOTENCY_CONFLICT = lambda: ContractError("IDEMPOTENCY_CONFLICT", 409, "The idempotency key was reused with another body.")
INVALID_CURSOR = lambda: ContractError("INVALID_CURSOR", 409, "The cursor is invalid for this owner.")
CURSOR_EXPIRED = lambda: ContractError("CURSOR_EXPIRED", 410, "The cursor has expired.")
PAYLOAD_TOO_LARGE = lambda: ContractError("PAYLOAD_TOO_LARGE", 413, "The request exceeds the configured limit.")
RATE_LIMITED = lambda seconds=60: ContractError("RATE_LIMITED", 429, "The request rate limit was exceeded.", True, seconds)
UPSTREAM_FAILURE = lambda: ContractError("UPSTREAM_FAILURE", 502, "A required upstream service failed.", True)
TEMPORARY_UNAVAILABLE = lambda: ContractError("TEMPORARY_UNAVAILABLE", 503, "The service is temporarily unavailable.", True)
INTERNAL_ERROR = lambda: ContractError("INTERNAL_ERROR", 500, "An internal error occurred.", True)
