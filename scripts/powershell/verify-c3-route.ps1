# ============================================================
# helloai Phase0 C3 gray route verifier (verify-c3-route, v1.0)
# 用途：C3 灰度路由归属断言（预研 7 章验收脚本表 verify-c3-route.ps1）：
#   S1 解析 application.yml gray-percent，断言与期望值一致
#   S2 生成只读 SQL 探针（.tmp\c3-route-violation.sql / c3-route-stats.sql）：
#      - 反侧违例：route=agent_runtime 观察点的 taskId % 100 必须 < gray（路由确定性，期望 0 行）
#        全量断言（档位上调后历史样本 mod100 < 旧档位 < 新档位，必然合规，不会误报）
#      - 正侧统计：Runtime 命中 task 占比 vs gray% 偏差 <= +-10%（验收标准 1，样本 >= 10 才判定）
#        支持 -WindowMinutes 限定当前档位窗口（档位切换后历史样本按旧档位命中，全量占比会被稀释）
#   S3 有 psql 自动执行断言（cmd /c type 透传原始字节，规则 6），否则提示会话内 MCP 执行核对
#   S4 汇总；配置/探针侧 FAIL>0 才 exit 1；DB 侧无 psql 时由 MCP 核对后回填判定
# Ref:  doc/design/HelloAI_Phase0_C3_双轨切换预研.md（六章验收标准 1/3；七章脚本表）
# 口径：route 观察点 = task_timeline event_type='sub_task_execution_command_consume'
#       AND payload->>'route'='agent_runtime'（LocalExecutionCommandConsumer.runViaRuntime 写入；
#       未命中旧直连路径不写 route 字段，故反侧断言可行）
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-c3-route.ps1
# 参数：-GrayPercent 期望灰度（默认 5）；-WindowMinutes 统计窗口（默认 0=全量，>0 限定最近 N 分钟）；-YmlPath 手动指定 application.yml
# 注：ver 1.1 新增 -WindowMinutes（LOG-20260903-009 25% 档放量时发现全量占比被 5% 历史样本稀释）
# (UTF-8 with BOM source; runtime literals pure ASCII; single-quote + concat output per rule 6)
# ============================================================

param(
    [int]$GrayPercent = 5,
    [int]$WindowMinutes = 0,
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
# S1: gray-percent config check
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
Assert-Pass ($cfgGray -eq $GrayPercent) 'S1' ('gray-percent=' + $cfgGray + ' (expect ' + $GrayPercent + ')')

if ($global:FailCount -gt 0) {
    Write-Output ('SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount)
    exit 1
}

# ------------------------------------------------------------
# S2: emit route probe SQL (read-only)
# ------------------------------------------------------------
Write-Output '==== S2: route probe SQL ===='

$probeDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\.tmp'
if (-not (Test-Path $probeDir)) { New-Item -ItemType Directory -Path $probeDir -Force | Out-Null }

$violFile = Join-Path $probeDir 'c3-route-violation.sql'
$vioSql = @'
-- C3 灰度路由反侧违例（只读）：期望 0 行。
-- route=agent_runtime 观察点的 taskId % 100 必须 < gray-percent（路由确定性：未命中必走旧直连，
-- 旧直连路径不写 route 字段）。违例行 = 灰度规则被破坏（含运行期变更 gray-percent 窗口），需人工核对。
SELECT DISTINCT task_id, sub_task_id, (task_id % 100)::int AS mod100
FROM task_timeline
WHERE event_type = 'sub_task_execution_command_consume'
  AND payload->>'route' = 'agent_runtime'
  AND (task_id % 100) >= {GRAY};
'@
$vioSql = $vioSql.Replace('{GRAY}', [string]$GrayPercent)
[System.IO.File]::WriteAllText($violFile, $vioSql.TrimStart([char]0xFEFF), $script:Utf8NoBom)

$statFile = Join-Path $probeDir 'c3-route-stats.sql'
$winCond = ''
if ($WindowMinutes -gt 0) {
    $winCond = "  AND create_time >= now() - interval '" + $WindowMinutes + " minutes'`n"
}
$staSql = @'
-- C3 灰度路由统计（只读，验收标准 1）：Runtime 命中 task 占比 vs gray-percent 偏差 <= +-10%。
-- {WINNOTE}样本过少（< 10 task）时不判定，先积累。
SELECT
  (SELECT COUNT(DISTINCT task_id) FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND payload->>'route' = 'agent_runtime'
{WINCOND}  ) AS rt_tasks,
  (SELECT COUNT(DISTINCT task_id) FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
{WINCOND}  ) AS all_tasks,
  {GRAY} AS gray;
'@
$winNote = ''
if ($WindowMinutes -gt 0) { $winNote = '窗口 ' + $WindowMinutes + 'min：' }
$staSql = $staSql.Replace('{WINCOND}', $winCond).Replace('{WINNOTE}', $winNote).Replace('{GRAY}', [string]$GrayPercent)
[System.IO.File]::WriteAllText($statFile, $staSql.TrimStart([char]0xFEFF), $script:Utf8NoBom)

Assert-Pass ((Test-Path $violFile) -and (Test-Path $statFile)) 'S2' ('probes written: ' + (Split-Path -Leaf $violFile) + ' / ' + (Split-Path -Leaf $statFile))

# ------------------------------------------------------------
# S3: auto-execute probes when local psql exists
# ------------------------------------------------------------
Write-Output '==== S3: route probe execution ===='

$psqlCmd = Get-Command psql -ErrorAction SilentlyContinue
if ($psqlCmd) {
    # pipe raw bytes via cmd /c type (rule 6: avoid PS 5.1 UTF-16 stdin wrapper)
    $vioOut = Join-Path $probeDir 'c3-route-violation.out'
    Remove-Item $vioOut -ErrorAction SilentlyContinue
    $vioLines = cmd /c ('type "' + $violFile + '" | "' + $psqlCmd.Source + '" -h 39.106.204.43 -p 15432 -U postgres -d helloai -v ON_ERROR_STOP=1 -X -t -A -F "|" 2>&1')
    $rc = $LASTEXITCODE
    $vioRows = @($vioLines) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    if ($rc -eq 0) {
        Assert-Pass ((Get-ListCount $vioRows) -eq 0) 'S3' ('route violation rows=' + (Get-ListCount $vioRows))
        $vioRows | ForEach-Object { Write-Output ('S3 VIOLATION: ' + $_) }
    } else {
        Assert-Pass $false 'S3' ('violation probe failed rc=' + $rc)
    }

    $staOut = Join-Path $probeDir 'c3-route-stats.out'
    Remove-Item $staOut -ErrorAction SilentlyContinue
    $staLines = cmd /c ('type "' + $statFile + '" | "' + $psqlCmd.Source + '" -h 39.106.204.43 -p 15432 -U postgres -d helloai -v ON_ERROR_STOP=1 -X -t -A -F "|" 2>&1')
    $rc2 = $LASTEXITCODE
    $staRow = @($staLines) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1
    if ($rc2 -eq 0 -and $staRow) {
        $parts = $staRow.Split('|')
        $rtT = [int]$parts[0]; $allT = [int]$parts[1]; $gray = [int]$parts[2]
        $ratioPct = $(if ($allT -gt 0) { [math]::Round(100.0 * $rtT / $allT, 1) } else { 0 })
        Write-Output ('S3 stats: rt_tasks=' + $rtT + ' all_tasks=' + $allT + ' ratio=' + $ratioPct + '% gray=' + $gray + '% window=' + $(if ($WindowMinutes -gt 0) { $WindowMinutes + 'min' } else { 'all' }))
        if ($allT -ge 10) {
            $dev = [math]::Abs($ratioPct - $gray)
            Assert-Pass ($dev -le 10.0) 'S3' ('route ratio deviation=' + $dev + '% (<= 10%)')
        } else {
            Write-Output 'S3 INFO: sample < 10 tasks, ratio deviation not judged yet (accumulate more runs)'
        }
    } else {
        Assert-Pass $false 'S3' ('stats probe failed rc=' + $rc2)
    }
} else {
    Write-Output 'S3 NOTE: no local psql; run probes via session MCP (postgres_helloai_dev query):'
    Write-Output ('  1) expect 0 rows : ' + $violFile)
    Write-Output ('  2) check stats   : ' + $statFile)
}

# ------------------------------------------------------------
# S4: summary
# ------------------------------------------------------------
Write-Output ('==== SUMMARY: PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' ====')
if ($global:FailCount -gt 0) {
    Write-Output 'RESULT: FAILED - fix items above, then rerun; DB-side probes to be confirmed via MCP'
    exit 1
}
Write-Output 'RESULT: ROUTE OK - gray config verified; DB-side probes to be confirmed via MCP'
exit 0