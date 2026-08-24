# ============================================================
# helloai api-key verify endpoints verifier (v1.0)
# Purpose: verify V59 two-step add-model backend chain:
#       S0 health check (requires a running backend, no auto-start)
#       S1 admin login
#       S2 llm-providers list carries billingType (fallback API_KEY)
#       S3 create provider with billingType=API_KEY -> response echoes it
#       S4 verifyApiKeyById returns well-formed result (fake key -> success=false)
#       S5 create with billingType=TOKEN_PLAN -> rejected (400 validation)
#       S6 verifyWebSearchApiKey returns well-formed result
#       S7 cleanup: delete provider created by S3
# Ref:  doc/log/HelloAI_Iteration log (V59 add-model dialog + key verify)
#       AGENTS.md rule 6: scripts must declare UTF-8 encoding
# Prereq: backend already running (docker compose up -d for pg/redis/rabbitmq).
# Usage (repo root):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-api-key-verify.ps1
# Idempotent: S3 uses a fixed providerCode and deletes any leftover first.
# (all strings use single-quote + concat to avoid PS 5.1 parser issues)
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [string]$TestProviderCode = 'verify-key-probe',
    [string]$TestApiKey = 'sk-verify-key-probe-fake-0001'
)

# ------------------------------------------------------------
# UTF-8 mandatory header (AGENTS.md rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

$global:PassCount = 0
$global:FailCount = 0

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
            -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 60
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

Write-Output '==== S0: health check ===='
try {
    $health = Invoke-WebRequest -Uri ($BaseUrl + '/api/health') -UseBasicParsing -TimeoutSec 5
    Assert-Pass ($health.StatusCode -eq 200) 'S0' 'backend healthy'
} catch {
    Assert-Pass $false 'S0' ('backend not reachable at ' + $BaseUrl + ' ; start it first')
    Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    exit 1
}

Write-Output '==== S1: admin login ===='
$loginResp = Invoke-Json 'Post' ($BaseUrl + '/api/auth/login') @{
    type       = 'admin'
    username   = $AdminUsername
    credential = $AdminPassword
} @{}
Assert-Pass ($loginResp -ne $null -and $loginResp.code -eq 200) 'S1' ('login code=' + $loginResp.code)
if ($loginResp -eq $null -or $loginResp.code -ne 200) {
    Write-Output 'S1 abort: login failed, cannot continue'
    exit 1
}
$adminHeaders = @{ 'X-Admin-Token' = $loginResp.data.token }

Write-Output '==== S2: provider list carries billingType ===='
$listResp = Invoke-Json 'Get' ($BaseUrl + '/api/admin/llm-providers/list') $null $adminHeaders
Assert-Pass ($listResp -ne $null -and $listResp.code -eq 200) 'S2' ('list code=' + $listResp.code)
$first = $null
if ($listResp -ne $null -and $listResp.data -ne $null) {
    $first = @($listResp.data) | Select-Object -First 1
}
Assert-Pass ($first -ne $null -and -not [string]::IsNullOrWhiteSpace([string]$first.billingType)) 'S2' ('first provider billingType=' + $first.billingType)

# cleanup leftover from previous runs (idempotent re-run)
if ($listResp -ne $null -and $listResp.data -ne $null) {
    $leftover = @($listResp.data) | Where-Object { $_.providerCode -eq $TestProviderCode } | Select-Object -First 1
    if ($leftover -ne $null) {
        Write-Output ('[S2] cleanup leftover provider id=' + $leftover.id)
        Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/deleteById/' + $leftover.id) $null $adminHeaders | Out-Null
    }
}

Write-Output '==== S3: create provider with billingType=API_KEY ===='
$createResp = Invoke-Json 'Post' ($BaseUrl + '/api/admin/llm-providers') @{
    providerCode = $TestProviderCode
    providerName = 'Verify Key Probe'
    protocolType = 'OPENAI_COMPATIBLE'
    baseUrl      = 'https://probe.example.com'
    defaultModel = 'probe-model'
    billingType  = 'API_KEY'
    enabled      = 1
} $adminHeaders
Assert-Pass ($createResp -ne $null -and $createResp.code -eq 200) 'S3' ('create code=' + $createResp.code)
$createdId = $null
if ($createResp -ne $null -and $createResp.data -ne $null) {
    $createdId = $createResp.data.id
}
Assert-Pass ($createdId -ne $null) 'S3' ('created id=' + $createdId)
Assert-Pass ($createResp -ne $null -and $createResp.data.billingType -eq 'API_KEY') 'S3' ('response billingType=API_KEY')

Write-Output '==== S4: verifyApiKeyById well-formed result (fake key -> success=false) ===='
if ($createdId -ne $null) {
    # saveApiKeyById 接收纯文本 apiKey（与前端 text/plain 一致），不经 ConvertTo-Json 避免多包引号
    $saveResp = $null
    try {
        $saveResp = Invoke-RestMethod -Method 'Put' `
            -Uri ($BaseUrl + '/api/admin/llm-providers/saveApiKeyById/' + $createdId) `
            -Headers $adminHeaders -ContentType 'text/plain; charset=utf-8' `
            -Body $TestApiKey -TimeoutSec 60
    } catch {
        Write-Output ('HTTP_FAIL: Put saveApiKeyById msg=' + $_.Exception.Message)
    }
    Assert-Pass ($saveResp -ne $null -and $saveResp.code -eq 200) 'S4' ('save key code=' + $saveResp.code)
    $verifyResp = Invoke-Json 'Post' ($BaseUrl + '/api/admin/llm-providers/verifyApiKeyById/' + $createdId) $null $adminHeaders
    Assert-Pass ($verifyResp -ne $null -and $verifyResp.code -eq 200) 'S4' ('verify code=' + $verifyResp.code)
    $wellFormed = $verifyResp -ne $null -and $verifyResp.data -ne $null `
        -and ($verifyResp.data.success -eq $true -or $verifyResp.data.success -eq $false) `
        -and -not [string]::IsNullOrWhiteSpace([string]$verifyResp.data.message)
    Assert-Pass $wellFormed 'S4' ('success=' + $verifyResp.data.success + ' message=' + $verifyResp.data.message)
} else {
    Assert-Pass $false 'S4' 'skipped: no created provider id'
}

Write-Output '==== S5: billingType=TOKEN_PLAN rejected ===='
$rejectResp = Invoke-Json 'Post' ($BaseUrl + '/api/admin/llm-providers') @{
    providerCode = ($TestProviderCode + '-plan')
    providerName = 'Verify Token Plan'
    protocolType = 'OPENAI_COMPATIBLE'
    baseUrl      = 'https://probe.example.com'
    billingType  = 'TOKEN_PLAN'
} $adminHeaders
# 400 validation -> Invoke-Json returns null and prints HTTP_FAIL status=400
Assert-Pass ($rejectResp -eq $null -or $rejectResp.code -ne 200) 'S5' 'TOKEN_PLAN rejected (expect HTTP 400 above)'

Write-Output '==== S6: verifyWebSearchApiKey well-formed result ===='
$wsResp = Invoke-Json 'Post' ($BaseUrl + '/api/admin/config/verifyWebSearchApiKey') @{} $adminHeaders
Assert-Pass ($wsResp -ne $null -and $wsResp.code -eq 200) 'S6' ('verify code=' + $wsResp.code)
$wsWellFormed = $wsResp -ne $null -and $wsResp.data -ne $null `
    -and ($wsResp.data.success -eq $true -or $wsResp.data.success -eq $false) `
    -and -not [string]::IsNullOrWhiteSpace([string]$wsResp.data.message)
Assert-Pass $wsWellFormed 'S6' ('success=' + $wsResp.data.success + ' message=' + $wsResp.data.message)

Write-Output '==== S7: cleanup ===='
if ($createdId -ne $null) {
    $delResp = Invoke-Json 'Delete' ($BaseUrl + '/api/admin/llm-providers/deleteById/' + $createdId) $null $adminHeaders
    Assert-Pass ($delResp -ne $null -and $delResp.code -eq 200) 'S7' ('deleted probe provider code=' + $delResp.code)
} else {
    Write-Output '[S7] nothing to cleanup'
}

Write-Output ('RESULT: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
if ($global:FailCount -gt 0) {
    exit 1
}
exit 0
