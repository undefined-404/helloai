# ============================================================
# helloai execution-dispatch startup guard verifier (S6, v1.0)
# 用途：验证 ExecutionDispatchValidator 的启动期 fail-fast 守卫。反复用不同
#       调度配置组合启动 helloai-start，断言非法组合在 @PostConstruct 直接 fail-fast
#       （进程退出码非 0 + 6565 未 Listen + 日志命中期望片段），合法组合 /api/health 200。
#         G1  consumer-mode=POLLER + consumer-enabled=false  -> 期望 fail-fast
#         G2  dispatch-mode=MQ     + producer-enabled=false  -> 期望 fail-fast
#         G3  dispatch-mode=NONE   + consumer-mode=EVENT     -> 期望 /api/health 200
# Ref:  doc/HelloAI_实现差距表.md      (N6，S6 守卫脚本 = 本文件)
#       doc/HelloAI_调度解耦重构分析.md (Validator 启动期 fail-fast / consumer-mode 语义)
#       .agents/skills/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：docker compose up -d 起 helloai-postgres；已 mvn package 出最新 jar。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-execution-dispatch-guard.ps1
# (all strings use single-quote + concat to avoid PS 5.1 parser issues)
# ============================================================

param(
    [int]$StartupTimeoutSec = 120,
    [string]$JarPath = 'e:\yhzx\1027\helloai\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar',
    [string]$JavaExe = ''
)

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

$container = 'helloai-postgres'
$port      = 6565
$healthUrl = 'http://localhost:6565/api/health'
# 脚本已迁至 scripts/powershell/，仓库根 = 脚本目录向上两级（保持 tmp 日志目录与迁移前一致）
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$runTag    = (Get-Date -Format 'yyyyMMdd-HHmmss')
$logDir    = Join-Path $scriptDir ('tmp\dispatch-guard-' + $runTag)
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$global:PassCount = 0
$global:FailCount = 0

function Assert-Pass {
    param([bool]$Condition, [string]$Scenario, [string]$Detail)
    if ($Condition) {
        Write-Output ('[' + $Scenario + '] PASS : ' + $Detail)
        $global:PassCount++
    } else {
        Write-Output ('[' + $Scenario + '] FAIL : ' + $Detail)
        $global:FailCount++
    }
}

function Free-Port {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
}

function Is-PortListening {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return [bool]$c
}

function Probe-JavaVersion {
    # run '<exe> -version' via Start-Process (stable) and return the version line,
    # or $null if the exe is missing / silent (stub) / crashes (broken JDK).
    param([string]$Exe)
    if (-not $Exe -or -not (Test-Path $Exe)) { return $null }
    $pout = Join-Path $logDir 'javaprobe.out'
    $perr = Join-Path $logDir 'javaprobe.err'
    Remove-Item $pout, $perr -ErrorAction SilentlyContinue
    try {
        Start-Process -FilePath $Exe -ArgumentList '-version' `
            -RedirectStandardOutput $pout -RedirectStandardError $perr `
            -NoNewWindow -Wait -ErrorAction Stop | Out-Null
    } catch {
        return $null
    }
    $txt = ''
    if (Test-Path $pout) { $txt += (Get-Content $pout -Raw -ErrorAction SilentlyContinue) }
    if (Test-Path $perr) { $txt += (Get-Content $perr -Raw -ErrorAction SilentlyContinue) }
    Remove-Item $pout, $perr -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($txt)) { return $null }
    if (($txt -match 'EXCEPTION_ACCESS_VIOLATION') -or ($txt -match 'fatal error')) { return $null }
    if ($txt -match 'version') {
        $line = ($txt -split "`n" | Where-Object { $_ -match 'version' } | Select-Object -First 1)
        return $line.Trim()
    }
    return $null
}

function Resolve-JavaExe {
    # pick a java that actually WORKS. bare 'java' may be a broken Oracle javapath
    # stub / WindowsApps alias (silent no-op); JAVA_HOME may point to a corrupt JDK
    # (crashes on -version). Probe candidates in priority order and return the first
    # one that prints a real version string.
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $found = & where.exe java 2>$null
    foreach ($p in $found) {
        if ($p -and ($p -notmatch 'javapath') -and ($p -notmatch 'WindowsApps')) { $candidates += $p }
    }
    $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdkRoot) {
        $jdks = Get-ChildItem -Path $jdkRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
        foreach ($d in $jdks) { $candidates += (Join-Path $d.FullName 'bin\java.exe') }
    }
    $seen = @{}
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $key = $c.ToLower()
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        $ver = Probe-JavaVersion -Exe $c
        if ($ver) { return @{ Exe = $c; Version = $ver } }
    }
    return $null
}

function Start-App {
    param([string[]]$OverrideArgs, [string]$Tag)

    Free-Port
    $log    = Join-Path $logDir ($Tag + '.out.log')
    $errlog = Join-Path $logDir ($Tag + '.err.log')
    Remove-Item $log, $errlog -ErrorAction SilentlyContinue

    $jvmArgs = @('-Dcglib.cache.classes=false', '-jar', $JarPath) + $OverrideArgs
    $proc = Start-Process -FilePath $script:JavaExe -ArgumentList $jvmArgs `
        -RedirectStandardOutput $log -RedirectStandardError $errlog `
        -PassThru -NoNewWindow
    # cache the OS handle immediately; otherwise a Start-Process spawned process
    # drops its ExitCode after it exits (returns empty), breaking the assertion.
    $null = $proc.Handle

    return @{ Proc = $proc; Log = $log; ErrLog = $errlog }
}

function Read-LogText {
    param([hashtable]$App)
    $text = ''
    if (Test-Path $App.Log)    { $text += (Get-Content $App.Log -Raw -ErrorAction SilentlyContinue) }
    if (Test-Path $App.ErrLog) { $text += (Get-Content $App.ErrLog -Raw -ErrorAction SilentlyContinue) }
    if ($null -eq $text) { return '' }
    return $text
}

function Stop-App {
    param([hashtable]$App)
    if ($App.Proc -and -not $App.Proc.HasExited) {
        Stop-Process -Id $App.Proc.Id -Force -ErrorAction SilentlyContinue
    }
    Free-Port
}

function Verify-FailFast {
    param([hashtable]$App, [string]$Scenario, [string[]]$ExpectSubstrings)

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
    while ((Get-Date) -lt $deadline -and -not $App.Proc.HasExited) {
        Start-Sleep -Milliseconds 500
    }

    $exited = $App.Proc.HasExited
    Assert-Pass $exited $Scenario ('process exited within ' + $StartupTimeoutSec + 's (fail-fast triggered)')

    if ($exited) {
        $code = $App.Proc.ExitCode
        Assert-Pass ($null -ne $code -and $code -ne 0) $Scenario ('exit code non-zero (got ' + $code + ')')
    }

    $logText = Read-LogText -App $App
    foreach ($sub in $ExpectSubstrings) {
        $hit = $logText.Contains($sub)
        Assert-Pass $hit $Scenario ('log contains: ' + $sub)
    }

    Assert-Pass (-not (Is-PortListening)) $Scenario 'port 6565 not listening (service not exposed)'
}

function Verify-Healthy {
    param([hashtable]$App, [string]$Scenario)

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ($App.Proc.HasExited) { break }
        try {
            $resp = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $healthy = $true; break }
        } catch {
            # health not ready yet
        }
        Start-Sleep -Seconds 2
    }

    Assert-Pass (-not $App.Proc.HasExited) $Scenario 'process still alive (legal config)'
    Assert-Pass $healthy $Scenario ('/api/health returned 200 within ' + $StartupTimeoutSec + 's')
}

# ============================================================
# pre-flight
# ============================================================
Write-Output ''
Write-Output '============================================================'
Write-Output ' Execution-Dispatch Startup Guard Verifier (S6, v1.0)'
Write-Output (' runTag = ' + $runTag)
Write-Output '============================================================'
Write-Output ''

$dockerCheck = & docker ps --format "{{.Names}}|{{.Status}}" --filter "name=$container" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$container\|Up")) {
    Write-Error ('container [' + $container + '] is NOT up. Run: docker compose up -d')
    exit 1
}
Write-Output ('container ' + $container + ' is Up')

if (-not (Test-Path $JarPath)) {
    Write-Error ('jar not found: ' + $JarPath + ' (rebuild helloai-start first)')
    exit 1
}
Write-Output ('jar = ' + $JarPath)
Write-Output ('logDir = ' + $logDir)

$javaInfo = Resolve-JavaExe -Explicit $JavaExe
if ($null -eq $javaInfo) {
    Write-Error 'no WORKING java found. Candidates were probed via -version; a JDK that is silent (stub) or crashes with EXCEPTION_ACCESS_VIOLATION (broken install, e.g. ms-17.0.18 here) is skipped. Fix: point JAVA_HOME at a healthy JDK 17 (e.g. ms-17.0.19), or run with -JavaExe "C:\path\to\jdk\bin\java.exe".'
    exit 1
}
# NOTE: $JavaExe is a [string] param, so $script:JavaExe is type-constrained to
# [string]. Keep the hashtable in a separate untyped var; only assign the string.
$script:JavaExe = [string]$javaInfo.Exe
$javaVer = [string]$javaInfo.Version
Write-Output ('java = ' + $script:JavaExe)
Write-Output ('java -version: ' + $javaVer)
Write-Output ''

# ============================================================
# G1
# ============================================================
Write-Output '=== [G1] consumer-mode=POLLER + consumer-enabled=false (expect fail-fast) ==='
$g1 = Start-App -Tag 'G1' -OverrideArgs @(
    '--helloai.mq.execution-command.consumer-enabled=false'
)
Verify-FailFast -App $g1 -Scenario 'G1' -ExpectSubstrings @(
    'consumer-mode=POLLER',
    'consumer-enabled=true'
)
Stop-App -App $g1
Write-Output ''

# ============================================================
# G2
# ============================================================
Write-Output '=== [G2] dispatch-mode=MQ + producer-enabled=false (expect fail-fast) ==='
$g2 = Start-App -Tag 'G2' -OverrideArgs @(
    '--helloai.mq.execution-command.producer-enabled=false'
)
Verify-FailFast -App $g2 -Scenario 'G2' -ExpectSubstrings @(
    'dispatch-mode=MQ',
    'producer-enabled=true'
)
Stop-App -App $g2
Write-Output ''

# ============================================================
# G3
# ============================================================
Write-Output '=== [G3] dispatch-mode=NONE + consumer-mode=EVENT (expect success) ==='
$g3 = Start-App -Tag 'G3' -OverrideArgs @(
    '--helloai.execution.dispatch-mode=NONE',
    '--helloai.execution.consumer-mode=EVENT'
)
Verify-Healthy -App $g3 -Scenario 'G3'
Stop-App -App $g3
Write-Output ''

# ============================================================
# summary
# ============================================================
Write-Output '============================================================'
Write-Output ' Execution-Dispatch Guard Verifier - RESULTS'
Write-Output '============================================================'
Write-Output (' PASS: ' + $global:PassCount)
Write-Output (' FAIL: ' + $global:FailCount)
Write-Output (' runTag = ' + $runTag)
Write-Output (' logDir = ' + $logDir)
Write-Output ''
Write-Output 'NOTE: all test instances stopped, port 6565 released.'
Write-Output '      To resume normal operation, start with default config, e.g.:'
Write-Output '      powershell -ExecutionPolicy Bypass -File .\scripts\powershell\start-sb.ps1'
Write-Output ''

if ($global:FailCount -gt 0) { exit 1 } else { exit 0 }