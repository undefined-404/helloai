# verify-onboarding-heartbeat.ps1
# Step 4 of the external-agent end-to-end plan: prove the connection stays alive with dual heartbeat.
#   Dual heartbeat has two directions:
#     A) server -> client : DoorbellKeepaliveTask broadcasts event:keepalive every
#        helloai.doorbell.keepalive-interval-ms (default 15s) so the SSE long connection
#        survives reverse-proxy idle timeouts (connection not interrupted).
#     B) client -> server : the agent calls MCP heartbeat -> HeartbeatService.seen(agentId)
#        which refreshes last_seen_at + Redis TTL and recomputes online status.
#
#   S0 POST /api/agents/register                       -> apiKey + agentId (self-service)
#   S1 MCP tools/call checkIn (Bearer apiKey)          -> ACTIVE lease (on-duty)
#   S2 GET  /api/agents/doorbell/sse (on-duty)         -> HTTP 200 + event:connected (kept open)
#   S3 keep the doorbell open ~one keepalive cycle     -> event:keepalive received AND job still Running
#   S4 POST /api/mcp/tools/heartbeat (Bearer apiKey)   -> ok:true (client->server heartbeat)
#   S5 MCP tools/call getAgentStatus (Bearer apiKey)   -> computedOnlineStatus not OFFLINE + lastSeenAt refreshed
#
# Prereq: backend up @ BaseUrl with PostgreSQL + Redis, helloai.doorbell.enabled=true,
#         MCP endpoints /mcp/sse + /mcp/messages enabled, curl.exe available.
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-onboarding-heartbeat.ps1 [http://localhost:6565] [waitSec]
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$base = if ($args.Count -ge 1) { $args[0] } else { 'http://localhost:6565' }
# wait a bit longer than the 15s keepalive interval so at least one keepalive frame lands
$waitSec = if ($args.Count -ge 2) { [int]$args[1] } else { 20 }
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$mcpSseFile = Join-Path $scriptDir 'sse-onboard-hb-mcp.txt'
$dbSseFile  = Join-Path $scriptDir 'sse-onboard-hb-doorbell.txt'
# unique ASCII name to avoid duplicate-name rejection on re-run
$agentName = 'e2e-onboard-hb-' + (Get-Date -Format 'yyyyMMddHHmmss')

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

Write-Host ('=== verify-onboarding-heartbeat against ' + $base + ' (keepalive wait ' + $waitSec + 's) ===')
Write-Host ('agentName = ' + $agentName)

# ---- S0: self-registration ----
$apiKey = $null
$agentId = $null
try {
    $regBody = @{ name = $agentName; role = 'EXECUTOR'; description = 'e2e onboarding heartbeat verify agent' } | ConvertTo-Json
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

$auth = @{ Authorization = ('Bearer ' + $apiKey) }

# ---- S1: MCP checkIn (go on-duty so the doorbell can be opened) ----
$checkedIn = $false
$mcp = $null
if ($apiKey) {
    $mcp = Start-McpSse -AbsFile $mcpSseFile -ApiKey $apiKey -BaseUrl $base
    $sid = $mcp.SessionId
    if ([string]::IsNullOrEmpty($sid)) {
        Assert $false 'S1 MCP sessionId extraction (see sse-onboard-hb-mcp.txt)'
    } else {
        Write-Host ('       MCP sessionId = ' + $sid)
        $msgUri = $base + '/mcp/messages?sessionId=' + $sid
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"onboard-hb-e2e","version":"1.0"}}}' | Out-Null
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' | Out-Null
        Start-Sleep -Seconds 1
        $ciBody = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"NORMAL","maxConcurrent":3,"ttlMinutes":10,"sessionId":"' + $sid + '"}}}'
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body $ciBody | Out-Null
        Start-Sleep -Seconds 2
        $mcpContent = ''
        if (Test-Path $mcpSseFile) { $mcpContent = Get-Content $mcpSseFile -Raw -ErrorAction SilentlyContinue }
        $checkedIn = ($mcpContent -match 'leaseId' -or $mcpContent -match '"ok"\s*:\s*true')
        Assert $checkedIn 'S1 checkIn succeeded via MCP (ACTIVE lease established)'
    }
}

# ---- S2: open doorbell (must be on-duty) and keep the stream running ----
$dbJob = $null
if ($checkedIn) {
    $dbJob = Start-DoorbellSse -AbsFile $dbSseFile -ApiKey $apiKey -BaseUrl $base
    Start-Sleep -Seconds 3
    $dbContent = ''
    if (Test-Path $dbSseFile) { $dbContent = Get-Content $dbSseFile -Raw -ErrorAction SilentlyContinue }
    Assert ($dbContent -match 'HTTP/1\.1 200') 'S2 doorbell connect returned HTTP 200'
    Assert ($dbContent -match 'event:connected') 'S2 connected handshake received (doorbell open)'
} else {
    Write-Host '[SKIP] S2 doorbell open (S1 checkIn did not succeed)' -ForegroundColor Yellow
}

# ---- S3: direction A -> server keepalive frames + connection not interrupted ----
if ($dbJob) {
    Write-Host ('       holding the doorbell open ' + $waitSec + 's to cross a keepalive interval (default 15s)...')
    Start-Sleep -Seconds $waitSec
    $dbContent3 = ''
    if (Test-Path $dbSseFile) { $dbContent3 = Get-Content $dbSseFile -Raw -ErrorAction SilentlyContinue }
    $kaCount = ([regex]::Matches($dbContent3, 'event:keepalive')).Count
    $jobState = (Get-Job -Id $dbJob.Id).State
    Write-Host '--- doorbell SSE frames after the keepalive window ---'
    Write-Host $dbContent3
    Write-Host ('       keepalive frames = ' + $kaCount + ' ; doorbell job state = ' + $jobState)
    Assert ($kaCount -ge 1) 'S3 doorbell pushed at least one keepalive frame (server->client)'
    Assert ($jobState -eq 'Running') 'S3 doorbell connection still Running (not interrupted)'
} else {
    Write-Host '[SKIP] S3 keepalive window (doorbell not open)' -ForegroundColor Yellow
}

# ---- S4: direction B -> client heartbeat refreshes last_seen (synchronous REST) ----
$hbOk = $false
if ($checkedIn) {
    $hbResp = Invoke-Json -Method POST -Uri ($base + '/api/mcp/tools/heartbeat') -Headers $auth -Body '{}'
    Assert ($hbResp.Code -eq 200) 'S4 heartbeat REST returned HTTP 200'
    $hbJson = $null
    try { $hbJson = $hbResp.Body | ConvertFrom-Json } catch { }
    $hbOk = ($null -ne $hbJson -and $hbJson.code -eq 200 -and $hbJson.data.ok -eq $true -and ([long]$hbJson.data.agentId) -eq ([long]$agentId))
    Write-Host ('       heartbeat body = ' + $hbResp.Body)
    Assert $hbOk 'S4 heartbeat ok:true and agentId matches (client->server heartbeat)'
} else {
    Write-Host '[SKIP] S4 heartbeat (S1 checkIn did not succeed)' -ForegroundColor Yellow
}

# ---- S5: getAgentStatus confirms the heartbeat refreshed online status ----
if ($hbOk -and $mcp -and $mcp.SessionId) {
    $sid = $mcp.SessionId
    $msgUri = $base + '/mcp/messages?sessionId=' + $sid
    $gsBody = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":' + $agentId + ',"sessionId":"' + $sid + '"}}}'
    Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body $gsBody | Out-Null
    Start-Sleep -Seconds 2
    $mcpContent2 = ''
    if (Test-Path $mcpSseFile) { $mcpContent2 = Get-Content $mcpSseFile -Raw -ErrorAction SilentlyContinue }
    Write-Host '--- MCP SSE frames after getAgentStatus ---'
    Write-Host $mcpContent2
    # getAgentStatus result is a nested/escaped JSON string in the SSE frame, so field names
    # appear as \"computedOnlineStatus\":\"IDLE\". Use tolerant char classes for both forms.
    Assert ($mcpContent2 -match 'computedOnlineStatus[\\":\s]*(ONLINE|IDLE)') 'S5 computedOnlineStatus is ONLINE or IDLE (not OFFLINE)'
    # last_seen_at was just refreshed by the heartbeat, so it must be a non-null 20xx timestamp
    Assert ($mcpContent2 -match 'lastSeenAt[\\":\s]*20\d\d') 'S5 lastSeenAt refreshed by the heartbeat'
} else {
    Write-Host '[SKIP] S5 getAgentStatus (no heartbeat ok or MCP session)' -ForegroundColor Yellow
}

# ---- cleanup background jobs ----
if ($dbJob) { Stop-Job $dbJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue }
if ($mcp -and $mcp.Job) { Stop-Job $mcp.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host ('=== RESULT: PASS=' + $pass + ' FAIL=' + $fail + ' ===')
if ($fail -eq 0) {
    Write-Host 'ALL PASSED: connection kept alive by keepalive frames + heartbeat refreshed online status' -ForegroundColor Green
    exit 0
} else {
    Write-Host ('SOME CHECKS FAILED (MCP log: ' + $mcpSseFile + ' ; doorbell log: ' + $dbSseFile + ')') -ForegroundColor Red
    exit 1
}
