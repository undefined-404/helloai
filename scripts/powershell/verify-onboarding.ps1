# verify-onboarding.ps1
# Step 1 of the external-agent end-to-end plan: verify one-click self-registration.
#   S1 POST /api/agents/register           -> returns apiKey + id (R.code == 200)
#   S2 GET  /api/agents/me/skill (Bearer)  -> skill placeholders fully substituted
#   S3 GET  /api/agent/inbox     (Bearer)  -> 200, proves the new key can authenticate
# Prereq: backend up on BaseUrl (PostgreSQL only; Redis/MQ NOT required for this step).
# Usage: .\scripts\powershell\verify-onboarding.ps1 [http://localhost:6565]
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Stop'
$BaseUrl = if ($args.Count -ge 1) { $args[0] } else { 'http://localhost:6565' }
# unique ASCII name to avoid duplicate-name rejection on re-run
$agentName = 'e2e-onboard-exec-' + (Get-Date -Format 'yyyyMMddHHmmss')

$pass = 0
$fail = 0
function Assert($cond, $label) {
    if ($cond) {
        Write-Host ('[PASS] ' + $label) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ('[FAIL] ' + $label) -ForegroundColor Red
        $script:fail++
    }
}

Write-Host ('=== verify-onboarding against ' + $BaseUrl + ' ===')
Write-Host ('agentName = ' + $agentName)

# ---- S1: self-registration ----
$apiKey = $null
$agentId = $null
try {
    $regBody = @{ name = $agentName; role = 'EXECUTOR'; description = 'e2e onboarding verify agent' } | ConvertTo-Json
    $reg = Invoke-RestMethod -Method Post -Uri ($BaseUrl + '/api/agents/register') -ContentType 'application/json' -Body $regBody
    Assert ($reg.code -eq 200) 'S1 register returns code 200'
    $apiKey = $reg.data.apiKey
    $agentId = $reg.data.id
    Assert ([string]::IsNullOrWhiteSpace($apiKey) -eq $false) 'S1 apiKey is present'
    Assert ($null -ne $agentId) 'S1 agentId is present'
    Write-Host ('       agentId=' + $agentId + '  apiKey=' + $apiKey.Substring(0, [Math]::Min(12, $apiKey.Length)) + '...')
} catch {
    Assert $false ('S1 register threw: ' + $_.Exception.Message)
}

# ---- S2: skill generation with variables substituted ----
if ($apiKey) {
    try {
        $headers = @{ Authorization = ('Bearer ' + $apiKey) }
        $skillResp = Invoke-RestMethod -Method Get -Uri ($BaseUrl + '/api/agents/me/skill') -Headers $headers
        Assert ($skillResp.code -eq 200) 'S2 me/skill returns code 200'
        $content = [string]$skillResp.data.content
        Assert ($content.Length -gt 0) 'S2 skill content not empty'
        # {{BASE_URL}} is an ASCII template token; it must be gone after substitution
        Assert ($content.Contains('{{BASE_URL}}') -eq $false) 'S2 no {{BASE_URL}} placeholder left'
        # positive checks: successful substitution means the real values are embedded.
        # (this also implicitly proves the CJK placeholders <..> were replaced,
        #  since the raw apiKey/baseUrl only appear after replacement.)
        Assert ($content.Contains($apiKey)) 'S2 skill embeds the real apiKey'
        Assert ($content.Contains($BaseUrl)) 'S2 skill embeds the real baseUrl'
    } catch {
        Assert $false ('S2 me/skill threw: ' + $_.Exception.Message)
    }
}

# ---- S3: the fresh key can authenticate against an agent endpoint ----
if ($apiKey) {
    try {
        $headers = @{ Authorization = ('Bearer ' + $apiKey) }
        $inbox = Invoke-RestMethod -Method Get -Uri ($BaseUrl + '/api/agent/inbox') -Headers $headers
        Assert ($inbox.code -eq 200) 'S3 inbox reachable with the new key (code 200)'
    } catch {
        Assert $false ('S3 inbox threw: ' + $_.Exception.Message)
    }
}

Write-Host ''
Write-Host ('=== RESULT: PASS=' + $pass + ' FAIL=' + $fail + ' ===')
if ($fail -eq 0) {
    Write-Host 'ALL PASSED: one-click self-registration + skill generation + key auth OK' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'SOME CHECKS FAILED (see above)' -ForegroundColor Red
    exit 1
}
