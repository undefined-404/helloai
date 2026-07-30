# ============================================================
# helloai 子任务死信兜底验证脚本（V25）
# 用途：验证重分配熔断达阈值后子任务进入 DEAD_LETTER 死信池，
#       并可通过人工兜底接口 /api/sub-tasks/dead-letter/redispatch/{id}
#       清零计数直接指派 ASSIGNED。
# 链路：创建子任务(ASSIGNED→CLI源Agent) → block → 连续 reassign 触发熔断计数
#       （每次调用入口 checkReassignCircuitBreaker 均累加 reassign_attempt_count，
#         无论本次改派本身成败）→ 达阈值转 DEAD_LETTER → 人工兜底指派目标 Agent
#       → 断言 ASSIGNED 且计数清零。
# Ref:  doc/HelloAI_实现差距表.md（V24 重分配熔断 / V25 死信兜底）
# 前置：helloai-start 已在 6565 运行；helloai.dispatch.max-reassign-attempts=5（默认）。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-subtask-deadletter.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$Role = "EXECUTOR",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [int]$MaxReassignAttempts = 5
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers) {
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec 30
}

# 允许失败的调用：熔断计数阶段中间态改派可能因状态不符/无候选而 4xx/5xx，
# 但入口处的熔断计数已累加，这正是本脚本要利用的行为
function Invoke-JsonAllowFail([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers) {
    try {
        $resp = Invoke-Json -Method $Method -Url $Url -Body $Body -Headers $Headers
        return @{ Ok = $true; Resp = $resp }
    } catch {
        return @{ Ok = $false; Resp = $null; Error = $_.ToString() }
    }
}

function Register-Agent([string]$Name, [string]$RoleValue, [string]$AccessType, [string]$ModelType) {
    $body = @{
        name = $Name
        role = $RoleValue
        description = "verify-subtask-deadletter"
        accessType = $AccessType
        idempotent = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($ModelType)) {
        $body.modelType = $ModelType
    }
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body $body -Headers @{}
    Assert-True ($resp.code -eq 200) ("register agent code=" + $resp.code + " msg=" + $resp.msg)
    Assert-True ($resp.data -ne $null) "register data is null"
    return $resp.data
}

function Get-SubTask([string]$SubTaskId, [string]$AdminToken) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/" + $SubTaskId) -Body $null -Headers @{
        "X-Admin-Token" = $AdminToken
    }
    Assert-True ($resp.code -eq 200) ("get subTask code=" + $resp.code + " msg=" + $resp.msg)
    Assert-True ($resp.data -ne $null) "subTask data is null"
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
$adminToken = $loginResp.data.token

$ts = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")

Write-Host "STEP2: register source CLI agent (never heartbeats -> stale, idempotent fixed name)"
$source = Register-Agent -Name "deadletter-source" -RoleValue $Role -AccessType "CLI_CLIENT" -ModelType ""
$sourceAgentId = [string]$source.id
Write-Host ("sourceAgentId=" + $sourceAgentId)

Write-Host "STEP3: register manual target agent (idempotent fixed name)"
$target = Register-Agent -Name "deadletter-target" -RoleValue $Role -AccessType "API_KEY_LLM" -ModelType "deepseek:deepseek-chat"
$targetAgentId = [string]$target.id
Write-Host ("targetAgentId=" + $targetAgentId)

Write-Host "STEP4: create task + subTask assigned to source agent"
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "deadletter-task-" + $ts
    description = "verify dead letter circuit breaker"
} -Headers @{ "X-Admin-Token" = $adminToken }
Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code)
$taskId = [string]$taskResp.data.id

$subTaskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks") -Body @{
    taskId = [long]$taskId
    title = "deadletter-subtask-" + $ts
    description = "verify dead letter path"
    deliverable = "status reaches DEAD_LETTER then manual ASSIGNED"
    acceptance = "sub_task.status=DEAD_LETTER -> ASSIGNED"
    priority = "HIGH"
    assignedAgent = [long]$sourceAgentId
} -Headers @{ "X-Admin-Token" = $adminToken }
Assert-True ($subTaskResp.code -eq 200) ("create subTask code=" + $subTaskResp.code + " msg=" + $subTaskResp.msg)
$subTaskId = [string]$subTaskResp.data.id
Assert-True ($subTaskResp.data.status -eq "ASSIGNED") ("unexpected initial status: " + $subTaskResp.data.status)
Write-Host ("subTaskId=" + $subTaskId)

Write-Host "STEP5: block subTask (enter reassign chain)"
$blockResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/block/" + $subTaskId) -Body @{} -Headers @{
    "X-Admin-Token" = $adminToken
}
Assert-True ($blockResp.code -eq 200) ("block code=" + $blockResp.code)

# 每次 /reassign 入口先走 checkReassignCircuitBreaker：未达阈值 → 计数 +1（即使
# 后续改派因源 Agent 心跳陈旧/状态不符而失败，计数已持久化）；达到阈值 → 转 DEAD_LETTER。
$loops = $MaxReassignAttempts + 1
Write-Host ("STEP6: trigger reassign x" + $loops + " to exceed circuit breaker threshold")
for ($i = 1; $i -le $loops; $i++) {
    $r = Invoke-JsonAllowFail -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/reassign/" + $subTaskId) -Body @{
        agentId = [long]$sourceAgentId
    } -Headers @{ "X-Admin-Token" = $adminToken }
    $detail = Get-SubTask -SubTaskId $subTaskId -AdminToken $adminToken
    Write-Host ("  attempt#" + $i + " httpOk=" + $r.Ok + " status=" + $detail.status)
    if ($detail.status -eq "DEAD_LETTER") {
        break
    }
    Start-Sleep -Seconds 1
}

Write-Host "STEP7: assert DEAD_LETTER"
$deadDetail = Get-SubTask -SubTaskId $subTaskId -AdminToken $adminToken
Assert-True ($deadDetail.status -eq "DEAD_LETTER") ("expected DEAD_LETTER, actual=" + $deadDetail.status)
Write-Host "DEAD_LETTER confirmed"

Write-Host "STEP8: manual dead-letter redispatch to target agent"
$redispatchResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/dead-letter/redispatch/" + $subTaskId) -Body @{
    agentId = [long]$targetAgentId
} -Headers @{ "X-Admin-Token" = $adminToken }
Assert-True ($redispatchResp.code -eq 200) ("redispatch code=" + $redispatchResp.code + " msg=" + $redispatchResp.msg)

Write-Host "STEP9: assert ASSIGNED + count reset"
$finalDetail = Get-SubTask -SubTaskId $subTaskId -AdminToken $adminToken
Assert-True ($finalDetail.status -eq "ASSIGNED") ("expected ASSIGNED, actual=" + $finalDetail.status)
Assert-True ($finalDetail.assignedAgent.ToString() -eq $targetAgentId) `
    ("expected assignedAgent=" + $targetAgentId + ", actual=" + $finalDetail.assignedAgent)
if ($finalDetail.PSObject.Properties.Name -contains "reassignAttemptCount") {
    Assert-True ([int]$finalDetail.reassignAttemptCount -eq 0) `
        ("expected reassignAttemptCount=0, actual=" + $finalDetail.reassignAttemptCount)
    Write-Host "reassignAttemptCount=0 confirmed"
} else {
    Write-Host "NOTE: detail response has no reassignAttemptCount field, verify in DB:"
    Write-Host ("  SELECT id, status, assigned_agent_id, reassign_attempt_count FROM sub_task WHERE id = " + $subTaskId + ";")
}

Write-Host "OK: dead letter circuit breaker + manual redispatch passed"
Write-Host ("taskId=" + $taskId)
Write-Host ("subTaskId=" + $subTaskId)
Write-Host ("sourceAgentId=" + $sourceAgentId)
Write-Host ("targetAgentId=" + $targetAgentId)
exit 0
