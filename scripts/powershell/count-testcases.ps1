# 统计 surefire XML 报告 testcase 数（surefire txt/XML 的 tests 属性存在统计 bug，以 testcase 元素为准）
# Usage: .\count-testcases.ps1 <xmlPath>
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$f = $args[0]
if (-not $f) { $f = 'e:\yhzx\1027\helloai\helloai-core\target\surefire-reports\TEST-com.helloai.core.planner.RequirementClarifyServiceTest.xml' }
$c = Get-Content $f -Raw -Encoding UTF8
$testcaseCount = ([regex]::Matches($c, '<testcase ')).Count
$failureCount = ([regex]::Matches($c, '<failure ')).Count
$errorCount = ([regex]::Matches($c, '<error ')).Count
Write-Host "TESTCASE_COUNT=$testcaseCount"
Write-Host "FAILURE_COUNT=$failureCount"
Write-Host "ERROR_COUNT=$errorCount"
if ($failureCount -eq 0 -and $errorCount -eq 0 -and $testcaseCount -gt 0) {
    Write-Host "RESULT=PASS"
} else {
    Write-Host "RESULT=FAIL"
}
