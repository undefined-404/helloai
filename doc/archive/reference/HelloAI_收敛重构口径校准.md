> **状态：HISTORICAL / REFERENCE**。当前开发请以根目录 Current/Target/Gap/Plan 为准。

# HelloAI 收敛重构口径校准（Phase 0 / Phase 1 / N 系列回顾用）

> **文档性质**：回顾口径说明（非执行方案、非事实源）。涉及的事实一律以仓库事实源优先级为准（代码与运行行为 > Flyway > 验证脚本/LOG > 基线文档），本文档引用的关键事实均标注出处，供日后回顾时对齐记忆、避免口径漂移。
>
> **状态**：口径校准文档（2026-09-06，R1）。随代码演进需同步修订，修订见文末记录。

---

## 1. 重构时间线全景（已收口段）

| 阶段 | 内容 | 收口证据 |
|---|---|---|
| Phase 0 事件层 + 双轨 | ADR-001 Run/Turn/Step 模型 + `agent_event`（append-only，不 Event Sourcing）；双轨 Executor 自我验证（新旧链并行、事件对账 100% 一致才放行） | Phase 0 方案 §三；ADR-001 |
| Phase 0 C3 灰度切换 | 阶梯放量（5% → 25% → …），25% 档验收：违例 0 行 / 对账 0 WARN；100% 档放量由用户决策 | LOG-20260903-009 |
| Phase 1 Harness 五件套 | 1.0 Runtime 定型（`AgentContext` + `AgentRuntime` + `LegacyExecutorAdapter` 唯一执行入口）；1.3 Tool Registry（Step 2）；1.1 Session Manager（Step 3 + P2-B B1）；1.4 SandboxProvider（Step 4）；1.2 Skill Registry 模式定型（依 CODE_STYLE §5.7 收敛：KNOWN_SPECS 元数据化，不建与 ToolRegistry 平行的独立 Registry 类） | Phase 1 方案 §0 状态表；LOG-20260904-006 / LOG-20260905-002/003/009 |
| P2-A 治理 | S0 门铃服务端验收；A2 N11 阈值回退；N-008 inbox 消息生命周期；A4 N-010 MQ 治理 | LOG-20260905-005/007/008/010 |
| P2-B 清账 | N-007 执行恢复（B1 prompt 续接）；N-004 Credential Vault 治理；N-005 Provider Factory（doc-stale，zero code change）；N-012 Planner Context | LOG-20260905-009/011/012/013 |

**口径**：这是一轮"控制面收敛 + 差距项清账"重构。角色语义、审查规则、报告产物等业务语义层被刻意作为不变量锁死（见 §4），收敛对象是流转路径与执行侧抽象（见 §2/§3）。

## 2. 三层收敛模型（核心校准点）

常被回顾为"角色被拆掉、改成无角色属性接口"——精确口径是**角色语义从执行器内嵌身份，重构为三层分工**：

```text
任务侧：task.agent_policy 角色槽位（plannerAgentId / executorAgentIds / reviewerAgentId
        / fallbackPolicy / difficulty）+ task.required_skills        —— 决定"谁该干什么"
        （列出自 V47，JSONB）
Agent 侧：agent.role / access_type / skills / capabilities / 在线态   —— 注册元数据，可复用属性
        （access_type: API_KEY_LLM / CLI_CLIENT / WEB_BROWSER）
执行侧：AgentRuntime.execute(ctx) Turn 级契约 + ExecutionEnvironment(name/supports)
        + SkillRegistry（KNOWN_SPECS 元数据）                        —— 回答"怎么执行"
```

- **"无角色属性"仅指执行侧接口**：`AgentRuntime` 接口无 planner/executor/reviewer 之分，执行一次 Turn 级工作周期，失败/超时不抛异常、以 `ExecutionStatus` 表达（代码事实：[AgentRuntime.java](../../helloai-core/src/main/java/com/helloai/core/agent/runtime/AgentRuntime.java)）。
- **角色没有删除**：调度分配仍按角色槽位 + 能力属性匹配（`SubTaskService.assignNext` / 收敛链），只是执行器不再内嵌身份。
- **执行环境独立供电**：`ExecutionEnvironmentProvider` 按接入类型解析环境（remote-agent / local-process），`ENVIRONMENT_RESOLVED`（step=7）埋点（ADR-001 R4）。

## 3. 内外统一路径落点（此前"各自一套"→ 现在共用一条）

| 层 | 统一后形态 | 出处 |
|---|---|---|
| 执行 | 内外部执行器统一收敛：`ExecutionCommand` → Local/Mq Consumer → `ExecutionResultHandler` **唯一回写入口** | Phase 1 §0 1.0；调度解耦重构分析 |
| 审查 | 内外 Reviewer 走同一审查链（同一角色槽位、同一审查状态机、驳回/返工/ReviewFact），不再按接入类型分路径 | CODE_STYLE 分层规范；审查链路实现 |
| 观测 | 内外部事件统一落 `agent_event` Run/Turn/Step 三层 + `task_timeline`；双心跳（last_seen_time / last_active_time）统一在线态语义 | ADR-001；N-011 |
| 调度 | 内外一致的最小可调度条件（在线 + 无执行中任务 + 能力匹配）；外部 3 连失败/超时阈值后自动回退平台内 `API_KEY_LLM` 保底 | N11（LOG-20260905-007）；架构参考 §4.8 |
| MQ | 基础设施化：业务失败以状态表达（不回投）；技术失败 NACK → DLX 死信台账；重放前重置幂等台账；`replayed_at` 防重复重放；人工恢复入口（Admin，纯转发） | A3/A4 方案（LOG-20260905-010） |

## 4. 业务语义不变量（重构刻意未动 + 证据）

**不变量清单**（这些语义在收敛过程中保持稳定）：

- `task` / `sub_task` 状态机与流转（含 BLOCKED fail-close、驳回返工、改派）
- 子任务依赖 ready 语义（`sub_task.depends_on` 全部前置 DONE 才可分派，V27）
- 审查规则与 Reviewer 结论语义（ReviewFact / 双审 / 核验熔断转死信）
- 终稿产物语义（`task.final_report` + `final_report_status` NONE/GENERATING/DONE/FAILED，V41）
- 租约 / 超时 / 孤儿收敛（Lease / Watchdog / 各类定时收敛任务）
- 契约先行（`sub_task.is_contract` V56 + `task_running_spec.contract` V55 全局注入）

**证据链**：

1. **C3 双轨灰度**即"证明业务没变"的验证装置：新旧两链并行、事件对账 100% 一致、关键路径行为一致才放行（Phase 0 方案 §三）；25% 档验收违例 0 行 / 对账 0 WARN（LOG-20260903-009）。
2. **收敛轮次的显式登记口径**：「既有调度链路（熔断 / 死信 / 孤儿巡检 / N11 回退）零行为变化，仅新增时间线留痕；澄清链除写库方式（全字段 → 定点）与入口统一外业务语义不变；时间线写入失败不阻断补偿主链路」（LOG-20260901-002）。
3. **回归数字链**：core 1085 → 1091（N-008）→ 1100（B1）→ 1118（N-010）→ 1131（N-004）→ 1138（N-012），每轮 feat 提交全绿（各 LOG 记载；2026-09-06 独立复测 1138 / api 32 / job 69 全绿）。
4. **治理轮次显式声明**：N-005 Provider Factory 为 doc-stale 修正（zero code change）；N-008 / N-010 为 zero DDL（复用 V1/V23/V60 预留列）。

## 5. 行为边界收敛清单（有意收紧、登记在案，非重构副作用）

遇到"和以前不一样"时先对照本表定性，再判断是否异常：

| 收敛点 | 旧 | 新 | 登记出处 |
|---|---|---|---|
| 澄清链写库 | 全字段写 | 定点写 + 入口统一 | LOG-20260901-002 |
| 结果回写 | 多入口 | `ExecutionResultHandler` 唯一 | Phase 1 §0 1.0 |
| MQ 失败语义 | 业务异常重投 | 业务终态不回投；技术故障才 NACK/DLX | A3/A4 方案 |
| MQ 恢复 | 无人工入口 | outbox requeue / 死信台账重放（Admin） | LOG-20260905-010 |
| 时间字段语义 | 命名与语义混用 | `*_time` 后缀统一 + TTL/租约语义（V23 等） | 字段归一各迁移 |
| 事件观测 | 内外各一套 | 统一 Run/Turn/Step + timeline 留痕（不改业务路径） | ADR-001 |
| SkillRegistry | 拟独立 Registry 类 | KNOWN_SPECS 元数据化（架构评分降级） | Phase 1 §0 1.2 |

## 6. 常见回顾偏差校准

| 常见说法 | 校准口径 |
|---|---|
| "角色被删掉了 / 执行器无角色了" | 角色三层化：任务槽位 + Agent 元数据 + 执行侧统一契约（§2），非删除 |
| "对外业务完全没变" | 业务**语义**未变（§4 不变量有证据）；行为边界存在有意收敛清单（§5） |
| "内外两套路径统一是一步到位的" | 分阶段：Phase 0 双轨对账 → Phase 1 执行侧收敛 → P2-A/B 治理清账（§1） |
| "新链路与旧链路行为差异 = 回归 bug" | 先对照 §4 不变量与 §5 收敛清单定性：语义面差异才算回归 |
| "重构引入了大版本技术升级" | JDK 17 / Spring AI 基线 / 数据库结构均未跨版本变动，全部为增量收敛 | 

## 7. 修订记录

### R1（2026-09-06）：初版产出

- **背景**：Phase 0/1 + N 系列收敛告一段落后，回顾口径出现漂移风险（"角色删除""内外统一"等说法需校准），沉淀一份代码事实可追溯的口径说明。
- **核查**：`AgentRuntime` 接口（无角色字段、ExecutionStatus 表达失败）；V27/V41/V47/V48/V55/V56 迁移与字段语义；Phase 1 方案 §0 状态表；ADR-001；LOG-20260901-002 / LOG-20260903-009 / LOG-20260905-005~013；独立复测 core 1138 / api 32 / job 69 全绿。
