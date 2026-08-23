# ============================================================
# helloai verify-quality-dashboard.ps1
# Purpose: assert Phase 5 quality metrics dashboard endpoints:
#   S1 gate closed : GET /api/admin/quality/overview returns business
#                    code 403 when sys config admin.quality.enabled != true
#   S2 gate open   : PUT /api/admin/config/updateByKey/admin.quality.enabled=true
#   S3 overview    : GET /overview -> data.totalReviewed/totalApproved/
#                    firstPassRate(0-100)/avgReworkRounds/activeExecutors
#   S4 agents      : GET /agents?limit=5 -> array items carry
#                    agentId/agentName/reviewedCount/firstPassRate/qualityScore
#   S5 dashboard   : GET /dashboard?days=30 -> data.overview + four arrays
#                    (trends/defectDistributions/reworkRounds/reviewers)
#   S6 window guard: days=0 and days=-7 still return 200 (server default 30)
# Mode: -Scene S1|S2|S3|S4|S5|S6|all (default all)
# Ref: doc/log/HelloAI_迭代执行记录.md (Phase 5)
#      .qoder/skills/helloai-preflight/SKILL.md (rule 6: UTF-8 BOM + single-quote concat)
# Preconditions: docker compose up -d; helloai-start running at :6565.
# Usage (repo root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-quality-dashboard.ps1
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [ValidateSet('S1','S2','S3','S4','S5','S6','all')]
    [string]$Scene = 'all',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123'
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6) - avoid CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

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
        elseif ($Method -eq 'DELETE') { $resp = $client.DeleteAsync($Uri).Result }
        elseif ($Method -eq 'POST')   { $resp = $client.PostAsync($Uri, $content).Result }
        elseif ($Method -eq 'PUT')    { $resp = $client.PutAsync($Uri, $content).Result }
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

function Get-JsonData {
    param([string]$RawBody)
    try {
        $json = $RawBody | ConvertFrom-Json
        if ($json -and $null -ne $json.data) { return $json.data }
        return $null
    } catch {
        return $null
    }
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
$authHeaders = @{ 'X-Admin-Token' = $adminToken }

# ============================================================
# STEP S1: gate closed -> business code 403
#   AdminQualityController 配置门控（生产默认关闭）：sys config
#   admin.quality.enabled != true 时全部端点返回 R{code:403}。
#   注意：gateDenied 返回 HTTP 200 + R{code:403}，断言 body.code。
# ============================================================
Write-Output ''
Write-Output '=== [S1] gate closed assertion ==='
if ($Scene -in @('S1','all')) {
    $gateStatus = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/config/getByKey/admin.quality.enabled') -Headers $authHeaders
    $gateJson = $null
    try { $gateJson = $gateStatus.Body | ConvertFrom-Json } catch { }
    # getByKey returns data as Map {key: value} (AdminConfigController),
    # NOT {value: ...} - take the first property value instead.
    $gateValue = 'false'
    if ($gateJson -and $gateJson.data -and $gateJson.data.PSObject.Properties.Count -gt 0) {
        $gateValue = [string]($gateJson.data.PSObject.Properties | Select-Object -First 1).Value
    }
    if ($gateValue -eq 'true') {
        Assert-Skip 'S1-gate-closed' 'admin.quality.enabled already open, skip closed-gate assertion'
    } else {
        $resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/overview') -Headers $authHeaders
        $json = $null
        try { $json = $resp.Body | ConvertFrom-Json } catch { }
        $code = if ($json) { [int]$json.code } else { -1 }
        Assert-Pass ($code -eq 403) 'S1-gate-closed' ('GET /overview without gate -> business code 403, got ' + $code + ' body=' + $resp.Body)
    }
} else {
    Assert-Skip 'S1-gate-closed' 'not in scene'
}

# ============================================================
# STEP S2: open gate
# ============================================================
Write-Output ''
Write-Output '=== [S2] open quality gate ==='
if ($Scene -in @('S2','S3','S4','S5','S6','all')) {
    $gateBody = '{"value":"true"}'
    $gateResp = Invoke-Json -Method PUT -Uri ($BaseUrl + '/api/admin/config/updateByKey/admin.quality.enabled') -Body $gateBody -Headers $authHeaders
    Assert-Pass ($gateResp.Code -eq 200) 'S2-gate-open' ('PUT admin.quality.enabled=true HTTP=' + $gateResp.Code + ' body=' + $gateResp.Body)
} else {
    Assert-Skip 'S2-gate-open' 'not in scene'
}

# ============================================================
# STEP S3: GET /overview field assertion
# ============================================================
Write-Output ''
Write-Output '=== [S3] overview ==='
if ($Scene -in @('S3','all')) {
    $resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/overview') -Headers $authHeaders
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    $okHttp = ($resp.Code -eq 200 -and $json -and $json.code -eq 200)
    $data = Get-JsonData -RawBody $resp.Body
    $hasFields = ($null -ne $data -and
        $null -ne $data.totalReviewed -and $data.totalReviewed -ge 0 -and
        $null -ne $data.totalApproved -and $data.totalApproved -ge 0 -and
        $null -ne $data.firstPassRate -and $data.firstPassRate -ge 0 -and $data.firstPassRate -le 100 -and
        $null -ne $data.avgReworkRounds -and $data.avgReworkRounds -ge 0 -and
        $null -ne $data.activeExecutors -and $data.activeExecutors -ge 0)
    Assert-Pass ($okHttp -and $hasFields) 'S3-overview' ('overview fields present, body=' + $resp.Body)
} else {
    Assert-Skip 'S3-overview' 'not in scene'
}

# ============================================================
# STEP S4: GET /agents?limit=5 field assertion
# ============================================================
Write-Output ''
Write-Output '=== [S4] agents ranking ==='
if ($Scene -in @('S4','all')) {
    $resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/agents?limit=5') -Headers $authHeaders
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    $okHttp = ($resp.Code -eq 200 -and $json -and $json.code -eq 200)
    $data = Get-JsonData -RawBody $resp.Body
    $listOk = $false
    if ($null -ne $data -and $data -is [System.Array]) {
        $listOk = $true
        foreach ($item in $data) {
            if ($null -eq $item.agentId -or $null -eq $item.agentName -or
                $null -eq $item.reviewedCount -or $null -eq $item.firstPassRate) {
                $listOk = $false
                break
            }
            if ($item.firstPassRate -lt 0 -or $item.firstPassRate -gt 100) { $listOk = $false; break }
        }
    }
    Assert-Pass ($okHttp -and $listOk -and @($data).Count -le 5) 'S4-agents' ('ranking array <=5 items with fields, count=' + @($data).Count + ' body=' + $resp.Body)
} else {
    Assert-Skip 'S4-agents' 'not in scene'
}

# ============================================================
# STEP S5: GET /dashboard?days=30 field assertion
# ============================================================
Write-Output ''
Write-Output '=== [S5] dashboard ==='
if ($Scene -in @('S5','all')) {
    $resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/dashboard?days=30') -Headers $authHeaders
    $json = $null
    try { $json = $resp.Body | ConvertFrom-Json } catch { }
    $okHttp = ($resp.Code -eq 200 -and $json -and $json.code -eq 200)
    $data = Get-JsonData -RawBody $resp.Body
    $hasFields = ($null -ne $data -and
        $null -ne $data.overview -and $null -ne $data.overview.totalReviewed -and
        $data.trends -is [System.Array] -and
        $data.defectDistributions -is [System.Array] -and
        $data.reworkRounds -is [System.Array] -and
        $data.reviewers -is [System.Array])
    Assert-Pass ($okHttp -and $hasFields) 'S5-dashboard' ('dashboard overview + 4 arrays present, body=' + $resp.Body)
} else {
    Assert-Skip 'S5-dashboard' 'not in scene'
}

# ============================================================
# STEP S6: window guard (days<=0 -> server default 30, still 200)
# ============================================================
Write-Output ''
Write-Output '=== [S6] window guard ==='
if ($Scene -in @('S6','all')) {
    $resp0 = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/dashboard?days=0') -Headers $authHeaders
    $respN = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/quality/dashboard?days=-7') -Headers $authHeaders
    $ok0 = $false
    $okN = $false
    try { $j0 = $resp0.Body | ConvertFrom-Json; $ok0 = ($resp0.Code -eq 200 -and $j0.code -eq 200) } catch { }
    try { $jN = $respN.Body | ConvertFrom-Json; $okN = ($respN.Code -eq 200 -and $jN.code -eq 200) } catch { }
    Assert-Pass ($ok0 -and $okN) 'S6-window-guard' ('days<=0 falls back to 30 days without error, days=0 http=' + $resp0.Code + ' days=-7 http=' + $respN.Code)
} else {
    Assert-Skip 'S6-window-guard' 'not in scene'
}

# ============================================================
# summary
# ============================================================
Write-Output ''
Write-Output ('========================================================')
Write-Output ('verify-quality-dashboard.ps1 done: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
if ($global:FailCount -gt 0) {
    exit 1
}
exit 0
