# ============================================================
# verify-skill-package-install.ps1
# ============================================================
# HelloAI REF-1.6 skill package install e2e verifier
#
# Covers (S1..S9):
#   S0  admin login
#   S1  install v1.0.0                     -> 200
#   S2  re-install same version            -> 409 [VERSION_NOT_NEWER]
#   S3  install higher version w/o confirm -> 409 [NEEDS_CONFIRM]
#   S4  same + confirmUpgrade=true         -> 200 (v1.0.0 -> HISTORICAL)
#   S5  GET /api/skills/catalog contains it (dual source visible)
#   S5b requiredTools round-trip: declared -> persisted -> read back (REF-1.6 acceptance)
#   S6  activate v1.0.0 (rollback)         -> 200
#   S7  GET /api/skills/packages states    -> 1.0.0 ACTIVE / 1.1.0 HISTORICAL
#   S8  gate rejects traversal zip         -> 400 [BAD_PATH]
#   S9  uninstall all versions + catalog no longer contains it
#
# NOTE: PowerShell 5.1 + zh-CN Windows. All runtime strings are 100% ASCII.
#
# NOTE on zips: PS 5.1's Compress-Archive writes entry names with BACKSLASH
#       separators, which this repo's ingest gate rejects by design. So the
#       fixtures here are built with System.IO.Compression.ZipArchive and
#       forward-slash entry names -- exactly what a compliant packer produces.
#
# Usage (any cwd, PowerShell):
#   powershell -File .\scripts\powershell\verify-skill-package-install.ps1
#   powershell -File .\scripts\powershell\verify-skill-package-install.ps1 -AdminPassword xxx
#   (admin password via $env:HELLOAI_ADMIN_PASSWORD or -AdminPassword)
#
# NOTE: paths are resolved RELATIVE TO THE REPO ROOT, never hardcoded absolute
#       (CODE_STYLE sec 39). Smoke cache files live under $RepoRoot/.tmp.
# ============================================================
param(
    [string]$RepoRoot = "",
    [string]$AdminPassword = "",
    [string]$BaseUrl = "http://localhost:6565"
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = $env:HELLOAI_ADMIN_PASSWORD }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw "未设置管理员口令：请导出环境变量 HELLOAI_ADMIN_PASSWORD（或传 -AdminPassword）" }
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$skillName = 'e2e-pkg-demo'
# 声明一个**真实已注册**的工具（@Tool 单一事实源里存在，见 eng-web-research 的 requiredTools），
# 用于行使 REF-1.6 验收的「requiredTools 一致」——用不存在的名字会让该断言失去意义。
$script:DeclaredTool = 'web_search'

# ------------------------------------------------------------
# repo root resolution (CODE_STYLE sec 39: no hardcoded local absolute path)
# ------------------------------------------------------------
$script:SkillRelPath = "scripts\powershell\verify-skill-package-install.ps1"

function Resolve-RepoRoot {
    param([string]$Explicit, [string[]]$StartDirs)
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
        try { return (Resolve-Path -LiteralPath $Explicit -ErrorAction Stop).Path }
        catch { throw "RepoRoot not found: $Explicit" }
    }
    foreach ($start in $StartDirs) {
        if ([string]::IsNullOrWhiteSpace($start)) { continue }
        $dir = $start
        while (-not [string]::IsNullOrWhiteSpace($dir)) {
            if (Test-Path -LiteralPath (Join-Path $dir $script:SkillRelPath) -PathType Leaf) { return $dir }
            $parent = Split-Path -Parent $dir
            if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $dir) { break }
            $dir = $parent
        }
    }
    return $null
}

$repoStartDirs = @($PSScriptRoot, (Get-Location).Path)
$resolvedRoot = Resolve-RepoRoot -Explicit $RepoRoot -StartDirs $repoStartDirs
if ([string]::IsNullOrWhiteSpace($resolvedRoot)) {
    Write-Error ("repo root not found: searched upward for '" + $script:SkillRelPath + "'. Pass -RepoRoot <path>.")
    exit 1
}
$tmpDir = Join-Path $resolvedRoot '.tmp\skill-pkg-e2e'
if (Test-Path -LiteralPath $tmpDir) { Remove-Item -LiteralPath $tmpDir -Recurse -Force }
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null

$passCount = 0
$failCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) { $script:passCount++; Write-Output ('[PASS] ' + $Message) }
    else { $script:failCount++; Write-Output ('[FAIL] ' + $Message) }
}

# ============================================================
# HTTP helpers
# ============================================================
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(30)

# 必须用 HttpMethod 的**静态属性**建方法：`[HttpMethod]::new('Delete')` 造出来的是
# `Delete`（大小写不合规），会被服务端按「非法请求 Method」拒掉；更坑的是它回的是
# **HTTP 200** 而不是 400 —— 断言只看状态码时会静默假通过（本脚本首版就踩了这个）。
function Get-HttpMethod {
    param([string]$Method)
    switch ($Method.ToUpperInvariant()) {
        'GET'    { return [System.Net.Http.HttpMethod]::Get }
        'POST'   { return [System.Net.Http.HttpMethod]::Post }
        'PUT'    { return [System.Net.Http.HttpMethod]::Put }
        'DELETE' { return [System.Net.Http.HttpMethod]::Delete }
        default  { return [System.Net.Http.HttpMethod]::new($Method.ToUpperInvariant()) }
    }
}

function Invoke-Api {
    param([string]$Method, [string]$Uri, [string]$Body = '', [hashtable]$Headers = @{})
    $req = [System.Net.Http.HttpRequestMessage]::new((Get-HttpMethod $Method), $Uri)
    foreach ($k in $Headers.Keys) { $req.Headers.Add($k, $Headers[$k]) | Out-Null }
    if ($Body -ne '') {
        $req.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $resp = $script:client.SendAsync($req).Result
    return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
}

function Invoke-Install {
    param([string]$Uri, [string]$FilePath, [string]$Token)
    # 两个端点都声明 consumes=multipart/form-data；activate 允许不带包体（回滚场景），
    # 此时仍须发 multipart —— 发 JSON 会被 Spring 判为 415（脚本验证时踩过）。
    $mp = [System.Net.Http.MultipartFormDataContent]::new()
    if (-not [string]::IsNullOrWhiteSpace($FilePath)) {
        $bytes = [System.IO.File]::ReadAllBytes($FilePath)
        $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/zip')
        $mp.Add($fileContent, 'file', [System.IO.Path]::GetFileName($FilePath))
    } else {
        $mp.Add([System.Net.Http.StringContent]::new('1'), 'noop')
    }
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Uri)
    $req.Headers.Add('X-Admin-Token', $Token) | Out-Null
    $req.Content = $mp
    $resp = $script:client.SendAsync($req).Result
    return @{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
}

# Build a package zip via ZipArchive (forward-slash entry names; see header note).
function New-PackageZip {
    param([string]$Path, [string]$Version, [string]$ExtraEntryName = $null, [string]$ExtraEntryText = 'x')
    # requiredTools 用 YAML 块列表（与 skills/plugins/eng-web-research.md 同格式），
    # 且刻意声明一个**真实已注册**的工具——用于行使 REF-1.6 验收里的「requiredTools 一致」
    # （计划 :115）。此前 fixture 声明空数组，等于该验收项没行使。
    $manifest = @"
---
name: $skillName
version: $Version
description: e2e demo skill package
requiredTools:
  - $script:DeclaredTool
---
## 执行速览

1. e2e demo.
"@
    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create)
    try {
        $zip = [System.IO.Compression.ZipArchive]::new($fs, [System.IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            $e = $zip.CreateEntry('skill-package-manifest.md')
            $w = [System.IO.StreamWriter]::new($e.Open(), $script:Utf8NoBom)
            $w.Write($manifest); $w.Dispose()
            $e2 = $zip.CreateEntry('scripts/run.sh')
            $w2 = [System.IO.StreamWriter]::new($e2.Open(), $script:Utf8NoBom)
            $w2.Write("echo hi`n"); $w2.Dispose()
            if ($ExtraEntryName) {
                $e3 = $zip.CreateEntry($ExtraEntryName)
                $w3 = [System.IO.StreamWriter]::new($e3.Open(), $script:Utf8NoBom)
                $w3.Write($ExtraEntryText); $w3.Dispose()
            }
        } finally { $zip.Dispose() }
    } finally { $fs.Dispose() }
}

# ============================================================
# STEP 0: reachability + login
# ============================================================
Write-Output '=== [S0] server reachability + admin login ==='
try {
    $ping = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/health')
    Assert-True ($ping.Code -eq 200) ('S0 health: HTTP ' + $ping.Code)
} catch {
    Write-Error ('Server NOT reachable at ' + $BaseUrl)
    exit 1
}

$loginBody = '{"type":"admin","username":"admin","credential":"' + $AdminPassword + '"}'
$login = Invoke-Api -Method 'Post' -Uri ($BaseUrl + '/api/auth/login') -Body $loginBody
$token = ($login.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrWhiteSpace($token)) { Write-Error 'admin login failed'; exit 1 }
$adminHeaders = @{ 'X-Admin-Token' = $token }

# ============================================================
# STEP 0b: pre-clean leftovers of previous runs
# ============================================================
Write-Output '=== [S0b] pre-clean leftovers ==='
$list = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/skills/packages') -Headers $adminHeaders
$existing = @()
try { $existing = @(($list.Body | ConvertFrom-Json).data) } catch {}
$leftovers = @($existing | Where-Object { $_.name -eq $skillName })
foreach ($row in $leftovers) {
    $del = Invoke-Api -Method 'Delete' -Uri ($BaseUrl + '/api/skills/packages/' + $row.id) -Headers $adminHeaders
    Write-Output ('pre-clean removed id=' + $row.id + ' code=' + $del.Code)
}

# ============================================================
# STEP 1: install v1.0.0
# ============================================================
Write-Output '=== [S1] install v1.0.0 ==='
$zip100 = Join-Path $tmpDir 'demo-1.0.0.zip'
$zip110 = Join-Path $tmpDir 'demo-1.1.0.zip'
$zipBad = Join-Path $tmpDir 'demo-bad.zip'
New-PackageZip -Path $zip100 -Version '1.0.0'
New-PackageZip -Path $zip110 -Version '1.1.0'
New-PackageZip -Path $zipBad -Version '1.0.0' -ExtraEntryName '../../evil.txt'

$s1 = Invoke-Install -Uri ($BaseUrl + '/api/skills/packages') -FilePath $zip100 -Token $token
Write-Output ('install HTTP ' + $s1.Code)
Assert-True ($s1.Code -eq 200) ('S1 install v1.0.0 -> 200 (got ' + $s1.Code + ')')
$s1Data = $null
try { $s1Data = ($s1.Body | ConvertFrom-Json).data } catch {}
Assert-True ($null -ne $s1Data -and $s1Data.state -eq 'ACTIVE') 'S1 installed row state is ACTIVE'

# ============================================================
# STEP 2: re-install same version -> rejected
# ============================================================
Write-Output '=== [S2] re-install same version ==='
$s2 = Invoke-Install -Uri ($BaseUrl + '/api/skills/packages') -FilePath $zip100 -Token $token
Write-Output ('HTTP ' + $s2.Code + ' body=' + $s2.Body)
Assert-True ($s2.Code -eq 409 -and $s2.Body -match 'VERSION_NOT_NEWER') 'S2 same version rejected with [VERSION_NOT_NEWER]'

# ============================================================
# STEP 3: higher version without confirm -> 409 NEEDS_CONFIRM
# ============================================================
Write-Output '=== [S3] install v1.1.0 without confirm ==='
$s3 = Invoke-Install -Uri ($BaseUrl + '/api/skills/packages') -FilePath $zip110 -Token $token
Write-Output ('HTTP ' + $s3.Code + ' body=' + $s3.Body)
Assert-True ($s3.Code -eq 409 -and $s3.Body -match 'NEEDS_CONFIRM') 'S3 higher version needs confirmation'

# ============================================================
# STEP 4: same + confirmUpgrade=true -> 200
# ============================================================
Write-Output '=== [S4] install v1.1.0 with confirmUpgrade=true ==='
$s4 = Invoke-Install -Uri ($BaseUrl + '/api/skills/packages?confirmUpgrade=true') -FilePath $zip110 -Token $token
Write-Output ('HTTP ' + $s4.Code)
Assert-True ($s4.Code -eq 200) ('S4 confirmed upgrade -> 200 (got ' + $s4.Code + ')')

# ============================================================
# STEP 5: catalog shows the installed package (dual source)
# ============================================================
Write-Output '=== [S5] /api/skills/catalog contains installed package ==='
$cat = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/skills/catalog') -Headers $adminHeaders
$catNames = @()
try { $catNames = @(($cat.Body | ConvertFrom-Json).data | ForEach-Object { $_.name }) } catch {}
Write-Output ('catalog: ' + ($catNames -join ','))
Assert-True ($catNames -contains $skillName) ('S5 catalog contains ' + $skillName)
Assert-True ($catNames -contains 'eng-code-review') 'S5 built-in source still present (no regression)'

# ============================================================
# STEP 5b: requiredTools round-trip (REF-1.6 acceptance: plan :115)
#   声明 -> 落库 -> 回读，值必须一致。走列表端点而非安装响应，
#   因为安装响应是精简结果、不含 requiredTools。
# ============================================================
Write-Output '=== [S5b] requiredTools round-trip ==='
$ls5 = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/skills/packages') -Headers $adminHeaders
$row5 = $null
try {
    $row5 = @(($ls5.Body | ConvertFrom-Json).data |
        Where-Object { $_.name -eq $skillName -and $_.version -eq '1.0.0' }) | Select-Object -First 1
} catch {}
$tools5 = @()
if ($row5 -ne $null -and $row5.requiredTools -ne $null) { $tools5 = @($row5.requiredTools) }
Write-Output ('declared=[' + $script:DeclaredTool + '] readback=[' + ($tools5 -join ',') + ']')
Assert-True ($row5 -ne $null) 'S5b installed row is listable'
Assert-True ($tools5 -contains $script:DeclaredTool) ('S5b requiredTools round-trip: declared ' + $script:DeclaredTool + ' survives install')

# ============================================================
# STEP 6: rollback to v1.0.0
# ============================================================
Write-Output '=== [S6] activate (rollback) v1.0.0 ==='
$s6 = Invoke-Install -Uri ($BaseUrl + '/api/skills/packages/' + $skillName + '/activate?version=1.0.0') -FilePath '' -Token $token
Write-Output ('HTTP ' + $s6.Code + ' body=' + $s6.Body)
Assert-True ($s6.Code -eq 200) ('S6 rollback -> 200 (got ' + $s6.Code + ')')

# ============================================================
# STEP 7: states after rollback
# ============================================================
Write-Output '=== [S7] states after rollback ==='
$ls = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/skills/packages') -Headers $adminHeaders
$rows = @()
try { $rows = @(($ls.Body | ConvertFrom-Json).data | Where-Object { $_.name -eq $skillName }) } catch {}
$state10 = ($rows | Where-Object { $_.version -eq '1.0.0' }).state
$state11 = ($rows | Where-Object { $_.version -eq '1.1.0' }).state
Write-Output ('1.0.0=' + $state10 + ' 1.1.0=' + $state11)
Assert-True ($state10 -eq 'ACTIVE') 'S7 v1.0.0 is ACTIVE after rollback'
Assert-True ($state11 -eq 'HISTORICAL') 'S7 v1.1.0 is HISTORICAL after rollback'

# ============================================================
# STEP 8: gate rejects a traversal zip
# ============================================================
Write-Output '=== [S8] gate rejects traversal zip ==='
$s8 = Invoke-Install -Uri ($BaseUrl + '/api/skills/packages') -FilePath $zipBad -Token $token
Write-Output ('HTTP ' + $s8.Code + ' body=' + $s8.Body)
Assert-True ($s8.Code -eq 400 -and $s8.Body -match 'BAD_PATH') 'S8 traversal zip rejected with [BAD_PATH]'

# ============================================================
# STEP 9: uninstall all versions, catalog drops the package
# ============================================================
Write-Output '=== [S9] uninstall all versions ==='
foreach ($row in $rows) {
    $del = Invoke-Api -Method 'Delete' -Uri ($BaseUrl + '/api/skills/packages/' + $row.id) -Headers $adminHeaders
    Assert-True ($del.Code -eq 200) ('S9 uninstall ' + $row.version + ' -> 200 (got ' + $del.Code + ')')
}
$cat2 = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/skills/catalog') -Headers $adminHeaders
$cat2Names = @()
try { $cat2Names = @(($cat2.Body | ConvertFrom-Json).data | ForEach-Object { $_.name }) } catch {}
Assert-True (-not ($cat2Names -contains $skillName)) 'S9 catalog no longer contains the uninstalled package'

# ============================================================
# cleanup
# ============================================================
$client.Dispose()
if (Test-Path -LiteralPath $tmpDir) { Remove-Item -LiteralPath $tmpDir -Recurse -Force }

Write-Output ''
Write-Output '============================================================'
Write-Output ('RESULT: PASS=' + $passCount + ' FAIL=' + $failCount)
if ($failCount -eq 0) {
    Write-Output 'ALL PASSED'
    exit 0
} else {
    Write-Output 'SOME CHECKS FAILED'
    exit 1
}
