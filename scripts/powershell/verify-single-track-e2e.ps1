# ============================================================
# helloai G-002 单轨端到端验证脚本 (verify-single-track-e2e.ps1)
# 用途：数据清空后从零验证单轨链路完整闭环
#       create task(agentPolicy 锁定平台内建三角色)
#         -> planById 异步拆解(PLANNER 真身)
#         -> confirmPlan 转正 + 自动分发(dispatchPendingSubTaskAuto)
#         -> MQ 执行命令 -> LocalExecutionCommandConsumer 单轨消费
#         -> RuntimeTurnExecutor 真身执行 -> 自动核验 -> Task 自动 DONE
# 不注册任何新 Agent：复用保留的平台内建 API_KEY_LLM Agent
#       (planner/executor/reviewer 由 agentPolicy 显式指定，避免选到
#        CLI_CLIENT 外部执行者导致链路等待外部客户端)
# Ref:  doc/HelloAI 实现差距表.md（G-002 单轨硬切，2026-09-30）
# 前置：helloai-start 已在 6565 运行；数据已按 cleanup-test-data.sql 清空；
#       平台内建 Agent 已有 ACTIVE vault 凭据（require-vault=true）。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-single-track-e2e.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = $env:HELLOAI_ADMIN_PASSWORD,
    # G-002 单轨验证固定三角色（数据清理前保留的平台内建 API_KEY_LLM Agent）
    [string]$PlannerAgentId  = "2088623807767121922",
    [string]$ExecutorAgentId = "2088623654343675905",
    [string]$ReviewerAgentId = "2088623970980073473",
    [int]$PlanTimeoutSec = 360,
    [int]$LoopTimeoutSec = 1200,
    [int]$PollIntervalSec = 10
)
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw "未设置管理员口令：请导出环境变量 HELLOAI_ADMIN_PASSWORD（或传 -AdminPassword）" }

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
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

# 拆解异步化契约：planById 同步段只做状态推进并立即返回，草案经 findPlanByTaskId 轮询获取
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

Write-Host "STEP1: admin login"
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type = "admin"
    username = $AdminUsername
    credential = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
Assert-True (-not [string]::IsNullOrWhiteSpace($loginResp.data.token)) "admin token is empty"
$adminHeaders = @{ "X-Admin-Token" = $loginResp.data.token }

$ts = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")

Write-Host "STEP2: create task with agentPolicy pinned to platform inner agents"
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "single-track-e2e-" + $ts
    description = "Write a short release note for version 1.0 of an internal task management app. Deliver 3 parts: 1) a one-line positioning statement of the app; 2) three bullet highlights of new features; 3) a 60-word summary paragraph. Output plain text only."
    agentPolicy = @{
        plannerAgentId  = $PlannerAgentId
        executorAgentIds = @($ExecutorAgentId)
        reviewerAgentId = $ReviewerAgentId
        fallbackPolicy = "AUTO"
        difficulty = "LOW"
    }
} -Headers $adminHeaders
Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code + " msg=" + $taskResp.msg)
$taskId = [string]$taskResp.data.id
Write-Host ("taskId=" + $taskId)

Write-Host ("STEP3: trigger decompose (async, poll drafts; timeout=" + $PlanTimeoutSec + "s)")
$planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/planById/" + $taskId) -Body @{} `
    -Headers $adminHeaders -TimeoutSec 30
Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
$drafts = @(Wait-Drafts -TaskId $taskId -MaxSecs $PlanTimeoutSec -Headers $adminHeaders)
Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
Write-Host ("draftCount=" + $drafts.Count)
foreach ($d in $drafts) {
    Write-Host ("  draft id=" + $d.id + " title=" + $d.title + " deps=[" + (@($d.dependsOn) -join ",") + "]")
}

# STEP4: confirm plan triggers dispatchPendingSubTaskAuto after promotion
Write-Host 'STEP4: confirm plan (promote + auto dispatch chain)'
$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/confirmPlanByTaskId/" + $taskId) -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)

Write-Host ("STEP5: watch single-track loop (dispatch -> MQ consume -> RuntimeTurnExecutor -> auto review), timeout=" + $LoopTimeoutSec + "s")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allDone = $false
$subTasks = @()
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
    $summary = ($subTasks | ForEach-Object { "" + $_.id + ":" + $_.status }) -join " "
    Write-Host ("  [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] " + $summary)

    $dead = @($subTasks | Where-Object { $_.status -in @("DEAD_LETTER", "BLOCKED") })
    Assert-True ($dead.Count -eq 0) ("subTasks entered DEAD_LETTER/BLOCKED: " + (($dead | ForEach-Object { $_.id }) -join ","))

    $notDone = @($subTasks | Where-Object { $_.status -notin @("DONE", "CANCELLED") })
    if ($notDone.Count -eq 0) {
        $allDone = $true
        break
    }
}
Assert-True $allDone ("single-track loop not finished in " + $LoopTimeoutSec + "s")
Write-Host "all subTasks DONE/CANCELLED"

Write-Host "STEP6: assert timeline evidence (assigned/execution/auto review)"
$reviewedCount = 0
foreach ($s in $subTasks) {
    if ($s.status -ne "DONE") { continue }
    $tlResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/listTimelineBySubTaskId/" + $s.id) -Body $null -Headers $adminHeaders
    $events = @($tlResp.data | ForEach-Object { $_.eventType })
    Write-Host ("  subTask " + $s.id + " events=[" + ($events -join ",") + "]")
    if ($events -contains "sub_task_auto_review_passed") { $reviewedCount++ }
}
Assert-True ($reviewedCount -ge 1) "no subTask carries sub_task_auto_review_passed timeline event (auto review gate not exercised)"
Write-Host ("auto-review passed on " + $reviewedCount + " subTasks")

Write-Host "STEP7: assert task auto-closed to DONE"
$taskDone = $false
$taskDetail = $null
$closeDeadline = [DateTime]::UtcNow.AddSeconds(120)
while ([DateTime]::UtcNow -lt $closeDeadline) {
    $taskDetail = Get-Task -TaskId $taskId -Headers $adminHeaders
    if ($taskDetail.status -eq "DONE") {
        $taskDone = $true
        break
    }
    Start-Sleep -Seconds 5
}
Assert-True $taskDone ("task not auto-closed, status=" + $taskDetail.status)

Write-Host "OK: G-002 single-track e2e passed (create -> decompose -> confirm -> dispatch -> RuntimeTurnExecutor -> auto review -> task auto close)"
Write-Host ("taskId=" + $taskId)
Write-Host ("subTaskCount=" + $subTasks.Count + " autoReviewed=" + $reviewedCount)
exit 0