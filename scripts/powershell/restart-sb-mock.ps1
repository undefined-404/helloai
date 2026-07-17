# ============================================================
# helloai 辅助运维脚本：以 mock 执行模式启动 helloai-start
# 用途：poller-e2e 验证需要 mock 执行；临时重启 Spring Boot 时使用。
# 注意：脚本内以绝对路径 Set-Location 到仓库根，位置无关。
# 注意：此脚本需要直接在 PowerShell 终端运行（不走 Node sandbox），
#       否则 JVM 在 Win11+JDK17 下会触发 STATUS_STACK_BUFFER_OVERRUN 崩溃。
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Set-Location e:\yhzx\1027\helloai

# 1. kill any stale Spring Boot process on port 6565
& .\scripts\powershell\kill-old.ps1 | Out-Null

# 2. (re)build the runnable jar
$jarPath = 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar'
$needBuild = $true
if (Test-Path $jarPath) {
    $ageMinutes = ((Get-Date) - (Get-Item $jarPath).LastWriteTime).TotalMinutes
    if ($ageMinutes -lt 5) { $needBuild = $false }
}
if ($needBuild) {
    Write-Host 'Building helloai-start jar...'
    mvn -pl helloai-start -am -DskipTests package | Out-Null
}

# 3. start the jar in background with mock-mode override, redirect log
$logFile = 'e:\yhzx\1027\helloai\spring-boot-run.log'
Remove-Item $logFile -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', $jarPath, '--helloai.execution.mock-mode=true') `
    -RedirectStandardOutput $logFile `
    -PassThru -NoNewWindow
$proc.Id | Out-File -FilePath 'e:\yhzx\1027\helloai\.spring-boot-pid' -Encoding ASCII
Write-Host ('Started PID=' + $proc.Id)

# 4. wait for /api/health to return 200
Write-Host 'Waiting for Spring Boot to be ready...'
$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:6565/api/health' -UseBasicParsing -TimeoutSec 2 -Method Get
        if ([int]$r.StatusCode -eq 200) {
            Write-Host ('READY after ' + ($i + 1) + 's')
            $ready = $true
            break
        }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    Write-Host ('TIMEOUT waiting for Spring Boot; tail of log:')
    Get-Content $logFile -Tail 30 -Encoding utf8
    exit 1
}

# 5. verify mock-mode is truly ON at runtime
$m = Invoke-WebRequest -Uri 'http://localhost:6565/api/health/execution-mode' -UseBasicParsing -TimeoutSec 5 -Method Get
Write-Host ('execution-mode: ' + $m.Content)