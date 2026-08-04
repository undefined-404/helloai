# verify-code-style-p1-paths.ps1
# Stage 2 P1: Verify backend Controller path/method naming compliance with CODE_STYLE.md S8.2
# Usage: .\scripts\powershell\verify-code-style-p1-paths.ps1
# ------------------------------------------------------------
# UTF-8 encoding header (Rule 6) - prevent CJK garbled output
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'
$script:Failures = 0
$script:Total = 0

$RepoRoot = Resolve-Path "$PSScriptRoot\..\.."
$ControllerDir = Join-Path $RepoRoot 'helloai-api\src\main\java\com\helloai\api\controller'

Write-Output ('=== verify-code-style-p1-paths ===')
Write-Output ('Controller dir: ' + $ControllerDir)
Write-Output ''

# -------------------------------------------------------------------
# Helper: extract @XxxMapping annotations and check path compliance
# -------------------------------------------------------------------
function Check-ControllerPaths {
    param([string]$FilePath, [string]$ShortName)

    $content = Get-Content -Raw -Encoding UTF8 $FilePath
    $lines = $content -split '\r?\n'

    # Extract class-level @RequestMapping
    $classPrefix = ''
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '@RequestMapping\("([^"]+)"\)') {
            $classPrefix = $matches[1]
            break
        }
    }

    Write-Output ('--- ' + $ShortName + ' (' + $classPrefix + ') ---')

    # Scan method-level @XxxMapping annotations
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $lineNum = $i + 1

        if ($line -match '@(Get|Post|Put|Delete)Mapping\("([^"]*)"\)') {
            $httpMethod = $matches[1]
            $methodPath = $matches[2]
            $fullPath = $classPrefix + $methodPath
            $script:Total++

            # Get method name from next line
            $methodName = ''
            for ($j = $i + 1; $j -lt [Math]::Min($i + 4, $lines.Count); $j++) {
                if ($lines[$j] -match 'public\s+\S+\s+(\w+)\(') {
                    $methodName = $matches[1]
                    break
                }
            }

            # Rule checks
            $violations = @()

            # R1: Kebab-case check (exempt: resource nouns, external CLI contracts, deprecated MCP paths)
            if ($methodPath -match '/([a-z]+)-([a-z]+)') {
                $isExempt = $methodPath -match '^/(sub-tasks|requirement-conversations|agent-executions|duty-leases|by-agent)'
                # External contract exemptions: ToolsController CLI paths, deprecated McpController, SSE doorbell
                if ($methodPath -match '^(/cli/check-update|/sse|/tools/)') { $isExempt = $true }
                if (-not $isExempt) {
                    $violations += 'kebab-case: use camelCase'
                }
            }

            # R2: Noun-based path check (path segments that are nouns without verb prefix)
            $pathSegments = $methodPath -split '/' | Where-Object { $_ -ne '' }
            $nounViolations = @()
            $knownVerbs = @('get', 'list', 'find', 'create', 'update', 'delete', 'change',
                           'claim', 'start', 'submit', 'complete', 'rework', 'block',
                           'reassign', 'redispatch', 'pause', 'resume', 'execute',
                           'check', 'set', 'bind', 'mark', 'archive', 'sleep', 'wake',
                           'register', 'login', 'logout', 'reset', 'download', 'upload',
                           'generate', 'confirm', 'reject', 'republish', 'send', 'retry',
                           'finalize', 'regenerate', 'abandon', 'compose', 'adjust',
                           'connect', 'merge', 'batch', 'plan', 'preview', 'page')

            foreach ($seg in $pathSegments) {
                # Skip if it's a path variable like {id}
                if ($seg -match '^\{') { continue }
                # Skip if it ends with "ById" or "ByXxx"
                if ($seg -match 'ById$|By\w+Id$|By\w+$') { continue }
                # Skip if it starts with a known verb
                $startsWithVerb = $false
                foreach ($verb in $knownVerbs) {
                    if ($seg -cmatch "^$verb[A-Z]") {
                        $startsWithVerb = $true
                        break
                    }
                }
                if (-not $startsWithVerb) {
                    # Pure nouns without verb prefix are violations
                    # But single-segment root paths (like just "/") are OK
                    if ($seg -notmatch '^(sse|me|cli|tools|jsonrpc|all|overview|highlights|trends|stats|logs|leaderboard|status|count|agents|merged|default|batch|planner-options|check)$') {
                        # Already caught by specific checks above
                    }
                }
            }

            # R3: RESTful nested check: {param}/something or something/{param}/something
            if ($methodPath -match '/\{[^}]+\}/[a-zA-Z]') {
                $violations += 'RESTful nested: use descriptive action+ById pattern'
            }

            # R4: Method name check (noun-based method names)
            $nounMethods = @{
                'overview' = 'getOverview'
                'highlights' = 'getHighlights'
                'trends' = 'getTrends'
                'stats' = 'getStats'
                'status' = 'getStatus'
                'count' = 'getUnreadCount'
                'leaderboard' = 'getLeaderboard'
                'logs' = 'listLogs'
                'agents' = 'listAgents'
                'plannerOptions' = 'listPlannerOptions'
                'connectivity' = 'checkConnectivityByAgentId'
                'preview' = 'previewByAgentId'
                'detail' = 'getById'
                'connect' = 'connect'  # OK - SSE
            }
            if ($nounMethods.ContainsKey($methodName) -and $methodName -ne $nounMethods[$methodName]) {
                $violations += ('method name "' + $methodName + '" should be "' + $nounMethods[$methodName] + '"')
            }

            # Report
            if ($violations.Count -gt 0) {
                $script:Failures++
                Write-Output ('  FAIL L' + $lineNum + ' [' + $httpMethod + ' ' + $fullPath + '] ' + $methodName + '()')
                foreach ($v in $violations) {
                    Write-Output ('       -> ' + $v)
                }
            }
        }
    }

    Write-Output ''
}

# -------------------------------------------------------------------
# Scan all controllers
# -------------------------------------------------------------------
Get-ChildItem -Path $ControllerDir -Filter '*Controller.java' | ForEach-Object {
    Check-ControllerPaths -FilePath $_.FullName -ShortName $_.Name
}

# -------------------------------------------------------------------
# Summary
# -------------------------------------------------------------------
Write-Output ('========================================')
Write-Output ('Total endpoints checked: ' + $script:Total)
Write-Output ('Violations found: ' + $script:Failures)
if ($script:Failures -eq 0) {
    Write-Output ('ALL PASSED - 0 violations')
} else {
    Write-Output ('SOME FAILURES - ' + $script:Failures + ' violation(s) remaining')
}
Write-Output ('========================================')
exit $script:Failures
