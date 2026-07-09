INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT a.id, tool.name, 1, 0, 'system', 'system'
FROM agent a
CROSS JOIN (VALUES
    ('pullTasks'),
    ('ack'),
    ('heartbeat'),
    ('claimSubTask'),
    ('uploadArtifact'),
    ('reportBlocked'),
    ('getAgentStatus')
) AS tool(name)
WHERE a.role = 'EXECUTOR' AND a.deleted = 0
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;
