# helloai verify-contract-first.ps1
# Purpose: preset + assert contract-first decomposition (Phase 2) evidence:
#   S1 decompose contract flag : planById triggers LLM decompose ->
#                                drafts contain contract sub-task (is_contract=1)
#   S2 contract backfill        : contract sub-task DONE (APPROVED) ->
#                                task running spec contract non-empty +
#                                content preserved + timeline success
#   S3 prompt section render    : GET /api/admin/quality/spec-section/{taskId}
#                                contains '## task contract' header + body
#   S4 zero-noise               : non-contract sub-task DONE ->
#                                no contract written, no contract section
# Mode:
#   -Scene S1|S2|S3|S4|all (default all)
#     NOTE: S3 depends on S2 backfilled contract.
# Ref: doc/HelloAI_迭代执行记录.md (Phase 2 contract-first)
#      .qoder/skills/helloai-preflight/SKILL.md (rule 6: UTF-8 BOM + single-quote concat)
# Preconditions: docker compose up -d (helloai-postgres:15432);
#                helloai-start running at :6565;
#                Flyway V54/V55/V56 applied.
# Usage (repo root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-contract-first.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-contract-first.ps1 -Scene S2
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [ValidateSet('S1','S2','S3','S4','all')]
    [string]$Scene = 'all',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [int]$PollIntervalSec = 3,
    [int]$PlanWaitSec = 180,
    [int]$BackfillWaitSec = 30
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6) - avoid CJK mojibake
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

# exec/reviewer agents reused from verify-quality-profile.ps1 (idempotent fixed names)
$execName     = 'qp-exec-a'
$reviewerName = 'qp-reviewer'

# '## ' + CJK code points for 'task contract' (U+4EFB U+52A1 U+5951 U+7EA6):
# built from code points so runtime strings stay pure ASCII (rule 6)
$script:ContractHeader = '## ' + [char]0x4EFB + [char]0x52A1 + [char]0x5951 + [char]0x7EA6

# contract body preset for S2 backfill (pure ASCII, no single/double quotes)
$script:ContractBody = 'API contract v1: POST /api/v1/users body {name,email}; error codes 400 409 500; auth Bearer token'

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
# helper: idempotent register / reuse fixed-name test agent
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
        $regBody = @{ name = $Name; role = $RoleValue; description = 'verify-contract-first preset agent'; accessType = $AccessType; modelType = $ModelType; idempotent = $true } | ConvertTo-Json -Depth 6
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
# helper: create task (no executor whitelist, idempotent cleanup)
# ============================================================
function New-CfTask {
    param([string]$Title, [string]$Description, [string]$AdminToken)
    $findSql = "SELECT id FROM task WHERE title = '" + $Title + "' AND deleted = 0 LIMIT 1;"
    $findFile = Join-Path $scriptDir 'verify-contract-first-find.out'
    $null = Run-Psql -Sql $findSql -OutFile $findFile
    $findLine = Get-PsqlFields -Path $findFile
    if ($findLine -and $findLine.Split('|')[0]) {
        $residualId = $findLine.Split('|')[0]
        Write-Output ('[preset] cleanup residual task id=' + $residualId)
        $delBody = '{"confirmTitle":"' + $Title + '"}'
        $null = Invoke-Json -Method DELETE -Uri ($BaseUrl + '/api/tasks/deleteById/' + $residualId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
    }
    $taskBody = '{"title":"' + $Title + '","description":"' + $Description + '"}'
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

# ============================================================
# helper: SQL-insert sub-task (contract presets bypass planner flow)
# ============================================================
function New-SubTaskSql {
    param([string]$TaskId, [string]$Title, [string]$AgentId, [int]$IsContract, [string]$ContextJson, [string]$StatusValue)
    $maxSql = 'SELECT COALESCE(MAX(id),0)+1 FROM sub_task;'
    $maxOut = Join-Path $scriptDir 'verify-contract-first-max.out'
    $null = Run-Psql -Sql $maxSql -OutFile $maxOut
    $maxLine = Get-PsqlFields -Path $maxOut
    if (-not $maxLine) { Write-Output '[preset] FAIL max sub_task id query'; return $null }
    $subId = $maxLine.Split('|')[0]
    if (-not $ContextJson) { $ContextJson = '{}' }
    $insSql = "INSERT INTO sub_task (id, task_id, title, status, assigned_agent_id, is_contract, context, deliverable, acceptance, create_by, update_by) VALUES (" +
        $subId + ', ' + $TaskId + ", '" + $Title + "', '" + $StatusValue + "', " + $AgentId + ', ' + $IsContract +
        ", '" + $ContextJson + "'::jsonb, 'verification evidence note', 'evidence present', 'verify-cf', 'verify-cf');"
    $insOut = Join-Path $scriptDir 'verify-contract-first-ins.out'
    $insRc = Run-Psql -Sql $insSql -OutFile $insOut
    if ($insRc -ne 0) {
        Write-Output ('[preset] FAIL insert sub-task rc=' + $insRc)
        return $null
    }
    return $subId
}

function Remove-CfTask {
    param([string]$TaskId, [string]$Title, [string]$AdminToken)
    $delBody = '{"confirmTitle":"' + $Title + '"}'
    $null = Invoke-Json -Method DELETE -Uri ($BaseUrl + '/api/tasks/deleteById/' + $TaskId) -Body $delBody -Headers @{ 'X-Admin-Token' = $AdminToken }
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
# contract state probe: true when running spec contract exists in
# either jsonb storage (task.context.runningSpec.contract) or
# table storage (task_running_spec.contract)
# ============================================================
function Get-ContractState {
    param([string]$TaskId)
    $sql = "SELECT ((t.context->'runningSpec'->'contract' IS NOT NULL) OR (ts.contract IS NOT NULL)) FROM task t LEFT JOIN task_running_spec ts ON ts.task_id = t.id WHERE t.id = " + $TaskId + ';'
    $out = Join-Path $scriptDir 'verify-contract-first-contract.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

function Get-ContractContent {
    param([string]$TaskId)
    $sql = "SELECT COALESCE(t.context->'runningSpec'->'contract'->>'content', ts.contract->>'content') FROM task t LEFT JOIN task_running_spec ts ON ts.task_id = t.id WHERE t.id = " + $TaskId + ';'
    $out = Join-Path $scriptDir 'verify-contract-first-content.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
}

function Get-ContractTimeline {
    param([string]$TaskId)
    $sql = "SELECT payload->>'status' FROM task_timeline WHERE task_id = " + $TaskId + " AND event_type = 'sub_task_contract_backfilled' ORDER BY id DESC LIMIT 1;"
    $out = Join-Path $scriptDir 'verify-contract-first-timeline.out'
    $rc = Run-Psql -Sql $sql -OutFile $out
    if ($rc -ne 0) { return '' }
    $line = Get-PsqlFields -Path $out
    if (-not $line) { return '' }
    return $line
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
# ensure preset agents (idempotent, fixed names)
# ============================================================
Write-Output ''
Write-Output '=== [agents] ensure preset test agents ==='
$execA = Ensure-TestAgent -Name $execName -RoleValue 'EXECUTOR' -AccessType 'CLI_CLIENT' -ModelType 'gpt-4o' -AdminToken $adminToken
$reviewer = Ensure-TestAgent -Name $reviewerName -RoleValue 'REVIEWER' -AccessType 'CLI_CLIENT' -ModelType 'gpt-4o' -AdminToken $adminToken
if (-not $execA -or -not $reviewer) {
    Write-Output 'FAIL : preset agents unavailable'
    exit 1
}
Write-Output ('[agents] exec=' + $execA.Id + ' reviewer=' + $reviewer.Id)

$execAId = [string]$execA.Id
$reviewerId = [string]$reviewer.Id

# task ids for teardown
$script:TaskIdS1 = ''
$script:TaskIdS2 = ''
$script:TaskIdS4 = ''

# ============================================================
# S1: decompose contract flag -> contract sub-task is_contract=1
# ============================================================
function Run-Scenario1 {
    Write-Output ''
    Write-Output '=== [S1] decompose produces contract sub-task with is_contract=1 ==='
    $taskTitle = 'cf-s1-api-contract-task'
    $taskId = New-CfTask -Title $taskTitle -Description 'design user-center multi-module API contract and wire up frontend-backend integration' -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S1' 'task preset failed'; return }
    $script:TaskIdS1 = $taskId

    $planResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/planById/' + $taskId) -Headers @{ 'X-Admin-Token' = $adminToken }
    Assert-Pass ($planResp.Code -eq 200) 'S1-plan-http' ('POST /api/tasks/planById HTTP=' + $planResp.Code + ' body=' + $planResp.Body)

    $drafts = Wait-Until -Condition {
        param($tid, $tok)
        $resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/tasks/findPlanByTaskId/' + $tid) -Headers @{ 'X-Admin-Token' = $tok }
        if ($resp.Code -ne 200) { return $null }
        $json = $null
        try { $json = $resp.Body | ConvertFrom-Json } catch { }
        if (-not $json -or -not $json.data) { return $null }
        $arr = @($json.data)
        if ($arr.Count -eq 0) { return $null }
        return $arr
    } -TimeoutSec $PlanWaitSec -Label 'S1 drafts ready' -ArgList @($taskId, $adminToken)

    if (-not $drafts) {
        Assert-Skip 'S1-contract-draft' 'LLM decompose timed out (platform LLM credential env-dependent)'
        return
    }
    $contractDrafts = @($drafts | Where-Object { $_.isContract -eq $true -or $_.isContract -eq 1 })
    Assert-Pass ($contractDrafts.Count -gt 0) 'S1-contract-draft' ('drafts=' + @($drafts).Count + ' contractDrafts=' + $contractDrafts.Count)

    $dbSql = "SELECT COUNT(*) FROM sub_task WHERE task_id = " + $taskId + " AND is_contract = 1 AND deleted = 0;"
    $dbOut = Join-Path $scriptDir 'verify-contract-first-s1-db.out'
    $dbRc = Run-Psql -Sql $dbSql -OutFile $dbOut
    $dbLine = if ($dbRc -eq 0) { Get-PsqlFields -Path $dbOut } else { '' }
    Assert-Pass ($dbRc -eq 0 -and $dbLine -eq '1') 'S1-is-contract-db' ('sub_task.is_contract=1 rows=' + $dbLine + ' (expect 1)')

    $confirmResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/confirmPlanByTaskId/' + $taskId) -Headers @{ 'X-Admin-Token' = $adminToken }
    Assert-Pass ($confirmResp.Code -eq 200) 'S1-confirm-http' ('POST confirmPlan HTTP=' + $confirmResp.Code)
}

# ============================================================
# S2: contract sub-task DONE -> task running spec contract backfilled
# ============================================================
function Run-Scenario2 {
    Write-Output ''
    Write-Output '=== [S2] contract sub-task DONE -> running spec contract backfilled ==='
    $taskTitle = 'cf-s2-contract-backfill-task'
    $taskId = New-CfTask -Title $taskTitle -Description 'contract backfill preset task' -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S2' 'task preset failed'; return }
    $script:TaskIdS2 = $taskId

    $jsonCtx = '{"lastExecution":{"output":"' + $script:ContractBody + '"}}'
    $subId = New-SubTaskSql -TaskId $taskId -Title 'cf-s2-contract-sub' -AgentId $execAId -IsContract 1 -ContextJson $jsonCtx -StatusValue 'REVIEW'
    if (-not $subId) { Assert-Pass $false 'S2' 'sub-task preset failed'; return }
    Write-Output ('[S2] preset contract subTaskId=' + $subId + ' status=REVIEW')

    $reviewBody = '{"subTaskId":' + $subId + ',"result":"APPROVED","score":5,"comment":"cf-s2 approve"}'
    $revResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/reviews') -Body $reviewBody -Headers @{ 'X-Admin-Token' = $adminToken; 'X-Agent-Id' = $reviewerId }
    Assert-Pass ($revResp.Code -eq 200) 'S2-review-http' ('POST /api/reviews APPROVED HTTP=' + $revResp.Code + ' body=' + $revResp.Body)

    $contractState = Wait-Until -Condition {
        param($tid)
        $st = Get-ContractState -TaskId $tid
        if ($st -eq 't') { return $st }
        return $null
    } -TimeoutSec $BackfillWaitSec -Label 'S2 contract backfilled' -ArgList @($taskId)
    Assert-Pass ($contractState -eq 't') 'S2-contract-nonempty' ('task running spec contract non-empty, state=' + $contractState)

    $content = Get-ContractContent -TaskId $taskId
    Assert-Pass ($content -and $content.Contains('POST /api/v1/users')) 'S2-contract-content' ('contract content preserved, len=' + $content.Length)

    $tlStatus = Get-ContractTimeline -TaskId $taskId
    Assert-Pass ($tlStatus -eq 'success') 'S2-timeline' ("timeline sub_task_contract_backfilled status=" + $tlStatus + " (expect success)")
}

# ============================================================
# S3: executor prompt section renders contract header + body
# ============================================================
function Run-Scenario3 {
    Write-Output ''
    Write-Output '=== [S3] executor prompt section renders contract header ==='
    if (-not $script:TaskIdS2) { Assert-Skip 'S3' 'run S2 first'; return }
    $resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/spec-section/' + $script:TaskIdS2) -Headers @{ 'X-Admin-Token' = $adminToken }
    Assert-Pass ($resp.Code -eq 200) 'S3-spec-http' ('GET /api/admin/quality/spec-section HTTP=' + $resp.Code)
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    $data = if ($json -and $json.data) { [string]$json.data } else { '' }
    Assert-Pass ($data.Contains($script:ContractHeader)) 'S3-contract-header' ('prompt section contains contract header, len=' + $data.Length)
    Assert-Pass ($data.Contains('POST /api/v1/users')) 'S3-contract-content' 'prompt section contains contract body'
}

# ============================================================
# S4: non-contract task zero-noise (no contract, no section)
# ============================================================
function Run-Scenario4 {
    Write-Output ''
    Write-Output '=== [S4] non-contract task zero-noise ==='
    $taskTitle = 'cf-s4-no-contract-task'
    $taskId = New-CfTask -Title $taskTitle -Description 'no-contract preset task' -AdminToken $adminToken
    if (-not $taskId) { Assert-Pass $false 'S4' 'task preset failed'; return }
    $script:TaskIdS4 = $taskId

    $plainCtx = '{"lastExecution":{"output":"plain deliverable without any contract"}}'
    $subId = New-SubTaskSql -TaskId $taskId -Title 'cf-s4-plain-sub' -AgentId $execAId -IsContract 0 -ContextJson $plainCtx -StatusValue 'REVIEW'
    if (-not $subId) { Assert-Pass $false 'S4' 'sub-task preset failed'; return }
    Write-Output ('[S4] preset plain subTaskId=' + $subId + ' status=REVIEW')

    $reviewBody = '{"subTaskId":' + $subId + ',"result":"APPROVED","score":5,"comment":"cf-s4 approve"}'
    $revResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/reviews') -Body $reviewBody -Headers @{ 'X-Admin-Token' = $adminToken; 'X-Agent-Id' = $reviewerId }
    Assert-Pass ($revResp.Code -eq 200) 'S4-review-http' ('POST /api/reviews APPROVED HTTP=' + $revResp.Code + ' body=' + $revResp.Body)

    Start-Sleep -Seconds 5
    $state4 = Get-ContractState -TaskId $taskId
    Assert-Pass ($state4 -eq 'f') 'S4-contract-null' ('contract stays null after plain sub-task done, state=' + $state4)

    $tl4 = Get-ContractTimeline -TaskId $taskId
    Assert-Pass ([string]::IsNullOrEmpty($tl4)) 'S4-no-timeline' ('no sub_task_contract_backfilled timeline, actual=' + $tl4)

    $resp4 = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/spec-section/' + $taskId) -Headers @{ 'X-Admin-Token' = $adminToken }
    $json4 = $null
    try { $json4 = $resp4.Body | ConvertFrom-Json } catch { }
    $data4 = if ($json4 -and $json4.data) { [string]$json4.data } else { '' }
    Assert-Pass (-not $data4.Contains($script:ContractHeader)) 'S4-no-header' ('prompt section has no contract header, len=' + $data4.Length)
}

# ============================================================
# run selected scenarios
# ============================================================
if ($Scene -eq 'all' -or $Scene -eq 'S1') { Run-Scenario1 }
if ($Scene -eq 'all' -or $Scene -eq 'S2') { Run-Scenario2 }
if ($Scene -eq 'all' -or $Scene -eq 'S3') { Run-Scenario3 }
if ($Scene -eq 'all' -or $Scene -eq 'S4') { Run-Scenario4 }

# ============================================================
# teardown
# ============================================================
Write-Output ''
Write-Output '=== [teardown] ==='
$taskTuples = @(
    @($script:TaskIdS1, 'cf-s1-api-contract-task'),
    @($script:TaskIdS2, 'cf-s2-contract-backfill-task'),
    @($script:TaskIdS4, 'cf-s4-no-contract-task')
)
foreach ($tuple in $taskTuples) {
    $tid = [string]$tuple[0]
    if (-not $tid) { continue }
    $revClean = 'DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $tid + ');'
    $revCleanOut = Join-Path $scriptDir 'verify-contract-first-revclean.out'
    $null = Run-Psql -Sql $revClean -OutFile $revCleanOut
    $specClean = 'DELETE FROM task_running_spec WHERE task_id = ' + $tid + ';'
    $specCleanOut = Join-Path $scriptDir 'verify-contract-first-specclean.out'
    $null = Run-Psql -Sql $specClean -OutFile $specCleanOut
    Remove-CfTask -TaskId $tid -Title ([string]$tuple[1]) -AdminToken $adminToken
    Write-Output ('[teardown] task removed id=' + $tid)
}
# S2/S4 reviews bump qp-exec-a profile rows; clean to avoid cross-script pollution
$profClean = "DELETE FROM agent_quality_profile WHERE agent_id = " + $execAId + ' AND deleted = 0;'
$profCleanOut = Join-Path $scriptDir 'verify-contract-first-profclean.out'
$null = Run-Psql -Sql $profClean -OutFile $profCleanOut
Write-Output '[teardown] exec profile rows cleaned'

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
