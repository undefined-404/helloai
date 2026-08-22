# ============================================================
# helloai 方案3 产出物化(LLM manifest 多文件) + Reviewer 附件内容级核验 e2e verifier
# 用途：验证 F1 交付侧 / F2 核验侧两条真实链路：
#   S1-S5  创建白名单任务 + 子任务，MCP claimSubTask 认领，submitResult 直投
#          manifest 协议产出（```json 围栏 + EXECUTION_RECORD 尾部）
#   S6      物化断言：attachment 多文件落库 + 各自可下载内容匹配 +
#          对话流 sub_task_execute 写 displayText（不刷 manifest JSON 全文、保留 EXECUTION_RECORD）
#   S7      自动核验断言：核验 Prompt 落库 subtask_review_prompt 且含附件正文注入段
#          （依赖 REVIEWER/PLANNER + API_KEY_LLM agent 且 credential_vault 已绑定，无则 SKIP）
#   S8      降级回归：纯文本产出（无 manifest）-> 单 .md 物化 + output 原样入对话流
#   S9      teardown：任务级联删除
# Ref:  .qoder/plans/产出物化方案3与Reviewer内容级核验_a4f2c9d7.md (F3.1)
#       .agents/skills/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：docker compose up -d（helloai-postgres:15432 / redis:26379）；
#       helloai-start 已在 :6565 运行（helloai.storage.enabled=true；
#       helloai.dispatch.attachment-content-enabled=true 默认开）。
#       注：helloai.storage.enabled 为启动期静态配置，"关闭不物化"需重启后端，
#       脚本以 S8 降级路径回归替代（见 S8 注释）。
# 用法（项目根，PowerShell 5.1）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-artifact-content-review.ps1
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'admin123',
    [int]$PollIntervalSec = 3,
    [int]$SubmitTimeoutSec = 60,
    [int]$ReviewTimeoutSec = 150
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

$scriptDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$pgContainer = 'helloai-postgres'
$pgUser      = 'postgres'
$pgDb        = 'helloai'

$agentName  = 'artifact-review-e2e-agent'
$taskTitle  = 'e2e-artifact-content-review-task'
$t1Title    = 'e2e-manifest-multi-file'
$t2Title    = 'e2e-degrade-plain-output'

$global:PassCount = 0
$global:FailCount = 0
$global:SkipCount = 0

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

function Assert-Skip {
    param([string]$Scenario, [string]$Detail)
    Write-Output ('[' + $Scenario + '] SKIP : ' + $Detail)
    $global:SkipCount++
}

# ============================================================
# helper: HTTP JSON (HttpClient, StringContent UTF-8, PS 5.1 safe)
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = '',
        [hashtable]$Headers = @{}
    )
    # 剥 here-string 可能串入的 BOM 头（规则 6）
    $Body = $Body.TrimStart([char]0xFEFF)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne 'GET') {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        if ($Method -eq 'GET')        { $resp = $client.GetAsync($Uri).Result }
        elseif ($Method -eq 'DELETE') {
            if ($Body) {
                $req = [System.Net.Http.HttpRequestMessage]::new('DELETE', $Uri)
                $req.Content = $content
                $resp = $client.SendAsync($req).Result
            } else {
                $resp = $client.DeleteAsync($Uri).Result
            }
        }
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
    return ($line -replace '<NULL>', '')
}

# ============================================================
# STEP S0: pre-flight
# ============================================================
Write-Output '=== [S0] pre-flight ==='
$dockerCheck = & docker ps --format '{{.Names}}|{{.Status}}' --filter "name=$pgContainer" 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($dockerCheck -match "$pgContainer\|Up")) {
    Write-Output 'FAIL : postgres container is NOT up. Run: docker compose up -d'
    exit 1
}
Write-Output '[S0] postgres container up'

try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync($BaseUrl + '/api/health').Result
    Write-Output ('[S0] server ' + $BaseUrl + ' HTTP ' + [int]$pingResp.StatusCode)
    $ping.Dispose()
} catch {
    Write-Output ('FAIL : server NOT reachable at ' + $BaseUrl + ' - start HelloAIApplication via IDEA first')
    exit 1
}

# ============================================================
# STEP S1: admin login
# ============================================================
Write-Output ''
Write-Output '=== [S1] admin login ==='
$loginBody = '{"type":"admin","username":"' + $AdminUsername + '","credential":"' + $AdminPassword + '"}'
$loginResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/auth/login') -Body $loginBody
$loginJson = $null
try { $loginJson = $loginResp.Body | ConvertFrom-Json } catch { }
$adminToken = if ($loginJson -and $loginJson.data -and $loginJson.data.token) { $loginJson.data.token } else { $null }
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Output ('FAIL : admin login failed: ' + $loginResp.Body)
    exit 1
}
Write-Output '[S1] admin token acquired'

# ============================================================
# STEP S2: create or reuse test executor agent + seed tools
# ============================================================
Write-Output ''
Write-Output ('=== [S2] create or reuse ' + $agentName + ' ===')
$lookupResp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/admin/agents/list?page=1&pageSize=200') -Headers @{ 'X-Admin-Token' = $adminToken }
$agentId = $null
$agentApiKey = $null
if ($lookupResp.Code -eq 200) {
    $lookupJson = $lookupResp.Body | ConvertFrom-Json
    if ($lookupJson -and $lookupJson.data -and $lookupJson.data.list) {
        $existing = @($lookupJson.data.list | Where-Object { $_.name -eq $agentName })
        if ($existing.Count -gt 0) {
            $agentId = $existing[0].id
            $agentApiKey = $existing[0].apiKey
            Write-Output ('[S2] reuse existing agentId=' + $agentId)
        }
    }
}
if (-not $agentId) {
    $createBody = '{"name":"' + $agentName + '","role":"EXECUTOR","remark":"artifact-content-review e2e auto-created"}'
    $createResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/admin/agents') -Body $createBody -Headers @{ 'X-Admin-Token' = $adminToken }
    if ($createResp.Code -ne 200) {
        Write-Output ('FAIL : create agent HTTP ' + $createResp.Code + ' body=' + $createResp.Body)
        exit 1
    }
    $createJson = $createResp.Body | ConvertFrom-Json
    if ($createJson.code -ne 200) {
        Write-Output ('FAIL : create agent biz-fail: ' + $createJson.msg)
        exit 1
    }
    $agentId = $createJson.data.id
    $agentApiKey = $createJson.data.apiKey
    Write-Output ('[S2] created agentId=' + $agentId)
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Output 'FAIL : agent apiKey empty'
    exit 1
}

# 幂等 seed 本 e2e 需要的 MCP 工具（默认工具 V13 seed 已含，补一次防手工建 agent 早于 V13）
$seedSql = @"
INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT $agentId, tool.name, 1, 0, 'e2e', 'e2e'
FROM (VALUES ('pullTasks'), ('ack'), ('claimSubTask'), ('heartbeat'),
             ('uploadArtifact'), ('submitResult'), ('reportBlocked')) AS tool(name)
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;
"@
$null = Run-Psql -Sql $seedSql -OutFile (Join-Path $scriptDir 'verify-artifact-content-review-s2-seed.out')

# ============================================================
# STEP S3: create task (whitelist) + sub-task t1
# ============================================================
Write-Output ''
Write-Output '=== [S3] create task + sub-task t1 ==='

# S3.0 残留清理（幂等起点：同名 task 先级联删除）
$findSql = "SELECT id FROM task WHERE title = '$taskTitle' AND deleted = 0 LIMIT 1;"
$findFile = Join-Path $scriptDir 'verify-artifact-content-review-s3-find.out'
$null = Run-Psql -Sql $findSql -OutFile $findFile
$findLine = Get-PsqlFields -Path $findFile
if ($findLine -and $findLine.Split('|')[0]) {
    $residualTaskId = $findLine.Split('|')[0]
    Write-Output ('[S3.0] cleanup residual task id=' + $residualTaskId)
    $delBody = '{"confirmTitle":"' + $taskTitle + '"}'
    $null = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/deleteById/' + $residualTaskId) -Body $delBody -Headers @{ 'X-Admin-Token' = $adminToken }
}

$taskBody = '{"title":"' + $taskTitle + '","description":"e2e artifact content review task","agentPolicy":{"executorAgentIds":[' + $agentId + ']}}'
$taskResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks') -Body $taskBody -Headers @{ 'X-Admin-Token' = $adminToken }
if ($taskResp.Code -ne 200) {
    Write-Output ('FAIL : create task HTTP=' + $taskResp.Code + ' body=' + $taskResp.Body)
    exit 1
}
$taskJson = $taskResp.Body | ConvertFrom-Json
if ($taskJson.code -ne 200) {
    Write-Output ('FAIL : create task biz-fail: ' + $taskJson.msg)
    exit 1
}
$taskId = $taskJson.data.id
Write-Output ('[S3] taskId=' + $taskId)

# t1: 非执行密集 deliverable（避免 fallback-skip-execution-dense 预检干扰）
$t1Body = '{"taskId":' + $taskId + ',"title":"' + $t1Title + '","description":"manifest multi-file output","deliverable":"REST API interface document","acceptance":"document and script consistent"}'
$t1Resp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/sub-tasks') -Body $t1Body -Headers @{ 'X-Admin-Token' = $adminToken }
if ($t1Resp.Code -ne 200) {
    Write-Output ('FAIL : create t1 HTTP=' + $t1Resp.Code + ' body=' + $t1Resp.Body)
    exit 1
}
$t1Json = $t1Resp.Body | ConvertFrom-Json
$t1Id = if ($t1Json -and $t1Json.data -and $t1Json.data.id) { $t1Json.data.id } else { $null }
if (-not $t1Id) {
    Write-Output ('FAIL : t1 id missing: ' + $t1Resp.Body)
    exit 1
}
Write-Output ('[S3] t1 subTaskId=' + $t1Id)

# ============================================================
# STEP S4: claim t1 (MCP REST 通道)
# ============================================================
Write-Output ''
Write-Output '=== [S4] claim t1 ==='
$claimBody = '{"subTaskId":' + $t1Id + '}'
$claimResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/mcp/tools/claimSubTask') -Body $claimBody -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
if ($claimResp.Code -ne 200) {
    Write-Output ('FAIL : claimSubTask HTTP=' + $claimResp.Code + ' body=' + $claimResp.Body)
    exit 1
}
$claimJson = $claimResp.Body | ConvertFrom-Json
if ($claimJson.code -ne 200 -or $claimJson.data.claimed -ne $true) {
    Write-Output ('FAIL : claimSubTask not claimed: ' + $claimResp.Body)
    exit 1
}
Write-Output ('[S4] t1 claimed by agent ' + $agentId)

# ============================================================
# STEP S5: submitResult with manifest output (induce multi-file)
# ============================================================
Write-Output ''
Write-Output '=== [S5] submitResult with manifest output ==='
# manifest JSON（```json 围栏）+ EXECUTION_RECORD 尾部：displayText 应保留尾部、不刷正文
$manifestOutput = @'
```json
{"summary":"REST API docs and example script","files":[
{"name":"README.md","type":"text/markdown","content":"# REST API Docs\necho hello from readme\n"},
{"name":"main.py","type":"text/x-python","content":"def main():\n    print(\"hello from main\")\n\nif __name__ == \"__main__\":\n    main()\n"}
]}
```
EXECUTION_RECORD
SUMMARY: generated REST API docs and example script
KEY_DECISIONS: manifest multi-file protocol
DOWNSTREAM_NOTES: none
'@
$resultId = 'e2e-manifest-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
$submitBody = @{
    subTaskId   = $t1Id
    success     = $true
    output      = $manifestOutput
    finishReason = 'completed'
    resultId    = $resultId
} | ConvertTo-Json -Depth 6
$submitResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/mcp/tools/submitResult') -Body $submitBody -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
if ($submitResp.Code -ne 200) {
    Write-Output ('FAIL : submitResult HTTP=' + $submitResp.Code + ' body=' + $submitResp.Body)
    exit 1
}
$submitJson = $submitResp.Body | ConvertFrom-Json
if ($submitJson.code -ne 200 -or $submitJson.data.ok -ne $true) {
    Write-Output ('FAIL : submitResult not accepted: ' + $submitResp.Body)
    exit 1
}
Write-Output ('[S5] submitResult accepted, resultId=' + $resultId)

# 等待 t1 流转 REVIEW（物化 afterCommit 同步完成）
$deadline = (Get-Date).AddSeconds($SubmitTimeoutSec)
$t1Status = ''
do {
    Start-Sleep -Seconds $PollIntervalSec
    $detailResp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/sub-tasks/getById/' + $t1Id) -Headers @{ 'X-Admin-Token' = $adminToken }
    $detailJson = $null
    try { $detailJson = $detailResp.Body | ConvertFrom-Json } catch { }
    if ($detailJson -and $detailJson.data) {
        $t1Status = [string]$detailJson.data.status
    }
} while (($t1Status -ne 'REVIEW') -and ($t1Status -ne 'DONE') -and ((Get-Date) -lt $deadline))
Write-Output ('[S5] t1 status=' + $t1Status)
if ($t1Status -ne 'REVIEW' -and $t1Status -ne 'DONE') {
    Write-Output ('FAIL : t1 did not reach REVIEW within ' + $SubmitTimeoutSec + 's')
    exit 1
}

# ============================================================
# STEP S6: materialization assertions (multi-file + download + displayText)
# ============================================================
Write-Output ''
Write-Output '=== [S6] materialization assertions ==='

$attSql = "SELECT file_name, mime_type, file_size, storage_url FROM attachment WHERE sub_task_id = $t1Id AND deleted = 0 ORDER BY file_name;"
$attFile = Join-Path $scriptDir 'verify-artifact-content-review-s6-att.out'
$rc = Run-Psql -Sql $attSql -OutFile $attFile
if ($rc -ne 0) {
    Write-Output ('FAIL : attachment query rc=' + $rc + ' see ' + $attFile)
    exit 1
}
$attRows = @(Get-Content -Path $attFile -Encoding UTF8 | Where-Object { $_ -and $_ -notmatch '^\(' })
Write-Output ('[S6] attachment rows=' + $attRows.Count)

# S6.1 多文件物化落库
Assert-Pass ($attRows.Count -eq 2) 'S6.1-attachment-count' ('expected 2 files (README.md/main.py), got ' + $attRows.Count)
$readmeRow = @($attRows | Where-Object { $_ -match '^README\.md\|' })
$mainRow   = @($attRows | Where-Object { $_ -match '^main\.py\|' })
Assert-Pass ($readmeRow.Count -eq 1) 'S6.1-readme-exists' 'README.md materialized'
Assert-Pass ($mainRow.Count -eq 1) 'S6.1-main-exists' 'main.py materialized'
if ($readmeRow.Count -eq 1) {
    $readmeFields = $readmeRow[0].Split('|')
    Assert-Pass ($readmeFields[1] -eq 'text/markdown') 'S6.1-readme-mime' ('README.md mime=' + $readmeFields[1])
    Assert-Pass ($readmeFields[2] -gt 0) 'S6.1-readme-size' ('README.md size=' + $readmeFields[2])
    Assert-Pass ($readmeFields[3] -like 'minio://*') 'S6.1-readme-storage' ('README.md storage=' + $readmeFields[3])
}
if ($mainRow.Count -eq 1) {
    $mainFields = $mainRow[0].Split('|')
    Assert-Pass ($mainFields[1] -eq 'text/x-python') 'S6.1-main-mime' ('main.py mime=' + $mainFields[1])
    Assert-Pass ($mainFields[3] -like 'minio://*') 'S6.1-main-storage' ('main.py storage=' + $mainFields[3])
}

# S6.2 各自可下载且内容匹配
$attListResp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/attachments?subTaskId=' + $t1Id) -Headers @{ 'X-Admin-Token' = $adminToken }
$attListJson = $null
try { $attListJson = $attListResp.Body | ConvertFrom-Json } catch { }
$downloadOk = $true
if ($attListJson -and $attListJson.data) {
    foreach ($att in $attListJson.data) {
        $dl = $null
        try {
            $dl = Invoke-WebRequest -Uri ($BaseUrl + '/api/attachments/downloadById/' + $att.id) `
                -Headers @{ 'X-Admin-Token' = $adminToken } -UseBasicParsing -TimeoutSec 20 -ErrorAction Stop
        } catch {
            $dl = $null
        }
        if ($null -eq $dl -or $dl.StatusCode -ne 200 -or $dl.RawContentLength -le 0) {
            $downloadOk = $false
            Write-Output ('[S6.2] download FAIL for id=' + $att.id + ' name=' + $att.fileName)
        } else {
            # downloadById 返回 application/octet-stream，PS 5.1 IWR 的 Content 是 byte[]
            $dlBytes = $dl.Content
            if ($dlBytes -isnot [byte[]]) { $dlBytes = [System.Text.Encoding]::UTF8.GetBytes([string]$dlBytes) }
            $dlText = [System.Text.Encoding]::UTF8.GetString($dlBytes)
            if ($att.fileName -eq 'README.md') {
                $ok = $dlText.Contains('echo hello from readme')
                Assert-Pass $ok 'S6.2-readme-content' 'README.md download content matches'
                if (-not $ok) { $downloadOk = $false }
            } elseif ($att.fileName -eq 'main.py') {
                $ok = $dlText.Contains('hello from main')
                Assert-Pass $ok 'S6.2-main-content' 'main.py download content matches'
                if (-not $ok) { $downloadOk = $false }
            } else {
                $downloadOk = $false
                Write-Output ('[S6.2] unexpected file: ' + $att.fileName)
            }
        }
    }
} else {
    $downloadOk = $false
    Write-Output ('[S6.2] attachment list API failed: ' + $attListResp.Body)
}
Assert-Pass $downloadOk 'S6.2-all-downloadable' 'all manifest files downloadable with matching content'

# S6.3 对话流 displayText：不刷 manifest JSON、保留文件概览与 EXECUTION_RECORD
$convSql = "SELECT replace(content, E'\n', '<NL>') FROM conversation_message WHERE sub_task_id = $t1Id AND tool_name = 'sub_task_execute' AND deleted = 0 ORDER BY seq DESC LIMIT 1;"
$convFile = Join-Path $scriptDir 'verify-artifact-content-review-s6-conv.out'
$null = Run-Psql -Sql $convSql -OutFile $convFile
$convLine = Get-PsqlFields -Path $convFile
if (-not $convLine) {
    Assert-Pass $false 'S6.3-displayText' 'sub_task_execute conversation message missing'
} else {
    $convText = $convLine -replace '<NL>', "`n"
    Assert-Pass ($convText.Contains('## 产出文件概览')) 'S6.3-overview-header' 'displayText contains file overview header'
    Assert-Pass ($convText.Contains('- README.md')) 'S6.3-readme-listed' 'displayText lists README.md'
    Assert-Pass ($convText.Contains('- main.py')) 'S6.3-main-listed' 'displayText lists main.py'
    Assert-Pass ($convText.Contains('EXECUTION_RECORD')) 'S6.3-record-kept' 'displayText keeps EXECUTION_RECORD tail'
    Assert-Pass (-not $convText.Contains('"files"')) 'S6.3-no-raw-json' 'displayText does NOT leak raw manifest JSON'
    Assert-Pass (-not $convText.Contains('echo hello from readme')) 'S6.3-no-file-body' 'displayText does NOT leak file bodies'
}

# ============================================================
# STEP S7: auto review assertion (review prompt contains attachment content)
# ============================================================
Write-Output ''
Write-Output '=== [S7] auto review attachment content assertion ==='

# 环境自检：REVIEWER/PLANNER + API_KEY_LLM agent 且 credential_vault 已绑定
$reviewerCheckSql = @"
SELECT COUNT(*) FROM agent a
WHERE a.deleted = 0 AND a.status = 'ACTIVE'
  AND a.role IN ('REVIEWER','PLANNER')
  AND a.access_type = 'API_KEY_LLM'
  AND EXISTS (SELECT 1 FROM credential_vault v
              WHERE v.owner_type = 'AGENT' AND v.owner_id = a.id
                AND v.status = 'ACTIVE' AND v.deleted = 0);
"@
$reviewerCheckFile = Join-Path $scriptDir 'verify-artifact-content-review-s7-precheck.out'
$null = Run-Psql -Sql $reviewerCheckSql -OutFile $reviewerCheckFile
$reviewerCount = 0
$rcLine = Get-PsqlFields -Path $reviewerCheckFile
if ($rcLine) {
    try { $reviewerCount = [int]$rcLine.Split('|')[0] } catch { $reviewerCount = 0 }
}
if ($reviewerCount -lt 1) {
    Assert-Skip 'S7-review-prompt' 'no REVIEWER/PLANNER agent with bound vault credential; auto review will not run (bind vault then re-run)'
} else {
    Write-Output ('[S7] reviewer agents with vault: ' + $reviewerCount)
    $promptLine = $null
    $waitDeadline = (Get-Date).AddSeconds($ReviewTimeoutSec)
    do {
        Start-Sleep -Seconds $PollIntervalSec
        $promptSql = "SELECT replace(content, E'\n', '<NL>') FROM conversation_message WHERE sub_task_id = $t1Id AND tool_name = 'subtask_review_prompt' AND deleted = 0 ORDER BY seq DESC LIMIT 1;"
        $promptFile = Join-Path $scriptDir 'verify-artifact-content-review-s7-prompt.out'
        $null = Run-Psql -Sql $promptSql -OutFile $promptFile
        $promptLine = Get-PsqlFields -Path $promptFile
    } while ((-not $promptLine) -and ((Get-Date) -lt $waitDeadline))

    if (-not $promptLine) {
        Assert-Pass $false 'S7-review-prompt' ('subtask_review_prompt not found within ' + $ReviewTimeoutSec + 's (check auto-review-enabled & reviewer LLM availability)')
    } else {
        $promptText = $promptLine -replace '<NL>', "`n"
        Assert-Pass ($promptText.Contains('## 物化附件内容')) 'S7-attachment-content-section' 'review prompt contains attachment content section'
        Assert-Pass ($promptText.Contains('### README.md')) 'S7-readme-header' 'review prompt lists README.md content header'
        Assert-Pass ($promptText.Contains('echo hello from readme')) 'S7-readme-body' 'review prompt injects README.md body'
        Assert-Pass ($promptText.Contains('### main.py')) 'S7-main-header' 'review prompt lists main.py content header'
        Assert-Pass ($promptText.Contains('hello from main')) 'S7-main-body' 'review prompt injects main.py body'
    }
}

# ============================================================
# STEP S8: degrade regression (plain text -> single .md, output raw)
# ============================================================
Write-Output ''
Write-Output '=== [S8] degrade regression (plain text output) ==='
# 说明：helloai.storage.enabled 为启动期静态配置，"关闭物化"需重启后端验证；
# 本步以"无 manifest 的纯文本产出"回归降级路径——物化单 .md、output 原样入对话流。

$t2Body = '{"taskId":' + $taskId + ',"title":"' + $t2Title + '","description":"plain text output","deliverable":"plain summary note","acceptance":"note present"}'
$t2Resp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/sub-tasks') -Body $t2Body -Headers @{ 'X-Admin-Token' = $adminToken }
if ($t2Resp.Code -ne 200) {
    Write-Output ('FAIL : create t2 HTTP=' + $t2Resp.Code + ' body=' + $t2Resp.Body)
    exit 1
}
$t2Json = $t2Resp.Body | ConvertFrom-Json
$t2Id = if ($t2Json -and $t2Json.data -and $t2Json.data.id) { $t2Json.data.id } else { $null }
if (-not $t2Id) {
    Write-Output ('FAIL : t2 id missing: ' + $t2Resp.Body)
    exit 1
}
Write-Output ('[S8] t2 subTaskId=' + $t2Id)

$claim2Resp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/mcp/tools/claimSubTask') -Body ('{"subTaskId":' + $t2Id + '}') -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
$claim2Json = $claim2Resp.Body | ConvertFrom-Json
if ($claim2Json.code -ne 200 -or $claim2Json.data.claimed -ne $true) {
    Write-Output ('FAIL : t2 claim failed: ' + $claim2Resp.Body)
    exit 1
}
Write-Output '[S8] t2 claimed'

$plainOutput = 'plain text output for degrade regression - no manifest'
$resultId2 = 'e2e-degrade-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
$submit2Body = @{
    subTaskId   = $t2Id
    success     = $true
    output      = $plainOutput
    finishReason = 'completed'
    resultId    = $resultId2
} | ConvertTo-Json -Depth 6
$submit2Resp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/mcp/tools/submitResult') -Body $submit2Body -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
$submit2Json = $submit2Resp.Body | ConvertFrom-Json
if ($submit2Json.code -ne 200 -or $submit2Json.data.ok -ne $true) {
    Write-Output ('FAIL : t2 submitResult not accepted: ' + $submit2Resp.Body)
    exit 1
}

$deadline2 = (Get-Date).AddSeconds($SubmitTimeoutSec)
$t2Status = ''
do {
    Start-Sleep -Seconds $PollIntervalSec
    $detail2Resp = Invoke-Json -Method GET -Uri ($BaseUrl + '/api/sub-tasks/getById/' + $t2Id) -Headers @{ 'X-Admin-Token' = $adminToken }
    $detail2Json = $null
    try { $detail2Json = $detail2Resp.Body | ConvertFrom-Json } catch { }
    if ($detail2Json -and $detail2Json.data) {
        $t2Status = [string]$detail2Json.data.status
    }
} while (($t2Status -ne 'REVIEW') -and ($t2Status -ne 'DONE') -and ((Get-Date) -lt $deadline2))
Write-Output ('[S8] t2 status=' + $t2Status)
if ($t2Status -ne 'REVIEW' -and $t2Status -ne 'DONE') {
    Write-Output ('FAIL : t2 did not reach REVIEW within ' + $SubmitTimeoutSec + 's')
    exit 1
}

# S8.1 降级单 .md 物化
$att2Sql = "SELECT file_name, mime_type FROM attachment WHERE sub_task_id = $t2Id AND deleted = 0 ORDER BY file_name;"
$att2File = Join-Path $scriptDir 'verify-artifact-content-review-s8-att.out'
$null = Run-Psql -Sql $att2Sql -OutFile $att2File
$att2Rows = @(Get-Content -Path $att2File -Encoding UTF8 | Where-Object { $_ -and $_ -notmatch '^\(' })
Assert-Pass ($att2Rows.Count -eq 1) 'S8.1-single-file' ('degrade: expected 1 attachment, got ' + $att2Rows.Count)
if ($att2Rows.Count -eq 1) {
    $att2Fields = $att2Rows[0].Split('|')
    Assert-Pass ($att2Fields[0] -eq ($t2Title + '.md')) 'S8.1-file-name' ('degrade file name=' + $att2Fields[0])
    Assert-Pass ($att2Fields[1] -eq 'text/markdown') 'S8.1-file-mime' ('degrade mime=' + $att2Fields[1])
}

# S8.2 对话流 output 原样（无 manifest 时 displayText == 原文）
$conv2Sql = "SELECT replace(content, E'\n', '<NL>') FROM conversation_message WHERE sub_task_id = $t2Id AND tool_name = 'sub_task_execute' AND deleted = 0 ORDER BY seq DESC LIMIT 1;"
$conv2File = Join-Path $scriptDir 'verify-artifact-content-review-s8-conv.out'
$null = Run-Psql -Sql $conv2Sql -OutFile $conv2File
$conv2Line = Get-PsqlFields -Path $conv2File
if (-not $conv2Line) {
    Assert-Pass $false 'S8.2-output-raw' 'sub_task_execute conversation message missing'
} else {
    $conv2Text = $conv2Line -replace '<NL>', "`n"
    Assert-Pass ($conv2Text.Contains($plainOutput)) 'S8.2-output-raw' 'degrade: output text kept as-is in conversation'
    Assert-Pass (-not $conv2Text.Contains('## 产出文件概览')) 'S8.2-no-overview' 'degrade: no displayText overview injected'
}

# ============================================================
# STEP S9: teardown (cascade delete task)
# ============================================================
Write-Output ''
Write-Output '=== [S9] teardown ==='
$delResp = Invoke-Json -Method POST -Uri ($BaseUrl + '/api/tasks/deleteById/' + $taskId) -Body ('{"confirmTitle":"' + $taskTitle + '"}') -Headers @{ 'X-Admin-Token' = $adminToken }
Write-Output ('[S9] delete task HTTP=' + $delResp.Code + ' body=' + $delResp.Body)
$verifySql = "SELECT COUNT(*) FROM sub_task WHERE task_id = $taskId AND deleted = 0;"
$verifyFile = Join-Path $scriptDir 'verify-artifact-content-review-s9-verify.out'
$null = Run-Psql -Sql $verifySql -OutFile $verifyFile
$verifyLine = Get-PsqlFields -Path $verifyFile
$left = if ($verifyLine) { $verifyLine.Split('|')[0] } else { '?' }
Assert-Pass ($left -eq '0') 'S9-cascade-delete' ('sub_tasks left after cascade delete: ' + $left)
if ($left -ne '0') {
    Write-Output 'NOTE: attachment rows may remain (no cascade); cleanup manually if needed:'
    Write-Output ('  DELETE FROM attachment WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = ' + $taskId + ');')
}

Write-Output ''
Write-Output ('SUMMARY PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
if ($global:FailCount -gt 0) {
    exit 1
}
Write-Output 'ALL PASSED: S6 manifest multi-file materialization / S7 review prompt attachment content / S8 degrade regression'
exit 0
