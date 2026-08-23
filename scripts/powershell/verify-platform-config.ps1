# ============================================================
# helloai platform provider config dynamic verifier (v1.0)
# 用途：验证"先启动后配置"平台级 Provider 配置链路（§6.51）：
#       S0 前置：health 检查；未启动则自动拉起 jar（清空 provider key 环境变量，
#          保证 yml api-key 占位符为空 -> provider 初始未配置）
#       S1 admin 登录
#       S2 初始列表：yml 无 key -> provider apiKeyConfigured=false（未配置）
#       S3 PUT api-key 写入平台级密钥（credential_vault PLATFORM 级，AES 加密）
#       S4 列表实时生效（不重启）：available=true / 脱敏尾4 / apiKeyFromVault=true
#       S5 目录同步：/api/admin/agents/listLlmProviders available=true
#       S6 注册 API_KEY_LLM Agent -> 自动补绑平台密钥
#       S7 vault 出现 AGENT 级 ACTIVE 凭证（hasEncryptedValue=true）
#       S8 外网地址断层修复：sys_config 写 helloai.base-url 后，
#          /api/agents/getMySkill 的 SKILL 内容立即包含该地址（不重启），
#          验证完写回空串还原
# Ref:  doc/log/HelloAI_迭代执行记录.md (§6.51 平台配置动态化)
#       .agents/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：docker compose up -d（helloai-postgres / redis / rabbitmq）；
#       mvn package 已产出最新 jar。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-platform-config.ps1
# 重复运行：vault 已有 PLATFORM 记录时 S2 的"未配置"断言会 FAIL，
#       加 -SkipInitialUnconfigured 跳过该断言（其余步骤幂等可重跑）。
# 只读模式：-ReadOnly 只跑 S0/S1/S2(仅展示)/S5(仅展示)，到 S3 写库前停下，
#       适合先确认服务器库当前状态、避免污染共享环境。
# (all strings use single-quote + concat to avoid PS 5.1 parser issues)
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [string]$Provider = 'deepseek',
    [string]$TestApiKey = 'sk-verify-platform-config-test-key-0001',
    [string]$TestBaseUrl = 'https://platform.example.com',
    [string]$JarPath = 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar',
    [string]$JavaExe = '',
    [int]$StartupTimeoutSec = 120,
    [switch]$SkipInitialUnconfigured,
    [switch]$ReadOnly,
    [switch]$KeepRunning
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

# 启动时需清空的 provider key 环境变量（防止 yml 占位符被 shell 环境填充）
$keyEnvVars = @('DEEPSEEK_API_KEY', 'MOONSHOT_API_KEY', 'MINIMAX_API_KEY', 'DASHSCOPE_API_KEY')

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
    if (-not [string]::IsNullOrWhiteSpace($JavaExe)) {
        return $JavaExe
    }
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

    # 清空 provider key 环境变量，保证 yml ${XXX_API_KEY:} 为空 -> 初始未配置
    foreach ($v in $keyEnvVars) {
        if (Test-Path ('Env:' + $v)) {
            Write-Output ('[S0] clear env var ' + $v)
            Remove-Item ('Env:' + $v) -ErrorAction SilentlyContinue
        }
    }

    $logDir = Join-Path $PSScriptRoot ('tmp\platform-config-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
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
    if ($global:StartedByScript -and -not $KeepRunning) {
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

Write-Output '==== S2: initial provider list (yml api-key empty) ===='
$listResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/platform/providers/list') $null $adminHeaders
Assert-Pass ($listResp -ne $null -and $listResp.code -eq 200) 'S2' ('list code=' + $listResp.code)
$item = $null
if ($listResp -ne $null -and $listResp.data -ne $null) {
    $item = @($listResp.data) | Where-Object { $_.name -eq $Provider } | Select-Object -First 1
}
Assert-Pass ($item -ne $null) 'S2' ('provider ' + $Provider + ' present in list')
if ($ReadOnly) {
    Write-Output '[S2] READONLY : current provider list state (no assert, show only)'
    $listResp.data | ConvertTo-Json -Depth 10
    $catRespRo = Invoke-Json 'Get' ($BaseUrl + '/api/admin/agents/listLlmProviders') $null $adminHeaders
    Write-Output '[S5] READONLY : current catalog state (show only)'
    $catRespRo.data | ConvertTo-Json -Depth 10
    Write-Output '==== READONLY: write steps skipped ===='
    Write-Output ('READONLY_MODE: next write step would be PUT /api/admin/platform/providers/saveApiKeyByProvider/' + $Provider + ' (writes PLATFORM-level credential to vault). Re-run without -ReadOnly after user confirmation.')
    Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    Stop-BackendIfStarted
    if ($global:FailCount -gt 0) {
        exit 1
    }
    exit 0
} elseif (-not $SkipInitialUnconfigured) {
    Assert-Pass ($item.apiKeyConfigured -eq $false) 'S2' ('initial apiKeyConfigured=false (yml empty, vault empty) for ' + $Provider)
    Assert-Pass ($item.available -eq $false) 'S2' ('initial available=false for ' + $Provider)
} else {
    Write-Output '[S2] SKIP : initial unconfigured check skipped (-SkipInitialUnconfigured)'
}

Write-Output '==== S3: PUT platform api-key ===='
$keyResp = Invoke-Json 'Put' ($BaseUrl + '/api/admin/platform/providers/saveApiKeyByProvider/' + $Provider) @{
    apiKey = $TestApiKey
} $adminHeaders
Assert-Pass ($keyResp -ne $null -and $keyResp.code -eq 200) 'S3' ('save api-key code=' + $keyResp.code + ' msg=' + $keyResp.msg)

Write-Output '==== S4: provider list live effect (no restart) ===='
$list2Resp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/platform/providers/list') $null $adminHeaders
$item2 = $null
if ($list2Resp -ne $null -and $list2Resp.data -ne $null) {
    $item2 = @($list2Resp.data) | Where-Object { $_.name -eq $Provider } | Select-Object -First 1
}
Assert-Pass ($item2.apiKeyConfigured -eq $true) 'S4' ('apiKeyConfigured=true for ' + $Provider)
Assert-Pass ($item2.available -eq $true) 'S4' ('available=true for ' + $Provider)
Assert-Pass ($item2.apiKeyFromVault -eq $true) 'S4' ('apiKeyFromVault=true (source=credential_vault)')
$suffix = $TestApiKey.Substring($TestApiKey.Length - 4)
Assert-Pass ($item2.apiKeyMasked -ne $null -and $item2.apiKeyMasked.EndsWith($suffix)) 'S4' ('masked ends with ' + $suffix + ' got=' + $item2.apiKeyMasked)

Write-Output '==== S5: catalog sync (agent registration dropdown) ===='
$catResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/agents/listLlmProviders') $null $adminHeaders
Assert-Pass ($catResp -ne $null -and $catResp.code -eq 200) 'S5' ('listLlmProviders code=' + $catResp.code)
$catItem = $null
if ($catResp -ne $null -and $catResp.data -ne $null) {
    $catItem = @($catResp.data) | Where-Object { $_.provider -eq $Provider } | Select-Object -First 1
}
Assert-Pass ($catItem.available -eq $true) 'S5' ('catalog available=true for ' + $Provider)

Write-Output '==== S6: register API_KEY_LLM agent (auto provision) ===='
$regResp = Invoke-Json 'Post' ($BaseUrl + '/api/agents/register') @{
    name        = 'platform-config-e2e'
    role        = 'EXECUTOR'
    description = 'platform config dynamic e2e probe'
    accessType  = 'API_KEY_LLM'
    modelType   = $Provider + ':deepseek-chat'
    idempotent  = $true
} @{}
Assert-Pass ($regResp -ne $null -and $regResp.code -eq 200) 'S6' ('register code=' + $regResp.code + ' msg=' + $regResp.msg)
if ($regResp -eq $null -or $regResp.code -ne 200) {
    Stop-BackendIfStarted
    exit 1
}
$agentId = [string]$regResp.data.id
$agentApiKey = [string]$regResp.data.apiKey
Assert-Pass (-not [string]::IsNullOrWhiteSpace($agentId)) 'S6' ('agentId=' + $agentId)
Assert-Pass (-not [string]::IsNullOrWhiteSpace($agentApiKey)) 'S6' 'agent apiKey not empty'

Write-Output '==== S7: AGENT-level credential auto-provisioned in vault ===='
$credResp = Invoke-Json 'Get' ($BaseUrl + '/api/credentials/listByAgentId/' + $agentId) $null $adminHeaders
Assert-Pass ($credResp -ne $null -and $credResp.code -eq 200) 'S7' ('listByAgentId code=' + $credResp.code)
$agentCred = $null
if ($credResp -ne $null -and $credResp.data -ne $null) {
    $agentCred = @($credResp.data) | Where-Object { $_.provider -eq $Provider -and $_.status -eq 'ACTIVE' -and ($_.hasEncryptedValue -or $_.hasSecretRef) } | Select-Object -First 1
}
Assert-Pass ($agentCred -ne $null) 'S7' ('AGENT-level ACTIVE credential for provider=' + $Provider + ' agentId=' + $agentId)

Write-Output '==== S8: base-url gap fix (sys_config -> getMySkill, no restart) ===='
$cfgResp = Invoke-Json 'Put' ($BaseUrl + '/api/admin/config/updateByKey/helloai.base-url') @{
    value = $TestBaseUrl
} $adminHeaders
Assert-Pass ($cfgResp -ne $null -and $cfgResp.code -eq 200) 'S8' ('set helloai.base-url code=' + $cfgResp.code)
$skillResp = Invoke-Json 'Get' ($BaseUrl + '/api/agents/getMySkill') $null @{
    Authorization = 'Bearer ' + $agentApiKey
}
Assert-Pass ($skillResp -ne $null -and $skillResp.code -eq 200) 'S8' ('getMySkill code=' + $skillResp.code)
Assert-Pass ($skillResp.data.content -ne $null -and $skillResp.data.content.Contains($TestBaseUrl)) 'S8' ('SKILL content contains ' + $TestBaseUrl + ' without restart')
# 还原 sys_config，避免影响后续手工验证
$restoreResp = Invoke-Json 'Put' ($BaseUrl + '/api/admin/config/updateByKey/helloai.base-url') @{
    value = ''
} $adminHeaders
Assert-Pass ($restoreResp -ne $null -and $restoreResp.code -eq 200) 'S8' ('restore helloai.base-url to empty code=' + $restoreResp.code)

Write-Output '==== summary ===='
Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
Stop-BackendIfStarted
if ($global:FailCount -gt 0) {
    exit 1
}
Write-Output 'ALL PASSED'
exit 0
