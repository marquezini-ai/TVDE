from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from .models import EcPublicJwk


SIGNATURE_ALGORITHM = "ECDSA_P256_SHA256"
CANONICAL_PREFIX = "TVDE1"


def b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def body_sha256(body: bytes) -> str:
    return b64url_encode(hashlib.sha256(body).digest())


def jwk_thumbprint(jwk: EcPublicJwk) -> str:
    canonical = json.dumps(
        {"crv": jwk.crv, "kty": jwk.kty, "x": jwk.x, "y": jwk.y},
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return b64url_encode(hashlib.sha256(canonical).digest())


def public_key_from_jwk(jwk: EcPublicJwk) -> ec.EllipticCurvePublicKey:
    x = int.from_bytes(b64url_decode(jwk.x), "big")
    y = int.from_bytes(b64url_decode(jwk.y), "big")
    return ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()


def public_jwk_from_key(key: ec.EllipticCurvePublicKey) -> EcPublicJwk:
    numbers = key.public_numbers()
    return EcPublicJwk(
        kty="EC",
        crv="P-256",
        x=b64url_encode(numbers.x.to_bytes(32, "big")),
        y=b64url_encode(numbers.y.to_bytes(32, "big")),
    )


def canonical_request(
    *,
    method: str,
    path: str,
    principal: str,
    timestamp: int,
    nonce: str,
    idempotency_key: str,
    body_hash: str,
) -> bytes:
    if not path.startswith("/") or "?" in path or "#" in path:
        raise ValueError("path must be an absolute path without query or fragment")
    values = (
        CANONICAL_PREFIX,
        method.upper(),
        path,
        principal,
        str(timestamp),
        nonce,
        idempotency_key,
        body_hash,
    )
    if any("\n" in value or "\r" in value for value in values):
        raise ValueError("canonical fields must not contain line breaks")
    return ("\n".join(values) + "\n").encode("utf-8")


def sign_request(private_key: ec.EllipticCurvePrivateKey, canonical: bytes) -> str:
    """Test/client reference helper. Android uses SHA256withECDSA from Keystore."""
    return b64url_encode(private_key.sign(canonical, ec.ECDSA(hashes.SHA256())))


def verify_request(public_key: ec.EllipticCurvePublicKey, canonical: bytes, signature: str) -> bool:
    try:
        public_key.verify(b64url_decode(signature), canonical, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


@dataclass(frozen=True)
class SignedHeaders:
    timestamp: int
    nonce: str
    idempotency_key: str
    body_hash: str
    signature: str
