# outer-trae-daemon.ps1
# HelloAI EXECUTOR outer_trae_executor (2084920580641759234)
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\powershell\outer-trae-daemon.ps1
# UTF-8 encoding header
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom
$global:ApiKey  = 'ak_1aac084f923083903d772c22b350acc6'
$global:BaseUrl = 'http://localhost:6565'
$global:AgentId = '2084920580641759234'
$global:RepoRoot   = 'E:\yhzx\1027\helloai'
$global:McpSseLog  = Join-Path $global:RepoRoot 'outer-trae-daemon-mcp.log'
$global:DoorbellLog = Join-Path $global:RepoRoot 'outer-trae-daemon-doorbell.log'
$global:McpJob = $null
$global:DoorbellJob = $null
$global:ShouldExit = $false
$global:CurrentSid = $null
$global:LeaseExpiresAt = $null
$global:HeartbeatIntervalSec = 30
$global:PollIntervalSec = 30
$global:LeaseMinutes = 30
$global:RenewBeforeExpirySec = 60

Add-Type -AssemblyName System.Net.Http

function Write-Log {
    param([string]$Msg)
    $ts = (Get-Date).ToString('HH:mm:ss')
    $line = '[' + $ts + '] ' + $Msg
    Write-Host $line
}

function Get-McHttpClient {
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $global:ApiKey)
    return $client
}

function Send-Mcp {
    param(
        [string]$Body,
        [string]$Label,
        [int]$WaitSeconds = 2
    )
    if ($global:ShouldExit) { return $null }
    Write-Log ('>>> ' + $Label)
    try {
        $client = Get-McHttpClient
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
        $uri = $global:BaseUrl + '/mcp/messages?sessionId=' + $global:CurrentSid
        $response = $client.PostAsync($uri, $content).Result
        $respBody = $response.Content.ReadAsStringAsync().Result
        Write-Log ('    POST Status=' + $response.StatusCode)
        if (-not [string]::IsNullOrEmpty($respBody)) {
            Write-Log ('    POST Body=' + $respBody)
        }
        Start-Sleep -Seconds $WaitSeconds
        Write-Output $respBody
    } catch {
        Write-Log ('    POST EXCEPTION: ' + $_.Exception.Message)
        Write-Output $null
    }
}

function Start-McpSse {
    Write-Log '=== Start /mcp/sse ==='
    if (Test-Path $global:McpSseLog) { Remove-Item $global:McpSseLog -Force }
    $apiKey = $global:ApiKey
    $url    = $global:BaseUrl + '/mcp/sse'
    $logPath = $global:McpSseLog
    $global:McpJob = Start-Job -ScriptBlock {
        & curl.exe -i -N -H ('Authorization: Bearer ' + $using:apiKey) $using:url 2>$null |
            Out-File -Encoding ascii $using:logPath
    }
    Write-Log ('    McpJob.Id=' + $global:McpJob.Id)
    Start-Sleep -Seconds 8
    $sid = $null
    if (Test-Path $logPath) {
        $sidLine = Select-String -Path $logPath -Pattern 'sessionId=([A-Za-z0-9-]+)' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($sidLine) { $sid = $sidLine.Matches.Groups[1].Value }
    }
    Write-Log ('    sessionId=' + $sid)
    return $sid
}

function Stop-McpSse {
    if ($global:McpJob) {
        Write-Log '    Stop /mcp/sse Job'
        try { Stop-Job $global:McpJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue } catch {}
        $global:McpJob = $null
    }
}

function Start-DoorbellSse {
    param([string]$Sid)
    Write-Log '=== Start doorbell SSE ==='
    if (Test-Path $global:DoorbellLog) { Remove-Item $global:DoorbellLog -Force }
    $apiKey = $global:ApiKey
    $url    = $global:BaseUrl + '/api/agents/doorbell/sse?sessionId=' + $Sid
    $logPath = $global:DoorbellLog
    $global:DoorbellJob = Start-Job -ScriptBlock {
        & curl.exe -i -N -H ('Authorization: Bearer ' + $using:apiKey) $using:url 2>$null |
            Out-File -Encoding ascii $using:logPath -Append
    }
    Write-Log ('    DoorbellJob.Id=' + $global:DoorbellJob.Id)
    Start-Sleep -Seconds 2
}

function Stop-DoorbellSse {
    if ($global:DoorbellJob) {
        Write-Log '    Stop doorbell Job'
        try { Stop-Job $global:DoorbellJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue } catch {}
        $global:DoorbellJob = $null
    }
}

function Read-DoorbellDelta {
    if (-not (Test-Path $global:DoorbellLog)) { return @() }
    $markerFile = $global:DoorbellLog + '.marker'
    if (-not (Test-Path $markerFile)) { Set-Content -Path $markerFile -Value '0' -Encoding UTF8 }
    $lastPos = [long](Get-Content $markerFile -Encoding UTF8 -Raw)
    $curPos = (Get-Item $global:DoorbellLog).Length
    if ($curPos -le $lastPos) { return @() }
    $reader = [System.IO.File]::Open($global:DoorbellLog, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $reader.Position = $lastPos
    $sr = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
    $newContent = $sr.ReadToEnd()
    $sr.Close(); $reader.Close()
    Set-Content -Path $markerFile -Value ([string]$curPos) -Encoding UTF8 -NoNewline
    $events = @()
    $lines = $newContent -split "`r?`n"
    $i = 0
    while ($i -lt $lines.Length) {
        $line = $lines[$i]
        if ($line -match '^event:(\S+)$') {
            $evt = $matches[1]
            $dataLine = if ($i + 1 -lt $lines.Length) { $lines[$i + 1] } else { '' }
            if ($dataLine -match '^data:(.+)$') {
                $events += [pscustomobject]@{ Event = $evt; Data = $matches[1].Trim() }
                $i += 2
                continue
            }
        }
        $i += 1
    }
    return $events
}

function Initialize-Mcp {
    param([string]$Sid)
    Send-Mcp -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"outer-trae-daemon","version":"1.0.0"}}}' -Label 'initialize' -WaitSeconds 3
    Send-Mcp -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label 'initialized' -WaitSeconds 1
}

function Invoke-CheckIn {
    Write-Log '=== MCP tools/call checkIn ==='
    $body = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $global:AgentId + ',"workMode":"AUTO","maxConcurrent":3,"ttlMinutes":' + $global:LeaseMinutes + ',"sessionId":"' + $global:CurrentSid + '"}}}'
    $resp = Send-Mcp -Body $body -Label 'checkIn' -WaitSeconds 3
    Start-Sleep -Seconds 2
    $sseNew = Read-DoorbellDelta
    $checkinSse = $null
    foreach ($e in $sseNew) { if ($e.Event -eq 'message' -and $e.Data -match 'checkIn') { $checkinSse = $e.Data; break } }
    if (-not $checkinSse) {
        Write-Log '    WARN: checkIn response not found in SSE stream, assume ok'
        $global:LeaseExpiresAt = (Get-Date).AddMinutes($global:LeaseMinutes)
        return $true
    }
    if ($checkinSse -match '"ok"\s*:\s*true' -and $checkinSse -match '"expiresAt"\s*:\s*"([^"]+)"') {
        $expStr = $matches[1]
        try { $global:LeaseExpiresAt = [DateTime]::Parse($expStr) } catch { $global:LeaseExpiresAt = (Get-Date).AddMinutes($global:LeaseMinutes) }
        Write-Log ('    checkIn OK, lease expires at ' + $global:LeaseExpiresAt.ToString('HH:mm:ss'))
        return $true
    }
    Write-Log '    checkIn response not OK: ' + $checkinSse
    return $false
}

function Invoke-CheckOut {
    Write-Log '=== MCP tools/call checkOut ==='
    $body = '{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $global:AgentId + ',"sessionId":"' + $global:CurrentSid + '"}}}'
    Send-Mcp -Body $body -Label 'checkOut' -WaitSeconds 2
}

function Invoke-Heartbeat {
    if ($global:ShouldExit) { return }
    $body = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"heartbeat","arguments":{"agentId":' + $global:AgentId + ',"sessionId":"' + $global:CurrentSid + '"}}}'
    Send-Mcp -Body $body -Label 'heartbeat' -WaitSeconds 1
}

function Invoke-PullTasks {
    if ($global:ShouldExit) { return }
    $body = '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"pullTasks","arguments":{"agentId":' + $global:AgentId + ',"sessionId":"' + $global:CurrentSid + '"}}}'
    $resp = Send-Mcp -Body $body -Label 'pullTasks' -WaitSeconds 1
    Start-Sleep -Seconds 2
    $events = Read-DoorbellDelta
    foreach ($e in $events) {
        if ($e.Event -eq 'inbox' -or ($e.Event -eq 'message' -and $e.Data -match 'inbox')) {
            Write-Log ('    [INBOX-EVENT] ' + $e.Data)
        }
    }
}

function Test-LeaseExpiringSoon {
    if (-not $global:LeaseExpiresAt) { return $false }
    $remaining = ($global:LeaseExpiresAt - (Get-Date)).TotalSeconds
    return ($remaining -lt $global:RenewBeforeExpirySec)
}

function Invoke-RenewLease {
    Write-Log '=== Lease expiring soon, renewing ==='
    Invoke-CheckOut
    Stop-DoorbellSse
    Stop-McpSse
    Start-Sleep -Seconds 2
    $global:CurrentSid = Start-McpSse
    if ([string]::IsNullOrEmpty($global:CurrentSid)) {
        Write-Log '    ERROR: renew failed (no sessionId)'
        return $false
    }
    Initialize-Mcp -Sid $global:CurrentSid
    Start-DoorbellSse -Sid $global:CurrentSid
    $ok = Invoke-CheckIn
    return $ok
}

# === Main ===
$global:ShouldExit = $false

Write-Log '==============================================='
Write-Log 'outer-trae-daemon starting'
Write-Log ('    AgentId=' + $global:AgentId + ' (outer_trae_executor)')
Write-Log ('    BaseUrl=' + $global:BaseUrl)
Write-Log ('    HeartbeatInterval=' + $global:HeartbeatIntervalSec + 's')
Write-Log ('    LeaseMinutes=' + $global:LeaseMinutes)
Write-Log '==============================================='

$global:CurrentSid = Start-McpSse
if ([string]::IsNullOrEmpty($global:CurrentSid)) {
    Write-Log 'FATAL: sessionId extraction failed, exiting'
    Stop-McpSse
    exit 1
}

Initialize-Mcp -Sid $global:CurrentSid
Start-DoorbellSse -Sid $global:CurrentSid
$checkInOk = Invoke-CheckIn
if (-not $checkInOk) {
    Write-Log 'FATAL: checkIn failed, exiting'
    Stop-DoorbellSse
    Stop-McpSse
    exit 1
}

Write-Log '=== Entering main loop (Ctrl+C to exit) ==='

while (-not $global:ShouldExit) {
    if (Test-LeaseExpiringSoon) {
        $renewed = Invoke-RenewLease
        if (-not $renewed) {
            Write-Log 'WARN: lease renewal failed, continue with current lease'
        }
    } else {
        Invoke-Heartbeat
        Invoke-PullTasks
        $events = Read-DoorbellDelta
        foreach ($e in $events) {
            if ($e.Event -eq 'inbox' -or ($e.Event -eq 'message' -and $e.Data -match 'inbox')) {
                Write-Log ('    [INBOX] ' + $e.Data)
            } elseif ($e.Event -match 'connect|keepalive') {
                Write-Log ('    [doorbell] event=' + $e.Event)
            }
        }
    }
    Start-Sleep -Seconds $global:HeartbeatIntervalSec
}

Write-Log '=== Exit cleanup ==='
Invoke-CheckOut
Stop-DoorbellSse
Stop-McpSse
Write-Log '=== daemon exited ==='
exit 0
