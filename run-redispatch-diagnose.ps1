param(
    [string]$BaseUrl = "http://localhost:6565",
    [ValidateSet("blocked", "offline")]
    [string]$Scenario = "blocked",
    [switch]$BindVault,
    [int]$DeepSeekConnectTimeoutMs = 3000,
    [int]$DeepSeekReadTimeoutMs = 45000,
    [int]$SyncTimeoutSeconds = 55,
    [int]$BlockedTimeoutSec = 120,
    [int]$OfflineTimeoutSec = 480,
    [int]$WaitHealthTimeoutSec = 90,
    [switch]$RestartBackend,
    [switch]$BuildBackend
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

if (-not $PSBoundParameters.ContainsKey("RestartBackend")) {
    $RestartBackend = $true
}
if (-not $PSBoundParameters.ContainsKey("BuildBackend")) {
    $BuildBackend = $true
}

$env:DEEPSEEK_CONNECT_TIMEOUT_MS = [string]$DeepSeekConnectTimeoutMs
$env:DEEPSEEK_READ_TIMEOUT_MS = [string]$DeepSeekReadTimeoutMs
$env:HELLOAI_EXECUTION_SYNC_TIMEOUT_SECONDS = [string]$SyncTimeoutSeconds

Write-Host ("ENV: DEEPSEEK_CONNECT_TIMEOUT_MS=" + $env:DEEPSEEK_CONNECT_TIMEOUT_MS)
Write-Host ("ENV: DEEPSEEK_READ_TIMEOUT_MS=" + $env:DEEPSEEK_READ_TIMEOUT_MS)
Write-Host ("ENV: HELLOAI_EXECUTION_SYNC_TIMEOUT_SECONDS=" + $env:HELLOAI_EXECUTION_SYNC_TIMEOUT_SECONDS)

if ($RestartBackend) {
    Write-Host "STEP0: restart backend"
    if ($BuildBackend) {
        Write-Host "STEP0: build backend"
        mvn -pl helloai-start -am -DskipTests package | Out-Null
    }
    & .\kill-old.ps1
    & .\start-sb.ps1
}

Write-Host "STEP0.1: wait for /actuator/health"
$deadline = (Get-Date).AddSeconds($WaitHealthTimeoutSec)
do {
    try {
        $h = Invoke-RestMethod -Method "Get" -Uri ($BaseUrl + "/actuator/health") -TimeoutSec 3
        if ($h -ne $null -and $h.status -eq "UP") {
            Write-Host "health=UP"
            break
        }
    } catch {
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

Write-Host "STEP1: run verify-subtask-redispatch-auto-execution.ps1"
$logPath = Join-Path "E:\yhzx\1027\helloai" ("redispatch-" + $Scenario + "-run-" + [DateTime]::UtcNow.ToString("yyyyMMddHHmmss") + ".log")
$invokeParams = @{
    BaseUrl = $BaseUrl
    Scenario = $Scenario
    BlockedTimeoutSec = $BlockedTimeoutSec
    OfflineTimeoutSec = $OfflineTimeoutSec
}
if ($BindVault) { $invokeParams.BindVault = $true }

$output = & .\verify-subtask-redispatch-auto-execution.ps1 @invokeParams 2>&1 | Tee-Object -FilePath $logPath
Write-Host ("runLog=" + $logPath)

$subTaskId = $null
foreach ($line in $output) {
    if ($line -match "subTaskId=(\\d+)") {
        $subTaskId = $Matches[1]
    }
}

if (-not [string]::IsNullOrWhiteSpace($subTaskId)) {
    Write-Host ("EVIDENCE: subTaskId=" + $subTaskId)
    Write-Host "SQL_RECENT_BEGIN"
    Write-Host ("SELECT id, status, assigned_agent, update_time FROM sub_task WHERE id = " + $subTaskId + " AND deleted = 0;")
    Write-Host ("SELECT id, event_type, role, agent_id, payload, create_time FROM task_timeline WHERE sub_task_id = " + $subTaskId + " AND deleted = 0 ORDER BY id DESC LIMIT 20;")
    Write-Host ("SELECT id, task_id, status, assigned_agent, update_time FROM sub_task WHERE deleted = 0 ORDER BY update_time DESC LIMIT 10;")
    Write-Host "SQL_RECENT_END"
}

exit $LASTEXITCODE
