# verify-a3b-agent-edit-skills.ps1 - AgentEditDialog skills 编辑链路验收脚本
# Usage: .\verify-a3b-agent-edit-skills.ps1
# 覆盖: adminList 列表回显 skills(VO 补字段) / updateById 整体替换 / [] 清空 / null 保持 / 级联清理
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
Write-Output 'A3B: AgentEditDialog skills edit chain verify'
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

# STEP2: register temp agent with explicit skills (derive must not contaminate)
Write-Output 'STEP2: register temp agent (explicit skills)'
$r2 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'a3b-edit-skills'
    role        = 'EXECUTOR'
    description = 'verify-a3b-agent-edit-skills'
    accessType  = 'CLI_CLIENT'
    skills      = @('kubernetes', 'golang')
    idempotent  = $true
} -Headers @{}
Assert-True ($r2.code -eq 200) ('reg code=' + $r2.code)
$agentId = $r2.data.id

# STEP3: adminList returns skills in records (AgentListItemVO.skills mapping)
Write-Output 'STEP3: adminList record contains skills'
$list = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=100&keyword=a3b-edit-skills') -Body $null -Headers $adminHeaders
Assert-True ($list.code -eq 200) ('list code=' + $list.code)
$rec = $list.data.list | Where-Object { $_.id -eq $agentId }
Assert-True ($null -ne $rec) 'record found by keyword'
Assert-True ($null -ne $rec.skills) 'record.skills field present (not null)'
$s3 = @($rec.skills)
Assert-True (($s3.Count -eq 2) -and ($s3 -contains 'kubernetes') -and ($s3 -contains 'golang')) ('list skills=' + ($s3 -join ','))

# STEP4: updateById with skills replaces whole list (edit dialog save)
Write-Output 'STEP4: updateById explicit replace'
$u4 = Invoke-Json -Method 'Put' -Url ($BaseUrl + '/api/admin/agents/updateById/' + $agentId) -Body @{
    skills = @('shell', 'docker')
} -Headers $adminHeaders
Assert-True ($u4.code -eq 200) ('update replace code=' + $u4.code)
$d4 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $agentId) -Body $null -Headers $adminHeaders
$s4 = @($d4.data.skills)
Assert-True (($s4.Count -eq 2) -and ($s4 -contains 'shell') -and ($s4 -contains 'docker')) ('replaced skills=' + ($s4 -join ','))

# STEP5: updateById with [] clears skills (edit dialog deletes all tags)
Write-Output 'STEP5: updateById empty array clears'
$u5 = Invoke-Json -Method 'Put' -Url ($BaseUrl + '/api/admin/agents/updateById/' + $agentId) -Body @{
    skills = @()
} -Headers $adminHeaders
Assert-True ($u5.code -eq 200) ('update clear code=' + $u5.code)
$d5 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $agentId) -Body $null -Headers $adminHeaders
$s5 = @($d5.data.skills)
Assert-True ($s5.Count -eq 0) ('cleared skills count=' + $s5.Count)

# STEP6: updateById without skills keeps existing (null keep semantics)
Write-Output 'STEP6: updateById null keeps'
$u6 = Invoke-Json -Method 'Put' -Url ($BaseUrl + '/api/admin/agents/updateById/' + $agentId) -Body @{
    remark = 'a3b-null-keep-check'
} -Headers $adminHeaders
Assert-True ($u6.code -eq 200) ('update remark code=' + $u6.code)
$d6 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $agentId) -Body $null -Headers $adminHeaders
$s6 = @($d6.data.skills)
Assert-True ($s6.Count -eq 0) ('null keeps skills count=' + $s6.Count)

# STEP7: cleanup cascade delete
Write-Output 'STEP7: cleanup cascade delete'
$del = Invoke-Json -Method 'Delete' -Url ($BaseUrl + '/api/admin/agents/deleteById/' + $agentId) -Body @{
    confirmName = 'a3b-edit-skills'
} -Headers $adminHeaders
Assert-True ($del.code -eq 200) ('delete code=' + $del.code)

Write-Output '===================================================='
if ($global:FailCount -eq 0) {
    Write-Output 'OK: A3B AgentEditDialog skills edit chain verify passed'
} else {
    Write-Output ('FAILED: ' + $global:FailCount + ' assertion(s) failed')
    exit 1
}
