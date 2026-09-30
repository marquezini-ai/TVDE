#!/usr/bin/env python3
"""Analisa uma pasta criada por perf_capture.ps1 sem dependências externas."""

from __future__ import annotations

import argparse
import json
import re
import statistics
from pathlib import Path


GC_RE = re.compile(
    r"(?P<reason>\w+) concurrent copying GC freed "
    r"(?P<objects>[\d.,]+)\((?P<size>[\d.,]+)(?P<unit>KB|MB|B)\).*?"
    r"total (?P<duration>[\d.,]+)ms",
    re.IGNORECASE,
)


def number(value: str) -> float:
    value = value.strip()
    if ',' in value and '.' not in value:
        value = value.replace(',', '.')
    else:
        value = value.replace(',', '')
    return float(value)


def summary(values: list[float]) -> dict[str, float | int]:
    if not values:
        return {"samples": 0}
    ordered = sorted(values)
    p95_index = min(len(ordered) - 1, int((len(ordered) - 1) * 0.95))
    return {
        "samples": len(values),
        "mean": round(statistics.fmean(values), 3),
        "median": round(statistics.median(values), 3),
        "p95": round(ordered[p95_index], 3),
        "max": round(max(values), 3),
        "samples_ge_80_percent": round(sum(v >= 80 for v in values) * 100 / len(values), 2),
    }


def parse_top(path: Path) -> dict[str, dict[str, float | int]]:
    groups: dict[str, list[float]] = {}
    cpu_index = None
    name_index = None
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        parts = line.split()
        if "%CPU" in parts:
            cpu_index = parts.index("%CPU")
            name_index = parts.index("THREAD") if "THREAD" in parts else len(parts) - 1
            continue
        if cpu_index is None or name_index is None or len(parts) <= max(cpu_index, name_index):
            continue
        try:
            cpu = number(parts[cpu_index].rstrip("%"))
        except ValueError:
            continue
        name = parts[name_index][:15]
        groups.setdefault(name, []).append(cpu)
    return {name: summary(values) for name, values in sorted(groups.items())}


def parse_gc(path: Path) -> dict[str, object]:
    events = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        match = GC_RE.search(line)
        if not match:
            continue
        size = number(match.group("size"))
        unit = match.group("unit").upper()
        size_mb = size / 1024 if unit == "KB" else size if unit == "MB" else size / (1024 * 1024)
        events.append(
            {
                "reason": match.group("reason"),
                "objects": number(match.group("objects")),
                "mb": size_mb,
                "duration_ms": number(match.group("duration")),
            }
        )
    return {
        "count": len(events),
        "objects": summary([event["objects"] for event in events]),
        "mb": summary([event["mb"] for event in events]),
        "duration_ms": summary([event["duration_ms"] for event in events]),
        "reasons": sorted({event["reason"] for event in events}),
    }


def first_match(text: str, pattern: str) -> float | None:
    match = re.search(pattern, text, re.IGNORECASE | re.MULTILINE)
    return number(match.group(1)) if match else None


def parse_mem(path: Path) -> dict[str, float | None]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    return {
        "total_pss_kb": first_match(text, r"TOTAL\s+(\d+)"),
        "total_rss_kb": first_match(text, r"TOTAL\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)"),
        "swap_pss_kb": first_match(text, r"TOTAL SWAP PSS:\s*(\d+)"),
        "java_heap_pss_kb": first_match(text, r"^\s*Java Heap:\s*(\d+)"),
        "native_heap_pss_kb": first_match(text, r"^\s*Native Heap:\s*(\d+)"),
        "graphics_pss_kb": first_match(text, r"^\s*Graphics:\s*(\d+)"),
    }


def analyze(folder: Path) -> dict[str, object]:
    metrics: dict[str, object] = {"folder": str(folder.resolve())}
    top = next(folder.glob("*-work-top.txt"), None)
    logcat = next(folder.glob("*-work-logcat.txt"), None)
    mem = next(folder.glob("*-work-mem.txt"), None)
    threads = next(folder.glob("*-work-threads.txt"), None)
    metrics["thread_cpu"] = parse_top(top) if top else {}
    metrics["gc"] = parse_gc(logcat) if logcat else {"count": 0}
    metrics["memory"] = parse_mem(mem) if mem else {}
    metrics["thread_count"] = (
        max(0, len(threads.read_text(encoding="utf-8", errors="ignore").splitlines()) - 1)
        if threads else None
    )
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    metrics = analyze(args.capture)
    output = args.output or args.capture / "metrics.json"
    output.write_text(json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
