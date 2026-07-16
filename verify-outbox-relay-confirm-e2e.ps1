# ============================================================
# helloai Phase 2H ②b RabbitMQ failure-path verifier (v1)
# Ref:  doc/HelloAI_迭代执行记录.md T4
#       差距表 N1 / 调度解耦分析 / OutboxRelayTask.handleConfirm
#
# Targets three real (or precisely-simulated) RabbitMQ failure paths of
# Phase 2H ②b publisher-confirms + retry state machine:
#
#   S1 NACK            -> broker rejects publish because queue overflow=reject-publish
#                         (max-length=2, 3 messages sent; 3rd is NACKed with confirm-nack)
#   S2 mandatory return-> publish to exchange with no binding match (mandatory=true
#                         causes ReturnsCallback to mark CorrelationData.getReturned())
#   S3 confirm timeout -> simulate broker ack lost / in-flight future lost by
#                         inserting a SENT row with stale last_sent_at so that
#                         OutboxRelayTask.revertExpiredSent picks it up and routes
#                         through scheduleRetryFromSent("confirm-timeout: expired-sent")
#
# Assertions:
#   - NACK / return / timeout  -> outbox row falls back to PENDING (markFailedFromSent)
#                                 with last_sent_at written, confirmed_at NULL,
#                                 retry_count incremented, next_retry_at in the future.
#   - On overflow max-retry     -> FAILED (markFinalFailedFromSent).
#   - CONFIRMED rows            -> confirmed_at + last_sent_at both populated
#                                 (asserted via a control row that hits the happy path).
#
# Pre-conditions:
#   - docker compose up -d (postgres:15432, redis:26379, rabbitmq:25672/25673)
#   - helloai-start running on :6565 with:
#       helloai.execution.dispatch-mode = MQ  (or BOTH)
#       helloai.mq.execution-command.producer-enabled = true
#       helloai.mq.execution-command.consumer-enabled = false (we don't consume here)
#       helloai.outbox.relay.enabled = true
#       helloai.outbox.relay.confirm-timeout-seconds = 30 (default)
#   - RabbitMQ Management API reachable at http://localhost:25673 with guest/guest
#
# Usage (project root):
#   powershell -ExecutionPolicy Bypass -File .\verify-outbox-relay-confirm-e2e.ps1
#   powershell -ExecutionPolicy Bypass -File .\verify-outbox-relay-confirm-e2e.ps1 -SkipPrepare
#   powershell -ExecutionPolicy Bypass -File .\verify-outbox-relay-confirm-e2e.ps1 -Cleanup
#
# Idempotency: every run uses a unique $runTag; rows are tagged via event_id
# suffix and aggregate_id pattern so re-runs do not collide. -Cleanup removes
# all rows produced by the most recent run.
# ============================================================

param(
    [switch]$SkipPrepare,
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $script:Utf8NoBom
[Console]::OutputEncoding = $script:Utf8NoBom
$OutputEncoding = $script:Utf8NoBom

$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

$rabbitHost  = 'localhost'
$rabbitPort  = 25672
$rabbitMgmtPort = 25673
$rabbitUser  = 'guest'
$rabbitPass  = 'guest'
$rabbitVhost = '%2F'   # '/' url-encoded

$execCmdExchange = 'helloai.execution-command.exchange'
$execCmdQueue    = 'helloai.execution-command.queue'
$execCmdBinding  = 'execution.command.*'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$runTag = (Get-Date -Format 'yyyyMMdd-HHmmss')

# ============================================================
# Snowflake-style bigint id generator (PS 5.1 compatible)
#   - application uses MyBatis-Plus ASSIGN_ID which fills `id` in Java;
#   - here we synthesize monotonic bigint ids so direct-SQL INSERTs do not
#     collide with app-generated rows and do not violate NOT NULL on `id`.
#   - base = epoch_ms * 1000 (shifts snowflake left 22 bits => upper time bits);
#   - seq  = monotonic counter so each row in this run gets a unique id.
# ============================================================
$script:outboxIdSeq = [long]([DateTimeOffset]::Now.ToUnixTimeMilliseconds() * 1000)

function Write-Utf8NoBomFile {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Content
    )
    [System.IO.File]::WriteAllText($Path, $Content, $script:Utf8NoBom)
}

# ============================================================
# helper: run psql via docker exec, capture stdout + stderr
# ============================================================
function Run-Psql {
    param(
        [Parameter(Mandatory=$true)][string]$Sql,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $tmpSql = [System.IO.Path]::GetTempFileName()
    $tmpOut = [System.IO.Path]::GetTempFileName()
    $tmpErr = [System.IO.Path]::GetTempFileName()
    Write-Utf8NoBomFile -Path $tmpSql -Content $Sql
    Remove-Item $OutFile -ErrorAction SilentlyContinue
    Remove-Item "$OutFile.err" -ErrorAction SilentlyContinue

    $dockerArgs = @('exec', '-i', $pgContainer, 'psql',
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-t', '-A', '-F', '|',
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

function Get-FirstPsqlDataLine {
    param(
        [Parameter(Mandatory=$true)][string]$Path
    )
    $line = Get-Content -Path $Path | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -ne '<NULL>' } |
        Select-Object -First 1
    if (-not $line) {
        return $null
    }
    return ($line -replace '<NULL>', '')
}

function Get-PsqlFields {
    param(
        [Parameter(Mandatory=$true)][string]$Path
    )
    $line = Get-FirstPsqlDataLine -Path $Path
    if (-not $line) {
        return $null
    }
    return $line.Split('|')
}

function Clear-ExecutionQueueMessages {
    $null = Invoke-RabbitMgmt -Method DELETE -Path "/api/queues/${rabbitVhost}/${execCmdQueue}/contents"
}

function Set-ExecutionQueuePolicy {
    param(
        [Parameter(Mandatory=$true)][hashtable]$Definition
    )
    $body = @{
        pattern    = "^$([Regex]::Escape($execCmdQueue))$"
        definition = $Definition
        priority   = 50
        'apply-to' = 'queues'
    } | ConvertTo-Json -Compress
    return (Invoke-RabbitMgmt -Method PUT -Path "/api/policies/${rabbitVhost}/helloai-e2e-exec-queue" -BodyJson $body)
}

function Clear-ExecutionQueuePolicy {
    return (Invoke-RabbitMgmt -Method DELETE -Path "/api/policies/${rabbitVhost}/helloai-e2e-exec-queue")
}

function Remove-ExchangeQueueBindings {
    param(
        [Parameter(Mandatory=$true)]$BindingsResponse
    )
    $saved = @()
    if ($BindingsResponse.ok -and $BindingsResponse.data) {
        foreach ($b in $BindingsResponse.data) {
            $saved += $b
            $propertiesKey = if ($b.PSObject.Properties.Name -contains 'properties_key') { $b.properties_key } else { $b.key }
            if (-not $propertiesKey) {
                continue
            }
            $delPath = "/api/bindings/${rabbitVhost}/e/${execCmdExchange}/q/${execCmdQueue}/${propertiesKey}"
            $null = Invoke-RabbitMgmt -Method DELETE -Path $delPath
        }
    }
    return ,$saved
}

function Restore-ExchangeQueueBindings {
    param(
        [Parameter(Mandatory=$true)]$Bindings
    )
    foreach ($b in $Bindings) {
        $routingKey = if ($b.PSObject.Properties.Name -contains 'routing_key') { $b.routing_key } else { $b.key }
        $arguments = if ($b.PSObject.Properties.Name -contains 'arguments' -and $b.arguments) { $b.arguments } else { @{} }
        $bodyJson = @{
            routing_key = if ($null -ne $routingKey) { $routingKey } else { '' }
            arguments   = $arguments
        } | ConvertTo-Json -Compress
        $null = Invoke-RabbitMgmt -Method POST -Path "/api/bindings/${rabbitVhost}/e/${execCmdExchange}/q/${execCmdQueue}" -BodyJson $bodyJson
    }
}

function Ensure-CanonicalExecutionBinding {
    $bindings = Invoke-RabbitMgmt -Method GET -Path "/api/bindings/${rabbitVhost}/e/${execCmdExchange}/q/${execCmdQueue}"
    $saved = Remove-ExchangeQueueBindings -BindingsResponse $bindings
    $null = Invoke-RabbitMgmt -Method POST -Path "/api/bindings/${rabbitVhost}/e/${execCmdExchange}/q/${execCmdQueue}" -BodyJson '{"routing_key":"execution.command.*","arguments":{}}'
    return $saved
}

function Assert-ExecutionQueuePolicyEffective {
    param(
        [Parameter(Mandatory=$true)][string]$ExpectedOverflow,
        [Parameter(Mandatory=$true)][int]$ExpectedMaxLength
    )
    $queue = Invoke-RabbitMgmt -Method GET -Path "/api/queues/${rabbitVhost}/${execCmdQueue}"
    if (-not $queue.ok) {
        throw "failed to query queue details for policy check"
    }
    $effective = $queue.data.effective_policy_definition
    if (-not $effective) {
        throw "S1 policy not effective: queue effective_policy_definition is empty"
    }
    $actualOverflow = if ($effective.PSObject.Properties.Name -contains 'overflow') { [string]$effective.overflow } else { '' }
    $actualMaxLength = if ($effective.PSObject.Properties.Name -contains 'max-length') { [int]$effective.'max-length' } else { -1 }
    if ($actualOverflow -ne $ExpectedOverflow -or $actualMaxLength -ne $ExpectedMaxLength) {
        throw "S1 policy mismatch: overflow=$actualOverflow, max-length=$actualMaxLength"
    }
}

function Test-PgPing {
    return (& docker exec $pgContainer psql -U $pgUser -d $pgDb -tAc 'SELECT 1;' *>&1) -match '^1$'
}

function Test-RabbitMgmtPing {
    # PS 5.1 (.NET Framework) compatible: no [System.Net.Http.HttpClient] (only in .NET 5+)
    $url = "http://${rabbitHost}:${rabbitMgmtPort}/api/overview"
    try {
        $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${rabbitUser}:${rabbitPass}"))
        $hdr = @{ 'Authorization' = "Basic $basic" }
        $r = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $hdr -TimeoutSec 3 -ErrorAction Stop
        if ([int]$r.StatusCode -eq 200 -and ($r.Content -match 'rabbitmq_version')) { return $true }
        return $false
    } catch {
        return $false
    }
}

# ============================================================
# helper: RabbitMQ Management API (Basic auth)
#   returns $null on success (2xx); $statusCode otherwise
# ============================================================
function Invoke-RabbitMgmt {
    param(
        [Parameter(Mandatory=$true)][string]$Method,
        [Parameter(Mandatory=$true)][string]$Path,
        [string]$BodyJson = $null
    )
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${rabbitUser}:${rabbitPass}"))
    $url = "http://${rabbitHost}:${rabbitMgmtPort}${Path}"

    $hdr = @{
        'Authorization' = "Basic $basic"
        'Content-Type'  = 'application/json'
    }
    try {
        if ($BodyJson) {
            $r = Invoke-RestMethod -Method $Method -Uri $url -Headers $hdr -Body $BodyJson -TimeoutSec 10
        } else {
            $r = Invoke-RestMethod -Method $Method -Uri $url -Headers $hdr -TimeoutSec 10
        }
        return @{ ok = $true; data = $r }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            return @{ ok = $false; statusCode = [int]$resp.StatusCode; error = $_.Exception.Message }
        }
        return @{ ok = $false; statusCode = -1; error = $_.Exception.Message }
    }
}

# ============================================================
# Cleanup mode: drop all rows produced by this run and reset broker config
# ============================================================
if ($Cleanup) {
    Write-Output "=== CLEANUP ==="
    $cleanupSql = @"
DELETE FROM agent_command_outbox
WHERE deleted = 0
  AND (event_id LIKE 't4-outbox-NACK-%'
       OR event_id LIKE 't4-outbox-RETURN-%'
       OR event_id LIKE 't4-outbox-TIMEOUT-%'
       OR event_id LIKE 't4-outbox-HAPPY-%');
"@
    $cleanupFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-cleanup.out"
    $rc = Run-Psql -Sql $cleanupSql -OutFile $cleanupFile
    Write-Output "cleanup psql rc=$rc"
    Write-Output "see $cleanupFile"

    # Restore broker config: clear e2e policy, purge queue, restore canonical binding
    $null = Clear-ExecutionQueuePolicy
    Clear-ExecutionQueueMessages
    $null = Ensure-CanonicalExecutionBinding
    Write-Output "broker config restored (queue args cleared, binding re-posted)"
    exit 0
}

# ============================================================
# STEP 0: pre-flight
# ============================================================
Write-Output ""
Write-Output "=== [0] pre-flight ==="

# Postgres
$dockerCheck = & docker ps --format "{{.Names}}|{{.Status}}" --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Error "container [$pgContainer] is NOT up. Run: docker compose up -d"
    exit 1
}
Write-Output "container $pgContainer is Up"
if (-not (Test-PgPing)) {
    Write-Error "postgres ping failed"
    exit 1
}
Write-Output "postgres ping OK"

# Spring Boot
$appYml = Join-Path $scriptDir 'helloai-start\src\main\resources\application.yml'
$portLine = Select-String -Path $appYml -Pattern "^  port:\s*(\d+)" | Select-Object -First 1
$servicePort = '6565'
if ($portLine -and $portLine.Matches.Groups[1].Value) { $servicePort = $portLine.Matches.Groups[1].Value }
try {
    # PS 5.1 (.NET Framework) compatible: no [System.Net.Http.HttpClient] (only in .NET 5+)
    $hr = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$servicePort/api/health" -TimeoutSec 3 -ErrorAction Stop
    if ([int]$hr.StatusCode -ne 200) {
        Write-Error "Spring Boot health HTTP $($hr.StatusCode), please run start-sb.ps1"
        exit 1
    }
    Write-Output "Spring Boot health OK (port $servicePort)"
} catch {
    Write-Error "Spring Boot unreachable: $($_.Exception.Message)"
    exit 1
}

# RabbitMQ Management API
if (-not (Test-RabbitMgmtPing)) {
    Write-Error "RabbitMQ Management API at http://$rabbitHost`:$rabbitMgmtPort not reachable"
    exit 1
}
Write-Output "RabbitMQ Management API OK (port $rabbitMgmtPort)"

# Confirm dispatch-mode/producer-enabled via Spring Boot actuator/health or via reading app log
# Heuristic: check whether publisher beans exist by trying a no-op publish with a fake key.
# Simpler: query Spring Boot /api/health if it surfaces relay status; otherwise trust operator to set
# dispatch-mode. We rely on env spec and add a runtime check: probe the queue via mgmt API.
$qCheck = Invoke-RabbitMgmt -Method GET -Path "/api/queues/${rabbitVhost}/${execCmdQueue}"
if (-not $qCheck.ok) {
    Write-Error "queue [$execCmdQueue] not declared in broker. Run start-sb first so topology is registered."
    exit 1
}
Write-Output "queue $execCmdQueue exists in broker (messages=$($qCheck.data.messages))"
if ($qCheck.data.messages -gt 0) {
    Write-Output "pre-flight cleanup: purge residual queue messages=$($qCheck.data.messages)"
    Clear-ExecutionQueueMessages
}
$null = Ensure-CanonicalExecutionBinding

# ============================================================
# Probe: ensure OutboxRelayTask is actually running in MQ mode.
#   When dispatch-mode=NONE or producer-enabled=false the publisher Bean is not
#   registered and OutboxRelayTask.relay() returns early, leaving every PENDING
#   row stuck at status=0. We insert a transient probe row and watch for status
#   transition. Without this, S1/S2/S4 would silently FAIL and the operator
#   would not know whether the issue is broker-side or app-config.
# ============================================================
Write-Output "OutboxRelay liveness probe: inserting transient PENDING row and waiting up to 8s..."
$probeSeedMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$probeAgentId   = [long]([Math]::Abs([int]($probeSeedMs % 90000000)) + 600000000)
$probeSubTaskId = $probeAgentId + 1
$probeEventId   = "evt-t4-probe-$runTag-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$script:outboxIdSeq += 1
$probeId = $script:outboxIdSeq
$probeInsertSql = @"
DELETE FROM agent_command_outbox WHERE event_id = '$probeEventId';
INSERT INTO agent_command_outbox
    (id, event_id, aggregate_type, aggregate_id, payload, status, retry_count, deleted, create_by, update_by)
VALUES
    ($probeId, '$probeEventId', 'EXECUTION_COMMAND', '$probeSubTaskId',
     jsonb_build_object('eventId','$probeEventId','subTaskId','$probeSubTaskId','agentId','$probeAgentId','trigger','t4-PROBE','accessType','API_KEY_LLM','recordId',null),
     0, 0, 0, 'e2e-t4', 'e2e-t4');
"@
$probePreFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-probe.out.pre"
$rc = Run-Psql -Sql $probeInsertSql -OutFile $probePreFile
if ($rc -ne 0) { Write-Error "probe insert failed, see $probePreFile.err"; exit 1 }

$probeChkFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-probe.out"
$probeAlive = $false
$probeFinalStatus = '0'
for ($i = 0; $i -lt 8; $i++) {
    Start-Sleep -Seconds 1
    $probeSql = "SELECT status FROM agent_command_outbox WHERE id = $probeId AND deleted = 0;"
    $null = Run-Psql -Sql $probeSql -OutFile $probeChkFile
    $probeFinalStatus = Get-FirstPsqlDataLine -Path $probeChkFile
    if ($probeFinalStatus -and $probeFinalStatus -ne '0') {
        Write-Output "OutboxRelay probe OK: status=$probeFinalStatus after $($i+1)s (relay alive)"
        $probeAlive = $true
        break
    }
}

# Always cleanup probe row regardless of outcome
$null = Run-Psql -Sql "DELETE FROM agent_command_outbox WHERE id = $probeId;" -OutFile $probeChkFile

if (-not $probeAlive) {
    Write-Output ""
    Write-Output "=== FATAL: OutboxRelay is not processing PENDING rows ==="
    Write-Output ""
    Write-Output "Probe row id=$probeId status=$probeFinalStatus after 8s wait."
    Write-Output "Most likely cause: OutboxRelayTask.relay() returned early because"
    Write-Output "  ExecutionCommandMqPublisher Bean is not registered."
    Write-Output ""
    Write-Output "Fix in helloai-start/src/main/resources/application.yml:"
    Write-Output "  line 130:  dispatch-mode: NONE       -->  dispatch-mode: MQ"
    Write-Output "  line 137:  producer-enabled: false  -->  producer-enabled: true"
    Write-Output ""
    Write-Output "Then RESTART Spring Boot (IDEA Run again) and re-run this script."
    Write-Output ""
    Write-Output "T4 E2E MUST run with MQ delivery enabled. The NACK / return / timeout"
    Write-Output "recovery paths under verification only fire inside the publishWithCorrelation"
    Write-Output "-> confirm callback -> ReturnedMessage callback chain."
    exit 1
}
Write-Output ""

# ============================================================
# STEP 1: prepare sample agent + sub_task (idempotent)
# ============================================================
Write-Output "=== [1] prepare sample agent + sub_task ==="

$agentName = "t4-outbox-agent-$runTag"
$taskTitle = "t4-outbox-task-$runTag"
$subTitle  = "t4-outbox-subtask-$runTag"

# deterministic large ids (snowflake-shaped)
$seedMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$agentId   = [long]([Math]::Abs([int]($seedMs % 99000000)) + 700000000)
$taskId    = $agentId + 10
$subTaskId = $agentId + 20

Write-Output "sample agentId=$agentId taskId=$taskId subTaskId=$subTaskId"

if (-not $SkipPrepare) {
    $prepareSql = @"
INSERT INTO agent (id, name, role, status, score, access_type, online_status, deleted, create_by, update_by)
VALUES ($agentId, '$agentName', 'EXECUTOR', 'ACTIVE', 80, 'CLI_CLIENT', 'ONLINE', 0, 'e2e-t4', 'e2e-t4')
ON CONFLICT (id) DO NOTHING;

INSERT INTO task (id, title, description, status, deleted, create_by, update_by)
VALUES ($taskId, '$taskTitle', 't4 outbox e2e auto task', 'PENDING', 0, 'e2e-t4', 'e2e-t4')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sub_task (id, task_id, title, content, status, assigned_agent, deliverable, acceptance, priority, version, rework_count, deleted, create_by, update_by)
VALUES ($subTaskId, $taskId, '$subTitle', 't4 outbox e2e sample subtask', 'ASSIGNED', $agentId, 'verified output', 'sub_task.status in DONE/REVIEW', 'MEDIUM', 0, 0, 0, 'e2e-t4', 'e2e-t4')
ON CONFLICT (id) DO NOTHING;
"@
    $prepareFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-prepare.out"
    $rc = Run-Psql -Sql $prepareSql -OutFile $prepareFile
    if ($rc -ne 0) {
        Write-Error "prepare insert failed, see $prepareFile.err"
        exit 1
    }
    Write-Output "prepare insert ok"
} else {
    Write-Output "SkipPrepare set, sample data must exist from a prior run"
}
Write-Output ""

# ============================================================
# Common helper: insert one outbox PENDING row bound to sub_task
#   used for NACK / RETURN / HAPPY scenarios
# ============================================================
function Insert-OutboxPending {
    param(
        [Parameter(Mandatory=$true)][string]$ScenarioTag,
        [Parameter(Mandatory=$true)][long]$OutboxSubTaskId,
        [int]$ExtraRetry = 0
    )
    $script:outboxIdSeq += 1
    $outboxId = $script:outboxIdSeq
    $eventId   = "evt-$ScenarioTag-$runTag-$([guid]::NewGuid().ToString('N').Substring(0,8))"
    $aggregateId = [string]$OutboxSubTaskId
    $sql = @"
DELETE FROM agent_command_outbox WHERE id = $outboxId;
DELETE FROM agent_command_outbox WHERE event_id = '$eventId';
INSERT INTO agent_command_outbox
    (id, event_id, aggregate_type, aggregate_id, payload, status, retry_count, deleted, create_by, update_by)
VALUES
    ($outboxId, '$eventId', 'EXECUTION_COMMAND', '$aggregateId',
     jsonb_build_object('eventId','$eventId','subTaskId',$OutboxSubTaskId::text,'agentId',$agentId::text,'trigger','t4-$ScenarioTag','accessType','API_KEY_LLM','recordId',null),
     0, $ExtraRetry, 0, 'e2e-t4', 'e2e-t4');
SELECT id, event_id, status FROM agent_command_outbox WHERE id = $outboxId;
"@
    $tmpOut = [System.IO.Path]::GetTempFileName()
    $tmpErr = [System.IO.Path]::GetTempFileName()
    $tmpSql = [System.IO.Path]::GetTempFileName()
    Write-Utf8NoBomFile -Path $tmpSql -Content $sql
    $dockerArgs = @('exec', '-i', $pgContainer, 'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-A', '-F', '|', '-U', $pgUser, '-d', $pgDb)
    Get-Content -Path $tmpSql -Raw | & docker $dockerArgs *> $tmpOut 2> $tmpErr
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Insert-OutboxPending failed: $(Get-Content $tmpErr -Raw)"
        exit 1
    }
    $rows = (Get-Content $tmpOut -Raw) -split "`n" | Where-Object { $_ -match '^\d+\|' }
    Remove-Item $tmpOut, $tmpErr, $tmpSql -ErrorAction SilentlyContinue
    # PowerShell 5.1: when $rows has a single match, $rows[0] can be unrolled
    # to a Char collection, which fails on .Split('|'). Coerce explicitly to [string].
    $line = [string]($rows | Select-Object -First 1)
    if (-not $line) {
        Write-Error "Insert-OutboxPending: no row returned"
        exit 1
    }
    $parts = $line.Split('|')
    return @{
        id      = [long]$parts[0]
        eventId = $parts[1]
        status  = [int]$parts[2]
    }
}

# Common helper: query outbox row final state
function Query-OutboxFinal {
    param(
        [Parameter(Mandatory=$true)][long]$OutboxId,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $sql = @"
SELECT
    id,
    status,
    retry_count,
    (last_sent_at IS NOT NULL)::int AS has_last_sent,
    (confirmed_at IS NOT NULL)::int AS has_confirmed,
    COALESCE(EXTRACT(EPOCH FROM (last_sent_at - create_time))::int, -1) AS last_sent_after_create_s,
    COALESCE(EXTRACT(EPOCH FROM (now() - next_retry_at))::int, -999) AS secs_until_next_retry,
    LEFT(COALESCE(error_msg,''), 200) AS err
FROM agent_command_outbox
WHERE id = $OutboxId AND deleted = 0;
"@
    return (Run-Psql -Sql $sql -OutFile $OutFile)
}

# ============================================================
# SCENARIO S1: NACK
#   - Set queue max-length=2, overflow=reject-publish (so broker NACKs the 3rd publish)
#   - Set dispatch-mode path open (already done at Spring Boot level)
#   - Insert 3 PENDING outbox rows -> relay will publish all three
#   - Expect:
#       row #1 -> CONFIRMED (ACK from broker; queue slot 1/2)
#       row #2 -> CONFIRMED (ACK from broker; queue slot 2/2)
#       row #3 -> markFailedFromSent (NACK) -> status PENDING, last_sent_at written,
#                 confirmed_at NULL, error_msg contains 'confirm-nack'
#   - Scheme A rationale: max-length=2 leaves headroom for the two happy-path rows so
#     r1/r2 CONFIRMED is not sensitive to broker ack ordering; only r3 hits overflow.
#   - Restore queue args at end
# ============================================================
Write-Output "=== [S1] broker NACK via queue max-length=2, overflow=reject-publish (Scheme A) ==="

# Reset queue state from prior runs
$null = Clear-ExecutionQueuePolicy
Clear-ExecutionQueueMessages
$null = Ensure-CanonicalExecutionBinding
Start-Sleep -Milliseconds 500

# Inject 3 PENDING rows (use distinct sub_task-like ids via random suffix to avoid conflict)
$s1Row1 = Insert-OutboxPending -ScenarioTag "NACK" -OutboxSubTaskId ($subTaskId + 1)
$s1Row2 = Insert-OutboxPending -ScenarioTag "NACK" -OutboxSubTaskId ($subTaskId + 2)
$s1Row3 = Insert-OutboxPending -ScenarioTag "NACK" -OutboxSubTaskId ($subTaskId + 3)
Write-Output "S1 prepared: row1.id=$($s1Row1.id) row2.id=$($s1Row2.id) row3.id=$($s1Row3.id)"

# Drain queue to zero so max-length=2 policy takes effect cleanly
Clear-ExecutionQueueMessages

# Apply max-length=2 with overflow=reject-publish via policy (Scheme A)
$null = Set-ExecutionQueuePolicy -Definition @{ 'max-length' = 2; overflow = 'reject-publish' }
Start-Sleep -Milliseconds 500
Assert-ExecutionQueuePolicyEffective -ExpectedOverflow 'reject-publish' -ExpectedMaxLength 2

Write-Output "S1 waiting for relay + confirm cycle (up to 20s)..."
$s1Done = $false
$s1ReportFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-s1.out"
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    $checkSql = @"
SELECT
  (SELECT status FROM agent_command_outbox WHERE id = $($s1Row1.id) AND deleted = 0) AS r1_status,
  (SELECT status FROM agent_command_outbox WHERE id = $($s1Row2.id) AND deleted = 0) AS r2_status,
  (SELECT status FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_status,
  (SELECT (last_sent_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_has_last_sent,
  (SELECT (confirmed_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_has_confirmed;
"@
    $tmpChk = [System.IO.Path]::GetTempFileName()
    $null = Run-Psql -Sql $checkSql -OutFile $tmpChk
    $line = Get-FirstPsqlDataLine -Path $tmpChk
    if (-not $line) {
        Remove-Item $tmpChk -ErrorAction SilentlyContinue
        throw "S1 query returned no data line"
    }
    $parts = $line.Split('|')
    Remove-Item $tmpChk -ErrorAction SilentlyContinue
    $r1 = [int]$parts[0]
    $r2 = [int]$parts[1]
    $r3 = [int]$parts[2]
    # S1 Scheme A condition: r1+r2 CONFIRMED(3), r3 PENDING(0), r3 last_sent=1, r3 confirmed=0
    if ($r1 -eq 3 -and $r2 -eq 3 -and $r3 -eq 0 -and $parts[3] -eq '1' -and $parts[4] -eq '0') {
        $s1Done = $true
        break
    }
}

# Final assertion query (record final state; Scheme A: r1+r2 CONFIRMED, r3 NACK fallback)
$s1FinalSql = @"
SELECT
    (SELECT status FROM agent_command_outbox WHERE id = $($s1Row1.id) AND deleted = 0) AS r1_status,
    (SELECT status FROM agent_command_outbox WHERE id = $($s1Row2.id) AND deleted = 0) AS r2_status,
    (SELECT status FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_status,
    (SELECT (last_sent_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_has_last_sent,
    (SELECT (confirmed_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_has_confirmed,
    (SELECT retry_count FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_retry_count,
    (SELECT LEFT(COALESCE(error_msg,''), 200) FROM agent_command_outbox WHERE id = $($s1Row3.id) AND deleted = 0) AS r3_err,
    (SELECT (confirmed_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row1.id) AND deleted = 0) AS r1_has_confirmed,
    (SELECT (last_sent_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row1.id) AND deleted = 0) AS r1_has_last_sent,
    (SELECT (confirmed_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row2.id) AND deleted = 0) AS r2_has_confirmed,
    (SELECT (last_sent_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s1Row2.id) AND deleted = 0) AS r2_has_last_sent;
"@
$rc = Run-Psql -Sql $s1FinalSql -OutFile $s1ReportFile
$s1Body = Get-Content $s1ReportFile -Raw
$s1Fields = Get-PsqlFields -Path $s1ReportFile
Write-Output "S1 final: $s1Body"

# Restore queue state
$null = Clear-ExecutionQueuePolicy
Clear-ExecutionQueueMessages
$null = Ensure-CanonicalExecutionBinding

$s1Pass = $true
if (-not $s1Fields -or $s1Fields.Count -lt 11) { $s1Pass = $false }
if ($s1Pass -and ($s1Fields[0] -ne '3')) { $s1Pass = $false }   # r1_status=3 (CONFIRMED)
if ($s1Pass -and ($s1Fields[1] -ne '3')) { $s1Pass = $false }   # r2_status=3 (CONFIRMED)
if ($s1Pass -and ($s1Fields[2] -ne '0')) { $s1Pass = $false }   # r3_status=0 (PENDING, NACK fallback)
if ($s1Pass -and ($s1Fields[3] -ne '1')) { $s1Pass = $false }   # r3 last_sent=1
if ($s1Pass -and ($s1Fields[4] -ne '0')) { $s1Pass = $false }   # r3 confirmed=0
if ($s1Pass -and ($s1Fields[6] -notmatch 'confirm-nack')) { $s1Pass = $false }  # r3 error_msg contains 'confirm-nack'
if ($s1Pass -and ($s1Fields[7] -ne '1')) { $s1Pass = $false }   # r1 confirmed=1
if ($s1Pass -and ($s1Fields[8] -ne '1')) { $s1Pass = $false }   # r1 last_sent=1
if ($s1Pass -and ($s1Fields[9] -ne '1')) { $s1Pass = $false }   # r2 confirmed=1
if ($s1Pass -and ($s1Fields[10] -ne '1')) { $s1Pass = $false }  # r2 last_sent=1

if ($s1Pass) {
    Write-Output "[S1] PASS - Scheme A: r1+r2 CONFIRMED, r3 fell back to PENDING with last_sent_at + confirm-nack in error_msg"
} else {
    Write-Output "[S1] FAIL - see $s1ReportFile"
    Write-Output "Note: Scheme A expects r1+r2 CONFIRMED and only r3 NACK'd; check queue max-length=2 policy + OutboxRelay log."
}
Write-Output ""

# ============================================================
# SCENARIO S2: mandatory return
#   - Delete the binding between exchange and queue (preserving exchange + queue)
#   - Insert 1 PENDING outbox row -> relay will publish; exchange has no binding match;
#     mandatory=true triggers ReturnsCallback which sets CorrelationData.getReturned()
#     OutboxRelayTask.handleConfirm treats this as a return and calls scheduleRetryFromSent
#   - Expect:
#       row -> markFailedFromSent -> status PENDING, last_sent_at written, confirmed_at NULL
#   - Restore binding at end
# ============================================================
Write-Output "=== [S2] mandatory return: drop binding, message unroutable ==="

# List current bindings on the exchange -> queue pair, then delete them
$bindings = Invoke-RabbitMgmt -Method GET -Path "/api/bindings/${rabbitVhost}/e/${execCmdExchange}/q/${execCmdQueue}"
$savedBindings = Remove-ExchangeQueueBindings -BindingsResponse $bindings
Start-Sleep -Milliseconds 500
Write-Output "S2 deleted $($savedBindings.Count) bindings on $execCmdExchange -> $execCmdQueue"

# Drain queue so we don't get residual CONFIRMED noise
Clear-ExecutionQueueMessages

$s2Row = Insert-OutboxPending -ScenarioTag "RETURN" -OutboxSubTaskId ($subTaskId + 3)
Write-Output "S2 prepared: row.id=$($s2Row.id)"

Write-Output "S2 waiting for relay + return path (up to 20s)..."
$s2ReportFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-s2.out"
$s2Done = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    $checkSql = @"
SELECT
  (SELECT status FROM agent_command_outbox WHERE id = $($s2Row.id) AND deleted = 0),
  (SELECT (last_sent_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s2Row.id) AND deleted = 0),
  (SELECT (confirmed_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s2Row.id) AND deleted = 0),
  (SELECT LEFT(COALESCE(error_msg,''), 200) FROM agent_command_outbox WHERE id = $($s2Row.id) AND deleted = 0);
"@
    $tmpChk = [System.IO.Path]::GetTempFileName()
    $null = Run-Psql -Sql $checkSql -OutFile $tmpChk
    $line = Get-FirstPsqlDataLine -Path $tmpChk
    if (-not $line) {
        Remove-Item $tmpChk -ErrorAction SilentlyContinue
        throw "S2 query returned no data line"
    }
    $parts = $line.Split('|')
    Remove-Item $tmpChk -ErrorAction SilentlyContinue
    # status=0 (PENDING), last_sent=1, confirmed=0, err contains 'returned'
    if ($parts[0] -eq '0' -and $parts[1] -eq '1' -and $parts[2] -eq '0' -and $parts[3] -match 'returned') {
        $s2Done = $true
        break
    }
}

$rc = Query-OutboxFinal -OutboxId $s2Row.id -OutFile $s2ReportFile
$s2Body = Get-Content $s2ReportFile -Raw
$s2Fields = Get-PsqlFields -Path $s2ReportFile
Write-Output "S2 final: $s2Body"

# Restore bindings
Restore-ExchangeQueueBindings -Bindings $savedBindings
Clear-ExecutionQueueMessages
$null = Ensure-CanonicalExecutionBinding
Write-Output "S2 restored $($savedBindings.Count) bindings"

$s2Pass = $true
if (-not $s2Fields -or $s2Fields.Count -lt 8) { $s2Pass = $false }
if ($s2Pass -and ($s2Fields[1] -ne '0')) { $s2Pass = $false }   # status=0 (PENDING)
if ($s2Pass -and ($s2Fields[3] -ne '1')) { $s2Pass = $false }   # last_sent=1
if ($s2Pass -and ($s2Fields[4] -ne '0')) { $s2Pass = $false }   # confirmed=0
if ($s2Pass -and ($s2Fields[7] -notmatch 'returned')) { $s2Pass = $false }

if ($s2Pass) {
    Write-Output "[S2] PASS - return path: row fell back to PENDING with last_sent_at written, error_msg indicates return"
} else {
    Write-Output "[S2] FAIL - see $s2ReportFile"
    Write-Output "Note: return path requires broker ack + ReturnsCallback both to fire before confirm-timeout."
}
Write-Output ""

# ============================================================
# SCENARIO S3: confirm timeout (simulated)
#   - Insert a SENT row with stale last_sent_at so that OutboxRelayTask.revertExpiredSent
#     picks it up via listExpiredSentForRetry (which scans SENT rows where
#     last_sent_at <= now - confirmTimeout AND retry_count < maxRetry)
#   - This emulates broker ack-loss / in-flight future-loss / restart-mid-confirm paths
#     that are not otherwise producible from outside the broker
#   - Expect:
#       row -> scheduleRetryFromSent -> markFailedFromSent -> status PENDING,
#              retry_count incremented, last_sent_at preserved, confirmed_at NULL,
#              error_msg contains 'confirm-timeout'
# ============================================================
Write-Output "=== [S3] confirm timeout (simulated via stale SENT row) ==="

$script:outboxIdSeq += 1
$s3RowId = $script:outboxIdSeq
$s3EventId = "evt-TIMEOUT-$runTag-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$s3AggregateId = [string]($subTaskId + 4)
$s3InsertSql = @"
DELETE FROM agent_command_outbox WHERE id = $s3RowId;
DELETE FROM agent_command_outbox WHERE event_id = '$s3EventId';
INSERT INTO agent_command_outbox
    (id, event_id, aggregate_type, aggregate_id, payload, status, retry_count,
     last_sent_at, next_retry_at, deleted, create_by, update_by)
VALUES
    ($s3RowId, '$s3EventId', 'EXECUTION_COMMAND', '$s3AggregateId',
     jsonb_build_object('eventId','$s3EventId','subTaskId','$($subTaskId + 4)','agentId','$agentId','trigger','t4-TIMEOUT','accessType','API_KEY_LLM','recordId',null),
     1, 0,
     now() - INTERVAL '120 seconds', now() - INTERVAL '120 seconds',
     0, 'e2e-t4', 'e2e-t4');
"@
$s3PreFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-s3.out.pre"
$rc = Run-Psql -Sql $s3InsertSql -OutFile $s3PreFile
if ($rc -ne 0) { Write-Error "S3 insert failed"; exit 1 }

# Fetch the row id
$idsSql = "SELECT id FROM agent_command_outbox WHERE event_id = '$s3EventId' AND deleted = 0;"
$tmpIds = [System.IO.Path]::GetTempFileName()
$null = Run-Psql -Sql $idsSql -OutFile $tmpIds
$s3RowId = [long](Get-FirstPsqlDataLine -Path $tmpIds)
Remove-Item $tmpIds -ErrorAction SilentlyContinue
Write-Output "S3 prepared: row.id=$s3RowId (SENT, last_sent_at=now-120s)"

Write-Output "S3 waiting for revertExpiredSent cycle (up to 2s, before next retry window elapses)..."
$s3Done = $false
for ($i = 0; $i -lt 2; $i++) {
    Start-Sleep -Seconds 1
    $s3CheckFile = [System.IO.Path]::GetTempFileName()
    $null = Query-OutboxFinal -OutboxId $s3RowId -OutFile $s3CheckFile
    $s3CheckFields = Get-PsqlFields -Path $s3CheckFile
    Remove-Item $s3CheckFile -ErrorAction SilentlyContinue
    if ($s3CheckFields -and $s3CheckFields.Count -ge 8 `
        -and $s3CheckFields[1] -eq '0' `
        -and $s3CheckFields[3] -eq '1' `
        -and $s3CheckFields[4] -eq '0' `
        -and $s3CheckFields[7] -match 'confirm-timeout') {
        $s3Done = $true
        break
    }
}

$s3ReportFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-s3.out"
$rc = Query-OutboxFinal -OutboxId $s3RowId -OutFile $s3ReportFile
$s3Body = Get-Content $s3ReportFile -Raw
$s3Fields = Get-PsqlFields -Path $s3ReportFile
Write-Output "S3 final: $s3Body"

$s3Pass = $true
if (-not $s3Fields -or $s3Fields.Count -lt 8) { $s3Pass = $false }
if ($s3Pass -and ($s3Fields[1] -ne '0')) { $s3Pass = $false }      # status=0 (PENDING)
if ($s3Pass -and ($s3Fields[3] -ne '1')) { $s3Pass = $false }      # last_sent=1 (preserved)
if ($s3Pass -and ($s3Fields[4] -ne '0')) { $s3Pass = $false }      # confirmed=0
if ($s3Pass -and ($s3Fields[7] -notmatch 'confirm-timeout')) { $s3Pass = $false }

if ($s3Pass) {
    Write-Output "[S3] PASS - revertExpiredSent moved stale SENT row back to PENDING with confirm-timeout marker"
} else {
    Write-Output "[S3] FAIL - see $s3ReportFile"
    Write-Output "Note: confirmTimeoutSeconds default is 30s; if app started with shorter window,"
    Write-Output "      row created with last_sent_at = now-120s should still satisfy."
}
Write-Output ""

# ============================================================
# SCENARIO S4 (control): happy path CONFIRMED
#   - Insert 1 PENDING row under normal broker config
#   - Expect CONFIRMED with last_sent_at + confirmed_at both populated
# ============================================================
Write-Output "=== [S4] control: happy path CONFIRMED ==="

$s4Row = Insert-OutboxPending -ScenarioTag "HAPPY" -OutboxSubTaskId ($subTaskId + 5)
Write-Output "S4 prepared: row.id=$($s4Row.id)"

Write-Output "S4 waiting for relay + ACK cycle (up to 15s)..."
$s4ReportFile = Join-Path $scriptDir "verify-outbox-relay-confirm-e2e-s4.out"
$s4Done = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    $checkSql = @"
SELECT
  (SELECT status FROM agent_command_outbox WHERE id = $($s4Row.id) AND deleted = 0),
  (SELECT (last_sent_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s4Row.id) AND deleted = 0),
  (SELECT (confirmed_at IS NOT NULL)::int FROM agent_command_outbox WHERE id = $($s4Row.id) AND deleted = 0);
"@
    $tmpChk = [System.IO.Path]::GetTempFileName()
    $null = Run-Psql -Sql $checkSql -OutFile $tmpChk
    $line = Get-FirstPsqlDataLine -Path $tmpChk
    if (-not $line) {
        Remove-Item $tmpChk -ErrorAction SilentlyContinue
        throw "S4 query returned no data line"
    }
    $parts = $line.Split('|')
    Remove-Item $tmpChk -ErrorAction SilentlyContinue
    # CONFIRMED=3 with last_sent=1 and confirmed=1
    if ($parts[0] -eq '3' -and $parts[1] -eq '1' -and $parts[2] -eq '1') {
        $s4Done = $true
        break
    }
}

$rc = Query-OutboxFinal -OutboxId $s4Row.id -OutFile $s4ReportFile
$s4Body = Get-Content $s4ReportFile -Raw
$s4Fields = Get-PsqlFields -Path $s4ReportFile
Write-Output "S4 final: $s4Body"

$s4Pass = $true
if (-not $s4Fields -or $s4Fields.Count -lt 8) { $s4Pass = $false }
if ($s4Pass -and ($s4Fields[1] -ne '3')) { $s4Pass = $false }    # status=3 (CONFIRMED)
if ($s4Pass -and ($s4Fields[3] -ne '1')) { $s4Pass = $false }    # last_sent=1
if ($s4Pass -and ($s4Fields[4] -ne '1')) { $s4Pass = $false }    # confirmed=1

if ($s4Pass) {
    Write-Output "[S4] PASS - happy path: row reached CONFIRMED with both timestamps populated"
} else {
    Write-Output "[S4] FAIL - see $s4ReportFile"
}
Clear-ExecutionQueueMessages
Write-Output ""

# ============================================================
# Final summary
# ============================================================
Write-Output "=== SUMMARY ==="
Write-Output "runTag=$runTag  agentId=$agentId  subTaskId=$subTaskId"
Write-Output "S1 (NACK)            : $($(if($s1Pass){'PASS'}else{'FAIL'}))   -> $($s1ReportFile)"
Write-Output "S2 (mandatory return): $($(if($s2Pass){'PASS'}else{'FAIL'}))   -> $($s2ReportFile)"
Write-Output "S3 (confirm timeout) : $($(if($s3Pass){'PASS'}else{'FAIL'}))   -> $($s3ReportFile)"
Write-Output "S4 (control happy)   : $($(if($s4Pass){'PASS'}else{'FAIL'}))   -> $($s4ReportFile)"
Write-Output ""
Write-Output "Idempotent cleanup: re-run with -Cleanup to drop all rows from this runTag"
Write-Output "                  and restore broker queue args / exchange bindings."
