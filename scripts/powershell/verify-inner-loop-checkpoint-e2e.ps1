# ============================================================
# HelloAI inner-loop checkpoint E2E verification
# (P0-C checkpoint; audit 2026-09-30 section 12.6 item 3: real E2E
#  DB evidence for agent_session.snapshot.loop growth across the
#  ChatModelToolLoop tool-calling iterations).
#
# Flow: admin login -> preflight inner agents -> dedup scan (fixed
# title) -> create task with the inner executor pinned via
# agent_policy.executorAgentIds -> plan -> confirm -> inner Runtime
# executes through ChatModelToolLoop with the web_search tool
# enabled -> poll agent_session.snapshot->'loop' while the sub task
# runs -> assert the loop snapshot appeared with iteration >= 1,
# toolCallCount >= 1 and executedTools containing web_search.
#
# Why this must run against a real provider:
#   MockChatModel emits plain text only (no tool_calls), so the loop
#   ends on its terminal round and no checkpoint fires. The inner
#   executor (API_KEY_LLM, deepseek) is exempt from heartbeat/OFFLINE
#   selection filters (AgentSelector.isHeartbeatFresh), so no external
#   client is needed - the task is dispatched and executed in-process.
#
# Pre-conditions:
#   - helloai-start up @ 6565 (local profile, real mode:
#     helloai.execution.mock-mode=false, require-vault=true)
#   - postgres container helloai-postgres:15432 reachable via docker
#   - inner agents ACTIVE in DB:
#       planner  2088623807767121922
#       executor 2088623654343675905 (web_search enabled in
#                agent_mcp_server; skills include eng-web-research)
#       reviewer 2088623970980073473
#   - inner executor has an ACTIVE deepseek credential in
#     credential_vault; bocha web-search key in sys_config
#
# Usage (project root):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-inner-loop-checkpoint-e2e.ps1
#   powershell ... -AssertOnly     # assert on the existing task only
#   powershell ... -LoopTimeoutSec 1800
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [string]$PlannerAgentId  = "2088623807767121922",
    [string]$ExecutorAgentId = "2088623654343675905",
    [string]$ReviewerAgentId = "2088623970980073473",
    [string]$TaskTitle = "ckpt-e2e-01-inner-loop-web-search",
    [int]$PlanTimeoutSec = 360,
    [int]$LoopTimeoutSec = 1200,
    [int]$PollIntervalSec = 10,
    [string]$PgContainer = "helloai-postgres",
    [switch]$AssertOnly
)

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
    $line = $output | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -First 1
    return $line
}

# Task description: hard requirement to exercise the web_search tool
# at least twice, so the loop has >= 2 tool rounds and the checkpoint
# fires more than once (iteration growth observable).
$TaskDesc = @'
Deliver a short Chinese summary (200-300 characters) on the current state of the Spring AI MCP (Model Context Protocol) ecosystem, based on live web research performed with the platform web_search tool.

HARD REQUIREMENTS:
1. You MUST call the web_search tool at least twice with two DIFFERENT keyword sets (for example: first "Spring AI MCP server support 2026", then "spring-ai-alibaba MCP web MVC starter").
2. List the exact keywords you used and summarize the main findings with at least 2 reference links. If a search returns no links, state that explicitly.
3. Keep the final answer within one short paragraph plus the keyword list.
'@

Write-Host "=== helloai inner-loop checkpoint e2e verification ==="
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
Write-Host "  (API_KEY_LLM agents are exempt from heartbeat/OFFLINE select filters)"

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
# STEP5: watch sub tasks + poll agent_session.snapshot->'loop'
# ============================================================
Write-Host ("STEP5: watch inner loop (timeout=" + $LoopTimeoutSec + "s, poll=" + $PollIntervalSec + "s)")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allClosed = $false
$loopSeen = $false
$loopHistory = @()
$lastSubTasks = @()
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $lastSubTasks = @(Get-SubTasks -TaskId $taskId -Headers $adminHeaders)
    $dead = @($lastSubTasks | Where-Object { $_.status -eq "DEAD_LETTER" })
    Assert-True ($dead.Count -eq 0) ("sub task entered DEAD_LETTER: " + (($dead | ForEach-Object { $_.id }) -join ","))
    $notClosed = 0
    foreach ($s in $lastSubTasks) {
        if ($s.status -notin @("DONE", "CANCELLED")) { $notClosed++ }
        $loopJson = Get-PsqlField ("SELECT snapshot->'loop'::text FROM agent_session WHERE sub_task_id = " + $s.id + " AND snapshot ? 'loop' ORDER BY id DESC LIMIT 1")
        if ($loopJson) {
            $loopSeen = $true
            $row = ("subTask=" + $s.id + " loop=" + $loopJson)
            if ($loopHistory -notcontains $row) { $loopHistory += $row }
        }
    }
    Write-Host ("  [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] status=[" + ((@($lastSubTasks) | ForEach-Object { $_.status }) -join ",") + "] loopSeen=" + $loopSeen)
    if ($notClosed -eq 0) { $allClosed = $true; break }
}

Write-Host ("  watch done: allClosed=" + $allClosed + " loopSeen=" + $loopSeen)
if ($loopHistory.Count -gt 0) {
    Write-Host "  loop checkpoint history (deduped):"
    foreach ($row in $loopHistory) { Write-Host ("    " + $row) }
}

# ============================================================
# STEP6: DB evidence + assertions on snapshot.loop
# ============================================================
Write-Host "STEP6: assert agent_session.snapshot.loop evidence"
Assert-True ($loopSeen) "agent_session.snapshot.loop never appeared during the run (checkpoint write path broken?)"
$passed = 0
foreach ($s in $lastSubTasks) {
    $loopJson = Get-PsqlField ("SELECT snapshot->'loop'::text FROM agent_session WHERE sub_task_id = " + $s.id + " AND snapshot ? 'loop' ORDER BY id DESC LIMIT 1")
    if (-not $loopJson) { continue }
    Write-Host ("  subTask " + $s.id + " status=" + $s.status + " snapshot.loop=" + $loopJson)
    $sessRow = Get-PsqlField ("SELECT turn || '|' || step || '|' || status FROM agent_session WHERE sub_task_id = " + $s.id + " ORDER BY id DESC LIMIT 1")
    Write-Host ("    agent_session latest row (turn|step|status) = " + $sessRow)
    $evt = Get-PsqlField ("SELECT COALESCE(string_agg(event_type || ' x' || cnt, ', ' ORDER BY event_type), '') FROM (SELECT event_type, count(*) AS cnt FROM agent_event WHERE sub_task_id = " + $s.id + " GROUP BY event_type) t")
    Write-Host ("    agent_event summary: " + $evt)
    $loop = $loopJson | ConvertFrom-Json
    Assert-True ([int]$loop.iteration -ge 1) ("iteration < 1: " + $loopJson)
    Assert-True ([int]$loop.toolCallCount -ge 1) ("toolCallCount < 1: " + $loopJson)
    # web_search presence is required for >=1 sub task overall; a document-only
    # sub task (e.g. the plan/contract draft) may legitimately never call it,
    # so count matching sub tasks instead of asserting on every one.
    $tools = @($loop.executedTools)
    if ($tools -contains "web_search") { $passed++ }
}
Assert-True ($passed -ge 1) "no sub task carried a snapshot.loop row with web_search in executedTools"

Write-Host ""
Write-Host "OK: inner-loop checkpoint e2e passed (snapshot.loop persisted with iteration>=1, executedTools contains web_search)"
Write-Host ("taskId=" + $taskId + " subTasks=" + $lastSubTasks.Count + " checked=" + $passed)
exit 0
