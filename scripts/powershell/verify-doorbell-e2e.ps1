# ============================================================
# helloai AgentHub V3 门铃通知通道 PR-3 real-env e2e
# Ref:
#   doc/HelloAI_门铃通知通道设计.md        (PR-3 值班鉴权收口 + 兜底验证)
#   doc/HelloAI_迭代执行记录.md            (AgentHub V3 门铃)
#   doc/HelloAI_实现差距表.md              (N13)
#   .agents/skills/helloai-preflight/SKILL.md  (规则 6：脚本 UTF-8 编码)
#
# 覆盖三个真实环境场景（IDEA 启动后端 + docker compose 起 postgres / redis / rabbitmq）：
#   S1  未在岗建连被拒     : 无 ACTIVE 租约时 GET /api/agents/doorbell/sse -> HTTP 500, body code=500
#   S2  在岗建连收握手     : INSERT 一条 ACTIVE 租约 -> SSE 建连收到 event:connected 握手帧
#   S3  离岗主动断门铃     : 把租约 expires_at 改到过去 -> 等 35s 让 DutyLeaseExpirationTask 翻 EXPIRED
#                            -> 发 DutyLeaseClosedEvent -> DoorbellDutyListener 主动 disconnect
#                            -> DB 校验 status=EXPIRED, close_reason=lease_expired；SSE 后台 job 结束(流被关闭)
#
# Pre-conditions:
#   - docker compose up -d (helloai-postgres:15432)
#   - helloai-start via IDEA @ :6565 with:
#       helloai.doorbell.enabled = true
#       helloai.job.enabled = true (DutyLeaseExpirationTask 需要 @Scheduled 启用)
#       redis 可达（DutyLeaseExpirationTask 用 Redis Lua 锁）
#
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-doorbell-e2e.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-doorbell-e2e.ps1 -Cleanup
# ============================================================

param(
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

$base        = 'http://localhost:6565'
# 脚本位于 scripts/powershell/，仓库根 = 脚本目录向上两级
$scriptDir   = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$sseFile     = Join-Path $scriptDir 'sse-doorbell-e2e.txt'

$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

$agentName   = 'doorbell-e2e-agent-v3'

Remove-Item $sseFile -ErrorAction SilentlyContinue

# ============================================================
# helper: HTTP JSON (StringContent w/o charset suffix, PS 5.1 safe)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 15
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne 'GET' -and $Method -ne 'DELETE') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if ($Method -eq 'GET')        { $resp = $client.GetAsync($Uri).Result }
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
# helper: start doorbell SSE long connection (background curl)
# ============================================================
function Start-DoorbellSse {
    param([string]$AbsFile, [string]$ApiKey)
    $job = Start-Job -ScriptBlock {
        param($f, $key)
        & curl.exe -s -i -N -H ('Authorization: Bearer ' + $key) `
            http://localhost:6565/api/agents/doorbell/sse *>&1 |
            Out-File -Encoding utf8 -FilePath $f
    } -ArgumentList $AbsFile, $ApiKey
    return $job
}

# clean all leases for the test agent, so it starts OFF-duty
function Clear-Leases {
    param([long]$AgentId)
    $sql = 'DELETE FROM agent_duty_lease WHERE agent_id = ' + $AgentId + ';'
    $out = Join-Path $scriptDir 'verify-doorbell-e2e-clear.out'
    Run-Psql -Sql $sql -OutFile $out | Out-Null
}

# ============================================================
# STEP 0: pre-flight
# ============================================================
Write-Output '=== [0] pre-flight ==='

$dockerCheck = & docker ps --format '{{.Names}}|{{.Status}}' --filter ('name=' + $pgContainer) 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match ($pgContainer + '\|Up'))) {
    Write-Error ('container [' + $pgContainer + '] is NOT up. Run: docker compose up -d')
    exit 1
}
Write-Output 'postgres container up'

try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync($base + '/api/health').Result
    Write-Output ('server ' + $base + ' HTTP ' + [int]$pingResp.StatusCode)
    $ping.Dispose()
} catch {
    Write-Error ('server NOT reachable at ' + $base + ' - start HelloAIApplication via IDEA first')
    exit 1
}
Write-Output ''

# ============================================================
# STEP A: admin login
# ============================================================
Write-Output '=== [A] admin login ==='
$loginResp = Invoke-Json -Method POST -Uri ($base + '/api/auth/login') -Body '{"type":"admin","username":"admin","credential":"admin123"}'
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error ('admin login failed: ' + $loginResp.Body)
    exit 1
}
Write-Output ('adminToken = ' + $adminToken.Substring(0, 16) + '...')
Write-Output ''

# ============================================================
# STEP B: create or reuse test agent
# ============================================================
Write-Output ('=== [B] create or reuse ' + $agentName + ' ===')
$lookupResp = Invoke-Json -Method GET -Uri ($base + '/api/admin/agents?pageSize=50') -Headers @{ 'X-Admin-Token' = $adminToken }
if ($lookupResp.Code -ne 200) {
    Write-Output ('lookup HTTP=' + $lookupResp.Code + ' body: ' + $lookupResp.Body)
    Write-Error 'admin agents list failed'
    exit 1
}
$lookupData = ($lookupResp.Body | ConvertFrom-Json).data
$agentId = $null
$agentApiKey = $null
if ($lookupData -and $lookupData.list) {
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
    if ($existing.Count -gt 0) {
        $agentId = $existing[0].id
        $agentApiKey = $existing[0].apiKey
        Write-Output ('reuse existing agentId=' + $agentId)
    }
}
if (-not $agentId) {
    $createBody = '{"name":"' + $agentName + '","role":"EXECUTOR","remark":"doorbell PR-3 e2e auto-created"}'
    $createResp = Invoke-Json -Method POST -Uri ($base + '/api/admin/agents') -Body $createBody -Headers @{ 'X-Admin-Token' = $adminToken }
    if ($createResp.Code -ne 200) {
        Write-Output ('create body: ' + $createResp.Body)
        Write-Error ('admin create agent HTTP ' + $createResp.Code)
        exit 1
    }
    $createJson = $createResp.Body | ConvertFrom-Json
    if ($createJson.code -ne 200) {
        Write-Error ('admin create agent biz-fail: code=' + $createJson.code + ' msg=' + $createJson.msg)
        exit 1
    }
    $agentId = $createJson.data.id
    $agentApiKey = $createJson.data.apiKey
    Write-Output ('created agentId=' + $agentId)
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Error 'agent create/lookup succeeded but apiKey empty'
    exit 1
}
Write-Output ('agentApiKey = ' + $agentApiKey.Substring(0, 16) + '...')
Write-Output ''

# ============================================================
# CLEANUP mode
# ============================================================
if ($Cleanup) {
    Write-Output '=== CLEANUP ==='
    Clear-Leases -AgentId $agentId
    Write-Output ('cleared leases for agentId=' + $agentId)
    Write-Output 'agent 保留（可下次复用）'
    exit 0
}

# ============================================================
# STEP S1: OFF-duty connect rejected
# ============================================================
Write-Output '=== [S1] OFF-duty doorbell connect must be rejected ==='
Clear-Leases -AgentId $agentId
$s1 = Invoke-Json -Method GET -Uri ($base + '/api/agents/doorbell/sse') -Headers @{ 'Authorization' = ('Bearer ' + $agentApiKey) } -TimeoutSec 8
Write-Output ('S1 HTTP=' + $s1.Code)
Write-Output ('S1 body=' + $s1.Body)
if ($s1.Code -ne 500) {
    Write-Error ('S1 FAIL: expected HTTP 500 when off-duty, got ' + $s1.Code)
    exit 1
}
if ($s1.Body -notmatch '"code"\s*:\s*500') {
    Write-Error 'S1 FAIL: response body missing code=500'
    exit 1
}
Write-Output 'S1 OK: off-duty connect rejected with code=500'
Write-Output ''

# ============================================================
# STEP S2: ON-duty connect receives connected handshake
# ============================================================
Write-Output '=== [S2] ON-duty doorbell connect receives connected handshake ==='
# 直接 INSERT 一条 ACTIVE 租约（expires 5 分钟后），令 isOnDuty=true
$leaseId = [long]([DateTimeOffset]::Now.ToUnixTimeMilliseconds() * 1000 + 13)
$s2Insert = @"
INSERT INTO agent_duty_lease
  (id, agent_id, session_id, work_mode, max_concurrent, status,
   started_at, last_renewed_at, expires_at,
   create_by, update_by, create_time, update_time, deleted, remark)
VALUES
  ($leaseId, $agentId, 'doorbell-e2e-lease', 'NORMAL', 3, 'ACTIVE',
   now(), now(), now() + interval '5 minutes',
   'e2e', 'e2e', now(), now(), 0, 'doorbell PR-3 e2e lease');
"@
$s2InsertFile = Join-Path $scriptDir 'verify-doorbell-e2e-s2-insert.out'
$rc = Run-Psql -Sql $s2Insert -OutFile $s2InsertFile
if ($rc -ne 0) { Write-Error ('S2 insert rc=' + $rc + '; see ' + $s2InsertFile); exit 1 }
Write-Output ('inserted ACTIVE lease id=' + $leaseId + ' (expires now+5min)')

$sseJob = Start-DoorbellSse -AbsFile $sseFile -ApiKey $agentApiKey
Start-Sleep -Seconds 4
$sseContent = ''
if (Test-Path $sseFile) { $sseContent = Get-Content $sseFile -Raw -ErrorAction SilentlyContinue }
Write-Output '--- SSE first frames ---'
Write-Output $sseContent
if ($sseContent -notmatch 'HTTP/1\.1 200') {
    Write-Error 'S2 FAIL: SSE connect did not return HTTP 200'
    Stop-Job $sseJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    exit 1
}
if ($sseContent -notmatch 'event:connected' -or $sseContent -notmatch '"type"\s*:\s*"connected"') {
    Write-Error 'S2 FAIL: connected handshake frame not received'
    Stop-Job $sseJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Output 'S2 OK: on-duty connect returned HTTP 200 + connected handshake'
Write-Output ''

# ============================================================
# STEP S3: lease expiration -> active disconnect
# ============================================================
Write-Output '=== [S3] lease expiration triggers active doorbell disconnect ==='
# 把租约 expires_at 改到过去（仍 ACTIVE），让 DutyLeaseExpirationTask 巡检时翻 EXPIRED
$s3Update = 'UPDATE agent_duty_lease SET expires_at = now() - interval ''1 minute'' ' +
            'WHERE id = ' + $leaseId + ' AND status = ''ACTIVE'';'
$s3UpdateFile = Join-Path $scriptDir 'verify-doorbell-e2e-s3-update.out'
$rc = Run-Psql -Sql $s3Update -OutFile $s3UpdateFile
if ($rc -ne 0) { Write-Error ('S3 update rc=' + $rc + '; see ' + $s3UpdateFile); exit 1 }
Write-Output 'set lease expires_at into the past; waiting 35s for DutyLeaseExpirationTask (fixedRate=30s)...'
Start-Sleep -Seconds 35

# 断言 1：DB 中租约翻为 EXPIRED
$s3Sql = @"
SELECT status, COALESCE(close_reason, '')
FROM agent_duty_lease
WHERE id = $leaseId AND deleted = 0;
"@
$s3File = Join-Path $scriptDir 'verify-doorbell-e2e-s3.out'
$rc = Run-Psql -Sql $s3Sql -OutFile $s3File
if ($rc -ne 0) { Write-Error ('S3 psql rc=' + $rc + '; see ' + $s3File); exit 1 }
$s3Fields = Get-PsqlFields -Path $s3File
if (-not $s3Fields) { Write-Error ('S3 FAIL: no lease row for id=' + $leaseId); exit 1 }
Write-Output ('S3 fields: ' + ($s3Fields -join ' | '))
if ($s3Fields[0] -ne 'EXPIRED')       { Write-Error ('S3 FAIL: status != EXPIRED (got ' + $s3Fields[0] + ')'); exit 1 }
if ($s3Fields[1] -ne 'lease_expired') { Write-Error ('S3 FAIL: close_reason != lease_expired (got ' + $s3Fields[1] + ')'); exit 1 }
Write-Output 'S3a OK: DutyLeaseExpirationTask flipped lease ACTIVE->EXPIRED (reason=lease_expired)'

# 断言 2：门铃 SSE 被主动断开——后台 curl job 结束（服务端 emitter.complete 关闭流）
$jobState = (Get-Job -Id $sseJob.Id).State
Write-Output ('S3 doorbell SSE job state = ' + $jobState)
if ($jobState -eq 'Running') {
    # 再宽限几秒（AFTER_COMMIT 事件 + 异步线程池调度存在轻微延迟）
    Start-Sleep -Seconds 5
    $jobState = (Get-Job -Id $sseJob.Id).State
    Write-Output ('S3 doorbell SSE job state (after grace) = ' + $jobState)
}
if ($jobState -eq 'Running') {
    Write-Error 'S3 FAIL: doorbell SSE still connected; expected active disconnect after lease expiration'
    Stop-Job $sseJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Output 'S3b OK: doorbell SSE actively disconnected after lease expiration'
Write-Output ''

# ============================================================
# teardown
# ============================================================
Write-Output '=== teardown ==='
try {
    Stop-Job $sseJob -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
} catch {}
Clear-Leases -AgentId $agentId
Write-Output ('SSE log: ' + $sseFile)
Write-Output ''
Write-Output 'ALL PASSED: S1 off-duty reject / S2 on-duty handshake / S3 expiration active-disconnect'
Write-Output 'reruns: pass -Cleanup first to clear leases, then rerun'
