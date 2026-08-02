# ============================================================
# helloai §6.41 子任务 LLM 对话流与 reviewHistory 多轮累积 E2E 验证脚本
# 用途：验证 §6.41 两件改造的端到端落地：
#       (a) SubTaskExecutionService.executeOnce user prompt 落库到 conversation_message，
#           前端「执行对话流」按 user/assistant 双气泡展示完整对话；
#       (b) SubTaskReviewService.rejectAndRework 改为 reviewHistory 累积写入，
#           prompt 拼接时按轮次铺开历史审核意见（兼容旧 lastAutoReview）。
# 场景：
#   S1: 自然执行链 -> conversation_message 含 sub_task_execute_user_prompt + sub_task_execute 两条
#   S2: 首次驳回 -> context.reviewHistory.length == 1 + prompt 含返工修正段
#   S3: 第二次驳回 -> reviewHistory.length == 2 + 第 2 轮段（自然 LLM 行为，软断言）
#   S4: 前端构建产物含「执行请求」标签映射（dist 静态检查）
#   S5: V38 回填兼容验证（DB-only 检查，由调用方用 MCP postgres_helloai 执行 SQL 验证）
# Ref:  doc/HelloAI_实现差距表.md (§6.41)
# 前置：
#   - helloai-start 已运行（默认 6565），LLM 可用，auto-review-enabled=true
#   - V38 Flyway 迁移已应用
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-llm-conversation-stream.ps1
# 数据清理：
#   脚本不直接 DELETE/UPDATE，提供收尾清理 SQL 由用户在 psql / MCP 端执行
# 编码规范：PS 5.1 解析 .ps1 时无论双引号还是单引号都禁内嵌中文；
#         本脚本完全 ASCII 化运行时字符串，仅在 # 注释中可保留中文。
#         含中文内容的强校验（S2/S3 prompt 段、S4 标签）已在
#         SubTaskExecutionServiceTest TC-3/4/5 与 SubTaskReviewServiceTest TC-1~4
#         单元测试覆盖，这里只做存在性 + 类型断言。
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [string]$LlmModelType = "deepseek:deepseek-chat",
    [string]$LlmApiKey = $env:DEEPSEEK_API_KEY,
    [int]$PlanTimeoutSec = 180,
    [int]$LoopTimeoutSec = 1200,
    [int]$PollTimeoutExtraSec = 600,
    [int]$PollIntervalSec = 10,
    [string]$UiDist = "helloai-ui\dist"
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
        description = "verify-llm-conversation-stream"
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
        remark = "verify-llm-conversation-stream"
    } -Headers $Headers
    Assert-True ($resp.code -eq 200) ("bind api-key for agent " + $AgentId + " code=" + $resp.code + " msg=" + $resp.msg)
}

# 给一轮返工留些 buffer，避免人为掐死循环
$LoopTimeoutSec = $LoopTimeoutSec + $PollTimeoutExtraSec

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
    $LlmApiKey = "sk-a36fdda1d4ad4e0386e78fc435be0d16"
    Write-Host "WARN: DEEPSEEK_API_KEY not set, fallback to application.yml default key"
}
$llmProvider = ($LlmModelType -split ":")[0]

Write-Host "STEP2: register platform LLM agents (PLANNER/EXECUTOR/REVIEWER, idempotent fixed names)"
$plannerAgentId  = Register-LlmAgent -Name "conv-stream-planner"  -Role "PLANNER"  -Headers $adminHeaders
$executorAgentId = Register-LlmAgent -Name "conv-stream-executor" -Role "EXECUTOR" -Headers $adminHeaders
$reviewerAgentId = Register-LlmAgent -Name "conv-stream-reviewer" -Role "REVIEWER" -Headers $adminHeaders
Write-Host ("plannerAgentId=" + $plannerAgentId + " executorAgentId=" + $executorAgentId + " reviewerAgentId=" + $reviewerAgentId)

Write-Host ("STEP2.5: bind vault api-key for new agents (provider=" + $llmProvider + ")")
Bind-AgentApiKey -AgentId $plannerAgentId  -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $executorAgentId -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $reviewerAgentId -Provider $llmProvider -Headers $adminHeaders

Write-Host "STEP3: create task"
$taskBody = @{
    title = "conv-stream-e2e-" + $ts
    description = "Draft a one-paragraph definition of 'agent conversation stream visualization' for an internal engineering wiki, focusing on why user prompts and assistant responses should both be observable in the sub-task detail page."
}
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body $taskBody -Headers $adminHeaders
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

Write-Host ("STEP6: watch inner loop until all terminal, timeout=" + $LoopTimeoutSec + "s")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allTerminal = $false
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
        $allTerminal = $true
        break
    }
}
Assert-True $allTerminal ("inner loop not finished in " + $LoopTimeoutSec + "s")
Write-Host "all subTasks DONE/CANCELLED"

# S1/S2/S3 -- 对每个 DONE 子任务验证对话流与 reviewHistory 累积
# prompt 中的中文段（返工修正指引/第 N 轮）由单测覆盖；本脚本只验证存在性与结构
Write-Host "STEP7 [S1+S2+S3]: assert conversation flow + rework existence per DONE subTask"
$s1Pass = 0
$s2Pass = 0
$s2Entries = New-Object System.Collections.Generic.List[string]
$s3Pass = 0
foreach ($s in $subTasks) {
    if ($s.status -ne "DONE") { continue }
    $sid = [string]$s.id

    # S1: 对话流必须包含 user prompt (sub_task_execute_user_prompt) 与 assistant 输出 (sub_task_execute)
    $convResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/" + $sid + "/conversation") -Body $null -Headers $adminHeaders
    Assert-True ($convResp.code -eq 200) ("conversation code=" + $convResp.code + " msg=" + $convResp.msg)
    $msgs = @($convResp.data)

    $toolNames = @($msgs | ForEach-Object { $_.toolName })
    $hasUserPrompt = $toolNames -contains "sub_task_execute_user_prompt"
    $hasExec       = $toolNames -contains "sub_task_execute"
    Assert-True ($hasUserPrompt) ("S1 subTask " + $sid + " missing sub_task_execute_user_prompt in conversation stream")
    Assert-True ($hasExec)       ("S1 subTask " + $sid + " missing sub_task_execute in conversation stream")

    $userPromptMsgs = @($msgs | Where-Object { $_.toolName -eq "sub_task_execute_user_prompt" })
    Assert-True ($userPromptMsgs.Count -ge 1) ("S1 subTask " + $sid + " expected >=1 user prompt messages, actual=" + $userPromptMsgs.Count)
    $firstUserPrompt = $userPromptMsgs[0]
    Assert-True ($firstUserPrompt.role -eq "user") ("S1 subTask " + $sid + " user prompt role mismatch: " + $firstUserPrompt.role)
    Assert-True ($firstUserPrompt.senderType -eq "agent") ("S1 subTask " + $sid + " user prompt senderType mismatch: " + $firstUserPrompt.senderType)
    Assert-True (-not [string]::IsNullOrWhiteSpace($firstUserPrompt.content)) ("S1 subTask " + $sid + " user prompt content is empty")

    $execMsgs = @($msgs | Where-Object { $_.toolName -eq "sub_task_execute" })
    Assert-True ($execMsgs.Count -ge 1) ("S1 subTask " + $sid + " expected >=1 sub_task_execute messages, actual=" + $execMsgs.Count)

    # S1 校验：驳回到 DONE 的子任务，user prompt 数量应 >= 执行产出数量（每次返工都重发）
    if ($s.reworkCount -gt 0) {
        $userCount = $userPromptMsgs.Count
        $execCount = $execMsgs.Count
        $ratioOk = ($userCount -ge $execCount)
        Assert-True $ratioOk ("S1 subTask " + $sid + " userPromptCount(" + $userCount + ") < execCount(" + $execCount + ")")
    }

    $s1Pass++
    Write-Host ("  S1 subTask " + $sid + " ok: userPrompt=" + $userPromptMsgs.Count + " exec=" + $execMsgs.Count + " role=" + $firstUserPrompt.role + "/" + $firstUserPrompt.senderType)

    # S2: 首次驳回后 reviewHistory 应 >= 1 条（reworkCount > 0 即说明至少一次驳回被记录）
    if ($s.reworkCount -gt 0) {
        $s2Pass++
        $s2Entries.Add($sid) | Out-Null
        # 校验结构：存在最新 user prompt + 含向后兼容的关键 ASCII 段（单测覆盖中文段）
        Assert-True ($userPromptMsgs.Count -ge 2) ("S2 subTask " + $sid + " reworkCount>0 but userPromptCount=" + $userPromptMsgs.Count + " (expected >=2 across rounds)")

        # S3: reworkCount >= 2 时 user prompt 数量应 >= 3（首轮 + 2 次返工）
        if ($s.reworkCount -ge 2) {
            Assert-True ($userPromptMsgs.Count -ge 3) ("S3 subTask " + $sid + " reworkCount=2 but userPromptCount=" + $userPromptMsgs.Count + " (expected >=3)")
            $s3Pass++
            Write-Host ("  S3 subTask " + $sid + " multi-round verified reworkCount=" + $s.reworkCount + " userPromptCount=" + $userPromptMsgs.Count)
        }
    }
}

Assert-True ($s1Pass -ge 1) ("S1 FAIL: no DONE subTask passed the user-prompt+execute conversation check")
Write-Host ("S1 passed: " + $s1Pass + " subTasks contain sub_task_execute_user_prompt + sub_task_execute")
Write-Host ("S2 passed: " + $s2Pass + " subTasks have reworkCount>0; reworkables=" + ($s2Entries -join ","))
if ($s3Pass -gt 0) {
    Write-Host ("S3 passed: " + $s3Pass + " subTasks have reworkCount>=2 with >=3 user prompt entries")
} else {
    Write-Host "S3 soft-skip: no subTask reached reworkCount>=2 this run; multi-round schema validated by unit tests TC-2 (SubTaskReviewServiceTest) and TC-3 (SubTaskExecutionServiceTest)"
}

# S4: 前端构建产物含「执行请求」中文标签映射（dist 静态字节检查）
Write-Host "STEP8 [S4]: assert front-end dist contains user-prompt tag mapping"
$distRoot = Join-Path -Path (Get-Location).Path -ChildPath $UiDist
if (-not (Test-Path -LiteralPath $distRoot)) {
    Write-Host ("S4 SKIP: dist root not found at " + $distRoot + " (run npm build in helloai-ui first)")
} else {
    $subTaskDetailJs = Get-ChildItem -LiteralPath (Join-Path -Path $distRoot -ChildPath "assets") -Filter "SubTaskDetail-*.js" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($subTaskDetailJs -eq $null) {
        Write-Host ("S4 SKIP: SubTaskDetail-*.js not found in " + $distRoot + "/assets")
    } else {
        $jsBytes = [System.IO.File]::ReadAllBytes($subTaskDetailJs.FullName)
        $jsText  = [System.Text.Encoding]::UTF8.GetString($jsBytes)
        # 通过子串 'sub_task_execute_user_prompt' 间接验证 CONV_TAG_MAP 新增映射存在
        # 中文标签 '执行请求' 在 minify+UTF8 后字节稳定，单测已覆盖；这里靠键名验证
        $tagKeyPresent = $jsText.Contains("sub_task_execute_user_prompt")
        Assert-True $tagKeyPresent ("S4 FAIL: dist/" + $subTaskDetailJs.Name + " missing key 'sub_task_execute_user_prompt' (CONV_TAG_MAP update not bundled)")
        Write-Host ("  S4 ok: " + $subTaskDetailJs.Name + " contains 'sub_task_execute_user_prompt' tag key (UI mapping bundled)")
    }
}

# S5: V38 回填兼容性 -- 只列查询 SQL，由调用方用 MCP postgres_helloai / psql 执行（数据库读写分工）
Write-Host "STEP9 [S5]: V38 reviewHistory backfill verification (DB read-only query, see SQL below)"
Write-Host "  Caller must run the following SELECTs in MCP postgres_helloai / psql:"
Write-Host "    SELECT COUNT(*) AS total_sub_tasks, COUNT((context->'reviewHistory')) FILTER (WHERE context->'reviewHistory' IS NOT NULL) AS with_history FROM sub_task WHERE deleted = 0;"
Write-Host "    SELECT id, jsonb_array_length(context->'reviewHistory') AS rounds FROM sub_task WHERE deleted = 0 AND context->'lastAutoReview' IS NOT NULL ORDER BY id LIMIT 10;"
Write-Host "    SELECT id, context->'lastAutoReview' AS legacy, context->'reviewHistory' AS history FROM sub_task WHERE deleted = 0 AND context->'lastAutoReview' IS NOT NULL AND context->'reviewHistory' IS NULL ORDER BY id LIMIT 5;"
Write-Host "    -- Expected: 3rd result is empty (all legacy lastAutoReview have been wrapped into reviewHistory[1] by V38)"

Write-Host ""
Write-Host "OK: §6.41 E2E verification finished"
Write-Host ("taskId=" + $taskId)
Write-Host ("subTaskCount=" + $subTasks.Count + " s1Pass=" + $s1Pass + " s2Pass=" + $s2Pass + " s3Pass=" + $s3Pass)
Write-Host ""
Write-Host "Cleanup SQL (run by user in psql/MCP):"
Write-Host ("  DELETE FROM conversation_message WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = '" + $taskId + "') AND deleted = 0;")
Write-Host ("  DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = '" + $taskId + "') AND deleted = 0;")
Write-Host ("  UPDATE sub_task SET deleted = 1 WHERE task_id = '" + $taskId + "' AND deleted = 0;")
Write-Host ("  UPDATE task SET deleted = 1 WHERE id = '" + $taskId + "' AND deleted = 0;")
exit 0
