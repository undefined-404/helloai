param(
    [string]$BaseUrl = "http://localhost:6565",
    [ValidateSet("blocked", "offline")]
    [string]$Scenario = "blocked",
    [string]$Role = "PATROL",
    [switch]$BindVault,
    [string]$VaultProvider = "deepseek",
    [string]$VaultApiKeyEnv = "DEEPSEEK_API_KEY",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [int]$BlockedTimeoutSec = 60,
    [int]$OfflineTimeoutSec = 480,
    [int]$PollIntervalSec = 5
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers) {
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    try {
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec 30
    } catch {
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $statusCode = $null
            try { $statusCode = [int]$resp.StatusCode } catch {}
            Write-Host ("HTTP_FAIL: " + $Method + " " + $Url + " status=" + $statusCode)
            try {
                $stream = $resp.GetResponseStream()
                if ($stream -ne $null) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $bodyText = $reader.ReadToEnd()
                    if (-not [string]::IsNullOrWhiteSpace($bodyText)) {
                        Write-Host "FAIL_BODY_BEGIN"
                        Write-Host $bodyText
                        Write-Host "FAIL_BODY_END"
                    }
                }
            } catch {
            }
        } else {
            Write-Host ("HTTP_FAIL: " + $Method + " " + $Url + " (no response body)")
        }
        throw
    }
}

function Start-McpSse([string]$AbsSseFile) {
    Remove-Item $AbsSseFile -ErrorAction SilentlyContinue
    $job = Start-Job -ScriptBlock {
        param($baseUrl, $absFile)
        & curl.exe -i -N ($baseUrl + "/mcp/sse") *>&1 | Out-File -Encoding utf8 -FilePath $absFile
    } -ArgumentList $BaseUrl, $AbsSseFile

    $deadline = (Get-Date).AddSeconds(15)
    do {
        Start-Sleep -Seconds 1
        if (Test-Path $AbsSseFile) {
            $content = Get-Content $AbsSseFile -Raw -ErrorAction SilentlyContinue
            $m = [regex]::Match($content, 'sessionId=([A-Za-z0-9-]+)')
            if ($m.Success) {
                return @{
                    Job = $job
                    SessionId = $m.Groups[1].Value
                    File = $AbsSseFile
                }
            }
        }
    } while ((Get-Date) -lt $deadline)

    try { Stop-Job $job -ErrorAction SilentlyContinue | Out-Null } catch {}
    try { Remove-Job $job -Force -ErrorAction SilentlyContinue } catch {}
    throw "mcp sse sessionId not ready"
}

function Stop-McpSse($SseInfo) {
    if ($null -eq $SseInfo) {
        return
    }
    try { Stop-Job $SseInfo.Job -ErrorAction SilentlyContinue | Out-Null } catch {}
    try { Remove-Job $SseInfo.Job -Force -ErrorAction SilentlyContinue } catch {}
}

function Send-McpMessage([string]$SessionId, [hashtable]$Headers, [object]$Body) {
    return Invoke-Json -Method "Post" -Url ($BaseUrl + "/mcp/messages?sessionId=" + $SessionId) -Body $Body -Headers $Headers
}

function Heartbeat-SourceAgent([string]$SourceAgentId, [string]$SourceAgentApiKey, [string]$AdminToken, [string]$SseFile) {
    Write-Host "STEP7: source CLI agent heartbeat once"
    $sseInfo = $null
    try {
        $sseInfo = Start-McpSse -AbsSseFile $SseFile
        Assert-True (-not [string]::IsNullOrWhiteSpace($sseInfo.SessionId)) "sessionId is empty"

        $initResp = Send-McpMessage -SessionId $sseInfo.SessionId -Headers @{ "X-Admin-Token" = $AdminToken } -Body @{
            jsonrpc = "2.0"
            id = 0
            method = "initialize"
            params = @{
                protocolVersion = "2024-11-05"
                capabilities = @{}
                clientInfo = @{
                    name = "ps-redispatch-check"
                    version = "1.0.0"
                }
            }
        }
        Assert-True ($initResp.code -eq 200) ("mcp initialize code=" + $initResp.code)

        $notifyResp = Send-McpMessage -SessionId $sseInfo.SessionId -Headers @{ "X-Admin-Token" = $AdminToken } -Body @{
            jsonrpc = "2.0"
            method = "notifications/initialized"
        }
        Assert-True ($notifyResp.code -eq 200) ("mcp initialized code=" + $notifyResp.code)

        $hbResp = Send-McpMessage -SessionId $sseInfo.SessionId -Headers @{ "Authorization" = ("Bearer " + $SourceAgentApiKey) } -Body @{
            jsonrpc = "2.0"
            id = 1
            method = "tools/call"
            params = @{
                name = "heartbeat"
                arguments = @{
                    agentId = [long]$SourceAgentId
                    sessionId = $sseInfo.SessionId
                }
            }
        }
        Assert-True ($hbResp.code -eq 200) ("heartbeat code=" + $hbResp.code)
    } finally {
        Stop-McpSse $sseInfo
    }
}

function Register-Agent([string]$Name, [string]$RoleValue, [string]$AccessType, [string]$Description, [string]$ModelType) {
    $body = @{
        name = $Name
        role = $RoleValue
        description = $Description
        accessType = $AccessType
    }
    if (-not [string]::IsNullOrWhiteSpace($ModelType)) {
        $body.modelType = $ModelType
    }
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body $body -Headers @{}
    Assert-True ($resp.code -eq 200) ("register agent code=" + $resp.code + " msg=" + $resp.msg)
    Assert-True ($resp.data -ne $null) "register data is null"
    return $resp.data
}

function Bind-TargetVault([string]$AdminToken, [string]$AgentId) {
    $apiKey = [System.Environment]::GetEnvironmentVariable($VaultApiKeyEnv)
    Assert-True (-not [string]::IsNullOrWhiteSpace($apiKey)) ("env var is empty: " + $VaultApiKeyEnv)

    $bindResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/agents/" + $AgentId + "/api-key") -Body @{
        provider = $VaultProvider
        apiKey = $apiKey
        remark = "verify-subtask-redispatch-auto-execution.ps1"
    } -Headers @{
        "X-Admin-Token" = $AdminToken
    }
    Assert-True ($bindResp.code -eq 200) ("bind vault code=" + $bindResp.code + " msg=" + $bindResp.msg)
}

function Get-SubTask([string]$SubTaskId, [string]$AdminToken) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/" + $SubTaskId) -Body $null -Headers @{
        "X-Admin-Token" = $AdminToken
    }
    Assert-True ($resp.code -eq 200) ("get subTask code=" + $resp.code + " msg=" + $resp.msg)
    Assert-True ($resp.data -ne $null) "subTask data is null"
    return $resp.data
}

function Wait-SubTaskReview([string]$SubTaskId, [string]$AdminToken, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $detail = Get-SubTask -SubTaskId $SubTaskId -AdminToken $AdminToken
        Write-Host ("POLL: subTaskId=" + $SubTaskId + " status=" + $detail.status + " assignedAgent=" + $detail.assignedAgent)
        if ($detail.status -eq "BLOCKED") {
            throw ("subTask became BLOCKED: subTaskId=" + $SubTaskId)
        }
        if ($detail.status -eq "CANCELLED") {
            throw ("subTask became CANCELLED: subTaskId=" + $SubTaskId)
        }
        if ($detail.status -eq "REVIEW") {
            return $detail
        }
        if ($detail.status -eq "DONE") {
            return $detail
        }
        Start-Sleep -Seconds $PollIntervalSec
    } while ((Get-Date) -lt $deadline)
    throw "subTask did not reach REVIEW within timeout"
}

function Write-SqlSnapshot([string]$Path, [string]$ScenarioValue, [string]$TaskId, [string]$SubTaskId, [string]$SourceAgentId, [string]$TargetAgentId) {
    $lines = @(
        "-- verify-subtask-redispatch-auto-execution.ps1 snapshot",
        "-- scenario=" + $ScenarioValue,
        "",
        "-- T1. sub_task final state",
        "SELECT id, task_id, status, assigned_agent, completed_at, update_time",
        "FROM sub_task",
        "WHERE id = " + $SubTaskId + " AND deleted = 0;",
        "",
        "-- T2. task_timeline evidence",
        "SELECT id, task_id, sub_task_id, event_type, role, agent_id, payload, create_time",
        "FROM task_timeline",
        "WHERE sub_task_id = " + $SubTaskId + " AND deleted = 0",
        "ORDER BY id DESC LIMIT 20;",
        "",
        "-- T3. source agent heartbeat / online fields",
        "SELECT id, name, role, status, online_status, last_seen_at, last_active_at, offline_reason, offline_at",
        "FROM agent",
        "WHERE id = " + $SourceAgentId + ";",
        "",
        "-- T4. target agent online fields",
        "SELECT id, name, role, status, online_status, last_seen_at, last_active_at, offline_reason, offline_at",
        "FROM agent",
        "WHERE id = " + $TargetAgentId + ";",
        "",
        "-- T5. task timeline by task",
        "SELECT id, task_id, sub_task_id, event_type, role, agent_id, payload, create_time",
        "FROM task_timeline",
        "WHERE task_id = " + $TaskId + " AND deleted = 0",
        "ORDER BY id DESC LIMIT 30;"
    )
    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

Write-Host "STEP1: admin login"
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type = "admin"
    username = $AdminUsername
    credential = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
Assert-True ($loginResp.data -ne $null) "login data is null"
Assert-True (-not [string]::IsNullOrWhiteSpace($loginResp.data.token)) "admin token is empty"
$adminToken = $loginResp.data.token

$ts = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")

Write-Host "STEP2: register source CLI agent"
$source = Register-Agent -Name ("redispatch-source-" + $Scenario + "-" + $ts) -RoleValue $Role -AccessType "CLI_CLIENT" -Description "redispatch source" -ModelType ""
$sourceAgentId = [string]$source.id
$sourceAgentApiKey = [string]$source.apiKey
Assert-True (-not [string]::IsNullOrWhiteSpace($sourceAgentId)) "source agent id empty"
Assert-True (-not [string]::IsNullOrWhiteSpace($sourceAgentApiKey)) "source agent apiKey empty"
Write-Host ("sourceAgentId=" + $sourceAgentId)

Write-Host "STEP3: register target API agent"
$target = Register-Agent -Name ("redispatch-target-" + $Scenario + "-" + $ts) -RoleValue $Role -AccessType "API_KEY_LLM" -Description "redispatch target" -ModelType "deepseek:deepseek-chat"
$targetAgentId = [string]$target.id
Assert-True (-not [string]::IsNullOrWhiteSpace($targetAgentId)) "target agent id empty"
Write-Host ("targetAgentId=" + $targetAgentId)

if ($BindVault) {
    Write-Host "STEP3.1: bind target credential_vault"
    Bind-TargetVault -AdminToken $adminToken -AgentId $targetAgentId
}

Write-Host "STEP4: create task"
$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "redispatch-task-" + $Scenario + "-" + $ts
    description = "verify redispatch auto execution"
} -Headers @{
    "X-Admin-Token" = $adminToken
}
Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code + " msg=" + $taskResp.msg)
Assert-True ($taskResp.data -ne $null) "task data is null"
$taskId = [string]$taskResp.data.id
Assert-True (-not [string]::IsNullOrWhiteSpace($taskId)) "task id empty"
Write-Host ("taskId=" + $taskId)

Write-Host "STEP5: create subTask assigned to source CLI agent"
$subTaskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks") -Body @{
    taskId = [long]$taskId
    title = "redispatch-subtask-" + $Scenario + "-" + $ts
    description = "verify redispatch path"
    deliverable = "status reaches review"
    acceptance = "sub_task.status=REVIEW"
    priority = "HIGH"
    assignedAgent = [long]$sourceAgentId
} -Headers @{
    "X-Admin-Token" = $adminToken
}
Assert-True ($subTaskResp.code -eq 200) ("create subTask code=" + $subTaskResp.code + " msg=" + $subTaskResp.msg)
Assert-True ($subTaskResp.data -ne $null) "subTask data is null"
$subTaskId = [string]$subTaskResp.data.id
Assert-True (-not [string]::IsNullOrWhiteSpace($subTaskId)) "subTask id empty"
Assert-True ($subTaskResp.data.status -eq "ASSIGNED") ("unexpected initial status: " + $subTaskResp.data.status)
Write-Host ("subTaskId=" + $subTaskId)

$sqlPath = Join-Path "E:\yhzx\1027\helloai" ("redispatch-" + $Scenario + "-snapshot-" + $ts + ".sql")
$ok = $false
$finalDetail = $null
$errorText = $null
try {
    if ($Scenario -eq "blocked") {
        Write-Host "STEP6: block subTask"
        $blockResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/block/" + $subTaskId) -Body @{} -Headers @{
            "X-Admin-Token" = $adminToken
        }
        Assert-True ($blockResp.code -eq 200) ("block code=" + $blockResp.code + " msg=" + $blockResp.msg)
        $blockedDetail = Get-SubTask -SubTaskId $subTaskId -AdminToken $adminToken
        Assert-True ($blockedDetail.status -eq "BLOCKED") ("expected BLOCKED, actual=" + $blockedDetail.status)

        Write-Host "STEP7: reassign blocked subTask to target API agent"
        $reassignResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/sub-tasks/reassign/" + $subTaskId) -Body @{
            agentId = [long]$targetAgentId
        } -Headers @{
            "X-Admin-Token" = $adminToken
        }
        Assert-True ($reassignResp.code -eq 200) ("reassign code=" + $reassignResp.code + " msg=" + $reassignResp.msg)

        Write-Host "STEP8: wait for auto execution to push subTask into REVIEW"
        $finalDetail = Wait-SubTaskReview -SubTaskId $subTaskId -AdminToken $adminToken -TimeoutSec $BlockedTimeoutSec
        Assert-True ($finalDetail.assignedAgent.ToString() -eq $targetAgentId) ("expected assignedAgent=" + $targetAgentId + ", actual=" + $finalDetail.assignedAgent)
    } else {
        $sseFile = Join-Path "E:\yhzx\1027\helloai" ("sse-redispatch-offline-" + $ts + ".txt")
        Heartbeat-SourceAgent -SourceAgentId $sourceAgentId -SourceAgentApiKey $sourceAgentApiKey -AdminToken $adminToken -SseFile $sseFile

        Write-Host "STEP8: wait for offline reconcile and auto execution"
        $finalDetail = Wait-SubTaskReview -SubTaskId $subTaskId -AdminToken $adminToken -TimeoutSec $OfflineTimeoutSec
        Assert-True ($finalDetail.assignedAgent.ToString() -ne $sourceAgentId) ("subTask still assigned to source agent: " + $sourceAgentId)
        Write-Host ("offline reassign target agent=" + $finalDetail.assignedAgent)
    }

    Assert-True (($finalDetail.status -eq "REVIEW") -or ($finalDetail.status -eq "DONE")) ("expected REVIEW/DONE, actual=" + $finalDetail.status)
    $ok = $true
} catch {
    $errorText = $_.ToString()
    Write-Host ("FAILED: " + $errorText)
} finally {
    if (-not [string]::IsNullOrWhiteSpace($taskId) -and -not [string]::IsNullOrWhiteSpace($subTaskId) `
            -and -not [string]::IsNullOrWhiteSpace($sourceAgentId) -and -not [string]::IsNullOrWhiteSpace($targetAgentId)) {
        Write-SqlSnapshot -Path $sqlPath -ScenarioValue $Scenario -TaskId $taskId -SubTaskId $subTaskId -SourceAgentId $sourceAgentId -TargetAgentId $targetAgentId
        Write-Host ("sqlSnapshot=" + $sqlPath)
    }
}

if ($ok) {
    Write-Host "OK: redispatch auto execution passed"
    Write-Host ("scenario=" + $Scenario)
    Write-Host ("taskId=" + $taskId)
    Write-Host ("subTaskId=" + $subTaskId)
    Write-Host ("sourceAgentId=" + $sourceAgentId)
    Write-Host ("targetAgentId=" + $targetAgentId)
    Write-Host ("finalStatus=" + $finalDetail.status)
    Write-Host ("finalAssignedAgent=" + $finalDetail.assignedAgent)
    exit 0
}
exit 1
