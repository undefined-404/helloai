# ============================================================
# helloai Agent 执行预览验证脚本
# 用途：验证 Agent 执行链路"最小执行/预览"闭环——建 Agent +（可选）绑定 credential_vault，
#       触发执行并断言输出包含期望片段（默认 [mock-executor]）。
# Ref:  doc/HelloAI_执行链路架构分析.md；doc/HelloAI_实现差距表.md (N6 执行命令消费与结果回写 / N10 credential_vault)
# 前置：helloai-start 已在 6565 运行。
# 用法（项目根）：powershell -File .\scripts\powershell\verify-agent-execution-preview.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$ExpectedSubstring = "[mock-executor]",
    [switch]$SkipOutputAssert,
    [switch]$BindVault,
    [switch]$BindVaultTwice,
    [string]$VaultProvider = "deepseek",
    [string]$VaultApiKeyEnv = "DEEPSEEK_API_KEY",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123"
)

$ErrorActionPreference = "Stop"

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        Write-Host ("ASSERT_FAIL: " + $Msg)
        exit 1
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers) {
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    try {
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec 20
    } catch {
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $statusCode = $null
            try { $statusCode = [int]$resp.StatusCode } catch {}
            Write-Host ("HTTP_FAIL: " + $Method + " " + $Url + " status=" + $statusCode)
            try {
                $stream = $resp.GetResponseStream()
                if ($stream -ne $null) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $bodyText = $reader.ReadToEnd()
                    if (-not [string]::IsNullOrWhiteSpace($bodyText)) {
                        Write-Host "FAIL_BODY_BEGIN"
                        Write-Host $bodyText
                        Write-Host "FAIL_BODY_END"
                    }
                }
            } catch {
            }
        } else {
            Write-Host ("HTTP_FAIL: " + $Method + " " + $Url + " (no response body)")
        }
        throw
    }
}

Write-Host "STEP1: admin login"
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type = "admin"
    username = $AdminUsername
    credential = $AdminPassword
} -Headers @{}

Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
Assert-True ($loginResp.data -ne $null) "login data is null"
Assert-True (-not [string]::IsNullOrWhiteSpace($loginResp.data.token)) "admin token is empty"
$adminToken = $loginResp.data.token

Write-Host "STEP2: register API_KEY_LLM agent (idempotent fixed name)"
$name = "T6-preview"
$regResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = $name
    role = "EXECUTOR"
    description = "t6 preview"
    accessType = "API_KEY_LLM"
    modelType = "deepseek:deepseek-chat"
    idempotent = $true
} -Headers @{}

Assert-True ($regResp.code -eq 200) ("register code=" + $regResp.code + " msg=" + $regResp.msg)
Assert-True ($regResp.data -ne $null) "register data is null"
Assert-True ($regResp.data.id -ne $null) "agentId is null"
$agentId = [string]$regResp.data.id

if ($BindVault) {
    Write-Host "STEP2.1: bind credential_vault"
    $apiKey = [System.Environment]::GetEnvironmentVariable($VaultApiKeyEnv)
    Assert-True (-not [string]::IsNullOrWhiteSpace($apiKey)) ("env var is empty: " + $VaultApiKeyEnv)
    $bindResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/agents/" + $agentId + "/api-key") -Body @{
        provider = $VaultProvider
        apiKey = $apiKey
        remark = "verify-agent-execution-preview.ps1"
    } -Headers @{
        "X-Admin-Token" = $adminToken
    }
    Assert-True ($bindResp.code -eq 200) ("bind vault code=" + $bindResp.code + " msg=" + $bindResp.msg)

    if ($BindVaultTwice) {
        Write-Host "STEP2.2: bind credential_vault again (rotate)"
        $bind2Resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/agents/" + $agentId + "/api-key") -Body @{
            provider = $VaultProvider
            apiKey = $apiKey
            remark = "verify-agent-execution-preview.ps1 rotate"
        } -Headers @{
            "X-Admin-Token" = $adminToken
        }
        Assert-True ($bind2Resp.code -eq 200) ("bind2 vault code=" + $bind2Resp.code + " msg=" + $bind2Resp.msg)
    }

    $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/credentials/agents/" + $agentId) -Body $null -Headers @{
        "X-Admin-Token" = $adminToken
    }
    Assert-True ($listResp.code -eq 200) ("list vault code=" + $listResp.code + " msg=" + $listResp.msg)
    Assert-True ($null -ne $listResp.data) "list vault data is null"
    $credList = @($listResp.data)
    Assert-True ($credList.Count -ge 1) "list vault empty"
    $has = $false
    $activeCount = 0
    foreach ($c in $credList) {
        if (($c.provider -eq $VaultProvider) -and ($c.hasEncryptedValue -or $c.hasSecretRef)) {
            $has = $true
        }
        if (($c.provider -eq $VaultProvider) -and ($c.status -eq "ACTIVE")) {
            $activeCount = $activeCount + 1
        }
    }
    Assert-True ($has) ("vault record missing for provider: " + $VaultProvider)
    Assert-True ($activeCount -eq 1) ("vault active record count invalid: " + $activeCount)
} else {
    # 不带 -BindVault 时：先探测 vault 是否有 ACTIVE 凭证
    # 否则 STEP3 直接打到后端会 HTTP 500（vault_fetch 失败），错误信息不友好。
    Write-Host "STEP2.5: probe credential_vault (without -BindVault)"
    $hasActive = $false
    try {
        $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/credentials/agents/" + $agentId) -Body $null -Headers @{
            "X-Admin-Token" = $adminToken
        }
        if ($listResp.code -eq 200 -and $listResp.data) {
            foreach ($c in @($listResp.data)) {
                if (($c.provider -eq $VaultProvider) -and ($c.status -eq "ACTIVE") -and ($c.hasEncryptedValue -or $c.hasSecretRef)) {
                    $hasActive = $true
                    break
                }
            }
        }
    } catch {
        Write-Host ("VAULT_PROBE_FAIL: " + $_.Exception.Message + " — continuing to STEP3.")
    }

    if (-not $hasActive) {
        Write-Host ("VAULT_MISSING: no ACTIVE credential for provider=" + $VaultProvider + " on agentId=" + $agentId)
        Write-Host ("VAULT_MISSING: ExpectedSubstring=[" + $ExpectedSubstring + "] is for mock-mode, but default mock-mode=false requires a real key.")
        Write-Host 'VAULT_MISSING: options to proceed:'
        Write-Host ('  1) Set env var and re-run with -BindVault (real LLM call):')
        Write-Host ('       $env:' + $VaultApiKeyEnv + ' = "sk-your-real-key"')
        Write-Host ('       powershell -File .\scripts\powershell\verify-agent-execution-preview.ps1 -BindVault')
        Write-Host '  2) Switch backend to mock-mode=true and re-run without -BindVault (expect mock prefix):'
        Write-Host ('       application.yml -> helloai.execution.mock-mode: true  (then restart)')
        exit 1
    }
    Write-Host ("STEP2.5 OK: existing ACTIVE credential found for provider=" + $VaultProvider)
}

Write-Host "STEP3: preview execution"
$previewResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agent-executions/" + $agentId + "/preview") -Body @{
    userPrompt = "hello preview"
} -Headers @{
    "X-Admin-Token" = $adminToken
}

Assert-True ($previewResp.code -eq 200) ("preview code=" + $previewResp.code + " msg=" + $previewResp.msg)
Assert-True ($previewResp.data -ne $null) "preview data is null"
Assert-True ($previewResp.data.success -eq $true) "preview success=false"
if (-not $SkipOutputAssert) {
    Assert-True (-not [string]::IsNullOrEmpty($ExpectedSubstring)) "ExpectedSubstring is empty, use -SkipOutputAssert to bypass output assertion"
    Assert-True ($previewResp.data.output -match [Regex]::Escape($ExpectedSubstring)) ("output missing expected substring: " + $ExpectedSubstring)
}

Write-Host "OK: preview execution passed"
exit 0
