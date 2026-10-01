from __future__ import annotations

import json
from pathlib import Path

from tvde_contract.harness import create_contract_app


def main() -> None:
    app = create_contract_app(clock=lambda: 0)
    specification = app.openapi()
    specification["info"]["description"] = (
        "Contract-only specification for TVDE Insight API v1. The executable harness "
        "uses only in-memory fakes and contains no Google or production integration."
    )
    specification["x-tvde-signature-protocol"] = {
        "algorithm": "ECDSA P-256 with SHA-256",
        "signatureEncoding": "base64url without padding of ASN.1 DER ECDSA signature",
        "bodyHash": "base64url without padding of SHA-256 over exact transmitted body bytes",
        "canonicalUtf8": (
            "TVDE1\\n{METHOD}\\n{PATH}\\n{PRINCIPAL}\\n{TIMESTAMP}\\n{NONCE}\\n"
            "{IDEMPOTENCY_KEY}\\n{BODY_SHA256}\\n"
        ),
        "registrationPrincipal": "RFC 7638 thumbprint of device_public_key",
        "syncPrincipal": "X-TVDE-Installation-Id",
        "signedPath": "Absolute path only; query and fragment are forbidden",
    }
    target = Path(__file__).resolve().parents[1] / "openapi.json"
    target.write_text(json.dumps(specification, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
