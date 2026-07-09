$jar = "C:\Users\$env:USERNAME\.m2\repository\io\modelcontextprotocol\sdk\mcp-core\0.16.0\mcp-core-0.16.0.jar"
Write-Host "=== All Exchange classes in mcp-core-0.16.0 ==="
$all = & jar tf $jar 2>&1 | Where-Object { $_ -like '*Exchange*.class' }
$all | ForEach-Object { Write-Host "  $($_.ToString().Trim())" }
Write-Host ""
Write-Host "=== McpAsyncServerExchange API ==="
& javap -p -classpath $jar 'io.modelcontextprotocol.server.McpAsyncServerExchange' 2>&1
Write-Host ""
Write-Host "=== McpServerTransportContextExtractor API ==="
& javap -p -classpath $jar 'io.modelcontextprotocol.server.McpTransportContextExtractor' 2>&1
