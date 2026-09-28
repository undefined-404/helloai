$cfg = Get-Content -Raw -Encoding UTF8 "scripts\powershell\executor-config.json" | ConvertFrom-Json
$utf8 = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Net.Http
$c = New-Object System.Net.Http.HttpClient
$c.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $cfg.apiKey)
function Tool($name, $argsObj) {
  $payload = @{ jsonrpc='2.0'; method='tools/call'; id=1; params=@{ name=$name; arguments=$argsObj } } | ConvertTo-Json -Depth 10 -Compress
  $content = New-Object System.Net.Http.StringContent($payload, $utf8, 'application/json')
  $resp = $c.PostAsync($cfg.baseUrl + '/api/mcp/jsonrpc', $content).GetAwaiter().GetResult()
  $t = $utf8.GetString($resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
  if ([string]::IsNullOrWhiteSpace($t)) { return $null }
  $p = $t | ConvertFrom-Json
  if ($p.error) { throw ('tool failed: ' + $p.error.message) }
  return $p.result
}
$target='2097935069198065670'
$terminal=@('sub_task.approved','sub_task.rejected','sub_task.rework')
$found=$null
for ($i=1; $i -le 14 -and -not $found; $i++) {
  $hb = Tool 'heartbeat' @{}
  $pt = Tool 'pullTasks' @{ role='EXECUTOR'; max=20; includeRead=$false }
  $msgs=@($pt.messages)
  Write-Output ("round {0}  {1}  onDuty={2}  unread={3}" -f $i,(Get-Date -Format 'HH:mm:ss'),$hb.onDuty,$msgs.Count)
  foreach ($m in $msgs) {
    Write-Output ("    [" + $m.messageId + "] " + $m.type + "  subTaskId=" + $m.subTaskId + "  " + $m.title)
    if ($m.subTaskId -eq $target -and $terminal -contains $m.type) { $found = $m }
  }
  if (-not $found) { Start-Sleep -Seconds 15 }
}
if ($found) { Write-Output ("VERDICT: " + $found.type + "  msg=" + $found.messageId) } else { Write-Output "VERDICT: none yet" }
$c.Dispose()
