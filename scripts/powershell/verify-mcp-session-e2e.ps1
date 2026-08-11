# verify-mcp-session-e2e.ps1
# ============================================================
# helloai A0-2 MCP session lifecycle + REST alias channel verifier
# 用途：验证 MCP 接入体验改进（A0-2, iteration 6.61）：
#   S1) SSE 握手 GET /mcp/sse 拿 sessionId
#   S2) SSE 通道完整协议握手（initialize + notifications/initialized）
#       -> tools/call heartbeat -> 200 + SSE result（session 有效）
#       NOTE: 缺 initialized 通知时 SDK 的 exchangeSink 永不发信号,
#             tools/call 会永久挂死（协议约束, 脚本必须按序握手）
#   S3) 未知 sessionId POST /mcp/messages -> 404 Session not found
#       + fixHint 修复提示（重新握手 / REST 别名通道）
#   S4) 断连后复用旧 sessionId -> 观察输出（SDK 回收有时延窗口,
#       回收时序不保证, 不做硬断言）
#   S5) REST 别名 POST /api/mcp/jsonrpc tools/list -> 10 工具 + inputSchema
#       （无需 session，断连后仍可用 = 免握手复用）
#   S6) REST 别名 tools/call heartbeat -> 同步 result（非 SSE 静默空 body）
#   S7) REST 别名 tools/call checkIn/checkOut -> 同步租约回执
#
# NOTE: PowerShell 5.1 + 中文 Windows。本脚本所有 runtime 字符串
#       保持 100% ASCII（含注释），规避 GBK/UTF-8 解析与 BOM 陷阱。
#
# Usage (project root, PowerShell):
#   powershell -File .\scripts\powershell\verify-mcp-session-e2e.ps1
# ============================================================

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

$base      = "http://localhost:6565"
$scriptDir = "E:\yhzx\1027\helloai"
$sseFile   = Join-Path $scriptDir "sse-mcp-session-e2e.txt"
$logFile   = Join-Path $scriptDir "mcp-session-e2e.log"

$passCount = 0
$failCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) {
        $script:passCount++
        Write-Output ('[PASS] ' + $Message)
    } else {
        $script:failCount++
        Write-Output ('[FAIL] ' + $Message)
    }
}

Remove-Item $sseFile -ErrorAction SilentlyContinue
Remove-Item $logFile -ErrorAction SilentlyContinue

# ============================================================
# HTTP helper (GET/POST/PUT/DELETE unified)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("GET","POST","PUT","DELETE")][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = "",
        [hashtable]$Headers = @{}
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne "GET" -and $Method -ne "DELETE") {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        try {
            if ($Method -eq "GET")       { $resp = $client.GetAsync($Uri).Result }
            elseif ($Method -eq "DELETE") { $resp = $client.DeleteAsync($Uri).Result }
            elseif ($Method -eq "POST")  { $resp = $client.PostAsync($Uri, $content).Result }
            elseif ($Method -eq "PUT")   { $resp = $client.PutAsync($Uri, $content).Result }
            $code = [int]$resp.StatusCode
            $bodyOut = $resp.Content.ReadAsStringAsync().Result
            return @{ Code = $code; Body = $bodyOut }
        } catch [System.Net.Http.HttpRequestException] {
            Write-Error "$Method $Uri network failed: $($_.Exception.Message)"
            return @{ Code = -1; Body = $_.Exception.Message }
        } catch {
            Write-Error "$Method $Uri failed: $($_.Exception.GetType().Name) - $($_.Exception.Message)"
            return @{ Code = -2; Body = $_.Exception.Message }
        }
    } finally {
        $client.Dispose()
    }
}

# ============================================================
# MCP SSE helper: start curl -N job, extract sessionId
# ============================================================
function Start-McpSse {
    param([string]$ScriptDir, [string]$SseFileName)
    $absSseFile = if ([System.IO.Path]::IsPathRooted($SseFileName)) {
        $SseFileName
    } else {
        Join-Path $ScriptDir $SseFileName
    }
    $job = Start-Job -ScriptBlock {
        param($absFile)
        & curl.exe -i -N http://localhost:6565/mcp/sse *>&1 |
            Out-File -Encoding utf8 -FilePath $absFile
    } -ArgumentList $absSseFile

    Start-Sleep -Seconds 3

    if (-not (Test-Path $absSseFile)) {
        Write-Warning "SSE file not yet created: $absSseFile - check curl.exe on PATH and server reachable"
    }
    $content = ""
    if (Test-Path $absSseFile) {
        $content = Get-Content $absSseFile -Raw -ErrorAction SilentlyContinue
    }
    $m = [regex]::Match($content, 'sessionId=([A-Za-z0-9-]+)')
    $sid = if ($m.Success) { $m.Groups[1].Value } else { "" }
    return @{ Job = $job; SessionId = $sid; AbsFile = $absSseFile }
}

# ============================================================
# STEP 0: server reachability
# ============================================================
Write-Output "=== [0] server reachability check ==="
try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync("$base/api/health").Result
    Write-Output "HTTP $($pingResp.StatusCode) - server is up"
    $ping.Dispose()
} catch {
    Write-Error "Server NOT reachable at $base - please run HelloAIApplication via IDEA first"
    Write-Error "Error: $($_.Exception.Message)"
    exit 1
}
Write-Output ""

# ============================================================
# STEP A: admin login
# ============================================================
Write-Output "=== [A] admin login ==="
$loginBody = '{"type":"admin","username":"admin","credential":"admin123"}'
$loginResp = Invoke-Json -Method POST -Uri "$base/api/auth/login" -Body $loginBody
Write-Output "HTTP $($loginResp.Code)"
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error "admin login failed: $($loginResp.Body)"
    exit 1
}
Write-Output "adminToken = $($adminToken.Substring(0, 16))..."
Write-Output ""

# ============================================================
# STEP B: create or reuse A02-test-executor
# ============================================================
Write-Output "=== [B] create or reuse A02-test-executor (admin token) ==="
$agentName   = "A02-test-executor"
$agentId     = $null
$agentApiKey = $null

$lookupResp = Invoke-Json -Method GET -Uri "$base/api/admin/agents/list?pageSize=50" -Headers @{ "X-Admin-Token" = $adminToken }
$parsedJson = $lookupResp.Body | ConvertFrom-Json
$lookupData = $parsedJson.data
if ($lookupData -eq $null -or $lookupData.list -eq $null) {
    $existing = @()
} else {
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
}
if ($existing.Count -gt 0) {
    $agentId = $existing[0].id
    $agentApiKey = $existing[0].apiKey
    Write-Output "reuse existing: id=$agentId"
} else {
    $createBody = "{`"name`":`"$agentName`",`"role`":`"EXECUTOR`",`"remark`":`"A0-2 session e2e auto created`"}"
    $createResp = Invoke-Json -Method POST -Uri "$base/api/admin/agents" -Body $createBody -Headers @{ "X-Admin-Token" = $adminToken }
    Write-Output "create HTTP $($createResp.Code)"
    $agentData = ($createResp.Body | ConvertFrom-Json).data
    $agentId = $agentData.id
    $agentApiKey = $agentData.apiKey
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Error "agent create / lookup failed (no apiKey)"
    exit 1
}
Write-Output "agentId    = $agentId"
Write-Output "agentApiKey = $agentApiKey"
Write-Output ""

# ============================================================
# STEP C: SSE handshake
# ============================================================
Write-Output "=== [C] SSE handshake (GET /mcp/sse) ==="
$sseInfo = Start-McpSse -ScriptDir $scriptDir -SseFileName (Split-Path $sseFile -Leaf)
$job = $sseInfo.Job
$sid = $sseInfo.SessionId
Write-Output "sse abs file = $($sseInfo.AbsFile)"
Write-Output "sessionId = $sid"
Assert-True (-not [string]::IsNullOrEmpty($sid)) "S1 handshake: sessionId extracted from SSE endpoint event"
if ([string]::IsNullOrEmpty($sid)) {
    Stop-Job $job -PassThru | Remove-Job -Force
    exit 1
}
Write-Output ""

# ============================================================
# STEP D: MCP protocol 4-step handshake on SSE channel
# (sse -> initialize -> notifications/initialized -> tools/call;
#  missing initialized => exchangeSink never signals => hang)
# ============================================================
Write-Output "=== [D1] initialize ==="
$initBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"a02-e2e","version":"1.0"}}}'
$initResp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=$sid" -Body $initBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "initialize POST Status: $($initResp.Code)"
Write-Output "initialize POST Body: $($initResp.Body)"
Start-Sleep -Seconds 1
Assert-True ($initResp.Code -eq 200) "S2a initialize: POST accepted (200)"

Write-Output "=== [D2] notifications/initialized ==="
$notifBody = '{"jsonrpc":"2.0","method":"notifications/initialized"}'
$notifResp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=$sid" -Body $notifBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "initialized POST Status: $($notifResp.Code)"
Assert-True ($notifResp.Code -eq 200) "S2b notifications/initialized: POST accepted (200)"

Write-Output "=== [D3] SSE channel tools/call heartbeat (session valid, after handshake) ==="
# MCP 通道工具需显式透传 agentId + sessionId（McpMcpServer.requireAuthId 路径 1，
# spring-ai 1.1.x 不隐式注入 ToolContext；鉴权用 filter 的 SESSION_AUTH 覆盖）
$dBody = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"heartbeat","arguments":{"agentId":' + $agentId + ',"sessionId":"' + $sid + '"}}}'
$posBefore = (Get-Item $sseFile).Length
$dResp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=$sid" -Body $dBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "POST Status: $($dResp.Code)"
Write-Output "POST Body: $($dResp.Body)"
Assert-True ($dResp.Code -eq 200) "S2 SSE channel heartbeat: POST accepted (200)"
Start-Sleep -Seconds 2
$posAfter = (Get-Item $sseFile).Length
$reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$reader.Position = $posBefore
$sseReader = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
$dSse = $sseReader.ReadToEnd()
$sseReader.Close()
$reader.Close()
Write-Output "--- SSE new content ---"
Write-Output $dSse
Assert-True ($dSse -match '"id":2' -and $dSse -match '"isError":false') "S2 SSE channel heartbeat: result pushed back via SSE stream"
Write-Output ""

# ============================================================
# STEP E: disconnect (stop SSE job) -> session reclaimed by SDK
# ============================================================
Write-Output "=== [E] disconnect: stop SSE job ==="
Stop-Job $job -PassThru | Remove-Job -Force
Start-Sleep -Seconds 5
Write-Output "SSE connection closed, waited 5s for server-side session cleanup"
Write-Output ""

# ============================================================
# STEP F1: unknown sessionId -> deterministic 404 + fixHint
# (session reclaimed == never existed from SDK perspective;
#  avoids depending on server-side reclaim timing after EOF)
# ============================================================
Write-Output "=== [F1] unknown sessionId -> expect 404 Session not found + fixHint ==="
$fBody = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"heartbeat","arguments":{}}}'
$fResp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=a02-unknown-session-000" -Body $fBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "POST Status: $($fResp.Code)"
Write-Output "POST Body: $($fResp.Body)"
if ($fResp.Code -eq 404) {
    Assert-True ($fResp.Body -match 'Session not found') "S3 unknown session: SDK 404 Session not found"
    Assert-True ($fResp.Body -match 'fixHint') "S4 unknown session: 404 body carries fixHint"
    Assert-True ($fResp.Body -match '/mcp/sse') "S4 fixHint points to re-handshake (GET /mcp/sse)"
    Assert-True ($fResp.Body -match '/api/mcp/jsonrpc') "S4 fixHint points to REST alias channel"
} else {
    Assert-True $false "S3 unknown session: expected 404 (got $($fResp.Code))"
}
Write-Output ""

# ============================================================
# STEP F2: reuse stale sessionId after disconnect (observation)
# SDK keeps session alive for a delay window after SSE EOF;
# reclaim timing is not deterministic -> no hard assertion.
# ============================================================
Write-Output "=== [F2] reuse stale sessionId after disconnect (observation only) ==="
$f2Resp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=$sid" -Body $fBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "POST Status: $($f2Resp.Code)"
Write-Output "POST Body: $($f2Resp.Body)"
if ($f2Resp.Code -eq 404) { Write-Output "observe: session reclaimed already (404 + fixHint expected)" }
elseif ($f2Resp.Code -eq 200) { Write-Output "observe: session still alive in reclaim window (200)" }
else { Write-Output "observe: unexpected code $($f2Resp.Code) - SDK exchange sink behavior" }
Write-Output ""

# ============================================================
# STEP G: REST alias tools/list (no session, stateless)
# ============================================================
Write-Output "=== [G] REST alias POST /api/mcp/jsonrpc tools/list (stateless) ==="
$gBody = '{"jsonrpc":"2.0","id":3,"method":"tools/list"}'
$gResp = Invoke-Json -Method POST -Uri "$base/api/mcp/jsonrpc" -Body $gBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($gResp.Code)"
Write-Output "Body: $($gResp.Body)"
$gObj = $null
try { $gObj = $gResp.Body | ConvertFrom-Json } catch {}
Assert-True ($gObj -ne $null -and $gObj.result -ne $null) "S5 REST alias tools/list: sync result returned"
if ($gObj -ne $null -and $gObj.result -ne $null) {
    $tools = @($gObj.result.tools)
    Assert-True ($tools.Count -eq 10) "S5 REST alias tools/list: 10 tools declared (got $($tools.Count))"
    $noSchema = @($tools | Where-Object { $_.inputSchema -eq $null })
    Assert-True ($noSchema.Count -eq 0) "S5 REST alias tools/list: every tool has inputSchema (JSON Schema)"
}
Write-Output ""

# ============================================================
# STEP H: REST alias tools/call heartbeat (sync result after disconnect)
# ============================================================
Write-Output "=== [H] REST alias tools/call heartbeat (sync result, no session needed) ==="
$hBody = '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"heartbeat","arguments":{}}}'
$hResp = Invoke-Json -Method POST -Uri "$base/api/mcp/jsonrpc" -Body $hBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($hResp.Code)"
Write-Output "Body: $($hResp.Body)"
$hObj = $null
try { $hObj = $hResp.Body | ConvertFrom-Json } catch {}
Assert-True ($hObj -ne $null -and $hObj.result -ne $null -and $hObj.result.ok -eq $true) "S6 REST alias heartbeat: sync result ok=true after SSE disconnect (stateless reuse)"
Write-Output ""

# ============================================================
# STEP I: REST alias tools/call checkIn -> sync lease receipt
# ============================================================
Write-Output "=== [I] REST alias tools/call checkIn (sync lease receipt) ==="
$iBody = '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"checkIn","arguments":{"workMode":"AUTO"}}}'
$iResp = Invoke-Json -Method POST -Uri "$base/api/mcp/jsonrpc" -Body $iBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($iResp.Code)"
Write-Output "Body: $($iResp.Body)"
$iObj = $null
try { $iObj = $iResp.Body | ConvertFrom-Json } catch {}
if ($iObj -ne $null -and $iObj.result -ne $null) {
    Assert-True ($iObj.result.ok -eq $true) "S7 REST alias checkIn: ok=true"
    Assert-True ($null -ne $iObj.result.leaseId) "S7 REST alias checkIn: sync leaseId"
    Assert-True (-not [string]::IsNullOrEmpty($iObj.result.expiresAt)) "S7 REST alias checkIn: sync expiresAt"
} else {
    Assert-True $false "S7 REST alias checkIn: sync result missing (body=$($iResp.Body))"
}
Write-Output ""

# ============================================================
# STEP J: REST alias tools/call checkOut (idempotent close)
# ============================================================
Write-Output "=== [J] REST alias tools/call checkOut (sync close receipt) ==="
$jBody = '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"checkOut","arguments":{"reason":"a02-e2e-close"}}}'
$jResp = Invoke-Json -Method POST -Uri "$base/api/mcp/jsonrpc" -Body $jBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($jResp.Code)"
Write-Output "Body: $($jResp.Body)"
$jObj = $null
try { $jObj = $jResp.Body | ConvertFrom-Json } catch {}
Assert-True ($jObj -ne $null -and $jObj.result -ne $null -and $jObj.result.ok -eq $true) "S7 REST alias checkOut: sync close receipt"
Write-Output ""

# ============================================================
# Summary
# ============================================================
Write-Output "===================================================="
Write-Output ("RESULT: PASS=" + $passCount + " FAIL=" + $failCount)
Write-Output "===================================================="
Write-Output "SSE log:   $sseFile"
Write-Output "Test agent: id=$agentId name=$agentName"
Write-Output ""

if ($failCount -gt 0) {
    Write-Error "verification FAILED with $failCount assertion(s)"
    exit 1
}
Write-Output "All assertions passed."
