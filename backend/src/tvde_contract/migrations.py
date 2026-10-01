from __future__ import annotations

import hashlib
import sqlite3
from dataclasses import dataclass
from pathlib import Path


class SchemaIncompatibleError(RuntimeError):
    pass


@dataclass(frozen=True)
class Migration:
    version: int
    name: str
    sql: str
    checksum: str


class MigrationRunner:
    def __init__(self, migrations_path: Path):
        self.migrations_path = migrations_path

    def discover(self) -> list[Migration]:
        migrations: list[Migration] = []
        for path in sorted(self.migrations_path.glob("[0-9][0-9][0-9]_*.sql")):
            version = int(path.name.split("_", 1)[0])
            sql = path.read_text(encoding="utf-8")
            migrations.append(
                Migration(version, path.name, sql, hashlib.sha256(sql.encode("utf-8")).hexdigest())
            )
        versions = [item.version for item in migrations]
        if not migrations or versions != list(range(1, len(migrations) + 1)):
            raise SchemaIncompatibleError("migrations must be contiguous and start at version 1")
        return migrations

    def apply(self, connection: sqlite3.Connection) -> None:
        migrations = self.discover()
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                checksum TEXT NOT NULL,
                applied_at INTEGER NOT NULL DEFAULT (unixepoch())
            )
            """
        )
        applied = {
            row[0]: (row[1], row[2])
            for row in connection.execute(
                "SELECT version, name, checksum FROM schema_migrations ORDER BY version"
            )
        }
        known_versions = {item.version for item in migrations}
        unknown = set(applied) - known_versions
        if unknown:
            raise SchemaIncompatibleError(f"database has unknown migration versions: {sorted(unknown)}")
        for migration in migrations:
            existing = applied.get(migration.version)
            if existing is not None:
                if existing != (migration.name, migration.checksum):
                    raise SchemaIncompatibleError(
                        f"migration {migration.version} differs from the applied schema"
                    )
                continue
            try:
                connection.executescript(
                    "BEGIN IMMEDIATE;\n"
                    + migration.sql
                    + "\nINSERT INTO schema_migrations(version, name, checksum) VALUES "
                    + f"({migration.version}, '{migration.name}', '{migration.checksum}');\n"
                    + f"PRAGMA user_version = {migration.version};\nCOMMIT;"
                )
            except Exception:
                if connection.in_transaction:
                    connection.rollback()
                raise
        current_version = connection.execute("PRAGMA user_version").fetchone()[0]
        if current_version != migrations[-1].version:
            raise SchemaIncompatibleError(
                f"database user_version {current_version} does not match {migrations[-1].version}"
            )
