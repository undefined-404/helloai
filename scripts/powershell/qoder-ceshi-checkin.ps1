# qoder-ceshi MCP checkIn + doorbell 一次性验证脚本
# 流程：
#   1) GET /mcp/sse 拿 sessionId（Start-Job 后台 curl.exe 写入 sse-qoder.log）
#   2) POST initialize (HttpClient + StringContent, 必须带 Authorization + charset)
#   3) POST notifications/initialized
#   4) POST tools/call checkIn (workMode=AUTO, maxConcurrent=3, ttlMinutes=30, sessionId=xxx)
#   5) GET /api/agents/doorbell/sse 验证门铃连通（5 秒探针）
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Net.Http

$url       = 'http://localhost:6565'
$apiKey    = 'ak_cbf5e0d7ea0a37639f0988d7f5664013'
$agentId   = '2078110337491955714'
$scriptDir = 'E:\yhzx\1027\helloai'
$sseFile   = Join-Path $scriptDir 'sse-qoder.log'
if (Test-Path $sseFile) { Remove-Item $sseFile -Force }

# 1) Start-Job SSE long connection
Write-Output '=== [1] Start SSE long connection (background Job) ==='
$job = Start-Job -ScriptBlock {
    & curl.exe -i -N -H "Authorization: Bearer $using:apiKey" http://localhost:6565/mcp/sse 2>$null |
        Out-File -Encoding ascii $using:sseFile
}
Start-Sleep -Seconds 3
$sid = (Select-String -Path $sseFile -Pattern 'sessionId=([A-Za-z0-9-]+)' -ErrorAction SilentlyContinue).Matches.Groups[1].Value
Write-Output ("sessionId = " + $sid)
if ([string]::IsNullOrEmpty($sid)) {
    Write-Error 'sessionId extraction failed'
    Stop-Job $job -PassThru | Remove-Job -Force
    exit 1
}

# 2) Helper: POST + read SSE stream (carry sessionId into arguments for tools that need it)
function Send-McpRequest {
    param(
        [string]$Body,
        [string]$Label,
        [int]$WaitSeconds = 2
    )
    Write-Output ''
    Write-Output ("=== " + $Label + " ===")
    Write-Output ("Body: " + $Body)
    $posBefore = (Get-Item $sseFile).Length
    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
        $content = [System.Net.Http.StringContent]::new(
            $Body,
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
        $uri = "$url/mcp/messages?sessionId=$sid"
        $response = $client.PostAsync($uri, $content).Result
        $respBody = $response.Content.ReadAsStringAsync().Result
        Write-Output ("POST Status: " + $response.StatusCode)
        Write-Output ("POST Body: '" + $respBody + "' (spring-ai MCP response via SSE)")
    } catch {
        Write-Output ("POST EXCEPTION: " + $_.Exception.Message)
    }
    Start-Sleep -Seconds $WaitSeconds
    $posAfter = (Get-Item $sseFile).Length
    Write-Output ("--- SSE stream new content (offset " + $posBefore + " -> " + $posAfter + ") ---")
    if ($posAfter -gt $posBefore) {
        $reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $reader.Position = $posBefore
        $sseReader = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
        $newContent = $sseReader.ReadToEnd()
        $sseReader.Close()
        $reader.Close()
        Write-Output $newContent
    } else {
        Write-Output '(no new content)'
    }
}

# 3) Standard MCP handshake
Send-McpRequest -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"qoder-ceshi-cli","version":"1.0.0"}}}' -Label '[2] initialize' -WaitSeconds 3
Send-McpRequest -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label '[3] notifications/initialized' -WaitSeconds 1

# 4) tools/call checkIn (核心): 必须传 sessionId/spring-ai 1.1 不支持隐式注入
$checkInBody = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":' + $agentId + ',"workMode":"AUTO","maxConcurrent":3,"ttlMinutes":30,"sessionId":"' + $sid + '"}}}'
Send-McpRequest -Body $checkInBody -Label '[4] tools/call checkIn (with sessionId)' -WaitSeconds 3

# 5) Doorbell probe (8s)
Write-Output ''
Write-Output '=== [5] Doorbell SSE probe (8s) ==='
$doorbellLog = Join-Path $scriptDir 'doorbell-qoder.log'
if (Test-Path $doorbellLog) { Remove-Item $doorbellLog -Force }
$dj = Start-Job -ScriptBlock {
    & curl.exe -i -N --max-time 8 -H "Authorization: Bearer $using:apiKey" http://localhost:6565/api/agents/doorbell/sse 2>$null |
        Out-File -Encoding ascii $using:doorbellLog
}
Start-Sleep -Seconds 9
Stop-Job $dj -PassThru | Remove-Job -Force
Get-Content $doorbellLog -ErrorAction SilentlyContinue

# 6) Cleanup SSE keepalive job
Write-Output ''
Write-Output '=== Cleanup ==='
Stop-Job $job -PassThru | Remove-Job -Force
