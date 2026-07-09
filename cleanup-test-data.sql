SET session_replication_role = replica;
TRUNCATE TABLE
    agent, sub_task, task, module,
    agent_inbox, agent_outbox_event,
    review_record, reward_log, activity_log,
    patrol_record, request_log, rule,
    attachment, conversation_archive, conversation_message,
    agent_execution_record, prompt_template
RESTART IDENTITY CASCADE;
SET session_replication_role = origin;
SELECT 'agent' AS tbl, COUNT(*) FROM agent UNION ALL
SELECT 'sub_task', COUNT(*) FROM sub_task UNION ALL
SELECT 'agent_inbox', COUNT(*) FROM agent_inbox UNION ALL
SELECT 'agent_outbox_event', COUNT(*) FROM agent_outbox_event UNION ALL
SELECT 'review_record', COUNT(*) FROM review_record UNION ALL
SELECT 'reward_log', COUNT(*) FROM reward_log UNION ALL
SELECT 'sys_user', COUNT(*) FROM sys_user;SET session_replication_role = replica;
TRUNCATE TABLE
    agent, sub_task, task, module,
    agent_inbox, agent_outbox_event,
    review_record, reward_log, activity_log,
    patrol_record, request_log, rule,
    attachment, conversation_archive, conversation_message,
    agent_execution_record, prompt_template
RESTART IDENTITY CASCADE;
SET session_replication_role = origin;
SELECT 'agent_count' AS k, COUNT(*) AS v FROM agent WHERE deleted=0
UNION ALL SELECT 'sub_task_count', COUNT(*) FROM sub_task WHERE deleted=0
UNION ALL SELECT 'agent_inbox_count', COUNT(*) FROM agent_inbox WHERE deleted=0
UNION ALL SELECT 'agent_outbox_count', COUNT(*) FROM agent_outbox_event WHERE deleted=0
UNION ALL SELECT 'review_record_count', COUNT(*) FROM review_record WHERE deleted=0
UNION ALL SELECT 'sys_user_count', COUNT(*) FROM sys_user WHERE deleted=0;