# clock.ps1 — HelloAI Executor checkIn / heartbeat / checkOut
# Usage:
#   powershell -File clock.ps1 -Action onDuty
#   powershell -File clock.ps1 -Action heartbeat
#   powershell -File clock.ps1 -Action checkOut
#
# Reads baseUrl / apiKey / agentId from config.json (copy from config.example.json first).
# On first onDuty, agentId is auto-written back to config.json.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('onDuty', 'heartbeat', 'checkOut')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 encoding header (Rule 6) — avoid Chinese garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ConfigPath = Join-Path $ScriptDir 'config.json'
if (-not (Test-Path $ConfigPath)) {
    Write-Error 'config.json not found. Copy config.example.json to config.json and fill in real values.'
    exit 1
}
$Config = Get-Content -Raw -Encoding UTF8 $ConfigPath | ConvertFrom-Json

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Config.apiKey)

function Post-Tool($name, $arguments) {
    $payload = @{ jsonrpc = '2.0'; method = 'tools/call'; id = 1; params = @{ name = $name; arguments = $arguments } } | ConvertTo-Json -Depth 10 -Compress
    $content = New-Object System.Net.Http.StringContent($payload, $script:Utf8NoBom, 'application/json')
    $resp = $client.PostAsync(($Config.baseUrl + '/api/mcp/jsonrpc'), $content).GetAwaiter().GetResult()
    $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = $script:Utf8NoBom.GetString($bytes)
    $json = $text | ConvertFrom-Json
    if ($json.error) {
        throw ('Tool ' + $name + ' failed: ' + $json.error.message)
    }
    return $json.result
}

switch ($Action) {
    'onDuty' {
        $r = Post-Tool 'checkIn' @{
            workMode      = 'AUTO'
            maxConcurrent = [int]$Config.concurrencyMax
            ttlMinutes    = [int]$Config.dutyTTL
        }
        Write-Host '=== onDuty ==='
        Write-Host ('agentId         = ' + $r.agentId)
        Write-Host ('leaseId         = ' + $r.leaseId)
        Write-Host ('sessionId       = ' + $r.sessionId)
        Write-Host ('leaseExpiresAt  = ' + $r.leaseExpiresAt)
        Write-Host ('onDuty          = ' + $r.onDuty)

        # Auto-write agentId back to config.json on first run
        if ($r.agentId -and (-not $Config.agentId)) {
            $Config.agentId = $r.agentId
            $Config | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $ConfigPath
            Write-Host '(agentId saved to config.json)'
        }
    }
    'heartbeat' {
        $args = @{}
        if ($Config.agentId) { $args.agentId = $Config.agentId }
        $r = Post-Tool 'heartbeat' $args
        Write-Host '=== heartbeat ==='
        Write-Host ('onDuty           = ' + $r.onDuty)
        Write-Host ('leaseExpiresAt   = ' + $r.leaseExpiresAt)
        Write-Host ('remainingTtlSec  = ' + $r.remainingTtlSeconds)
    }
    'checkOut' {
        $args = @{ closeReason = 'shutdown' }
        if ($Config.agentId) { $args.agentId = $Config.agentId }
        $r = Post-Tool 'checkOut' $args
        Write-Host '=== checkOut ==='
        Write-Host ('currentStatus = ' + $r.currentStatus)
        Write-Host ('closedCount   = ' + $r.closedCount)
    }
}

$client.Dispose()