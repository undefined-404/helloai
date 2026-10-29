# ============================================================================
# HelloAI - Quickstart shutdown (Windows)
#
#   .\stop.ps1          stop containers, KEEP all data (default)
#   .\stop.ps1 --purge  stop + delete volumes AND .env secrets (asks first)
#
# Why the default keeps data: the postgres volume holds every task, and
#   .env holds the AES key that decrypts every stored LLM credential.
#   Deleting either is irreversible, so it must be an explicit opt-in.
#
# Pure ASCII on purpose (see start.ps1 header for the PS 5.1 rationale).
# ============================================================================

# ------------------------------------------------------------
# UTF-8 encoding header (repo rule 6) - avoid mojibake
# ------------------------------------------------------------
$script:Utf8NoBom         = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom

$ErrorActionPreference = 'Stop'

$QS_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $QS_DIR
$EnvFile = Join-Path $QS_DIR '.env'

function Invoke-Dc {
    $dockerArgs = @('compose', '-f', 'docker-compose.yml') + $args
    & docker @dockerArgs
    return $LASTEXITCODE
}

$Purge = $false
foreach ($a in $args) {
    switch -Regex ($a) {
        '^--purge$' { $Purge = $true }
        '^(-h|--help)$' {
            Write-Host 'Usage: .\stop.ps1 [--purge]'
            Write-Host '  (no flags)  stop containers, keep data'
            Write-Host '  --purge     delete volumes and .env (irreversible)'
            exit 0
        }
        default {
            Write-Host ('[helloai][error] unknown argument: ' + $a) -ForegroundColor Red
            exit 2
        }
    }
}

# '--profile sandbox' is required on down as well: without it compose only
# removes services from the active profile set and leaves the sandbox
# containers behind as orphans.
if ($Purge) {
    @'
================================================================
 WARNING: --purge is destructive and irreversible.

   - deletes volumes: postgres_data, redis_data, rabbitmq_data, minio_data
     (every task, execution record, attachment and credential is lost)
   - deletes .env
     (the AES key inside it decrypts every stored LLM API key; a new key
      cannot decrypt the old ciphertext even if you restore a database dump)
================================================================
'@ | Write-Host

    $answer = Read-Host "Type 'yes' to continue"
    if ($answer -ne 'yes') {
        Write-Host 'aborted (nothing was changed)'
        exit 1
    }

    $code = Invoke-Dc --profile sandbox down -v --remove-orphans
    if ($code -ne 0) {
        Write-Host ('[helloai][error] docker compose down failed (exit ' + $code + ')') -ForegroundColor Red
        exit 1
    }
    if (Test-Path $EnvFile) { Remove-Item $EnvFile -Force }
    Write-Host '[helloai] purged: containers, volumes and .env removed.'
} else {
    $code = Invoke-Dc --profile sandbox down --remove-orphans
    if ($code -ne 0) {
        Write-Host ('[helloai][error] docker compose down failed (exit ' + $code + ')') -ForegroundColor Red
        exit 1
    }
    Write-Host '[helloai] stopped. Data volumes and .env kept; run .\start.ps1 to resume.'
}
