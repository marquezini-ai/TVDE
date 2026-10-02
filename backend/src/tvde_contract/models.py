from __future__ import annotations

from datetime import date
from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from .limits import LIMITS


StrictText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
Address = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=LIMITS.max_address_chars)]
Category = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=LIMITS.max_category_chars)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class Role(StrEnum):
    CLIENT = "CLIENT"
    ADMIN = "ADMIN"


class Platform(StrEnum):
    UBER = "UBER"
    BOLT = "BOLT"


class Decision(StrEnum):
    ACEITAR = "ACEITAR"
    ANALISAR = "ANALISAR"
    REJEITAR = "REJEITAR"


class Criterion(StrEnum):
    RECOLHA = "RECOLHA"
    KM = "KM"
    HORA = "HORA"
    VIAGEM_LONGA = "VIAGEM_LONGA"
    VALOR_MINIMO = "VALOR_MINIMO"


class Metric(StrEnum):
    VALUE_PER_KM = "VALUE_PER_KM"
    VALUE_PER_HOUR = "VALUE_PER_HOUR"
    TRIP_VALUE = "TRIP_VALUE"
    NET_TRIP_VALUE = "NET_TRIP_VALUE"


class ValueMode(StrEnum):
    FREE = "FREE"
    GROSS = "GROSS"


class Shift(StrEnum):
    ALL = "ALL"
    MORNING = "MORNING"
    AFTERNOON = "AFTERNOON"
    NIGHT = "NIGHT"
    DAWN = "DAWN"


class CardColor(StrEnum):
    ALL = "ALL"
    GREEN = "GREEN"
    YELLOW = "YELLOW"
    RED = "RED"


class EcPublicJwk(StrictModel):
    kty: Literal["EC"]
    crv: Literal["P-256"]
    x: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{43}$")]
    y: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{43}$")]


class ClientDescriptor(StrictModel):
    app_id: Annotated[str, StringConstraints(pattern=r"^[A-Za-z][A-Za-z0-9_.]{2,149}$")]
    app_version: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=40)]
    api_version: Literal[1] = 1


class RegistrationRequest(StrictModel):
    activation_key: Annotated[str, StringConstraints(strip_whitespace=True, min_length=20, max_length=4096)]
    device_public_key: EcPublicJwk
    client: ClientDescriptor


class SyncPolicy(StrictModel):
    max_events_per_sync: int
    max_request_bytes: int
    timestamp_skew_seconds: int
    nonce_retention_seconds: int
    request_idempotency_retention_seconds: int
    cursor_retention_seconds: int
    own_changes_page_size: int
    sync_requests_per_minute: int
    sync_burst: int
    worker_attempts_per_run: int
    max_event_future_skew_seconds: int


class RegistrationResponse(StrictModel):
    installation_id: UUID
    role: Role
    policy_version: int = Field(ge=1)
    server_time: int = Field(ge=0, description="Unix epoch seconds")
    sync_policy: SyncPolicy


class ClientLicenseIssueRequest(StrictModel):
    android_id: Annotated[str, StringConstraints(strip_whitespace=True, to_lower=True, pattern=r"^[a-f0-9]{8,64}$")]
    expires_at_epoch_ms: int = Field(gt=0)
    license_type: Literal["CUSTOM"] = "CUSTOM"


class ClientLicenseIssueResponse(StrictModel):
    activation_key: Annotated[str, StringConstraints(min_length=20, max_length=4096)]
    android_id: str
    expires_at_epoch_ms: int = Field(gt=0)
    license_type: Literal["CUSTOM"] = "CUSTOM"
    server_time: int = Field(ge=0)


class CriterionDecision(StrictModel):
    criterion: Criterion
    decision: Decision


class OfferEvent(StrictModel):
    event_id: UUID
    recorded_at_epoch_ms: int = Field(ge=0)
    platform: Platform
    category: Category | None = None
    decision: Decision
    trip_value_cents: int = Field(ge=0, le=10_000_000)
    net_trip_value_cents: int | None = Field(default=None, ge=0, le=10_000_000)
    toll_amount_cents: int = Field(default=0, ge=0, le=1_000_000)
    value_per_km_cents: int = Field(ge=0, le=1_000_000)
    gross_value_per_km_cents: int = Field(ge=0, le=1_000_000)
    value_per_hour_cents: int = Field(ge=0, le=10_000_000)
    pickup_distance_meters: int | None = Field(default=None, ge=0, le=2_000_000)
    pickup_duration_seconds: int | None = Field(default=None, ge=0, le=172_800)
    destination_distance_meters: int | None = Field(default=None, ge=0, le=2_000_000)
    destination_duration_seconds: int | None = Field(default=None, ge=0, le=172_800)
    pickup_address: Address | None = None
    pickup_municipality: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=120)] | None = None
    destination_address: Address | None = None
    current_location_address: Address | None = None
    current_latitude_microdegrees: int | None = Field(default=None, ge=-90_000_000, le=90_000_000)
    current_longitude_microdegrees: int | None = Field(default=None, ge=-180_000_000, le=180_000_000)
    vehicle_cost_applied: bool = False
    active_criteria: list[Criterion] = Field(default_factory=list, max_length=5)
    criterion_decisions: list[CriterionDecision] = Field(default_factory=list, max_length=5)
    stop_rejection: bool = False

    @model_validator(mode="after")
    def validate_unique_criteria(self) -> OfferEvent:
        if len(set(self.active_criteria)) != len(self.active_criteria):
            raise ValueError("active_criteria must not contain duplicates")
        decision_criteria = [item.criterion for item in self.criterion_decisions]
        if len(set(decision_criteria)) != len(decision_criteria):
            raise ValueError("criterion_decisions must not contain duplicate criteria")
        return self


class CategoryFilter(StrictModel):
    platform: Platform
    name: Category


class AggregateQuery(StrictModel):
    platforms: list[Platform] = Field(min_length=1, max_length=2)
    metric: Metric
    value_mode: ValueMode = ValueMode.FREE
    shift: Shift = Shift.ALL
    card_color: CardColor = CardColor.ALL
    category: CategoryFilter | None = None
    start_date: date
    end_date: date

    @model_validator(mode="after")
    def validate_query(self) -> AggregateQuery:
        if len(set(self.platforms)) != len(self.platforms):
            raise ValueError("platforms must not contain duplicates")
        if self.end_date < self.start_date:
            raise ValueError("end_date must not precede start_date")
        if (self.end_date - self.start_date).days > 365:
            raise ValueError("aggregate date range must not exceed 366 inclusive days")
        if self.category is not None and self.category.platform not in self.platforms:
            raise ValueError("category platform must be included in platforms")
        return self


class SyncRequest(StrictModel):
    cursor: Annotated[str, StringConstraints(min_length=1, max_length=2048)] | None = None
    events: list[OfferEvent] = Field(default_factory=list, max_length=LIMITS.max_events_per_sync)
    aggregate_query: AggregateQuery


class EventStatus(StrEnum):
    ACCEPTED = "ACCEPTED"
    DUPLICATE = "DUPLICATE"
    REJECTED = "REJECTED"


class EventResult(StrictModel):
    event_id: UUID
    status: EventStatus
    code: str | None = None


class OwnChange(StrictModel):
    sequence: int = Field(ge=1)
    operation: Literal["UPSERT"] = "UPSERT"
    event: OfferEvent


class PickupMunicipalityAggregate(StrictModel):
    municipality: Annotated[str, StringConstraints(min_length=1, max_length=120)]
    median_cents: int = Field(ge=0)
    event_count: int = Field(ge=1)


class HeatmapAggregate(StrictModel):
    day_of_week: Literal["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
    shift: Literal["MORNING", "AFTERNOON", "NIGHT", "DAWN"]
    median_cents: int | None = Field(default=None, ge=0)
    event_count: int = Field(ge=0)


class DailyAggregate(StrictModel):
    date: date
    average_cents: int | None = Field(default=None, ge=0)
    event_count: int = Field(ge=0)


class GlobalAggregates(StrictModel):
    query: AggregateQuery
    pickup_municipalities: list[PickupMunicipalityAggregate] = Field(max_length=5)
    heatmap: list[HeatmapAggregate] = Field(max_length=28)
    daily_calendar: list[DailyAggregate] = Field(max_length=30)
    recorded_dates: list[date] = Field(max_length=366)
    generated_at: int = Field(ge=0, description="Unix epoch seconds")


class SyncResponse(StrictModel):
    event_results: list[EventResult]
    own_changes: list[OwnChange]
    next_cursor: str
    has_more: bool
    global_aggregates: GlobalAggregates
    aggregate_version: int = Field(ge=1)
    policy_version: int = Field(ge=1)
    server_time: int = Field(ge=0, description="Unix epoch seconds")


class ErrorBody(StrictModel):
    code: str
    message: str
    retryable: bool
    request_id: str
    server_time: int = Field(ge=0)


class ErrorEnvelope(StrictModel):
    error: ErrorBody
