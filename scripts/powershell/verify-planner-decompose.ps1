# ============================================================
# helloai Planner 平台内自动拆解验证脚本（V26）
# 用途：验证"需求 → 自动拆解 → 用户确认/拒绝 → 进入既有分发链"闭环：
#       POST /api/tasks/{id}/plan          触发 LLM 拆解（Task PENDING → PLANNING，
#                                          草案落库 PENDING_PLAN_REVIEW）
#       GET  /api/tasks/{id}/plan          查看草案列表
#       POST /api/tasks/{id}/plan/confirm  草案转正 PENDING（Task → IN_PROGRESS，
#                                          按 autoAssignOnCreate 触发分发链）
#       POST /api/tasks/{id}/plan/reject   草案翻 CANCELLED（Task 回退 PENDING）
# Ref:  doc/HelloAI_实现差距表.md（V26 Planner 平台内拆解）
# 前置：helloai-start 已在 6565 运行；helloai.providers 已配置可用 LLM（deepseek）。
#       脚本自动注册 role=PLANNER + accessType=API_KEY_LLM 的 Agent 供拆解使用。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-planner-decompose.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [string]$PlannerModelType = "deepseek:deepseek-chat",
    [int]$PlanTimeoutSec = 180
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

function New-PlannerTask([string]$Title, [string]$Description, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
        title = $Title
        description = $Description
    } -Headers $Headers
    Assert-True ($resp.code -eq 200) ("create task code=" + $resp.code + " msg=" + $resp.msg)
    Assert-True ($resp.data.status -eq "PENDING") ("unexpected task status: " + $resp.data.status)
    return [string]$resp.data.id
}

function Get-Task([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/" + $TaskId) -Body $null -Headers $Headers
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

Write-Host "STEP2: register platform planner agent (API_KEY_LLM, idempotent fixed name)"
$plannerResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = "planner-decompose"
    role = "PLANNER"
    description = "verify-planner-decompose"
    accessType = "API_KEY_LLM"
    modelType = $PlannerModelType
    idempotent = $true
} -Headers @{}
Assert-True ($plannerResp.code -eq 200) ("register planner code=" + $plannerResp.code + " msg=" + $plannerResp.msg)
$plannerAgentId = [string]$plannerResp.data.id
Write-Host ("plannerAgentId=" + $plannerAgentId)

# ------------------------------------------------------------
# 主路径：拆解 → 断言草案 → 确认 → 断言转正 + Task IN_PROGRESS
# ------------------------------------------------------------

Write-Host "STEP3: create task for confirm path"
$taskId = New-PlannerTask -Title ("planner-e2e-confirm-" + $ts) `
    -Description "Build a daily report module: DB schema, statistics REST API, frontend chart page, unit tests and deployment doc." `
    -Headers $adminHeaders
Write-Host ("taskId=" + $taskId)

Write-Host ("STEP4: trigger decompose (LLM call, timeout=" + $PlanTimeoutSec + "s)")
$planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body @{} `
    -Headers $adminHeaders -TimeoutSec $PlanTimeoutSec
Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
$drafts = @($planResp.data)
Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
Assert-True ($drafts.Count -le 10) ("expected <=10 drafts, actual=" + $drafts.Count)
foreach ($d in $drafts) {
    Assert-True ($d.status -eq "PENDING_PLAN_REVIEW") ("draft " + $d.id + " unexpected status: " + $d.status)
}
Write-Host ("draftCount=" + $drafts.Count + " all PENDING_PLAN_REVIEW")

Write-Host "STEP5: assert task PLANNING + GET drafts consistent"
$taskDetail = Get-Task -TaskId $taskId -Headers $adminHeaders
Assert-True ($taskDetail.status -eq "PLANNING") ("expected PLANNING, actual=" + $taskDetail.status)
$listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body $null -Headers $adminHeaders
Assert-True ($listResp.code -eq 200) ("list drafts code=" + $listResp.code)
Assert-True (@($listResp.data).Count -eq $drafts.Count) `
    ("draft list mismatch: create=" + $drafts.Count + " list=" + @($listResp.data).Count)

Write-Host "STEP6: confirm plan"
$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan/confirm") -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)
$confirmed = @($confirmResp.data)
Assert-True ($confirmed.Count -eq $drafts.Count) ("confirmed count mismatch: " + $confirmed.Count)
foreach ($s in $confirmed) {
    # autoAssignOnCreate 开启时可能已被分发链推进到 ASSIGNED，两者均为合法转正结果
    Assert-True ($s.status -eq "PENDING" -or $s.status -eq "ASSIGNED") `
        ("subTask " + $s.id + " unexpected status after confirm: " + $s.status)
}
Write-Host ("confirmed " + $confirmed.Count + " subTasks (PENDING/ASSIGNED)")

Write-Host "STEP7: assert task IN_PROGRESS + no drafts left"
$taskDetail = Get-Task -TaskId $taskId -Headers $adminHeaders
Assert-True ($taskDetail.status -eq "IN_PROGRESS") ("expected IN_PROGRESS, actual=" + $taskDetail.status)
$leftResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body $null -Headers $adminHeaders
Assert-True (@($leftResp.data).Count -eq 0) ("expected 0 drafts left, actual=" + @($leftResp.data).Count)

# ------------------------------------------------------------
# 回归路径：拆解 → 拒绝 → 断言 CANCELLED + Task 回退 PENDING
# ------------------------------------------------------------

Write-Host "STEP8: create task for reject path"
$taskId2 = New-PlannerTask -Title ("planner-e2e-reject-" + $ts) `
    -Description "Prototype an internal FAQ chatbot: knowledge ingestion, retrieval API and a simple web UI." `
    -Headers $adminHeaders
Write-Host ("taskId2=" + $taskId2)

Write-Host "STEP9: trigger decompose again"
$planResp2 = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId2 + "/plan") -Body @{} `
    -Headers $adminHeaders -TimeoutSec $PlanTimeoutSec
Assert-True ($planResp2.code -eq 200) ("plan2 code=" + $planResp2.code + " msg=" + $planResp2.msg)
$drafts2 = @($planResp2.data)
Assert-True ($drafts2.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts2.Count)

Write-Host "STEP10: reject plan"
$rejectResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId2 + "/plan/reject") -Body @{} -Headers $adminHeaders
Assert-True ($rejectResp.code -eq 200) ("reject code=" + $rejectResp.code + " msg=" + $rejectResp.msg)
Assert-True ([int]$rejectResp.data.cancelledCount -eq $drafts2.Count) `
    ("cancelledCount mismatch: expected=" + $drafts2.Count + " actual=" + $rejectResp.data.cancelledCount)

Write-Host "STEP11: assert task back to PENDING + drafts cancelled"
$taskDetail2 = Get-Task -TaskId $taskId2 -Headers $adminHeaders
Assert-True ($taskDetail2.status -eq "PENDING") ("expected PENDING, actual=" + $taskDetail2.status)
$leftResp2 = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/" + $taskId2 + "/plan") -Body $null -Headers $adminHeaders
Assert-True (@($leftResp2.data).Count -eq 0) ("expected 0 drafts left, actual=" + @($leftResp2.data).Count)

Write-Host "STEP12: duplicate decompose on IN_PROGRESS task must fail"
$dupFailed = $false
try {
    Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body @{} `
        -Headers $adminHeaders -TimeoutSec $PlanTimeoutSec | Out-Null
} catch {
    $dupFailed = $true
}
if (-not $dupFailed) {
    # 平台统一 R 响应也可能以 code!=200 返回而非抛 HTTP 异常
    $dupResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body @{} `
        -Headers $adminHeaders -TimeoutSec $PlanTimeoutSec
    Assert-True ($dupResp.code -ne 200) "duplicate decompose unexpectedly succeeded"
}
Write-Host "duplicate decompose rejected as expected"

Write-Host "OK: planner decompose / confirm / reject e2e passed"
Write-Host ("plannerAgentId=" + $plannerAgentId)
Write-Host ("confirmTaskId=" + $taskId)
Write-Host ("rejectTaskId=" + $taskId2)
exit 0
