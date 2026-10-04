from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAX_TEXT_FILE_BYTES = 5 * 1024 * 1024
FORBIDDEN_NAMES = (
    re.compile(r"(^|/)(local|keystore)\.properties$", re.IGNORECASE),
    re.compile(r"(^|/).*service[-_]?account.*\.json$", re.IGNORECASE),
    re.compile(r"(^|/)\.env(?:\..+)?$", re.IGNORECASE),
    re.compile(r"\.(?:jks|keystore|p12|pk8|pem)$", re.IGNORECASE),
)
SECRET_PATTERNS = (
    ("private key PEM", re.compile(r"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----")),
    ("Google API key", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
    ("GitHub token", re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}")),
    ("Slack token", re.compile(r"xox[baprs]-[0-9A-Za-z-]{20,}")),
    ("AWS access key", re.compile(r"(?:AKIA|ASIA)[A-Z0-9]{16}")),
    (
        "service-account private_key field",
        re.compile(r'"private_key"\s*:\s*"(?!REPLACE|EXAMPLE|CHANGEME)', re.IGNORECASE),
    ),
)


def tracked_files() -> list[str]:
    output = subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=ROOT, stderr=subprocess.STDOUT
    )
    return [item.decode("utf-8") for item in output.split(b"\0") if item]


def main() -> None:
    findings: list[str] = []
    for relative in tracked_files():
        normalized = relative.replace("\\", "/")
        if any(pattern.search(normalized) for pattern in FORBIDDEN_NAMES):
            findings.append(f"forbidden tracked filename: {normalized}")
        path = ROOT / relative
        if not path.is_file() or path.stat().st_size > MAX_TEXT_FILE_BYTES:
            continue
        raw = path.read_bytes()
        if b"\0" in raw:
            continue
        text = raw.decode("utf-8", errors="replace")
        for label, pattern in SECRET_PATTERNS:
            if pattern.search(text):
                findings.append(f"{label}: {normalized}")
    if findings:
        raise SystemExit("Potential tracked secrets found:\n" + "\n".join(sorted(set(findings))))
    print("Tracked secret scan passed")


if __name__ == "__main__":
    main()
