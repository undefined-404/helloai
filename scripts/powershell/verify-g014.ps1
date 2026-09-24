# ============================================================
# verify-g014.ps1 - G-014 外部 Agent 执行通道修复 · 端到端验证
#
# 为何新增（规约 §23/§26）：本专项 7 个验证点（依赖门禁新 reason / 开工工具
# 幂等 / 附件与贡献者可发现 / 归属 403 / 平台账号 200 回归 / 13 工具 schema）
# 在既有 scripts/powershell/ 资产中无对应断言，且需「一正一反」双通道验证，
# 故新增本脚本；复用同一 HTTP/MCP 调用范式，不引入新框架。
#
# 前置：
#   1) 后端已启动且连目标库（本次为生产库 39.106.204.43 的中间件）
#   2) 至少一个 Agent API Key（-AgentKey）；另可选他人 Key / 管理端 token
#   3) 候选子任务 ID 从库中选出（勿在生产库造数据）：
#      -StartSubTaskId     : ASSIGNED 或 REWORK 且归属 -AgentKey 对应 Agent 的子任务
#      -NotReadySubTaskId  : 前置未全部 DONE 的 PENDING 子任务
#      -ForeignAttachmentId: 归属【其他】Agent 子任务的附件 ID（用于 403 反证）
#      -ListSubTaskId      : 任意有附件的子任务 ID（用于平台账号 200 正证）
#
# 用法示例：
#   .\verify-g014.ps1 -AgentKey ak_xxx -StartSubTaskId 2102932048573370372 `
#       -NotReadySubTaskId 2102932048577564673 `
#       -ForeignAttachmentId 2102938640949710849 -AdminToken <X-Admin-Token> `
#       -ListSubTaskId 2102932048573370372
#
# 输出：逐条 [S*] PASS / FAIL / SKIP，末尾汇总；退出码 = 失败条数（0 = 全绿）
# ============================================================
[CmdletBinding()]
param(
  # 默认指向本地启动的后端（IDEA / 本地部署）；连服务器后端时显式传 -BaseUrl http://<host>
  [string]$BaseUrl = 'http://localhost:6565',
  [Parameter(Mandatory = $true)][string]$AgentKey,
  # 可选：ASSIGNED/REWORK 且归属该 Agent 的子任务（缺省时 S2/S3 跳过）
  [string]$StartSubTaskId,
  [string]$NotReadySubTaskId,
  [string]$DetailSubTaskId,
  [string]$OtherAgentKey,
  [string]$ForeignAttachmentId,
  [string]$ListSubTaskId,
  [string]$AdminToken,
  # 负向用例（S8）用：归属 -AgentKey 对应 Agent、但已是终态（DONE/CANCELLED）的子任务
  [string]$DoneSubTaskId
)

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$script:Pass = 0
$script:Fail = 0
$script:Skip = 0

function New-JsonBody([string]$Json) {
  # 入口统一剥源文件/入参可能残留的 BOM，避免 body 首字符污染。
  # 注意：PS 函数返回数组会被自动展开为 Object[]，必须用一元逗号 (,) 包装保持 byte[] 类型；
  # 否则 Invoke-WebRequest -Body 收到 Object[] 会发出畸形请求体，服务端返回 500。
  return ,$script:Utf8NoBom.GetBytes($Json.TrimStart([char]0xFEFF))
}

function Invoke-McpCall {
  param([string]$Key, [string]$ToolName, [hashtable]$Arguments, [int]$Id = 1)
  $payload = @{
    jsonrpc = '2.0'
    method  = 'tools/call'
    params  = @{ name = $ToolName; arguments = $Arguments }
    id      = $Id
  } | ConvertTo-Json -Depth 8 -Compress
  $resp = Invoke-WebRequest -Uri ($BaseUrl + '/api/mcp/jsonrpc') -Method Post `
    -Headers @{ Authorization = ('Bearer ' + $Key) } `
    -ContentType 'application/json; charset=utf-8' `
    -Body (New-JsonBody $payload) -UseBasicParsing -TimeoutSec 30
  return ($resp.Content | ConvertFrom-Json)
}

function Invoke-McpRaw([string]$Key, [string]$RawJson) {
  $resp = Invoke-WebRequest -Uri ($BaseUrl + '/api/mcp/jsonrpc') -Method Post `
    -Headers @{ Authorization = ('Bearer ' + $Key) } `
    -ContentType 'application/json; charset=utf-8' `
    -Body (New-JsonBody $RawJson) -UseBasicParsing -TimeoutSec 30
  return ($resp.Content | ConvertFrom-Json)
}

function Get-HttpStatus {
  param([string]$Url, [hashtable]$Headers)
  try {
    $r = Invoke-WebRequest -Uri $Url -Headers $Headers -Method Get -UseBasicParsing -TimeoutSec 30
    return [int]$r.StatusCode
  } catch {
    if ($_.Exception.Response -ne $null) { return [int]$_.Exception.Response.StatusCode }
    return -1
  }
}

function Report([string]$Case, [bool]$Ok, [string]$Detail) {
  if ($Ok) {
    $script:Pass++
    Write-Output ('[' + $Case + '] PASS : ' + $Detail)
  } else {
    $script:Fail++
    Write-Output ('[' + $Case + '] FAIL : ' + $Detail)
  }
}

function Skip([string]$Case, [string]$Reason) {
  $script:Skip++
  Write-Output ('[' + $Case + '] SKIP : ' + $Reason)
}

Write-Output ('=== verify-g014 @ ' + $BaseUrl + ' ===')

# ------------------------------------------------------------
# S1  T07 schema：13 工具 + 每个 inputSchema 带 required + 含 startSubTask
# ------------------------------------------------------------
try {
  $json = Invoke-McpRaw $AgentKey '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}'
  $tools = @($json.result.tools)
  $count = $tools.Count
  $hasStart = @($tools | Where-Object { $_.name -eq 'startSubTask' }).Count -eq 1
  $missingRequired = @($tools | Where-Object {
      -not ($_.inputSchema.PSObject.Properties.Name -contains 'required')
    }).Count
  Report 'S1' (($count -eq 13) -and $hasStart -and ($missingRequired -eq 0)) `
    ('tools=' + $count + ' startSubTask=' + $hasStart + ' missingRequired=' + $missingRequired)
} catch {
  Report 'S1' $false ('request failed: ' + $_.Exception.Message)
}

# ------------------------------------------------------------
# S2/S3  T02 开工出口 + 幂等（需 ASSIGNED/REWORK 候选）
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($StartSubTaskId)) {
  Skip 'S2' 'StartSubTaskId not provided (no ASSIGNED/REWORK candidate)'
  Skip 'S3' 'StartSubTaskId not provided'
} else {
  try {
    $r = Invoke-McpCall $AgentKey 'startSubTask' @{ subTaskId = [long]$StartSubTaskId }
    $res = $r.result
    $ok = ($res.ok -eq $true) -and ($res.started -eq $true) -and ($res.status -eq 'IN_PROGRESS')
    Report 'S2' $ok ('ok=' + $res.ok + ' started=' + $res.started + ' status=' + $res.status + ' reason=' + $res.reason)
  } catch {
    Report 'S2' $false ('request failed: ' + $_.Exception.Message)
  }

  try {
    $r = Invoke-McpCall $AgentKey 'startSubTask' @{ subTaskId = [long]$StartSubTaskId }
    $res = $r.result
    $ok = ($res.ok -eq $true) -and ($res.started -eq $true) -and ($res.status -eq 'IN_PROGRESS')
    Report 'S3' $ok ('second call: started=' + $res.started + ' status=' + $res.status + ' reason=' + $res.reason)
  } catch {
    Report 'S3' $false ('request failed: ' + $_.Exception.Message)
  }
}

# ------------------------------------------------------------
# S4  T01 依赖门禁：前置未就绪 -> claimed=false + dependency_not_ready
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($NotReadySubTaskId)) {
  Skip 'S4' 'NotReadySubTaskId not provided'
} else {
  try {
    $r = Invoke-McpCall $AgentKey 'claimSubTask' @{ subTaskId = [long]$NotReadySubTaskId }
    $res = $r.result
    $ok = ($res.claimed -eq $false) -and ($res.reason -eq 'dependency_not_ready')
    Report 'S4' $ok ('claimed=' + $res.claimed + ' reason=' + $res.reason)
  } catch {
    Report 'S4' $false ('request failed: ' + $_.Exception.Message)
  }
}

# ------------------------------------------------------------
# S5  T04 可发现性：detail 含 attachments[] 与 contributors[]
# ------------------------------------------------------------
$targetId = $DetailSubTaskId
if ([string]::IsNullOrWhiteSpace($targetId)) { $targetId = $StartSubTaskId }
if ([string]::IsNullOrWhiteSpace($targetId)) {
  Skip 'S5' 'DetailSubTaskId / StartSubTaskId not provided'
} else {
try {
  $r = Invoke-McpCall $AgentKey 'getSubTaskDetail' @{ subTaskId = [long]$targetId }
  $res = $r.result
  $hasAtt = $res.PSObject.Properties.Name -contains 'attachments'
  $hasCon = $res.PSObject.Properties.Name -contains 'contributors'
  Report 'S5' ($hasAtt -and $hasCon) `
    ('attachments field=' + $hasAtt + ' count=' + @($res.attachments).Count + ' contributors field=' + $hasCon + ' count=' + @($res.contributors).Count)
} catch {
  Report 'S5' $false ('request failed: ' + $_.Exception.Message)
}
}

# ------------------------------------------------------------
# S6  T04b 越权反证：他人 Key 读该子任务附件 -> 403
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($OtherAgentKey) -or [string]::IsNullOrWhiteSpace($ForeignAttachmentId)) {
  Skip 'S6' 'OtherAgentKey / ForeignAttachmentId not provided'
} else {
  $code = Get-HttpStatus ($BaseUrl + '/api/attachments/getById/' + $ForeignAttachmentId) @{ Authorization = ('Bearer ' + $OtherAgentKey) }
  Report 'S6' ($code -eq 403) ('http=' + $code + ' (expect 403)')
}

# ------------------------------------------------------------
# S7  T04b 平台账号正证（P0 回归点）：管理端读附件 -> 200，不得 403
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($AdminToken) -or [string]::IsNullOrWhiteSpace($ListSubTaskId)) {
  Skip 'S7' 'AdminToken / ListSubTaskId not provided'
} else {
  $code = Get-HttpStatus ($BaseUrl + '/api/attachments?subTaskId=' + $ListSubTaskId) @{ 'X-Admin-Token' = $AdminToken }
  Report 'S7' ($code -eq 200) ('http=' + $code + ' (expect 200; 403 means channel check regressed)')
}

# ------------------------------------------------------------
# S8  T02 负向：终态子任务开工应被状态白名单拒绝（无需等新任务即可验证）
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($DoneSubTaskId)) {
  Skip 'S8' 'DoneSubTaskId not provided'
} else {
  try {
    $r = Invoke-McpCall $AgentKey 'startSubTask' @{ subTaskId = [long]$DoneSubTaskId }
    $res = $r.result
    $reasonStr = [string]$res.reason
    $ok = ($res.ok -eq $false) -and ($res.started -eq $false) -and ($reasonStr.StartsWith('invalid_status:'))
    Report 'S8' $ok ('ok=' + $res.ok + ' started=' + $res.started + ' reason=' + $res.reason)
  } catch {
    Report 'S8' $false ('request failed: ' + $_.Exception.Message)
  }
}

# ------------------------------------------------------------
# 汇总
# ------------------------------------------------------------
Write-Output ('--- summary: PASS=' + $script:Pass + ' FAIL=' + $script:Fail + ' SKIP=' + $script:Skip + ' ---')
exit $script:Fail
