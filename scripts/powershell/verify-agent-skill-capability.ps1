# ============================================================
# V52 e2e: Agent skill capability driven (plan 2182376f)
# Usage: .\scripts\powershell\verify-agent-skill-capability.ps1
# Prereq: backend on http://localhost:6565 (IDEA or start-sb.ps1),
#         Flyway V52 applied, DB cleaned.
# Covers:
#   S1 deepseek register with skills=[shell]      -> [thinking,shell]
#   S2 kimi register with skills=[web-search]     -> [thinking,web-search]
#   S3 deepseek register with skills=[web-search] -> rejected (not in whitelist)
#   S4 deepseek register with skills=[kubernetes] -> [thinking,kubernetes] (custom exempt)
#   S5 kimi register without skills               -> keyword-derived + thinking locked
#   S6 edit deepseek agent skills=[web-search]    -> rejected
#   S7 edit agent to kimi model + [shell,web-search] -> [thinking,shell,web-search]
#   S8 cleanup (API cascade delete)
# ============================================================
# UTF-8 encoding header (rule 6) -- avoid CJK garbled output
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://localhost:6565'
$AdminUsername = 'admin'
$AdminPassword = 'admin123'

$Script:PassCount = 0
$Script:FailCount = 0

function Write-Pass([string]$Label, [string]$Detail) {
    $Script:PassCount++
    Write-Output ('[PASS] ' + $Label + ' : ' + $Detail)
}

function Write-Fail([string]$Label, [string]$Detail) {
    $Script:FailCount++
    Write-Output ('[FAIL] ' + $Label + ' : ' + $Detail)
}

function Assert-True([bool]$Cond, [string]$Label, [string]$Detail) {
    if ($Cond) { Write-Pass $Label $Detail } else { Write-Fail $Label $Detail }
}

# PS 5.1: HTTP 4xx/5xx raises WebException; read body and parse as R<...>
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [string]$BodyJson = ''
    )
    try {
        if ($BodyJson) {
            # rule 6: send body as UTF-8 bytes, otherwise PS 5.1 encodes CJK
            # strings with the ANSI codepage and the backend receives '??'
            $bodyBytes = $script:Utf8NoBom.GetBytes($BodyJson)
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers `
                -ContentType 'application/json' -Body $bodyBytes
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $raw = $reader.ReadToEnd()
            try { return ($raw | ConvertFrom-Json) } catch { return $null }
        }
        return $null
    }
}

function ConvertTo-JsonSafe($obj) {
    # compact single-line JSON without BOM
    return $obj | ConvertTo-Json -Compress -Depth 10
}

# ---- admin login ----
Write-Output '==== S0: admin login ===='
$loginBody = ConvertTo-JsonSafe @{ type = 'admin'; username = $AdminUsername; credential = $AdminPassword }
$login = Invoke-Api -Method 'Post' -Uri ($BaseUrl + '/api/auth/login') -BodyJson $loginBody
Assert-True ($login -ne $null -and $login.code -eq 200) 'S0' ('login code=' + ($login.code))
if ($login -eq $null -or $login.code -ne 200) {
    Write-Output 'S0 abort: login failed, cannot continue'
    exit 1
}
$AdminToken = $login.data.token
$AdminHeaders = @{ 'X-Admin-Token' = $AdminToken }
Assert-True (-not [string]::IsNullOrWhiteSpace($AdminToken)) 'S0' 'admin token not empty'

$CreatedAgents = @()

# pre-clean: remove leftover agents from previous runs (name prefix v52-e2e-)
function Remove-LeftoverAgents {
    $page = 1
    while ($true) {
        $listResp = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/admin/agents/list?page=' + $page + '&pageSize=100&keyword=v52-e2e-') -Headers $AdminHeaders
        $items = @()
        if ($listResp -ne $null -and $listResp.code -eq 200 -and $listResp.data -ne $null) { $items = @($listResp.data.list) }
        if ($items.Count -eq 0) { break }
        foreach ($it in $items) {
            if ($it.name -match '^v52-e2e-') {
                $delBody = ConvertTo-JsonSafe @{ confirmName = $it.name }
                $del = Invoke-Api -Method 'Delete' -Uri ($BaseUrl + '/api/admin/agents/deleteById/' + $it.id) -Headers $AdminHeaders -BodyJson $delBody
                Write-Output ('pre-clean removed ' + $it.name + ' code=' + ($del.code))
            }
        }
        if ($items.Count -lt 100) { break }
        $page++
    }
}

Write-Output '==== S0b: pre-clean leftovers ===='
Remove-LeftoverAgents

function Get-AgentSkills([string]$AgentId) {
    $detail = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/admin/agents/getById/' + $AgentId) -Headers $AdminHeaders
    if ($detail -ne $null -and $detail.code -eq 200 -and $detail.data -ne $null -and $detail.data.skills -ne $null) {
        return @($detail.data.skills)
    }
    # fallback: detail skills missing in older backend build -> search list by prefix and match id
    $page = 1
    while ($true) {
        $listResp = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/admin/agents/list?page=' + $page + '&pageSize=100&keyword=v52-e2e-') -Headers $AdminHeaders
        $items = @()
        if ($listResp -ne $null -and $listResp.code -eq 200 -and $listResp.data -ne $null) { $items = @($listResp.data.list) }
        if ($items.Count -eq 0) { return @() }
        foreach ($it in $items) {
            if ([string]$it.id -eq [string]$AgentId -and $it.skills -ne $null) { return @($it.skills) }
        }
        if ($items.Count -lt 100) { return @() }
        $page++
    }
}

function Register-TestAgent([string]$Name, [string]$Role, [string]$Desc, [string]$ModelType, $Skills) {
    $body = @{ name = $Name; role = $Role; description = $Desc; accessType = 'API_KEY_LLM' }
    if ($ModelType) { $body.modelType = $ModelType }
    if ($Skills -ne $null) { $body.skills = @($Skills) }
    $json = ConvertTo-JsonSafe $body
    $resp = Invoke-Api -Method 'Post' -Uri ($BaseUrl + '/api/agents/register') -BodyJson $json
    if ($resp -ne $null -and $resp.code -eq 200 -and $resp.data -ne $null -and $resp.data.id) {
        $script:CreatedAgents += @{ id = [string]$resp.data.id; name = $Name }
    }
    return $resp
}

# ---- S1: deepseek + explicit shell ----
Write-Output '==== S1: deepseek register skills=[shell] ===='
$s1 = Register-TestAgent 'v52-e2e-ds-v1' 'EXECUTOR' 'e2e deepseek' 'deepseek:deepseek-v4-flash' @('shell')
Assert-True ($s1 -ne $null -and $s1.code -eq 200) 'S1' ('register code=' + ($s1.code) + ' msg=' + ($s1.msg))
if ($s1 -ne $null -and $s1.code -eq 200) {
    $s1Skills = Get-AgentSkills ([string]$s1.data.id)
    Assert-True ($s1Skills -contains 'thinking') 'S1' ('skills=' + ($s1Skills -join ',') + ' contains thinking')
    Assert-True ($s1Skills -contains 'shell') 'S1' ('skills=' + ($s1Skills -join ',') + ' contains shell')
    Assert-True (-not ($s1Skills -contains 'web-search')) 'S1' ('skills=' + ($s1Skills -join ',') + ' NOT contains web-search')
}

# ---- S2: kimi + explicit web-search ----
Write-Output '==== S2: kimi register skills=[web-search] ===='
$s2 = Register-TestAgent 'v52-e2e-kimi-v1' 'EXECUTOR' 'e2e kimi' 'moonshot:kimi-k2.6' @('web-search')
Assert-True ($s2 -ne $null -and $s2.code -eq 200) 'S2' ('register code=' + ($s2.code) + ' msg=' + ($s2.msg))
if ($s2 -ne $null -and $s2.code -eq 200) {
    $s2Skills = Get-AgentSkills ([string]$s2.data.id)
    Assert-True ($s2Skills -contains 'thinking') 'S2' ('skills=' + ($s2Skills -join ',') + ' contains thinking')
    Assert-True ($s2Skills -contains 'web-search') 'S2' ('skills=' + ($s2Skills -join ',') + ' contains web-search')
}

# ---- S3: deepseek + web-search rejected ----
Write-Output '==== S3: deepseek register skills=[web-search] rejected ===='
$s3 = Register-TestAgent 'v52-e2e-ds-bad' 'PLANNER' 'e2e bad skill' 'deepseek:deepseek-v4-flash' @('web-search')
Assert-True ($s3 -eq $null -or $s3.code -ne 200) 'S3' ('register rejected, code=' + ($s3.code) + ' msg=' + ($s3.msg))
Assert-True ($s3 -ne $null -and $s3.msg -match '不支持技能') 'S3' ('msg contains [不支持技能]: ' + ($s3.msg))

# ---- S4: custom skill exempt ----
Write-Output '==== S4: deepseek register skills=[kubernetes] custom exempt ===='
$s4 = Register-TestAgent 'v52-e2e-ds-custom' 'REVIEWER' 'e2e custom' 'deepseek:deepseek-v4-flash' @('kubernetes')
Assert-True ($s4 -ne $null -and $s4.code -eq 200) 'S4' ('register code=' + ($s4.code) + ' msg=' + ($s4.msg))
if ($s4 -ne $null -and $s4.code -eq 200) {
    $s4Skills = Get-AgentSkills ([string]$s4.data.id)
    Assert-True ($s4Skills -contains 'thinking') 'S4' ('skills=' + ($s4Skills -join ',') + ' contains thinking')
    Assert-True ($s4Skills -contains 'kubernetes') 'S4' ('skills=' + ($s4Skills -join ',') + ' contains kubernetes')
}

# ---- S5: kimi no skills -> keyword derived ----
Write-Output '==== S5: kimi register without skills ===='
$s5 = Register-TestAgent 'v52-e2e-kimi-derive' 'REVIEWER' '负责代码审查与联网检索' 'moonshot:kimi-k2.6' $null
Assert-True ($s5 -ne $null -and $s5.code -eq 200) 'S5' ('register code=' + ($s5.code) + ' msg=' + ($s5.msg))
if ($s5 -ne $null -and $s5.code -eq 200) {
    $s5Skills = Get-AgentSkills ([string]$s5.data.id)
    Assert-True ($s5Skills -contains 'thinking') 'S5' ('skills=' + ($s5Skills -join ',') + ' contains thinking')
    Assert-True ($s5Skills -contains 'code-review') 'S5' ('skills=' + ($s5Skills -join ',') + ' contains code-review')
    Assert-True ($s5Skills -contains 'web-search') 'S5' ('skills=' + ($s5Skills -join ',') + ' contains web-search')
}

# ---- S6: edit deepseek agent with web-search rejected ----
Write-Output '==== S6: edit deepseek agent skills=[web-search] rejected ===='
$s6Target = $null
foreach ($a in $CreatedAgents) { if ($a.name -eq 'v52-e2e-ds-v1') { $s6Target = $a } }
if ($s6Target) {
    $s6Body = ConvertTo-JsonSafe @{ skills = @('web-search') }
    $s6 = Invoke-Api -Method 'Put' -Uri ($BaseUrl + '/api/admin/agents/updateById/' + $s6Target.id) -Headers $AdminHeaders -BodyJson $s6Body
    Assert-True ($s6 -eq $null -or $s6.code -ne 200) 'S6' ('update rejected, code=' + ($s6.code) + ' msg=' + ($s6.msg))
    Assert-True ($s6 -ne $null -and $s6.msg -match '不支持技能') 'S6' ('msg contains [不支持技能]: ' + ($s6.msg))
} else {
    Write-Fail 'S6' 'target agent v52-e2e-ds-v1 not found'
}

# ---- S7: edit agent to kimi model + [shell,web-search] ----
Write-Output '==== S7: edit agent modelType=kimi + skills=[shell,web-search] ===='
if ($s6Target) {
    $s7Body = ConvertTo-JsonSafe @{ modelType = 'moonshot:kimi-k2.7-code'; skills = @('shell', 'web-search') }
    $s7 = Invoke-Api -Method 'Put' -Uri ($BaseUrl + '/api/admin/agents/updateById/' + $s6Target.id) -Headers $AdminHeaders -BodyJson $s7Body
    Assert-True ($s7 -ne $null -and $s7.code -eq 200) 'S7' ('update code=' + ($s7.code) + ' msg=' + ($s7.msg))
    if ($s7 -ne $null -and $s7.code -eq 200) {
        $s7Skills = Get-AgentSkills ([string]$s6Target.id)
        Assert-True ($s7Skills -contains 'thinking') 'S7' ('skills=' + ($s7Skills -join ',') + ' contains thinking')
        Assert-True ($s7Skills -contains 'shell') 'S7' ('skills=' + ($s7Skills -join ',') + ' contains shell')
        Assert-True ($s7Skills -contains 'web-search') 'S7' ('skills=' + ($s7Skills -join ',') + ' contains web-search')
    }
} else {
    Write-Fail 'S7' 'target agent v52-e2e-ds-v1 not found'
}

# ---- S8: cleanup ----
Write-Output '==== S8: cleanup ===='
foreach ($a in $CreatedAgents) {
    $delBody = ConvertTo-JsonSafe @{ confirmName = $a.name }
    $del = Invoke-Api -Method 'Delete' -Uri ($BaseUrl + '/api/admin/agents/deleteById/' + $a.id) -Headers $AdminHeaders -BodyJson $delBody
    $ok = ($del -ne $null -and $del.code -eq 200)
    Assert-True $ok ('S8 cleanup ' + $a.name) ('delete code=' + ($del.code))
}

Write-Output ''
Write-Output ('==== RESULT: PASS=' + $Script:PassCount + ' FAIL=' + $Script:FailCount + ' ====')
if ($Script:FailCount -gt 0) { exit 1 }
Write-Output 'ALL PASSED'
exit 0
