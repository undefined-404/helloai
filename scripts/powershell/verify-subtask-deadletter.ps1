# ============================================================
# helloai 子任务死信兜底验证脚本（V25）
# 用途：验证重分配熔断达阈值后子任务进入 DEAD_LETTER 死信池，
#       并可通过人工兜底接口 /api/sub-tasks/redispatchDeadLetterById/{id}
#       清零计数直接指派 ASSIGNED。
# 链路：创建子任务(ASSIGNED→CLI源Agent) → blockById → [SQL 预置 attempt_total=max]
#       → reassignById 触发熔断 → DEAD_LETTER → 人工兜底指派目标 Agent
#       → 断言 ASSIGNED 且 attempt_total 清零。
#
# 【2026-10-05 订正四处过期口径】
#   1) REST 路径：本仓子任务写接口统一带后缀——
#        getById/{id} / blockById/{id} / reassignById/{id} / redispatchDeadLetterById/{id}
#        （旧的 /api/sub-tasks/{id}、/block/{id}、/reassign/{id}、/dead-letter/redispatch/{id} 均 404）。
#   2) 目标 Agent 模型名：deepseek:deepseek-chat 已不可用（注册被拒），
#        改用实际启用模型 deepseek:deepseek-v4-pro（llm_provider_model enabled=1）。
#        注意角色内模型唯一约束（仅 API_KEY_LLM）：deepseek-v4-flash 已被内建
#        inner-deepseek-flash-excutor 占用，故这里选 v4-pro；同名复用兜底见 Get-AgentByName。
#   3) 熔断计数语义（G-015 B2.2）：共享预算列是 sub_task.attempt_total
#        （Phase 0 A3 起替代 reassign_attempt_count）；isReassignBlockedOrEscalate 在
#        「未达阈值且处于退避窗口 60/180/600/1800s」时只跳过本轮、不累加预算——
#        故**连续快速 reassign 不会累加**，靠连点无法打满预算。
#        本脚本改为直接 SQL 预置 attempt_total = max-reassign-attempts，
#        再调一次 reassignById 精确触发熔断（确定、可重复）。
#   4) 计数读取：详情响应无 reassignAttemptCount 字段，改按真实列 attempt_total 校验
#        （redispatchDeadLetter 会 resetAttemptTotal 清零，并清除 context 残留信号）。
#
# Ref:  doc/HelloAI 实现差距表.md（V24 重分配熔断 / V25 死信兜底）
# 前置：helloai-start 已在 6565 运行；helloai.dispatch.max-reassign-attempts=5（默认）；
#       docker 容器 helloai-postgres 可用（脚本用 docker exec psql 直连库预置计数）。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-subtask-deadletter.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$Role = "EXECUTOR",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [int]$MaxReassignAttempts = 5,
    [string]$PgContainer = "helloai-postgres",
    [string]$PgUser = "postgres",
    [string]$PgDb = "helloai"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

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

# 允许失败的调用：熔断触发前若中途 4xx/5xx（状态不符/无候选），脚本需据实际状态继续判定
function Invoke-JsonAllowFail([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers) {
    try {
        $resp = Invoke-Json -Method $Method -Url $Url -Body $Body -Headers $Headers
        return @{ Ok = $true; Resp = $resp }
    } catch {
        return @{ Ok = $false; Resp = $null; Error = $_.ToString() }
    }
}

# 直连本地 PG（docker exec psql）执行 SQL 并返回非空行（-t -A 元组输出，单列即标量）。
# 仅用于「预置 attempt_total」与「读回真实计数」，不参与业务写入。
function Invoke-Psql([string]$Sql) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $Sql, $script:Utf8NoBom)
    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmp
    $out = $sqlContent | & docker exec -i $PgContainer psql -v ON_ERROR_STOP=1 -X -t -A -U $PgUser -d $PgDb 2>&1
    $rc = $LASTEXITCODE
    Remove-Item $tmp -ErrorAction SilentlyContinue
    if ($rc -ne 0) {
        throw ("psql failed rc=" + $rc + " out=" + ($out -join ' '))
    }
    return @($out | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
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

# 按名字回捞已注册 Agent（幂等复用兜底：带 modelType 的复制注册在角色内模型唯一约束下会 409）
function Get-AgentByName([string]$Name, [string]$AdminToken) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/admin/agents/list?pageSize=200") -Body $null -Headers @{ "X-Admin-Token" = $AdminToken }
    if ($resp.code -ne 200 -or $resp.data -eq $null -or $resp.data.list -eq $null) {
        return $null
    }
    $hit = @($resp.data.list | Where-Object { $_.name -eq $Name })
    if ($hit.Count -gt 0) {
        return $hit[0]
    }
    return $null
}

function Get-SubTask([string]$SubTaskId, [string]$AdminToken) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/getById/" + $SubTaskId) -Body $null -Headers @{
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

Write-Host "STEP3: register manual target agent (idempotent fixed name; model = deepseek-v4-pro)"
$target = $null
try {
    $target = Register-Agent -Name "deadletter-target" -RoleValue $Role -AccessType "API_KEY_LLM" -ModelType "deepseek:deepseek-v4-pro"
} catch {
    # 角色内模型唯一约束：二次运行时同名 Agent 已占用该模型 → 409；回捞复用即可（幂等语义）
    Write-Host ("  target register rejected (" + $_.Exception.Message + ") -> reuse by name")
    $target = Get-AgentByName -Name "deadletter-target" -AdminToken $adminToken
}
Assert-True ($target -ne $null) "cannot create or reuse target agent 'deadletter-target'"
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
$blockResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/blockById/" + $subTaskId) -Body @{} -Headers @{
    "X-Admin-Token" = $adminToken
}
Assert-True ($blockResp.code -eq 200) ("block code=" + $blockResp.code)

# 熔断触发（G-015 B2.2）：退避窗口使「连续快速 reassign」不累加预算，
# 故直接 SQL 预置 attempt_total = max-reassign-attempts，再调一次 reassignById：
# 入口 isReassignBlockedOrEscalate 读 attempt_total >= max → 转 DEAD_LETTER（确定性触发）。
Write-Host ("STEP6: seed attempt_total=" + $MaxReassignAttempts + " via SQL, then single reassignById")
Invoke-Psql ("UPDATE sub_task SET attempt_total = " + $MaxReassignAttempts + " WHERE id = " + $subTaskId + ";") | Out-Null
# 注意 @(...)：Invoke-Psql 返回单值时会退化为标量字符串，直接 [0] 会取到「首字符」，
# 必须先用 @() 强制成数组再取下标（否则 [int]'5' = 53 的字符码，断言恒假）。
$seeded = @(Invoke-Psql ("SELECT COALESCE(attempt_total, 0) FROM sub_task WHERE id = " + $subTaskId + ";"))[0]
Assert-True ([int]$seeded -eq $MaxReassignAttempts) ("seed attempt_total failed, actual=" + $seeded)
Write-Host ("  seeded attempt_total=" + $seeded)

$r = Invoke-JsonAllowFail -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/reassignById/" + $subTaskId) -Body @{
    agentId = [long]$sourceAgentId
} -Headers @{ "X-Admin-Token" = $adminToken }
Write-Host ("  reassign httpOk=" + $r.Ok)

Write-Host "STEP7: assert DEAD_LETTER"
$deadDetail = Get-SubTask -SubTaskId $subTaskId -AdminToken $adminToken
Assert-True ($deadDetail.status -eq "DEAD_LETTER") ("expected DEAD_LETTER, actual=" + $deadDetail.status)
Write-Host "DEAD_LETTER confirmed"

Write-Host "STEP8: manual dead-letter redispatch to target agent"
$redispatchResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/redispatchDeadLetterById/" + $subTaskId) -Body @{
    agentId = [long]$targetAgentId
} -Headers @{ "X-Admin-Token" = $adminToken }
Assert-True ($redispatchResp.code -eq 200) ("redispatch code=" + $redispatchResp.code + " msg=" + $redispatchResp.msg)

Write-Host "STEP9: assert ASSIGNED + attempt_total reset to 0"
$finalDetail = Get-SubTask -SubTaskId $subTaskId -AdminToken $adminToken
Assert-True ($finalDetail.status -eq "ASSIGNED") ("expected ASSIGNED, actual=" + $finalDetail.status)
Assert-True ($finalDetail.assignedAgent.ToString() -eq $targetAgentId) `
    ("expected assignedAgent=" + $targetAgentId + ", actual=" + $finalDetail.assignedAgent)
# 真实计数列 attempt_total（Phase 0 A3；详情响应无 reassignAttemptCount 字段）
$finalAttempt = @(Invoke-Psql ("SELECT COALESCE(attempt_total, 0) FROM sub_task WHERE id = " + $subTaskId + ";"))[0]
Assert-True ([int]$finalAttempt -eq 0) ("expected attempt_total=0 after redispatch, actual=" + $finalAttempt)
Write-Host "attempt_total=0 confirmed"
# G-015 B3.1：上一轮死信残留信号应被清除
if ($finalDetail.context -ne $null) {
    Assert-True ($finalDetail.context.PSObject.Properties.Name -notcontains "dead_letter_reason") `
        "context.dead_letter_reason should be cleared after redispatch"
    Write-Host "context.dead_letter_reason cleared confirmed"
}

Write-Host "OK: dead letter circuit breaker + manual redispatch passed"
Write-Host ("taskId=" + $taskId)
Write-Host ("subTaskId=" + $subTaskId)
Write-Host ("sourceAgentId=" + $sourceAgentId)
Write-Host ("targetAgentId=" + $targetAgentId)
exit 0
