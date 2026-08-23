# ============================================================
# helloai admin 授权拦截防回归脚本 v1
# verify admin authorization guard for /api/admin/** (regression)
#
# 用途：验证 AdminOnlyInterceptor 对 /api/admin/** 的授权收口——
#       agent 身份（外部 AI 的 API Key）访问任意 admin 端点必须 403，
#       admin 身份不受影响（200），无凭证请求 401。
# Ref:  doc/HelloAI_CODE_STYLE.md §6.8 授权拦截红线
#       HelloAI后端代码评审报告 P0（admin 授权缺口）
# 前置：helloai-start 已在 6565 运行；docker compose 起 postgres。
# 用法（项目根）：powershell -File .\scripts\powershell\verify-admin-authz.ps1
# 流程：
#   0) 健康检查
#   A) admin 登录 -> adminToken
#   B) 创建/复用测试 Agent（adminToken）-> agentApiKey
#   C) agent Bearer 探 8 个 /api/admin/** 前缀端点 -> 全部断言 403
#      （各前缀一个代表性 GET 零副作用端点 + 一个写端点假 provider，
#        写端点在拦截器处即被拦下，不会触达业务逻辑）
#   D) admin token 同一批 GET 端点 -> 断言 200（拦截器不误伤管理员）
#   E) 无凭证探一个端点 -> 断言 401
#   F) 汇总 PASS/FAIL，FAIL > 0 时退出码 1
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Net.Http

$base = 'http://localhost:6565'
$pass = 0
$fail = 0

# 通用 HTTP 请求助手：返回 @{ Code; Body }，非 2xx 不抛异常
function Invoke-Http {
    param([string]$Method, [string]$Uri, [string]$Body = '', [hashtable]$Headers = @{})
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    try {
        $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::$Method, $Uri)
        if ($Body -ne '') {
            $req.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
        }
        $resp = $client.SendAsync($req).Result
        $code = [int]$resp.StatusCode
        $text = $resp.Content.ReadAsStringAsync().Result
        return @{ Code = $code; Body = $text }
    } catch {
        return @{ Code = -1; Body = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

# 断言助手：状态码一致计 PASS，否则计 FAIL（不中断，继续探完全部端点）
function Assert-Status {
    param([string]$Label, [int]$Expected, [hashtable]$Resp)
    if ($Resp.Code -eq $Expected) {
        $script:pass = $script:pass + 1
        Write-Output ('[PASS] ' + $Label + ' -> ' + $Resp.Code)
    } else {
        $script:fail = $script:fail + 1
        Write-Output ('[FAIL] ' + $Label + ' expected=' + $Expected + ' actual=' + $Resp.Code)
        Write-Output ('       body: ' + $Resp.Body.Substring(0, [Math]::Min(200, $Resp.Body.Length)))
    }
}

# ==================== [0] health check ====================
Write-Output '=== [0] server reachability check ==='
$ping = Invoke-Http -Method 'GET' -Uri ($base + '/api/health')
if ($ping.Code -ne 200) {
    Write-Output ('server NOT reachable at ' + $base + ' - start helloai-start first')
    exit 1
}
Write-Output 'HTTP 200 - server is up'
Write-Output ''

# ==================== [A] admin login ====================
Write-Output '=== [A] admin login ==='
$loginBody = '{"type":"admin","username":"admin","credential":"admin123"}'
$loginResp = Invoke-Http -Method 'POST' -Uri ($base + '/api/auth/login') -Body $loginBody
if ($loginResp.Code -ne 200) {
    Write-Output ('admin login failed: ' + $loginResp.Body)
    exit 1
}
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Output ('admin login returned no token: ' + $loginResp.Body)
    exit 1
}
Write-Output ('adminToken = ' + $adminToken.Substring(0, 16) + '...')
Write-Output ''

# ==================== [B] create or reuse test agent ====================
Write-Output '=== [B] create or reuse test agent (admin token) ==='
$agentName = 'authz-probe-executor'
$agentApiKey = $null
$lookupResp = Invoke-Http -Method 'GET' -Uri ($base + '/api/admin/agents/list?pageSize=50') -Headers @{ 'X-Admin-Token' = $adminToken }
if ($lookupResp.Code -ne 200) {
    Write-Output ('agent lookup failed: ' + $lookupResp.Body)
    exit 1
}
$lookupData = ($lookupResp.Body | ConvertFrom-Json).data
$existing = @()
if ($lookupData -ne $null -and $lookupData.list -ne $null) {
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
}
if ($existing.Count -gt 0) {
    $agentApiKey = $existing[0].apiKey
    Write-Output ('reuse existing agent: id=' + $existing[0].id)
} else {
    $createBody = '{"name":"' + $agentName + '","role":"EXECUTOR","remark":"admin-authz verify auto created"}'
    $createResp = Invoke-Http -Method 'POST' -Uri ($base + '/api/admin/agents') -Body $createBody -Headers @{ 'X-Admin-Token' = $adminToken }
    if ($createResp.Code -ne 200) {
        Write-Output ('agent create failed: ' + $createResp.Body)
        exit 1
    }
    $agentApiKey = ($createResp.Body | ConvertFrom-Json).data.apiKey
    Write-Output 'created new test agent'
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Output 'no agent apiKey available, abort'
    exit 1
}
Write-Output ''

# ==================== 探测端点清单 ====================
# 每个 /api/admin/** 前缀一个代表性 GET（GET 零副作用）；路径变量用不存在的假 id，
# 授权拦截发生在 Controller 之前，业务逻辑不会被触达
$probePaths = @(
    '/api/admin/agents/list',
    '/api/admin/llm-providers/list',
    '/api/admin/platform/providers/list',
    '/api/admin/config',
    '/api/admin/prompts',
    '/api/admin/dashboard/getOverview',
    '/api/admin/quality/findSpecSectionByTaskId/999999',
    '/api/admin/duty-leases'
)
# admin 侧 sanity 端点（剔除 quality：假 taskId 会让业务层报错而非 200）
$adminPaths = @(
    '/api/admin/agents/list',
    '/api/admin/llm-providers/list',
    '/api/admin/platform/providers/list',
    '/api/admin/config',
    '/api/admin/prompts',
    '/api/admin/dashboard/getOverview',
    '/api/admin/duty-leases'
)

# ==================== [C] agent identity must be rejected (403) ====================
Write-Output '=== [C] agent Bearer probing /api/admin/** (expect all 403) ==='
foreach ($p in $probePaths) {
    $resp = Invoke-Http -Method 'GET' -Uri ($base + $p) -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
    Assert-Status -Label ('GET ' + $p) -Expected 403 -Resp $resp
}
# 写端点探测：假 provider，拦截器在业务逻辑前拦截，无副作用
$putResp = Invoke-Http -Method 'PUT' -Uri ($base + '/api/admin/platform/providers/saveApiKeyByProvider/FAKE-PROVIDER') -Body '{"apiKey":"probe"}' -Headers @{ 'Authorization' = 'Bearer ' + $agentApiKey }
Assert-Status -Label 'PUT /api/admin/platform/providers/saveApiKeyByProvider/{provider}' -Expected 403 -Resp $putResp
Write-Output ''

# ==================== [D] admin identity unaffected (200) ====================
Write-Output '=== [D] admin token same endpoints (expect all 200) ==='
foreach ($p in $adminPaths) {
    $resp = Invoke-Http -Method 'GET' -Uri ($base + $p) -Headers @{ 'X-Admin-Token' = $adminToken }
    Assert-Status -Label ('GET ' + $p) -Expected 200 -Resp $resp
}
Write-Output ''

# ==================== [E] no credential must be rejected (401) ====================
Write-Output '=== [E] no credential (expect 401) ==='
$anonResp = Invoke-Http -Method 'GET' -Uri ($base + '/api/admin/agents/list')
Assert-Status -Label 'GET /api/admin/agents/list (anonymous)' -Expected 401 -Resp $anonResp
Write-Output ''

# ==================== [F] summary ====================
Write-Output '=== Summary ==='
Write-Output ('PASS=' + $pass + ' FAIL=' + $fail)
if ($fail -gt 0) {
    Write-Output 'RESULT: FAILED - admin authorization guard broken'
    exit 1
}
Write-Output 'RESULT: ALL PASSED - /api/admin/** requires admin identity'
exit 0
