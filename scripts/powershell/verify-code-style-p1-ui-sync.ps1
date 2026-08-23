# verify-code-style-p1-ui-sync.ps1
# Stage 3 P1: Verify frontend paths.ts (single source of truth) is in sync with backend Controller endpoints
# Channel A: every path literal in paths.ts must exist as a backend endpoint (method-agnostic)
# Channel B: every request.xxx() call in api/*.ts must reference a valid paths.<block>.<key> (no inline literals)
# Usage: .\scripts\powershell\verify-code-style-p1-ui-sync.ps1
# ------------------------------------------------------------
# UTF-8 encoding header (Rule 6) - prevent CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'
$script:Failures = 0

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$ControllerDir = Join-Path $RepoRoot 'helloai-api\src\main\java\com\helloai\api\controller'
$ApiDir = Join-Path $RepoRoot 'helloai-ui\src\api'
$PathsFile = Join-Path $ApiDir 'paths.ts'

# Known frontend-only endpoints (backend not yet implemented): method+path for Channel B
$KnownExemptions = @(
    'POST /rules',
    'PUT /rules/updateById/{id}',
    'DELETE /rules/deleteById/{id}'
)
$ExemptionSet = @{}
$PathExemptionSet = @{}
foreach ($e in $KnownExemptions) {
    $ExemptionSet[$e] = $true
    $PathExemptionSet[$e.Substring($e.IndexOf(' ') + 1)] = $true
}

# -------------------------------------------------------------------
# Helper: normalize a path (strip /api prefix, unify path variables to {id})
# -------------------------------------------------------------------
function Normalize-Path {
    param([string]$Path)
    $p = $Path -replace '^/api/?', '/' -replace '\$\{[^}]+\}', '{id}' -replace '/\{[^}]+\}', '/{id}'
    return $p
}

Write-Output '=== verify-code-style-p1-ui-sync ==='
Write-Output ''

# -------------------------------------------------------------------
# Step 1: Build backend endpoint registry from Java controllers
# -------------------------------------------------------------------
Write-Output '--- Step 1: backend endpoint registry ---'

$BackendEndpoints = @{}
$BackendPaths = @{}

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
            $normalizedPath = Normalize-Path ($classPrefix + $methodPath)
            $BackendEndpoints["$httpMethod $normalizedPath"] = $true
            $BackendPaths[$normalizedPath] = $true
        }
    }
}

Write-Output ('Backend paths registered (method-agnostic): ' + $BackendPaths.Count)
Write-Output ('Backend endpoints registered (method+path): ' + $BackendEndpoints.Count)
Write-Output ''

# -------------------------------------------------------------------
# Step 2 (Channel A): parse paths.ts path literals, method-agnostic match
# -------------------------------------------------------------------
Write-Output '--- Step 2 (Channel A): paths.ts literals vs backend ---'

$PathKeyMap = @{}
$PathLiterals = @{}

$blockStack = New-Object System.Collections.Generic.List[string]
$pathsLines = (Get-Content -Raw -Encoding UTF8 $PathsFile) -split '\r?\n'
foreach ($line in $pathsLines) {
    $t = $line.Trim()
    if ($t -eq '') { continue }
    if ($t.StartsWith('//')) { continue }
    if ($t -match '^paths\s*=\s*\{') { $blockStack.Add('paths'); continue }
    if ($t -match '^\}\s*,?\s*$') {
        if ($blockStack.Count -gt 0) { $blockStack.RemoveAt($blockStack.Count - 1) }
        continue
    }
    if ($t -match '^(\w+):\s*\{') { $blockStack.Add($matches[1]); continue }
    if ($blockStack.Count -gt 0 -and $t -match '^(\w+):\s*(.+)$') {
        $key = $matches[1]
        $valPart = $matches[2]
        $val = ''
        if ($valPart -match "'([^']+)'") { $val = $matches[1] }
        elseif ($valPart -match '`([^`]+)`') { $val = $matches[1] }
        if ($val -like '/*') {
            $fullKey = ($blockStack -join '.') + '.' + $key
            $np = Normalize-Path $val
            $PathKeyMap[$fullKey] = $np
            if (-not $PathLiterals.ContainsKey($np)) { $PathLiterals[$np] = $fullKey }
        }
    }
}

Write-Output ('paths.ts path literals extracted: ' + $PathLiterals.Count)
$channelAMisses = @()
foreach ($np in ($PathLiterals.Keys | Sort-Object)) {
    if (-not $BackendPaths.ContainsKey($np) -and -not $PathExemptionSet.ContainsKey($np)) {
        $channelAMisses += $np
        $script:Failures++
        Write-Output ('  MISSING [' + $PathLiterals[$np] + '] ' + $np + '  (no backend endpoint, not exempted)')
    }
}
if ($channelAMisses.Count -eq 0) {
    Write-Output '  Channel A: ALL paths.ts literals exist in backend (or exempted)'
}
Write-Output ''

# -------------------------------------------------------------------
# Step 3 (Channel B): api/*.ts request calls must reference paths.<block>.<key>
# -------------------------------------------------------------------
Write-Output '--- Step 3 (Channel B): api file references ---'

$inlineArgPattern = '^\s*[''\x60"]'
$callPattern = 'request\.(get|post|put|delete)'
$chainPattern = 'paths\.([a-zA-Z]\w*(?:\.[a-zA-Z]\w*)*)'
# Matches "request.post(\n" or "request.post<T,R>(\n" (argument on next line)
$openCallPattern = 'request\.(get|post|put|delete)\s*(?:<[^>]*>)?\(\s*$'

$script:TotalCalls = 0
$script:ChannelBFailures = 0

Get-ChildItem -Path $ApiDir -Filter '*.ts' | Where-Object { $_.Name -ne 'paths.ts' -and $_.Name -ne 'request.ts' -and $_.Name -ne 'index.ts' } | Sort-Object Name | ForEach-Object {
    $fileShort = $_.Name
    $content = Get-Content -Raw -Encoding UTF8 $_.FullName
    $lines = $content -split '\r?\n'
    $fileCalls = 0
    $fileViolations = @()

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match $callPattern) {
            $httpMethod = $matches[1].ToUpper()
            $script:TotalCalls++
            $fileCalls++
            $trimmed = $line.Trim()
            if ($trimmed.StartsWith('//')) { continue }

            # Locate the argument line: same line, or next line for generic multi-line form
            $argIdx = $i
            $argLine = $line
            if ($line -notmatch $chainPattern -and $line -match $openCallPattern) {
                if ($i + 1 -lt $lines.Count) {
                    $argIdx = $i + 1
                    $argLine = $lines[$argIdx]
                }
            }

            if ($argLine -match $inlineArgPattern) {
                $script:Failures++
                $script:ChannelBFailures++
                $fileViolations += ('  INLINE path literal on L' + ($argIdx + 1) + ' (S19.0): ' + $argLine.Trim())
                continue
            }
            if ($argLine -match $chainPattern) {
                $chain = $matches[1]
                if ($PathKeyMap.ContainsKey($chain)) {
                    $np = $PathKeyMap[$chain]
                    $key = "$httpMethod $np"
                    if (-not $BackendEndpoints.ContainsKey($key) -and -not $ExemptionSet.ContainsKey($key)) {
                        $script:Failures++
                        $script:ChannelBFailures++
                        $fileViolations += ('  MISMATCH [' + $httpMethod + ' ' + $np + '] via paths.' + $chain + ' on L' + ($argIdx + 1))
                    }
                } else {
                    $script:Failures++
                    $script:ChannelBFailures++
                    $fileViolations += ('  UNKNOWN key paths.' + $chain + ' on L' + ($argIdx + 1) + ': ' + $argLine.Trim())
                }
            } else {
                $script:Failures++
                $script:ChannelBFailures++
                $fileViolations += ('  NO paths.* reference on L' + ($argIdx + 1) + ': ' + $argLine.Trim())
            }
        }
    }

    $status = 'OK'
    if ($fileViolations.Count -gt 0) { $status = 'FAIL' }
    Write-Output ('  ' + $fileShort + ' (' + $fileCalls + ' calls) - ' + $status)
    foreach ($v in $fileViolations) { Write-Output $v }
}

Write-Output ''
Write-Output ('Total request calls scanned: ' + $script:TotalCalls)
Write-Output ('Channel B violations: ' + $script:ChannelBFailures)
Write-Output ''

# -------------------------------------------------------------------
# Summary
# -------------------------------------------------------------------
Write-Output '========================================'
Write-Output ('Channel A (paths.ts literals) unmatched: ' + $channelAMisses.Count)
Write-Output ('Channel B (api references) violations: ' + $script:ChannelBFailures)
Write-Output ('Total failures: ' + $script:Failures)
if ($script:Failures -eq 0) {
    Write-Output 'ALL SYNCED - 0 failures'
} else {
    Write-Output ('SYNC FAILURES - ' + $script:Failures + ' failure(s) remaining')
}
Write-Output '========================================'
exit $script:Failures
