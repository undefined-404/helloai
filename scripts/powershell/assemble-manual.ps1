# assemble-manual.ps1 - deterministic assembly of the final manual from approved chapters.
# Rules: chapter bodies are copied verbatim; only heading levels are shifted (H1->H2, H2->H3, H3->H4).
# Front matter lives in tmp/manual-front-template.md and is filled from derived data.
# No timestamps are emitted, so repeated runs produce byte-identical output.
[CmdletBinding()]
param(
  [string]$Root = "doc\manual\executor-duty",
  [string]$Template = "tmp\manual-front-template.md",
  [string]$OutFile = "doc\manual\executor-duty\manual-assembled.md"
)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$DI = [char]0x7B2C      # 'di'  (ordinal prefix)
$ZHANG = [char]0x7AE0   # 'zhang' (chapter)
$SEC = [char]0xA7       # section sign

$sources = [ordered]@{
  '00' = '00-manual-contract.md'
  '01' = '01-duty-and-work-mode.md'
  '02' = '02-task-acquisition-and-claim.md'
  '03' = '03-result-submission.md'
  '04' = '04-heartbeat-and-lease.md'
  '05' = '05-troubleshooting.md'
}

function Read-Doc([string]$p) { [System.IO.File]::ReadAllText($p, $utf8) }

function Demote([string]$text) {
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($l in ($text -split "`r?`n")) {
    if ($l -match '^(#{1,4})(\s+)(.*)$') {
      $lvl = [Math]::Min(4, $matches[1].Length + 1)
      $out.Add(('#' * $lvl) + $matches[2] + $matches[3])
    } else { $out.Add($l) }
  }
  return ($out -join "`r`n")
}

# --- derive TBD rows from every source document ---
$tbdRows = New-Object System.Collections.Generic.List[string]
foreach ($k in $sources.Keys) {
  $t = Read-Doc (Join-Path $Root $sources[$k])
  foreach ($l in ($t -split "`r?`n")) {
    if ($l -match '^\|\s*TBD-') {
      $cells = $l.Trim().Trim('|') -split '\|'
      $id = $cells[0].Trim()
      $item = $cells[1].Trim()
      $duty = ''
      if ($cells.Count -ge 4) { $duty = $cells[3].Trim() }
      $owner = $DI + ' ' + $k + ' ' + $ZHANG
      $tbdRows.Add('| ' + $id + ' | ' + $owner + ' | ' + $item + ' | ' + $duty + ' |')
    }
  }
}

# --- derive glossary terms from contract section 3 table ---
$contract = Read-Doc (Join-Path $Root $sources['00'])
$terms = New-Object System.Collections.Generic.List[string]
$inSec = $false
$rowIdx = 0
foreach ($l in ($contract -split "`r?`n")) {
  if ($l -match '^##\s+3\.\s') { $inSec = $true; continue }
  if ($inSec -and $l -match '^##\s') { break }
  if (-not $inSec) { continue }
  if ($l -notmatch '^\|') { continue }
  if ($l -match '^\|[\s\-\|]+\|$') { continue }
  $rowIdx++
  if ($rowIdx -eq 1) { continue }
  if ($l -match '^\|\s*([^|]+?)\s*\|') { $terms.Add($matches[1].Trim()) }
}

$termRows = New-Object System.Collections.Generic.List[string]
$termPos = $DI + ' 00 ' + $ZHANG + ' ' + $SEC + '3'
foreach ($t in $terms) { $termRows.Add('| ' + $t + ' | ' + $termPos + ' |') }

$front = Read-Doc (Join-Path (Get-Location) $Template)
$front = $front.Replace('{{TERMS_ROWS}}', ($termRows -join "`r`n"))
$front = $front.Replace('{{TBD_ROWS}}', ($tbdRows -join "`r`n"))
$front = $front.Replace('{{TBD_COUNT}}', [string]$tbdRows.Count)

$body = New-Object System.Collections.Generic.List[string]
foreach ($k in $sources.Keys) {
  $body.Add((Demote (Read-Doc (Join-Path $Root $sources[$k])).TrimEnd()))
  $body.Add('')
  $body.Add('')
}

$manual = $front + "`r`n" + ($body -join "`r`n")
$target = Join-Path (Get-Location) $OutFile
[System.IO.File]::WriteAllText($target, $manual, $utf8)
$sha = (Get-FileHash -Algorithm SHA256 -Path $target).Hash
Write-Output ('out   : ' + $OutFile)
Write-Output ('chars : ' + $manual.Length)
Write-Output ('bytes : ' + (Get-Item $target).Length)
Write-Output ('sha256: ' + $sha)
Write-Output ('terms : ' + $termRows.Count)
Write-Output ('tbds  : ' + $tbdRows.Count)