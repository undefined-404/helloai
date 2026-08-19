# ============================================================
# helloai attachment versioning (same-name supersede) verifier v1.0
# Purpose:
#   S1  upload file a.md v1            -> ACTIVE
#   S2  upload same-name a.md v2       -> old version auto INACTIVE,
#                                        newest version becomes ACTIVE
#   S3  list attachments              -> two same-name rows,
#                                        old=INACTIVE new=ACTIVE
#   S4  upload different-name b.md    -> a.md stays ACTIVE (no re-supersede)
#   S5  active-only view              -> exactly [a.md v2, b.md] (platform
#                                        trusted view == review evidence view)
# Ref:
#   doc/HelloAI_实现差距表.md  (attachment versioning gap item)
#   .agents/skills/helloai-preflight/SKILL.md (rule 6: ps1 utf-8 header)
# Prereqs:
#   helloai-start running on :6565 (helloai.storage.enabled=true);
#   API agent whose id == subTask.assignedAgentId, with its API key.
# Usage (repo root, PS 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-attachment-version.ps1 `
#       -AgentId <agentId> -SubTaskId <subTaskId> -ApiKey <agentApiKey>
# (source file must be saved as UTF-8 with BOM; runtime literals all ASCII;
#  single-quote + concat only, no double-quote interpolation)
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [Parameter(Mandatory = $true)][long]$AgentId,
    [Parameter(Mandatory = $true)][long]$SubTaskId,
    [Parameter(Mandatory = $true)][string]$ApiKey
)

# ------------------------------------------------------------
# UTF-8 encoding force header (rule 6) -- avoid mojibake
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'

$runTag   = (Get-Date -Format 'yyyyMMdd-HHmmss')
$fileName = 'verify-attachment-version-' + $runTag + '.md'
$file2    = 'verify-attachment-other-' + $runTag + '.md'

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

# ------------------------------------------------------------
# helper: HTTP JSON via HttpClient (PS 5.1 safe, UTF-8)
# ------------------------------------------------------------
function Invoke-Json {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET','POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{}
    )
    $Body = $Body.TrimStart([char]0xFEFF)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -eq 'POST') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if ($Method -eq 'GET') {
            $resp = $client.GetAsync($Uri).Result
        } else {
            $resp = $client.PostAsync($Uri, $content).Result
        }
        $text = $resp.Content.ReadAsStringAsync().Result
        $json = $null
        $parseOk = $true
        try {
            $json = $text | ConvertFrom-Json
        } catch {
            $parseOk = $false
        }
        return @{ Code = [int]$resp.StatusCode; Body = $text; Json = $json; ParseOk = $parseOk }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message; Json = $null; ParseOk = $false }
    } finally {
        $client.Dispose()
    }
}

function Get-Attachments {
    $r = Invoke-Json -Method 'GET' -Uri ($BaseUrl + '/api/attachments?subTaskId=' + $SubTaskId)
    if ($r.Code -ne 200 -or -not $r.ParseOk) {
        Write-Output ('  list attachments failed: code=' + $r.Code + ' body=' + $r.Body)
        return $null
    }
    return @($r.Json.data)
}

function Upload-File {
    param([string]$Name, [long]$Size)
    $body = '{"subTaskId":' + $SubTaskId + ',"fileName":"' + $Name + '","mimeType":"text/markdown","fileSize":' + $Size + ',"storageUrl":"local://helloai-local/' + $SubTaskId + '/' + $Name + '"}'
    $headers = @{ 'Authorization' = 'Bearer ' + $ApiKey }
    return Invoke-Json -Method 'POST' -Uri ($BaseUrl + '/api/mcp/tools/uploadArtifact') -Body $body -Headers $headers
}

# ============================================================
# S0: backend health
# ============================================================
$h = Invoke-Json -Method 'GET' -Uri ($BaseUrl + '/api/health')
Assert-Pass ($h.Code -eq 200) 'S0-backend-health' ('health code=' + $h.Code)

# ============================================================
# S1: upload v1 (same-name file, first version)
# ============================================================
$v1 = Upload-File -Name $fileName -Size 10
$v1Ok = ($v1.Code -eq 200 -and $v1.Json -and $v1.Json.data -and $v1.Json.data.attachmentId)
Assert-Pass $v1Ok 'S1-upload-v1' ('upload v1 -> code=' + $v1.Code)
if (-not $v1Ok) {
    Write-Output 'STOP: v1 upload failed, cannot continue.'
    Write-Output ('SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    exit 1
}
$v1Id = $v1.Json.data.attachmentId

# ============================================================
# S2: upload v2 with SAME file name -> old one must be INACTIVE
# ============================================================
$v2 = Upload-File -Name $fileName -Size 20
$v2Ok = ($v2.Code -eq 200 -and $v2.Json -and $v2.Json.data -and $v2.Json.data.attachmentId)
Assert-Pass $v2Ok 'S2-upload-v2-same-name' ('upload v2 -> code=' + $v2.Code)
if (-not $v2Ok) {
    Write-Output 'STOP: v2 upload failed, cannot continue.'
    Write-Output ('SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    exit 1
}
$v2Id = $v2.Json.data.attachmentId

# ============================================================
# S3: list assertions -- two same-name rows, old INACTIVE / new ACTIVE
# ============================================================
$list1 = Get-Attachments
$sameName = @($list1 | Where-Object { $_.fileName -eq $fileName })
Assert-Pass ($sameName.Count -eq 2) 'S3-two-versions-exist' ('same-name rows=' + $sameName.Count)

$oldRow = @($sameName | Where-Object { $_.id -eq $v1Id })[0]
$newRow = @($sameName | Where-Object { $_.id -eq $v2Id })[0]
Assert-Pass ($oldRow -and $oldRow.status -eq 'INACTIVE') 'S3-old-version-INACTIVE' ('v1 status=' + $oldRow.status)
Assert-Pass ($newRow -and $newRow.status -eq 'ACTIVE') 'S3-new-version-ACTIVE' ('v2 status=' + $newRow.status)

# ============================================================
# S4: upload a DIFFERENT name -> must NOT touch a.md versions
# ============================================================
$b1 = Upload-File -Name $file2 -Size 30
$b1Ok = ($b1.Code -eq 200 -and $b1.Json -and $b1.Json.data -and $b1.Json.data.attachmentId)
Assert-Pass $b1Ok 'S4-upload-other-name' ('upload b.md -> code=' + $b1.Code)

$list2 = Get-Attachments
$sameName2 = @($list2 | Where-Object { $_.fileName -eq $fileName })
$oldRow2 = @($sameName2 | Where-Object { $_.id -eq $v1Id })[0]
$newRow2 = @($sameName2 | Where-Object { $_.id -eq $v2Id })[0]
Assert-Pass ($oldRow2.status -eq 'INACTIVE') 'S4-other-name-no-supersede' ('a.md v1 still=' + $oldRow2.status)
Assert-Pass ($newRow2.status -eq 'ACTIVE') 'S4-other-name-v2-kept' ('a.md v2 still=' + $newRow2.status)

# ============================================================
# S5: active-only view (== review/upstream trusted view) -- exactly 2 ACTIVE
# ============================================================
$activeOnly = @($list2 | Where-Object { $_.status -eq 'ACTIVE' })
Assert-Pass ($activeOnly.Count -eq 2) 'S5-active-count' ('ACTIVE rows=' + $activeOnly.Count)
$activeNames = @($activeOnly | ForEach-Object { $_.fileName })
Assert-Pass ($activeNames -contains $fileName -and $activeNames -contains $file2) 'S5-active-names' ('names=' + ($activeNames -join ','))
Assert-Pass (($activeNameIds = @($activeOnly | Where-Object { $_.fileName -eq $fileName }).id) -eq $v2Id) 'S5-active-newest-wins' ('active a.md id=' + $activeNameIds)

# ============================================================
# summary
# ============================================================
Write-Output ''
Write-Output ('SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
if ($global:FailCount -gt 0) {
    exit 1
}
Write-Output 'ALL PASSED: attachment same-name versioning works as expected.'
exit 0