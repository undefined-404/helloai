# verify-code-style-p1-ui-sync.ps1
# Stage 3 P1: Verify frontend API paths are in sync with backend Controller endpoints
# Usage: .\scripts\powershell\verify-code-style-p1-ui-sync.ps1
# ------------------------------------------------------------
# UTF-8 encoding header (Rule 6) - prevent CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'
$script:Mismatches = 0
$script:TotalApiCalls = 0
$script:MatchedCalls = 0

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$ControllerDir = Join-Path $RepoRoot 'helloai-api\src\main\java\com\helloai\api\controller'
$ApiDir = Join-Path $RepoRoot 'helloai-ui\src\api'

# Known frontend-only endpoints (backend not yet implemented)
$KnownExemptions = @(
    'POST /rules',
    'PUT /rules/updateById/{id}',
    'DELETE /rules/deleteById/{id}'
)
$ExemptionSet = @{}
foreach ($e in $KnownExemptions) { $ExemptionSet[$e] = $true }

Write-Output '=== verify-code-style-p1-ui-sync ==='
Write-Output ''

# -------------------------------------------------------------------
# Helper: extract path from a line (handles backtick, single-quote, double-quote)
# -------------------------------------------------------------------
function Extract-PathFromLine {
    param([string]$Line)
    if ($Line -match '`([^`]+)`') { return $matches[1] }
    if ($Line -match "'([^']+)'") { return $matches[1] }
    if ($Line -match '"([^"]+)"') { return $matches[1] }
    return ''
}

# -------------------------------------------------------------------
# Step 1: Build backend endpoint registry from Java controllers
# -------------------------------------------------------------------
Write-Output '--- Building backend endpoint registry ---'

$BackendEndpoints = @{}

Get-ChildItem -Path $ControllerDir -Filter '*Controller.java' | ForEach-Object {
    $content = Get-Content -Raw -Encoding UTF8 $_.FullName
    $lines = $content -split '\r?\n'

    $classPrefix = ''
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '@RequestMapping\("([^"]+)"\)') {
            $classPrefix = $matches[1]
            break
        }
    }

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '@(Get|Post|Put|Delete)Mapping(?:\s*\(\s*"([^"]*)"\s*\))?') {
            $httpMethod = $matches[1].ToUpper()
            $methodPath = if ($matches[2]) { $matches[2] } else { '' }
            $fullPath = $classPrefix + $methodPath
            # Normalize: remove /api prefix and standardize path variable names to {id}
            $normalizedPath = $fullPath -replace '^/api/', '/' -replace '/\{[^}]+\}', '/{id}'
            $key = "$httpMethod $normalizedPath"
            $BackendEndpoints[$key] = $true
        }
    }
}

Write-Output ('Backend endpoints registered: ' + $BackendEndpoints.Count)
Write-Output ''

# -------------------------------------------------------------------
# Step 2: Scan frontend API files line-by-line and check against registry
# -------------------------------------------------------------------
Write-Output '--- Scanning frontend API files ---'

Get-ChildItem -Path $ApiDir -Filter '*.ts' | Where-Object { $_.Name -ne 'request.ts' -and $_.Name -ne 'index.ts' } | Sort-Object Name | ForEach-Object {
    $fileShort = $_.Name
    $content = Get-Content -Raw -Encoding UTF8 $_.FullName
    $lines = $content -split '\r?\n'

    $inRequest = $false
    $pendingMethod = ''
    $fileCalls = 0

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]

        if ($line -match 'request\.(get|post|put|delete)') {
            $pendingMethod = $matches[1].ToUpper()
            $inRequest = $true
            $path = Extract-PathFromLine -Line $line
            if ($path) {
                $script:TotalApiCalls++
                $fileCalls++
                $matchPath = $path -replace '\$\{[^}]+\}', '{id}'
                $key = "$pendingMethod $matchPath"
                if ($BackendEndpoints.ContainsKey($key)) {
                    $script:MatchedCalls++
                } elseif ($ExemptionSet.ContainsKey($key)) {
                    $script:MatchedCalls++
                } else {
                    $script:Mismatches++
                    Write-Output ('  MISMATCH [' + $pendingMethod + '] ' + $matchPath + '  (' + $fileShort + ')')
                }
                $inRequest = $false
            }
        }
        elseif ($inRequest) {
            $path = Extract-PathFromLine -Line $line
            if ($path) {
                $script:TotalApiCalls++
                $fileCalls++
                $matchPath = $path -replace '\$\{[^}]+\}', '{id}'
                $key = "$pendingMethod $matchPath"
                if ($BackendEndpoints.ContainsKey($key)) {
                    $script:MatchedCalls++
                } elseif ($ExemptionSet.ContainsKey($key)) {
                    $script:MatchedCalls++
                } else {
                    $script:Mismatches++
                    Write-Output ('  MISMATCH [' + $pendingMethod + '] ' + $matchPath + '  (' + $fileShort + ')')
                }
                $inRequest = $false
            }
        }
    }

    Write-Output ('  ' + $fileShort + ' (' + $fileCalls + ' calls)')
}

# -------------------------------------------------------------------
# Summary
# -------------------------------------------------------------------
Write-Output ''
Write-Output '========================================'
Write-Output ('Total frontend API calls: ' + $script:TotalApiCalls)
Write-Output ('Matched: ' + $script:MatchedCalls)
Write-Output ('Mismatches: ' + $script:Mismatches)
if ($script:Mismatches -eq 0) {
    Write-Output 'ALL SYNCED - 0 mismatches'
} else {
    Write-Output ('SYNC FAILURES - ' + $script:Mismatches + ' mismatch(es) remaining')
}
Write-Output '========================================'
exit $script:Mismatches
