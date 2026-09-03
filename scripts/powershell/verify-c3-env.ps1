# ============================================================
# helloai Phase0 C3 Step1 dev-env verifier (verify-c3-env, v1.0)
# 用途：C3 Step 1 灰度前置的 dev 环境就绪检查（预研文档 7 章验收脚本之一）。
#       全 PASS 才允许灰度（gray-percent>0）：
#   S1 服务端口存活：后端 6565（TCP + /api/health）+ dev 中间件
#      PG 15432 / Redis 26379 / RabbitMQ 25672（TCP）
#   S2 对账任务窗口：扫描日志最近 N 分钟（默认 12 >= B3 10min 窗口），
#      断言窗口内 0 条「事件对账不一致 / 事件对账发现不一致」WARN 与
#      EventReconciliationTask 异常 ERROR；日志文件最后写入时间新鲜（后端在写）
#   S3 executor Agent 在线数：admin 登录 -> /api/agents/list，
#      统计 role=EXECUTOR 且 status=ACTIVE 且 onlineStatus=ONLINE 且
#      lastSeenAt 心跳新鲜（默认 5min，对齐 AgentSelector.isHeartbeatFresh
#      的 offlineMinutes 语义）>= 1（灰度前置：注册一律人工，脚本不代注册）
#   S4 汇总：FAIL=0 才 exit 0（ALL PASSED - 可进入灰度）
# Ref:  doc/design/HelloAI_Phase0_C3_双轨切换预研.md (7 章验收脚本表)
#       doc/log/2026-09.md (LOG-20260902-010 C3 Step 0 落地）
#       .agents/skills/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：后端已启动（IDEA/jar 均可，连 dev 库）；日志目录存有 helloai.log。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-c3-env.ps1
# 参数：-DbHost  中间件主机（默认 39.106.204.43 dev 服务器；本地 docker 用 localhost）
#       -LogFile 手动指定 helloai.log 路径（自动探测失败时）
# (all strings use single-quote + concat to avoid PS 5.1 parser issues;
#  this file is pure ASCII: CJK log patterns are assembled from code points)
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$DbHost = '39.106.204.43',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [int]$ReconcileWindowMinutes = 12,
    [int]$FreshMinutes = 5,
    [int]$LogTailLines = 10000,
    [int]$ExpectedGrayPercent = 5,
    [string]$LogFile = ''
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

# CJK log keywords assembled via code points to keep this file pure ASCII:
#   kTag      = 事件对账
#   kMismatch = 不一致
#   kExec     = 执行
# NOTE: -join [char[]](...) is used instead of [string]::Concat(char,...) which
#       throws ArgumentNullException under PS 5.1 params-binding.
$kTag      = -join ([char[]](0x4E8B, 0x4EF6, 0x5BF9, 0x8D26))
$kMismatch = -join ([char[]](0x4E0D, 0x4E00, 0x81F4))
$kExec     = -join ([char[]](0x6267, 0x884C))

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

function Test-TcpPort {
    param([string]$HostName, [int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($HostName, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(3000)
        if ($ok) {
            try { $client.EndConnect($iar) } catch { return $false }
        }
        return $ok
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Invoke-Json {
    param([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers)
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    try {
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers `
            -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 120
    } catch {
        $resp = $_.Exception.Response
        $statusCode = $null
        if ($resp -ne $null) {
            try { $statusCode = [int]$resp.StatusCode } catch { }
        }
        Write-Output ('HTTP_FAIL: ' + $Method + ' ' + $Url + ' status=' + $statusCode + ' msg=' + $_.Exception.Message)
        return $null
    }
}

function Get-ListCount {
    param($List)
    # PS 5.1 quirk: a single PSCustomObject (e.g. one Where-Object result) has .Count = $null,
    # while @($null).Count = 1 is the opposite trap; this helper returns 0/1/n correctly.
    if ($List -eq $null) { return 0 }
    return @($List).Count
}

function Get-UtcDate {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    try {
        $dto = [datetimeoffset]::Parse($Text, [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::RoundtripKind)
        return $dto.UtcDateTime
    } catch {
        return $null
    }
}

# ------------------------------------------------------------
# S1: backend + middleware ports
# ------------------------------------------------------------
Write-Output '==== S1: service ports ===='

$httpOk = $false
try {
    $r = Invoke-WebRequest -Uri ($BaseUrl + '/api/health') -UseBasicParsing -TimeoutSec 5
    $httpOk = ($r.StatusCode -eq 200)
} catch { }
Assert-Pass (Test-TcpPort '127.0.0.1' 6565) 'S1' ('backend :6565 TCP listen (base=' + $BaseUrl + ')')
Assert-Pass $httpOk 'S1' ('backend /api/health HTTP 200')

Assert-Pass (Test-TcpPort $DbHost 15432) 'S1' ('middleware ' + $DbHost + ':15432 PostgreSQL TCP')
Assert-Pass (Test-TcpPort $DbHost 26379) 'S1' ('middleware ' + $DbHost + ':26379 Redis TCP')
Assert-Pass (Test-TcpPort $DbHost 25672) 'S1' ('middleware ' + $DbHost + ':25672 RabbitMQ TCP')

# ------------------------------------------------------------
# S2: reconciliation task window check (log scan)
# ------------------------------------------------------------
Write-Output '==== S2: reconciliation window (no mismatch in last 12min) ===='

if ([string]::IsNullOrWhiteSpace($LogFile)) {
    $scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
    $candidates = @(
        (Join-Path $scriptDir 'logs\helloai.log'),
        (Join-Path $scriptDir 'helloai-start\logs\helloai.log')
    )
    $LogFile = ($candidates | Where-Object { Test-Path $_ } | Select-Object -First 1)
}

$logFound = (-not [string]::IsNullOrWhiteSpace($LogFile)) -and (Test-Path $LogFile)
Assert-Pass $logFound 'S2' ('log file found: ' + $(if ($logFound) { $LogFile } else { 'none (pass -LogFile)' }))

if ($logFound) {
    $lastWrite = (Get-Item $LogFile).LastWriteTime
    $logFresh = ((Get-Date) - $lastWrite).TotalMinutes -le 3
    Assert-Pass $logFresh 'S2' ('log last write ' + $lastWrite.ToString('HH:mm:ss') + ' (backend alive)')

    $cutoff = (Get-Date).AddMinutes(-$ReconcileWindowMinutes)
    $tail = Get-Content $LogFile -Tail $LogTailLines -ErrorAction SilentlyContinue
    $windowLines = @($tail) | Where-Object {
        $m = [regex]::Match($_, '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})')
        if ($m.Success) {
            try {
                $t = [datetime]::ParseExact($m.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null)
                return $t -ge $cutoff
            } catch { return $false }
        }
        return $false
    }

    $mismatchLines = @($windowLines) | Where-Object {
        $_.Contains($kTag) -and $_.Contains($kMismatch)
    }
    $taskErrorLines = @($windowLines) | Where-Object {
        $_.Contains('EventReconciliationTask') -and $_.Contains($kExec)
    }

    Assert-Pass ((Get-ListCount $mismatchLines) -eq 0) 'S2' ('reconcile mismatch WARN in window=' + (Get-ListCount $mismatchLines))
    Assert-Pass ((Get-ListCount $taskErrorLines) -eq 0) 'S2' ('EventReconciliationTask ERROR in window=' + (Get-ListCount $taskErrorLines))
    Write-Output ('S2 info: tail=' + (Get-ListCount $tail) + ' lines, window=' + (Get-ListCount $windowLines) + ' lines, last log line=' + $tail[-1])
    if ((Get-ListCount $mismatchLines) -gt 0) {
        $mismatchLines | Select-Object -First 5 | ForEach-Object { Write-Output ('S2 WARN sample: ' + $_) }
    }
} else {
    Assert-Pass $false 'S2' 'skip window scan (log missing)'
}

# ------------------------------------------------------------
# S3: executor agent online count
# ------------------------------------------------------------
Write-Output '==== S3: executor agent online ===='

$loginResp = Invoke-Json 'Post' ($BaseUrl + '/api/auth/login') @{
    type       = 'admin'
    username   = $AdminUsername
    credential = $AdminPassword
} @{}
$loginOk = ($loginResp -ne $null -and $loginResp.code -eq 200 -and $loginResp.data.token)
Assert-Pass $loginOk 'S3' ('admin login token=' + $(if ($loginOk) { 'ok' } else { 'FAILED' }))

if ($loginOk) {
    $adminHeaders = @{ 'X-Admin-Token' = $loginResp.data.token }
    $listResp = Invoke-Json 'Get' ($BaseUrl + '/api/agents/list') $null $adminHeaders
    $listOk = ($listResp -ne $null -and $listResp.code -eq 200)
    Assert-Pass $listOk 'S3' ('agent list code=' + $(if ($listOk) { $listResp.code } else { 'N/A' }))

    if ($listOk) {
        $agents = @($listResp.data)
        $executors = @($agents) | Where-Object { $_.role -ieq 'EXECUTOR' }
        $utcNow = [datetime]::UtcNow
        $cutoffUtc = $utcNow.AddMinutes(-$FreshMinutes)

        foreach ($a in ($executors | Sort-Object -Property id)) {
            $seenUtc = Get-UtcDate ([string]$a.lastSeenAt)
            $fresh = ($seenUtc -ne $null) -and ($seenUtc -ge $cutoffUtc)
            $inner = -not [string]::IsNullOrWhiteSpace([string]$a.modelType)
            $seenText = if ($seenUtc -ne $null) { $seenUtc.ToUniversalTime().ToString('HH:mm:ss') + 'Z' } else { 'null' }
            Write-Output ('S3 agent: id=' + $a.id + ' name=' + $a.name + ' status=' + $a.status +
                ' online=' + $a.onlineStatus + ' seen=' + $seenText + ' fresh=' + $fresh +
                ' innerLlm=' + $inner + ' (modelType=' + $(if ($inner) { $a.modelType } else { '-' }) + ')')
        }

        # Aligned with AgentSelector candidate filter:
        #   internal LLM agent (modelType not empty = accessType API_KEY_LLM) is exempt from
        #   online-status and heartbeat freshness checks (requiresRuntimeLiveness=false), and
        #   CLI_CLIENT is selectable when onlineStatus is NOT OFFLINE / NOT SLEEPING (IDLE is
        #   selectable) plus heartbeat within offlineMinutes.
        $onlineExecutors = @($executors) | Where-Object {
            if ($_.status -ine 'ACTIVE') { return $false }
            if (-not [string]::IsNullOrWhiteSpace([string]$_.modelType)) { return $true }
            $seenUtc = Get-UtcDate ([string]$_.lastSeenAt)
            return ($_.onlineStatus -ine 'OFFLINE') -and ($_.onlineStatus -ine 'SLEEPING') -and `
                ($seenUtc -ne $null) -and ($seenUtc -ge $cutoffUtc)
        }
        $onlineDetail = 'selectable executor count=' + (Get-ListCount $onlineExecutors) + ' (ACTIVE; LLM-exempt or not-OFFLINE/SLEEPING+heartbeat-fresh)'
        Assert-Pass ((Get-ListCount $onlineExecutors) -ge 1) 'S3' $onlineDetail
        if ((Get-ListCount $onlineExecutors) -eq 0) {
            Write-Output 'S3 NOTE: grayscale gate requires >= 1 selectable executor; agent registration/checkin is manual-only.'
        }
    }
}

# ------------------------------------------------------------
# S4: grayscale config check (application.yml gray-percent)
# ------------------------------------------------------------
Write-Output '==== S4: grayscale config (gray-percent) ===='

$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$cfgFile = Join-Path $scriptDir 'helloai-start\src\main\resources\application.yml'
$grayNow = $null
if (Test-Path $cfgFile) {
    $m2 = [regex]::Match((Get-Content -Raw $cfgFile), '(?m)^[ \t]*gray-percent:[ \t]*(\d+)')
    if ($m2.Success) { $grayNow = [int]$m2.Groups[1].Value }
}
$grayText = if ($grayNow -ne $null) { [string]$grayNow } else { 'NOT-FOUND' }
Assert-Pass ($grayNow -eq $ExpectedGrayPercent) 'S4' ('gray-percent=' + $grayText + ' (expect ' + $ExpectedGrayPercent + ')')

# ------------------------------------------------------------
# S5: summary
# ------------------------------------------------------------
Write-Output ('==== SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' ====')
if ($global:FailCount -gt 0) {
    Write-Output 'RESULT: FAILED - fix above items before C3 Step 1 grayscale'
    exit 1
} else {
    Write-Output 'RESULT: ALL PASSED - dev env ready, grayscale gate (agent online >= 1) satisfied'
    exit 0
}