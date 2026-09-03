# ============================================================
# helloai Phase0 C3 Step2 event-chain verifier (verify-c3-events, v1.0)
# 用途：C3 Step 2 事件完整性验收脚本（预研 7 章验收脚本表 verify-c3-events.ps1），
#       按验收标准 3/4/5 生成三支只读 SQL 探针并（若通道可用）自动执行断言：
#   P1 事件成对性：经 Runtime 路径（route=agent_runtime）的每个 Run(sub_task)
#      存在 agent_started -> ... -> agent_completed 成对序列，无孤儿
#     （孤儿 = started > completed 且子任务已离开 IN_PROGRESS）
#   P2 终态投影：route=agent_runtime 子任务的业务状态 vs 末条事件
#      （B3 五态映射；人工验收路径已补 REVIEW_APPROVED/REVIEW_REJECTED 埋点）
#   P3 执行记录：agent_execution_record 无 RUNNING 滞留（markSuccess/markFailed 无遗漏）
# 通道策略：本机有 psql 客户端时自动执行断言；否则将探针落盘 .tmp\c3-events-probe.sql，
#      提示用会话内 MCP（postgres_helloai_dev）执行（预研统一约束：只读 SQL 走 MCP）。
# Ref:  doc/design/HelloAI_Phase0_C3_双轨切换预研.md（六章验收标准 3/4/5）
#       .qoder/skills/helloai-preflight/SKILL.md（规则 6：UTF-8 头 + 单引号拼接）
# 前置：后端已启动连 dev 库；dest 库 = dev（39.106.204.43:15432）；c3gs 灰度样本已存在。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-c3-events.ps1
# 参数：-DbHost/-DbPort/-DbUser/-DbName（自动执行模式下的连接信息）
# (pure ASCII source; single-quote + concat output only)
# ============================================================

param(
    [string]$DbHost = '39.106.204.43',
    [int]$DbPort = 15432,
    [string]$DbUser = 'postgres',
    [string]$DbName = 'helloai'
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
# S1: probe generation (3 read-only SQL probes)
# ------------------------------------------------------------
Write-Output '==== S1: emit event-chain SQL probes ===='

$probeDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\.tmp'
if (-not (Test-Path $probeDir)) { New-Item -ItemType Directory -Path $probeDir -Force | Out-Null }
$probeFile = Join-Path $probeDir 'c3-events-probe.sql'
Remove-Item $probeFile -ErrorAction SilentlyContinue

$p1Pair = @'
-- P1 事件成对性：route=agent_runtime 子任务的 started/completed 计数。
-- 期望：每行 completed_cnt >= started_cnt（执行中 IN_PROGRESS 允许相等）。
--       孤儿 = completed_cnt < started_cnt 且 status <> 'IN_PROGRESS'。
WITH rt AS (
    SELECT DISTINCT sub_task_id
    FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND payload->>'route' = 'agent_runtime'
      AND deleted = 0
)
SELECT rt.sub_task_id, s.title, s.status,
       (SELECT COUNT(*) FROM agent_event e
         WHERE e.sub_task_id = rt.sub_task_id AND e.deleted = 0
           AND e.event_type = 'agent_started')  AS started_cnt,
       (SELECT COUNT(*) FROM agent_event e
         WHERE e.sub_task_id = rt.sub_task_id AND e.deleted = 0
           AND e.event_type = 'agent_completed') AS completed_cnt
FROM rt JOIN sub_task s ON s.id = rt.sub_task_id
ORDER BY rt.sub_task_id;
'@

$p2Projection = @'
-- P2 终态投影：route=agent_runtime 子任务业务状态 vs 末条事件（B3 五态映射）。
-- 期望：无 mismatch 行（DONE 末条应为 review_approved；REVIEW 为 agent_completed/review_started；
--       REWORK 为 review_rejected/rework_started）。人工验收路径埋点已补齐（LOG-20260903-007）。
WITH rt AS (
    SELECT DISTINCT sub_task_id
    FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND payload->>'route' = 'agent_runtime'
      AND deleted = 0
), proj AS (
    SELECT s.id, s.status,
           (SELECT e.event_type FROM agent_event e
             WHERE e.sub_task_id = s.id AND e.deleted = 0
             ORDER BY e.create_time DESC, e.id DESC LIMIT 1) AS last_event
    FROM rt JOIN sub_task s ON s.id = rt.sub_task_id
)
SELECT p.id, p.status, p.last_event,
       CASE WHEN
           (p.status='ASSIGNED' AND p.last_event='task_assigned') OR
           (p.status='IN_PROGRESS' AND p.last_event IN ('agent_started','context_built','tool_call_started','tool_call_completed')) OR
           (p.status='REVIEW' AND p.last_event IN ('agent_completed','review_started')) OR
           (p.status='REWORK' AND p.last_event IN ('review_rejected','rework_started')) OR
           (p.status='DONE' AND p.last_event='review_approved')
       THEN 'ok' ELSE 'MISMATCH' END AS verdict
FROM proj p
ORDER BY p.id;
'@

$p3Running = @'
-- P3 执行记录滞留：RUNNING 状态执行记录（应无滞留；如存在配合 update_time 判断新鲜度）。
SELECT id, sub_task_id, status, start_time, update_time
FROM agent_execution_record
WHERE status = 'RUNNING' AND deleted = 0
ORDER BY update_time DESC;
'@

$fullProbe = (
    '-- ============================================================' +
    "`n-- C3 Step 2 event-chain probe (generated by verify-c3-events.ps1)" +
    "`n-- 只读；期望 P1 无孤儿 / P2 无 MISMATCH / P3 0 行" +
    "`n-- 执行通道：psql 或会话内 MCP postgres_helloai_dev" +
    "`n-- ============================================================" +
    "`n`n" + $p1Pair + "`n`n" + $p2Projection + "`n`n" + $p3Running + "`n"
)
[System.IO.File]::WriteAllText($probeFile, $fullProbe.TrimStart([char]0xFEFF), $script:Utf8NoBom)
Assert-Pass (Test-Path $probeFile) 'S1' ('probe written: ' + $probeFile)

# ------------------------------------------------------------
# S2: auto-execute when a psql client is available
# ------------------------------------------------------------
Write-Output '==== S2: db channel auto-run (optional) ===='

$psqlCmd = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psqlCmd) {
    Write-Output ('S2 NOTE: no local psql; execute ' + $probeFile + ' via session MCP (postgres_helloai_dev query), then confirm: P1 no orphan / P2 no MISMATCH / P3 0 rows')
    $global:DbManual = $true
} else {
    $out = Join-Path $probeDir 'c3-events-probe.out'
    Remove-Item $out -ErrorAction SilentlyContinue
    $sqlContent = [System.IO.File]::ReadAllText($probeFile)
    $sqlContent | & $psqlCmd.Source -h $DbHost -p $DbPort -U $DbUser -d $DbName `
        -v ON_ERROR_STOP=1 -X -t -A -F '|' *>> $out
    $rc = $LASTEXITCODE
    Write-Output ('S2 psql exit=' + $rc)
    if ($rc -eq 0 -and (Test-Path $out)) {
        $lines = @(Get-Content $out)
        $orphans = @($lines) | Where-Object {
            $p = $_.Split('|')
            # P1 row: sub_task_id|title|status|started|completed
            $p.Length -ge 5 -and $p[2] -ne 'IN_PROGRESS' -and [int]$p[3] -gt [int]$p[4]
        }
        $mismatch = @($lines) | Where-Object { $_.Contains('MISMATCH') }
        $running = @($lines) | Where-Object {
            # P3 rows have 5 columns starting with record id (numeric); P2 rows end with verdict word
            $p = $_.Split('|')
            $p.Length -ge 5 -and $p[1] -match '^\d+$' -and $p[2] -eq 'RUNNING'
        }
        Assert-Pass ((Get-ListCount $orphans) -eq 0) 'S2' ('P1 orphans=' + (Get-ListCount $orphans))
        Assert-Pass ((Get-ListCount $mismatch) -eq 0) 'S2' ('P2 mismatches=' + (Get-ListCount $mismatch))
        Assert-Pass ((Get-ListCount $running) -eq 0) 'S2' ('P3 RUNNING stuck=' + (Get-ListCount $running))
        $lines | Select-Object -First 20 | ForEach-Object { Write-Output ('S2 row: ' + $_) }
    } else {
        Assert-Pass $false 'S2' ('psql probe failed rc=' + $rc)
    }
}

# ------------------------------------------------------------
# S3: summary
# ------------------------------------------------------------
Write-Output ('==== SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' ====')
if ($global:FailCount -gt 0) {
    Write-Output 'RESULT: FAILED - inspect probe output above'
    exit 1
}
if ($global:DbManual) {
    Write-Output 'RESULT: PROBE READY - run probe via MCP and confirm no orphan / no MISMATCH / no RUNNING stuck'
    exit 0
}
Write-Output 'RESULT: EVENTS OK - P1/P2/P3 all passed via psql'
exit 0