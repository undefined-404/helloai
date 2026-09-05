# ============================================================
# helloai Phase0 C3 Step2 reconciliation verifier (verify-c3-reconcile, v1.0)
# 用途：C3 Step 2 对账确认脚本（预研 7 章验收脚本表 verify-c3-reconcile.ps1）：
#   S1 定位后端日志文件（logs/helloai.log 或 helloai-start/logs/helloai.log）
#   S2 对账窗口（默认 20min，>= B3 10min 窗口 + 60s 调度余量）内：
#      - 逐条「事件对账不一致: subTaskId=...」WARN = 0
#      - 汇总「事件对账发现不一致: 数量=N」WARN = 0
#      - EventReconciliationTask 执行异常 ERROR = 0
#   S3 生成 B3 对账口径复刻 SQL 探针（只读，五态投影核对，窗口 10min）
#      -> .tmp\c3-reconcile-probe.sql；本机有 psql 时自动执行并断言，
#         否则提示用会话内 MCP（postgres_helloai_dev）执行核对
#   S4 汇总：日志检查 FAIL>0 才 exit 1；DB 侧由 MCP 核对后回填判定
# Ref:  doc/design/HelloAI_Phase0_C3_双轨切换预研.md（六章验收标准 2/4；七章脚本表）
#       预研统一约束：只读 SQL 走 MCP，DB 写操作仅提供 SQL 由用户执行
# 前置：后端已启动连 dev 库；helloai.log 存在。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-c3-reconcile.ps1
# 参数：-LogFile 手动指定日志路径；-WindowMinutes 观察窗口（默认 20）
# (pure ASCII source; CJK log keywords assembled from code points;
#  single-quote + concat output only per PS 5.1 rule 6)
# ============================================================

param(
    [int]$WindowMinutes = 20,
    [int]$LogTailLines = 20000,
    [string]$LogFile = ''
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

$global:PassCount = 0
$global:FailCount = 0

# CJK keywords via code points (keep source pure ASCII):
#   kTag=事件对账  kMismatch=不一致  kExec=执行  kFound=发现
$kTag      = -join ([char[]](0x4E8B, 0x4EF6, 0x5BF9, 0x8D26))
$kMismatch = -join ([char[]](0x4E0D, 0x4E00, 0x81F4))
$kExec     = -join ([char[]](0x6267, 0x884C))
$kFound    = -join ([char[]](0x53D1, 0x73B0))

function Assert-Pass {
    param([bool]$Condition, [string]$Scenario, [string]$Detail)
    if ($Condition) {
        Write-Output ('[' + $Scenario + '] PASS : ' + $Detail)
        $global:PassCount++
    } else {
        Write-Output ('[' + $Scenario + '] FAIL : ' + $Detail)
        $global:FailCount++
    }
}

function Get-ListCount {
    param($List)
    if ($List -eq $null) { return 0 }
    return @($List).Count
}

# ------------------------------------------------------------
# S1: locate backend log
# ------------------------------------------------------------
Write-Output '==== S1: locate backend log ===='

if ([string]::IsNullOrWhiteSpace($LogFile)) {
    $scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
    $candidates = @(
        (Join-Path $scriptDir 'logs\helloai.log'),
        (Join-Path $scriptDir 'helloai-start\logs\helloai.log')
    )
    $LogFile = ($candidates | Where-Object { Test-Path $_ } | Select-Object -First 1)
}

$logFound = (-not [string]::IsNullOrWhiteSpace($LogFile)) -and (Test-Path $LogFile)
Assert-Pass $logFound 'S1' ('log file: ' + $(if ($logFound) { $LogFile } else { 'NOT-FOUND, pass -LogFile' }))

if (-not $logFound) {
    Write-Output ('SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    exit 1
}

# ------------------------------------------------------------
# S2: reconciliation window WARN scan
# ------------------------------------------------------------
Write-Output ('==== S2: reconcile WARN window=' + $WindowMinutes + 'min ====')

$lastWrite = (Get-Item $LogFile).LastWriteTime
$logFresh = ((Get-Date) - $lastWrite).TotalMinutes -le 3
Assert-Pass $logFresh 'S2' ('log last write ' + $lastWrite.ToString('HH:mm:ss') + ' (backend alive)')

$cutoff = (Get-Date).AddMinutes(-$WindowMinutes)
$tail = Get-Content $LogFile -Tail $LogTailLines -ErrorAction SilentlyContinue
$windowLines = @($tail) | Where-Object {
    $m = [regex]::Match($_, '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})')
    if ($m.Success) {
        try {
            $t = [datetime]::ParseExact($m.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null)
            return $t -ge $cutoff
        } catch { return $false }
    }
    return $false
}

$itemWarn = @($windowLines) | Where-Object { $_.Contains($kTag) -and $_.Contains($kMismatch) }
$sumWarn  = @($windowLines) | Where-Object { $_.Contains($kTag) -and $_.Contains($kFound) -and $_.Contains($kMismatch) }
$taskErr  = @($windowLines) | Where-Object { $_.Contains('EventReconciliationTask') -and $_.Contains($kExec) }

Assert-Pass ((Get-ListCount $itemWarn) -eq 0) 'S2' ('per-item reconcile WARN in window=' + (Get-ListCount $itemWarn))
Assert-Pass ((Get-ListCount $sumWarn) -eq 0) 'S2' ('summary reconcile WARN in window=' + (Get-ListCount $sumWarn))
Assert-Pass ((Get-ListCount $taskErr) -eq 0) 'S2' ('EventReconciliationTask ERROR in window=' + (Get-ListCount $taskErr))

Write-Output ('S2 info: tail=' + (Get-ListCount $tail) + ' lines, window lines=' + (Get-ListCount $windowLines))
if ((Get-ListCount $itemWarn) -gt 0) { $itemWarn | Select-Object -First 5 | ForEach-Object { Write-Output ('S2 WARN sample: ' + $_) } }

# ------------------------------------------------------------
# S3: emit B3 replica SQL probe (read-only, 10min window, 5-state projection)
# ------------------------------------------------------------
Write-Output '==== S3: B3 replica SQL probe ===='

$probeDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\.tmp'
if (-not (Test-Path $probeDir)) { New-Item -ItemType Directory -Path $probeDir -Force | Out-Null }
$probeFile = Join-Path $probeDir 'c3-reconcile-probe.sql'

$probeSql = @'
-- B3 对账口径复刻（只读；窗口 10min，与 EventReconciliationServiceImpl 一致）。
-- 期望：mismatches 全为 0。>0 时按逐条核对人工验收埋点（已补 REVIEW_APPROVED/REJECTED）。
WITH recent AS (
    SELECT s.id, s.status
    FROM sub_task s
    WHERE s.deleted = 0
      AND s.update_time >= now() - interval '10 minutes'
    ORDER BY s.update_time DESC
    LIMIT 500
), last_ev AS (
    SELECT r.id, r.status,
           (SELECT e.event_type FROM agent_event e
             WHERE e.sub_task_id = r.id AND e.deleted = 0
             ORDER BY e.create_time DESC, e.id DESC LIMIT 1) AS last_event
    FROM recent r
)
SELECT status,
       COUNT(*)                                   AS total,
       COUNT(*) FILTER (WHERE
           (status='ASSIGNED' AND last_event='task_assigned') OR
           (status='IN_PROGRESS' AND last_event IN ('agent_started','context_built','tool_call_started','tool_call_completed','skill_resolved','tool_resolved')) OR
           (status='REVIEW' AND last_event IN ('agent_completed','review_started')) OR
           (status='REWORK' AND last_event IN ('review_rejected','rework_started')) OR
           (status='DONE' AND last_event='review_approved')) AS matched,
       COUNT(*) FILTER (WHERE NOT (
           (status='ASSIGNED' AND last_event='task_assigned') OR
           (status='IN_PROGRESS' AND last_event IN ('agent_started','context_built','tool_call_started','tool_call_completed','skill_resolved','tool_resolved')) OR
           (status='REVIEW' AND last_event IN ('agent_completed','review_started')) OR
           (status='REWORK' AND last_event IN ('review_rejected','rework_started')) OR
           (status='DONE' AND last_event='review_approved'))) AS mismatches
FROM last_ev
WHERE status IN ('ASSIGNED','IN_PROGRESS','REVIEW','REWORK','DONE')
GROUP BY status
ORDER BY status;
'@

[System.IO.File]::WriteAllText($probeFile, $probeSql.TrimStart([char]0xFEFF), $script:Utf8NoBom)
Assert-Pass (Test-Path $probeFile) 'S3' ('probe written: ' + $probeFile)

# auto-execute when a psql client exists on PATH or -PsqlPath given
$psqlCmd = Get-Command psql -ErrorAction SilentlyContinue
if ($psqlCmd) {
    $out = Join-Path $probeDir 'c3-reconcile-probe.out'
    Remove-Item $out -ErrorAction SilentlyContinue
    $sqlContent = [System.IO.File]::ReadAllText($probeFile)
    $sqlContent | & $psqlCmd.Source -h 39.106.204.43 -p 15432 -U postgres -d helloai -v ON_ERROR_STOP=1 -X -t -A -F '|' *>> $out
    $rc = $LASTEXITCODE
    Write-Output ('S3 psql exit=' + $rc + ' (stdout in ' + $out + ')')
    if ($rc -eq 0 -and (Test-Path $out)) {
        # mismatches = 4th column; any data line with nonzero mismatch is a FAIL
        $bad = @(Get-Content $out) | Where-Object {
            $parts = $_.Split('|')
            $parts.Length -ge 4 -and $parts[3] -match '^[1-9]'
        }
        Assert-Pass ((Get-ListCount $bad) -eq 0) 'S3' ('probe mismatches rows=' + (Get-ListCount $bad))
        Get-Content $out | ForEach-Object { Write-Output ('S3 probe row: ' + $_) }
    } else {
        Assert-Pass $false 'S3' ('psql probe failed rc=' + $rc)
    }
} else {
    Write-Output 'S3 NOTE: no local psql; run probe via session MCP (postgres_helloai_dev query) and expect mismatches=0'
}

# ------------------------------------------------------------
# S4: summary
# ------------------------------------------------------------
Write-Output ('==== SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' ====')
if ($global:FailCount -gt 0) {
    Write-Output 'RESULT: FAILED - fix items above, then rerun; zero mismatch is the C3 Step 2 gate'
    exit 1
}
Write-Output 'RESULT: RECONCILE OK - window WARN=0; DB-side probe verdict to be confirmed via MCP'
exit 0