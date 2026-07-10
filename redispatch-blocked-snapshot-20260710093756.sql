-- verify-subtask-redispatch-auto-execution.ps1 snapshot
-- scenario=
blocked

-- T1. sub_task final state
SELECT id, task_id, status, assigned_agent, completed_at, update_time
FROM sub_task
WHERE id = 
2075514853803102209
 AND deleted = 0;

-- T2. task_timeline evidence
SELECT id, task_id, sub_task_id, event_type, role, agent_id, payload, create_time
FROM task_timeline
WHERE sub_task_id = 
2075514853803102209
 AND deleted = 0
ORDER BY id DESC LIMIT 20;

-- T3. source agent heartbeat / online fields
SELECT id, name, role, status, online_status, last_seen_at, last_active_at, offline_reason, offline_at
FROM agent
WHERE id = 
2075514853417226242
;

-- T4. target agent online fields
SELECT id, name, role, status, online_status, last_seen_at, last_active_at, offline_reason, offline_at
FROM agent
WHERE id = 
2075514853480140802
;

-- T5. task timeline by task
SELECT id, task_id, sub_task_id, event_type, role, agent_id, payload, create_time
FROM task_timeline
WHERE task_id = 
2075514853673078785
 AND deleted = 0
ORDER BY id DESC LIMIT 30;
