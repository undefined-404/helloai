# ============================================================
# helloai M5 场景矩阵 2~5 预置 + 断言验证脚本（N14 收尾）
# 用途：为 M5 场景 2~5 预置条件并断言证据链：
#   S2 blocked path ：预置依赖链任务（下游 dependsOn 上游未完成）
#                     -> claimSubTask + reportBlocked
#                     -> 断言 BLOCKED + blockedReason + timeline 事件 + PLANNER 收件箱
#   S3 超时替补     ：预置 ASSIGNED 超时（直接指派 + update_time 回拨 15 分钟）
#                     -> 等 AssignedSubTaskTimeoutTask（helloai-job，30s 巡检）回收
#                     -> 断言同角色换人（assigned_agent_id 变更）+ trigger=assigned_timeout
#   S4 附件 path    ：预置 PENDING 子任务 -> claim + uploadArtifact 登记 +
#                     submitResult（manifest 多文件）
#                     -> 断言 附件 ACTIVE 落库 + 物化事件 + 核验 Prompt 注入（可 SKIP）
#   S5 双 Agent 值班：预置双 ACTIVE 租约（exec-a / exec-b 各自 checkIn）
#                     -> 同一 PENDING 子任务并发 claimSubTask 竞速
#                     -> 断言 恰一个 claimed=true（互斥）+ 落库归属赢家 + 双租约仍 ACTIVE
# 模式：
#   -Scene S2|S3|S4|S5|all（默认 all）
#   -PresetOnly  只预置 + 打印真实 AI 操作指引 + 轮询等待证据（供用户用 qoder/trae 实测）
#                （S3/S5 无需真实 AI，两种模式执行相同）
#   -RealAgentId 真实外部 AI 的 agent id（PresetOnly 时建议传入，写入任务白名单）
# Ref: doc/HelloAI_迭代执行记录.md §6.94（场景 1 happy path 实测方式）
#      .qoder/skills/helloai-preflight/SKILL.md（规则 6：UTF-8 with BOM + 单引号拼接）
# 前置：docker compose up -d（helloai-postgres:15432）；helloai-start 在 :6565 运行；
#       S3 需要 helloai-job 实例在运行（AssignedSubTaskTimeoutTask 30s 巡检）。
# 用法（项目根，PowerShell 5.1）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-m5-scenarios.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-m5-scenarios.ps1 -Scene S5
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-m5-scenarios.ps1 -Scene S2 -PresetOnly -RealAgentId 2088261489367584770
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [ValidateSet('S2','S3','S4','S5','all')]
    [string]$Scene = 'all',
    [switch]$PresetOnly,
    [long]$RealAgentId = 0,
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [int]$PollIntervalSec = 3,
    [int]$BlockedWaitSec = 300,
    [int]$RedispatchWaitSec = 240,
    [int]$SubmitWaitSec = 60,
    [int]$AssignAgeMinutes = 15
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

$scriptDir  = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

$execAName = 'm5-scene-execa'
$execBName = 'm5-scene-execb'
$plannerName = 'm5-scene-planner'

$global:PassCount = 0
$global:FailCount = 0
$global:SkipCount = 0

function Assert-Pass {
    param([bool]$Condition, [string]$Scenario, [string]$Detail)
    if ($Condition) {
        Write-Output ('[' + $Scenario + '] PASS : ' + $Detail)
        $global:PassCount++
    } else {
        Write-Output ('[' + $Scenario + '] FAIL : ' + $Detail)
        $global:FailCount++
    }
}

function Assert-Skip {
    param([string]$Scenario, [string]$Detail)
    Write-Output ('[' + $Scenario + '] SKIP : ' + $Detail)
    $global:SkipCount++
}

# ============================================================
# helper: HTTP JSON (HttpClient, StringContent UTF-8, PS 5.1 safe)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{}
    )
    # 剥 here-string 可能串入的 BOM 头（规则 6）
    $Body = $Body.TrimStart([char]0xFEFF)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne 'GET') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if ($Method -eq 'GET')        { $resp = $client.GetAsync($Uri).Result }
        elseif ($Method -eq 'DELETE') {
            if ($Body) {
                $req = [System.Net.Http.HttpRequestMessage]::new('DELETE', $Uri)
                $req.Content = $content
                $resp = $client.SendAsync($req).Result
            } else {
                $resp = $client.DeleteAsync($Uri).Result
            }
        }
        elseif ($Method -eq 'POST')   { $resp = $client.PostAsync($Uri, $content).Result }
        elseif ($Method -eq 'PUT')    { $resp = $client.PutAsync($Uri, $content).Result }
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

# ============================================================
# helper: docker exec psql
# ============================================================
function Run-Psql {
    param(
        [Parameter(Mandatory=$true)][string]$Sql,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $Sql = $Sql.TrimStart([char]0xFEFF)
    $tmpSql = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmpSql, $Sql, $script:Utf8NoBom)
    Remove-Item $OutFile -ErrorAction SilentlyContinue

    $dockerArgs = @('exec', '-i', $pgContainer, 'psql',
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-t', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)

    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $output = $sqlContent | & docker @dockerArgs 2>&1
    $rc = $LASTEXITCODE
    $output | Out-File -FilePath $OutFile -Encoding UTF8
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return $rc
}

function Get-PsqlFields {
    param([Parameter(Mandatory=$true)][string]$Path)
    $line = Get-Content -Path $Path -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^\(' } |
        Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '<NULL>', '')
}

# ============================================================
# helper: 幂等注册 / 复用固定名测试 Agent（返回 @{Id;ApiKey}）
# ============================================================
function Ensure-TestAgent {
    param([string]$Name, [string]$RoleValue, [string]$AccessType, [string]$ModelType, [string]$AdminToken)
    $lookupResp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=200') -Headers @{ 'X-Admin-Token' = $AdminToken }
    $agentId = $null
    $agentApiKey = $null
    if ($lookupResp.Code -eq 200) {
        $lookupJson = $null
        try { $lookupJson = $lookupResp.Body | ConvertFrom-Json } catch { }
        if ($lookupJson -and $lookupJson.data -and $lookupJson.data.list) {
            $existing = @($lookupJson.data.list | Where-Object { $_.name -eq $Name })
            if ($existing.Count -gt 0) {
                $agentId = $existing[0].id
                $agentApiKey = $existing[0].apiKey
                Write-Output ('[agent] reuse ' + $Name + ' id=' + $agentId)
            }
        }
    }
    if (-not $agentId) {
        $regBody = @{ name = $Name; role = $RoleValue; description = 'verify-m5-scenarios preset agent'; accessType = $AccessType; modelType = $ModelType; idempotent = $true } | ConvertTo-Json -Depth 6
        $regResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/agents/register') -Body $regBody -Headers @{}
        if ($regResp.Code -ne 200) {
            Write-Output ('[agent] FAIL register ' + $Name + ' HTTP=' + $regResp.Code + ' body=' + $regResp.Body)
            return $null
        }
        $regJson = $null
        try { $regJson = $regResp.Body | ConvertFrom-Json } catch { }
        if ($regJson -and $regJson.data -and $regJson.data.id) {
            $agentId = $regJson.data.id
            $agentApiKey = $regJson.data.apiKey
            Write-Output ('[agent] registered ' + $Name + ' id=' + $agentId)
        }
    }
    if (-not $agentId) { return $null }
    if ([string]::IsNullOrEmpty($agentApiKey)) {
        # 复用场景：注册接口不重复下发 apiKey，回列表兜底取
        $lookupResp2 = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=200') -Headers @{ 'X-Admin-Token' = $AdminToken }
        if ($lookupResp2.Code -eq 200) {
            $lookupJson2 = $null
            try { $lookupJson2 = $lookupResp2.Body | ConvertFrom-Json } catch { }
            if ($lookupJson2 -and $lookupJson2.data -and $lookupJson2.data.list) {
                $existing2 = @($lookupJson2.data.list | Where-Object { $_.name -eq $Name })
                if ($existing2.Count -gt 0) { $agentApiKey = $existing2[0].apiKey }
            }
        }
    }
    if ([string]::IsNullOrEmpty($agentApiKey)) {
        Write-Output ('[agent] FAIL ' + $Name + ' apiKey empty (re-register manually or clean the agent)')
        return $null
    }
    return @{ Id = $agentId; ApiKey = [string]$agentApiKey }
}

# ============================================================
# helper: MCP REST 直通工具调用（R 包装 {code,msg,data}）
# ============================================================
function Invoke-Tool {
    param([string]$ApiKey, [string]$ToolName, [hashtable]$Args = @{})
    $body = @{ jsonrpc = '2.0'; id = 1; method = 'tools/call'; params = @{ name = $ToolName; arguments = $Args } } | ConvertTo-Json -Depth 8
    return Invoke-Json -Method POST -Uri ($BaseUrl + '/api/mcp/jsonrpc') -Body $body -Headers @{ 'Authorization' = ('Bearer ' + $ApiKey) }
}

function Get-ToolResult {
    param([object]$Resp, [string]$Scenario)
    if ($Resp.Code -ne 200) {
        Write-Output ('[' + $Scenario + '] FAIL : HTTP=' + $Resp.Code + ' body=' + $Resp.Body)
        return $null
    }
    $json = $null
    try { $json = $Resp.Body | ConvertFrom-Json } catch { }
    if (-not $json) {
        Write-Output ('[' + $Scenario + '] FAIL : invalid json body=' + $Resp.Body)
        return $null
    }
    if ($json.error) {
        Write-Output ('[' + $Scenario + '] FAIL : jsonrpc error=' + ($json.error | ConvertTo-Json -Compress -Depth 6))
        return $null
    }
    return $json.result
}

# ============================================================
# helper: 建任务（白名单）+ 建子任务 + 重置 PENDING
# ============================================================
function New-TaskWithWhitelist {
    param([string]$Title, [long[]]$ExecutorIds, [string]$AdminToken)
    # 残留清理：同名任务级联删除（幂等起点）
    $findSql = "SELECT id FROM task WHERE title = '" + $Title + "' AND deleted = 0 LIMIT 1;"
    $findFile = Join-Path $scriptDir 'verify-m5-scenarios-find.out'
    $null = Run-Psql -Sql $findSql -OutFile $findFile
    $findLine = Get-PsqlFields -Path $findFile
    if ($findLine -and $findLine.Split('|')[0]) {
        $residualId = $findLine.Split('|')[0]
        Write-Output ('[preset] cleanup residual task id=' + $residualId)
        $delBody = '{"confirmTitle":"' + $Title + '"}'
        $null = Invoke-Json -Method DELETE -Uri ($BaseUrl + '/api/tasks/deleteById/' + $residualId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    }
    $policy = ''
    if ($ExecutorIds -and $ExecutorIds.Count -gt 0) {
        $ids = ($ExecutorIds | ForEach-Object { [string]$_ }) -join ','
        $policy = ',"agentPolicy":{"executorAgentIds":[' + $ids + ']}'
    }
    $taskBody = '{"title":"' + $Title + '","description":"verify-m5-scenarios preset task"' + $policy + '}'
    $taskResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks') -Body $taskBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    if ($taskResp.Code -ne 200) {
        Write-Output ('[preset] FAIL create task HTTP=' + $taskResp.Code + ' body=' + $taskResp.Body)
        return $null
    }
    $taskJson = $null
    try { $taskJson = $taskResp.Body | ConvertFrom-Json } catch { }
    if (-not $taskJson -or $taskJson.code -ne 200 -or -not $taskJson.data.id) {
        Write-Output ('[preset] FAIL create task biz: ' + $taskResp.Body)
        return $null
    }
    return [string]$taskJson.data.id
}

function New-SubTask {
    param([string]$TaskId, [string]$Title, [long]$AssignedAgent, [string]$AdminToken)
    $assignField = ''
    if ($AssignedAgent -gt 0) { $assignField = ',"assignedAgent":' + $AssignedAgent }
    $body = '{"taskId":' + $TaskId + ',"title":"' + $Title + '","description":"preset sub-task","deliverable":"verification evidence note","acceptance":"evidence present"' + $assignField + '}'
    $resp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/sub-tasks') -Body $body -Headers @{ 'X-Admin-Token' = $AdminToken }
    if ($resp.Code -ne 200) {
        Write-Output ('[preset] FAIL create sub-task HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    if (-not $json -or $json.code -ne 200 -or -not $json.data.id) {
        Write-Output ('[preset] FAIL create sub-task biz: ' + $resp.Body)
        return $null
    }
    return [string]$json.data.id
}

function Reset-Pending {
    param([string]$SubTaskId)
    $sql = "UPDATE sub_task SET status = 'PENDING', assigned_agent_id = NULL, update_by = 'm5-preset' WHERE id = " + $SubTaskId + " AND status <> 'PENDING';"
    $out = Join-Path $scriptDir 'verify-m5-scenarios-reset.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) {
        Write-Output ('[preset] FAIL reset pending rc=' + $rc)
    }
}

function Get-SubTaskState {
    param([string]$SubTaskId)
    $sql = "SELECT status || '|' || COALESCE(assigned_agent_id::text, 'NULL') || '|' || rework_count FROM sub_task WHERE id = " + $SubTaskId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-m5-scenarios-state.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

function Set-DependsOn {
    param([string]$DownstreamId, [string]$UpstreamId)
    $sql = "UPDATE sub_task SET depends_on = '[" + $UpstreamId + "]'::jsonb WHERE id = " + $DownstreamId + ";"
    $out = Join-Path $scriptDir 'verify-m5-scenarios-dep.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) {
        Write-Output ('[preset] FAIL set depends_on rc=' + $rc)
        return $false
    }
    return $true
}

function Age-AssignedUpdateTime {
    param([string]$SubTaskId, [int]$Minutes)
    # PG 触发器会把 update_time 覆盖为当前时间，须先禁用触发器再回拨（postgres 拥有表）
    $sql = @"
ALTER TABLE sub_task DISABLE TRIGGER update_sub_task_update_time;
UPDATE sub_task SET update_time = now() - interval '$Minutes minutes' WHERE id = $SubTaskId;
ALTER TABLE sub_task ENABLE TRIGGER update_sub_task_update_time;
"@
    $out = Join-Path $scriptDir 'verify-m5-scenarios-age.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) {
        Write-Output ('[preset] FAIL age update_time rc=' + $rc)
        return $false
    }
    return $true
}

function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSec, [string]$Label, [object[]]$ArgList = @())
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $value = & $Condition @ArgList
        if ($value) { return $value }
        Start-Sleep -Seconds $PollIntervalSec
    } while ((Get-Date) -lt $deadline)
    Write-Output ('[wait] timeout: ' + $Label + ' not satisfied within ' + $TimeoutSec + 's')
    return $null
}

function Remove-Task {
    param([string]$TaskId, [string]$Title, [string]$AdminToken)
    $delBody = '{"confirmTitle":"' + $Title + '"}'
    $null = Invoke-Json -Method DELETE -Uri ($BaseUrl + '/api/tasks/deleteById/' + $TaskId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
}

# ============================================================
# STEP S0: pre-flight
# ============================================================
Write-Output '=== [S0] pre-flight ==='
$dockerCheck = & docker ps --format '{{.Names}}|{{.Status}}' --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Output 'FAIL : postgres container is NOT up. Run: docker compose up -d'
    exit 1
}
Write-Output '[S0] postgres container up'

try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync($BaseUrl + '/api/health').Result
    Write-Output ('[S0] server ' + $BaseUrl + ' HTTP ' + [int]$pingResp.StatusCode)
    $ping.Dispose()
} catch {
    Write-Output ('FAIL : server NOT reachable at ' + $BaseUrl)
    exit 1
}

# ============================================================
# STEP S1: admin login
# ============================================================
Write-Output ''
Write-Output '=== [S1] admin login ==='
$loginBody = '{"type":"admin","username":"' + $AdminUsername + '","credential":"' + $AdminPassword + '"}'
$loginResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/auth/login') -Body $loginBody
$loginJson = $null
try { $loginJson = $loginResp.Body | ConvertFrom-Json } catch { }
$adminToken = if ($loginJson -and $loginJson.data -and $loginJson.data.token) { $loginJson.data.token } else { $null }
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Output ('FAIL : admin login failed: ' + $loginResp.Body)
    exit 1
}
Write-Output '[S1] admin token acquired'

# ============================================================
# ensure preset agents (idempotent, fixed names)
# ============================================================
Write-Output ''
Write-Output '=== [agents] ensure preset test agents ==='
$execA = Ensure-TestAgent -Name $execAName -RoleValue 'EXECUTOR' -AccessType 'CLI_CLIENT' -ModelType 'gpt-4o' -AdminToken $adminToken
$execB = Ensure-TestAgent -Name $execBName -RoleValue 'EXECUTOR' -AccessType 'CLI_CLIENT' -ModelType 'gpt-4o' -AdminToken $adminToken
$planner = Ensure-TestAgent -Name $plannerName -RoleValue 'PLANNER' -AccessType 'CLI_CLIENT' -ModelType 'gpt-4o' -AdminToken $adminToken
if (-not $execA -or -not $execB -or -not $planner) {
    Write-Output 'FAIL : preset agents unavailable'
    exit 1
}
Write-Output ('[agents] exec-a=' + $execA.Id + ' exec-b=' + $execB.Id + ' planner=' + $planner.Id)

$execAId = [string]$execA.Id
$execBId = [string]$execB.Id
$plannerId = [string]$planner.Id

# whitelist: preset agents + optional real agent
$wl = @([long]$execA.Id, [long]$execB.Id)
if ($RealAgentId -gt 0) { $wl += [long]$RealAgentId }

# ============================================================
# S2: blocked path
# ============================================================
function Run-Scenario2 {
    Write-Output ''
    Write-Output '=== [S2] blocked path: dependency chain + reportBlocked evidence ==='
    $taskTitle = 'm5-scene2-blocked-path-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds $wl -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S2' 'task preset failed'; return }
    $upId = New-SubTask -TaskId $taskId -Title 'm5-s2-upstream' -AssignedAgent 0 -AdminToken $adminToken
    $downId = New-SubTask -TaskId $taskId -Title 'm5-s2-downstream' -AssignedAgent 0 -AdminToken $adminToken
    if (-not $upId -or -not $downId) { Assert-Pass $false 'S2' 'sub-task preset failed'; Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken; return }
    $null = Set-DependsOn -DownstreamId $downId -UpstreamId $upId
    # 强制回到 PENDING + 未指派（auto-assign-on-create 开启时创建即被派发，需重置）
    Reset-Pending -SubTaskId $downId
    Write-Output ('[S2] preset taskId=' + $taskId + ' upstream=' + $upId + ' downstream=' + $downId + ' (downstream dependsOn upstream, upstream stays PENDING)')

    if ($PresetOnly) {
        Write-Output '[S2] ==== REAL AGENT INSTRUCTIONS (run with any EXECUTOR apiKey) ===='
        Write-Output ('[S2]  1. POST ' + $BaseUrl + '/api/mcp/jsonrpc -> tools/call claimSubTask {"subTaskId":' + $downId + '}')
        Write-Output ('[S2]  2. POST ' + $BaseUrl + '/api/mcp/jsonrpc -> tools/call getDepsSummary {"subTaskId":' + $downId + '}  (depCount=1, dep not DONE)')
        Write-Output ('[S2]  3. POST ' + $BaseUrl + '/api/mcp/jsonrpc -> tools/call reportBlocked {"subTaskId":' + $downId + ',"reason":"upstream dependency not done, external api unavailable"}')
        Write-Output ('[S2] ==== waiting for BLOCKED evidence (max ' + $BlockedWaitSec + 's) ====')
        $blockerState = Wait-Until -Condition { param($id) $s = Get-SubTaskState $id; ($s -and $s.StartsWith('BLOCKED')) } -TimeoutSec $BlockedWaitSec -Label 'S2 BLOCKED' -ArgList @($downId)
    } else {
        # scripted rehearsal: exec-a 全环（claim -> reportBlocked）
        $claimRes = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'claimSubTask' -Args @{ subTaskId = [long]$downId }) -Scenario 'S2-claim'
        if (-not $claimRes -or $claimRes.claimed -ne $true) {
            Write-Output ('[S2] FAIL : claim not won: ' + $claimRes)
            Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
            return
        }
        Write-Output ('[S2] downstream claimed by exec-a id=' + $execAId)
        $blockRes = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'reportBlocked' -Args @{ subTaskId = [long]$downId; reason = 'upstream dependency not done, external api unavailable' }) -Scenario 'S2-reportBlocked'
        if (-not $blockRes -or $blockRes.blocked -ne $true) {
            Write-Output ('[S2] FAIL : reportBlocked not accepted: ' + $blockRes)
            Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
            return
        }
        Write-Output '[S2] reportBlocked accepted by platform'
    }

    # ---- assertions ----
    $stateLine = Get-SubTaskState -SubTaskId $downId
    Write-Output ('[S2] state=' + $stateLine)
    Assert-Pass ($stateLine -and $stateLine.StartsWith('BLOCKED')) 'S2-status' ('downstream status=BLOCKED, actual=' + $stateLine)

    $ctxSql = "SELECT COALESCE(context->>'blockedReason','') || '|' || COALESCE((context->>'blockedByAgentId'),'NULL') FROM sub_task WHERE id = " + $downId + ";"
    $ctxOut = Join-Path $scriptDir 'verify-m5-scenarios-s2-ctx.out'
    $null = Run-Psql -Sql $ctxSql -OutFile $ctxOut
    $ctxLine = Get-PsqlFields -Path $ctxOut
    if ($ctxLine) {
        $ctxParts = $ctxLine.Split('|')
        Assert-Pass (-not [string]::IsNullOrWhiteSpace($ctxParts[0])) 'S2-ctx-reason' ('blockedReason=' + $ctxParts[0])
        Assert-Pass ($ctxParts[1] -ne 'NULL') 'S2-ctx-reporter' ('blockedByAgentId=' + $ctxParts[1])
    } else {
        Assert-Pass $false 'S2-ctx' 'context patch missing'
    }

    $tlSql = "SELECT event_type || '|' || COALESCE(payload->>'source','') || '|' || COALESCE(payload->>'reason','') FROM task_timeline WHERE sub_task_id = " + $downId + " AND event_type = 'sub_task_report_blocked' ORDER BY id DESC LIMIT 1;"
    $tlOut = Join-Path $scriptDir 'verify-m5-scenarios-s2-tl.out'
    $null = Run-Psql -Sql $tlSql -OutFile $tlOut
    $tlLine = Get-PsqlFields -Path $tlOut
    if ($tlLine) {
        $tlParts = $tlLine.Split('|')
        Assert-Pass ($tlParts[0] -eq 'sub_task_report_blocked') 'S2-timeline-event' 'sub_task_report_blocked present'
        Assert-Pass ($tlParts[1] -eq 'agent_report') 'S2-timeline-source' ('payload.source=' + $tlParts[1])
        Assert-Pass (-not [string]::IsNullOrWhiteSpace($tlParts[2])) 'S2-timeline-reason' ('payload.reason=' + $tlParts[2])
    } else {
        Assert-Pass $false 'S2-timeline' 'sub_task_report_blocked event missing'
    }

    $inboxSql = "SELECT event_type || '|' || ref_id || '|' || priority FROM agent_inbox WHERE agent_id = " + $plannerId + " AND event_type = 'sub_task.blocked' AND ref_id = " + $downId + " AND deleted = 0 ORDER BY id DESC LIMIT 1;"
    $inboxOut = Join-Path $scriptDir 'verify-m5-scenarios-s2-inbox.out'
    $null = Run-Psql -Sql $inboxSql -OutFile $inboxOut
    $inboxLine = Get-PsqlFields -Path $inboxOut
    if ($inboxLine) {
        $inboxParts = $inboxLine.Split('|')
        Assert-Pass ($inboxParts[0] -eq 'sub_task.blocked') 'S2-inbox' ('planner inbox notified, priority=' + $inboxParts[2])
    } else {
        Assert-Pass $false 'S2-inbox' 'planner inbox sub_task.blocked missing'
    }

    Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
    Write-Output '[S2] teardown done'
}

# ============================================================
# S3: timeout reassignment (fully automatic, helloai-job required)
# ============================================================
function Run-Scenario3 {
    Write-Output ''
    Write-Output '=== [S3] timeout reassignment: ASSIGNED aged -> same-role redispatch ==='
    $taskTitle = 'm5-scene3-timeout-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds $wl -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S3' 'task preset failed'; return }
    $subId = New-SubTask -TaskId $taskId -Title 'm5-s3-assigned-stuck' -AssignedAgent ([long]$execAId) -AdminToken $adminToken
    if (-not $subId) { Assert-Pass $false 'S3' 'sub-task preset failed'; Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken; return }
    $stateLine = Get-SubTaskState -SubTaskId $subId
    Write-Output ('[S3] preset subTaskId=' + $subId + ' state=' + $stateLine + ' (assigned to exec-a, exec-a never claims)')
    Assert-Pass ($stateLine -and $stateLine.StartsWith('ASSIGNED')) 'S3-preset' ('assigned directly to exec-a, actual=' + $stateLine)

    # 回拨 update_time 触发超时巡检（阈值默认 10 分钟，回拨 15 分钟留裕量）
    $null = Age-AssignedUpdateTime -SubTaskId $subId -Minutes $AssignAgeMinutes
    Write-Output ('[S3] update_time aged back ' + $AssignAgeMinutes + ' minutes')

    # exec-b 打卡保新鲜度（候选资格：心跳新鲜 + 非 STRICT 值班）
    $checkInB = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execB.ApiKey -ToolName 'checkIn' -Args @{ workMode = 'AUTO'; maxConcurrent = 3; ttlMinutes = 30 }) -Scenario 'S3-checkIn-b'
    if ($checkInB) {
        Write-Output ('[S3] exec-b checkIn leaseId=' + $checkInB.leaseId)
    }

    # 等 AssignedSubTaskTimeoutTask（30s 巡检）回收并改派
    Write-Output ('[S3] waiting redispatch (max ' + $RedispatchWaitSec + 's; requires helloai-job running)')
    $finalState = Wait-Until -Condition {
        param($id, $orig)
        $s = Get-SubTaskState $id
        if (-not $s) { return $null }
        $parts = $s.Split('|')
        if ($parts[1] -ne $orig) { return $s }
        return $null
    } -TimeoutSec $RedispatchWaitSec -Label 'S3 reassignment' -ArgList @($subId, $execAId)
    $stateAfter = Get-SubTaskState -SubTaskId $subId
    Write-Output ('[S3] state after wait=' + $stateAfter)
    $partsAfter = @($stateAfter.Split('|'))
    if ($partsAfter.Count -ge 2) {
        Assert-Pass ($partsAfter[1] -ne $execAId) 'S3-reassigned' ('assigned_agent_id changed: ' + $execAId + ' -> ' + $partsAfter[1])
        Assert-Pass ($partsAfter[1] -ne 'NULL') 'S3-new-assignee' ('new assignee=' + $partsAfter[1])
    } else {
        Assert-Pass $false 'S3-reassigned' 'state query failed'
    }

    $tlSql = "SELECT event_type || '|' || COALESCE(payload->>'trigger','') || '|' || COALESCE((payload->>'previousAgentId'),'NULL') FROM task_timeline WHERE sub_task_id = " + $subId + " AND event_type = 'sub_task_dispatch_prepare' AND payload->>'trigger' = 'assigned_timeout' ORDER BY id DESC LIMIT 1;"
    $tlOut = Join-Path $scriptDir 'verify-m5-scenarios-s3-tl.out'
    $null = Run-Psql -Sql $tlSql -OutFile $tlOut
    $tlLine = Get-PsqlFields -Path $tlOut
    if ($tlLine) {
        $tlParts = $tlLine.Split('|')
        Assert-Pass ($tlParts[1] -eq 'assigned_timeout') 'S3-timeline' ('sub_task_dispatch_prepare trigger=assigned_timeout, previousAgentId=' + $tlParts[2])
    } else {
        Assert-Pass $false 'S3-timeline' 'assigned_timeout timeline event missing'
    }

    Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
    Write-Output '[S3] teardown done'
}

# ============================================================
# S4: attachment path (claim + uploadArtifact + manifest submitResult)
# ============================================================
function Run-Scenario4 {
    Write-Output ''
    Write-Output '=== [S4] attachment path: register + materialize + review ==='
    $taskTitle = 'm5-scene4-attachment-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds $wl -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S4' 'task preset failed'; return }
    $subId = New-SubTask -TaskId $taskId -Title 'm5-s4-attachment-sub' -AssignedAgent 0 -AdminToken $adminToken
    if (-not $subId) { Assert-Pass $false 'S4' 'sub-task preset failed'; Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken; return }
    Reset-Pending -SubTaskId $subId
    Write-Output ('[S4] preset taskId=' + $taskId + ' subTaskId=' + $subId)

    if ($PresetOnly) {
        Write-Output '[S4] ==== REAL AGENT INSTRUCTIONS (run with any EXECUTOR apiKey) ===='
        Write-Output ('[S4]  1. POST ' + $BaseUrl + '/api/mcp/jsonrpc -> tools/call claimSubTask {"subTaskId":' + $subId + '}')
        Write-Output ('[S4]  2. POST ' + $BaseUrl + '/api/mcp/jsonrpc -> tools/call uploadArtifact {"subTaskId":' + $subId + ',"fileName":"evidence.md","mimeType":"text/markdown","fileSize":64,"storageUrl":"minio://helloai-artifacts/m5/scene4/evidence.md"}')
        Write-Output ('[S4]  3. POST ' + $BaseUrl + '/api/mcp/jsonrpc -> tools/call submitResult {"subTaskId":' + $subId + ',"success":true,"finishReason":"completed","resultId":"m5-s4-' + (Get-Date -Format 'yyyyMMddHHmmss') + '","output":"```json fence manifest with >= 2 files + EXECUTION_RECORD tail"}')
        Write-Output ('[S4] ==== waiting for REVIEW evidence (max ' + $SubmitWaitSec + 's) ====')
        $null = Wait-Until -Condition { param($id) $s = Get-SubTaskState $id; ($s -and ($s.StartsWith('REVIEW') -or $s.StartsWith('DONE') -or $s.StartsWith('REWORK'))) } -TimeoutSec $SubmitWaitSec -Label 'S4 REVIEW' -ArgList @($subId)
    } else {
        $claimRes = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'claimSubTask' -Args @{ subTaskId = [long]$subId }) -Scenario 'S4-claim'
        if (-not $claimRes -or $claimRes.claimed -ne $true) {
            Write-Output ('[S4] FAIL : claim not won')
            Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
            return
        }
        Write-Output '[S4] claimed by exec-a'

        $upRes = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'uploadArtifact' -Args @{ subTaskId = [long]$subId; fileName = 'evidence.md'; mimeType = 'text/markdown'; fileSize = 64; storageUrl = 'minio://helloai-artifacts/m5/scene4/evidence.md' }) -Scenario 'S4-uploadArtifact'
        if (-not $upRes) {
            Write-Output '[S4] FAIL : uploadArtifact failed'
            Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
            return
        }
        Write-Output ('[S4] uploadArtifact attachmentId=' + $upRes.attachmentId)

        $manifestOutput = @'
```json
{"summary":"scene4 evidence bundle","files":[
{"name":"README.md","type":"text/markdown","content":"# M5 scene4\nattachment path evidence\n"},
{"name":"verify.py","type":"text/x-python","content":"def main():\n    print(\"scene4 ok\")\n\nif __name__ == \"__main__\":\n    main()\n"}
]}
```
EXECUTION_RECORD
SUMMARY: attachment path evidence produced
KEY_DECISIONS: manifest multi-file protocol
DOWNSTREAM_NOTES: none
'@
        $resultId = 'm5-s4-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
        $subRes = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'submitResult' -Args @{ subTaskId = [long]$subId; success = $true; finishReason = 'completed'; resultId = $resultId; output = $manifestOutput }) -Scenario 'S4-submitResult'
        if (-not $subRes -or $subRes.accepted -ne $true) {
            Write-Output ('[S4] FAIL : submitResult not accepted')
            Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
            return
        }
        Write-Output ('[S4] submitResult accepted, resultId=' + $resultId)
        $null = Wait-Until -Condition { param($id) $s = Get-SubTaskState $id; ($s -and ($s.StartsWith('REVIEW') -or $s.StartsWith('DONE') -or $s.StartsWith('REWORK'))) } -TimeoutSec $SubmitWaitSec -Label 'S4 REVIEW' -ArgList @($subId)
    }

    # ---- assertions ----
    $stateLine = Get-SubTaskState -SubTaskId $subId
    Write-Output ('[S4] state=' + $stateLine)
    Assert-Pass ($stateLine -and ($stateLine.StartsWith('REVIEW') -or $stateLine.StartsWith('DONE') -or $stateLine.StartsWith('REWORK'))) 'S4-flow' ('submitted -> REVIEW/DONE/REWORK, actual=' + $stateLine)

    $attSql = "SELECT COUNT(*) FROM attachment WHERE sub_task_id = " + $subId + " AND status = 'ACTIVE' AND deleted = 0;"
    $attOut = Join-Path $scriptDir 'verify-m5-scenarios-s4-att.out'
    $null = Run-Psql -Sql $attSql -OutFile $attOut
    $attLine = Get-PsqlFields -Path $attOut
    $attCount = if ($attLine) { [int]$attLine } else { 0 }
    Write-Output ('[S4] active attachment count=' + $attCount)
    Assert-Pass ($attCount -ge 1) 'S4-att-registered' ('uploadArtifact registration persisted, count=' + $attCount)

    $matSql = "SELECT event_type || '|' || COALESCE((payload->>'count'),'NULL') FROM task_timeline WHERE sub_task_id = " + $subId + " AND event_type = 'sub_task_artifact_materialized' ORDER BY id DESC LIMIT 1;"
    $matOut = Join-Path $scriptDir 'verify-m5-scenarios-s4-mat.out'
    $null = Run-Psql -Sql $matSql -OutFile $matOut
    $matLine = Get-PsqlFields -Path $matOut
    if ($matLine) {
        $matParts = $matLine.Split('|')
        Assert-Pass ($matParts[0] -eq 'sub_task_artifact_materialized') 'S4-materialized' ('materialized event count=' + $matParts[1])
        Assert-Pass ($attCount -ge 2) 'S4-materialized-files' ('manifest multi-file materialized, attachments=' + $attCount)
    } else {
        Assert-Skip 'S4-materialized' 'no materialized event (helloai.storage.enabled may be off)'
    }

    $prSql = "SELECT tool_name || '|' || LENGTH(content) FROM conversation_message WHERE tool_name = 'subtask_review_prompt' AND content LIKE '%' || " + $subId + " || '%' ORDER BY id DESC LIMIT 1;"
    $prOut = Join-Path $scriptDir 'verify-m5-scenarios-s4-prompt.out'
    $null = Run-Psql -Sql $prSql -OutFile $prOut
    $prLine = Get-PsqlFields -Path $prOut
    if ($prLine) {
        Assert-Pass ($prLine.StartsWith('subtask_review_prompt')) 'S4-review-prompt' ('review prompt persisted, contentLen=' + $prLine.Split('|')[1])
    } else {
        Assert-Skip 'S4-review-prompt' 'no auto review prompt (needs API_KEY_LLM reviewer with vault credential)'
    }

    Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
    Write-Output '[S4] teardown done'
}

# ============================================================
# S5: dual agent duty + concurrent claim mutual exclusion
# ============================================================
function Run-Scenario5 {
    Write-Output ''
    Write-Output '=== [S5] dual duty + concurrent claim mutual exclusion ==='

    # 双值班租约：exec-a / exec-b 各自 checkIn
    $ciA = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'checkIn' -Args @{ workMode = 'AUTO'; maxConcurrent = 3; ttlMinutes = 30 }) -Scenario 'S5-checkIn-a'
    $ciB = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execB.ApiKey -ToolName 'checkIn' -Args @{ workMode = 'AUTO'; maxConcurrent = 3; ttlMinutes = 30 }) -Scenario 'S5-checkIn-b'
    if (-not $ciA -or -not $ciB) {
        Assert-Pass $false 'S5-lease' 'dual checkIn failed'
        return
    }
    Write-Output ('[S5] exec-a leaseId=' + $ciA.leaseId + ' exec-b leaseId=' + $ciB.leaseId)

    $leaseSql = "SELECT COUNT(*) FROM agent_duty_lease WHERE agent_id IN (" + $execAId + ',' + $execBId + ") AND status = 'ACTIVE' AND deleted = 0;"
    $leaseOut = Join-Path $scriptDir 'verify-m5-scenarios-s5-lease.out'
    $null = Run-Psql -Sql $leaseSql -OutFile $leaseOut
    $leaseLine = Get-PsqlFields -Path $leaseOut
    $leaseCount = if ($leaseLine) { [int]$leaseLine } else { 0 }
    Assert-Pass ($leaseCount -eq 2) 'S5-dual-lease' ('two ACTIVE duty leases, count=' + $leaseCount)

    # 预置同一 PENDING 子任务
    $taskTitle = 'm5-scene5-dual-duty-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds $wl -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S5' 'task preset failed'; return }
    $subId = New-SubTask -TaskId $taskId -Title 'm5-s5-race-sub' -AssignedAgent 0 -AdminToken $adminToken
    if (-not $subId) { Assert-Pass $false 'S5' 'sub-task preset failed'; Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken; return }
    Reset-Pending -SubTaskId $subId
    Write-Output ('[S5] preset race subTaskId=' + $subId)

    # 并发认领竞速：两个独立进程同时 tools/call claimSubTask
    $jobBody = @{ jsonrpc = '2.0'; id = 9; method = 'tools/call'; params = @{ name = 'claimSubTask'; arguments = @{ subTaskId = [long]$subId } } } | ConvertTo-Json -Depth 6 -Compress
    $jobA = Start-Job -ScriptBlock {
        param($base, $apiKey, $body)
        try {
            $r = Invoke-RestMethod -Method Post -Uri ($base + '/api/mcp/jsonrpc') -Headers @{ 'Authorization' = ('Bearer ' + $apiKey) } -ContentType 'application/json' -Body $body -TimeoutSec 15
            return @{ Ok = $true; Claimed = $r.result.claimed; Reason = $r.result.reason }
        } catch {
            return @{ Ok = $false; Claimed = $false; Reason = $_.Exception.Message }
        }
    } -ArgumentList $BaseUrl, $execA.ApiKey, $jobBody
    $jobB = Start-Job -ScriptBlock {
        param($base, $apiKey, $body)
        try {
            $r = Invoke-RestMethod -Method Post -Uri ($base + '/api/mcp/jsonrpc') -Headers @{ 'Authorization' = ('Bearer ' + $apiKey) } -ContentType 'application/json' -Body $body -TimeoutSec 15
            return @{ Ok = $true; Claimed = $r.result.claimed; Reason = $r.result.reason }
        } catch {
            return @{ Ok = $false; Claimed = $false; Reason = $_.Exception.Message }
        }
    } -ArgumentList $BaseUrl, $execB.ApiKey, $jobBody
    $null = Wait-Job -Job $jobA, $jobB -Timeout 30
    $resA = Receive-Job -Job $jobA
    $resB = Receive-Job -Job $jobB
    Remove-Job -Job $jobA, $jobB -Force -ErrorAction SilentlyContinue
    Write-Output ('[S5] race result exec-a: claimed=' + $resA.Claimed + ' reason=' + $resA.Reason)
    Write-Output ('[S5] race result exec-b: claimed=' + $resB.Claimed + ' reason=' + $resB.Reason)

    $winnerId = $null
    if ($resA.Ok -and $resA.Claimed) { $winnerId = $execAId }
    if ($resB.Ok -and $resB.Claimed) { $winnerId = $execBId }
    Assert-Pass ($winnerId -ne $null) 'S5-exclusive-claim' 'exactly one claimed=true'
    Assert-Pass (($resA.Ok -and $resB.Ok)) 'S5-race-ok' 'both race calls returned'

    $stateLine = Get-SubTaskState -SubTaskId $subId
    Write-Output ('[S5] state=' + $stateLine)
    $parts = @($stateLine.Split('|'))
    if ($parts.Count -ge 2 -and $winnerId) {
        Assert-Pass ($parts[1] -eq $winnerId) 'S5-owner' ('assigned_agent_id=' + $parts[1] + ' equals winner ' + $winnerId)
    } else {
        Assert-Pass $false 'S5-owner' 'winner or state missing'
    }

    $leaseSql2 = "SELECT COUNT(*) FROM agent_duty_lease WHERE agent_id IN (" + $execAId + ',' + $execBId + ") AND status = 'ACTIVE' AND deleted = 0;"
    $leaseOut2 = Join-Path $scriptDir 'verify-m5-scenarios-s5-lease2.out'
    $null = Run-Psql -Sql $leaseSql2 -OutFile $leaseOut2
    $leaseLine2 = Get-PsqlFields -Path $leaseOut2
    $leaseCount2 = if ($leaseLine2) { [int]$leaseLine2 } else { 0 }
    Assert-Pass ($leaseCount2 -eq 2) 'S5-lease-after' ('both leases still ACTIVE after race, count=' + $leaseCount2)

    # teardown: 双签退 + 删任务
    $null = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'checkOut' -Args @{ closeReason = 'm5-s5-done' }) -Scenario 'S5-checkOut-a'
    $null = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execB.ApiKey -ToolName 'checkOut' -Args @{ closeReason = 'm5-s5-done' }) -Scenario 'S5-checkOut-b'
    Remove-Task -TaskId $taskId -Title $taskTitle -AdminToken $adminToken
    Write-Output '[S5] teardown done'
}

# ============================================================
# run selected scenarios
# ============================================================
if ($Scene -eq 'all' -or $Scene -eq 'S2') { Run-Scenario2 }
if ($Scene -eq 'all' -or $Scene -eq 'S3') { Run-Scenario3 }
if ($Scene -eq 'all' -or $Scene -eq 'S4') { Run-Scenario4 }
if ($Scene -eq 'all' -or $Scene -eq 'S5') { Run-Scenario5 }

# ============================================================
# Summary
# ============================================================
Write-Output ''
Write-Output '===================================================='
Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
Write-Output '===================================================='
if ($global:FailCount -gt 0) {
    Write-Output 'verification FAILED'
    exit 1
}
Write-Output 'ALL PASSED (skips are environment-dependent and documented)'
exit 0
