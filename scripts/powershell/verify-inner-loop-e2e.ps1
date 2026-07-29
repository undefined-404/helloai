# ============================================================
# helloai 内循环最小流程 E2E 验证脚本（V27）
# 用途：真实链路验证"拆解(带依赖) → ready 分发 → 平台 LLM 执行 → LLM 自动核验
#       → 下游解锁 → Task 自动收尾"完整闭环（不使用 mock，全程走 DB + LLM）：
#       POST /api/tasks/{id}/plan          LLM 拆解（草案带 dependsOn）
#       POST /api/tasks/{id}/plan/confirm  转正 + 环校验 + 序号→id 映射落库
#       （后台）依赖 ready 放行 → 自动执行 → submit(REVIEW) → 自动核验 → DONE
#       （后台）complete 后解锁下游 → 全部 DONE 后 Task 自动 DONE
# Ref:  doc/HelloAI_实现差距表.md（V27 内循环最小流程）
# 前置：helloai-start 已在 6565 运行；helloai.providers 已配置可用 LLM；
#       helloai.dispatch.auto-review-enabled=true（默认开启）。
#       helloai.execution.require-vault=true 时凭证必须绑定在 Agent 上，
#       脚本会自动为新注册 Agent 绑定托管凭证（-LlmApiKey 或 DEEPSEEK_API_KEY）。
#       建议配置 helloai.dispatch.force-access-type=API_KEY_LLM 将内循环
#       收敛到平台 LLM（避免历史外部 Agent 抢占分发）。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-inner-loop-e2e.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [string]$LlmModelType = "deepseek:deepseek-chat",
    # require-vault=true 时绑定给 Agent 的托管 API Key；默认取环境变量，
    # 再回退 application.yml 中 spring.ai.deepseek.api-key 的同款默认值
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

function Get-Task([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("get task code=" + $resp.code + " msg=" + $resp.msg)
    return $resp.data
}

function Register-LlmAgent([string]$Name, [string]$Role, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
        name = $Name
        role = $Role
        description = "verify-inner-loop-e2e"
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
        remark = "verify-inner-loop-e2e"
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

# require-vault=true 时脚本必须能拿到可绑定的 API Key，否则拆解/执行必然 500
if ([string]::IsNullOrWhiteSpace($LlmApiKey)) {
    # 与 helloai-start application.yml 中 spring.ai.deepseek.api-key 的默认值保持一致
    $LlmApiKey = "sk-a36fdda1d4ad4e0386e78fc435be0d16"
    Write-Host "WARN: DEEPSEEK_API_KEY not set, fallback to application.yml default key"
}
$llmProvider = ($LlmModelType -split ":")[0]

# STEP2.0 (sleep stale inner-loop-* agents) removed:
# fixed-name idempotent registration reuses the same agents across runs,
# and AgentSelector now filters API_KEY_LLM candidates without active vault credentials

Write-Host "STEP2: register platform LLM agents (PLANNER/EXECUTOR/REVIEWER, API_KEY_LLM, idempotent fixed names)"
$plannerAgentId  = Register-LlmAgent -Name "inner-loop-planner"  -Role "PLANNER"  -Headers $adminHeaders
$executorAgentId = Register-LlmAgent -Name "inner-loop-executor" -Role "EXECUTOR" -Headers $adminHeaders
$reviewerAgentId = Register-LlmAgent -Name "inner-loop-reviewer" -Role "REVIEWER" -Headers $adminHeaders
Write-Host ("plannerAgentId=" + $plannerAgentId + " executorAgentId=" + $executorAgentId + " reviewerAgentId=" + $reviewerAgentId)

Write-Host ("STEP2.5: bind vault api-key for new agents (provider=" + $llmProvider + ")")
Bind-AgentApiKey -AgentId $plannerAgentId  -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $executorAgentId -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $reviewerAgentId -Provider $llmProvider -Headers $adminHeaders
Write-Host "vault credentials bound for planner/executor/reviewer"

Write-Host "STEP3: create task"
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "inner-loop-e2e-" + $ts
    description = "Write a short 3-part product intro for an internal note-taking app: 1) one-line positioning statement; 2) three key feature bullets based on the positioning; 3) a 50-word summary paragraph combining 1 and 2. Steps depend on each other in order."
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
$withDeps = 0
foreach ($d in $drafts) {
    $depCount = @($d.dependsOn).Count
    if ($depCount -gt 0) { $withDeps++ }
    Write-Host ("  draft id=" + $d.id + " title=" + $d.title + " dependsOn=[" + (@($d.dependsOn) -join ",") + "]")
}
Write-Host ("draftCount=" + $drafts.Count + " draftsWithDeps=" + $withDeps)
# 依赖由 LLM 产出，不强制非空；但描述刻意构造了顺序依赖，通常应 >=1
if ($withDeps -eq 0) {
    Write-Host "WARN: no draft carries dependsOn (LLM output had no dependencies); ready-order assertions will be trivially satisfied"
}

Write-Host "STEP5: confirm plan (cycle validation + seq->id mapping happens server-side)"
$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan/confirm") -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)

Write-Host "STEP6: assert ready-gating right after confirm"
$subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
$idToStatus = @{}
foreach ($s in $subTasks) { $idToStatus[[string]$s.id] = $s.status }
foreach ($s in $subTasks) {
    $deps = @($s.dependsOn)
    if ($deps.Count -gt 0) {
        # 有依赖的节点在依赖 DONE 前必须停在 PENDING（ready 守卫不放行）
        Assert-True ($s.status -eq "PENDING") `
            ("subTask " + $s.id + " has deps [" + ($deps -join ",") + "] but status=" + $s.status + " (expected PENDING)")
    }
}
Write-Host ("ready-gating ok: " + $subTasks.Count + " subTasks, gated=" + $withDeps)

Write-Host ("STEP7: watch inner loop (execute -> auto review -> unlock downstream), timeout=" + $LoopTimeoutSec + "s")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allDone = $false
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
    $statusMap = @{}
    foreach ($s in $subTasks) { $statusMap[[string]$s.id] = $s.status }
    $summary = ($subTasks | ForEach-Object { "" + $_.id + ":" + $_.status }) -join " "
    Write-Host ("  [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] " + $summary)

    # 不变量：任何已被放行（非 PENDING/CANCELLED）且带依赖的节点，其依赖必须已全部 DONE
    foreach ($s in $subTasks) {
        $deps = @($s.dependsOn)
        if ($deps.Count -eq 0) { continue }
        if ($s.status -in @("PENDING", "CANCELLED")) { continue }
        foreach ($dep in $deps) {
            Assert-True ($statusMap[[string]$dep] -eq "DONE") `
                ("ready-order violated: subTask " + $s.id + " status=" + $s.status + " but dep " + $dep + " is " + $statusMap[[string]$dep])
        }
    }

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

Write-Host "STEP8: assert auto-review timeline evidence"
$reviewedCount = 0
foreach ($s in $subTasks) {
    if ($s.status -ne "DONE") { continue }
    $tlResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/" + $s.id + "/timeline") -Body $null -Headers $adminHeaders
    $events = @($tlResp.data | ForEach-Object { $_.eventType })
    if ($events -contains "sub_task_auto_review_passed") { $reviewedCount++ }
}
Assert-True ($reviewedCount -ge 1) "no subTask carries sub_task_auto_review_passed timeline event (auto review gate not exercised)"
Write-Host ("auto-review passed on " + $reviewedCount + " subTasks")

Write-Host "STEP9: assert task auto-closed to DONE"
$taskDone = $false
$closeDeadline = [DateTime]::UtcNow.AddSeconds(60)
while ([DateTime]::UtcNow -lt $closeDeadline) {
    $taskDetail = Get-Task -TaskId $taskId -Headers $adminHeaders
    if ($taskDetail.status -eq "DONE") {
        $taskDone = $true
        break
    }
    Start-Sleep -Seconds 5
}
Assert-True $taskDone ("task not auto-closed, status=" + $taskDetail.status)

Write-Host "OK: inner loop e2e passed (decompose with deps -> ready dispatch -> execute -> auto review -> unlock -> task auto close)"
Write-Host ("taskId=" + $taskId)
Write-Host ("subTaskCount=" + $subTasks.Count + " withDeps=" + $withDeps + " autoReviewed=" + $reviewedCount)
exit 0
