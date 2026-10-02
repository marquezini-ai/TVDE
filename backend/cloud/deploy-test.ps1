param(
    [string]$ProjectId = "project-3fe6c5dd-dd46-4e67-b9c",
    [string]$Region = "europe-southwest1",
    [Parameter(Mandatory = $true)][string]$ImageTag
)

$ErrorActionPreference = "Stop"
$Repository = "tvde-backend-test"
$Service = "tvde-backend-test-api"
$RuntimeIdentity = "tvde-backend-test@$ProjectId.iam.gserviceaccount.com"
$Image = "$Region-docker.pkg.dev/$ProjectId/$Repository/api:$ImageTag"

gcloud builds submit .. --tag $Image --project $ProjectId
gcloud run deploy $Service `
    --project $ProjectId `
    --region $Region `
    --image $Image `
    --service-account $RuntimeIdentity `
    --set-env-vars "TVDE_BACKEND_ENV=cloud-test,GOOGLE_CLOUD_PROJECT=$ProjectId,TVDE_FIRESTORE_DATABASE=(default),TVDE_TIMEZONE=Europe/Lisbon,TVDE_LOG_LEVEL=INFO" `
    --memory 512Mi `
    --cpu 1 `
    --concurrency 20 `
    --timeout 30 `
    --min 0 `
    --max 2 `
    --execution-environment gen2 `
    --ingress all `
    --allow-unauthenticated

