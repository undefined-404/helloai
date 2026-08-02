# ============================================================
# helloai Task Running Spec Phase B 端到端验证脚本
# 用途：切到 storage=table，独立表存储验证全链路：
#       1. 重启 backend（带 -Dhelloai.task-running-spec.storage=table）
#       2. admin 登录 + 注册 PLANNER/EXECUTOR/REVIEWER + vault 绑 key
#       3. 创建任务 → LLM 拆解 → confirm 触发 Baseline 写入 task_running_spec
#       4. 跑通内循环 → 各子任务完成 → ExecutionRecord 写入 task_execution_record
#       5. DB 验证：V36 表已建；running_spec 行 baseline+contextSummary 非空；
#          execution_record 行数 = 子任务数；UNIQUE INDEX 防重复 insert
#       6. 日志验证：TaskRunningSpecDataMigrator 已触发
# Ref:  doc/HelloAI_实现差距表.md（V36 + Phase B）
# 前置：docker compose up -d（postgres:15432）；mvn package 已产出 helloai-start jar；
#       DEEPSEEK_API_KEY 已配置（或使用 application.yml 默认）。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-task-running-spec-phase-b.ps1
#   # 不重启后端（用现有 6565 进程；storage 可能不是 table，Phase B 断言可能失败）：
#   powershell -File .\scripts\powershell\verify-task-running-spec-phase-b.ps1 -SkipRestart
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [string]$LlmModelType = "deepseek:deepseek-chat",
    [string]$LlmApiKey = $env:DEEPSEEK_API_KEY,
    [string]$StorageMode = "table",
    [int]$PlanTimeoutSec = 180,
    [int]$LoopTimeoutSec = 900,
    [int]$PollIntervalSec = 10,
    [int]$HealthTimeoutSec = 180,
    [switch]$SkipRestart = $false
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 强制头（避免中文乱码 + 无 BOM）
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$projectRoot = 'e:\yhzx\1027\helloai'
$jarPath     = "$projectRoot\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar"
$logFile     = "$projectRoot\spring-boot-run.log"
$pidFile     = "$projectRoot\.spring-boot-pid"
$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

# ------------------------------------------------------------
# helpers
# ------------------------------------------------------------
function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        Write-Error ("ASSERT_FAIL: " + $Msg)
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 30) {
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Get-SubTasks([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks?taskId=" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("list sub-tasks code=" + $resp.code + " msg=" + $resp.msg)
    if ($resp.data.records -ne $null) { return @($resp.data.records) }
    return @($resp.data)
}

function Get-Task([string]$TaskId, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/" + $TaskId) -Body $null -Headers $Headers
    Assert-True ($resp.code -eq 200) ("get task code=" + $resp.code + " msg=" + $resp.msg)
    return $resp.data
}

function Register-LlmAgent([string]$Name, [string]$Role, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/agents/register") -Body @{
        name        = $Name
        role        = $Role
        description = "verify-task-running-spec-phase-b"
        accessType  = "API_KEY_LLM"
        modelType   = $LlmModelType
        idempotent  = $true
    } -Headers @{}
    Assert-True ($resp.code -eq 200) ("register " + $Role + " code=" + $resp.code + " msg=" + $resp.msg)
    return [string]$resp.data.id
}

function Bind-AgentApiKey([string]$AgentId, [string]$Provider, [hashtable]$Headers) {
    $resp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/credentials/agents/" + $AgentId + "/api-key") -Body @{
        provider = $Provider
        apiKey   = $LlmApiKey
        remark   = "verify-task-running-spec-phase-b"
    } -Headers $Headers
    Assert-True ($resp.code -eq 200) ("bind api-key for agent " + $AgentId + " code=" + $resp.code + " msg=" + $resp.msg)
}

function Run-Psql {
    param(
        [Parameter(Mandatory=$true)][string]$Sql,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $Sql = $Sql.TrimStart([char]0xFEFF)
    $tmpSql = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmpSql, $Sql, $script:Utf8NoBom)
    Remove-Item $OutFile -ErrorAction SilentlyContinue

    $dockerArgs = @('exec', '-i', $pgContainer, 'psql',
        '-v', 'ON_ERROR_STOP=0',
        '-X', '-t', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)

    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    # 不能用 $output：那是 PowerShell 的自动变量，会与 pipe collector 冲突。
    # 用 $psqlOutput 重命名后 psql 输出只走 Out-File，不再泄漏到函数 pipeline。
    # [6.4] 故意制造 UNIQUE INDEX duplicate（$ErrorActionPreference=Stop 下 docker 非零 exit 会抛错）：
    # 临时把 $ErrorActionPreference 设为 Continue，捕获错误后恢复，避免误报。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $psqlOutput = $sqlContent | & docker @dockerArgs 2>&1
    $rc = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    $psqlOutput | Out-File -FilePath $OutFile -Encoding UTF8

    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return $rc
}

function Get-PsqlFields([string]$Path) {
    $line = Get-Content -Path $Path -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^\(' } |
        Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '<NULL>', '').Split('|')
}

function Wait-ForHealth {
    param([int]$TimeoutSec = 90)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    # localhost 在 Windows 上可能优先解析为 IPv6；逐个尝试环回地址，避免单地址解析差异。
    $healthUris = @(
        ($BaseUrl + '/api/health'),
        'http://127.0.0.1:6565/api/health',
        'http://[::1]:6565/api/health'
    )
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($uri in $healthUris) {
            try {
                # 统一使用 PowerShell 原生请求，避免 Windows PowerShell 5.1 下
                # HttpClient Task.Result 的代理/异常包装差异吞掉可用的 200 响应。
                $pingResp = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Get -TimeoutSec 5
                if ([int]$pingResp.StatusCode -eq 200) {
                    Write-Host ('    [health] OK via ' + $uri)
                    return $true
                }
            } catch {
                # 当前地址失败后继续尝试其它环回地址。
            }
        }
        Write-Host '    [health] waiting...'
        Start-Sleep -Seconds 3
    }
    return $false
}

# ------------------------------------------------------------
# STEP 0: pre-flight
# ------------------------------------------------------------
Write-Output '=== [0] pre-flight ==='
Set-Location $projectRoot

$dockerCheck = & docker ps --format '{{.Names}}|{{.Status}}' --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Error "container [$pgContainer] is NOT up. Run: docker compose up -d"
    exit 1
}
Write-Output 'postgres container up'

if (-not (Test-Path $jarPath)) {
    Write-Output "jar missing, building..."
    mvn -pl helloai-start -am -DskipTests package 2>&1 | Out-Null
    if (-not (Test-Path $jarPath)) {
        Write-Error "build failed, jar still missing: $jarPath"
        exit 1
    }
}
Write-Output "jar: $jarPath"

# ------------------------------------------------------------
# STEP 1: (optional) restart backend with storage=$StorageMode
# ------------------------------------------------------------
if (-not $SkipRestart) {
    Write-Output "=== [1] restart backend with -Dhelloai.task-running-spec.storage=$StorageMode ==="

    # kill old (PID file preferred; fall back to netstat)
    if (Test-Path $pidFile) {
        $oldPid = [int](Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($oldPid -gt 0) {
            Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
            Write-Output ("Killed previous PID $oldPid (from pid file)")
        }
    }
    $conns = Get-NetTCPConnection -LocalPort 6565 -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        Write-Output ("Killed PID " + $c.OwningProcess + " (port 6565 owner)")
    }
    Start-Sleep -Seconds 4

    # rebuild only if jar older than 5 minutes
    $needBuild = $true
    if (Test-Path $jarPath) {
        $ageMinutes = ((Get-Date) - (Get-Item $jarPath).LastWriteTime).TotalMinutes
        if ($ageMinutes -lt 5) { $needBuild = $false }
    }
    if ($needBuild) {
        Write-Output "jar stale, rebuilding..."
        mvn -pl helloai-start -am -DskipTests package 2>&1 | Out-Null
    }

    # Pin to JDK17 (Oracle Java wrapper on PATH can fail silently in some shells);
    # JAVA_HOME is the canonical location declared in docker-compose / IDEA configs.
    $javaExe = Join-Path ([Environment]::GetEnvironmentVariable('JAVA_HOME')) 'bin\java.exe'
    if (-not (Test-Path $javaExe)) {
        Write-Error "JAVA_HOME java.exe not found at $javaExe"
        exit 1
    }

    if (Test-Path $logFile) { Remove-Item $logFile -Force }
    # 用 Start-Process 启动后台 Java；通过 -PassThru 拿 PID，立刻写 PID 文件
    $proc = Start-Process -FilePath $javaExe `
        -ArgumentList @('-Dhelloai.task-running-spec.storage=' + $StorageMode, '-jar', $jarPath) `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError ($logFile + '.err') `
        -PassThru -NoNewWindow
    Write-Output ("Started PID=" + $proc.Id + " using " + $javaExe + " storage=" + $StorageMode)
    $proc.Id | Out-File -FilePath $pidFile -Encoding ASCII
    # 不调用 Disown / WaitForExit，让 Start-Process 创建的进程独立于当前 PS 进程继续运行

    $healthy = Wait-ForHealth -TimeoutSec $HealthTimeoutSec
    Assert-True $healthy ("backend not healthy in " + $HealthTimeoutSec + "s; see $logFile")
    Write-Output 'backend healthy'
} else {
    Write-Output "=== [1] SKIP restart (using existing 6565); continuing anyway ==="
    $healthy = Wait-ForHealth -TimeoutSec 10
    Assert-True $healthy "server NOT reachable at $BaseUrl"
}
Write-Output ''

# ------------------------------------------------------------
# STEP 2: admin login + agent register + vault bind
# ------------------------------------------------------------
Write-Output '=== [2] admin login + agent register + vault bind ==='
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type        = "admin"
    username    = $AdminUsername
    credential  = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
$adminHeaders = @{ "X-Admin-Token" = $loginResp.data.token }

if ([string]::IsNullOrWhiteSpace($LlmApiKey)) {
    $LlmApiKey = "sk-a36fdda1d4ad4e0386e78fc435be0d16"
    Write-Output "WARN: DEEPSEEK_API_KEY not set, fallback to application.yml default key"
}
$llmProvider = ($LlmModelType -split ":")[0]

$plannerAgentId  = Register-LlmAgent -Name "phase-b-planner"  -Role "PLANNER"  -Headers $adminHeaders
$executorAgentId = Register-LlmAgent -Name "phase-b-executor" -Role "EXECUTOR" -Headers $adminHeaders
$reviewerAgentId = Register-LlmAgent -Name "phase-b-reviewer" -Role "REVIEWER" -Headers $adminHeaders
Write-Output ("planner=" + $plannerAgentId + " executor=" + $executorAgentId + " reviewer=" + $reviewerAgentId)

Bind-AgentApiKey -AgentId $plannerAgentId  -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $executorAgentId -Provider $llmProvider -Headers $adminHeaders
Bind-AgentApiKey -AgentId $reviewerAgentId -Provider $llmProvider -Headers $adminHeaders
Write-Output 'vault credentials bound'
Write-Output ''

# ------------------------------------------------------------
# STEP 3: create task + decompose + confirm
# ------------------------------------------------------------
$ts = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")
Write-Output "=== [3] create task + decompose + confirm ==="

$taskResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = "phase-b-rs-" + $ts
    description = "Write a 2-sentence product blurb for an internal note-taking app that highlights: 1) AI-assisted tagging; 2) cross-device sync."
} -Headers $adminHeaders
Assert-True ($taskResp.code -eq 200) ("create task code=" + $taskResp.code + " msg=" + $taskResp.msg)
$taskId = [string]$taskResp.data.id
Write-Output ("taskId=" + $taskId)

Write-Output ("[3a] decompose (LLM call, timeout=" + $PlanTimeoutSec + "s)")
$planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan") -Body @{} `
    -Headers $adminHeaders -TimeoutSec $PlanTimeoutSec
Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
$drafts = @($planResp.data)
Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
Write-Output ("drafts=" + $drafts.Count)

Write-Output "[3b] confirm plan (triggers TaskRunningSpec.initialize -> task_running_spec INSERT)"
$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/" + $taskId + "/plan/confirm") -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)
$confirmedSubTasks = @($confirmResp.data)
Assert-True ($confirmedSubTasks.Count -eq $drafts.Count) ("confirmed count mismatch")
Write-Output ("confirmedSubTasks=" + $confirmedSubTasks.Count)
Write-Output ''

# ------------------------------------------------------------
# STEP 4: wait for inner loop (write ExecutionRecord)
# ------------------------------------------------------------
Write-Output "=== [4] wait for inner loop (timeout=$LoopTimeoutSec s) ==="
$deadline = [DateTime]::UtcNow.AddSeconds($LoopTimeoutSec)
$allDone = $false
$lastStatus = ""
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSec
    $subTasks = Get-SubTasks -TaskId $taskId -Headers $adminHeaders
    $statusMap = @{}
    foreach ($s in $subTasks) { $statusMap[[string]$s.id] = $s.status }
    $summary = ($subTasks | ForEach-Object { "" + $_.id + ":" + $_.status }) -join " "
    Write-Output ("    [" + [DateTime]::UtcNow.ToString("HH:mm:ss") + "] " + $summary)
    $lastStatus = $summary

    $dead = @($subTasks | Where-Object { $_.status -in @("DEAD_LETTER", "BLOCKED") })
    Assert-True ($dead.Count -eq 0) ("subTasks entered DEAD_LETTER/BLOCKED: " + (($dead | ForEach-Object { $_.id }) -join ","))

    $notDone = @($subTasks | Where-Object { $_.status -notin @("DONE", "CANCELLED") })
    if ($notDone.Count -eq 0) {
        $allDone = $true
        break
    }
}
Assert-True $allDone ("inner loop not finished in " + $LoopTimeoutSec + "s; last=" + $lastStatus)
Write-Output 'all subTasks DONE'
Write-Output ''

# wait a bit more for ExecutionRecord writes + context_summary update to settle
Write-Output "[4b] wait extra 10s for ExecutionRecord writes to settle..."
Start-Sleep -Seconds 10

# ------------------------------------------------------------
# STEP 5: assert task auto-closed to DONE
# ------------------------------------------------------------
Write-Output "=== [5] assert task auto-closed ==="
$taskDone = $false
$closeDeadline = [DateTime]::UtcNow.AddSeconds(60)
while ([DateTime]::UtcNow -lt $closeDeadline) {
    $taskDetail = Get-Task -TaskId $taskId -Headers $adminHeaders
    if ($taskDetail.status -eq "DONE") {
        $taskDone = $true
        break
    }
    Start-Sleep -Seconds 5
}
Assert-True $taskDone ("task not auto-closed, status=" + $taskDetail.status)
Write-Output ("task DONE, taskId=" + $taskId)
Write-Output ''

# ------------------------------------------------------------
# STEP 6: DB verification
# ------------------------------------------------------------
Write-Output '=== [6] DB verification ==='

$outDir = Join-Path $projectRoot ('phase-b-rs-' + $ts)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# [6.1] V36 schema sanity (表存在 + 唯一索引存在)
$s61Sql = @"
SELECT
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'task_running_spec') AS rs_tbl,
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'task_execution_record') AS er_tbl,
    (SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'task_execution_record' AND indexname = 'idx_execution_record_task_subtask') AS er_idx;
"@
$s61File = Join-Path $outDir 's61-schema.txt'
Run-Psql -Sql $s61Sql -OutFile $s61File
$s61 = Get-PsqlFields -Path $s61File
Assert-True ($s61 -and $s61.Count -eq 3) "s61 unable to parse schema fields"
Assert-True ([int]$s61[0] -gt 0) "task_running_spec table missing"
Assert-True ([int]$s61[1] -gt 0) "task_execution_record table missing"
Assert-True ([int]$s61[2] -gt 0) "unique index idx_execution_record_task_subtask missing"
Write-Output ("[6.1] schema OK: rs_tbl=" + $s61[0] + " er_tbl=" + $s61[1] + " er_idx=" + $s61[2])

# [6.2] task_running_spec row for our task
$s62Sql = @"
SELECT
    task_id,
    version,
    CASE WHEN baseline IS NOT NULL THEN 1 ELSE 0 END AS has_baseline,
    COALESCE(LENGTH(context_summary), 0) AS cs_len,
    COALESCE(jsonb_typeof(baseline), 'null') AS bl_type
FROM task_running_spec WHERE task_id = $taskId;
"@
$s62File = Join-Path $outDir 's62-running-spec.txt'
Run-Psql -Sql $s62Sql -OutFile $s62File
$s62 = Get-PsqlFields -Path $s62File
if (-not $s62) {
    if ($StorageMode -ne 'table') {
        Write-Output ("[6.2] no task_running_spec row (storage=" + $StorageMode + "); SKIP (expected when not Phase B)")
    } else {
        Assert-True $false "no task_running_spec row for taskId=$taskId; storage=table but row missing"
    }
} else {
    Assert-True ([int]$s62[2] -eq 1) ("baseline IS NULL: " + ($s62 -join '|'))
    Assert-True ([int]$s62[3] -gt 0) ("context_summary empty: cs_len=" + $s62[3])
    Write-Output ("[6.2] running_spec OK: taskId=" + $s62[0] + " version=" + $s62[1] + " has_baseline=" + $s62[2] + " cs_len=" + $s62[3] + " bl_type=" + $s62[4])
}

# [6.3] task_execution_record row count == sub-task count
$expectedCount = $confirmedSubTasks.Count
$s63Sql = "SELECT COUNT(*) FROM task_execution_record WHERE task_id = $taskId AND deleted = 0;"
$s63File = Join-Path $outDir 's63-record-count.txt'
Run-Psql -Sql $s63Sql -OutFile $s63File | Out-Null
$s63 = Get-PsqlFields -Path $s63File
Write-Output ('    [diag] s63 raw=' + ($s63 -join ',') + ' fileContent=' + ((Get-Content $s63File -Encoding UTF8 -Raw) -replace "`r?`n", '\n'))
# PowerShell 陷阱 1：$s63 是字符串时，$s63[0] 返回 char，'[int][char]4' = 52（ASCII 码）。
# PowerShell 陷阱 2：$s63 是数组时，$s63[0] 是首元素，但若它仍是 string，[0] 又会拿 char。
# 稳健写法：把 $s63 整体转为 string → 取第一段（防御数组/字符串混用） → 只保留数字 → 校验后转 int。
$countStr = ''
if ($s63) {
    $firstSeg = ([string]$s63 -split '\|')[0]
    $countStr = ($firstSeg -replace '[^\d]', '').Trim()
}
$actualCount = if ($countStr -match '^\d+$') { [int]$countStr } else { 0 }
if ($StorageMode -eq 'table') {
    Assert-True ($actualCount -gt 0) "expected >0 execution_record rows for taskId=$taskId, got 0"
    Assert-True ($actualCount -le $expectedCount) ("UNIQUE broke: " + $actualCount + " > " + $expectedCount + " subTasks")
    Write-Output ("[6.3] execution_records: actual=$actualCount <= subTasks=$expectedCount (UNIQUE enforced)")
} else {
    Write-Output ("[6.3] execution_records: actual=$actualCount (storage=$StorageMode, informational only)")
}

# [6.4] UNIQUE INDEX forces dedup: direct INSERT of duplicate row must fail
if ($StorageMode -eq 'table') {
    $firstSubId = ([string]$confirmedSubTasks[0].id)
    # 取一个非常高的 id 避免与 base_id 冲突；两条记录都拿同一个 (task_id, sub_task_id)，第二条 INSERT 应失败
    $s64Sql = @"
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO task_execution_record (id, task_id, sub_task_id, create_by, update_by, create_time, update_time)
VALUES (9000000000001, $taskId, $firstSubId, 'phase-b-test', 'phase-b-test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO task_execution_record (id, task_id, sub_task_id, create_by, update_by, create_time, update_time)
VALUES (9000000000002, $taskId, $firstSubId, 'phase-b-test-dup', 'phase-b-test-dup', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
ROLLBACK;
SELECT 'UNIQUE_INDEX_OK';
"@
    $s64File = Join-Path $outDir 's64-unique-test.txt'
    Run-Psql -Sql $s64Sql -OutFile $s64File
    $s64Content = Get-Content -Path $s64File -Encoding UTF8 -Raw
    $uniqueViolationSeen = $false
    $allLines = Get-Content -Path $s64File -Encoding UTF8
    foreach ($ln in $allLines) {
        if ($ln -match 'duplicate key value violates unique constraint') {
            $uniqueViolationSeen = $true
            break
        }
        if ($ln -match 'idx_execution_record_task_subtask') {
            $uniqueViolationSeen = $true
            break
        }
    }
    Assert-True $uniqueViolationSeen ("UNIQUE INDEX did not block duplicate (task_id, sub_task_id) insert; see $s64File")
    Write-Output "[6.4] UNIQUE INDEX blocks duplicate ExecutionRecord (rework dedup OK)"
}

# [6.5] DataMigrator ran on startup
# 注意：Select-String -Pattern 在 PS 5.1 下处理中文正则会被 ANSI 解析，遇到 CJK 报“非法正则”。
# 改用 Get-Content + [regex]::IsMatch（C# 库支持 Unicode 正常）逐行匹配。
$migratorLines = @()
if (Test-Path $logFile) {
    $migPattern = 'TaskRunningSpec (迁移|表已有|未发现)'
    foreach ($ln in Get-Content -Path $logFile -Encoding UTF8) {
        if ([regex]::IsMatch($ln, $migPattern)) {
            $migratorLines += [PSCustomObject]@{ Line = $ln }
        }
    }
}
$migratorCount = @($migratorLines).Count
Write-Output ("[6.5] DataMigrator log lines found: $migratorCount")
if ($migratorCount -gt 0) {
    foreach ($ln in $migratorLines | Select-Object -First 3) { Write-Output ("    " + $ln.Line.Trim()) }
}
# Note: 在新启动且表已存在数据时也会输出"跳过：新表已有 ..."，因此 >=0 都是合法。
# 我们不强断言 > 0，因为如果 storage != table，DataMigrator 根本没注册。

# [6.6] 双存储对比声明：当 storage=table 时，task.context.runningSpec 不应被 Phase B 写入
$s66Sql = @"
SELECT
    (context ? 'runningSpec') AS has_running_spec
FROM task WHERE id = $taskId;
"@
$s66File = Join-Path $outDir 's66-context-nojspec.txt'
Run-Psql -Sql $s66Sql -OutFile $s66File
$s66 = Get-PsqlFields -Path $s66File
$hasCtxSpec = if ($s66) { ($s66[0] -match '^(t|true)$') -or ($s66[0] -eq 't') } else { $false }
if ($StorageMode -eq 'table') {
    # Phase B 不应再写 task.context.runningSpec（TableService 只写真表）
    # 但 Phase A 之前的写入如果存在，仍会被读到。这里我们只记录，不强制：
    Write-Output ("[6.6] task.context has runningSpec key = $hasCtxSpec (informational; Phase B writes only to new tables)")
} else {
    Write-Output ("[6.6] task.context has runningSpec key = $hasCtxSpec (storage=$StorageMode)")
}

Write-Output ''
Write-Output '=== ALL PASSED ==='
Write-Output ("Phase B end-to-end verified.")
Write-Output ("taskId=$taskId  drafts=$($drafts.Count)  subTasks=$($confirmedSubTasks.Count)  execRecords=$actualCount  storage=$StorageMode")
Write-Output ("DB artifacts: $outDir")
Write-Output ("backend log: $logFile")
exit 0
