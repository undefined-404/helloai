$jars = Get-ChildItem -Path 'C:\Users' -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'spring-ai-mcp*.jar' -or $_.Name -like 'spring-ai-model*.jar' }
foreach ($jar in $jars) {
    Write-Host "=== $($jar.Name) ==="
    # 1) Exchange 模糊搜索
    $hits1 = & jar tf $jar.FullName 2>&1 | Select-String -Pattern 'Exchange' -SimpleMatch
    Write-Host "  Exchange hits: $($hits1.Count)"
    $hits1 | ForEach-Object { Write-Host "    $($_.ToString().Trim())" }
    # 2) Session 模糊搜索
    $hits2 = & jar tf $jar.FullName 2>&1 | Select-String -Pattern 'Session' -SimpleMatch
    Write-Host "  Session hits: $($hits2.Count)"
    $hits2 | Select-Object -First 8 | ForEach-Object { Write-Host "    $($_.ToString().Trim())" }
    # 3) McpAsync / McpSync / McpSchema 数量
    $hits3 = & jar tf $jar.FullName 2>&1 | Where-Object { $_ -match 'McpAsync|McpSync|McpSchema' }
    Write-Host "  Mcp* count: $($hits3.Count)"
}
