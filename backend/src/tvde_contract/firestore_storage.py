from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Callable, TypeVar
from uuid import UUID

from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter

from .domain import IdempotentResponse, Installation, LicenseRecord, StoredCursor
from .migrations import SchemaIncompatibleError
from .models import EcPublicJwk, OfferEvent, Role
from .security import public_key_from_jwk


T = TypeVar("T")
SCHEMA_VERSION = 1


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _license_id(activation_key_or_reference: str) -> str:
    prefix = "sha256:"
    if activation_key_or_reference.startswith(prefix):
        return activation_key_or_reference[len(prefix) :]
    return _digest(activation_key_or_reference)


def _idempotency_id(endpoint: str, principal: str, key: str) -> str:
    return _digest(f"{endpoint}\0{principal}\0{key}")


def _event_id(owner_id: str, event_id: UUID) -> str:
    return _digest(f"{owner_id}\0{event_id}")


def _rate_limit_id(scope: str, principal: str, window_start: int) -> str:
    return _digest(f"{scope}\0{principal}\0{window_start}")


@dataclass(frozen=True)
class CloudOutboxItem:
    document_id: str
    owner_id: str
    event_id: UUID
    event: OfferEvent
    attempts: int
    target_row: int


class FirestoreUnitOfWork:
    def __init__(self, storage: FirestoreStorage, transaction):
        self.storage = storage
        self.transaction = transaction
        self.pending_writes: list[tuple[str, object, dict]] = []
        self.pending_events: dict[tuple[str, UUID], tuple[str, OfferEvent, int]] = {}
        self.pending_changes: dict[str, list[tuple[int, OfferEvent]]] = {}
        self.owner_sequences: dict[str, int] = {}
        self.projection_next_row: int | None = None
        self.metadata_now: int | None = None

    def set(self, reference, data: dict) -> None:
        self.pending_writes.append(("set", reference, data))

    def create(self, reference, data: dict) -> None:
        self.pending_writes.append(("create", reference, data))

    def flush(self) -> None:
        self.storage._finalize_transaction_metadata(self, self.metadata_now or 0)
        for operation, reference, data in self.pending_writes:
            if operation == "create":
                self.transaction.create(reference, data)
            else:
                self.transaction.set(reference, data)


class FirestoreStorage:
    """Firestore adapter with the same unit-of-work surface as SQLiteStorage."""

    def __init__(self, project_id: str, database_id: str = "(default)", *, client=None):
        self.project_id = project_id
        self.database_id = database_id
        self.client = client or firestore.Client(project=project_id, database=database_id)

    def initialize_schema(self, now: int) -> None:
        reference = self.client.collection("_meta").document("backend")

        @firestore.transactional
        def initialize(transaction):
            snapshot = reference.get(transaction=transaction)
            if snapshot.exists:
                version = snapshot.to_dict().get("schema_version")
                if version != SCHEMA_VERSION:
                    raise SchemaIncompatibleError(
                        f"Firestore schema {version} does not match {SCHEMA_VERSION}"
                    )
                return
            transaction.create(
                reference,
                {"schema_version": SCHEMA_VERSION, "created_at": now, "updated_at": now},
            )

        initialize(self.client.transaction(max_attempts=5))

    def assert_schema(self) -> None:
        snapshot = self.client.collection("_meta").document("backend").get()
        if not snapshot.exists:
            raise SchemaIncompatibleError("Firestore backend schema is not initialized")
        version = snapshot.to_dict().get("schema_version")
        if version != SCHEMA_VERSION:
            raise SchemaIncompatibleError(
                f"Firestore schema {version} does not match {SCHEMA_VERSION}"
            )

    def run_transaction(self, operation: Callable[[FirestoreUnitOfWork], T]) -> T:
        @firestore.transactional
        def execute(transaction):
            unit = FirestoreUnitOfWork(self, transaction)
            result = operation(unit)
            unit.flush()
            return result

        return execute(self.client.transaction(max_attempts=5))

    def consume_rate_limit(
        self,
        scope: str,
        principal: str,
        *,
        maximum: int,
        window_seconds: int,
        now: int,
    ) -> bool:
        """Atomically enforce a fixed window shared by every Cloud Run instance."""
        window_start = now - (now % window_seconds)
        reference = self.client.collection("rate_limits").document(
            _rate_limit_id(scope, principal, window_start)
        )

        @firestore.transactional
        def consume(transaction):
            snapshot = reference.get(transaction=transaction)
            count = int(snapshot.to_dict().get("count", 0)) if snapshot.exists else 0
            if count >= maximum:
                return False
            transaction.set(
                reference,
                {
                    "scope": scope,
                    "principal_hash": _digest(principal),
                    "window_start": window_start,
                    "expires_at": window_start + (2 * window_seconds),
                    "count": count + 1,
                    "updated_at": now,
                },
            )
            return True

        return consume(self.client.transaction(max_attempts=5))

    def put_license(
        self,
        activation_key: str,
        role: str,
        expires_at: int | None,
        revoked: bool,
        now: int,
    ) -> None:
        self.client.collection("licenses").document(_license_id(activation_key)).set(
            {
                "role": role,
                "expires_at": expires_at,
                "revoked": revoked,
                "created_at": now,
                "updated_at": now,
            },
            merge=True,
        )

    def get_license(
        self, activation_key: str, connection: FirestoreUnitOfWork | None = None
    ) -> LicenseRecord | None:
        reference = self.client.collection("licenses").document(_license_id(activation_key))
        snapshot = (
            reference.get(transaction=connection.transaction)
            if connection is not None
            else reference.get()
        )
        if not snapshot.exists:
            return None
        data = snapshot.to_dict()
        return LicenseRecord(
            activation_key=activation_key,
            role=Role(data["role"]),
            expires_at=data.get("expires_at"),
            revoked=bool(data.get("revoked", False)),
        )

    def get_installation(
        self, installation_id: UUID, connection: FirestoreUnitOfWork | None = None
    ) -> Installation | None:
        reference = self.client.collection("installations").document(str(installation_id))
        snapshot = (
            reference.get(transaction=connection.transaction)
            if connection is not None
            else reference.get()
        )
        return self._installation(snapshot) if snapshot.exists else None

    def get_installation_for_license(
        self, activation_key: str, connection: FirestoreUnitOfWork
    ) -> Installation | None:
        license_id = _license_id(activation_key)
        binding = self.client.collection("license_bindings").document(license_id).get(
            transaction=connection.transaction
        )
        if not binding.exists:
            return None
        return self.get_installation(UUID(binding.to_dict()["installation_id"]), connection)

    @staticmethod
    def _installation(snapshot) -> Installation:
        data = snapshot.to_dict()
        jwk = EcPublicJwk.model_validate(data["public_jwk"])
        return Installation(
            installation_id=UUID(snapshot.id),
            owner_id=data["owner_id"],
            activation_key=f"sha256:{data['license_id']}",
            public_key=public_key_from_jwk(jwk),
            key_thumbprint=data["key_thumbprint"],
            role=Role(data["role"]),
            revoked=bool(data.get("revoked", False)),
        )

    def insert_installation(
        self,
        connection: FirestoreUnitOfWork,
        installation: Installation,
        public_jwk: EcPublicJwk,
        now: int,
    ) -> None:
        license_id = _license_id(installation.activation_key)
        reference = self.client.collection("installations").document(
            str(installation.installation_id)
        )
        connection.create(
            reference,
            {
                "owner_id": installation.owner_id,
                "license_id": license_id,
                "public_jwk": public_jwk.model_dump(mode="json"),
                "key_thumbprint": installation.key_thumbprint,
                "role": installation.role.value,
                "revoked": installation.revoked,
                "created_at": now,
            },
        )
        connection.create(
            self.client.collection("license_bindings").document(license_id),
            {"installation_id": str(installation.installation_id), "created_at": now},
        )

    def revoke_installation(self, installation_id: UUID) -> None:
        self.client.collection("installations").document(str(installation_id)).update(
            {"revoked": True}
        )

    def consume_nonce(self, principal: str, nonce: str, expires_at: int, now: int) -> bool:
        reference = self.client.collection("nonces").document(_digest(f"{principal}\0{nonce}"))

        @firestore.transactional
        def consume(transaction):
            snapshot = reference.get(transaction=transaction)
            if snapshot.exists and snapshot.to_dict().get("expires_at", 0) >= now:
                return False
            transaction.set(
                reference,
                {"principal_hash": _digest(principal), "expires_at": expires_at, "created_at": now},
            )
            return True

        return consume(self.client.transaction(max_attempts=5))

    def get_idempotency(
        self,
        connection: FirestoreUnitOfWork,
        endpoint: str,
        principal: str,
        key: str,
        now: int,
    ) -> IdempotentResponse | None:
        reference = self.client.collection("idempotency").document(
            _idempotency_id(endpoint, principal, key)
        )
        snapshot = reference.get(transaction=connection.transaction)
        if not snapshot.exists:
            return None
        data = snapshot.to_dict()
        if data["expires_at"] < now:
            return None
        return IdempotentResponse(
            body_hash=data["body_hash"],
            status=data["response_status"],
            body=data["response"],
            expires_at=data["expires_at"],
        )

    def insert_idempotency(
        self,
        connection: FirestoreUnitOfWork,
        endpoint: str,
        principal: str,
        key: str,
        body_hash: str,
        status: int,
        body: dict,
        expires_at: int,
        now: int,
    ) -> None:
        reference = self.client.collection("idempotency").document(
            _idempotency_id(endpoint, principal, key)
        )
        connection.set(
            reference,
            {
                "endpoint": endpoint,
                "principal_hash": _digest(principal),
                "key_hash": _digest(key),
                "body_hash": body_hash,
                "response_status": status,
                "response": body,
                "expires_at": expires_at,
                "created_at": now,
            },
        )

    def get_event(
        self, connection: FirestoreUnitOfWork, owner_id: str, event_id: UUID
    ) -> tuple[str, OfferEvent, int] | None:
        pending = connection.pending_events.get((owner_id, event_id))
        if pending is not None:
            return pending
        reference = self.client.collection("events").document(_event_id(owner_id, event_id))
        snapshot = reference.get(transaction=connection.transaction)
        if not snapshot.exists:
            return None
        data = snapshot.to_dict()
        return data["event_hash"], OfferEvent.model_validate(data["event"]), data["sequence"]

    def _next_sequence(self, connection: FirestoreUnitOfWork, owner_id: str) -> int:
        if owner_id not in connection.owner_sequences:
            reference = self.client.collection("owners").document(owner_id)
            snapshot = reference.get(transaction=connection.transaction)
            connection.owner_sequences[owner_id] = (
                int(snapshot.to_dict().get("last_sequence", 0)) if snapshot.exists else 0
            )
        connection.owner_sequences[owner_id] += 1
        return connection.owner_sequences[owner_id]

    def _next_projection_row(self, connection: FirestoreUnitOfWork) -> int:
        if connection.projection_next_row is None:
            reference = self.client.collection("_meta").document("projection")
            snapshot = reference.get(transaction=connection.transaction)
            connection.projection_next_row = (
                int(snapshot.to_dict().get("last_row", 1)) if snapshot.exists else 1
            )
        connection.projection_next_row += 1
        return connection.projection_next_row

    def insert_event_bundle(
        self,
        connection: FirestoreUnitOfWork,
        owner_id: str,
        event: OfferEvent,
        event_hash: str,
        now: int,
    ) -> int:
        sequence = self._next_sequence(connection, owner_id)
        target_row = self._next_projection_row(connection)
        event_document_id = _event_id(owner_id, event.event_id)
        event_data = {
            "owner_id": owner_id,
            "event_id": str(event.event_id),
            "event_hash": event_hash,
            "event": event.model_dump(mode="json"),
            "sequence": sequence,
            "recorded_at_epoch_ms": event.recorded_at_epoch_ms,
            "platform": event.platform.value,
            "category": event.category,
            "decision": event.decision.value,
            "trip_value_cents": event.trip_value_cents,
            "net_trip_value_cents": event.net_trip_value_cents,
            "value_per_km_cents": event.value_per_km_cents,
            "gross_value_per_km_cents": event.gross_value_per_km_cents,
            "value_per_hour_cents": event.value_per_hour_cents,
            "pickup_municipality": event.pickup_municipality,
            "created_at": now,
        }
        connection.create(self.client.collection("events").document(event_document_id), event_data)
        connection.create(
            self.client.collection("owners").document(owner_id).collection("changes").document(
                f"{sequence:020d}"
            ),
            {"sequence": sequence, "event": event.model_dump(mode="json"), "created_at": now},
        )
        connection.create(
            self.client.collection("projection_outbox").document(event_document_id),
            {
                "owner_id": owner_id,
                "event_id": str(event.event_id),
                "event": event.model_dump(mode="json"),
                "target_row": target_row,
                "state": "PENDING",
                "attempts": 0,
                "available_at": now,
                "processing_started_at": None,
                "last_error_code": None,
                "created_at": now,
                "updated_at": now,
            },
        )
        connection.pending_events[(owner_id, event.event_id)] = (event_hash, event, sequence)
        connection.pending_changes.setdefault(owner_id, []).append((sequence, event))
        connection.metadata_now = now
        return sequence

    def list_changes(
        self,
        connection: FirestoreUnitOfWork,
        owner_id: str,
        after_sequence: int,
        limit: int,
    ) -> tuple[list[tuple[int, OfferEvent]], bool]:
        query = (
            self.client.collection("owners")
            .document(owner_id)
            .collection("changes")
            .where(filter=FieldFilter("sequence", ">", after_sequence))
            .order_by("sequence")
            .limit(limit + 1)
        )
        rows = [
            (data["sequence"], OfferEvent.model_validate(data["event"]))
            for snapshot in query.stream(transaction=connection.transaction)
            for data in [snapshot.to_dict()]
        ]
        rows.extend(
            item
            for item in connection.pending_changes.get(owner_id, [])
            if item[0] > after_sequence
        )
        rows.sort(key=lambda item: item[0])
        has_more = len(rows) > limit
        return rows[:limit], has_more

    def get_cursor(
        self, connection: FirestoreUnitOfWork, token: str
    ) -> StoredCursor | None:
        snapshot = self.client.collection("cursors").document(_digest(token)).get(
            transaction=connection.transaction
        )
        if not snapshot.exists:
            return None
        data = snapshot.to_dict()
        return StoredCursor(data["owner_id"], data["sequence"], data["expires_at"])

    def insert_cursor(
        self,
        connection: FirestoreUnitOfWork,
        token: str,
        owner_id: str,
        sequence: int,
        expires_at: int,
        now: int,
    ) -> None:
        connection.create(
            self.client.collection("cursors").document(_digest(token)),
            {
                "owner_id": owner_id,
                "sequence": sequence,
                "expires_at": expires_at,
                "created_at": now,
            },
        )

    def expire_cursor(self, token: str, now: int) -> None:
        self.client.collection("cursors").document(_digest(token)).update(
            {"expires_at": now - 1}
        )

    def aggregate_rows(self, connection: FirestoreUnitOfWork) -> list[dict]:
        rows = [
            self._aggregate_row(snapshot.to_dict())
            for snapshot in self.client.collection("events").stream(
                transaction=connection.transaction
            )
        ]
        for _, event, _ in connection.pending_events.values():
            rows.append(self._aggregate_event(event))
        return rows

    @staticmethod
    def _aggregate_row(data: dict) -> dict:
        return {
            "recorded_at_epoch_ms": data["recorded_at_epoch_ms"],
            "platform": data["platform"],
            "category": data.get("category"),
            "decision": data["decision"],
            "trip_value_cents": data["trip_value_cents"],
            "net_trip_value_cents": data.get("net_trip_value_cents"),
            "value_per_km_cents": data["value_per_km_cents"],
            "gross_value_per_km_cents": data["gross_value_per_km_cents"],
            "value_per_hour_cents": data["value_per_hour_cents"],
            "pickup_municipality": data.get("pickup_municipality"),
        }

    @staticmethod
    def _aggregate_event(event: OfferEvent) -> dict:
        return {
            "recorded_at_epoch_ms": event.recorded_at_epoch_ms,
            "platform": event.platform.value,
            "category": event.category,
            "decision": event.decision.value,
            "trip_value_cents": event.trip_value_cents,
            "net_trip_value_cents": event.net_trip_value_cents,
            "value_per_km_cents": event.value_per_km_cents,
            "gross_value_per_km_cents": event.gross_value_per_km_cents,
            "value_per_hour_cents": event.value_per_hour_cents,
            "pickup_municipality": event.pickup_municipality,
        }

    def _finalize_transaction_metadata(self, connection: FirestoreUnitOfWork, now: int) -> None:
        for owner_id, sequence in connection.owner_sequences.items():
            connection.set(
                self.client.collection("owners").document(owner_id),
                {"last_sequence": sequence, "updated_at": now},
            )
        if connection.projection_next_row is not None:
            connection.set(
                self.client.collection("_meta").document("projection"),
                {"last_row": connection.projection_next_row, "updated_at": now},
            )

    def claim_projection(self, now: int, processing_timeout_seconds: int) -> CloudOutboxItem | None:
        candidates = sorted(
            (
                snapshot
                for snapshot in self.client.collection("projection_outbox").stream()
                if self._projection_is_available(snapshot.to_dict(), now, processing_timeout_seconds)
            ),
            key=lambda snapshot: (snapshot.to_dict().get("available_at", 0), snapshot.id),
        )
        for candidate in candidates:
            reference = candidate.reference

            @firestore.transactional
            def claim(transaction):
                snapshot = reference.get(transaction=transaction)
                if not snapshot.exists:
                    return None
                data = snapshot.to_dict()
                if not self._projection_is_available(data, now, processing_timeout_seconds):
                    return None
                attempts = int(data.get("attempts", 0)) + 1
                transaction.update(
                    reference,
                    {
                        "state": "PROCESSING",
                        "attempts": attempts,
                        "processing_started_at": now,
                        "updated_at": now,
                    },
                )
                return CloudOutboxItem(
                    document_id=snapshot.id,
                    owner_id=data["owner_id"],
                    event_id=UUID(data["event_id"]),
                    event=OfferEvent.model_validate(data["event"]),
                    attempts=attempts,
                    target_row=data["target_row"],
                )

            claimed = claim(self.client.transaction(max_attempts=5))
            if claimed is not None:
                return claimed
        return None

    @staticmethod
    def _projection_is_available(data: dict, now: int, timeout: int) -> bool:
        if data.get("state") == "PENDING":
            return data.get("available_at", 0) <= now
        return (
            data.get("state") == "PROCESSING"
            and data.get("processing_started_at") is not None
            and data["processing_started_at"] <= now - timeout
        )

    def complete_projection(self, document_id: str, now: int) -> None:
        self.client.collection("projection_outbox").document(document_id).update(
            {
                "state": "DONE",
                "processing_started_at": None,
                "last_error_code": None,
                "updated_at": now,
            }
        )

    def fail_projection(
        self, document_id: str, now: int, retry_seconds: int, error_code: str
    ) -> None:
        self.client.collection("projection_outbox").document(document_id).update(
            {
                "state": "PENDING",
                "available_at": now + retry_seconds,
                "processing_started_at": None,
                "last_error_code": error_code[:80],
                "updated_at": now,
            }
        )

    def projection_row(self, event_id: UUID) -> dict | None:
        snapshots = list(
            self.client.collection("projection_outbox")
            .where(filter=FieldFilter("event_id", "==", str(event_id)))
            .limit(1)
            .stream()
        )
        return snapshots[0].to_dict() if snapshots else None
