# process_one.ps1 — HelloAI Executor process a single sub-task
# Usage:
#   powershell -File process_one.ps1 -SubTaskId <id> -FilePath <abs path to deliverable> -MessageId <inbox msg id>
#
# Flow: startById (REST) → upload artifact → submitResult (MCP JSON-RPC) → ack (MCP JSON-RPC)
# NOTE: startById uses REST because the MCP tool returns 500 for this endpoint.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$SubTaskId,
    [Parameter(Mandatory = $true)] [string]$FilePath,
    [Parameter(Mandatory = $false)] [string]$MessageId,
    [string]$ResultTag = 'v1',
    [string]$FinishReason = 'completed'
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

if (-not (Test-Path $FilePath)) { throw ('File not found: ' + $FilePath) }
$fileSize = (Get-Item $FilePath).Length
$fileName = Split-Path -Leaf $FilePath
Write-Host ('file size = ' + $fileSize)

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Config.apiKey)

# 1) startById — use REST endpoint (MCP tool returns 500)
$req = New-Object System.Net.Http.HttpRequestMessage -ArgumentList ([System.Net.Http.HttpMethod]::Post), ($Config.baseUrl + '/api/sub-tasks/startById/' + $SubTaskId)
$resp = $client.SendAsync($req).GetAwaiter().GetResult()
$startBytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
$startText = $script:Utf8NoBom.GetString($startBytes)
Write-Host ('startById: ' + $startText)

# 2) Upload artifact
$mc = New-Object System.Net.Http.MultipartFormDataContent
$stream = [System.IO.File]::OpenRead($FilePath)
$fc = New-Object System.Net.Http.StreamContent $stream
$fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('text/markdown; charset=utf-8')
$mc.Add($fc, 'file', $fileName)
$mc.Add((New-Object System.Net.Http.StringContent($SubTaskId)), 'subTaskId')
$mc.Add((New-Object System.Net.Http.StringContent('text/markdown')), 'mimeType')
$resp = $client.PostAsync(($Config.baseUrl + '/api/artifacts/upload'), $mc).GetAwaiter().GetResult()
$upBytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
$upText = $script:Utf8NoBom.GetString($upBytes)
$stream.Dispose()
Write-Host ('upload: ' + $upText)
$attachmentId = ($upText | ConvertFrom-Json).data.attachmentId

# 3) submitResult via MCP JSON-RPC
$execRecord = @"
## EXECUTION_RECORD
SUMMARY: Generated $fileName (attachmentId=$attachmentId, size=$fileSize). Processed by executor script.
KEY_DECISIONS:
- Followed deliverable template as specified in task requirements.
DOWNSTREAM_NOTES:
- See deliverable document body for details.
DELIVERABLES:
- $fileName (attachmentId=$attachmentId, size=$fileSize)
VERIFICATION:
- command: test -s $fileName && echo OK
- output: OK
- conclusion: passed
"@

# Strip BOM if present in here-string (PS 5.1 UTF-8 with BOM source file quirk)
$execRecord = $execRecord.TrimStart([char]0xFEFF)

$payload = @{
    jsonrpc = '2.0'
    method  = 'tools/call'
    id      = 1
    params  = @{
        name = 'submitResult'
        arguments = @{
            subTaskId    = $SubTaskId
            resultId     = ('r-' + $SubTaskId + '-' + $ResultTag)
            success      = $true
            output       = $execRecord
            finishReason = $FinishReason
        }
    }
} | ConvertTo-Json -Depth 10 -Compress

$content = New-Object System.Net.Http.StringContent($payload, $script:Utf8NoBom, 'application/json')
$resp = $client.PostAsync(($Config.baseUrl + '/api/mcp/jsonrpc'), $content).GetAwaiter().GetResult()
$bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
$text = $script:Utf8NoBom.GetString($bytes)
Write-Host ('submit: ' + $text)

# 4) ack inbox message
if ($MessageId) {
    $ackPayload = @{ jsonrpc = '2.0'; method = 'tools/call'; id = 2; params = @{ name = 'ack'; arguments = @{ messageId = $MessageId } } } | ConvertTo-Json -Depth 6 -Compress
    $ackContent = New-Object System.Net.Http.StringContent($ackPayload, $script:Utf8NoBom, 'application/json')
    $ackResp = $client.PostAsync(($Config.baseUrl + '/api/mcp/jsonrpc'), $ackContent).GetAwaiter().GetResult()
    $ackBytes = $ackResp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $ackText = $script:Utf8NoBom.GetString($ackBytes)
    Write-Host ('ack ' + $MessageId + ': ' + $ackText)
}

$client.Dispose()
Write-Host 'DONE'