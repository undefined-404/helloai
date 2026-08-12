# verify-tool-matrix.ps1
# ============================================================
# helloai A0-3 tool surface consistency verifier
# 用途：验证三通道工具面一致性 + SKILL 动作清单与服务器声明 diff（防漂移）：
#   S1) REST alias POST /api/mcp/jsonrpc tools/list -> 11 tools + inputSchema
#   S2) GET /api/mcp/tools (REST direct) -> 11 tools, same name set as tools/list
#   S3) REST direct /api/mcp/tools/getAgentStatus probe (POST + Bearer -> 200)
#   S4) SKILL.md 0.1 table tool names == server tools/list names (diff guard)
#   S5) SKILL.md 0.2 table REST endpoint paths -> probe each (route existence)
#       GET endpoints: expect 200; POST endpoints: probe via GET -> 405 (route exists)
#   S6) SKILL.md forbidden old paths check (/api/agents/<, /api/rules/merged)
#   S7) REST direct checkIn -> checkOut real call (sync lease receipt)
#
# NOTE: PowerShell 5.1 + zh-CN Windows. All runtime strings are 100% ASCII.
#
# Usage (project root, PowerShell):
#   powershell -File .\scripts\powershell\verify-tool-matrix.ps1
# ============================================================

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

$base       = "http://localhost:6565"
$scriptDir  = "E:\yhzx\1027\helloai"
$skillFile  = Join-Path $scriptDir "helloai-core\src\main\resources\skills\executor\SKILL.md"

$passCount = 0
$failCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) {
        $script:passCount++
        Write-Output ('[PASS] ' + $Message)
    } else {
        $script:failCount++
        Write-Output ('[FAIL] ' + $Message)
    }
}

# ============================================================
# HTTP helper
# ============================================================
function Invoke-Json {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("GET","POST","PUT","DELETE")][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Body = "",
        [hashtable]$Headers = @{}
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    foreach ($k in $Headers.Keys) {
        $client.DefaultRequestHeaders.Add($k, $Headers[$k]) | Out-Null
    }
    $content = $null
    if ($Method -ne "GET" -and $Method -ne "DELETE") {
        $content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        try {
            if ($Method -eq "GET")       { $resp = $client.GetAsync($Uri).Result }
            elseif ($Method -eq "DELETE") { $resp = $client.DeleteAsync($Uri).Result }
            elseif ($Method -eq "POST")  { $resp = $client.PostAsync($Uri, $content).Result }
            elseif ($Method -eq "PUT")   { $resp = $client.PutAsync($Uri, $content).Result }
            $code = [int]$resp.StatusCode
            $bodyOut = $resp.Content.ReadAsStringAsync().Result
            return @{ Code = $code; Body = $bodyOut }
        } catch [System.Net.Http.HttpRequestException] {
            Write-Error "$Method $Uri network failed: $($_.Exception.Message)"
            return @{ Code = -1; Body = $_.Exception.Message }
        } catch {
            Write-Error "$Method $Uri failed: $($_.Exception.GetType().Name) - $($_.Exception.Message)"
            return @{ Code = -2; Body = $_.Exception.Message }
        }
    } finally {
        $client.Dispose()
    }
}

# ============================================================
# STEP 0: server reachability
# ============================================================
Write-Output "=== [0] server reachability check ==="
try {
    $ping = [System.Net.Http.HttpClient]::new()
    $ping.Timeout = [TimeSpan]::FromSeconds(3)
    $pingResp = $ping.GetAsync("$base/api/health").Result
    Write-Output "HTTP $($pingResp.StatusCode) - server is up"
    $ping.Dispose()
} catch {
    Write-Error "Server NOT reachable at $base - please start HelloAIApplication first"
    exit 1
}
Write-Output ""

# ============================================================
# STEP A: admin login
# ============================================================
Write-Output "=== [A] admin login ==="
$loginBody = '{"type":"admin","username":"admin","credential":"admin123"}'
$loginResp = Invoke-Json -Method POST -Uri "$base/api/auth/login" -Body $loginBody
Write-Output "HTTP $($loginResp.Code)"
$adminToken = ($loginResp.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrEmpty($adminToken)) {
    Write-Error "admin login failed: $($loginResp.Body)"
    exit 1
}
Write-Output "adminToken = $($adminToken.Substring(0, 16))..."
Write-Output ""

# ============================================================
# STEP B: create or reuse A03-test-executor
# ============================================================
Write-Output "=== [B] create or reuse A03-test-executor (admin token) ==="
$agentName   = "A03-test-executor"
$agentId     = $null
$agentApiKey = $null

$lookupResp = Invoke-Json -Method GET -Uri "$base/api/admin/agents/list?pageSize=50" -Headers @{ "X-Admin-Token" = $adminToken }
$parsedJson = $lookupResp.Body | ConvertFrom-Json
$lookupData = $parsedJson.data
if ($lookupData -eq $null -or $lookupData.list -eq $null) {
    $existing = @()
} else {
    $existing = @($lookupData.list | Where-Object { $_.name -eq $agentName })
}
if ($existing.Count -gt 0) {
    $agentId = $existing[0].id
    $agentApiKey = $existing[0].apiKey
    Write-Output "reuse existing: id=$agentId"
} else {
    $createBody = "{`"name`":`"$agentName`",`"role`":`"EXECUTOR`",`"remark`":`"A0-3 tool matrix auto created`"}"
    $createResp = Invoke-Json -Method POST -Uri "$base/api/admin/agents" -Body $createBody -Headers @{ "X-Admin-Token" = $adminToken }
    Write-Output "create HTTP $($createResp.Code)"
    $agentData = ($createResp.Body | ConvertFrom-Json).data
    $agentId = $agentData.id
    $agentApiKey = $agentData.apiKey
}
if ([string]::IsNullOrEmpty($agentApiKey)) {
    Write-Error "agent create / lookup failed (no apiKey)"
    exit 1
}
Write-Output "agentId    = $agentId"
Write-Output "agentApiKey = $agentApiKey"
Write-Output ""

# ============================================================
# STEP S1: REST alias tools/list -> 11 tools + inputSchema
# ============================================================
Write-Output "=== [S1] REST alias POST /api/mcp/jsonrpc tools/list ==="
$s1Body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
$s1Resp = Invoke-Json -Method POST -Uri "$base/api/mcp/jsonrpc" -Body $s1Body -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($s1Resp.Code)"
$s1Obj = $null
try { $s1Obj = $s1Resp.Body | ConvertFrom-Json } catch {}
$s1Names = @()
if ($s1Obj -ne $null -and $s1Obj.result -ne $null -and $s1Obj.result.tools -ne $null) {
    $s1Names = @($s1Obj.result.tools | ForEach-Object { $_.name })
    $schemaOk = $true
    foreach ($t in $s1Obj.result.tools) {
        if ($t.inputSchema -eq $null -or $t.inputSchema.type -ne "object") { $schemaOk = $false }
    }
    Assert-True ($s1Names.Count -eq 11) "S1 REST alias tools/list: 11 tools declared (got $($s1Names.Count))"
    Assert-True $schemaOk "S1 REST alias tools/list: every tool has inputSchema (type=object)"
} else {
    Assert-True $false "S1 REST alias tools/list: parse failed or no result (HTTP $($s1Resp.Code))"
}
Write-Output "tools: $($s1Names -join ',')"
Write-Output ""

# ============================================================
# STEP S2: GET /api/mcp/tools (REST direct) same name set
# ============================================================
Write-Output "=== [S2] GET /api/mcp/tools (REST direct) ==="
$s2Resp = Invoke-Json -Method GET -Uri "$base/api/mcp/tools" -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($s2Resp.Code)"
$s2Obj = $null
try { $s2Obj = $s2Resp.Body | ConvertFrom-Json } catch {}
$s2Names = @()
if ($s2Obj -ne $null -and $s2Obj.data -ne $null) {
    $s2Names = @($s2Obj.data | ForEach-Object { [string]$_ })
    $diff = Compare-Object $s1Names $s2Names
    Assert-True ($diff -eq $null -and $s2Names.Count -eq 11) "S2 GET /api/mcp/tools: 11 tools, same name set as REST alias (got $($s2Names.Count))"
} else {
    Assert-True $false "S2 GET /api/mcp/tools: parse failed (HTTP $($s2Resp.Code))"
}
Write-Output ""

# ============================================================
# STEP S3: REST direct getAgentStatus probe (no side effects)
# ============================================================
Write-Output "=== [S3] REST direct POST /api/mcp/tools/getAgentStatus ==="
$s3Resp = Invoke-Json -Method POST -Uri "$base/api/mcp/tools/getAgentStatus" -Body "{}" -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "HTTP $($s3Resp.Code)"
$s3Body = $s3Resp.Body
Assert-True ($s3Resp.Code -eq 200 -and $s3Body -match '"data"') "S3 REST direct getAgentStatus: 200 with data (route exists and delegates)"
Write-Output ""

# ============================================================
# STEP S4: SKILL.md 0.1 table tool names == server tools/list
# ============================================================
Write-Output "=== [S4] SKILL.md 0.1 table vs server tools/list (diff guard) ==="
$skillText = Get-Content -Raw -Encoding UTF8 $skillFile
# region-limited parsing: 0.1 table only (### 0.1 ... ### 0.2), so the 1.2 MCP tool
# table (### 1.2) and error-code samples are excluded. Anchors are pure ASCII.
$idx01 = $skillText.IndexOf('### 0.1')
$idx02 = $skillText.IndexOf('### 0.2')
$region01 = ""
if ($idx01 -ge 0 -and $idx02 -gt $idx01) {
    $region01 = $skillText.Substring($idx01, $idx02 - $idx01)
}
$skillToolNames = @()
# NOTE: no non-ASCII chars in regex (PS 5.1 parses .ps1 as GBK); (?m) needed for
# line-start anchor on multi-line string.
$skillToolRegex = [regex]'(?m)^\| `([A-Za-z]+)` \|'
foreach ($m in $skillToolRegex.Matches($region01)) {
    $skillToolNames += $m.Groups[1].Value
}
$skillToolNames = @($skillToolNames | Sort-Object -Unique)
Write-Output ("SKILL 0.1 tools: " + ($skillToolNames -join ',') + " count=" + $skillToolNames.Count)
$diffS4 = Compare-Object $s1Names $skillToolNames
Assert-True ($diffS4 -eq $null) "S4 SKILL 0.1 table tool names == server tools/list (both $($s1Names.Count))"
Write-Output ""

# ============================================================
# STEP S5: SKILL.md 0.2 REST endpoint paths -> probe each
# ============================================================
Write-Output "=== [S5] SKILL.md 0.2 REST endpoint paths probe ==="
# region-limited parsing: 0.2 table only (### 0.2 ... next ## section), so the
# error-code table sample rows (| 404 | `GET /api/agents/<id>` ...) are excluded.
$idx02b = $skillText.IndexOf('### 0.2')
# use "\n## " as the next-h2 anchor: plain "## " would match inside the "### 0.2"
# heading itself (chars 2-4), yielding a 1-char region.
$idxNextH2 = $skillText.IndexOf("`n## ", $idx02b)
$region02 = ""
if ($idx02b -ge 0 -and $idxNextH2 -gt $idx02b) {
    $region02 = $skillText.Substring($idx02b, $idxNextH2 - $idx02b)
}
$skillPathRegex = [regex]'(?m)^\| [^|]+ \| `(GET|POST|PUT|DELETE) ([^`]+)`'
$s5Total = 0
$s5Fail = 0
foreach ($m in $skillPathRegex.Matches($region02)) {
    $method = $m.Groups[1].Value
    $path = $m.Groups[2].Value
    # normalize placeholders
    $path = $path -replace '\{id\}', '1'
    $uri = "$base$path"
    if ($method -eq "GET") {
        $r = Invoke-Json -Method GET -Uri $uri -Headers @{ "Authorization" = "Bearer $agentApiKey" }
        # business errors may return 200 with code!=200; 404 means route missing
        $ok = ($r.Code -eq 200)
        if (-not $ok) {
            Write-Output ("[FAIL] S5 GET " + $path + " -> HTTP " + $r.Code)
            $s5Fail++
        } else {
            Write-Output ("[PASS] S5 GET " + $path + " -> 200")
            $script:passCount++
        }
    } else {
        # POST-only route: probe via GET -> 405 (route exists) is the reliable signal
        $r = Invoke-Json -Method GET -Uri $uri -Headers @{ "Authorization" = "Bearer $agentApiKey" }
        $ok = ($r.Code -eq 405)
        if (-not $ok) {
            Write-Output ("[FAIL] S5 POST-route " + $path + " -> GET probe HTTP " + $r.Code + " (expect 405 = route exists)")
            $s5Fail++
        } else {
            Write-Output ("[PASS] S5 POST-route " + $path + " -> GET probe 405 (route exists)")
            $script:passCount++
        }
    }
    $s5Total++
}
Assert-True ($s5Fail -eq 0) "S5 all $s5Total SKILL 0.2 REST endpoint routes exist"
Write-Output ""

# ============================================================
# STEP S6: SKILL.md forbidden old paths
# ============================================================
Write-Output "=== [S6] SKILL.md forbidden old paths (outside error-code table) ==="
# The error-code table intentionally keeps old-path samples as "wrong usage"
# teaching rows (| 404 | `GET /api/agents/<id>` ...). Check only the region
# before the first error table row so recommended usage stays clean.
$errIdx = $skillText.IndexOf('| 404 |')
$checkText = if ($errIdx -ge 0) { $skillText.Substring(0, $errIdx) } else { $skillText }
Assert-True (-not $checkText.Contains('/api/agents/<')) "S6 no old path '/api/agents/<' outside error table"
Assert-True (-not $checkText.Contains('/api/rules/merged')) "S6 no old path '/api/rules/merged' outside error table"
Write-Output ""

# ============================================================
# STEP S7: REST direct checkIn -> checkOut (sync lease receipt)
# ============================================================
Write-Output "=== [S7] REST direct checkIn / checkOut ==="
$s7InBody = '{"workMode":"AUTO","ttlMinutes":5}'
$s7InResp = Invoke-Json -Method POST -Uri "$base/api/mcp/tools/checkIn" -Body $s7InBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "checkIn HTTP $($s7InResp.Code)"
$s7InOk = ($s7InResp.Code -eq 200 -and $s7InResp.Body -match '"leaseId"')
Assert-True $s7InOk "S7 REST direct checkIn: 200 with leaseId (sync lease receipt)"
$s7OutBody = '{"closeReason":"a03-matrix-close"}'
$s7OutResp = Invoke-Json -Method POST -Uri "$base/api/mcp/tools/checkOut" -Body $s7OutBody -Headers @{ "Authorization" = "Bearer $agentApiKey" }
Write-Output "checkOut HTTP $($s7OutResp.Code)"
$s7OutOk = ($s7OutResp.Code -eq 200 -and $s7OutResp.Body -match '"closedCount"')
Assert-True $s7OutOk "S7 REST direct checkOut: 200 with closedCount (idempotent close)"
Write-Output ""

# ============================================================
# summary
# ============================================================
Write-Output "============================================================"
Write-Output ("RESULT: PASS=$passCount FAIL=$failCount")
if ($failCount -eq 0) {
    Write-Output "ALL PASSED"
} else {
    Write-Output "SOME CHECKS FAILED"
    exit 1
}
