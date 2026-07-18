# qoder-ceshi Agent 门铃 SSE 后台长连接脚本
# 前置：checkIn 已成功，租约 ACTIVE
# 用途：保持 GET /api/agents/doorbell/sse 长连接，自动追加心跳后保持在线
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$logFile = 'E:\yhzx\1027\helloai\scripts\powershell\logs\doorbell-sse.log'
if (Test-Path $logFile) {
    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    Rename-Item -Path $logFile -NewName ("doorbell-sse-" + $ts + ".log") -Force
}
'' | Out-File -FilePath $logFile -Encoding utf8

$hdr = 'Authorization: Bearer ak_cbf5e0d7ea0a37639f0988d7f5664013'

& curl.exe -i -N -s --no-buffer -H $hdr http://localhost:6565/api/agents/doorbell/sse 2>&1 |
    ForEach-Object {
        $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $_
        Write-Host $line
        Add-Content -Path $logFile -Value $_ -Encoding utf8
    }
