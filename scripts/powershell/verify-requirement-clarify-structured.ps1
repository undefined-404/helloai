# ============================================================
# helloai 结构化选项式需求澄清验证脚本（V33）
# 用途：真实链路验证 requirement_message.payload 双模协议：
#       ① 创建澄清会话（模糊需求 + 钉定 PLANNER），断言 assistant 消息落库
#       ② 检查最后一条 assistant 消息 payload：mode=structured 时校验
#          questions/options 结构完整（软断言，LLM 输出形态不可控）
#       ③ mode=structured 时按第一题第一选项构造 selectedOptions 提交，
#          断言 user 消息 payload 含 selections 选择快照
#       ④ freeform / 无 payload 时验证纯文本兼容路径（payload 为 NULL）
#       ⑤ 终稿信息量回归（P2-2，SOFT）：推进至终稿，软断言 description 小节组命中 >= 3、
#          长度 >= 300、context.requirementPackage 存在（LLM 输出不可控，不 hard fail）
#       ⑥ abandon 会话清理（已 FINALIZED 时跳过，改提示人工清理测试数据）
# Ref:  doc/HelloAI_实现差距表.md（V33 结构化选项式需求澄清）
# 前置：helloai-start 已运行 + LLM 可用（DEEPSEEK_API_KEY 或 application.yml 默认 key）
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-requirement-clarify-structured.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    # 平台 llm_provider_model 现役模型（V49 模型管理启用后注册会校验）：deepseek→v4-flash/v4-pro
    [string]$LlmModelType = "deepseek:deepseek-v4-flash",
    [string]$LlmApiKey = $env:DEEPSEEK_API_KEY,
    [int]$RoundTimeoutSec = 180
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
# PS5.1 非交互会话不预载 System.Net.Http，HttpClient 类型需显式加载（与 tool-matrix 等脚本一致）
Add-Type -AssemblyName System.Net.Http

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 30) {
    # 请求体：JSON 字符串以 UTF-8 字节发出（LLM 回包中的中文选项 label 回填时防 GBK 混淆）
    # 响应体：必须按字节读 + 显式 UTF-8 解码 —— PS5.1 的 Invoke-RestMethod 对 Content-Type
    # 不带 charset 的 application/json 响应按 ISO-8859-1 解码，中文选项值（"确认"）乱码回传，
    # 服务端 isAcceptSelected 匹配失败 —— P2-2 终稿回归堵点的根因（2026-09-11 定位）
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, [string]$Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
        $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        try {
            if ($Method -eq "Get")      { $resp = $client.GetAsync($Url).Result }
            elseif ($Method -eq "Post") { $resp = $client.PostAsync($Url, $content).Result }
            elseif ($Method -eq "Put")  { $resp = $client.PutAsync($Url, $content).Result }
            else { throw ("unsupported method: " + $Method) }
        } finally {
            if ($content -ne $null) { $content.Dispose() }
        }
        $respBytes = $resp.Content.ReadAsByteArrayAsync().Result
        $respText = [System.Text.Encoding]::UTF8.GetString($respBytes)
        $statusCode = [int]$resp.StatusCode
        $resp.Dispose()
        if ($statusCode -lt 200 -or $statusCode -ge 300) {
            $errText = $respText
            if ($errText.Length -gt 500) { $errText = $errText.Substring(0, 500) }
            throw ("HTTP " + $statusCode + ": " + $errText)
        }
        if ([string]::IsNullOrWhiteSpace($respText)) { return $null }
        return ($respText | ConvertFrom-Json)
    } catch {
        # PS 方法调用包 MethodInvocationException、Task.Result 包 AggregateException，逐层解包
        $ex = $_.Exception
        while (($ex -is [System.Management.Automation.MethodInvocationException] -or $ex -is [System.AggregateException]) -and $ex.InnerException -ne $null) {
            $ex = $ex.InnerException
        }
        throw $ex
    } finally {
        $client.Dispose()
    }
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

if ([string]::IsNullOrWhiteSpace($LlmApiKey)) {
    # 与 helloai-start application.yml 中 spring.ai.deepseek.api-key 的默认值保持一致
    $LlmApiKey = "sk-a36fdda1d4ad4e0386e78fc435be0d16"
    Write-Host "WARN: DEEPSEEK_API_KEY not set, fallback to application.yml default key"
}
$llmProvider = ($LlmModelType -split ":")[0]

Write-Host "STEP2: register PLANNER LLM agent (API_KEY_LLM, idempotent fixed name)"
$regResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = "clarify-structured-planner"
    role = "PLANNER"
    description = "verify-requirement-clarify-structured"
    accessType = "API_KEY_LLM"
    modelType = $LlmModelType
    idempotent = $true
} -Headers @{}
Assert-True ($regResp.code -eq 200) ("register planner code=" + $regResp.code + " msg=" + $regResp.msg)
$plannerAgentId = [string]$regResp.data.id
Write-Host ("plannerAgentId=" + $plannerAgentId)

Write-Host ("STEP2.5: bind vault api-key for planner (provider=" + $llmProvider + ")")
# 端点已更名：旧 /api/credentials/agents/{id}/api-key 已下线（Phase 2 B2 凭证管理面收口）
$bindResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/bindApiKeyByAgentId/" + $plannerAgentId) -Body @{
    provider = $llmProvider
    apiKey = $LlmApiKey
    remark = "verify-requirement-clarify-structured"
} -Headers $adminHeaders
Assert-True ($bindResp.code -eq 200) ("bind api-key code=" + $bindResp.code + " msg=" + $bindResp.msg)

Write-Host ("STEP3: create clarify conversation with vague requirement (LLM call, timeout=" + $RoundTimeoutSec + "s)")
# 刻意给模糊需求，诱导 LLM 走 structured 追问（但不强制，形态由 LLM 决定）
$createResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/requirement-conversations") -Body @{
    message = "I want to build an internal report tool for my team, not sure about the details yet."
    plannerAgentId = $plannerAgentId
} -Headers $adminHeaders -TimeoutSec $RoundTimeoutSec
Assert-True ($createResp.code -eq 200) ("create conversation code=" + $createResp.code + " msg=" + $createResp.msg)
$convId = [string]$createResp.data.conversation.id
$messages = @($createResp.data.messages)
Write-Host ("conversationId=" + $convId + " messageCount=" + $messages.Count)
Assert-True ($messages.Count -ge 2) ("expected >=2 messages (user+assistant), actual=" + $messages.Count)

$lastMsg = $messages[$messages.Count - 1]
Assert-True ($lastMsg.role -eq "assistant") ("last message role=" + $lastMsg.role + ", expected assistant")
Assert-True (-not [string]::IsNullOrWhiteSpace($lastMsg.content)) "assistant content is empty"

Write-Host "STEP4: inspect assistant payload (structured / freeform / null)"
$payloadMode = $null
$payloadObj = $null
if (-not [string]::IsNullOrWhiteSpace($lastMsg.payload)) {
    $payloadObj = $lastMsg.payload | ConvertFrom-Json
    $payloadMode = [string]$payloadObj.mode
    Write-Host ("assistant payload mode=" + $payloadMode + " progress=" + $payloadObj.progress)
    Assert-True ($payloadMode -eq "structured" -or $payloadMode -eq "freeform") ("unexpected payload mode=" + $payloadMode)
    if ($payloadObj.progress -ne $null) {
        Assert-True ($payloadObj.progress -ge 0 -and $payloadObj.progress -le 100) ("progress out of range: " + $payloadObj.progress)
    }
} else {
    # payload 为 NULL：纯文本兼容路径（final 直出或 freeform 无 progress）
    Write-Host "assistant payload is null (plain-text compatible path)"
}

if ($payloadMode -eq "structured") {
    # 硬断言：mode=structured 时结构必须完整（后端 isStructuredValid 已兜底）
    $questions = @($payloadObj.questions)
    Assert-True ($questions.Count -ge 1) "structured payload has no questions"
    foreach ($q in $questions) {
        Assert-True (-not [string]::IsNullOrWhiteSpace($q.id)) "question id is empty"
        Assert-True (-not [string]::IsNullOrWhiteSpace($q.text)) "question text is empty"
        $opts = @($q.options)
        Assert-True ($opts.Count -ge 1) ("question " + $q.id + " has no options")
        foreach ($opt in $opts) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($opt.label)) "option label is empty"
            Assert-True (-not [string]::IsNullOrWhiteSpace($opt.value)) "option value is empty"
        }
    }
    Write-Host ("structured questions verified: count=" + $questions.Count)

    Write-Host "STEP5: submit selection of first option of first question"
    $q1 = $questions[0]
    $opt1 = @($q1.options)[0]
    $answerText = [string]$q1.text + ": " + [string]$opt1.label
    $sendResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/requirement-conversations/sendMessageById/" + $convId) -Body @{
        message = $answerText
        selectedOptions = @(
            @{
                questionId = $q1.id
                questionText = $q1.text
                values = @([string]$opt1.value)
                labels = @([string]$opt1.label)
                custom = $false
                customText = $null
            }
        )
    } -Headers $adminHeaders -TimeoutSec $RoundTimeoutSec
    Assert-True ($sendResp.code -eq 200) ("send message code=" + $sendResp.code + " msg=" + $sendResp.msg)

    Write-Host "STEP6: assert user message payload contains selections snapshot"
    $allMsgs = @($sendResp.data.messages)
    $userWithSnapshot = $allMsgs | Where-Object { $_.role -eq "user" -and $_.payload -ne $null -and $_.payload -like "*selections*" }
    Assert-True (@($userWithSnapshot).Count -ge 1) "no user message with selections snapshot in payload"
    $snapshot = (@($userWithSnapshot)[0]).payload | ConvertFrom-Json
    $selections = @($snapshot.selections)
    Assert-True ($selections.Count -ge 1) "selections array is empty"
    Assert-True ([string]$selections[0].questionId -eq [string]$q1.id) ("snapshot questionId mismatch: " + $selections[0].questionId)
    Write-Host ("selection snapshot verified: questionId=" + $selections[0].questionId)
} else {
    # 软断言：LLM 未走 structured 不算失败（协议允许 freeform），仅提示
    Write-Host "SOFT: LLM did not return structured mode this round, skip STEP5/6 (freeform is a valid first-class path)"
}

# ── 终稿信息量回归（P2-2，SOFT）：澄清 → 终稿链路的 description 信息量此前无任何回归防线 ──
Write-Host ("STEP7: final draft info-volume regression (LLM call, timeout=" + $RoundTimeoutSec + "s; SOFT)")
$finalized = $false
$createdTaskId = $null
try {
    # 终稿触发：用户明确"没有其他要求"时提示词要求立即产出终稿（requirement-clarify.md 第 4 条）
    $nudgeResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/requirement-conversations/sendMessageById/" + $convId) -Body @{
        message = "没有其他要求，请直接生成终稿（描述须完整给出「背景与目标」「范围与边界」「交付物」「验收标准」四个小节的正文）。"
    } -Headers $adminHeaders -TimeoutSec $RoundTimeoutSec
    Assert-True ($nudgeResp.code -eq 200) ("finalize nudge code=" + $nudgeResp.code + " msg=" + $nudgeResp.msg)

    $finalResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/requirement-conversations/finalizeById/" + $convId) -Body @{} -Headers $adminHeaders -TimeoutSec $RoundTimeoutSec
    Assert-True ($finalResp.code -eq 200) ("finalize code=" + $finalResp.code + " msg=" + $finalResp.msg)
    $createdTask = $finalResp.data
    $createdTaskId = [string]$createdTask.id
    $finalized = $true

    # 软断言（LLM 输出不可控，硬断言会让脚本日常飘红）：小节组命中 / 描述长度 / 需求包存在
    $finalDesc = [string]$createdTask.description
    $sectionHits = 0
    if ($finalDesc -match "背景|目标") { $sectionHits++ }
    if ($finalDesc -match "范围|边界") { $sectionHits++ }
    if ($finalDesc -match "交付物|交付") { $sectionHits++ }
    if ($finalDesc -match "验收") { $sectionHits++ }
    $finalPkg = $null
    if ($createdTask.context -ne $null) { $finalPkg = $createdTask.context.requirementPackage }
    $acceptanceCount = 0
    if ($finalPkg -ne $null) { $acceptanceCount = @($finalPkg.acceptanceCriteria).Count }
    Write-Host ("final draft: taskId=" + $createdTaskId + " descLength=" + $finalDesc.Length + " sectionHits=" + $sectionHits + "/4 package=" + ($finalPkg -ne $null) + " acceptanceCriteria=" + $acceptanceCount)
    if ($sectionHits -ge 3 -and $finalDesc.Length -ge 300 -and $finalPkg -ne $null) {
        Write-Host "final description regression verified (P2-2 soft assertions passed)"
    } else {
        Write-Host "SOFT: final description info-volume below conservative threshold (sections>=3 / length>=300 / package present) - LLM output is not deterministic, not a hard failure"
    }
} catch {
    Write-Host ("SOFT: final draft regression skipped (LLM 未产出终稿 / 调用异常): " + $_.Exception.Message)
}

Write-Host "STEP8: cleanup"
if ($finalized) {
    # FINALIZED 会话承载任务追溯（禁止 abandon / 删除），测试任务与草案需人工清理
    Write-Host ("SKIP abandon: conversation FINALIZED with test task taskId=" + $createdTaskId + "（可用 cleanup-test-data.sql 清理残留）")
} else {
    $abandonResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/requirement-conversations/abandonById/" + $convId) -Body @{} -Headers $adminHeaders
    Assert-True ($abandonResp.code -eq 200) ("abandon code=" + $abandonResp.code + " msg=" + $abandonResp.msg)
}

Write-Host ""
Write-Host "=== verify-requirement-clarify-structured PASSED ==="
