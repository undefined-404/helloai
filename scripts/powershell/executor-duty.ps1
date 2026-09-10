# executor-duty.ps1
# HelloAI EXECUTOR duty actions for "in-conversation" passive polling mode.
# Reads baseUrl / apiKey / agentId from executor-config.json (created by executor-onboard.ps1).
#
# Usage:
#   powershell -File executor-duty.ps1 -Action checkIn
#   powershell -File executor-duty.ps1 -Action status
#   powershell -File executor-duty.ps1 -Action heartbeat
#   powershell -File executor-duty.ps1 -Action pull [-IncludeRead]
#   powershell -File executor-duty.ps1 -Action poll -Rounds 5 -IntervalSeconds 30
#   powershell -File executor-duty.ps1 -Action claim -SubTaskId 123
#   powershell -File executor-duty.ps1 -Action ack   -MessageId inbox-10001
#   powershell -File executor-duty.ps1 -Action checkOut
#
# Notes (from the platform SKILL):
#   - checkIn is the only proof of being on duty; pullTasks before checkIn returns 500.
#   - Any tool call auto-renews the ACTIVE lease, so heartbeat+pullTasks every ~30s
#     is enough to stay on duty; no manual re-checkIn needed.
#   - heartbeat is the only call that refreshes last_seen_time (offline after 5 min without it).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('checkIn', 'status', 'heartbeat', 'pull', 'poll', 'claim', 'ack', 'checkOut')]
    [string]$Action,
    [int]$Rounds = 5,
    [int]$IntervalSeconds = 30,
    [int]$Max = 20,
    [switch]$IncludeRead,
    [string]$SubTaskId,
    [string]$MessageId,
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 encoding header (repo rule 6) - avoid CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition }
if (-not $ConfigPath) { $ConfigPath = Join-Path $ScriptDir 'executor-config.json' }
if (-not (Test-Path $ConfigPath)) {
    Write-Host ('[FAIL] config not found: ' + $ConfigPath)
    Write-Host '       run executor-onboard.ps1 first (one-click registration).'
    exit 1
}
$Config = Get-Content -Raw -Encoding UTF8 $ConfigPath | ConvertFrom-Json

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(20)
$client.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Config.apiKey)

function Call-Tool($name, $arguments) {
    $payload = @{
        jsonrpc = '2.0'
        method  = 'tools/call'
        id      = 1
        params  = @{ name = $name; arguments = $arguments }
    } | ConvertTo-Json -Depth 10 -Compress
    $content = New-Object System.Net.Http.StringContent($payload, $script:Utf8NoBom, 'application/json')
    $resp = $client.PostAsync(($Config.baseUrl + '/api/mcp/jsonrpc'), $content).GetAwaiter().GetResult()
    $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = $script:Utf8NoBom.GetString($bytes)
    $json = $text | ConvertFrom-Json
    if ($json.error) { throw ('tool ' + $name + ' failed: ' + $json.error.message) }
    return $json.result
}

function Invoke-Round {
    $hb = Call-Tool 'heartbeat' @{}
    $pt = Call-Tool 'pullTasks' @{ role = 'EXECUTOR'; max = $Max; includeRead = [bool]$IncludeRead }
    $msgs = @($pt.messages)
    Write-Host ('onDuty=' + $hb.onDuty + '  remainingTtlSec=' + $hb.remainingTtlSeconds + '  unread=' + $msgs.Count)
    foreach ($m in $msgs) {
        Write-Host ('  [' + $m.messageId + '] ' + $m.type + '  subTaskId=' + $m.subTaskId + '  priority=' + $m.priority + '  reassigned=' + $m.reassigned)
        if ($m.title) { Write-Host ('      title: ' + $m.title) }
        if ($m.deadline) { Write-Host ('      deadline: ' + $m.deadline) }
    }
    return $msgs
}

switch ($Action) {
    'checkIn' {
        $skills = [string]$Config.skills
        $args = @{ workMode = 'AUTO'; maxConcurrent = [int]$Config.maxConcurrent; ttlMinutes = [int]$Config.ttlMinutes }
        if ($skills) { $args.skills = $skills }
        $r = Call-Tool 'checkIn' $args
        Write-Host '=== checkIn ==='
        Write-Host ('ok                = ' + $r.ok)
        Write-Host ('agentId           = ' + $r.agentId)
        Write-Host ('leaseId           = ' + $r.leaseId)
        Write-Host ('sessionId         = ' + $r.sessionId)
        Write-Host ('workMode          = ' + $r.workMode)
        Write-Host ('maxConcurrent     = ' + $r.maxConcurrent)
        Write-Host ('expiresAt         = ' + $r.expiresAt)
        # mergedSkills is only populated by the MCP SSE channel; the REST channels
        # (jsonrpc / tools/*) ignore the skills argument, so this stays empty there.
        Write-Host ('mergedSkills      = ' + (($r.mergedSkills) -join ','))
    }
    'status' {
        $r = Call-Tool 'getAgentStatus' @{}
        Write-Host '=== getAgentStatus ==='
        Write-Host ('status                = ' + $r.status)
        Write-Host ('dbOnlineStatus        = ' + $r.dbOnlineStatus)
        Write-Host ('computedOnlineStatus  = ' + $r.computedOnlineStatus)
        Write-Host ('lastSeenAt            = ' + $r.lastSeenAt)
        Write-Host ('serverTime            = ' + $r.serverTime)
    }
    'heartbeat' {
        $r = Call-Tool 'heartbeat' @{}
        Write-Host '=== heartbeat ==='
        Write-Host ('onDuty            = ' + $r.onDuty)
        Write-Host ('leaseExpiresAt    = ' + $r.leaseExpiresAt)
        Write-Host ('remainingTtlSec   = ' + $r.remainingTtlSeconds)
    }
    'pull' {
        $r = Call-Tool 'pullTasks' @{ role = 'EXECUTOR'; max = $Max; includeRead = [bool]$IncludeRead }
        $msgs = @($r.messages)
        Write-Host ('=== pullTasks (includeRead=' + [bool]$IncludeRead + ') ===')
        Write-Host ('count = ' + $msgs.Count)
        foreach ($m in $msgs) {
            Write-Host ('  [' + $m.messageId + '] ' + $m.type + '  subTaskId=' + $m.subTaskId + '  priority=' + $m.priority + '  reassigned=' + $m.reassigned)
            if ($m.title) { Write-Host ('      title: ' + $m.title) }
        }
    }
    'poll' {
        Write-Host ('=== duty poll: rounds=' + $Rounds + ' interval=' + $IntervalSeconds + 's ===')
        $found = @()
        for ($i = 1; $i -le $Rounds; $i++) {
            Write-Host ('--- round ' + $i + '/' + $Rounds + '  ' + (Get-Date -Format 'HH:mm:ss') + ' ---')
            # @() keeps a single message an array, so .Count is reliable
            $msgs = @(Invoke-Round)
            if ($msgs.Count -gt 0) { $found = $msgs; break }
            if ($i -lt $Rounds) { Start-Sleep -Seconds $IntervalSeconds }
        }
        Write-Host ('poll finished, new messages = ' + $found.Count)
    }
    'claim' {
        if (-not $SubTaskId) { Write-Host '[FAIL] -SubTaskId is required for claim'; exit 1 }
        $r = Call-Tool 'claimSubTask' @{ subTaskId = $SubTaskId }
        Write-Host '=== claimSubTask ==='
        Write-Host ('claimed       = ' + $r.claimed)
        Write-Host ('reason        = ' + $r.reason)
        Write-Host ('assignedAgent = ' + $r.assignedAgent)
        Write-Host ('version       = ' + $r.version)
    }
    'ack' {
        if (-not $MessageId) { Write-Host '[FAIL] -MessageId is required for ack'; exit 1 }
        $r = Call-Tool 'ack' @{ messageId = $MessageId }
        Write-Host '=== ack ==='
        Write-Host ('acknowledged = ' + $r.acknowledged)
        Write-Host ('messageId    = ' + $r.messageId)
    }
    'checkOut' {
        $r = Call-Tool 'checkOut' @{ closeReason = 'session_end' }
        Write-Host '=== checkOut ==='
        Write-Host ('ok            = ' + $r.ok)
        Write-Host ('closedCount   = ' + $r.closedCount)
        Write-Host ('currentStatus = ' + $r.currentStatus)
    }
}

$client.Dispose()
