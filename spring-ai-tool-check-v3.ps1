$jars = Get-ChildItem -Path 'C:\Users' -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'spring-ai-mcp*.jar' }
foreach ($jar in $jars) {
    Write-Host "=== $($jar.Name) ==="
    $all = & jar tf $jar.FullName 2>&1
    Write-Host "  total entries: $($all.Count)"
    Write-Host "  --- ALL .class entries ---"
    $all | Where-Object { $_ -like '*.class' } | ForEach-Object { Write-Host "    $($_.ToString().Trim())" }
}
