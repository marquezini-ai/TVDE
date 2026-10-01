# ADR-002 — Local persistent backend

- Status: accepted for Phase 3B.2 local validation
- Contract: API v1 from ADR-001, unchanged
- Storage: SQLite, backend-only
- Cloud/Android: explicitly out of scope

## Decision

Use a layered local implementation:

```text
HTTP API -> application service -> repository interfaces -> SQLite
                                             -> projection outbox -> fake sink
```

`ContractHarness` remains the fast in-memory executable specification. `PersistentBackend` implements the same application boundary without inheriting memory storage. Domain records live in `domain.py`; replaceable repository contracts live in `ports.py`; `SQLiteStorage` is the Phase 3B.2 adapter.

## Schema

| Table | Purpose | Critical constraints |
|---|---|---|
| `schema_migrations` | Applied migration identity/checksum | Unique version |
| `licenses` | Synthetic local licensing state | Activation key primary key, server role |
| `installations` | Public key and owner binding | Installation primary key, unique license |
| `nonces` | Persistent replay prevention | Unique principal + nonce |
| `idempotency_records` | Exact request result | Unique endpoint + principal + key |
| `events` | Source-of-truth accepted event | Unique owner + event ID |
| `changes` | Monotonic owner delta | Unique owner + sequence/event |
| `cursors` | Opaque persistent cursor | Token primary key and owner binding |
| `projection_outbox` | Reliable downstream work | Unique owner + event, state constraint |

Migration files are immutable and checksummed. Startup rejects gaps, modified applied migrations and unknown future versions. Tests create databases from zero and reopen them.

## Atomic sync

SQLite uses `BEGIN IMMEDIATE`, foreign keys and unique constraints. One sync transaction performs:

1. idempotency lookup;
2. event conflict/deduplication;
3. accepted event insert;
4. owner change sequence insert;
5. projection outbox insert;
6. cursor creation;
7. aggregate read;
8. exact idempotency response insert;
9. commit.

Any exception before commit rolls back all these writes. A failure after commit is recovered by sending the same body and idempotency key with a new nonce; the stored response is returned exactly.

## Replay and concurrency

Nonce consumption is a separate atomic transaction after signature verification. It intentionally remains consumed if later business processing fails; a retry must use a new nonce. SQLite uniqueness is the final concurrency guard for nonces, events, changes, outbox work and idempotency keys. A process lock reduces local contention but is not the correctness boundary.

## Cursor

Cursor tokens remain opaque and stored with owner, sequence and expiry. Pages are ordered by owner sequence. New events created between pages appear after the stored sequence. Tokens cannot be used by another owner and survive process restart.

## Aggregates

Aggregates are calculated from persisted events across owners while raw `own_changes` remain owner-scoped. Filtering follows Android rules for platform, date range, shift, card color, category, metric and free/gross value mode. Shift boundaries are 00–05, 06–11, 12–17 and 18–23 in the configured timezone.

Input values are integer cents. Because API v1 aggregate outputs are also integer cents, half-cent medians and fractional-cent averages are rounded to the nearest cent with `ROUND_HALF_UP`. This is deterministic but cannot represent the Android `Double` result at sub-cent precision; changing this would require an explicit contract decision.

## Projection outbox

Accepted events create one `PENDING` outbox item in the same transaction. The fake worker atomically claims it as `PROCESSING`, then marks it `DONE`. Unavailability returns it to `PENDING` with a retry time. Abandoned `PROCESSING` rows are recovered after the configured timeout. The fake sink deduplicates by owner + event ID, modelling the idempotency required from a future Google projection.

Projection failure never changes or deletes the source event. SQLite, not Google, is authoritative.

## Structured logging

Logs are JSON and may include request method/path/status/latency, hashed principal, safe counts, attempt and safe error code. Keys containing activation key, signature, token, key, address, coordinate, payload or OCR are removed. Full bodies and credentials are never logged.

## Alternatives

- SQLAlchemy/Alembic: unnecessary for the current single SQLite adapter and one migration.
- Materialized aggregates: unnecessary at current scale; direct reads simplify correctness.
- Persisted rate limiting: not required for this local phase; nonce/idempotency correctness is persistent.
- Real Google projection: forbidden in Phase 3B.2.

## Limitations and next boundary

This implementation is not cloud-ready operationally. SQLite transactions prove behavior, but a cloud store must reproduce its unique constraints and atomic boundaries. Decisions on retention, aggregate privacy thresholds, key recovery, first-binding protection and production storage remain open. Phase 3B.3 requires separate authorization.
