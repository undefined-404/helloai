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