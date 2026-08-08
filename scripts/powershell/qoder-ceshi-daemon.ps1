# qoder-ceshi-daemon.ps1
# HelloAI EXECUTOR 常驻值班守护进程（PowerShell 5.1 兼容）
# 用法：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\powershell\qoder-ceshi-daemon.ps1
# 功能：
#   1) MCP 四步握手 + checkIn 打卡
#   2) 启动门铃 SSE 长连接（独立 curl.exe 进程，持续 keepalive + inbox 推送）
#   3) 主循环（30s 一次）：
#      - MCP heartbeat（健康证明）
#      - MCP pullTasks（门铃断时的兜底）
#      - 扫门铃日志增量（inbox 事件检测）
#      - 租约 TTL 到期前 60s 自动 checkOut + checkIn + 重连门铃
#   4) Ctrl+C / SIGINT 触发退出清理剧本：
#      - MCP checkOut
#      - kill 门铃 SSE 进程
#      - kill /mcp/sse 进程
# 退出码：
#   0 = 正常退出（Ctrl+C 或 TTL 到期自动退出）
#   1 = checkIn 失败或 sessionId 拿不到
# 编码：UTF-8 with BOM（PS 5.1 中文安全）
# 注：门铃已搁置（技术瓶颈，2026-08-07），门铃 SSE 监听逻辑仅作历史参考，值守请改用纯轮询（heartbeat + pullTasks）
# ------------------------------------------------------------
# UTF-8 编码强制头（preflight 规则 6）
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom
$global:ApiKey  = $env:HELLOAI_API_KEY
if ([string]::IsNullOrEmpty($global:ApiKey)) { $global:ApiKey = 'ak_cbf5e0d7ea0a37639f0988d7f5664013' }
$global:BaseUrl = 'http://localhost:6565'
$global:AgentId = '2078110337491955714'
$global:RepoRoot   = 'E:\yhzx\1027\helloai'
$global:McpSseLog  = Join-Path $global:RepoRoot 'qoder-ceshi-daemon-mcp.log'
$global:DoorbellLog = Join-Path $global:RepoRoot 'qoder-ceshi-daemon-doorbell.log'
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
    # Use Write-Host: 走 host 不进 pipeline，避免污染函数返回值
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
    Write-Log '=== Start /mcp/sse (Start-Job curl.exe, checkin.ps1-style) ==='
    if (Test-Path $global:McpSseLog) { Remove-Item $global:McpSseLog -Force }
    $apiKey = $global:ApiKey
    $url    = $global:BaseUrl + '/mcp/sse'
    $logPath = $global:McpSseLog
    $global:McpJob = Start-Job -ScriptBlock {
        & curl.exe -i -N -H ('Authorization: Bearer ' + $using:apiKey) $using:url 2>$null |
            Out-File -Encoding ascii $using:logPath
    }
    Write-Log ('    McpJob.Id=' + $global:McpJob.Id + ' (logPath=' + $logPath + ')')
    Start-Sleep -Seconds 8
    $sid = $null
    if (Test-Path $logPath) {
        $sidLine = Select-String -Path $logPath -Pattern 'sessionId=([A-Za-z0-9-]+)' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($sidLine) { $sid = $sidLine.Matches.Groups[1].Value }
    }
    Write-Log ('    McpSseLog size=' + (Get-Item $logPath -ErrorAction SilentlyContinue).Length + 'B sid=' + $sid)
    return $sid
}

function Stop-McpSse {
    if ($global:McpJob) {
        Write-Log '    Stop /mcp/sse Job (Stop-Job | Remove-Job)'
        try { Stop-Job $global:McpJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue } catch {}
        $global:McpJob = $null
    }
}

function Start-DoorbellSse {
    param([string]$Sid)
    Write-Log '=== Start doorbell SSE (Start-Job curl.exe, checkin.ps1-style) ==='
    if (Test-Path $global:DoorbellLog) { Remove-Item $global:DoorbellLog -Force }
    $apiKey = $global:ApiKey
    $url    = $global:BaseUrl + '/api/agents/doorbell/sse?sessionId=' + $Sid
    $logPath = $global:DoorbellLog
    $global:DoorbellJob = Start-Job -ScriptBlock {
        & curl.exe -i -N -H ('Authorization: Bearer ' + $using:apiKey) $using:url 2>$null |
            Out-File -Encoding ascii $using:logPath -Append
    }
    Write-Log ('    DoorbellJob.Id=' + $global:DoorbellJob.Id + ' (logPath=' + $logPath + ')')
    Start-Sleep -Seconds 2
}

function Stop-DoorbellSse {
    if ($global:DoorbellJob) {
        Write-Log '    Stop doorbell Job (Stop-Job | Remove-Job)'
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
    Send-Mcp -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"qoder-ceshi-daemon","version":"1.0.0"}}}' -Label 'initialize' -WaitSeconds 3
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


$global:ShouldExit = $false

Write-Log '==============================================='
Write-Log 'qoder-ceshi-daemon starting'
Write-Log ('    BaseUrl=' + $global:BaseUrl)
Write-Log ('    AgentId=' + $global:AgentId)
Write-Log ('    HeartbeatInterval=' + $global:HeartbeatIntervalSec + 's')
Write-Log ('    PollInterval=' + $global:PollIntervalSec + 's')
Write-Log ('    LeaseMinutes=' + $global:LeaseMinutes + ' (renew before ' + $global:RenewBeforeExpirySec + 's)')
Write-Log '==============================================='

$global:CurrentSid = Start-McpSse
if ([string]::IsNullOrEmpty($global:CurrentSid)) {
    Write-Log 'FATAL: sessionId extraction failed, exiting'
    Stop-McpSse
    exit 1
}
Write-Log ('    sessionId=' + $global:CurrentSid)

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