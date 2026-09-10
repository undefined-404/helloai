# executor-api.ps1 - REST business endpoints + generic MCP tool escape hatch.
# Companion to executor-duty.ps1 (duty loop) for the parts duty actions do not cover.
#
# Usage:
#   powershell -File executor-api.ps1 -Action mine
#   powershell -File executor-api.ps1 -Action available
#   powershell -File executor-api.ps1 -Action task -TaskId 123
#   powershell -File executor-api.ps1 -Action start  -SubTaskId 123
#   powershell -File executor-api.ps1 -Action upload -SubTaskId 123 -File out.md
#   powershell -File executor-api.ps1 -Action submit -SubTaskId 123 -OutputFile out.md -Success
#   powershell -File executor-api.ps1 -Action raw -Path /api/sub-tasks/listMine?agentId=1
#   powershell -File executor-api.ps1 -Action tool -Name getAgentStatus -ArgumentsJson '{}'
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('mine','available','task','start','upload','submit','raw','tool')]
    [string]$Action,
    [string]$SubTaskId,
    [string]$TaskId,
    [string]$File,
    [string]$OutputFile,
    [string]$MimeType = 'text/markdown',
    [string]$Path,
    [string]$Name,
    [string]$ArgumentsJson = '{}',
    [string]$ResultId,
    [switch]$Success,
    [switch]$Quiet,
    [string]$ConfigPath
)
$ErrorActionPreference = 'Stop'

$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = $utf8

$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition }
if (-not $ConfigPath) { $ConfigPath = Join-Path $ScriptDir 'executor-config.json' }
$Config = Get-Content -Raw -Encoding UTF8 $ConfigPath | ConvertFrom-Json

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Config.apiKey)

function Read-Body($resp) {
    $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    return $utf8.GetString($bytes)
}

function Get-Raw([string]$p) {
    $resp = $client.GetAsync($Config.baseUrl + $p).GetAwaiter().GetResult()
    $body = Read-Body $resp
    if (-not $Quiet) { Write-Output ('HTTP ' + [int]$resp.StatusCode) }
    return ($body | ConvertFrom-Json)
}

function Post-Tool([string]$tool, $arguments) {
    $payload = @{
        jsonrpc = '2.0'; method = 'tools/call'; id = 1
        params  = @{ name = $tool; arguments = $arguments }
    }
    $json = $payload | ConvertTo-Json -Depth 10 -Compress
    $content = New-Object System.Net.Http.StringContent($json, $utf8, 'application/json')
    $resp = $client.PostAsync($Config.baseUrl + '/api/mcp/jsonrpc', $content).GetAwaiter().GetResult()
    $text = Read-Body $resp
    $parsed = $text | ConvertFrom-Json
    if ($parsed.error) { throw ('tool ' + $tool + ' failed: ' + $parsed.error.message) }
    return $parsed.result
}

try {
    switch ($Action) {
        'mine' {
            $r = Get-Raw ('/api/sub-tasks/listMine?agentId=' + $Config.agentId)
            Write-Output ('=== listMine: ' + @($r.data).Count + ' ===')
            foreach ($s in $r.data) {
                Write-Output ('  id=' + $s.id + '  status=' + $s.status + '  title=' + $s.title)
            }
        }
        'available' {
            $r = Get-Raw '/api/sub-tasks/listAvailable'
            Write-Output ('=== listAvailable: ' + @($r.data).Count + ' ===')
            foreach ($s in $r.data) {
                Write-Output ('  id=' + $s.id + '  status=' + $s.status + '  title=' + $s.title)
            }
        }
        'task' {
            $r = Get-Raw ('/api/sub-tasks/list?taskId=' + $TaskId)
            Write-Output ('=== task ' + $TaskId + ': ' + @($r.data).Count + ' subtasks ===')
            foreach ($s in $r.data) {
                Write-Output ('  id=' + $s.id + '  status=' + $s.status + '  deps=' + (@($s.dependsOn) -join ',') + '  title=' + $s.title)
            }
        }
        'start' {
            if (-not $SubTaskId) { throw '-SubTaskId is required' }
            $empty = New-Object System.Net.Http.StringContent('', $utf8, 'application/json')
            $resp = $client.PostAsync(($Config.baseUrl + '/api/sub-tasks/startById/' + $SubTaskId), $empty).GetAwaiter().GetResult()
            $body = Read-Body $resp
            Write-Output ('HTTP ' + [int]$resp.StatusCode)
            $j = $body | ConvertFrom-Json
            Write-Output ('code=' + $j.code + ' msg=' + $j.msg + ' status=' + $j.data.status)
        }
        'upload' {
            if (-not $SubTaskId -or -not $File) { throw '-SubTaskId and -File are required' }
            $mp = New-Object System.Net.Http.MultipartFormDataContent
            $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $File).Path)
            $bc = New-Object System.Net.Http.ByteArrayContent(, $bytes)
            $bc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($MimeType)
            $mp.Add($bc, 'file', [System.IO.Path]::GetFileName($File))
            $mp.Add((New-Object System.Net.Http.StringContent($SubTaskId)), 'subTaskId')
            $mp.Add((New-Object System.Net.Http.StringContent($MimeType)), 'mimeType')
            $resp = $client.PostAsync(($Config.baseUrl + '/api/artifacts/upload'), $mp).GetAwaiter().GetResult()
            Write-Output ('HTTP ' + [int]$resp.StatusCode)
            Write-Output (Read-Body $resp)
        }
        'submit' {
            if (-not $SubTaskId) { throw '-SubTaskId is required' }
            if (-not $ResultId) { $ResultId = 'subTask-' + $SubTaskId + '-r1' }
            $out = ''
            if ($OutputFile) { $out = [System.IO.File]::ReadAllText((Resolve-Path $OutputFile).Path, $utf8) }
            $sid = $SubTaskId
            if ($SubTaskId -match '^[0-9]+$') { $sid = [long]$SubTaskId }
            $r = Post-Tool 'submitResult' @{
                subTaskId    = $sid
                resultId     = $ResultId
                success      = [bool]$Success
                output       = $out
                finishReason = 'completed'
            }
            Write-Output '=== submitResult ==='
            $r | ConvertTo-Json -Depth 6
        }
        'raw' {
            $r = Get-Raw $Path
            $r | ConvertTo-Json -Depth 8
        }
        'tool' {
            $r = Post-Tool $Name ($ArgumentsJson | ConvertFrom-Json)
            $r | ConvertTo-Json -Depth 8
        }
    }
} finally {
    $client.Dispose()
}