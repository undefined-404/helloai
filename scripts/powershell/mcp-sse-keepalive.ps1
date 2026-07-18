# MCP SSE 长连接保持脚本（幂等：每次启动先 truncate 日志）
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$logFile = "E:\yhzx\1027\helloai\scripts\powershell\logs\mcp-sse.log"
if (Test-Path $logFile) {
    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    Rename-Item -Path $logFile -NewName ("mcp-sse-" + $ts + ".log") -Force
}
"" | Out-File -FilePath $logFile -Encoding utf8

$hdr = @(
    "Authorization: Bearer ak_cbf5e0d7ea0a37639f0988d7f5664013",
    "Accept: text/event-stream"
)

& curl.exe -N -s --no-buffer `
    -H $hdr[0] -H $hdr[1] `
    http://localhost:6565/mcp/sse 2>&1 |
    ForEach-Object {
        $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $_
        Write-Host $line
        Add-Content -Path $logFile -Value $_ -Encoding utf8
    }
