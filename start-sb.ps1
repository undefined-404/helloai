$ErrorActionPreference = 'Stop'
Set-Location e:\yhzx\1027\helloai
$logFile = 'e:\yhzx\1027\helloai\spring-boot-run.log'
if (Test-Path $logFile) { Remove-Item $logFile -Force }
$jarPath = 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar'
$needBuild = $true
if (Test-Path $jarPath) {
    $ageMinutes = ((Get-Date) - (Get-Item $jarPath).LastWriteTime).TotalMinutes
    if ($ageMinutes -lt 5) {
        $needBuild = $false
    }
}
if ($needBuild) {
    Write-Host "Building helloai-start jar..."
    mvn -pl helloai-start -am -DskipTests package | Out-File -Encoding utf8 -FilePath $logFile -Append
}
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', $jarPath) `
    -RedirectStandardOutput $logFile `
    -PassThru -NoNewWindow
Write-Host "Started PID=$($proc.Id)"
$proc.Id | Out-File -FilePath e:\yhzx\1027\helloai\.spring-boot-pid -Encoding ASCII
