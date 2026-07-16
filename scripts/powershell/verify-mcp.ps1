# ============================================================
# helloai MCP 端 to end v8
# 用途：MCP-over-SSE 主通道最小连通验证（SSE 建连 + initialize + tools/call 冒烟）。
# Ref:  doc/HelloAI_实现差距表.md (N3 MCP Server 工具集)
#       .agents/skills/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：helloai-start 已在 6565 运行；docker compose 起 postgres。
# 用法（项目根）：powershell -File .\scripts\powershell\verify-mcp.ps1
# - SSE: curl -N + Out-File (proven works in v2/v3)
# - POST: HttpClient + StringContent(application/json, UTF8) (v6 charset fix)
# - Response: read sse.txt offset (v7 SSE stream readback)
# ============================================================

Add-Type -AssemblyName System.Net.Http

$url = "http://localhost:6565"
$scriptDir = "E:\yhzx\1027\helloai"
$sseFile = "$scriptDir\sse.txt"
Remove-Item $sseFile -ErrorAction SilentlyContinue

# 1) Start-Job: curl -N write to sse.txt (proven in v2/v3)
Write-Output "=== [1] Start SSE long connection (background Job) ==="
$job = Start-Job -ScriptBlock {
    curl.exe -i -N http://localhost:6565/mcp/sse 2>$null | Out-File -Encoding ascii "$using:scriptDir\sse.txt"
}
Start-Sleep -Seconds 3
Get-Content $sseFile -ErrorAction SilentlyContinue
Write-Output ""

# 2) Extract sessionId
$sid = (Select-String -Path $sseFile -Pattern 'sessionId=([A-Za-z0-9-]+)' -ErrorAction SilentlyContinue).Matches.Groups[1].Value
Write-Output "Extracted sessionId = $sid"
if ([string]::IsNullOrEmpty($sid)) {
    Write-Error "sessionId extraction failed, sse.txt empty" -ErrorAction Continue
    Stop-Job $job -PassThru | Remove-Job -Force
    return
}
Write-Output ""

# 3) Helper: POST + read SSE new content
function Send-McpRequest {
    param([string]$Body, [string]$Label)
    Write-Output "=== $Label ==="
    Write-Output "Body: $Body"

    $posBefore = (Get-Item $sseFile).Length

    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        $content = [System.Net.Http.StringContent]::new(
            $Body,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
        $uri = "$url/mcp/messages?sessionId=$sid"
        $response = $client.PostAsync($uri, $content).Result
        $respBody = $response.Content.ReadAsStringAsync().Result
        Write-Output "POST Status: $($response.StatusCode)"
        Write-Output "POST Body: '$respBody' (Note: spring-ai MCP response via SSE)"
    } catch {
        Write-Output "POST EXCEPTION: $($_.Exception.Message)"
    }

    Start-Sleep -Seconds 2
    $posAfter = (Get-Item $sseFile).Length
    Write-Output "--- SSE stream new content (offset $posBefore -> $posAfter) ---"
    if ($posAfter -gt $posBefore) {
        $reader = [System.IO.File]::Open($sseFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $reader.Position = $posBefore
        $sseReader = New-Object System.IO.StreamReader($reader, [System.Text.Encoding]::UTF8)
        $newContent = $sseReader.ReadToEnd()
        $sseReader.Close()
        $reader.Close()
        Write-Output $newContent
    } else {
        Write-Output "(no new content)"
    }
    Write-Output ""
    Write-Output ""
}

# 4) Tests
Send-McpRequest -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{}}' -Label "[2] minimal initialize"
Send-McpRequest -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ps-test","version":"0.0.1"}}}' -Label "[3] standard initialize"
Send-McpRequest -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -Label "[4] notifications/initialized"
Send-McpRequest -Body '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' -Label "[5] tools/list"
Send-McpRequest -Body '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getAgentStatus","arguments":{"agentId":1}}}' -Label "[6] tools/call getAgentStatus"

# 5) Cleanup
Write-Output "=== Cleanup ==="
Stop-Job $job -PassThru | Remove-Job -Force
