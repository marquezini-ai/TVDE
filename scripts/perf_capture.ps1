[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after')]
    [string]$Label,

    [Parameter(Mandatory = $true)]
    [ValidateSet('repouso', 'uber', 'bolt')]
    [string]$Scenario,

    [int]$Run = 1,
    [int]$DurationSeconds = 60,
    [int]$WarmupSeconds = -1,
    [string]$Package = 'com.daniel.tvdeinsight'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $properties = Join-Path $repoRoot 'local.properties'
    $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'ADB não encontrado e sdk.dir não existe em local.properties.' }
    $sdk = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $candidate)) { throw "ADB não encontrado em $candidate" }
    return $candidate
}

function Invoke-AdbCapture([string[]]$Arguments, [string]$OutputFile) {
    & $adb @Arguments 2>&1 | Set-Content -LiteralPath $OutputFile -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "ADB falhou: $($Arguments -join ' ')" }
}

$adb = Resolve-Adb
$devices = & $adb devices
if (($devices | Select-String '\tdevice$').Count -ne 1) {
    throw 'É necessário exatamente um dispositivo ADB autorizado.'
}

$pidValue = (& $adb shell pidof $Package).Trim()
if (-not $pidValue) { throw "Processo $Package não está em execução." }
$pidValue = ($pidValue -split '\s+')[0]

if ($WarmupSeconds -lt 0) {
    $WarmupSeconds = if ($Scenario -eq 'repouso') { 0 } else { 240 }
}
if ($WarmupSeconds -gt 0) {
    Write-Host "Warm-up ${Scenario}: $WarmupSeconds s. Mantenha o cenário ativo."
    Start-Sleep -Seconds $WarmupSeconds
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputDir = Join-Path $repoRoot "docs\perf\$Label\$Scenario\run-$Run-$timestamp"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$prefix = "$Label-$Scenario"

$metadata = @(
    "label=$Label"
    "scenario=$Scenario"
    "run=$Run"
    "package=$Package"
    "pid=$pidValue"
    "duration_seconds=$DurationSeconds"
    "warmup_seconds=$WarmupSeconds"
    "started_at=$((Get-Date).ToString('o'))"
    "adb=$adb"
)
$metadata | Set-Content -LiteralPath (Join-Path $outputDir "$prefix-metadata.txt") -Encoding utf8

Invoke-AdbCapture @('shell', 'dumpsys', 'thermalservice') (Join-Path $outputDir "$prefix-thermal-before.txt")
& $adb logcat -c
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível limpar o logcat.' }

$logcatFile = Join-Path $outputDir "$prefix-work-logcat.txt"
$threadTopFile = Join-Path $outputDir "$prefix-work-top.txt"
$systemTopFile = Join-Path $outputDir "$prefix-work-system-top.txt"
$logcat = $null
$threadTop = $null
$systemTop = $null
try {
    $logcat = Start-Process -FilePath $adb -ArgumentList @('logcat', "--pid=$pidValue", '-v', 'threadtime') `
        -RedirectStandardOutput $logcatFile -RedirectStandardError (Join-Path $outputDir "$prefix-logcat-error.txt") `
        -WindowStyle Hidden -PassThru
    $threadTop = Start-Process -FilePath $adb -ArgumentList @('shell', 'top', '-H', '-p', $pidValue, '-d', '1', '-n', $DurationSeconds) `
        -RedirectStandardOutput $threadTopFile -RedirectStandardError (Join-Path $outputDir "$prefix-top-error.txt") `
        -WindowStyle Hidden -PassThru
    $systemTop = Start-Process -FilePath $adb -ArgumentList @('shell', 'top', '-d', '1', '-n', $DurationSeconds) `
        -RedirectStandardOutput $systemTopFile -RedirectStandardError (Join-Path $outputDir "$prefix-system-top-error.txt") `
        -WindowStyle Hidden -PassThru

    Wait-Process -Id $threadTop.Id, $systemTop.Id
} finally {
    if ($logcat -and -not $logcat.HasExited) { Stop-Process -Id $logcat.Id -Force }
}

Invoke-AdbCapture @('shell', 'dumpsys', 'cpuinfo') (Join-Path $outputDir "$prefix-work-cpuinfo.txt")
Invoke-AdbCapture @('shell', 'dumpsys', 'meminfo', $Package) (Join-Path $outputDir "$prefix-work-mem.txt")
Invoke-AdbCapture @('shell', 'ps', '-T', '-p', $pidValue) (Join-Path $outputDir "$prefix-work-threads.txt")
Invoke-AdbCapture @('shell', 'dumpsys', 'thermalservice') (Join-Path $outputDir "$prefix-thermal-after.txt")
& $adb logcat -d 2>&1 | Select-String -Pattern 'SIOP|HYPER|thermal|GPUFreq|ARM_MAX|GPUMaxFreq' |
    Set-Content -LiteralPath (Join-Path $outputDir "$prefix-siop.txt") -Encoding utf8

Add-Content -LiteralPath (Join-Path $outputDir "$prefix-metadata.txt") `
    -Value "finished_at=$((Get-Date).ToString('o'))"
Write-Host "Captura concluída: $outputDir"
