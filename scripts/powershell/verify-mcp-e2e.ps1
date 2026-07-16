# ============================================================
# helloai MCP M5 end-to-end business loop verifier v10
# 用途：MCP-over-SSE 完整业务闭环 E2E（登录/建 Agent/建 Task/SubTask ->
#       heartbeat/pullTasks/claim/uploadArtifact/ack/submit/complete 全链路）。
# Ref: doc/HelloAI_实现差距表.md (N3 MCP Server 工具集 / N6 执行命令消费与结果回写)
#      v2.5 roadmap sec 3.13 / appendix E.3 M5 / appendix F.5（历史路线图，仅溯源）
# Stages:
#   A) admin login                                  -> adminToken
#   B) create or reuse M5-test-executor-v10         -> agentId + agentApiKey
#   C) admin create Task                            -> taskId
#   D) admin create SubTask (assignedAgent=agentId) -> subTaskId (auto inbox)
#   E) SSE long connection (curl -N)                -> sessionId
#   F) [protocol] initialize + notifications/initialized (admin token)
#   G) [tool 1] agent SSE heartbeat                  -> expect last_seen_at refreshed
#   H) [tool 2] agent SSE getAgentStatus             -> expect computedOnlineStatus ONLINE/IDLE
#   I) [tool 3] agent SSE pullTasks                  -> expect >=1 inbox (sub_task.assigned)
#   J) [tool 4] agent SSE claimSubTask               -> expect claimed=true (idempotent)
#   K) REST POST /api/sub-tasks/start/{id}           -> expect status=IN_PROGRESS
#   L) [tool 1 again] agent SSE heartbeat            -> expect last_active_at refreshed
#   M) [tool 5] agent SSE uploadArtifact             -> expect attachmentId
#   N) [tool 6] agent SSE ack                        -> expect inbox.is_read=1
#   O) REST POST /api/sub-tasks/submit/{id}          -> expect status=REVIEW
#   P) REST POST /api/sub-tasks/complete/{id}        -> expect status=DONE
#   Q) admin GET /api/admin/agents/{id}              -> verify lastSeenAt/lastActiveAt
#   R) admin GET /api/sub-tasks/{id}                 -> verify status=DONE
#   S) HTTP /api/agent/inbox/count (Bearer apiKey)   -> verify unread count
#   T) write psql snapshot script                    -> inbox / attachment / sub_task final
#
# NOTE: PowerShell 5.1 + UTF-8 no-BOM parses Chinese bytes as ANSI and eats
#       adjacent ASCII double quotes, breaking string interpolation.
#       All runtime string literals in this script are 100% ASCII.
#       Comments above and below may contain CJK for readability (PS handles
#       them leniently); output messages deliberately avoid CJK.
#
# Usage (project root, PowerShell):
#   powershell -File .\scripts\powershell\verify-mcp-e2e.ps1
# ============================================================

# Make console UTF-8 friendly for any echoed data with CJK
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Net.Http

$base        = "http://localhost:6565"
$scriptDir   = "E:\yhzx\1027\helloai"
$sseFile     = Join-Path $scriptDir "sse-mcp-e2e.txt"
$logFile     = Join-Path $scriptDir "m5-e2e-test.log"
$psqlLogFile = Join-Path $scriptDir "m5-e2e-psql.log"

# ============================================================
# Known pitfalls (carried from v9, hardened for v10)
# ============================================================
# - StringContent mediaType must NOT include charset suffix (PS 5.1 rejects it)
# - Single-quoted strings DO NOT interpolate variables -> use double quotes or +
# - AdminAgentController.list() returns data.list (not records)
# - curl.exe + Start-Job is more reliable than HttpClient for SSE in PS 5.1
# - CJK inside double-quoted runtime literals corrupts parsing under UTF-8 no-BOM

Remove-Item $sseFile     -ErrorAction SilentlyContinue
Remove-Item $logFile     -ErrorAction SilentlyContinue
Remove-Item $psqlLogFile -ErrorAction SilentlyContinue

# ============================================================
# HTTP helper (GET/POST/PUT/DELETE unified)
# ============================================================

function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("GET","POST","PUT","DELETE")][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = "",
        [hashtable]$Headers = @{}
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne "GET" -and $Method -ne "DELETE") {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        try {
            if ($Method -eq "GET")    { $resp = $client.GetAsync($Uri).Result }
            elseif ($Method -eq "DELETE") { $resp = $client.DeleteAsync($Uri).Result }
            elseif ($Method -eq "POST")   { $resp = $client.PostAsync($Uri, $content).Result }
            elseif ($Method -eq "PUT")    { $resp = $client.PutAsync($Uri, $content).Result }
            $code = [int]$resp.StatusCode
            $bodyOut = $resp.Content.ReadAsStringAsync().Result
            return @{ Code = $code; Body = $bodyOut }
        } catch [System.Net.Http.HttpRequestException] {
            Write-Error "$Method $Uri network failed: $($_.Exception.Message)"
            return @{ Code = -1; Body = $_.Exception.Message }
        } catch {
            Write-Error "$Method $Uri failed: $($_.Exception.GetType().Name) - $($_.Exception.Message)"
            return @{ Code = -2; Body = $_.Exception.Message }
        }
    } finally {
        $client.Dispose()
    }
}

# ============================================================
# MCP SSE helpers
# ============================================================

function Start-McpSse {
    param([string]$ScriptDir, [string]$SseFileName)
    # Build absolute path up front so subshell cwd shift cannot break it
    $absSseFile = if ([System.IO.Path]::IsPathRooted($SseFileName)) {
        $SseFileName
    } else {
        Join-Path $ScriptDir $SseFileName
    }

    # Use -ArgumentList instead of $using: (more reliable in PS 5.1 subshells)
    # *>&1 merges stderr into stdout so missing-curl messages are captured
    $job = Start-Job -ScriptBlock {
        param($absFile)
        # v2.5.x #11: use UTF-8 encoding so SSE CJK payload bytes are preserved
        # (ASCII encoding would corrupt multi-byte UTF-8 characters)
        & curl.exe -i -N http://localhost:6565/mcp/sse *>&1 |
            Out-File -Encoding utf8 -FilePath $absFile
    } -ArgumentList $absSseFile

    Start-Sleep -Seconds 3

    if (-not (Test-Path $absSseFile)) {
        Write-Warning "SSE file not yet created: $absSseFile - check if curl.exe is on PATH and server reachable"
    }

    $content = ""
    if (Test-Path $absSseFile) {
        $content = Get-Content $absSseFile -Raw -ErrorAction SilentlyContinue
    }
    $m = [regex]::Match($content, 'sessionId=([A-Za-z0-9-]+)')
    $sid = if ($m.Success) { $m.Groups[1].Value } else { "" }
    return @{ Job = $job; SessionId = $sid; AbsFile = $absSseFile }
}

function Send-Mcp {
    param(
        [string]$Body,
        [string]$Label,
        [hashtable]$Headers = @{}
    )
    Write-Output "=== $Label ==="
    Write-Output "Body: $Body"
    if ($Headers.Count -gt 0) {
        Write-Output "Headers: $($Headers.Keys -join ', ')"
    }
    $posBefore = (Get-Item $sseFile).Length
    $resp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=$sid" -Body $Body -Headers $Headers
    Write-Output "POST Status: $($resp.Code)"
    Write-Output "POST Body: $($resp.Body)"
    Start-Sleep -Seconds 2
    $posAfter = (Get-Item $sseFile).Length
    Write-Output "--- SSE stream new content (offset $posBefore -> $posAfter) ---"
    if ($posAfter -gt $posBefore) {
        $reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $reader.Position = $posBefore
        $sseReader = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
        $newContent = $sseReader.ReadToEnd()
        $sseReader.Close()
        $reader.Close()
        Write-Output $newContent
    } else {
        Write-Output "(no new content)"
    }
    Write-Output ""
    Write-Output ""
}

# ============================================================
# STEP 0: server reachability
# ============================================================
Write-Output "=== [0] server reachability check ==="
try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync("$base/api/health").Result
    Write-Output "HTTP $($pingResp.StatusCode) - server is up"
    $ping.Dispose()
} catch {
    Write-Error "Server NOT reachable at $base - please run HelloAIApplication via IDEA first"
    Write-Error "Error: $($_.Exception.Message)"
    exit 1
}
Write-Output ""

# ============================================================
# STEP A: admin login
# ============================================================
Write-Output "=== [A] admin login ==="
$loginBody = '{"type":"admin","username":"admin","credential":"admin123"}'
$loginResp = Invoke-Json -Method POST -Uri "$base/api/auth/login" -Body $loginBody
Write-Output "HTTP $($loginResp.Code)"
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error "admin login failed: $($loginResp.Body)"
    exit 1
}
Write-Output "adminToken = $($adminToken.Substring(0, 16))..."
Write-Output ""

# ============================================================
# STEP B: create or reuse M5-test-executor
# ============================================================
Write-Output "=== [B] create or reuse M5-test-executor (admin token) ==="
$agentName = "M5-test-executor-v10"
$agentId    = $null
$agentApiKey = $null

# B-1 idempotent lookup
$lookupResp = Invoke-Json -Method GET -Uri "$base/api/admin/agents?pageSize=50" -Headers @{ "X-Admin-Token" = $adminToken }
$parsedJson = $lookupResp.Body | ConvertFrom-Json
$lookupData = $parsedJson.data
if ($lookupData -eq $null -or $lookupData.list -eq $null) {
    Write-Output "lookup data is null, will create"
    $existing = @()
} else {
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
}
if ($existing.Count -gt 0) {
    $agentId = $existing[0].id
    $agentApiKey = $existing[0].apiKey
    Write-Output "reuse existing: id=$agentId"
} else {
    Write-Output "not found, creating"
    $createBody = "{`"name`":`"$agentName`",`"role`":`"EXECUTOR`",`"remark`":`"M5 e2e test v10 auto created`"}"
    $createResp = Invoke-Json -Method POST -Uri "$base/api/admin/agents" -Body $createBody -Headers @{ "X-Admin-Token" = $adminToken }
    Write-Output "create HTTP $($createResp.Code)"
    Write-Output "create Body: $($createResp.Body)"
    $agentData = ($createResp.Body | ConvertFrom-Json).data
    $agentId = $agentData.id
    $agentApiKey = $agentData.apiKey
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Error "agent create / lookup failed (no apiKey)"
    exit 1
}
Write-Output "agentId    = $agentId"
Write-Output "agentApiKey = $agentApiKey"
Write-Output ""

# ============================================================
# STEP C: admin create task
# ============================================================
Write-Output "=== [C] admin create task ==="
$taskTitle = "M5-e2e-task-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$taskBody  = "{`"title`":`"$taskTitle`",`"description`":`"M5 e2e business loop verification auto created v10`"}"
$taskResp = Invoke-Json -Method POST -Uri "$base/api/tasks" -Body $taskBody -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "create task HTTP $($taskResp.Code)"
Write-Output "create task Body: $($taskResp.Body)"
$taskId = ($taskResp.Body | ConvertFrom-Json).data.id
if ([string]::IsNullOrEmpty($taskId)) {
    Write-Error "task create failed"
    exit 1
}
Write-Output "taskId = $taskId"
Write-Output ""

# ============================================================
# STEP D: admin create subTask (assignedAgent=agentId)
# ============================================================
Write-Output "=== [D] admin create subTask (assignedAgent=$agentId) ==="
$subTaskTitle = "M5-subtask-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$subTaskBody  = "{`"taskId`":$taskId,`"title`":`"$subTaskTitle`",`"description`":`"M5 subtask assigned to $agentId`",`"deliverable`":`"README + attachment registration`",`"acceptance`":`"sub_task.status=DONE`",`"priority`":`"HIGH`",`"assignedAgent`":$agentId}"
$subTaskResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks" -Body $subTaskBody -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "create subTask HTTP $($subTaskResp.Code)"
Write-Output "create subTask Body: $($subTaskResp.Body)"
$subTaskData = ($subTaskResp.Body | ConvertFrom-Json).data
$subTaskId = $subTaskData.id
if ([string]::IsNullOrEmpty($subTaskId)) {
    Write-Error "subTask create failed"
    exit 1
}
Write-Output "subTaskId = $subTaskId, status = $($subTaskData.status)"
Write-Output ""

# ============================================================
# STEP E: start SSE long connection
# ============================================================
Write-Output "=== [E] start SSE long connection ==="
$sseInfo = Start-McpSse -ScriptDir $scriptDir -SseFileName (Split-Path $sseFile -Leaf)
$job = $sseInfo.Job
$sid = $sseInfo.SessionId
Write-Output "sse abs file = $($sseInfo.AbsFile)"
Write-Output "sessionId = $sid"
if ([string]::IsNullOrEmpty($sid)) {
    Write-Error "sessionId extraction failed (file: $($sseInfo.AbsFile))"
    Stop-Job $job -PassThru | Remove-Job -Force
    exit 1
}
Write-Output ""

# ============================================================
# STEP F: protocol handshake (admin token)
# ============================================================
Write-Output "=== [F] MCP protocol handshake (admin token) ==="
Send-Mcp -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ps-m5-v10","version":"1.0.0"}}}' -Label "[F1] initialize with admin token" -Headers @{ "X-Admin-Token" = $adminToken }

Send-Mcp -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label "[F2] notifications/initialized (admin token)" -Headers @{ "X-Admin-Token" = $adminToken }

# ============================================================
# STEP G: agent SSE heartbeat
# ============================================================
Write-Output "=== [G] agent SSE heartbeat (refresh last_seen_at) ==="
$gBody = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"heartbeat","arguments":{"agentId":' + $agentId + ',"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $gBody -Label "[G] tools/call heartbeat (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP H: agent SSE getAgentStatus
# ============================================================
Write-Output "=== [H] agent SSE getAgentStatus (verify computedOnlineStatus) ==="
$hBody = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":' + $agentId + ',"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $hBody -Label "[H] tools/call getAgentStatus (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP I: agent SSE pullTasks
# ============================================================
Write-Output "=== [I] agent SSE pullTasks (expect >=1 inbox sub_task.assigned) ==="
$iBody = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"pullTasks","arguments":{"agentId":' + $agentId + ',"role":"EXECUTOR","max":20,"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $iBody -Label "[I] tools/call pullTasks (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP J: agent SSE claimSubTask
# ============================================================
Write-Output "=== [J] agent SSE claimSubTask (idempotent, assignedAgent preset) ==="
$jBody = '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"claimSubTask","arguments":{"agentId":' + $agentId + ',"subTaskId":' + $subTaskId + ',"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $jBody -Label "[J] tools/call claimSubTask (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP K: REST POST start (subTask IN_PROGRESS)
# ============================================================
Write-Output "=== [K] REST start subTask -> IN_PROGRESS ==="
$kResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks/start/$subTaskId" -Body "" -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "start HTTP $($kResp.Code)"
Write-Output "start Body: $($kResp.Body)"
Write-Output ""

# ============================================================
# STEP L: agent SSE heartbeat 2nd (IN_PROGRESS -> last_active_at refresh)
# ============================================================
Write-Output "=== [L] agent SSE heartbeat 2nd (IN_PROGRESS, refresh last_active_at) ==="
$lBody = '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"heartbeat","arguments":{"agentId":' + $agentId + ',"sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $lBody -Label "[L] tools/call heartbeat 2nd (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP M: agent SSE uploadArtifact
# ============================================================
Write-Output "=== [M] agent SSE uploadArtifact (DB metadata only) ==="
$mBody = '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"uploadArtifact","arguments":{"agentId":' + $agentId + ',"subTaskId":' + $subTaskId + ',"fileName":"M5-result.txt","mimeType":"text/plain","fileSize":1024,"storageUrl":"minio://helloai-test/M5-test/' + $subTaskId + '/result.txt","sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $mBody -Label "[M] tools/call uploadArtifact (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP N: agent SSE ack (mark inbox as read)
# ============================================================
Write-Output "=== [N] agent SSE ack (mark inbox.is_read=1) ==="
# Extract inbox ID from SSE stream produced by step I (use abs path + Test-Path)
if (-not (Test-Path $sseFile)) {
    Write-Warning "SSE file not found at $sseFile, fallback inbox id 0"
    $inboxId = "0"
} else {
    $reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $fullSseReader = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
    $fullSse = $fullSseReader.ReadToEnd()
    $fullSseReader.Close()
    $reader.Close()
    $inboxId = $null
    $p1 = '"messageId":"inbox-(\d+)".*?"subTaskId":' + $subTaskId
    $p2 = '\\"messageId\\":\\"inbox-(\\d+)\\".*?\\"subTaskId\\":' + $subTaskId
    $m1 = [regex]::Match($fullSse, $p1)
    if ($m1.Success) {
        $inboxId = $m1.Groups[1].Value
    } else {
        $m2 = [regex]::Match($fullSse, $p2)
        if ($m2.Success) {
            $inboxId = $m2.Groups[1].Value
        } else {
            $m3 = [regex]::Match($fullSse, 'inbox-(\d+)')
            if ($m3.Success) {
                $inboxId = $m3.Groups[1].Value
            }
        }
    }
    if ([string]::IsNullOrEmpty($inboxId)) {
        Write-Warning "no inbox ID found in SSE stream, fallback to inbox-0 (idempotent skip)"
        $inboxId = "0"
    } else {
        Write-Output "parsed inbox ID from I step: $inboxId"
    }
}
if ($inboxId -eq "0") {
    Write-Error "ack failed: cannot parse inboxId from SSE stream"
    exit 1
}
$nBody = '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ack","arguments":{"agentId":' + $agentId + ',"messageId":"inbox-' + $inboxId + '","sessionId":"' + $sid + '"}}}'
Send-Mcp -Body $nBody -Label "[N] tools/call ack (agent apiKey)" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# ============================================================
# STEP O: REST POST submit (subTask REVIEW)
# ============================================================
Write-Output "=== [O] REST submit subTask -> REVIEW ==="
$oResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks/submit/$subTaskId" -Body "" -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "submit HTTP $($oResp.Code)"
Write-Output "submit Body: $($oResp.Body)"
Write-Output ""

# ============================================================
# STEP P: REST POST complete (subTask DONE)
# ============================================================
Write-Output "=== [P] REST complete subTask -> DONE ==="
$pResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks/complete/$subTaskId" -Body "" -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "complete HTTP $($pResp.Code)"
Write-Output "complete Body: $($pResp.Body)"
Write-Output ""

# ============================================================
# STEP Q: admin GET agent detail
# ============================================================
Write-Output "=== [Q] admin GET agent detail (last_seen_at/last_active_at refresh check) ==="
$qResp = Invoke-Json -Method GET -Uri "$base/api/admin/agents/$agentId" -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "agent detail HTTP $($qResp.Code)"
Write-Output "agent detail Body: $($qResp.Body)"
$qObj = $null
try {
    $qObj = $qResp.Body | ConvertFrom-Json
} catch {
    Write-Error "agent detail JSON parse failed"
    exit 1
}
if ($qObj -eq $null -or $qObj.data -eq $null) {
    Write-Error "agent detail response has no data"
    exit 1
}
if ([string]::IsNullOrEmpty($qObj.data.lastActivityAt)) {
    Write-Error "lastActivityAt not refreshed (expected after start/claim/submit)"
    exit 1
}
Write-Output ""

# ============================================================
# STEP R: admin GET subTask status
# ============================================================
Write-Output "=== [R] admin GET subTask status (expect DONE) ==="
$rResp = Invoke-Json -Method GET -Uri "$base/api/sub-tasks/$subTaskId" -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output "subTask detail HTTP $($rResp.Code)"
Write-Output "subTask detail Body: $($rResp.Body)"
Write-Output ""

# ============================================================
# STEP S: HTTP /api/agent/inbox/count (Bearer agent apiKey)
# ============================================================
Write-Output "=== [S] HTTP GET /api/agent/inbox/count (Bearer agent apiKey) ==="
$sResp = Invoke-Json -Method GET -Uri "$base/api/agent/inbox/count" -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "inbox/count HTTP $($sResp.Code)"
Write-Output "inbox/count Body: $($sResp.Body)"
Write-Output ""

# ============================================================
# STEP T: psql snapshot script (mixed verification - attachment table only via DB)
# ============================================================
Write-Output "=== [T] write psql snapshot script (4 SELECTs) ==="
$psqlScript = @"
-- T1. inbox read
SELECT id, agent_id, event_type, ref_type, ref_id, is_read, read_at
FROM agent_inbox
WHERE agent_id = $agentId AND deleted = 0
ORDER BY id DESC LIMIT 5;

-- T2. attachment registered
SELECT id, sub_task_id, file_name, mime_type, file_size, storage_url, status
FROM attachment
WHERE sub_task_id = $subTaskId AND deleted = 0
ORDER BY id DESC LIMIT 5;

-- T3. sub_task final state (DONE + score)
SELECT id, status, assigned_agent, completed_at, composite_score, score_grade
FROM sub_task
WHERE id = $subTaskId AND deleted = 0;

-- T4. agent heartbeat fields
SELECT id, name, last_seen_at, last_active_at, online_status
FROM agent
WHERE id = $agentId;
"@
$psqlScript | Out-File -Encoding utf8 "$psqlLogFile.sql"
Write-Output "wrote $psqlLogFile.sql - please run via psql / docker exec and paste results back"
Write-Output ""

# ============================================================
# Cleanup
# ============================================================
Write-Output "=== Cleanup ==="
try {
    Stop-Job $job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
} catch {}

Write-Output "SSE log:   $sseFile"
Write-Output "DB script: $psqlLogFile.sql"
Write-Output "Test agent: id=$agentId name=$agentName (delete via admin UI if needed)"
Write-Output "Test task/subTask: taskId=$taskId subTaskId=$subTaskId"
Write-Output ""
Write-Output "Done. paste key screenshots / assertion results back."
