# ADR-001 — Secure Sync API v1

- Status: accepted as Phase 3B.1 contract; not implemented in production
- Baseline: Android `0.5.69-unified`, version code `172`, commit `9cc4ae48dcbfa2eae6e9c799089a8a9968c8e2bd`
- Scope: API contract, fakes and tests only

## 1. Problem

The Android APK currently embeds a Google service-account private key and calls Google Sheets directly. Every device also downloads raw rows produced by other devices. API v1 must remove permanent shared credentials from Android, authenticate each installation, authorize access server-side, preserve offline-first capture and prevent replay and duplicate writes.

## 2. Current global-data dependency

`RoomOfferAnalysisStore.globalHistory` is consumed only by `StatisticsViewModel`. `StatisticsCalculator` computes personal state first, then replaces exactly four fields with results calculated from global raw history:

| Current use | Classification | Required global input | v1 replacement |
|---|---|---|---|
| Top five pickup municipalities | B — aggregate is sufficient | Filtered municipality, selected metric median and count | `pickup_municipalities` |
| Day-of-week/shift heatmap | B — aggregate is sufficient | Filtered metric median and count for 28 cells | `heatmap` |
| Thirty-day calendar | B — aggregate is sufficient | Daily metric average and count | `daily_calendar` |
| Dates containing records | B — aggregate is sufficient | Set of dates only | `recorded_dates` |
| Platform results, summary, decisions, rejection reasons, trend, categories and matching rows | C — own history only | Local/own events | Continue from Room and `own_changes` |
| History screen and route details | C — own history only | Own raw addresses/coordinates | `own_changes` only |
| Excel export | C — own history only | `matchingEntries` from own history | Continue locally |
| Raw trips, destination/current-location addresses, coordinates and criteria from other devices | D — received without functional need | None | Never returned to Client |

No existing feature was found that obligatorily requires raw global events. The four global views are filter-sensitive. Therefore `/v1/sync` includes an explicit `aggregate_query`; the backend returns only the aggregate snapshot for that query. The Android may cache snapshots. If product requirements demand arbitrary global filtering while fully offline, a cache strategy or a bounded aggregate cube must be decided before Android integration; raw global rows are not an acceptable fallback.

## 3. Technology decision

Use Python 3.13, FastAPI and Pydantic for the small backend.

Reasons:

- DTO constraints and OpenAPI are produced from one typed model;
- fast contract tests and simple Cloud Run containerization;
- small implementation and maintenance surface;
- mature standard cryptography library;
- cold start is acceptable for hourly/on-demand sync.

TypeScript was a valid alternative with strong typing, but adds a separate schema/runtime validation stack. Kotlin would share language with Android but provides no contract benefit here and generally produces a heavier service. Neither is rejected for technical inability; Python is proportional to this backend.

## 4. Domain model

| Concept | Identifier/origin | Lifecycle and persistence | Relationship |
|---|---|---|---|
| `Installation` | Server UUID after registration | Persists until revoked/deleted; stores public key, role, owner scope and license state | Signs requests and owns an authorization context |
| `Role` | Server-controlled `CLIENT` or `ADMIN` | Persists with installation; never accepted from client claims | Determines allowed operations |
| `Event` | UUID generated once at capture | Immutable; stored for the required trip-retention period | Owned by server-derived owner scope |
| `SyncOperation` | One authenticated `/v1/sync` request | Request result retained seven days | Contains zero to 100 events and one aggregate query |
| `IdempotencyKey` | Client UUID in header | Scoped to installation + endpoint; retained seven days | Returns same response for same body; conflict for different body |
| `Cursor` | Opaque server token | Bound to owner and change sequence; valid 30 days | Requests own-event deltas only |
| `Aggregate` | Server-generated versioned snapshot | Cacheable; contains no raw third-party events | Result of `aggregate_query` |
| `Registration` | Signed `/register` request | First binding is persistent; same key is repeatable | Binds license to device public key and installation |
| `Nonce` | 16 random bytes from client | Single use per principal; retained 10 minutes | Stops replay of a valid signed transmission |
| `Timestamp` | Unix epoch seconds | Accepted within plus/minus five minutes | Bounds replay window and detects clock errors |
| `PolicyVersion` | Positive integer from server | Changes only when server policy changes | Lets Android detect updated limits/behavior |

## 5. Registration contract

### `POST /v1/installations/register`

Body:

| Field | Required | Limits/meaning |
|---|---:|---|
| `activation_key` | Yes | Opaque, 20–4096 characters; enrollment proof, never logged |
| `device_public_key` | Yes | EC JWK, curve P-256, 32-byte `x` and `y` encoded base64url |
| `client.app_id` | Yes | 3–150 characters; compatibility/telemetry only, never authorization |
| `client.app_version` | Yes | 1–40 characters |
| `client.api_version` | Yes | Exactly `1` |

Removed from the Phase 3A draft:

- `claimedDeviceId`: untrusted and unnecessary; license payload/server registry owns binding.
- body `requestId`: replaced by standard `Idempotency-Key` header.
- body `timestamp` and `signature`: signed-request metadata belongs in headers.
- requested `role`: forbidden; server assigns role.

Required signed headers: `X-TVDE-Key-Id`, `X-TVDE-Timestamp`, `X-TVDE-Nonce`, `Idempotency-Key`, `X-TVDE-Body-SHA256`, `X-TVDE-Signature`.

Response fields: `installation_id`, server-assigned `role`, `policy_version`, `server_time`, and `sync_policy` with current configurable limits.

Behavior:

- first valid registration creates the binding;
- same license and same public key returns the same installation;
- same idempotency key and body returns the original response;
- same idempotency key with a different body returns `IDEMPOTENCY_CONFLICT`;
- same license with a new key returns `LICENSE_ALREADY_BOUND`; reinstall/key loss requires explicit administrative reset;
- expired, revoked or invalid license is denied;
- revoked installation cannot silently register again;
- clock outside the accepted window is denied with server time in the error;
- collisions are prevented by server-generated UUID and unique license/key constraints;
- existing license possession is a one-time enrollment factor, not a permanent request secret.

The first-binding migration needs an allowlist or explicit administrative approval to reduce the risk of a copied activation key being enrolled before its legitimate device.

## 6. Sync contract

### `POST /v1/sync`

Body:

- `cursor`: optional opaque token, maximum 2048 characters;
- `events`: zero to 100 explicit `OfferEvent` objects;
- `aggregate_query`: the exact filters for the privacy-safe global result.

`OfferEvent` uses integers to avoid floating-point ambiguity:

| Current Android data | API field/unit |
|---|---|
| Local event ID | New persistent UUID `event_id` |
| Timestamp | `recorded_at_epoch_ms` |
| Platform/category/decision | Enumerations and bounded category string |
| Trip, net and toll values | Integer euro cents |
| Per-km/gross-per-km/per-hour values | Integer euro cents |
| Pickup/destination distances | Integer metres |
| Pickup/destination durations | Integer seconds |
| Addresses | Optional, maximum 500 characters; returned only to owner |
| Pickup municipality | Optional normalized value for aggregation |
| Latitude/longitude | Optional integer microdegrees |
| Vehicle-cost flag | Boolean |
| Active criteria and decisions | Bounded enumerations, maximum five |
| Stop rejection | Boolean |

Not sent: screenshot filename, screenshot contents or client-controlled `sourceDeviceId`. Ownership is derived from the authenticated installation.

Response:

- `event_results`: one `ACCEPTED`, `DUPLICATE` or `REJECTED` result per submitted event;
- `own_changes`: ordered owner-only upserts after the supplied cursor;
- `next_cursor` and `has_more`;
- `global_aggregates`, containing only the four approved aggregate groups;
- `aggregate_version`, `policy_version`, and `server_time`.

Separate accepted/duplicate/rejected arrays were replaced by one ordered `event_results` list. This preserves request correlation and simplifies partial outcomes.

## 7. Request authentication

Algorithm: standard ECDSA P-256 with SHA-256 (`SHA256withECDSA`). Android generates the private key in Android Keystore. The public key is a JWK. The signature is ASN.1 DER encoded and then base64url encoded without padding.

`X-TVDE-Body-SHA256` is base64url without padding of SHA-256 over the **exact transmitted request bytes**. JSON is not reserialized before verification.

Canonical UTF-8 bytes:

```text
TVDE1\n
{UPPERCASE_HTTP_METHOD}\n
{ABSOLUTE_PATH_WITHOUT_QUERY_OR_FRAGMENT}\n
{PRINCIPAL}\n
{UNIX_TIMESTAMP_SECONDS}\n
{NONCE}\n
{IDEMPOTENCY_KEY}\n
{BODY_SHA256}\n
```

There are no blank lines in the actual canonical value; each displayed item is one line. For registration, `PRINCIPAL` is the RFC 7638 thumbprint of `device_public_key`. For sync, it is `installation_id`. The backend uses the installation ID to find the registered public key.

Changing body, method, path, principal, timestamp, nonce or idempotency key invalidates the signature.

## 8. Replay protection versus idempotency

Replay protection rejects reuse of the same nonce for the same principal during the 10-minute retention window, even when the signature is valid. A retry creates a new timestamp, nonce and signature.

Idempotency allows that retry to reuse the same `Idempotency-Key` and body. The backend returns the original stored response. These controls are intentionally separate.

## 9. Idempotency and atomicity

- Request scope: endpoint + installation/principal + idempotency key.
- Request record retention: seven days, configurable.
- Same key/same body hash: exact stored status and body.
- Same key/different body hash: `IDEMPOTENCY_CONFLICT`.
- Event scope: owner + event ID.
- Same event ID/same canonical event: `DUPLICATE`.
- Same event ID/different contents: `REJECTED/EVENT_CONFLICT`.
- Event identity is retained at least as long as the event itself.

Production persistence must atomically create/check event identities, owner change sequence and request idempotency result. Concurrent requests must produce one accepted event. Google Sheets must be a downstream projection; it cannot be the atomic idempotency ledger. A timeout after commit is recovered by resending the same idempotency key with a new nonce.

## 10. Cursor and delta sync

- cursor is opaque and generated by the server;
- bound to owner scope and API version;
- represents a monotonically increasing owner change sequence;
- changes are ordered by sequence, with deterministic event ID tie-break if required;
- maximum page is 100 changes;
- `has_more=true` requires immediate next-page sync with returned cursor;
- cursor validity is 30 days, configurable;
- invalid owner/token returns `INVALID_CURSOR`;
- expired cursor returns `CURSOR_EXPIRED`;
- Android resets to `cursor=null` and obtains a paginated owner-only snapshot;
- Android never parses or modifies cursor contents.

## 11. Authorization

| Operation | CLIENT | ADMIN |
|---|---:|---:|
| Register an approved installation | Yes | Yes |
| Submit own events | Yes | Yes |
| Receive own history/delta | Yes | Yes |
| Receive global aggregates | Yes | Yes |
| Receive raw events from others | No | No by current product need |
| Administer installations | No | Not exposed in API v1 |

`ADMIN` is stored server-side for future use, but API v1 intentionally exposes no admin endpoint. `BuildConfig.IS_ADMIN_APP`, package name, Android ID, APK flavor and client flags never grant a role.

## 12. Error and Android action matrix

All errors use `{"error":{"code","message","retryable","request_id","server_time"}}`. Messages are safe and contain no credentials or personal payload.

| Code | HTTP | Retryable | Android action |
|---|---:|---:|---|
| `INVALID_REQUEST` | 400 | No | Fix client/contract; retain event and report |
| `VALIDATION_FAILED` | 422 | No | Quarantine invalid event/request; do not loop |
| `INVALID_SIGNATURE` | 401 | No | Stop automatic retry; diagnose key or re-register |
| `TIMESTAMP_OUT_OF_RANGE` | 401 | No | Rebuild once using `server_time`; then intervention |
| `INSTALLATION_NOT_FOUND` | 404 | No | Enter registration flow |
| `INSTALLATION_REVOKED` | 403 | No | Stop sync; user/admin intervention |
| `LICENSE_INVALID` | 403 | No | Registration intervention |
| `LICENSE_EXPIRED` | 403 | No | Renew license |
| `LICENSE_REVOKED` | 403 | No | Stop sync; intervention |
| `LICENSE_ALREADY_BOUND` | 409 | No | Explicit key reset/admin intervention |
| `ROLE_FORBIDDEN` | 403 | No | Stop operation; never elevate locally |
| `REPLAY_DETECTED` | 409 | No* | Rebuild once with new nonce and same idempotency key |
| `IDEMPOTENCY_CONFLICT` | 409 | No | Quarantine and diagnose client bug |
| `INVALID_CURSOR` | 409 | No* | Clear cursor and request bounded snapshot |
| `CURSOR_EXPIRED` | 410 | No* | Clear cursor and request bounded snapshot |
| `PAYLOAD_TOO_LARGE` | 413 | No* | Split batch; if one event fails, quarantine it |
| `RATE_LIMITED` | 429 | Yes | Respect `Retry-After` exactly |
| `UPSTREAM_FAILURE` | 502 | Yes | Backoff; keep outbox |
| `TEMPORARY_UNAVAILABLE` | 503 | Yes | Backoff; keep outbox |
| `INTERNAL_ERROR` | 500 | Yes | Backoff; keep outbox |

Transport handling:

- HTTP 408, timeout, DNS and `IOException`: retry same body/idempotency key with new nonce/signature;
- other 5xx: retry;
- maximum five attempts in one worker execution;
- exponential delay with jitter; no tight/infinite loop;
- after exhaustion, leave durable outbox intact and wait for the next scheduled or connectivity trigger;
- always honor `Retry-After`, capped by a configurable safety maximum.

## 13. Initial configurable limits

| Limit | Initial value |
|---|---:|
| Events per sync | 100 |
| Request body | 256 KiB |
| Address | 500 characters |
| Category | 80 characters |
| Timestamp skew | ±300 seconds |
| Nonce retention | 600 seconds |
| Request idempotency retention | 7 days |
| Cursor validity | 30 days |
| Own-change page | 100 |
| Sync requests | 30/minute/installation |
| Short burst | 10 requests |
| Registration attempts | 5 per 15 minutes per key/IP scope |
| Worker attempts per execution | 5 |
| Maximum event timestamp in future | 24 hours |
| Earliest accepted event timestamp | 2020-01-01T00:00:00Z |
| Aggregate query range | 366 inclusive days |

All values are centralized in policy/configuration. The server returns relevant client limits in `sync_policy`; Android must not scatter them as magic numbers.

## 14. API evolution

- path major version starts at `/v1/`;
- additive optional fields are allowed within v1;
- clients ignore unknown response fields;
- server rejects unknown request fields to catch accidental/malicious claims;
- required-field removal, semantic reinterpretation or incompatible enum changes require `/v2/`;
- backend uses `client.api_version` and app version for minimum-version policy;
- old clients may be allowed temporarily, rejected with a documented upgrade policy, or routed to the preserved v1 implementation;
- no field is silently repurposed.

## 15. Privacy and retention

Clients receive own raw events plus global aggregates only. Global responses contain municipality names, bucketed dates/shifts and counts/metrics, never other drivers' addresses, coordinates, identifiers or event rows.

Raw-event retention remains a product/legal decision. Until decided, the contract does not promise indefinite server retention. Request idempotency is seven days; event uniqueness is retained with the event. Logs must contain request ID, hashed installation ID, status, latency, counts and safe error code only.

## 16. Alternatives and limitations

- Firebase Authentication remains unnecessary without human accounts.
- Play Integrity may later be an additional registration risk signal, never the sole authentication mechanism.
- The fake harness does not model Google, Firestore, Cloud Run, durable transactions, distributed rate limiting or real license cryptography.
- Aggregate calculation is represented structurally but intentionally returns empty fake values in Phase 3B.1.
- Key-loss/reinstallation requires a future administrative reset workflow; no API endpoint is introduced prematurely.
- A decision is still required on aggregate cache behavior for arbitrary filtering while offline.

## 17. Next approved phase boundary

Phase 3B.2 may replace in-memory ports with a local backend implementation and durable fake/local persistence while keeping this contract. It must not yet deploy to Cloud Run or touch Android production unless separately authorized.
