# Global aggregate filter compatibility

The Android `StatisticsCalculator` currently uses these filters:

| Filter | Represented by API v1 | Local backend behavior |
|---|---:|---|
| Platform Uber/Bolt | Yes | Exact enumeration |
| Start/end date | Yes | Inclusive local dates |
| Shift | Yes | Same four six-hour ranges |
| Card color | Yes | Green=`ACEITAR`, yellow=`ANALISAR`, red=`REJEITAR` |
| Category | Yes | Platform-bound, case-insensitive name |
| Metric | Yes | Per-km, per-hour, trip or net trip value |
| Free/gross value | Yes | Selects free or gross per-km cents |

These filters fully represent Top 5 municipalities, heatmap and 30-day calendar without exposing raw third-party events.

`recordedDates` differs: existing Android derives every date from the entire supplied global history and ignores active filters. API v1 limits `recorded_dates` to 366 entries and has no independent date-discovery request. The local backend therefore returns unique global dates inside the requested date range, independent of the other filters. Product must decide whether future cloud behavior should keep that bounded rule or add a separate privacy-safe date-index endpoint/versioned field.

Arbitrary offline recomputation remains impossible from one aggregate response. Android can cache query-specific snapshots, but raw global events must not be redistributed. A bounded aggregate cube or cache policy remains a product decision.
