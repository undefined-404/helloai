# qoder-ceshi Agent 后台轮询脚本
# Agent ID: 2078110337491955714
# 用途：REST 轮询收件箱与可认领子任务，发现新任务时输出告警
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$apiBase = 'http://localhost:6565'
$apiKey  = 'ak_cbf5e0d7ea0a37639f0988d7f5664013'
$agentId = '2078110337491955714'
$logDir  = Join-Path $PSScriptRoot 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$logFile = Join-Path $logDir ("qoder-ceshi-poll-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".log")
$pollSeconds = 30

function Write-Log {
    param([string]$msg)
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

$hdr = @{ Authorization = "Bearer $apiKey" }

Write-Log ("Poll loop started. agentId=" + $agentId + ", interval=" + $pollSeconds + "s, log=" + $logFile)

$idleCount = 0
while ($true) {
    try {
        $inboxCount = Invoke-RestMethod -Uri ($apiBase + '/api/agent/inbox/count') -Headers $hdr -Method GET
        $unread = 0
        if ($inboxCount.data -and $inboxCount.data.total_unread) { $unread = [int]$inboxCount.data.total_unread }

        if ($unread -gt 0) {
            $inbox = Invoke-RestMethod -Uri ($apiBase + '/api/agent/inbox') -Headers $hdr -Method GET
            Write-Log ("INBOX ALERT: unread=" + $unread + " payload=" + ($inbox | ConvertTo-Json -Depth 6 -Compress))
            $idleCount = 0
        }

        $available = Invoke-RestMethod -Uri ($apiBase + '/api/sub-tasks/available') -Headers $hdr -Method GET
        if ($available.data -and ($available.data | ConvertTo-Json -Compress) -ne '[]' -and $available.data -ne $null) {
            $cnt = @($available.data).Count
            if ($cnt -gt 0) {
                Write-Log ("AVAILABLE SUB-TASKS: count=" + $cnt + " ids=" + (($available.data | ForEach-Object { $_.id }) -join ','))
                $idleCount = 0
            }
        }

        if ($unread -eq 0) {
            $idleCount++
            if ($idleCount % 10 -eq 0) {
                Write-Log ("idle heartbeat #" + $idleCount + ", inbox=0")
            }
        }
    } catch {
        Write-Log ("poll error: " + $_.Exception.Message)
    }
    Start-Sleep -Seconds $pollSeconds
}
