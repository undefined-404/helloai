# ============================================================
# helloai 本地 B 级集成测试（Testcontainers *IT）一键运行脚本
# 用途：封装本机实跑 4 个 IT 的三前置 ——
#       1) 会话级 PATH 清洗（剔除系统注册表坏项 'C:\Program '，仅本会话生效，不代改注册表）
#       2) $env:DOCKER_HOST 指向 Docker Desktop npipe 端点（新版 Docker Desktop 强制 Host 头，
#          必须配 testcontainers 2.0.5 / docker-java 4.x 才能连通）
#       3) JAVA_HOME 固定为 ms-17.0.20.1（17.0.19 本机必然 JVM 崩溃，见 ci/lib-jdk.sh 黑名单）
#       然后执行 mvn -s .tmp\settings-aliyun.xml -pl helloai-start -am test
#       仅跑 *IT（surefire 默认 exclude 集成测试），完整日志落 .tmp\it-run-local.log。
# Ref:  doc/log/2026-09.md「2026-09-29 本机 Docker 实跑 4 个 IT：8/8 全绿」
# 用法（项目根）：powershell -File .\scripts\run-it-local.ps1
# 前置：Docker Desktop 已启动且引擎就绪；Maven 在系统 PATH（如 E:\apache-maven-3.9.15\bin）
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot '.tmp'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$logFile = Join-Path $logDir 'it-run-local.log'

# ---- 0) 检测系统注册表 PATH 坏项与残片（只提示，不代改；根治需用户自行操作） ----
# 注意：不能用 [Environment]::GetEnvironmentVariable('Path','Machine')——.NET 对进程环境块中已存在的同名
# 变量（Path 必有）返回进程块旧快照：用户修复注册表后仍报旧坏项。必须 Registry API 直读才是注册表真值
# （REG_EXPAND_SZ 默认按进程块展开 %VAR%，展开后条目均为绝对路径，检测/清洗正则不受影响）。
$envKey = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment')
$userKey = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
$machinePath = [string]$envKey.GetValue('Path', '')
$userPath = [string]$userKey.GetValue('Path', '')
$badEntries = @($machinePath -split ';' | Where-Object { $_ -match '\s+$' })
$fragmentEntries = @($machinePath -split ';' | Where-Object {
        $_ -and $_ -notmatch '^[A-Za-z]:[\\/]' -and $_ -notmatch '^\\\\' -and $_ -notmatch '^%' -and $_ -ne '.'
    })
if ($badEntries.Count -gt 0 -or $fragmentEntries.Count -gt 0) {
    Write-Host '[WARN] 系统注册表 PATH 存在坏项（本脚本不做系统级修改，仅会话级绕过）：' -ForegroundColor Yellow
    if ($badEntries.Count -gt 0) {
        Write-Host '  - 以空格结尾的坏条目（任何 Paths.get 遍历都会崩溃）：' -ForegroundColor Yellow
        $badEntries | ForEach-Object { Write-Host "      '$($_.TrimEnd()) '（含结尾空格）" -ForegroundColor Yellow }
    }
    if ($fragmentEntries.Count -gt 0) {
        Write-Host '  - 相对路径残片（非盘符开头，永远无法解析）：' -ForegroundColor Yellow
        $fragmentEntries | ForEach-Object { Write-Host "      '$_'" -ForegroundColor Yellow }
    }
    Write-Host '  本机这两类条目是同一个路径被分号劈开的结果：' -ForegroundColor Yellow
    Write-Host '    C:\Program Files\Docker\Docker\resources\bin（Docker CLI 完整路径）' -ForegroundColor Yellow
    Write-Host '  清理提示（需管理员）：' -ForegroundColor Yellow
    Write-Host '    A. 注册表编辑器：定位 HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Session Manager\Environment' -ForegroundColor Yellow
    Write-Host '       的 Path 值，删除坏项与残片，再确认存在完整路径条目（无则新增）。' -ForegroundColor Yellow
    Write-Host '    B. 管理员 PowerShell（建议先备份 Path 原值）：' -ForegroundColor Yellow
    Write-Host "       `$p=[Environment]::GetEnvironmentVariable('Path','Machine'); " -ForegroundColor Yellow
    Write-Host "       [Environment]::SetEnvironmentVariable('Path',(((`$p -split ';' | Where-Object {`$_ -ne 'C:\Program ' -and `$_ -ne 'iles\Docker\Docker\resources\bin'}) -join ';') + ';C:\Program Files\Docker\Docker\resources\bin'),'Machine')" -ForegroundColor Yellow
    Write-Host '  本次运行已用清洗后的会话 PATH 绕过，不影响执行。' -ForegroundColor Yellow
}

# ---- 1) 会话级 PATH 清洗（剔除坏项/残片/空项，保留当前会话其余注入项） ----
$env:PATH = ($env:PATH -split ';' | Where-Object {
        $_ -and $_ -notmatch '\s+$' -and $_ -ne '.' -and
        ($_ -match '^[A-Za-z]:[\\/]' -or $_ -match '^\\\\' -or $_ -match '^%')
    }) -join ';'
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    # 当前会话 PATH 不完整（如从干净 cmd 启动）时，从注册表重建补齐
    $env:PATH = (((($machinePath + ';' + $userPath) -split ';' | Where-Object {
                    $_ -and $_ -notmatch '\s+$' -and $_ -ne '.' -and
                    ($_ -match '^[A-Za-z]:[\\/]' -or $_ -match '^\\\\' -or $_ -match '^%')
                }) -join ';') + ';' + $env:PATH)
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host '[ERROR] 会话 PATH 中找不到 mvn，请确认 Maven 已安装且 bin 目录在系统 PATH（如 E:\apache-maven-3.9.15\bin）' -ForegroundColor Red
    exit 2
}

# ---- 2) Docker CLI 探测 + DOCKER_HOST 指向 npipe 端点并探活 ----
$dockerCli = Get-Command docker -ErrorAction SilentlyContinue
if (-not $dockerCli) {
    $dockerBin = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin'
    if (Test-Path (Join-Path $dockerBin 'docker.exe')) {
        $env:PATH = $env:PATH + ';' + $dockerBin
        $dockerCli = Get-Command docker -ErrorAction SilentlyContinue
    }
}
if (-not $dockerCli) {
    Write-Host '[ERROR] 会话 PATH 中找不到 docker.exe（注册表残片无法解析）；' -ForegroundColor Red
    Write-Host '  请按顶部提示修复注册表 PATH，或手动把 C:\Program Files\Docker\Docker\resources\bin 加入 PATH 后重开终端' -ForegroundColor Red
    exit 3
}
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
$dockerVersion = docker version --format "{{.Server.Version}}" 2>$null
if ($LASTEXITCODE -ne 0 -or -not $dockerVersion) {
    Write-Host "[ERROR] 无法连接 Docker（$env:DOCKER_HOST），请先启动 Docker Desktop 并等待引擎就绪" -ForegroundColor Red
    exit 4
}
Write-Host "[OK] Docker 引擎可用：ServerVersion=$dockerVersion（CLI: $($dockerCli.Source)）"

# ---- 3) JAVA_HOME 固定 ms-17.0.20.1 ----
$jdkRoot = Join-Path $env:USERPROFILE '.jdks'
$jdk = Join-Path $jdkRoot 'ms-17.0.20.1'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host "[ERROR] 未找到 $jdk（ms-17.0.20.1 是本机唯一稳定可用的 JDK17，见 ci/lib-jdk.sh 黑名单；17.0.19 必然崩溃）" -ForegroundColor Red
    Get-ChildItem $jdkRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  候选 JDK: $($_.Name)" }
    exit 5
}
$env:JAVA_HOME = $jdk
Write-Host "[OK] JAVA_HOME=$env:JAVA_HOME"

# ---- 4) 执行 B 级 IT（等价日志命令，仅跑 *IT） ----
Push-Location $repoRoot
try {
    Write-Host "[RUN] mvn -s .tmp\settings-aliyun.xml -pl helloai-start -am test -DskipTests=false -Dtest=*IT（实时进度见 $logFile）"
    # PS5.1 两个坑：① Stop 模式下原生 stderr 会被 2>&1 包装成 NativeCommandError 终止 → 临时降级 Continue；
    #                ② mvn(java) 输出为系统 GBK 字节流，须临时按 GBK(936) 解码，否则中文测试名变 U+FFFD 乱码
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $previousEnc = [Console]::OutputEncoding
    try { [Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding(936) } catch { }
    & mvn '-s' '.tmp\settings-aliyun.xml' '-pl' 'helloai-start' '-am' 'test' '-DskipTests=false' "-Dtest=*IT" '-Dsurefire.failIfNoSpecifiedTests=false' 2>&1 |
        Tee-Object -FilePath $logFile |   # PS5.1 无 -Encoding 参数（PS7+ 才有），日志为 UTF-16，Select-String 可自动识别
        Out-Null
    [Console]::OutputEncoding = $previousEnc
    $ErrorActionPreference = $previousEap
} finally {
    Pop-Location
}

# ---- 5) 汇总与退出码（从日志提取，此时控制台已恢复 UTF-8，中文正确显示） ----
$buildFailed = [bool](Select-String -Path $logFile -Pattern 'BUILD FAILURE' -Quiet)
Select-String -Path $logFile -Pattern 'in B[1-4]:' | ForEach-Object { Write-Host ('  ' + $_.Line.Trim()) }
$summary = Select-String -Path $logFile -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+\s*$' |
    Select-Object -Last 1
if ($summary) { Write-Host ("[SUMMARY] " + $summary.Line.Trim()) }
if ($LASTEXITCODE -ne 0 -or $buildFailed) {
    Write-Host "[FAIL] B 级 IT 未全绿，完整日志见 $logFile" -ForegroundColor Red
    exit 1
}
Write-Host "[PASS] B 级 IT 全绿（8/8 期望），完整日志见 $logFile"
exit 0