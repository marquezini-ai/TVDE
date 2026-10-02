# ADR-003: Firestore for the cloud test backend

Status: Accepted for the test environment

Date: 2026-10-01

## Context

The backend needs durable idempotency, replay protection, cursors, owner-local sequences, concurrent event ingestion and a projection outbox. Current volume is low and the test environment should scale to zero with little operational work.

## Options

| Criterion | Firestore Standard, Native mode | Cloud SQL for PostgreSQL |
|---|---|---|
| Transactions/concurrency | Document transactions with automatic retry | Strong relational transactions and constraints |
| Idempotency | Deterministic document IDs and transaction creates | Unique constraints |
| Monotonic owner sequence | Transactional owner counter | Sequence/row lock |
| Schema migrations | Application schema version; additive document changes | Explicit SQL migrations |
| Idle cost/maintenance | Serverless; free allowance available | Instance remains provisioned; patching and connections require more work |
| Cold start | No database instance startup | Connection establishment/pooling required |
| Backup/recovery | Managed export; PITR is optional and billed | Automated backups/PITR available and billed |
| Fit for current volume | Good | Technically stronger but disproportionate |

## Decision

Use Firestore Standard in Native mode, database `(default)`, region `europe-southwest1`, for the cloud test environment. Keep persistence behind the existing storage surface; HTTP and domain code do not import Firestore.

Atomic Firestore transactions protect idempotency, event creation, owner sequence, cursor, outbox row allocation and distributed rate limits. Deterministic document IDs prevent duplicate events. Google Sheets is only a projection.

## Consequences

- Aggregate queries currently scan cloud-test events in a transaction. This is acceptable only for the current low-volume test; production requires measured limits or materialized aggregates.
- Firestore does not provide relational constraints. The adapter enforces invariants through deterministic keys and transactions.
- Expired nonce, idempotency, cursor and rate-limit documents need a documented retention/TTL policy before production.
- Cloud SQL remains the migration option if measured production volume or reporting queries exceed this design.

