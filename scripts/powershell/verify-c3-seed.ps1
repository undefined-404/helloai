# verify-c3-seed.ps1 - Phase 0 C3 Step 1 grayscale seed data generator (HTTP-only)
# Usage: .\verify-c3-seed.ps1 [-RunCount 5] [-SubsPerTask 1]
# Purpose (C3 Step 1, N=5 observation window, inner command-chain):
#   For each Run:
#     1. POST /api/tasks        (agentPolicy.executorAgentIds=[inner-deepseek-flash2-executor] whitelist)
#     2. POST /api/sub-tasks    (assignedAgent=inner -> deterministic ASSIGNED)
#     3. POST /api/sub-tasks/executeById/{id}  (create execution command -> consume -> grayscale route)
#     4. GET  /api/sub-tasks/getById/{id}      (poll until leaving ASSIGNED)
#   Route decision is based on taskId % 100 < gray-percent (5), independent of
#   assignment path, so explicit assignment keeps each Run deterministic.
#   Note: inner (API_KEY_LLM) is the only executor the command chain can route to;
#   CLI_CLIENT (TeleAgent) self-drives via MCP pull, not via executeById.
# Guard: MaxTotalMinutes breaks the loop so a foreground timeout/background resume
#   cannot keep seeding runs forever (2026-09-02 r8/r9 incident).
# Rules: preflight skill rule 6 - UTF-8 header; single-quote + concat output only.
param(
    [string]$BaseUrl = 'http://localhost:6565',
    [long]$ExecutorAgentId = 2093226386712219649,
    [int]$RunCount = 5,
    [int]$SubsPerTask = 1,
    [int]$PollTimeoutSec = 180,
    [int]$PollIntervalSec = 3,
    [int]$MaxTotalMinutes = 30,
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123'
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6) - avoid CJK mojibake on Chinese Windows
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

$script:PassCount = 0
$script:FailCount = 0
$script:DoneMap = @{}   # subTaskId -> status (for summary distribution)

function Assert-Pass {
    param([bool]$Ok, [string]$Tag, [string]$Detail)
    if ($Ok) {
        $script:PassCount++
        Write-Output ('[' + $Tag + '] PASS : ' + $Detail)
    } else {
        $script:FailCount++
        Write-Output ('[' + $Tag + '] FAIL : ' + $Detail)
    }
}

function Invoke-Json {
    param([string]$Method, [string]$Uri, [string]$Body = $null, [hashtable]$Headers = @{})
    $Body = [string]$Body
    $Body = $Body.TrimStart([char]0xFEFF)
    try {
        if ($Method -eq 'GET') {
            # PS 5.1: GET + -Body '' + -ContentType throws 'Cannot send a content-body with this verb-type'
            # (caused false 'stuck at no-status' polling failures on 2026-09-02/03 seed runs)
            $resp = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -TimeoutSec 15 -UseBasicParsing
        } else {
            $resp = Invoke-WebRequest -Uri $Uri -Method $Method -ContentType 'application/json' `
                -Body $Body -Headers $Headers -TimeoutSec 15 -UseBasicParsing
        }
        return [pscustomobject]@{ Code = [int]$resp.StatusCode; Body = $resp.Content }
    } catch {
        $code = -1
        $text = $_.Exception.Message
        if ($_.Exception.Response -ne $null) {
            try { $code = [int]$_.Exception.Response.StatusCode } catch { }
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $text = $reader.ReadToEnd()
                $reader.Close()
            } catch { }
        }
        return [pscustomobject]@{ Code = $code; Body = $text }
    }
}

function New-TaskWithWhitelist {
    param([string]$Title, [string]$AdminToken)
    $policy = ',"agentPolicy":{"executorAgentIds":[' + $ExecutorAgentId + ']}'
    $body = '{"title":"' + $Title + '","description":"C3 Step 1 grayscale observation seed"' + $policy + '}'
    $resp = Invoke-Json 'POST' ($BaseUrl + '/api/tasks') $body @{ 'X-Admin-Token' = $AdminToken }
    if ($resp.Code -ne 200) {
        Write-Output ('[seed] FAIL create task HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    if (-not $json -or $json.code -ne 200 -or -not $json.data.id) {
        Write-Output ('[seed] FAIL create task biz: ' + $resp.Body)
        return $null
    }
    return [string]$json.data.id
}

function New-SubTask {
    param([string]$TaskId, [string]$Title, [string]$AdminToken)
    $body = '{"taskId":' + $TaskId + ',"title":"' + $Title + '","description":"C3 Step 1 seed sub-task","deliverable":"verification evidence note","acceptance":"evidence present","assignedAgent":' + $ExecutorAgentId + '}'
    $resp = Invoke-Json 'POST' ($BaseUrl + '/api/sub-tasks') $body @{ 'X-Admin-Token' = $AdminToken }
    if ($resp.Code -ne 200) {
        Write-Output ('[seed] FAIL create sub-task HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    if (-not $json -or $json.code -ne 200 -or -not $json.data.id) {
        Write-Output ('[seed] FAIL create sub-task biz: ' + $resp.Body)
        return $null
    }
    return [string]$json.data.id
}

function Get-SubTaskStatus {
    param([string]$SubTaskId, [string]$AdminToken)
    $resp = Invoke-Json 'GET' ($BaseUrl + '/api/sub-tasks/getById/' + $SubTaskId) $null @{ 'X-Admin-Token' = $AdminToken }
    if ($resp.Code -ne 200) { return '' }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    if (-not $json -or $json.code -ne 200) { return '' }
    return [string]$json.data.status
}

function Invoke-ExecuteById {
    param([string]$SubTaskId, [string]$AdminToken)
    $resp = Invoke-Json 'POST' ($BaseUrl + '/api/sub-tasks/executeById/' + $SubTaskId) $null @{ 'X-Admin-Token' = $AdminToken }
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    $mark = ''
    $ok = ($resp.Code -eq 200 -and $json -and $json.code -eq 200)
    if ($ok -and $json.data -and $json.data.recordId) { $mark = ' recordId=' + $json.data.recordId }
    if (-not $ok -and $json) {
        if ($json.msg -match '进行中') {
            # 已存在进行中执行记录（上一轮秒级内已消费），视为已触发
            $ok = $true
            $mark = ' (already-in-flight)'
        } elseif ($json.msg -match '状态不允许执行') {
            # CLI 已抢先领取（ASSIGNED -> IN_PROGRESS），命令链被自驱截胡：合法样本，单独归类
            return [pscustomobject]@{ Ok = $false; CliDriven = $true; Detail = ('HTTP=' + $resp.Code + ' msg=' + $json.msg + ' (CLI self-driven) ') }
        }
    }
    return [pscustomobject]@{ Ok = $ok; CliDriven = $false; Detail = ('HTTP=' + $resp.Code + ' msg=' + $(if ($json) { $json.msg } else { $resp.Body }) + $mark) }
}

function Wait-SubTaskLeavesAssigned {
    param([string]$SubTaskId, [string]$AdminToken)
    $deadline = (Get-Date).AddSeconds($PollTimeoutSec)
    $last = ''
    do {
        $last = Get-SubTaskStatus $SubTaskId $AdminToken
        if ($last -and $last -ne 'ASSIGNED' -and $last -ne 'PENDING') { return $last }
        Start-Sleep -Seconds $PollIntervalSec
    } while ((Get-Date) -lt $deadline)
    return $last
}

# ============================================================
# S0: server health (with retry)
# ============================================================
Write-Output '==== S0: server health ===='
$healthHttp = -1
for ($try = 1; $try -le 3; $try++) {
    $health = Invoke-Json 'GET' ($BaseUrl + '/api/health') $null @{}
    $healthHttp = $health.Code
    if ($healthHttp -eq 200) { break }
    Start-Sleep -Seconds 2
}
Assert-Pass ($healthHttp -eq 200) 'S0' ('GET /api/health HTTP=' + $healthHttp + ' (retry up to 3)')

# ============================================================
# S1: admin login
# ============================================================
Write-Output '==== S1: admin login ===='
$loginBody = '{"type":"admin","username":"' + $AdminUsername + '","credential":"' + $AdminPassword + '"}'
$loginResp = Invoke-Json 'POST' ($BaseUrl + '/api/auth/login') $loginBody @{}
$loginJson = $null
try { $loginJson = $loginResp.Body | ConvertFrom-Json } catch { }
$adminToken = if ($loginJson -and $loginJson.data -and $loginJson.data.token) { $loginJson.data.token } else { $null }
Assert-Pass (-not [string]::IsNullOrEmpty($adminToken)) 'S1' ('admin login token=' + $(if ($adminToken) { 'ok' } else { $loginResp.Body }))
if (-not $adminToken) {
    Write-Output ('SUMMARY: PASS=' + $script:PassCount + ' FAIL=' + $script:FailCount)
    exit 1
}

# ============================================================
# S2: seed N runs (create task -> sub-task(ASSIGNED) -> execute command -> poll)
# ============================================================
Write-Output '==== S2: seed runs ===='
$taskCount = [math]::Ceiling($RunCount / [math]::Max(1, $SubsPerTask))
Write-Output ('[seed] plan: tasks=' + $taskCount + ' runs=' + $RunCount + ' subsPerTask=' + $SubsPerTask + ' executor=' + $ExecutorAgentId + ' maxTotalMinutes=' + $MaxTotalMinutes)

$script:TaskIds = @()
$script:SubTaskIds = @()
$script:Missed = @()
$script:CliDrivenCount = 0
$script:SeedStart = Get-Date
$seedIndex = 0

for ($t = 1; $t -le $taskCount; $t++) {
    if (((Get-Date) - $script:SeedStart).TotalMinutes -ge $MaxTotalMinutes) {
        Write-Output ('[seed] WARN: total runtime >= ' + $MaxTotalMinutes + ' min, guard break (prevent background self-loop)')
        break
    }
    $taskTitle = 'c3-gs-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-t' + $t
    $taskId = New-TaskWithWhitelist -Title $taskTitle -AdminToken $adminToken
    Assert-Pass ($null -ne $taskId) ('S2-t' + $t) ('create task ' + $taskTitle + ' -> id=' + $(if ($taskId) { $taskId } else { 'N/A' }))
    if (-not $taskId) { continue }
    $script:TaskIds += $taskId

    $subsThisTask = [math]::Min($SubsPerTask, $RunCount - $seedIndex)
    for ($s = 1; $s -le $subsThisTask; $s++) {
        $seedIndex++
        $subTitle = 'c3gs-r' + $seedIndex
        $subId = New-SubTask -TaskId $taskId -Title $subTitle -AdminToken $adminToken
        if (-not $subId) {
            Assert-Pass $false ('S2-r' + $seedIndex) ('create sub-task ' + $subTitle + ' failed')
            $script:Missed += $subTitle
            continue
        }
        $script:SubTaskIds += $subId

        # trigger execution command (ASSIGNED is a legal executeById state)
        $exec = Invoke-ExecuteById $subId $adminToken
        if (-not $exec.Ok) {
            if ($exec.CliDriven) {
                # CLI poll 抢先领取：命令链被自驱截胡，属合法外部执行路径，单独归类统计
                $script:CliDrivenCount++
                Assert-Pass $true ('S2-r' + $seedIndex) ('CLI self-driven (no command): ' + $subId + ' ' + $exec.Detail)
                $final = Wait-SubTaskLeavesAssigned $subId $adminToken
                if ($final) {
                    $script:DoneMap[$subId] = ($final + '_CLI_DIRECT')
                } else {
                    $script:Missed += $subId
                }
            } else {
                Assert-Pass $false ('S2-r' + $seedIndex) ('executeById ' + $subId + ' ' + $exec.Detail)
                $script:Missed += $subId
            }
            continue
        }

        # poll until sub-task leaves ASSIGNED (command consumed -> IN_PROGRESS/REVIEW/DONE/...)
        $final = Wait-SubTaskLeavesAssigned $subId $adminToken
        if (-not $final -or $final -eq 'ASSIGNED' -or $final -eq 'PENDING') {
            Assert-Pass $false ('S2-r' + $seedIndex) ('sub-task ' + $subId + ' stuck at ' + $(if ($final) { $final } else { 'no-status' }) + ' (exec ' + $exec.Detail + ')')
            $script:Missed += $subId
        } else {
            Assert-Pass $true ('S2-r' + $seedIndex) ('sub-task ' + $subId + ' task=' + $taskId + ' exec-ok -> ' + $final)
            $script:DoneMap[$subId] = $final
        }
        if ($seedIndex -ge $RunCount) { break }
    }
    if ($seedIndex -ge $RunCount) { break }
}

# ============================================================
# S3: summary
# ============================================================
Write-Output '==== S3: summary ===='
$got = $script:DoneMap.Count
Assert-Pass ($got -eq $RunCount) 'S3' ('runs executed=' + $got + '/' + $RunCount + ' (command-chain=' + ($got - $script:CliDrivenCount) + ' cli-self-driven=' + $script:CliDrivenCount + ')')
if ($script:Missed.Count -gt 0) {
    Write-Output ('[seed] missed/stuck: ' + ($script:Missed -join ','))
}
$dist = @{}
foreach ($k in $script:DoneMap.Keys) {
    $v = $script:DoneMap[$k]
    if (-not $dist.ContainsKey($v)) { $dist[$v] = 0 }
    $dist[$v]++
}
foreach ($k in ($dist.Keys | Sort-Object)) {
    Write-Output ('[seed] status distribution: ' + $k + '=' + $dist[$k])
}
Write-Output ('[seed] taskIds (route key = taskId % 100 < 5): ' + ($script:TaskIds -join ','))

Write-Output ('SUMMARY: PASS=' + $script:PassCount + ' FAIL=' + $script:FailCount)
if ($script:FailCount -gt 0) {
    Write-Output 'RESULT: SEED PARTIAL - inspect missed list above (TeleAgent daemon alive?)'
    exit 1
}
Write-Output 'RESULT: SEED COMPLETE - runs in observation window; route share check via timeline route=agent_runtime (Step 2)'
exit 0