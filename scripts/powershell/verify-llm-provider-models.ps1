# ============================================================
# helloai LLM provider model multi-select config verifier (v1.0)
# 用途：端到端验证 V49 模型多选配置链路：
#       S0  health 检查（未启动则自动拉起 jar）
#       S1  admin 登录
#       S2  内置 Provider 模型列表（deepseek 2 模型 / moonshot 5 模型，默认标记正确）
#       S3  清理残留 + 创建自定义测试 Provider model-e2e-probe
#       S4  saveAllModels 批量保存 3 个模型（默认 m1）
#       S5  模型列表校验（3 个模型、m1 isDefault=1）
#       S6  异常路径（空列表 / 默认不在列表 / addModel 重复 / setDefault 不存在）
#       S7  setDefaultModel 切换默认到 m2
#       S8  deleteModel 保护（删默认被拒 / 正常删除）
#       S9  toggleModel 保护（禁用默认且无其他启用被拒）
#       S10 Agent 角色唯一性（同角色同模型冲突 / 跨角色不冲突 / 同角色不同模型不冲突）
#       S11 清理（删除测试 Agent + Provider）
# Ref:  .qoder/plans/LLM供应商模型多选配置重构_d80b193e.md (Phase 2/5)
#       执行记录 doc/log/HelloAI_迭代执行记录.md (V49)
#       .agents/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：docker compose up -d（helloai-postgres / redis / rabbitmq）；
#       mvn package 已产出最新 jar；后端可访问 localhost:6565。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-llm-provider-models.ps1
# 重复运行：脚本开头会自动清理上次残留的测试数据，幂等可重跑。
# (all strings use single-quote + concat to avoid PS 5.1 parser issues)
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [string]$ProbeProviderCode = 'model-e2e-probe',
    [string]$JarPath = 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar',
    [int]$StartupTimeoutSec = 150
)

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

$healthUrl = $BaseUrl + '/api/health'
$global:PassCount = 0
$global:FailCount = 0
$global:BackendPid = 0
$global:StartedByScript = $false

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

function Invoke-Json {
    param([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers)
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
        if ($json -ne $null) {
            $json = $json.TrimStart([char]0xFEFF)
        }
    }
    try {
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers `
            -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 120
    } catch {
        $resp = $_.Exception.Response
        $statusCode = $null
        if ($resp -ne $null) {
            try { $statusCode = [int]$resp.StatusCode } catch { }
        }
        Write-Output ('HTTP_FAIL: ' + $Method + ' ' + $Url + ' status=' + $statusCode + ' msg=' + $_.Exception.Message)
        $bodyText = ''
        if ($resp -ne $null) {
            try {
                $stream = $resp.GetResponseStream()
                if ($stream -ne $null) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $bodyText = $reader.ReadToEnd()
                }
            } catch { }
        }
        if (-not [string]::IsNullOrWhiteSpace($bodyText)) {
            Write-Output ('HTTP_FAIL_BODY: ' + $bodyText)
        }
        return $null
    }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) {
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Free-Port {
    $conns = Get-NetTCPConnection -LocalPort 6565 -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        $ownerPid = [int]$c.OwningProcess
        $ownerProc = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
        if ($ownerProc -and $ownerProc.ProcessName -eq 'java') {
            Write-Output ('Free-Port: killing java pid=' + $ownerPid)
        }
        Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
}

function Find-Java {
    # 优先探测 .jdks 下 ms-17*（项目固定 JDK 17；系统 PATH 的 java 可能是失效 stub）
    $homeJdks = Join-Path $HOME '.jdks'
    if (Test-Path $homeJdks) {
        $cand = Get-ChildItem $homeJdks -Directory -Filter 'ms-17*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($cand -and (Test-Path (Join-Path $cand.FullName 'bin\java.exe'))) {
            return (Join-Path $cand.FullName 'bin\java.exe')
        }
    }
    return 'java'
}

function Start-BackendIfNeeded {
    Write-Output ('[S0] check ' + $healthUrl)
    if (Wait-Healthy) {
        Write-Output '[S0] backend already running, reuse it'
        return
    }
    if (-not (Test-Path $JarPath)) {
        Write-Output ('[S0] FAIL : jar not found at ' + $JarPath + ' , run mvn package first')
        exit 1
    }
    Write-Output '[S0] backend not running, start jar'
    Free-Port
    $logDir = Join-Path $PSScriptRoot ('tmp\llm-models-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $outLog = Join-Path $logDir 'backend.out.log'
    $errLog = Join-Path $logDir 'backend.err.log'
    $javaCmd = Find-Java
    Write-Output ('[S0] java=' + $javaCmd)
    $proc = Start-Process -FilePath $javaCmd -ArgumentList @('-jar', $JarPath) `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    $global:BackendPid = $proc.Id
    $global:StartedByScript = $true
    Write-Output ('[S0] started java pid=' + $global:BackendPid + ' logs=' + $logDir)

    if (-not (Wait-Healthy)) {
        Write-Output ('[S0] FAIL : backend not healthy within ' + $StartupTimeoutSec + 's, see ' + $logDir)
        if ($global:StartedByScript) {
            Stop-Process -Id $global:BackendPid -Force -ErrorAction SilentlyContinue
        }
        exit 1
    }
    Write-Output '[S0] backend healthy'
}

function Stop-BackendIfStarted {
    if ($global:StartedByScript) {
        Write-Output ('cleanup: stop backend pid=' + $global:BackendPid + ' (started by this script)')
        Stop-Process -Id $global:BackendPid -Force -ErrorAction SilentlyContinue
    }
}

# ============================================================
Write-Output '==== S0: backend readiness ===='
Start-BackendIfNeeded

Write-Output '==== S1: admin login ===='
$loginResp = Invoke-Json 'Post' ($BaseUrl + '/api/auth/login') @{
    type       = 'admin'
    username   = $AdminUsername
    credential = $AdminPassword
} @{}
Assert-Pass ($loginResp -ne $null -and $loginResp.code -eq 200) 'S1' ('login code=' + $loginResp.code)
if ($loginResp -eq $null -or $loginResp.code -ne 200) {
    Write-Output 'S1 abort: login failed, cannot continue'
    Stop-BackendIfStarted
    exit 1
}
$adminToken = $loginResp.data.token
Assert-Pass (-not [string]::IsNullOrWhiteSpace($adminToken)) 'S1' 'admin token not empty'
$adminHeaders = @{ 'X-Admin-Token' = $adminToken }

Write-Output '==== S2: builtin provider model lists (V49 seed) ===='
$deepseekId = $null
$provList = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/list') $null $adminHeaders
Assert-Pass ($provList -ne $null -and $provList.code -eq 200) 'S2' ('provider list code=' + $provList.code)
if ($provList -ne $null -and $provList.data -ne $null) {
    $deepseek = @($provList.data) | Where-Object { $_.providerCode -eq 'deepseek' } | Select-Object -First 1
    $deepseekId = $deepseek.id
}
Assert-Pass ($deepseekId -ne $null) 'S2' 'builtin provider deepseek present'
if ($deepseekId -eq $null) {
    Write-Output 'S2 abort: deepseek provider not found, cannot continue'
    Stop-BackendIfStarted
    exit 1
}
$dsModelsResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/' + $deepseekId + '/models/list') $null $adminHeaders
Assert-Pass ($dsModelsResp -ne $null -and $dsModelsResp.code -eq 200) 'S2' ('deepseek models code=' + $dsModelsResp.code)
$dsModels = @($dsModelsResp.data)
Assert-Pass ($dsModels.Count -eq 2) 'S2' ('deepseek has 2 seed models, got ' + $dsModels.Count)
$dsDefault = $dsModels | Where-Object { $_.isDefault -eq 1 } | Select-Object -First 1
Assert-Pass ($dsDefault -ne $null -and $dsDefault.modelName -eq 'deepseek-v4-flash') 'S2' ('deepseek default model is deepseek-v4-flash, got ' + $dsDefault.modelName)
$moonshot = @($provList.data) | Where-Object { $_.providerCode -eq 'moonshot' } | Select-Object -First 1
if ($moonshot -ne $null) {
    $msModelsResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/' + $moonshot.id + '/models/list') $null $adminHeaders
    $msModels = @($msModelsResp.data)
    Assert-Pass ($msModels.Count -eq 5) 'S2' ('moonshot has 5 seed models, got ' + $msModels.Count)
    $msDefault = $msModels | Where-Object { $_.isDefault -eq 1 } | Select-Object -First 1
    Assert-Pass ($msDefault -ne $null -and $msDefault.modelName -eq 'kimi-k2.5') 'S2' ('moonshot default model is kimi-k2.5, got ' + $msDefault.modelName)
} else {
    Write-Output '[S2] WARN : moonshot provider missing, skip its assertions'
}

Write-Output '==== S3: cleanup leftovers + create probe provider ===='
# 清理上次运行残留：删除 probe 前缀 Agent（先删引用者）再删 probe Provider
$agentListResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/agents/list') $null $adminHeaders
if ($agentListResp -ne $null -and $agentListResp.data -ne $null) {
    $probeAgents = @($agentListResp.data.list) | Where-Object { $_.name -like 'llm-model-e2e-*' }
    foreach ($a in $probeAgents) {
        $delResp = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/agents/deleteById/' + $a.id) @{ confirmName = $a.name } $adminHeaders
        $delCode = if ($delResp -eq $null) { 'HTTP_FAIL' } else { [string]$delResp.code }
        Write-Output ('[S3] cleanup agent ' + $a.name + ' id=' + $a.id + ' code=' + $delCode)
    }
}
$provList2 = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/list') $null $adminHeaders
$probeProvider = $null
if ($provList2 -ne $null -and $provList2.data -ne $null) {
    $probeProvider = @($provList2.data) | Where-Object { $_.providerCode -eq $ProbeProviderCode } | Select-Object -First 1
}
if ($probeProvider -ne $null) {
    $delProv = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/deleteById/' + $probeProvider.id) $null $adminHeaders
    Write-Output ('[S3] cleanup probe provider id=' + $probeProvider.id + ' code=' + $delProv.code)
}
$createResp = Invoke-Json 'Post' ($BaseUrl + '/api/admin/llm-providers') @{
    providerCode = $ProbeProviderCode
    providerName = 'Model E2E Probe'
    protocolType = 'OPENAI_COMPATIBLE'
    baseUrl      = 'https://probe.example.com/v1'
    enabled      = 1
} $adminHeaders
Assert-Pass ($createResp -ne $null -and $createResp.code -eq 200) 'S3' ('create probe provider code=' + $createResp.code + ' msg=' + $createResp.msg)
if ($createResp -eq $null -or $createResp.code -ne 200) {
    Write-Output 'S3 abort: probe provider creation failed'
    Stop-BackendIfStarted
    exit 1
}
$probeId = [long]$createResp.data.id
Assert-Pass ($probeId -gt 0) 'S3' ('probe provider id=' + $probeId)

Write-Output '==== S4: saveAllModels (multi-select bulk save) ===='
$saveResp = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/saveAll') @{
    modelNames   = @('m1', 'm2', 'm3')
    defaultModel = 'm1'
} $adminHeaders
Assert-Pass ($saveResp -ne $null -and $saveResp.code -eq 200) 'S4' ('saveAll code=' + $saveResp.code + ' msg=' + $saveResp.msg)

Write-Output '==== S5: model list after saveAll ===='
$modelsResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/list') $null $adminHeaders
Assert-Pass ($modelsResp -ne $null -and $modelsResp.code -eq 200) 'S5' ('models list code=' + $modelsResp.code)
$models = @($modelsResp.data)
Assert-Pass ($models.Count -eq 3) 'S5' ('probe provider has 3 models, got ' + $models.Count)
$m1 = $models | Where-Object { $_.modelName -eq 'm1' } | Select-Object -First 1
Assert-Pass ($m1 -ne $null -and $m1.isDefault -eq 1) 'S5' 'm1 is default model'
$m2 = $models | Where-Object { $_.modelName -eq 'm2' } | Select-Object -First 1
Assert-Pass ($m2 -ne $null -and $m2.isDefault -eq 0 -and $m2.enabled -eq 1) 'S5' 'm2 present, non-default, enabled'

Write-Output '==== S6: error paths ===='
$errEmpty = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/saveAll') @{
    modelNames   = @()
    defaultModel = 'm1'
} $adminHeaders
Assert-Pass ($errEmpty -eq $null -or $errEmpty.code -ne 200) 'S6' ('empty modelNames rejected, code=' + $(if ($errEmpty -eq $null) { 'HTTP_FAIL' } else { [string]$errEmpty.code }))
$errDefault = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/saveAll') @{
    modelNames   = @('m1', 'm2')
    defaultModel = 'm9'
} $adminHeaders
Assert-Pass ($errDefault -eq $null -or $errDefault.code -ne 200) 'S6' ('default not in list rejected, code=' + $(if ($errDefault -eq $null) { 'HTTP_FAIL' } else { [string]$errDefault.code }))
$errDup = Invoke-Json 'Post' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models') @{
    modelName = 'm1'
    isDefault = $false
} $adminHeaders
Assert-Pass ($errDup -eq $null -or $errDup.code -ne 200) 'S6' ('duplicate addModel rejected, code=' + $(if ($errDup -eq $null) { 'HTTP_FAIL' } else { [string]$errDup.code }))
$errSetDefault = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/setDefaultByName/m9') @{} $adminHeaders
Assert-Pass ($errSetDefault -eq $null -or $errSetDefault.code -ne 200) 'S6' ('setDefault on missing model rejected, code=' + $(if ($errSetDefault -eq $null) { 'HTTP_FAIL' } else { [string]$errSetDefault.code }))
# 异常路径不改动数据：仍为 3 个模型
$modelsAfterErr = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/list') $null $adminHeaders
Assert-Pass (@($modelsAfterErr.data).Count -eq 3) 'S6' ('error paths left data untouched, still 3 models')

Write-Output '==== S7: setDefaultModel switch ===='
$setDefResp = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/setDefaultByName/m2') @{} $adminHeaders
Assert-Pass ($setDefResp -ne $null -and $setDefResp.code -eq 200) 'S7' ('setDefault m2 code=' + $setDefResp.code)
$modelsAfterDef = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/list') $null $adminHeaders
$defAfter = @($modelsAfterDef.data) | Where-Object { $_.isDefault -eq 1 } | Select-Object -First 1
Assert-Pass ($defAfter -ne $null -and $defAfter.modelName -eq 'm2') 'S7' ('default model switched to m2, got ' + $defAfter.modelName)

Write-Output '==== S8: deleteModel protection ===='
$delDefaultResp = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/deleteByName/m2') $null $adminHeaders
Assert-Pass ($delDefaultResp -eq $null -or $delDefaultResp.code -ne 200) 'S8' ('delete default m2 rejected, code=' + $(if ($delDefaultResp -eq $null) { 'HTTP_FAIL' } else { [string]$delDefaultResp.code }))
$delLastGuard = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/deleteByName/m1') $null $adminHeaders
Assert-Pass ($delLastGuard -ne $null -and $delLastGuard.code -eq 200) 'S8' ('delete non-default m1 allowed, code=' + $delLastGuard.code)
$modelsAfterDel = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/list') $null $adminHeaders
Assert-Pass (@($modelsAfterDel.data).Count -eq 2) 'S8' ('after delete m1, still 2 models')
# 删除最后一个模型被拒（当前默认 m2 + 仅剩 m3，删 m3 后剩 1 个 -> 允许；再删 m2 拒绝）
$delM3 = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/deleteByName/m3') $null $adminHeaders
Assert-Pass ($delM3 -ne $null -and $delM3.code -eq 200) 'S8' ('delete m3 allowed, code=' + $delM3.code)
$delLast = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/deleteByName/m2') $null $adminHeaders
Assert-Pass ($delLast -eq $null -or $delLast.code -ne 200) 'S8' ('delete last model m2 rejected, code=' + $(if ($delLast -eq $null) { 'HTTP_FAIL' } else { [string]$delLast.code }))

Write-Output '==== S9: toggleModel protection ===='
# 恢复现场：当前仅剩 m2（默认启用）。新增 m1 再测禁用保护
$addResp = Invoke-Json 'Post' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models') @{
    modelName = 'm1'
    isDefault = $false
} $adminHeaders
Assert-Pass ($addResp -ne $null -and $addResp.code -eq 200) 'S9' ('re-add m1 code=' + $addResp.code)
# 禁用默认 m2 但 m1 仍启用 -> 允许
$t1 = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/toggleByName/m2') @{ enabled = $false } $adminHeaders
Assert-Pass ($t1 -ne $null -and $t1.code -eq 200) 'S9' ('disable default m2 with m1 enabled allowed, code=' + $t1.code)
# 再禁用 m1 -> 无其他启用 -> 拒绝
$t2 = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/toggleByName/m1') @{ enabled = $false } $adminHeaders
Assert-Pass ($t2 -eq $null -or $t2.code -ne 200) 'S9' ('disable last enabled m1 rejected, code=' + $(if ($t2 -eq $null) { 'HTTP_FAIL' } else { [string]$t2.code }))
# 恢复：启用 m2（默认）、m1 保持启用
$t3 = Invoke-Json 'Put' ($BaseUrl + '/api/admin/llm-providers/' + $probeId + '/models/toggleByName/m2') @{ enabled = $true } $adminHeaders
Assert-Pass ($t3 -ne $null -and $t3.code -eq 200) 'S9' ('re-enable m2 code=' + $t3.code)

Write-Output '==== S10: agent role model uniqueness ===='
$reg1 = Invoke-Json 'Post' ($BaseUrl + '/api/agents/register') @{
    name        = 'llm-model-e2e-exec-1'
    role        = 'EXECUTOR'
    description = 'llm model e2e probe agent 1'
    accessType  = 'API_KEY_LLM'
    modelType   = $ProbeProviderCode + ':m2'
    idempotent  = $true
} @{}
Assert-Pass ($reg1 -ne $null -and $reg1.code -eq 200) 'S10' ('register exec-1 (m2) code=' + $reg1.code + ' msg=' + $reg1.msg)
if ($reg1 -ne $null -and $reg1.code -eq 200) {
    $reg2 = Invoke-Json 'Post' ($BaseUrl + '/api/agents/register') @{
        name        = 'llm-model-e2e-exec-2'
        role        = 'EXECUTOR'
        description = 'llm model e2e probe agent 2'
        accessType  = 'API_KEY_LLM'
        modelType   = $ProbeProviderCode + ':m2'
        idempotent  = $true
    } @{}
    Assert-Pass ($reg2 -ne $null -and $reg2.code -ne 200) 'S10' ('same role+model rejected, code=' + $reg2.code + ' msg=' + $reg2.msg)
    $reg3 = Invoke-Json 'Post' ($BaseUrl + '/api/agents/register') @{
        name        = 'llm-model-e2e-plan-1'
        role        = 'PLANNER'
        description = 'llm model e2e probe planner'
        accessType  = 'API_KEY_LLM'
        modelType   = $ProbeProviderCode + ':m2'
        idempotent  = $true
    } @{}
    Assert-Pass ($reg3 -ne $null -and $reg3.code -eq 200) 'S10' ('same model different role allowed, code=' + $reg3.code)
    $reg4 = Invoke-Json 'Post' ($BaseUrl + '/api/agents/register') @{
        name        = 'llm-model-e2e-exec-3'
        role        = 'EXECUTOR'
        description = 'llm model e2e probe agent 3'
        accessType  = 'API_KEY_LLM'
        modelType   = $ProbeProviderCode + ':m1'
        idempotent  = $true
    } @{}
    Assert-Pass ($reg4 -ne $null -and $reg4.code -eq 200) 'S10' ('same role different model allowed, code=' + $reg4.code)
} else {
    Write-Output '[S10] SKIP : base registration failed, skip uniqueness assertions'
}

Write-Output '==== S11: cleanup ===='
$agentList2 = Invoke-Json 'Get' ($BaseUrl + '/api/admin/agents/list') $null $adminHeaders
$cleanedAgents = 0
if ($agentList2 -ne $null -and $agentList2.data -ne $null) {
    $probeAgents2 = @($agentList2.data.list) | Where-Object { $_.name -like 'llm-model-e2e-*' }
    foreach ($a in $probeAgents2) {
        $delA = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/agents/deleteById/' + $a.id) @{ confirmName = $a.name } $adminHeaders
        if ($delA -ne $null -and $delA.code -eq 200) { $cleanedAgents++ }
    }
}
Assert-Pass ($cleanedAgents -ge 3) 'S11' ('cleaned ' + $cleanedAgents + ' probe agents')
$delProvFinal = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/deleteById/' + $probeId) $null $adminHeaders
Assert-Pass ($delProvFinal -ne $null -and $delProvFinal.code -eq 200) 'S11' ('delete probe provider code=' + $delProvFinal.code + ' msg=' + $delProvFinal.msg)

Write-Output '==== summary ===='
Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
Stop-BackendIfStarted
if ($global:FailCount -gt 0) {
    exit 1
}
Write-Output 'ALL PASSED'
exit 0
