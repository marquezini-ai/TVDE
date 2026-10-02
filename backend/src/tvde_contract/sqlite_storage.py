from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, TypeVar
from uuid import UUID

from .domain import IdempotentResponse, Installation, LicenseRecord, StoredCursor
from .migrations import MigrationRunner
from .models import EcPublicJwk, OfferEvent, Role
from .security import public_key_from_jwk


@dataclass(frozen=True)
class OutboxItem:
    outbox_id: int
    owner_id: str
    event_id: UUID
    event: OfferEvent
    attempts: int


T = TypeVar("T")


class SQLiteStorage:
    def __init__(self, database_path: Path, migrations_path: Path):
        self.database_path = database_path
        self.migrations_path = migrations_path
        self.lock = threading.RLock()
        database_path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            MigrationRunner(migrations_path).apply(connection)

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(
            self.database_path,
            timeout=30,
            isolation_level=None,
            check_same_thread=False,
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 30000")
        connection.execute("PRAGMA journal_mode = WAL")
        return connection

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                yield connection
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    def run_transaction(self, operation: Callable[[sqlite3.Connection], T]) -> T:
        with self.transaction() as connection:
            return operation(connection)

    def put_license(
        self,
        activation_key: str,
        role: str,
        expires_at: int | None,
        revoked: bool,
        now: int,
    ) -> None:
        with self.transaction() as connection:
            connection.execute(
                """
                INSERT INTO licenses(activation_key, role, expires_at, revoked, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(activation_key) DO UPDATE SET
                    role=excluded.role,
                    expires_at=excluded.expires_at,
                    revoked=excluded.revoked
                """,
                (activation_key, role, expires_at, int(revoked), now),
            )

    def get_license(
        self, activation_key: str, connection: sqlite3.Connection | None = None
    ) -> LicenseRecord | None:
        if connection is None:
            with self.connect() as owned:
                return self.get_license(activation_key, owned)
        row = connection.execute(
            "SELECT activation_key, role, expires_at, revoked FROM licenses WHERE activation_key = ?",
            (activation_key,),
        ).fetchone()
        if row is None:
            return None
        return LicenseRecord(row["activation_key"], Role(row["role"]), row["expires_at"], bool(row["revoked"]))

    def get_installation(
        self, installation_id: UUID, connection: sqlite3.Connection | None = None
    ) -> Installation | None:
        if connection is None:
            with self.connect() as owned:
                return self.get_installation(installation_id, owned)
        row = connection.execute(
            "SELECT * FROM installations WHERE installation_id = ?", (str(installation_id),)
        ).fetchone()
        return self._installation(row) if row else None

    def get_installation_for_license(
        self, activation_key: str, connection: sqlite3.Connection
    ) -> Installation | None:
        row = connection.execute(
            "SELECT * FROM installations WHERE activation_key = ?", (activation_key,)
        ).fetchone()
        return self._installation(row) if row else None

    @staticmethod
    def _installation(row: sqlite3.Row) -> Installation:
        jwk = EcPublicJwk.model_validate_json(row["public_jwk_json"])
        return Installation(
            installation_id=UUID(row["installation_id"]),
            owner_id=row["owner_id"],
            activation_key=row["activation_key"],
            public_key=public_key_from_jwk(jwk),
            key_thumbprint=row["key_thumbprint"],
            role=Role(row["role"]),
            revoked=bool(row["revoked"]),
        )

    def insert_installation(
        self,
        connection: sqlite3.Connection,
        installation: Installation,
        public_jwk: EcPublicJwk,
        now: int,
    ) -> None:
        connection.execute(
            """
            INSERT INTO installations(
                installation_id, owner_id, activation_key, public_jwk_json,
                key_thumbprint, role, revoked, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                str(installation.installation_id),
                installation.owner_id,
                installation.activation_key,
                public_jwk.model_dump_json(),
                installation.key_thumbprint,
                installation.role.value,
                int(installation.revoked),
                now,
            ),
        )

    def revoke_installation(self, installation_id: UUID) -> None:
        with self.transaction() as connection:
            connection.execute(
                "UPDATE installations SET revoked = 1 WHERE installation_id = ?",
                (str(installation_id),),
            )

    def consume_nonce(self, principal: str, nonce: str, expires_at: int, now: int) -> bool:
        with self.transaction() as connection:
            connection.execute("DELETE FROM nonces WHERE expires_at < ?", (now,))
            try:
                connection.execute(
                    "INSERT INTO nonces(principal, nonce, expires_at) VALUES (?, ?, ?)",
                    (principal, nonce, expires_at),
                )
            except sqlite3.IntegrityError:
                return False
        return True

    def get_idempotency(
        self,
        connection: sqlite3.Connection,
        endpoint: str,
        principal: str,
        key: str,
        now: int,
    ) -> IdempotentResponse | None:
        connection.execute("DELETE FROM idempotency_records WHERE expires_at < ?", (now,))
        row = connection.execute(
            """
            SELECT body_hash, response_status, response_json, expires_at
            FROM idempotency_records
            WHERE endpoint = ? AND principal = ? AND idempotency_key = ?
            """,
            (endpoint, principal, key),
        ).fetchone()
        if row is None:
            return None
        return IdempotentResponse(
            body_hash=row["body_hash"],
            status=row["response_status"],
            body=json.loads(row["response_json"]),
            expires_at=row["expires_at"],
        )

    def insert_idempotency(
        self,
        connection: sqlite3.Connection,
        endpoint: str,
        principal: str,
        key: str,
        body_hash: str,
        status: int,
        body: dict,
        expires_at: int,
        now: int,
    ) -> None:
        connection.execute(
            """
            INSERT INTO idempotency_records(
                endpoint, principal, idempotency_key, body_hash,
                response_status, response_json, expires_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                endpoint,
                principal,
                key,
                body_hash,
                status,
                json.dumps(body, separators=(",", ":"), sort_keys=True),
                expires_at,
                now,
            ),
        )

    def get_event(
        self, connection: sqlite3.Connection, owner_id: str, event_id: UUID
    ) -> tuple[str, OfferEvent, int] | None:
        row = connection.execute(
            """
            SELECT e.event_hash, e.event_json, c.sequence
            FROM events e JOIN changes c
              ON c.owner_id = e.owner_id AND c.event_id = e.event_id
            WHERE e.owner_id = ? AND e.event_id = ?
            """,
            (owner_id, str(event_id)),
        ).fetchone()
        if row is None:
            return None
        return row["event_hash"], OfferEvent.model_validate_json(row["event_json"]), row["sequence"]

    def insert_event_bundle(
        self,
        connection: sqlite3.Connection,
        owner_id: str,
        event: OfferEvent,
        event_hash: str,
        now: int,
    ) -> int:
        sequence = connection.execute(
            "SELECT COALESCE(MAX(sequence), 0) + 1 FROM changes WHERE owner_id = ?",
            (owner_id,),
        ).fetchone()[0]
        connection.execute(
            """
            INSERT INTO events(
                owner_id, event_id, event_hash, event_json, recorded_at_epoch_ms,
                platform, category, decision, trip_value_cents, net_trip_value_cents,
                value_per_km_cents, gross_value_per_km_cents, value_per_hour_cents,
                pickup_municipality, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                owner_id,
                str(event.event_id),
                event_hash,
                event.model_dump_json(),
                event.recorded_at_epoch_ms,
                event.platform.value,
                event.category,
                event.decision.value,
                event.trip_value_cents,
                event.net_trip_value_cents,
                event.value_per_km_cents,
                event.gross_value_per_km_cents,
                event.value_per_hour_cents,
                event.pickup_municipality,
                now,
            ),
        )
        connection.execute(
            "INSERT INTO changes(owner_id, sequence, event_id, created_at) VALUES (?, ?, ?, ?)",
            (owner_id, sequence, str(event.event_id), now),
        )
        connection.execute(
            """
            INSERT INTO projection_outbox(
                owner_id, event_id, state, available_at, created_at, updated_at
            ) VALUES (?, ?, 'PENDING', ?, ?, ?)
            """,
            (owner_id, str(event.event_id), now, now, now),
        )
        return sequence

    def list_changes(
        self,
        connection: sqlite3.Connection,
        owner_id: str,
        after_sequence: int,
        limit: int,
    ) -> tuple[list[tuple[int, OfferEvent]], bool]:
        rows = connection.execute(
            """
            SELECT c.sequence, e.event_json
            FROM changes c JOIN events e
              ON e.owner_id = c.owner_id AND e.event_id = c.event_id
            WHERE c.owner_id = ? AND c.sequence > ?
            ORDER BY c.sequence ASC
            LIMIT ?
            """,
            (owner_id, after_sequence, limit + 1),
        ).fetchall()
        has_more = len(rows) > limit
        rows = rows[:limit]
        return [(row["sequence"], OfferEvent.model_validate_json(row["event_json"])) for row in rows], has_more

    def get_cursor(self, connection: sqlite3.Connection, token: str) -> StoredCursor | None:
        row = connection.execute(
            "SELECT owner_id, sequence, expires_at FROM cursors WHERE token = ?", (token,)
        ).fetchone()
        if row is None:
            return None
        return StoredCursor(row["owner_id"], row["sequence"], row["expires_at"])

    def insert_cursor(
        self,
        connection: sqlite3.Connection,
        token: str,
        owner_id: str,
        sequence: int,
        expires_at: int,
        now: int,
    ) -> None:
        connection.execute(
            "INSERT INTO cursors(token, owner_id, sequence, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            (token, owner_id, sequence, expires_at, now),
        )

    def expire_cursor(self, token: str, now: int) -> None:
        with self.transaction() as connection:
            connection.execute("UPDATE cursors SET expires_at = ? WHERE token = ?", (now - 1, token))

    def aggregate_rows(self, connection: sqlite3.Connection) -> list[dict]:
        rows = connection.execute(
            """
            SELECT recorded_at_epoch_ms, platform, category, decision,
                   trip_value_cents, net_trip_value_cents, value_per_km_cents,
                   gross_value_per_km_cents, value_per_hour_cents, pickup_municipality
            FROM events
            """
        ).fetchall()
        return [dict(row) for row in rows]

    def count(self, table: str) -> int:
        allowed = {
            "licenses", "installations", "nonces", "idempotency_records",
            "events", "changes", "cursors", "projection_outbox",
        }
        if table not in allowed:
            raise ValueError("unsupported table")
        with self.connect() as connection:
            return connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]

    def claim_projection(self, now: int, processing_timeout_seconds: int) -> OutboxItem | None:
        with self.transaction() as connection:
            connection.execute(
                """
                UPDATE projection_outbox
                SET state = 'PENDING', processing_started_at = NULL, updated_at = ?
                WHERE state = 'PROCESSING' AND processing_started_at <= ?
                """,
                (now, now - processing_timeout_seconds),
            )
            row = connection.execute(
                """
                SELECT o.outbox_id, o.owner_id, o.event_id, o.attempts, e.event_json
                FROM projection_outbox o JOIN events e
                  ON e.owner_id = o.owner_id AND e.event_id = o.event_id
                WHERE o.state = 'PENDING' AND o.available_at <= ?
                ORDER BY o.outbox_id
                LIMIT 1
                """,
                (now,),
            ).fetchone()
            if row is None:
                return None
            updated = connection.execute(
                """
                UPDATE projection_outbox
                SET state = 'PROCESSING', attempts = attempts + 1,
                    processing_started_at = ?, updated_at = ?
                WHERE outbox_id = ? AND state = 'PENDING'
                """,
                (now, now, row["outbox_id"]),
            ).rowcount
            if updated != 1:
                return None
            return OutboxItem(
                outbox_id=row["outbox_id"],
                owner_id=row["owner_id"],
                event_id=UUID(row["event_id"]),
                event=OfferEvent.model_validate_json(row["event_json"]),
                attempts=row["attempts"] + 1,
            )

    def complete_projection(self, outbox_id: int, now: int) -> None:
        with self.transaction() as connection:
            connection.execute(
                """
                UPDATE projection_outbox
                SET state = 'DONE', processing_started_at = NULL,
                    last_error_code = NULL, updated_at = ?
                WHERE outbox_id = ? AND state = 'PROCESSING'
                """,
                (now, outbox_id),
            )

    def fail_projection(self, outbox_id: int, now: int, retry_seconds: int, error_code: str) -> None:
        with self.transaction() as connection:
            connection.execute(
                """
                UPDATE projection_outbox
                SET state = 'PENDING', available_at = ?, processing_started_at = NULL,
                    last_error_code = ?, updated_at = ?
                WHERE outbox_id = ? AND state = 'PROCESSING'
                """,
                (now + retry_seconds, error_code[:80], now, outbox_id),
            )

    def projection_row(self, event_id: UUID) -> dict | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT * FROM projection_outbox WHERE event_id = ?", (str(event_id),)
            ).fetchone()
            return dict(row) if row else None
