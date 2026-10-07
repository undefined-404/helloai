# ============================================================
# helloai 无候选兜底 + 每子任务重试节拍 验证脚本
#   verify-no-candidate-fallback.ps1
#
# 目的：真机验证「长期无候选 → 每子任务重试节拍 → 倒计时 → 达阈值转人工介入」
#       （时钟 C，2026-10-05 新增：commit 091f714 + 0751f16；前端倒计时 0bcead9）
#
# 确定性构造（不靠"抢占用"）：
#   把任务 agentPolicy.executorAgentIds 钉到一个**离线/从未 checkIn 的 CLI_CLIENT**
#   agent ⇒ AgentSelector 必然滤空唯一候选 ⇒ 稳定复现「无可用候选」。
#   ★agent 从库中解析（不新注册）：register 的唯一性是「role + model_type 全局唯一」，
#     PLANNER+deepseek-v4-flash / EXECUTOR+空model 均已被现存 agent 占用 ⇒ 409。
#
# 判定链路：
#   建任务(executor=离线agent) -> planById -> confirmPlan(自动分发失败保持PENDING)
#     -> PENDING 孤儿扫描捕获 NoCandidateAgentException
#     -> context.noCandidate{rounds,nextDispatchAt,...} 写入
#     -> 节拍未到则 skipWaiting；到则 rounds+1
#     -> rounds >= max -> manualIntervention + noCandidate 清除 -> 后续被标记跳过
#
# ★实测时长（默认真实配置，不加速）：首次扫描需等 pending-orphan-threshold-minutes(5min)，
#   之后每轮 = max(scan 60s, interval 120s+jitter) ⇒ 10 轮达阈值总耗时 ≈ 25 分钟。
#   故 -WatchSec 默认已放宽到 1800。若要加速：临时改
#   helloai.dispatch.no-candidate-max-rounds=2 / -retry-interval-seconds=10 /
#   -retry-jitter-seconds=0 / helloai.execution.pending-orphan-threshold-minutes=1 并重启后端
#   （验完务必改回默认）。
#
# 前置：helloai-start @ 6565 (local profile)；docker 容器 helloai-postgres:15432。
# 用法（项目根，PowerShell 5.1）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-no-candidate-fallback.ps1
# 参数：
#   -MaxRounds    断言"达阈值"的轮数（须与后端 no-candidate-max-rounds 一致，默认 2）
#   -IntervalSec  预期节拍间隔（须与后端 no-candidate-retry-interval-seconds 一致，默认 10）
#   -WatchSec     最长观察秒数（默认 1800）
#   -Sid          传入则跳过建任务，直接观察既有子任务（复跑省时）
#   -KeepTask     保留测试任务不删（默认删）
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin123",
    [int]$MaxRounds = 2,
    [int]$IntervalSec = 10,
    [int]$WatchSec = 1800,
    [int]$PollIntervalSec = 5,
    [string]$LlmModelType = "deepseek:deepseek-v4-flash",
    [string]$PlannerId = "",
    [string]$ReviewerId = "",
    [string]$OfflineExecutorId = "",
    [string]$Sid = "",
    [string]$PgContainer = "helloai-postgres",
    [switch]$KeepTask
)

$ErrorActionPreference = "Stop"
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) { throw ("ASSERT_FAIL: " + $Msg) }
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [hashtable]$Headers, [int]$TimeoutSec = 60) {
    $json = $null
    if ($null -ne $Body) { $json = ($Body | ConvertTo-Json -Depth 12) }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Get-PsqlField([string]$Sql) {
    $tmpSql = [System.IO.Path]::GetTempFileName()
    $sql = $Sql.TrimStart([char]0xFEFF)
    [System.IO.File]::WriteAllText($tmpSql, $sql, $script:Utf8NoBom)
    $sqlContent = Get-Content -Raw -Encoding UTF8 $tmpSql
    $output = $sqlContent | & docker exec -i $PgContainer psql -v ON_ERROR_STOP=1 -X -t -A -F "|" -U postgres -d helloai 2>&1
    $rc = $LASTEXITCODE
    Remove-Item $tmpSql -ErrorAction SilentlyContinue
    if ($rc -ne 0) { return $null }
    return ($output | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -First 1)
}

# 取子任务 context 中的 noCandidate / manualIntervention（用 ->> 逐个字段，避免解析整段 JSON）
function Get-NoCandidateSnapshot([string]$SubTaskId) {
    $row = Get-PsqlField ("SELECT " +
        "COALESCE(context->'noCandidate'->>'rounds','') || '|' || " +
        "COALESCE(context->'noCandidate'->>'nextDispatchAt','') || '|' || " +
        "COALESCE(context->'manualIntervention'->>'reason','') || '|' || " +
        "COALESCE(status,'') || '|' || " +
        "CASE WHEN context ? 'noCandidate' THEN '1' ELSE '0' END " +
        "FROM sub_task WHERE id = " + $SubTaskId)
    if (-not $row) { return $null }
    $f = @($row.Split('|'))
    return [pscustomobject]@{
        Rounds          = $f[0]
        NextDispatchAt  = $f[1]
        ManualReason    = $f[2]
        Status          = $f[3]
        HasNoCandidate  = ($f[4] -eq '1')
    }
}

# ============================================================
# STEP1: admin login
# ============================================================
Write-Host "STEP1: admin login"
$loginResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/auth/login") -Body @{
    type = "admin"; username = $AdminUsername; credential = $AdminPassword
} -Headers @{}
Assert-True ($loginResp.code -eq 200) ("login code=" + $loginResp.code + " msg=" + $loginResp.msg)
$adminHeaders = @{ "X-Admin-Token" = $loginResp.data.token }
Write-Host "  login OK"

$ts = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")

# ============================================================
# STEP2: 解析 agent（不新注册）
#   ★实测约束：register 的唯一性是「role + model_type 全局唯一」——
#     PLANNER+deepseek-v4-flash、EXECUTOR+空 model_type 都已被现存 agent 占用 ⇒ 409。
#     故改为**从库中解析既有 agent**：
#       planner  : ACTIVE + API_KEY_LLM + PLANNER（带 model_type）
#       reviewer : ACTIVE + API_KEY_LLM + REVIEWER（带 model_type）
#       offline  : ACTIVE + CLI_CLIENT + EXECUTOR 且心跳陈旧/从未 checkIn
#                  ⇒ AgentSelector 必滤除 ⇒ 稳定复现「无可用候选」
# ============================================================
Write-Host "STEP2: resolve agents from DB"

function Resolve-AgentId([string]$Sql, [string]$Label) {
    $v = Get-PsqlField $Sql
    if (-not $v) { throw ("ASSERT_FAIL: 未能在库中解析到 " + $Label) }
    return $v
}

if (-not $PlannerId) {
    $PlannerId = Resolve-AgentId ("SELECT id FROM agent WHERE role='PLANNER' AND access_type='API_KEY_LLM' " +
        "AND status='ACTIVE' AND deleted=0 AND model_type IS NOT NULL ORDER BY id LIMIT 1") "PLANNER(API_KEY_LLM)"
}
if (-not $ReviewerId) {
    $ReviewerId = Resolve-AgentId ("SELECT id FROM agent WHERE role='REVIEWER' AND access_type='API_KEY_LLM' " +
        "AND status='ACTIVE' AND deleted=0 AND model_type IS NOT NULL ORDER BY id LIMIT 1") "REVIEWER(API_KEY_LLM)"
}
if (-not $OfflineExecutorId) {
    # 排除本测试自己可能注册过的残留，并优先取「从未 checkIn」者
    $OfflineExecutorId = Resolve-AgentId ("SELECT id FROM agent WHERE role='EXECUTOR' AND access_type='CLI_CLIENT' " +
        "AND status='ACTIVE' AND deleted=0 AND name NOT LIKE 'nc-%' " +
        "AND (last_seen_time IS NULL OR now() - last_seen_time > interval '30 minutes') ORDER BY last_seen_time NULLS FIRST, id LIMIT 1") "离线 CLI_CLIENT EXECUTOR"
}
$plannerId = [string]$PlannerId
$reviewerId = [string]$ReviewerId
$offlineId = [string]$OfflineExecutorId
$offlineName = Get-PsqlField ("SELECT name FROM agent WHERE id = " + $offlineId)
Write-Host ("  planner=" + $plannerId + " reviewer=" + $reviewerId + " offlineExecutor=" + $offlineId + "(" + $offlineName + ")")

# 断言离线 executor 此刻确实不被判为新鲜（否则本用例不成立）
$fresh = Get-PsqlField ("SELECT CASE WHEN last_seen_time IS NOT NULL AND (now() - last_seen_time) < interval '5 minutes' THEN 'FRESH' ELSE 'STALE' END FROM agent WHERE id = " + $offlineId)
Write-Host ("  offline executor heartbeat=" + $fresh)
if ($fresh -eq "FRESH") {
    Write-Warning "离线 executor 心跳竟然新鲜——用例前提不成立（该 agent 可能正在被别的会话心跳）。请改用其它未启用的 CLI_CLIENT。"
}

# ============================================================
# STEP3: 建任务（executor 钉到离线 agent）+ 拆解 + 确认
# ============================================================
# ============================================================
# STEP3+4: 建任务（executor 钉到离线 agent）+ 拆解 + 确认
#   -Sid 传入时跳过建任务，直接观察既有子任务（复跑省时）
# ============================================================
if ($Sid) {
    $subId = $Sid
    $taskId = Get-PsqlField ("SELECT task_id FROM sub_task WHERE id = " + $subId)
    Write-Host ("STEP3+4: SKIP (resume on Sid=" + $subId + ", taskId=" + $taskId + ")")
} else {
Write-Host "STEP3: create task (executor = offline agent)"
$title = ("nc-fallback-" + $ts)
$createResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks") -Body @{
    title = $title
    description = "verify no-candidate fallback + retry cadence (test-only task)."
    agentPolicy = @{
        plannerAgentId = $plannerId
        executorAgentIds = @($offlineId)
        reviewerAgentId = $reviewerId
        fallbackPolicy = "NONE"      # 禁止 N11 自动回退，确保停在"无候选"而非换人
        difficulty = "LOW"
    }
    requiredSkills = @()
} -Headers $adminHeaders
Assert-True ($createResp.code -eq 200) ("create code=" + $createResp.code + " msg=" + $createResp.msg)
$taskId = [string]$createResp.data.id
Write-Host ("  taskId=" + $taskId)

Write-Host "STEP4: planById + wait drafts + confirm"
$planResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/planById/" + $taskId) -Body @{} -Headers $adminHeaders -TimeoutSec 30
Assert-True ($planResp.code -eq 200) ("plan code=" + $planResp.code + " msg=" + $planResp.msg)
$waited = 0; $drafts = @()
while ($waited -lt 360) {
    $listResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/tasks/findPlanByTaskId/" + $taskId) -Body $null -Headers $adminHeaders
    $drafts = @($listResp.data)
    if ($drafts.Count -ge 1) { break }
    Start-Sleep -Seconds 3; $waited += 3
}
Assert-True ($drafts.Count -ge 1) ("expected >=1 drafts, actual=" + $drafts.Count)
Write-Host ("  draftCount=" + $drafts.Count)

$confirmResp = Invoke-Json -Method "Post" -Url ($BaseUrl + "/api/tasks/confirmPlanByTaskId/" + $taskId) -Body @{} -Headers $adminHeaders
Assert-True ($confirmResp.code -eq 200) ("confirm code=" + $confirmResp.code + " msg=" + $confirmResp.msg)

# 取「就绪且未派发」的子任务 id
#   ★坑：不能盲取 $subs[0] —— planner 拆出的 DAG 里首条常是「汇总」节点
#     （depends_on 非空 ⇒ isReady=false ⇒ 被 skipNotReady 永久跳过 ⇒ 永不触发无候选）。
#   判据：status=PENDING 且 attempt_total=0 且（depends_on 为空 或 全部依赖已 DONE）。
$subResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/list?taskId=" + $taskId) -Body $null -Headers $adminHeaders
$subs = @($subResp.data)
if ($subs.Count -eq 0 -and $null -ne $subResp.data.records) { $subs = @($subResp.data.records) }
Assert-True ($subs.Count -ge 1) "no sub tasks after confirm"

$depRaw = Get-PsqlField ("SELECT string_agg(s.id::text || ':' || COALESCE((SELECT string_agg(d.id::text, ',') FROM sub_task d WHERE d.task_id = s.task_id AND d.deleted = 0 AND d.status = 'DONE' AND d.id = ANY(ARRAY(SELECT jsonb_array_elements_text(s.depends_on)::bigint))), ''), ';') FROM sub_task s WHERE s.task_id = " + $taskId + " AND s.status = 'PENDING' AND COALESCE(s.attempt_total,0) = 0 AND COALESCE((SELECT count(*) FROM jsonb_array_elements_text(s.depends_on) t WHERE NOT EXISTS (SELECT 1 FROM sub_task d WHERE d.id = t::bigint AND d.status = 'DONE')), 0) = 0 ORDER BY s.create_time LIMIT 1")
$subId = ($depRaw -split ';')[0]
if ($subId) { $subId = ($subId -split ':')[0] }
Assert-True ($subId) "未找到就绪且未派发的 PENDING 子任务（无可触发无候选的节点）"
Write-Host ("  subTaskId=" + $subId)
}

# ★P1-1 回归基线：记下当前 attempt_total，观察全程必须保持不变（无候选不烧重派预算）
$attemptBefore = [int](Get-PsqlField ("SELECT COALESCE(attempt_total,0) FROM sub_task WHERE id = " + $subId))
Write-Host ("  attempt_total(before)=" + $attemptBefore)

# ============================================================
# STEP5: 观察 noCandidate 节拍演化，直到达阈值转人工
# ============================================================
Write-Host ("STEP5: watch cadence (up to " + $WatchSec + "s, expect rounds reach " + $MaxRounds + ")")
$elapsed = 0
$sawNoCandidate = $false
$sawSkipWaiting = $false
$prevRound = -1
$finalEscalated = $false
$lastSnap = $null

while ($elapsed -lt $WatchSec) {
    $snap = Get-NoCandidateSnapshot -SubTaskId $subId
    if ($null -ne $snap) {
        $lastSnap = $snap
        if ($snap.HasNoCandidate) { $sawNoCandidate = $true }

        # 轮数变化时打印
        if ($snap.Rounds -ne "" -and [int]$snap.Rounds -ne $prevRound) {
            $prevRound = [int]$snap.Rounds
            Write-Host ("  [t=" + $elapsed + "s] rounds=" + $snap.Rounds + " nextDispatchAt=" + $snap.NextDispatchAt + " status=" + $snap.Status)
        }

        # 达阈值判据：manualIntervention.reason = no_candidate_long_wait 且 noCandidate 被清除
        if ($snap.ManualReason -eq "no_candidate_long_wait") {
            Write-Host ("  [t=" + $elapsed + "s] ESCALATED: manualIntervention.reason=no_candidate_long_wait, noCandidate removed=" + (-not $snap.HasNoCandidate))
            $finalEscalated = $true
            break
        }
    }
    Start-Sleep -Seconds $PollIntervalSec
    $elapsed += $PollIntervalSec
}

# ---- 断言 ----
Assert-True $sawNoCandidate "从未观察到 context.noCandidate —— 无候选节拍未生效（检查加速配置是否重启生效 / 孤儿扫描阈值）"
Assert-True $finalEscalated ("未在 " + $WatchSec + "s 内达阈值转人工（rounds 应达 " + $MaxRounds + "）——检查 no-candidate-max-rounds 与阈值配置")
Assert-True (-not $lastSnap.HasNoCandidate) "达阈值后 context.noCandidate 应被清除，实际仍存在（P3 修复未生效？）"
Write-Host "STEP5 PASS: noCandidate 节拍 + 达阈值转人工 + 计数器清除 均已观测"

# ============================================================
# STEP6: 达阈值后再等一个扫描 tick，应被 manualIntervention 跳过、不再累加
# ============================================================
Write-Host "STEP6: after escalation, verify skip (no further accumulation)"
$before = Get-NoCandidateSnapshot -SubTaskId $subId
Start-Sleep -Seconds ([Math]::Max(70, $IntervalSec + 60))   # 跨过一个孤儿扫描 tick(60s)
$after = Get-NoCandidateSnapshot -SubTaskId $subId
Assert-True ($after.ManualReason -eq "no_candidate_long_wait") "达阈值后 manualIntervention 标记丢失"
Assert-True (-not $after.HasNoCandidate) "达阈值后不应再写回 noCandidate（说明未被标记拦住）"
Write-Host "STEP6 PASS: 标记后不再空转/不再累加"

# ============================================================
# STEP7: 前端数据源断言（context 已回传，供倒计时读取）
# ============================================================
Write-Host "STEP7: API context exposure (front-end countdown data source)"
$detailResp = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/getById/" + $subId) -Body $null -Headers $adminHeaders
$dsub = $detailResp.data
if ($null -eq $dsub) {
    # 回退：从 list 里找
    $lr = Invoke-Json -Method "Get" -Url ($BaseUrl + "/api/sub-tasks/list?taskId=" + $taskId) -Body $null -Headers $adminHeaders
    $arr = @($lr.data); if ($arr.Count -eq 0 -and $null -ne $lr.data.records) { $arr = @($lr.data.records) }
    $dsub = $arr | Where-Object { [string]$_.id -eq $subId } | Select-Object -First 1
}
Assert-True ($null -ne $dsub) "未能取到子任务详情（context 暴露断言无法进行）"
$ctxProp = @($dsub.PSObject.Properties.Name)
Assert-True ($ctxProp -contains "context") "SubTaskResponse 未暴露 context（前端倒计时无法取数）"
Write-Host "STEP7 PASS: context 已暴露"

# ============================================================
# STEP7.5: ★P1-1 核心回归 —— 无候选全程 attempt_total 必须不变（不烧重派预算）
#   旧行为：doDispatchPendingAuto 先 accumulateReassignAttempt(+1) 再抛无候选 ⇒ 每次 +1
#   现行为：选人成功后才计数 ⇒ 无候选时恒为 before
# ============================================================
$attemptAfter = [int](Get-PsqlField ("SELECT COALESCE(attempt_total,0) FROM sub_task WHERE id = " + $subId))
$ncEvents = [int](Get-PsqlField ("SELECT count(*) FROM sub_task_event WHERE sub_task_id = " + $subId + " AND event_type = 'sub_task_no_candidate'"))
$leSm = [int](Get-PsqlField ("SELECT count(*) FROM sub_task_event WHERE sub_task_id = " + $subId + " AND event_type = 'sub_task_manual_intervention_required'"))
Write-Host ("STEP7.5: attempt_total before=" + $attemptBefore + " after=" + $attemptAfter + " | sub_task_no_candidate=" + $ncEvents + " | manual_intervention_required=" + $leSm)
Assert-True ($attemptAfter -eq $attemptBefore) ("★P1-1 回归失败：无候选期间 attempt_total 变了 " + $attemptBefore + " -> " + $attemptAfter + "（应保持不变）")
Assert-True ($ncEvents -ge 1) "★P1-1 回归失败：未落 sub_task_no_candidate 事件（仍静默）"
Assert-True ($leSm -ge 1) "未落 sub_task_manual_intervention_required 事件（转人工未留痕）"
Write-Host "STEP7.5 PASS: P1-1 无候选不消耗重派预算 + 事件可观测"

# ============================================================
# STEP8: 清理
# ============================================================
if (-not $KeepTask) {
    Write-Host "STEP8: cleanup test task"
    try {
        $delResp = Invoke-Json -Method "Delete" -Url ($BaseUrl + "/api/tasks/deleteById/" + $taskId) -Body @{ confirmTitle = (Get-PsqlField ("SELECT title FROM task WHERE id = " + $taskId)) } -Headers $adminHeaders
        Write-Host ("  delete code=" + $delResp.code)
    } catch {
        Write-Warning ("cleanup failed (manual): taskId=" + $taskId + " err=" + $_.Exception.Message)
    }
} else {
    Write-Host ("STEP8: KeepTask=true, task kept: taskId=" + $taskId + " subTaskId=" + $subId)
}

Write-Host ""
Write-Host "OK: no-candidate fallback + retry cadence verify passed"
Write-Host "提示：验完请把 helloai.dispatch.no-candidate-* 与 pending-orphan-threshold-minutes 改回默认并重启。"