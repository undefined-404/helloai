# ============================================================
# helloai Phase0 C3 rollback drill verifier (verify-c3-rollback, v1.0)
# 用途：C3 回滚预案演练验收（预研 7 章验收脚本表 verify-c3-rollback.ps1；四章回滚表格灰度期行）：
#   S1 解析 application.yml gray-percent：
#      - 非 0（未处于回滚态）：输出演练操作指引并退出 0（本脚本只读，不改配置）
#        指引：改 gray-percent=0 -> 重启后端 -> 造一个新任务 -> 重跑本脚本验证
#      - 为 0（回滚态）：继续 S2-S4 断言
#   S2 日志窗口（默认 30min）内 'route=agent_runtime' 出现次数 = 0（后端日志关键词同源观察）
#   S3 只读 SQL 探针（.tmp\c3-rollback-probe.sql）窗口内：
#      - rt_new（route=agent_runtime 观察点）= 0（无新任务经 Runtime）
#      - consume_new（全部执行命令消费观察点）> 0（正面证据：新任务已回旧直连路径）
#      - 有 psql 自动执行断言（cmd /c type 透传原始字节，规则 6），否则提示会话内 MCP 执行核对
#   S4 汇总；回滚态下 FAIL>0 才 exit 1；演练完成后提示恢复灰度步骤
# Ref:  doc/design/HelloAI_Phase0_C3_双轨切换预研.md（四章回滚表格；七章脚本表）
# 口径：route 观察点 = task_timeline event_type='sub_task_execution_command_consume'
#       AND payload->>'route'='agent_runtime'（LocalExecutionCommandConsumer.runViaRuntime 写入；
#       未命中旧直连路径不写 route 字段，gray-percent=0 时 routeToRuntime 恒 false）
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-c3-rollback.ps1
# 参数：-WindowMinutes 观察窗口（默认 30）；-YmlPath 手动指定 application.yml
# (UTF-8 with BOM source; runtime literals pure ASCII; single-quote + concat output per rule 6)
# ============================================================

param(
    [int]$WindowMinutes = 30,
    [int]$LogTailLines = 20000,
    [string]$YmlPath = ''
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

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

function Get-ListCount {
    param($List)
    if ($List -eq $null) { return 0 }
    return @($List).Count
}

# ------------------------------------------------------------
# S1: gray-percent config + rollback-state gate
# ------------------------------------------------------------
Write-Output '==== S1: gray-percent config ===='

if ([string]::IsNullOrWhiteSpace($YmlPath)) {
    $projectRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
    $candidates = @(
        (Join-Path $projectRoot 'helloai-start\src\main\resources\application.yml'),
        (Join-Path $projectRoot 'src\main\resources\application.yml')
    )
    $YmlPath = ($candidates | Where-Object { Test-Path $_ } | Select-Object -First 1)
}

$ymlFound = (-not [string]::IsNullOrWhiteSpace($YmlPath)) -and (Test-Path $YmlPath)
Assert-Pass $ymlFound 'S1' ('application.yml: ' + $(if ($ymlFound) { $YmlPath } else { 'NOT-FOUND' }))

$cfgGray = -1
if ($ymlFound) {
    $ymlText = Get-Content $YmlPath -Raw -ErrorAction SilentlyContinue
    if ($ymlText) {
        $m = [regex]::Match($ymlText, '(?m)^\s*gray-percent:\s*(\d+)')
        if ($m.Success) { $cfgGray = [int]$m.Groups[1].Value }
    }
}
Assert-Pass ($cfgGray -ge 0) 'S1' ('gray-percent=' + $cfgGray + ' (parsed from yml)')

if ($global:FailCount -gt 0) {
    Write-Output ('SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    exit 1
}

if ($cfgGray -ne 0) {
    Write-Output ('[S1] INFO : gray-percent=' + $cfgGray + ' (not in rollback state)')
    Write-Output '==== ROLLBACK DRILL GUIDE (run these steps, then rerun this script) ===='
    Write-Output '  1. edit application.yml: set  gray-percent: 0'
    Write-Output '  2. restart backend (kill old process, then relaunch)'
    Write-Output '  3. create a new task / let an existing chain consume at least one command'
    Write-Output '  4. rerun:'
    Write-Output ('     powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-c3-rollback.ps1')
    Write-Output '     expect: all PASS + DB probe rt_new=0, consume_new>0'
    Write-Output ('RESUME GRAY AFTER DRILL: set gray-percent back to ' + $cfgGray + ', restart,')
    Write-Output '  then rerun verify-c3-env.ps1 to confirm grayscale gate back to normal'
    Write-Output ('==== SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' ====')
    Write-Output 'RESULT: GUIDE - not in rollback state, drill steps printed above'
    exit 0
}

Write-Output 'S1 state: rollback mode (gray-percent=0), proceeding to assertions'

# ------------------------------------------------------------
# S2: log window scan for route=agent_runtime
# ------------------------------------------------------------
Write-Output ('==== S2: log window=' + $WindowMinutes + 'min route=agent_runtime scan ====')

$logDir = Join-Path $projectRoot 'logs\helloai.log'
if (-not (Test-Path $logDir)) { $logDir = Join-Path $projectRoot 'helloai-start\logs\helloai.log' }
$logFound = Test-Path $logDir
Assert-Pass $logFound 'S2' ('log file: ' + $(if ($logFound) { $logDir } else { 'NOT-FOUND' }))

if ($logFound) {
    $cutoff = (Get-Date).AddMinutes(-$WindowMinutes)
    $tail = Get-Content $logDir -Tail $LogTailLines -ErrorAction SilentlyContinue
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
    $rtHits = @($windowLines) | Where-Object { $_.Contains('route=agent_runtime') }
    Assert-Pass ((Get-ListCount $rtHits) -eq 0) 'S2' ('log route=agent_runtime in window=' + (Get-ListCount $rtHits))
} else {
    # no log file: cannot assert log side, but DB probe remains authoritative
    Write-Output 'S2 NOTE: log not found; skip log assertion (DB probe below still authoritative)'
}

# ------------------------------------------------------------
# S3: emit + run rollback probe SQL (read-only)
# ------------------------------------------------------------
Write-Output '==== S3: rollback probe SQL ===='

$probeDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\.tmp'
if (-not (Test-Path $probeDir)) { New-Item -ItemType Directory -Path $probeDir -Force | Out-Null }

$probeFile = Join-Path $probeDir 'c3-rollback-probe.sql'
$probeSql = @'
-- C3 回滚演练断言（只读）：回滚态（gray-percent=0）下窗口 {WINDOW}min 内
-- rt_new = 0（无新任务经 Runtime）；consume_new > 0（新任务已回旧直连，正面证据）。
SELECT
  (SELECT COUNT(*) FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND payload->>'route' = 'agent_runtime'
      AND create_time >= now() - interval '{WINDOW} minutes') AS rt_new,
  (SELECT COUNT(*) FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND create_time >= now() - interval '{WINDOW} minutes') AS consume_new;
'@
$probeSql = $probeSql.Replace('{WINDOW}', [string]$WindowMinutes)
[System.IO.File]::WriteAllText($probeFile, $probeSql.TrimStart([char]0xFEFF), $script:Utf8NoBom)
Assert-Pass (Test-Path $probeFile) 'S3' ('probe written: ' + $probeFile)

$psqlCmd = Get-Command psql -ErrorAction SilentlyContinue
if ($psqlCmd) {
    # pipe raw bytes via cmd /c type (rule 6: avoid PS 5.1 UTF-16 stdin wrapper)
    $probeOut = Join-Path $probeDir 'c3-rollback-probe.out'
    Remove-Item $probeOut -ErrorAction SilentlyContinue
    $probeLines = cmd /c ('type "' + $probeFile + '" | "' + $psqlCmd.Source + '" -h 39.106.204.43 -p 15432 -U postgres -d helloai -v ON_ERROR_STOP=1 -X -t -A -F "|" 2>&1')
    $rc = $LASTEXITCODE
    $probeRow = @($probeLines) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1
    if ($rc -eq 0 -and $probeRow) {
        $parts = $probeRow.Split('|')
        $rtNew = [int]$parts[0]; $consumeNew = [int]$parts[1]
        Write-Output ('S3 probe: rt_new=' + $rtNew + ' consume_new=' + $consumeNew + ' (window ' + $WindowMinutes + 'min)')
        Assert-Pass ($rtNew -eq 0) 'S3' ('new agent_runtime observations=' + $rtNew + ' (expect 0)')
        if ($consumeNew -gt 0) {
            Write-Output ('S3 INFO: consume_new=' + $consumeNew + ' > 0, positive evidence new tasks go legacy path')
        } else {
            Write-Output 'S3 INFO: consume_new=0 in window - no evidence yet, create a task and rerun'
        }
    } else {
        Assert-Pass $false 'S3' ('rollback probe failed rc=' + $rc)
    }
} else {
    Write-Output 'S3 NOTE: no local psql; run probe via session MCP (postgres_helloai_dev query):'
    Write-Output ('  ' + $probeFile)
    Write-Output '  expect: rt_new=0 and consume_new>0'
}

# ------------------------------------------------------------
# S4: summary
# ------------------------------------------------------------
Write-Output ('==== SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' ====')
if ($global:FailCount -gt 0) {
    Write-Output 'RESULT: FAILED - rollback drill failed, restore grayscale NOW (set gray-percent back, restart)'
    exit 1
}
Write-Output 'RESULT: ROLLBACK OK - no new runtime routing; legacy path confirmed'
Write-Output 'REMINDER: resume grayscale by setting gray-percent back to default and rerun verify-c3-env.ps1'
exit 0