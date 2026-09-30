# pull_tasks.ps1 — HelloAI Executor pull inbox and print
# Usage:
#   powershell -File pull_tasks.ps1                # unread only
#   powershell -File pull_tasks.ps1 -IncludeRead    # include read messages

[CmdletBinding()]
param(
    [switch]$IncludeRead,
    [int]$Max = 20
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
$Config = Get-Content -Raw -Encoding UTF8 (Join-Path $ScriptDir 'config.json') | ConvertFrom-Json

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Config.apiKey)

$payload = @{
    jsonrpc = '2.0'
    method  = 'tools/call'
    id      = 1
    params  = @{ name = 'pullTasks'; arguments = @{ role = 'EXECUTOR'; max = $Max; includeRead = [bool]$IncludeRead } }
} | ConvertTo-Json -Depth 8 -Compress

$content = New-Object System.Net.Http.StringContent($payload, $script:Utf8NoBom, 'application/json')
$resp = $client.PostAsync(($Config.baseUrl + '/api/mcp/jsonrpc'), $content).GetAwaiter().GetResult()
$bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
$text = $script:Utf8NoBom.GetString($bytes)
$client.Dispose()

$j = $text | ConvertFrom-Json
if ($j.error) {
    Write-Host ('ERROR: ' + $j.error.message)
    exit 1
}
$msgs = $j.result.messages
Write-Host ('=== inbox (includeRead=' + [bool]$IncludeRead + ') ===')
Write-Host ('count = ' + $msgs.Count)
foreach ($m in $msgs) {
    Write-Host ('  [' + $m.messageId + '] ' + $m.type + '  subTaskId=' + $m.subTaskId + '  priority=' + $m.priority)
}
if ($msgs.Count -gt 0) {
    Write-Host ''
    Write-Host ('first messageId: ' + $msgs[0].messageId)
    Write-Host ('first subTaskId: ' + $msgs[0].subTaskId)
}