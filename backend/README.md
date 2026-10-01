# TVDE Insight local backend — Phase 3B.2

This directory is isolated from Android. It contains the stable API v1 contract, its in-memory specification and a persistent local SQLite implementation. It does **not** contain Google Sheets access, Firestore, Cloud Run configuration, production configuration or credentials.

Architecture:

```text
FastAPI v1
  -> PersistentBackend service
    -> repository ports
      -> SQLiteStorage + versioned migrations
        -> transactional events/changes/idempotency/outbox
  -> FakeProjectionWorker -> FakeGoogleSink
```

## Local verification

```powershell
py -3.13 -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -e "backend[test]"
backend/.venv/Scripts/python.exe backend/tools/export_openapi.py
backend/.venv/Scripts/python.exe -m pytest backend/tests
```

## Run locally

```powershell
$env:TVDE_BACKEND_ENV = "development"
$env:TVDE_DATABASE_PATH = "C:\safe-local-path\tvde-local.sqlite3"
backend/.venv/Scripts/python.exe backend/tools/seed_test_license.py --activation-key "test.activation.local.client.change-me"
backend/.venv/Scripts/python.exe -m uvicorn tvde_contract.main:app --host 127.0.0.1 --port 8080
```

The activation key argument is required and has no default. Use only synthetic local values. The server creates and migrates the SQLite database automatically. Never commit the database, WAL or SHM files.

Supported environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `TVDE_BACKEND_ENV` | `development` | Only `development` and `test` are accepted |
| `TVDE_DATABASE_PATH` | `backend/var/tvde-local.sqlite3` | SQLite path |
| `TVDE_LOG_LEVEL` | `INFO` | Structured log level |
| `TVDE_TIMEZONE` | `Europe/Lisbon` | Statistics timezone |
| `TVDE_PROJECTION_RETRY_SECONDS` | `30` | Fake projection retry delay |
| `TVDE_PROJECTION_PROCESSING_TIMEOUT_SECONDS` | `300` | Recovery timeout for abandoned work |

## Persistence and recovery

- Migrations in `migrations/` are contiguous, checksummed and reapplied automatically.
- Unknown or changed applied migrations stop startup with `SchemaIncompatibleError`.
- Event, owner change, idempotency result and projection outbox item commit together.
- Nonces, cursors, registrations and idempotency survive restart.
- A failed fake projection returns to `PENDING`; an abandoned `PROCESSING` item is recovered after its timeout.
- SQLite is the local source of truth. The fake Google sink is never authoritative.
- Restore/rollback in this phase means stop the service and restore a consistent SQLite file plus its matching code/migrations. Do not copy a live WAL database without SQLite backup semantics.

Authoritative artifacts:

- `docs/ADR-001-secure-sync-api-v1.md`: decisions and protocol semantics.
- `docs/ADR-002-local-persistent-backend.md`: persistence, transactions and recovery.
- `docs/FILTER-COMPATIBILITY.md`: Android filter mapping and open limitations.
- `openapi.json`: machine-readable API surface.
- `src/tvde_contract/models.py`: executable DTO constraints.
- `src/tvde_contract/security.py`: canonical signature reference.
- `src/tvde_contract/harness.py`: in-memory executable specification.
- `src/tvde_contract/persistent.py`: persistent application service.
- `src/tvde_contract/sqlite_storage.py`: SQLite repositories and unit of work.
- `src/tvde_contract/projector.py`: fake outbox consumer and fake sink.
- `tests/`: contract and security proof matrix.

The harness and fake projector must never be deployed. Cloud work remains outside Phase 3B.2.
