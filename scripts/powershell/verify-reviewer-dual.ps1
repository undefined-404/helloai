# helloai verify-reviewer-dual.ps1
# Purpose: preset + assert feedback-loop Phase 4 (reviewer dual review + recheck) evidence:
#   S1 dual approve   : HIGH difficulty task with 2 different-model API_KEY_LLM reviewers
#                        -> dual review fires (orphan scanner path, no execution chain needed);
#                        consensus APPROVED asserts: exactly ONE review_record landed,
#                        both reviewer profile reviewer_reviewed_count +1 (snapshot compare),
#                        timeline sub_task_dual_review_consented, sub-task DONE;
#                        LLM-unavailable env degrades to SKIP with mechanism-level asserts.
#   S2 disagreement   : second HIGH task with boundary output -> if LLM verdicts disagree:
#                        context.manualIntervention.reason=reviewer_disagreement (front-end
#                        panel visible), timeline sub_task_reviewer_disagreement, sub-task
#                        stays REVIEW, reviewer_disagreement_count +1; otherwise SKIP with
#                        the actual dual event (LLM verdicts are environment-dependent).
#   S3 recheck link   : sampling data-link asserts (task scheduling body is covered by
#                        ReviewerRecheckTaskTest): S1 approved record is a recheck candidate
#                        (mapper SQL replica), one simulated recheck log round excludes it
#                        from the next candidates (NOT EXISTS window semantics), log row
#                        shape (original/recheck/discrepancy/reviewer), schema readiness.
# Mode:
#   -Scene S1|S2|S3|all (default all). S2/S3 reuse S1 presets; S3 uses S1 approved record
#     when present, otherwise presets its own APPROVED record via direct SQL (S5 precedent).
# Ref: doc/HelloAI_迭代执行记录.md (Phase 4 反馈回路: Reviewer 双审 + 抽检)
#      .qoder/skills/helloai-preflight/SKILL.md (rule 6: UTF-8 BOM + single-quote concat)
# Preconditions: docker compose up -d (helloai-postgres:15432);
#                helloai-start running at :6565 with Phase 4 assembly (V57 migration applied).
# Usage (repo root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-reviewer-dual.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-reviewer-dual.ps1 -Scene S1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-reviewer-dual.ps1 -ReviewerModelA deepseek:deepseek-chat -ReviewerModelB deepseek:deepseek-reasoner
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [ValidateSet('S1','S2','S3','all')]
    [string]$Scene = 'all',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [int]$PollIntervalSec = 5,
    [int]$ReviewWaitSec = 240,
    [int]$RecheckWindowDays = 7,
    # provider:model pairs for the two reviewers; must be available in llm_provider_model
    # (register pre-validates). Override when the default models are not in the catalog.
    [string]$ReviewerModelA = 'deepseek:deepseek-chat',
    [string]$ReviewerModelB = 'deepseek:deepseek-reasoner'
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6) - avoid CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

$scriptDir  = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

$execName    = 'rd-exec'
$revAName    = 'rd-reviewer-a'
$revBName    = 'rd-reviewer-b'

$global:PassCount = 0
$global:FailCount = 0
$global:SkipCount = 0

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

function Assert-Skip {
    param([string]$Scenario, [string]$Detail)
    Write-Output ('[' + $Scenario + '] SKIP : ' + $Detail)
    $global:SkipCount++
}

# assert every dual participant's profile counter incremented by AT LEAST 1.
# pickDual picks ANY two different-model REVIEWERs (first = AgentSelector
# preferred), so fixed-name asserts (rd-reviewer-a/b) are wrong when other
# REVIEWER agents exist; assert on the ACTUAL verdict senders instead.
# >= 1 not == 1: the automatic ReviewerRecheckTask may sample a participant's
# older APPROVED record between the before/after snapshots (+1 per sampled
# record), so an exact +1 assert is flaky while 0 increments still FAILs.
function Assert-ProfileIncrements {
    param([hashtable]$BeforeMap, [hashtable]$AfterMap, [string[]]$SenderIds, [string]$Scenario, [int]$CountIndex, [string]$MetricName)
    $ok = $true
    $detail = ''
    foreach ($rid in $SenderIds) {
        if (-not $BeforeMap.ContainsKey($rid) -or -not $AfterMap.ContainsKey($rid)) {
            $ok = $false
            $detail = $detail + ' sender ' + $rid + ' not in REVIEWER profile snapshot;'
            continue
        }
        $beforeCnt = [int]$BeforeMap[$rid].Split('|')[$CountIndex]
        $afterCnt  = [int]$AfterMap[$rid].Split('|')[$CountIndex]
        if ($afterCnt -lt ($beforeCnt + 1)) {
            $ok = $false
            $detail = $detail + ' reviewer ' + $rid + ' ' + $MetricName + ' ' + $beforeCnt + ' -> ' + $afterCnt + ' (expected >= ' + ($beforeCnt + 1) + ');'
        }
    }
    Assert-Pass $ok $Scenario ('every dual participant ' + $MetricName + ' >= +1, senders=' + ($SenderIds -join ','))
    if (-not $ok) { Write-Output ('[' + $Scenario + '] profile mismatch detail: ' + $detail) }
}

# ============================================================
# helper: HTTP JSON (HttpClient, StringContent UTF-8, PS 5.1 safe)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{}
    )
    # strip BOM possibly smuggled into here-strings (rule 6)
    $Body = $Body.TrimStart([char]0xFEFF)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne 'GET') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if ($Method -eq 'GET')        { $resp = $client.GetAsync($Uri).Result }
        elseif ($Method -eq 'DELETE') {
            if ($Body) {
                $req = [System.Net.Http.HttpRequestMessage]::new('DELETE', $Uri)
                $req.Content = $content
                $resp = $client.SendAsync($req).Result
            } else {
                $resp = $client.DeleteAsync($Uri).Result
            }
        }
        elseif ($Method -eq 'POST')   { $resp = $client.PostAsync($Uri, $content).Result }
        elseif ($Method -eq 'PUT')    { $resp = $client.PutAsync($Uri, $content).Result }
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

# ============================================================
# helper: docker exec psql (temp file + no-BOM UTF-8, rule 6)
# ============================================================
function Run-Psql {
    param(
        [Parameter(Mandatory=$true)][string]$Sql,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $Sql = $Sql.TrimStart([char]0xFEFF)
    $tmpSql = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmpSql, $Sql, $script:Utf8NoBom)
    Remove-Item $OutFile -ErrorAction SilentlyContinue

    $dockerArgs = @('exec', '-i', $pgContainer, 'psql',
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-t', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)

    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $output = $sqlContent | & docker @dockerArgs 2>&1
    $rc = $LASTEXITCODE
    $output | Out-File -FilePath $OutFile -Encoding UTF8
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return $rc
}

function Get-PsqlFields {
    param([Parameter(Mandatory=$true)][string]$Path)
    $line = Get-Content -Path $Path -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^\(' } |
        Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '<NULL>', '')
}

function Get-PsqlLines {
    param([Parameter(Mandatory=$true)][string]$Path)
    return @(Get-Content -Path $Path -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^\(' })
}

# ============================================================
# helper: idempotent register / reuse fixed-name test agents
#   returns @{Id;ApiKey} or $null. API_KEY_LLM reviewers get a
#   SQL fallback forcing ACTIVE + distinct model_type (pickDual
#   needs two different-model usable reviewers).
# ============================================================
function Ensure-TestAgent {
    param([string]$Name, [string]$RoleValue, [string]$AccessType, [string]$ModelType, [string]$AdminToken)
    $lookupResp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=200') -Headers @{ 'X-Admin-Token' = $AdminToken }
    $agentId = $null
    $agentApiKey = $null
    if ($lookupResp.Code -eq 200) {
        $lookupJson = $null
        try { $lookupJson = $lookupResp.Body | ConvertFrom-Json } catch { }
        if ($lookupJson -and $lookupJson.data -and $lookupJson.data.list) {
            $existing = @($lookupJson.data.list | Where-Object { $_.name -eq $Name })
            if ($existing.Count -gt 0) {
                $agentId = $existing[0].id
                $agentApiKey = $existing[0].apiKey
                Write-Host ('[agent] reuse ' + $Name + ' id=' + $agentId)
            }
        }
    }
    if (-not $agentId) {
        $regBody = @{ name = $Name; role = $RoleValue; description = 'verify-reviewer-dual preset agent'; accessType = $AccessType; modelType = $ModelType; idempotent = $true } | ConvertTo-Json -Depth 6
        $regResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/agents/register') -Body $regBody -Headers @{}
        if ($regResp.Code -ne 200) {
            Write-Host ('[agent] FAIL register ' + $Name + ' HTTP=' + $regResp.Code + ' body=' + $regResp.Body)
            return $null
        }
        $regJson = $null
        try { $regJson = $regResp.Body | ConvertFrom-Json } catch { }
        if ($regJson -and $regJson.data -and $regJson.data.id) {
            $agentId = $regJson.data.id
            $agentApiKey = $regJson.data.apiKey
            Write-Host ('[agent] registered ' + $Name + ' id=' + $agentId)
        }
    }
    if (-not $agentId) { return $null }
    if ([string]::IsNullOrEmpty($agentApiKey)) {
        $lookupResp2 = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=200') -Headers @{ 'X-Admin-Token' = $AdminToken }
        if ($lookupResp2.Code -eq 200) {
            $lookupJson2 = $null
            try { $lookupJson2 = $lookupResp2.Body | ConvertFrom-Json } catch { }
            if ($lookupJson2 -and $lookupJson2.data -and $lookupJson2.data.list) {
                $existing2 = @($lookupJson2.data.list | Where-Object { $_.name -eq $Name })
                if ($existing2.Count -gt 0) { $agentApiKey = $existing2[0].apiKey }
            }
        }
    }
    # SQL fallback: force ACTIVE + distinct model_type so pickDual pairing is stable
    $fixSql = "UPDATE agent SET status = 'ACTIVE', model_type = '" + $ModelType + "' WHERE id = " + $agentId + " AND deleted = 0;"
    $fixOut = Join-Path $scriptDir 'verify-reviewer-dual-agentfix.out'
    $null = Run-Psql -Sql $fixSql -OutFile $fixOut
    return @{ Id = $agentId; ApiKey = [string]$agentApiKey }
}

# ============================================================
# helper: task + sub-task preset
# ============================================================
function New-TaskWithWhitelist {
    param([string]$Title, [long[]]$ExecutorIds, [string]$Difficulty, [string]$AdminToken)
    $findSql = "SELECT id FROM task WHERE title = '" + $Title + "' AND deleted = 0 LIMIT 1;"
    $findFile = Join-Path $scriptDir 'verify-reviewer-dual-find.out'
    $null = Run-Psql -Sql $findSql -OutFile $findFile
    $findLine = Get-PsqlFields -Path $findFile
    if ($findLine -and $findLine.Split('|')[0]) {
        $residualId = $findLine.Split('|')[0]
        Write-Host ('[preset] cleanup residual task id=' + $residualId)
        $delBody = '{"confirmTitle":"' + $Title + '"}'
        $null = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/deleteById/' + $residualId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    }
    $policy = '{"difficulty":"' + $Difficulty + '"'
    if ($ExecutorIds -and $ExecutorIds.Count -gt 0) {
        $ids = ($ExecutorIds | ForEach-Object { [string]$_ }) -join ','
        $policy = $policy + ',"executorAgentIds":[' + $ids + ']'
    }
    $policy = $policy + '}'
    $taskBody = '{"title":"' + $Title + '","description":"verify-reviewer-dual preset task","agentPolicy":' + $policy + '}'
    $taskResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks') -Body $taskBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    if ($taskResp.Code -ne 200) {
        Write-Host ('[preset] FAIL create task HTTP=' + $taskResp.Code + ' body=' + $taskResp.Body)
        return $null
    }
    $taskJson = $null
    try { $taskJson = $taskResp.Body | ConvertFrom-Json } catch { }
    if (-not $taskJson -or $taskJson.code -ne 200 -or -not $taskJson.data.id) {
        Write-Output ('[preset] FAIL create task biz: ' + $taskResp.Body)
        return $null
    }
    return [string]$taskJson.data.id
}

function New-SubTask {
    param([string]$TaskId, [string]$Title, [string]$Deliverable, [string]$Acceptance, [long]$AssignedAgent, [string]$AdminToken)
    $assignField = ''
    if ($AssignedAgent -gt 0) { $assignField = ',"assignedAgent":' + $AssignedAgent }
    $body = '{"taskId":' + $TaskId + ',"title":"' + $Title + '","description":"preset sub-task","deliverable":"' + $Deliverable + '","acceptance":"' + $Acceptance + '"' + $assignField + '}'
    $resp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/sub-tasks') -Body $body -Headers @{ 'X-Admin-Token' = $AdminToken }
    if ($resp.Code -ne 200) {
        Write-Host ('[preset] FAIL create sub-task HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    if (-not $json -or $json.code -ne 200 -or -not $json.data.id) {
        Write-Output ('[preset] FAIL create sub-task biz: ' + $resp.Body)
        return $null
    }
    return [string]$json.data.id
}

function Remove-Task {
    param([string]$TaskId, [string]$Title, [string]$AdminToken)
    $delBody = '{"confirmTitle":"' + $Title + '"}'
    $null = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/deleteById/' + $TaskId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
}

# ============================================================
# helper: DB state readers
# ============================================================
# Force REVIEW with a concrete lastExecution.output (evidence hard-check passes
# without attachments); update_time=now() re-arms the orphan scanner window.
function Set-ReviewState {
    param([string]$SubTaskId, [string]$Output)
    $ctxJson = '{"lastExecution":{"output":"' + $Output + '"}}'
    $sql = "UPDATE sub_task SET context = '" + $ctxJson + "'::jsonb, status = 'REVIEW', update_by = 'rd-preset', update_time = now() WHERE id = " + $SubTaskId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-setreview.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) {
        Write-Output ('[preset] FAIL set REVIEW rc=' + $rc)
    }
}

function Get-SubTaskState {
    param([string]$SubTaskId)
    $sql = "SELECT status || '|' || COALESCE(assigned_agent_id::text, 'NULL') FROM sub_task WHERE id = " + $SubTaskId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-state.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

# Latest dual-review timeline event for a sub-task:
#   event_type|agent_id|consensus(payload)
# NOTE: disagreement lands as sub_task_reviewer_disagreement (NOT a dual_review_* prefix),
# so the matcher must include it or the wait window times out on disagree paths.
function Get-DualReviewEvent {
    param([string]$SubTaskId)
    $sql = "SELECT event_type || '|' || COALESCE(agent_id::text,'NULL') || '|' || COALESCE(payload->>'consensus','') FROM task_timeline WHERE sub_task_id = " + $SubTaskId + " AND (event_type LIKE 'sub_task_dual_review_%' OR event_type = 'sub_task_reviewer_disagreement') AND deleted = 0 ORDER BY id DESC LIMIT 1;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-evts.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return $null }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return $null }
    return $line
}

# reviewer_dimension profile counts: reviewer_reviewed_count|reviewer_disagreement_count
function Get-ReviewerProfile {
    param([string]$AgentId)
    $sql = "SELECT COALESCE(reviewer_reviewed_count,0) || '|' || COALESCE(reviewer_disagreement_count,0) FROM agent_quality_profile WHERE agent_id = " + $AgentId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-prof.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '0|0' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '0|0' }
    return $line
}

# snapshot of every ACTIVE REVIEWER profile (agentId -> 'reviewed|disagreed');
# used to assert profile increments on the ACTUAL dual pair (verdict senders).
function Get-ReviewerProfiles {
    $sql = "SELECT id FROM agent WHERE role = 'REVIEWER' AND status = 'ACTIVE' AND deleted = 0 ORDER BY id;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-revids.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return @{} }
    $ids = @(Get-PsqlLines -Path $out)
    $map = @{}
    foreach ($idLine in $ids) {
        $idv = $idLine.Split('|')[0]
        if ($idv) { $map[$idv] = Get-ReviewerProfile -AgentId $idv }
    }
    return $map
}

function Get-ReviewRecordCount {
    param([string]$SubTaskId)
    $sql = "SELECT COUNT(*) FROM review_record WHERE sub_task_id = " + $SubTaskId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-reccount.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '-1' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '0' }
    return $line
}

function Get-ApprovedRecordId {
    param([string]$SubTaskId)
    $sql = "SELECT id FROM review_record WHERE sub_task_id = " + $SubTaskId + " AND result = 'APPROVED' AND deleted = 0 ORDER BY id DESC LIMIT 1;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-apprid.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line.Split('|')[0]
}

# distinct reviewer agent ids that produced a verdict message for the sub-task
function Get-VerdictReviewers {
    param([string]$SubTaskId)
    $sql = "SELECT DISTINCT sender_id FROM conversation_message WHERE sub_task_id = " + $SubTaskId + " AND tool_name = 'subtask_review_verdict' AND sender_id IS NOT NULL AND deleted = 0 ORDER BY sender_id;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-verdicts.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return @() }
    return @(Get-PsqlLines -Path $out)
}

# subtask_review_prompt message count (LLM-call evidence for SKIP vs FAIL triage)
function Get-PromptMessageCount {
    param([string]$SubTaskId)
    $sql = "SELECT COUNT(*) FROM conversation_message WHERE sub_task_id = " + $SubTaskId + " AND tool_name = 'subtask_review_prompt' AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-promptcnt.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '0' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '0' }
    return $line
}

# manualIntervention reason from sub_task.context (front-end panel visibility)
function Get-ManualInterventionReason {
    param([string]$SubTaskId)
    $sql = "SELECT COALESCE(context->'manualIntervention'->>'reason','') FROM sub_task WHERE id = " + $SubTaskId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-mi.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

# Replica of ReviewRecheckLogMapper.countRecheckCandidates / selectRecheckCandidateIds:
# APPROVED records in window, not yet rechecked (NOT EXISTS window semantics).
function Get-RecheckCandidates {
    $sql = "SELECT r.id FROM review_record r WHERE r.result = 'APPROVED' AND r.deleted = 0 AND r.create_time >= now() - interval '" + $RecheckWindowDays + " days' AND NOT EXISTS (SELECT 1 FROM review_recheck_log l WHERE l.review_record_id = r.id AND l.deleted = 0) ORDER BY r.create_time ASC;"
    $out = Join-Path $scriptDir 'verify-reviewer-dual-cands.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return @() }
    return @(Get-PsqlLines -Path $out)
}

# ============================================================
# helper: poll until condition
# ============================================================
function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSec, [string]$Label, [object[]]$ArgList = @())
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $value = & $Condition @ArgList
        if ($value) { return $value }
        Start-Sleep -Seconds $PollIntervalSec
    } while ((Get-Date) -lt $deadline)
    Write-Host ('[wait] timeout: ' + $Label + ' not satisfied within ' + $TimeoutSec + 's')
    return $null
}

# ============================================================
# STEP S0: pre-flight
# ============================================================
Write-Output '=== [S0] pre-flight ==='
$dockerCheck = & docker ps --format '{{.Names}}|{{.Status}}' --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Output 'FAIL : postgres container is NOT up. Run: docker compose up -d'
    exit 1
}
Write-Output '[S0] postgres container up'

try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync($BaseUrl + '/api/health').Result
    Write-Output ('[S0] server ' + $BaseUrl + ' HTTP ' + [int]$pingResp.StatusCode)
    $ping.Dispose()
} catch {
    Write-Output ('FAIL : server NOT reachable at ' + $BaseUrl)
    exit 1
}

# ============================================================
# STEP A1: admin login
# ============================================================
Write-Output ''
Write-Output '=== [A1] admin login ==='
$loginBody = '{"type":"admin","username":"' + $AdminUsername + '","credential":"' + $AdminPassword + '"}'
$loginResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/auth/login') -Body $loginBody
$loginJson = $null
try { $loginJson = $loginResp.Body | ConvertFrom-Json } catch { }
$adminToken = if ($loginJson -and $loginJson.data -and $loginJson.data.token) { $loginJson.data.token } else { $null }
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Output ('FAIL : admin login failed: ' + $loginResp.Body)
    exit 1
}
Write-Output '[A1] admin token acquired'

# ============================================================
# ensure preset agents (idempotent, fixed names)
#   rd-exec: EXECUTOR placeholder (assigned_agent_id target, no MCP calls needed)
#   rd-reviewer-a / rd-reviewer-b: REVIEWER API_KEY_LLM with DIFFERENT model_type
#   (pickDual pairing contract); registration fails when the model is not in the
#   llm_provider_model catalog -> S1/S2 SKIP (environment dependency), S3 still runs.
# ============================================================
Write-Output ''
Write-Output '=== [agents] ensure preset test agents ==='
$exec = Ensure-TestAgent -Name $execName -RoleValue 'EXECUTOR' -AccessType 'CLI_CLIENT' -ModelType 'deepseek:deepseek-v4-pro' -AdminToken $adminToken
$revA = Ensure-TestAgent -Name $revAName -RoleValue 'REVIEWER' -AccessType 'API_KEY_LLM' -ModelType $ReviewerModelA -AdminToken $adminToken
$revB = Ensure-TestAgent -Name $revBName -RoleValue 'REVIEWER' -AccessType 'API_KEY_LLM' -ModelType $ReviewerModelB -AdminToken $adminToken
if (-not $exec) {
    Write-Output 'FAIL : executor preset agent unavailable'
    exit 1
}
Write-Output ('[agents] exec=' + $exec.Id + ' reviewer-a=' + $revA.Id + ' reviewer-b=' + $revB.Id)

$execId  = [string]$exec.Id
$revAId  = [string]$revA.Id
$revBId  = [string]$revB.Id
$script:ReviewersReady = ($revA -ne $null) -and ($revB -ne $null)

# task ids for teardown
$script:TaskIdS1 = ''
$script:TaskIdS2 = ''
$script:TaskIdS3 = ''
$script:SubTaskIdS1 = ''
$script:SubTaskIdS2 = ''
$script:ApprovedRecordId = ''

# ============================================================
# S1: HIGH difficulty dual review -> consensus approve
# ============================================================
function Run-Scenario1 {
    Write-Output ''
    Write-Output '=== [S1] HIGH dual review: consensus approve path ==='
    if (-not $script:ReviewersReady) {
        Assert-Skip 'S1' 'API_KEY_LLM reviewers unavailable (register failed: model not in catalog or already used in role)'
        return
    }
    $beforeA = Get-ReviewerProfile -AgentId $revAId
    $beforeB = Get-ReviewerProfile -AgentId $revBId
    $profilesBefore = Get-ReviewerProfiles
    Write-Output ('[S1] reviewer profile before: a=' + $beforeA + ' b=' + $beforeB + ' all-reviewers=' + $profilesBefore.Count)

    # preset HIGH task (no reviewerAgentId -> dual review required) + sub-task
    $taskTitle = 'rd-s1-dual-approve'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds @([long]$execId) -Difficulty 'HIGH' -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S1' 'task preset failed'; return }
    $script:TaskIdS1 = $taskId
    $subId = New-SubTask -TaskId $taskId -Title 'rd-s1-sub' -Deliverable 'verification evidence note' -Acceptance 'note contains explicit PASS marker' -AssignedAgent ([long]$execId) -AdminToken $adminToken
    if (-not $subId) { Assert-Pass $false 'S1' 'sub-task preset failed'; return }
    $script:SubTaskIdS1 = $subId

    # force REVIEW with concrete output (evidence check passes, orphan scanner path)
    Set-ReviewState -SubTaskId $subId -Output 'PASS marker present. The deliverable is complete and matches the acceptance criteria.'
    $state0 = Get-SubTaskState -SubTaskId $subId
    Write-Output ('[S1] preset subTaskId=' + $subId + ' state=' + $state0)
    Assert-Pass ($state0 -and $state0.StartsWith('REVIEW')) 'S1-preset' ('sub-task forced REVIEW, actual=' + $state0)

    # wait for orphan scanner (30s interval, 60s threshold) -> dual review event
    $evt = Wait-Until -Condition {
        param($id)
        return Get-DualReviewEvent $id
    } -TimeoutSec $ReviewWaitSec -Label 'S1 dual review event landed' -ArgList @($subId)
    if (-not $evt) {
        Assert-Pass $false 'S1-trigger' 'no dual-review event within wait window (orphan scanner / auto review disabled / event chain down)'
        return
    }
    $evtType = $evt.Split('|')[0]
    $evtConsensus = if ($evt.Split('|').Count -ge 3) { $evt.Split('|')[2] } else { '' }
    Write-Output ('[S1] dual event=' + $evtType + ' consensus=' + $evtConsensus)

    # mechanism-level assert: dual path fired, NOT degraded
    Assert-Pass ($evtType -ne 'sub_task_dual_review_degraded') 'S1-no-degraded' ('dual review fired without candidate degradation, evt=' + $evtType)

    if ($evtType -eq 'sub_task_dual_review_incomplete') {
        # orchestration fired but at least one LLM verdict unavailable
        $promptCnt = Get-PromptMessageCount -SubTaskId $subId
        if ($promptCnt -eq '0') {
            Assert-Skip 'S1' ('dual orchestration fired but LLM call failed (no prompt message; requires vault API key or mock mode). evt=' + $evtType)
        } else {
            Assert-Pass $true 'S1-dual-orchestration' 'dual review orchestration fired (incomplete: LLM output unparseable in this env)'
            Assert-Skip 'S1-consensus' 'LLM verdict unavailable in this env; consensus/record asserts skipped'
        }
        return
    }
    if ($evtType -eq 'sub_task_reviewer_disagreement') {
        Assert-Pass $true 'S1-dual-orchestration' 'dual review fired, LLM verdicts disagreed (env-dependent)'
        Assert-Skip 'S1-consensus' ('verdicts disagreed; approve-path asserts skipped (see S2 for disagreement asserts), consensus=' + $evtConsensus)
        return
    }
    # consented path
    Assert-Pass ($evtType -eq 'sub_task_dual_review_consented') 'S1-consented-event' ('timeline sub_task_dual_review_consented, actual=' + $evtType)
    $state1 = Get-SubTaskState -SubTaskId $subId
    if ($evtConsensus -ne 'APPROVED') {
        # consensus REJECTED: dual flow works, but env LLM rejected -> single-record discipline still asserted
        $recCount = Get-ReviewRecordCount -SubTaskId $subId
        Assert-Pass ($recCount -eq '1') 'S1-single-record' ('dual review lands exactly one review_record even on reject, actual=' + $recCount)
        Assert-Skip 'S1-approve' ('dual consensus=' + $evtConsensus + ' (env LLM rejected); approve-path asserts skipped')
        return
    }
    Assert-Pass ($state1 -and $state1.StartsWith('DONE')) 'S1-done' ('consensus approve -> sub-task DONE, actual=' + $state1)
    $recCount = Get-ReviewRecordCount -SubTaskId $subId
    Assert-Pass ($recCount -eq '1') 'S1-single-record' ('dual review lands exactly ONE review_record (reviewer1 owns), actual=' + $recCount)
    $script:ApprovedRecordId = Get-ApprovedRecordId -SubTaskId $subId
    Assert-Pass ($script:ApprovedRecordId -ne '') 'S1-approved-record' ('APPROVED review_record id=' + $script:ApprovedRecordId)

    # reviewer-dimension profile increments: assert on ACTUAL dual participants.
    # pickDual picks any two different-model REVIEWERs, so rd-reviewer-a/b are
    # NOT guaranteed to be the chosen pair when other REVIEWERs exist.
    $afterProfiles = Get-ReviewerProfiles
    $afterADisp = if ($afterProfiles.ContainsKey($revAId)) { $afterProfiles[$revAId] } else { '?' }
    $afterBDisp = if ($afterProfiles.ContainsKey($revBId)) { $afterProfiles[$revBId] } else { '?' }
    Write-Output ('[S1] reviewer profile after: a=' + $afterADisp + ' b=' + $afterBDisp)

    # dual-call evidence: verdict messages from BOTH distinct reviewers
    $verdictReviewers = Get-VerdictReviewers -SubTaskId $subId
    Write-Output ('[S1] verdict senders=' + ($verdictReviewers -join ','))
    Assert-Pass ($verdictReviewers.Count -ge 2) 'S1-dual-calls' ('both reviewers produced verdict messages, senders=' + ($verdictReviewers -join ','))
    Assert-ProfileIncrements -BeforeMap $profilesBefore -AfterMap $afterProfiles -SenderIds $verdictReviewers -Scenario 'S1-dual-profile' -CountIndex 0 -MetricName 'reviewer_reviewed_count'
}

# ============================================================
# S2: disagreement -> manual intervention marker (front-end panel)
# ============================================================
function Run-Scenario2 {
    Write-Output ''
    Write-Output '=== [S2] dual disagreement: manualIntervention marker ==='
    if (-not $script:ReviewersReady) {
        Assert-Skip 'S2' 'API_KEY_LLM reviewers unavailable (register failed)'
        return
    }
    $beforeA = Get-ReviewerProfile -AgentId $revAId
    $beforeB = Get-ReviewerProfile -AgentId $revBId
    $profilesBefore = Get-ReviewerProfiles
    Write-Output ('[S2] reviewer profile before: a=' + $beforeA + ' b=' + $beforeB + ' all-reviewers=' + $profilesBefore.Count)

    # second HIGH task, boundary output (LLM verdicts may diverge)
    $taskTitle = 'rd-s2-dual-disagree'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds @([long]$execId) -Difficulty 'HIGH' -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S2' 'task preset failed'; return }
    $script:TaskIdS2 = $taskId
    $subId = New-SubTask -TaskId $taskId -Title 'rd-s2-sub' -Deliverable 'boundary compliance report' -Acceptance 'report must fully address all acceptance items' -AssignedAgent ([long]$execId) -AdminToken $adminToken
    if (-not $subId) { Assert-Pass $false 'S2' 'sub-task preset failed'; return }
    $script:SubTaskIdS2 = $subId

    Set-ReviewState -SubTaskId $subId -Output 'PARTIAL: only two of five acceptance items are addressed. The remaining three items lack supporting evidence.'
    $state0 = Get-SubTaskState -SubTaskId $subId
    Write-Output ('[S2] preset subTaskId=' + $subId + ' state=' + $state0)
    Assert-Pass ($state0 -and $state0.StartsWith('REVIEW')) 'S2-preset' ('sub-task forced REVIEW, actual=' + $state0)

    $evt = Wait-Until -Condition {
        param($id)
        return Get-DualReviewEvent $id
    } -TimeoutSec $ReviewWaitSec -Label 'S2 dual review event landed' -ArgList @($subId)
    if (-not $evt) {
        Assert-Pass $false 'S2-trigger' 'no dual-review event within wait window (orphan scanner / auto review disabled)'
        return
    }
    $evtType = $evt.Split('|')[0]
    Write-Output ('[S2] dual event=' + $evtType)
    Assert-Pass ($evtType -ne 'sub_task_dual_review_degraded') 'S2-no-degraded' ('dual review fired without candidate degradation, evt=' + $evtType)

    if ($evtType -eq 'sub_task_reviewer_disagreement') {
        # strong asserts: manual intervention marker visible to the front-end panel
        $miReason = Get-ManualInterventionReason -SubTaskId $subId
        Assert-Pass ($miReason -eq 'reviewer_disagreement') 'S2-manual-marker' ('context.manualIntervention.reason=reviewer_disagreement, actual=' + $miReason)
        $state1 = Get-SubTaskState -SubTaskId $subId
        Assert-Pass ($state1 -and $state1.StartsWith('REVIEW')) 'S2-stays-review' ('sub-task stays REVIEW awaiting human, actual=' + $state1)
        # reviewer-dimension disagreement counts +1 for ACTUAL dual participants
        # (pickDual pair is environment-dependent; assert on verdict senders)
        $verdictReviewers = Get-VerdictReviewers -SubTaskId $subId
        Write-Output ('[S2] verdict senders=' + ($verdictReviewers -join ','))
        $afterProfiles = Get-ReviewerProfiles
        $afterADisp = if ($afterProfiles.ContainsKey($revAId)) { $afterProfiles[$revAId] } else { '?' }
        $afterBDisp = if ($afterProfiles.ContainsKey($revBId)) { $afterProfiles[$revBId] } else { '?' }
        Write-Output ('[S2] reviewer profile after: a=' + $afterADisp + ' b=' + $afterBDisp)
        Assert-ProfileIncrements -BeforeMap $profilesBefore -AfterMap $afterProfiles -SenderIds $verdictReviewers -Scenario 'S2-disagree-profile' -CountIndex 1 -MetricName 'reviewer_disagreement_count'
        $recCount = Get-ReviewRecordCount -SubTaskId $subId
        Assert-Pass ($recCount -eq '0') 'S2-no-record' ('disagreement lands NO review_record (stays REVIEW), actual=' + $recCount)
        return
    }
    # not reproduced: mechanism still verified, disagreement asserts skipped
    Assert-Skip 'S2' ('disagreement not reproduced this round (evt=' + $evtType + '); manual-intervention asserts skipped (LLM verdicts are environment-dependent)')
}

# ============================================================
# S3: recheck sampling data-link + reviewer-dimension counting
#   NOTE: the scheduled task body (lock/sampling math/per-item
#   isolation) is covered by ReviewerRecheckTaskTest; this script
#   asserts the data link the task consumes: candidate visibility,
#   one-round exclusion (NOT EXISTS window semantics), log row
#   shape, schema readiness, and the real reviewer counter
#   increments produced by the dual path in S1.
# ============================================================
function Run-Scenario3 {
    Write-Output ''
    Write-Output '=== [S3] recheck sampling: candidate link + log contract ==='

    # 1) candidate source: S1 real APPROVED record, or a preset one (S5 precedent)
    $recordId = $script:ApprovedRecordId
    $recordSubId = $script:SubTaskIdS1
    if (-not $recordId) {
        Write-Output '[S3] no S1 approved record (env-dependent); presetting an APPROVED record via direct SQL'
        $taskTitle = 'rd-s3-sampled-task'
        $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds @() -Difficulty 'MEDIUM' -AdminToken $adminToken
        if (-not $taskId) { Assert-Pass $false 'S3' 'task preset failed'; return }
        $script:TaskIdS3 = $taskId
        $subId = New-SubTask -TaskId $taskId -Title 'rd-s3-sub' -Deliverable 'sampled deliverable' -Acceptance 'accepted' -AssignedAgent 0 -AdminToken $adminToken
        if (-not $subId) { Assert-Pass $false 'S3' 'sub-task preset failed'; return }
        $recordSubId = $subId
        $maxSql = 'SELECT COALESCE(MAX(id),0)+1 FROM review_record;'
        $maxOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-max.out'
        $null = Run-Psql -Sql $maxSql -OutFile $maxOut
        $maxLine = Get-PsqlFields -Path $maxOut
        if (-not $maxLine) { Assert-Pass $false 'S3' 'max review_record id query failed'; return }
        $recordId = $maxLine.Split('|')[0]
        $insSql = "INSERT INTO review_record (id, sub_task_id, reviewer_agent_id, result, score, issues, comment, round, create_by, update_by) VALUES (" + $recordId + ', ' + $recordSubId + ', ' + $revAId + ", 'APPROVED', 4, NULL, 'rd-s3 preset approved', 1, 'rd-preset', 'rd-preset');"
        $insOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-ins.out'
        $insRc = Run-Psql -Sql $insSql -OutFile $insOut
        Assert-Pass ($insRc -eq 0) 'S3-preset-record' ('direct APPROVED review_record id=' + $recordId + ' rc=' + $insRc)
    }

    # 2) candidate visibility: the record is a recheck candidate (next scheduled round picks it)
    $cands1 = Get-RecheckCandidates
    Write-Output ('[S3] candidates before=' + ($cands1 -join ','))
    Assert-Pass ($cands1 -contains $recordId) 'S3-candidate-visible' ('APPROVED record id=' + $recordId + ' is a recheck candidate (count=' + $cands1.Count + ')')

    # 3) one simulated recheck round lands the log row
    $logSql = 'SELECT COALESCE(MAX(id),0)+1 FROM review_recheck_log;'
    $logOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-logmax.out'
    $null = Run-Psql -Sql $logSql -OutFile $logOut
    $logLine = Get-PsqlFields -Path $logOut
    if (-not $logLine) { Assert-Pass $false 'S3' 'max review_recheck_log id query failed'; return }
    $logId = $logLine.Split('|')[0]
    $insLogSql = "INSERT INTO review_recheck_log (id, review_record_id, sub_task_id, original_result, recheck_result, discrepancy, reviewer_agent, score, comment, create_by, update_by) VALUES (" + $logId + ', ' + $recordId + ', ' + $recordSubId + ", 'APPROVED', 'APPROVED', 0, " + $revAId + ", 4, 'rd-s3 simulated recheck round', 'rd-preset', 'rd-preset');"
    $insLogOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-logins.out'
    $insLogRc = Run-Psql -Sql $insLogSql -OutFile $insLogOut
    Assert-Pass ($insLogRc -eq 0) 'S3-log-insert' ('review_recheck_log row id=' + $logId + ' rc=' + $insLogRc)

    # 4) exclusion semantics: rechecked record leaves the candidate window (NOT EXISTS)
    $cands2 = Get-RecheckCandidates
    Write-Output ('[S3] candidates after=' + ($cands2 -join ','))
    Assert-Pass ($cands2 -notcontains $recordId) 'S3-excluded-after-recheck' ('record id=' + $recordId + ' excluded from candidates after one recheck round (window semantics)')

    # 5) log row shape (discrepancy=0 consistent round)
    $shapeSql = "SELECT original_result || '|' || recheck_result || '|' || discrepancy || '|' || reviewer_agent FROM review_recheck_log WHERE id = " + $logId + ';'
    $shapeOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-shape.out'
    $shapeRc = Run-Psql -Sql $shapeSql -OutFile $shapeOut
    $shape = ''
    if ($shapeRc -eq 0) { $shape = Get-PsqlFields -Path $shapeOut }
    Assert-Pass ($shape -eq ('APPROVED|APPROVED|0|' + $revAId)) 'S3-log-shape' ('log row original/recheck/discrepancy/reviewer, actual=' + $shape)

    # 6) schema readiness (V57 columns)
    $schemaSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'review_recheck_log' AND column_name IN ('review_record_id','sub_task_id','original_result','recheck_result','discrepancy','reviewer_agent');"
    $schemaOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-schema.out'
    $schemaRc = Run-Psql -Sql $schemaSql -OutFile $schemaOut
    $schemaCnt = '0'
    if ($schemaRc -eq 0) { $schemaCnt = Get-PsqlFields -Path $schemaOut }
    Assert-Pass ($schemaCnt -eq '6') 'S3-schema' ('review_recheck_log core columns ready (V57), count=' + $schemaCnt)

    # 7) reviewer-dimension counter columns (V54) so sampling can drive them
    $profSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'agent_quality_profile' AND column_name IN ('reviewer_reviewed_count','reviewer_disagreement_count');"
    $profOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-profcols.out'
    $profRc = Run-Psql -Sql $profSql -OutFile $profOut
    $profCnt = '0'
    if ($profRc -eq 0) { $profCnt = Get-PsqlFields -Path $profOut }
    Assert-Pass ($profCnt -eq '2') 'S3-reviewer-cols' ('agent_quality_profile reviewer-dimension columns ready (V54), count=' + $profCnt)

    # cleanup simulated recheck log (keep the real record for S1 teardown)
    $delLogSql = 'DELETE FROM review_recheck_log WHERE id = ' + $logId + ';'
    $delLogOut = Join-Path $scriptDir 'verify-reviewer-dual-s3-logdel.out'
    $null = Run-Psql -Sql $delLogSql -OutFile $delLogOut
    Write-Output ('[S3] simulated recheck log removed id=' + $logId)
}

# ============================================================
# run selected scenarios
# ============================================================
if ($Scene -eq 'all' -or $Scene -eq 'S1') { Run-Scenario1 }
if ($Scene -eq 'all' -or $Scene -eq 'S2') { Run-Scenario2 }
if ($Scene -eq 'all' -or $Scene -eq 'S3') { Run-Scenario3 }

# ============================================================
# teardown
# ============================================================
Write-Output ''
Write-Output '=== [teardown] ==='
if ($script:TaskIdS1) {
    $revClean = 'DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS1 + ');'
    $revCleanOut = Join-Path $scriptDir 'verify-reviewer-dual-revclean.out'
    $null = Run-Psql -Sql $revClean -OutFile $revCleanOut
    $msgClean = 'DELETE FROM conversation_message WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS1 + ');'
    $msgCleanOut = Join-Path $scriptDir 'verify-reviewer-dual-msgclean.out'
    $null = Run-Psql -Sql $msgClean -OutFile $msgCleanOut
    $tlClean = 'DELETE FROM task_timeline WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS1 + ');'
    $tlCleanOut = Join-Path $scriptDir 'verify-reviewer-dual-tlclean.out'
    $null = Run-Psql -Sql $tlClean -OutFile $tlCleanOut
    Remove-Task -TaskId $script:TaskIdS1 -Title 'rd-s1-dual-approve' -AdminToken $adminToken
    Write-Output ('[teardown] task S1 removed id=' + $script:TaskIdS1)
}
if ($script:TaskIdS2) {
    $revClean2 = 'DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS2 + ');'
    $revCleanOut2 = Join-Path $scriptDir 'verify-reviewer-dual-revclean2.out'
    $null = Run-Psql -Sql $revClean2 -OutFile $revCleanOut2
    $msgClean2 = 'DELETE FROM conversation_message WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS2 + ');'
    $msgCleanOut2 = Join-Path $scriptDir 'verify-reviewer-dual-msgclean2.out'
    $null = Run-Psql -Sql $msgClean2 -OutFile $msgCleanOut2
    $tlClean2 = 'DELETE FROM task_timeline WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS2 + ');'
    $tlCleanOut2 = Join-Path $scriptDir 'verify-reviewer-dual-tlclean2.out'
    $null = Run-Psql -Sql $tlClean2 -OutFile $tlCleanOut2
    Remove-Task -TaskId $script:TaskIdS2 -Title 'rd-s2-dual-disagree' -AdminToken $adminToken
    Write-Output ('[teardown] task S2 removed id=' + $script:TaskIdS2)
}
if ($script:TaskIdS3) {
    $revClean3 = 'DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS3 + ');'
    $revCleanOut3 = Join-Path $scriptDir 'verify-reviewer-dual-revclean3.out'
    $null = Run-Psql -Sql $revClean3 -OutFile $revCleanOut3
    Remove-Task -TaskId $script:TaskIdS3 -Title 'rd-s3-sampled-task' -AdminToken $adminToken
    Write-Output ('[teardown] task S3 removed id=' + $script:TaskIdS3)
}

# ============================================================
# Summary
# ============================================================
Write-Output ''
Write-Output '===================================================='
Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
Write-Output '===================================================='
if ($global:FailCount -gt 0) {
    Write-Output 'verification FAILED'
    exit 1
}
Write-Output 'ALL PASSED (skips are environment-dependent and documented)'
exit 0
