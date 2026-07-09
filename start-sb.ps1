$ErrorActionPreference = 'Stop'
Set-Location e:\yhzx\1027\helloai
$logFile = 'e:\yhzx\1027\helloai\spring-boot-run.log'
if (Test-Path $logFile) { Remove-Item $logFile -Force }
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar') `
    -RedirectStandardOutput $logFile `
    -PassThru -NoNewWindow
Write-Host "Started PID=$($proc.Id)"
$proc.Id | Out-File -FilePath e:\yhzx\1027\helloai\.spring-boot-pid -Encoding ASCII
