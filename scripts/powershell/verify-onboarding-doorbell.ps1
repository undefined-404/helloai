# verify-onboarding-doorbell.ps1
# Step 2 of the external-agent end-to-end plan: prove the REAL onboarding path
#   register -> MCP checkIn (self-service apiKey, NO db seed) -> doorbell SSE connected.
# This complements verify-doorbell-e2e.ps1 (which DB-inserts a lease); here we prove an
# external AI can actually punch in via MCP after the DEFAULT_EXECUTOR_TOOLS fix.
#
#   S0 POST /api/agents/register                 -> apiKey + agentId (self-service)
#   S1 GET  /api/agents/doorbell/sse (no checkIn) -> rejected HTTP 500, code=500 (off-duty gate)
#   S2 MCP  tools/call checkIn (Bearer apiKey)    -> ACTIVE lease created (ok=true)
#   S3 GET  /api/agents/doorbell/sse (on-duty)    -> HTTP 200 + event:connected handshake
#
# Prereq: backend up @ BaseUrl with PostgreSQL + Redis (checkIn refreshes heartbeat),
#         helloai.doorbell.enabled=true, MCP endpoints /mcp/sse + /mcp/messages enabled.
#         curl.exe available (Windows 10+ ships it).
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-onboarding-doorbell.ps1 [http://localhost:6565]
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$base = if ($args.Count -ge 1) { $args[0] } else { 'http://localhost:6565' }
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$mcpSseFile = Join-Path $scriptDir 'sse-onboard-doorbell-mcp.txt'
$dbSseFile  = Join-Path $scriptDir 'sse-onboard-doorbell.txt'
# unique ASCII name to avoid duplicate-name rejection on re-run
$agentName = 'e2e-onboard-doorbell-' + (Get-Date -Format 'yyyyMMddHHmmss')

Remove-Item $mcpSseFile -ErrorAction SilentlyContinue
Remove-Item $dbSseFile -ErrorAction SilentlyContinue

$pass = 0
$fail = 0
function Assert($cond, $label) {
    if ($cond) {
        Write-Host ('[PASS] ' + $label) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ('[FAIL] ' + $label) -ForegroundColor Red
        $script:fail++
    }
}

# HTTP JSON helper (StringContent UTF-8, PS 5.1 safe)
function Invoke-Json {
    param(
        [ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 15
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    foreach ($k in $Headers.Keys) { $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null }
    $content = $null
    if ($Method -ne 'GET' -and $Method -ne 'DELETE') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if ($Method -eq 'GET')      { $resp = $client.GetAsync($Uri).Result }
        elseif ($Method -eq 'POST') { $resp = $client.PostAsync($Uri, $content).Result }
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

# start MCP SSE long connection (background curl), return job + extracted sessionId
function Start-McpSse {
    param([string]$AbsFile, [string]$ApiKey, [string]$BaseUrl)
    $job = Start-Job -ScriptBlock {
        param($f, $key, $u)
        & curl.exe -s -i -N -H ('Authorization: Bearer ' + $key) ($u + '/mcp/sse') *>&1 |
            Out-File -Encoding utf8 -FilePath $f
    } -ArgumentList $AbsFile, $ApiKey, $BaseUrl
    Start-Sleep -Seconds 3
    $content = ''
    if (Test-Path $AbsFile) { $content = Get-Content $AbsFile -Raw -ErrorAction SilentlyContinue }
    $m = [regex]::Match($content, 'sessionId=([A-Za-z0-9-]+)')
    $sid = if ($m.Success) { $m.Groups[1].Value } else { '' }
    return @{ Job = $job; SessionId = $sid }
}

# start doorbell SSE long connection (background curl)
function Start-DoorbellSse {
    param([string]$AbsFile, [string]$ApiKey, [string]$BaseUrl)
    return Start-Job -ScriptBlock {
        param($f, $key, $u)
        & curl.exe -s -i -N -H ('Authorization: Bearer ' + $key) ($u + '/api/agents/doorbell/sse') *>&1 |
            Out-File -Encoding utf8 -FilePath $f
    } -ArgumentList $AbsFile, $ApiKey, $BaseUrl
}

Write-Host ('=== verify-onboarding-doorbell against ' + $base + ' ===')
Write-Host ('agentName = ' + $agentName)

# ---- S0: self-registration ----
$apiKey = $null
$agentId = $null
try {
    $regBody = @{ name = $agentName; role = 'EXECUTOR'; description = 'e2e onboarding+doorbell verify agent' } | ConvertTo-Json
    $reg = Invoke-Json -Method POST -Uri ($base + '/api/agents/register') -Body $regBody
    $regJson = $reg.Body | ConvertFrom-Json
    Assert ($regJson.code -eq 200) 'S0 register returns code 200'
    $apiKey = $regJson.data.apiKey
    $agentId = $regJson.data.id
    Assert ([string]::IsNullOrWhiteSpace($apiKey) -eq $false) 'S0 apiKey present'
    Assert ($null -ne $agentId) 'S0 agentId present'
    Write-Host ('       agentId=' + $agentId + '  apiKey=' + $apiKey.Substring(0, [Math]::Min(12, $apiKey.Length)) + '...')
} catch {
    Assert $false ('S0 register threw: ' + $_.Exception.Message)
}

# ---- S1: off-duty doorbell connect must be rejected ----
if ($apiKey) {
    $s1 = Invoke-Json -Method GET -Uri ($base + '/api/agents/doorbell/sse') -Headers @{ Authorization = ('Bearer ' + $apiKey) } -TimeoutSec 8
    Write-Host ('       S1 HTTP=' + $s1.Code + ' body=' + $s1.Body)
    Assert ($s1.Code -eq 500 -and $s1.Body -match '"code"\s*:\s*500') 'S1 off-duty doorbell connect rejected (code 500)'
}

# ---- S2: MCP checkIn using the fresh self-service apiKey (NO db seed) ----
$checkedIn = $false
if ($apiKey) {
    $mcp = Start-McpSse -AbsFile $mcpSseFile -ApiKey $apiKey -BaseUrl $base
    $sid = $mcp.SessionId
    if ([string]::IsNullOrEmpty($sid)) {
        Assert $false 'S2 MCP sessionId extraction (see sse-onboard-doorbell-mcp.txt)'
    } else {
        Write-Host ('       MCP sessionId = ' + $sid)
        $auth = @{ Authorization = ('Bearer ' + $apiKey) }
        $msgUri = $base + '/mcp/messages?sessionId=' + $sid
        # MCP handshake
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"onboard-doorbell-e2e","version":"1.0"}}}' | Out-Null
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' | Out-Null
        Start-Sleep -Seconds 1
        # tools/call checkIn (agentId is overridden by server-side auth id anyway)
        $ciBody = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"NORMAL","maxConcurrent":1,"ttlMinutes":5,"sessionId":"' + $sid + '"}}}'
        $ci = Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body $ciBody
        Write-Host ('       S2 checkIn POST HTTP=' + $ci.Code)
        Start-Sleep -Seconds 2
        # checkIn result streams back on the SSE channel; read it and verify a success payload.
        # success payload carries leaseId / ok:true; if checkIn were blocked by tool-authz the
        # server returns a BizException error frame (no leaseId) -> this assert fails, inspect the log.
        $mcpContent = ''
        if (Test-Path $mcpSseFile) { $mcpContent = Get-Content $mcpSseFile -Raw -ErrorAction SilentlyContinue }
        $checkedIn = ($mcpContent -match 'leaseId' -or $mcpContent -match '"ok"\s*:\s*true')
        Assert $checkedIn 'S2 checkIn succeeded via MCP (default authz enables checkIn; ACTIVE lease established)'
    }
    if ($mcp -and $mcp.Job) {
        Stop-Job $mcp.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    }
}

# ---- S3: on-duty doorbell connect receives connected handshake ----
if ($checkedIn) {
    $dbJob = Start-DoorbellSse -AbsFile $dbSseFile -ApiKey $apiKey -BaseUrl $base
    Start-Sleep -Seconds 4
    $dbContent = ''
    if (Test-Path $dbSseFile) { $dbContent = Get-Content $dbSseFile -Raw -ErrorAction SilentlyContinue }
    Write-Host '--- doorbell SSE first frames ---'
    Write-Host $dbContent
    Assert ($dbContent -match 'HTTP/1\.1 200') 'S3 doorbell connect returned HTTP 200'
    Assert ($dbContent -match 'event:connected' -and $dbContent -match '"type"\s*:\s*"connected"') 'S3 connected handshake received'
    Stop-Job $dbJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
} else {
    Write-Host '[SKIP] S3 doorbell connect (S2 checkIn did not succeed)' -ForegroundColor Yellow
}

Write-Host ''
Write-Host ('=== RESULT: PASS=' + $pass + ' FAIL=' + $fail + ' ===')
if ($fail -eq 0) {
    Write-Host 'ALL PASSED: register -> MCP checkIn -> doorbell connected (real onboarding path)' -ForegroundColor Green
    exit 0
} else {
    Write-Host ('SOME CHECKS FAILED (see above; MCP SSE log: ' + $mcpSseFile + ')') -ForegroundColor Red
    exit 1
}
