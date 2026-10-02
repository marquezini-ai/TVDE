from __future__ import annotations

import base64

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def load_license_private_key(encoded: str | None) -> ec.EllipticCurvePrivateKey | None:
    if not encoded or not encoded.strip():
        return None
    key = serialization.load_der_private_key(base64.b64decode(encoded.strip(), validate=True), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("TVDE license signing key must be a P-256 PKCS#8 private key")
    return key


def issue_activation_key(
    private_key: ec.EllipticCurvePrivateKey,
    *,
    android_id: str,
    expires_at_epoch_ms: int,
    license_type: str,
) -> str:
    payload = f"v1|{android_id.lower()}|{expires_at_epoch_ms}|{license_type}".encode("utf-8")
    signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    encode = lambda value: base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")
    return f"{encode(payload)}.{encode(signature)}"
