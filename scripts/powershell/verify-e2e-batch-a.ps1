# verify-e2e-batch-a.ps1 - E2E batch A: Workflow instantiation / Team policy snapshot / Task priority (HTTP-only)
# Usage: .\verify-e2e-batch-a.ps1 [-BaseUrl http://localhost:6565] [-AgentIds 'id1,id2'] [-SkipA1] [-SkipA2] [-SkipA3]
# Purpose: verify three closed-loop chains against a RUNNING local backend (started by user):
#   A1 Workflow (C1/N-001): template -> version -> publish -> instantiate ->
#      task/sub_task materialized + dependsOn backfilled + priority inherited -> aggregate status
#   A2 Team (C2/N-002): team create -> 2 EXECUTOR members -> publish ACTIVE ->
#      create task with agentPolicy.teamId -> policy snapshot expansion on task;
#      negative case: DRAFT team must fail-close on task creation
#   A3 Priority (C4/N-006): task default MEDIUM on create; explicit HIGH sub-task wins;
#      sub-task without priority inherits task.priority (MEDIUM)
# Note:
#   - Backend MUST be started by user first (java -jar ... --spring.profiles.active=local, port 6565)
#   - updatePriority has no API surface (core-only), covered by unit tests - not asserted here
#   - Creates e2e data (ids printed in final summary); deleteById cleanup for A2/A3 tasks only
# Preflight skill rule: UTF-8 header; single-quote + concat output only (no CJK in runtime literals).
param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [string]$AgentIds = '',
    [switch]$SkipA1,
    [switch]$SkipA2,
    [switch]$SkipA3
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
$script:SkipCount = 0
$script:Created = @{}   # id registry for final summary / cleanup

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

function Write-Skip {
    param([string]$Tag, [string]$Detail)
    $script:SkipCount++
    Write-Output ('[' + $Tag + '] SKIP : ' + $Detail)
}

function Invoke-Json {
    param([string]$Method, [string]$Uri, [string]$Body = $null, [hashtable]$Headers = @{})
    $Body = [string]$Body
    $Body = $Body.TrimStart([char]0xFEFF)
    try {
        if ($Method -eq 'GET') {
            # PS 5.1: GET + Body throws 'Cannot send a content-body with this verb-type'
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

function ConvertTo-Body {
    param($Obj)
    return ($Obj | ConvertTo-Json -Depth 12 -Compress)
}

function Parse-R {
    param($Resp)
    try {
        $json = $Resp.Body | ConvertFrom-Json
        if ($null -eq $json) { return $null }
        return $json
    } catch {
        return $null
    }
}

function Invoke-Biz {
    # POST/PUT helper: returns parsed R envelope (or $null), prints raw body on parse/HTTP failure
    param([string]$Method, [string]$Uri, [object]$Payload, [string]$Token)
    $headers = @{}
    if ($Token) { $headers['X-Admin-Token'] = $Token }
    $resp = Invoke-Json $Method $Uri (ConvertTo-Body $Payload) $headers
    $json = Parse-R $resp
    if ($resp.Code -ne 200 -or $null -eq $json) {
        Write-Output ('      [http] ' + $Method + ' ' + $Uri + ' -> HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    return $json
}

function Invoke-BizGet {
    param([string]$Uri, [string]$Token)
    $headers = @{}
    if ($Token) { $headers['X-Admin-Token'] = $Token }
    $resp = Invoke-Json 'GET' $Uri $null $headers
    $json = Parse-R $resp
    if ($resp.Code -ne 200 -or $null -eq $json) {
        Write-Output ('      [http] GET ' + $Uri + ' -> HTTP=' + $resp.Code + ' body=' + $resp.Body)
        return $null
    }
    return $json
}

function Assert-BizOk {
    param($Json, [string]$Tag, [string]$Detail)
    $ok = ($null -ne $Json) -and ($Json.code -eq 200)
    $extra = ''
    if ($null -ne $Json -and $Json.code -ne 200) { $extra = ' msg=' + $Json.msg }
    Assert-Pass $ok $Tag ($Detail + $extra)
    return $ok
}

function Login-Admin {
    param([string]$User, [string]$Pass)
    $payload = @{ type = 'admin'; username = $User; credential = $Pass }
    $json = Invoke-Biz 'POST' ($BaseUrl + '/api/auth/login') $payload ''
    if ($null -eq $json -or $json.code -ne 200 -or $null -eq $json.data -or $null -eq $json.data.token) {
        Write-Output ('[auth] FAIL login: HTTP ok=' + ($null -ne $json) + ' body=' + $json.msg)
        return ''
    }
    return [string]$json.data.token
}

function New-Timestamp {
    return Get-Date -Format 'yyyyMMddHHmmssfff'
}

# ============================================================
# Main
# ============================================================
Write-Output '=== verify-e2e-batch-a: start ==='
Write-Output ('target: ' + $BaseUrl)

$token = Login-Admin $AdminUsername $AdminPassword
if (-not $token) {
    Write-Output '=== verify-e2e-batch-a: ABORT (login failed) ==='
    exit 1
}
Write-Output '[auth] PASS : admin login ok'
$ts = New-Timestamp

# ------------------------------------------------------------
# A1 Workflow (C1/N-001)
# ------------------------------------------------------------
if (-not $SkipA1) {
    Write-Output ''
    Write-Output '--- A1 Workflow instantiation ---'

    # 1) template
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/workflow-templates') `
        @{ name = 'E2E-A1-WF-' + $ts; description = 'batch-a workflow e2e'; category = 'e2e' } $token
    if (-not (Assert-BizOk $r 'A1.1' 'create workflow template')) { exit 1 }
    $templateId = [string]$r.data.id
    $script:Created['a1_templateId'] = $templateId
    Write-Output ('      templateId=' + $templateId)

    # 2) version (definition)
    $ba = '{{business_area}}'
    $definition = @{
        taskDefaults = @{
            titleTemplate       = 'E2E-A1 ' + $ba + ' materialized'
            descriptionTemplate = 'e2e instantiation of ' + $ba
        }
        nodes = @(
            @{
                nodeKey   = 'n1'
                role      = 'executor'
                spec      = @{
                    title          = 'analyze ' + $ba
                    goal           = 'produce risk analysis for ' + $ba
                    deliverable    = 'analysis note'
                    acceptance     = 'note contains risk list'
                    estimated_effort = 1
                }
                constraints = @{}
                dependsOn  = @()
            },
            @{
                nodeKey   = 'n2'
                role      = 'executor'
                spec      = @{
                    title          = 'review ' + $ba + ' findings'
                    goal           = 'verify analysis for ' + $ba
                    deliverable    = 'verification note'
                    acceptance     = 'note confirms findings'
                    estimated_effort = 1
                }
                constraints = @{}
                dependsOn  = @('n1')
            }
        )
        paramsSchema = @{
            business_area = @{ type = 'string'; required = $true }
        }
    }
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/workflow-templates/' + $templateId + '/versions') `
        @{ definition = $definition } $token
    if (-not (Assert-BizOk $r 'A1.2' 'create version v1')) { exit 1 }
    $versionId = [string]$r.data.id
    $versionNo = $r.data.versionNo
    $script:Created['a1_versionId'] = $versionId
    Assert-Pass ($versionNo -eq 1) 'A1.2' ('versionNo=1 got=' + $versionNo)

    # 3) publish
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/workflow-templates/versions/' + $versionId + '/publish') @{} $token
    if (-not (Assert-BizOk $r 'A1.3' 'publish version')) { exit 1 }
    Assert-Pass ($r.data.status -eq 'PUBLISHED') 'A1.3' ('version status=PUBLISHED got=' + $r.data.status)

    # 4) instantiate
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/workflow-templates/' + $templateId + '/instances') `
        @{ params = @{ business_area = 'retail-contract' } } $token
    if (-not (Assert-BizOk $r 'A1.4' 'create workflow instance')) { exit 1 }
    $instanceId = [string]$r.data.id
    $taskId = [string]$r.data.taskId
    $script:Created['a1_instanceId'] = $instanceId
    $script:Created['a1_taskId'] = $taskId
    Write-Output ('      instanceId=' + $instanceId + ' taskId=' + $taskId)

    # 5) task materialized + rendered title + priority inherited (MEDIUM)
    $r = Invoke-BizGet ($BaseUrl + '/api/tasks/getById/' + $taskId) $token
    if (-not (Assert-BizOk $r 'A1.5' 'get materialized task')) { exit 1 }
    Assert-Pass ($r.data.title -like '*retail-contract*') 'A1.5' ('title rendered got=' + $r.data.title)
    Assert-Pass ($r.data.priority -eq 'MEDIUM') 'A1.5' ('task.priority default MEDIUM got=' + $r.data.priority)

    # 6) sub_task materialized: count=2, all MEDIUM, dependsOn backfilled n1 <- n2
    $r = Invoke-BizGet ($BaseUrl + '/api/sub-tasks/list?taskId=' + $taskId) $token
    if (-not (Assert-BizOk $r 'A1.6' 'list materialized sub-tasks')) { exit 1 }
    $subs = @($r.data)
    Assert-Pass ($subs.Count -eq 2) 'A1.6' ('sub-task count=2 got=' + $subs.Count)
    $n1 = $null
    $n2 = $null
    $allMedium = $true
    foreach ($s in $subs) {
        if ($null -ne $s.priority -and $s.priority -ne 'MEDIUM') { $allMedium = $false }
        if ($s.title -like '*analyze retail-contract*') { $n1 = $s }
        if ($s.title -like '*review retail-contract*') { $n2 = $s }
    }
    Assert-Pass $allMedium 'A1.6' 'all sub-tasks priority inherited MEDIUM'
    Assert-Pass ($null -ne $n1) 'A1.6' 'node n1 materialized'
    Assert-Pass ($null -ne $n2) 'A1.6' 'node n2 materialized'
    $depOk = $false
    if ($null -ne $n1 -and $null -ne $n2) {
        $deps = @($n2.dependsOn)
        $depOk = ($deps.Count -eq 1) -and ([string]$deps[0] -eq [string]$n1.id)
    }
    Assert-Pass $depOk 'A1.6' ('dependsOn n2 -> [n1] backfilled ok=' + $depOk)

    # 7) aggregate status (pure query projection)
    $r = Invoke-BizGet ($BaseUrl + '/api/admin/workflow-instances/' + $instanceId + '/status') $token
    if (-not (Assert-BizOk $r 'A1.7' 'get instance aggregate status')) { exit 1 }
    Assert-Pass ($r.data.totalCount -eq 2) 'A1.7' ('totalCount=2 got=' + $r.data.totalCount)
    Assert-Pass ($null -ne $r.data.status -and $r.data.status -ne '') 'A1.7' ('status non-empty got=' + $r.data.status)
    Assert-Pass ([string]$r.data.taskId -eq $taskId) 'A1.7' 'aggregate taskId matches instance'
    Write-Output ('      aggregate: status=' + $r.data.status + ' done=' + $r.data.doneCount + '/total=' + $r.data.totalCount)
}

# ------------------------------------------------------------
# A2 Team (C2/N-002)
# ------------------------------------------------------------
if (-not $SkipA2) {
    Write-Output ''
    Write-Output '--- A2 Team policy snapshot ---'

    # 1) pick ACTIVE EXECUTOR agents (team publish requires >= 1; snapshot check uses up to 2)
    $execIds = @()
    if ($AgentIds) {
        $execIds = @($AgentIds -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    } else {
        $r = Invoke-BizGet ($BaseUrl + '/api/admin/agents/list?role=EXECUTOR&status=ACTIVE&page=1&pageSize=100') $token
        if (Assert-BizOk $r 'A2.1' 'list ACTIVE EXECUTOR agents') {
            foreach ($rec in @($r.data.list)) {
                $execIds += [string]$rec.id
                if ($execIds.Count -ge 2) { break }
            }
        }
    }
    if ($execIds.Count -lt 1) {
        Write-Skip 'A2' ('need >= 1 ACTIVE EXECUTOR agent, got ' + $execIds.Count + ' - provide -AgentIds id1[,id2]')
    } else {
        Write-Output ('      executors=' + ($execIds -join ','))

        # 2) team create
        $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/teams') `
            @{ name = 'E2E-A2-TEAM-' + $ts; description = 'team snapshot e2e' } $token
        if (-not (Assert-BizOk $r 'A2.2' 'create team')) { exit 1 }
        $teamId = [string]$r.data.id
        $script:Created['a2_teamId'] = $teamId
        Write-Output ('      teamId=' + $teamId)

        # 3) add 2 EXECUTOR members
        $memberOk = $true
        foreach ($aid in $execIds) {
            $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/teams/' + $teamId + '/members') `
                @{ agentId = $aid; slotRole = 'EXECUTOR' } $token
            if (-not (Assert-BizOk $r 'A2.3' ('add member agent=' + $aid))) { $memberOk = $false }
        }
        if (-not $memberOk) { exit 1 }

        # 4) publish -> ACTIVE
        $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/teams/' + $teamId + '/publish') @{} $token
        if (-not (Assert-BizOk $r 'A2.4' 'publish team')) { exit 1 }
        Assert-Pass ($r.data.status -eq 'ACTIVE') 'A2.4' ('team status=ACTIVE got=' + $r.data.status)

        # 5) negative: DRAFT team must fail-close on createTask
        $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/teams') `
            @{ name = 'E2E-A2-NEG-' + $ts; description = 'draft team fail-close' } $token
        $negTeamId = ''
        if (Assert-BizOk $r 'A2.5' 'create draft team (negative fixture)') { $negTeamId = [string]$r.data.id }
        $script:Created['a2_negTeamId'] = $negTeamId
        if ($negTeamId) {
            $r = Invoke-Biz 'POST' ($BaseUrl + '/api/tasks') `
                @{ title = 'E2E-A2-NEG-' + $ts; description = 'must fail'; agentPolicy = @{ teamId = $negTeamId } } $token
            $negClosed = ($null -ne $r) -and ($r.code -ne 200)
            Assert-Pass $negClosed 'A2.5' ('DRAFT team createTask fail-closed got code=' + $(if ($r) { $r.code } else { 'http-err' }) + ' msg=' + $(if ($r) { $r.msg } else { '' }))
        }

        # 6) positive: create task with agentPolicy.teamId -> snapshot expansion
        $title = 'E2E-A2-EXP-' + $ts
        $r = Invoke-Biz 'POST' ($BaseUrl + '/api/tasks') `
            @{ title = $title; description = 'team policy expansion'; agentPolicy = @{ teamId = $teamId } } $token
        if (-not (Assert-BizOk $r 'A2.6' 'create task with teamId')) { exit 1 }
        $taskId = [string]$r.data.id
        $script:Created['a2_taskId'] = $taskId
        Write-Output ('      taskId=' + $taskId)

        $r = Invoke-BizGet ($BaseUrl + '/api/tasks/getById/' + $taskId) $token
        if (-not (Assert-BizOk $r 'A2.6' 'get task policy snapshot')) { exit 1 }
        $policy = $r.data.agentPolicy
        $teamIdOk = $false
        $execOk = $false
        if ($null -ne $policy) {
            $teamIdOk = ($null -ne $policy.teamId) -and ([string]$policy.teamId -eq $teamId)
            $got = @($policy.executorAgentIds | ForEach-Object { [string]$_ } | Sort-Object)
            $want = @($execIds | Sort-Object)
            $execOk = ($got.Count -eq $want.Count)
            if ($execOk) {
                for ($i = 0; $i -lt $want.Count; $i++) {
                    if ($got[$i] -ne $want[$i]) { $execOk = $false; break }
                }
            }
        }
        Assert-Pass $teamIdOk 'A2.6' ('policy.teamId preserved got=' + $(if ($policy) { $policy.teamId } else { 'null' }))
        Assert-Pass $execOk 'A2.6' ('policy.executorAgentIds expanded to team members')
        Write-Output ('      policy=' + ($policy | ConvertTo-Json -Depth 6 -Compress))

        # 7) cleanup: delete A2 tasks + archive teams (best-effort, warn only)
        $r = Invoke-Biz 'POST' ($BaseUrl + '/api/tasks/deleteById/' + $taskId) @{ confirmTitle = $title } $token
        if ($null -eq $r -or $r.code -ne 200) {
            Write-Output ('      [warn] cleanup delete A2 task failed: ' + $(if ($r) { $r.msg } else { 'http' }))
        } else {
            Write-Output '      [cleanup] A2 task deleted'
        }
        foreach ($tid in @($negTeamId)) {
            if ($tid) {
                $r = Invoke-Biz 'POST' ($BaseUrl + '/api/admin/teams/' + $tid + '/archive') @{} $token
                if ($null -eq $r -or $r.code -ne 200) {
                    Write-Output ('      [warn] cleanup archive neg team failed: ' + $(if ($r) { $r.msg } else { 'http' }))
                }
            }
        }
    }
}

# ------------------------------------------------------------
# A3 Priority (C4/N-006)
# ------------------------------------------------------------
if (-not $SkipA3) {
    Write-Output ''
    Write-Output '--- A3 Task priority ---'

    # 1) create task -> default MEDIUM persisted
    $title = 'E2E-A3-PRI-' + $ts
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/tasks') `
        @{ title = $title; description = 'priority e2e' } $token
    if (-not (Assert-BizOk $r 'A3.1' 'create task')) { exit 1 }
    $taskId = [string]$r.data.id
    $script:Created['a3_taskId'] = $taskId
    Write-Output ('      taskId=' + $taskId)
    Assert-Pass ($r.data.priority -eq 'MEDIUM') 'A3.1' ('task default priority=MEDIUM got=' + $r.data.priority)

    # 2) explicit HIGH sub-task wins
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/sub-tasks') `
        @{ taskId = $taskId; title = 'A3-HIGH'; description = 'explicit priority'; deliverable = 'note'; acceptance = 'ok'; priority = 'HIGH' } $token
    if (-not (Assert-BizOk $r 'A3.2' 'create sub-task with priority HIGH')) { exit 1 }
    Assert-Pass ($r.data.priority -eq 'HIGH') 'A3.2' ('explicit HIGH persisted got=' + $r.data.priority)

    # 3) sub-task without priority inherits task.priority (MEDIUM)
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/sub-tasks') `
        @{ taskId = $taskId; title = 'A3-INHERIT'; description = 'inherit priority'; deliverable = 'note'; acceptance = 'ok' } $token
    if (-not (Assert-BizOk $r 'A3.3' 'create sub-task without priority')) { exit 1 }
    Assert-Pass ($r.data.priority -eq 'MEDIUM') 'A3.3' ('inherited MEDIUM got=' + $r.data.priority)

    # cleanup: cascade delete A3 task (best-effort)
    $r = Invoke-Biz 'POST' ($BaseUrl + '/api/tasks/deleteById/' + $taskId) @{ confirmTitle = $title } $token
    if ($null -eq $r -or $r.code -ne 200) {
        Write-Output ('      [warn] cleanup delete A3 task failed: ' + $(if ($r) { $r.msg } else { 'http' }))
    } else {
        Write-Output '      [cleanup] A3 task deleted'
    }
}

# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------
Write-Output ''
Write-Output '===== verify-e2e-batch-a: summary ====='
Write-Output ('PASS=' + $script:PassCount + ' FAIL=' + $script:FailCount + ' SKIP=' + $script:SkipCount)
Write-Output 'created ids (for manual cleanup if needed):'
foreach ($key in @($script:Created.Keys | Sort-Object)) {
    Write-Output ('  ' + $key + '=' + $script:Created[$key])
}
if ($script:FailCount -gt 0) {
    Write-Output '===== RESULT: FAIL ====='
    exit 1
} else {
    Write-Output '===== RESULT: PASS ====='
    exit 0
}
