# ============================================================
# helloai AgentHub V1 P0 (checkIn / checkOut / DutyLeaseExpiration) real-env e2e
# Ref:
#   doc/HelloAI_迭代执行记录.md  (AgentHub V1 P0)
#   doc/HelloAI_实现差距表.md   (N12)
#   .agents/skills/helloai-preflight/SKILL.md   (规则 6：脚本 UTF-8 编码)
#
# 覆盖三个真实环境场景（IDEA 启动后端 + docker compose 起 postgres / redis / rabbitmq）：
#   S1  MCP-over-SSE tools/call checkIn      -> agent_duty_lease 出现 status=ACTIVE 行
#   S2  MCP-over-SSE tools/call checkOut     -> 同一行翻为 CLOSED，close_reason 匹配
#   S3  手工 INSERT 一条 expire_time 已过期的 ACTIVE 租约（独立 test agent）
#        -> 等 35s，DutyLeaseExpirationTask (@Scheduled fixedRate=30s) 巡检
#        -> DB 校验 status='EXPIRED', close_reason='lease_expired'
#   S7  E1 动态 TTL 自适应（N12 A2 第 2 段）：checkIn 不带 ttlMinutes
#        S7.1 score=0（低表现）-> expire_time 距 now 约 min(5min) 短窗口
#        S7.2 score=100（高表现）-> expire_time 距 now 约 max(240min) 长窗口
#        S7.3 cleanup: checkOut + score 复位 0
#   S8  E2 并发额度预扣（N12 A2 第 3 段）：checkIn(maxConcurrent=1) 派发即占用
#        S8.1 checkIn(maxConcurrent=1) -> 租约 quota=1
#        S8.2 建 t1（白名单）自动派发选中 -> S8.3 建 t2 满额被跳过（保持 PENDING）
#        S8.4 submitResult 释放 -> 建 t3 重派成功 -> S8.5 并发建 t4/t5 在飞数 <=1
#        S8.6 cleanup checkOut + 任务级联删除
#        （前置条件：helloai.dispatch.auto-assign-on-create=true，脚本有行为自检）
#
# Pre-conditions:
#   - docker compose up -d (helloai-postgres:15432)
#   - helloai-start via IDEA @ :6565 with:
#       helloai.job.enabled = true (DutyLeaseExpirationTask 需要 @Scheduled 启用)
#       redis 可达（DutyLeaseExpirationTask 用 Redis Lua 锁）
#   - Flyway 已跑到 V21（agent_mcp_server 已 seed checkIn/checkOut）
#
# Usage (project root, PowerShell 5.1):
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-agenthub-duty-e2e.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-agenthub-duty-e2e.ps1 -Cleanup
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
$OutputEncoding           = $script:Utf8NoBom   # 关键：无 BOM，防止管道输出时添加 BOM

Add-Type -AssemblyName System.Net.Http

$base        = "http://localhost:6565"
# 脚本已迁至 scripts/powershell/，仓库根 = 脚本目录向上两级（保持 .out/.log 相对路径与迁移前一致）
$scriptDir   = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$sseFile     = Join-Path $scriptDir "sse-duty-e2e.txt"
$logFile     = Join-Path $scriptDir "verify-agenthub-duty-e2e.log"

$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

$agentName   = 'duty-e2e-agent-v1'

Remove-Item $sseFile -ErrorAction SilentlyContinue
Remove-Item $logFile -ErrorAction SilentlyContinue

# ============================================================
# helper: HTTP JSON (StringContent w/o charset suffix, PS 5.1 safe)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("GET","POST","PUT","DELETE")][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = "",
        [hashtable]$Headers = @{}
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne "GET") {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        if ($Method -eq "GET")        { $resp = $client.GetAsync($Uri).Result }
        elseif ($Method -eq "DELETE") {
            # S8: DELETE 带 body（任务级联删除需 confirmTitle）——SendAsync 保证跨 .NET 版本兼容
            if ($Body) {
                $req = [System.Net.Http.HttpRequestMessage]::new('DELETE', $Uri)
                $req.Content = $content
                $resp = $client.SendAsync($req).Result
            } else {
                $resp = $client.DeleteAsync($Uri).Result
            }
        }
        elseif ($Method -eq "POST")   { $resp = $client.PostAsync($Uri, $content).Result }
        elseif ($Method -eq "PUT")    { $resp = $client.PutAsync($Uri, $content).Result }
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
    # 去掉 BOM 头
    $Sql = $Sql.TrimStart([char]0xFEFF)
    $tmpSql = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmpSql, $Sql, $script:Utf8NoBom)
    Remove-Item $OutFile -ErrorAction SilentlyContinue

    $dockerArgs = @('exec', '-i', $pgContainer, 'psql',
        '-v', 'ON_ERROR_STOP=1',
        '-X', '-t', '-A', '-F', '|',
        '-U', $pgUser, '-d', $pgDb)

    # 读取 UTF‑8 文件并通过管道传给 docker（依赖脚本头设置的 $OutputEncoding = UTF8）
    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $output = $sqlContent | & docker @dockerArgs 2>&1
    $rc = $LASTEXITCODE

    # 将 stdout + stderr 写入输出文件
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
    # 返回单 string，避开 PS 5.1 函数 return 时单元素数组被 unroll 成 System.String 的坑。
    # 调用方拿到 string 后自己 .Split('|')，而 string.Split() 总是返回 String[]（不会 unroll）。
    return ($line -replace '<NULL>', '')
}

# ============================================================
# helper: MCP SSE start + call
# ============================================================
function Start-McpSse {
    param([string]$AbsFile)
    $job = Start-Job -ScriptBlock {
        param($f)
        & curl.exe -i -N http://localhost:6565/mcp/sse *>&1 |
            Out-File -Encoding utf8 -FilePath $f
    } -ArgumentList $AbsFile
    Start-Sleep -Seconds 3
    $content = ""
    if (Test-Path $AbsFile) { $content = Get-Content $AbsFile -Raw -ErrorAction SilentlyContinue }
    $m = [regex]::Match($content, 'sessionId=([A-Za-z0-9-]+)')
    $sid = if ($m.Success) { $m.Groups[1].Value } else { "" }
    return @{ Job = $job; SessionId = $sid }
}

function Send-Mcp {
    param([string]$Body, [string]$Label, [hashtable]$Headers = @{}, [string]$Sid)
    Write-Output "=== $Label ==="
    Write-Output "Body: $Body"
    $posBefore = (Get-Item $sseFile).Length
    $resp = Invoke-Json -Method POST -Uri "$base/mcp/messages?sessionId=$Sid" -Body $Body -Headers $Headers
    Write-Output "POST HTTP: $($resp.Code)"
    Write-Output "POST Body: $($resp.Body)"
    Start-Sleep -Seconds 2
    $posAfter = (Get-Item $sseFile).Length
    if ($posAfter -gt $posBefore) {
        $reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $reader.Position = $posBefore
        $sr = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
        $newContent = $sr.ReadToEnd()
        $sr.Close(); $reader.Close()
        Write-Output "--- SSE new content ($posBefore -> $posAfter) ---"
        Write-Output $newContent
    }
    Write-Output ""
    return $resp
}

# ============================================================
# STEP 0: pre-flight
# ============================================================
Write-Output "=== [0] pre-flight ==="

$dockerCheck = & docker ps --format "{{.Names}}|{{.Status}}" --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Error "container [$pgContainer] is NOT up. Run: docker compose up -d"
    exit 1
}
Write-Output "postgres container up"

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
Write-Output ""

# ============================================================
# STEP A: admin login
# ============================================================
Write-Output "=== [A] admin login ==="
$loginResp = Invoke-Json -Method POST -Uri "$base/api/auth/login" -Body '{"type":"admin","username":"admin","credential":"admin123"}'
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error "admin login failed: $($loginResp.Body)"
    exit 1
}
Write-Output "adminToken = $($adminToken.Substring(0, 16))..."
Write-Output ""

# ============================================================
# STEP B: create or reuse test agent
# ============================================================
Write-Output "=== [B] create or reuse $agentName ==="
$lookupResp = Invoke-Json -Method GET -Uri "$base/api/admin/agents/list?pageSize=50" -Headers @{ "X-Admin-Token" = $adminToken }
if ($lookupResp.Code -ne 200) {
    Write-Output "lookup HTTP=$($lookupResp.Code)"
    Write-Output "lookup body: $($lookupResp.Body)"
    Write-Error "admin agents list failed"
    exit 1
}
$lookupJson = $lookupResp.Body | ConvertFrom-Json
$lookupData = $lookupJson.data
$agentId = $null
$agentApiKey = $null
if ($lookupData -and $lookupData.list) {
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
    if ($existing.Count -gt 0) {
        $agentId = $existing[0].id
        $agentApiKey = $existing[0].apiKey
        Write-Output "reuse existing agentId=$agentId"
    }
}
if (-not $agentId) {
    $createBody = "{`"name`":`"$agentName`",`"role`":`"EXECUTOR`",`"remark`":`"AgentHub V1 duty e2e auto-created`"}"
    $createResp = Invoke-Json -Method POST -Uri "$base/api/admin/agents" -Body $createBody -Headers @{ "X-Admin-Token" = $adminToken }
    Write-Output "create HTTP=$($createResp.Code)"
    Write-Output "create body: $($createResp.Body)"
    if ($createResp.Code -ne 200) {
        Write-Error "admin create agent HTTP $($createResp.Code)"
        exit 1
    }
    $createJson = $createResp.Body | ConvertFrom-Json
    if ($createJson.code -ne 200) {
        Write-Error "admin create agent biz-fail: code=$($createJson.code) msg=$($createJson.msg)"
        exit 1
    }
    $agentData = $createJson.data
    $agentId = $agentData.id
    $agentApiKey = $agentData.apiKey
    Write-Output "created agentId=$agentId"
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Error "agent create/lookup succeeded but apiKey empty (check AgentRegistrationResponse mapping)"
    exit 1
}
Write-Output "agentApiKey = $($agentApiKey.Substring(0, 16))..."

# 确保 V21 seed 已生效；若手工建的 test agent 早于 V21 也补一次（幂等）
$seedSql = @"
INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT $agentId, tool.name, 1, 0, 'e2e', 'e2e'
FROM (VALUES ('checkIn'), ('checkOut')) AS tool(name)
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;
"@
$seedFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-seed.out"
$rc = Run-Psql -Sql $seedSql -OutFile $seedFile
if ($rc -ne 0) { Write-Warning "seed rc=$rc (可能列 ON CONFLICT 需 partial index，检查 $seedFile)" }
Write-Output ""

# Cleanup 模式：删掉本 agent 所有 lease + inbox，用于反复回归
if ($Cleanup) {
    Write-Output "=== CLEANUP ==="
    $cleanupSql = @"
DELETE FROM agent_duty_lease WHERE agent_id = $agentId;
DELETE FROM agent_inbox WHERE agent_id = $agentId;
"@
    $cleanupFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-cleanup.out"
    $rc = Run-Psql -Sql $cleanupSql -OutFile $cleanupFile
    Write-Output "cleanup rc=$rc, see $cleanupFile"
    Write-Output "agent保留（可下次复用）；如需彻底删除 agent 请走 admin UI"
    exit 0
}

# ============================================================
# STEP C: start SSE
# ============================================================
Write-Output "=== [C] start MCP SSE long connection ==="
$sseInfo = Start-McpSse -AbsFile $sseFile
$sid = $sseInfo.SessionId
if ([string]::IsNullOrEmpty($sid)) {
    Write-Error "sessionId extraction failed; see $sseFile"
    Stop-Job $sseInfo.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Output "sessionId = $sid"
Write-Output ""

# ============================================================
# STEP D: initialize
# ============================================================
Write-Output "=== [D] MCP initialize ==="
Send-Mcp -Sid $sid -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"duty-e2e","version":"1.0"}}}' -Label "initialize" -Headers @{ "X-Admin-Token" = $adminToken } | Out-Null
Send-Mcp -Sid $sid -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label "notifications/initialized" -Headers @{ "X-Admin-Token" = $adminToken } | Out-Null

# ============================================================
# STEP S1: checkIn -> lease ACTIVE
# ============================================================
Write-Output "=== [S1] tools/call checkIn (workMode=AUTO, maxConcurrent=3, ttlMinutes=5) ==="
$s1Body = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"AUTO","maxConcurrent":3,"ttlMinutes":5,"sessionId":"' + $sid + '"}}}'
$s1Resp = Send-Mcp -Sid $sid -Body $s1Body -Label "S1 checkIn" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

# Assert via DB
Write-Output "--- [S1] DB assertion ---"
$s1Sql = @"
SELECT status, work_mode, max_concurrent,
       (expire_time > now()) AS not_yet_expired,
       COALESCE(close_reason, '')
FROM agent_duty_lease
WHERE agent_id = $agentId AND status = 'ACTIVE' AND deleted = 0
ORDER BY id DESC LIMIT 1;
"@
$s1File = Join-Path $scriptDir "verify-agenthub-duty-e2e-s1.out"
$rc = Run-Psql -Sql $s1Sql -OutFile $s1File
if ($rc -ne 0) {
    Write-Error "S1 psql rc=$rc; see $s1File"
    Stop-Job $sseInfo.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    exit 1
}
$s1Line = Get-PsqlFields -Path $s1File
if (-not $s1Line) {
    Write-Error "S1 FAILED: no ACTIVE lease row found (see $s1File)"
    Stop-Job $sseInfo.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
    exit 1
}
$s1Fields = $s1Line.Split('|')
Write-Output "S1 fields: $($s1Fields -join ' | ')"
if ($s1Fields[0] -ne 'ACTIVE')       { Write-Error "S1 FAIL: status != ACTIVE"; exit 1 }
if ($s1Fields[1] -ne 'AUTO')         { Write-Error "S1 FAIL: work_mode != AUTO"; exit 1 }
if ($s1Fields[2] -ne '3')            { Write-Error "S1 FAIL: max_concurrent != 3"; exit 1 }
if ($s1Fields[3] -ne 't')            { Write-Error "S1 FAIL: lease already expired"; exit 1 }
Write-Output "S1 OK: checkIn -> lease ACTIVE, work_mode=AUTO, max_concurrent=3, not-yet-expired"
Write-Output ""

# ============================================================
# STEP S2: checkOut -> lease CLOSED
# ============================================================
Write-Output "=== [S2] tools/call checkOut (reason=e2e_test_close) ==="
$s2Body = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $agentId + ',"closeReason":"e2e_test_close","sessionId":"' + $sid + '"}}}'
$s2Resp = Send-Mcp -Sid $sid -Body $s2Body -Label "S2 checkOut" -Headers @{ "Authorization" = "Bearer $agentApiKey" }

Write-Output "--- [S2] DB assertion ---"
$s2Sql = @"
SELECT status, COALESCE(close_reason, '')
FROM agent_duty_lease
WHERE agent_id = $agentId AND deleted = 0
ORDER BY id DESC LIMIT 1;
"@
$s2File = Join-Path $scriptDir "verify-agenthub-duty-e2e-s2.out"
$rc = Run-Psql -Sql $s2Sql -OutFile $s2File
if ($rc -ne 0) { Write-Error "S2 psql rc=$rc; see $s2File"; exit 1 }
$s2Line = Get-PsqlFields -Path $s2File
if (-not $s2Line) { Write-Error "S2 FAIL: no lease row"; exit 1 }
$s2Fields = $s2Line.Split('|')
Write-Output "S2 fields: $($s2Fields -join ' | ')"
if ($s2Fields[0] -ne 'CLOSED')          { Write-Error "S2 FAIL: status != CLOSED"; exit 1 }
if ($s2Fields[1] -ne 'e2e_test_close')  { Write-Error "S2 FAIL: close_reason mismatch ($($s2Fields[1]))"; exit 1 }
Write-Output "S2 OK: checkOut -> lease CLOSED, close_reason=e2e_test_close"
Write-Output ""

# ============================================================
# STEP S3: Lease Expiration
#   - 直接 INSERT 一条 expire_time 已过期的 ACTIVE 租约
#   - 等 35s，让 DutyLeaseExpirationTask @Scheduled(fixedRate=30s) 至少跑 1 次
#   - 校验 status='EXPIRED', close_reason='lease_expired'
#
# 注意：uk_duty_lease_agent_active 保证同 agent 最多 1 条 ACTIVE，所以上面
#      S2 之后 agent 已无 ACTIVE 租约，此处 INSERT 安全。
# ============================================================
Write-Output "=== [S3] simulate expired ACTIVE lease and wait for DutyLeaseExpirationTask ==="
$leaseIdSeq = [long]([DateTimeOffset]::Now.ToUnixTimeMilliseconds() * 1000 + 7)
$s3InsertSql = @"
INSERT INTO agent_duty_lease
  (id, agent_id, session_id, work_mode, max_concurrent, status,
   start_time, last_renew_time, expire_time,
   create_by, update_by, create_time, update_time, deleted, remark)
VALUES
  ($leaseIdSeq, $agentId, 'e2e-expired-lease', 'AUTO', 3, 'ACTIVE',
   now() - interval '3 minutes',
   now() - interval '3 minutes',
   now() - interval '1 minute',
   'e2e', 'e2e', now(), now(), 0, 'DutyLeaseExpirationTask e2e input');
"@
$s3InsertFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-s3-insert.out"
$rc = Run-Psql -Sql $s3InsertSql -OutFile $s3InsertFile
if ($rc -ne 0) { Write-Error "S3 insert rc=$rc; see $s3InsertFile"; exit 1 }
Write-Output "inserted expired ACTIVE lease id=$leaseIdSeq (expire_time=now-1min)"

Write-Output "waiting 35s for DutyLeaseExpirationTask fixedRate=30s (may hit 1-2 ticks)..."
Start-Sleep -Seconds 35

$s3Sql = @"
SELECT status, COALESCE(close_reason, '')
FROM agent_duty_lease
WHERE id = $leaseIdSeq AND deleted = 0;
"@
$s3File = Join-Path $scriptDir "verify-agenthub-duty-e2e-s3.out"
$rc = Run-Psql -Sql $s3Sql -OutFile $s3File
if ($rc -ne 0) { Write-Error "S3 psql rc=$rc; see $s3File"; exit 1 }
$s3Line = Get-PsqlFields -Path $s3File
if (-not $s3Line) { Write-Error "S3 FAIL: no lease row found for id=$leaseIdSeq"; exit 1 }
$s3Fields = $s3Line.Split('|')
Write-Output "S3 fields: $($s3Fields -join ' | ')"
if ($s3Fields[0] -ne 'EXPIRED')       { Write-Error "S3 FAIL: status != EXPIRED (got $($s3Fields[0]))"; exit 1 }
if ($s3Fields[1] -ne 'lease_expired') { Write-Error "S3 FAIL: close_reason != lease_expired (got $($s3Fields[1]))"; exit 1 }
Write-Output "S3 OK: DutyLeaseExpirationTask flipped lease ACTIVE->EXPIRED with reason=lease_expired"
Write-Output ""

# ============================================================
# STEP S6: N12 P1 STRICT 独占报锁（AgentSelector 退出替补池）
#   - 创建独立 test agent（不复用主 agent，主 agent 已被 S2 签退）
#   - S6.1 workMode=STRICT checkIn -> DB 断言 work_mode=STRICT
#   - S6.2 workMode=strict（小写）checkIn -> DB 断言 work_mode=STRICT（大小写不敏感）
#   - S6.3 workMode=BOGUS_VALUE checkIn -> 断言 BizException 拒绝（不默默降级 AUTO）
#   - S6.4 cleanup checkOut
# ============================================================
Write-Output "=== [S6] N12 P1 STRICT 独占报锁 (AgentSelector pickAlternative 过滤验证) ==="

$strictAgentName = 'duty-e2e-strict-agent-v1'
$strictLookupResp = Invoke-Json -Method GET -Uri "$base/api/admin/agents/list?page=1&pageSize=200" -Headers @{ "X-Admin-Token" = $adminToken }
$strictLookupJson = $strictLookupResp.Body | ConvertFrom-Json
$strictAgentId = $null
$strictAgentApiKey = $null
if ($strictLookupJson -and $strictLookupJson.data -and $strictLookupJson.data.list) {
    $strictExisting = @($strictLookupJson.data.list | Where-Object { $_.name -eq $strictAgentName })
    if ($strictExisting.Count -gt 0) {
        $strictAgentId = $strictExisting[0].id
        $strictAgentApiKey = $strictExisting[0].apiKey
        Write-Output "S6 reuse existing strict agentId=$strictAgentId"
    }
}
if (-not $strictAgentId) {
    $strictCreateBody = "{`"name`":`"$strictAgentName`",`"role`":`"EXECUTOR`",`"remark`":`"AgentHub N12 P1 STRICT e2e auto-created`"}"
    $strictCreateResp = Invoke-Json -Method POST -Uri "$base/api/admin/agents" -Body $strictCreateBody -Headers @{ "X-Admin-Token" = $adminToken }
    if ($strictCreateResp.Code -ne 200) { Write-Error "S6 create agent HTTP $($strictCreateResp.Code)"; exit 1 }
    $strictCreateJson = $strictCreateResp.Body | ConvertFrom-Json
    if ($strictCreateJson.code -ne 200) { Write-Error "S6 create agent biz-fail: $($strictCreateJson.msg)"; exit 1 }
    $strictAgentData = $strictCreateJson.data
    $strictAgentId = $strictAgentData.id
    $strictAgentApiKey = $strictAgentData.apiKey
    Write-Output "S6 created strict agentId=$strictAgentId"
}

# seed checkIn / checkOut tool（幂等）
$strictSeedSql = @"
INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT $strictAgentId, tool.name, 1, 0, 'e2e', 'e2e'
FROM (VALUES ('checkIn'), ('checkOut')) AS tool(name)
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;
"@
$strictSeedFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-s6-seed.out"
$null = Run-Psql -Sql $strictSeedSql -OutFile $strictSeedFile

# 清理旧 lease（避免 uk_duty_lease_agent_active 冲突）
$strictCleanSql = "DELETE FROM agent_duty_lease WHERE agent_id = $strictAgentId;"
$null = Run-Psql -Sql $strictCleanSql -OutFile (Join-Path $scriptDir 'verify-agenthub-duty-e2e-s6-clean.out')

# ---------- S6.1 workMode=STRICT checkIn ----------
# MCP tools/call 返回 JSON-RPC 2.0 格式 ({jsonrpc,id,result:{content:[{type,text}]}})，
# 不业务包装的 {code,msg,data}。断言只用 HTTP 200 + DB 状态，不解析业务响应体。
$s61Body = '{"jsonrpc":"2.0","id":61,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $strictAgentId + ',"workMode":"STRICT","maxConcurrent":1,"ttlMinutes":5,"sessionId":"' + $sid + '"}}}'
$s61Resp = Send-Mcp -Sid $sid -Body $s61Body -Label "S6.1 checkIn STRICT" -Headers @{ "Authorization" = "Bearer $strictAgentApiKey" }
if ($s61Resp.Code -ne 200) {
    Write-Error ('S6.1 FAIL: HTTP=' + $s61Resp.Code + ' body=' + $s61Resp.Body)
    exit 1
}

$s61AssertSql = "SELECT status, work_mode FROM agent_duty_lease WHERE agent_id = $strictAgentId AND deleted = 0 ORDER BY id DESC LIMIT 1;"
$s61AssertFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-s6-1.out"
$rc = Run-Psql -Sql $s61AssertSql -OutFile $s61AssertFile
$s61Line = Get-PsqlFields -Path $s61AssertFile
if (-not $s61Line) { Write-Error 'S6.1 FAIL: no lease row'; exit 1 }
$s61Fields = $s61Line.Split('|')
if ($s61Fields[0] -ne 'ACTIVE') { Write-Error ('S6.1 FAIL: status != ACTIVE (got ' + $s61Fields[0] + ')'); exit 1 }
if ($s61Fields[1] -ne 'STRICT') { Write-Error ('S6.1 FAIL: work_mode != STRICT (got ' + $s61Fields[1] + ')'); exit 1 }
Write-Output 'S6.1 OK: checkIn(workMode=STRICT) -> DB status=ACTIVE, work_mode=STRICT'

# 签退为 S6.2 清理 ACTIVE（uk_duty_lease_agent_active 同一 agent 只 1 条）
$s61OutBody = '{"jsonrpc":"2.0","id":62,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $strictAgentId + ',"closeReason":"s6_1_cleanup","sessionId":"' + $sid + '"}}}'
$null = Send-Mcp -Sid $sid -Body $s61OutBody -Label "S6.1 checkOut cleanup" -Headers @{ "Authorization" = "Bearer $strictAgentApiKey" }

# ---------- S6.2 workMode=strict (lower-case) checkIn ----------
# 验证 strictParse 大小写不敏感。HTTP 200 + DB work_mode=STRICT 说明大小写不敏感成功。
$s62Body = '{"jsonrpc":"2.0","id":63,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $strictAgentId + ',"workMode":"strict","maxConcurrent":1,"ttlMinutes":5,"sessionId":"' + $sid + '"}}}'
$s62Resp = Send-Mcp -Sid $sid -Body $s62Body -Label "S6.2 checkIn strict lower" -Headers @{ "Authorization" = "Bearer $strictAgentApiKey" }
if ($s62Resp.Code -ne 200) {
    Write-Error ('S6.2 FAIL: HTTP=' + $s62Resp.Code + ' body=' + $s62Resp.Body)
    exit 1
}
$s62AssertSql = "SELECT work_mode FROM agent_duty_lease WHERE agent_id = $strictAgentId AND deleted = 0 ORDER BY id DESC LIMIT 1;"
$s62AssertFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-s6-2.out"
$rc = Run-Psql -Sql $s62AssertSql -OutFile $s62AssertFile
$s62Line = Get-PsqlFields -Path $s62AssertFile
if (-not $s62Line) { Write-Error 'S6.2 FAIL: no lease row'; exit 1 }
$s62Fields = $s62Line.Split('|')
if ($s62Fields[0] -ne 'STRICT') { Write-Error ('S6.2 FAIL: work_mode != STRICT (got ' + $s62Fields[0] + '), case-insensitive check failed'); exit 1 }
Write-Output 'S6.2 OK: checkIn(workMode=strict lower) -> DB work_mode=STRICT (case-insensitive)'

$s62OutBody = '{"jsonrpc":"2.0","id":64,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $strictAgentId + ',"closeReason":"s6_2_cleanup","sessionId":"' + $sid + '"}}}'
$null = Send-Mcp -Sid $sid -Body $s62OutBody -Label "S6.2 checkOut cleanup" -Headers @{ "Authorization" = "Bearer $strictAgentApiKey" }

# ---------- S6.3 workMode=BOGUS_VALUE checkIn -> 拒绝 ----------
# 验证 strictParse 拒绝非法值。成功标志：HTTP 仍 200（MCP tools/call 不会传 HTTP 非 200），
# 但 DB 中 lease 不增加（即不被 BizException 拒绝后不会落库）。
$s63BeforeCountSql = "SELECT COUNT(*) FROM agent_duty_lease WHERE agent_id = $strictAgentId AND deleted = 0;"
$s63BeforeCountFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-s6-3-before.out"
$rc = Run-Psql -Sql $s63BeforeCountSql -OutFile $s63BeforeCountFile
$s63BeforeCountLine = Get-PsqlFields -Path $s63BeforeCountFile
if (-not $s63BeforeCountLine) { Write-Error 'S6.3 FAIL: before count empty'; exit 1 }
$s63BeforeCount = $s63BeforeCountLine.Split('|')[0]

$s63Body = '{"jsonrpc":"2.0","id":65,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $strictAgentId + ',"workMode":"BOGUS_VALUE","maxConcurrent":1,"ttlMinutes":5,"sessionId":"' + $sid + '"}}}'
$s63Resp = Send-Mcp -Sid $sid -Body $s63Body -Label "S6.3 checkIn BOGUS_VALUE" -Headers @{ "Authorization" = "Bearer $strictAgentApiKey" }
if ($s63Resp.Code -ne 200) {
    Write-Error ('S6.3 FAIL: HTTP=' + $s63Resp.Code + ' body=' + $s63Resp.Body)
    exit 1
}

$s63AfterCountSql = "SELECT COUNT(*) FROM agent_duty_lease WHERE agent_id = $strictAgentId AND deleted = 0;"
$s63AfterCountFile = Join-Path $scriptDir "verify-agenthub-duty-e2e-s6-3-after.out"
$rc = Run-Psql -Sql $s63AfterCountSql -OutFile $s63AfterCountFile
$s63AfterCountLine = Get-PsqlFields -Path $s63AfterCountFile
if (-not $s63AfterCountLine) { Write-Error 'S6.3 FAIL: after count empty'; exit 1 }
$s63AfterCount = $s63AfterCountLine.Split('|')[0]
if ($s63AfterCount -ne $s63BeforeCount) {
    Write-Error ('S6.3 FAIL: BOGUS_VALUE was accepted and persisted (lease count from ' + $s63BeforeCount + ' to ' + $s63AfterCount + ')')
    exit 1
}
Write-Output ('S6.3 OK: workMode=BOGUS_VALUE was rejected by BizException, lease count unchanged (still ' + $s63AfterCount + ')')

# ---------- S6.4 清理（如果 S6.3 之前还有 ACTIVE 也兜底清一次） ----------
$s64CleanBody = '{"jsonrpc":"2.0","id":66,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $strictAgentId + ',"closeReason":"s6_final_cleanup","sessionId":"' + $sid + '"}}}'
$null = Send-Mcp -Sid $sid -Body $s64CleanBody -Label "S6.4 final checkOut" -Headers @{ "Authorization" = "Bearer $strictAgentApiKey" }
Write-Output 'S6 OK: STRICT persisted / case-insensitive / invalid-rejected three cases all green'
Write-Output ""

# ============================================================
# STEP S7: E1 动态 TTL 自适应（N12 A2 第 2 段）
#   - S7.0 score 复位 0（幂等起点）
#   - S7.1 score=0（低表现）checkIn 不带 ttlMinutes -> 约 min(5min) 短窗口
#   - S7.2 score=100（高表现）checkIn 不带 ttlMinutes -> 约 max(240min) 长窗口
#   - S7.3 cleanup: checkOut + score 复位 0
# 复用主 test agent（$agentId），与 S1-S3/S6 互不干扰（每步前清理 ACTIVE）
# ============================================================
Write-Output '=== [S7] E1 dynamic TTL (adaptive window by agent score) ==='

# ---------- S7.0 复位 score=0（幂等起点） ----------
$s70ScoreSql = "UPDATE agent SET score = 0 WHERE id = $agentId AND deleted = 0;"
$s70ScoreFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s7-0-score.out'
$null = Run-Psql -Sql $s70ScoreSql -OutFile $s70ScoreFile

# ---------- S7.1 低分 Agent：checkIn 不带 ttl -> 短窗口 ----------
$s71Body = '{"jsonrpc":"2.0","id":71,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"AUTO","maxConcurrent":1,"sessionId":"' + $sid + '"}}}'
$s71Resp = Send-Mcp -Sid $sid -Body $s71Body -Label 'S7.1 checkIn no-ttl low-score' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
if ($s71Resp.Code -ne 200) {
    Write-Error ('S7.1 FAIL: HTTP=' + $s71Resp.Code + ' body=' + $s71Resp.Body)
    exit 1
}
$s71TtlSql = "SELECT ROUND(EXTRACT(EPOCH FROM (expire_time - now())) / 60)::int FROM agent_duty_lease WHERE agent_id = $agentId AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;"
$s71TtlFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s7-1-ttl.out'
$rc = Run-Psql -Sql $s71TtlSql -OutFile $s71TtlFile
if ($rc -ne 0) { Write-Error ('S7.1 psql rc=' + $rc); exit 1 }
$s71Line = Get-PsqlFields -Path $s71TtlFile
if (-not $s71Line) { Write-Error 'S7.1 FAIL: no ACTIVE lease row'; exit 1 }
$s71Ttl = [int]$s71Line.Split('|')[0]
Write-Output ('S7.1 ttl-minutes=' + $s71Ttl)
if ($s71Ttl -lt 3 -or $s71Ttl -gt 8) { Write-Error ('S7.1 FAIL: expected ~5min window, got ' + $s71Ttl); exit 1 }
Write-Output 'S7.1 OK: score=0 checkIn -> short window (~5min)'

# ---------- S7.2 高分 Agent：score=100 -> checkIn 不带 ttl -> 长窗口 ----------
$s72ScoreSql = "UPDATE agent SET score = 100 WHERE id = $agentId AND deleted = 0;"
$s72ScoreFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s7-2-score.out'
$null = Run-Psql -Sql $s72ScoreSql -OutFile $s72ScoreFile
$s72OutBody = '{"jsonrpc":"2.0","id":72,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $agentId + ',"closeReason":"s7_before_recheckin","sessionId":"' + $sid + '"}}}'
$null = Send-Mcp -Sid $sid -Body $s72OutBody -Label 'S7.2 pre checkOut' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
$s72Body = '{"jsonrpc":"2.0","id":73,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"AUTO","maxConcurrent":1,"sessionId":"' + $sid + '"}}}'
$s72Resp = Send-Mcp -Sid $sid -Body $s72Body -Label 'S7.2 checkIn no-ttl high-score' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
if ($s72Resp.Code -ne 200) {
    Write-Error ('S7.2 FAIL: HTTP=' + $s72Resp.Code + ' body=' + $s72Resp.Body)
    exit 1
}
$s72TtlSql = "SELECT ROUND(EXTRACT(EPOCH FROM (expire_time - now())) / 60)::int FROM agent_duty_lease WHERE agent_id = $agentId AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;"
$s72TtlFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s7-2-ttl.out'
$rc = Run-Psql -Sql $s72TtlSql -OutFile $s72TtlFile
if ($rc -ne 0) { Write-Error ('S7.2 psql rc=' + $rc); exit 1 }
$s72Line = Get-PsqlFields -Path $s72TtlFile
if (-not $s72Line) { Write-Error 'S7.2 FAIL: no ACTIVE lease row'; exit 1 }
$s72Ttl = [int]$s72Line.Split('|')[0]
Write-Output ('S7.2 ttl-minutes=' + $s72Ttl)
if ($s72Ttl -lt 236 -or $s72Ttl -gt 244) { Write-Error ('S7.2 FAIL: expected ~240min window, got ' + $s72Ttl); exit 1 }
Write-Output 'S7.2 OK: score=100 checkIn -> long window (~240min)'

# ---------- S7.3 cleanup: checkOut + score 复位 0 ----------
$s73OutBody = '{"jsonrpc":"2.0","id":74,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $agentId + ',"closeReason":"s7_final_cleanup","sessionId":"' + $sid + '"}}}'
$null = Send-Mcp -Sid $sid -Body $s73OutBody -Label 'S7.3 final checkOut' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
$s73ScoreSql = "UPDATE agent SET score = 0 WHERE id = $agentId AND deleted = 0;"
$s73ScoreFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s7-3-score.out'
$null = Run-Psql -Sql $s73ScoreSql -OutFile $s73ScoreFile
Write-Output 'S7 OK: dynamic TTL by score (low ~5min / high ~240min) both green'
Write-Output ""

# ============================================================
# STEP S8: E2 并发额度预扣（N12 A2 第 3 段，§6.86）
#   - S8.0 残留清理：同名 task（e2e-quota-verify-task）残留先级联删除（幂等起点）
#   - S8.1 checkIn(maxConcurrent=1) -> DB 断言 ACTIVE + max_concurrent=1
#   - S8.2 建 task（agentPolicy.executorAgentIds=[本 agent] 白名单）+ t1
#         -> 自动派发 -> 断言 t1.assigned_agent_id=本 agent（额度内选中）
#   - S8.3 建 t2 -> 自动派发 -> 断言 t2 保持 PENDING（满额被选人链跳过，不超发）
#   - S8.4 submitResult(t1) 释放额度 -> 建 t3 -> 断言 t3.assigned_agent_id=本 agent（释放重派）
#   - S8.5 并发窗口：submitResult(t3) 释放 -> 并发建 t4/t5
#         -> 断言本 agent 在飞数 <= 1（FOR UPDATE 原子防线防并发超发）
#   - S8.6 cleanup: checkOut + DELETE task 级联删除
#
# 前置条件：helloai.dispatch.auto-assign-on-create=true（application.yml dispatch 段；
#           脚本行为自检：t1 创建后 2s 未派发 -> 报错提示改配置）
# 隔离策略：任务级 agentPolicy 白名单（V47）把选人限定在本 agent——环境里其他 ACTIVE
#           Agent 不参与，断言环境无关、可重复回归
# ============================================================
Write-Output '=== [S8] E2 concurrency quota (maxConcurrent=1 dispatch-occupies) ==='
$quotaTaskTitle = 'e2e-quota-verify-task'

# ---------- S8.0 残留清理（幂等起点） ----------
$s80FindSql = "SELECT id FROM task WHERE title = '$quotaTaskTitle' AND deleted = 0 LIMIT 1;"
$s80FindFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-0-find.out'
$rc = Run-Psql -Sql $s80FindSql -OutFile $s80FindFile
$s80Line = Get-PsqlFields -Path $s80FindFile
if ($s80Line -and $s80Line.Split('|')[0]) {
    $residualTaskId = $s80Line.Split('|')[0]
    Write-Output "S8.0 cleanup residual task id=$residualTaskId"
    $s80DelBody = "{`"confirmTitle`":`"$quotaTaskTitle`"}"
    $null = Invoke-Json -Method DELETE -Uri "$base/api/tasks/deleteById/$residualTaskId" -Body $s80DelBody -Headers @{ "X-Admin-Token" = $adminToken }
}

# ---------- S8.1 checkIn(maxConcurrent=1) ----------
$s81Body = '{"jsonrpc":"2.0","id":81,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"AUTO","maxConcurrent":1,"ttlMinutes":10,"sessionId":"' + $sid + '"}}}'
$s81Resp = Send-Mcp -Sid $sid -Body $s81Body -Label 'S8.1 checkIn maxConcurrent=1' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
if ($s81Resp.Code -ne 200) {
    Write-Error ('S8.1 FAIL: HTTP=' + $s81Resp.Code + ' body=' + $s81Resp.Body)
    exit 1
}
$s81AssertSql = "SELECT status, max_concurrent FROM agent_duty_lease WHERE agent_id = $agentId AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;"
$s81AssertFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-1.out'
$rc = Run-Psql -Sql $s81AssertSql -OutFile $s81AssertFile
$s81Line = Get-PsqlFields -Path $s81AssertFile
if (-not $s81Line) { Write-Error 'S8.1 FAIL: no ACTIVE lease row'; exit 1 }
$s81Fields = $s81Line.Split('|')
if ($s81Fields[0] -ne 'ACTIVE') { Write-Error ('S8.1 FAIL: status != ACTIVE (got ' + $s81Fields[0] + ')'); exit 1 }
if ($s81Fields[1] -ne '1') { Write-Error ('S8.1 FAIL: max_concurrent != 1 (got ' + $s81Fields[1] + ')'); exit 1 }
Write-Output 'S8.1 OK: checkIn(maxConcurrent=1) -> lease ACTIVE, quota=1'

# ---------- S8.2 建 task（白名单）+ t1 -> 自动派发选中 ----------
$s82TaskBody = '{"title":"' + $quotaTaskTitle + '","description":"E2 concurrency quota verify task","agentPolicy":{"executorAgentIds":[' + $agentId + ']}}'
$s82TaskResp = Invoke-Json -Method POST -Uri "$base/api/tasks" -Body $s82TaskBody -Headers @{ "X-Admin-Token" = $adminToken }
if ($s82TaskResp.Code -ne 200) { Write-Error ('S8.2 create task HTTP=' + $s82TaskResp.Code + ' body=' + $s82TaskResp.Body); exit 1 }
$s82TaskJson = $s82TaskResp.Body | ConvertFrom-Json
if ($s82TaskJson.code -ne 200) { Write-Error ('S8.2 create task biz-fail: ' + $s82TaskJson.msg); exit 1 }
$s82TaskId = $s82TaskJson.data.id
Write-Output ('S8.2 taskId=' + $s82TaskId)

$s82StBody = '{"taskId":' + $s82TaskId + ',"title":"e2e-quota-t1","description":"first task within quota","deliverable":"E2 quota proof"}'
$s82StResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks" -Body $s82StBody -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output ('S8.2 create t1 HTTP=' + $s82StResp.Code + ' body=' + $s82StResp.Body)
$s82StJson = $null
try { $s82StJson = $s82StResp.Body | ConvertFrom-Json } catch { }
$s82T1Id = if ($s82StJson -and $s82StJson.data.id) { $s82StJson.data.id } else { $null }
if (-not $s82T1Id) { Write-Error 'S8.2 FAIL: t1 id missing from create response'; exit 1 }
Start-Sleep -Seconds 2
$s82CheckSql = "SELECT status, COALESCE(assigned_agent_id::text, '0') FROM sub_task WHERE id = $s82T1Id AND deleted = 0;"
$s82CheckFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-2.out'
$rc = Run-Psql -Sql $s82CheckSql -OutFile $s82CheckFile
$s82Line = Get-PsqlFields -Path $s82CheckFile
if (-not $s82Line) { Write-Error 'S8.2 FAIL: t1 row missing'; exit 1 }
$s82Fields = $s82Line.Split('|')
if ($s82Fields[1] -ne "$agentId") {
    if ($s82Fields[1] -eq '0') {
        Write-Error 'S8.2 FAIL: t1 not dispatched (still PENDING). Check application.yml helloai.dispatch.auto-assign-on-create=true'
    } else {
        Write-Error ('S8.2 FAIL: t1 assigned to agent ' + $s82Fields[1] + ' (whitelist broken?)')
    }
    exit 1
}
Write-Output 'S8.2 OK: t1 auto-dispatched to whitelist agent (within quota)'

# ---------- S8.3 建 t2 -> 满额被选人链跳过（保持 PENDING，不超发） ----------
$s83StBody = '{"taskId":' + $s82TaskId + ',"title":"e2e-quota-t2","description":"second task over quota","deliverable":"E2 quota skip proof"}'
$s83StResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks" -Body $s83StBody -Headers @{ "X-Admin-Token" = $adminToken }
# 满额时 pickPreferred 白名单内无候选 -> BizException（HTTP 500/业务错误）；不依赖响应码，DB 断言为准
Write-Output ('S8.3 create t2 HTTP=' + $s83StResp.Code + ' body=' + $s83StResp.Body)
Start-Sleep -Seconds 2
$s83CheckSql = "SELECT status, COALESCE(assigned_agent_id::text, 'NULL') FROM sub_task WHERE title = 'e2e-quota-t2' AND task_id = $s82TaskId AND deleted = 0 LIMIT 1;"
$s83CheckFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-3.out'
$rc = Run-Psql -Sql $s83CheckSql -OutFile $s83CheckFile
$s83Line = Get-PsqlFields -Path $s83CheckFile
if (-not $s83Line) { Write-Error 'S8.3 FAIL: t2 row missing'; exit 1 }
$s83Fields = $s83Line.Split('|')
if ($s83Fields[1] -ne 'NULL') { Write-Error ('S8.3 FAIL: t2 was assigned to agent ' + $s83Fields[1] + ' while quota full (selector soft-filter broken)'); exit 1 }
Write-Output 'S8.3 OK: t2 skipped by selector while quota full (kept PENDING, no over-dispatch)'

# ---------- S8.4 submitResult(t1) 释放额度 -> 建 t3 -> 重派成功 ----------
$s84ResultId = 'e2e-quota-submit-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
$s84SrBody = '{"subTaskId":' + $s82T1Id + ',"success":true,"output":"e2e quota release proof","finishReason":"completed","resultId":"' + $s84ResultId + '"}'
$s84SrResp = Invoke-Json -Method POST -Uri "$base/api/mcp/tools/submitResult" -Body $s84SrBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output ('S8.4 submitResult HTTP=' + $s84SrResp.Code + ' body=' + $s84SrResp.Body)
if ($s84SrResp.Code -ne 200) { Write-Error 'S8.4 FAIL: submitResult HTTP != 200'; exit 1 }
$s84SrJson = $s84SrResp.Body | ConvertFrom-Json
if ($s84SrJson.code -ne 200 -or $s84SrJson.data.ok -ne $true) { Write-Error ('S8.4 FAIL: submitResult not accepted: ' + $s84SrResp.Body); exit 1 }
Start-Sleep -Seconds 1
# t1 已流转 REVIEW（不在占用口径 ASSIGNED/IN_PROGRESS/REWORK -> 额度释放）
$s84T1Sql = "SELECT status FROM sub_task WHERE id = $s82T1Id AND deleted = 0;"
$s84T1File = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-4-t1.out'
$rc = Run-Psql -Sql $s84T1Sql -OutFile $s84T1File
$s84T1Line = Get-PsqlFields -Path $s84T1File
if (-not $s84T1Line) { Write-Error 'S8.4 FAIL: t1 row missing'; exit 1 }
$s84T1Status = $s84T1Line.Split('|')[0]
Write-Output ('S8.4 t1 status=' + $s84T1Status)
if ($s84T1Status -notin @('REVIEW', 'DONE')) { Write-Error ('S8.4 FAIL: t1 not released after submit (status=' + $s84T1Status + ')'); exit 1 }

$s84StBody = '{"taskId":' + $s82TaskId + ',"title":"e2e-quota-t3","description":"third task after release","deliverable":"E2 quota release proof"}'
$s84StResp = Invoke-Json -Method POST -Uri "$base/api/sub-tasks" -Body $s84StBody -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output ('S8.4 create t3 HTTP=' + $s84StResp.Code)
Start-Sleep -Seconds 2
$s84CheckSql = "SELECT COALESCE(assigned_agent_id::text, 'NULL') FROM sub_task WHERE title = 'e2e-quota-t3' AND task_id = $s82TaskId AND deleted = 0 LIMIT 1;"
$s84CheckFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-4.out'
$rc = Run-Psql -Sql $s84CheckSql -OutFile $s84CheckFile
$s84Line = Get-PsqlFields -Path $s84CheckFile
if (-not $s84Line) { Write-Error 'S8.4 FAIL: t3 row missing'; exit 1 }
$s84Assigned = $s84Line.Split('|')[0]
if ($s84Assigned -ne "$agentId") { Write-Error ('S8.4 FAIL: t3 not re-dispatched after release (assigned=' + $s84Assigned + ')'); exit 1 }
Write-Output 'S8.4 OK: submit released quota -> t3 dispatched to same agent'

# ---------- S8.5 并发窗口：释放后并发建 t4/t5 -> 原子防线防超发 ----------
$s85T3Sql = "SELECT id FROM sub_task WHERE title = 'e2e-quota-t3' AND task_id = $s82TaskId AND deleted = 0 LIMIT 1;"
$s85T3File = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-5-t3id.out'
$rc = Run-Psql -Sql $s85T3Sql -OutFile $s85T3File
$s85T3Line = Get-PsqlFields -Path $s85T3File
if (-not $s85T3Line) { Write-Error 'S8.5 FAIL: t3 id missing'; exit 1 }
$s85T3Id = $s85T3Line.Split('|')[0]
$s85ResultId = 'e2e-quota-submit-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
$s85SrBody = '{"subTaskId":' + $s85T3Id + ',"success":true,"output":"e2e quota concurrency release","finishReason":"completed","resultId":"' + $s85ResultId + '"}'
$null = Invoke-Json -Method POST -Uri "$base/api/mcp/tools/submitResult" -Body $s85SrBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Start-Sleep -Seconds 1

$s85Body4 = '{"taskId":' + $s82TaskId + ',"title":"e2e-quota-t4","description":"concurrent a","deliverable":"E2 quota atomic proof"}'
$s85Body5 = '{"taskId":' + $s82TaskId + ',"title":"e2e-quota-t5","description":"concurrent b","deliverable":"E2 quota atomic proof"}'
$s85Url = "$base/api/sub-tasks"
# 两个 Start-Job 并发 POST：选人通过 vs 落库满额的冲突窗口由 agent 行锁串行化，
# 后到者在锁内 canAccept=false -> AgentUnavailableException -> 白名单内无替代 -> 冒泡 PENDING
$s85Job4 = Start-Job -ScriptBlock {
    param($u, $b, $token)
    $c = [System.Net.Http.HttpClient]::new()
    $c.Timeout = [TimeSpan]::FromSeconds(30)
    $c.DefaultRequestHeaders.Add('X-Admin-Token', $token) | Out-Null
    $content = [System.Net.Http.StringContent]::new($b, [System.Text.Encoding]::UTF8, 'application/json')
    $r = $c.PostAsync($u, $content).Result
    return ('code=' + [int]$r.StatusCode)
} -ArgumentList $s85Url, $s85Body4, $adminToken
$s85Job5 = Start-Job -ScriptBlock {
    param($u, $b, $token)
    $c = [System.Net.Http.HttpClient]::new()
    $c.Timeout = [TimeSpan]::FromSeconds(30)
    $c.DefaultRequestHeaders.Add('X-Admin-Token', $token) | Out-Null
    $content = [System.Net.Http.StringContent]::new($b, [System.Text.Encoding]::UTF8, 'application/json')
    $r = $c.PostAsync($u, $content).Result
    return ('code=' + [int]$r.StatusCode)
} -ArgumentList $s85Url, $s85Body5, $adminToken
$s85R4 = Receive-Job -Wait $s85Job4 -AutoRemoveJob
$s85R5 = Receive-Job -Wait $s85Job5 -AutoRemoveJob
Write-Output ('S8.5 concurrent create t4/t5: ' + $s85R4 + ' / ' + $s85R5)
Start-Sleep -Seconds 3
$s85CheckSql = "SELECT COUNT(*) FROM sub_task WHERE assigned_agent_id = $agentId AND status IN ('ASSIGNED','IN_PROGRESS','REWORK') AND deleted = 0;"
$s85CheckFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-5.out'
$rc = Run-Psql -Sql $s85CheckSql -OutFile $s85CheckFile
$s85Line = Get-PsqlFields -Path $s85CheckFile
if (-not $s85Line) { Write-Error 'S8.5 FAIL: in-flight count query empty'; exit 1 }
$s85InFlight = $s85Line.Split('|')[0]
if ([int]$s85InFlight -gt 1) { Write-Error ('S8.5 FAIL: in-flight count ' + $s85InFlight + ' > quota 1 (atomic guard broken)'); exit 1 }
Write-Output ('S8.5 OK: concurrent dispatch kept in-flight count at ' + $s85InFlight + ' (<= quota 1)')

# ---------- S8.6 cleanup: checkOut + 任务级联删除 ----------
$s86OutBody = '{"jsonrpc":"2.0","id":86,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":' + $agentId + ',"closeReason":"s8_final_cleanup","sessionId":"' + $sid + '"}}}'
$null = Send-Mcp -Sid $sid -Body $s86OutBody -Label 'S8.6 final checkOut' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
$s86DelBody = "{`"confirmTitle`":`"$quotaTaskTitle`"}"
$s86DelResp = Invoke-Json -Method DELETE -Uri "$base/api/tasks/deleteById/$s82TaskId" -Body $s86DelBody -Headers @{ "X-Admin-Token" = $adminToken }
Write-Output ('S8.6 delete task HTTP=' + $s86DelResp.Code + ' body=' + $s86DelResp.Body)
$s86VerifySql = "SELECT COUNT(*) FROM sub_task WHERE task_id = $s82TaskId AND deleted = 0;"
$s86VerifyFile = Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-6.out'
$rc = Run-Psql -Sql $s86VerifySql -OutFile $s86VerifyFile
$s86Line = Get-PsqlFields -Path $s86VerifyFile
if (-not $s86Line) { Write-Error 'S8.6 FAIL: verify query empty'; exit 1 }
$s86Left = $s86Line.Split('|')[0]
if ($s86Left -ne '0') { Write-Error ('S8.6 FAIL: residual sub_tasks ' + $s86Left + ' after cascade delete'); exit 1 }
Write-Output 'S8.6 OK: checkOut + task cascade delete (zero residual sub_tasks)'
Write-Output 'S8 OK: E2 concurrency quota all green (dispatch-occupies / soft-skip / release / atomic-guard)'
Write-Output ""

# ============================================================
# Cleanup SSE job
# ============================================================
Write-Output "=== teardown ==="
try {
    Stop-Job $sseInfo.Job -PassThru | Remove-Job -Force -ErrorAction SilentlyContinue
} catch {}
Write-Output "SSE log:    $sseFile"
Write-Output "S1 out:     $(Join-Path $scriptDir 'verify-agenthub-duty-e2e-s1.out')"
Write-Output "S2 out:     $(Join-Path $scriptDir 'verify-agenthub-duty-e2e-s2.out')"
Write-Output "S3 out:     $(Join-Path $scriptDir 'verify-agenthub-duty-e2e-s3.out')"
Write-Output "S6 out:     $(Join-Path $scriptDir 'verify-agenthub-duty-e2e-s6-*.out')"
Write-Output "S7 out:     $(Join-Path $scriptDir 'verify-agenthub-duty-e2e-s7-*.out')"
Write-Output "S8 out:     $(Join-Path $scriptDir 'verify-agenthub-duty-e2e-s8-*.out')"
Write-Output ""
Write-Output "ALL PASSED: S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / S6 N12-P1 STRICT / S7 E1 dynamic TTL / S8 E2 concurrency quota"
Write-Output "如需反复回归，可先跑 -Cleanup 清空 lease/inbox，再重跑此脚本"
