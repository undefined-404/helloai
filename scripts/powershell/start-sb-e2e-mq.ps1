# ============================================================
# helloai 辅助运维脚本：启动 helloai-start 后端（Phase 2G E2E MQ 场景）
# 用途：以 dispatch-mode=BOTH + producer/consumer 双开关=true 启动后端，供 MQ 链路
#       E2E（Outbox / Confirm、双路消费）使用；日志 spring-boot-e2e-mq.log(.err)。
# Ref:  doc/HelloAI_实现差距表.md (N1 Outbox / N6 执行命令消费)；配合 verify-outbox-relay-confirm-e2e.ps1。
# 用法（项目根）：powershell -File .\scripts\powershell\start-sb-e2e-mq.ps1
# 注意：脚本内以绝对路径 Set-Location 到仓库根，位置无关；当前写死用 .jdks\ms-17.0.18，
#       若该 JDK 损坏（EXCEPTION_ACCESS_VIOLATION）需改为健康 JDK（如 ms-17.0.19）。
# ============================================================
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
