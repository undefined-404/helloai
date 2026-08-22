# ============================================================
# helloai dependency direction guard (regression) v1
# verify dependency direction for core business domains
#
# Purpose: assert one-way layering of core domains (CODE_STYLE
#          sec 3.x dependency direction red line):
#          planner/review -> task -> agent -> system -> shared
#          - system must NOT import task / agent / planner / review
#          - task   must NOT import planner / review
#          - agent  must NOT import planner / review
#          - agent must NOT poke task.mapper directly (zero target)
# Ref:  doc/HelloAI_CODE_STYLE.md sec 3.x (V1.9)
#       backend code review report P0 domain dependency direction
# Usage (repo root): powershell -File .\scripts\powershell\verify-dependency-direction.ps1
# Flow: for each (domain, forbidden import prefix) scan *.java,
#       any hit -> FAIL + list files; fail > 0 -> exit code 1.
# NOTE: keep runtime literals ASCII (PS 5.1 CJK parsing trap),
#       CJK text only in comments.
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$coreRoot = 'e:\yhzx\1027\helloai\helloai-core\src\main\java\com\helloai\core'
$fail = 0

# Assert a domain has no import with given prefix.
# Args: $1=domain dir  $2=rule label  $3=forbidden import prefix
function Assert-NoImport {
    param([string]$Domain, [string]$Label, [string]$Forbidden)
    $hits = @(Get-ChildItem -Path (Join-Path $coreRoot $Domain) -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |
        Select-String -SimpleMatch -Pattern $Forbidden -List | Select-Object -ExpandProperty Path)
    if ($hits.Count -gt 0) {
        $script:fail = $script:fail + 1
        Write-Output ('[FAIL] ' + $Domain + ' must not depend on [' + $Label + '], hits=' + $hits.Count + ':')
        foreach ($h in $hits) {
            Write-Output ('    ' + $h.Substring($coreRoot.Length + 1))
        }
    } else {
        Write-Output ('[PASS] ' + $Domain + ' has no dependency on [' + $Label + ']')
    }
}

Write-Output '=== dependency direction guard (system/task/agent forbidden imports) ==='
Assert-NoImport -Domain 'system' -Label 'task' -Forbidden 'import com.helloai.core.task'
Assert-NoImport -Domain 'system' -Label 'agent' -Forbidden 'import com.helloai.core.agent'
Assert-NoImport -Domain 'system' -Label 'planner' -Forbidden 'import com.helloai.core.planner'
Assert-NoImport -Domain 'system' -Label 'review' -Forbidden 'import com.helloai.core.review'
Assert-NoImport -Domain 'task' -Label 'planner' -Forbidden 'import com.helloai.core.planner'
Assert-NoImport -Domain 'task' -Label 'review' -Forbidden 'import com.helloai.core.review'
Assert-NoImport -Domain 'agent' -Label 'planner' -Forbidden 'import com.helloai.core.planner'
Assert-NoImport -Domain 'agent' -Label 'review' -Forbidden 'import com.helloai.core.review'
Assert-NoImport -Domain 'agent' -Label 'task.mapper(direct poke)' -Forbidden 'import com.helloai.core.task.mapper'

Write-Output ''
if ($fail -gt 0) {
    Write-Output ('RESULT: FAILED - FAIL=' + $fail + ' dependency direction violated, fix per CODE_STYLE sec 3.x')
    exit 1
}
Write-Output 'RESULT: ALL PASSED - core domains comply with dependency direction red line'
exit 0