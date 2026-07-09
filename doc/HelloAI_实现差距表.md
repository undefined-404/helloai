# HelloAI 实现差距表

## 摘要

### 1. 文档定位

本文件用于回答：

- 文档原先承诺了什么
- 当前代码实际做到了什么
- 差距属于“应改文档”还是“应补功能”

本文件是当前版本对表、验收、排期的主参照文档。

### 2. 当前总体结论

基于当前仓库、README、路线图、技术方案、onboarding 与验收脚本，当前差距大致分为三类：

- 已交付闭环：MCP 接入、鉴权、工具链路、心跳在线态、熔断降级、Reconcile 健康检查、Session TTL 清理
- 文档口径失真：历史路线图与部分说明文档对工具数量、兼容通道、当前默认能力的表述已部分落后于实现
- 路线图未落地：工作流模板、平台内 AgentExecutor、浏览器 MCP、凭证保险库等仍属于目标态
- 已补充结论：当前父工程 `spring-ai 1.1.8` 已通过 macOS 下 `verify-mcp-auth.sh` + `verify-mcp-e2e.sh` 主链路回归

### 3. 状态定义

- 已交付：代码、配置、数据结构、主要调用链与验收链路均已闭环
- 部分落地：已有主体实现，但仍缺配套链路、兼容层或文档口径未收口
- 未落地：文档提出但仓库内无对应主体实现
- 文档失真：实现已变化，但文档仍沿用旧口径

---

## 细则

### 1. 已交付闭环能力

| 主题 | 当前结论 | 处理建议 |
|---|---|---|
| MCP SSE + 鉴权链路 | 已交付 | 保持 README 与验收脚本同步 |
| MCP 业务工具链路 | 已交付 | 统一工具数量说明 |
| Agent 在线状态三件套 | 已交付 | 以代码与初始化脚本为准 |
| 熔断降级与替代选人 | 已交付 | 在 README 中保留简述 |
| Reconcile 健康检查 | 已交付 | 在差距表中只保留状态，不在路线图中继续堆日志 |
| Session TTL 清理 | 已交付 | 记入执行记录，不再写入路线图正文 |

### 2. 文档与实现不一致项

| 编号 | 主题 | 文档口径 | 当前实现 | 状态 | 建议 |
|---|---|---|---|---|---|
| D1 | MCP 工具数量 | README 仍写标准工具集为 6 个 | 代码实际对外是 echo + 7 个业务工具 | 文档失真 | 改 README 与差距表，不改代码 |
| D2 | 兼容通道定位 | 历史文档对 `tools/list`/`tools/call` 兼容入口说明不足 | 兼容入口仍存在，但与主 MCP schema 口径不完全一致 | 文档失真 | 明确标记 deprecated/兼容通道 |
| D3 | 路线图正文混入实施日志 | 路线图同时承担计划与执行记录 | 当前 v2.5 已臃肿 | 文档失真 | 把执行记录迁出，路线图只保留基线目标 |
| D4 | Spring AI 版本口径 | 历史路线图附录 F.8 仍写 `1.1.0` 永久锁定 | 当前父工程实际为 `1.1.8`，且 macOS 回归已通过 | 已收口 | README/基线已同步，历史路线图仅保留归档说明 |
| D5 | `/api/tools/cli` 鉴权口径 | 技术方案曾将其描述为需要鉴权的 Agent 入口 | 当前 MVC 配置已放行该接口 | 文档失真 | 二选一收口，以实际行为为准 |
| D6 | 心跳刷新规则口径 | 路线图写“心跳/拉取/ack 任一请求即刷新 `last_seen_at`” | 当前明确刷新在线态的是 `heartbeat` 工具 | 文档失真 | 若设计以文档为准则补代码，否则改文档 |

### 3. 路线图 N1-N10 差距总表

| 编号 | 主题 | 当前状态 | 差距定义 | 处理建议 |
|---|---|---|---|---|
| N1 | 接入类型 + 能力画像 | 已交付 | 字段与主体逻辑已在主线使用 | 保持现状，文档收口 |
| N2 | 可配置工作流模板 | 未落地 | 缺模板表、模板管理、调度模板化入口 | 后续功能迭代 |
| N3 | MCP Server 工具集 | 已交付但口径不一致 | 实际工具数量已超过最初“固定 6 工具”表述 | 先改文档，再评估兼容策略 |
| N4 | 心跳与在线判定 | 已交付 | 三件套已形成主线能力 | 保持现状 |
| N5 | 熔断降级 | 已交付 | 已形成 per-agent 熔断与 fallback | 保持现状 |
| N6 | API Key 类 AgentExecutor | 未落地 | 缺统一接口与平台侧直调执行路径 | 后续重点补齐 |
| N7 | 健康检查改写 | 已交付 | Reconcile 与离线重分配已具备 | 保持现状 |
| N8 | 网页版 AI 浏览器接入 | 未落地 | 仅见 `WEB_BROWSER` 枚举预留，缺执行模块 | 后续独立迭代 |
| N9 | Spring AI ChatClient 复用 | 未落地 | 文档提及但仓库未见实际使用点 | 与 N6 联动实施 |
| N10 | 工牌模式 + `credential_vault` | 未落地 | 缺表结构、实体、服务、迁移策略 | 后续重点补齐 |

### 4. 额外缺口

| 编号 | 主题 | 当前状态 | 建议 |
|---|---|---|---|
| E1 | `task_timeline` + MinIO 镜像双写 | 部分落地 | 当前以 DB 侧为主，MinIO 归档镜像后补 |
| E2 | 附件 HTTP 上传端点 | 有意未做 | 若继续采用“客户端直传 + 元数据登记”，应在文档中明确 |
| E3 | 旧路线图作为唯一对表依据 | 不适合继续沿用 | 以后以本文件作为现实差距权威 |

### 5. 建议优先级

建议后续工作顺序如下：

1. 先修文档失真项 D1-D3、D5-D6
2. 再补平台执行基座：N10 + N6 + N9
3. 之后补产品编排能力：N2
4. 最后评估网页版 AI 接入：N8

### 6. 文档失真项明细

#### D1 MCP 工具数量

- 文档定位：
  - `README.md`
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-core/src/main/java/com/helloai/core/mcp/McpMcpServer.java`
  - `helloai-core/src/main/java/com/helloai/core/mcp/McpToolConfig.java`
- 当前状态：文档失真
- 差距定义：历史文档长期沿用“固定 6 工具”口径，但当前主线已是 `echo + 7 个业务工具`
- 结论：改文档
- 备注：该项属于文档未跟随实现演进，不建议为了贴文档去回退代码能力

#### D2 兼容通道定位

- 文档定位：
  - `README.md`
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-api/src/main/java/com/helloai/api/controller/McpController.java`
- 当前状态：文档失真
- 差距定义：旧 `tools/list` / `tools/call` 兼容通道仍在，但主文档没有清晰说明“主通道”和“兼容通道”的边界
- 结论：改文档
- 备注：应明确 `MCP SSE` 是主通道，`McpController` 是兼容保留且已 deprecated

#### D3 路线图正文混入实施日志

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：不适用
- 当前状态：文档失真
- 差距定义：路线图同时承担目标态设计、现状盘点、阶段拆分、实施记录，导致正文越来越臃肿
- 结论：改文档
- 备注：已通过新增“项目基线 / 实现差距 / 迭代执行记录”三类文档开始收口

#### D4 Spring AI 版本口径

- 文档定位：
  - `README.md`
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `pom.xml`
- 当前状态：已收口
- 差距定义：此前历史路线图附录 F.8 仍保留“`spring-ai 1.1.0` 永久锁定”的旧结论，而当前父 POM 已运行在 `1.1.8`
- 结论：已按现状更新 README / 项目基线 / 执行记录，并在历史路线图中补充“已被后续验证结论覆盖”的归档说明
- 备注：当前 `spring-ai 1.1.8` 已在 macOS 下通过 `verify-mcp-auth.sh` + `verify-mcp-e2e.sh` 主链路回归

#### D5 `/api/tools/cli` 鉴权口径

- 文档定位：
  - `doc/HelloAI_技术方案与补齐清单_v1.1.md`
- 代码定位：
  - `helloai-api/src/main/java/com/helloai/api/config/WebMvcConfig.java`
- 当前状态：文档失真
- 差距定义：技术方案中的接口定位与当前拦截器排除规则不一致
- 结论：改文档或改实现，二选一
- 备注：当前更建议先按现有代码行为收口文档

#### D6 心跳刷新规则

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-core/src/main/java/com/helloai/core/service/McpToolService.java`
  - `helloai-core/src/main/java/com/helloai/core/service/HeartbeatService.java`
- 当前状态：文档失真
- 差距定义：路线图描述“pullTasks/ack/heartbeat 任一请求都刷新 `last_seen_at`”，但当前主线显式刷新在线态的是 `heartbeat`
- 结论：待决策
- 优先建议：先按当前代码更新文档；若后续确实需要“拉取/回执也算活跃”，再补代码

### 7. 路线图 N1-N10 逐项对表

#### N1 接入类型 + 能力画像

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-common/src/main/java/com/helloai/common/constant/AgentAccessType.java`
  - `helloai-core/src/main/java/com/helloai/core/entity/Agent.java`
  - `helloai-start/src/main/resources/db/migration/V1__init_all.sql`
- 当前状态：已交付
- 差距定义：数据结构、枚举、能力字段与数据库列均已落地
- 结论：改文档
- 优先级：P0

#### N2 可配置工作流模板

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - 当前仓库未见 `WorkflowTemplate` / `ProcessTemplate` / 模板表 / 模板管理控制器
- 当前状态：未落地
- 差距定义：缺模板表、模板配置、角色绑定、模板管理接口与按模板调度入口
- 结论：真补功能
- 优先级：P0

#### N3 MCP Server 工具集

- 文档定位：
  - `README.md`
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-core/src/main/java/com/helloai/core/mcp/McpMcpServer.java`
  - `helloai-core/src/main/java/com/helloai/core/mcp/McpToolConfig.java`
  - `helloai-core/src/main/java/com/helloai/core/service/AgentMcpServerService.java`
- 当前状态：已交付但口径不一致
- 差距定义：主体能力已交付，但工具数量与兼容入口说明仍未文档收口
- 结论：改文档
- 优先级：P0

#### N4 心跳与在线判定

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-core/src/main/java/com/helloai/core/service/HeartbeatService.java`
  - `helloai-api/src/main/java/com/helloai/api/controller/AdminAgentController.java`
  - `helloai-start/src/main/resources/db/migration/V1__init_all.sql`
- 当前状态：已交付
- 差距定义：三件套、在线态枚举和 SLEEPING 防护均已形成主线能力
- 结论：改文档
- 优先级：P0

#### N5 熔断降级

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-core/src/main/java/com/helloai/core/service/ResilientDispatcher.java`
  - `helloai-core/src/main/java/com/helloai/core/service/AgentSelector.java`
  - `helloai-core/src/main/java/com/helloai/core/service/CircuitBreakerEventRecorder.java`
- 当前状态：已交付
- 差距定义：per-agent 熔断、fallback 分配和事件记录均已落地
- 结论：改文档
- 优先级：P1

#### N6 API Key 类 AgentExecutor

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - 当前仓库未见 `AgentExecutor` 接口、`ApiKeyExecutor` 实现或平台内直调 LLM 执行入口
- 当前状态：未落地
- 差距定义：缺统一执行抽象层，也缺基于平台侧配置直调模型的执行通路
- 结论：真补功能
- 优先级：P1

#### N7 健康检查改写

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-job/src/main/java/com/helloai/job/task/AgentHealthCheckTask.java`
  - `helloai-core/src/main/java/com/helloai/core/mapper/AgentMapper.java`
- 当前状态：已交付
- 差距定义：超时扫描、Redis 二次确认、CAS 标离线、任务重分配都已具备
- 结论：改文档
- 优先级：P0

#### N8 网页版 AI 浏览器接入

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `helloai-common/src/main/java/com/helloai/common/constant/AgentAccessType.java`
  - 当前仓库未见浏览器 MCP 接入模块、执行器或联动端点
- 当前状态：未落地
- 差距定义：目前只有 `WEB_BROWSER` 枚举和默认能力预留，未见真实接入链路
- 结论：真补功能
- 优先级：P2

#### N9 Spring AI ChatClient 复用

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - `pom.xml`
  - 当前仓库未见 `ChatClient` 直接使用点
- 当前状态：未落地
- 差距定义：虽已引入 Spring AI 体系，但当前使用重点在 MCP Server，不在平台内 LLM 执行链
- 结论：真补功能
- 优先级：P1

#### N10 工牌模式 + `credential_vault`

- 文档定位：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`
- 代码定位：
  - 当前仓库未见 `credential_vault` 表、实体、服务、迁移逻辑
- 当前状态：未落地
- 差距定义：真实凭证未进入独立保险库，`api_key` 语义升级和历史迁移策略尚未实现
- 结论：真补功能
- 优先级：P1

### 8. 当前文档治理结论

- 当前最优先处理的是文档失真项，而不是继续膨胀历史路线图
- 差距关闭后，应优先回填本文件与《迭代执行记录》，而不是把日志直接写回路线图正文
- 后续若启动真正的大功能迭代，建议以 `N10 + N6 + N9` 为第一组，以 `N2` 为第二组，以 `N8` 为第三组

---

## 模板

### 1. 差距项模板

后续新增差距项时，统一按以下模板填写：

```md
### [编号] [主题]

- 文档定位：
- 代码定位：
- 当前状态：已交付 / 部分落地 / 未落地 / 文档失真
- 差距定义：
- 结论：改文档 / 做兼容 / 真补功能
- 优先级：P0 / P1 / P2
- 备注：
```

### 2. 评审结论模板

```md
## 本轮评审结论

- 评审日期：
- 评审范围：
- 新增已交付项：
- 新增差距项：
- 已关闭差距项：
- 下一轮建议：
```
