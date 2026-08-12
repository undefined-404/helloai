# ============================================================
# helloai A1 任务执行策略透传验证脚本（V47 收尾）
# 用途：验证任务创建/编辑接口已透传 agentPolicy/requiredSkills/slaMinutes：
#       POST /api/tasks            创建带策略任务（policy 五键 + 技能 + SLA）
#       GET  /api/tasks/getById    回显断言（DB 落库证明）
#       PUT  /api/tasks/updateById 编辑（整体替换 policy）+ 空集合清空
#       清理：删除验证任务
# Ref:  doc/HelloAI_迭代执行记录.md §6.69（A1）
# 前置：helloai-start（A1 代码）已在 6565 运行。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-a1-task-policy.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123"
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

function Get-Task([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/getById/" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("get task code=" + $resp.code + " msg=" + $resp.msg)
    return $resp.data
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

Write-Host "STEP2: register fixed-name agents (idempotent) for policy target"
$plannerResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = "a1-policy-planner"
    role = "PLANNER"
    description = "verify-a1-task-policy"
    accessType = "API_KEY_LLM"
    modelType = "deepseek:deepseek-chat"
    idempotent = $true
} -Headers @{}
Assert-True ($plannerResp.code -eq 200) ("register planner code=" + $plannerResp.code + " msg=" + $plannerResp.msg)
$plannerAgentId = [string]$plannerResp.data.id

$reviewerResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = "a1-policy-reviewer"
    role = "REVIEWER"
    description = "verify-a1-task-policy"
    accessType = "API_KEY_LLM"
    modelType = "deepseek:deepseek-chat"
    idempotent = $true
} -Headers @{}
Assert-True ($reviewerResp.code -eq 200) ("register reviewer code=" + $reviewerResp.code + " msg=" + $reviewerResp.msg)
$reviewerAgentId = [string]$reviewerResp.data.id

$executorResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = "a1-policy-executor"
    role = "EXECUTOR"
    description = "verify-a1-task-policy"
    accessType = "CLI_CLIENT"
    modelType = "gpt-4o"
    idempotent = $true
} -Headers @{}
Assert-True ($executorResp.code -eq 200) ("register executor code=" + $executorResp.code + " msg=" + $executorResp.msg)
$executorAgentId = [string]$executorResp.data.id

Write-Host ("agents: planner=" + $plannerAgentId + " reviewer=" + $reviewerAgentId + " executor=" + $executorAgentId)

Write-Host "STEP3: create task with full policy + skills + SLA"
$createResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = ("a1-policy-create-" + $ts)
    description = "A1 verify: task level agent policy passthrough."
    slaMinutes = 90
    agentPolicy = @{
        plannerAgentId = $plannerAgentId
        executorAgentIds = @($executorAgentId)
        reviewerAgentId = $reviewerAgentId
        fallbackPolicy = "RESTRICTED"
        difficulty = "HIGH"
    }
    requiredSkills = @("shell", "docker")
} -Headers $adminHeaders
Assert-True ($createResp.code -eq 200) ("create code=" + $createResp.code + " msg=" + $createResp.msg)
$created = $createResp.data
Assert-True ($created.status -eq "PENDING") ("unexpected status: " + $created.status)
Assert-True ($created.slaMinutes -eq 90) ("slaMinutes mismatch: " + $created.slaMinutes)
Assert-True ($created.agentPolicy.plannerAgentId -eq $plannerAgentId) "policy plannerAgentId mismatch"
Assert-True (@($created.agentPolicy.executorAgentIds).Count -eq 1 -and $created.agentPolicy.executorAgentIds[0] -eq $executorAgentId) "policy executorAgentIds mismatch"
Assert-True ($created.agentPolicy.reviewerAgentId -eq $reviewerAgentId) "policy reviewerAgentId mismatch"
Assert-True ($created.agentPolicy.fallbackPolicy -eq "RESTRICTED") "policy fallbackPolicy mismatch"
Assert-True ($created.agentPolicy.difficulty -eq "HIGH") "policy difficulty mismatch"
Assert-True (@($created.requiredSkills).Count -eq 2) "requiredSkills mismatch"
$taskId = [string]$created.id
Write-Host ("created taskId=" + $taskId)

Write-Host "STEP4: getById echo (DB persisted) assertion"
$detail = Get-Task -TaskId $taskId -Headers $adminHeaders
Assert-True ($detail.agentPolicy.plannerAgentId -eq $plannerAgentId) "echo plannerAgentId mismatch"
Assert-True ($detail.agentPolicy.fallbackPolicy -eq "RESTRICTED") "echo fallbackPolicy mismatch"
Assert-True (@($detail.requiredSkills).Count -eq 2) "echo requiredSkills mismatch"
Assert-True ($detail.slaMinutes -eq 90) "echo slaMinutes mismatch"
Write-Host "echo OK: agent_policy / required_skills / sla persisted"

Write-Host "STEP5: update task policy (full replace, keep planner, change fallback+difficulty+skills)"
$updateResp = Invoke-Json -Method "Put" -Url ($BaseUrl + "/api/tasks/updateById/" + $taskId) -Body @{
    title = ("a1-policy-updated-" + $ts)
    description = "A1 verify: policy updated."
    slaMinutes = 120
    agentPolicy = @{
        plannerAgentId = $plannerAgentId
        fallbackPolicy = "NONE"
        difficulty = "MEDIUM"
    }
    requiredSkills = @("python")
} -Headers $adminHeaders
Assert-True ($updateResp.code -eq 200) ("update code=" + $updateResp.code + " msg=" + $updateResp.msg)
$detail2 = Get-Task -TaskId $taskId -Headers $adminHeaders
Assert-True ($detail2.title -eq ("a1-policy-updated-" + $ts)) "title not updated"
Assert-True ($detail2.slaMinutes -eq 120) "slaMinutes not updated"
Assert-True ($detail2.agentPolicy.plannerAgentId -eq $plannerAgentId) "plannerAgentId lost after update"
Assert-True ($detail2.agentPolicy.fallbackPolicy -eq "NONE") "fallbackPolicy not updated"
Assert-True ($detail2.agentPolicy.difficulty -eq "MEDIUM") "difficulty not updated"
Assert-True (@($detail2.agentPolicy.PSObject.Properties.Name) -notcontains "executorAgentIds") "executorAgentIds should be removed after full replace"
Assert-True (@($detail2.requiredSkills).Count -eq 1 -and $detail2.requiredSkills[0] -eq "python") "requiredSkills not updated"
Write-Host "update OK: policy full-replace semantics verified"

Write-Host "STEP6: update with empty collections (explicit clear)"
$clearResp = Invoke-Json -Method "Put" -Url ($BaseUrl + "/api/tasks/updateById/" + $taskId) -Body @{
    title = ("a1-policy-cleared-" + $ts)
    agentPolicy = @{}
    requiredSkills = @()
} -Headers $adminHeaders
Assert-True ($clearResp.code -eq 200) ("clear code=" + $clearResp.code + " msg=" + $clearResp.msg)
$detail3 = Get-Task -TaskId $taskId -Headers $adminHeaders
# PS 5.1 空 PSCustomObject 的 Properties.Count 返回 $null，用 @() 包装保证数值比较
Assert-True (@($detail3.agentPolicy.PSObject.Properties).Count -eq 0) "agentPolicy not cleared"
Assert-True (@($detail3.requiredSkills).Count -eq 0) "requiredSkills not cleared"
Assert-True ($detail3.slaMinutes -eq 120) "slaMinutes should keep when omitted"
Write-Host "clear OK: empty map/list clears policy and skills"

Write-Host "STEP7: cleanup test task"
$deleteResp = Invoke-Json -Method "Delete" -Url ($BaseUrl + "/api/tasks/deleteById/" + $taskId) -Body @{
    confirmTitle = ("a1-policy-cleared-" + $ts)
} -Headers $adminHeaders
Assert-True ($deleteResp.code -eq 200) ("delete code=" + $deleteResp.code + " msg=" + $deleteResp.msg)
Write-Host ("cleanup OK: deleted taskId=" + $taskId)

Write-Host "OK: A1 task policy passthrough verify passed"
exit 0
