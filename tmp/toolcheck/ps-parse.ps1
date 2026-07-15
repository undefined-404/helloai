$ErrorActionPreference = 'Stop'
$path = 'e:\yhzx\1027\helloai\verify-poller-e2e.ps1'
try {
    $null = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$null)
    Write-Output "PARSE_OK: $path"
} catch {
    Write-Output "PARSE_FAIL: $($_.Exception.Message)"
    exit 2
}