# ============================================================
# helloai AgentHub V1 P1 (Dashboard 值班概览 + 列表页) + R2 + R3 验证脚本
# Ref:
#   doc/HelloAI_迭代执行记录.md  (AgentHub V1 P1)
#   doc/HelloAI_实现差距表.md   (N12 P1 收尾)
#   .agents/skills/helloai-preflight/SKILL.md   (规则 6：脚本 UTF-8 编码 + PS 5.1 单引号 + 拼接)
#
# 覆盖四个真实环境场景：
#   S1  GET /api/admin/duty-leases/overview          -> 200，active/closed/expired/total 字段齐
#   S2  GET /api/admin/duty-leases                   -> 200，PageResult.list/total/pages/current 齐
#   S3  GET /api/admin/duty-leases?status=ACTIVE     -> 过滤生效（返回行 status 均为 ACTIVE）
#   S4  DB 抽查：agent_command_outbox 中 status IN (1,3) 行的 last_sent_at/confirmed_at 不全为 NULL
#        验证 V22 backfill 已生效（R3 收尾证据）
#
# Pre-conditions:
#   - docker compose up -d (helloai-postgres:15432)
#   - helloai-start via IDEA @ :6565
#   - Flyway 已跑到 V22（agent_command_outbox_backfill_timestamps）
#   - 至少有一个 Agent 执行过 dispatch-mode ∈ {MQ,BOTH} 的子任务（才能产生 SENT/CONFIRMED 行）
#
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-dashboard-duty-leases.ps1
# ============================================================

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom   # 关键：无 BOM，防止管道输出时添加 BOM

Add-Type -AssemblyName System.Net.Http

$base      = 'http://localhost:6565'
$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))

$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

# ============================================================
# helper: HTTP JSON (PS 5.1 safe single-quote + plus concat)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{}
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne 'GET' -and $Method -ne 'DELETE') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if     ($Method -eq 'GET')    { $resp = $client.GetAsync($Uri).Result }
        elseif ($Method -eq 'DELETE') { $resp = $client.DeleteAsync($Uri).Result }
        elseif ($Method -eq 'POST')   { $resp = $client.PostAsync($Uri, $content).Result }
        elseif ($Method -eq 'PUT')    { $resp = $client.PutAsync($Uri, $content).Result }
        return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

# ============================================================
# helper: docker exec psql
# ============================================================
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
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-t', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)

    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $output = $sqlContent | & docker @dockerArgs 2>&1
    $rc = $LASTEXITCODE

    $output | Out-File -FilePath $OutFile -Encoding UTF8

    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    return $rc
}

function Get-PsqlFields {
    param([Parameter(Mandatory=$true)][string]$Path)
    $line = Get-Content -Path $Path -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^\(' } |
        Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '<NULL>', '').Split('|')
}

# ============================================================
# STEP 0: pre-flight
# ============================================================
Write-Output '=== [0] pre-flight ==='

$dockerCheck = & docker ps --format '{{.Names}}|{{.Status}}' --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Error "container [$pgContainer] is NOT up. Run: docker compose up -d"
    exit 1
}
Write-Output 'postgres container up'

try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync("$base/api/health").Result
    Write-Output "server $base HTTP $($pingResp.StatusCode)"
    $ping.Dispose()
} catch {
    Write-Error "server NOT reachable at $base - start HelloAIApplication via IDEA first"
    exit 1
}
Write-Output ''

# ============================================================
# STEP A: admin login
# ============================================================
Write-Output '=== [A] admin login ==='
$loginResp = Invoke-Json -Method POST -Uri "$base/api/auth/login" -Body '{"type":"admin","username":"admin","credential":"admin123"}'
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error "admin login failed: $($loginResp.Body)"
    exit 1
}
Write-Output "adminToken = $($adminToken.Substring(0, 16))..."
Write-Output ''

# ============================================================
# STEP S1: overview
# ============================================================
Write-Output '=== [S1] GET /api/admin/duty-leases/overview ==='
$s1Resp = Invoke-Json -Method GET -Uri "$base/api/admin/duty-leases/overview" -Headers @{ 'X-Admin-Token' = $adminToken }
Write-Output "HTTP $($s1Resp.Code)"
Write-Output "body: $($s1Resp.Body)"
if ($s1Resp.Code -ne 200) {
    Write-Error "S1 FAIL: HTTP $($s1Resp.Code)"
    exit 1
}
$s1Json = $s1Resp.Body | ConvertFrom-Json
$s1Data = $s1Json.data
if (-not $s1Data) {
    Write-Error 'S1 FAIL: no data field in response'
    exit 1
}
foreach ($field in @('activeCount','closedCount','expiredCount','totalCount')) {
    if ($null -eq $s1Data.$field) {
        Write-Error "S1 FAIL: missing field [$field]"
        exit 1
    }
}
Write-Output "S1 OK: active=$($s1Data.activeCount) closed=$($s1Data.closedCount) expired=$($s1Data.expiredCount) total=$($s1Data.totalCount)"
Write-Output ''

# ============================================================
# STEP S2: list (page 1, size 20)
# ============================================================
Write-Output '=== [S2] GET /api/admin/duty-leases ==='
$s2Resp = Invoke-Json -Method GET -Uri "$base/api/admin/duty-leases?page=1&size=20" -Headers @{ 'X-Admin-Token' = $adminToken }
Write-Output "HTTP $($s2Resp.Code)"
Write-Output "body length: $($s2Resp.Body.Length) chars"
if ($s2Resp.Code -ne 200) {
    Write-Error "S2 FAIL: HTTP $($s2Resp.Code), body=$($s2Resp.Body)"
    exit 1
}
$s2Json = $s2Resp.Body | ConvertFrom-Json
$s2Data = $s2Json.data
if (-not $s2Data) {
    Write-Error 'S2 FAIL: no data field'
    exit 1
}
foreach ($field in @('list','total','pages','current')) {
    if ($null -eq $s2Data.$field) {
        Write-Error "S2 FAIL: missing field [$field]"
        exit 1
    }
}
if ($s2Data.current -ne 1) {
    Write-Error "S2 FAIL: current != 1 (got $($s2Data.current))"
    exit 1
}
Write-Output "S2 OK: total=$($s2Data.total) pages=$($s2Data.pages) current=$($s2Data.current) rows=$(@($s2Data.list).Count)"
Write-Output ''

# ============================================================
# STEP S3: filter status=ACTIVE
# ============================================================
Write-Output '=== [S3] GET /api/admin/duty-leases?status=ACTIVE ==='
$s3Resp = Invoke-Json -Method GET -Uri "$base/api/admin/duty-leases?status=ACTIVE&page=1&size=20" -Headers @{ 'X-Admin-Token' = $adminToken }
if ($s3Resp.Code -ne 200) {
    Write-Error "S3 FAIL: HTTP $($s3Resp.Code), body=$($s3Resp.Body)"
    exit 1
}
$s3Json = $s3Resp.Body | ConvertFrom-Json
$s3Data = $s3Json.data
$s3List = @($s3Data.list)
$bad = @($s3List | Where-Object { $_.status -ne 'ACTIVE' })
if ($bad.Count -gt 0) {
    Write-Error "S3 FAIL: $($bad.Count) rows have status != ACTIVE (filter not effective)"
    exit 1
}
Write-Output "S3 OK: $($s3List.Count) rows, all status=ACTIVE"
Write-Output ''

# ============================================================
# STEP S4: V22 backfill effectiveness
#   - agent_command_outbox 中 status=1 (SENT) 行 last_sent_at IS NULL 数量
#   - agent_command_outbox 中 status=3 (CONFIRMED) 行 confirmed_at IS NULL 数量
#   - 两者均应为 0（V22 已 backfill）；允许数据库本身无该状态行（total=0）也算通过
# ============================================================
Write-Output '=== [S4] V22 backfill audit on agent_command_outbox ==='
$s4Sql = @"
SELECT
    (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 1 AND deleted = 0) AS sent_total,
    (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 1 AND deleted = 0 AND last_sent_at IS NULL) AS sent_null_last_sent,
    (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 3 AND deleted = 0) AS confirmed_total,
    (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 3 AND deleted = 0 AND confirmed_at IS NULL) AS confirmed_null_confirmed;
"@
$s4File = Join-Path $scriptDir 'verify-dashboard-duty-leases-s4.out'
$rc = Run-Psql -Sql $s4Sql -OutFile $s4File
if ($rc -ne 0) {
    Write-Error "S4 psql rc=$rc; see $s4File"
    exit 1
}
$s4Fields = Get-PsqlFields -Path $s4File
if (-not $s4Fields) {
    Write-Error "S4 FAIL: no fields parsed from $s4File"
    exit 1
}
Write-Output "S4 fields: $($s4Fields -join ' | ')"
$s4SentTotal          = [int]$s4Fields[0]
$s4SentNullLastSent   = [int]$s4Fields[1]
$s4ConfirmedTotal     = [int]$s4Fields[2]
$s4ConfirmedNull      = [int]$s4Fields[3]

if ($s4SentTotal -gt 0 -and $s4SentNullLastSent -ne 0) {
    Write-Error "S4 FAIL: $s4SentNullLastSent / $s4SentTotal SENT rows still have last_sent_at IS NULL (V22 backfill missing)"
    exit 1
}
if ($s4ConfirmedTotal -gt 0 -and $s4ConfirmedNull -ne 0) {
    Write-Error "S4 FAIL: $s4ConfirmedNull / $s4ConfirmedTotal CONFIRMED rows still have confirmed_at IS NULL (V22 backfill missing)"
    exit 1
}
Write-Output "S4 OK: sent_total=$s4SentTotal (null_last_sent=$s4SentNullLastSent), confirmed_total=$s4ConfirmedTotal (null_confirmed=$s4ConfirmedNull)"
Write-Output ''

# ============================================================
# done
# ============================================================
Write-Output 'ALL PASSED: S1 overview / S2 list / S3 status filter / S4 V22 backfill audit'
Write-Output "S4 out:    $s4File"