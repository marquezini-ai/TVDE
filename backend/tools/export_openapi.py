from __future__ import annotations

import argparse
import json
from pathlib import Path

from tvde_contract.harness import create_contract_app


def render_openapi() -> str:
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
    return json.dumps(specification, indent=2, sort_keys=True) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Export or verify the committed OpenAPI contract")
    parser.add_argument("--check", action="store_true", help="fail when openapi.json is stale")
    args = parser.parse_args()
    target = Path(__file__).resolve().parents[1] / "openapi.json"
    rendered = render_openapi()
    if args.check:
        if not target.is_file() or target.read_text(encoding="utf-8") != rendered:
            raise SystemExit("backend/openapi.json is stale; run backend/tools/export_openapi.py")
        return
    target.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
