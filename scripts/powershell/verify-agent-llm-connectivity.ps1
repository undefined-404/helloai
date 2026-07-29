# ============================================================
# helloai Agent LLM 真连通验证脚本
# 用途：验证平台内 API_KEY_LLM Agent 通过 credential_vault 绑定真实 Provider(DeepSeek)
#       后能真正连通 LLM 并返回结果（真连通冒烟，非 mock）。
# Ref:  doc/HelloAI_实现差距表.md (N9 Provider 配置与 ChatClient 复用 / N10 credential_vault)
# 前置：helloai-start 已在 6565 运行；-BindVault 时需环境变量 DEEPSEEK_API_KEY。
# 用法（项目根）：powershell -File .\scripts\powershell\verify-agent-llm-connectivity.ps1 -BindVault
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [switch]$BindVault,
    [string]$VaultProvider = "deepseek",
    [string]$VaultApiKeyEnv = "DEEPSEEK_API_KEY",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [switch]$SkipSuccessAssert
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
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec 120
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
$name = "T7-connectivity"
$regResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
    name = $name
    role = "EXECUTOR"
    description = "llm connectivity probe"
    accessType = "API_KEY_LLM"
    modelType = "deepseek:deepseek-chat"
    idempotent = $true
} -Headers @{}

Assert-True ($regResp.code -eq 200) ("register code=" + $regResp.code + " msg=" + $regResp.msg)
Assert-True ($regResp.data -ne $null) "register data is null"
Assert-True ($regResp.data.id -ne $null) "agentId is null"
$agentId = [string]$regResp.data.id
Write-Host ("agentId=" + $agentId)

if ($BindVault) {
    Write-Host "STEP2.1: bind credential_vault"
    $apiKey = [System.Environment]::GetEnvironmentVariable($VaultApiKeyEnv)
    Assert-True (-not [string]::IsNullOrWhiteSpace($apiKey)) ("env var is empty: " + $VaultApiKeyEnv)
    $bindResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/agents/" + $agentId + "/api-key") -Body @{
        provider = $VaultProvider
        apiKey = $apiKey
        remark = "verify-agent-llm-connectivity.ps1"
    } -Headers @{
        "X-Admin-Token" = $adminToken
    }
    Assert-True ($bindResp.code -eq 200) ("bind vault code=" + $bindResp.code + " msg=" + $bindResp.msg)
} else {
    # 不带 -BindVault 时：先检查 vault 是否已有该 agent 的 active 凭证
    # 如果没有，给出明确提示，避免直接到 STEP3 才暴露 vault_fetch 失败。
    Write-Host "STEP2.5: probe credential_vault (without -BindVault)"
    try {
        $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/credentials/agents/" + $agentId) -Body $null -Headers @{
            "X-Admin-Token" = $adminToken
        }
        $hasActive = $false
        if ($listResp.code -eq 200 -and $listResp.data) {
            foreach ($c in @($listResp.data)) {
                if (($c.provider -eq $VaultProvider) -and ($c.status -eq "ACTIVE") -and ($c.hasEncryptedValue -or $c.hasSecretRef)) {
                    $hasActive = $true
                    break
                }
            }
        }
        if (-not $hasActive) {
            Write-Host ("VAULT_MISSING: no ACTIVE credential for provider=" + $VaultProvider + " on agentId=" + $agentId)
            Write-Host ('VAULT_MISSING: re-run with -BindVault and env var [' + $VaultApiKeyEnv + '] set, e.g.:')
            Write-Host ('  $env:' + $VaultApiKeyEnv + ' = "sk-your-real-key"')
            Write-Host "  powershell -File .\scripts\powershell\verify-agent-llm-connectivity.ps1 -BindVault"
            exit 1
        }
        Write-Host ("STEP2.5 OK: existing ACTIVE credential found for provider=" + $VaultProvider)
    } catch {
        Write-Host ("VAULT_PROBE_FAIL: " + $_.Exception.Message)
        Write-Host "VAULT_PROBE_FAIL: continuing to STEP3 (will likely fail at vault_fetch)."
    }
}

Write-Host "STEP3: call connectivity probe"
$probeResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agent-executions/connectivity/" + $agentId) -Body @{
    userPrompt = "Please reply with OK for HelloAI connectivity probe."
} -Headers @{
    "X-Admin-Token" = $adminToken
}

Assert-True ($probeResp.code -eq 200) ("probe code=" + $probeResp.code + " msg=" + $probeResp.msg)
Assert-True ($probeResp.data -ne $null) "probe data is null"

Write-Host "CONNECTIVITY_RESULT_BEGIN"
$probeResp.data | ConvertTo-Json -Depth 10
Write-Host "CONNECTIVITY_RESULT_END"

if (-not $SkipSuccessAssert) {
    Assert-True ($probeResp.data.success -eq $true) ("connectivity success=false stage=" + $probeResp.data.stage + " error=" + $probeResp.data.errorMessage)
}

Write-Host "OK: connectivity probe finished"
exit 0
