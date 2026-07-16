# ============================================================
# helloai DB Poller end-to-end verifier (v3.1 — T5 降级后 + AgentHub V1 + 主消费路径隔离)
# Ref:  doc/HelloAI_调度解耦重构分析.md §7 阶段 1
#       架构设计参考 §5.1 第一阶段
#
# T5 起重塑：DB Poller 已从"主消费载体"降级为"孤儿 / 超时 / 补偿兜底"。
# 本脚本验证的核心问题是：
#   "MQ Consumer / 本地 Event Consumer 主路径异常时，
#    Poller 兜底是否真的能接住孤儿 PENDING，避免记录永久挂死"
#
# Scenarios (all work under any consumer-mode: EVENT/POLLER/BOTH):
#   S1  orphan PENDING recovery    — 单条孤儿 PENDING 被 Poller 扫到并推进
#   S2  duplicate consumption      — 5 条同 sub_task 的 PENDING 只有 1 条被消费
#   S3  late-arriving result       — IN_PROGRESS 子任务接受晚到结果
#   S4  batch orphan recovery      — 多条 (3+) 不同 sub_task 的孤儿 PENDING 在
#                                     Poller 的一个扫描周期内全部被扫到
#   S5  Poller-only recovery path  — 直接 INSERT 绕过 ExecutionCommandService.publish()，
#                                     没有 MQ 消息也没有本地 Spring 事件，是"主消费路径
#                                     不可达"的等价形态。断言：
#                                       * last_attempt_at 被刷新 (markPolled)
#                                       * timeline 含 sub_task_execution_command_poll_recovery
#                                       * consume 事件的 trigger 以 poll-recovery: 开头
#                                       * 反证：没有任何 trigger 不以 poll-recovery: 开头的
#                                         consume 事件被记录（说明没有任何主消费者参与）
#   S6  已迁出 —— 见 verify-execution-dispatch-guard.ps1（S6, v1.0）。
#       旧 S6 是"重启时把 consumer-enabled 设为 false，再跑 S1-S4"；但 T5 引入
#       ExecutionDispatchValidator 后，consumer-mode ∈ {POLLER, BOTH} + consumer-enabled=false
#       会在 @PostConstruct 阶段直接 fail-fast，应用根本起不来，旧组合已不再是"能跑的验证"。
#       S6 因此重定义为独立的"启动期 fail-fast 守卫验证"（反复用不同配置组合启动、观察
#       非法组合启动失败 / 合法组合 /api/health 200），本质是"重启 JVM + 观察启动成败"，
#       与本脚本的"运行期 Poller 兜底 E2E"不是一类验证，故单独成脚本、不再塞进这里。
#
# T5 semantics verified per scenario:
#   - timeline event name: sub_task_execution_command_poll_recovery (not _polled_main)
#   - Poller uses listOrphanPending (not listAllPending)
#   - CAS markRunning guarantees single-consumer even with concurrent scans
#
# Pre-conditions:
#   - helloai-postgres container is Up+healthy (docker compose up -d)
#   - helloai-start Spring Boot is running on 6565
#   - Poller enabled + consumer-mode allows Poller to run
#
# Usage (project root):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-poller-e2e.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-poller-e2e.ps1 -SkipPrepare
#
# All runtime literals are ASCII (PS 5.1 + UTF-8 no-BOM double-quote pitfall).
# Comments may use CJK for readability.
# ============================================================

param(
    [switch]$SkipPrepare,
    [switch]$AllowRealExecution
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$container = 'helloai-postgres'
$pgUser    = 'postgres'
$pgDb      = 'helloai'
# 脚本已迁至 scripts/powershell/，仓库根 = 脚本目录向上两级（保持 .out 日志与 helloai-start 相对路径与迁移前一致）
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))

$runTag = (Get-Date -Format 'yyyyMMdd-HHmmss')

# ============================================================
# helper functions
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

    $dockerArgs = @('exec', '-i', $container, 'psql',
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)
    Get-Content -Path $tmpSql -Raw | & docker $dockerArgs *> $tmpOut 2> $tmpErr
    $rc = $LASTEXITCODE

    Move-Item -Path $tmpOut -Destination $OutFile -Force
    if ((Test-Path $tmpErr) -and (Get-Item $tmpErr).Length -gt 0) {
        Move-Item -Path $tmpErr -Destination "$OutFile.err" -Force
    } else {
        Remove-Item $tmpErr -ErrorAction SilentlyContinue
    }
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return $rc
}

function Assert-Pass {
    param([bool]$Condition, [string]$Scenario, [string]$Detail)
    if ($Condition) {
        Write-Output "[$Scenario] PASS : $Detail"
        $global:PassCount++
    } else {
        Write-Output "[$Scenario] FAIL : $Detail"
        $global:FailCount++
    }
}

$global:PassCount = 0
$global:FailCount = 0

# ============================================================
# STEP 0: pre-flight
# ============================================================
Write-Output ""
Write-Output "============================================================"
Write-Output " Poller E2E Verifier v3.1 (T5 downgrade + AgentHub V1 + 主消费隔离)"
Write-Output " runTag = $runTag"
Write-Output "============================================================"
Write-Output ""

Write-Output "=== [0] pre-flight ==="

$dockerCheck = & docker ps --format "{{.Names}}|{{.Status}}" --filter "name=$container" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$container\|Up")) {
    Write-Error "container [$container] is NOT up. Run: docker compose up -d"
    exit 1
}
Write-Output "container $container is Up"

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

$healthOk = $false
$lastHealthErr = $null
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    try {
        $hr = Invoke-WebRequest -Uri "http://localhost:$servicePort/api/health" -UseBasicParsing -TimeoutSec 3 -Method Get
        if ([int]$hr.StatusCode -eq 200) { $healthOk = $true; break }
        $lastHealthErr = "HTTP " + $hr.StatusCode
    } catch {
        $lastHealthErr = $_.Exception.Message
    }
    Start-Sleep -Seconds 2
}
if (-not $healthOk) {
    $port = 6565
    try { $port = [int]$servicePort } catch {}
    $listen = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    $isListening = [bool]$listen
    Write-Error ("Spring Boot unreachable at http://localhost:" + $servicePort + " (listening=" + $isListening + ", lastErr=" + $lastHealthErr + "). Start Spring Boot first (IDEA or .\\scripts\\powershell\\start-sb.ps1), then re-run.")
    exit 1
}
Write-Output "Spring Boot health OK"
Write-Output ""

# ============================================================
# STEP 0.5: mock-execution hard gate (pre-flight for consume-path scenarios)
#
# 用户约束：S2/S5 走真实主消费链（consume -> startIfNeeded -> markRunning ->
# executeOnce），必须锁定 mock 执行，不依赖真实 DeepSeek。否则脚本会把
# "调度/消费链路验证" 退化为 "外部 LLM 稳定性验证"，引入随机波动。
#
# 证据优先级：
#   (1) 运行期端点 GET /api/health/execution-mode（公开、无需认证，已在
#       WebMvcConfig 中 /api/health/** 放行）回显 live app 的
#       helloai.execution.mock-mode——最可信的运行期证据。
#   (2) 若该端点不存在（旧构建未重启）或不可达，回退读 boot 配置源
#       application.yml 的 mock-mode（配置源证据，非活时运行值）。
#   两者均无法确认 mock-mode=true 时，直接 FAIL。
# ============================================================
Write-Output "=== [0.5] mock-execution hard gate ==="
$mockMode = $null
$provider = $null
$mockSource = $null
try {
    $mockProbe = Invoke-WebRequest -Uri "http://localhost:$servicePort/api/health/execution-mode" -UseBasicParsing -TimeoutSec 5 -Method Get
    $mockJson = $mockProbe.Content | ConvertFrom-Json
    $mockMode = [bool]$mockJson.data.mockMode
    $provider = [string]$mockJson.data.provider
    $mockSource = 'runtime endpoint /api/health/execution-mode'
} catch {
    Write-Output ("runtime execution-mode probe unavailable (" + $_.Exception.Message + "); falling back to application.yml config source")
    if (Test-Path $appYml) {
        $mockLine = Select-String -Path $appYml -Pattern '^\s*mock-mode:\s*(true|false)' | Select-Object -First 1
        if ($mockLine) {
            $mockMode = ($mockLine.Matches.Groups[1].Value -eq 'true')
            $mockSource = 'application.yml (config source, NOT live runtime)'
        }
        $providerLine = Select-String -Path $appYml -Pattern '^\s*provider:\s*(\S+)\s*$' | Select-Object -First 1
        if ($providerLine) {
            $provider = [string]$providerLine.Matches.Groups[1].Value
        }
    }
}
$safeMock = ($mockMode -eq $true) -or ($provider -eq 'mock')
if (-not $safeMock) {
    if ($AllowRealExecution) {
        Write-Warning ("mock execution is OFF (mockMode=" + $mockMode + ", provider=" + $provider + ", source=" + $mockSource + "). Continue because -AllowRealExecution is set. This run may call a real LLM and is NOT a stable regression baseline.")
    } else {
        Write-Error ("mock execution is OFF or unconfirmed (mockMode=" + $mockMode + ", provider=" + $provider + ", source=" + $mockSource + "). This env would trigger a real LLM; results are NOT a stable regression baseline. Set helloai.execution.mock-mode=true (or provider=mock) and restart Spring Boot, or re-run with -AllowRealExecution.")
        exit 1
    }
}
Write-Output ("mock execution confirmed (mockMode=" + $mockMode + ", provider=" + $provider + ", source: " + $mockSource + ")")
Write-Output ""

# ============================================================
# STEP 1: prepare sample data
# ============================================================
Write-Output "=== [1] prepare sample sub_task + agent pair ==="

$s1ReportFile = Join-Path $scriptDir "verify-poller-e2e-s1.out"
$s2ReportFile = Join-Path $scriptDir "verify-poller-e2e-s2.out"
$s3ReportFile = Join-Path $scriptDir "verify-poller-e2e-s3.out"
$s4ReportFile = Join-Path $scriptDir "verify-poller-e2e-s4.out"
$s5ReportFile = Join-Path $scriptDir "verify-poller-e2e-s5.out"

if (-not $SkipPrepare) {
    $agentName  = "poller-e2e-agent-$runTag"
    $taskTitle  = "poller-e2e-task-$runTag"
    $subTitle   = "poller-e2e-subtask-$runTag"

    $agentId    = [long]([Math]::Abs([int]([DateTimeOffset]::Now.ToUnixTimeMilliseconds() % 99000000)) + 100000000)
    $taskId     = $agentId + 10
    $subTaskId  = $agentId + 20

    Write-Output "sample agentId=$agentId taskId=$taskId subTaskId=$subTaskId"

    $prepareSql = @"
INSERT INTO agent (id, name, role, status, score, access_type, online_status, deleted, create_by, update_by)
VALUES ($agentId, '$agentName', 'EXECUTOR', 'ACTIVE', 80, 'CLI_CLIENT', 'ONLINE', 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;

INSERT INTO task (id, title, description, status, deleted, create_by, update_by)
VALUES ($taskId, '$taskTitle', 'e2e auto task (poller verifier v3)', 'PENDING', 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sub_task (id, task_id, title, content, status, assigned_agent, deliverable, acceptance, priority, version, rework_count, deleted, create_by, update_by)
VALUES ($subTaskId, $taskId, '$subTitle', 'e2e sample subtask', 'PENDING', $agentId, 'verified output', 'sub_task.status in DONE/REVIEW', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;
"@
    $rc = Run-Psql -Sql $prepareSql -OutFile "$scriptDir\verify-poller-e2e-prepare.out"
    if ($rc -ne 0) {
        Write-Error "prepare insert failed, see verify-poller-e2e-prepare.out"
        exit 1
    }
    Write-Output "prepare insert ok"
} else {
    Write-Output "SkipPrepare set, sample data must exist from a prior run."
}

# pick ids
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
# psql 用 -A -F '|' 但未加 -t，输出第 1 行是列名表头、末行是 "(N rows)" 页脚，
# 故不能盲取首行，需过滤出真正的数据行（三段纯数字，以 '|' 分隔）
$idsLine = Get-Content $idsFile | Where-Object { $_ -match '^\d+\|\d+\|\d+$' } | Select-Object -First 1
if (-not $idsLine) {
    Write-Error "failed to parse sample ids from $idsFile (no numeric data row found)"
    exit 1
}
$ids = $idsLine.Split('|')
$subTaskId = [long]$ids[0]
$agentId   = [long]$ids[1]
$taskId    = [long]$ids[2]
Write-Output "picked taskId=$taskId subTaskId=$subTaskId agentId=$agentId"
Write-Output ""

# ============================================================
# S1: orphan PENDING recovery
# T5 semantics: Poller uses listOrphanPending, not listAllPending.
# timeline event: sub_task_execution_command_poll_recovery
# ============================================================
Write-Output "=== [S1] orphan PENDING recovery ==="
Write-Output "      T5 downgrade: Poller scans orphan PENDING via listOrphanPending"
Write-Output "      Expected timeline event: sub_task_execution_command_poll_recovery"

$s1RecordId = $subTaskId * 1000 + 1
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

Write-Output "poller cycle wait (3s)..."
Start-Sleep -Seconds 3

$s1CheckSql = @"
SELECT id, status, last_attempt_at IS NOT NULL AS polled
FROM agent_execution_record
WHERE event_id = '$s1EventId';

SELECT event_type, role, create_time
FROM task_timeline
WHERE sub_task_id = $subTaskId AND deleted = 0
  AND event_type = 'sub_task_execution_command_poll_recovery'
ORDER BY create_time DESC LIMIT 5;
"@
$rc = Run-Psql -Sql $s1CheckSql -OutFile $s1ReportFile
if ($rc -ne 0) { Write-Error "S1 check failed"; exit 1 }

$s1Body = Get-Content $s1ReportFile -Raw
Write-Output "S1 raw output:"
Write-Output $s1Body

Assert-Pass ($s1Body -match "sub_task_execution_command_poll_recovery") "S1" "timeline event 'sub_task_execution_command_poll_recovery' found"
Assert-Pass ($s1Body -match '\|t(\r?\n|$)') "S1" "last_attempt_at refreshed (polled=true)"
Write-Output ""

# ============================================================
# S2: duplicate consumption
# 5 PENDING for same sub_task -> at most 1 consumed (CAS dedup)
# ============================================================
Write-Output "=== [S2] duplicate consumption ==="
Write-Output "      5 PENDING records for same sub_task; CAS markRunning dedup"

$s2Prefix = "evt-poller-s2-$runTag"
# id 基值在 PowerShell 内用 [long] 算好（$subTaskId 已是 [long]），避免在 SQL 里
# 用 int4 字面量相乘导致 "integer out of range"（112801095 * 1000 溢出 int4）
$s2RecordBase = $subTaskId * 1000 + 100
# 用户约束：S2 用独立 sub_task（ASSIGNED）隔离，走真实 consume 链推进出 PENDING。
# startIfNeeded 允许 ASSIGNED -> start()，消费方能把记录推出 PENDING；不再复用共享
# sample sub_task，也不 reset 回 PENDING。title 前缀 poller-e2e-s2-sub- 不匹配
# ids 选取用的 'poller-e2e-subtask-%'，避免被后续轮次误选为主样本。
$s2SubId = $subTaskId + 400
$s2PrepareSql = @"
DELETE FROM agent_execution_record WHERE event_id LIKE '$s2Prefix%';
INSERT INTO sub_task (id, task_id, title, content, status, assigned_agent, deliverable, acceptance, priority, version, rework_count, deleted, create_by, update_by)
VALUES ($s2SubId, $taskId, 'poller-e2e-s2-sub-$runTag', 'e2e s2 dedup subtask', 'ASSIGNED', $agentId, 'x', 'x', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;
INSERT INTO agent_execution_record
    (id, event_id, sub_task_id, status, agent_id, access_type, trigger,
     retry_count, deleted, create_by, update_by, create_time, update_time)
SELECT
    $s2RecordBase + gs               AS id,
    '$s2Prefix-' || gs               AS event_id,
    $s2SubId                         AS sub_task_id,
    'PENDING'                        AS status,
    $agentId                         AS agent_id,
    'API_KEY_LLM'                    AS access_type,
    'assigned'                       AS trigger,
    0                                AS retry_count,
    0                                AS deleted,
    'e2e'                            AS create_by,
    'e2e'                            AS update_by,
    now() - INTERVAL '120 seconds'   AS create_time,
    now()                            AS update_time
FROM generate_series(1, 5) gs;
"@
$rc = Run-Psql -Sql $s2PrepareSql -OutFile "$s2ReportFile.pre"
if ($rc -ne 0) { Write-Error "S2 insert failed"; exit 1 }

Write-Output "poller cycles wait (4s)..."
Start-Sleep -Seconds 4

# 聚合行放首个查询：total|advanced 便于稳健解析（避免依赖 GROUP BY 分裂后的字面 '5'）。
$s2CheckSql = @"
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE status <> 'PENDING') AS advanced
FROM agent_execution_record
WHERE event_id LIKE '$s2Prefix%';

SELECT status, COUNT(*) AS cnt
FROM agent_execution_record
WHERE event_id LIKE '$s2Prefix%'
GROUP BY status
ORDER BY status;
"@
$rc = Run-Psql -Sql $s2CheckSql -OutFile $s2ReportFile
if ($rc -ne 0) { Write-Error "S2 check failed"; exit 1 }

$s2Body = Get-Content $s2ReportFile -Raw
Write-Output "S2 status distribution:"
Write-Output $s2Body

if ($s2Body -match "(\d+)\|(\d+)") {
    $s2Total = [int]$Matches[1]
    $s2Advanced = [int]$Matches[2]
    Assert-Pass ($s2Total -eq 5) "S2" "5 records accounted (got $s2Total)"
    Assert-Pass ($s2Advanced -ge 1) "S2" "at least 1 advanced out of PENDING via consume (got $s2Advanced)"
} else {
    Assert-Pass $false "S2" "unable to parse aggregate row"
}
Write-Output ""

# ============================================================
# S3: late-arriving result
# sub_task IN_PROGRESS accepts late submit (no discard)
# ============================================================
Write-Output "=== [S3] late-arriving result ==="
Write-Output "      IN_PROGRESS sub_task accepts late submitResult via ExecutionResultHandler"

$s3RecordId = $subTaskId * 1000 + 999
$s3EventId  = "evt-poller-s3-$runTag"
# timeline id 同样在 PowerShell 内用 [long] 算好，避免 SQL 侧 int4 相乘溢出
$s3TimelineId = $subTaskId * 10000 + 5001

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

INSERT INTO task_timeline
    (id, task_id, sub_task_id, event_type, role, agent_id, payload,
     deleted, create_by, update_by, create_time, update_time)
VALUES
    ($s3TimelineId,
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
if ($rc -ne 0) { Write-Error "S3 prepare failed"; exit 1 }

$s3CheckSql = @"
SELECT id, status FROM sub_task WHERE id = $subTaskId AND deleted = 0;
SELECT id, status FROM agent_execution_record WHERE event_id = '$s3EventId';
SELECT event_type, payload->>'source' AS source
FROM task_timeline
WHERE sub_task_id = $subTaskId AND deleted = 0
  AND event_type = 'sub_task_execute_submit'
  AND payload->>'source' = 'E2E_S3'
ORDER BY create_time DESC LIMIT 1;
SELECT COUNT(*) AS discarded_rows
FROM task_timeline
WHERE sub_task_id = $subTaskId AND deleted = 0
  AND event_type = 'sub_task_execute_result_discarded'
  AND payload->>'source' = 'E2E_S3';
"@
$rc = Run-Psql -Sql $s3CheckSql -OutFile $s3ReportFile
if ($rc -ne 0) { Write-Error "S3 check failed"; exit 1 }

$s3Body = Get-Content $s3ReportFile -Raw
Write-Output "S3 raw output:"
Write-Output $s3Body

Assert-Pass ($s3Body -match "IN_PROGRESS") "S3" "sub_task status is IN_PROGRESS"
Assert-Pass ($s3Body -match "RUNNING") "S3" "execution record is RUNNING"
Assert-Pass ($s3Body -match "sub_task_execute_submit") "S3" "timeline event 'sub_task_execute_submit' found"
Assert-Pass ($s3Body -match 'discarded_rows\r?\n0(\r?\n|$)') "S3" "no discarded timeline events"
Write-Output ""

# ============================================================
# S4: batch orphan recovery (NEW in v3)
# Insert 3 orphan PENDING records across different sub_task-like ids,
# verify Poller scans and processes all within one cycle window.
# This validates that Poller handles multiple orphan records correctly
# when the main consumer path is unavailable.
# ============================================================
Write-Output "=== [S4] batch orphan recovery ==="
Write-Output "      3 orphan PENDING records across different ids; all should be polled"

$s4Prefix = "evt-poller-s4-$runTag"
$s4RecordBase = $subTaskId * 1000 + 500
# S4 用 3 个不同的 sub_task；agent_execution_record.sub_task_id 有 FK 指向 sub_task(id)，
# 故先建好这 3 个 sub_task。title 用与主用例不同的前缀，避免下一轮
# 按 title LIKE 'poller-e2e-subtask-%' 选 id 时误选中这些额外 sub_task。
$s4Sub1 = $subTaskId + 100
$s4Sub2 = $subTaskId + 200
$s4Sub3 = $subTaskId + 300

$s4PrepareSql = @"
DELETE FROM agent_execution_record WHERE event_id LIKE '$s4Prefix%';
INSERT INTO sub_task (id, task_id, title, content, status, assigned_agent, deliverable, acceptance, priority, version, rework_count, deleted, create_by, update_by)
VALUES
    ($s4Sub1, $taskId, 'poller-e2e-s4-extra-1-$runTag', 'e2e s4 extra subtask', 'ASSIGNED', $agentId, 'x', 'x', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e'),
    ($s4Sub2, $taskId, 'poller-e2e-s4-extra-2-$runTag', 'e2e s4 extra subtask', 'ASSIGNED', $agentId, 'x', 'x', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e'),
    ($s4Sub3, $taskId, 'poller-e2e-s4-extra-3-$runTag', 'e2e s4 extra subtask', 'ASSIGNED', $agentId, 'x', 'x', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;
INSERT INTO agent_execution_record
    (id, event_id, sub_task_id, status, agent_id, access_type, trigger,
     retry_count, deleted, create_by, update_by, create_time, update_time)
VALUES
    ($s4RecordBase + 1, '$s4Prefix-1', $s4Sub1, 'PENDING', $agentId, 'API_KEY_LLM', 'assigned', 0, 0, 'e2e', 'e2e', now() - INTERVAL '240 seconds', now()),
    ($s4RecordBase + 2, '$s4Prefix-2', $s4Sub2, 'PENDING', $agentId, 'API_KEY_LLM', 'assigned', 0, 0, 'e2e', 'e2e', now() - INTERVAL '240 seconds', now()),
    ($s4RecordBase + 3, '$s4Prefix-3', $s4Sub3, 'PENDING', $agentId, 'API_KEY_LLM', 'assigned', 0, 0, 'e2e', 'e2e', now() - INTERVAL '240 seconds', now());
"@
$rc = Run-Psql -Sql $s4PrepareSql -OutFile "$s4ReportFile.pre"
if ($rc -ne 0) { Write-Error "S4 insert failed"; exit 1 }

Write-Output "poller cycles wait (5s for batch recovery)..."
Start-Sleep -Seconds 5

$s4CheckSql = @"
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE last_attempt_at IS NOT NULL) AS polled,
       COUNT(*) FILTER (WHERE status <> 'PENDING') AS progressed
FROM agent_execution_record
WHERE event_id LIKE '$s4Prefix%';

SELECT event_id, status, last_attempt_at IS NOT NULL AS polled
FROM agent_execution_record
WHERE event_id LIKE '$s4Prefix%'
ORDER BY event_id;
"@
$rc = Run-Psql -Sql $s4CheckSql -OutFile $s4ReportFile
if ($rc -ne 0) { Write-Error "S4 check failed"; exit 1 }

$s4Body = Get-Content $s4ReportFile -Raw
Write-Output "S4 batch status:"
Write-Output $s4Body

# Extract polled count and total from the aggregate row
if ($s4Body -match "(\d+)\|(\d+)\|(\d+)") {
    $s4Total = [int]$Matches[1]
    $s4Polled = [int]$Matches[2]
    $s4Progressed = [int]$Matches[3]

    Assert-Pass ($s4Total -eq 3) "S4" "total rows = 3 (got $s4Total)"
    Assert-Pass ($s4Polled -ge 3) "S4" "all 3 polled (got $s4Polled)"
    Assert-Pass ($s4Progressed -ge 1) "S4" "at least 1 progressed out of PENDING (got $s4Progressed)"
} else {
    Assert-Pass $false "S4" "unable to parse aggregate row"
}

# Also check timeline events for S4 records
$s4TimelineSql = @"
SELECT COUNT(DISTINCT sub_task_id) AS distinct_sub_tasks
FROM task_timeline
WHERE sub_task_id IN ($($subTaskId + 100), $($subTaskId + 200), $($subTaskId + 300))
  AND deleted = 0
  AND event_type = 'sub_task_execution_command_poll_recovery';
"@
$s4TimelineFile = Join-Path $scriptDir "verify-poller-e2e-s4-timeline.out"
$rc = Run-Psql -Sql $s4TimelineSql -OutFile $s4TimelineFile
if ($rc -eq 0) {
    $s4TimelineBody = Get-Content $s4TimelineFile -Raw
    Write-Output "S4 timeline: distinct sub_tasks with poll_recovery event = $s4TimelineBody"
}
Write-Output ""

# ============================================================
# S5: Poller-only recovery path (主消费路径不可达的轻量等价验证)
#
# 脚本不能直接重启 Spring Boot 关闭 MQ/Event 消费者（这会破坏 pre-flight
# 健康检查），所以这里采用等价的形态：直接 INSERT 到 agent_execution_record，
# 绕过 ExecutionCommandService.publish()。这种记录不会进入 MQ，也不会有
# 本地 Spring 事件被发布 —— Poller 是唯一可能看到它的执行者。
#
# 验证维度：
#   (a) last_attempt_at IS NOT NULL
#       —— Poller.processRecord 第一步就是 markPolled,主消费者不写这个字段。
#   (b) timeline 含 sub_task_execution_command_poll_recovery
#       —— 只有 Poller 写这个事件;LocalExecutionCommandConsumer 只写
#          sub_task_execution_command_consume。
#   (c) consume 事件的 trigger 以 poll-recovery: 开头
#       —— Poller 把原始 trigger 改成 "poll-recovery:" 前缀后再投给
#          LocalExecutionCommandConsumer.consume();主消费者自己处理时
#          trigger 是原始的 "assigned" / "recovered",不会有这个前缀。
#   (d) 反证：没有任何 trigger 不以 poll-recovery: 开头的 consume 事件
#       —— 如果出现,说明有主消费者也参与了处理,主消费路径并未隔离。
# ============================================================
Write-Output "=== [S5] Poller-only recovery path (主消费路径不可达等价) ==="
Write-Output "      Direct INSERT 绕过 publish()：没有 MQ 消息，也没有本地 Spring 事件"
Write-Output "      期望：所有处理痕迹都来自 Poller；任何主消费者信号出现都视为失败"

$s5RecordId = $subTaskId * 1000 + 555
$s5EventId  = "evt-poller-s5-$runTag"

# 用户约束：S5 用独立 sub_task（ASSIGNED）隔离，不再把共享 sample sub_task
# reset 回 PENDING。与 T5 startIfNeeded 契约对齐（ASSIGNED -> start()），让 Poller
# 推的 consume 能拿到带 trigger 字段的 sub_task_execution_command_consume 事件，
# 而不是只写 consume_skipped。title 前缀 poller-e2e-s5-sub- 不匹配 ids 选取用的
# 'poller-e2e-subtask-%'，避免被后续轮次误选为主样本。
$s5SubId = $subTaskId + 500
$s5PrepareSql = @"
DELETE FROM agent_execution_record WHERE event_id = '$s5EventId';

INSERT INTO sub_task (id, task_id, title, content, status, assigned_agent, deliverable, acceptance, priority, version, rework_count, deleted, create_by, update_by)
VALUES ($s5SubId, $taskId, 'poller-e2e-s5-sub-$runTag', 'e2e s5 poller-only subtask', 'ASSIGNED', $agentId, 'x', 'x', 'MEDIUM', 0, 0, 0, 'e2e', 'e2e')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_execution_record
    (id, event_id, sub_task_id, status, agent_id, access_type, trigger,
     retry_count, deleted, create_by, update_by, create_time, update_time)
VALUES
    ($s5RecordId, '$s5EventId', $s5SubId, 'PENDING', $agentId, 'API_KEY_LLM', 'assigned',
     0, 0, 'e2e', 'e2e', now() - INTERVAL '120 seconds', now());
"@
$rc = Run-Psql -Sql $s5PrepareSql -OutFile "$s5ReportFile.pre"
if ($rc -ne 0) { Write-Error "S5 insert failed"; exit 1 }

Write-Output "poller cycle wait (3s)..."
Start-Sleep -Seconds 3

# 一次 SQL 取齐 (a)(b)(c)(d) 四个维度
$s5CheckSql = @"
-- (a) Poller 触及标志
SELECT id, status, last_attempt_at IS NOT NULL AS polled
FROM agent_execution_record
WHERE event_id = '$s5EventId';

-- (b) Poller 专属 timeline 事件 (只有 Poller 写这一条)
SELECT event_type,
       payload->>'eventId'         AS event_id,
       payload->>'originalTrigger' AS original_trigger,
       payload->>'scan'            AS scan,
       payload->>'consumerMode'    AS consumer_mode
FROM task_timeline
WHERE sub_task_id = $s5SubId AND deleted = 0
  AND event_type = 'sub_task_execution_command_poll_recovery'
  AND payload->>'eventId' = '$s5EventId'
ORDER BY create_time DESC LIMIT 5;

-- (c) 内层消费者 consume 事件：trigger 必须以 poll-recovery: 开头
SELECT event_type,
       payload->>'eventId' AS event_id,
       payload->>'trigger' AS trigger
FROM task_timeline
WHERE sub_task_id = $s5SubId AND deleted = 0
  AND event_type = 'sub_task_execution_command_consume'
  AND payload->>'eventId' = '$s5EventId'
ORDER BY create_time DESC LIMIT 5;

-- (d) 反证：是否存在 trigger 不以 poll-recovery: 开头的 consume 事件
SELECT COUNT(*) AS rogue_consume_events
FROM task_timeline
WHERE sub_task_id = $s5SubId AND deleted = 0
  AND event_type = 'sub_task_execution_command_consume'
  AND payload->>'eventId' = '$s5EventId'
  AND (payload->>'trigger' IS NULL
       OR payload->>'trigger' NOT LIKE 'poll-recovery:%');
"@
$rc = Run-Psql -Sql $s5CheckSql -OutFile $s5ReportFile
if ($rc -ne 0) { Write-Error "S5 check failed"; exit 1 }

$s5Body = Get-Content $s5ReportFile -Raw
Write-Output "S5 raw output:"
Write-Output $s5Body

# (a) Poller markPolled 留下了 last_attempt_at
Assert-Pass ($s5Body -match '\|t(\r?\n|$)') "S5" "(a) last_attempt_at refreshed by Poller (markPolled)"

# (b) Poller 专属 timeline 事件存在
Assert-Pass ($s5Body -match "sub_task_execution_command_poll_recovery") `
    "S5" "(b) Poller 专属 timeline 事件 sub_task_execution_command_poll_recovery 存在"

# (c) 内层 consume 事件的 trigger 必须以 poll-recovery: 开头
Assert-Pass ($s5Body -match "poll-recovery:") `
    "S5" "(c) consume trigger starts with 'poll-recovery:'"

# (d) 反证：不能出现主消费者路径的痕迹
Assert-Pass ($s5Body -match 'rogue_consume_events\r?\n0(\r?\n|$)') `
    "S5" "(d) no rogue consume events (trigger not starting with poll-recovery:)"

Write-Output ""

# ============================================================
# Final summary
# ============================================================
Write-Output "============================================================"
Write-Output " Poller E2E Verifier v3.1 — RESULTS"
Write-Output "============================================================"
Write-Output " PASS: $global:PassCount"
Write-Output " FAIL: $global:FailCount"
Write-Output " runTag = $runTag"
Write-Output ""
Write-Output "Reports:"
Write-Output "  S1: $s1ReportFile"
Write-Output "  S2: $s2ReportFile"
Write-Output "  S3: $s3ReportFile"
Write-Output "  S4: $s4ReportFile"
Write-Output "  S5: $s5ReportFile"
Write-Output "  ids: $idsFile"
Write-Output ""
Write-Output "S6 (manual MQ-isolation) requires helloai.mq.execution-command"
Write-Output "   .consumer-enabled=false at Spring Boot startup; not runnable from"
Write-Output "   this script. See header comment for instructions."
Write-Output ""

if ($global:FailCount -gt 0) {
    Write-Output "=== SOME SCENARIOS FAILED ==="
    exit 1
} else {
    Write-Output "=== ALL SCENARIOS PASSED ==="
    Write-Output "Poller downgrade recovery verified: orphan PENDING records are not permanently stuck."
    exit 0
}
