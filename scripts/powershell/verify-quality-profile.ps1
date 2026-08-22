# ============================================================
# helloai verify-quality-profile.ps1
# Purpose: preset + assert feedback-loop layer-1 (quality profile) evidence:
#   S1 profile increment : 1 APPROVED review -> initial profile row
#                          (reviewed/approved/first_reviewed/first_pass/
#                           total_score/rework all correct)
#   S2 metric semantics  : same sub-task two rounds REJECTED ->
#                          first-pass rate / rework round / [defect] tag
#                          aggregation asserted
#   S3 scheduling feedback: qualityRank selects the agent with better
#                          profile (exec-a) over profile-less exec-b
#                          via POST /api/admin/quality/dispatchById/{id}
#   S4 dynamic TTL       : composite score -> exec-a lease TTL longer
#                          than exec-b (approx 134 vs 122 minutes)
#   S5 rebuild reconcile : SQL-insert 4th review bypassing increment
#                          -> POST /api/admin/quality/rebuildById/{id}
#                          -> full recompute equals expected totals
#   S6 history inject    : REWORK sub-task executed via admin executeById
#                          -> full prompt in conversation_message contains
#                          the history section + timeline historySummary flag
# Mode:
#   -Scene S1|S2|S3|S4|S5|S6|all (default all)
#     NOTE: S3/S4/S6 depend on S1+S2 profile data; S5 depends on S1+S2.
# Ref: doc/HelloAI_迭代执行记录.md (Phase 1 反馈回路第 1 层)
#      .qoder/skills/helloai-preflight/SKILL.md (rule 6: UTF-8 BOM + single-quote concat)
# Preconditions: docker compose up -d (helloai-postgres:15432);
#                helloai-start running at :6565.
# Usage (repo root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-quality-profile.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-quality-profile.ps1 -Scene S1
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [ValidateSet('S1','S2','S3','S4','S5','S6','all')]
    [string]$Scene = 'all',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [int]$PollIntervalSec = 3,
    [int]$DispatchWaitSec = 60,
    # provider:model pairs for preset agents; must exist in llm_provider_model
    # and be role-free within the role (same role + same model is unique),
    # otherwise register pre-validation fails and the script aborts.
    [string]$ExecutorModelA = 'dashscope:qwen3.6-Flash',
    [string]$ExecutorModelB = 'dashscope:qwen3.7-plus',
    [string]$ReviewerModel  = 'moonshot:kimi-k3'
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

$execAName   = 'qp-exec-a'
$execBName   = 'qp-exec-b'
$reviewerName = 'qp-reviewer'

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
    # strip BOM possibly smuggled into here-strings (rule 6)
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

function Get-PsqlLines {
    param([Parameter(Mandatory=$true)][string]$Path)
    return @(Get-Content -Path $Path -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^\(' })
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
                Write-Host ('[agent] reuse ' + $Name + ' id=' + $agentId)
            }
        }
    }
    if (-not $agentId) {
        $regBody = @{ name = $Name; role = $RoleValue; description = 'verify-quality-profile preset agent'; accessType = $AccessType; modelType = $ModelType; idempotent = $true } | ConvertTo-Json -Depth 6
        $regResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/agents/register') -Body $regBody -Headers @{}
        if ($regResp.Code -ne 200) {
            Write-Host ('[agent] FAIL register ' + $Name + ' HTTP=' + $regResp.Code + ' body=' + $regResp.Body)
            return $null
        }
        $regJson = $null
        try { $regJson = $regResp.Body | ConvertFrom-Json } catch { }
        if ($regJson -and $regJson.data -and $regJson.data.id) {
            $agentId = $regJson.data.id
            $agentApiKey = $regJson.data.apiKey
            Write-Host ('[agent] registered ' + $Name + ' id=' + $agentId)
        }
    }
    if (-not $agentId) { return $null }
    if ([string]::IsNullOrEmpty($agentApiKey)) {
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
        Write-Host ('[agent] FAIL ' + $Name + ' apiKey empty (re-register manually or clean the agent)')
        return $null
    }
    # SQL fallback: force ACTIVE + correct model_type so reuse is idempotent
    # (same precedent as verify-reviewer-dual.ps1; register pre-validation
    # rejects legacy/gpt-4o model_type on the next run otherwise).
    $fixSql = "UPDATE agent SET status = 'ACTIVE', model_type = '" + $ModelType + "' WHERE id = " + $agentId + " AND deleted = 0;"
    $fixOut = Join-Path $scriptDir 'verify-quality-profile-agentfix.out'
    $null = Run-Psql -Sql $fixSql -OutFile $fixOut
    return @{ Id = $agentId; ApiKey = [string]$agentApiKey }
}

# ============================================================
# helper: MCP REST 直通工具调用（R 包装 {code,msg,data}）
# ============================================================
function Invoke-Tool {
    param([string]$ApiKey, [string]$ToolName, [hashtable]$ToolArgs = @{})
    # NOTE: parameter must NOT be named $Args -- $args is a PS automatic
    # variable (unbound-argument array); PS 5.1 then fails to bind the
    # hashtable default and throws ConvertToFinalInvalidCastException.
    $body = @{ jsonrpc = '2.0'; id = 1; method = 'tools/call'; params = @{ name = $ToolName; arguments = $ToolArgs } } | ConvertTo-Json -Depth 8
    return Invoke-Json -Method POST -Uri ($BaseUrl + '/api/mcp/jsonrpc') -Body $body -Headers @{ 'Authorization' = ('Bearer ' + $ApiKey) }
}

function Get-ToolResult {
    param([object]$Resp, [string]$Scenario)
    if ($Resp.Code -ne 200) {
        Write-Host ('[' + $Scenario + '] FAIL : HTTP=' + $Resp.Code + ' body=' + $Resp.Body)
        return $null
    }
    $json = $null
    try { $json = $Resp.Body | ConvertFrom-Json } catch { }
    if (-not $json) {
        Write-Host ('[' + $Scenario + '] FAIL : invalid json body=' + $Resp.Body)
        return $null
    }
    if ($json.error) {
        Write-Host ('[' + $Scenario + '] FAIL : jsonrpc error=' + ($json.error | ConvertTo-Json -Compress -Depth 6))
        return $null
    }
    return $json.result
}

# ============================================================
# helper: 建任务（白名单）+ 建子任务 + 重置 PENDING + 状态查询
# ============================================================
function New-TaskWithWhitelist {
    param([string]$Title, [long[]]$ExecutorIds, [string]$AdminToken)
    $findSql = "SELECT id FROM task WHERE title = '" + $Title + "' AND deleted = 0 LIMIT 1;"
    $findFile = Join-Path $scriptDir 'verify-quality-profile-find.out'
    $null = Run-Psql -Sql $findSql -OutFile $findFile
    $findLine = Get-PsqlFields -Path $findFile
    if ($findLine -and $findLine.Split('|')[0]) {
        $residualId = $findLine.Split('|')[0]
        Write-Host ('[preset] cleanup residual task id=' + $residualId)
        $delBody = '{"confirmTitle":"' + $Title + '"}'
        $null = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/deleteById/' + $residualId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    }
    $policy = ''
    if ($ExecutorIds -and $ExecutorIds.Count -gt 0) {
        $ids = ($ExecutorIds | ForEach-Object { [string]$_ }) -join ','
        $policy = ',"agentPolicy":{"executorAgentIds":[' + $ids + ']}'
    }
    $taskBody = '{"title":"' + $Title + '","description":"verify-quality-profile preset task"' + $policy + '}'
    $taskResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks') -Body $taskBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    if ($taskResp.Code -ne 200) {
        Write-Host ('[preset] FAIL create task HTTP=' + $taskResp.Code + ' body=' + $taskResp.Body)
        return $null
    }
    $taskJson = $null
    try { $taskJson = $taskResp.Body | ConvertFrom-Json } catch { }
    if (-not $taskJson -or $taskJson.code -ne 200 -or -not $taskJson.data.id) {
        Write-Host ('[preset] FAIL create task biz: ' + $taskResp.Body)
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
        Write-Host ('[preset] FAIL create sub-task HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    if (-not $json -or $json.code -ne 200 -or -not $json.data.id) {
        Write-Host ('[preset] FAIL create sub-task biz: ' + $resp.Body)
        return $null
    }
    return [string]$json.data.id
}

function Reset-Pending {
    param([string]$SubTaskId)
    $sql = "UPDATE sub_task SET status = 'PENDING', assigned_agent_id = NULL, update_by = 'qp-preset' WHERE id = " + $SubTaskId + " AND status <> 'PENDING';"
    $out = Join-Path $scriptDir 'verify-quality-profile-reset.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) {
        Write-Host ('[preset] FAIL reset pending rc=' + $rc)
    }
}

function Set-SubTaskReview {
    param([string]$SubTaskId, [string]$AgentId)
    $sql = "UPDATE sub_task SET status = 'REVIEW', assigned_agent_id = " + $AgentId + ", update_by = 'qp-preset' WHERE id = " + $SubTaskId + ";"
    $out = Join-Path $scriptDir 'verify-quality-profile-setreview.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) {
        Write-Output ('[preset] FAIL set REVIEW rc=' + $rc)
    }
}

function Get-SubTaskState {
    param([string]$SubTaskId)
    $sql = "SELECT status || '|' || COALESCE(assigned_agent_id::text, 'NULL') || '|' || rework_count FROM sub_task WHERE id = " + $SubTaskId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-quality-profile-state.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

function Get-ProfileRow {
    param([string]$AgentId)
    $sql = "SELECT reviewed_count || '|' || approved_count || '|' || first_reviewed_count || '|' || first_pass_count || '|' || total_score || '|' || rework_round_sum || '|' || COALESCE(last_review_record_id::text,'NULL') FROM agent_quality_profile WHERE agent_id = " + $AgentId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-quality-profile-prof.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

function Get-DefectCount {
    param([string]$AgentId, [string]$Label)
    $sql = "SELECT COALESCE(issue_defect_stats->>'" + $Label + "','0') FROM agent_quality_profile WHERE agent_id = " + $AgentId + " AND deleted = 0;"
    $out = Join-Path $scriptDir 'verify-quality-profile-defect.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '0' }
    return $line
}

function Remove-Task {
    param([string]$TaskId, [string]$Title, [string]$AdminToken)
    $delBody = '{"confirmTitle":"' + $Title + '"}'
    $null = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/deleteById/' + $TaskId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
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
# STEP A1: admin login
# ============================================================
Write-Output ''
Write-Output '=== [A1] admin login ==='
$loginBody = '{"type":"admin","username":"' + $AdminUsername + '","credential":"' + $AdminPassword + '"}'
$loginResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/auth/login') -Body $loginBody
$loginJson = $null
try { $loginJson = $loginResp.Body | ConvertFrom-Json } catch { }
$adminToken = if ($loginJson -and $loginJson.data -and $loginJson.data.token) { $loginJson.data.token } else { $null }
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Output ('FAIL : admin login failed: ' + $loginResp.Body)
    exit 1
}
Write-Output '[A1] admin token acquired'

# ============================================================
# STEP A1.5: enable admin quality endpoints gate
#   AdminQualityController 配置门控（生产默认关闭）：sys config
#   admin.quality.enabled=true 才开放 rebuild/dispatch/spec-section。
#   详见评审整改计划阶段四-4 与 AdminQualityController Javadoc。
# ============================================================
Write-Output ''
Write-Output '=== [A1.5] enable admin quality gate ==='
$gateBody = '{"value":"true"}'
$gateResp = Invoke-Json -Method PUT -Uri ($BaseUrl + '/api/admin/config/updateByKey/admin.quality.enabled') -Body $gateBody -Headers @{ 'X-Admin-Token' = $adminToken }
Assert-Pass ($gateResp.Code -eq 200) 'A1.5-quality-gate' ('PUT /api/admin/config/updateByKey/admin.quality.enabled HTTP=' + $gateResp.Code + ' body=' + $gateResp.Body)


# ============================================================
# ensure preset agents (idempotent, fixed names)
# ============================================================
Write-Output ''
Write-Output '=== [agents] ensure preset test agents ==='
$execA = Ensure-TestAgent -Name $execAName -RoleValue 'EXECUTOR' -AccessType 'CLI_CLIENT' -ModelType $ExecutorModelA -AdminToken $adminToken
$execB = Ensure-TestAgent -Name $execBName -RoleValue 'EXECUTOR' -AccessType 'CLI_CLIENT' -ModelType $ExecutorModelB -AdminToken $adminToken
$reviewer = Ensure-TestAgent -Name $reviewerName -RoleValue 'REVIEWER' -AccessType 'CLI_CLIENT' -ModelType $ReviewerModel -AdminToken $adminToken
if (-not $execA -or -not $execB -or -not $reviewer) {
    Write-Output 'FAIL : preset agents unavailable'
    exit 1
}
Write-Output ('[agents] exec-a=' + $execA.Id + ' exec-b=' + $execB.Id + ' reviewer=' + $reviewer.Id)

$execAId = [string]$execA.Id
$execBId = [string]$execB.Id
$reviewerId = [string]$reviewer.Id

# ============================================================
# idempotent cleanup: profile rows + stale leases for both executors
# ============================================================
$cleanSql = "DELETE FROM agent_quality_profile WHERE agent_id IN (" + $execAId + ',' + $execBId + ") AND deleted = 0; UPDATE agent_duty_lease SET status = 'CLOSED', update_by = 'qp-preset' WHERE agent_id IN (" + $execAId + ',' + $execBId + ") AND status = 'ACTIVE';"
$cleanOut = Join-Path $scriptDir 'verify-quality-profile-clean.out'
$cleanRc = Run-Psql -Sql $cleanSql -OutFile $cleanOut
if ($cleanRc -ne 0) {
    Write-Output ('[cleanup] FAIL clean profile/lease rc=' + $cleanRc)
    exit 1
}
Write-Output '[cleanup] profile rows removed + stale leases closed'

# task ids for teardown
$script:TaskIdS1 = ''
$script:TaskIdS3 = ''
$script:TaskIdS6 = ''

# ============================================================
# S1: first APPROVED review -> initial profile row
# ============================================================
function Run-Scenario1 {
    Write-Output ''
    Write-Output '=== [S1] profile increment: first APPROVED review ==='
    $taskTitle = 'qp-s1-profile-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds @() -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S1' 'task preset failed'; return }
    $script:TaskIdS1 = $taskId
    $sub1Id = New-SubTask -TaskId $taskId -Title 'qp-s1-sub' -AssignedAgent ([long]$execAId) -AdminToken $adminToken
    if (-not $sub1Id) { Assert-Pass $false 'S1' 'sub-task preset failed'; return }
    Set-SubTaskReview -SubTaskId $sub1Id -AgentId $execAId
    $state1 = Get-SubTaskState -SubTaskId $sub1Id
    Write-Output ('[S1] preset subTaskId=' + $sub1Id + ' state=' + $state1)
    Assert-Pass ($state1 -and $state1.StartsWith('REVIEW')) 'S1-preset' ('sub-task forced REVIEW, actual=' + $state1)

    $reviewBody = '{"subTaskId":' + $sub1Id + ',"result":"APPROVED","score":5,"comment":"qp-s1 approve"}'
    $revResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/reviews') -Body $reviewBody -Headers @{ 'X-Admin-Token' = $adminToken; 'X-Agent-Id' = $reviewerId }
    Assert-Pass ($revResp.Code -eq 200) 'S1-review-http' ('POST /api/reviews HTTP=' + $revResp.Code)

    $prof = Get-ProfileRow -AgentId $execAId
    Write-Output ('[S1] profile row=' + $prof)
    $parts = @($prof.Split('|'))
    if ($parts.Count -ge 7) {
        Assert-Pass ($parts[0] -eq '1') 'S1-reviewed' ('reviewed_count=1, actual=' + $parts[0])
        Assert-Pass ($parts[1] -eq '1') 'S1-approved' ('approved_count=1, actual=' + $parts[1])
        Assert-Pass ($parts[2] -eq '1') 'S1-first-reviewed' ('first_reviewed_count=1, actual=' + $parts[2])
        Assert-Pass ($parts[3] -eq '1') 'S1-first-pass' ('first_pass_count=1, actual=' + $parts[3])
        Assert-Pass ($parts[4] -eq '5') 'S1-total-score' ('total_score=5, actual=' + $parts[4])
        Assert-Pass ($parts[5] -eq '0') 'S1-rework' ('rework_round_sum=0, actual=' + $parts[5])
        Assert-Pass ($parts[6] -ne 'NULL' -and $parts[6] -ne '') 'S1-last-id' ('last_review_record_id=' + $parts[6])
    } else {
        Assert-Pass $false 'S1-profile' ('profile row missing or malformed: ' + $prof)
    }
}

# ============================================================
# S2: two-round REJECTED -> first-pass / rework / defect metrics
# ============================================================
function Run-Scenario2 {
    Write-Output ''
    Write-Output '=== [S2] metric semantics: first-pass rate + rework + defect tags ==='
    $sub2Id = New-SubTask -TaskId $script:TaskIdS1 -Title 'qp-s2-sub' -AssignedAgent ([long]$execAId) -AdminToken $adminToken
    if (-not $sub2Id) { Assert-Pass $false 'S2' 'sub-task preset failed'; return }

    # round 1: REJECTED score=3 with one defect tag
    Set-SubTaskReview -SubTaskId $sub2Id -AgentId $execAId
    $issuesR1 = '[defect] missing-unit-test [location] service [impact] coverage gap [evidence] none'
    $revBody1 = '{"subTaskId":' + $sub2Id + ',"result":"REJECTED","score":3,"issues":"' + $issuesR1 + '","comment":"qp-s2 reject r1"}'
    $revResp1 = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/reviews') -Body $revBody1 -Headers @{ 'X-Admin-Token' = $adminToken; 'X-Agent-Id' = $reviewerId }
    Assert-Pass ($revResp1.Code -eq 200) 'S2-review-r1-http' ('round1 reject HTTP=' + $revResp1.Code)

    # round 2: force REVIEW again (reject moved state to REWORK), same defect tag
    Set-SubTaskReview -SubTaskId $sub2Id -AgentId $execAId
    $issuesR2 = '[defect] missing-unit-test [location] service [impact] coverage gap [evidence] none'
    $revBody2 = '{"subTaskId":' + $sub2Id + ',"result":"REJECTED","score":2,"issues":"' + $issuesR2 + '","comment":"qp-s2 reject r2"}'
    $revResp2 = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/reviews') -Body $revBody2 -Headers @{ 'X-Admin-Token' = $adminToken; 'X-Agent-Id' = $reviewerId }
    Assert-Pass ($revResp2.Code -eq 200) 'S2-review-r2-http' ('round2 reject HTTP=' + $revResp2.Code)

    $prof = Get-ProfileRow -AgentId $execAId
    Write-Output ('[S2] profile row=' + $prof)
    $parts = @($prof.Split('|'))
    if ($parts.Count -ge 7) {
        Assert-Pass ($parts[0] -eq '3') 'S2-reviewed' ('reviewed_count=3, actual=' + $parts[0])
        Assert-Pass ($parts[1] -eq '1') 'S2-approved' ('approved_count=1, actual=' + $parts[1])
        Assert-Pass ($parts[2] -eq '2') 'S2-first-reviewed' ('first_reviewed_count=2, actual=' + $parts[2])
        Assert-Pass ($parts[3] -eq '1') 'S2-first-pass' ('first_pass_count=1, actual=' + $parts[3])
        Assert-Pass ($parts[4] -eq '10') 'S2-total-score' ('total_score=10, actual=' + $parts[4])
        Assert-Pass ($parts[5] -eq '1') 'S2-rework' ('rework_round_sum=1 (round2 contributes 1), actual=' + $parts[5])
    } else {
        Assert-Pass $false 'S2-profile' ('profile row missing or malformed: ' + $prof)
    }
    $defectCount = Get-DefectCount -AgentId $execAId -Label 'missing-unit-test'
    Assert-Pass ($defectCount -eq '2') 'S2-defect-tags' ('issue_defect_stats[missing-unit-test]=2, actual=' + $defectCount)
}

# ============================================================
# S3: qualityRank feedback into dispatch selection
# ============================================================
function Run-Scenario3 {
    Write-Output ''
    Write-Output '=== [S3] qualityRank selection: profile-rich exec-a wins over exec-b ==='

    # 1) neutralize base score: both agents score=50 (qualityRank becomes decisive)
    $scoreSql = "UPDATE agent SET score = 50, update_by = 'qp-preset' WHERE id IN (" + $execAId + ',' + $execBId + ');'
    $scoreOut = Join-Path $scriptDir 'verify-quality-profile-score.out'
    $scoreRc = Run-Psql -Sql $scoreSql -OutFile $scoreOut
    Assert-Pass ($scoreRc -eq 0) 'S3-score-preset' ('both agents score=50, rc=' + $scoreRc)

    # 2) dual checkIn WITHOUT ttlMinutes (dynamic TTL path also feeds S4)
    $ciA = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'checkIn' -ToolArgs @{ workMode = 'AUTO'; maxConcurrent = 3 }) -Scenario 'S3-checkIn-a'
    $ciB = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execB.ApiKey -ToolName 'checkIn' -ToolArgs @{ workMode = 'AUTO'; maxConcurrent = 3 }) -Scenario 'S3-checkIn-b'
    if (-not $ciA -or -not $ciB) {
        Assert-Pass $false 'S3-lease' 'dual checkIn failed'
        return
    }
    Write-Output ('[S3] exec-a leaseId=' + $ciA.leaseId + ' expiresAt=' + $ciA.expiresAt)
    Write-Output ('[S3] exec-b leaseId=' + $ciB.leaseId + ' expiresAt=' + $ciB.expiresAt)

    # 3) whitelist task + PENDING sub-task (no assigned agent)
    $taskTitle = 'qp-s3-dispatch-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds @([long]$execAId, [long]$execBId) -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S3' 'task preset failed'; return }
    $script:TaskIdS3 = $taskId
    $sub3Id = New-SubTask -TaskId $taskId -Title 'qp-s3-auto-sub' -AssignedAgent 0 -AdminToken $adminToken
    if (-not $sub3Id) { Assert-Pass $false 'S3' 'sub-task preset failed'; return }
    Reset-Pending -SubTaskId $sub3Id
    $state3 = Get-SubTaskState -SubTaskId $sub3Id
    Write-Output ('[S3] preset subTaskId=' + $sub3Id + ' state=' + $state3)
    Assert-Pass ($state3 -and $state3.StartsWith('PENDING')) 'S3-preset' ('sub-task PENDING, actual=' + $state3)

    # 4) dispatch via admin endpoint -> qualityRank feedback visible in assignment
    $dispResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/admin/quality/dispatchById/' + $sub3Id) -Headers @{ 'X-Admin-Token' = $adminToken }
    Assert-Pass ($dispResp.Code -eq 200) 'S3-dispatch-http' ('POST /api/admin/quality/dispatchById HTTP=' + $dispResp.Code + ' body=' + $dispResp.Body)

    $finalState = Wait-Until -Condition {
        param($id, $want)
        $s = Get-SubTaskState $id
        if (-not $s) { return $null }
        $p = $s.Split('|')
        if ($p[1] -eq $want) { return $s }
        return $null
    } -TimeoutSec $DispatchWaitSec -Label 'S3 assigned to exec-a' -ArgList @($sub3Id, $execAId)
    $stateAfter = Get-SubTaskState -SubTaskId $sub3Id
    Write-Output ('[S3] state after dispatch=' + $stateAfter)
    $partsAfter = @($stateAfter.Split('|'))
    if ($partsAfter.Count -ge 2) {
        Assert-Pass ($partsAfter[0] -eq 'ASSIGNED') 'S3-assigned-status' ('status=ASSIGNED, actual=' + $partsAfter[0])
        Assert-Pass ($partsAfter[1] -eq $execAId) 'S3-quality-rank-winner' ('assigned to exec-a (qualityRank winner), actual=' + $partsAfter[1])
    } else {
        Assert-Pass $false 'S3-quality-rank-winner' 'state query failed'
    }
}

# ============================================================
# S4: dynamic TTL composite score (leases from S3 checkIn)
# ============================================================
function Run-Scenario4 {
    Write-Output ''
    Write-Output '=== [S4] dynamic TTL composite score ==='
    $leaseSql = "SELECT agent_id || '|' || ROUND(EXTRACT(EPOCH FROM (expire_time - start_time)) / 60)::int FROM (SELECT DISTINCT ON (agent_id) agent_id, start_time, expire_time FROM agent_duty_lease WHERE agent_id IN (" + $execAId + ',' + $execBId + ") AND status = 'ACTIVE' AND deleted = 0 ORDER BY agent_id, id DESC) t ORDER BY agent_id;"
    $leaseOut = Join-Path $scriptDir 'verify-quality-profile-lease.out'
    $leaseRc = Run-Psql -Sql $leaseSql -OutFile $leaseOut
    Assert-Pass ($leaseRc -eq 0) 'S4-lease-query' ('lease query rc=' + $leaseRc)
    $leaseLines = Get-PsqlLines -Path $leaseOut
    Write-Output ('[S4] lease rows=' + ($leaseLines -join ' ; '))
    $ttlA = $null
    $ttlB = $null
    foreach ($line in $leaseLines) {
        $lp = $line.Split('|')
        if ($lp.Count -ge 2) {
            if ($lp[0] -eq $execAId) { $ttlA = [int]$lp[1] }
            elseif ($lp[0] -eq $execBId) { $ttlB = [int]$lp[1] }
        }
    }
    if ($ttlA -ne $null -and $ttlB -ne $null) {
        # expected: exec-a composite = 50 + round(54*0.1) = 55 -> TTL = 5 + 235*55/100 = 134
        #           exec-b no profile -> performance 50 -> TTL = 5 + 235*50/100 = 122
        Assert-Pass (($ttlA -ge 132 -and $ttlA -le 136)) 'S4-ttl-a' ('exec-a TTL=' + $ttlA + ' expected ~134')
        Assert-Pass (($ttlB -ge 120 -and $ttlB -le 124)) 'S4-ttl-b' ('exec-b TTL=' + $ttlB + ' expected ~122')
        Assert-Pass (($ttlA - $ttlB) -ge 8) 'S4-ttl-diff' ('exec-a TTL longer by ' + ($ttlA - $ttlB) + ' min (expect >= 8)')
    } else {
        Assert-Pass $false 'S4-ttl' ('lease TTL rows missing: a=' + $ttlA + ' b=' + $ttlB)
    }
}

# ============================================================
# S5: rebuild reconcile (SQL-inserted 4th review bypassing increment)
# ============================================================
function Run-Scenario5 {
    Write-Output ''
    Write-Output '=== [S5] rebuild reconcile: full recompute equals expected totals ==='

    # find sub-task ids created in S1/S2 under task S1
    $subSql = "SELECT id FROM sub_task WHERE task_id = " + $script:TaskIdS1 + " AND title = 'qp-s2-sub' AND deleted = 0 LIMIT 1;"
    $subOut = Join-Path $scriptDir 'verify-quality-profile-s5-sub.out'
    $null = Run-Psql -Sql $subSql -OutFile $subOut
    $sub2Line = Get-PsqlFields -Path $subOut
    if (-not $sub2Line) {
        Assert-Pass $false 'S5' 'qp-s2-sub not found (run S1+S2 first)'
        return
    }
    $sub2Id = $sub2Line.Split('|')[0]

    # direct insert round=3 REJECTED review (bypasses increment path)
    $maxSql = 'SELECT COALESCE(MAX(id),0)+1 FROM review_record;'
    $maxOut = Join-Path $scriptDir 'verify-quality-profile-s5-max.out'
    $null = Run-Psql -Sql $maxSql -OutFile $maxOut
    $maxLine = Get-PsqlFields -Path $maxOut
    if (-not $maxLine) { Assert-Pass $false 'S5' 'max id query failed'; return }
    $newRid = $maxLine.Split('|')[0]
    $insSql = "INSERT INTO review_record (id, sub_task_id, reviewer_agent_id, result, score, issues, comment, round, create_by, update_by) VALUES (" + $newRid + ', ' + $sub2Id + ', ' + $reviewerId + ", 'REJECTED', 1, '[defect] missing-docs [location] README [impact] doc gap [evidence] none', 'qp-s5 direct insert', 3, 'verify-qp', 'verify-qp');"
    $insOut = Join-Path $scriptDir 'verify-quality-profile-s5-ins.out'
    $insRc = Run-Psql -Sql $insSql -OutFile $insOut
    Assert-Pass ($insRc -eq 0) 'S5-direct-insert' ('direct review_record insert id=' + $newRid + ' rc=' + $insRc)

    # rebuild via admin endpoint
    $rebResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/admin/quality/rebuildById/' + $execAId) -Headers @{ 'X-Admin-Token' = $adminToken }
    Assert-Pass ($rebResp.Code -eq 200) 'S5-rebuild-http' ('POST /api/admin/quality/rebuildById HTTP=' + $rebResp.Code)

    $prof = Get-ProfileRow -AgentId $execAId
    Write-Output ('[S5] profile row after rebuild=' + $prof)
    $parts = @($prof.Split('|'))
    if ($parts.Count -ge 7) {
        Assert-Pass ($parts[0] -eq '4') 'S5-reviewed' ('reviewed_count=4, actual=' + $parts[0])
        Assert-Pass ($parts[1] -eq '1') 'S5-approved' ('approved_count=1, actual=' + $parts[1])
        Assert-Pass ($parts[4] -eq '11') 'S5-total-score' ('total_score=11 (5+3+2+1), actual=' + $parts[4])
        Assert-Pass ($parts[5] -eq '3') 'S5-rework' ('rework_round_sum=3 (round2=1 + round3=2), actual=' + $parts[5])
        Assert-Pass ($parts[6] -eq $newRid) 'S5-last-id' ('last_review_record_id=' + $parts[6] + ' expect ' + $newRid)
    } else {
        Assert-Pass $false 'S5-profile' ('profile row missing or malformed: ' + $prof)
    }
    $defectUnit = Get-DefectCount -AgentId $execAId -Label 'missing-unit-test'
    $defectDocs = Get-DefectCount -AgentId $execAId -Label 'missing-docs'
    Assert-Pass ($defectUnit -eq '2') 'S5-defect-unit' ('missing-unit-test=2, actual=' + $defectUnit)
    Assert-Pass ($defectDocs -eq '1') 'S5-defect-docs' ('missing-docs=1, actual=' + $defectDocs)
}

# ============================================================
# S6: history inject (layer-2): profile summary -> executor prompt
# ============================================================
function Run-Scenario6 {
    Write-Output ''
    Write-Output '=== [S6] history inject: profile summary rendered into executor prompt ==='

    # 中文断言标记（单引号逐字串 + 变量拼接，规避 PS 5.1 双引号-CJK 解析陷阱）
    $markHeading = '你的历史表现'
    $markStats   = '累计评审 \d+ 次'
    $markRemind  = '本轮提醒'

    # P0: depends on S1+S2 profile data
    $prof = Get-ProfileRow -AgentId $execAId
    if (-not $prof) {
        Assert-Skip 'S6' 'exec-a profile missing (run S1+S2 first)'
        return
    }
    Write-Output ('[S6] profile present: ' + $prof)

    # 1) preset task + sub-task assigned to exec-a, then force REWORK
    #    (REWORK = real rework workbench state, accepted by executeById)
    $taskTitle = 'qp-s6-history-task'
    $taskId = New-TaskWithWhitelist -Title $taskTitle -ExecutorIds @([long]$execAId) -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S6' 'task preset failed'; return }
    $script:TaskIdS6 = $taskId
    $sub6Id = New-SubTask -TaskId $taskId -Title 'qp-s6-history-sub' -AssignedAgent ([long]$execAId) -AdminToken $adminToken
    if (-not $sub6Id) { Assert-Pass $false 'S6' 'sub-task preset failed'; return }
    $rwSql = "UPDATE sub_task SET status = 'REWORK', update_by = 'qp-preset' WHERE id = " + $sub6Id + ' AND deleted = 0;'
    $rwOut = Join-Path $scriptDir 'verify-quality-profile-s6-rw.out'
    $rwRc = Run-Psql -Sql $rwSql -OutFile $rwOut
    Assert-Pass ($rwRc -eq 0) 'S6-preset-rework' ('sub-task forced REWORK rc=' + $rwRc)

    # 2) trigger execution via admin endpoint (creates ExecutionCommand,
    #    consumed asynchronously by local consumer / DB poller)
    $execResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/sub-tasks/executeById/' + $sub6Id) -Headers @{ 'X-Admin-Token' = $adminToken }
    if ($execResp.Code -ne 200) {
        Assert-Skip 'S6' ('executeById HTTP=' + $execResp.Code + ' body=' + $execResp.Body + ' (execution chain unavailable)')
        return
    }
    Write-Output ('[S6] executeById HTTP 200 body=' + $execResp.Body)

    # 3) wait for the full user prompt to land in conversation_message
    #    (addMessage runs BEFORE executeSync -> prompt persists even if the
    #    LLM call itself fails, making the assertion chain-stable)
    $promptContent = Wait-Until -Condition {
        param($id)
        # NOTE: prompt is multi-line; Get-PsqlFields only keeps the first
        # line, so collapse whitespace in SQL (POSIX [[:space:]] class) to
        # make the whole prompt a single row before reading it back.
        $sqlP = "SELECT regexp_replace(content, '[[:space:]]+', ' ', 'g') FROM conversation_message WHERE sub_task_id = " + $id + " AND tool_name = 'sub_task_execute_user_prompt' ORDER BY seq DESC LIMIT 1;"
        $outP = Join-Path $scriptDir 'verify-quality-profile-s6-prompt.out'
        $rcP = Run-Psql -Sql $sqlP -OutFile $outP
        if ($rcP -ne 0) { return $null }
        $lineP = Get-PsqlFields -Path $outP
        if (-not $lineP) { return $null }
        return $lineP
    } -TimeoutSec $DispatchWaitSec -Label 'S6 prompt landed in conversation_message' -ArgList @($sub6Id)

    if (-not $promptContent) {
        # distinguish unavailable chain from real failure: if no execution
        # record was ever created, the local consumer is not running (SKIP);
        # if it exists but prompt is missing, that is a real defect (FAIL)
        $exeSql = "SELECT COALESCE(MAX(id),0) FROM agent_execution_record WHERE sub_task_id = " + $sub6Id + ';'
        $exeOut = Join-Path $scriptDir 'verify-quality-profile-s6-exe.out'
        $exeRc = Run-Psql -Sql $exeSql -OutFile $exeOut
        $exeMax = '<err>'
        if ($exeRc -eq 0) { $exeMax = Get-PsqlFields -Path $exeOut }
        if ($exeMax -eq '0') {
            Assert-Skip 'S6' 'execution record never created (local consumer chain not active)'
            return
        }
        Assert-Pass $false 'S6-prompt-landed' ('prompt missing in conversation_message though execution record id=' + $exeMax + ' exists')
        return
    }

    # 4) history section markers inside the actual prompt
    $prompt = [string]$promptContent
    Assert-Pass ($prompt.Contains($markHeading)) 'S6-history-heading' ('prompt contains ' + $markHeading)
    Assert-Pass ($prompt -match $markStats) 'S6-history-stats' ('prompt contains reviewed-count summary line')
    Assert-Pass ($prompt.Contains($markRemind)) 'S6-history-remind' ('prompt contains self-check reminder line')

    # 5) timeline observability flag historySummary=true
    $tlSql = "SELECT payload ->> 'historySummary' FROM task_timeline WHERE sub_task_id = " + $sub6Id + " AND event_type = 'sub_task_spec_context_loaded' AND deleted = 0 ORDER BY id DESC LIMIT 1;"
    $tlOut = Join-Path $scriptDir 'verify-quality-profile-s6-tl.out'
    $tlRc = Run-Psql -Sql $tlSql -OutFile $tlOut
    $tlFlag = ''
    if ($tlRc -eq 0) { $tlFlag = Get-PsqlFields -Path $tlOut }
    Assert-Pass ($tlFlag -eq 'true') 'S6-timeline-flag' ('historySummary=true in task_timeline, actual=' + $tlFlag)
}

# ============================================================
# run selected scenarios
# ============================================================
if ($Scene -eq 'all' -or $Scene -eq 'S1') { Run-Scenario1 }
if ($Scene -eq 'all' -or $Scene -eq 'S2') { Run-Scenario2 }
if ($Scene -eq 'all' -or $Scene -eq 'S3') { Run-Scenario3 }
if ($Scene -eq 'all' -or $Scene -eq 'S4') { Run-Scenario4 }
if ($Scene -eq 'all' -or $Scene -eq 'S5') { Run-Scenario5 }
if ($Scene -eq 'all' -or $Scene -eq 'S6') { Run-Scenario6 }

# ============================================================
# teardown
# ============================================================
Write-Output ''
Write-Output '=== [teardown] ==='
if ($script:TaskIdS1) {
    $revClean = 'DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS1 + ');'
    $revCleanOut = Join-Path $scriptDir 'verify-quality-profile-revclean.out'
    $null = Run-Psql -Sql $revClean -OutFile $revCleanOut
    Remove-Task -TaskId $script:TaskIdS1 -Title 'qp-s1-profile-task' -AdminToken $adminToken
    Write-Output ('[teardown] task S1 removed id=' + $script:TaskIdS1)
}
if ($script:TaskIdS3) {
    Remove-Task -TaskId $script:TaskIdS3 -Title 'qp-s3-dispatch-task' -AdminToken $adminToken
    Write-Output ('[teardown] task S3 removed id=' + $script:TaskIdS3)
}
if ($script:TaskIdS6) {
    $msgClean = 'DELETE FROM conversation_message WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $script:TaskIdS6 + ');'
    $msgCleanOut = Join-Path $scriptDir 'verify-quality-profile-msgclean.out'
    $null = Run-Psql -Sql $msgClean -OutFile $msgCleanOut
    Remove-Task -TaskId $script:TaskIdS6 -Title 'qp-s6-history-task' -AdminToken $adminToken
    Write-Output ('[teardown] task S6 removed id=' + $script:TaskIdS6)
}
$null = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execA.ApiKey -ToolName 'checkOut' -ToolArgs @{ closeReason = 'qp-verify-done' }) -Scenario 'teardown-checkOut-a'
$null = Get-ToolResult -Resp (Invoke-Tool -ApiKey $execB.ApiKey -ToolName 'checkOut' -ToolArgs @{ closeReason = 'qp-verify-done' }) -Scenario 'teardown-checkOut-b'
Write-Output '[teardown] both agents checked out'

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
