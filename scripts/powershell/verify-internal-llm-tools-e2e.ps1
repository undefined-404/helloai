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
# 与 macOS 侧同名 .sh 对齐（2026-10-12，依据 LOG-20261012-007/008 双平台实测）——四处订正：
#   ① 子任务列表端点改名：`GET /api/sub-tasks?taskId=` 实测 **405** ⇒ `/api/sub-tasks/list?taskId=`；
#   ② Agent 来源：平台限「同角色同模型唯一（API_KEY_LLM + 未删除）」
#      （AgentSkillPolicyService.validateModelUniqueInRole），dev 库常被常驻 `inner-<model>-<role>` 占满
#      ⇒ 注册必 **409**；自动改用**存量 Agent**（管理端 `GET /api/admin/agents/list` 的 `apiKey`；
#      不注册 / 不绑凭证 / 不改库）。`-ReuseMode auto|on|off` 可覆盖（默认 auto）；
#   ③ 选人：`AgentSelector.pickPreferred` 排序首位 `dutyRank`、次位 `score` ⇒ 不打卡则子任务派给存量
#      Agent（score 高者必胜），探针附件无法以「该执行者身份」上传（403）⇒ 新增 **STEP2.8 值班打卡**
#      （EXECUTOR 硬前置，PLANNER/REVIEWER 仅告警；离场 finally 自动 checkOut）。`-DutyTtlMinutes` 可调；
#   ④ **两层结论 + 证据先出**：`DEAD_LETTER`/超时不再当场退出，仍采集 STEP8 证据；
#      退出码 `0`=两层皆过 / `1`=判据层红（H1-H5 失败）/ `2`=判据层绿而流程层未收敛。
#   【状态】本轮改动**在 macOS 上编写，本机无 pwsh，未运行验证（NOT RUN）**——请在 Windows 侧实跑确认。
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
    [int]$AssignWaitSec = 180,
    # Agent 来源：auto=能注册就注册，被「同角色同模型唯一」占用则改用存量 Agent；on=直接复用；off=只注册
    [ValidateSet("auto", "on", "off")]
    [string]$ReuseMode = "auto",
    # 子任务被派给本次 Agent 之外的执行者时立即退出（默认仅告警，与 macOS 侧 .sh 一致）
    [switch]$FailOnForeignAssignee,
    # 值班租约 TTL（分钟）——STEP2.8 打卡用；离场自动 checkOut
    [int]$DutyTtlMinutes = 60,
    # 跳过 STEP2.8 打卡（回到与改版前 ps1 完全一致的行为；此时子任务可能派给存量 Agent）
    [switch]$NoCheckIn
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
    if (-not $Cond) {
        # 硬失败出口：先注销值班租约（best-effort），再抛出（与 macOS 侧 .sh 的 cleanup trap 尽力对齐）
        Invoke-CheckOutAll
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 30) {
    $json = $null
    if ($Body -ne $null) { $json = ($Body | ConvertTo-Json -Depth 10) }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Get-SubTasks([string]$TaskId, [hashtable]$Headers) {
    # 端点已随「描述性路径」整改改名：旧路径 `GET /api/sub-tasks?taskId=` 实测 405，现为 /list
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

# ────────────────────────────────────────────────────────────
#  存量 Agent 复用 + 值班打卡（与 macOS 侧 .sh 同源）
# ────────────────────────────────────────────────────────────

# 管理端 Agent 列表（含 apiKey，用于复用存量 Agent；不注册 / 不绑凭证 / 不改库）
function Get-AdminAgents([hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/admin/agents/list?page=1&pageSize=200") -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("admin agents list code=" + $resp.code + " msg=" + $resp.msg)
    return @($resp.data.list)
}

# 复用选择：精确模型优先，其次同角色任意可用 API_KEY_LLM（ACTIVE 且带 apiKey）
function Get-ReuseAgent([string]$Role, [object[]]$AllAgents) {
    $eligible = @($AllAgents | Where-Object {
        $_.role -eq $Role -and $_.accessType -eq "API_KEY_LLM" -and $_.status -eq "ACTIVE" `
            -and -not [string]::IsNullOrWhiteSpace([string]$_.apiKey)
    })
    Assert-True ($eligible.Count -ge 1) ("复用失败：找不到 ACTIVE 且带 apiKey 的 API_KEY_LLM " + $Role)
    $exact = @($eligible | Where-Object { $_.modelType -eq $LlmModelType })
    if ($exact.Count -ge 1) { return $exact[0] }
    return $eligible[0]
}

# 值班打卡（agent 自身 apiKey 鉴权）：dutyRank=1 ⇒ 初始分配优先选中本次 Agent
function Invoke-CheckIn([string]$AgentId, [string]$ApiKey) {
    $body = @{ workMode = "AUTO"; maxConcurrent = 5; ttlMinutes = $DutyTtlMinutes } | ConvertTo-Json
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add("Authorization", "Bearer " + $ApiKey)
        $content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
        $resp = $client.PostAsync(($BaseUrl + "/api/mcp/tools/checkIn"), $content).Result
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } finally { $client.Dispose() }
}

# 离场注销值班租约（best-effort，失败不报错）
function Invoke-CheckOut([string]$AgentId, [string]$ApiKey) {
    if ([string]::IsNullOrWhiteSpace($AgentId)) { return }
    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds(10)
        $client.DefaultRequestHeaders.Add("Authorization", "Bearer " + $ApiKey)
        $content = [System.Net.Http.StringContent]::new('{"closeReason":"verify-internal-llm-tools-e2e finished"}',
            [System.Text.Encoding]::UTF8, "application/json")
        $null = $client.PostAsync(($BaseUrl + "/api/mcp/tools/checkOut"), $content).Result
        $client.Dispose()
    } catch { }
}

# 三个 Agent 全部 checkOut（本脚本无 try/finally 包裹主流程 ⇒ 所有出口显式调用：
# 正常出口 exit 之前、硬失败处由 Assert-True 调用；漏掉的路径由租约 TTL 自然过期兜底）
function Invoke-CheckOutAll() {
    if ($NoCheckIn) { return }
    if ($agents -eq $null) { return }   # 尚未 provision（STEP2 之前失败）时无租约
    foreach ($role in @("PLANNER", "EXECUTOR", "REVIEWER")) {
        if ($agents.ContainsKey($role)) {
            Invoke-CheckOut -AgentId ([string]$agents[$role].Id) -ApiKey ([string]$agents[$role].ApiKey)
        }
    }
}

# 派给「非本次 Agent」时的告警/快败（STEP6 与 STEP7 共用；与 macOS 侧 .sh 同源）
function Confirm-ForeignAssignee([string]$SubTaskId, [string]$Assignee, [hashtable]$WarnedForeign) {
    if ($WarnedForeign.ContainsKey($SubTaskId)) { return }
    $WarnedForeign[$SubTaskId] = $true
    Write-Host ("  WARN: subTask " + $SubTaskId + " assigned to agent " + $Assignee + " which is NOT one of this run's API_KEY_LLM agents")
    Write-Host "        HINT: 本脚本默认会给本次 Agent 打卡（dutyRank 优先）；仍被派给他人说明存在并发抢占，"
    Write-Host "              可加 -FailOnForeignAssignee 快速失败，或改用独占候选池（清掉存量候选 / 换库）"
    if ($FailOnForeignAssignee) {
        throw ("subTask " + $SubTaskId + " 被派给非本次 Agent " + $Assignee + " ⇒ 本次断言无意义（-FailOnForeignAssignee 生效）")
    }
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
# STEP 2: provision platform LLM agents (register-or-reuse)
# ------------------------------------------------------------
Write-Host "STEP2: provision platform LLM agents (PLANNER/EXECUTOR/REVIEWER, API_KEY_LLM)"
$agents = @{}
$reusedAgents = $false
$allAgents = @(Get-AdminAgents -Headers $adminHeaders)
$occupied = @($allAgents | Where-Object {
    $_.accessType -eq "API_KEY_LLM" -and $_.modelType -eq $LlmModelType -and ($_.role -eq "PLANNER" -or $_.role -eq "EXECUTOR" -or $_.role -eq "REVIEWER")
})

if ($ReuseMode -eq "off") {
    Write-Host "  register mode (-ReuseMode off)"
} elseif ($ReuseMode -eq "on" -or $occupied.Count -gt 0) {
    if ($ReuseMode -eq "on") {
        Write-Host "  reuse mode (-ReuseMode on)"
    } else {
        $occupiedDesc = (($occupied | ForEach-Object { "" + $_.role + ":" + $_.name }) -join ",")
        Write-Host ("  reuse mode (auto)：" + $LlmModelType + " 已被占用（" + $occupiedDesc + "）—— 平台限「同角色同模型唯一」，改用存量 Agent")
    }
    foreach ($role in @("PLANNER", "EXECUTOR", "REVIEWER")) {
        $a = Get-ReuseAgent -Role $role -AllAgents $allAgents
        $agents[$role] = @{ Id = [string]$a.id; ApiKey = [string]$a.apiKey }
        Write-Host ("  " + $role.ToLower() + " = " + $a.name + " (" + $a.modelType + ")")
    }
    $reusedAgents = $true
} else {
    $agents["PLANNER"]  = Register-LlmAgent -Name ("illt-planner-"  + $ts) -Role "PLANNER"  -Headers $adminHeaders
    $agents["EXECUTOR"] = Register-LlmAgent -Name ("illt-executor-" + $ts) -Role "EXECUTOR" -Headers $adminHeaders
    $agents["REVIEWER"] = Register-LlmAgent -Name ("illt-reviewer-" + $ts) -Role "REVIEWER" -Headers $adminHeaders
    Write-Host ("  planner=" + $agents["PLANNER"].Id + " executor=" + $agents["EXECUTOR"].Id + " reviewer=" + $agents["REVIEWER"].Id)
}

$keyByAgentId = @{}
foreach ($k in $agents.Keys) { $keyByAgentId[[string]$agents[$k].Id] = [string]$agents[$k].ApiKey }

if ($reusedAgents) {
    Write-Host "STEP2.5: skip vault bind (复用存量 Agent 用其自己的托管凭证，不改库)"
} else {
    Write-Host ("STEP2.5: bind vault api-key (provider=" + $llmProvider + ")")
    foreach ($k in $agents.Keys) { Bind-AgentApiKey -AgentId $agents[$k].Id -Provider $llmProvider -Headers $adminHeaders }
    Write-Host "  vault credentials bound"
}

# ------------------------------------------------------------
# STEP 2.8: 值班打卡（pickPreferred 首位 dutyRank ⇒ 初始分配才轮到本次 Agent）
# ------------------------------------------------------------
Write-Host ("STEP2.8: check in (duty lease ttl=" + $DutyTtlMinutes + "min)")
if ($NoCheckIn) {
    Write-Host "  skipped check-in (-NoCheckIn)：分配可能落到存量 Agent 上"
} else {
    $exeCheck = Invoke-CheckIn -AgentId $agents["EXECUTOR"].Id -ApiKey $agents["EXECUTOR"].ApiKey
    Assert-True ($exeCheck.Code -eq 200) ("EXECUTOR checkIn HTTP " + $exeCheck.Code + " body=" + $exeCheck.Body + "（探针附件必须由该子任务的执行者上传：执行者不是本次 Agent 则 H3/H4 无解）")
    Write-Host ("  on duty: executor=" + $agents["EXECUTOR"].Id)
    foreach ($role in @("PLANNER", "REVIEWER")) {
        $r = Invoke-CheckIn -AgentId $agents[$role].Id -ApiKey $agents[$role].ApiKey
        if ($r.Code -eq 200) {
            Write-Host ("  on duty: " + $role.ToLower() + "=" + $agents[$role].Id)
        } else {
            Write-Host ("  WARN: " + $role + " checkIn 失败（HTTP " + $r.Code + "）—— 不阻断：存量 API_KEY_LLM 同角色 Agent 同样会被平台自动执行")
        }
    }
}

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
            Confirm-ForeignAssignee -SubTaskId $sid -Assignee $assignee -WarnedForeign $warnedForeign
            continue
        }
        $todo += @{ Id = $sid; Assignee = $assignee }
    }
    foreach ($item in $todo) {
        $up = Upload-ProbeAttachment -SubTaskId $item.Id -AgentApiKey $keyByAgentId[$item.Assignee] -FileName $AttachmentFileName -Content $BriefContent
        if ($up.Code -ge 200 -and $up.Code -lt 300) {
            $uploaded[$item.Id] = @{ At = [DateTime]::Now.ToString("HH:mm:ss"); Body = $up.Body }
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
$loopIssue = ""
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
        if (-not $keyByAgentId.ContainsKey($assignee)) {
            Confirm-ForeignAssignee -SubTaskId $sid -Assignee $assignee -WarnedForeign $warnedForeign
            continue
        }
        $up = Upload-ProbeAttachment -SubTaskId $sid -AgentApiKey $keyByAgentId[$assignee] -FileName $AttachmentFileName -Content $BriefContent
        if ($up.Code -ge 200 -and $up.Code -lt 300) {
            $uploaded[$sid] = @{ At = [DateTime]::Now.ToString("HH:mm:ss"); Body = $up.Body }
            Write-Host ("  [late] uploaded probe attachment for subTask " + $sid + " (assignee=" + $assignee + ")")
        }
    }

    # 死信/阻塞只记录并跳出——**不在此处退出**：H1-H5 是「内部 LLM 工具能力」判据，
    # 与之无关的流程未收敛（评审返工超限 / 超时）不该把已产生的证据一起丢掉
    $dead = @($subTasks | Where-Object { $_.status -in @("DEAD_LETTER", "BLOCKED") })
    if ($dead.Count -gt 0) {
        $loopIssue = "subTasks entered DEAD_LETTER/BLOCKED: " + (($dead | ForEach-Object { $_.id }) -join ",")
        break
    }

    $notDone = @($subTasks | Where-Object { $_.status -notin @("DONE", "CANCELLED") })
    if ($notDone.Count -eq 0) { $allDone = $true; break }
}
if ($allDone) {
    $subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
    Write-Host ("  all subTasks DONE/CANCELLED (" + $subTasks.Count + " total)")
} elseif ([string]::IsNullOrWhiteSpace($loopIssue)) {
    $loopIssue = "loop not finished in " + $LoopTimeoutSec + "s"
    Write-Host ("  WARN: " + $loopIssue + " —— 仍继续采集工具调用证据（STEP8）")
}

# ------------------------------------------------------------
# STEP 8: collect tool-call evidence from every sub-task timeline
# ------------------------------------------------------------
Write-Host "STEP8: collect tool_call_* evidence from timelines"
$startedTools = @{}
$completedByTool = @{}
Write-Host ""
Write-Host "  附件上传时刻 ↔ 工具调用时刻（判 H3/H4 失败是「没赶在执行前」还是「读了没用」）："
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
    $uploadedAt = ""
    if ($uploaded.ContainsKey($sid)) { $uploadedAt = [string]$uploaded[$sid].At }
    Write-Host ("subTask " + $sid + " | probeUploadedAt " + $uploadedAt)
    foreach ($ev in $events) {
        $et = [string]$ev.eventType
        if (-not $et.StartsWith("tool_call_")) { continue }
        Write-Host ("    " + $ev.createTime + "  " + $et + "  " + [string]$ev.payload.tool + "  success=" + [string]$ev.payload.success)
    }
}
Write-Host ""
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
Write-Host ("taskId=" + $taskId + " taskStatus=" + $taskDetail.status)
Write-Host ("  subTaskCount=" + $subTasks.Count + " uploadedProbe=" + $uploaded.Count)
Write-Host ("  internal tools called: " + (($internalStarted | Sort-Object) -join ", "))
Write-Host ("  marker=" + $Marker)
Write-Host ""
Write-Host "=== 结论（两层，互不替代）==="
Write-Host "判据层 H1-H5: PASS —— 内部 LLM 工具面已注入、模型真调 internal_*、附件内容回流进交付物"
if (-not [string]::IsNullOrWhiteSpace($loopIssue)) {
    Write-Host ("流程层: 未收敛 —— " + $loopIssue)
    Write-Host "  与内部 LLM 工具能力无关（属评审/返工/超时链）：看上面 STEP8 证据与本结论分层判读"
    Invoke-CheckOutAll
    exit 2
}
Write-Host "流程层: PASS —— 全部子任务 DONE/CANCELLED"
Write-Host ""
Write-Host "OK: internal LLM tool capability e2e passed"
Invoke-CheckOutAll
exit 0
