# ============================================================================
# HelloAI - Quickstart launcher (Windows / Docker Desktop, Linux containers)
#
# Goal: one command, no source tree, no JDK/Maven/Node, no local image build.
#   .\start.ps1                 core mode  (DB, Redis, MQ, MinIO, app, web)
#   .\start.ps1 --with-sandbox  + OpenSandbox (extra ~2 GB of images)
#   .\start.ps1 --offline       import .\helloai-offline-*.tar instead of pull
#   .\start.ps1 --no-pull       skip pull (use locally built images)
#
# This file is intentionally pure ASCII: PowerShell 5.1 parses an ASCII file
# safely on any codepage, while non-ASCII string literals can be misread as a
# string terminator (the "TerminatorExpectedAtEndOfString" class of failure).
# The encoding header below is still mandatory by repo rule 6 so that any
# value echoed to the console or piped to docker stays byte-clean.
#
# Chinese usage notes: see README.md in this directory.
# ============================================================================

# ------------------------------------------------------------
# UTF-8 encoding header (repo rule 6) - avoid mojibake
# ------------------------------------------------------------
$script:Utf8NoBom         = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'

$QS_DIR      = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $QS_DIR

$EnvFile     = Join-Path $QS_DIR '.env'
$ExampleFile = Join-Path $QS_DIR '.env.example'

$MinMemCoreMb    = 3500
$MinMemSandboxMb = 5500

$WithSandbox = $false
$Offline     = $false
$NoPull      = $false

# ---------------------------------------------------------------- helpers
function Write-Log  { param([string]$Message) Write-Host ('[helloai] ' + $Message) }
function Write-Warn { param([string]$Message) Write-Host ('[helloai][warn] ' + $Message) -ForegroundColor Yellow }
function Stop-WithError {
    param([string]$Message)
    Write-Host ('[helloai][error] ' + $Message) -ForegroundColor Red
    exit 1
}

# Raw docker compose invocation. No param block on purpose: $args keeps
# switches such as '-d' and '--profile' from being parsed as PowerShell
# parameters. Returns the exit code instead of throwing.
function Invoke-Dc {
    $dockerArgs = @('compose', '-f', 'docker-compose.yml') + $args
    & docker @dockerArgs
    return $LASTEXITCODE
}

function Invoke-DcStrict {
    $code = Invoke-Dc @args
    if ($code -ne 0) {
        Stop-WithError ('docker compose ' + ($args -join ' ') + ' failed (exit ' + $code + ')')
    }
}

function Show-Usage {
    @'
HelloAI Quickstart

  .\start.ps1                 start the core stack
  .\start.ps1 --with-sandbox  also enable OpenSandbox skill execution
  .\start.ps1 --offline       load .\helloai-offline-*.tar instead of pulling
  .\start.ps1 --no-pull       do not pull (use images already on this host)
  .\start.ps1 --help          show this message

Environment overrides (edit .env instead of the shell):
  HELLOAI_HTTP_PORT     host port for the web entry (default 8080)
  HELLOAI_SANDBOX_PORT  host port for the sandbox server (default 8090, loopback)
  HELLOAI_JVM_XMX       backend heap (default 2g)
'@ | Write-Host
}

# ---------------------------------------------------------------- arg parsing
foreach ($a in $args) {
    switch -Regex ($a) {
        '^--with-sandbox$' { $WithSandbox = $true }
        '^--offline$'      { $Offline     = $true }
        '^--no-pull$'      { $NoPull      = $true }
        '^(-h|--help)$'    { Show-Usage; exit 0 }
        default {
            Write-Warn ('unknown argument: ' + $a)
            Show-Usage
            exit 2
        }
    }
}

# ---------------------------------------------------------------- randomness
function New-RandomHex {
    param([int]$ByteCount)
    $buf = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buf)
    return (($buf | ForEach-Object { $_.ToString('x2') }) -join '')
}

function New-RandomBase64 {
    param([int]$ByteCount)
    $buf = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buf)
    return [Convert]::ToBase64String($buf)
}

# ---------------------------------------------------------------- .env helpers
# Read/write with UTF8 (no BOM): a BOM at the head of the first line would
# become part of the first key name and silently break the interpolation.
function Read-EnvLines {
    if (-not (Test-Path $EnvFile)) { return @() }
    return [System.IO.File]::ReadAllLines($EnvFile, $script:Utf8NoBom)
}

function Write-EnvLines {
    param([string[]]$Lines)
    [System.IO.File]::WriteAllLines($EnvFile, $Lines, $script:Utf8NoBom)
}

function Get-EnvValue {
    param([string]$Key)
    foreach ($line in (Read-EnvLines)) {
        if ($line -like ($Key + '=*')) { return $line.Substring($Key.Length + 1).TrimEnd("`r") }
    }
    return ''
}

function Set-EnvValue {
    param([string]$Key, [string]$Value)
    $lines = Read-EnvLines
    $out = New-Object System.Collections.Generic.List[string]
    $found = $false
    foreach ($line in $lines) {
        if ($line -like ($Key + '=*')) {
            $out.Add($Key + '=' + $Value)
            $found = $true
        } else {
            $out.Add($line)
        }
    }
    if (-not $found) { $out.Add($Key + '=' + $Value) }
    Write-EnvLines -Lines $out.ToArray()
}

function Fill-EnvValue {
    param([string]$Key, [scriptblock]$Generator)
    if ([string]::IsNullOrEmpty((Get-EnvValue $Key))) {
        Set-EnvValue -Key $Key -Value (& $Generator)
        Write-Log ('generated ' + $Key)
    }
}

function Assert-EnvValue {
    param([string]$Key)
    if ([string]::IsNullOrEmpty((Get-EnvValue $Key))) {
        Stop-WithError ($Key + ' is empty in ' + $EnvFile)
    }
}

function Initialize-Env {
    if (-not (Test-Path $EnvFile)) {
        if (-not (Test-Path $ExampleFile)) { Stop-WithError ('missing ' + $ExampleFile) }
        Copy-Item $ExampleFile $EnvFile
        Write-Log 'created .env from .env.example'
    }

    # Secrets only. Topology defaults come from .env.example / the images.
    # NEVER overwrite an existing value: the AES key decrypts every stored
    # LLM API key, and a regenerated key makes the old ciphertext useless.
    Fill-EnvValue 'DATASOURCE_PASSWORD'               { New-RandomHex 16 }
    Fill-EnvValue 'HELLOAI_RABBIT_PASSWORD'           { New-RandomHex 16 }
    Fill-EnvValue 'HELLOAI_CREDENTIAL_AES_KEY_BASE64' { New-RandomBase64 32 }
    Fill-EnvValue 'HELLOAI_AGENT_REGISTRATION_TOKEN'  { New-RandomHex 32 }
    Fill-EnvValue 'MINIO_ROOT_USER'                   { New-RandomHex 12 }
    Fill-EnvValue 'MINIO_ROOT_PASSWORD'               { New-RandomHex 16 }
    Fill-EnvValue 'SANDBOX_API_KEY'                   { New-RandomHex 32 }

    $required = @(
        'HELLOAI_APP_IMAGE',
        'HELLOAI_WEB_IMAGE',
        'DATASOURCE_PASSWORD',
        'HELLOAI_RABBIT_PASSWORD',
        'HELLOAI_CREDENTIAL_AES_KEY_BASE64',
        'HELLOAI_AGENT_REGISTRATION_TOKEN',
        'MINIO_ROOT_USER',
        'MINIO_ROOT_PASSWORD'
    )
    foreach ($k in $required) { Assert-EnvValue $k }
    if ($WithSandbox) { Assert-EnvValue 'SANDBOX_API_KEY' }
}

# ---------------------------------------------------------------- preflight
function Test-Preflight {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithError 'docker not found. Install Docker Desktop first: https://docs.docker.com/desktop/'
    }
    & docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError 'cannot reach the Docker daemon. Start Docker Desktop (Linux containers mode), then re-run.'
    }
    & docker compose version 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError 'docker compose (v2) not available. Update Docker Desktop.'
    }
}

function Test-Arch {
    $arch = ((& docker info --format '{{.Architecture}}') -join '').Trim()
    Write-Log ('docker architecture: ' + $arch)
    if ($WithSandbox -and $arch -ne 'x86_64') {
        Write-Warn ('sandbox mode is only validated on linux/amd64; on ' + $arch + ' the OpenSandbox images may be unavailable.')
    }
}

function Test-Memory {
    $raw = ((& docker info --format '{{.MemTotal}}') -join '').Trim()
    $mb = 0
    if ($raw -match '^\d+$') { $mb = [int]([int64]$raw / 1MB) }
    if ($mb -le 0) {
        Write-Warn 'could not read the Docker memory limit; skipping the check.'
        return
    }
    $need = $MinMemCoreMb
    if ($WithSandbox) { $need = $MinMemSandboxMb }
    Write-Log ('docker memory available: ' + $mb + ' MB (recommended >= ' + $need + ' MB)')
    if ($mb -lt $need) {
        Write-Warn ('Docker has only ' + $mb + ' MB. Raise the memory limit in Docker Desktop settings, or the app may be OOM-killed during Flyway/LLM reporting.')
    }
}

function Test-PortFree {
    param([int]$Port)
    $listener = $null
    try {
        # Bind 0.0.0.0 (not loopback): a process already listening on the
        # wildcard address still accepts loopback traffic, so a loopback
        # probe would report "free" while docker's publish then fails.
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener) { try { $listener.Stop() } catch { } }
    }
}

function Test-EntryPort {
    $portText = Get-EnvValue 'HELLOAI_HTTP_PORT'
    $port = 8080
    if ($portText -match '^\d+$') { $port = [int]$portText }
    if (-not (Test-PortFree -Port $port)) {
        Stop-WithError ('host port ' + $port + ' is already in use. Set HELLOAI_HTTP_PORT in .env to a free port (e.g. 8081) and re-run.')
    }
    Write-Log ('web entry will be published on port ' + $port)
}

function Test-ImageExists {
    param([string]$Image)
    & docker image inspect $Image 2>&1 | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# ---------------------------------------------------------------- images
function Import-OfflineImages {
    $tars = @(Get-ChildItem -Path $QS_DIR -Filter 'helloai-offline-*.tar' -ErrorAction SilentlyContinue)
    if ($tars.Count -eq 0) {
        Write-Warn 'no helloai-offline-*.tar found; expecting images to be loaded already.'
        return
    }
    foreach ($t in $tars) {
        Write-Log ('docker load <- ' + $t.Name)
        & docker load -i $t.FullName
        if ($LASTEXITCODE -ne 0) { Stop-WithError ('failed to load ' + $t.Name) }
    }
    Write-Log ('loaded ' + $tars.Count + ' archive(s)')
}

function Receive-CoreImages {
    if ($Offline) { Write-Log '--offline: skipping pull'; return }
    if ($NoPull)  { Write-Log '--no-pull: skipping pull'; return }

    $code = Invoke-Dc pull postgres redis rabbitmq minio
    if ($code -ne 0) { Stop-WithError 'failed to pull middleware images' }

    $code = Invoke-Dc pull app web
    if ($code -ne 0) {
        Write-Warn 'could not pull app/web images.'
        Write-Warn '  - locally built images? re-run with --no-pull'
        Write-Warn '  - slow network? pull manually: docker compose pull app web'
        $appImg = Get-EnvValue 'HELLOAI_APP_IMAGE'
        $webImg = Get-EnvValue 'HELLOAI_WEB_IMAGE'
        if (-not (Test-ImageExists $appImg)) { Stop-WithError ('app image ' + $appImg + ' is neither pullable nor present locally') }
        if (-not (Test-ImageExists $webImg)) { Stop-WithError ('web image ' + $webImg + ' is neither pullable nor present locally') }
    }
}

# ---------------------------------------------------------------- sandbox images
# Mirrors deploy/pull-sandbox-images.sh -- keep BOTH in sync if the lists change.
#   That script is the reference implementation, but it is not invoked here because
#   a Windows user may have no bash at all, and skipping the check silently is
#   exactly the failure mode this guards against.
# Why not `Invoke-Dc --profile prewarm pull` (the previous approach):
#   1) it covers only the two business images declared in the prewarm profile and
#      silently skips the two HELPER images (execd / egress);
#   2) it never verifies that the image actually LANDED on the host daemon -- the
#      sandbox server resolves images by exact name through the docker socket, so
#      a missing one surfaces as a bare ~35s timeout with no cause.
$SandboxHelperImages = @(
    'sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.1.0'
    'sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/egress:v1.1.7'
)
$SandboxBusinessImages = @('python:3.12', 'node:20-bookworm-slim')

function Receive-SandboxImages {
    if (-not $WithSandbox) { return }

    $fetch = $true
    $reason = ''
    if ($Offline) { $fetch = $false; $reason = '--offline' }
    elseif ($NoPull) { $fetch = $false; $reason = '--no-pull' }

    $all = @($SandboxHelperImages) + @($SandboxBusinessImages)
    $missing = @($all | Where-Object { -not (Test-ImageExists $_) })

    if (-not $fetch) {
        # The user asked for the sandbox EXPLICITLY, so a missing image is a hard
        # error rather than a warning: continuing would only move the failure to
        # the first skill call, where it reads as a bare timeout.
        if ($missing.Count -gt 0) {
            Stop-WithError ('sandbox images are missing and ' + $reason + ' forbids fetching them: ' + ($missing -join ', ') + [Environment]::NewLine + '  Load the offline sandbox archive first, or drop --with-sandbox.')
        }
        Write-Log 'sandbox images verified on this host (no network)'
        return
    }

    Write-Log 'preparing sandbox images (helper: execd/egress, business: python/node)'
    $failed = @()
    foreach ($img in $missing) {
        Write-Log ('  pulling: ' + $img)
        & docker pull $img
        if ($LASTEXITCODE -ne 0 -or -not (Test-ImageExists $img)) { $failed += $img }
    }
    if ($failed.Count -gt 0) {
        Stop-WithError ('sandbox image preparation failed: ' + ($failed -join ', ') + [Environment]::NewLine + '  On a host without Docker Hub access, import them from a working machine:' + [Environment]::NewLine + '    docker save python:3.12 node:20-bookworm-slim -o sandbox-images.tar' + [Environment]::NewLine + '    docker load -i sandbox-images.tar' + [Environment]::NewLine + '  Core services are unaffected; re-run without --with-sandbox if you only need them.')
    }
    Write-Log 'sandbox images ready'
}

# ---------------------------------------------------------------- health gate
function Wait-Healthy {
    param([string]$Service, [int]$TimeoutSec = 180)

    $start = Get-Date
    while ($true) {
        $cid = ''
        $ps = @(Invoke-Dc ps -q $Service)
        if ($ps.Count -gt 0) { $cid = ($ps[0] | Out-String).Trim() }

        $status = ''
        if ($cid) {
            $status = ((& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $cid) -join '').Trim()
        }
        if ($status -eq 'healthy') { return }
        if ($status -eq 'unhealthy' -or $status -eq 'exited' -or $status -eq 'dead') {
            Write-Warn ($Service + ' is ' + $status + ' -- recent logs:')
            $null = Invoke-Dc logs --tail 40 $Service
            Stop-WithError ($Service + ' failed to start')
        }
        if (((Get-Date) - $start).TotalSeconds -ge $TimeoutSec) {
            Write-Warn ($Service + ' did not become healthy within ' + $TimeoutSec + 's (last status: ' + $status + ')')
            $null = Invoke-Dc logs --tail 40 $Service
            Stop-WithError ($Service + ' did not become healthy')
        }
        Start-Sleep -Seconds 3
    }
}

# ---------------------------------------------------------------- rabbitmq
function Initialize-Rabbitmq {
    $ps = @(Invoke-Dc ps -q rabbitmq)
    if ($ps.Count -eq 0) { Stop-WithError 'rabbitmq container not found' }
    $cid = ($ps[0] | Out-String).Trim()

    $user = Get-EnvValue 'HELLOAI_RABBIT_USER'
    if ([string]::IsNullOrEmpty($user)) { $user = 'helloai' }
    $pass = Get-EnvValue 'HELLOAI_RABBIT_PASSWORD'

    # add_vhost / add_user fail when the object already exists - expected on
    # re-runs, so the exit code is deliberately ignored. change_password is
    # idempotent and keeps the broker in sync with .env, which is what
    # prevents the "auth works but the password differs" lockout.
    $null = & docker exec $cid rabbitmqctl add_vhost '/helloai' 2>$null
    $null = & docker exec $cid rabbitmqctl add_user $user $pass 2>$null

    $null = & docker exec $cid rabbitmqctl change_password $user $pass 2>$null
    if ($LASTEXITCODE -ne 0) { Stop-WithError ('could not set the RabbitMQ password for ' + $user) }

    $null = & docker exec $cid rabbitmqctl set_permissions -p '/helloai' $user '.*' '.*' '.*' 2>$null
    if ($LASTEXITCODE -ne 0) { Stop-WithError ('could not grant permissions on vhost /helloai to ' + $user) }

    Write-Log ('rabbitmq ready: user=' + $user + ' vhost=/helloai')
}

# ---------------------------------------------------------------- summary
function Show-Summary {
    $port = Get-EnvValue 'HELLOAI_HTTP_PORT'
    if ($port -notmatch '^\d+$') { $port = '8080' }
    $mode = 'core (sandbox disabled)'
    if ($WithSandbox) { $mode = 'core + sandbox' }

    Write-Host ''
    Write-Host '================================================================'
    Write-Host ' HelloAI is up.'
    Write-Host ''
    Write-Host ('   Web UI        : http://localhost:' + $port)
    Write-Host ('   API docs      : http://localhost:' + $port + '/swagger-ui.html')
    Write-Host '   Default admin : admin / admin123   (change it after first login)'
    Write-Host ''
    Write-Host ('   Mode          : ' + $mode)
    Write-Host ('   Secrets file  : ' + $EnvFile + '  (back it up)')
    Write-Host ''
    Write-Host ' Next steps:'
    Write-Host '   1. Open the Web UI and sign in with the default admin account.'
    Write-Host '   2. Configure an LLM provider key in Settings (it is encrypted with'
    Write-Host '      the AES key in .env; losing that file means losing every key).'
    Write-Host '   3. Create a task and watch the execution timeline.'
    Write-Host ''
    Write-Host ' Useful commands (run from this directory):'
    Write-Host '   .\stop.ps1                 stop everything, KEEP data'
    Write-Host '   .\start.ps1                start again'
    Write-Host '   docker compose -f docker-compose.yml ps'
    Write-Host '   docker compose -f docker-compose.yml logs -f app'
    Write-Host ''
    Write-Host ' To enable sandbox skills later:'
    Write-Host '   .\start.ps1 --with-sandbox'
    Write-Host '================================================================'
}

# ---------------------------------------------------------------- main flow
Test-Preflight
Initialize-Env
Test-Arch
Test-Memory
Test-EntryPort

if ($Offline) { Import-OfflineImages }
Receive-CoreImages
Receive-SandboxImages

Write-Log 'starting middleware (postgres, redis, rabbitmq, minio)'
Invoke-DcStrict up -d postgres redis rabbitmq minio
Wait-Healthy -Service 'postgres' -TimeoutSec 90
Wait-Healthy -Service 'redis'    -TimeoutSec 60
Wait-Healthy -Service 'rabbitmq' -TimeoutSec 120
Wait-Healthy -Service 'minio'    -TimeoutSec 90

Initialize-Rabbitmq

if ($WithSandbox) {
    $env:SANDBOX_ENABLED = 'true'
    $env:SANDBOX_URL     = 'http://opensandbox-server:8090'
    $env:SANDBOX_API_KEY = (Get-EnvValue 'SANDBOX_API_KEY')
    Write-Log 'starting app + web + sandbox'
    Invoke-DcStrict --profile sandbox up -d
} else {
    $env:SANDBOX_ENABLED = 'false'
    Write-Log 'starting app + web (core mode: sandbox disabled)'
    Invoke-DcStrict up -d
}

Wait-Healthy -Service 'app' -TimeoutSec 420
Wait-Healthy -Service 'web' -TimeoutSec 60

Show-Summary
