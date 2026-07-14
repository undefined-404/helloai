$ErrorActionPreference = 'Stop'
Set-Location e:\yhzx\1027\helloai
$logFile = 'e:\yhzx\1027\helloai\spring-boot-e2e-mq.log'
$errFile = "$logFile.err"
if (Test-Path $logFile) { Remove-Item $logFile -Force }
if (Test-Path $errFile) { Remove-Item $errFile -Force }
$jarPath = 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar'

# 动态枚举 Java 路径，避免脚本文件里写死中文用户名（Node fallback shell 编码不一致会导致 Start-Process 找不到 exe）
$userDir = Get-ChildItem 'C:\Users' -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName '.jdks\ms-17.0.18\bin\java.exe') } |
    Select-Object -First 1
if (-not $userDir) { throw 'JDK 17 not found under C:\Users\*\.jdks\ms-17.0.18' }
$javaExe = Join-Path $userDir.FullName '.jdks\ms-17.0.18\bin\java.exe'
if (-not (Test-Path $javaExe)) { throw "Java executable not found: $javaExe" }

# Phase 2G E2E：dispatch-mode=BOTH + producer/consumer 双开关 true
$javaArgs = @(
    '-Dhelloai.execution.dispatch-mode=BOTH',
    '-Dhelloai.mq.execution-command.producer-enabled=true',
    '-Dhelloai.mq.execution-command.consumer-enabled=true',
    '-Dlogging.level.com.helloai.core.agent.command=DEBUG',
    '-Dlogging.level.com.helloai.core.agent.mqconsumer=DEBUG',
    '-Dlogging.level.com.helloai.mq=DEBUG',
    '-jar',
    $jarPath
)

$proc = Start-Process -FilePath $javaExe `
    -ArgumentList $javaArgs `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errFile `
    -WindowStyle Hidden `
    -PassThru
Write-Host "Started PID=$($proc.Id) mode=E2E-MQ-BOTH log=$logFile err=$errFile"
$proc.Id | Out-File -FilePath e:\yhzx\1027\helloai\.spring-boot-pid -Encoding ASCII
