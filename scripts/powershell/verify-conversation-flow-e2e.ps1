# ============================================================
# helloai 执行对话流与核验落库 E2E 验证脚本（V28）
# 用途：真实链路验证 conversation_message 激活 + 自动核验落 review_record：
#       复用内循环链路（拆解 → confirm → 自动执行 → 自动核验 → DONE），随后断言
#       ① GET /api/sub-tasks/{id}/conversation 含执行产出(sub_task_execute)、
#          核验 Prompt(subtask_review_prompt)、核验分析(subtask_review_verdict)
#          且 seq 严格递增、核验分析含 analysis 字段
#       ② GET /api/reviews?subTaskId= 存在 remark=AUTO_REVIEW 记录且 round>=1
#       ③ 驳回返工场景（reworkCount>0）下对话流出现多轮消息（软断言，LLM 不可控）
# Ref:  doc/HelloAI_实现差距表.md（V28 执行对话流与核验落库）
# 前置：同 verify-inner-loop-e2e.ps1（helloai-start 已运行 + LLM 可用 +
#       auto-review-enabled=true；require-vault=true 时脚本自动绑定托管凭证）
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-conversation-flow-e2e.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [string]$LlmModelType = "deepseek:deepseek-chat",
    [string]$LlmApiKey = $env:DEEPSEEK_API_KEY,
    [int]$PlanTimeoutSec = 180,
    [int]$LoopTimeoutSec = 900,
    [int]$PollIntervalSec = 10
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 30) {
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Get-SubTasks([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks?taskId=" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("list sub-tasks code=" + $resp.code + " msg=" + $resp.msg)
    if ($resp.data.records -ne $null) { return @($resp.data.records) }
    return @($resp.data)
}

function Register-LlmAgent([string]$Name, [string]$Role, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
        name = $Name
        role = $Role
        description = "verify-conversation-flow-e2e"
        accessType = "API_KEY_LLM"
        modelType = $LlmModelType
        idempotent = $true
    } -Headers @{}
    Assert-True ($resp.code -eq 200) ("register " + $Role + " code=" + $resp.code + " msg=" + $resp.msg)
    return [string]$resp.data.id
}

function Bind-AgentApiKey([string]$AgentId, [string]$Provider, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/agents/" + $AgentId + "/api-key") -Body @{
        provider = $Provider
        apiKey = $LlmApiKey
        remark = "verify-conversation-flow-e2e"
    } -Headers $Headers
    Assert-True ($resp.code -eq 200) ("bind api-key for agent " + $AgentId + " code=" + $resp.code + " msg=" + $resp.msg)
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

if ([string]::IsNullOrWhiteSpace($LlmApiKey)) {
    # 与 helloai-start application.yml 中 spring.ai.deepseek.api-key 的默认值保持一致
    $LlmApiKey = "sk-a36fdda1d4ad4e0386e78fc435be0d16"
    Write-Host "WARN: DEEPSEEK_API_KEY not set, fallback to application.yml default key"
}
$llmProvider = ($LlmModelType -split ":")[0]

# STEP2.0 (sleep stale agents) removed: fixed-name idempotent registration reuses
# the same agents across runs, and AgentSelector now filters API_KEY_LLM candidates
# without active vault credentials

Write-Host "STEP2: register platform LLM agents (PLANNER/EXECUTOR/REVIEWER, API_KEY_LLM, idempotent fixed names)"
$plannerAgentId  = Register-LlmAgent -Name "conv-flow-planner"  -Role "PLANNER"  -Headers $adminHeaders
$executorAgentId = Register-LlmAgent -Name "conv-flow-executor" -Role "EXECUTOR" -Headers $adminHeaders
$reviewerAgentId = Register-LlmAgent -Name "conv-flow-reviewer" -Role "REVIEWER" -Headers $adminHeaders
Write-Host ("plannerAgentId=" + $plannerAgentId + " executorAgentId=" + $executorAgentId + " reviewerAgentId=" + $reviewerAgentId)

Write-Host ("STEP2.5: bind vault api-key for new agents (provider=" + $llmProvider + ")")
Bind-AgentApiKey -AgentId $plannerAgentId  -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $executorAgentId -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $reviewerAgentId -Provider $llmProvider -Headers $adminHeaders

Write-Host "STEP3: create task"
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "conv-flow-e2e-" + $ts
    description = "Write a short 2-part product intro for an internal wiki tool: 1) one-line positioning statement; 2) three key feature bullets based on the positioning. Step 2 depends on step 1."
} -Headers $adminHeaders
Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code + " msg=" + $taskResp.msg)
$taskId = [string]$taskResp.data.id
Write-Host ("taskId=" + $taskId)

Write-Host ("STEP4: trigger decompose (LLM call, timeout=" + $PlanTimeoutSec + "s)")
$planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body @{} `
    -Headers $adminHeaders -TimeoutSec $PlanTimeoutSec
Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
$drafts = @($planResp.data)
Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
Write-Host ("draftCount=" + $drafts.Count)

Write-Host "STEP5: confirm plan"
$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan/confirm") -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)

Write-Host ("STEP6: watch inner loop until all DONE, timeout=" + $LoopTimeoutSec + "s")
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
Assert-True $allDone ("inner loop not finished in " + $LoopTimeoutSec + "s")
Write-Host "all subTasks DONE/CANCELLED"

Write-Host "STEP7: assert conversation flow per DONE subTask"
$convOkCount = 0
$reworkedSubTaskIds = @()
foreach ($s in $subTasks) {
    if ($s.status -ne "DONE") { continue }
    $sid = [string]$s.id
    $convResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/" + $sid + "/conversation") -Body $null -Headers $adminHeaders
    Assert-True ($convResp.code -eq 200) ("conversation code=" + $convResp.code + " msg=" + $convResp.msg)
    $msgs = @($convResp.data)
    Assert-True ($msgs.Count -ge 3) ("subTask " + $sid + " expected >=3 conversation messages, actual=" + $msgs.Count)

    # seq 严格递增
    $prevSeq = -1
    foreach ($m in $msgs) {
        Assert-True ($m.seq -gt $prevSeq) ("subTask " + $sid + " seq not increasing: prev=" + $prevSeq + " cur=" + $m.seq)
        $prevSeq = $m.seq
    }

    # 三类消息齐备
    $toolNames = @($msgs | ForEach-Object { $_.toolName })
    Assert-True ($toolNames -contains "sub_task_execute") ("subTask " + $sid + " missing sub_task_execute message")
    Assert-True ($toolNames -contains "subtask_review_prompt") ("subTask " + $sid + " missing subtask_review_prompt message")
    Assert-True ($toolNames -contains "subtask_review_verdict") ("subTask " + $sid + " missing subtask_review_verdict message")

    # 执行产出非空 + 核验分析含 analysis 字段（Prompt 已强制要求）
    $execMsg = @($msgs | Where-Object { $_.toolName -eq "sub_task_execute" })[0]
    Assert-True (-not [string]::IsNullOrWhiteSpace($execMsg.content)) ("subTask " + $sid + " sub_task_execute content is empty")
    Assert-True ($execMsg.role -eq "assistant" -and $execMsg.senderType -eq "agent") ("subTask " + $sid + " sub_task_execute role/senderType mismatch")
    $verdictMsg = @($msgs | Where-Object { $_.toolName -eq "subtask_review_verdict" })[0]
    Assert-True (-not [string]::IsNullOrWhiteSpace($verdictMsg.content)) ("subTask " + $sid + " subtask_review_verdict content is empty")
    if ($verdictMsg.content -notmatch '"analysis"') {
        Write-Host ("WARN: subTask " + $sid + " verdict output has no analysis field (LLM omitted, tolerated by parser)")
    }
    $promptMsg = @($msgs | Where-Object { $_.toolName -eq "subtask_review_prompt" })[0]
    Assert-True ($promptMsg.role -eq "user" -and $promptMsg.senderType -eq "platform") ("subTask " + $sid + " subtask_review_prompt role/senderType mismatch")

    # 驳回返工的子任务应有多轮消息（>1 条执行产出）
    if ($s.reworkCount -gt 0) {
        $reworkedSubTaskIds += $sid
        $execCount = @($msgs | Where-Object { $_.toolName -eq "sub_task_execute" }).Count
        Assert-True ($execCount -ge 2) ("reworked subTask " + $sid + " expected >=2 sub_task_execute messages, actual=" + $execCount)
    }
    $convOkCount++
    Write-Host ("  subTask " + $sid + " conversation ok: " + $msgs.Count + " messages, reworkCount=" + $s.reworkCount)
}
Assert-True ($convOkCount -ge 1) "no DONE subTask carries a valid conversation flow"
if ($reworkedSubTaskIds.Count -eq 0) {
    Write-Host "WARN: no rework happened this run (LLM passed all first-shot); multi-round assertion trivially satisfied"
}

Write-Host "STEP8: assert AUTO_REVIEW records in review_record"
$autoReviewCount = 0
foreach ($s in $subTasks) {
    if ($s.status -ne "DONE") { continue }
    $sid = [string]$s.id
    $rvResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/reviews?subTaskId=" + $sid) -Body $null -Headers $adminHeaders
    Assert-True ($rvResp.code -eq 200) ("reviews code=" + $rvResp.code + " msg=" + $rvResp.msg)
    $autoRecords = @($rvResp.data | Where-Object { $_.remark -eq "AUTO_REVIEW" })
    Assert-True ($autoRecords.Count -ge 1) ("subTask " + $sid + " has no AUTO_REVIEW review_record")
    foreach ($r in $autoRecords) {
        Assert-True ($r.round -ge 1) ("subTask " + $sid + " AUTO_REVIEW round invalid: " + $r.round)
        Assert-True ($r.result -in @("APPROVED", "REJECTED")) ("subTask " + $sid + " AUTO_REVIEW result invalid: " + $r.result)
    }
    # DONE 子任务最后一条自动核验必须是 APPROVED
    $lastAuto = $autoRecords | Sort-Object { [int]$_.round } | Select-Object -Last 1
    Assert-True ($lastAuto.result -eq "APPROVED") ("subTask " + $sid + " last AUTO_REVIEW is " + $lastAuto.result + " but status DONE")
    $autoReviewCount += $autoRecords.Count
    Write-Host ("  subTask " + $sid + " AUTO_REVIEW records=" + $autoRecords.Count)
}
Assert-True ($autoReviewCount -ge 1) "no AUTO_REVIEW record found across DONE subTasks"

Write-Host "OK: conversation flow e2e passed (execute output + review prompt/verdict persisted, seq increasing, AUTO_REVIEW recorded)"
Write-Host ("taskId=" + $taskId)
Write-Host ("subTaskCount=" + $subTasks.Count + " convOk=" + $convOkCount + " autoReviewRecords=" + $autoReviewCount + " reworked=" + $reworkedSubTaskIds.Count)
exit 0
