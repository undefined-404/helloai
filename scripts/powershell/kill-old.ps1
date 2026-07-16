# ============================================================
# helloai 辅助运维脚本：释放本地 6565 端口
# 用途：杀掉占用 6565（helloai-start）的旧进程，等待端口释放后打印状态；
#       通常在重启后端前调用（被 run-redispatch-diagnose.ps1 调用，或手工重启）。
# Ref:  辅助脚本，无直接 doc 对应；配合 start-sb.ps1 使用。
# 用法（项目根）：powershell -File .\scripts\powershell\kill-old.ps1
# ============================================================
$conns = Get-NetTCPConnection -LocalPort 6565 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) {
    Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host ("Killed PID " + $c.OwningProcess)
}
Start-Sleep -Seconds 4
$alive = Get-NetTCPConnection -LocalPort 6565 -State Listen -ErrorAction SilentlyContinue
if ($alive) {
    Write-Host ("Still alive: " + ($alive.OwningProcess -join ','))
} else {
    Write-Host "Port 6565 free"
}