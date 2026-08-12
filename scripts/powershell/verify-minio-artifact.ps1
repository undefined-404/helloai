# ============================================================
# helloai MinIO 附件存储集成 verifier (v2.7, v1.0)
# 用途：验证 v2.7 MinIO 集成后的附件链路：
#   G1  MinIO 服务健康（29000 /minio/health/live）
#   G2  平台附件列表存在 minio:// 附件（storageUrl 前缀正确、bucket/objectKey 已落库）
#   G3  minio:// 附件平台直读：下载接口返回 200 + 非空字节 + Content-Disposition
#       （v2.7 起 isContentLoadable=true，不再 302 重定向）
# Ref:  doc/HelloAI_实现差距表.md (A0-5 遗留②：minio:// 外部存储平台不可直读)
#       doc/HelloAI_项目基线文档.md
#       .agents/skills/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码)
# 前置：docker compose up -d 起 helloai-minio；后端已重启（storage.type=minio）；
#       有任一子任务物化产出过附件（无 minio:// 附件时 G2/G3 输出 SKIP 与指引）。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\verify-minio-artifact.ps1
#   powershell ... -BaseUrl http://localhost:6565 -Token <登录 token> -MinioHealthUrl http://localhost:29000
# (all strings use single-quote + concat to avoid PS 5.1 parser issues)
# ============================================================

param(
    [string]$BaseUrl = 'http://localhost:6565',
    [string]$Token = '',
    [string]$MinioHealthUrl = 'http://localhost:29000'
)

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

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

# ---------- G1: MinIO 服务健康 ----------
$healthOk = $false
try {
    $resp = Invoke-WebRequest -Uri $MinioHealthUrl -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
    $healthOk = ($resp.StatusCode -eq 200)
} catch {
    $healthOk = $false
}
Assert-Pass $healthOk 'G1-MinIO-health' ('MinIO health check ' + $MinioHealthUrl)

if (-not $healthOk) {
    Write-Output ''
    Write-Output '[G1] 前置失败：MinIO 未就绪，先执行 docker compose up -d（helloai-minio 映射 29000/29001）。'
    Write-Output ('PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
    exit 1
}

# ---------- G2: 平台附件列表存在 minio:// 附件 ----------
if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Output ''
    Write-Output '[G2] 未提供 -Token，跳过平台侧验证（仅完成 MinIO 健康检查）。'
    Write-Output ('PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
    exit 0
}

$headers = @{ Authorization = ('Bearer ' + $Token) }
$attachments = $null
try {
    $listResp = Invoke-WebRequest -Uri ($BaseUrl + '/api/attachments') -Headers $headers -UseBasicParsing -TimeoutSec 15 -ErrorAction Stop
    $listJson = $listResp.Content | ConvertFrom-Json
    $attachments = $listJson.data
} catch {
    $attachments = $null
}

if ($null -eq $attachments) {
    Assert-Pass $false 'G2-attachment-list' ('附件列表接口不可用或返回异常: ' + $BaseUrl + '/api/attachments')
} elseif ($attachments.Count -eq 0) {
    Assert-Skip 'G2-attachment-list' '平台暂无附件记录（先跑一次执行让物化链产出附件）'
} else {
    $minioAtts = @($attachments | Where-Object { $_.storageUrl -like 'minio://*' })
    if ($minioAtts.Count -eq 0) {
        $localCount = @($attachments | Where-Object { $_.storageUrl -like 'local://*' }).Count
        Assert-Skip 'G2-attachment-list' ('无 minio:// 附件（存量 local:// 附件 ' + $localCount + ' 条；先跑一次执行验证新物化链路）')
    } else {
        Assert-Pass $true 'G2-attachment-list' ('存在 minio:// 附件 ' + $minioAtts.Count + ' 条，storageUrl/bucket/objectKey 已落库')
        $sample = $minioAtts[0]
        $objKey = $sample.objectKey
        $ok = $objKey -match '^[^/]+/\d{4}/\d{2}/\d+/\d+/[0-9a-f]{8}-.+$'
        Assert-Pass $ok 'G2-objectKey-rule' ('objectKey 按 归属者/年/月/taskId/subTaskId 组织: ' + $objKey)
    }
}

# ---------- G3: minio:// 附件平台直读（下载 200 + 非空 + 不重定向） ----------
$sampleAtt = $null
if ($null -ne $attachments) {
    $cands = @($attachments | Where-Object { $_.storageUrl -like 'minio://*' })
    if ($cands.Count -gt 0) {
        $sampleAtt = $cands[0]
    }
}

if ($null -eq $sampleAtt) {
    Assert-Skip 'G3-minio-download' '无 minio:// 附件可下载，跳过直读验证'
} else {
    try {
        $dl = Invoke-WebRequest -Uri ($BaseUrl + '/api/attachments/downloadById/' + $sampleAtt.id) `
            -Headers $headers -UseBasicParsing -TimeoutSec 20 -ErrorAction Stop
        $disposition = [string]$dl.Headers['Content-Disposition']
        $statusOk = ($dl.StatusCode -eq 200)
        $nonEmpty = ($dl.RawContentLength -gt 0)
        $isRedirect = $dl.BaseResponse.ResponseUri.AbsoluteUri -ne ($BaseUrl + '/api/attachments/downloadById/' + $sampleAtt.id)
        Assert-Pass ($statusOk -and $nonEmpty) 'G3-minio-download' ('附件 ' + $sampleAtt.id + ' 下载 200 + 字节 ' + $dl.RawContentLength)
        Assert-Pass (-not $isRedirect) 'G3-no-redirect' 'minio:// 附件平台直读（未 302 到外部地址）'
        Assert-Pass ($disposition -match 'attachment') 'G3-content-disposition' '响应带 Content-Disposition attachment'
    } catch {
        Assert-Pass $false 'G3-minio-download' ('附件 ' + $sampleAtt.id + ' 下载失败: ' + $_.Exception.Message)
    }
}

Write-Output ''
Write-Output ('SUMMARY PASS=' + $global:PassCount + ' FAIL=' + $global:FailCount + ' SKIP=' + $global:SkipCount)
if ($global:FailCount -gt 0) {
    exit 1
}
exit 0
