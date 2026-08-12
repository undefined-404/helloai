# verify-a2-skill-derive.ps1 - A2 agent.skills best-effort 推导验证脚本
# Usage: .\verify-a2-skill-derive.ps1
# 覆盖: 注册推导(accessType 基础技能) / 关键词命中合并 / 显式技能优先 / 幂等复用不覆盖 / 管理端更新(整体替换+null 保持) / 级联清理
# 前置: 后端已启动 (http://localhost:6565), 管理员账号 admin/admin123
# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom
$ErrorActionPreference    = 'Stop'

$BaseUrl       = 'http://localhost:6565'
$AdminUsername = 'admin'
$AdminPassword = 'admin123'

$global:StepCount = 0
$global:FailCount = 0

# Invoke-Json: json body helper, strips BOM, returns parsed response
function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        $Body,
        [hashtable]$Headers
    )
    $json = $null
    if ($Body -ne $null) {
        $json = $Body | ConvertTo-Json -Depth 10
        $json = $json.TrimStart([char]0xFEFF)
    }
    $resp = Invoke-RestMethod -Uri $Url -Method $Method -ContentType 'application/json; charset=utf-8' -Body $json -Headers $Headers -TimeoutSec 30
    return $resp
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) {
        Write-Output ('[PASS] ' + $Message)
    } else {
        $global:FailCount++
        Write-Output ('[FAIL] ' + $Message)
    }
}

Write-Output '===================================================='
Write-Output 'A2: agent.skills best-effort derive verify'
Write-Output '===================================================='

# STEP1: admin login
Write-Output 'STEP1: admin login'
$loginResp = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/auth/login') -Body @{
    type       = 'admin'
    username   = $AdminUsername
    credential = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ('login code=' + $loginResp.code)
Assert-True (-not [string]::IsNullOrWhiteSpace($loginResp.data.token)) 'admin token is empty'
$adminHeaders = @{ 'X-Admin-Token' = $loginResp.data.token }

# STEP2: CLI_CLIENT derive -> shell base skill
Write-Output 'STEP2: CLI_CLIENT no keyword -> shell base'
$r2 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'a2-skill-cli'
    role        = 'EXECUTOR'
    description = 'verify-a2-skill-derive'
    accessType  = 'CLI_CLIENT'
    idempotent  = $true
} -Headers @{}
Assert-True ($r2.code -eq 200) ('reg cli code=' + $r2.code)
$cliId = $r2.data.id
$d2 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $cliId) -Body $null -Headers $adminHeaders
$s2 = @($d2.data.skills)
Assert-True (($s2 -contains 'shell') -and ($s2.Count -eq 1)) ('cli skills=' + ($s2 -join ','))

# STEP3: API_KEY_LLM keyword merge -> code-review base + docker keyword
Write-Output 'STEP3: API_KEY_LLM keyword merge -> code-review + docker'
$r3 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'a2-skill-llm'
    role        = 'EXECUTOR'
    description = 'Docker 审查专家'
    accessType  = 'API_KEY_LLM'
    modelType   = 'deepseek:deepseek-chat'
    idempotent  = $true
} -Headers @{}
Assert-True ($r3.code -eq 200) ('reg llm code=' + $r3.code)
$llmId = $r3.data.id
$d3 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $llmId) -Body $null -Headers $adminHeaders
$s3 = @($d3.data.skills)
Assert-True ($s3 -contains 'code-review') ('llm skills contain code-review: ' + ($s3 -join ','))
Assert-True ($s3 -contains 'docker') ('llm skills contain docker: ' + ($s3 -join ','))

# STEP4: explicit skills win (no derive contamination)
Write-Output 'STEP4: explicit skills take precedence'
$r4 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'a2-skill-explicit'
    role        = 'EXECUTOR'
    description = 'verify-a2-skill-derive'
    accessType  = 'CLI_CLIENT'
    skills      = @('kubernetes', 'golang')
    idempotent  = $true
} -Headers @{}
Assert-True ($r4.code -eq 200) ('reg explicit code=' + $r4.code)
$explicitId = $r4.data.id
$d4 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $explicitId) -Body $null -Headers $adminHeaders
$s4 = @($d4.data.skills)
Assert-True (($s4.Count -eq 2) -and ($s4 -contains 'kubernetes') -and ($s4 -contains 'golang')) ('explicit skills=' + ($s4 -join ','))

# STEP5: idempotent re-register keeps existing skills (no re-derive override)
Write-Output 'STEP5: idempotent re-register keeps existing skills'
$r5 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'a2-skill-explicit'
    role        = 'EXECUTOR'
    description = 'verify-a2-skill-derive'
    accessType  = 'CLI_CLIENT'
    idempotent  = $true
} -Headers @{}
Assert-True ($r5.code -eq 200) ('re-register code=' + $r5.code)
$d5 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $explicitId) -Body $null -Headers $adminHeaders
$s5 = @($d5.data.skills)
Assert-True ($s5 -contains 'kubernetes') ('idempotent keeps explicit skills: ' + ($s5 -join ','))

# STEP6: admin update with skills replaces; without skills keeps
Write-Output 'STEP6: admin update (explicit replace / null keep)'
$u1 = Invoke-Json -Method 'Put' -Url ($BaseUrl + '/api/admin/agents/updateById/' + $cliId) -Body @{
    skills = @('shell', 'docker')
} -Headers $adminHeaders
Assert-True ($u1.code -eq 200) ('update skills code=' + $u1.code)
$d6 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $cliId) -Body $null -Headers $adminHeaders
$s6 = @($d6.data.skills)
Assert-True (($s6.Count -eq 2) -and ($s6 -contains 'docker')) ('updated skills=' + ($s6 -join ','))
$u2 = Invoke-Json -Method 'Put' -Url ($BaseUrl + '/api/admin/agents/updateById/' + $cliId) -Body @{
    remark = 'a2-keep-skills-check'
} -Headers $adminHeaders
Assert-True ($u2.code -eq 200) ('update remark code=' + $u2.code)
$d7 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $cliId) -Body $null -Headers $adminHeaders
$s7 = @($d7.data.skills)
Assert-True ($s7 -contains 'docker') ('null skills keeps existing: ' + ($s7 -join ','))

# STEP7: cleanup cascade delete
Write-Output 'STEP7: cleanup cascade delete'
foreach ($item in @(@{ id = $cliId; name = 'a2-skill-cli' }, @{ id = $llmId; name = 'a2-skill-llm' }, @{ id = $explicitId; name = 'a2-skill-explicit' })) {
    $del = Invoke-Json -Method 'Delete' -Url ($BaseUrl + '/api/admin/agents/deleteById/' + $item.id) -Body @{
        confirmName = $item.name
    } -Headers $adminHeaders
    Assert-True ($del.code -eq 200) ('delete ' + $item.name + ' code=' + $del.code)
}

Write-Output '===================================================='
if ($global:FailCount -eq 0) {
    Write-Output 'OK: A2 skill derive verify passed'
} else {
    Write-Output ('FAILED: ' + $global:FailCount + ' assertion(s) failed')
    exit 1
}

