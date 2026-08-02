# step9b 验证：Planner applyDependsOn 修复后 depends_on 不再回滚到同一 id
# Usage: .\verify-step9b-depends-on.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Stop'

$projectRoot = 'e:\yhzx\1027\helloai'
$jarPath     = "$projectRoot\helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar"
$logFile     = "$projectRoot\spring-boot-run.log"
$pidFile     = "$projectRoot\.spring-boot-pid"
$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'
$BaseUrl     = 'http://localhost:6565'

# UTF-8 无 BOM（避免 PowerShell 5.1 自动 BOM 干扰 docker exec 文本输入）
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 30) {
    $json = $null
    if ($Body -ne $null) {
        $json = ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType 'application/json' -Body $json -TimeoutSec $TimeoutSec
}

function Run-Psql {
    param([Parameter(Mandatory=$true)][string]$Sql)
    $tmpSql = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmpSql, $Sql, $script:Utf8NoBom)
    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = $sqlContent | & docker exec -i $pgContainer psql -v ON_ERROR_STOP=1 -t -A -F '|' -U $pgUser -d $pgDb 2>&1
    $rc = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return @{ rc = $rc; out = $out }
}

function Wait-ForHealth([int]$TimeoutSec = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    $healthUris = @(
        ($BaseUrl + '/api/health'),
        'http://127.0.0.1:6565/api/health',
        'http://[::1]:6565/api/health'
    )
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($uri in $healthUris) {
            try {
                $pingResp = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Get -TimeoutSec 5
                if ([int]$pingResp.StatusCode -eq 200) { return $true }
            } catch {}
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

# ------------------------------------------------------------
# STEP 0: pre-flight
# ------------------------------------------------------------
Set-Location $projectRoot

$dockerCheck = & docker ps --format '{{.Names}}' --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match $pgContainer)) {
    Write-Error "container [$pgContainer] not up"
    exit 1
}

if (-not (Test-Path $jarPath)) {
    Write-Error "jar missing: $jarPath"
    exit 1
}

# ------------------------------------------------------------
# STEP 1: kill old java + restart with storage=table
# ------------------------------------------------------------
Write-Output '=== [1] kill old java + restart backend (storage=table) ==='
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

$conns = Get-NetTCPConnection -LocalPort 6565 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

if (Test-Path $logFile) { Remove-Item $logFile -Force }

$javaExe = Join-Path ([Environment]::GetEnvironmentVariable('JAVA_HOME')) 'bin\java.exe'
if (-not (Test-Path $javaExe)) { Write-Error "JAVA_HOME java.exe not found"; exit 1 }

$proc = Start-Process -FilePath $javaExe `
    -ArgumentList @('-Dhelloai.task-running-spec.storage=table', '-jar', $jarPath) `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError ($logFile + '.err') `
    -PassThru -NoNewWindow
$proc.Id | Out-File -FilePath $pidFile -Encoding ASCII
Write-Output ("Started PID=" + $proc.Id)

Write-Output '=== [2] wait for health ==='
$ok = Wait-ForHealth -TimeoutSec 120
if (-not $ok) {
    Write-Error 'health check failed; tail log:'
    Get-Content $logFile -Tail 80 -Encoding UTF8
    exit 1
}
Write-Output 'health OK'

# ------------------------------------------------------------
# STEP 3: admin login + 快速注册 PLANNER（idempotent）
# ------------------------------------------------------------
Write-Output '=== [3] admin login + register PLANNER ==='
$loginResp = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/auth/login') -Body @{
    type = 'admin'; username = 'admin'; credential = 'admin123'
} -Headers @{}
Assert-True ($loginResp.code -eq 200) 'login failed'
$adminHeaders = @{ 'X-Admin-Token' = $loginResp.data.token }

$plannerResp = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/agents/register') -Body @{
    name = 'step9b-verify-planner'
    role = 'PLANNER'
    description = 'step9b verify'
    accessType = 'API_KEY_LLM'
    modelType = 'deepseek:deepseek-chat'
    idempotent = $true
} -Headers @{}
Assert-True ($plannerResp.code -eq 200) 'register planner failed'
Write-Output ("plannerAgentId=" + $plannerResp.data.id)

# ------------------------------------------------------------
# STEP 4: create task + decompose + confirm
# ------------------------------------------------------------
$ts = [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
Write-Output '=== [4] create task + decompose + confirm ==='

$taskResp = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/tasks') -Body @{
    title = 'step9b-dep-verify-' + $ts
    description = 'Build a small note-taking API: schema, REST endpoints, unit tests, README.'
} -Headers $adminHeaders
Assert-True ($taskResp.code -eq 200) 'create task failed'
$taskId = [string]$taskResp.data.id
Write-Output ("taskId=" + $taskId)

$planResp = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/tasks/' + $taskId + '/plan') -Body @{} `
    -Headers $adminHeaders -TimeoutSec 180
Assert-True ($planResp.code -eq 200) 'plan failed'
$drafts = @($planResp.data)
$draftCount = $drafts.Count
Write-Output ("draftCount=" + $draftCount)

$confirmResp = Invoke-Json -Method 'Post' -Url ($BaseUrl + '/api/tasks/' + $taskId + '/plan/confirm') -Body @{} `
    -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) 'confirm failed'
$confirmed = @($confirmResp.data)
Write-Output ("confirmed=" + $confirmed.Count)

# ------------------------------------------------------------
# STEP 5: DB 验证 sub_task.depends_on 是否真实
# ------------------------------------------------------------
Write-Output '=== [5] DB check: sub_task.depends_on by task ==='
$expectedSubCount = $confirmed.Count

$sqlSub = @"
SELECT id, title, depends_on, status
FROM sub_task
WHERE task_id = $taskId
ORDER BY create_time ASC, id ASC;
"@
$subResult = Run-Psql -Sql $sqlSub
Write-Output ('sub rows:')
Write-Output $subResult.out
$rows = @($subResult.out -split "`n" | Where-Object { $_ -and $_ -notmatch '^\(' })
Assert-True ($rows.Count -ge 1) 'no sub_task rows'

# 收集所有真实 id
$realIds = @()
foreach ($r in $rows) {
    $fields = $r.Trim().Split('|')
    $realIds += [string]$fields[0]
}
Write-Output ('real ids: ' + ($realIds -join ','))

# 检查每行的 depends_on
$failures = @()
foreach ($r in $rows) {
    $fields = $r.Trim().Split('|')
    $id = [string]$fields[0]
    $title = [string]$fields[1]
    $deps = [string]$fields[2]
    Write-Output ('  id=' + $id + ' title=' + $title + ' deps=' + $deps)
    if ($deps -eq '{}' -or $deps -eq '[]' -or $deps -eq '') { continue }
    # depends_on 是 JSONB 数组：psql 默认输出 [id] 或 [id,id] 形式
    # trim [] 后 split 逗号取数字 token
    $clean = $deps.TrimStart('[').TrimEnd(']').Trim()
    if ([string]::IsNullOrWhiteSpace($clean)) { continue }
    foreach ($tok in $clean.Split(',')) {
        $tok = $tok.Trim()
        if ($tok -notmatch '^\d+$') { continue }
        if (-not ($realIds -contains $tok)) {
            $failures += "id=$id depends_on contains unknown id=$tok (not in real ids)"
        }
    }
}

# 关键修复检查：依赖中不能出现"全 batch 同一 id"——历史 bug 是全回滚成同一id
$distinctDepTokens = @()
foreach ($r in $rows) {
    $fields = $r.Trim().Split('|')
    $deps = [string]$fields[2]
    if ($deps -eq '{}' -or $deps -eq '[]' -or $deps -eq '') { continue }
    $clean = $deps.TrimStart('[').TrimEnd(']').Trim()
    if ([string]::IsNullOrWhiteSpace($clean)) { continue }
    foreach ($tok in $clean.Split(',')) {
        $tok = $tok.Trim()
        if ($tok -match '^\d+$') { $distinctDepTokens += $tok }
    }
}
$distinctDepCount = ($distinctDepTokens | Select-Object -Unique).Count
Write-Output ("distinct dep tokens across all rows = " + $distinctDepCount + " (expected >=2 for non-trivial dep graph)")

if ($failures.Count -gt 0) {
    Write-Error ('依赖图含未知 id:' + [Environment]::NewLine + ($failures -join [Environment]::NewLine))
    exit 1
}
if ($distinctDepCount -lt 2 -and $expectedSubCount -ge 3) {
    # 这恰好是 step9b 想修的"全回滚到同一 id"症状
    Write-Error '依赖图所有依赖都指向同一 id, 已知 step9b 历史 bug 复发'
    exit 1
}

# 取日志中 step9b 诊断行确认（仅 deps 非空的子任务才打诊断行，是预期行为）
$diagHits = Select-String -Path $logFile -Pattern 'applyDependsOn:' -Encoding UTF8
Write-Output ('applyDependsOn 诊断行数 = ' + $diagHits.Count)
if ($diagHits.Count -gt $expectedSubCount) {
    Write-Error ('诊断日志超过子任务数 期望 <= ' + $expectedSubCount + ' 实际=' + $diagHits.Count)
    exit 1
}

Write-Output 'OK: step9b verify PASS, deps ids are real batch ids and graph healthy'
exit 0
