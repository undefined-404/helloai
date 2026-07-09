try {
    $r = Invoke-RestMethod -Method Post -Uri 'http://localhost:6565/api/auth/login' -ContentType 'application/json' -Body '{"type":"admin","username":"admin","credential":"helloai123"}' -TimeoutSec 10
    $r | ConvertTo-Json -Depth 5
} catch {
    Write-Host "FAIL_MSG: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        Write-Host "FAIL_STATUS: $($_.Exception.Response.StatusCode)"
        $stream = $_.Exception.Response.GetResponseStream()
        $sr = New-Object System.IO.StreamReader($stream)
        Write-Host "FAIL_BODY: $($sr.ReadToEnd())"
    }
}
