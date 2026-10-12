# ============================================================
# helloai 内部 LLM 工具能力 E2E 验证（REF-8.1 / REF-8.4 收敛判据③）
#
# 用途：验证内部 LLM（API_KEY_LLM）**真的会自己调 internal_* 工具**完成任务，
#       而不是「工具面已注入但模型只聊天」。这是 REF-8.1 验收「可调通」的
#       唯一硬证据（此前只有单测直连方法，未走模型循环）。
#
# 流程（全程真实链路，无 mock）：
#   POST /api/auth/login                                管理员登录
#   POST /api/agents/register                           注册 PLANNER/EXECUTOR/REVIEWER（每次运行唯一名，确保拿得到 apiKey）
#   POST /api/credentials/bindApiKeyByAgentId/{id}      绑定托管 LLM 凭证
#   POST /api/tasks                                     建任务（描述强制要求「先读附件再作答」）
#   POST /api/tasks/planById/{id}                       触发 LLM 拆解（异步，轮询草案）
#   POST /api/tasks/confirmPlanByTaskId/{id}            转正 + 自动分发
#   POST /api/artifacts/upload                          子任务一被指派，立刻以**该执行者身份**上传探针附件
#   GET  /api/sub-tasks?taskId=                         轮询直到全部 DONE
#   GET  /api/sub-tasks/listTimelineBySubTaskId/{id}    取 tool_call_started/completed 事件
#   GET  /api/sub-tasks/listConversationBySubTaskId/{id} 取执行产出全文
#
# 断言（HARD = 失败即 exit 1）：
#   H1 至少一次 tool_call_started 且 tool 以 internal_ 开头      → L1 工具面注入 + 模型真的调了
#   H2 至少一次 internal_get_sub_task_detail                     → 模型走了「先看任务详情」的发现步
#   H3 至少一次 internal_read_attachment                         → 模型真的读了附件正文
#   H4 至少一次 tool_call_completed(tool=internal_read_attachment) 的 output 含 marker
#                                                                 → 附件内容确实流进了模型上下文
#   H5 marker 出现在执行产出全文（conversation）                 → 模型把读到的内容用进了交付物
#   S1 internal_register_output_attachment（SOFT，仅告警）       → 交付登记工具（模型按需，不强制）
#
# 前置：helloai-start 已在 6565 运行；helloai.providers 已配置可用 LLM。
#       建议 helloai.dispatch.force-access-type=API_KEY_LLM，把内循环收敛到平台 LLM。
#
# 凭据获取（管理员口令 / LLM API Key，优先级从高到低）：
#   1) -AdminPassword / -LlmApiKey 参数传非空值
#   2) 环境变量 HELLOAI_ADMIN_PASSWORD / DEEPSEEK_API_KEY 非空
#   3) 两者都没有 → 交互式提示输入（不回显）；非交互宿主（CI/无人值守）直接报错
#
# 已知残量竞态（诚实边界）：附件上传要求子任务**已被指派**，而指派与执行几乎同时发生。
#       脚本以 1s 粒度轮询 assignedAgent 并立即上传，通常能赶在模型第一次工具调用之前；
#       带依赖的子任务执行得晚，必定看得到附件。若 H3/H4 失败，先看打印的工具调用清单
#       判断是「没读到附件」还是「读到了没用」。
#
# 用法（项目根，PowerShell）：
#   powershell -File .\scripts\powershell\verify-internal-llm-tools-e2e.ps1 -AdminPassword "..." -LlmApiKey "..."
#   # 或使用环境变量：
#   $env:HELLOAI_ADMIN_PASSWORD="..."; $env:DEEPSEEK_API_KEY="..."
#   powershell -File .\scripts\powershell\verify-internal-llm-tools-e2e.ps1
#
# Ref: doc/HelloAI 实现差距表.md G-021（REF-8.1）· D-2026-10-12-5
#      doc/plan/HelloAI 借鉴落地实施计划.md §10.2 / §10.4（收敛判据③）
#      LOG-20261012-006
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "",
    [string]$LlmModelType = "deepseek:deepseek-v4-flash",
    [string]$LlmApiKey = "",
    [string]$AttachmentFileName = "input-brief.txt",
    [int]$PlanTimeoutSec = 360,
    [int]$LoopTimeoutSec = 900,
    [int]$PollIntervalSec = 10,
    # 指派后上传附件的轮询粒度（赶在执行前落地；1s 已接近 PowerShell 轮询下限）
    [int]$AssignPollMs = 1000,
    # 等所有子任务都被指派的窗口（超时只告警，不失败——后续轮询仍会补传）
    [int]$AssignWaitSec = 180
)
# 凭据获取三级优先：1) 显式参数非空 2) 环境变量非空 3) 交互输入（仅交互宿主；空输入最多重试 3 次）
function Resolve-Credential {
    param(
        [string]$ParamValue,
        [string]$EnvName,
        [string]$PromptText,
        [string]$MissingMessage
    )
    if (-not [string]::IsNullOrWhiteSpace($ParamValue)) { return $ParamValue }
    $fromEnv = [Environment]::GetEnvironmentVariable($EnvName)
    if (-not [string]::IsNullOrWhiteSpace($fromEnv)) { return $fromEnv }
    if (-not [Environment]::UserInteractive) { throw $MissingMessage }
    for ($i = 1; $i -le 3; $i++) {
        $secure = Read-Host -AsSecureString -Prompt $PromptText
        $plain = (New-Object System.Net.NetworkCredential("", $secure)).Password
        if (-not [string]::IsNullOrWhiteSpace($plain)) { return $plain }
        Write-Host ("  empty input - attempt " + $i + " of 3")
    }
    throw $MissingMessage
}

$AdminPassword = Resolve-Credential -ParamValue $AdminPassword -EnvName "HELLOAI_ADMIN_PASSWORD" -PromptText "Admin password (hidden input)" -MissingMessage "missing admin password: set env HELLOAI_ADMIN_PASSWORD or pass -AdminPassword"
$LlmApiKey = Resolve-Credential -ParamValue $LlmApiKey -EnvName "DEEPSEEK_API_KEY" -PromptText "LLM api key (hidden input)" -MissingMessage "missing LLM api key: set env DEEPSEEK_API_KEY or pass -LlmApiKey"

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) { throw ("ASSERT_FAIL: " + $Msg) }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 30) {
    $json = $null
    if ($Body -ne $null) { $json = ($Body | ConvertTo-Json -Depth 10) }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Get-SubTasks([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks?taskId=" + $TaskId) -Body $null -Headers $Headers
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
    while ($waited -lt $MaxSecs) {
        $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/findPlanByTaskId/" + $TaskId) -Body $null -Headers $Headers
        $drafts = @($listResp.data)
        if ($drafts.Count -ge 1) { return $drafts }
        Start-Sleep -Seconds 3
        $waited = $waited + 3
    }
    return @($listResp.data)
}

# 注册即返回 apiKey（唯一名 ⇒ 不会是「已存在、不回显 key」的分支）
function Register-LlmAgent([string]$Name, [string]$Role, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
        name = $Name
        role = $Role
        description = "verify-internal-llm-tools-e2e"
        accessType = "API_KEY_LLM"
        modelType = $LlmModelType
    } -Headers @{}
    Assert-True ($resp.code -eq 200) ("register " + $Role + " code=" + $resp.code + " msg=" + $resp.msg)
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$resp.data.apiKey)) ("register " + $Role + " returned empty apiKey")
    return @{ Id = [string]$resp.data.id; ApiKey = [string]$resp.data.apiKey }
}

function Bind-AgentApiKey([string]$AgentId, [string]$Provider, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/bindApiKeyByAgentId/" + $AgentId) -Body @{
        provider = $Provider
        apiKey = $LlmApiKey
        remark = "verify-internal-llm-tools-e2e"
    } -Headers $Headers
    Assert-True ($resp.code -eq 200) ("bind api-key for agent " + $AgentId + " code=" + $resp.code + " msg=" + $resp.msg)
}

# multipart 上传探针附件；以**该子任务的执行者身份**上传（否则 403 归属他人 / 409 无归属）
function Upload-ProbeAttachment([string]$SubTaskId, [string]$AgentApiKey, [string]$FileName, [string]$Content) {
    $boundary = [System.Guid]::NewGuid().ToString("N")
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Content)
    $ms = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.StreamWriter($ms, [System.Text.Encoding]::UTF8)
    $writer.Write("--" + $boundary + "`r`n")
    $writer.Write("Content-Disposition: form-data; name=`"file`"; filename=`"" + $FileName + "`"`r`n")
    $writer.Write("Content-Type: text/plain; charset=utf-8`r`n`r`n")
    $writer.Flush()
    $ms.Write($bytes, 0, $bytes.Length)
    $writer.Write("`r`n--" + $boundary + "`r`n")
    $writer.Write("Content-Disposition: form-data; name=`"subTaskId`"`r`n`r`n" + $SubTaskId + "`r`n")
    $writer.Write("--" + $boundary + "--`r`n")
    $writer.Flush()
    $payload = $ms.ToArray()
    $writer.Close(); $ms.Close()

    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add("Authorization", "Bearer " + $AgentApiKey)
        $content = New-Object System.Net.Http.ByteArrayContent($payload)
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("multipart/form-data; boundary=" + $boundary)
        $httpResp = $client.PostAsync(($BaseUrl + "/api/artifacts/upload"), $content).Result
        $bodyText = $httpResp.Content.ReadAsStringAsync().Result
        return @{ Code = [int]$httpResp.StatusCode; Body = $bodyText }
    } finally {
        $client.Dispose()
    }
}

function Get-Timeline([string]$SubTaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/listTimelineBySubTaskId/" + $SubTaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("timeline code=" + $resp.code + " msg=" + $resp.msg)
    return @($resp.data)
}

function Get-Conversation([string]$SubTaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/listConversationBySubTaskId/" + $SubTaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("conversation code=" + $resp.code + " msg=" + $resp.msg)
    return @($resp.data)
}

# ------------------------------------------------------------
# STEP 0: server reachability
# ------------------------------------------------------------
Write-Host "STEP0: server reachability ($BaseUrl)"
try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(5)
    $pingResp = $ping.GetAsync($BaseUrl + "/api/health").Result
    Write-Host ("  HTTP " + [int]$pingResp.StatusCode + " - server is up")
    $ping.Dispose()
} catch {
    throw ("server NOT reachable at " + $BaseUrl + " - start HelloAIApplication first: " + $_.Exception.Message)
}

# ------------------------------------------------------------
# STEP 1: admin login
# ------------------------------------------------------------
Write-Host "STEP1: admin login"
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type = "admin"
    username = $AdminUsername
    credential = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
$adminHeaders = @{ "X-Admin-Token" = [string]$loginResp.data.token }

$ts = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")
$llmProvider = ($LlmModelType -split ":")[0]

# 探针 marker：每次运行随机，避免与历史数据撞车
$markerRnd = -join ((48..57) + (65..90) | Get-Random -Count 8 | ForEach-Object { [char]$_ })
$Marker = "HLMRK-" + $markerRnd
Write-Host ("  probe marker = " + $Marker)

$briefLines = @(
    "INPUT BRIEF - internal LLM tool capability probe",
    "=================================================",
    ("marker_token: " + $Marker),
    "product: internal note-taking app 'NoteDeck'",
    "positioning: a single-window workspace where engineers capture decisions next to code",
    "features: (1) code-block aware capture, (2) decision timeline with reversible edits, (3) offline-first sync",
    "rule: any executor MUST quote marker_token verbatim in its final answer"
)
$BriefContent = ($briefLines -join "`r`n") + "`r`n"

# ------------------------------------------------------------
# STEP 2: register platform LLM agents (unique names -> apiKey always returned)
# ------------------------------------------------------------
Write-Host "STEP2: register platform LLM agents (PLANNER/EXECUTOR/REVIEWER, API_KEY_LLM)"
$agents = @{}
$agents["PLANNER"]  = Register-LlmAgent -Name ("illt-planner-"  + $ts) -Role "PLANNER"  -Headers $adminHeaders
$agents["EXECUTOR"] = Register-LlmAgent -Name ("illt-executor-" + $ts) -Role "EXECUTOR" -Headers $adminHeaders
$agents["REVIEWER"] = Register-LlmAgent -Name ("illt-reviewer-" + $ts) -Role "REVIEWER" -Headers $adminHeaders
$keyByAgentId = @{}
foreach ($k in $agents.Keys) { $keyByAgentId[[string]$agents[$k].Id] = [string]$agents[$k].ApiKey }
Write-Host ("  planner=" + $agents["PLANNER"].Id + " executor=" + $agents["EXECUTOR"].Id + " reviewer=" + $agents["REVIEWER"].Id)

Write-Host ("STEP2.5: bind vault api-key (provider=" + $llmProvider + ")")
foreach ($k in $agents.Keys) { Bind-AgentApiKey -AgentId $agents[$k].Id -Provider $llmProvider -Headers $adminHeaders }
Write-Host "  vault credentials bound"

# ------------------------------------------------------------
# STEP 3: create task whose description FORCES reading the attachment
# ------------------------------------------------------------
Write-Host "STEP3: create task (description mandates reading the attached input file)"
$taskDesc = @"
Two-part internal note, strictly in order:
1) A required input file named '$AttachmentFileName' is attached to your sub-task. Before writing anything you MUST use the platform tools to read that attachment's full text. Do not guess, summarise from memory, or invent its content.
2) Then write a 3-sentence summary of the brief, and on its own line quote the marker_token value found in that file verbatim.
"@
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "internal-llm-tools-e2e-" + $ts
    description = $taskDesc
} -Headers $adminHeaders
Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code + " msg=" + $taskResp.msg)
$taskId = [string]$taskResp.data.id
Write-Host ("  taskId=" + $taskId)

# ------------------------------------------------------------
# STEP 4: decompose (async) + confirm
# ------------------------------------------------------------
Write-Host ("STEP4: trigger decompose (async; timeout=" + $PlanTimeoutSec + "s)")
$planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/planById/" + $taskId) -Body @{} -Headers $adminHeaders -TimeoutSec 30
Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
$drafts = @(Wait-Drafts -TaskId $taskId -MaxSecs $PlanTimeoutSec -Headers $adminHeaders)
Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
foreach ($d in $drafts) {
    Write-Host ("  draft id=" + $d.id + " title=" + $d.title + " dependsOn=[" + (@($d.dependsOn) -join ",") + "]")
}

Write-Host "STEP5: confirm plan"
$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/confirmPlanByTaskId/" + $taskId) -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)

# ------------------------------------------------------------
# STEP 6: upload the probe attachment the moment each sub-task gets an assignee
#   (upload requires assignedAgentId != null AND caller == assignee; see ArtifactUploadServiceImpl)
# ------------------------------------------------------------
Write-Host "STEP6: upload probe attachment as soon as each sub-task is assigned"
$uploaded = @{}
$assignDeadline = [DateTime]::UtcNow.AddSeconds($AssignWaitSec)
$warnedForeign = @{}
while ($true) {
    $subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
    $todo = @()
    foreach ($s in $subTasks) {
        $sid = [string]$s.id
        if ($uploaded.ContainsKey($sid)) { continue }
        $assignee = [string]$s.assignedAgent
        if ([string]::IsNullOrWhiteSpace($assignee)) { continue }
        if (-not $keyByAgentId.ContainsKey($assignee)) {
            if (-not $warnedForeign.ContainsKey($sid)) {
                $warnedForeign[$sid] = $true
                Write-Host ("  WARN: subTask " + $sid + " assigned to agent " + $assignee + " which is NOT one of this run's API_KEY_LLM agents (set helloai.dispatch.force-access-type=API_KEY_LLM)")
            }
            continue
        }
        $todo += @{ Id = $sid; Assignee = $assignee }
    }
    foreach ($item in $todo) {
        $up = Upload-ProbeAttachment -SubTaskId $item.Id -AgentApiKey $keyByAgentId[$item.Assignee] -FileName $AttachmentFileName -Content $BriefContent
        if ($up.Code -ge 200 -and $up.Code -lt 300) {
            $uploaded[$item.Id] = $up.Body
            Write-Host ("  uploaded probe attachment for subTask " + $item.Id + " (assignee=" + $item.Assignee + ")")
        } else {
            Write-Host ("  WARN: upload failed for subTask " + $item.Id + " HTTP " + $up.Code + " body=" + $up.Body)
        }
    }
    $remaining = @($subTasks | Where-Object { -not $uploaded.ContainsKey([string]$_.id) })
    if ($remaining.Count -eq 0) { break }
    if ([DateTime]::UtcNow -gt $assignDeadline) {
        Write-Host ("  WARN: not every sub-task got an upload within " + $AssignWaitSec + "s; continuing (STEP7 keeps retrying)")
        break
    }
    Start-Sleep -Milliseconds $AssignPollMs
}
Write-Host ("  uploaded=" + $uploaded.Count + " subTasks")

# ------------------------------------------------------------
# STEP 7: watch inner loop until all DONE
# ------------------------------------------------------------
Write-Host ("STEP7: watch loop until all subTasks DONE (timeout=" + $LoopTimeoutSec + "s)")
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allDone = $false
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
    $summary = ($subTasks | ForEach-Object { "" + $_.id + ":" + $_.status }) -join " "
    Write-Host ("  [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] " + $summary)

    # 边跑边补传（带依赖的子任务此时才被指派）
    foreach ($s in $subTasks) {
        $sid = [string]$s.id
        if ($uploaded.ContainsKey($sid)) { continue }
        $assignee = [string]$s.assignedAgent
        if ([string]::IsNullOrWhiteSpace($assignee)) { continue }
        if (-not $keyByAgentId.ContainsKey($assignee)) { continue }
        $up = Upload-ProbeAttachment -SubTaskId $sid -AgentApiKey $keyByAgentId[$assignee] -FileName $AttachmentFileName -Content $BriefContent
        if ($up.Code -ge 200 -and $up.Code -lt 300) {
            $uploaded[$sid] = $up.Body
            Write-Host ("  [late] uploaded probe attachment for subTask " + $sid + " (assignee=" + $assignee + ")")
        }
    }

    $dead = @($subTasks | Where-Object { $_.status -in @("DEAD_LETTER", "BLOCKED") })
    Assert-True ($dead.Count -eq 0) ("subTasks entered DEAD_LETTER/BLOCKED: " + (($dead | ForEach-Object { $_.id }) -join ","))

    $notDone = @($subTasks | Where-Object { $_.status -notin @("DONE", "CANCELLED") })
    if ($notDone.Count -eq 0) { $allDone = $true; break }
}
Assert-True $allDone ("loop not finished in " + $LoopTimeoutSec + "s")
$subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
Write-Host ("  all subTasks DONE/CANCELLED (" + $subTasks.Count + " total)")

# ------------------------------------------------------------
# STEP 8: collect tool-call evidence from every sub-task timeline
# ------------------------------------------------------------
Write-Host "STEP8: collect tool_call_* evidence from timelines"
$startedTools = @{}
$completedByTool = @{}
foreach ($s in $subTasks) {
    $sid = [string]$s.id
    $events = Get-Timeline -SubTaskId $sid -Headers $adminHeaders
    foreach ($ev in $events) {
        if ($ev.eventType -ne "tool_call_started" -and $ev.eventType -ne "tool_call_completed") { continue }
        $toolName = [string]$ev.payload.tool
        if ([string]::IsNullOrWhiteSpace($toolName)) { continue }
        if ($ev.eventType -eq "tool_call_started") {
            if (-not $startedTools.ContainsKey($toolName)) { $startedTools[$toolName] = 0 }
            $startedTools[$toolName] = $startedTools[$toolName] + 1
        } else {
            $out = ""
            if ($ev.payload.output -ne $null) { $out = [string]$ev.payload.output }
            if (-not $completedByTool.ContainsKey($toolName)) { $completedByTool[$toolName] = @() }
            $completedByTool[$toolName] += $out
        }
    }
}
Write-Host "  tool_call_started by tool:"
if ($startedTools.Count -eq 0) {
    Write-Host "    (none - NO tool call happened at all)"
} else {
    foreach ($k in ($startedTools.Keys | Sort-Object)) {
        Write-Host ("    " + $k + " x" + $startedTools[$k])
    }
}

$internalStarted = @($startedTools.Keys | Where-Object { $_ -like "internal_*" })
$readOutputs = @()
if ($completedByTool.ContainsKey("internal_read_attachment")) { $readOutputs = @($completedByTool["internal_read_attachment"]) }

# ------------------------------------------------------------
# STEP 9: assertions
# ------------------------------------------------------------
Write-Host "STEP9: assertions"
Assert-True ($internalStarted.Count -ge 1) ("no internal_* tool call observed (started tools: " + ($startedTools.Keys -join ",") + ") - L1 tool surface not exercised")
Write-Host ("  H1 OK: internal_* tool called (" + ($internalStarted -join ",") + ")")

Assert-True ($startedTools.ContainsKey("internal_get_sub_task_detail")) "internal_get_sub_task_detail never called - model skipped task-detail discovery"
Write-Host "  H2 OK: internal_get_sub_task_detail called"

Assert-True ($startedTools.ContainsKey("internal_read_attachment")) "internal_read_attachment never called - model did not read the attached probe file"
Write-Host "  H3 OK: internal_read_attachment called"

$readHit = @($readOutputs | Where-Object { $_ -like "*" + $Marker + "*" })
Assert-True ($readHit.Count -ge 1) ("internal_read_attachment returned no output containing marker " + $Marker + " - attachment content did not reach the model")
Write-Host "  H4 OK: marker flowed through internal_read_attachment output"

$convoText = ""
foreach ($s in $subTasks) {
    foreach ($m in (Get-Conversation -SubTaskId ([string]$s.id) -Headers $adminHeaders)) {
        if ($m.content -ne $null) { $convoText = $convoText + "`n" + [string]$m.content }
    }
}
Assert-True ($convoText -like "*" + $Marker + "*") "marker not found in any sub-task execution output - model read the file but did not use it in the deliverable"
Write-Host "  H5 OK: marker appears in execution output"

if ($startedTools.ContainsKey("internal_register_output_attachment")) {
    Write-Host "  S1 OK: internal_register_output_attachment also called"
} else {
    Write-Host "  S1 WARN: internal_register_output_attachment not called (soft: deliverable is plain text, platform materialises it)"
}

# ------------------------------------------------------------
# summary
# ------------------------------------------------------------
$taskDetail = Get-Task -TaskId $taskId -Headers $adminHeaders
Write-Host ""
Write-Host "OK: internal LLM tool capability e2e passed"
Write-Host ("  taskId=" + $taskId + " taskStatus=" + $taskDetail.status)
Write-Host ("  subTaskCount=" + $subTasks.Count + " uploadedProbe=" + $uploaded.Count)
Write-Host ("  internal tools called: " + (($internalStarted | Sort-Object) -join ", "))
Write-Host ("  marker=" + $Marker)
exit 0
