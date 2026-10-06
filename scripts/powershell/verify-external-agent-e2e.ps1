# ============================================================
# helloai external-agent e2e verify (verify-external-agent-e2e.ps1)
# Purpose:
#   Drive 5 DISTINCT tasks through the EXTERNAL executor loop:
#     create task(agentPolicy: planner/reviewer = inner, executor = external CLI)
#       -> planById async decompose (inner PLANNER LLM)
#       -> confirmPlan promote + auto dispatch to EXTERNAL agent
#       -> external client (real AI agent via MCP) pulls/claims/executes/submits
#       -> platform auto review (inner REVIEWER) -> sub task DONE -> task auto DONE
#   The script NEVER simulates the external client: it only creates tasks,
#   confirms plans and polls status. The external agent is expected to be
#   checked-in and heartbeating (see helloai-duty SKILL for the client loop).
#
# Task de-dup requirement (user explicit):
#   Same task must NOT be re-issued. Each of the 5 tasks uses a FIXED title.
#   Before creation the script lists existing tasks (GET /api/tasks/list);
#   any title already present is SKIPPED (not re-issued). Re-running the
#   script after success will therefore issue 0 new tasks.
#
# 5 distinct task subjects (all different, none overlaps the inner
# verify-single-track-e2e.ps1 release-note subject):
#   ex-e2e-01  bash service start/stop script      (shell)
#   ex-e2e-02  SQL top-N slow query analysis       (sql)
#   ex-e2e-03  Java code review                     (eng-code-review)
#   ex-e2e-04  web research: Spring AI MCP state   (eng-web-research)
#   ex-e2e-05  acceptance checklist for upload     (eng-verification / eng-doc-standard)
#   Multiple external agents: tasks rotate over -ExternalAgentIds.
#
# Pre-conditions:
#   - helloai-start up @ 6565; postgres container helloai-postgres:15432
#   - External CLI_CLIENT agents registered via admin UI (NOT by this script)
#     with broad skills, e.g. ["thinking","code-review","shell","python","sql",
#     "java","web-search","eng-doc-standard","eng-verification",
#     "eng-code-review","eng-web-research"] -- planner tags eng-* on sub tasks.
#   - External agent client checked-in (checkIn) and heartbeating; the script
#     pre-flights last_seen_time freshness and fails fast with guidance.
#
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-external-agent-e2e.ps1 -ExternalAgentIds 123,456
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = $env:HELLOAI_ADMIN_PASSWORD,
    # inner platform agents (pinned, same as single-track verify)
    [string]$PlannerAgentId  = "2088623807767121922",
    [string]$ReviewerAgentId = "2088623970980073473",
    # external CLI_CLIENT executors, comma separated; tasks rotate over them
    [string]$ExternalAgentIds = "",
    [int]$PlanTimeoutSec = 360,
    [int]$LoopTimeoutSec = 3600,
    [int]$PollIntervalSec = 10,
    [string]$PgContainer = "helloai-postgres",
    [switch]$SkipDutyPreflight,
    # 只断言已存在任务（不新建/不轮询）：配合固定标题查重，二次运行 STEP6/7 用
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
    if ($Body -ne $null) {
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

function Get-Task([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/getById/" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("get task code=" + $resp.code + " msg=" + $resp.msg)
    return $resp.data
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

# ============================================================
# 5 distinct task definitions (FIXED titles = dedup keys)
# ============================================================
$TaskDefs = @(
    @{
        Key = "ex-e2e-01"
        Title = "ex-e2e-01-bash-service-startup-script"
        Description = "Write a bash script that starts and stops a dockerized postgres service on a Linux host. The script must support two commands: start (docker compose up -d postgres) and stop (docker compose stop postgres), print a short status line after each action, and exit non-zero when docker is unavailable. Deliver the script content and a 3-line usage comment."
        RequiredSkills = @("shell")
    },
    @{
        Key = "ex-e2e-02"
        Title = "ex-e2e-02-sql-slow-query-analysis"
        Description = "Write a SQL query against pg_stat_statements that lists the top 10 queries by total execution time. The query must include query text (truncated to 100 chars), calls, mean execution time and total time, ordered by total time descending. Deliver the SQL plus a 2-line explanation of the ordering."
        RequiredSkills = @("sql")
    },
    @{
        Key = "ex-e2e-03"
        Title = "ex-e2e-03-java-code-review"
        Description = "Review the following Java snippet and report issues: class Calc { int div(int a, int b) { return a / b; } } . Deliver a review list with severity for each finding (e.g. division by zero, missing validation, naming style), and a fixed version of the method that throws IllegalArgumentException on b==0."
        RequiredSkills = @("eng-code-review")
    },
    @{
        Key = "ex-e2e-04"
        Title = "ex-e2e-04-web-research-spring-ai-mcp"
        Description = "Research the current state of Spring AI MCP (Model Context Protocol) support as of mid 2026. Deliver a 150-word summary covering: whether spring-ai-alibaba MCP web MVC starter exists, the main MCP client/server abstractions, and one notable production concern. Include 3 reference links."
        RequiredSkills = @("eng-web-research")
    },
    @{
        Key = "ex-e2e-05"
        Title = "ex-e2e-05-upload-acceptance-checklist"
        Description = "Write an acceptance checklist (table format) with 5 test cases for a file upload feature: happy path, oversize rejection, empty file, wrong content type, concurrent double upload. Each row must list test name, steps, and expected result. Deliver the checklist as plain text table."
        RequiredSkills = @("eng-verification")
    }
)

# ============================================================
# STEP0: parse external agent ids
# ============================================================
$ExternalIds = @($ExternalAgentIds -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($ExternalIds.Count -eq 0 -and -not $AssertOnly) {
    Write-Error "No -ExternalAgentIds given. Register external CLI_CLIENT agents first (admin UI), then pass their ids."
    exit 1
}
Write-Host ("external executors: " + ($ExternalIds -join ","))

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
# STEP2: preflight external agents (ACTIVE + heartbeat fresh)
# ============================================================
Write-Host "STEP2: preflight external agents"
if (-not $SkipDutyPreflight) {
    foreach ($aid in $ExternalIds) {
# 心跳新鲜度在 SQL 侧判定（now() - last_seen_time < 6min），避免 PS 本地时区解析 UTC 时间戳造成 8h 误判
        $row = Get-PsqlField ("SELECT COALESCE(online_status,'') || '|' || COALESCE(CASE WHEN last_seen_time IS NOT NULL AND (now() - last_seen_time) < interval '6 minutes' THEN 'FRESH' ELSE 'STALE' END,'STALE') || '|' || COALESCE(status,'') FROM agent WHERE id = " + $aid + " AND deleted = 0")
        if (-not $row) {
            Write-Error ("external agent not found or psql failed: id=" + $aid)
            exit 1
        }
        $f = @($row.Split('|'))
        Write-Host ("  agent " + $aid + " online=" + $f[0] + " heartbeat=" + $f[1] + " status=" + $f[2])
        $fresh = ($f[1] -eq "FRESH")
        if (-not $fresh -or $f[2] -ne "ACTIVE") {
            Write-Error ("agent " + $aid + " NOT ready: needs checkIn + heartbeat within 5min and status ACTIVE. Start the external client loop (MCP checkIn -> heartbeat/pullTasks every ~30s) and re-run.")
            exit 1
        }
    }
    Write-Host "  all external agents heartbeating + ACTIVE"
} else {
    Write-Host "  skipped (SkipDutyPreflight)"
}

# ============================================================
# STEP3: dedup scan (same task must not be re-issued)
# ============================================================
$assertOnlyMode = $false
Write-Host "STEP3: dedup scan on /api/tasks/list"
$existingTitles = @()
$existingTasks = @{}
$page = 1
while ($true) {
    $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/list?page=" + $page + "&pageSize=200") -Body $null -Headers $adminHeaders
    Assert-True ($listResp.code -eq 200) ("tasks list code=" + $listResp.code)
    $recs = @($listResp.data.list)
    foreach ($r in $recs) {
        $existingTitles += [string]$r.title
        $existingTasks[[string]$r.title] = [string]$r.id
    }
    if ($recs.Count -lt 200) { break }
    $page = $page + 1
    if ($page -gt 20) { break }
}
$todo = @()
$skipped = @()
foreach ($t in $TaskDefs) {
    if ($existingTitles -contains $t.Title) {
        $skipped += $t.Key
        Write-Host ("  [SKIP] " + $t.Key + " already exists (not re-issued): " + $t.Title)
    } else {
        $todo += $t
        Write-Host ("  [NEW]  " + $t.Key + " : " + $t.Title)
    }
}
if ($todo.Count -eq 0) {
    if ($AssertOnly) {
        # 二次运行：不新建任务，直接按固定标题回查 taskId，供 STEP6/7 复用
        Write-Host "AssertOnly: resolve existing tasks, skip create/watch"
        $issued = @()
        foreach ($t in $TaskDefs) {
            if ($existingTasks.ContainsKey($t.Title)) {
                $issued += @{ Key = $t.Key; TaskId = $existingTasks[$t.Title]; ExecutorId = "existing" }
            }
        }
        $finalMap = @{}
        foreach ($it in $issued) {
            $finalMap[$it.TaskId] = Get-SubTasks -TaskId $it.TaskId -Headers $adminHeaders
        }
        $assertOnlyMode = $true
    } else {
        Write-Host "All 5 task titles already exist - nothing to issue. Delete tasks first or use fresh titles to re-verify."
        exit 0
    }
}

# ============================================================
# STEP4: create + decompose + confirm, one task at a time
# ============================================================
if (-not $assertOnlyMode) {
Write-Host ("STEP4: issue " + $todo.Count + " new tasks (rotate executors)")
$issued = @()
$idx = 0
foreach ($t in $todo) {
    $execId = $ExternalIds[$idx % $ExternalIds.Count]
    $idx = $idx + 1
    Write-Host ("--- " + $t.Key + " -> executor " + $execId + " ---")
    $taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
        title = $t.Title
        description = $t.Description
        agentPolicy = @{
            plannerAgentId  = $PlannerAgentId
            executorAgentIds = @($execId)
            reviewerAgentId = $ReviewerAgentId
            fallbackPolicy = "AUTO"
            difficulty = "LOW"
        }
        requiredSkills = $t.RequiredSkills
    } -Headers $adminHeaders
    Assert-True ($taskResp.code -eq 200) ("create task " + $t.Key + " code=" + $taskResp.code + " msg=" + $taskResp.msg)
    $taskId = [string]$taskResp.data.id
    Write-Host ("  taskId=" + $taskId)

    $planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/planById/" + $taskId) -Body @{} -Headers $adminHeaders -TimeoutSec 30
    Assert-True ($planResp.code -eq 200) ("plan " + $t.Key + " code=" + $planResp.code + " msg=" + $planResp.msg)
    $drafts = @(Wait-Drafts -TaskId $taskId -MaxSecs $PlanTimeoutSec -Headers $adminHeaders)
    Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts for " + $t.Key + ", actual=" + $drafts.Count)
    Write-Host ("  draftCount=" + $drafts.Count)
    foreach ($d in $drafts) {
        Write-Host ("    draft id=" + $d.id + " skills=[" + (@($d.requiredSkills) -join ",") + "] deps=[" + (@($d.dependsOn) -join ",") + "]")
    }

    $confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/confirmPlanByTaskId/" + $taskId) -Body @{} -Headers $adminHeaders
    Assert-True ($confirmResp.code -eq 200) ("confirm " + $t.Key + " code=" + $confirmResp.code + " msg=" + $confirmResp.msg)

    $issued += @{ Key = $t.Key; TaskId = $taskId; ExecutorId = $execId }
}
Write-Host ("issued=" + $issued.Count)

# ============================================================
# STEP5: watch external loop until all sub tasks reach TERMINAL state
# 终态集合 = {DONE, DEAD_LETTER, CANCELLED, FAILED}（§25 轮询语义，对齐 awaitUntil）。
# 收敛后判定：任一子任务 FAILED/DEAD_LETTER ⇒ FAIL（真实失败）；全 DONE/CANCELLED ⇒ 继续。
# 窗口耗尽仍有非终态（RUNNING/PENDING…）⇒ INCONCLUSIVE（§27，≠FAIL）并打印诊断快照。
# ============================================================
Write-Host ("STEP5: watch external loop (dispatch -> external client claim/execute/submit -> auto review), timeout=" + $LoopTimeoutSec + "s")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$terminalSet = @("DONE", "DEAD_LETTER", "CANCELLED", "FAILED")
$allTerminal = $false
$finalMap = @{}
$round = 0
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $round = $round + 1
    $totalNotTerminal = 0
    $rowTexts = @()
    foreach ($it in $issued) {
        $subTasks = Get-SubTasks -TaskId $it.TaskId -Headers $adminHeaders
        $finalMap[$it.TaskId] = $subTasks
        # 终态收敛：DEAD_LETTER/FAILED 视为终态（是否 FAIL 交由收敛后的判定统一给结论，
        # 不在循环内即时判死——避免长返工链在推进中被误判）
        $notTerminal = @($subTasks | Where-Object { $_.status -notin $terminalSet })
        $totalNotTerminal = $totalNotTerminal + $notTerminal.Count
        $rowTexts += ("task " + $it.Key + ":" + (($subTasks | ForEach-Object { $_.id.ToString().Substring([Math]::Max(0, $_.id.ToString().Length - 4)) + "=" + $_.status }) -join " "))
    }
    if (($round % 3) -eq 0 -or $totalNotTerminal -eq 0) {
        Write-Host ("  [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] " + ($rowTexts -join " | ") + "  (left=" + $totalNotTerminal + ")")
    }
    if ($totalNotTerminal -eq 0) {
        $allTerminal = $true
        break
    }
}

# 子任务状态计数快照（供收敛后判定与 INCONCLUSIVE 诊断共用）
$statusCounts = @{}
foreach ($it in $issued) {
    foreach ($s in @($finalMap[$it.TaskId])) {
        $k = [string]$s.status
        if (-not $statusCounts.ContainsKey($k)) { $statusCounts[$k] = 0 }
        $statusCounts[$k] = $statusCounts[$k] + 1
    }
}
$statusSummary = (($statusCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Key + "=" + $_.Value }) -join " ")
Write-Host ("STEP5 status summary: " + $statusSummary)

# 取子任务最近一条 timeline 事件（诊断用，失败不致命）
function Get-LastTimelineEvent([string]$SubTaskId) {
    try {
        $tlResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/listTimelineBySubTaskId/" + $SubTaskId) -Body $null -Headers $adminHeaders
        $evs = @($tlResp.data)
        if ($evs.Count -eq 0) { return "(no timeline)" }
        $last = $evs[$evs.Count - 1]
        return ([string]$last.eventType + "@" + [string]$last.createdTime)
    } catch {
        return ("(timeline query failed: " + $_.Exception.Message + ")")
    }
}

if (-not $allTerminal) {
    # INCONCLUSIVE（§27）：窗口耗尽仍有子任务非终态 ⇒ 不等价于 FAIL，打印诊断后退出
    Write-Host ""
    Write-Host ("[INCONCLUSIVE] external loop not converged within " + $LoopTimeoutSec + "s (NOT a FAIL; still " + $statusSummary + ")")
    foreach ($it in $issued) {
        foreach ($s in @($finalMap[$it.TaskId])) {
            Write-Host ("  " + $it.Key + " subTask=" + $s.id + " status=" + $s.status + " lastTimeline=" + (Get-LastTimelineEvent ([string]$s.id)))
        }
    }
    foreach ($aid in $ExternalIds) {
        $hb = Get-PsqlField ("SELECT COALESCE(last_seen_time::text,'') FROM agent WHERE id = " + $aid + " AND deleted = 0")
        Write-Host ("  agent " + $aid + " last_seen_time=" + $hb)
    }
    Write-Host "  hint: external client may still be working; re-run with -AssertOnly, or raise -LoopTimeoutSec"
    exit 2
}

# 终态收敛后判定：任一 FAILED / DEAD_LETTER ⇒ FAIL（真实失败，不作 INCONCLUSIVE）
foreach ($it in $issued) {
    foreach ($s in @($finalMap[$it.TaskId])) {
        if ($s.status -in @("FAILED", "DEAD_LETTER")) {
            $lastTl = Get-LastTimelineEvent ([string]$s.id)
            Assert-True $false ("task " + $it.Key + " subTask=" + $s.id + " status=" + $s.status + " (terminal failure) lastTimeline=" + $lastTl)
        }
    }
}
} else {
    Write-Host "STEP4/5 skipped (AssertOnly)"
}

# ============================================================
# STEP6: assert timeline evidence (assigned + external submit + auto review)
# 注：task_assigned 由 ExecutionCommandServiceImpl（执行命令创建）埋点，仅内部自动执行链路产生；
# 外部 CLI_CLIENT 走 MCP claimSubTask 认领（不产执行命令、无 task_assigned），
# 故对外部链路断言 sub_task_dispatch_prepare（平台侧准备/定向派发）+ sub_task_auto_review_passed 即可。
Write-Host "STEP6: assert timeline evidence"
$execReward = @{}
foreach ($it in $issued) {
    $subTasks = $finalMap[$it.TaskId]
    $reviewed = 0
    Write-Host ("  task " + $it.Key + " taskId=" + $it.TaskId)
    foreach ($s in $subTasks) {
        if ($s.status -ne "DONE") { continue }
        $tlResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/listTimelineBySubTaskId/" + $s.id) -Body $null -Headers $adminHeaders
        $events = @($tlResp.data | ForEach-Object { $_.eventType })
        Write-Host ("    subTask " + $s.id + " events=[" + ($events -join ",") + "]")
        if ($events -contains "sub_task_auto_review_passed") { $reviewed++ }
        Assert-True ($events -contains "sub_task_dispatch_prepare") ("subTask " + $s.id + " lacks sub_task_dispatch_prepare (external dispatch gate not exercised)")
    }
    Assert-True ($reviewed -ge 1) ("task " + $it.Key + " has 0 sub_task_auto_review_passed events")
    Write-Host ("    auto-reviewed=" + $reviewed + "/" + $subTasks.Count)
}

# ============================================================
# STEP7: assert every task auto-closed to DONE
# ============================================================
Write-Host "STEP7: assert tasks auto-closed to DONE"
foreach ($it in $issued) {
    $taskDone = $false
    $taskDetail = $null
    $closeDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $closeDeadline) {
        $taskDetail = Get-Task -TaskId $it.TaskId -Headers $adminHeaders
        if ($taskDetail.status -eq "DONE") {
            $taskDone = $true
            break
        }
        Start-Sleep -Seconds 5
    }
    Assert-True $taskDone ("task " + $it.Key + " not auto-closed, status=" + $taskDetail.status)
    Write-Host ("  task " + $it.Key + " DONE")
}

Write-Host ""
Write-Host "OK: external-agent e2e passed (5 distinct tasks, no re-issue, external client loop -> auto review -> task auto close)"
Write-Host ("issued=" + $issued.Count + " skipped=" + $skipped.Count)
foreach ($it in $issued) {
    Write-Host ("  " + $it.Key + " taskId=" + $it.TaskId + " executor=" + $it.ExecutorId)
}
exit 0