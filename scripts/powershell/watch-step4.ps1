# ============================================================
# watch-step4.ps1 - C3 Step4 100% full-volume gray observation (Windows PowerShell)
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\powershell\watch-step4.ps1
#        [-IntervalMinutes 30] [-DurationHours 24] [-GrayPercent 100]
# 每轮（间隔 IntervalMinutes）：verify-c3-reconcile.ps1 + verify-c3-events.ps1
#   - 探针日志侧自动判定 FAIL；DB 侧无 psql 时走 NOTE，由会话内 MCP(postgres_helloai_dev)
#     执行 .tmp\c3-*.sql 核对（与 shell 版 watch-step4.sh 一致的口径）
# 全量核查（首轮 + 每天 0 点档）：verify-c3-env.ps1(-ExpectedGrayPercent) + verify-c3-route.ps1(-GrayPercent)
# 结果：每轮一个日志文件 logs\step4-watch-YYYYMMDD-HHMMSS.log（UTF-8 no BOM，追加本轮行）
# 任一轮 FAIL>0 追加 !!!ANOMALY!!! 标记
# (runtime literals pure ASCII; CJK only in comments; single-quote + concat per rule 6)
# ============================================================

param(
    [int]$IntervalMinutes = 30,
    [int]$DurationHours = 24,
    [int]$GrayPercent = 100
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

# ------------------------------------------------------------
# paths
# ------------------------------------------------------------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$logDir = Join-Path $projectRoot 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

$reconcileProbe = Join-Path $scriptDir 'verify-c3-reconcile.ps1'
$eventsProbe    = Join-Path $scriptDir 'verify-c3-events.ps1'
$envProbe       = Join-Path $scriptDir 'verify-c3-env.ps1'
$routeProbe     = Join-Path $scriptDir 'verify-c3-route.ps1'

# ------------------------------------------------------------
# helpers
# ------------------------------------------------------------
function Write-Log {
    param([string]$LogPath, [string]$Line)
    Write-Output $Line
    [System.IO.File]::AppendAllText($LogPath, ($Line + "`n"), $script:Utf8NoBom)
}

# 运行探针：解析 'SUMMARY: PASS=n FAIL=m'，追加 PASS/FAIL 计数 + 末 2 行摘要；返回 FAIL 数
function Invoke-Probe {
    param([string]$LogPath, [string]$Name, [string]$ScriptPath, [string[]]$ExtraArgs)
    $out = & powershell -ExecutionPolicy Bypass -File $ScriptPath @ExtraArgs 2>&1
    $joined = ($out | Out-String).Trim()
    $pMatch = [regex]::Match($joined, 'SUMMARY: PASS=(\d+)')
    $fMatch = [regex]::Match($joined, 'FAIL=(\d+)')
    $passN = if ($pMatch.Success) { [int]$pMatch.Groups[1].Value } else { 0 }
    $failN = if ($fMatch.Success) { [int]$fMatch.Groups[1].Value } else { 0 }
    $tail = @($joined -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 2) -join ' | '
    Write-Log $LogPath ('[' + $Name + '] PASS=' + $passN + ' FAIL=' + $failN + '  ' + $tail)
    return $failN
}

# ------------------------------------------------------------
# observation loop
# ------------------------------------------------------------
$round = 0
$endTime = $null
if ($DurationHours -gt 0) { $endTime = (Get-Date).AddHours($DurationHours) }

while ($true) {
    $round++
    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    $logFile = Join-Path $logDir ('step4-watch-' + $ts + '.log')
    Write-Log $logFile ('===== round ' + $round + ' start (interval=' + $IntervalMinutes + 'min) =====')

    # 1) lightweight probes every round (reconcile WARN + event chain, log-side)
    $rFail = Invoke-Probe $logFile 'reconcile' $reconcileProbe @()
    $eFail = Invoke-Probe $logFile 'events' $eventsProbe @()

    # 2) daily full check (first round always; then once per day at 00:xx)
    $now = Get-Date
    if ($round -eq 1 -or ($now.Hour -eq 0 -and $now.Minute -lt $IntervalMinutes)) {
        Write-Log $logFile '---- daily full check ----'
        $envOut = & powershell -ExecutionPolicy Bypass -File $envProbe -ExpectedGrayPercent $GrayPercent 2>&1 | Select-Object -Last 2
        $routeOut = & powershell -ExecutionPolicy Bypass -File $routeProbe -GrayPercent $GrayPercent -WindowMinutes 0 2>&1 | Select-Object -Last 3
        $envTail = @($envOut | ForEach-Object { $_.ToString() }) -join ' | '
        $routeTail = @($routeOut | ForEach-Object { $_.ToString() }) -join ' | '
        Write-Log $logFile ('env   : ' + $envTail)
        Write-Log $logFile ('route : ' + $routeTail)
    }

    # 3) anomaly marker
    if ($rFail -gt 0 -or $eFail -gt 0) {
        Write-Log $logFile ('!!!ANOMALY!!! round=' + $round + ' reconcile_FAIL=' + $rFail + ' events_FAIL=' + $eFail + ' -- check ' + $logFile)
    } else {
        Write-Log $logFile ('round ' + $round + ' clean (reconcile/events FAIL=0)')
    }

    # 4) termination
    if ($endTime -ne $null -and (Get-Date) -ge $endTime) {
        Write-Log $logFile ('===== watch complete after ' + $DurationHours + ' hours (' + $round + ' rounds), last log: ' + $logFile + ' =====')
        break
    }
    Start-Sleep -Seconds ($IntervalMinutes * 60)
}