from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

from cryptography.hazmat.primitives.asymmetric import ec

from .models import Role


@dataclass
class LicenseRecord:
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
