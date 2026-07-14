# HelloAI 实现差距表

## 1. 文档定位

本文档用于回答：

- 文档原先承诺了什么
- 当前代码实际做到了什么
- 差距属于“应改文档”还是“应补功能”

本文档是当前版本对表、验收与排期的主参照文档。

---

## 2. 当前总体结论

- 已交付闭环：MCP SSE 接入、鉴权、核心工具链路、在线状态三件套、熔断降级、Reconcile 健康检查
- 文档失真：历史路线图、技术方案与部分说明文档曾混入过时事实或实施日志
- 部分落地：执行命令主链、Provider 配置复用、工牌模式与 `credential_vault`
- 未落地：工作流模板、浏览器型 Agent 真实接入链路

---

## 3. 文档失真项（D）

| 编号 | 主题 | 当前状态 | 结论 |
|---|---|---|---|
| D1 | MCP 工具数量口径 | ✅ 已关闭 | README 已明确：工具数量不写死，以 `tools/list` 实际输出为准（2026-07-13） |
| D2 | 兼容通道定位 | ✅ 已关闭 | README 已明确：MCP SSE 是唯一主通道，REST `tools/list` / `tools/call` 属兼容保留（2026-07-13） |
| D3 | 路线图正文混入实施日志 | ✅ 已关闭 | 实施记录已迁移到《迭代执行记录》，路线图已退出事实源 |
| D4 | Spring AI 版本口径 | ✅ 已关闭 | 现行版本以代码、基线文档与回归结果为准 |
| D5 | `/api/tools/cli` 鉴权口径 | ✅ 已关闭 | 代码验证：`WebMvcConfig` 中 `/api/tools/cli` 已通过 `excludePathPatterns` 排除鉴权（公开下载入口，设计如此）。历史技术方案不再作为事实源（2026-07-13） |
| D6 | 心跳刷新规则口径 | ✅ 已关闭 | README 已明确：`last_seen_at`/在线态刷新以 `heartbeat` 为主；仅将 pull/ack 也计入活跃需代码配合再升级（2026-07-13） |
| D7 | README 文档边界 | ✅ 已关闭 | README 已收口为项目介绍与使用说明，不再承载阶段性执行结论 |

---

## 4. 路线图项差距（N）

| 编号 | 主题 | 当前状态 | 差距定义 | 处理建议 |
|---|---|---|---|---|
| N1 | Outbox / 命令可靠投递底座 | 部分落地 | `AgentOutboxService` 已承担事务性消息发布基础能力，Outbox 思路已进入主线；执行命令已完成 DB Poller 主消费载体（`consumer-mode=POLLER` 默认），但尚未形成“执行命令 → Outbox → MQ → 独立 Consumer”的可靠投递闭环 | 继续补功能 |
| N2 | 可配置工作流模板 | 未落地 | 缺模板表、模板管理、模板化调度入口与 Team 编排 | 后续独立迭代 |
| N3 | MCP Server 工具集 | 已交付 | MCP SSE 主通道工具链已可用，已具备外部 Agent 最小执行闭环能力：`pullTasks/ack/claimSubTask/heartbeat/uploadArtifact/submitResult/reportBlocked/getAgentStatus`（其中 `submitResult` 对接统一回写入口，`reportBlocked` 记录阻塞原因证据链） | 保持现状 |
| N4 | 心跳与在线判定 | 已交付 | 在线态三件套、在线计算态与巡检收敛已具备 | 保持现状 |
| N5 | 熔断降级 | 已交付 | per-agent 熔断与同角色替补策略已具备 | 保持现状 |
| N6 | 执行命令消费与结果回写 | 部分落地 | 已完成 DB Poller 主线化：`consumer-mode` 支持 `EVENT / POLLER / BOTH`，默认 `POLLER`；`ExecutionCommandService` 在 `EVENT/BOTH` 才发布本地事务事件，`POLLER` 仅落库 PENDING 命令；`ExecutionCommandPoller` 在 `POLLER/BOTH` 扫描全部 PENDING（主消费），在 `EVENT` 仅扫描孤儿 PENDING（兜底）；Poller 依赖抽象 `ExecutionCommandConsumer`，默认实现为 `LocalExecutionCommandConsumer.consume()`（Bean 常驻）。结果回写入口已收口为 `ExecutionResultHandler.handleReport(ExecutionResultReport)`，平台内执行链与外部 MCP `submitResult` 统一走该入口。当前尚未新增 MQ Consumer，执行命令主链仍未切到 RabbitMQ | 后续推进“MQ 主链路 + DB 状态中心 + Poller 兜底恢复”（引入执行命令 MQ Consumer，Poller 保留为漏消费/超时/恢复兜底） |
| N7 | 健康检查改写 | 已交付 | Reconcile、离线重分配、兜底收敛已具备 | 保持现状 |
| N8 | 网页版 AI 浏览器接入 | 未落地 | 只有枚举与预留，没有真实接入模块 | 后续独立迭代 |
| N9 | Provider 配置与 ChatClient 复用 | 部分落地 | Provider 配置入口已统一（`helloai.providers`）+ provider/model 解析已收口（`AgentProviderResolver`）；仍缺 ChatModel 缓存（每次 new）及多 Provider 扩展验证 | 继续补功能 |
| N10 | 工牌模式 + `credential_vault` | 部分落地 | 最小模型、绑定与托管语义已具备，但轮换、迁移、权限颗粒度仍未收口 | 继续补功能 |
| N11 | 调度策略：外部优先 + 空闲优先 + LLM 保底 | 部分落地 | 已将候选选择收口为可配置策略：支持 `preferExternal`、`requireIdle`、`forceAccessType(API_KEY_LLM 纯保底回归)`；并提供“初始分配自动选人入口”与 `autoAssignOnCreate` 开关（默认关闭以保持 PENDING+claim 工作流）。尚未形成基于“外部执行超时/掉线次数阈值”的自动回退与再分配闭环 | 继续补功能 |

---

## 5. 当前建议优先级

1. ~~先继续关闭文档失真项：D1、D2、D5、D6~~ ✅ 全部已关闭（2026-07-13）
2. 再继续夯实执行链：N1、N6、N9、N10
3. 最后推进产品化编排能力：N2、N8

---

## 6. 本轮文档治理结论

- `v3.0` 已不再作为路线图继续维护，而是降级为《架构设计参考》
- `v2.4` 已归档为历史资产，不再作为事实源
- 后续若出现“设计理念、目标态、实现状态、执行记录”混写，优先拆回文档矩阵，而不是继续往单个文件里堆内容
