# ============================================================
# HelloAI token-usage E2E verification
# (audit 2026-09-30 section 12.3 P2 / B5: real E2E DB evidence
#  for the token usage capture chain:
#    AgentLoopResult.tokenUsage -> AgentExecutionResult.tokenUsage
#    -> agent_execution_record.token_usage
#    -> agent_event AGENT_COMPLETED payload.tokens
#    -> sub_task.context.lastExecution.tokens)
#
# Flow: admin login -> preflight inner agents -> dedup scan (fixed
# title) -> create task with the inner executor pinned via
# agent_policy.executorAgentIds -> plan -> confirm -> inner Runtime
# executes through ChatModelToolLoop with web_search enabled (>=2
# tool rounds, so multi-round accumulation is exercised) -> poll
# sub tasks until closed -> assert token evidence in three places:
#   1) agent_execution_record.token_usage NOT NULL for >=1 record
#   2) AGENT_COMPLETED payload->>'tokens' present in agent_event
#   3) sub_task.context->'lastExecution'->>'tokens' present
#
# Pre-conditions:
#   - helloai-start up @ 6565 (local profile, real mode,
#     Flyway applied through V97__agent_execution_record_token_usage)
#   - postgres container helloai-postgres:15432 reachable via docker
#   - inner agents ACTIVE in DB:
#       planner  2088623807767121922
#       executor 2088623654343675905 (web_search enabled, deepseek
#                credential ACTIVE in credential_vault)
#       reviewer 2088623970980073473
#
# Usage (project root):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-token-usage-e2e.ps1
#   powershell ... -AssertOnly     # assert on the existing task only
#   powershell ... -LoopTimeoutSec 1800
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = $env:HELLOAI_ADMIN_PASSWORD,
    [string]$PlannerAgentId  = "2088623807767121922",
    [string]$ExecutorAgentId = "2088623654343675905",
    [string]$ReviewerAgentId = "2088623970980073473",
    [string]$TaskTitle = "tku-e2e-01-token-usage",
    [int]$PlanTimeoutSec = 360,
    [int]$LoopTimeoutSec = 1200,
    [int]$PollIntervalSec = 10,
    [string]$PgContainer = "helloai-postgres",
    [switch]$AssertOnly
)
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw "未设置管理员口令：请导出环境变量 HELLOAI_ADMIN_PASSWORD（或传 -AdminPassword）" }

# ------------------------------------------------------------
# UTF-8 encoding header (repo rule 6) - avoid CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = "Stop"

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 60) {
    $json = $null
    if ($null -ne $Body) {
        $json = ($Body | ConvertTo-Json -Depth 12)
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Get-SubTasks([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/list?taskId=" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("list sub-tasks code=" + $resp.code + " msg=" + $resp.msg)
    if ($resp.data.records -ne $null) { return @($resp.data.records) }
    return @($resp.data)
}

function Wait-Drafts([string]$TaskId, [int]$MaxSecs, [hashtable]$Headers) {
    $waited = 0
    $listResp = $null
    while ($waited -lt $MaxSecs) {
        $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/findPlanByTaskId/" + $TaskId) -Body $null -Headers $Headers
        $drafts = @($listResp.data)
        if ($drafts.Count -ge 1) { return $drafts }
        Start-Sleep -Seconds 3
        $waited = $waited + 3
    }
    return @($listResp.data)
}

function Get-PsqlField([string]$Sql) {
    $tmpSql = [System.IO.Path]::GetTempFileName()
    $sql = $Sql.TrimStart([char]0xFEFF)
    [System.IO.File]::WriteAllText($tmpSql, $sql, $script:Utf8NoBom)
    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $output = $sqlContent | & docker exec -i $PgContainer psql -v ON_ERROR_STOP=1 -X -t -A -F "|" -U postgres -d helloai 2>&1
    $rc = $LASTEXITCODE
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    if ($rc -ne 0) { return $null }
    $line = $output | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ } | Select-Object -First 1
    return $line
}

# Task description: hard requirement to exercise the web_search tool
# at least twice, so the loop runs >= 2 tool rounds and the
# multi-round token accumulation is exercised for real.
$TaskDesc = @'
Deliver a short English summary (150-250 characters) on the current state of the MCP protocol adoption in Java ecosystems, based on live web research performed with the platform web_search tool.

HARD REQUIREMENTS:
1. You MUST call the web_search tool at least twice with two DIFFERENT keyword sets (for example: first "MCP Java SDK adoption 2026", then "Spring AI MCP client server support").
2. List the exact keywords you used and summarize the main findings with at least 2 reference links. If a search returns no links, state that explicitly.
3. Keep the final answer within one short paragraph plus the keyword list.
'@

Write-Host "=== helloai token-usage e2e verification ==="
Write-Host ("executor=" + $ExecutorAgentId + " planner=" + $PlannerAgentId + " reviewer=" + $ReviewerAgentId)

# ============================================================
# STEP1: admin login
# ============================================================
Write-Host "STEP1: admin login"
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type = "admin"
    username = $AdminUsername
    credential = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
$adminHeaders = @{ "X-Admin-Token" = $loginResp.data.token }
Write-Host "admin token ok"

# ============================================================
# STEP2: preflight inner executor (ACTIVE + web_search tool enabled)
# ============================================================
Write-Host "STEP2: preflight inner executor"
$status = Get-PsqlField ("SELECT COALESCE(status,'') FROM agent WHERE id = " + $ExecutorAgentId + " AND deleted = 0")
Write-Host ("  executor status=" + $status)
Assert-True ($status -eq "ACTIVE") ("inner executor not ACTIVE: " + $status)
$toolRow = Get-PsqlField ("SELECT tool_name FROM agent_mcp_server WHERE agent_id = " + $ExecutorAgentId + " AND tool_name = 'web_search' AND is_enabled = 1 AND deleted = 0")
Write-Host ("  web_search tool row=" + $toolRow)
Assert-True ($toolRow -eq "web_search") ("web_search tool not enabled for inner executor")
$credRow = Get-PsqlField ("SELECT COALESCE(provider,'') FROM credential_vault WHERE owner_type = 'AGENT' AND owner_id = " + $ExecutorAgentId + " AND status = 'ACTIVE' AND deleted = 0")
Write-Host ("  active agent credential provider=" + $credRow)

# ============================================================
# STEP3: dedup scan (fixed title = dedup key; never re-issue)
# ============================================================
Write-Host "STEP3: dedup scan on /api/tasks/list"
$existingTaskId = $null
$page = 1
while ($true) {
    $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/list?page=" + $page + "&pageSize=200") -Body $null -Headers $adminHeaders
    Assert-True ($listResp.code -eq 200) ("tasks list code=" + $listResp.code)
    $recs = @($listResp.data.list)
    foreach ($r in $recs) {
        if ([string]$r.title -eq $TaskTitle) { $existingTaskId = [string]$r.id }
    }
    if ($recs.Count -lt 200) { break }
    $page = $page + 1
    if ($page -gt 20) { break }
}
if ($null -ne $existingTaskId) {
    Write-Host ("  task already exists, id=" + $existingTaskId + " (will not re-issue)")
} else {
    Write-Host "  no existing task with this title"
}

# ============================================================
# STEP4: create + decompose + confirm (skipped when -AssertOnly)
# ============================================================
$taskId = $existingTaskId
if (-not $AssertOnly) {
    if ($null -eq $taskId) {
        Write-Host "STEP4: create task (inner executor pinned) + plan + confirm"
        $taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
            title = $TaskTitle
            description = $TaskDesc
            agentPolicy = @{
                plannerAgentId  = $PlannerAgentId
                executorAgentIds = @($ExecutorAgentId)
                reviewerAgentId = $ReviewerAgentId
                fallbackPolicy = "AUTO"
                difficulty = "LOW"
            }
            requiredSkills = @("eng-web-research")
        } -Headers $adminHeaders
        Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code + " msg=" + $taskResp.msg)
        $taskId = [string]$taskResp.data.id
        Write-Host ("  taskId=" + $taskId)

        $planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/planById/" + $taskId) -Body @{} -Headers $adminHeaders -TimeoutSec 30
        Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
        $drafts = @(Wait-Drafts -TaskId $taskId -MaxSecs $PlanTimeoutSec -Headers $adminHeaders)
        Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
        foreach ($d in $drafts) {
            Write-Host ("    draft id=" + $d.id + " skills=[" + (@($d.requiredSkills) -join ",") + "]")
        }

        $confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/confirmPlanByTaskId/" + $taskId) -Body @{} -Headers $adminHeaders
        Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)
        Write-Host "  plan confirmed, dispatched"
    } else {
        Write-Host "STEP4: skipped (task exists, no re-issue)"
    }
} else {
    Assert-True ($null -ne $taskId) ("AssertOnly but no task with title " + $TaskTitle)
    Write-Host "STEP4: skipped (AssertOnly)"
}

# ============================================================
# STEP5: watch sub tasks until closed
# ============================================================
Write-Host ("STEP5: watch sub tasks (timeout=" + $LoopTimeoutSec + "s, poll=" + $PollIntervalSec + "s)")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allClosed = $false
$lastSubTasks = @()
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $lastSubTasks = @(Get-SubTasks -TaskId $taskId -Headers $adminHeaders)
    # DEAD_LETTER is a terminal business state (rework fuse / reviewer strictness);
    # the token usage chain under test is unaffected, so it is reported but does
    # not block the watch loop.
    $dead = @($lastSubTasks | Where-Object { $_.status -eq "DEAD_LETTER" })
    if ($dead.Count -gt 0) {
        Write-Host ("  [warn] dead-letter sub tasks observed (terminal, not blocking): " + (($dead | ForEach-Object { $_.id }) -join ","))
    }
    $notClosed = 0
    foreach ($s in $lastSubTasks) {
        if ($s.status -notin @("DONE", "CANCELLED", "DEAD_LETTER")) { $notClosed++ }
    }
    $usagePeek = Get-PsqlField ("SELECT COALESCE(string_agg(DISTINCT token_usage::text, ','), '') FROM agent_execution_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = " + $taskId + ") AND token_usage IS NOT NULL")
    Write-Host ("  [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] status=[" + ((@($lastSubTasks) | ForEach-Object { $_.status }) -join ",") + "] tokenUsageSeen=[" + $usagePeek + "]")
    if ($notClosed -eq 0) { $allClosed = $true; break }
}

Write-Host ("  watch done: allClosed=" + $allClosed)

# ============================================================
# STEP6: DB evidence + assertions on the three token channels
# ============================================================
Write-Host "STEP6: assert token usage evidence (record column / event payload / sub task context)"

# Channel 1: agent_execution_record.token_usage (V97 column, CAS write path)
$recRows = @()
foreach ($s in $lastSubTasks) {
    $row = Get-PsqlField ("SELECT COALESCE(string_agg(DISTINCT status || ':' || COALESCE(token_usage::text,'null'), ','), '') FROM agent_execution_record WHERE sub_task_id = " + $s.id)
    if ($row) { $recRows += ("subTask=" + $s.id + " status=[" + $s.status + "] records=" + $row) }
}
foreach ($r in $recRows) { Write-Host ("  [record] " + $r) }
$tokRecords = Get-PsqlField ("SELECT COALESCE(string_agg(DISTINCT token_usage::text, ','), '') FROM agent_execution_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = " + $taskId + ") AND token_usage IS NOT NULL")
Write-Host ("  [record] distinct token_usage values in task scope = " + $tokRecords)
Assert-True (-not [string]::IsNullOrEmpty($tokRecords)) "no agent_execution_record row carried token_usage for this task"

# Channel 2: agent_event AGENT_COMPLETED payload.tokens
# (event_type is stored lower-case: 'agent_completed')
$evtTokens = Get-PsqlField ("SELECT COALESCE(string_agg(DISTINCT payload->>'tokens', ','), '') FROM agent_event WHERE task_id = " + $taskId + " AND event_type = 'agent_completed' AND payload->>'tokens' IS NOT NULL")
Write-Host ("  [event] AGENT_COMPLETED payload.tokens values = " + $evtTokens)
Assert-True (-not [string]::IsNullOrEmpty($evtTokens)) "no AGENT_COMPLETED event carried payload.tokens for this task"

# Channel 3: sub_task.context.lastExecution.tokens
$ctxTokens = Get-PsqlField ("SELECT COALESCE(string_agg(DISTINCT context->'lastExecution'->>'tokens', ','), '') FROM sub_task WHERE task_id = " + $taskId + " AND context->'lastExecution'->>'tokens' IS NOT NULL")
Write-Host ("  [context] sub_task.context.lastExecution.tokens values = " + $ctxTokens)
Assert-True (-not [string]::IsNullOrEmpty($ctxTokens)) "no sub_task.context.lastExecution.tokens for this task"

Write-Host ""
Write-Host "OK: token-usage e2e passed (record column + event payload + sub task context all carried token evidence)"
Write-Host ("taskId=" + $taskId + " subTasks=" + $lastSubTasks.Count)
exit 0
