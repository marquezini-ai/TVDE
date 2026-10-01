from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from statistics import median
from zoneinfo import ZoneInfo

from .models import (
    AggregateQuery,
    CardColor,
    DailyAggregate,
    GlobalAggregates,
    HeatmapAggregate,
    Metric,
    PickupMunicipalityAggregate,
    Shift,
    ValueMode,
)


DAY_NAMES = (
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
)
HEATMAP_SHIFTS = ("DAWN", "MORNING", "AFTERNOON", "NIGHT")
COLOR_DECISION = {
    CardColor.GREEN: "ACEITAR",
    CardColor.YELLOW: "ANALISAR",
    CardColor.RED: "REJEITAR",
}


def _round_cent(value: float) -> int:
    return int(Decimal(str(value)).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def _shift(hour: int) -> str:
    if 0 <= hour <= 5:
        return "DAWN"
    if 6 <= hour <= 11:
        return "MORNING"
    if 12 <= hour <= 17:
        return "AFTERNOON"
    return "NIGHT"


def _metric(row: dict, query: AggregateQuery) -> int | None:
    if query.metric == Metric.VALUE_PER_KM:
        return (
            row["value_per_km_cents"]
            if query.value_mode == ValueMode.FREE
            else row["gross_value_per_km_cents"]
        )
    if query.metric == Metric.VALUE_PER_HOUR:
        return row["value_per_hour_cents"]
    if query.metric == Metric.TRIP_VALUE:
        return row["trip_value_cents"]
    return row["net_trip_value_cents"]


def calculate_global_aggregates(
    rows: list[dict],
    query: AggregateQuery,
    *,
    now: int,
    timezone: str,
) -> GlobalAggregates:
    zone = ZoneInfo(timezone)
    enriched: list[tuple[dict, date, str, str, int | None]] = []
    all_dates: set[date] = set()
    wanted_platforms = {item.value for item in query.platforms}
    for row in rows:
        local = datetime.fromtimestamp(row["recorded_at_epoch_ms"] / 1000, zone)
        local_date = local.date()
        if query.start_date <= local_date <= query.end_date:
            all_dates.add(local_date)
        if row["platform"] not in wanted_platforms:
            continue
        if not query.start_date <= local_date <= query.end_date:
            continue
        row_shift = _shift(local.hour)
        if query.shift != Shift.ALL and row_shift != query.shift.value:
            continue
        expected_decision = COLOR_DECISION.get(query.card_color)
        if expected_decision is not None and row["decision"] != expected_decision:
            continue
        if query.category is not None:
            if row["platform"] != query.category.platform.value:
                continue
            if (row["category"] or "").strip().casefold() != query.category.name.casefold():
                continue
        enriched.append((row, local_date, DAY_NAMES[local.weekday()], row_shift, _metric(row, query)))

    municipality_values: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for row, _, _, _, value in enriched:
        municipality = (row["pickup_municipality"] or "").strip()
        if municipality and value is not None:
            municipality_values[municipality.casefold()].append((municipality, value))
    municipalities = [
        PickupMunicipalityAggregate(
            municipality=values[0][0],
            median_cents=_round_cent(median(item[1] for item in values)),
            event_count=len(values),
        )
        for values in municipality_values.values()
    ]
    municipalities.sort(key=lambda item: (-item.median_cents, item.municipality.casefold()))

    heatmap: list[HeatmapAggregate] = []
    for shift in HEATMAP_SHIFTS:
        for day in DAY_NAMES:
            matching = [
                value
                for _, _, event_day, event_shift, value in enriched
                if event_day == day and event_shift == shift and value is not None
            ]
            count = sum(
                1
                for _, _, event_day, event_shift, _ in enriched
                if event_day == day and event_shift == shift
            )
            heatmap.append(
                HeatmapAggregate(
                    day_of_week=day,
                    shift=shift,
                    median_cents=_round_cent(median(matching)) if matching else None,
                    event_count=count,
                )
            )

    calendar_start = query.end_date - timedelta(days=29)
    daily_calendar: list[DailyAggregate] = []
    for offset in range(30):
        current = calendar_start + timedelta(days=offset)
        matching_rows = [item for item in enriched if item[1] == current]
        values = [item[4] for item in matching_rows if item[4] is not None]
        daily_calendar.append(
            DailyAggregate(
                date=current,
                average_cents=_round_cent(sum(values) / len(values)) if values else None,
                event_count=len(matching_rows),
            )
        )

    return GlobalAggregates(
        query=query,
        pickup_municipalities=municipalities[:5],
        heatmap=heatmap,
        daily_calendar=daily_calendar,
        recorded_dates=sorted(all_dates),
        generated_at=now,
    )
