# ============================================================
# helloai 辅助运维脚本：启动 helloai-start 后端（默认配置）
# 用途：必要时先 mvn package 出 jar（jar 新于 5 分钟则跳过重建），再以 java -jar 后台
#       启动 helloai-start（日志 spring-boot-run.log，PID 写 .spring-boot-pid）。
# Ref:  辅助脚本，无直接 doc 对应；被 run-redispatch-diagnose.ps1 调用。
# 用法（项目根）：powershell -File .\scripts\powershell\start-sb.ps1
# 注意：脚本内以绝对路径 Set-Location 到仓库根，位置无关，可从任意目录调用。
# ============================================================
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
