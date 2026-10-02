# Cloud test environment

## Scope

This is not production. Android `0.5.69-unified`/`172`, the production Google Sheet and the legacy Google key are intentionally unchanged.

## Architecture

`test client -> HTTPS Cloud Run API -> Firestore -> projection_outbox -> Cloud Run Job -> test Google Sheet`

Firestore is the source of truth. A Google Sheets failure leaves the event and outbox item durable and retryable.

## Fixed resources

- Project: `project-3fe6c5dd-dd46-4e67-b9c`
- Region: `europe-southwest1`
- API service: `tvde-backend-test-api`
- Projection job: `tvde-backend-test-projector`
- Scheduler: `tvde-backend-test-projector-hourly`, hourly, `europe-west1`
- Runtime identity: `tvde-backend-test@project-3fe6c5dd-dd46-4e67-b9c.iam.gserviceaccount.com`
- Scheduler identity: `tvde-projector-scheduler@project-3fe6c5dd-dd46-4e67-b9c.iam.gserviceaccount.com`
- Firestore: `(default)`, Standard/Native, `europe-southwest1`
- Artifact Registry: `tvde-backend-test`
- Test sheet: `TVDE Insight - Cloud Test Events`, tab `Events`

No service-account JSON or Google private key is used. Cloud Run receives Application Default Credentials from its managed identity.

## IAM

| Identity | Grant | Scope | Reason |
|---|---|---|---|
| Runtime service account | `roles/datastore.user` | Project | Read/write backend Firestore documents |
| Runtime service account | Editor share | Test sheet only | Write deterministic projection rows |
| Scheduler service account | `roles/run.invoker` | Projector job | Start the projection job |
| Cloud Build compute identity | `roles/storage.objectViewer` | Cloud Build source bucket | Read submitted build source |
| Cloud Build compute identity | `roles/artifactregistry.writer` | Test repository | Push built images |
| Cloud Build compute identity | `roles/logging.logWriter` | Project | Build logs |

The API is publicly reachable over HTTPS because application-level ECDSA authentication protects its two business endpoints. No administrative endpoint is public. `/_health` discloses only `{"status":"ok"}`.

## Configuration

Required API variables:

- `TVDE_BACKEND_ENV=cloud-test`
- `GOOGLE_CLOUD_PROJECT`
- `TVDE_FIRESTORE_DATABASE=(default)`

Projector-only variables:

- `TVDE_GOOGLE_SHEET_ID`
- `TVDE_GOOGLE_SHEET_TAB=Events`

Non-sensitive tuning uses `TVDE_LOG_LEVEL`, `TVDE_TIMEZONE`, `TVDE_PROJECTION_RETRY_SECONDS`, `TVDE_PROJECTION_PROCESSING_TIMEOUT_SECONDS` and `TVDE_PROJECTION_BATCH_SIZE`.

## Deploy and rollback

Run `cloud/deploy-test.ps1` from the backend directory with an immutable image tag. The service uses one vCPU, 512 MiB, concurrency 20, minimum zero and maximum two instances.

Run `cloud/deploy-projector-test.ps1` with the same immutable image tag and the test spreadsheet ID. It deploys the single-task projector job and keeps the hourly scheduler configuration idempotent. The scheduler uses `europe-west1` because Cloud Scheduler is not offered in `europe-southwest1`; the API, Firestore and projector remain in Madrid.

To rollback, list revisions, then route traffic to a known revision:

```powershell
gcloud run revisions list --service tvde-backend-test-api --region europe-southwest1
gcloud run services update-traffic tvde-backend-test-api --region europe-southwest1 --to-revisions REVISION=100
```

Returning traffic to the current revision does not modify Firestore data.

## Backup and restore

Before production, enable a retention policy and scheduled Firestore exports to a dedicated, versioned Cloud Storage bucket. Test export:

```powershell
gcloud firestore export gs://BUCKET/PREFIX --database="(default)"
gcloud firestore import gs://BUCKET/PREFIX --database="(default)"
```

Restore must first target an isolated test project/database. Never import over production without validating schema version and event counts. During a Sheets outage, keep accepting events; the outbox is the recovery mechanism.

## Projection behavior

Each accepted event gets a unique, transactionally allocated target row. Retries update that exact row rather than append, preventing retry duplicates. The sheet contains event ID, hashed owner ID, metrics and municipality; it excludes street addresses and coordinates.

401/403, 429, 5xx, timeout/network and invalid responses are classified, logged without credentials and returned to `PENDING` with a controlled retry. `/v1/sync` does not call Sheets.

## Tests

- Unit/local: `python -m pytest` (cloud test skipped by default).
- Firestore integration: set `TVDE_RUN_CLOUD_TESTS=1` and `GOOGLE_CLOUD_PROJECT`, then run `pytest tests/test_firestore_cloud.py`.
- Deployed API: `python tools/verify_cloud_api.py --base-url URL --project PROJECT`.
- Full deployed API limit check: add `--test-rate-limit` (creates a fresh synthetic installation and exactly reaches the 30 requests/minute boundary).

The cloud verifier uses only synthetic, random test licences and an in-memory ECDSA client key.

## Troubleshooting

- Startup failure: check `_meta/backend.schema_version == 1`, the runtime identity and `roles/datastore.user`.
- `SHEETS_AUTH`: confirm the test sheet is shared directly with the runtime service account.
- `SHEETS_QUOTA`: keep the outbox pending; do not loop rapidly.
- `SHEETS_NETWORK`/`SHEETS_UNAVAILABLE`: wait for the scheduled retry.
- Duplicate projection concern: inspect `projection_outbox.target_row`; rerunning updates that row.
- 429 from API: distributed Firestore rate limiting is working; wait for the response retry interval.

## Cost controls

- Cloud Run API: scale to zero, max two instances.
- Firestore: Standard free allowance is suitable for the current test volume; PITR is disabled.
- Artifact Registry: storage is billed; keep only necessary immutable test images.
- Cloud Build: build minutes and source storage can be billed after allowances.
- Cloud Logging: ingestion/retention can be billed; logs exclude request bodies and addresses.
- Sheets API: no separate API charge for ordinary use, but quotas apply.
- Network egress can be billed.

No production capacity, minimum Cloud Run instance or Cloud SQL instance is provisioned.

## Open decisions before production

- Legal/operational retention and Firestore TTL.
- Small-cohort aggregate anonymization.
- Aggregate materialization and arbitrary offline filters.
- Android Keystore loss/re-registration policy.
- Licence copy/first-registration recovery.
- Backup bucket, retention and tested disaster-recovery objectives.
