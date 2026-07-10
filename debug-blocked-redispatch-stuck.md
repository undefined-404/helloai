# [OPEN] blocked-redispatch-stuck

## 症状
- 场景：blocked -> 重新分配 -> 自动执行
- 期望：subTask 在重新分配给 API_KEY_LLM agent 后，自动执行完成并进入 REVIEW
- 实际：subTask 从 ASSIGNED 进入 IN_PROGRESS，但持续停留在 IN_PROGRESS，脚本超时

## 复现步骤（脚本）
- PowerShell：`.\verify-subtask-redispatch-auto-execution.ps1 -Scenario blocked -BindVault`

## 当前已知信息
- subTaskId=2075471695878721538
- targetAgentId=2075471695635451905
- pg_stat_activity：多数连接 idle(ClientRead)，1 个 active

## 待证伪假设（H）
H1. 自动执行进入 executor 后，实际卡在 LLM 调用（网络/超时/线程池）导致 never-finish，但 subTask 状态未被回写为 REVIEW/FAILED。  
H2. 执行链在 Vault 取凭证阶段卡住或抛错被吞掉，导致 execution 未完成且未触发状态迁移。  
H3. blocked->reassign 的重置逻辑不完整：旧 execution/timeline 仍被认为“在跑”，导致新执行链不推进到 review。  
H4. 状态机/事件发布链路缺失：ASSIGNED->IN_PROGRESS 触发了，但“执行完成 -> REVIEW”的事件未投递或被事务回滚。  
H5. 并发/幂等保护误判：同一 subTask 的 execute 被去重/锁住，导致 worker 实际没跑完或没跑到收尾阶段。

## 取证计划
1) 启动 Debug Server，确保可接收 ndjson 事件流  
2) 在关键节点增加最小埋点（仅 instrumentation，不改业务逻辑）：
   - reassign/redispatch 后：subTask 状态、assignedAgent、触发 auto-exec 的入口
   - dispatcher 选择 executor：agentType、offline 判定、选路结果
   - executor：vault 查询、解密、chatclient 调用开始/结束/异常
   - 收尾：execution 结果、subTask 状态迁移到 REVIEW 的入口与结果
3) 复跑脚本，抓取 pre-fix 运行态证据并定位卡点

## 证据记录
- 预留：trae-debug-log-blocked-redispatch-stuck.ndjson（由 Debug Server 生成）

## 结论（待填写）
- 根因：
- 最小修复：
- 回归验证：

