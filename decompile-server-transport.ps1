$jar = Get-ChildItem -Path 'C:\Users' -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'mcp-spring-webmvc-0.16.0.jar' } | Select-Object -First 1
$jarPath = $jar.FullName
Write-Host "JAR: $jarPath"
Write-Host ""
Write-Host "=== WebMvcSseServerTransportProvider ==="
& javap -p "-cp" $jarPath "io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider" 2>&1
Write-Host ""
Write-Host "=== WebMvcSseServerTransportProvider\$WebMvcMcpSessionTransport ==="
& javap -p "-cp" $jarPath 'io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider$WebMvcMcpSessionTransport' 2>&1
