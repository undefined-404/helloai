$src = Get-ChildItem -Path 'C:\Users' -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'mcp-spring-webmvc*.jar' }
foreach ($jar in $src) {
    Write-Host "=== $($jar.FullName) ==="
    $all = & jar tf $jar.FullName 2>&1 | Where-Object { $_ -like '*.class' }
    Write-Host "  total classes: $($all.Count)"
    $all | ForEach-Object { Write-Host "    $($_.ToString().Trim())" }
}
