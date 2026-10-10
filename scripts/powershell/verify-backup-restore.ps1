# ============================================================
# verify-backup-restore.ps1
# ============================================================
# HelloAI REF-2.3 / 2.3b backup + restore drill verifier
#
# Covers (S0..S7):
#   S0  admin login
#   S1  trigger manual backup -> poll ledger until SUCCESS
#   S2  ledger fields: pgVersion / flywayMaxVersion == code max / dumpBytes /
#       artifactCount / checksumSha256 / objectPrefix
#   S3  DB stays usable (backup does NOT lock the database, D-2026-10-10-2)
#   S4  restore preflight endpoint returns a verdict (read-only, no side effect)
#   S5  REAL restore drill into a THROWAWAY probe database, then reconcile counts
#   S6  cleanup (probe DB / local dump / backup objects / ledger row)
#   S7  prints why the app's destructive restore endpoint is NOT auto-invoked
#
# SAFETY: the restore drill NEVER touches the configured database. It creates a
#       freshly-named probe DB ($ProbeDb), restores there, reconciles, drops it.
#       The app's own /api/backup/{id}/restore endpoint is deliberately NOT called
#       (it can only target the configured DB, i.e. the developer's working DB).
#
# NOTE: PowerShell 5.1 + zh-CN Windows. All runtime strings are 100% ASCII.
#
# NOTE on the binary dump: PowerShell 5.1 mangles binary data when it goes
#       through a pipeline / redirection (it re-encodes text). The dump is
#       therefore pulled with a .NET Process whose stdout is copied via
#       BaseStream -- the pattern the repo preflight skill prescribes for
#       "raw bytes through an external command".
#
# Usage (any cwd, PowerShell):
#   powershell -File .\scripts\powershell\verify-backup-restore.ps1
#   powershell -File .\scripts\powershell\verify-backup-restore.ps1 -AdminPassword xxx
#   (admin password via $env:HELLOAI_ADMIN_PASSWORD or -AdminPassword)
#
# NOTE: paths are resolved RELATIVE TO THE REPO ROOT, never hardcoded absolute
#       (CODE_STYLE sec 39).
# ============================================================
param(
    [string]$RepoRoot = "",
    [string]$AdminPassword = "",
    [string]$BaseUrl = "http://localhost:6565",
    [string]$PgContainer = "helloai-postgres",
    [string]$MinioContainer = "helloai-minio",
    [string]$PgPort = "15432",
    [string]$PgUser = "postgres",
    [string]$ProbeDb = "helloai_restore_probe",
    # dedicated backup bucket (helloai.backup.bucket). NOT helloai.storage.minio-bucket.
    [string]$BackupBucket = "helloai-backups"
)

# ------------------------------------------------------------
# UTF-8 encoding header (rule 6)
# ------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = $env:HELLOAI_ADMIN_PASSWORD }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw 'admin password not set: export HELLOAI_ADMIN_PASSWORD (or pass -AdminPassword)' }
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

Add-Type -AssemblyName System.Net.Http

$script:SkillRelPath = "scripts\powershell\verify-backup-restore.ps1"

function Resolve-RepoRoot {
    param([string]$Explicit, [string[]]$StartDirs)
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
        try { return (Resolve-Path -LiteralPath $Explicit -ErrorAction Stop).Path }
        catch { throw ('RepoRoot not found: ' + $Explicit) }
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

$resolvedRoot = Resolve-RepoRoot -Explicit $RepoRoot -StartDirs @($PSScriptRoot, (Get-Location).Path)
if ([string]::IsNullOrWhiteSpace($resolvedRoot)) {
    Write-Error ('repo root not found: searched upward for ' + $script:SkillRelPath + '; pass -RepoRoot <path>.')
    exit 1
}

$passCount = 0
$failCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) { $script:passCount++; Write-Output ('[PASS] ' + $Message) }
    else { $script:failCount++; Write-Output ('[FAIL] ' + $Message) }
}

# ============================================================
# helpers
# ============================================================
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(60)

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

# docker exec psql -tAc "<sql>" ; returns trimmed stdout (text only, never binary)
function Invoke-Psql {
    param([string]$Db, [string]$Sql)
    $Sql = $Sql.TrimStart([char]0xFEFF)   # strip source-file BOM that can leak into literals
    $out = & docker exec $PgContainer psql -U $PgUser -d $Db -tAc $Sql 2>&1
    return (($out | Out-String).Trim())
}

# Pull a MinIO object to a local file WITHOUT mangling bytes (see header note).
function Save-MinioObject {
    param([string]$ContainerPath, [string]$LocalPath)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'docker'
    $psi.Arguments = 'exec ' + $MinioContainer + ' mc cat ' + $ContainerPath
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $fs = [System.IO.File]::Create($LocalPath)
    try { $p.StandardOutput.BaseStream.CopyTo($fs) } finally { $fs.Dispose() }
    $p.WaitForExit()
    return $p.ExitCode
}

# Object key -> present in the backup bucket? (mc stat exit code; no binary goes through PS)
function Test-MinioObject {
    param([string]$Key)
    & docker exec $MinioContainer mc stat ('bk/' + $BackupBucket + '/' + $Key) 2>&1 | Out-Null
    return ($LASTEXITCODE -eq 0)
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
$login = Invoke-Api -Method 'Post' -Uri ($BaseUrl + '/api/auth/login') -Body ('{"type":"admin","username":"admin","credential":"' + $AdminPassword + '"}')
$token = ($login.Body | ConvertFrom-Json).data.token
if ([string]::IsNullOrWhiteSpace($token)) { Write-Error 'admin login failed'; exit 1 }
$adminHeaders = @{ 'X-Admin-Token' = $token }

# 代码内已知最高迁移（与 App 的 MigrationVersionResolver 同口径：取 db/migration 下最大 V 号）
$codeMax = 0
Get-ChildItem (Join-Path $resolvedRoot 'helloai-start\src\main\resources\db\migration\V*.sql') -ErrorAction SilentlyContinue |
    ForEach-Object { if ($_.Name -match '^V(\d+)__') { $n = [int]$Matches[1]; if ($n -gt $codeMax) { $codeMax = $n } } }
Write-Output ('code max migration = V' + $codeMax)

# ============================================================
# STEP 1: trigger a manual backup and poll
# ============================================================
Write-Output '=== [S1] trigger manual backup + poll ==='
$t1 = Invoke-Api -Method 'Post' -Uri ($BaseUrl + '/api/backup') -Headers $adminHeaders
Write-Output ('trigger HTTP ' + $t1.Code)
Assert-True ($t1.Code -eq 200) ('S1 trigger -> 200 (got ' + $t1.Code + ')')
$row = $null
try { $row = ($t1.Body | ConvertFrom-Json).data } catch {}
Assert-True ($null -ne $row -and $row.state -eq 'RUNNING') 'S1 trigger returns immediately with state=RUNNING (async shape)'
$backupId = $row.id

$final = $null
for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep -Seconds 5
    $d = Invoke-Api -Method 'Get' -Uri ($BaseUrl + '/api/backup/' + $backupId) -Headers $adminHeaders
    $final = ($d.Body | ConvertFrom-Json).data
    Write-Output ('  poll[' + $i + '] state=' + $final.state)
    if ($final.state -eq 'SUCCESS' -or $final.state -eq 'FAILED') { break }
}
Assert-True ($null -ne $final -and $final.state -eq 'SUCCESS') ('S1 backup reaches SUCCESS (state=' + $final.state + ', reason=' + $final.failureReason + ')')
if ($null -eq $final -or $final.state -ne 'SUCCESS') {
    Write-Output 'SOME CHECKS FAILED (backup did not succeed; aborting before drill)'
    $script:client.Dispose()
    exit 1
}

# ============================================================
# STEP 2: ledger fields
# ============================================================
Write-Output '=== [S2] ledger fields ==='
$runtimeMajor = [int](Invoke-Psql -Db 'postgres' -Sql "SELECT current_setting('server_version_num')::int / 10000")
Write-Output ('runtime PG major = ' + $runtimeMajor + ' / pgVersion=' + $final.pgVersion)
Assert-True ($final.pgVersion -match ('^' + $runtimeMajor + '\.')) 'S2 pgVersion major matches runtime PG major'
# 这一条正是字典序 bug 的守卫：库在 V105 时 max(version) 会给出 "99"
Assert-True ([int]$final.flywayMaxVersion -eq $codeMax) ('S2 flywayMaxVersion == code max V' + $codeMax + ' (got ' + $final.flywayMaxVersion + ')')
Assert-True ([int]$final.dumpBytes -gt 0) ('S2 dumpBytes > 0 (got ' + $final.dumpBytes + ')')
Assert-True ([int]$final.artifactCount -ge 0) ('S2 artifactCount reported (got ' + $final.artifactCount + ')')
Assert-True (-not [string]::IsNullOrWhiteSpace($final.checksumSha256)) 'S2 checksumSha256 present'
Assert-True (-not [string]::IsNullOrWhiteSpace($final.objectPrefix)) 'S2 objectPrefix present'
# The API contract exposes objectPrefix only (no manifestKey/dumpKey) -- verify the
# three artifacts really landed under it, rather than asserting hidden fields.
Assert-True (Test-MinioObject ($final.objectPrefix + 'database.dump')) 'S2 dump object present under objectPrefix'
Assert-True (Test-MinioObject ($final.objectPrefix + 'manifest.json')) 'S2 manifest object present under objectPrefix'
Assert-True (Test-MinioObject ($final.objectPrefix + 'artifacts.json')) 'S2 artifacts inventory present under objectPrefix'

# ============================================================
# STEP 3: database stayed usable (backup must not lock the DB)
# ============================================================
Write-Output '=== [S3] database usable after backup (no lock held) ==='
$q = Invoke-Psql -Db 'helloai' -Sql 'SELECT 1'
Assert-True ($q -eq '1') 'S3 DB answers queries right after backup (no lingering lock)'

# ============================================================
# STEP 4: restore preflight (read-only)
# ============================================================
Write-Output '=== [S4] restore preflight (read-only) ==='
$pf = Invoke-Api -Method 'Post' -Uri ($BaseUrl + '/api/backup/' + $backupId + '/restore/preflight') -Headers $adminHeaders
Write-Output ('preflight HTTP ' + $pf.Code + ' body=' + $pf.Body)
Assert-True ($pf.Code -eq 200) ('S4 preflight -> 200 (got ' + $pf.Code + ')')
$verdict = $null
try { $verdict = ($pf.Body | ConvertFrom-Json).data } catch {}
Assert-True ($null -ne $verdict -and $null -ne $verdict.restorable) 'S4 preflight returns a verdict (restorable=true/false)'
Assert-True ($verdict.pgVersion -match ('^' + $runtimeMajor + '\.')) 'S4 preflight reports the archive pgVersion'

# ============================================================
# STEP 5: REAL restore drill into a throwaway probe DB
# ============================================================
Write-Output '=== [S5] restore drill into probe DB (never touches the working DB) ==='
Write-Output ('  target probe DB = ' + $ProbeDb + ' (working DB is NOT touched)')
$probeDir = Join-Path $resolvedRoot '.tmp\backup-restore-drill'
if (Test-Path -LiteralPath $probeDir) { Remove-Item -LiteralPath $probeDir -Recurse -Force }
New-Item -ItemType Directory -Path $probeDir -Force | Out-Null
$dumpPath = Join-Path $probeDir 'probe.dump'

# mc path = alias/bucket/objectKey. NOTE: dumpKey already carries the key-prefix
# ("backups/manual/..."), so the bucket must come from config -- deriving it from
# objectPrefix[0] would mistake the key-prefix "backups" for a bucket name.
$dumpKey = $final.objectPrefix + 'database.dump'
$mcPath = 'bk/' + $BackupBucket + '/' + $dumpKey
Write-Output ('  pulling ' + $BackupBucket + '/' + $dumpKey)

# 确保 mc 已配别名（本地默认 minioadmin/minioadmin123，与 application-local.yml 一致）
& docker exec $MinioContainer mc alias set bk http://localhost:9000 minioadmin minioadmin123 2>&1 | Out-Null
$pullExit = Save-MinioObject -ContainerPath $mcPath -LocalPath $dumpPath
$dumpSize = 0
if (Test-Path -LiteralPath $dumpPath) { $dumpSize = (Get-Item -LiteralPath $dumpPath).Length }
Write-Output ('  mc exit=' + $pullExit + ' local dump bytes=' + $dumpSize)
Assert-True ($pullExit -eq 0 -and $dumpSize -gt 0) ('S5 dump pulled from object storage (' + $dumpSize + ' bytes)')

# 建探针库 -> 恢复 -> 对账 -> 删库
Invoke-Psql -Db 'postgres' -Sql ('DROP DATABASE IF EXISTS ' + $ProbeDb) | Out-Null
Invoke-Psql -Db 'postgres' -Sql ('CREATE DATABASE ' + $ProbeDb) | Out-Null

$pgRestore = Join-Path $resolvedRoot '.tools\pgsql\bin\pg_restore.exe'
if (-not (Test-Path -LiteralPath $pgRestore)) { $pgRestore = 'pg_restore' }
$env:PGPASSWORD = 'postgres'
& $pgRestore -h localhost -p $PgPort -U $PgUser -d $ProbeDb --no-owner --no-privileges -j 4 $dumpPath 2>&1 | Out-Null
$restoreExit = $LASTEXITCODE
Write-Output ('  pg_restore exit=' + $restoreExit)
Assert-True ($restoreExit -eq 0) ('S5 pg_restore into probe DB succeeded (exit=' + $restoreExit + ')')

# 对账：表数 + 若干大表行数
$srcTables = [int](Invoke-Psql -Db 'helloai' -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
$dstTables = [int](Invoke-Psql -Db $ProbeDb -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
Write-Output ('  tables source=' + $srcTables + ' probe=' + $dstTables)
Assert-True ($srcTables -gt 0 -and $srcTables -eq $dstTables) ('S5 table count reconciled (' + $srcTables + ' == ' + $dstTables + ')')

foreach ($t in @('agent', 'task', 'sub_task', 'attachment')) {
    $a = Invoke-Psql -Db 'helloai' -Sql ('SELECT count(*) FROM ' + $t)
    $b = Invoke-Psql -Db $ProbeDb -Sql ('SELECT count(*) FROM ' + $t)
    Assert-True ($a -eq $b) ('S5 row count reconciles: ' + $t + ' ' + $a + ' == ' + $b)
}

# ============================================================
# STEP 6: cleanup
# ============================================================
Write-Output '=== [S6] cleanup ==='
Invoke-Psql -Db 'postgres' -Sql ('DROP DATABASE IF EXISTS ' + $ProbeDb) | Out-Null
$probeGone = Invoke-Psql -Db 'postgres' -Sql ("SELECT count(*) FROM pg_database WHERE datname='" + $ProbeDb + "'")
Assert-True ($probeGone -eq '0') 'S6 probe DB dropped'
if (Test-Path -LiteralPath $probeDir) { Remove-Item -LiteralPath $probeDir -Recurse -Force }

# remove ONLY this run's prefix dir -- never the whole bucket (older backups live beside it)
$objectDir = ('bk/' + $BackupBucket + '/' + $final.objectPrefix).TrimEnd('/')
& docker exec $MinioContainer mc rm --recursive --force $objectDir 2>&1 | Out-Null
& docker exec $MinioContainer mc stat ($mcPath) 2>&1 | Out-Null
Assert-True ($LASTEXITCODE -ne 0) 'S6 backup objects removed from object storage (dump object gone)'
$beforeDelete = Invoke-Psql -Db 'helloai' -Sql ('SELECT count(*) FROM platform_backup WHERE id=' + $backupId)
Assert-True ($beforeDelete -eq '1') 'S6 ledger row present before cleanup'
Invoke-Psql -Db 'helloai' -Sql ('DELETE FROM platform_backup WHERE id=' + $backupId) | Out-Null
$after = Invoke-Psql -Db 'helloai' -Sql ('SELECT count(*) FROM platform_backup WHERE id=' + $backupId)
Assert-True ($after -eq '0') 'S6 ledger row removed'

# ============================================================
# STEP 7: explicitly NOT auto-invoking the destructive restore
# ============================================================
Write-Output '=== [S7] destructive restore endpoint NOT auto-invoked ==='
Write-Output '  /api/backup/{id}/restore can only target the CONFIGURED database,'
Write-Output '  i.e. the developer working DB. It is a documented STOP-THE-WORLD'
Write-Output '  operation (REF-2.4), driven by an operator, not by an automated check.'
Write-Output '  The three illegal-restore rejection gates are covered by unit tests:'
Write-Output '    helloai-core/src/test/java/com/helloai/core/system/backup/RestoreGateTest.java'
Assert-True (Test-Path -LiteralPath (Join-Path $resolvedRoot 'helloai-core\src\test\java\com\helloai\core\system\backup\RestoreGateTest.java')) 'S7 rejection-gate unit test present (three must-reject cases)'

# ============================================================
# summary
# ============================================================
$client.Dispose()
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
