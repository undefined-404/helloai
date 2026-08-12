# verify-674-remove-specialization.ps1 - specializationSlug 移除链路验收脚本
# Usage: .\verify-674-remove-specialization.ps1
# 覆盖: 列表/详情响应不再含 specializationSlug / 注册带 skills / 编辑不带 modelType / 内部 LLM 注册缺省 modelType / 级联清理
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

# 检查 JSON 对象不含指定字段（响应契约层面确认已移除）
function Has-NoField {
    param($Obj, [string]$FieldName)
    $props = $Obj.PSObject.Properties.Name
    return ($props -notcontains $FieldName)
}

Write-Output '===================================================='
Write-Output '6.74: specializationSlug removal chain verify'
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

# STEP2: adminList 响应记录不含 specializationSlug（AgentListItemVO 已删字段）
Write-Output 'STEP2: adminList record has no specializationSlug'
$list = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=5') -Body $null -Headers $adminHeaders
Assert-True ($list.code -eq 200) ('list code=' + $list.code)
$firstRec = @($list.data.list)[0]
if ($null -ne $firstRec) {
    Assert-True (Has-NoField $firstRec 'specializationSlug') 'list record has no specializationSlug field'
} else {
    Write-Output '[SKIP] no agents present, list field check skipped'
}

# STEP3: 注册外部 AI Agent（CLI_CLIENT）带技能——注册响应无 specializationSlug、skills 生效
Write-Output 'STEP3: register external CLI agent with skills'
$r3 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'v674-ext-smoke'
    role        = 'EXECUTOR'
    description = 'verify-674-remove-specialization'
    accessType  = 'CLI_CLIENT'
    skills      = @('shell', 'kubernetes')
    idempotent  = $true
} -Headers @{}
Assert-True ($r3.code -eq 200) ('reg code=' + $r3.code)
Assert-True (Has-NoField $r3.data 'specializationSlug') 'register response has no specializationSlug field'
$agentId = $r3.data.id
$d3 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $agentId) -Body $null -Headers $adminHeaders
Assert-True (Has-NoField $d3.data 'specializationSlug') 'detail response has no specializationSlug field'
$s3 = @($d3.data.skills)
Assert-True (($s3.Count -eq 2) -and ($s3 -contains 'shell') -and ($s3 -contains 'kubernetes')) ('reg skills=' + ($s3 -join ','))

# STEP4: 编辑 Agent 不带 modelType/specializationSlug（编辑弹窗新契约）——更新成功、skills 整体替换
Write-Output 'STEP4: updateById without modelType/specializationSlug'
$u4 = Invoke-Json -Method 'Put' -Url ($BaseUrl + '/api/admin/agents/updateById/' + $agentId) -Body @{
    name   = 'v674-ext-smoke-edited'
    remark = 'edit-without-model'
    skills = @('docker')
} -Headers $adminHeaders
Assert-True ($u4.code -eq 200) ('update code=' + $u4.code)
$d4 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $agentId) -Body $null -Headers $adminHeaders
$s4 = @($d4.data.skills)
Assert-True (($s4.Count -eq 1) -and ($s4 -contains 'docker')) ('replaced skills=' + ($s4 -join ','))
Assert-True ($d4.data.name -eq 'v674-ext-smoke-edited') ('name updated=' + $d4.data.name)

# STEP5: 注册内部 LLM Agent（API_KEY_LLM）不传 modelType——缺省走系统默认 provider，注册响应 modelType 为 null
Write-Output 'STEP5: register internal LLM agent without modelType'
$r5 = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name        = 'v674-llm-smoke'
    role        = 'EXECUTOR'
    description = 'verify-674-remove-specialization'
    accessType  = 'API_KEY_LLM'
    idempotent  = $true
} -Headers @{}
Assert-True ($r5.code -eq 200) ('reg llm code=' + $r5.code)
$llmAgentId = $r5.data.id
$d5 = Invoke-Json -Method 'Get' -Url ($BaseUrl + '/api/agents/getById/' + $llmAgentId) -Body $null -Headers $adminHeaders
Assert-True ($d5.data.accessType -eq 'API_KEY_LLM') ('accessType=' + $d5.data.accessType)
Assert-True ($null -eq $d5.data.modelType) 'modelType null (system default provider will apply)'

# STEP6: 清理级联删除
Write-Output 'STEP6: cleanup cascade delete'
$del1 = Invoke-Json -Method 'Delete' -Url ($BaseUrl + '/api/admin/agents/deleteById/' + $agentId) -Body @{
    confirmName = 'v674-ext-smoke-edited'
} -Headers $adminHeaders
Assert-True ($del1.code -eq 200) ('delete ext code=' + $del1.code)
$del2 = Invoke-Json -Method 'Delete' -Url ($BaseUrl + '/api/admin/agents/deleteById/' + $llmAgentId) -Body @{
    confirmName = 'v674-llm-smoke'
} -Headers $adminHeaders
Assert-True ($del2.code -eq 200) ('delete llm code=' + $del2.code)

Write-Output '===================================================='
if ($global:FailCount -eq 0) {
    Write-Output 'OK: 6.74 specializationSlug removal chain verify passed'
} else {
    Write-Output ('FAILED: ' + $global:FailCount + ' assertion(s) failed')
    exit 1
}
