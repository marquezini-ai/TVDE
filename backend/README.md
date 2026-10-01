# TVDE Insight backend contract — Phase 3B.1

This directory is isolated from the Android application. It contains an executable API contract, in-memory fakes, OpenAPI and tests. It does **not** contain a production backend, Google Sheets access, Firestore access, Cloud Run configuration or credentials.

## Local verification

```powershell
py -3.13 -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -e "backend[test]"
backend/.venv/Scripts/python.exe backend/tools/export_openapi.py
backend/.venv/Scripts/python.exe -m pytest backend/tests
```

Authoritative artifacts:

- `docs/ADR-001-secure-sync-api-v1.md`: decisions and protocol semantics.
- `openapi.json`: machine-readable API surface.
- `src/tvde_contract/models.py`: executable DTO constraints.
- `src/tvde_contract/security.py`: canonical signature reference.
- `src/tvde_contract/harness.py`: non-production in-memory behavior.
- `tests/`: contract and security proof matrix.

The harness must never be deployed. Phase 3B.2 will implement real ports and persistence behind the same contract after explicit approval.
