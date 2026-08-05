# 历史任务迭代记录回填脚本（V42）
# 用法：.\backfill-task-iterations.ps1
# 调用 POST /api/tasks/backfillTaskIterations 触法回填

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

param(
    [string]$BaseUrl = "http://localhost:6565"
)

$Endpoint = "$BaseUrl/api/tasks/backfillTaskIterations"

Write-Output ('V42 task_iteration history backfill')
Write-Output ('Endpoint: ' + $Endpoint)
Write-Output ''

try {
    $Response = Invoke-WebRequest -Uri $Endpoint -Method POST -ContentType 'application/json' -UseBasicParsing
    $Body = $Response.Content | ConvertFrom-Json
    Write-Output ('Status : ' + $Response.StatusCode)
    Write-Output ('Code   : ' + $Body.code)
    Write-Output ('Msg    : ' + $Body.msg)
    if ($Body.data) {
        Write-Output ('Backfilled : ' + $Body.data.backfilledCount + ' tasks')
    }
} catch {
    Write-Output ('FAIL: ' + $_.Exception.Message)
    if ($_.Exception.Response) {
        $Reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Output ('Response: ' + $Reader.ReadToEnd())
        $Reader.Close()
    }
    exit 1
}
