param(
    [string]$ProjectId = "project-3fe6c5dd-dd46-4e67-b9c",
    [string]$Region = "europe-southwest1",
    [string]$SchedulerRegion = "europe-west1",
    [Parameter(Mandatory = $true)][string]$ImageTag,
    [Parameter(Mandatory = $true)][string]$SpreadsheetId
)

$ErrorActionPreference = "Stop"
$Repository = "tvde-backend-test"
$Job = "tvde-backend-test-projector"
$RuntimeIdentity = "tvde-backend-test@$ProjectId.iam.gserviceaccount.com"
$SchedulerIdentity = "tvde-projector-scheduler@$ProjectId.iam.gserviceaccount.com"
$SchedulerJob = "tvde-backend-test-projector-hourly"
$Image = "$Region-docker.pkg.dev/$ProjectId/$Repository/api:$ImageTag"
$RunUri = "https://$Region-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/$ProjectId/jobs/${Job}:run"

gcloud run jobs deploy $Job `
    --project $ProjectId `
    --region $Region `
    --image $Image `
    --service-account $RuntimeIdentity `
    --command python `
    --args=-m,tvde_contract.projector_main `
    --set-env-vars "TVDE_BACKEND_ENV=cloud-test,GOOGLE_CLOUD_PROJECT=$ProjectId,TVDE_FIRESTORE_DATABASE=(default),TVDE_GOOGLE_SHEET_ID=$SpreadsheetId,TVDE_GOOGLE_SHEET_TAB=Events,TVDE_LOG_LEVEL=INFO" `
    --memory 512Mi `
    --cpu 1 `
    --tasks 1 `
    --max-retries 0 `
    --task-timeout 300s

gcloud run jobs add-iam-policy-binding $Job `
    --project $ProjectId `
    --region $Region `
    --member "serviceAccount:$SchedulerIdentity" `
    --role roles/run.invoker

$ExistingScheduler = gcloud scheduler jobs list `
    --project $ProjectId `
    --location $SchedulerRegion `
    --format="value(name)" | Select-String "/$SchedulerJob$"

if ($ExistingScheduler) {
    gcloud scheduler jobs update http $SchedulerJob `
        --project $ProjectId `
        --location $SchedulerRegion `
        --schedule "0 * * * *" `
        --time-zone "Europe/Lisbon" `
        --uri $RunUri `
        --http-method POST `
        --oauth-service-account-email $SchedulerIdentity `
        --oauth-token-scope "https://www.googleapis.com/auth/cloud-platform" `
        --attempt-deadline 180s `
        --max-retry-attempts 1 `
        --min-backoff 60s `
        --max-backoff 300s
} else {
    gcloud scheduler jobs create http $SchedulerJob `
        --project $ProjectId `
        --location $SchedulerRegion `
        --schedule "0 * * * *" `
        --time-zone "Europe/Lisbon" `
        --uri $RunUri `
        --http-method POST `
        --oauth-service-account-email $SchedulerIdentity `
        --oauth-token-scope "https://www.googleapis.com/auth/cloud-platform" `
        --attempt-deadline 180s `
        --max-retry-attempts 1 `
        --min-backoff 60s `
        --max-backoff 300s
}
