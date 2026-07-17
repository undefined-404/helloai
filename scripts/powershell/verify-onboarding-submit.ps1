# verify-onboarding-submit.ps1
# Step 5 (final) of the external-agent end-to-end plan: prove the full loop closes with submitResult.
#   register -> MCP checkIn (on-duty) -> get an ASSIGNED sub_task -> submitResult(success=true)
#   -> ExecutionResultHandler applies it -> sub_task flows IN_PROGRESS -> REVIEW (accepted).
# submitResult requires the sub_task to be ASSIGNED (auto start -> IN_PROGRESS) or already IN_PROGRESS;
# on success it calls subTaskService.submit() which moves the sub_task to REVIEW.
#
#   S0 POST /api/agents/register                        -> apiKey + agentId (self-service)
#   S1 MCP tools/call checkIn (Bearer apiKey)           -> ACTIVE lease (on-duty)
#   S2 POST /api/tasks + POST /api/sub-tasks(assigned)  -> ASSIGNED sub_task owned by this agent
#   S3 POST /api/mcp/tools/submitResult (success=true)  -> ok:true + accepted:true + status:applied
#   S4 GET  /api/sub-tasks/{id}                         -> status == REVIEW (loop closed)
#
# Prereq: backend up @ BaseUrl with PostgreSQL + Redis, helloai.doorbell.enabled=true,
#         MCP endpoints /mcp/sse + /mcp/messages enabled, curl.exe available.
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-onboarding-submit.ps1 [http://localhost:6565]
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$base = if ($args.Count -ge 1) { $args[0] } else { 'http://localhost:6565' }
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$mcpSseFile = Join-Path $scriptDir 'sse-onboard-submit-mcp.txt'
# unique ASCII name to avoid duplicate-name rejection on re-run
$agentName = 'e2e-onboard-submit-' + (Get-Date -Format 'yyyyMMddHHmmss')

Remove-Item $mcpSseFile -ErrorAction SilentlyContinue

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

Write-Host ('=== verify-onboarding-submit against ' + $base + ' ===')
Write-Host ('agentName = ' + $agentName)

# ---- S0: self-registration ----
$apiKey = $null
$agentId = $null
try {
    $regBody = @{ name = $agentName; role = 'EXECUTOR'; description = 'e2e onboarding submit verify agent' } | ConvertTo-Json
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

# ---- S1: MCP checkIn (go on-duty; checkIn is only exposed on the MCP SSE channel) ----
$checkedIn = $false
$mcp = $null
if ($apiKey) {
    $mcp = Start-McpSse -AbsFile $mcpSseFile -ApiKey $apiKey -BaseUrl $base
    $sid = $mcp.SessionId
    if ([string]::IsNullOrEmpty($sid)) {
        Assert $false 'S1 MCP sessionId extraction (see sse-onboard-submit-mcp.txt)'
    } else {
        Write-Host ('       MCP sessionId = ' + $sid)
        $msgUri = $base + '/mcp/messages?sessionId=' + $sid
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"onboard-submit-e2e","version":"1.0"}}}' | Out-Null
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

# ---- S2: create an ASSIGNED sub_task owned by this agent ----
$subTaskId = $null
if ($checkedIn) {
    $taskBody = @{ title = ('e2e-submit-task-' + (Get-Date -Format 'HHmmss')); description = 'e2e onboarding submit verify task' } | ConvertTo-Json
    $taskResp = Invoke-Json -Method POST -Uri ($base + '/api/tasks') -Headers $auth -Body $taskBody
    $taskId = $null
    try { $taskId = ($taskResp.Body | ConvertFrom-Json).data.id } catch { }
    Assert ($null -ne $taskId) 'S2 task created (parent for sub_task)'
    Write-Host ('       taskId=' + $taskId)

    if ($taskId) {
        # assignedAgent set -> create() -> claim() -> changeStatus(ASSIGNED)
        $stBody = @{ taskId = $taskId; title = ('e2e-submit-subtask-' + (Get-Date -Format 'HHmmss')); description = 'assigned to the onboarding agent'; deliverable = 'proof of submitResult loop'; assignedAgent = $agentId } | ConvertTo-Json
        $stResp = Invoke-Json -Method POST -Uri ($base + '/api/sub-tasks') -Headers $auth -Body $stBody
        $stJson = $null
        try { $stJson = $stResp.Body | ConvertFrom-Json } catch { }
        $subTaskId = if ($stJson) { $stJson.data.id } else { $null }
        Assert ($null -ne $subTaskId) 'S2 sub_task created and assigned'
        Assert ($stJson.data.status -eq 'ASSIGNED') 'S2 sub_task initial status is ASSIGNED'
        Write-Host ('       subTaskId=' + $subTaskId + '  status=' + $stJson.data.status)
    }
}

# ---- S3: external AI submits the result (success) -> accepted + applied ----
$submitted = $false
if ($subTaskId) {
    $resultId = 'e2e-submit-' + (Get-Date -Format 'yyyyMMddHHmmss')
    $srBody = @{ subTaskId = $subTaskId; success = $true; output = 'e2e onboarding submit output'; finishReason = 'completed'; resultId = $resultId } | ConvertTo-Json
    $srResp = Invoke-Json -Method POST -Uri ($base + '/api/mcp/tools/submitResult') -Headers $auth -Body $srBody
    Assert ($srResp.Code -eq 200) 'S3 submitResult REST returned HTTP 200'
    $srJson = $null
    try { $srJson = $srResp.Body | ConvertFrom-Json } catch { }
    Write-Host ('       submitResult body = ' + $srResp.Body)
    $submitted = ($null -ne $srJson -and $srJson.code -eq 200 -and $srJson.data.ok -eq $true -and $srJson.data.accepted -eq $true)
    Assert $submitted 'S3 submitResult ok:true and accepted:true'
    Assert ($srJson.data.status -eq 'applied') 'S3 submitResult status is applied'
} else {
    Write-Host '[SKIP] S3 submitResult (no sub_task created)' -ForegroundColor Yellow
}

# ---- S4: the sub_task flowed to REVIEW (success submit -> review), loop closed ----
if ($submitted) {
    Start-Sleep -Seconds 1
    $getResp = Invoke-Json -Method GET -Uri ($base + '/api/sub-tasks/' + $subTaskId) -Headers $auth
    $getJson = $null
    try { $getJson = $getResp.Body | ConvertFrom-Json } catch { }
    Write-Host ('       sub_task after submit = ' + $getResp.Body)
    Assert ($null -ne $getJson -and $getJson.code -eq 200) 'S4 sub_task fetch returned code 200'
    Assert ($getJson.data.status -eq 'REVIEW') 'S4 sub_task status flowed to REVIEW (submitResult loop closed)'
} else {
    Write-Host '[SKIP] S4 status check (submitResult not accepted)' -ForegroundColor Yellow
}

# ---- cleanup background job ----
if ($mcp -and $mcp.Job) { Stop-Job $mcp.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host ('=== RESULT: PASS=' + $pass + ' FAIL=' + $fail + ' ===')
if ($fail -eq 0) {
    Write-Host 'ALL PASSED: register -> checkIn -> assigned -> submitResult -> REVIEW (full loop closed)' -ForegroundColor Green
    exit 0
} else {
    Write-Host ('SOME CHECKS FAILED (MCP log: ' + $mcpSseFile + ')') -ForegroundColor Red
    exit 1
}
