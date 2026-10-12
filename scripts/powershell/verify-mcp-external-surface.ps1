# ============================================================
# helloai 对外 MCP 工具面隔离验证（REF-8.1 B / D-2026-10-12-5）
#
# 用途：证明 internal_* 工具**没有泄漏进对外 MCP tools/list**。
#
# 为什么必须走 SSE 通道（而不是 REST 别名）：
#   GET  /api/mcp/tools            → 返回**手写静态清单** McpController.TOOL_NAMES
#   POST /api/mcp/jsonrpc tools/list → 同样是**手写静态清单**
#   两者都与 spring-ai 自动配置聚合出的真实对外面**不同源**，断言它们等于没断言
#   （甚至本身就可能漂移）。唯一真实对外面 = spring-ai
#   ToolCallbackConverterAutoConfiguration 聚合全部 ToolCallbackProvider/ToolCallback Bean
#   后经 MCP SSE 暴露的集合 ⇒ 本脚本走 GET /mcp/sse + POST /mcp/messages。
#
# 断言（HARD = 失败即 exit 1）：
#   H1 tools/list 能拿到非空工具集
#   H2 工具集**恰好等于** 13 个对外工具（多一个 = 泄漏，少一个 = 误删）
#   H3 没有任何工具名以 internal_ 开头
#
# 前置：helloai-start 已在 6565 运行；curl.exe 在 PATH 上（SSE 长连接用）。
#
# 凭据获取（管理员口令，优先级从高到低）：
#   1) -AdminPassword 参数传非空值
#   2) 环境变量 HELLOAI_ADMIN_PASSWORD 非空
#   3) 两者都没有 → 交互式提示输入（不回显）；非交互宿主（CI/无人值守）直接报错
#
# 用法（项目根，PowerShell）：
#   powershell -File .\scripts\powershell\verify-mcp-external-surface.ps1 -AdminPassword "..."
#   # 或使用环境变量：
#   $env:HELLOAI_ADMIN_PASSWORD="..."
#   powershell -File .\scripts\powershell\verify-mcp-external-surface.ps1
#
# Ref: LOG-20261012-006 · doc/HelloAI 实现差距表.md D-2026-10-12-5
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "",
    [string]$WorkDir = ".tmp",
    [int]$SseWarmupSec = 3,
    [int]$ReplyWaitSec = 3
)

# ------------------------------------------------------------
# 程序集预加载（PS 5.1 兼容）——必须先于任何 System.Net.Http.HttpClient 使用，
# 否则报「找不到类型 [System.Net.Http.HttpClient]」（5.1 会话未必预加载该程序集）。
# 规则 6：PS 5.1 在中文 Windows 上按 GBK 解析源码，本脚本必须存 UTF-8 with BOM。
# ------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http

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

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

# 对外工具面唯一权威清单（与 McpController.TOOL_NAMES 对齐；改动须同步两边）
$ExpectedTools = @(
    "pullTasks", "ack", "claimSubTask", "startSubTask", "heartbeat", "uploadArtifact",
    "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
    "getSubTaskDetail", "checkIn", "checkOut"
)

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) { throw ("ASSERT_FAIL: " + $Msg) }
}

function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("GET","POST")][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = "",
        [hashtable]$Headers = @{}
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    foreach ($k in $Headers.Keys) { $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null }
    $content = $null
    if ($Method -eq "POST") {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        if ($Method -eq "GET") { $resp = $client.GetAsync($Uri).Result }
        else { $resp = $client.PostAsync($Uri, $content).Result }
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } finally { $client.Dispose() }
}

# 从 SSE 文本里抽出所有 data: 行的 JSON 对象
function Read-SseJson([string]$FilePath, [long]$FromOffset) {
    $items = New-Object System.Collections.ArrayList
    if (-not (Test-Path $FilePath)) { return ,$items }
    $reader = [System.IO.File]::Open($FilePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $reader.Position = $FromOffset
        $sr = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd()
        $sr.Close()
    } finally { $reader.Close() }
    foreach ($line in ($text -split "`r?`n")) {
        $t = $line.Trim()
        if (-not $t.StartsWith("data:")) { continue }
        $payload = $t.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($payload)) { continue }
        try { [void]$items.Add(($payload | ConvertFrom-Json)) } catch { }
    }
    return ,$items
}

# 发一条 JSON-RPC 消息，等待并读回 SSE 新内容里的 JSON 对象
function Send-Mcp {
    param([string]$Body, [string]$Label, [hashtable]$Headers, [string]$SseFile, [string]$SessionId, [int]$WaitSec)
    Write-Host ("=== " + $Label + " ===")
    $posBefore = 0
    if (Test-Path $SseFile) { $posBefore = (Get-Item $SseFile).Length }
    $resp = Invoke-Json -Method POST -Uri ($BaseUrl + "/mcp/messages?sessionId=" + $SessionId) -Body $Body -Headers $Headers
    Write-Host ("  POST HTTP " + $resp.Code)
    if ($resp.Code -ne 200) { Write-Host ("  POST body: " + $resp.Body) }
    Start-Sleep -Seconds $WaitSec
    $posAfter = 0
    if (Test-Path $SseFile) { $posAfter = (Get-Item $SseFile).Length }
    $msgs = @(Read-SseJson -FilePath $SseFile -FromOffset $posBefore)
    Write-Host ("  SSE new bytes " + $posBefore + " -> " + $posAfter + ", json messages=" + $msgs.Count)
    return ,$msgs
}

# ------------------------------------------------------------
# STEP 0: reachability
# ------------------------------------------------------------
Write-Host ("STEP0: server reachability (" + $BaseUrl + ")")
try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(5)
    $r = $ping.GetAsync($BaseUrl + "/api/health").Result
    Write-Host ("  HTTP " + [int]$r.StatusCode + " - server is up")
    $ping.Dispose()
} catch {
    throw ("server NOT reachable at " + $BaseUrl + " - start HelloAIApplication first: " + $_.Exception.Message)
}

# ------------------------------------------------------------
# STEP 1: admin login
# ------------------------------------------------------------
Write-Host "STEP1: admin login"
$loginBody = '{"type":"admin","username":"' + $AdminUsername + '","credential":"' + $AdminPassword + '"}'
$loginResp = Invoke-Json -Method POST -Uri ($BaseUrl + "/api/auth/login") -Body $loginBody
$parsed = $loginResp.Body | ConvertFrom-Json
Assert-True ([int]$loginResp.Code -eq 200) ("login HTTP " + $loginResp.Code + " body=" + $loginResp.Body)
$adminToken = [string]$parsed.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($adminToken)) "admin token is empty"
$adminHeaders = @{ "X-Admin-Token" = $adminToken }
Write-Host "  admin token acquired"

# ------------------------------------------------------------
# STEP 2: open the real external MCP surface (SSE)
# ------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
# 用绝对路径（Resolve-Path）——Start-Job 的 job 进程工作目录与主脚本不同，
# 若 $sseFile 是相对 .tmp\... 会被 job 写到别处，主脚本读不到 ⇒ sessionId 提取失败。
$workDirAbs = (Resolve-Path $WorkDir).Path
$sseFile = Join-Path $workDirAbs ("mcp-external-surface-" + [DateTime]::UtcNow.ToString("yyyyMMddHHmmss") + ".txt")
if (Test-Path $sseFile) { Remove-Item $sseFile -Force }

Write-Host "STEP2: open MCP SSE long connection"
# curl 用 -o 直接写字节文件、不经 PowerShell 管道——避免经 | Out-File 时的 UTF-8→string(GBK)
# 解码错位：SSE 工具名/描述含中文，管道解码错误会破坏 JSON，ConvertFrom-Json 失败 → 0 工具。
# -s 抑制进度条；-N 无缓冲持续写（SSE 长连接期间文件持续增长）。
$job = Start-Job -ScriptBlock {
    param($absFile, $baseUrl)
    & curl.exe -s -N ($baseUrl + "/mcp/sse") -o $absFile
} -ArgumentList $sseFile, $BaseUrl
Start-Sleep -Seconds $SseWarmupSec

$sid = ""
if (Test-Path $sseFile) {
    $content = Get-Content $sseFile -Raw -ErrorAction SilentlyContinue
    $m = [regex]::Match($content, 'sessionId=([A-Za-z0-9-]+)')
    if ($m.Success) { $sid = $m.Groups[1].Value }
}
Assert-True (-not [string]::IsNullOrWhiteSpace($sid)) ("sessionId extraction failed (sse file: " + $sseFile + ")")
Write-Host ("  sessionId=" + $sid)

try {
    # ------------------------------------------------------------
    # STEP 3: MCP handshake (required before tools/list)
    # ------------------------------------------------------------
    Write-Host "STEP3: MCP handshake"
    $initBody = '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ps-mcp-external-surface","version":"1.0.0"}}}'
    $null = Send-Mcp -Body $initBody -Label "initialize" -Headers $adminHeaders -SseFile $sseFile -SessionId $sid -WaitSec $ReplyWaitSec
    $null = Send-Mcp -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label "notifications/initialized" -Headers $adminHeaders -SseFile $sseFile -SessionId $sid -WaitSec 1

    # ------------------------------------------------------------
    # STEP 4: tools/list on the REAL external surface
    # ------------------------------------------------------------
    Write-Host "STEP4: tools/list (real external surface)"
    $listMsgs = @(Send-Mcp -Body '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' -Label "tools/list" -Headers $adminHeaders -SseFile $sseFile -SessionId $sid -WaitSec $ReplyWaitSec)

    $names = New-Object System.Collections.ArrayList
    foreach ($m in $listMsgs) {
        if ($m.result -eq $null) { continue }
        if ($m.result.tools -eq $null) { continue }
        foreach ($t in @($m.result.tools)) {
            if ($t.name -ne $null) { [void]$names.Add([string]$t.name) }
        }
    }
    Write-Host ("  tools/list returned " + $names.Count + " tools:")
    foreach ($n in ($names | Sort-Object)) { Write-Host ("    " + $n) }

    # ------------------------------------------------------------
    # STEP 5: assertions
    # ------------------------------------------------------------
    Write-Host "STEP5: assertions"
    Assert-True ($names.Count -ge 1) "tools/list returned an empty tool set - external MCP surface is broken"

    $leaked = @($names | Where-Object { $_ -like "internal_*" })
    Assert-True ($leaked.Count -eq 0) ("internal_* tools leaked into external MCP tools/list: " + ($leaked -join ","))
    Write-Host "  H1 OK: tools/list non-empty"
    Write-Host "  H2 OK: no internal_* tool in external tools/list"

    $extra = @($names | Where-Object { $ExpectedTools -notcontains $_ })
    $missing = @($ExpectedTools | Where-Object { $names -notcontains $_ })
    Assert-True (($extra.Count -eq 0) -and ($missing.Count -eq 0)) ("external tool set mismatch: extra=[" + ($extra -join ",") + "] missing=[" + ($missing -join ",") + "]")
    Write-Host ("  H3 OK: external tool set == expected " + $ExpectedTools.Count + " tools")

    Write-Host ""
    Write-Host "OK: external MCP surface contains no internal_* tool"
    Write-Host ("  toolCount=" + $names.Count + " sessionId=" + $sid)
    Write-Host ("  sse log: " + $sseFile)
    exit 0
} finally {
    if ($job -ne $null) {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}
