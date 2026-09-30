# ============================================================
# helloai MinIO bucket cleanup tool (v1.0, 2026-09-30)
# 用途：清理 MinIO 桶对象 —— 清库前置手段（P-1 批次 A4）。
#   场景：attachment 表 TRUNCATE / 清测试数据后，桶内对象会成孤儿（DB 无记录、桶里有对象）；
#         本脚本提供「先看后删」的安全入口：
#           - 默认 dry-run：分页列举 + 统计（对象数/字节/顶层前缀分布/抽样），不删任何东西；
#           - -Execute：对列举结果执行 DeleteObjects 批量删除（每批 <=1000），删后复核剩余为 0；
#           - -Prefix：只处理某前缀（如 'admin/2026/09/'），未指定则全桶。
#   背景：tku-e2e-01 复盘（审计报告 §13）指出「无 MinIO 清桶工具」是清库前置缺口（C-11）。
# 签名：纯 PowerShell + .NET（SHA256 / HMAC-SHA256 手写 SigV4），零外部依赖（不需要 mc / aws cli）。
# Ref:  doc/design/HelloAI_执行产出物化与结构化多文件产出方案.md（objectKey 规则）
#       doc/HelloAI_实现差距表.md（C-11：MinIO 清桶工具）
#       .agents/skills/helloai-preflight/SKILL.md（规则 6：脚本 UTF-8 编码）
# 前置：Docker 起 helloai-minio（本地 29000；dev 共享实例为 39.106.204.43:29000，见 application-dev.yml）。
# 用法（项目根）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\powershell\clean-minio-bucket.ps1
#       -> 默认对 http://localhost:29000 / helloai-artifacts 做 dry-run 列举（不删除）
#   ... -Endpoint http://39.106.204.43:29000        # dev 共享实例（只读列举同样适用）
#   ... -Prefix 'admin/2026/09/'                    # 只处理该前缀
#   ... -Execute                                    # 显式风险开关：真正执行删除
# (all strings use single-quote + concat to avoid PS 5.1 parser issues)
# ============================================================

param(
    [string]$Endpoint = 'http://localhost:29000',
    [string]$AccessKey = 'minioadmin',
    [string]$SecretKey = 'minioadmin123',
    [string]$Bucket = 'helloai-artifacts',
    [string]$Region = 'us-east-1',
    [string]$Prefix = '',
    [switch]$Execute
)

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Continue'

$script:BaseUrl = $Endpoint.TrimEnd('/')
$u = [System.Uri]$script:BaseUrl
$defaultPort = (($u.Scheme -eq 'http' -and $u.Port -eq 80) -or ($u.Scheme -eq 'https' -and $u.Port -eq 443))
$script:HostHeader = $(if ($defaultPort) { $u.Host } else { $u.Host + ':' + $u.Port })
$script:EmptySha256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

# ---------- 基础工具 ----------
function Get-Sha256Hex([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash($Bytes) } finally { $sha.Dispose() }
    return ([System.BitConverter]::ToString($hash)).Replace('-', '').ToLowerInvariant()
}

function Get-HmacSha256([byte[]]$Key, [string]$Data) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = $Key
    try { return $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Data)) } finally { $hmac.Dispose() }
}

# RFC3986 编码（S3 SigV4 要求）：仅保留 A-Z a-z 0-9 - _ . ~；keepSlash 时保留 /
function ConvertTo-S3Encoded([string]$Text, [bool]$KeepSlash) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $Text.ToCharArray()) {
        $code = [int]$ch
        $unreserved = (($code -ge 65 -and $code -le 90) -or ($code -ge 97 -and $code -le 122) `
            -or ($code -ge 48 -and $code -le 57) -or $code -eq 45 -or $code -eq 95 -or $code -eq 46 -or $code -eq 126)
        if ($unreserved) {
            [void]$sb.Append($ch)
        } elseif ($KeepSlash -and $ch -eq '/') {
            [void]$sb.Append('/')
        } else {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$ch)
            foreach ($b in $bytes) { [void]$sb.Append('%' + $b.ToString('X2')) }
        }
    }
    return $sb.ToString()
}

function ConvertTo-XmlEscaped([string]$Text) {
    return $Text.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;').Replace('"', '&quot;').Replace("'", '&apos;')
}

# ---------- SigV4 签名 ----------
function New-SigV4Auth {
    param(
        [string]$MethodName,
        [string]$CanonicalUri,
        [string]$CanonicalQuery,
        [string]$PayloadHash,
        [string]$ContentMd5Base64
    )
    $now = (Get-Date).ToUniversalTime()
    $amzDate = $now.ToString("yyyyMMdd'T'HHmmss'Z'")
    $dateStamp = $now.ToString('yyyyMMdd')

    $headerLines = @()
    if (-not [string]::IsNullOrEmpty($ContentMd5Base64)) {
        $headerLines += ('content-md5:' + $ContentMd5Base64)
    }
    $headerLines += ('host:' + $script:HostHeader)
    $headerLines += ('x-amz-content-sha256:' + $PayloadHash)
    $headerLines += ('x-amz-date:' + $amzDate)
    $headerLines = $headerLines | Sort-Object
    $signedHeaders = (($headerLines | ForEach-Object { $_.Split(':')[0] }) -join ';')
    $canonicalHeaders = (($headerLines -join "`n") + "`n")

    $canonicalRequest = $MethodName + "`n" + $CanonicalUri + "`n" + $CanonicalQuery + "`n" `
        + $canonicalHeaders + "`n" + $signedHeaders + "`n" + $PayloadHash
    $scope = $dateStamp + '/' + $Region + '/s3/aws4_request'
    $stringToSign = 'AWS4-HMAC-SHA256' + "`n" + $amzDate + "`n" + $scope + "`n" `
        + (Get-Sha256Hex ([System.Text.Encoding]::UTF8.GetBytes($canonicalRequest)))

    $kDate = Get-HmacSha256 ([System.Text.Encoding]::UTF8.GetBytes('AWS4' + $SecretKey)) $dateStamp
    $kRegion = Get-HmacSha256 $kDate $Region
    $kService = Get-HmacSha256 $kRegion 's3'
    $kSigning = Get-HmacSha256 $kService 'aws4_request'
    $signature = ([System.BitConverter]::ToString((Get-HmacSha256 $kSigning $stringToSign))).Replace('-', '').ToLowerInvariant()

    $authorization = 'AWS4-HMAC-SHA256 Credential=' + $AccessKey + '/' + $scope `
        + ', SignedHeaders=' + $signedHeaders + ', Signature=' + $signature
    return @{
        Authorization = $authorization
        AmzDate       = $amzDate
        AmzContentSha = $PayloadHash
        ContentMd5    = $ContentMd5Base64
    }
}

# ---------- HTTP 调用（HttpWebRequest，精确控制 header 与 query） ----------
function Invoke-S3Request {
    param(
        [string]$MethodName,
        [string]$Url,
        [hashtable]$Auth,
        [byte[]]$BodyBytes,
        [string]$ContentType
    )
    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.Method = $MethodName
    $request.Timeout = 30000
    $request.ReadWriteTimeout = 30000
    $request.ServicePoint.Expect100Continue = $false
    [void]$request.Headers.Add('Authorization', $Auth.Authorization)
    [void]$request.Headers.Add('x-amz-date', $Auth.AmzDate)
    [void]$request.Headers.Add('x-amz-content-sha256', $Auth.AmzContentSha)
    if (-not [string]::IsNullOrEmpty($Auth.ContentMd5)) {
        [void]$request.Headers.Add('Content-MD5', $Auth.ContentMd5)
    }
    if ($null -ne $BodyBytes) {
        $request.ContentType = $ContentType
        $request.ContentLength = $BodyBytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($BodyBytes, 0, $BodyBytes.Length) } finally { $stream.Close() }
    }
    try {
        $response = $request.GetResponse()
        $status = [int]$response.StatusCode
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $content = $reader.ReadToEnd() } finally { $reader.Close(); $response.Close() }
        return @{ Status = $status; Content = $content; Error = $null }
    } catch [System.Net.WebException] {
        $webResp = $_.Exception.Response
        if ($null -ne $webResp) {
            $status = [int]$webResp.StatusCode
            $reader = New-Object System.IO.StreamReader($webResp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            try { $content = $reader.ReadToEnd() } finally { $reader.Close(); $webResp.Close() }
            return @{ Status = $status; Content = $content; Error = $_.Exception.Message }
        }
        return @{ Status = 0; Content = ''; Error = $_.Exception.Message }
    } catch {
        return @{ Status = 0; Content = ''; Error = $_.Exception.Message }
    }
}

function Format-S3Error([hashtable]$Result) {
    $detail = 'HTTP ' + $Result.Status
    if (-not [string]::IsNullOrEmpty($Result.Content)) {
        try {
            $x = [xml]$Result.Content
            if ($null -ne $x.Error) {
                $detail = $detail + ' ' + [string]$x.Error.Code + ': ' + [string]$x.Error.Message
            }
        } catch {
            $snippet = $Result.Content
            if ($snippet.Length -gt 200) { $snippet = $snippet.Substring(0, 200) }
            $detail = $detail + ' ' + $snippet
        }
    } elseif (-not [string]::IsNullOrEmpty($Result.Error)) {
        $detail = $detail + ' ' + $Result.Error
    }
    return $detail
}

# ---------- S3 操作 ----------
function Get-ObjectPage([string]$ContinuationToken) {
    # canonical query 按 key 字典序：continuation-token < list-type < max-keys < prefix
    $parts = @()
    if (-not [string]::IsNullOrEmpty($ContinuationToken)) {
        $parts += ('continuation-token=' + (ConvertTo-S3Encoded $ContinuationToken $false))
    }
    $parts += 'list-type=2'
    $parts += 'max-keys=1000'
    if (-not [string]::IsNullOrEmpty($Prefix)) {
        $parts += ('prefix=' + (ConvertTo-S3Encoded $Prefix $false))
    }
    $canonicalQuery = ($parts -join '&')
    $canonicalUri = '/' + $Bucket
    $auth = New-SigV4Auth -MethodName 'GET' -CanonicalUri $canonicalUri `
        -CanonicalQuery $canonicalQuery -PayloadHash $script:EmptySha256 -ContentMd5Base64 ''
    $url = $script:BaseUrl + $canonicalUri + '?' + $canonicalQuery
    return Invoke-S3Request -MethodName 'GET' -Url $url -Auth $auth -BodyBytes $null -ContentType $null
}

function Remove-ObjectBatch([string[]]$Keys) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('<Delete xmlns="http://s3.amazonaws.com/doc/2006-03-01/">')
    foreach ($k in $Keys) {
        [void]$sb.Append('<Object><Key>' + (ConvertTo-XmlEscaped $k) + '</Key></Object>')
    }
    [void]$sb.Append('</Delete>')
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try { $md5Base64 = [System.Convert]::ToBase64String($md5.ComputeHash($bodyBytes)) } finally { $md5.Dispose() }

    $payloadHash = Get-Sha256Hex $bodyBytes
    $canonicalUri = '/' + $Bucket
    $canonicalQuery = 'delete='
    $auth = New-SigV4Auth -MethodName 'POST' -CanonicalUri $canonicalUri `
        -CanonicalQuery $canonicalQuery -PayloadHash $payloadHash -ContentMd5Base64 $md5Base64
    $url = $script:BaseUrl + $canonicalUri + '?delete'
    return Invoke-S3Request -MethodName 'POST' -Url $url -Auth $auth -BodyBytes $bodyBytes -ContentType 'application/xml'
}

# ---------- 主流程 ----------
Write-Output ('[health] MinIO health check: ' + $script:BaseUrl)
$health = $null
try {
    $health = Invoke-WebRequest -Uri ($script:BaseUrl + '/minio/health/live') -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
} catch {
    $health = $null
}
if ($null -eq $health -or $health.StatusCode -ne 200) {
    Write-Output '[health] FAIL : MinIO 未就绪（先 docker compose up -d，或检查 -Endpoint 是否可达）'
    exit 1
}
Write-Output '[health] PASS : HTTP 200'

Write-Output ('[target] endpoint=' + $script:BaseUrl + ' bucket=' + $Bucket `
    + ' prefix=' + $(if ([string]::IsNullOrEmpty($Prefix)) { '(全桶)' } else { $Prefix }))
Write-Output ('[mode]   ' + $(if ($Execute) { 'EXECUTE（将真实删除）' } else { 'DRY-RUN（只列举不删除）' }))

# 分页列举全部对象
$objects = New-Object System.Collections.Generic.List[object]
$token = ''
$pages = 0
$listFailed = $false
do {
    $page = Get-ObjectPage $token
    if ($page.Status -ne 200) {
        Write-Output ('[list] FAIL : ' + (Format-S3Error $page))
        $listFailed = $true
        break
    }
    $pages++
    $x = [xml]$page.Content
    $contents = @($x.ListBucketResult.Contents)
    foreach ($c in $contents) {
        if ($null -eq $c) { continue }
        $objects.Add([pscustomobject]@{ Key = [string]$c.Key; Size = [long]$c.Size })
    }
    $truncated = ([string]$x.ListBucketResult.IsTruncated -eq 'true')
    $token = [string]$x.ListBucketResult.NextContinuationToken
    if (-not $truncated) { $token = '' }
} while (-not [string]::IsNullOrEmpty($token))
if ($listFailed) { exit 1 }

$totalBytes = 0
foreach ($o in $objects) { $totalBytes += $o.Size }
Write-Output ('[list] 列举完成: 对象 ' + $objects.Count + ' 个, 合计 ' + $totalBytes + ' 字节, 分页 ' + $pages + ' 轮')

if ($objects.Count -eq 0) {
    Write-Output '[summary] 桶内（该前缀下）无对象，无需清理。'
    exit 0
}

# 顶层前缀分布（前 10）
Write-Output '[dist] 顶层前缀分布（前 10）:'
$groups = $objects | Group-Object { ($_.Key -split '/')[0] } | Sort-Object Count -Descending | Select-Object -First 10
foreach ($g in $groups) {
    $gb = 0
    foreach ($o in $g.Group) { $gb += $o.Size }
    Write-Output ('    ' + $g.Name + '  ' + $g.Count + ' 个 / ' + $gb + ' 字节')
}

# 抽样前 10 个 key
Write-Output '[sample] 抽样前 10 个对象:'
$max = [Math]::Min(10, $objects.Count)
for ($i = 0; $i -lt $max; $i++) {
    $o = $objects[$i]
    $shownKey = $o.Key
    if ($shownKey.Length -gt 120) { $shownKey = $shownKey.Substring(0, 120) + '...' }
    Write-Output ('    ' + $shownKey + ' (' + $o.Size + ' bytes)')
}

if (-not $Execute) {
    Write-Output ''
    Write-Output ('[DRY-RUN] 计划删除 ' + $objects.Count + ' 个对象（未执行任何删除）。')
    Write-Output '          确认无误后加 -Execute 真正删除；或用 -Prefix 缩小范围。'
    exit 0
}

# EXECUTE：批量删除（每批 <=1000）
Write-Output ''
Write-Output ('[EXECUTE] 开始批量删除 ' + $objects.Count + ' 个对象...')
$batchSize = 1000
$deleted = 0
$failed = 0
$batchNo = 0
for ($start = 0; $start -lt $objects.Count; $start += $batchSize) {
    $batchNo++
    $end = [Math]::Min($start + $batchSize, $objects.Count)
    $keys = @()
    for ($i = $start; $i -lt $end; $i++) { $keys += $objects[$i].Key }
    $result = Remove-ObjectBatch $keys
    if ($result.Status -eq 200) {
        # 解析 <DeleteResult> 中的错误条目（S3 允许部分失败仍返回 200）
        $batchErrors = 0
        try {
            $x = [xml]$result.Content
            $errs = @($x.DeleteResult.Error)
            $batchErrors = @($errs | Where-Object { $null -ne $_ }).Count
        } catch {
            $batchErrors = 0
        }
        $deleted += ($keys.Count - $batchErrors)
        $failed += $batchErrors
        Write-Output ('[batch ' + $batchNo + '] 删除 ' + $keys.Count + ' 个 -> HTTP 200（失败条目 ' + $batchErrors + '）')
    } else {
        $failed += $keys.Count
        Write-Output ('[batch ' + $batchNo + '] FAIL : ' + (Format-S3Error $result))
    }
}

# 复核
$verify = Get-ObjectPage ''
$remaining = -1
if ($verify.Status -eq 200) {
    $x = [xml]$verify.Content
    $remaining = @($x.ListBucketResult.Contents | Where-Object { $null -ne $_ }).Count
}
Write-Output ''
Write-Output ('[verify] 删除后复核: 剩余 ' + $(if ($remaining -ge 0) { $remaining } else { '不可枚举（复核请求失败）' }) + ' 个对象')
Write-Output ('[SUMMARY] 计划删除=' + $objects.Count + ' 成功=' + $deleted + ' 失败=' + $failed)
if ($failed -gt 0 -or $remaining -gt 0) { exit 1 }
exit 0
