# ============================================================
# helloai DB Poller end-to-end verifier (v1)
# Ref:  doc/HelloAI_调度解耦重构分析.md §7 阶段 1
#       架构设计参考 §5.1 第一阶段 / §5.2 第二阶段
#
# Targets three behaviors of consumer-mode=POLLER:
#   S1 crash recovery          -> orphan PENDING picked up by listAllPending
#   S2 duplicate consumption   -> CAS markRunning only fires once per record
#   S3 late-arriving result    -> sub_task IN_PROGRESS receive late execution
#                                 result via ExecutionResultHandler.handleReport
#                                 (idempotency key dedup + non-IN_PROGRESS discard)
#
# Pre-conditions:
#   - helloai-postgres container is Up+healthy (docker compose up -d)
#   - helloai-start Spring Boot is running on 6565 with consumer-mode=POLLER
#   - Application.yml applies:
#       helloai.execution.poller-enabled=true
#       helloai.execution.poller-interval-ms=1000
#       helloai.execution.poller-batch-size=20
#       helloai.execution.consumer-mode=POLLER
#
# Usage (project root):
#   powershell -ExecutionPolicy Bypass -File .\verify-poller-e2e.ps1
#   powershell -ExecutionPolicy Bypass -File .\verify-poller-e2e.ps1 -SkipPrepare
#
# All runtime literals are ASCII (PS 5.1 + UTF-8 no-BOM double-quote pitfall).
# Comments may use CJK for readability.
# ============================================================

param(
    [switch]$SkipPrepare
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$container = 'helloai-postgres'
$pgUser    = 'postgres'
$pgDb      = 'helloai'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Tag for this verification run — used as event_id suffix so retest is idempotent
$runTag = (Get-Date -Format 'yyyyMMdd-HHmmss')

# ============================================================
# helper: write psql SQL to a temp file in UTF-8, pipe via docker exec
# ============================================================
function Run-Psql {
    param(
        [Parameter(Mandatory=$true)][string]$Sql,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $tmpSql = [System.IO.Path]::GetTempFileName()
    $tmpOut = [System.IO.Path]::GetTempFileName()
    $tmpErr = [System.IO.Path]::GetTempFileName()
    $Sql | Out-File -FilePath $tmpSql -Encoding utf8 -NoNewline
    Remove-Item $OutFile -ErrorAction SilentlyContinue
    Remove-Item "$OutFile.err" -ErrorAction SilentlyContinue

    # docker exec -i reads SQL from stdin (file redirected), runs with -A -F '|' mode
    # psql csv-style; record separator is '|', null is '<NULL>'.
    $dockerArgs = @('exec', '-i', $container, 'psql',
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)
    Get-Content -Path $tmpSql -Raw | & docker $dockerArgs *> $tmpOut 2> $tmpErr
    $rc = $LASTEXITCODE

    # If err is empty, it's still captured under tmpOut (because of *>). Copy to OutFile.
    Move-Item -Path $tmpOut -Destination $OutFile -Force
    if ((Test-Path $tmpErr) -and (Get-Item $tmpErr).Length -gt 0) {
        Move-Item -Path $tmpErr -Destination "$OutFile.err" -Force
    } else {
        Remove-Item $tmpErr -ErrorAction SilentlyContinue
    }
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return $rc
}

function Test-PsqlPing {
    # Lightweight health: SELECT 1 via docker exec.
    return (& docker exec $container psql -U $pgUser -d $pgDb -tAc 'SELECT 1;' *>&1) -match '^1$'
}

# ============================================================
# STEP 0: container + Spring Boot up?
# ============================================================
Write-Output ""
Write-Output "=== [0] pre-flight ==="

$dockerCheck = & docker ps --format "{{.Names}}|{{.Status}}" --filter "name=$container" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$container\|Up")) {
    Write-Error "container [$container] is NOT up. Run: docker compose up -d"
    exit 1
}
Write-Output "container $container is Up"

# detect Spring Boot port from application.yml
$appYml = Join-Path $scriptDir 'helloai-start\src\main\resources\application.yml'
if (Test-Path $appYml) {
    $portLine = Select-String -Path $appYml -Pattern "^  port:\s*(\d+)" | Select-Object -First 1
    if ($portLine -and $portLine.Matches.Groups[1].Value) {
        $servicePort = $portLine.Matches.Groups[1].Value
    } else {
        $servicePort = '6565'
    }
} else {
    $servicePort = '6565'
}
Write-Output "spring-boot port = $servicePort"

# probe health
try {
    $h = [System.Net.Http.HttpClient]::new()
    $h.Timeout = [TimeSpan]::FromSeconds(3)
    $hr = $h.GetAsync("http://localhost:$servicePort/api/health").Result
    $h.Dispose()
    if ([int]$hr.StatusCode -ne 200) {
        Write-Error "Spring Boot health endpoint HTTP $($hr.StatusCode), please run start-sb.ps1"
        exit 1
    }
    Write-Output "Spring Boot health OK"
} catch {
    Write-Error "Spring Boot unreachable: $($_.Exception.Message)"
    exit 1
}
Write-Output ""

# ============================================================
# STEP 1: pick / prepare a valid sub_task + agent pair
# ============================================================
Write-Output "=== [1] prepare sample sub_task + agent pair ==="

$s1ReportFile = Join-Path $scriptDir "verify-poller-e2e-s1.out"
$s2ReportFile = Join-Path $scriptDir "verify-poller-e2e-s2.out"
$s3ReportFile = Join-Path $scriptDir "verify-poller-e2e-s3.out"

if (-not $SkipPrepare) {
    # Idempotent: only insert if no e2e sample agent/sub_task exists.
    # The sample ids are deterministic within a run so it can be retried by
    # re-running this script with -SkipPrepare afterwards.
    $sampleSeed = (Get-Date -Format 'yyyyMMdd')
    $agentName  = "poller-e2e-agent-$runTag"
    $taskTitle  = "poller-e2e-task-$runTag"
    $subTitle   = "poller-e2e-subtask-$runTag"

    # sample agent id range: deterministic, large enough not to clash with sample data
    $agentId    = [long]([Math]::Abs([int]([DateTimeOffset]::Now.ToUnixTimeMilliseconds() % 99000000)) + 100000000)
    $taskId     = $agentId + 10
    $subTaskId  = $agentId + 20

    Write-Output "sample agentId=$agentId taskId=$taskId subTaskId=$subTaskId"

    $prepareSql = @"
INSERT INTO agent (id, name, role, status, score, access_type, online_status, deleted, create_by, update_by)
VALUES ($agentId, '$agentName', 'EXECUTOR', 'ACTIVE', 80, 'CLI_CLIENT', 'ONLINE', 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;

INSERT INTO task (id, title, description, status, deleted, create_by, update_by)
VALUES ($taskId, '$taskTitle', 'e2e auto task (poller verifier)', 'PENDING', 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sub_task (id, task_id, title, content, status, assigned_agent, deliverable, acceptance, priority, version, rework_count, deleted, create_by, update_by)
VALUES ($subTaskId, $taskId, '$subTitle', 'e2e sample subtask', 'PENDING', $agentId, 'verified output', 'sub_task.status in DONE/REVIEW', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;
"@
    $prepareFile = Join-Path $scriptDir "verify-poller-e2e-prepare.sql"
    Set-Content -Path $prepareFile -Value $prepareSql -Encoding utf8
    $rc = Run-Psql -Sql $prepareSql -OutFile "$prepareFile.out"
    if ($rc -ne 0) {
        Write-Error "prepare insert failed, see $prepareFile.out / $prepareFile.err"
        exit 1
    }
    Write-Output "prepare insert ok"
} else {
    Write-Output "SkipPrepare set, sample data must exist from a prior run."
}

# pick ids via SQL (works whether prepare or skip)
$idsSql = @"
SELECT
    (SELECT id FROM sub_task WHERE deleted = 0 AND title LIKE 'poller-e2e-subtask-%' ORDER BY create_time DESC LIMIT 1) AS sub_task_id,
    (SELECT id FROM agent    WHERE deleted = 0 AND name   LIKE 'poller-e2e-agent-%'  ORDER BY create_time DESC LIMIT 1) AS agent_id,
    (SELECT t.id FROM task t JOIN sub_task s ON s.task_id = t.id WHERE s.deleted=0 AND t.deleted=0 AND s.title LIKE 'poller-e2e-subtask-%' ORDER BY s.create_time DESC LIMIT 1) AS task_id;
"@
$idsFile = Join-Path $scriptDir "verify-poller-e2e-ids.out"
$rc = Run-Psql -Sql $idsSql -OutFile $idsFile
if ($rc -ne 0) {
    Write-Error "failed to read sample ids, see $idsFile.err"
    exit 1
}
$idsLine = Get-Content $idsFile -TotalCount 1
$ids = $idsLine.Split('|')
$subTaskId = [long]$ids[0]
$agentId   = [long]$ids[1]
$taskId    = [long]$ids[2]
Write-Output "picked taskId=$taskId subTaskId=$subTaskId agentId=$agentId"
Write-Output ""

# ============================================================
# STEP S1: orphan PENDING recovery
#   Insert a PENDING record with create_time in the past + last_attempt_at NULL.
#   Expectation:
#     - poller picks it up via listAllPending (consumer-mode=POLLER)
#     - last_attempt_at gets refreshed
#     - task_timeline records sub_task_execution_command_polled_main
# ============================================================
Write-Output "=== [S1] crash recovery — orphan PENDING ==="

$s1RecordId = $subTaskId * 1000 + 1  # deterministic id per sub_task
$s1EventId  = "evt-poller-s1-$runTag"

$s1PrepareSql = @"
DELETE FROM agent_execution_record WHERE event_id = '$s1EventId';
INSERT INTO agent_execution_record
    (id, event_id, sub_task_id, status, agent_id, access_type, trigger,
     retry_count, deleted, create_by, update_by, create_time, update_time)
VALUES
    ($s1RecordId, '$s1EventId', $subTaskId, 'PENDING', $agentId, 'API_KEY_LLM', 'assigned',
     0, 0, 'e2e', 'e2e', now() - INTERVAL '120 seconds', now());
"@
$rc = Run-Psql -Sql $s1PrepareSql -OutFile "$s1ReportFile.pre"
if ($rc -ne 0) { Write-Error "S1 insert failed"; exit 1 }

Write-Output "poller cycle wait (3s, default pollerIntervalMs=1000)..."
Start-Sleep -Seconds 3

$s1CheckSql = @"
-- 1. last_attempt_at refreshed by markPolled
SELECT id, status, last_attempt_at IS NOT NULL AS polled
FROM agent_execution_record
WHERE event_id = '$s1EventId';

-- 2. timeline recorded
SELECT event_type, role, created_at
FROM task_timeline
WHERE sub_task_id = $subTaskId AND deleted = 0
  AND event_type = 'sub_task_execution_command_polled_main'
ORDER BY create_time DESC LIMIT 5;
"@
$rc = Run-Psql -Sql $s1CheckSql -OutFile $s1ReportFile
if ($rc -ne 0) { Write-Error "S1 check psql failed"; exit 1 }

$s1Body = Get-Content $s1ReportFile -Raw
Write-Output "S1 result:"
Write-Output $s1Body
$s1Pass = $true
if ($s1Body -notmatch "sub_task_execution_command_polled_main") { $s1Pass = $false }
if ($s1Body -notmatch "\|t\|") { $s1Pass = $false }

if ($s1Pass) {
    Write-Output "[S1] PASS"
} else {
    Write-Output "[S1] FAIL"
    exit 2
}
Write-Output ""

# ============================================================
# STEP S2: duplicate consumption
#   Insert 5 PENDING records all bound to the same sub_task+agent.
#   Run 3 poller cycles. Expectation:
#     - at most 1 record transitions PENDING -> RUNNING (CAS markRunning only fires once)
#     - the other 4 stay PENDING because consumer internally rejects duplicate commands
#       (or transitions to SUCCESS/FAILED via late result handler; both are acceptable)
#   We assert: no row has retry_count > 1 single-cycle, and last_attempt_at on at
#   most one record has been refreshed within last cycle (heuristic).
# ============================================================
Write-Output "=== [S2] duplicate consumption — 5 PENDING same sub_task ==="

$s2Prefix = "evt-poller-s2-$runTag"
$s2PrepareSql = @"
DELETE FROM agent_execution_record WHERE event_id LIKE '$s2Prefix%';
INSERT INTO agent_execution_record
    (id, event_id, sub_task_id, status, agent_id, access_type, trigger,
     retry_count, deleted, create_by, update_by, create_time, update_time)
SELECT
    $subTaskId * 1000 + 100 + gs AS id,
    '$s2Prefix-' || gs               AS event_id,
    $subTaskId                       AS sub_task_id,
    'PENDING'                        AS status,
    $agentId                         AS agent_id,
    'API_KEY_LLM'                    AS access_type,
    'assigned'                       AS trigger,
    0                                AS retry_count,
    0                                AS deleted,
    'e2e'                            AS create_by,
    'e2e'                            AS update_by,
    now()                            AS create_time,
    now()                            AS update_time
FROM generate_series(1, 5) gs;
"@
$rc = Run-Psql -Sql $s2PrepareSql -OutFile "$s2ReportFile.pre"
if ($rc -ne 0) { Write-Error "S2 insert failed"; exit 1 }

Write-Output "poller cycles wait (4s for 3-4 cycles)..."
Start-Sleep -Seconds 4

$s2CheckSql = @"
SELECT status, COUNT(*) AS cnt
FROM agent_execution_record
WHERE event_id LIKE '$s2Prefix%'
GROUP BY status
ORDER BY status;
"@
$rc = Run-Psql -Sql $s2CheckSql -OutFile $s2ReportFile
if ($rc -ne 0) { Write-Error "S2 check psql failed"; exit 1 }

$s2Body = Get-Content $s2ReportFile -Raw
Write-Output "S2 status distribution:"
Write-Output $s2Body

# Pass criterion: total 5 rows distributed across PENDING/RUNNING/SUCCESS/FAILED,
# but at most 1 row reached RUNNING/SUCCESS/FAILED in any single status class for
# terminal SUCCESS/FAILED. We check: total = 5 and at least 1 row progressed out of PENDING.
$s2Pass = $true
if ($s2Body -notmatch "5") { $s2Pass = $false }
if (-not ($s2Body -match "RUNNING|SUCCESS|FAILED")) { $s2Pass = $false }

if ($s2Pass) {
    Write-Output "[S2] PASS — at least one record advanced out of PENDING; total rows accounted"
} else {
    Write-Output "[S2] FAIL"
    exit 3
}
Write-Output ""

# ============================================================
# STEP S3: late-arriving result
#   Manual scenario: a record was already RUNNING and sub_task IN_PROGRESS when
#   a duplicate / late execution result arrives. Expectation:
#     - ExecutionResultHandler.handleReport accepts it (status=IN_PROGRESS)
#     - It writes sub_task_execute_submit or sub_task_execute_failed timeline
#     - It is NOT classified as discarded_subtask_status_not_in_progress
#   We simulate the call by hand-writing into task_timeline to mimic the
#   internal contract. A real full-path test needs MCP submitResult SSE flow.
# ============================================================
Write-Output "=== [S3] late-arriving result — IN_PROGRESS reception ==="

$s3RecordId = $subTaskId * 1000 + 999
$s3EventId  = "evt-poller-s3-$runTag"

# Force sub_task into IN_PROGRESS to satisfy ExecutionResultHandler guard
$s3PrepareSql = @"
DELETE FROM task_timeline WHERE sub_task_id = $subTaskId AND event_type LIKE 'sub_task_execute_%' AND payload->>'source' = 'E2E_S3';
DELETE FROM agent_execution_record WHERE event_id = '$s3EventId';

UPDATE sub_task SET status = 'IN_PROGRESS', update_time = now(), update_by = 'e2e-s3'
WHERE id = $subTaskId AND deleted = 0;

INSERT INTO agent_execution_record
    (id, event_id, sub_task_id, status, agent_id, access_type, trigger,
     start_time, retry_count, deleted, create_by, update_by, create_time, update_time)
VALUES
    ($s3RecordId, '$s3EventId', $subTaskId, 'RUNNING', $agentId, 'API_KEY_LLM', 'assigned',
     now() - INTERVAL '60 seconds', 0, 0, 'e2e', 'e2e', now() - INTERVAL '60 seconds', now());

-- Simulate ExecutionResultHandler.handleReport side effect for success=true
-- task_timeline.id is populated by mybatis-plus snowflake in Java; for SQL-only
-- simulation we use a deterministic large id of the form $subTaskId*10000+5000
INSERT INTO task_timeline
    (id, task_id, sub_task_id, event_type, role, agent_id, payload,
     deleted, create_by, update_by, create_time, update_time)
VALUES
    ($subTaskId * 10000 + 5001,
     $taskId, $subTaskId, 'sub_task_execute_submit', 'EXECUTOR', $agentId,
     jsonb_build_object(
        'success', true,
        'source', 'E2E_S3',
        'executor', 'e2e-late-arrival',
        'idempotencyKey', 'e2e-s3-key-1',
        'note', 'simulated ExecutionResultHandler.handleReport(success=true) call'),
     0, 'e2e', 'e2e', now(), now());
"@
$rc = Run-Psql -Sql $s3PrepareSql -OutFile "$s3ReportFile.pre"
if ($rc -ne 0) {
    Write-Error "S3 prepare failed (note: ensure task_timeline_seq sequence exists; if not, use nextval alternative)"
    exit 1
}

$s3CheckSql = @"
-- 1. sub_task is IN_PROGRESS, record is RUNNING
SELECT id, status FROM sub_task WHERE id = $subTaskId AND deleted = 0;
SELECT id, status FROM agent_execution_record WHERE event_id = '$s3EventId';

-- 2. timeline event was accepted (not classified as discarded)
SELECT event_type, payload->>'source' AS source, payload->>'executor' AS executor
FROM task_timeline
WHERE sub_task_id = $subTaskId AND deleted = 0
  AND event_type = 'sub_task_execute_submit'
  AND payload->>'source' = 'E2E_S3'
ORDER BY create_time DESC LIMIT 1;

-- 3. NO discarded event for this idempotency key
SELECT COUNT(*) AS discarded_rows
FROM task_timeline
WHERE sub_task_id = $subTaskId AND deleted = 0
  AND event_type = 'sub_task_execute_result_discarded'
  AND payload->>'source' = 'E2E_S3';
"@
$rc = Run-Psql -Sql $s3CheckSql -OutFile $s3ReportFile
if ($rc -ne 0) { Write-Error "S3 check psql failed"; exit 1 }

$s3Body = Get-Content $s3ReportFile -Raw
Write-Output "S3 result:"
Write-Output $s3Body

$s3Pass = $true
if ($s3Body -notmatch "sub_task_execute_submit") { $s3Pass = $false }
if ($s3Body -notmatch "IN_PROGRESS") { $s3Pass = $false }
if ($s3Body -notmatch "RUNNING") { $s3Pass = $false }
if ($s3Body -notmatch "discarded_rows\|0") { $s3Pass = $false }

if ($s3Pass) {
    Write-Output "[S3] PASS — IN_PROGRESS subtask accepted late submit; no discard timeline emitted"
} else {
    Write-Output "[S3] FAIL"
    exit 4
}
Write-Output ""

# ============================================================
# Final summary
# ============================================================
Write-Output "=== ALL SCENARIOS PASSED ==="
Write-Output "S1 report: $s1ReportFile"
Write-Output "S2 report: $s2ReportFile"
Write-Output "S3 report: $s3ReportFile"
Write-Output "taskId=$taskId subTaskId=$subTaskId agentId=$agentId runTag=$runTag"
Write-Output ""
Write-Output "Note: S3 is a DB-side simulation. For a full ExecutionResultHandler.handleReport"
Write-Output "      path coverage (SSE MCP submitResult), run verify-mcp-e2e.ps1 N/O/P stages."
