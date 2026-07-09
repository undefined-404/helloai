# ============================================================
# helloai MCP M4 鉴权验证脚本 v9
# 流程：
#   1) admin 登录 -> adminToken
#   2) 创建测试 agent (adminToken) -> agentApiKey
#   3) 跑 MCP SSE + 各种鉴权组合 -> 验证 401 / 200 + agentId 覆盖
# ============================================================

Add-Type -AssemblyName System.Net.Http

$base = "http://localhost:6565"
$scriptDir = "E:\yhzx\1027\helloai"
$sseFile = "$scriptDir\sse-auth.txt"
$logFile = "$scriptDir\m4-auth-test.log"

Remove-Item $sseFile -ErrorAction SilentlyContinue
Remove-Item $logFile -ErrorAction SilentlyContinue

# Helper: HTTP POST with optional auth header
function Invoke-PostJson {
    param([string]$Uri, [string]$Body, [hashtable]$Headers = @{})
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $content = [System.Net.Http.StringContent]::new(
        $Body, [System.Text.Encoding]::UTF8, "application/json"
    )
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    try {
        try {
            $resp = $client.PostAsync($Uri, $content).Result
            $code = [int]$resp.StatusCode
            $body = $resp.Content.ReadAsStringAsync().Result
            return @{ Code = $code; Body = $body }
        } catch [System.Net.Http.HttpRequestException] {
            Write-Error "POST $Uri failed (network): $($_.Exception.Message)"
            return @{ Code = -1; Body = $_.Exception.Message }
        } catch {
            Write-Error "POST $Uri failed: $($_.Exception.GetType().Name) - $($_.Exception.Message)"
            return @{ Code = -2; Body = $_.Exception.Message }
        }
    } finally {
        $client.Dispose()
    }
}

# Helper: HTTP GET
function Invoke-GetJson {
    param([string]$Uri, [hashtable]$Headers = @{})
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    try {
        try {
            $resp = $client.GetAsync($Uri).Result
            $code = [int]$resp.StatusCode
            $body = $resp.Content.ReadAsStringAsync().Result
            return @{ Code = $code; Body = $body }
        } catch [System.Net.Http.HttpRequestException] {
            Write-Error "GET $Uri failed (network): $($_.Exception.Message)"
            return @{ Code = -1; Body = $_.Exception.Message }
        } catch {
            Write-Error "GET $Uri failed: $($_.Exception.GetType().Name) - $($_.Exception.Message)"
            return @{ Code = -2; Body = $_.Exception.Message }
        }
    } finally {
        $client.Dispose()
    }
}

# ==================== STEP 0: server reachability check ====================
Write-Output "=== [0] server reachability check ==="
try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync("$base/api/health").Result
    Write-Output "HTTP $($pingResp.StatusCode) - server is up"
    $ping.Dispose()
} catch {
    Write-Error "Server NOT reachable at $base - please run: kill-old.ps1 + mvn -pl helloai-start spring-boot:run"
    Write-Error "Error: $($_.Exception.Message)"
    exit 1
}
Write-Output ""

# ==================== STEP A: admin login ====================
Write-Output "=== [A] admin login ==="
$loginBody = '{"type":"admin","username":"admin","credential":"admin123"}'
$loginResp = Invoke-PostJson -Uri "$base/api/auth/login" -Body $loginBody
Write-Output "HTTP $($loginResp.Code)"
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error "admin login failed: $($loginResp.Body)"
    exit 1
}
Write-Output "adminToken = $($adminToken.Substring(0, 16))..."
Write-Output ""

# ==================== STEP B: create or reuse test agent ====================
Write-Output "=== [B] create or reuse test agent (admin token) ==="
$agentName = "M4-test-executor"
$agentId = $null
$agentApiKey = $null

# B-1: 先查已存在（M4 重复跑 / 多分支测试必备的幂等性）
# 直接拉所有 agent（不传 role / page，默认 pageSize=20），客户端按 name 精确过滤
# 绕开 pageSize+role 组合某些情况下 server 返 data=null 的坑
$lookupUrl = "$base/api/admin/agents?pageSize=50"
$lookupResp = Invoke-GetJson -Uri $lookupUrl -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "lookup HTTP $($lookupResp.Code)"
Write-Output "lookup Body (前 800 字符): $($lookupResp.Body.Substring(0, [Math]::Min(800, $lookupResp.Body.Length)))"
$parsedJson = $lookupResp.Body | ConvertFrom-Json
$lookupData = $parsedJson.data
if ($lookupData -eq $null -or $lookupData.list -eq $null) {
    Write-Output "lookup data is null, will create"
    $existing = @()
} else {
    # 注意：AdminAgentController 用 list，不是 MyBatis Plus 默认的 records
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
}
if ($existing.Count -gt 0) {
    $agentId = $existing[0].id
    $agentApiKey = $existing[0].apiKey
    Write-Output "reuse existing: id=$agentId"
} else {
    # B-2: 不存在才创建
    Write-Output "not found, creating"
    $createBody = "{`"name`":`"$agentName`",`"role`":`"EXECUTOR`",`"remark`":`"M4 验证自动创建（v10 幂等版）`"}"
    $createResp = Invoke-PostJson -Uri "$base/api/admin/agents" -Body $createBody -Headers @{ "X-Admin-Token" = $adminToken }
    Write-Output "create HTTP $($createResp.Code)"
    Write-Output "create Body: $($createResp.Body)"
    $agentData = ($createResp.Body | ConvertFrom-Json).data
    $agentId = $agentData.id
    $agentApiKey = $agentData.apiKey
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Error "agent create / lookup failed (no apiKey)"
    exit 1
}
Write-Output "agentId = $agentId"
Write-Output "agentApiKey = $agentApiKey"
Write-Output ""

# ==================== STEP C: start SSE ====================
Write-Output "=== [C] start SSE long connection ==="
$job = Start-Job -ScriptBlock {
    curl.exe -i -N http://localhost:6565/mcp/sse 2>$null | Out-File -Encoding ascii "$using:scriptDir\sse-auth.txt"
}
Start-Sleep -Seconds 3
$sid = (Select-String -Path $sseFile -Pattern 'sessionId=([A-Za-z0-9-]+)' -ErrorAction SilentlyContinue).Matches.Groups[1].Value
Write-Output "sessionId = $sid"
if ([string]::IsNullOrEmpty($sid)) {
    Write-Error "sessionId extraction failed"
    Stop-Job $job -PassThru | Remove-Job -Force
    exit 1
}
Write-Output ""

# Helper: send MCP request, return [POST_code, POST_body, SSE_new_content]
function Send-Mcp {
    param([string]$Body, [string]$Label, [hashtable]$Headers = @{})
    Write-Output "=== $Label ==="
    Write-Output "Body: $Body"
    if ($Headers.Count -gt 0) {
        Write-Output "Headers: $($Headers.Keys -join ', ')"
    }
    $posBefore = (Get-Item $sseFile).Length
    $resp = Invoke-PostJson -Uri "$base/mcp/messages?sessionId=$sid" -Body $Body -Headers $Headers
    $global:lastMcpResp = $resp
    Write-Output "POST Status: $($resp.Code)"
    Write-Output "POST Body: $($resp.Body)"
    Start-Sleep -Seconds 2
    $posAfter = (Get-Item $sseFile).Length
    Write-Output "--- SSE stream new content (offset $posBefore -> $posAfter) ---"
    $newContent = ""
    if ($posAfter -gt $posBefore) {
        $reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $reader.Position = $posBefore
        $sseReader = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
        $newContent = $sseReader.ReadToEnd()
        $sseReader.Close()
        $reader.Close()
        Write-Output $newContent
    } else {
        Write-Output "(no new content)"
    }
    $global:lastSseNewContent = $newContent
    Write-Output ""
    Write-Output ""
}

# ==================== STEP D: M4 鉴权测试 ====================

# [D1] initialize (admin token) — 应该成功
Send-Mcp -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ps-m4","version":"0.0.1"}}}' -Label "[D1] initialize with admin token" -Headers @{ "X-Admin-Token" = $adminToken }

# [D2] notifications/initialized (admin token)
Send-Mcp -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label "[D2] notifications/initialized (admin token)" -Headers @{ "X-Admin-Token" = $adminToken }

# [D3] tools/call getAgentStatus 不带 token — 期待 401
Send-Mcp -Body '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":999}}}' -Label "[D3] tools/call NO TOKEN (expect 401)"
if ($global:lastMcpResp.Code -ne 401) {
    Write-Error "D3 failed: expected HTTP 401"
    exit 1
}

# [D4] tools/call getAgentStatus 带错 token — 期待 401
Send-Mcp -Body '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":999}}}' -Label "[D4] tools/call with WRONG token (expect 401)" -Headers @{ "Authorization" = "Bearer wrong-api-key-xxxxx" }
if ($global:lastMcpResp.Code -ne 401) {
    Write-Error "D4 failed: expected HTTP 401"
    exit 1
}

# [D5] tools/call getAgentStatus 带 agent apiKey + 传错 agentId=999 + _sessionId — 期待 200 + WARN（覆盖到真实 agentId=$agentId）
$d5Body = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":999,"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $d5Body -Label "[D5] tools/call with AGENT apiKey + WRONG agentId=999 + _sessionId (expect 200 + override to $agentId)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }
if ($global:lastMcpResp.Code -ne 200) {
    Write-Error "D5 failed: expected HTTP 200"
    exit 1
}
if ([string]::IsNullOrEmpty($global:lastSseNewContent)) {
    Write-Error "D5 failed: expected SSE response"
    exit 1
}
if ($global:lastSseNewContent -notmatch '"id":3') {
    Write-Error "D5 failed: SSE missing jsonrpc id=3"
    exit 1
}
if ($global:lastSseNewContent -notmatch '"isError":false') {
    Write-Error "D5 failed: expected isError=false"
    exit 1
}
$expectedAgentIdPattern = 'agentId\\":' + $agentId
if ($global:lastSseNewContent -notmatch $expectedAgentIdPattern) {
    Write-Error "D5 failed: expected agentId overridden to $agentId"
    exit 1
}

# [D6] tools/call getAgentStatus 带 admin token + 传 agentId=999 + _sessionId — 期待 200 + 覆盖到 admin 自己的 _authId (注意：admin 不是 agent，会抛 BizException)
$d6Body = '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":999,"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $d6Body -Label "[D6] tools/call with ADMIN token + agentId=999 + _sessionId (expect error: agent not found in agent table)" -Headers @{ "X-Admin-Token" = $adminToken }
if ($global:lastMcpResp.Code -ne 200) {
    Write-Error "D6 failed: expected HTTP 200"
    exit 1
}
if ([string]::IsNullOrEmpty($global:lastSseNewContent)) {
    Write-Error "D6 failed: expected SSE response"
    exit 1
}
if ($global:lastSseNewContent -notmatch '"id":4') {
    Write-Error "D6 failed: SSE missing jsonrpc id=4"
    exit 1
}
if ($global:lastSseNewContent -notmatch '"isError":true') {
    Write-Error "D6 failed: expected isError=true"
    exit 1
}

# ==================== STEP E: cleanup ====================
Write-Output "=== Cleanup ==="
Stop-Job $job -PassThru | Remove-Job -Force
Write-Output "Test agent id=$agentId still in DB (use admin UI to delete if needed)"
Write-Output "Done."
