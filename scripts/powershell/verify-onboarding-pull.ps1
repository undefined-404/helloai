# verify-onboarding-pull.ps1
# Step 3 of the external-agent end-to-end plan: prove the doorbell -> pullTasks loop.
#   register -> MCP checkIn -> open doorbell -> create ASSIGNED sub_task (real inbox source)
#   -> doorbell pushes an 'inbox' signal -> external AI calls MCP pullTasks and gets the task.
# The inbox message MUST be produced through the service layer (AgentInboxService.send), which
# fires InboxMessageCreatedEvent -> DoorbellRinger -> doorbell 'inbox' signal. A raw DB insert
# would NOT ring the doorbell, so here we drive it via POST /api/tasks + POST /api/sub-tasks.
#
#   S0 POST /api/agents/register                       -> apiKey + agentId (self-service)
#   S1 MCP tools/call checkIn (Bearer apiKey)          -> ACTIVE lease (on-duty)
#   S2 GET  /api/agents/doorbell/sse (on-duty)         -> HTTP 200 + event:connected (kept open)
#   S3 POST /api/tasks + POST /api/sub-tasks(assigned) -> ASSIGNED -> sub_task.assigned inbox
#   S4 doorbell stream receives event:inbox (type=inbox, eventType=sub_task.assigned)
#   S5 MCP tools/call pullTasks (Bearer apiKey)        -> messages contains that sub_task
#
# Prereq: backend up @ BaseUrl with PostgreSQL + Redis, helloai.doorbell.enabled=true,
#         MCP endpoints /mcp/sse + /mcp/messages enabled, curl.exe available.
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-onboarding-pull.ps1 [http://localhost:6565]
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$base = if ($args.Count -ge 1) { $args[0] } else { 'http://localhost:6565' }
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$mcpSseFile = Join-Path $scriptDir 'sse-onboard-pull-mcp.txt'
$dbSseFile  = Join-Path $scriptDir 'sse-onboard-pull-doorbell.txt'
# unique ASCII name to avoid duplicate-name rejection on re-run
$agentName = 'e2e-onboard-pull-' + (Get-Date -Format 'yyyyMMddHHmmss')

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

Write-Host ('=== verify-onboarding-pull against ' + $base + ' ===')
Write-Host ('agentName = ' + $agentName)

# ---- S0: self-registration ----
$apiKey = $null
$agentId = $null
try {
    $regBody = @{ name = $agentName; role = 'EXECUTOR'; description = 'e2e onboarding pull verify agent' } | ConvertTo-Json
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
        Assert $false 'S1 MCP sessionId extraction (see sse-onboard-pull-mcp.txt)'
    } else {
        Write-Host ('       MCP sessionId = ' + $sid)
        $msgUri = $base + '/mcp/messages?sessionId=' + $sid
        Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"onboard-pull-e2e","version":"1.0"}}}' | Out-Null
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

# ---- S3: create an ASSIGNED sub_task -> real inbox source (sub_task.assigned) ----
$subTaskId = $null
if ($checkedIn) {
    # a task is required by CreateSubTaskRequest.taskId (@NotNull)
    $taskBody = @{ title = ('e2e-pull-task-' + (Get-Date -Format 'HHmmss')); description = 'e2e onboarding pull verify task' } | ConvertTo-Json
    $taskResp = Invoke-Json -Method POST -Uri ($base + '/api/tasks') -Headers $auth -Body $taskBody
    $taskId = $null
    try { $taskId = ($taskResp.Body | ConvertFrom-Json).data.id } catch { }
    Assert ($null -ne $taskId) 'S3 task created (parent for sub_task)'
    Write-Host ('       taskId=' + $taskId)

    if ($taskId) {
        # assignedAgent set -> create() -> claim() -> changeStatus(ASSIGNED) -> sub_task.assigned inbox
        $stBody = @{ taskId = $taskId; title = ('e2e-pull-subtask-' + (Get-Date -Format 'HHmmss')); description = 'assigned to the onboarding agent'; deliverable = 'proof of doorbell->pullTasks loop'; assignedAgent = $agentId } | ConvertTo-Json
        $stResp = Invoke-Json -Method POST -Uri ($base + '/api/sub-tasks') -Headers $auth -Body $stBody
        try { $subTaskId = ($stResp.Body | ConvertFrom-Json).data.id } catch { }
        Assert ($null -ne $subTaskId) 'S3 sub_task created and assigned (triggers sub_task.assigned inbox)'
        Write-Host ('       subTaskId=' + $subTaskId)
    }
}

# ---- S4: the doorbell must push an inbox signal (event-driven ring) ----
if ($subTaskId) {
    Start-Sleep -Seconds 4   # AFTER_COMMIT async ring + curl flush
    $dbContent2 = ''
    if (Test-Path $dbSseFile) { $dbContent2 = Get-Content $dbSseFile -Raw -ErrorAction SilentlyContinue }
    Write-Host '--- doorbell SSE frames after sub_task assignment ---'
    Write-Host $dbContent2
    $gotInbox = ($dbContent2 -match 'event:inbox' -and $dbContent2 -match '"type"\s*:\s*"inbox"')
    Assert $gotInbox 'S4 doorbell pushed an inbox signal (type=inbox)'
    Assert ($dbContent2 -match 'sub_task.assigned') 'S4 inbox signal eventType is sub_task.assigned'
} else {
    Write-Host '[SKIP] S4 inbox signal (S3 did not create a sub_task)' -ForegroundColor Yellow
}

# ---- S5: external AI calls MCP pullTasks and gets the assigned sub_task ----
if ($subTaskId -and $mcp -and $mcp.SessionId) {
    $sid = $mcp.SessionId
    $msgUri = $base + '/mcp/messages?sessionId=' + $sid
    $ptBody = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pullTasks","arguments":{"agentId":' + $agentId + ',"role":"EXECUTOR","max":20,"sessionId":"' + $sid + '"}}}'
    Invoke-Json -Method POST -Uri $msgUri -Headers $auth -Body $ptBody | Out-Null
    Start-Sleep -Seconds 2
    $mcpContent2 = ''
    if (Test-Path $mcpSseFile) { $mcpContent2 = Get-Content $mcpSseFile -Raw -ErrorAction SilentlyContinue }
    Write-Host '--- MCP SSE frames after pullTasks ---'
    Write-Host $mcpContent2
    Assert ($mcpContent2 -match 'sub_task.assigned') 'S5 pullTasks returned the sub_task.assigned message'
    # pullTasks result is a nested/escaped JSON string in the SSE frame, so the field name
    # appears as \"subTaskId\":<id>. Use a tolerant char class ([\ " : whitespace]) so the
    # match works for both escaped and plain JSON forms.
    Assert ($mcpContent2 -match ('subTaskId[\\":\s]*' + $subTaskId)) 'S5 pullTasks message points to the created subTaskId'
} else {
    Write-Host '[SKIP] S5 pullTasks (no sub_task or MCP session)' -ForegroundColor Yellow
}

# ---- cleanup background jobs ----
if ($dbJob) { Stop-Job $dbJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue }
if ($mcp -and $mcp.Job) { Stop-Job $mcp.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host ('=== RESULT: PASS=' + $pass + ' FAIL=' + $fail + ' ===')
if ($fail -eq 0) {
    Write-Host 'ALL PASSED: doorbell inbox signal -> MCP pullTasks fetched the assigned task' -ForegroundColor Green
    exit 0
} else {
    Write-Host ('SOME CHECKS FAILED (MCP log: ' + $mcpSseFile + ' ; doorbell log: ' + $dbSseFile + ')') -ForegroundColor Red
    exit 1
}
