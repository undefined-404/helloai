$jar = "C:\Users\$env:USERNAME\.m2\repository\io\modelcontextprotocol\sdk\mcp-core\0.16.0\mcp-core-0.16.0.jar"
& javap -p -classpath $jar 'io.modelcontextprotocol.server.McpAsyncServer$StructuredOutputCallToolHandler' 2>&1
Write-Host ""
& javap -p -classpath $jar 'io.modelcontextprotocol.server.McpAsyncServer' 2>&1
