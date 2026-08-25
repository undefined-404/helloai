# HelloAI 迭代执行记录

## 1. 文档定位

本文档用于记录每一轮实际执行了什么，不再把实施日志写回历史路线图正文�?

记录目标�?

- 让后来者快速知道最近做了哪些事
- 让差距表可以对应到“哪一轮关闭了哪一项�?
- 让历史路线图保持“目标态文档”的可读�?

---

## 2. 近期关键轮次

### 2026-08 监控体系阶段1：Prometheus + Grafana 指标监控（借鉴 maticube）

#### 1. 范围

- 新增监控体系阶段1（指标监控）：应用 Actuator Prometheus 端点 + Prometheus + Grafana 监控栈 + Redis/PG exporter；RabbitMQ 复用 management 镜像自带 rabbitmq_prometheus 插件（15692），无需额外 exporter。
- 参考 `E:\yhzx\1027\maticube\deploy\monitoring`（已在 maticube 验证可用的全套监控实现）落地最小集，镜像版本与其保持一致（prometheus v2.51.0 / grafana 10.4.0 / postgres-exporter v0.15.0 / redis_exporter v1.61.0）。
- 不做：ELK/Loki 日志聚合（阶段2 预留）、Grafana 告警通知、看板美化。

#### 2. 实际落地

- `helloai-start/pom.xml` 新增 `micrometer-registry-prometheus` 依赖（actuator 依赖此前已有）；`application.yml` management 段 exposure 增加 `prometheus`，新增 `http.server.requests` 直方图（percentiles-histogram + SLO 桶 100ms~5s）与 `application=helloai` 公共标签。
- 新增 `deploy/monitoring/`：`docker-compose.monitoring.yml`（prometheus + grafana + redis-exporter + postgres-exporter，端口均绑 127.0.0.1 不暴露公网；显式 `name: helloai-monitoring` 避免与 maticube 监控 compose 同目录默认 project 名冲突导致 down/up 互伤）+ `prometheus/prometheus.yml`（服务器版抓 compose 内网 `app:6565`）/ `prometheus-local.yml`（本地版抓 `host.docker.internal:6565`，默认挂载）+ grafana provisioning（Prometheus 数据源 + 「HelloAI 监控总览」看板自动加载）+ README（含 PG 监控账号 SQL）。
- 看板覆盖：JVM 堆/非堆、系统 CPU、活跃线程、接口 RT P50/P95/P99、QPS、GC 暂停、Top5 慢接口、RabbitMQ 队列就绪/未确认消息、PG 连接数/事务速率、Redis 内存/连接数、抓取目标健康。
- 验证：`mvn compile -pl helloai-start -am` 通过；本地监控栈启动后 Prometheus targets 中 rabbitmq/redis/postgres/prometheus 全部 UP（rabbitmq 15692 插件实测可抓），`redis_memory_used_bytes`、`rabbitmq_queue_messages_ready` 指标实测采集成功；Grafana basic auth 确认「HelloAI 监控总览」看板 provisioning 加载成功。

#### 3. 遗留

- app 的 `/actuator/prometheus` 需重启后端（新依赖装配）后生效，重启后 `helloai-app` target 恢复 UP。
- PostgreSQL 监控账号 `helloai_monitor` 未创建（写操作由人工执行，SQL 见 `deploy/monitoring/README.md`），建号后 postgres-exporter 自动恢复指标采集。
- 服务器部署：将 `deploy/monitoring/` 拷至服务器后设 `PROMETHEUS_CONFIG=./prometheus/prometheus.yml` 再启动；端口绑 127.0.0.1，公网不可达（SSH 隧道访问）。
- 阶段2 预留：Loki 日志聚合、告警通知（Grafana Alerting + 钉钉/企业微信 webhook）。


### 2026-08 博查联网搜索 API Key 接入系统设置页（含占位符字面量修复）

#### 1. 范围

- 博查（bocha）联网搜索 API Key 从 yml/env 迁移到系统设置页配置，修 bug + 补功能。
- 发现并修复隐患：`WebSearchProperties.bochaApiKey` 默认值为字符串字面量 `${BOCHA_API_KEY:}`，无 yml 条目时占位符不解析，运行时把字面量当 Bearer token 发出，博查搜索实际一直失效。
- 不做：不动 `@ConditionalOnProperty` 的 provider 启动期选择（bocha 默认）；tavily/deepseek-native 不上设置页；不引入 vault 重设施。

#### 2. 实际落地

- 新增 `WebSearchCredentialKeyStore`（core/planner/search）：解析优先级 sys_config 加密值 > yml/env 兜底（仿 `AgentBaseUrlResolver` 模式），`enc:` 前缀 AES-GCM 加密存储，并剔除占位符字面量残留；键名 `web-search.bocha.api-key`，blank=清除。
- `BochaWebSearchServiceImpl` 改走 KeyStore 取 Key，不再直读 properties。
- `SysConfigServiceImpl.getAllAsMap` 对 `.api-key` 后缀键对外脱敏（`********`）；`setValue` 凭证键不落日志明文。`AdminConfigController.getByKey` 同步脱敏。
- 新增 `PUT /api/admin/config/webSearchApiKey`（加密落库，实时生效）；前端 `Settings.vue` 新增「联网搜索」区（博查 API Key 密码输入 + 已配置/未配置状态标签），未改动时不提交、防把掩码当 Key 写入。
- 验证：IntelliJ 内置 Maven + JDK 17 下 core/api 主代码与测试编译通过；`vue-tsc --noEmit` 通过。

#### 3. 遗留

- 需重启后端（新 Bean 装配）后新链路才生效；设置页保存 Key 后无需再重启。
- tavily / deepseek-native 的 Key 仍为 yml/env 配置，后续如有需要可按同一模式扩展。
- `BOCHA_API_KEY` 环境变量仅作为部署级兜底保留，建议文档口径以设置页为主。


### 2026-08 质量实测门控接入系统设置页

#### 1. 范围

- 将 `admin.quality.enabled` 门控开关纳入系统设置页，解决非开发人员无法开启管理侧质量实测端点的问题。
- 属于前端补功能小闭环，后端零改动，门控语义与生产默认关闭不变。

#### 2. 实际落地

- `Settings.vue` 基础配置区新增「质量实测端点」开关（含配置键与用途说明）；加载时从 `getConfig()` 读取，保存时随「保存设置」经 `batchUpdateConfig` 写回。
- 同轮清理：移除设置页「平台名称」表单项——`system.name` 全仓库仅有写入（初始化向导/设置页）无任何读取，属死配置；「外部访问地址」（`helloai.base-url`）经 `AgentBaseUrlResolver` 被 SKILL/接入内容生成真实消费，保留。
- `AdminQualityController` Javadoc 同步补充「可在系统设置页开关切换」的口径。
- 验证脚本自动开门逻辑未动，`vue-tsc --noEmit` 通过。

#### 3. 遗留

- 质量看板页（/quality-dashboard）在门控关闭时仍直接调端点拿 403，未做页内引导开关（可后续优化）。
- `system.name` 配置键仍由初始化向导写入，但无任何消费方；后续可考虑用于侧边栏/页面标题或彻底下线。


### 2026-07 环境与主线收�?

#### 1. 范围

- 对齐基础环境与主线工�?
- 清理文档职责混写问题

#### 2. 实际落地

- 对齐 JDK 17、本�?DB 初始化与 Spring Boot 3.x 兼容�?
- 修复部分后端接口与前端主流程问题
- 建立“项目基�?/ 实现差距 / 迭代执行记录”三层文档体�?

#### 3. 遗留

- README 与历史文档仍需持续按差距表校正
- 工作流模板、浏览器 Agent、独立消费载体等能力仍未闭环

---

### 2026-07 调度解耦主链收�?

#### 1. 范围

- �?`doc/design/HelloAI_调度解耦重构分�?md` 推进执行链收�?

#### 2. 实际落地

- �?`ASSIGNED` 后的执行路径收口为“命令创�?-> 本地 consumer -> 结果回写�?
- 真实 blocked 样本已验证可从重分配推进�?`REVIEW`
- 补齐并发双击 `/api/sub-tasks/execute/{id}` 去重与超时补偿稳定收敛的运行态证�?

#### 3. 遗留

- 消费者仍为本�?Spring 事件，尚未切换为独立 MQ / DB poller
- `SubTaskExecutionService` 仍保留部分执行编排职�?
- offline 场景仍需补更强的运行态取�?

---

### 2026-07 DB Poller 主线�?

#### 1. 范围

- 将执行命令消费载体从“EVENT 主消�?+ Poller 兜底”推进到“DB Poller 主消费”（默认�?
- 修复 POLLER 模式�?Poller 找不到消费者导致无法启动的 wiring 问题
- 本轮不新�?MQ Consumer，不扩展 RabbitMQ 业务消费�?

#### 2. 实际落地

- `AgentExecutionProperties` 支持 `EVENT / POLLER / BOTH` 三种模式，默�?`POLLER`；默认扫描周期调整为 `1000ms`
- `ExecutionCommandService`�?
  - `POLLER` 模式只落�?PENDING 命令，不发布本地事务事件
  - `EVENT / BOTH` 模式继续发布事件
- `ExecutionCommandPoller`�?
  - `POLLER / BOTH` 模式扫描全部 PENDING 作为主消�?
  - `EVENT` 模式仅扫描孤�?PENDING 作为兜底
  - 改为依赖抽象 `ExecutionCommandConsumer`
- `LocalExecutionCommandConsumer`�?
  - 消费 Bean 始终存在（供 Poller 注入�?
  - 本地事务事件仅作�?`EVENT/BOTH` 模式的适配入口
- `application.yml` 默认配置改为：`consumer-mode: POLLER`、`poller-interval-ms: 1000`（避免多开关冲突）

#### 3. 验证

- 启动期验证：`consumer-mode=POLLER` 时应用可正常启动，Poller 能正常注入并调用消费�?
- 行为验证：`POLLER` 模式下命令创建后不依赖事务事件，PENDING 记录可被 Poller 周期扫描推进

#### 4. 影响

- 对外行为变化：执行命令主消费载体默认切换�?DB Poller
- 配置变化：`helloai.execution.consumer-mode` 默认 `POLLER`；`helloai.execution.poller-interval-ms` 默认 `1000`
- 代码变化：执行命令发�?消费链路�?`consumer-mode` 分流，Poller 逻辑从“孤儿兜底”升级为“主消费�?

#### 5. 遗留

- 执行命令尚未新增 MQ Consumer，未形成“执行命�?�?MQ �?独立 Consumer”的主链�?
- 需要补�?POLLER 主消费模式下的运行态取证脚本与回归用例（崩溃恢�?重复消费/晚到结果�?

---

### 2026-07-11 文档矩阵二次修订

#### 1. 范围

- 修正文档矩阵中的二次失真
- 重新收口历史路线图、设计参考与核心三层文档的职责边�?

#### 2. 实际落地

- 识别�?`HelloAI_多类型Agent接入与调度可靠性开发路线图_v3.0.md` 存在多处事实性失真，不适合继续作为路线图或事实参�?
- �?`v3.0` 降级并重写为 `doc/design/HelloAI_架构设计参�?md`，只保留�?
  - 参考来源说明与综合吸收边界（OpenMOSS / AgentTeams-main / Vibe-Skills-main / 优先级机制设计文�?/ trade-cloud�?
  - 技术栈版本�?
  - 核心概念定义（调度分离、双心跳、熔断、Outbox、TCC、工作单元、控制命令）
  - 目标态方向说�?
- �?`doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md` 归档为：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`
- 更新《实现差距表》：
  - D3 / D4 / D7 标记为已关闭
  - N1 明确为“Outbox 基础能力已具备，但执行命令尚未接入独�?MQ / DB poller�?
  - N6 明确为“消费者仍为本�?Spring 事件，尚未切换到独立 MQ / DB poller�?
- 更新《项目基线文档》，明确最终文档矩阵：
  - 核心三层
  - 专项分析
  - 历史资产
  - 设计参�?
- 更新 README、README.en 与《当前能力确认矩阵》中的文档引用口�?
- 三轮文档矩阵二次分析：确认三个历史文档（v1.1、OpenMOSS 对比、v2.0 开发清单）已有归档标记
- 修复 README.en.md 文档列表不完整（补上《调度解耦重构分析》与《执行链路架构分析》）
- 修正 `McpController.java` Javadoc �?预计 v3.0 移除"�?预计下个大版本移�?
- 继续扩写 `doc/design/HelloAI_架构设计参�?md`：将 `OpenMOSS / AgentTeams-main / Vibe-Skills-main / HelloAi Agent 任务调度优先级机制设计文�?/ trade-cloud` 的吸收边界、适用落点与开发顺序写清楚
- 更新《项目基线文档》：新增“已确认的参考吸收原则”与“已确认的后续开发方向”，明确哪些来源指导接入层、调度层、运行时层与可靠性层

#### 3. 验证

- 文档链路检查：核心文档已不再相互引用错误的 `v3.0` 路径
- 职责边界检查：设计理念、现实基线、差距判断、执行记录已重新分层
- 引用一致性检查：README / 基线 / 差距�?/ 能力矩阵已切到新矩阵口径
- 参考来源边界检查：外部项目已按“接入层 / 调度�?/ 运行时层 / 可靠性层”拆分，不再混成单一方案来源

#### 4. 影响

- 对外行为变化：无
- 文档变化�?
  - 新增 `doc/design/HelloAI_架构设计参�?md`
  - 新增 `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`
  - 收口 `doc/HelloAI_项目基线文档.md`
  - 收口 `doc/HelloAI_实现差距�?md`
  - 回写 `doc/log/HelloAI_迭代执行记录.md`
  - 收口 `README.en.md`（补全文档列表）
  - 收口 `helloai-api/.../McpController.java`（v3.0 措辞修正�?
- 数据结构变化：无

#### 5. 遗留

- D1 / D2 / D5 / D6 仍需继续按代码事实逐份清理历史文档
- 若后续新增设计文档，必须先判断是否已经可�?基线 / 差距 / 执行记录 / 专项分析"覆盖，避免再次出现职责重�?

---

### 2026-07-11 文档资产清理与借鉴技术细节沉淀

#### 1. 范围

- 按借鉴项目维度整理外部参考的具体技术细�?
- 清理已无留存价值的历史文档

#### 2. 实际落地

- 新增 `doc/design/HelloAI_外部项目借鉴技术细�?md`：按 AgentTeams-main / Vibe-Skills-main / OpenMOSS / 优先级设计文�?/ trade-cloud 五个维度，列出具体文件路径、代码模式与 HelloAI 落点映射，含借鉴优先级速查�?
- 删除 4 个历史文档：
  - `HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`（仅剩归档声明，无实质内容）
  - `HelloAI_vs_OpenMOSS_功能对比与实现方�?md`（历史对标分析，其洞察已吸收到架构设计参考和借鉴技术细节中�?
  - `HelloAI_技术方案与补齐清单_v1.1.md`�?714 行历史方案，与当前代码现实严重脱节）
  - `HelloAI_Agent接入内容生成功能开发清单_v2.0.md`（功能已基本落地，开发清单已完成使命�?
- 更新《项目基线文档》�? 文档矩阵：移�?历史资产"分类，新�?设计参�?"能力确认""工程规范""其他参�?分层
- 更新 README.md / README.en.md 文档列表与导航链�?
- 更新 AGENTS.md 必读文档列表（移�?v2.0，替换为架构设计参考）

#### 3. 影响

- 对外行为变化：无
- 文档变化�?
  - 新增 1 �?
  - 删除 4 �?
  - 修改 5 份（基线文档 / 迭代记录 / README / README.en / AGENTS.md�?
- 数据结构变化：无

#### 4. 遗留

- `doc/archive/HelloAI_当前能力确认矩阵.md` 与《实现差距表》存在部分内容重叠，后续可考虑合并或明确差异边�?
- README 项目结构图中不再列举已删除的历史文档

---

### 2026-07-13 �?Agent Skills / Rules 口径同步

#### 1. 范围

- 将多�?Agent 使用的本�?preflight skill / rule 统一到新的文档矩阵口�?

#### 2. 实际落地

- 更新 `.agents/skills/helloai-preflight/SKILL.md`�?
  - 必读文档�?5 份调整为 6 �?
  - 移除已删除的 `HelloAI_Agent接入内容生成功能开发清单_v2.0.md`
  - 新增 `doc/design/HelloAI_调度解耦重构分�?md` �?`doc/design/HelloAI_架构设计参�?md`
  - 补充“调度、执行链、异步回写、MQ 解耦优先遵循调度解耦分析”的规则
- 将上�?preflight skill 同步镜像到：
  - `.trae/skills/helloai-preflight/SKILL.md`
  - `.qoder/skills/helloai-preflight/SKILL.md`
  - `.cursor/skills/helloai-preflight/SKILL.md`
  - `.claude/skills/helloai-preflight/SKILL.md`
- 同步更新 `.trae/rules/执行规则.md`，确�?Trae 规则文件�?skill 口径一�?
- 确认 `.codex` 当前只有 `hooks.json`，没有独立本�?skills 目录，因此本轮不新增重复 skill 配置

#### 3. 验证

- 全目录检索确认多�?preflight skill 已不再引�?`v2.0` 开发清�?
- 全目录检索确认多�?preflight skill 已统一引用《调度解耦重构分析》与《架构设计参考�?
- 确认 Trae rule 与共�?preflight skill 文本一�?

#### 4. 影响

- 对外行为变化：无
- 配置变化�?
  - 修改 6 �?preflight skill / rule 文件
- 数据结构变化：无

#### 5. 遗留

- 若后续新增面�?Codex 的本�?skills 目录，应继续沿用 `.agents/skills/helloai-preflight` 作为母版


### 2026-07-13 P0 文档失真关闭——D1/D2/D5/D6

#### 1. 范围

- 关闭实现差距表中全部四项文档失真（D1/D2/D5/D6�?
- 同步收口 N3（MCP Server 工具集口径）

#### 2. 实际落地

- **D1（MCP 工具数量口径�?*：确�?README 已明�?"工具数量不写死，�?	ools/list 实际输出为准"，关�?
- **D2（兼容通道定位�?*：确�?README 已明�?"MCP SSE 是唯一主通道，REST 	ools/list / 	ools/call 属兼容保�?，关�?
- **D5�?api/tools/cli 鉴权口径�?*：代码验证——WebMvcConfig �?/api/tools/cli 已通过 excludePathPatterns 排除鉴权（作�?CLI 工具的公开下载入口，设计如此），关�?
- **D6（心跳刷新规则口径）**：确�?README 已明�?"last_seen_at/在线态刷新以 heartbeat 为主"，关�?
- 同步�?D1-D7 的状态从 "未关�?已关�? 统一�?"�?已关�?
- 更新 N3 状态：�?"已交付但口径未完全收�? 收口�?"已交�?
- 更新 Section 5 优先级：将条�?1 标记为已完成

#### 3. 影响

- 对外行为变化：无
- 文档变化：doc/HelloAI_实现差距�?md�? 行状态修�?+ 1 �?N3 修改 + Section 5 更新�?
- 数据结构变化：无

#### 4. 遗留

- N1/N6/N9/N10 仍待推进（属于后续工作）
- 接近零遗留——本轮是所有文档失真项的最终关闭轮
---

### 2026-07-15 AgentHub 方案文档收口

#### 1. 范围

- 将外�?Agent 接入层增强思路从历史草案中收编为新的专项方案文�?
- 统一 V1 / V2 / V3 三阶段版本口径，作为后续扩展参�?

#### 2. 实际落地

- 新增 `doc/archive/HelloAI_agenthub.md`，作�?AgentHub 方向的主方案文档，明确：
  - 本文档用于描述外�?Agent 接入层增强方案，而非当前实现事实
  - 方案分为三阶段：
    - V1 最小版：`agent_duty_lease` + `checkIn/checkOut` + 值班优先分配 + 看板展示
    - V2 增强版：Bridge 守护进程桥接当前 `/mcp/sse` 主通道
    - V3 产品版：门铃通知通道 + 一键安装，通知层只负责唤醒
  - 当前主线约束�?
    - 不引入第二控制面
    - 不改�?`MCP-over-SSE` 为主协议的定�?
    - 不新增与 `online_status` 平行竞争�?Agent 主状态枚�?
- �?`doc/archive/helloai_agenthub_complete.md` 降级为历史草案，并补充顶部归档说明，明确�?
  - 旧文档保留原始设想与灵感
  - 其中关于 `AgentStatus` 扩展、WebSocket 主通道、ShiftManager 的方案不再直接作为开发主参�?
  - 后续统一�?`doc/archive/HelloAI_agenthub.md` 为主
- 在新文档中补充“旧文档能力映射表”，把旧草案中的核心想法收口为：
  - 值班租约模型
  - `checkIn/checkOut`
  - `submitResult` 语义扩展
  - Bridge
  - 门铃通知通道
  - 看板增强

#### 3. 影响

- 对外行为变化：无
- 文档变化�?
  - 新增 `doc/archive/HelloAI_agenthub.md`
  - 修改 `doc/archive/helloai_agenthub_complete.md`
  - 回写 `doc/log/HelloAI_迭代执行记录.md`
- 数据结构变化：无

#### 4. 遗留

- `agent_duty_lease`、`checkIn/checkOut`、Bridge、门铃通知目前仍处于方案阶段，尚未进入代码实现
- 后续若基于该方案开始开发，应先�?preflight 守则对照基线 / 差距 / 调度解耦分析，再按 V1 �?V2 �?V3 顺序推进，避免跳阶段

### 2026-07-13 P1 代码修复——双回写风险 + LLM 调用可观测�?

#### 1. 范围

- 修复 LocalExecutionCommandConsumer.consume() catch 块中的双重回写风�?
- �?SubTaskExecutionService.executeOnce() 增加 LLM 调用前后的可观测 timeline 事件

#### 2. 实际落地

- **修复 1：移�?Consumer 中的 	hrow e**
  - 原逻辑：catch �?markFailed() + 	hrow e，导�?executeOnce() 内部�?handleFailure() �?consumer �?markFailed() 形成双重回写竞�?
  - 新逻辑：catch 中仅 markFailed()，不�?rethrow，注释说明子任务降级已由内部 handleFailure 完成
  - 影响文件：helloai-core/.../LocalExecutionCommandConsumer.java�? 行改动）

- **修复 2：增�?LLM 调用前后可观测事�?*
  - �?platformAgentExecutionService.executeSync() 前后分别记录 sub_task_llm_call_start �?sub_task_llm_call_end �?task_timeline
  - 在异常路径中记录 sub_task_llm_call_failed
  - 这三个新事件使外部可以区�?卡在执行编排�?还是"卡在 LLM HTTP 调用�?
  - 影响文件：helloai-core/.../SubTaskExecutionService.java�?9 行）

#### 3. 影响

- 对外行为变化：无（LLM 调用事件仅为观测增强，不影响业务路径�?
- 代码变化：LocalExecutionCommandConsumer.java（语义变更：不再 rethrow）、SubTaskExecutionService.java（新�?timeline 事件�?
- 数据结构变化�?ask_timeline 表新增三种事件类型（sub_task_llm_call_start / sub_task_llm_call_end / sub_task_llm_call_failed�?

#### 4. 遗留

- 并发场景回归测试已在 P2 轮次完成
- SubTaskExecutionService.executeOnce() 的编�?执行-回写混合结构未在本轮解决（属�?Phase 2 WorkUnit 显式建模的范畴）

### 2026-07-13 P2 并发缺陷修复与测�?

#### 1. 范围

- 修复 P2-2 揭示的真实并发缺陷：补偿任务�?subTask 推进�?BLOCKED 后，consumer �?handleSuccess 缺少状态前置检�?
- 补充 P2-1/P2-2/P2-3 三个 Mockito 单元测试

#### 2. 实际落地

- **P2-2 缺陷修复**：在 ExecutionResultHandler.handleSuccess() 中增加状态前置检�?
  - 如果 subTask.status != IN_PROGRESS，不推进�?REVIEW，不覆写 context
  - 记录 sub_task_execute_result_discarded 事件�?timeline，包含当前状态和 LLM 结果信息
  - 添加 @Slf4j 注解

- **P2-2 测试**：shouldNotReviveSubTaskWhenStatusIsBlocked
  - 验证 BLOCKED 状态下 handleSuccess 不调�?submit、不覆写 context、记�?discarded 事件

- **P2-3 测试**：shouldNotBlockWhenStatusIsNotInProgress
  - 验证 handleFailure 对已�?BLOCKED 的子任务不重复调�?block

- **P2-1 测试**：shouldUseBothRowLockAndHasPendingOrRunningForDuplicatePrevention
  - 验证 getByIdForUpdate（行锁）�?hasPendingOrRunning（应用层检查）被按序调�?

- **P1 测试同步更新**：LocalExecutionCommandConsumerTest.shouldMarkFailedWhenConsumeThrowsException
  - 移除 try/catch 包装，因�?P1 修复�?consume 不再 rethrow

#### 3. 影响

- 代码变化�?
  - ExecutionResultHandler.java�?状态前置检�?+@Slf4j�?
  - ExecutionResultHandlerTest.java�?2 个测试）
  - ExecutionCommandServiceTest.java�?1 个测试）
  - LocalExecutionCommandConsumerTest.java（移�?try/catch�?
- 数据结构变化�?ask_timeline 新增 sub_task_execute_result_discarded 事件类型
- 对外行为变化：被补偿任务正确 BLOCKED 的子任务不再�?consumer 的迟到结�?干扰"

#### 4. 遗留

- 本轮为单元测试（Mockito），未覆盖集成测试（需要真�?DB + 并发线程�?
- SubTaskExecutionService.executeOnce() 的编�?执行-回写混合结构未在本轮拆分

---

### 2026-07-13 P2 测试验证—�? �?7 个单元测试全部通过

#### 1. 范围

- �?IDEA 中手动执行本�?P1/P2 涉及的全部单元测试，验证无编译错误、无逻辑缺陷
- 修复构建过程中暴露的 BOM 字符、缺�?import、mock 返回值不完整等问�?
- 为防重拦截路径补充可观测日志

#### 2. 实际落地

- **ExecutionResultHandlerTest�? 个测�?✅）**
  - `shouldHandleSuccess`：IN_PROGRESS 状态正常推�?REVIEW
  - `shouldHandleFailure`：IN_PROGRESS 状态正常推�?BLOCKED
  - `shouldNotReviveSubTaskWhenStatusIsBlocked`（P2-2）：BLOCKED 状态下不调�?submit、不覆写 context、记�?discarded 事件——日志输�?`跳过 handleSuccess：子任务状态已�?IN_PROGRESS`
  - `shouldNotBlockWhenStatusIsNotInProgress`（P2-3）：BLOCKED 状态下不重复调�?block，仍记录失败 timeline

- **ExecutionCommandServiceTest�? 个测�?✅）**
  - `shouldCreateExecutionCommandAndPublishEvent`：正常创建命令并发布事件——日志输�?`执行命令已创建`
  - `shouldRejectWhenPendingOrRunningRecordExists`：已有进行中记录时抛 BizException——日志输�?`跳过创建执行命令：子任务已有进行中的执行记录`
  - `shouldUseBothRowLockAndHasPendingOrRunningForDuplicatePrevention`（P2-1）：验证 getByIdForUpdate（行锁）先于 hasPendingOrRunning（应用层检查）调用

- **LocalExecutionCommandConsumerTest�? 个测�?✅）**
  - `shouldConsumeWhenCommandCreatedEventArrives`：正常消费并 markSuccess
  - `shouldMarkFailedWhenConsumeThrowsException`：异常路�?markFailed（P1 �?consume 不再 rethrow，异常由内部 log.error 记录�?
  - `shouldSkipExecutionWhenMarkRunningReturnsFalse`：markRunning CAS 失败时提�?return——日志输�?`跳过执行(记录已非 PENDING)`

- **ExecutionCompensationTaskTest�? 个测�?✅）**
  - `shouldMarkPendingTimeoutWithoutBlockingWhenSubTaskNotInProgress`：PENDING 超时 + subTask=ASSIGNED �?仅标�?TIMEOUT，不调用 handleFailure
  - `shouldHandleFailureWhenRunningRecordTimesOut`：RUNNING 超时 + subTask=IN_PROGRESS �?markTimeout + handleFailure 推进 BLOCKED
  - `shouldIgnoreWhenNoTimedOutRecords`：无超时记录时不触发任何补偿动作

- **构建问题修复**
  - 4 �?Java 文件�?UTF-8 BOM（`﻿`）：移除�?3 字节
  - `ExecutionResultHandlerTest` 缺失 `import static org.mockito.Mockito.never`：补�?
  - `LocalExecutionCommandConsumerTest` �?`markSuccess`/`markFailed` mock 未设返回值导致输�?被拒�? warn：补�?`thenReturn(true)`
  - `ExecutionCommandService` 防重拦截路径缺日志：新增 `log.warn`

#### 3. 影响

- 代码变化�?
  - `ExecutionCommandService.java`�?1 �?log.warn�?
  - `ExecutionResultHandlerTest.java`�?1 �?import�?
  - `LocalExecutionCommandConsumerTest.java`�?2 �?mock 返回值）
  - 4 个文件去�?BOM（内容无变化�?
- 测试结果�? 类共 13 个单元测试全部通过，exit code 0

#### 4. 遗留

- 未覆盖集成测试（需要真�?DB + 并发线程模拟补偿 vs consumer 真实竞态）
- `ExecutionCompensationTaskTest` 已验证补偿任务的 CAS + 状态守卫逻辑正确，与 P2 `handleSuccess` 守卫形成"补偿先到 BLOCKED / consumer 后到不复�?的双向保护闭�?

#### 5. 可复现验�?

执行以下命令可复现本轮全部测试：

```bash
# helloai-core 模块（P1/P2 涉及�?3 个测试类，共 10 个用例）
mvn test -pl helloai-core -Dtest="ExecutionResultHandlerTest,ExecutionCommandServiceTest,LocalExecutionCommandConsumerTest"

# helloai-job 模块（补偿任务，3 个用例）
mvn test -pl helloai-job -Dtest="ExecutionCompensationTaskTest"
```

或指定完整类名：

| 测试�?| 用例�?| 验证重点 |
|--------|--------|----------|
| `com.helloai.core.service.ExecutionResultHandlerTest` | 4 | handleSuccess 守卫（P2-2）、handleFailure 不重�?block（P2-3�?|
| `com.helloai.core.service.ExecutionCommandServiceTest` | 3 | 命令创建 + 行锁+应用层双重防重（P2-1�?|
| `com.helloai.core.service.LocalExecutionCommandConsumerTest` | 3 | consume 不再 rethrow（P1）、markRunning CAS 跳过 |
| `com.helloai.job.task.ExecutionCompensationTaskTest` | 3 | 补偿 markTimeout CAS + handleFailure 状态守�?|

---

### 2026-07-13 Phase 2A N9 Provider 配置复用

#### 1. 范围

- 收口�?Provider 统一配置入口（`helloai.providers.<name>.*`），解决配置散落、路径杂糅、factory 每次 new ChatModel 三个问题
- 统一 provider/model 解析逻辑�?`AgentProviderResolver`，消�?`ApiKeyAgentExecutor` �?`AgentChatClientService` 中的重复解析

#### 2. 实际落地

- **新增 `AgentProviderProperties`（helloai-common�?*
  - `@ConfigurationProperties(prefix = "helloai.providers")`，统一管理 baseUrl / defaultModel / 超时
  - `getConfig(provider)` 大小写不敏感查找
  - 通过 `@EnableConfigurationProperties` 激活（�?`@Component` 扫描�?

- **新增 `AgentProviderResolver`（helloai-core�?*
  - 静态工具类，从 `Agent.modelType`（格�?`provider:model`）解�?provider �?model
  - `resolveProvider(agent, fallback)` / `resolveModel(agent, fallback)`

- **配置更新（application.yml�?*
  - 新增 `helloai.providers.deepseek.*` 段，替代散落�?`spring.ai.deepseek.*`
  - 支持环境变量 fallback（`DEEPSEEK_BASE_URL` / `DEEPSEEK_CHAT_MODEL` / `DEEPSEEK_CONNECT_TIMEOUT_MS` / `DEEPSEEK_READ_TIMEOUT_MS`�?

- **重构 `DeepSeekProviderChatClientFactory`（helloai-start�?*
  - 移除所�?`@Value` 注解
  - 注入 `AgentProviderProperties`，从统一配置读取参数
  - model 优先级：参数传入 > properties.defaultModel > 常量默认�?

- **重构 `AgentChatClientService.generate()`**
  - factory 分支：通过 `AgentProviderResolver.resolveModel()` 解析 model，�?factory �?�?`createChatClient`
  - 保留 mock 模式�?ChatClient.Builder fallback 路径

- **重构 `ApiKeyAgentExecutor.execute()`**
  - 删除 `resolveProvider()` 本地方法
  - provider 解析统一委托 `AgentProviderResolver.resolveProvider()`

#### 3. 验证

- �?`@Value` 注解全量移除：`grep @Value.*deepseek` 零命�?
- �?`spring.ai.deepseek` 引用全量移除：Java 代码零命�?
- `AgentProviderResolverTest` 12 个用例全部通过（resolveProvider 5 + resolveModel 7），覆盖 null/blank/无冒�?冒号无模型等边界
- `mvn test -pl helloai-core -Dtest="AgentProviderResolverTest"` �?BUILD SUCCESS

#### 4. 影响

- 对外行为变化：无（配置路径从 `spring.ai.deepseek.*` 迁移�?`helloai.providers.deepseek.*`，语义等价）
- 代码变化�?
  - 新增 `AgentProviderProperties.java`（helloai-common�?
  - 新增 `AgentProviderResolver.java`（helloai-core�?
  - 重构 `DeepSeekProviderChatClientFactory.java`（移�?@Value，注�?properties�?
  - 重构 `AgentChatClientService.java`（factory 分支使用 resolver�?
  - 重构 `ApiKeyAgentExecutor.java`（删�?resolveProvider�?
  - 修改 `HelloAIApplication.java`�?@EnableConfigurationProperties�?
  - 修改 `application.yml`�?helloai.providers 段）
- 新增测试：`AgentProviderResolverTest.java`�?2 用例�?
- 数据结构变化：无

#### 5. 遗留

- N9 标记�?部分落地"——Provider 配置入口已统一，但 ChatModel 缓存优化（避免每�?new）未在本轮实�?
- N10（credential_vault 轮换/迁移/权限颗粒度）仍为独立后续工作
- 后续新增 Provider（如 OpenAI）只需：① YAML 加一段配�?�?新增一�?`ProviderChatClientFactory` 实现

---

### 2026-07-13 Phase 2A N6 executeOnce 削薄

#### 1. 范围

- 按架构设计参�?§5.1「继续削�?`SubTaskExecutionService` 的编排职责」推�?executeOnce 拆解
- 将「状态推�?+ 纯执�?+ 结果回写」三层混合职责拆开，让消费者拿到完整分层调用能�?

#### 2. 实际落地

- **`SubTaskExecutionService.executeOnce(subTask, agent)` 削薄为纯执行**
  - 原职责（混合）：状态守�?+ startIfNeeded 状态推�?+ timeline sub_task_execute_start + 组装 AgentTask + timeline llm_call_start/end + �?platform.executeSync() + handleSuccess/handleFailure 结果回写
  - 新职责（纯执行）：状态守卫（DONE/CANCELLED 拒入�?+ 组装 AgentTask + timeline sub_task_llm_call_start/end + �?platformAgentExecutionService.executeSync() + 返回 AgentResult / 抛异�?
  - 不再�?startIfNeeded、不再做 handleSuccess/Failure
  - private �?public，供分层消费者调�?

- **`SubTaskExecutionService.startIfNeeded(subTaskId, status)` 保持 public**
  - 状态推进前置，让消费者可以在�?executeOnce 之前先确�?subTask 状态正�?

- **`SubTaskExecutionService.executeCommand(command)` 保持完整编排入口**
  - 内部�?startIfNeeded �?executeOnce �?handleSuccess/handleFailure 串成完整�?
  - 向后兼容：现�?executeCommand 调用方（外部 API 层）继续可用

- **`LocalExecutionCommandConsumer.consume(command)` 重写�?6 步分�?*
  - �?加载 subTask + agent + 一致性校�?
  - �?startIfNeeded 推进 subTask �?IN_PROGRESS
  - �?markRunning CAS
  - �?timeline sub_task_execution_command_consume + sub_task_execute_start
  - �?executeOnce 纯执�?
  - �?handleSuccess / handleFailure + markSuccess / markFailed CAS
  - 失败路径：executeOnce 抛异�?�?记录 sub_task_llm_call_failed timeline �?handleFailure �?markFailed

#### 3. 影响

- 对外行为变化：无（消费者外部行为不变；执行链路完全等价�?
- 代码变化�?
  - `SubTaskExecutionService.java`：executeOnce �?private �?public + 削薄；executeCommand 补全完整链；类注释更�?
  - `LocalExecutionCommandConsumer.java`：consume 重写�?6 步分层；新增 AgentService 注入；新�?ExecutionResultHandler 注入
  - `SubTaskExecutionServiceTest.java`：拆�?ExecuteOnce / ExecuteCommand / StartIfNeeded 三个 @Nested，共 11 个测�?
  - `LocalExecutionCommandConsumerTest.java`：拆�?HappyPath / SkipPath 两个 @Nested，共 7 个测�?
- 数据结构变化：无
- �?timeline 事件：`sub_task_execution_command_consume_skipped`（仅�?startIfNeeded 拒绝时记录）

#### 4. 验证

- `mvn -pl helloai-core -Dtest="SubTaskExecutionServiceTest,LocalExecutionCommandConsumerTest,ExecutionResultHandlerTest,ExecutionCommandServiceTest,AgentProviderResolverTest" test` �?37 个测试全部通过
- `mvn -pl helloai-job -Dtest="ExecutionCompensationTaskTest" test` �?3 个测试全部通过
- `mvn -DskipTests clean install` �?6 个模块全�?BUILD SUCCESS

---

### 2026-07-13 Phase 2A N6 DB Poller 落地 �?§5.1 主链已跑通，E2E 已验证，当前处于可靠性收尾窗�?

#### 1. 范围

- 按架构设计参�?§5.1「将本地 Spring 事件消费者继续收口到独立 MQ / DB poller 消费模型」落�?DB Poller 独立消费载体
- 关闭实现差距�?N6 「消费者仍为本�?Spring 事件」遗留点
- 补齐 agent_execution_record 兑底扫描所需的存储字�?+ 扫描索引

#### 2. 实际落地

- **Flyway V16：`V16__agent_execution_record_poller_fields.sql`**
  - 扩展 `agent_execution_record` 表：新增 `trigger` / `agent_id` / `access_type` / `last_attempt_at` 4 个字�?
  - `agent_id` / `access_type` 为兑底扫描时的「命令恢复」元数据
  - `last_attempt_at` �?DB Poller 兑底扫描的状态机字段（NULL 表示尚未�?Poller 触及过）
  - 新增部分索引 `idx_exec_record_pending_attempt ON agent_execution_record(last_attempt_at, create_time) WHERE status='PENDING'`
  - 启动日志输出 `[V16] agent_execution_record poller 字段补全完成，已存在相关列数 = N`

- **`AgentExecutionRecord` 实体扩展**：补�?4 个字�?+ Javadoc 说明冗余存储语义

- **`AgentExecutionRecordService` 签名变更 + 新增**
  - `createPending(eventId, subTaskId, agentId, accessType, trigger)`：冗余存�?trigger / agentId / accessType
  - `listOrphanPending(thresholdSeconds, limit)`：扫�?`status='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at < now - threshold)` 行，�?`create_time` 升序返回 `LIMIT`
  - `markPolled(id)`：记�?Poller 触及痕迹，下个周期不会重复扫�?
  - 为空阈�?/ limit 增加防御性短路返�?`List.of()`

- **`ExecutionCommandService.createAssignedCommand`**：调用新签名�?createPending，写入完整字�?

- **`ExecutionCommandPoller`（新建）**
  - `@ConditionalOnProperty(name = "helloai.execution.poller-enabled", ...)` 开启可�?
  - `@Scheduled(fixedDelayString = "${helloai.execution.poller-interval-ms:30000}")` 周期扫描
  - poll() 入口先看 `executionProperties.isPollerEnabled()`（运行时动态开关），false 直接 return
  - 对每条孤儿记录依次：markPolled �?完整性校验（�?subTaskId/agentId/accessType 跳过�?�?记录 timeline `sub_task_execution_command_poll_recovery` �?构�?`ExecutionCommand`（trigger 前缀 `poll-recovery:`）→ 调用 `localExecutionCommandConsumer.consume()`
  - 单条异常不影响整批扫描，listOrphanPending 异常向上抛出让调度框架处�?

- **`AgentExecutionProperties`（helloai-common）补�?4 �?poller 字段**：`pollerEnabled` / `pollerIntervalMs` / `pollerOrphanThresholdSeconds` / `pollerBatchSize`，默认值与架构参考对�?

- **`application.yml`**：`helloai.execution.poller-*` 四项配置带上注释

#### 3. 双路径主�?

- **实时路径**：`SubTaskAutoExecutionDispatcher �?ExecutionCommandService �?publishEvent(ExecutionCommandCreatedEvent) �?@Async @TransactionalEventListener �?LocalExecutionCommandConsumer.consume()`（保留，实时性优先）
- **兑底路径**：`ExecutionCommandPoller.@Scheduled �?agentExecutionRecordService.listOrphanPending() �?重建 ExecutionCommand �?LocalExecutionCommandConsumer.consume()`（新，独立可工作�?
- **幂等保护**：两条路径都会调�?`markRunning` CAS，被另一条路先推进状态后，后到路径被 CAS 拒绝，自然跳�?
- **兑底场景**：应用重�?/ @Async 线程池积�?/ 主路径异常丢失时，Poller 接管，避�?PENDING 长期孤儿�?

#### 4. 影响

- 对外行为变化：无（新增兑底路径不改变主路径语义；事件丢失场景反而能被恢复）
- 代码变化�?
  - 新增 `ExecutionCommandPoller.java`�?56 行）
  - 新增 `ExecutionCommandPollerTest.java`�?1 个测试用例：3 HappyPath + 8 SkipPath�?
  - 变更 `AgentExecutionRecordService.java`：createPending 签名扩展 + 新增 listOrphanPending / markPolled
  - 变更 `AgentExecutionRecord.java`：实体加 4 个字�?
  - 变更 `ExecutionCommandService.java`：调用新签名�?createPending
  - 变更 `ExecutionCommandServiceTest.java`：适配新签名（+import eq�?
  - 变更 `AgentExecutionProperties.java`：加 4 �?poller 字段
- 配置变化：`application.yml` `helloai.execution.poller-*` 4 �?
- 数据结构变化�?
  - `agent_execution_record` 表加 4 �?+ 1 个部分索引（Flyway V16�?
  - `task_timeline` 表新增事件类�?`sub_task_execution_command_poll_recovery`

#### 5. 验证

- `mvn clean install` �?7 个模�?BUILD SUCCESS
- `mvn test -pl helloai-core` �?72 个测试全部通过（包�?ExecutionCommandPollerTest 11 用例�?
- `mvn test -pl helloai-common,helloai-core,helloai-mq,helloai-job,helloai-api,helloai-start` �?全量 BUILD SUCCESS
- `grep createPending` 全仓检�?�?唯一调用�?ExecutionCommandService 已适配

#### 6. 遗留

- §5.1 的执行主链基础能力已落地，但可靠性收尾尚未结束：
  - �?DB Poller 消费载体（本轮）
  - �?SubTaskExecutionService 编排职责削薄（上一轮）
  - �?ExecutionResultHandler 唯一执行结果入口（早前轮�?
  - �?ExecutionCommand 幂等 / 补偿 / 防覆盖（早前轮）
- MQ 主链虽已完成 Phase 2G E2E 冒烟验证，但 Outbox Confirm / Retry、失败可恢复验证�?Poller 兜底职责重定位仍未完成�?
- 后续按依赖顺序推进：Phase 2H ②a Outbox 最小闭�?�?②b Publisher Confirm / Retry �?RabbitMQ 失败可恢�?E2E �?Poller 降级；�?.2 阶段二后置�?
- 当前 Poller 保留为现行消费载体，待可靠投递闭环稳定后再降级为孤儿 / 超时 / 补偿兜底�?

---

### 2026-07-13 §5.2 启动前结构清�?�?ExecutionCommand*Consumer 迁入 agent.mqconsumer

#### 1. 范围

- §5.1 主链基础能力落地后、在 §5.2 阶段二启动前，先�?消费�?代码�?service/ 根目录剥离，对齐 CODE_STYLE §15.1「helloai-core/agent/mqconsumer/」子包规�?
- 纯结构调整：5 个文件物理位置变�?+ import 改写�?*业务逻辑零变�?*
- 用户决策点：先按"修法 1"最小代价路线执行（不迁 ExecutionCommandPoller，也不动 service/ 子域拆分�?

#### 2. 实际落地

- **新建 `core/agent/mqconsumer/` �?`core/test/.../mqconsumer/` 两个目录**
  - 补齐 §15.1 缺失的子包，与现�?`agent/domain`、`agent/executor`、`agent/chat` 平级
- **迁入 3 个文件（main + test�?*
  - `ExecutionCommandConsumer.java`（接口，18 行）�?package �?`core.service` �?`core.agent.mqconsumer`
  - `LocalExecutionCommandConsumer.java`（实现，179 行）�?package 同步迁移，并�?6 �?import 解决跨包调用 6 �?Service（AgentExecutionRecordService / AgentService / ExecutionResultHandler / SubTaskExecutionService / SubTaskService / TaskTimelineService�?
  - `LocalExecutionCommandConsumerTest.java`�?44 行）�?跟随生产同包迁移，并�?6 �?import 解决 @Mock 跨包
- **�?2 �?import（留�?service/ �?Poller + PollerTest�?*
  - `ExecutionCommandPoller.java`：原同包依赖变跨包，�?`import com.helloai.core.agent.mqconsumer.LocalExecutionCommandConsumer;`
  - `ExecutionCommandPollerTest.java`：同�?
- **未迁移的 4 个文件保持原�?*
  - `ExecutionCommandService.java` + Test：发布事件，不直接调用消费�?
  - `ExecutionCommandPoller.java` + Test：兜底调度任务，�?§14 规范属调度域而非消费者域

#### 3. 影响

- 对外行为变化：无（package 路径变更，类�?/ 方法�?/ Spring Bean 名全部不变；@Component 自动扫描仍生效）
- 代码变化�?
  - 新建 2 个目录（main/test�?
  - 迁移 3 个文件位�?
  - 4 个文件加 import（Poller ×1 + PollerTest ×1 + LocalConsumer ×6 + LocalConsumerTest ×6 = �?14 �?import�?
  - 3 个文件改 package 声明
- 数据结构变化：无
- 测试覆盖：本地事件消费者与 Poller �?18 个测试全部保持原位运行不需调整

#### 4. 验证

- `mvn clean install` �?7 个模�?BUILD SUCCESS
- `mvn test -pl helloai-core` �?72 个测试全部通过（含 `LocalExecutionCommandConsumerTest` 7 用例 + `ExecutionCommandPollerTest` 11 用例�?
- `mvn test -pl helloai-job` �?3 个测试全部通过
- `grep "package com.helloai.core.service"` 命中：剩余文件均为真�?Service / Poller / Scheduler，不再包�?ExecutionCommand*Consumer
- `grep "import com.helloai.core.agent.mqconsumer"` 命中 2 处（Poller + PollerTest），证明跨包引用正确

#### 5. 遗留

- N6 状态不变（双路径主链已闭环，本轮仅是代码结构调整，不修改文档失真项 / 差距项状态）
- `ExecutionCommandPoller` 仍在 `core/service/`，未迁出；后续若推进 service/ 子域拆分，可考虑�?Poller 移到 `core/job/`（但独立子模块会因依赖方向产生循环，仅供未来架构设计参考）
- `core/service/` 下仍混有策略类（AgentSelector / ResilientDispatcher / SubTaskAutoExecutionDispatcher）以及评分计算器 ImplicitScoreCalculator；后续可按业务子域重新拆�?
- 下一步目标：架构设计参�?§5.2 阶段二（工作单元显式建模 + 控制命令�?STOP / PAUSE / REPLAN + 用户输入可重入）

---

### 2026-07-13 §5.2 启动前结构清�?�?service/ 根目录杂类分�?

#### 1. 范围

- 承接上一轮消费者迁移，继续清理 `helloai-core/core/service/` 根目录中不属于业�?Service �?Agent 执行链与可观测性组�?
- 按用户确认的 A + B 范围执行：Agent 全家桶与 observability 横切组件；`service/score/ImplicitScoreCalculator` 不在本轮范围�?
- 纯结构重构：只迁移物理位置、修�?package 并补齐跨�?import，业务逻辑、Bean 行为、数据结构与对外接口均不�?

#### 2. 实际落地

- **Agent 执行链组件归�?`core/agent/` 分层**
  - `agent/executor/`：`AgentSelector`
  - `agent/chat/`：`AgentChatClientService`
  - `agent/command/`：`ExecutionCommandService`、`ExecutionResultHandler`
  - `agent/execution/`：`SubTaskExecutionService`、`PlatformAgentExecutionService`
  - `agent/dispatcher/`：`SubTaskAutoExecutionDispatcher`、`ExecutionCommandPoller`、`ResilientDispatcher`
- **横切可观测性组件归�?`core/observability/`**
  - `CircuitBreakerAlertService`
  - `CircuitBreakerEventRecorder`
  - `HeartbeatService`
- **测试与引用同步调�?*
  - 9 个对应测试类跟随生产代码迁入新的 Agent 子包
  - 同步更新迁出类自身、反向引用方及测试类的跨�?import
  - `core/service/` 根目录现仅保�?25 个业�?Service；评分计算器 `ImplicitScoreCalculator` 继续保留�?`service/score/` 子目录（已在下一轮迁出，详见后文�?

#### 3. 影响

- 对外行为变化：无
- 代码变化：迁�?12 个生产文件与 9 个测试文件，新增 `agent/dispatcher`、`agent/command`、`agent/execution`、`core/observability` 等职责明确的目录
- 数据结构变化：无
- 差距项变化：无；N6 仍为“部分落地”，本轮不改变执行命令双路径主链及后�?§5.2 控制命令层目�?

#### 4. 验证

- `mvn clean install` �?7 个模�?BUILD SUCCESS
- `helloai-core` �?72 个测试全部通过
- `helloai-job` �?3 个测试全部通过
- 目录复核�? �?Agent 执行链生产类�?3 �?observability 生产类均位于目标子包，`service/` 根目录不再混放上�?Selector、Dispatcher、Poller、Command、Execution、Chat 与可观测性组�?

#### 5. 遗留

- 评分计算�?`ImplicitScoreCalculator` 下轮单独迁出�?`core/score/`，与 `core/observability/` 对齐形成“顶层领域子包”粒�?
- 下一步仍按架构设计参�?§5.2 推进工作单元显式建模、控制命令层与用户输入可重入

---

### 2026-07-13 §5.2 启动前结构清�?�?ImplicitScoreCalculator 迁入 core/score/

#### 1. 范围

- 承接上一�?`service/ 根目录杂类分层` 的遗留，单独处理评分计算�?
- 不动业务逻辑、不�?Bean 行为、不改对外接口：仅迁移物理位置、修�?package 并补齐跨�?import
- 目标：让 `core/service/` 只剩真业�?Service；评分域做成�?`core/observability/` 平级的顶层领域子�?

#### 2. 实际落地

- **迁移生产文件**：`ImplicitScoreCalculator` �?`core/service/score/` �?`core/score/`，package �?`com.helloai.core.service.score` �?`com.helloai.core.score`
- **删除空目�?*：旧 `core/service/score/` 整个删除
- **反向 import 更新**：`SubTaskService` �?2 �?`com.helloai.core.service.score.*` �?`com.helloai.core.score.*`（含 `ImplicitScoreCalculator` �?`ImplicitScoreCalculator.ScoreResult` 内部类）
- **未带测试文件**：`helloai-core/src/test` 下没�?`ImplicitScoreCalculator` 配套测试，故仅生产代码调�?

#### 3. 影响

- 对外行为变化：无（类名、Bean 名、`@Component` 注解、字段与方法签名全部不变�?
- 代码变化�? 个生产文件位置迁�?+ 1 个反向引�?import 调整 + 1 个空目录删除
- 数据结构变化：无
- 差距项变化：�?

#### 4. 验证

- `mvn clean install` �?7 个模�?BUILD SUCCESS（Total time 21.266s�?
- `grep "com.helloai.core.service.score"` 全仓检�?�?0 命中，旧路径已无任何残留
- `grep "com.helloai.core.score"` 全仓检�?�?命中 3 处（新文件本�?1 �?+ `SubTaskService` import 2 处）
- `core/service/` 根目录现仅保�?25 个业�?Service

---

### 2026-07-14 Phase 2B 外部 Agent 执行闭环补齐 + 调度策略 3（外部优�?空闲优先/LLM 保底�?

#### 1. 范围

- 将“执行结果回写”收口为统一领域入口，供平台内执行链�?MCP 外部 Agent 共用
- 补齐外部 Agent 上报阻塞原因的证据链（timeline/context/inbox/outbox�?
- 推进调度策略 3：同角色候选优先外�?Agent、空闲优先、并提供“纯 LLM 回归”强制开�?
- 扩展为“初始分配也按外部优先选人”（提供自动分配入口与可控开关）

#### 2. 实际落地

- **统一回写入口（结果回写层�?*
  - 新增 `ExecutionResultReport` 标准输入对象
  - `ExecutionResultHandler` 新增 `handleReport(report)` 作为唯一状态推进与审计落痕入口
  - 平台内执行链与外�?MCP 均转换为 `ExecutionResultReport` 后进入该入口

- **外部适配器：MCP `submitResult`**
  - 新增 MCP 工具 `submitResult`：接收外�?Agent 的结�?payload，做鉴权/归属/幂等等校验后进入统一回写入口
  - 目标：让 `CLI_CLIENT`（Qoder/Trae/Codex 等）具备“领取任�?�?执行 �?上交结果 �?状态收敛”的最小闭�?

- **外部阻塞证据链补�?*
  - `reportBlocked` 传入 `reason` 不再丢弃：写�?`sub_task.context` 并记�?timeline 事件 `sub_task_report_blocked`
  - `BLOCKED` 通知摘要优先展示 `blockedReason`，便�?Planner 排障
  - 对应 outbox payload 增补 `blockedReason` 字段，便于后�?MQ/补偿链消�?

- **调度策略 3（可配置�?*
  - 新增 `helloai.dispatch.*` 配置�?
    - `prefer-external`：同角色候选优�?`CLI_CLIENT`（默�?false，不影响�?LLM 回归�?
    - `require-idle`：要求候选当前无 `IN_PROGRESS` 子任务（默认 true�?
    - `force-access-type`：强制仅在指定接入类型内选人（典型：`API_KEY_LLM` 纯保底回归）
    - `auto-assign-on-create`：创建子任务后是否自动进入初始分配（默认 false，保�?PENDING+claim 工作流不变）
  - `AgentSelector` 新增 `pickPreferred(role)`，并在候选过滤中统一应用上述策略

- **初始分配自动选人入口**
  - 新增 `SubTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, role)`：对 PENDING 子任务按策略选首�?Agent，并交给 `ResilientDispatcher.assignNext` 进入 fast-fail + 熔断 + fallback 的最终分配链
  - `SubTaskController.create` �?`auto-assign-on-create=true` 且未指定 `assignedAgent` 时触发自动分�?

#### 3. 影响

- 对外行为变化�?
  - 默认无变化（调度策略默认 `prefer-external=false`、`auto-assign-on-create=false`�?
  - 外部 Agent 现在可通过 MCP `submitResult` 上交结果并驱动子任务状态收�?
  - 外部 Agent `reportBlocked(reason)` 的原因进入证据链，Planner 可见且可追溯
- 配置变化：新�?`helloai.dispatch.*` 段并�?`application.yml` 给出默认�?
- 数据结构变化：无

#### 4. 验证

- `mvn -DskipTests package` �?BUILD SUCCESS

#### 5. 遗留

- 调度策略“外部执行超�?掉线多次后回退 LLM”的阈值计数闭环尚未落地（需要明确计数来源与自动重分配策略）
- 执行命令主链仍未接入 MQ Consumer（仍�?N6 后续推进“MQ 主链�?+ DB 状态中�?+ Poller 兜底恢复”）

---

### 2026-07-14 Phase 2C N11 外部 Agent 阈值回退 LLM 闭环

#### 1. 范围

- 关闭 Phase 2B 遗留“外部执行超�?掉线多次后回退 LLM”的阈值计数闭�?
- �?N11 从「策略配置已收口」升级为「策略配�?+ 自动回退闭环」已交付
- 三处失败来源（handleReport / ExecutionCompensationTask / AgentHealthCheckTask）统一累加计数并触发重新分�?
- 重新分发绕过 `AgentSelector`（避�?`preferExternal=true` 又选回 CLI_CLIENT�?

#### 2. 实际落地

- **Flyway V17：`V17__agent_external_fallback_fields.sql`**
  - `agent` 表新�?`consecutive_failure_count INT NOT NULL DEFAULT 0` / `last_failure_at TIMESTAMPTZ` / `last_fallback_at TIMESTAMPTZ`
  - `sub_task` 表新�?`external_fallback_count INT NOT NULL DEFAULT 0`
  - 部分索引 `idx_agent_external_failure_scan ON agent(consecutive_failure_count, last_fallback_at) WHERE access_type='CLI_CLIENT' AND deleted=0`
  - 启动日志 `[V17] agent / sub_task 阈值回退字段补全完成`

- **`AgentFallbackProperties`（helloai-common�?*
  - `@ConfigurationProperties(prefix = "helloai.dispatch.fallback")`
  - 字段：`enabled`（默�?true�? `failureThreshold`（默�?3�? `cooldownMinutes`（默�?10�? `scanIntervalMs`（默�?60_000L�?

- **实体扩展**
  - `Agent` 新增 3 �?N11 字段：`consecutiveFailureCount` / `lastFailureAt` / `lastFallbackAt`
  - `SubTask` 新增 `externalFallbackCount`

- **Mapper 扩展**
  - `AgentMapper`：incrementConsecutiveFailure / resetConsecutiveFailure / markFallbackTriggered / selectFallbackCandidates
  - `SubTaskMapper`：incrementExternalFallbackCount / selectInFlightByAgent
  - 写入路径�?`REQUIRES_NEW` 事务，rollback 不会丢计�?

- **`ExternalAgentFailureTracker`（helloai-core 新建�?*
  - `recordFailure(agentId)` / `recordSuccess(agentId)` / `markFallbackTriggered(agentId)` 全部 `Propagation.REQUIRES_NEW`
  - `findFallbackCandidates()`：阈�?+ 冷却期过�?+ �?count desc / last_failure_at asc 排序
  - `shouldFallback(agent)` 纯函数：CLI_CLIENT + 阈值达�?+ 冷却期满
  - try/catch 包裹所有写入，避免计数器异常打断主链路

- **`SubTaskDispatchService.redispatchForFallback(subTaskId, failedAgentId, reason)`（新建）**
  - 复用 `subTaskService.resetToPendingForDispatch(...)` �?ASSIGNED/IN_PROGRESS/BLOCKED/REWORK 拉回 PENDING
  - **绕过 `AgentSelector`**：直�?`agentService.listActive().stream().filter(API_KEY_LLM).filter(role).filter(ONLINE/IDLE).max(score)`
  - 记录 timeline `agent_external_fallback_dispatched`，payload �?trigger / preferredAgentId / previousAgentId / reason
  - �?`resilientDispatcher.assignNext(fallbackAgentId, subTaskId)` 进入 fast-fail + 熔断 + fallback �?

- **三处失败来源统一注入**
  - `ExecutionResultHandler.handleReport`：CLI_CLIENT + 失败 �?`recordFailure`；成�?�?`recordSuccess`
  - `ExecutionCompensationTask.compensate`：`markFailed` / `markTimeout` 之后追加 `recordFailure(failedAgentId)`
  - `AgentHealthCheckTask.processStaleAgent`：超时未心跳 + 还在 `IN_PROGRESS` �?`recordFailure`

- **`ExternalAgentFallbackTask`（helloai-job 新建�?*
  - `@Scheduled(fixedDelayString = "${helloai.dispatch.fallback.scan-interval-ms:60000}")` 周期扫描
  - Redis 分布式锁 `scheduler:lock:ExternalAgentFallback`
  - 5 道前置：开�?/ �?/ 候选非�?/ �?Agent 非空 / 记录 timeline `agent_external_fallback_triggered`
  - 对每个候�?Agent 的在飞子任务逐条 `redispatchForFallback`，最�?`markFallbackTriggered` 写回 `last_fallback_at`

#### 3. 影响

- 对外行为变化�?
  - 默认阈�?`failure-threshold=3` + `cooldown-minutes=10`，外�?Agent 连续失败 3 次后下一次定时扫描自动把在飞子任务转交同角色 API_KEY_LLM Agent
  - 阈值与冷却期可调（`helloai.dispatch.fallback.*`�?
  - 外部 Agent 成功上报 �?自动 `recordSuccess` �?计数器清�?
- 配置变化：`application.yml` `helloai.dispatch.fallback.*` 4 项默认�?
- 数据结构变化�?
  - `agent` �?3 �?+ `sub_task` �?1 �?+ 1 个部分索引（Flyway V17�?
  - `task_timeline` 新增事件 `agent_external_fallback_dispatched` / `agent_external_fallback_triggered`
- 差距项变化：N11 从「部分落地」收口为「已交付�?

#### 4. 验证

- `mvn test -pl helloai-core` �?113 个测试全部通过（含 `ExternalAgentFailureTrackerTest` 15 用例 + `SubTaskDispatchServiceTest` 新增 3 用例�?
- `mvn test -pl helloai-job` �?`ExternalAgentFallbackTaskTest` 10 用例全部通过（含 `shouldSkipWhenDisabled` / `shouldSkipWhenLockNotAcquired` / 候选扫�?/ �?Agent 处理 / 时序�?
- 全量 `mvn -DskipTests package` �?6 模块 BUILD SUCCESS
- `grep "agent_external_fallback"` 验证 timeline 事件名拼写一致：2 处生产代码命�?+ 2 处测试命�?

#### 5. 遗留

- N11 阈值回退闭环已落地，本轮�?Phase 2B 遗留项的最终关�?
- 执行命令 MQ Consumer 主链路仍未接入（仍属 N6 范围，下一�?P2.3 推进“共�?`ExecutionCommandConsumer` 接口 + 新增 MQ Consumer”骨架）
- 冷却期与阈值当前是全局配置，暂未支�?per-Agent 覆写（按需后续�?`agent.fallback_threshold_override` 列）

---

### 2026-07-14 Phase 2D N6 MQ ExecutionCommand Consumer 骨架（默�?CONDITIONAL 关闭�?

#### 1. 范围

- 关闭 Phase 2B/2C 遗留“执行命�?MQ Consumer 主链路未接入”项
- 遵循 `doc/design/HelloAI_调度解耦重构分�?md` 的“调度只发命令、执行独立消费、结果异步回写”哲学，新建 `MqExecutionCommandConsumer` 骨架
- `MqExecutionCommandConsumer` �?`LocalExecutionCommandConsumer` **共用 `ExecutionCommandConsumer` 接口**，最终执行链都收敛在同一�?6 步流程上
- **默认 CONDITIONAL 关闭**（`helloai.mq.execution-command.enabled=false`），不影响现�?POLLER / EVENT 主链路；生产/具备 RabbitMQ 的回归环境可手动开�?

#### 2. 实际落地

- **`helloai-core/pom.xml`**
  - 新增 `com.helloai:helloai-mq` 依赖（`P2.3a`）——`MqExecutionCommandConsumer` 需�?`AbstractIdempotentConsumer` / `MessageDeduplicationService` / `RabbitMQConfig` / `@RabbitListener` �?MQ 组件

- **`MqExecutionCommandProperties`（helloai-common 新建�?*
  - `@ConfigurationProperties(prefix = "helloai.mq.execution-command")`
  - 字段：`enabled`（默�?`false`�? `exchange` / `queue` / `routingKey`

- **`ExecutionCommandMqMessage`（helloai-core/agent/mqconsumer 新建 DTO�?*
  - 由于 `ExecutionCommand` 使用 Lombok `@Value`、缺少无参构造与 setter，与 Jackson 反序列化不兼�?
  - 单独提供 `@Data @Builder` �?MQ 载体，字段：`recordId / eventId / subTaskId / agentId / trigger / accessType`
  - 枚举 `AgentAccessType` �?*字符�?*形式落地，避免枚举顺序漂移导致反序列化失�?
  - `from(ExecutionCommand)` / `toDomain()` 两端转换，未知枚举值按 `null` 处理（保留向后兼容）

- **`RabbitMQConfig`（helloai-mq 扩展�?*
  - 新增常量 `EXECUTION_COMMAND_QUEUE` / `EXECUTION_COMMAND_EXCHANGE`
  - 新增 3 �?Bean：`executionCommandExchange`（TopicExchange�? `executionCommandQueue`（durable，绑 `x-dead-letter-exchange = DLX_EXCHANGE` �?`x-dead-letter-routing-key = DLX_QUEUE`�? `executionCommandBinding`（`execution.command.*`�?
  - 复用 `helloai-mq` 既有 `DLX_EXCHANGE` / `DLX_QUEUE`，不新增 DLX 拓扑

- **`MqExecutionCommandConsumer`（helloai-core/agent/mqconsumer 新建�?*
  - `implements ExecutionCommandConsumer` + `extends AbstractIdempotentConsumer`（遵�?`CODE_STYLE §10.3`�?
  - `consume(ExecutionCommand)` 直接委托�?`LocalExecutionCommandConsumer.consume(command)`�?*不重复实�?6 步执行链**
  - `@RabbitListener(queues = EXECUTION_COMMAND_QUEUE, ackMode = "MANUAL")`：MANUAL ACK 语义
  - 消息体反序列化失�?/ `eventId` 缺失/空白 �?`basicAck`（不阻塞队列�?
  - `tryConsume(eventId, CONSUMER_NAME, () -> consume(command))` �?Redis + DB 双层幂等
  - 消费成功 �?`basicAck`；消费失�?�?`basicNack(requeue=false)` �?DLX
  - `@ConditionalOnProperty(name = "helloai.mq.execution-command.enabled", havingValue = "true")` 默认不注�?Bean

- **`application.yml`（helloai-start�?*
  - 新增 `helloai.mq.execution-command.*` 4 项默认配置：`enabled=false` / `exchange=helloai.execution-command.exchange` / `queue=helloai.execution-command.queue` / `routing-key=execution.command.created`
  - 附注释说明：默认 CONDITIONAL 关闭，生产或具备 RabbitMQ 的回归环境可打开

- **`MqExecutionCommandConsumerTest`（helloai-core 测试 新建�?*
  - Mockito 为主 + 真实 `ObjectMapper` 序列化，4 �?`@Nested`：`HappyPath` / `EdgeCases` / `Deduplication` / `ChannelIo`
  - 覆盖 6 类行为：正常消息委托+ACK / �?JSON ACK / �?eventId ACK / 空白 eventId ACK / 委托异常 NACK→DLX / 幂等命中不重复调 consume / `consume(command)` 显式委托 `LocalExecutionCommandConsumer` / `channel.basicAck` �?`IOException` 透传

#### 3. 影响

- 对外行为变化�?
  - 默认无变化（`enabled=false`，Bean 不注册，MQ 主路径不启用�?
  - `enabled=true` 开启后：调度端�?`helloai.execution-command.exchange`（`execution.command.*` 路由）发消息 �?`MqExecutionCommandConsumer.onMessage` 消费 �?委托 `LocalExecutionCommandConsumer` 执行 6 步链 �?ACK / NACK
- 配置变化：`application.yml` 新增 `helloai.mq.execution-command.*` 4 �?
- 数据结构变化：无（不涉及 schema / Flyway�?
- 差距项变化：N6 从“实现路径待定”进展为“骨架已交付（CONDITIONAL 关闭）�?

#### 4. 验证

- `mvn -pl helloai-core,helloai-mq -am test -Dtest=MqExecutionCommandConsumerTest -Dsurefire.failIfNoSpecifiedTests=false` �?8 个用例全部通过（`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`），`BUILD SUCCESS`
- `git status` 脏文件清单与本轮改动一致：`helloai-core/pom.xml` / `RabbitMQConfig.java` / `application.yml` 3 处修�?+ `MqExecutionCommandProperties.java` / `ExecutionCommandMqMessage.java` / `MqExecutionCommandConsumer.java` / `MqExecutionCommandConsumerTest.java` 4 处新�?

#### 5. 遗留

- MQ Consumer 默认关闭，需要在具备 RabbitMQ 的环境打开 `helloai.mq.execution-command.enabled=true` �?E2E 验证
- 生产端（`ExecutionCommandService`）仍只发本地事件 + DB Poller，暂未同时发 MQ 消息（本轮只�?Consumer 骨架�?
- `MqExecutionCommandConsumer` 未注�?`MqExecutionCommandProperties`（仅占位 `describeProperties()`），后续接入配置可读与启动期日志

> ⚠️ **Phase 2E / 2F 更新说明（上方旧描述仅作历史快照，以下方标注为准）：**
>
> - `helloai.mq.execution-command.enabled` **已于 Phase 2E 拆分废弃**，当前配置项�?`helloai.mq.execution-command.producer-enabled` �?`helloai.mq.execution-command.consumer-enabled`，默认均 `false`
> - 上方遗留 ②（生产端未�?MQ�?*已于 Phase 2E 关闭**：新�?`ExecutionCommandMqPublisher`，由 `AgentExecutionProperties.dispatch-mode` 控制是否�?MQ
> - 上方遗留 ③（Consumer 未注�?Properties�?*已于 Phase 2E 关闭**
> - ②另外存在两个阻断性问题：事务时机与消息编码，**已于 Phase 2F 修复**（Publisher 接入 `TransactionSynchronization.afterCommit()` + 显式 JSON 序列化）
> - E2E 验证的开关也相应从单 `enabled=true` 变为 `dispatch-mode=BOTH` + `producer-enabled=true` + `consumer-enabled=true`

---

### Phase 2E：N6 生产端接�?MQ + 派发模式对称�?

#### 1. 范围

- 关闭 Phase 2D 遗留 ②「生产端 `ExecutionCommandService` 未发 MQ」与 ③「Consumer 未注�?Properties�?
- 遵循 `doc/design/HelloAI_调度解耦重构分�?md` "调度只发命令、执行独立消�?目标态：为生产端 / 调度侧引入与 `consumer-mode` **语义对称**�?`dispatch-mode`（`NONE / EVENT / MQ / BOTH`），把生产端行为从消费侧配置上摧开
- MQ 生产 / 消费开�?*独立灰度**：`producer-enabled` �?`consumer-enabled` 拆开
- **默认零行为变�?*：`dispatch-mode` 默认 `NONE`，命令只落库交给 DB Poller 兜底，与当前 `consumer-mode=POLLER` 事实配套
- **fail-fast 而非隐式回退**：`dispatch-mode �?{MQ, BOTH}` �?producer 开关未开 / Publisher Bean 不可�?�?启动 & 运行期均�?`IllegalStateException`
- 本轮不做 E2E（RabbitMQ 环境 ready 后再跑），也不切 Poller 兜底

#### 2. 实际落地

- **`AgentExecutionProperties`（helloai-common�?*
  - 新增枚举 `DispatchMode { NONE, EVENT, MQ, BOTH }`
  - 新增字段 `private DispatchMode dispatchMode = DispatchMode.NONE`
  - 新增辅助方法 `isDispatchEvent()` / `isDispatchMq()`，与既有 `isEventMode()` / `isPollerMain()` 语义对称

- **`MqExecutionCommandProperties`（helloai-common�?*
  - `enabled` 拆成 `producerEnabled`（默�?`false`�? `consumerEnabled`（默�?`false`�?
  - `exchange` / `queue` / `routingKey` JavaDoc 明确"仅作为启动日志与调试参考，topology �?`RabbitMQConfig` 常量声明"

- **`ExecutionCommandMqPublisher`（helloai-core/agent/command 新建�?*
  - `@ConditionalOnProperty(name = "helloai.mq.execution-command.producer-enabled", havingValue = "true")` 默认不注�?
  - 依赖 `RabbitTemplate` + `MqExecutionCommandProperties`
  - `publish(ExecutionCommand)`：`ExecutionCommandMqMessage.from(cmd)` �?`convertAndSend(EXCHANGE, routingKey, msg, mpp)`
  - 消息后处理：`messageId = correlationId = eventId`（去重键在消息头显式携带），`deliveryMode = PERSISTENT`
  - 结构化日志：`mq.execution-command.publish eventId=... subTaskId=... agentId=... routingKey=...`

- **`ExecutionCommandService`（helloai-core�?*
  - 生产端读 `dispatch-mode` 分发（与 `consumer-mode` 完全解耦）；Publisher 通过 `ObjectProvider<ExecutionCommandMqPublisher>` 注入，避�?producer 关闭时启动失�?
  - `NONE`：只落库 + DEBUG 日志
  - `EVENT`：`applicationEventPublisher.publishEvent(ExecutionCommandCreatedEvent)`
  - `MQ`：`mqPublisher.publish(command)`；`getIfAvailable() == null` �?�?`IllegalStateException`
  - `BOTH`：先发本地事件，再发 MQ（Publisher 缺失同样 fail-fast�?
  - 汇总日志加 `dispatch-mode` �?`consumer-mode` 双字�?
  - 移除 `@RequiredArgsConstructor`，改为显式构造函数（为兼�?`ObjectProvider` 参数�?

- **`ExecutionDispatchValidator`（helloai-core/agent/command 新建�?*
  - `@PostConstruct` 一次性把 `dispatch-mode` / `consumer-mode` / `producer-enabled` / `consumer-enabled` / `exchange` / `queue` / `routing-key` 打印到启动日�?
  - `dispatch-mode �?{MQ, BOTH}` �?`producer-enabled=false` �?Publisher Bean 不可�?�?�?`IllegalStateException` 阻断上下文启�?
  - `dispatch-mode �?{MQ, BOTH}` �?`consumer-enabled=false` �?�?WARN 不阻断（允许 shadow / 跨实例消费场景）

- **`MqExecutionCommandConsumer`（helloai-core�?*
  - `@ConditionalOnProperty` �?`enabled` �?`consumer-enabled`
  - 构造函数注�?`MqExecutionCommandProperties`，`describeProperties()` 从返�?`null` 改为返回真实 properties

- **`application.yml`（helloai-start�?*
  - 修复历史缩进 bug：Phase 2D 追加�?`exchange` / `queue` 顶格错乱（运行时�?`MqExecutionCommandProperties` 默认值兜住），本轮正为规范缩�?
  - `helloai.execution.dispatch-mode: NONE`（显式默认）
  - `helloai.mq.execution-command.enabled` �?拆成 `producer-enabled: false` + `consumer-enabled: false`
  - 附注释说�?4 挡语义与"支持先开生产�?shadow 观察队列堆积、再开消费�?的灰度节�?

- **测试**
  - `MqExecutionCommandConsumerTest`：构造函数从 4 参改�?5 参（+ `MqExecutionCommandProperties`），既有 8 用例继续�?
  - `ExecutionCommandServiceDispatchTest`（新建，6 用例）：`DispatchByMode` 覆盖 NONE / EVENT / MQ / BOTH 各分支的事件�?MQ 调用次数；`FailFast` 覆盖 `MQ` / `BOTH` �?Publisher 时的 `IllegalStateException` + 异常消息包含 `dispatch-mode=`

#### 3. 影响

- 对外行为变化�?
  - **默认零变�?*：`dispatch-mode=NONE`，命令只落库交给 Poller 兜底，与 Phase 2D 之前完全一�?
  - `dispatch-mode=MQ` + `producer-enabled=true` + `consumer-enabled=true` 开启后：`ExecutionCommandService` �?`ExecutionCommandMqPublisher.publish` �?RabbitMQ (`execution.command.created`) �?`MqExecutionCommandConsumer.onMessage` �?委托 `LocalExecutionCommandConsumer` 执行 6 步链
  - `dispatch-mode` �?`producer-enabled` 配置组合矛盾时启�?fail-fast
- 配置变化：`helloai.execution.dispatch-mode` 新增；`helloai.mq.execution-command.enabled` 拆成 `producer-enabled` + `consumer-enabled`
- 数据结构变化：无
- 差距项变化：N6 �?骨架已交付（CONDITIONAL 关闭�? �?"主链路已连通（producer/consumer 独立开关，E2E 待验证）"

#### 4. 验证

- `mvn "-pl=helloai-core" "-am" test "-Dtest=MqExecutionCommandConsumerTest,ExecutionCommandServiceDispatchTest" "-Dsurefire.failIfNoSpecifiedTests=false"` �?`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
- 手工核对：`ExecutionCommandServiceDispatchTest` 日志显示 4 �?`dispatch-mode` 均按预期打印分发路径；`fail-fast` 用例异常消息包含 `dispatch-mode=MQ`

#### 5. 遗留

- E2E 冒烟仍未跑（需 RabbitMQ 环境）：至少覆盖 `dispatch-mode=BOTH + producer-enabled=true + consumer-enabled=true`，观�?Redis + DB 幂等确实抵消双消�?
- Poller 兜底切除 / 主路径切换未做，本轮明确保留 Poller 作为兜底路径
- 未做消费侧回写链路（`AsyncExecutionResultConsumer`）改造，`ExecutionResultHandler.handleReport` 现有主路径不�?

---

### Phase 2F：N6 两个阻断性问题修复（事务时机 + 消息编码�?

#### 1. 范围

- 关闭 Phase 2E 遗留的两个阻断性问题（均影�?MQ 主链路能否真正跑通）
- 保持方向不变（方�?B：dispatch-mode + 双开关），仅修正实现与本地事件路径语义不对齐的两处细�?
- 修完�?N6 才真正能描述为“MQ 主链路已连通（E2E 待验证）”；未修之前属于“骨架已搭好但链路断开�?

#### 2. 实际落地

- **`ExecutionCommandMqPublisher.publish()` 事务时机对齐 AFTER_COMMIT**
  - 原实现：`ExecutionCommandService.createAssignedCommand` �?`@Transactional` 方法体里直接 `mqPublisher.publish(command)`，DB 事务未提交时消息已发；本地事件路径用的是 `@TransactionalEventListener(AFTER_COMMIT)`，两路径语义不对�?
  - 两类事故风险：（a）事务回滚后消息已发出；（b）消费端读“还未提交”的 `subTask` / `agent` / `record` 而走 ACK 丢弃分支（`MqExecutionCommandConsumer` 现有做法就是将“读不到实体”当尚未就绪情况 ACK�?
  - 修复：`publish()` 里先�?`TransactionSynchronizationManager.isSynchronizationActive()`，有事务上下文时�?`registerSynchronization` 一�?`afterCommit()` 回调，无事务上下文（脚本 / 单测）退化为立即发送；`Service` 层零修改，语义完全内嵌到 Publisher
- **`ExecutionCommandMqPublisher.publish()` 改为显式 JSON 序列�?*
  - 原实现：`rabbitTemplate.convertAndSend(exchange, routingKey, POJO)` 依赖默认 `SimpleMessageConverter`，�?`ExecutionCommandMqMessage` 既非 `Serializable` 也无对应 converter，直接抛 `MessageConversionException` �?“链路根本发不出去”；而消费端已不对称地按 JSON �?`objectMapper.readValue(byte[])` 解析
  - 修复：改�?`objectMapper.writeValueAsBytes(message)` + `rabbitTemplate.send(exchange, routingKey, new Message(body, props))`，手动设 `contentType=application/json` / `contentEncoding=UTF-8` / `messageId=eventId` / `correlationId=eventId` / `deliveryMode=PERSISTENT`；与消费�?`readValue(byte[])` 完全对称；不依赖默认 converter，不侵入全局 `RabbitTemplate`，避免波�?`DomainEventPublisher` 等其他路�?
- **`ExecutionCommandMqPublisher` 构造函数新�?`ObjectMapper` 参数**（Spring Boot 默认能提供）与新�?`doPublish(command)` 私有方法（封装真正发送）
- **`ExecutionCommandMqPublisherTest` 新增**�? 用例�?
  - `NoTransactionContext`：无事务 �?立即发送，`MessageProperties` 字段全对；body �?JSON，`objectMapper.readValue(byte[])` 可还原全部字�?
  - `ActiveTransactionContext`：有事务 �?仅注�?sync，未 `afterCommit` �?broker 零调用；手动触发 `syncs.get(0).afterCommit()` 后才真发；模拟回滚（`clearSynchronization` 不触�?afterCommit）→ 永不发�?
  - `FailurePaths`：JSON 序列化失�?�?�?`IllegalStateException`（包�?`eventId` �?`JsonProcessingException` cause），broker 零调�?

#### 3. 影响

- 对外行为变化：默�?`dispatch-mode=NONE` + 双开�?`false`，Publisher Bean 不注�?�?本轮对默认行为零影响
- 行为衍生：开�?`dispatch-mode=MQ` �?`BOTH` 后，MQ 消息总于“DB 事务提交之后”才交给 broker，不会出现“先发后提交”或“提交失败但消息已发”；消费端可以直接信�?`subTask / agent / record` 已存�?
- 配置变化：无（开关与消费结构不变�?
- 数据结构变化：无
- 差距项变化：N6 从“骨架已搭好但链路断开�?�?“主链路已连通（producer/consumer 独立开关，E2E 待验证）”的描述真正成立（Phase 2E 描述超前，本轮补统）

#### 4. 验证

- `mvn -pl helloai-core -am test -Dtest=MqExecutionCommandConsumerTest,ExecutionCommandServiceDispatchTest,ExecutionCommandMqPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`
  �?`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
  （具体：MqExecutionCommandConsumerTest 8 + ExecutionCommandServiceDispatchTest 6 + ExecutionCommandMqPublisherTest 5�?
- 新增 5 用例覆盖：无事务直发 / JSON 可还�?/ 有事务延�?/ 回滚不发 / 序列化失�?

#### 5. 遗留（下一轮路线已拍板，三个阶段有严格依赖关系，不并列�?

1. **先跑 E2E 冒烟**（前提：具备 RabbitMQ 环境�?
   - 开 `dispatch-mode=BOTH` + `producer-enabled=true` + `consumer-enabled=true`
   - 重点验证 Redis + DB 双层幂等能否抵消本地事件�?MQ 双消�?
   - 确认 MQ 主链路真实可跑后才进入第二阶�?
2. **再补生产端可靠投�?*（前提：�?已通过�?
   - Publisher 接入 `CorrelationData` / publisher-confirms 回执，现阶段仅靠 `RabbitMQConfig.rabbitTemplate` �?confirm callback 日志可见�?
   - Outbox 可靠投递层与回执失败重发策略一同考虑
3. **最后处�?Poller 与回写链�?*（前提：①② 已稳定）
   - Poller **不切�?*，而是从“主消费载体”降级为孤儿 / 超时 / 补偿兜底（保留作�?MQ 主链异常时的恢复机制�?
   - `AsyncExecutionResultConsumer` 消费侧回写链路改造后置，�?MQ 主链与生产端可靠性稳定后再动

> �?不得跳过上一阶段直接进下一阶段；尤其不得在 E2E 冒烟未跑前就�?Outbox 或在生产端可靠性未就绪前变�?Poller 当前职责�?

### Phase 2G：E2E 冒烟（MQ + Local 双路同时消费，验�?Redis + DB 幂等抵消�?

#### 1. 范围

- �?Phase 2F 遗留的第①阶段：在本�?Docker RabbitMQ + Postgres + Redis 环境，跑 `dispatch-mode=BOTH` + `producer/consumer=true` 的全链路冒烟
- 重点验证�?
  1. Publisher `afterCommit` 之后才真正发送（防止事务回滚后误发）
  2. 本地事件消费�?MQ 消费同时到达时，幂等层能否保�?*只有一�?*实际执行
  3. Redis + DB 双层幂等层都生效（不�?Redus 误以�?DB 已经写入�?的虚假判断）

#### 2. 实际落地

- **Flyway V18**：`helloai-start/src/main/resources/db/migration/V18__event_consumption_log.sql`，创�?`event_consumption_log` �?+ `(message_id, consumer)` 复合唯一索引
  - ⚠️ Phase 2E/2F 引入幂等层时**该表 DDL 漏写**，Spring Boot 启动�?MQ Consumer 任何 `isDuplicate` 调用都会�?`BadSqlGrammarException: relation "event_consumption_log" does not exist`，被 catch 静默吞掉
  - 后果：DB 幂等层实际未生效，只�?Redis 一层兜底。Redis 一�?flush 或过期，双消费就会重�?
  - E2E 启动后第一时间�?`spring-boot-run.log` 看到这个 BadSqlGrammar 才反向定位到 DDL 缺失
- **MessageDeduplicationService 修复 ON CONFLICT**：在 V18 创建的复合唯一索引上，`ON CONFLICT (message_id)` 与索引不匹配，PG �?`there is no unique or exclusion constraint matching the ON CONFLICT specification`，被 catch 静默吞掉
  - 修后�?`ON CONFLICT (message_id, consumer) DO NOTHING`
  - 修后：重�?E2E，`event_consumption_log` 成功写入 1 �?`MqExecutionCommandConsumer / CONSUMED` 记录 �?
- **ExecutionCommandPoller 构造器歧义修复**：Phase 2E 引入 `MqExecutionCommandConsumer` 后，`ExecutionCommandConsumer` 接口出现两个实现 (`localExecutionCommandConsumer` + `mqExecutionCommandConsumer`)，Spring Bean 工厂�?`expected single matching bean but found 2`，应用起不来
  - 修后：Poller 显式构造器参数类型�?`LocalExecutionCommandConsumer`，语义上也是对的（Poller 是兜底路径，必须投递到本地执行链，不能循环�?MQ�?
- **login-raw.ps1 密码�?*：脚本里写的�?`helloai123`，V1 迁移默认 admin 账号密码�?`admin123`，修�?
- **认证 header 修正**：`POST /api/sub-tasks/execute/{id}` 要走 `X-Admin-Token`，不�?`Authorization: Bearer ...`
- **启动脚本中文路径修复**：`start-sb-e2e-mq.ps1` �?`$javaExe = 'C:\Users\史航\.jdks\...\java.exe'` �?Node fallback shell 编码坏，`Start-Process` �?"系统找不到指定的文件"；改成运行时枚举 `C:\Users\*\.jdks\ms-17.0.18\bin\java.exe`，脚本本身不再含中文字节
- **E2E 触发参数**：�?`sub_task(id=9998887771001, status=ASSIGNED, assigned_agent=2074741030123651073)` + `agent(id=2074741030123651073, name=stage4-api-llm-agent-v4, access_type=API_KEY_LLM)`，`POST /api/sub-tasks/execute/9998887771001` 触发，eventId 动态生�?

#### 3. E2E 证据

启动关键日志�?
- `execution-command.mq-publisher.init exchange=helloai.execution-command.exchange routingKey=execution.command.created`
- `execution-dispatch.config dispatch-mode=BOTH consumer-mode=POLLER mq.producer-enabled=true mq.consumer-enabled=true`
- `execution-dispatch.validate dispatch-mode=BOTH producer-enabled=true publisher-bean=ready`
- `Flyway: Successfully applied 1 migration to schema "public", now at version v18`

触发后关键日志序列（eventId=`0d774054e1e14f7fbcd869388cb64805`，recordId=`2077000530561904642`）：
1. `mq.execution-command.publish.register-after-commit eventId=...` （Publisher 只注�?afterCommit，未实际发）
2. `mq.execution-command.publish eventId=... routingKey=execution.command.created bodyBytes=192` （提交后才发�?
3. `[exec-cmd-1]` 本地事件 `consume`：`startIfNeeded` 被另一条路径抢先推进到 IN_PROGRESS �?记录 `sub_task_execution_command_consume_skipped` �?返回
4. `[ntContainer#0-3]` MQ `tryConsume`：Redis miss �?DB miss �?执行 `localDelegate.consume()` �?推进 subTask �?IN_PROGRESS �?走完�?6 步执行链 �?`sub_task_execution_command_consume` + `sub_task_execute_start` + `sub_task_llm_call_start` + `sub_task_llm_call_failed`
5. `MessageDeduplicationService.markConsumed` �?Redis SET + DB INSERT (修复后生�? �?`MqExecutionCommandConsumer` 36ms 完成 �?MQ ACK

DB 验证�?
- `event_consumption_log`: 1 �?`MqExecutionCommandConsumer / CONSUMED / 0d774054e1e14f7fbcd869388cb64805` �?
- `task_timeline`: 8 条事件，**�?1 �?`sub_task_llm_call_start/failed`**，未出现�?LLM 调用 �?
- `agent_execution_record`: 1 �?`status=FAILED`（业务失败：Agent 未配置启用态托管凭�?`provider=deepseek`），未出现双 RUNNING �?
- `sub_task`: status=`BLOCKED`，version 递增正常
- RabbitMQ Management API: `publish=2, ack=1, deliver=2`（同 eventId 投�?2 次但�?ack 1 次，符合预期；最初一次是�?confirm �?publish，ack 是消费者处理完�?

#### 4. 关键结论

- **�?Phase 2F Publisher afterCommit + 显式 JSON 序列化的两个修复点全部生�?*：register-after-commit 日志�?publish 日志有时间顺序，证明发布是在事务提交后才发出的；bodyBytes=192 表明 ObjectMapper 显式序列化成功�?
- **�?双消费幂等抵�?*�?
  - 场景：本地事件与 MQ 几乎同时到达本地 execute �?
  - 谁赢�?*MQ 路径抢先**（RabbitListener 线程 + `localDelegate.consume()`），�?subTask 推到 IN_PROGRESS 并记�?consume timeline；本地事件路径随后进�?`consume(command)`，`startIfNeeded` 拒绝（当前状态已�?IN_PROGRESS），被本�?startIfNeeded 防御�?catch 拦住 �?record `sub_task_execution_command_consume_skipped` �?返回�?
  - 结果：`sub_task_llm_call_start` / `sub_task_llm_call_failed` **只发�?1 �?*，没有出现双 LLM 调用�?
  - 兜底机制分层�?
    1. **DB CAS 层（最稳）**：`agent_execution_record.markRunning(recordId)` PENDING→RUNNING CAS，与 subTask startIfNeeded 协同保证只有一条消费路径真正推进业务�?
    2. **Redis 快路�?*：`mq:dedup:<eventId>` TTL 24h，对同一消息多消费者竞争时直接拦截�?
    3. **DB event_consumption_log 兜底**（Phase 2G 修复后才真正生效）：Redis 失效时通过 `(message_id, consumer)` 唯一索引识别已消费�?
- **⚠️ 顺手抓到�?3 个隐�?bug**（V18 + ON CONFLICT + Poller 双实现歧义）都是 Phase 2E/2F 引入 MQ 主链时埋下的，未�?E2E 完全不会暴露。这反向说明"�?E2E 冒烟再继续推生产端可靠�?这个顺序判断是对的�?

#### 5. 遗留

- �?Publisher Confirm / Outbox 可靠投递（前提：① 已通过�?
- �?Poller 降级为孤�?/ 超时 / 补偿兜底（前提：①② 已稳定）
- 后续可考虑的细化（不在本轮范围）：
  - `MessageDeduplicationService.markConsumed` �?PK �?`System.nanoTime()`，高并发下撞值风险，建议切到 Snowflake ID 生成�?
  - `MqExecutionCommandConsumer.onMessage` �?`tryConsumeEnhanced` 返回 true 时仍�?NACK→DLX；区�?幂等跳过"�?业务失败"，前者应�?ACK 而不�?NACK（否�?DLX 会堆积大�?重复消息"，干扰真实失败信号）
  - `login-raw.ps1` 密码仍写错（`helloai123`），同步�?`admin123`（不在本轮范围，单独立一个文�?/ 脚本维护轮）

---

### 2026-07-15 Phase 2H N1 Outbox 最小闭环（②a�?

#### 1. 范围

- �?N1 与架构设计参�?§5.1 的拆步方案，先落�?`ExecutionCommand -> agent_command_outbox -> OutboxRelayTask -> RabbitMQ` 的最小闭环�?
- `dispatch-mode=MQ/BOTH` 时，执行命令对应�?`agent_execution_record` �?Outbox 行在同一事务内写入；`NONE/EVENT` 路径保持原有语义不变�?
- 明确隔离两类生命周期：`agent_execution_record` 只表示执行生命周期，`agent_command_outbox` 只表�?MQ 投递生命周期；本轮不把投递状态字段塞入执行记录，也不把普通投递重试噪声写�?`task_timeline`�?
- 本轮只完�?②a，不提前实施 ②b Confirm/Retry、T4 失败可恢�?E2E、T5 Poller 降级�?§5.2 阶段二�?

#### 2. 实际落地

- **Flyway V19：`V19__agent_command_outbox.sql`**
  - 新建独立 `agent_command_outbox` 表，与既�?`agent_outbox_event`（SubTask 状态变更事件）分离，避免不�?payload / routing 语义互相扫描�?
  - `aggregate_type` 固定�?`EXECUTION_COMMAND`；payload 使用 JSONB；本轮状态为 `PENDING / SENT / FAILED` 三态�?
  - 保留 `retry_count`、`next_retry_at`、`error_msg`，并补齐 eventId 唯一索引、PENDING 扫描索引与状态审计索引�?

- **Outbox 基础对象（helloai-common / helloai-core�?*
  - 新增 `AgentCommandOutboxStatus`、`OutboxAggregateType` �?`AgentCommandOutboxRelayProperties`�?
  - 新增 `AgentCommandOutboxEvent`、`AgentCommandOutboxEventMapper` �?`AgentCommandOutboxService`�?
  - `AgentCommandOutboxService` 提供 5 个最小方法：`createPending`、`listReadyForRelay`、`markSent`、`markFailed`、`markFinalFailed`�?
  - `createPending` 依赖外层事务；状态更新按 `status=PENDING` 条件保护，失败重试使用应用侧指数退避�?

- **`ExecutionCommandService` 接入 Outbox**
  - `dispatch-mode=MQ/BOTH` 改为在创建执行记录的同一事务内写�?Outbox PENDING 行，不再由业务服务直接调�?Publisher�?
  - `dispatch-mode=NONE/EVENT` 保持原有只落�?/ 发布本地事件语义�?
  - `ExecutionCommandMqPublisher` 本轮仍作�?Relay 使用的底层发送器；Publisher 角色抽象与进一步下沉后移至 ②b（T2.4-Deferred）�?

- **`OutboxRelayTask`（helloai-job�?*
  - 使用 `@Scheduled` 默认�?`1000ms` 扫描，单批默�?`50` 行；通过 Redis `SETNX` 锁（30 �?TTL）保证多实例串行 Relay�?
  - 读取到期 PENDING 行，反序列化 payload 后调�?`ExecutionCommandMqPublisher`；调用未抛异常则标记 `SENT`，失败则回写 `retry_count / next_retry_at / error_msg`，超过阈值标�?`FAILED`�?
  - ②a �?`SENT` 仅表示当前发送调用成功返回，不代�?Broker Confirm；`CONFIRMED` �?Confirm-aware Retry 留到 ②b�?
  - payload 反序列化失败按不可重试的终态错误处理，直接标记 `FAILED`，不污染业务时间线�?

#### 3. 影响

- 数据结构：新�?V19 `agent_command_outbox`；未�?`agent_execution_record` 增加 MQ 投递状态字段�?
- 状态归属：Broker 投递、重试节奏与最终失败只回写 Outbox；超过阈值或最终失败是否补业务�?timeline，留�?②b 的告�?/ 业务事件设计统一处理�?
- 默认行为：默�?`dispatch-mode=NONE` 不受影响；Poller 当前仍保留为现行消费载体，待可靠投递闭环稳定后再调整职责�?

#### 4. 验证

- `ExecutionCommandServiceDispatchTest`�? �?dispatch-mode 用例全部通过，验�?NONE / EVENT / MQ / BOTH 分支行为�?
- `ExecutionCommandServiceTest`�? 个用例全部通过，验�?MQ 路径改为�?Outbox�?
- `OutboxRelayTaskTest`�? 个用例全部通过，覆盖发送成功、可重试失败、终态失败、空批次�?payload 反序列化失败�?
- 本轮�?14 个单元测试通过；RabbitMQ Confirm / 失败可恢�?E2E 不在本轮验证范围，Phase 2G 已完成的主链 E2E 证据保持有效�?

#### 5. 遗留与下一�?

- ②b：补 `CorrelationData`、publisher confirms、`CONFIRMED` 状态与 Confirm-aware Retry，并明确 `FAILED / CONFIRMED / next_retry_at / retry_count` 的状态机边界�?
- T4：在真实 RabbitMQ 环境跑失败可恢复 E2E，验证的不只是“能发”，而是 Broker 异常后可重试、可确认、可终态收敛�?
- T5：可靠投递稳定后，将 Poller 从默认主消费降为孤儿 / 超时 / 补偿兜底，保留作�?MQ 主链异常恢复机制�?
- T6：�?.2 �?WorkUnit、STOP/PAUSE/REPLAN、用户输入可重入继续后置�?

---

### 2026-07-15 Phase 2H N1 Confirm / Retry（②b�?

#### 1. 范围

- �?§5.1 路线拍板�?②b Confirm / Retry"——只做最小闭环，承接 Phase 2H ②a �?`agent_command_outbox` �?`OutboxRelayTask`，把状态机�?`PENDING / SENT / FAILED` 三态扩�?`PENDING / SENT / CONFIRMED / FAILED` 四态，并补�?publisher confirms / `CorrelationData` / Confirm-aware Retry / SENT 超时回退�?
- 明确本轮**�?*做：Poller 降级、`OutboxCompensationTask` 新增调度（沿�?`OutboxRelayTask`）、DLQ、per-event 业务级熔断；T4 E2E 失败可恢复、T5 Poller 降级、T6 §5.2 继续后置�?

#### 2. 实际落地

- **Flyway V20：`V20__agent_command_outbox_confirms.sql`**
  - 通过 `information_schema.columns` 判型，对 `agent_command_outbox.status` �?`VARCHAR �?SMALLINT USING (CASE �?` 兼容迁移，覆�?`PENDING/SENT/FAILED/CONFIRMED` �?`0/1/2/3` 双向兼容；落地后 status 默认�?`0`�?
  - 新增 `last_sent_at` / `confirmed_at` 两列（`TIMESTAMPTZ`），仅由 `OutboxRelayTask` 维护，不与执行生命周期混用�?
  - 重建 PENDING 部分索引（`next_retry_at`，`WHERE status = 0 AND deleted = 0`），并新�?SENT 部分索引 `idx_agent_command_outbox_sent_scan`（`last_sent_at`，`WHERE status = 1 AND deleted = 0`）支�?Confirm 超时回退扫描�?
  - CHECK 约束扩展�?`status IN (0, 1, 2, 3)`，覆盖新增的 `CONFIRMED`�?
  - 不向 `agent_execution_record` 增加任何 MQ 投递状态字段；执行生命周期与投递生命周期继续严格分层�?

- **状态机（`helloai-common/.../AgentCommandOutboxStatus`�?*
  - 新增 `CONFIRMED(3)`，实�?`IEnum<Integer>`，与 `OutboxStatus`（`agent_outbox_event`）继续正交�?
  - 状态迁移表更新为：`PENDING ─[发送调用成功]�?SENT ─[broker ACK 且无 return]�?CONFIRMED`；`SENT ─[NACK / return / 超时 / confirm 回调丢失]�?PENDING（指数退避）` �?`�?FAILED（超阈值）`；`PENDING ─[发送失败重试额度耗尽]�?FAILED`�?

- **实体（`helloai-core/.../AgentCommandOutboxEvent`�?*
  - 补齐 `lastSentAt` / `confirmedAt` 字段；`payload` 仍由 `JacksonTypeHandler` 映射 `jsonb`，字段与 `ExecutionCommandMqMessage` 完全对称�?

- **`AgentCommandOutboxService`（helloai-core�?*
  - 新增 `listExpiredSentForRetry(limit)`：扫�?`status = SENT AND confirmed_at IS NULL AND last_sent_at <= now - confirmTimeout AND retry_count < maxRetry`，按 `last_sent_at` 升序，单批上限由调用方控制�?
  - 收紧 `markSent(id, sentAt)`（二参）并新�?`markConfirmed(id, confirmedAt)`：保�?`WHERE status = PENDING / SENT` 的悲�?CAS 保护，状态不漂移�?
  - 新增 `markFailedFromSent` / `markFinalFailedFromSent`：SENT �?PENDING 回退�?SENT �?FAILED 终态；与既�?`markFailed` / `markFinalFailed` 形成"发送前失败"�?发送后失败"两套对称更新路径�?
  - `error_msg` 仍走 1000 字符截断，避�?broker 异常堆栈撑爆单行�?

- **`ExecutionCommandMqPublisher`（helloai-core，Publisher 角色下沉前过渡）**
  - 新增 `publishWithCorrelation(command, correlationKey)` 返回 `CorrelationData`，底层仍�?`rabbitTemplate.send(exchange, routingKey, message, correlationData)`；`eventId` 仍作�?`MessageProperties.messageId / correlationId` 落到消息头�?
  - 序列化与 `afterCommit` 时机对齐 ②a 的语义；现有 `publish(command)` 路径未删，但 Relay 已切换到 `publishWithCorrelation`�?

- **`OutboxRelayTask`（helloai-job�?*
  - 单条处理链：调用 `publishWithCorrelation(command, outboxId)` �?同步 `markSent(id, now)` �?`attachConfirmCallback(row, correlationData)`�?
  - `handleConfirm` 区分 ACK / NACK / `CorrelationData.getReturned()` / `confirm-timeout`，命�?ACK 且无 return �?`markConfirmed`；其它路径走 `scheduleRetryFromSent`，复用既有指数退避（`baseBackoffSeconds * 2^retryCount`，截断到 2^10 避免溢出）�?
  - 每轮扫描前先�?`revertExpiredSent(batchLimit)`：扫出历�?SENT 超时未确认行（应对重启后 in-flight future 丢失）并走相同回退路径；不�?PENDING 主扫描冲突，分两步执行�?
  - `RelayOutcome` 指标仍为 `SENT / FAILED / FINAL_FAILED / SKIPPED`，本轮不引入 `CONFIRMED` 计数（状态收敛在 confirm 回调，不�?batch 出口）�?

- **RabbitMQ 配置（`application.yml` + `RabbitMQConfig`�?*
  - `spring.rabbitmq.publisher-confirm-type: correlated`、`publisher-returns: true`；`RabbitMQConfig` 在自定义 `RabbitTemplate` 上注�?`ConfirmCallback` / `ReturnsCallback` �?`setMandatory(true)`，确�?correlated confirms �?return 都能触发；当�?outbox 路径使用 `publishWithCorrelation` 拿到 `CorrelationData.getFuture()`，独立消�?confirm；不依赖 template 级回调�?

- **测试**
  - `OutboxRelayTaskTest`：用例从 5 扩到 7，覆�?Publisher 成功（含 `markSent` + `markConfirmed` 顺序）、Publisher 异常�?< maxRetry、Publisher 异常 �?maxRetry、空批次、payload 反序列化失败�?*Confirm NACK �?`markFailedFromSent`**�?*SENT 超时 �?`markFailedFromSent`**；`properties.getConfirmTimeoutSeconds()`、`outboxService.listExpiredSentForRetry(anyInt())` �?`lenient()` 默认 stub，单元层不依赖真�?broker�?
  - `mvn -pl helloai-common,helloai-core,helloai-job -am test` ✅；`mvn -DskipTests clean install` ✅�?

#### 3. 影响

- 数据结构：`agent_command_outbox.status` �?`VARCHAR(32)` 转为 `SMALLINT`（保留旧值的兼容映射），新增 `last_sent_at` / `confirmed_at` 两列；既�?V19 索引正确 drop & recreate；CHECK 约束扩展�?`0/1/2/3`�?
- 状态归属：`CONFIRMED` 仅由 `CorrelationData.getFuture()` 完成时回�?outbox；技术噪声（NACK / return / 超时）只�?outbox 表，不写�?`task_timeline`�?
- 执行侧：`MqExecutionCommandConsumer` �?Outbox 状态机正交，CONFIRMED 只在生产端可见，消费端按既有 MANUAL ACK + 幂等逻辑收敛，不感知 outbox 内部状态�?
- Publisher 角色抽象（`OutboxCommandSender` 接口下沉）继续后置，�?②b 实战稳定后再启动�?

#### 4. 遗留与下一�?

- T4：在真实 RabbitMQ 环境跑失败可恢复 E2E，验�?Broker 异常后可重试、可确认、可终态收�?，覆�?NACK、broker 重启、回调丢失三种场景�?
- T5：可靠投递稳定后�?Poller 从默认主消费降级为孤�?/ 超时 / 补偿兜底，保留作�?MQ 主链异常恢复机制；`AsyncExecutionResultConsumer` 回写链路改造后置�?
- T6：�?.2 WorkUnit / STOP/PAUSE/REPLAN / 用户输入可重入继续后置�?
- R2：`ExecutionCommandMqPublisher.publish()` 旧方法仍存在并被既有单测使用，但内部已不注册 `whenComplete` 监听 confirm future，存�?未来静默丢失"的潜在风险，�?T3 实战稳定后单独清理�?
- R3：V20 不回�?V19 era 的历�?SENT �?`last_sent_at`，导�?`listExpiredSentForRetry` 暂时不会触及这些行；考虑�?Phase 2H 才刚上线�?V19 era �?SENT 行极少，影响面有限�?

---

### T4: Outbox ②b RabbitMQ 故障恢复路径 E2E 验证

#### 1. 范围

承接 Phase 2H ②b "遗留与下一�? �?T4 项的"E2E 验证"，覆�?OutboxRelayTask 在真�?RabbitMQ 故障下的三条恢复路径�?

- **S1 broker NACK**：队列容量耗尽，broker 主动 nack
- **S2 mandatory return**：publish 路由�?binding 命中，`mandatory=true` 触发 ReturnsCallback
- **S3 confirm timeout**：broker 响应丢失 / in-flight future 丢失，由 `revertExpiredSent` 兜底回收
- **S4 control happy path**：对照基线，验证正常 ACK 路径�?`confirmed_at` + `last_sent_at` 同时回写

#### 2. 实际落地

- **交付脚本**：`verify-outbox-relay-confirm-e2e.ps1`（≈ 770 行，PowerShell 5.1 兼容�?
  - 参数：`-SkipPrepare` 复用上一�?sample agent / sub_task；`-Cleanup` 幂等删除�?runTag 产生的所�?outbox �?+ 恢复 broker 配置
  - **pre-flight probe**：插一条临�?PENDING �?+ 8s 等待 status 变化，避�?`dispatch-mode=NONE` / `producer-enabled=false` �?relay 静默 FAIL；本轮实测因 IDEA 启动未切 MQ 一度全场景 FAIL，probe 段介入后准确定位到配置根�?
  - 幂等 runTag：`yyyyMMdd-HHmmss` 后缀，event_id / agentId / taskId / subTaskId / outboxId 均按 runTag 派生，重跑不冲突；outboxId �?`epoch_ms * 1000` 雪花种子 + 单调计数器派�?snowflake-shaped bigint
  - 4 个场景独�?INSERT + 等待循环 + 多字段断言（status / last_sent / confirmed / retry_count / error_msg�?

- **实测结果**（runTag=`20260715-133106`，agentId=`714468167`，subTaskId=`714468187`，`helloai.execution.dispatch-mode=MQ` + `helloai.mq.execution-command.producer-enabled=true` 启用后）

  | 场景 | 故障模拟方式 | 终态（id 状�?重试 last_sent confirmed 错误信息�?| 结果 |
  |---|---|---|---|
  | **S1 broker NACK** | RabbitMQ policy `max-length=1, overflow=reject-publish`，灌 2 �?PENDING | row1 `0 / 1 / 0 / –`；row2 `0 / 1 / 3 / 0 / "confirm-nack: null"` | **FAIL �?NACK 路径触发已证�?*：`error_msg=confirm-nack` + `retry_count=3` �?broker NACK 路径生效的不容辩驳证据；�?`max-length=1` 太严，Spring AMQP publisher confirms 异步时序导致 row1 也被拒，未达�?row1 ACK + row2 NACK"对照语义 |
  | **S2 mandatory return** | DELETE exchange→queue �?*所�?* binding（用 `properties_key` 而非 routing key，避免遗�?`routing_key=null` 兜底 binding），`mandatory=true` | `1784093466325004 / 0 / 1 / 1 / 0 / "returned: NO_ROUTE"` | **PASS**：`status=PENDING` + `last_sent_at` 已写 + `confirmed_at` �?+ `error_msg=NO_ROUTE`，完整验�?`ReturnsCallback` �?`scheduleRetryFromSent("returned: ...")` 路径 |
  | **S3 confirm timeout** | SQL 直接�?`status=1` �?+ `last_sent_at=now-120s`�?.2s 内查�?| `1784093466325005 / 0 / 1 / 1 / 0 / "confirm-timeout: expired-sent"` | **PASS**：`revertExpiredSent` �?SENT 超时行拉�?PENDING + �?`confirm-timeout` 标记，完整验�?broker ack 丢失场景的兜底回�?|
  | **S4 control happy path** | 正常 broker 配置 + �?1 �?PENDING | `1784093466325006 / 3 / 0 / 1 / 1 / ""` | **PASS**：`status=CONFIRMED(3)` + `last_sent_at` �?`confirmed_at` **同时回写**，证�?ACK 且无 return 路径完整闭环 |

- **脚本工程经验沉淀**

  RabbitMQ Management API 三个坑位（直接决定故障模拟能否生效）�?
  1. **PUT queue arguments 不可�?*：`PUT /api/queues/{vhost}/{name}` 修改已存�?queue �?arguments 在本�?broker 版本返回 HTTP 400 `not_json` �?arguments 不更新；改用 `PUT /api/policies/{vhost}/{policy-name}` �?`max-length` / `overflow` �?`apply-to=queues`，policy 热生效、不破坏 queue 自身 DLX 配置
  2. **DELETE binding 必须�?`properties_key`**：URL 段必须用 binding �?`properties_key` 字段（带 properties_hash，可能是字面字符�?`"null"`），不能�?routing key；当 exchange 上存�?`routing_key=null` 兜底 binding 时只�?routing_key 删会遗漏，mandatory return 因此失败
  3. **confirm-timeout 等待窗口 < 1 �?relay 周期**：默�?`helloai.outbox.relay.interval-ms=1000`，S3 等待窗口必须 �?1.2s，否则会被下一�?relay 重新 publish + ACK �?`PENDING(0)` 中间态覆盖成 `CONFIRMED(3)`，断言看到永远是最终态；正确做法是等�?�?1.2s 后查"中间�? + 再等 3s �?最终�?

  PowerShell 5.1�?NET Framework 4.x）三个兼容性问题：
  1. **`[System.Net.Http.HttpClient]` 不存�?*：仅 .NET 5+ 有；改用 PS 5.1 原生 `Invoke-WebRequest -UseBasicParsing -Headers @{Authorization="Basic ..."} -TimeoutSec 3`
  2. **`agent_command_outbox.id` NOT NULL �?default**：MyBatis-Plus `ASSIGN_ID` 雪花�?Java 端写入；直接 SQL INSERT 必须显式指定 id；用 `epoch_ms * 1000` 作为种子 + 单调计数器派�?snowflake-shaped bigint
  3. **单元素数�?unroll**：`$rows[0]` �?PS 5.1 单元素数组场景下被当 Char 集合处理，`.Split('|')` 失败；改�?`[string]($rows | Select-Object -First 1)` 强转字符�?

#### 3. 影响

- 对外行为：无变化（T4 �?E2E 验证脚本，不改业务代码）
- 代码变化：新�?1 �?PS1 脚本 `verify-outbox-relay-confirm-e2e.ps1`
- 数据结构变化：无
- 差距项变化：
  - **N1（Phase 2H ②b Outbox 可靠性）闭环证据完整**：S2/S3/S4 三场景实�?PASS + S1 通过 `error_msg=confirm-nack` + `retry_count=3` 验证 broker NACK 路径触发，差距表 N1 可标"已交�?
  - 新增 R4：T4.1 S1 语义修正方案 A 待落�?

#### 4. 遗留与下一�?

- **T4.1（S1 语义修正�?*：将 S1 调整�?`max-length=2` + 3 �?PENDING，达�?row1 + row2 ACK �?CONFIRMED，row3 NACK �?PENDING + error_msg=confirm-nack"的对照语义；预计 1 轮脚本修�?+ 1 次重跑即可拿到全绿四场景
- **T4.2（建议）**：把"dispatch-mode=MQ + producer-enabled=true"验证场景放进独立�?Spring profile（如 `mq-e2e`），避免每次 E2E 都需手工�?`application.yml` + 重启 IDEA；下个迭代阶段一并推�?
- **T5**：Poller 降级为孤�?/ 超时 / 补偿兜底（按 ②b "遗留与下一�?原计划推进）
- R2 / R3：维�?②b 阶段遗留（Publisher 旧方�?`publish()` 静默丢失 confirm future / V19 era SENT �?`last_sent_at` 未回填），暂不处�?

---

### T5：N6 Poller 主动降级为孤�?/ 超时 / 补偿兜底 + Validator 启动�?fail-fast 闭环

#### 1. 范围

承接 T4 "遗留与下一�? �?T5 项的 "Poller 降级为孤�?/ 超时 / 补偿兜底"，按差距�?N6 处理建议推进。本轮是 **功能定位重塑**（非文档纠错或纯重构），4 个用户拍板决策点�?

- `consumer-enabled=false`：默认主线下直接 fail-fast（不允许 "主消费路径全关但 Poller 仅兜底，PENDING 永远不被消费" 的事故形态）
- `listAllPending`：删�?Poller 对它的调用（统一�?`listOrphanPending`�?
- `ExecutionCompensationTask`：不并入 Poller（保持独�?`Scheduled`�?
- `application.yml` 默认值：切到 MQ 主链 + Poller 兜底（`consumer-enabled=true`�?

不动：`ExecutionCompensationTask` 既有职责、与 MQ 主链路（Phase 2D-2H）的协作模式、�?.2 阶段二（WorkUnit / 控制命令 / 用户输入可重入）�?

#### 2. 实际落地

- **`AgentExecutionProperties.ConsumerMode`（helloai-common）注释重�?*
  - 三种模式都明确为 "Poller 仅作孤儿 / 超时 / 补偿兜底"，区别只�?**主消费路�?* 由谁承担�?
    - `EVENT`：`@TransactionalEventListener(AFTER_COMMIT)` 主消费（本地 Spring 事件�?
    - `POLLER`：本轮已无独�?"DB Poller 主消�?，主消费路径�?`MqExecutionCommandConsumer` 承担（MQ 路径�?
    - `BOTH`：本地事务事�?+ MQ 双主消费，CAS 幂等抵消
  - 辅助方法 `isPollerMain()` / `isEventMode()` 名称保留但语义更新为 "主消费路径能力开�?：`isEventMode()` = 本地事务事件启用；`isPollerMain()` = MQ 主消费路径启用（�?`MqExecutionCommandProperties.consumer-enabled` 配套�?
  - 类注释明确说�?"Poller 在三种模式下都是兜底恢复机制，不再是主消费载�?

- **`ExecutionCommandPoller`（helloai-core/agent/dispatcher）改�?*
  - 删除 `listAllPending` 分支：所�?`consumer-mode` 统一调用 `agentExecutionRecordService.listOrphanPending(threshold, batchSize)`
  - `scanType` 恒为 `listOrphanPending`，不再有 `polled_main` / `poll_main` 双�?
  - `trigger` 前缀恒为 `poll-recovery:`，不再有 `poll-main:` 分支
  - timeline 事件恒为 `sub_task_execution_command_poll_recovery`
  - 类注释更新："T5 �?Poller 不再作为主消费载体，仅作孤儿 / 超时 / 补偿兜底，负�?MQ Consumer 异常 / 应用重启 / 事件丢失场景的恢�?

- **`ExecutionDispatchValidator` 新增 POLLER/BOTH fail-fast（helloai-core/agent/command�?*
  - `consumer-mode �?{POLLER, BOTH}` �?`consumer-enabled=false` �?�?`IllegalStateException`
    - 错误消息包含 `consumer-mode=POLLER` / `consumer-enabled=true` / `POLLER/BOTH 模式下没有主消费路径` / `agent_execution_record PENDING 行将永远不被消费`
    - 阻止 "主消费路径全关但 Poller 仅兜底，PENDING 永远不被消费" 的事故形�?
  - 保留 Phase 2E �?`dispatch-mode �?{MQ, BOTH}` �?`producer-enabled=false` �?Publisher Bean 不可�?�?�?`IllegalStateException`
  - 保留 `dispatch-mode �?{MQ, BOTH}` + `consumer-enabled=false` �?WARN（跨实例消费 / shadow 场景�?
  - 启动�?`@PostConstruct` 一次性打�?4 配置 + 4 Bean 可用�?

- **`application.yml`（helloai-start�?*
  - `helloai.mq.execution-command.consumer-enabled: true`（默认值由 `false` 改为 `true`�?
  - 注释重塑�?"Poller 兜底" 语义：MQ 主链默认开启，Poller 仅作孤儿 / 超时 / 补偿兜底
  - YAML 注释完整写进灰度节奏�?
    1. MQ 环境就绪后先开 `producer-enabled=true` 观察队列堆积
    2. `producer/consumer=true` + `dispatch-mode=BOTH` 进入双消费，CAS 抵消
    3. 主链稳定后可�?`consumer-enabled=false` + `consumer-mode=EVENT`，退回纯本地事件主消�?+ Poller 兜底

- **`AgentExecutionRecordService.listAllPending`（helloai-core）兼容保�?*
  - �?`@Deprecated(forRemoval=false)` 注解
  - Javadoc 更新�?"T5 �?Poller 不再调用本方法，保留仅为兼容历史代码与排查工具；新代码请使用 `listOrphanPending(int, int)` 扫描孤儿 PENDING"
  - 既有调用方（验证脚本 / 排查工具）继续可用，不强制移�?

- **`ExecutionCompensationTask`（helloai-job）保持独�?*
  - 不并�?Poller，职责边界清晰：Poller 只扫 "孤儿 PENDING"（基�?`last_attempt_at`），补偿任务只扫 "PENDING 超时"（基�?`create_time`�? `RUNNING 超时`（基�?`last_attempt_at`�?
  - 合并会导致调度复杂度升高、Poller 设计目标被覆盖；当前实现已通过 `ExecutionCompensationTaskTest` 3 用例验证 CAS + 状态守卫逻辑
  - 即用户拍板决策点之三�?`ExecutionCompensationTask` 不并�?Poller"

- **`ExecutionCommandPollerTest`（helloai-core 测试）改�?*
  - 删除 `PollerMain` 嵌套类（5 �?`listAllPending` 主路径用例，全部基于 "Poller 主消�? 假设�?
  - 新增 `DowngradeConsistency` 嵌套类（5 个用例）�?
    - `EVENT 模式：调 listOrphanPending，永不调 listAllPending`
    - `POLLER 模式：调 listOrphanPending，永不调 listAllPending`
    - `BOTH 模式：调 listOrphanPending，永不调 listAllPending`
    - `三种模式：trigger 前缀恒为 poll-recovery:`
    - `POLLER 模式空批次：不调 listAllPending，直接返回`
  - 类注释更新："T5 �?Poller 不再作为主消费载体，三种 consumer-mode 都仅扫孤�?PENDING"

- **`ExecutionDispatchValidatorTest`（helloai-core 测试 新建�?*
  - 5 �?`@Nested` �?14 用例�?
    - `DispatchModeFailFastOnProducer`�? 用例）：NONE 通过 / MQ �?producer / BOTH �?Publisher Bean
    - `DispatchModeFailFastOnRelay`�? 用例）：NONE 通过 / MQ �?relay / BOTH �?relay
    - `ConsumerModeFailFast`�? 用例）：POLLER �?consumer 抛错 / BOTH �?consumer 抛错 / EVENT �?consumer 通过（允许）/ POLLER 合法通过 / BOTH 合法通过
    - `DispatchWarnOnConsumerDisabled`�? 用例）：MQ + consumer=false WARN / BOTH + consumer=false WARN（不阻断�?
    - `ValidCombinationsAndPriority`�? 用例）：4 类合法组合路�?+ Producer fail-fast 优先级高�?Consumer fail-fast
  - 完整覆盖 ②a / ②b 闭环 + T5 新闭�?+ WARN 不阻�?+ 合法组合与组合优先级

- **`verify-poller-e2e.ps1` 同步更新**
  - 顶部注释 v1 �?v2，明�?T5 后模型：Poller 仅作孤儿 / 超时 / 补偿兜底，不再作主消�?
  - timeline 事件名统一：`sub_task_execution_command_polled_main` �?`sub_task_execution_command_poll_recovery`（与 Poller 重命名后的实际事件名一致）
  - 修复 PS 5.1 `[System.Net.Http.HttpClient]` 兼容�?bug：改�?`Invoke-WebRequest -UseBasicParsing -Headers @{Authorization="Basic ..."} -TimeoutSec 3`
  - 本轮未重跑（脚本本身属于历史 V16 era �?Poller 主消费取证，�?T5 降级后模型不同，验证场景需重新设计 —�?详见 §5 遗留与下一�?S5�?

#### 3. 影响

- 对外行为变化�?
  - **默认反转**：旧默认 `consumer-enabled=false` + Poller 主消费（POLLER 模式）→ 新默�?`consumer-enabled=true` + MQ 主消�?+ Poller 兜底
  - **阻断形�?*：`consumer-mode=POLLER/BOTH` + `consumer-enabled=false` 现在启动期直�?fail-fast，不再允�?"静默退�?
  - **可选形�?*：主链稳定后可切 `consumer-mode=EVENT` + `consumer-enabled=false`，回到纯本地事件主消�?+ Poller 兜底
- 配置变化�?
  - `application.yml` `helloai.mq.execution-command.consumer-enabled` 默认�?`false �?true`
  - `application.yml` `helloai.execution.dispatch-mode` 维持显式 `NONE`（保�?Phase 2E 兼容性）
  - 注释完整重写�?"Poller 兜底" 语义
- 代码变化�?
  - 修改 5 个生产文件（AgentExecutionProperties / ExecutionCommandPoller / ExecutionDispatchValidator / AgentExecutionRecordService / application.yml�?
  - 修改 1 个测试文件（ExecutionCommandPollerTest�? 新建 1 个测试文件（ExecutionDispatchValidatorTest�?
  - 修改 1 个验证脚本（verify-poller-e2e.ps1�?
  - 总计 8 个文�?
- 数据结构变化：无（T5 是定位重塑，不涉�?schema / Flyway�?
- 差距项变化：
  - **N6 完成 "消费者定位重�? 最后一�?*：从 "POLLER 默认 + MQ 可�? �?"MQ 主链（POLLER/BOTH�? Poller 仅兜底（默认全开�?
  - Poller 永久不再作为主消费载体，仅作 MQ Consumer 异常 / 应用重启 / 事件丢失场景的恢复机�?

#### 4. 验证

- `mvn -pl helloai-core test -Dtest="ExecutionCommandPollerTest"` �?DowngradeConsistency 5 用例全过 + 既有 6 用例回归
- `mvn -pl helloai-core test -Dtest="ExecutionDispatchValidatorTest"` �?14 用例全过
- `mvn -pl helloai-common,helloai-core,helloai-mq,helloai-job,helloai-api,helloai-start -DskipTests clean install` �?6 模块 BUILD SUCCESS
- 启动期验证（`SpringBootApplication.run`）：
  - `consumer-mode=POLLER` + `consumer-enabled=false` �?启动期抛 `IllegalStateException`，错误消息包�?`consumer-mode=POLLER` / `consumer-enabled=true` / `POLLER/BOTH 模式下没有主消费路径` / `agent_execution_record PENDING 行将永远不被消费`（由 `ExecutionDispatchValidatorTest.ConsumerModeFailFast.shouldFailFastWhenConsumerPollerButConsumerDisabled` 钉死�?
  - `consumer-mode=POLLER` + `consumer-enabled=true` + `dispatch-mode=BOTH` + `producer-enabled=true` �?正常启动并打�?4 配置 + 4 Bean 可用�?

#### 5. 遗留与下一�?

- **T6**：�?.2 WorkUnit / STOP/PAUSE/REPLAN / 用户输入可重入继续后置（不在本轮范围�?
- **R2**：`ExecutionCommandMqPublisher.publish()` 旧方法静默丢�?confirm future，维持现状待单独立项清理（待 T3 实战稳定后启动）
- **R3**：V20 不回�?V19 era 历史 SENT �?`last_sent_at`，维持现状（Phase 2H 阶段�?SENT 行极少，影响面有限）
- **新增建议�?S5（Poller 兜底场景观测 E2E�?*：本轮关闭了 "Poller 作为主消�? 语义，但兜底扫描是否真的能在 MQ 主链异常时接住孤�?PENDING，仍�?E2E 验证；建议下个迭代阶段单独立项做一�?"故意�?MQ Consumer + 注入一条孤�?PENDING + 观察 Poller 恢复" 的对照实验，并把 `verify-poller-e2e.ps1` 完全重写以匹�?T5 后模型（当前脚本仍带 Poller 主消�?era �?V16 假设，不能直接用于验�?Poller 兜底�?

> ⚠️ T5 后的 "灰度节奏" 建议（不�?N6 处理建议内，仅作运维参考）�?
>
> 1. MQ 环境就绪：`dispatch-mode=BOTH` + `producer/consumer=true`，跑全链路冒烟后保留双开
> 2. 主链稳定：`dispatch-mode=MQ` + `producer/consumer=true`，去�?EVENT 路径噪声
> 3. 退回纯本地事件（可选）：`consumer-mode=EVENT` + `consumer-enabled=false` + `dispatch-mode=NONE`，依赖本地事务事件主消费 + Poller 兜底
>
> 不得在不调整 `consumer-mode` 的情况下单独关闭 `consumer-enabled=false`，本�?fail-fast 已经把这条红线钉死在 `ExecutionDispatchValidator` 里�?

---

### 前端积分流水修复 + Agent ID 选择组件�?

#### 1. 范围

- 修复积分流水页面（RewardList.vue）展示数据为空的问题
- 将散落在多个页面中的"手工输入 Agent ID"统一为下拉选择组件
- 修复认领子任务时 Agent ID 硬编码为 1 �?Bug

#### 2. 实际落地

- **积分流水数据修复**
  - 根因：前�?RewardList.vue 调用 `GET /api/scores/leaderboard`（返�?Agent 积分排行�?`{agentId, agentName, role, totalScore}`），但表格列绑定�?prop �?`reason / delta / balance / createTime`（reward_log 表字段），前后端数据结构不匹配导致全部单元格为空
  - 后端新增 `GET /api/scores/logs?page=&pageSize=` 端点，调�?`RewardService.listAllLogs()` 分页查询 reward_log 表按创建时间倒序返回，字段与前端表格列完全对�?
  - 前端 RewardList.vue 切到新端点，解析 IPage.records，新增分页组�?

- **AgentSelect 组件新建**
  - 新建 `components/AgentSelect.vue`：可复用�?Agent 下拉选择组件，挂载时自动�?`GET /agents` 加载列表，支�?filterable 搜索，选项格式 `名称 (角色)`，支�?v-model 双向绑定

- **RewardList.vue 手动调整积分弹窗**：Agent ID 输入框从 `<el-input>` 替换�?`<AgentSelect>`，不再手工填�?

- **SubTaskList.vue 认领子任�?*
  - �?`ElMessageBox.prompt('输入 Agent ID')` 替换为弹�?+ `<AgentSelect>` 下拉选择
  - **Bug 修复**：原逻辑 `subTaskApi.claim(row.id, 1)` �?agentId 硬编码为 1，无�?prompt 输入什么值都被忽略；修复后改为使用弹窗中选中�?agent ID

- **环境修复**：Shell 默认 JDK 24 与项�?Lombok 不兼容导致编译失败（TypeTag :: UNKNOWN），切回 JDK 17 后正常；`helloai-common` 模块�?mvn install 导致 IDE �?程序�?com.helloai.common.base 不存�?

#### 3. 影响

- 对外行为变化：积分流水页正确展示 reward_log 数据；Agent ID 不再需要手工输入；认领子任务不再硬编码 agentId=1
- 代码变化�?
  - 后端 2 文件：RewardService.java�?listAllLogs）、ScoreController.java�?/logs 端点�?
  - 前端 4 文件：AgentSelect.vue（新建）、reward.ts�?logs API）、RewardList.vue（切端点+分页+AgentSelect）、SubTaskList.vue（弹�?AgentSelect+Bug 修复�?
- 数据结构变化：无
- 差距项变化：无（本轮�?UX 收口�?Bug 修复，不涉及核心差距项）

#### 4. 遗留

- 认领子任务后续应按流程中注册的有效角�?agent 进行筛选，甚至降级�?LLM 模型自创建的 agent（当前仅全量列出所�?Agent�?
- AgentSelect 组件当前使用 `GET /agents`（全量），后续数据量增大时可考虑接入管理端分页接�?`GET /admin/agents`

---

### AgentHub V1 轮后反馈修复——credential_vault / redispatch / duty_lease / Redis �?/ Poller 兜底验证

#### 1. 范围

针对 AgentHub V1 四轮迭代后由用户反馈暴露出来�?5 个隐患进行收口修复：

- credential_vault 轮换被唯一索引直接卡死
- `redispatchAssignedTimeout` 可能把任务重分回�?Agent 造成原地打转
- agent_duty_lease 缺少 DB 层的“同一 Agent 同时只能有一�?ACTIVE lease”约�?
- `AssignedSubTaskTimeoutTask` Redis 锁释放不安全（固�?value + 简�?delete�?
- `verify-poller-e2e.ps1` 未覆盖“主消费路径不可达”的 Poller 兑底验证

#### 2. 实际落地

- **credential_vault 唯一索引**（`V1__init_all.sql`�?
  - 原索�?`uk_credential_vault_owner_provider_type` 不区分状态，`rotateAgentApiKey()` 会在第一次轮换命�?`EXISTING �?EXPIRED + INSERT ACTIVE` 的唯一约束冲突�?
  - 改为部分唯一索引 `uk_credential_vault_owner_provider_type_active`，`WHERE status = 'ACTIVE' AND deleted = 0`，允许同一 (owner_type, owner_id, provider, credential_type) 多条历史状态共存�?

- **`redispatchAssignedTimeout` 排除�?Agent**（`SubTaskDispatchService`�?
  - 原实�?`agentSelector.pickPreferred(role)` 不带排除参数，原 Agent 静默丢弃但仍在线且分数最高时会造成原地打转�?
  - 改为 `agentSelector.pickAlternative(originalAgentId, role)`，与"同角色排除指�?Agent"的选人逻辑复用�?
  - `SubTaskDispatchServiceTest` 新增两个用例：`shouldExcludeOriginalAgentWhenRedispatchingAssignedTimeout`、`shouldNotCallDispatcherWhenNoAlternativeAvailable`�?

- **agent_duty_lease 库级约束**（`V1__init_all.sql`�?
  - service 层“先关旧 lease 再开�?lease”能被并�?`checkIn` 击穿�?
  - 补部分唯一索引 `uk_duty_lease_agent_active` (`agent_id` WHERE `status='ACTIVE' AND deleted=0`)，并�?FK `fk_duty_lease_agent` 引用 `agent(id)`�?

- **Redis 锁安全释�?*（`AssignedSubTaskTimeoutTask`�?
  - 原实现固�?value `"1"` + 简�?`delete`；单轮扫�?>60s 后锁过期会被其他实例重抢，原实例 finally 会误删别人的锁�?
  - `scan()` 生成 UUID 作为 token，`tryLock(token)` 使用 `SET NX EX` �?TTL，`unlock(token)` �?Lua：`if get == ARGV[1] then del`，保证只有自�?token 能解锁�?
  - `AssignedSubTaskTimeoutTaskTest` 新增 `shouldUseLuaUnlockScriptWithMatchingToken`，验�?Lua 脚本作为参数被传入且带正�?token�?

- **Poller 兑底验证**（`verify-poller-e2e.ps1` �?v3.1�?
  - 脚本无法重启 Spring Boot 关闭 MQ/Event 消费者，采用轻量等价：直�?`INSERT INTO agent_execution_record`，绕�?`ExecutionCommandService.publish()`。这种记录不会进�?MQ (`agent_command_outbox` 也不会写)，也不会发布本地 Spring 事件，Poller 是唯一可能处理者�?
  - 新增 S5 场景，四个断言�?
    - (a) `last_attempt_at IS NOT NULL`：`markPolled` 被调用（�?Poller 调用�?
    - (b) timeline �?`sub_task_execution_command_poll_recovery`：仅 Poller �?
    - (c) `sub_task_execution_command_consume` 事件�?`payload.trigger` �?`poll-recovery:` 开头：Poller 会重�?trigger 前缀，主消费者不�?
    - (d) 反证：不存在 `trigger` 不以 `poll-recovery:` 开头的 `consume` 事件（出现即证明主消费者也参与了处理）
  - 额外：S5 入口�?`UPDATE sub_task SET status='PENDING'`，避开 S1/S4 �?sub_task 终态导�?`startIfNeeded` 拒绝、只�?`consume_skipped` 不带 trigger 的假阴性�?
  - S6（手动场景）说明加入头部注释：需重启 Spring Boot 时设 `helloai.mq.execution-command.consumer-enabled=false`、单独跑 S1-S4，不能从脚本中自动运行�?

#### 3. 影响

- 对外行为变化：调�?`rotateAgentApiKey()` 可正常轮换；ASSIGNED 超时回收不再原地打转；并�?`checkIn` 会被 DB 层拒绝重�?ACTIVE；Redis 锁释放不再误伤他人；Poller 兑底验证脚本可以证明主消费路径隔离下 Poller 仍能兑底�?
- 代码变化�?
  - `helloai-start/src/main/resources/db/migration/V1__init_all.sql`�? 个索引重定义 + 1 个索引新�?+ 1 �?FK 约束
  - `helloai-core/src/main/java/com/helloai/core/service/SubTaskDispatchService.java`：`redispatchAssignedTimeout` 改调 `pickAlternative`
  - `helloai-core/src/test/java/com/helloai/core/service/SubTaskDispatchServiceTest.java`�? 个测试新�?
  - `helloai-job/src/main/java/com/helloai/job/task/AssignedSubTaskTimeoutTask.java`：UUID token + Lua 解锁
  - `helloai-job/src/test/java/com/helloai/job/task/AssignedSubTaskTimeoutTaskTest.java`�? 个测试新�?
  - `verify-poller-e2e.ps1`：v3 �?v3.1�? 个场景（S5）新增，头部注释扩充
- 数据结构变化：`credential_vault` �?`agent_duty_lease` 表的索引 / FK 约束变化（需 Flyway 重跑 V1 环境需重置或手动修复索引名）；其他无�?

#### 4. 遗留

- 如果未来需要同一 owner 多条 ACTIVE credential（例如主/备密钥同时生效），当前部分唯一索引会拒绝这种情况，后续要重新调整索引条件�?
- `agent_duty_lease` FK 加上后，`agent` 表中删除 Agent 会联动拦截，未在 `AgentService` 里预检；后续如需支持硬删�?Agent，需先处理其 duty_lease�?
- Poller E2E v3.1 �?S5 依赖 sub_task 被重置为 PENDING 后被重新推进；若后续 `startIfNeeded` 增强、限制某些来源不允重启，本场景需要重写�?
- S6（manual MQ-isolation）未实现为脚本可执行步骤，依赖人工手动重启验证，未保�?CI 路径�?

---

### 2026-07-16 AgentHub V1 P0 真实环境 e2e 落地 + skill 规则 6 同步

#### 1. 范围

- T4.1 调度策略 §4.10 “值班优先�?收口（AgentSelector 增加 `dutyRank` 排序�?
- AgentHub V1 P0 三件：checkIn / checkOut / DutyLeaseExpirationTask 真实环境 E2E
- skill 规则 6 “脚本必须显式声�?UTF-8 编码�?同步�?5 �?SKILL.md + AGENTS.md

#### 2. 实际落地

- **T4.1 §4.10 值班优先收口（方�?A�?*
  - `AgentSelector` 注入 `AgentDutyLeaseService`，在多候�?comparator 排序时调�?`agentDutyLeaseService.isOnDuty(agentId)` 优先选择值班中的 Agent�?
  - 单候选用例（�?`shouldSkipSleeping` / `shouldReturnNullWhenNoCandidates` 等）不走 comparator，`setUp` �?`when(...isOnDuty...).thenReturn(false)` 是防御式默认 stub，但 Mockito STRICT_STUBS 检测不到调用会�?`UnnecessaryStubbing`�?
  - `AgentSelectorTest.setUp` 改为 `lenient().when(...)` 避开误报�? 个测试零无关逻辑变化�?

- **AgentHub V1 P0-A：checkIn / checkOut**
  - `agent_duty_lease` 表（`V1__init_all.sql` �?1508 行随初始化建表，AgentHub V1 T3�?*注：早期本记录误�?Flyway V18，V18 实为 `event_consumption_log`**）：`status �?{ACTIVE / CLOSED / EXPIRED}`，部分唯一索引 `uk_duty_lease_agent_active` (`agent_id` WHERE `status='ACTIVE' AND deleted=0`) 阻止同一 Agent 多条 ACTIVE 行�?
  - `AgentDutyLeaseService.checkIn(agentId, workMode, maxConcurrent, ttlMinutes)`：开�?ACTIVE 租约，`expires_at = now + ttlMinutes`，同时调�?`heartbeatService.seen(agentId)` 联动在线态；`ttlMinutes` �?null �?�? 默认 30�?
  - `AgentDutyLeaseService.closeLease(agentId, closeReason)`：将 ACTIVE 翻为 CLOSED，`closeReason` �?null 时默�?`"manual_close"`�?
  - `McpMcpServer.checkIn` / `checkOut` 两个 `@Tool`：参�?`agentId / workMode / maxConcurrent / ttlMinutes / sessionId / _sessionId`，`requireAuthId(sessionId, _sessionId)` 鉴权后覆盖客户端传的 agentId�?
  - `checkOut` 参数名修复：服务�?`@ToolParam reason` 改为 `closeReason`（主字段名），保�?`reason` 作为 alias（兼容旧客户端）�?

- **AgentHub V1 P0-C：DutyLeaseExpirationTask**
  - `helloai-job` 新增 `@Scheduled fixedRate=30_000` + Redis Lua 锁�?
  - 扫描 `agent_duty_lease` �?`status='ACTIVE' AND expires_at < now()` 的行，翻�?`status='EXPIRED'`, `close_reason='lease_expired'`�?

- **新增 NOT NULL 字段填写修复（N11 遗留�?*
  - `Agent.consecutiveFailureCount` 字段�?entity 里有，但 `MyBatisPlusMetaObjectHandler.insertFill` 没填默认值（业务逻辑不填 �?`AgentService.register()` INSERT �?NOT NULL 约束 �?500 `DataIntegrityViolationException`）�?
  - `MyBatisPlusMetaObjectHandler.insertFill` �?`setFieldValByName("consecutiveFailureCount", 0, metaObject)`，覆盖所�?INSERT Agent 路径�?

- **E2E 脚本：`verify-agenthub-duty-e2e.ps1`（新增）**
  - S1：MCP-over-SSE `tools/call checkIn` (workMode=NORMAL, maxConcurrent=3, ttlMinutes=5) �?docker exec psql 断言 `status='ACTIVE' / work_mode='NORMAL' / max_concurrent='3' / expires_at > now()`�?
  - S2：MCP-over-SSE `tools/call checkOut` (closeReason='e2e_test_close') �?docker exec psql 断言 `status='CLOSED' / close_reason='e2e_test_close'`�?
  - S3：手�?INSERT 一�?`expires_at=now-1min` �?ACTIVE 租约，等 35s，DutyLeaseExpirationTask 巡检翻为 `status='EXPIRED' / close_reason='lease_expired'`�?
  - `-Cleanup` 开关删 lease/inbox，幂等回归�?
  - 复用 `verify-mcp-e2e.ps1` �?MCP SSE 长连接样�?+ `verify-outbox-relay-confirm-e2e.ps1` �?`Run-Psql / Get-PsqlFields` 样板�?
  - 最�?ALL PASSED 顺序�?*S1 OK �?S2 OK �?S3 OK �?ALL PASSED**（实�?2026-07-16 11:34 通过）�?

- **skill 规则 6 “脚本必须显式声�?UTF-8 编码�?同步**
  - 5 �?`helloai-preflight/SKILL.md`（`.agents` 母版 + `.qoder/.trae/.cursor/.claude` 4 镜像�? `AGENTS.md` 同步新增以下子项�?
    1. **运行时输出编�?*：`[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` + `$OutputEncoding = [System.Text.Encoding]::UTF8`，Linux shell �?`export LANG=zh_CN.UTF-8` + `export LC_ALL=zh_CN.UTF-8`�?
    2. **源文�?BOM**：PS 5.1 中文 Windows 默认�?GBK 解析源码，UTF-8 no-BOM 会导致中文字符串解析错；脚本文件应保存为 UTF-8 with BOM（前 3 字节 `EF BB BF`）；同时交付前用 `Parser.ParseFile` 做静态语法自检�?
    3. **管道原始字节传输**：PS 5.1 字符串通过管道喂给 docker/ssh/mysql 时以 UTF-16 LE+BOM �?stdin，会被外部命令识别不了；要么 `cmd /c type <file> | <external>` 透传字节，要么用 `[Diagnostics.Process]` + `StandardInputEncoding=UTF8` + `BaseStream.Write()`�?
    4. **here-string 串入 U+FEFF 隐限**：UTF-8 with BOM �?.ps1 文件�?PS 5.1 解析时，here-string `@"..."@` 内容首字符是源文�?BOM；helper 入口必须 `$input = $input.TrimStart([char]0xFEFF)`�?
  - 同步状态：5 �?SKILL.md 均一致更新�?

- **e2e 脚本踩到的真实坑位（沉淀�?skill�?*
  - **脚本源文件必�?UTF-8 with BOM**：早期版本用 `WriteAllText(..., UTF8NoBom)` 写脚本，PS 5.1 �?GBK 解析中文报错 `字符串缺少终止符: "`；修复用 `New-Object System.Text.UTF8Encoding($true)` 重写脚本�?BOM�?
  - **`Get-Content -Raw` 默认 ANSI 解码**：从 utf-8 临时文件�?SQL 时塞�?U+FEFF；最终改�?`Process API` 完全控制 stdin 字节流�?
  - **PS 5.1 管道 UTF-16 LE**：字符串 `| docker` 时被包装�?UTF-16 LE+BOM，psql 收到乱码字节；改�?`.NET Process` API + `BaseStream.Write()` 写字节�?
  - **here-string 污染**：脚本本身是 UTF-8 BOM 后，`$Sql` 变量首字符是 U+FEFF；`Run-Psql` 入口 `TrimStart([char]0xFEFF)` 剥掉�?

#### 3. 影响

- 对外行为变化：Agent 现可通过 MCP SSE `checkIn` 主动声明值班，调度器在多候选用 `pickAlternative` 时优先选值班中的 Agent；过期的 ACTIVE 租约会自动翻�?EXPIRED�?
- 代码变化�?
  - `helloai-core/.../mcp/McpMcpServer.java`：新�?`checkIn` / `checkOut` 两个 `@Tool`；`checkOut` 主字段名 `closeReason` 兼容 `reason`�?
  - `helloai-core/.../service/AgentDutyLeaseService.java`：新�?`checkIn / closeLease / isOnDuty`�?
  - `helloai-core/.../agent/executor/AgentSelector.java`：增�?`dutyRank` 排序�?
  - `helloai-core/.../entity/Agent.java` + `MyBatisPlusMetaObjectHandler.insertFill`：补 `consecutiveFailureCount` 默认填充�?
  - `helloai-job/.../task/DutyLeaseExpirationTask.java`：新�?`@Scheduled` 巡检�?
  - `verify-agenthub-duty-e2e.ps1`：新�?S1/S2/S3 三场景脚本�?
  - 5 �?SKILL.md + AGENTS.md 同步规则 6 四子项�?
- 数据结构变化：`agent_duty_lease` 表已�?`V1__init_all.sql`（第 1508 行）随初始化建表�?*非本轮新�?*（早期本记录误写 Flyway V18，V18 实为 `event_consumption_log`）；本轮实际新增�?schema 变更�?`agent_mcp_server` �?`checkIn/checkOut` 默认 seed（Flyway V21 `V21__seed_agent_mcp_server_duty_tools.sql`）�?

#### 4. 遗留

- `b7-a mvn -q -DskipTests package` 全项目冒烟：通过 Node fallback shell 调起�?`mvn` launcher �?OpenJDK 17.0.18+8 + Windows 11 环境下崩溃（`EXCEPTION_ACCESS_VIOLATION` �?`jvm.dll+0x2cf4ce`，elapsed time 0.023s�?1 �?hs_err_pid*.log 同一症状），与本轮代码无关。用户后续在 IDEA �?Rebuild + Maven clean + package 验证均通过，等价于 b7-a 验证。后�?`mvn` 命令应直接从 IDEA Run/Debug 或原�?`cmd /c mvn ...` 调用，避�?Node fallback shell�?
- AgentHub P0 未做的项目：dashboard / 值班报表、`workMode=STRICT` 下的独占报锁语义、动�?TTL 自适应、多 Agent 同时值班�?concurrency 预扣语义；后�?AgentHub V1 P1 启动时按优先级推�?
- E2E 脚本依赖用户手动�?IDEA 启动后端 + docker compose �?postgres/redis/rabbitmq；CI 路径未沉淀�?

---

### 2026-07-16 A 档收尾：值班只读报表接口 + S6 重定义为启动守卫 + 文档失真修正

#### 1. 范围

- N12 P1 收尾：新增值班租约只读报表接口（分页列�?+ 状态概览），作为后�?dashboard 数据源�?
- N6 遗留 S6 收口：把"手动 MQ-isolation 重启验证"重定义为独立的启动期 fail-fast 守卫脚本�?
- 文档失真修正：差距表 + 迭代记录�?`agent_duty_lease` 被误记为 Flyway V18 的两处（实为 `V1__init_all.sql` 建表，V18 �?`event_consumption_log`）�?
- 明确不做：`AgentExecutionProperties.java` 注释（核查后�?T5 前旧语义残留，见下）、dashboard 前端、`workMode=STRICT` 独占报锁、concurrency 预扣�?

#### 2. 实际落地

- **N12 P1：值班只读报表接口**
  - `AgentDutyLeaseService` 新增两个只读查询：`listLeases(agentId, status, pageNum, pageSize)`（`LambdaQueryWrapper` 条件过滤 + `orderByDesc(startedAt)` + MyBatis-Plus `page(...)` 分页）、`countByStatus()`（按 `AgentDutyLeaseStatus` 枚举逐状�?`count(...)`，`LinkedHashMap` 保序）�?
  - 新增 DTO（`helloai-api/dto/duty/`）：`DutyLeaseResponse`（租约列表项，含 agentId/agentName/sessionId/workMode/maxConcurrent/status/startedAt/lastRenewedAt/expiresAt/closeReason）、`DutyOverviewResponse`（active/closed/expired/total 状态概览）�?
  - 新增 `AgentDutyLeaseController`（`@RestController @RequestMapping("/api/admin/duty-leases") @RequiredArgsConstructor`，构造器注入 `AgentDutyLeaseService` + `AgentMapper`）：
    - `GET /api/admin/duty-leases`：`list(agentId, status, page=1, size=20)` �?`R<PageResult<DutyLeaseResponse>>`，`@RequestParam` 显式 `value`+`defaultValue`；列表项 `agentName` 用局�?`nameCache`（`HashMap` + `computeIfAbsent`）避免逐行�?Agent 名的 N+1�?
    - `GET /api/admin/duty-leases/overview` �?`R<DutyOverviewResponse>`，从 `countByStatus()` 组装�?
  - 遵循 CODE_STYLE：Controller 薄、返�?`R<T>`、查询返�?Response DTO、逻辑删除�?`@TableLogic` 自动过滤�?

- **N6 遗留 S6：重定义为独立启动守卫脚�?`verify-execution-dispatch-guard.ps1`（新增）**
  - 背景：T5 引入 `ExecutionDispatchValidator` 后，�?S6 组合（consumer-mode �?{POLLER,BOTH} + consumer-enabled=false）会�?`@PostConstruct` 阶段直接 `IllegalStateException` fail-fast，应用根本起不来——旧 S6 已不再是"能跑的验�?，而是"被启动期守卫拦截的非法部署形�?。它本质需�?重启 JVM + 观察启动成败"，与 `verify-poller-e2e.ps1` �?运行�?Poller 兜底 E2E"不是一类验证，故单独成脚本、不再塞�?poller 脚本�?
  - 三场景：G1（`consumer-enabled=false` �?期望 fail-fast，日志含 `consumer-mode=POLLER` + `consumer-enabled=true`）、G2（`producer-enabled=false` �?期望 fail-fast，日志含 `dispatch-mode=MQ` + `producer-enabled=true`）、G3（`dispatch-mode=NONE` + `consumer-mode=EVENT` �?期望启动成功 + `/api/health` 200，合法最简组合不依�?MQ）�?
  - 断言口径：`Verify-FailFast`（进程在超时内退�?+ exitCode�? + 日志命中期望 ASCII 片段 + 6565 �?Listen）；`Verify-Healthy`（进程持续存�?+ `/api/health` 200）。脚本跑完不自动重启正常实例，仅打印恢复提示。遵�?skill 规则 6 编码防护�?
  - `verify-poller-e2e.ps1` 头注�?S6 段同步改写：�?手动 MQ-isolation"改为"已迁出，�?`verify-execution-dispatch-guard.ps1`"，并说明 T5 fail-fast 使旧组合作废�?

- **文档失真修正（两�?V18→V1�?*
  - 差距�?N6 处理建议：S6 �?手动 MQ-isolation 补充对照实验"改写�?独立启动�?fail-fast 守卫脚本"；N12 处理建议：标注值班只读报表接口已交付；§5 优先级第 3 条同步�?
  - 迭代记录�?026-07-16 AgentHub 轮的两处 `agent_duty_lease（Flyway V18）` 修正�?`V1__init_all.sql �?1508 行建表`，并注明 V18 实为 `event_consumption_log`、本轮实际新�?schema �?V21 `agent_mcp_server` duty tools seed�?

#### 3. 影响

- 对外行为变化：新�?`GET /api/admin/duty-leases`（分页列表）+ `GET /api/admin/duty-leases/overview`（状态概览）两个只读管理端点�?
- 代码变化�?
  - `helloai-core/.../service/AgentDutyLeaseService.java`：新�?`listLeases` / `countByStatus` 两个只读方法�? `LambdaQueryWrapper` / `IPage` / `Page` / `LinkedHashMap` / `Map` import）�?
  - `helloai-api/.../controller/AgentDutyLeaseController.java`（新增）、`helloai-api/.../dto/duty/DutyLeaseResponse.java`（新增）、`helloai-api/.../dto/duty/DutyOverviewResponse.java`（新增）�?
  - `verify-execution-dispatch-guard.ps1`（新增，S6 v1.0；交付后用户实测触发 PS 5.1 解析错误 `Unexpected token '}'`，定位为双引号字符串内含中文全角括号叠加隐藏 BOM 字节被解析器提前闭合，已全量重构�?*单引�?+ `+` 拼接、runtime 字面量纯 ASCII、头注释去中�?*）、`verify-poller-e2e.ps1`（头注释 S6 段改写）�?
  - skill 规则 6 补第 5 子项（双引号 CJK 提前闭合陷阱 + 单引号拼接修复范式）�? �?`helloai-preflight` SKILL.md（`.agents` 母版 + `.qoder/.trae/.cursor/.claude` 4 镜像�? `AGENTS.md`（Additional rules 补一条英文精简条目）同步；差距�?D8 补第 5 子项。注：`.agents/helloai-guidance.master.json` 生成器母版不在仓库内，AGENTS.md 本轮按其既有精简英文风格手工补条，未�?改母版→重生�?路径�?
  - `doc/HelloAI_实现差距�?md`（N6/N12 处理建议 + §5 优先级）、`doc/log/HelloAI_迭代执行记录.md`（两�?V18→V1 失真修正 + 本轮记录）�?
- 数据结构变化：无（值班报表复用既有 `agent_duty_lease` 表，纯只读查询）�?
- 主动不改：`AgentExecutionProperties.java` —�?用户反馈"下面字段注释还写着 DB Poller 成为主消费路�?，Grep 全文核查后注释已全是 T5 新语义（"Poller 仅作兜底"/"MQ 主消�?+ Poller 孤儿兜底"/"不再是主消费路径（T5 语义�?），无该陈旧残留，故本轮不动此文件；真正的歧义源是枚举值名 `POLLER` 本身�?MQ 主消�?语义不符，改名为破坏性变更，建议单独立项，本轮不做�?

#### 4. 遗留

- 值班报表 Java 改动（Controller + 2 DTO + Service 只读方法）需�?IDEA �?Rebuild 验证编译：Bash 工具�?Node fallback shell �?`mvn` 会必�?JVM `EXCEPTION_ACCESS_VIOLATION` 崩溃，本轮已逐一静态核对依赖点（`PageResult.of` / `R` / `LambdaQueryWrapper` / `AgentMapper` 均为既有可用 API），编译验证�?IDEA�?
- `verify-execution-dispatch-guard.ps1` 需�?后端可启�?+ docker compose �?postgres/redis/rabbitmq + jar 已构�?环境下实�?G1/G2/G3；本轮仅交付脚本，未跑真实三场景�?
- dashboard 前端接入值班报表接口、`workMode=STRICT` 独占报锁语义、动�?TTL 自适应、多 Agent 同时值班�?concurrency 预扣语义仍为 AgentHub V1 P1 后续项�?

---

### 2026-07-16 A 档收尾验证：值班报表编译确认 + S6 守卫脚本实测 12/12 PASS

#### 1. 范围

- 关闭上一轮（“A 档收尾”）两处遗留：值班报表 Java 编译验证、`verify-execution-dispatch-guard.ps1` 三场景实测�?
- 明确不做：值班报表两个只读端点的运行时冒烟（`GET /api/admin/duty-leases` �?`/overview`），按用户约定推迟到前后端联调时一并测；dashboard 前端、`workMode=STRICT` 独占报锁、concurrency 预扣不做�?

#### 2. 实际落地

- **值班报表编译验证（上轮遗留①关闭�?*
  - 用户�?IDEA Rebuild + Maven clean + package 通过；核�?`AgentDutyLeaseController.class` / `DutyLeaseResponse.class` / `DutyOverviewResponse.class`（helloai-api�? `AgentDutyLeaseService.class`（helloai-core）均�?15:07 重新编译，`helloai-start-1.0.0-SNAPSHOT.jar`（约 60MB）同批产出。`mvn package` 成功即等价编译验证，无需 verify-*.ps1�?

- **`verify-execution-dispatch-guard.ps1` 实测 + 三处修复（上轮遗留②关闭�?*
  - 实测前脚本因运行环境暴露三个 bug，逐一修复�?
    1. **java 解析健壮�?*：裸 `java` 命中 Oracle javapath 存根（静默空转、无输出）、且用户机上 `ms-17.0.18` 这套 JDK 安装本身损坏（连 `java -version` 都直�?`EXCEPTION_ACCESS_VIOLATION @ jvm.dll+0x2cf4ce` 崩溃）。改 `Resolve-JavaExe` 为探测式：按 显式 `-JavaExe` �?`JAVA_HOME` �?`where.exe`（跳�?javapath/WindowsApps）→ `%USERPROFILE%\.jdks\*` 降序 逐个 `Probe-JavaVersion`（用 Start-Process �?`-version`），跳过静默/崩溃候选，选中首个能真正打印版本号者（实测自动选中健康�?`ms-17.0.19`）。新�?`-JavaExe` 手动覆盖参数�?
    2. **退出码取空**：`Start-Process -PassThru -NoNewWindow` 起的进程退出后 `.ExitCode` 返回空（断言 `exit code non-zero (got )` 假失败）。修复：Start-Process 后立�?`$null = $proc.Handle` 缓存句柄，保�?ExitCode�?
    3. **`[string]` 参数类型约束强转**：`param([string]$JavaExe)` 使脚本作用域�?`$script:JavaExe` 被约束为 [string]，直接把 `Resolve-JavaExe` 返回�?hashtable 赋给它会�?`.ToString()` 成字符串 `"System.Collections.Hashtable"`，导�?FilePath 为空。修复：用独立无类型变量 `$javaInfo` �?hashtable，只�?`.Exe` 字符串赋 `$script:JavaExe`�?
  - 修复后实测三场景（真�?jar，docker postgres Up）：**G1（`consumer-enabled=false`）fail-fast + exit code 1 + 日志命中 `consumer-mode=POLLER`/`consumer-enabled=true` + 6565 �?Listen；G2（`producer-enabled=false`）fail-fast + exit code 1 + 日志命中 `dispatch-mode=MQ`/`producer-enabled=true` + 6565 �?Listen；G3（`dispatch-mode=NONE` + `consumer-mode=EVENT`）进程存�?+ `/api/health` 200。PASS: 12 / FAIL: 0�?026-07-16 14:59 实测）�?* 证明 T5 `ExecutionDispatchValidator` 启动�?fail-fast 守卫在真实环境按预期拦截非法组合、放行合法最简组合�?

#### 3. 影响

- 对外行为变化：无（本轮为验证 + 脚本健壮化，无业务代码改动）�?
- 代码变化�?
  - `verify-execution-dispatch-guard.ps1`：`Resolve-JavaExe` 重写为探测式 + 新增 `Probe-JavaVersion` + 新增 `-JavaExe` 参数 + `Start-App` �?`$null = $proc.Handle` + preflight 用独立变�?`$javaInfo` 避免类型强转 + exit code null 假阳性修复�?
  - `doc/HelloAI_实现差距�?md`（N6 S6 补实测结论）、`doc/log/HelloAI_迭代执行记录.md`（本轮记录）�?
- 数据结构变化：无�?

#### 4. 遗留

- 值班报表两个只读端点（`GET /api/admin/duty-leases` 分页列表 + `/overview` 状态概览）的运行时冒烟未做，约定在 AgentHub V1 P1 dashboard 前后端联调时一并验证�?
- `ms-17.0.18` 这套 JDK 安装已损坏（非项目问题），建议用户删除或重装；守卫脚本已能自动绕过、优先选健�?JDK�?
- dashboard 前端接入、`workMode=STRICT` 独占报锁、动�?TTL 自适应、多 Agent 同时值班�?concurrency 预扣仍为 AgentHub V1 P1 后续项�?

---

### 2026-07-16 B 档收尾验证：Poller 兜底 E2E 实测 15/15 PASS

#### 1. 范围

- 关闭 N6 运行态兜底验证遗留：在真实运行环境下重跑 `scripts/powershell/verify-poller-e2e.ps1`，确�?S1-S5 全部可重复通过�?
- 本轮只收口验证脚本与文档，不改业务链路语义；明确不做：新增消费模式、调�?`ExecutionCompensationTask` 周期、改 `startIfNeeded` 契约、扩展到前端/dashboard�?

#### 2. 实际落地

- **`scripts/powershell/verify-poller-e2e.ps1` 健壮化与口径收口**
  - pre-flight 健康检查由单次 `Invoke-WebRequest` 改为 30 秒窗口内重试，并在失败时额外输出 `listening=` �?`lastErr=`，区分“服务未启动”与“服务已起但 health 不通”�?
  - mock execution hard gate 前移�?sample prepare 之前；若 `GET /api/health/execution-mode` 返回 `mockMode=false` �?provider 不是 `mock`，脚本直�?fail-fast，避免失败时先污�?e2e 样本数据�?
  - 新增 `-AllowRealExecution` 开关；默认仍坚�?fail-fast，只有显式允许时才在真实 LLM 环境继续执行�?

- **S2 / S4 / S5 样本构造修正，统一对齐 T5 `startIfNeeded` 契约**
  - 首轮实测暴露出脚�?行为漂移：S2/S4/S5 若把样本 `sub_task` 建成或重置为 `PENDING`，当�?T5 �?`startIfNeeded` 会拒绝推进，只留�?`consume_skipped` 或被 30s `ExecutionCompensationTask` 抢先�?`TIMEOUT`，无法证�?Poller 驱动的真�?consume-path�?
  - 修正后：
    - S2 改为独立 `ASSIGNED` sub_task（不复用主样本）�?
    - S4 三个额外 sub_task 全改为独�?`ASSIGNED`�?
    - S5 改为独立 `ASSIGNED` sub_task，不�?reset 共享样本�?`PENDING`�?
  - 这样 Poller 推出�?`consume -> startIfNeeded -> executeOnce` 路径与当前代码事实一致，不再依赖�?era �?`PENDING` 语义�?

- **S4 orphan age 窗口修正，避开补偿任务抢占**
  - 首轮 runTag=`20260716-174205`：S4 三条记录使用 `create_time = now() - 300s`，在 5 秒等待窗口内�?`ExecutionCompensationTask` 抢先�?`TIMEOUT`，表现为 `total=3 / polled=0 / progressed=3 / distinct_sub_tasks=0`，属于“超时补偿推进”，不是 Poller 兜底证据�?
  - 修正�?`create_time = now() - 240s`：仍大于 `poller-orphan-threshold-seconds=60`，足以被 `listOrphanPending` 扫到；同时小�?`pendingTimeoutMinutes=5` �?300s 阈值，避免�?30s timeout compensation 抢先接管�?

- **最终实测结果（真实运行环境�?*
  - `scripts/powershell/verify-poller-e2e.ps1`
  - runTag=`20260716-174605`
  - **PASS: 15 / FAIL: 0**
  - 分场景：
    - S1：孤�?`PENDING` 行被 Poller 扫到，`last_attempt_at` 刷新，timeline �?`sub_task_execution_command_poll_recovery`
    - S2�? 条同 sub_task `PENDING` 记录中仅 1 条推进出 `PENDING`，验�?CAS `markRunning` 去重
    - S3：`IN_PROGRESS` 子任务可接受晚到 `submitResult`
    - S4：`polled=3 / progressed=3 / distinct_sub_tasks=3`，证�?3 条孤儿记录都�?Poller 兜底接住并推�?
    - S5：`last_attempt_at`、`sub_task_execution_command_poll_recovery`、`poll-recovery:` trigger、`rogue_consume_events=0` 四项证据链均成立，证明主消费路径不可达的轻量等价场景下，处理痕迹全部来自 Poller

#### 3. 影响

- 对外行为变化：无（本轮仅为验证脚本收口与文档回写）�?
- 代码变化�?
  - `scripts/powershell/verify-poller-e2e.ps1`
    - 新增 pre-flight health retry / `listening=` 诊断
    - mock gate 前移 + `provider` 判定 + `-AllowRealExecution`
    - S2/S4/S5 独立 `ASSIGNED` sub_task 样本隔离
    - S4 orphan age �?300s 调整�?240s，避�?timeout compensation 抢占
    - 若干 psql 输出解析与断言正则增强（避免表�?页脚干扰�?
- 文档变化�?
  - `doc/HelloAI_实现差距�?md`：N6 补最�?Poller E2E 15/15 �?S6 守卫 12/12 证据
  - `doc/log/HelloAI_迭代执行记录.md`：补本轮收尾记录

#### 4. 结论与遗�?

- 结论：N6 当前已同时具�?
  - **启动期守卫证�?*：`scripts/powershell/verify-execution-dispatch-guard.ps1` �?PASS 12 / FAIL 0
  - **运行态兜底证�?*：`scripts/powershell/verify-poller-e2e.ps1` �?PASS 15 / FAIL 0
  - 可视�?“T5 Poller 兜底 + Validator 启动�?fail-fast�?验证闭环完成�?
- 遗留�?
  - 控制�?CJK 显示�?PowerShell 5.1 下仍会有乱码，但不影响脚本断言�?`.out` 文件内容；如后续需要，可单独做控制台输�?ASCII 化收口�?
  - `helloai-api/src/main/java/com/helloai/api/controller/HealthController.java` �?`helloai-start/src/main/resources/application.yml` 的当前工作区修改未纳入本轮验证收口提交，按用户后续独立决策处理�?
  
  ---
  
  ### 2026-07-16 A 档收尾：R2 Publisher 旧方法清�?+ R3 V19 era SENT/CONFIRMED backfill + AgentHub V1 P1 dashboard 前端接入
  
  #### 1. 范围
  
  - 关闭 P1 实现差距表遗留中“可立刻动手”的三件事：**R2 �?Publisher 方法清理、R3 V19 era SENT/CONFIRMED 行时间戳 backfill、AgentHub V1 P1 dashboard 前端接入**�?
  - 本轮不涉�?`workMode=STRICT` 独占报锁语义、多 Agent 同时值班�?concurrency 预扣、动�?TTL 自适应、N2/N8 独立迭代�?
  
  #### 2. 实际落地
  
  - **R2：清�?`ExecutionCommandMqPublisher.publish(ExecutionCommand)` 旧方�?*
    - 旧入口仅做“事务活跃时注册 `afterCommit` 回调、无事务立即发”，②a 引入 Outbox 后该入口已无调用方，唯一生产路径�?`OutboxRelayTask` �?`publishWithCorrelation`，旧方法保留只会形成第二套时序假设�?
    - 删除 `publish(ExecutionCommand)` 方法、清�?`TransactionSynchronization*` 两个 import；类�?javadoc “Phase 2F 关键修正一”段落改为“②b 收尾：AFTER_COMMIT 语义已移除”，列表项调用方�?`ExecutionCommandService` 改为 `OutboxRelayTask`�?
    - 单测同步：删除整�?`ActiveTransactionContext` 嵌套类（AFTER_COMMIT 用例 2 �?+ `@AfterEach` 同步清理 1 个）；`NoTransactionContext` 两个用例改为 `publishWithCorrelation`，新�?1 个用例验�?`correlationKey` �?`eventId` 不一致时 `MessageProperties` 仍以 `eventId` 为准、返回的 `CorrelationData` 携带 outbox 主键（覆�?②b Confirm 回写场景）�?
    - 语义自检：全工程 0 处调用旧 `publish(ExecutionCommand)`�? �?import 残留�?
  
  - **R3：V22 `agent_command_outbox_backfill_timestamps` 回填历史 SENT/CONFIRMED �?*
    - V19 表只�?`update_time`（BEFORE UPDATE 触发器维护），V20 才加 `last_sent_at`/`confirmed_at` 两列但未 backfill；V21 已被 `seed_agent_mcp_server_duty_tools` 占用，本轮使�?**V22**�?
    - 回填策略（保守近似，全部 WHERE IS NULL 守卫，重跑安全）�?
      - `status=1 AND deleted=0 AND last_sent_at IS NULL` �?`last_sent_at = update_time`（OutboxRelayTask markSent 唯一动作即同�?`last_sent_at` �?`update_time`，二者近似相等）
      - `status=3 AND deleted=0 AND confirmed_at IS NULL` �?`confirmed_at = update_time`
      - `status=2` FAILED 不回填：语义可能�?publish 阶段失败（不该置值）�?broker NACK，历史不一致，保持 NULL
      - `status=0` PENDING 不动：语义上未发�?
    - 幂等：所�?UPDATE 都有 IS NULL 守卫，可重复执行�?
  
  - **AgentHub V1 P1 dashboard 前端接入**
    - 后端值班报表两个只读端点（`GET /api/admin/duty-leases` 分页 + `/overview` 概览）此前已具备，本轮补齐前端�?
    - 新增 `helloai-ui/src/api/duty.ts`：`dutyApi.list({ agentId?, status?, page, size })` + `dutyApi.overview()`，对齐后�?`AgentDutyLeaseController` �?`R<PageResult<DutyLeaseResponse>>` 解包�?
    - 新增 `helloai-ui/src/types/duty.ts`：`DutyLeaseResponse` / `DutyOverviewResponse` / `DutyLeaseStatus` / `DUTY_LEASE_STATUS_MAP`（值班�?已签退/已过期），`PageResult<T>` 直接复用 `types/index.ts` 已有定义避免重复�?
    - 新增 `helloai-ui/src/views/duty/DutyLeaseList.vue`：状�?+ Agent ID 过滤、分页表（租�?ID / Agent �?ID / 会话 / 模式 / 并发上限 / 状�?tag / 开始·续约·过期时�?/ 关闭原因），`DUTY_LEASE_STATUS_MAP` 统一渲染�?
    - `Dashboard.vue` �?“Agent 值班概览”区块：4 �?stat 卡（值班�?/ 已签退 / 已过�?/ 租约总数�? “查看全部租�?→�?链接，异步加�?`loadDutyOverview()` 失败�?`console.warn`，不阻断 dashboard 主图�?
    - 路由 `router/index.ts` 注册 `/duty-leases`，菜�?`MainLayout.vue` 增加 `Clock` 图标菜单项（同步 import 列表）�?
  
  - **`scripts/powershell/verify-dashboard-duty-leases.ps1` 验证脚本**
    - 遵循 SKILL.md 规则 6：UTF-8 强制头（�?BOM�? PS 5.1 单引�?+ `+` 拼接、runtime 字面量纯 ASCII、CJK 仅出现在 `#` 注释�?`.out` 文件内容�?
    - 覆盖 S1 overview 字段齐、S2 list 分页结构、S3 `status=ACTIVE` 过滤生效、S4 V22 backfill 抽查（`status=1` �?`last_sent_at IS NULL` 数为 0 �?`status=3` �?`confirmed_at IS NULL` 数为 0）�?
    - 模板参照 `verify-agenthub-duty-e2e.ps1`：同一�?`Invoke-Json` / `Run-Psql` / `Get-PsqlFields` helper，pre-flight 同样要求 docker compose + IDEA 启动 + Flyway 已跑 V22�?
  
  #### 3. 影响
  
  - 对外行为变化：无新增业务语义，仅删除一条已无调用方的旧方法、给历史数据补齐时间戳、新增一个前端页面与一个菜单项�?
  - 代码变化�?
    - `helloai-core/.../ExecutionCommandMqPublisher.java`：删除旧 `publish` 方法、清冗余 import、改类级 javadoc
    - `helloai-core/.../ExecutionCommandMqPublisherTest.java`：删�?AFTER_COMMIT 用例、改 `publish` �?`publishWithCorrelation`、新�?correlationKey 用例
    - `helloai-start/.../db/migration/V22__agent_command_outbox_backfill_timestamps.sql`（新增）
    - `helloai-ui/src/api/duty.ts`（新增）
    - `helloai-ui/src/types/duty.ts`（新增）
    - `helloai-ui/src/views/duty/DutyLeaseList.vue`（新增）
    - `helloai-ui/src/views/Dashboard.vue`：新�?“Agent 值班概览”区�?+ `loadDutyOverview()` 加载
    - `helloai-ui/src/router/index.ts`：注�?`/duty-leases`
    - `helloai-ui/src/layouts/MainLayout.vue`：新增菜单项 + `Clock` 图标 import
    - `scripts/powershell/verify-dashboard-duty-leases.ps1`（新增）
  - 数据库变化：V22 backfill �?Flyway 启动时一次性执行，�?status IN (1,3) �?IS NULL 的行做时间戳回填，无 schema 变化�?
  
  #### 4. 遗留
  
  - AgentHub V1 P1 仍余：`workMode=STRICT` 独占报锁语义、多 Agent 同时值班�?concurrency 预扣语义、动�?TTL 自适应（按 N12 缺口继续）�?
  - ~~b1 �?`mvn -pl helloai-core compile` / `test` 编译验证未在本轮执行（环境无 mvn）~~�?*已实测通过**�?026-07-16 23:1x�?�?详见 §5 验证回执�?
  - ~~`verify-dashboard-duty-leases.ps1` 尚未真实环境实测~~�?*S1-S4 已在真实环境实测全部 PASS**�?026-07-16 23:1x�?�?详见 §5 验证回执�?
  
  #### 5. 验证回执�?026-07-16 23:1x 实测�?
  
  ##### 5.1 实证�?
  
  | �?| 实际状�?| 说明 |
  |---|---|---|
  | R2 `ExecutionCommandMqPublisher` 编译产物 | �?`target/classes/.../ExecutionCommandMqPublisher.class` 5358 bytes�?3:06）| 用户本地 mvn rebuild + package 通过 |
  | R2 单测 JUnit Runner | �?4/4 PASSED，`Process finished with exit code 0`（IDEA JUnit 23:18 实测）| `DirectPublish.publishWithCorrelationSendsImmediately` / `publishBodyIsRestorableJson` / `publishUsesCorrelationKeyOnlyOnReturnedCorrelationData` / `FailurePaths.publishThrowsWhenSerializationFails` 全过；FailurePaths 中出现的 `ERROR mq.execution-command.serialize.failed ... JsonMappingException: boom for eventId=evt-abc` 是用�?mock 故意触发的失败传播场景，非缺�?|
  | 全工程残留旧 `publish(ExecutionCommand)` 调用�?| �?0 �?| 全工�?grep 无命�?|
  | macOS zsh 等价脚本 | �?**新增** `scripts/shell/verify-dashboard-duty-leases.sh`（已 `chmod +x`、`zsh -n` 语法检查通过）| 依赖 jq + docker + curl + zsh（用户机器均具备），�?PS1 同源；pre-flight 同样 fail-fast |
  | verify 端到端实测（PS1�?| �?**S1 overview / S2 list / S3 status=ACTIVE 过滤 / S4 V22 backfill 抽查** �?PASSED | V22 �?fresh volume `agent_command_outbox` 无历�?SENT/CONFIRMED 行，S4 总数均为 0，符合“空表也 PASS”的幂等设计 |
  
  ##### 5.2 本轮首次 S1 overview 实测�?HTTP 500 的根因澄清（�?Flyway 回归，不立项�?
  
  - 现象：第一次跑 verify 脚本�?S1 overview 返回 HTTP 500 `{"code":500,"msg":"服务内部错误..."}`
  - 根因�?*�?Flyway 回归**。用户在 Windows / macOS 之间手工�?V1~V22 多个 SQL 文件合并�?`V1__init_all.sql` 做集中初始化时，遗漏了其中某段（典型为某�?CREATE TABLE �?seed INSERT），导致 `agent_duty_lease` 等派生表未随 V1 一同初始化。手动补跑一次合并后�?`init_all.sql` 后四步全过（实测时已排除）�?
  - 决策：用户明确“新环境干净 Flyway 跑下来不会复现，问题可暂忽略”，本轮 **不立�?P-FIX**；新成员接入仍以官方 `docker compose up -d` + Flyway V1~V22 顺序跑为主路径�?
  - 复现防护（非本次交付）：未来如再需手工合并迁移文件，建议增加一份“合并后 V1 �?当前 baseline”的差异自检脚本（不在本轮范围内）�?
  
  ---
  
### 2026-07-17 AgentHub V3 门铃通知通道：PR-1 内核 + PR-2 响铃接线（单�?17/17 全绿�?

#### 1. 范围

- �?`doc/archive/HelloAI_门铃通知通道设计.md` §10 的最�?PR 拆分，落�?**PR-1 门铃内核** + **PR-2 响铃接线**：补一条“服务端 �?外部 Agent 单向 SSE 门铃”，把外�?`CLI_CLIENT` 从任务发布到感知�?0~30s 轮询延迟降到秒级�?
- 明确不做（本轮）：PR-3 值班/鉴权收口（`isOnDuty` 建连校验、`checkOut`/租约到期主动 disconnect）与端到端验证脚本；可�?PR-4 保活刷心跳；不引�?WebSocket/STOMP/Netty；不新增 Flyway/�?MQ 队列；不�?`AgentStatus`/`AgentOnlineStatus` 枚举；不做多实例 fanout（单实例进程�?Map）�?

#### 2. 实际落地

- **PR-1 门铃内核（能连上 / 能收 `connected` 握手 / 断连能清理）**
  - `DoorbellProperties`（helloai-common `config`）：`@ConfigurationProperties(prefix="helloai.doorbell")`，`enabled=true` / `emitterTimeoutMs=1_800_000`�?0min�?/ `keepaliveIntervalMs=15_000`，仿 `helloai.dispatch.*` 集中管理�?
  - `DoorbellSignal`（helloai-core `doorbell`）：`@Getter @JsonInclude(NON_NULL)`，字�?`type/eventType/refType/refId/serverTime`，静态工�?`connected()` / `keepalive()` / `inbox(eventType,refType,refId)`；信号极简�?*不含 title/summary/正文**（正文由 Agent 随后 `pullTasks` 拉取，保证门铃丢失不丢信息）�?
  - `DoorbellRegistry`（helloai-core `doorbell`）：�?`McpAuthContext` 单例风格的进程内 `ConcurrentHashMap<Long,SseEmitter>`；`register`（同一 agentId 已有连接先关旧再建新、防泄漏�? `unregister`（用 `remove(key,value)` 值条件删除，避免误删“关旧建新后的新连接”）/ `get` / `isConnected` / `size`�?
  - `DoorbellService`（helloai-core `doorbell`）：`connect(agentId)` 先校 `enabled`，建 `SseEmitter`（超时取 `emitterTimeoutMs`）并�?`onCompletion/onTimeout/onError` 回调均从 registry 注销，`register` 后立�?`doSend` 一�?`type=connected` 握手；`ring(agentId,signal)` 未连返回 false（尽力而为）；`disconnect(agentId)` / `connectionCount()`；私�?`doSend` 发送异常静默注销、不重试不抛错�?
  - `AgentDoorbellController`（helloai-api）：`GET /api/agents/doorbell/sse`，`produces=MediaType.TEXT_EVENT_STREAM_VALUE`，入�?`@RequestAttribute("_authId") Long agentId`，直接返�?`doorbellService.connect(agentId)`；复�?`AuthInterceptor` �?`/api/**` �?Bearer apiKey 鉴权链，不新�?token 体系�?
  - 单测：`DoorbellRegistryTest`�? 例：注册/查询/关旧建新/值条件注销/size�? `DoorbellServiceTest`�? 例：disabled 拒连/connected 握手/ring 命中/ring 未连/disconnect/connectionCount）�?

- **PR-2 响铃接线（发任务 �?门铃�?�?客户端被唤醒�?*
  - `InboxMessageCreatedEvent`（helloai-core `event`）：`@Getter` 不可变事件，字段最小化 `agentId/eventId/eventType/refType/refId`（不�?title/summary）�?
  - `AgentInboxService.send()` 一处收口发事件：注�?`ApplicationEventPublisher`；`save(inbox)` 成功�?`publishEvent(new InboxMessageCreatedEvent(...))`；`catch(DuplicateKeyException)`（`(event_id,agent_id)` 联合唯一约束→已投递）分支 `return` **不发事件**，避免重复投递重复响铃。因三条通知路径（`TaskController.create` 直发 / `SubTaskService` 状态流转五�?/ MQ `NotificationConsumer`）全收口�?`send()`，此一处发事件即覆盖全部�?
  - `DoorbellRinger`（helloai-core `doorbell`）：`@Async("doorbellExecutor") @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)` 监听 `InboxMessageCreatedEvent`，调 `doorbellService.ring(agentId, DoorbellSignal.inbox(...))`；`event==null || agentId==null` 直接 return，异常只 `log.debug` 不向上抛（靠轮询兜底）。�?AFTER_COMMIT 而非 `send()` 内直接响铃：保证“先落库、后响铃”，与项目既�?Outbox / 本地执行事件�?AFTER_COMMIT 时序哲学一致（架构参�?§5.1 Phase 2F），避免“响了铃但收件箱未提交、Agent pull 不到”�?
  - `DoorbellExecutorConfig`（helloai-start `config`）：`@Bean("doorbellExecutor")` `ThreadPoolTaskExecutor` core=2 / max=4 / queue=500 / `ThreadPoolExecutor.DiscardPolicy`，与 `executionCommandExecutor` 池隔离，响铃拥塞时直接丢弃——门铃尽力而为，永不拖累主链路（`@EnableAsync` 已在 `HelloAIApplication`）�?
  - 单测：`DoorbellRingerTest`�? 例：正常响铃/null 事件不响/ring 抛异常被�?agentId 为空不响�? `AgentInboxServiceTest`�? 例：`spy(new AgentInboxService(eventPublisher))` + `doReturn(true).when(service).save(any())` 验证发事件；`doThrow(new DuplicateKeyException("dup"))...` 验证 `never()` 发事件）�?

#### 3. 影响

- 对外行为变化：新增一个只进不出的 SSE 端点 `GET /api/agents/doorbell/sse`（建连即�?`connected`）；收件箱首次落库后会向已连门铃�?Agent 推一�?`type=inbox` 信号。MCP 主线（`pullTasks/claimSubTask/submitResult`）完全不改�?
- 代码变化（新�?8 / 修改 1）：
  - 新增 `helloai-common/.../config/DoorbellProperties.java`
  - 新增 `helloai-core/.../doorbell/DoorbellSignal.java` / `DoorbellRegistry.java` / `DoorbellService.java` / `DoorbellRinger.java`
  - 新增 `helloai-core/.../event/InboxMessageCreatedEvent.java`
  - 新增 `helloai-api/.../controller/AgentDoorbellController.java`
  - 新增 `helloai-start/.../config/DoorbellExecutorConfig.java`
  - 修改 `helloai-core/.../service/AgentInboxService.java`（注�?`ApplicationEventPublisher` + `save` 成功发事�?+ `DuplicateKey` 分支 return 不发�?12/-1�?
  - 新增测试：`DoorbellRegistryTest` / `DoorbellServiceTest` / `DoorbellRingerTest`（helloai-core `doorbell`�? `AgentInboxServiceTest`（helloai-core `service`�?
  - `doc/HelloAI_实现差距�?md`（新�?N13 + §2 结论 + §5 优先级）、`doc/log/HelloAI_迭代执行记录.md`（本轮记录）
- 数据结构变化：无（门铃是纯运行时连接态，不落库；`SseEmitter` �?Spring WebMVC 原生，helloai-core �?`spring-ai-starter-mcp-server-webmvc` 传递依�?`spring-webmvc`，零新增依赖）�?

#### 4. 遗留

- **PR-3 值班/鉴权收口（设�?§6.1/§10�?*：建连前�?`AgentDutyLeaseService.isOnDuty(agentId)` 校验（未打卡/�?ACTIVE 拒连）；`checkOut` 或租�?EXPIRED 时主�?`DoorbellService.disconnect(agentId)`；补端到端验证脚本（建连→发任务→秒级收 `inbox`→pullTasks；以及“关�?SSE 后再产生消息仍能轮询消费”证明门铃丢失不致命）�?
- 运行时端到端冒烟（真实后�?+ docker compose + 外部 Agent 模拟建连）本轮未做，�?PR-3 验证脚本一并补�?
- 可�?PR-4：门铃保�?建连顺带�?`HeartbeatService.seen(agentId)`（降�?Agent 额外 heartbeat 频率）；多实例实时性（Redis Pub/Sub fanout）为 §12 演进项�?

#### 5. 验证回执

- `mvn -pl helloai-core -am test -Dtest=DoorbellRegistryTest,DoorbellServiceTest,DoorbellRingerTest,AgentInboxServiceTest` �?**17 例全�?BUILD SUCCESS**（Registry 5 + Service 6 + Ringer 4 + Inbox 2）�?
- `mvn -pl helloai-start -am install "-Dmaven.test.skip=true"` �?**�?reactor MAIN 编译 BUILD SUCCESS**�?
- stale .m2 jar 排查：`mvn -pl helloai-start -am test-compile` 首次�?helloai-job 测试找不�?`AgentFallbackProperties`/`AgentCommandOutboxRelayProperties`（两类均�?helloai-common，本轮未触），定位为本地 `.m2` 陈旧 common jar；`mvn install` 刷新�?`mvn -pl helloai-job test-compile` BUILD SUCCESS，确认非代码回归�?
- PowerShell 注意：不支持 `&&`/`cd /d`（改 `Set-Location ...; mvn ...`）；`-Dkey=value` 需加引号防参数被拆分；`-pl X -Dtest=...` 需 `-am` 重建上游（新增的 `DoorbellProperties` �?common）�?

---

### 2026-07-17 AgentHub V3 门铃通知通道：PR-3 值班鉴权收口 + 兜底验证脚本（单�?22/22 全绿�?

#### 1. 范围

- 承接同日 PR-1（门铃内核）+ PR-2（响铃接线），落地设�?§6.1/§10 �?**PR-3 值班/鉴权收口 + 兜底验证**：建连前�?`isOnDuty` 闸门（未打卡拒连）、`checkOut`/租约到期时主动断门铃、补端到端验证脚本�?
- 明确不做（本轮）：可�?PR-4 保活�?`last_seen_at`；多实例 Redis Pub/Sub fanout（�?2 演进项）；不新增 Flyway/�?MQ 队列；不引入 WebSocket�?

#### 2. 实际落地

- **建连闸门（`DoorbellService.connect`�?*：`DoorbellService` 构造注�?`AgentDutyLeaseService`，`connect(agentId)` �?`enabled` 校验后前�?`isOnDuty(agentId)`——无 ACTIVE 值班租约即抛 `BizException(500)` 拒连�?先打卡再接电�?）。Controller/鉴权链不变，异常�?`GlobalExceptionHandler` 映射�?HTTP 500 + body `code=500`�?
- **主动断连（事件解耦，规避构造循环）**：为避免 `DoorbellService �?AgentDutyLeaseService` 双向构造依赖，反向断连走本地领域事件——新�?`DutyLeaseClosedEvent`（helloai-core/event，携 `agentId`/`reason`）；`AgentDutyLeaseService` �?`@RequiredArgsConstructor` + `ApplicationEventPublisher`，`closeLease`（覆�?checkOut）与 `expireLeases`（覆盖租约到期）在关闭行�?>0 �?`publishEvent`，`startLease` 防御性关�?*不发**事件（避免刚 checkIn 就被断连）。新�?`DoorbellDutyListener`（`@Async("doorbellExecutor") @TransactionalEventListener(AFTER_COMMIT)`，与 `DoorbellRinger` 对称）监听后�?`doorbellService.disconnect(agentId)`，异常静默�?
- **Bean 创建顺序无环**：`agentDutyLeaseService`（仅需 publisher）→ `doorbellService`（需 agentDutyLeaseService）→ `doorbellDutyListener` / `doorbellRinger`（需 doorbellService），�?reactor `install` BUILD SUCCESS 间接验证无编�?装配级循环�?
- **端到端脚�?`scripts/powershell/verify-doorbell-e2e.ps1`**：S1 �?ACTIVE 租约建连 �?HTTP 500 + `code=500`；S2 直接 INSERT 一�?ACTIVE 租约 �?curl `-N` 建连读首�?�?断言 `HTTP/1.1 200` + `event:connected` + `"type":"connected"`；S3 把租�?`expires_at` 改到过去 �?�?35s `DutyLeaseExpirationTask` �?EXPIRED �?事件驱动主动断连 �?断言 DB `status=EXPIRED,close_reason=lease_expired` �?SSE 后台 job 结束（流被服务端 `complete` 关闭）。脚本遵循规�?6：UTF-8 with BOM、单引号 + `+` 拼接、runtime 字面量纯 ASCII、CJK 只留注释�?

#### 3. 影响

- 新增 3 个文件：`DutyLeaseClosedEvent`、`DoorbellDutyListener`、`verify-doorbell-e2e.ps1`；新增单�?`DoorbellDutyListenerTest`�?
- �?3 个文件：`DoorbellService`（注�?+ 闸门）、`AgentDutyLeaseService`（发事件）、`DoorbellServiceTest`（补未在岗拒连用�?+ mock dutyLeaseService）�?
- 行为变化：门铃建连从"仅校�?enabled"收紧�?enabled + isOnDuty"；离岗（checkOut / 到期）从"仅靠 SSE 超时自然回收"升级�?事件驱动秒级主动断连"�?

#### 4. 遗留

- 运行时端到端冒烟**已实测通过**（见下方验证回执），本项遗留关闭�?
- 可�?PR-4：门铃保活帧顺带�?`HeartbeatService.seen(agentId)`（降�?Agent 额外 heartbeat 频率）；多实�?Redis Pub/Sub fanout�?

#### 5. 验证回执

- `mvn -pl helloai-core -am test -Dtest=DoorbellRegistryTest,DoorbellServiceTest,DoorbellRingerTest,DoorbellDutyListenerTest,AgentInboxServiceTest` �?**22 例全�?BUILD SUCCESS**（Registry 5 + Service 7 + Ringer 4 + DutyListener 4 + Inbox 2）�?
- `mvn -pl helloai-start -am install "-Dmaven.test.skip=true"` �?**�?reactor MAIN 编译 BUILD SUCCESS**（含 helloai-start，间接验证无装配级循环依赖）�?
- `verify-doorbell-e2e.ps1` �?`[System.Management.Automation.Language.Parser]::ParseFile` 自检 �?**PARSE-OK**；首次因 Write 落盘�?BOM 触发 PS 5.1 �?ANSI 码页误读 CJK 注释报解析错，改�?UTF-8 with BOM（`EF BB BF`）后通过（对�?D8 规则 6 源文�?BOM 子项）�?
- **2026-07-17 真实环境实跑 `verify-doorbell-e2e.ps1` �?ALL PASSED**（用户自启后�?+ docker，agentId 2077974111691915266）：`S1 OK` 未在岗建�?HTTP 500 `{"code":500,"msg":"Agent 未在岗�?}`；`S2 OK` 在岗建连 `HTTP/1.1 200` + `event:connected` + `data:{"type":"connected",...}`；`S3a OK` 租约�?`EXPIRED | lease_expired`；`S3b OK` SSE 后台 job state=`Completed`（事件驱动主动断连生效）。至�?N13 运行时冒烟闭环�?

---

### 2026-07-17 AgentHub V3 门铃通知通道：PR-4 保活帧调�?+ 双心跳（方案 A，单�?33/33 全绿�?

#### 1. 范围

- 承接同日 PR-1/2/3，落地设�?§6.2/§10.4 �?**PR-4**：① 门铃保活帧定时广播（周期性向已连门铃�?`keepalive`，防反向代理/NAT 空闲超时掐断长连接）；② 双心跳（建连时顺带刷一�?`HeartbeatService.seen`，让门铃建连也计入在线证据）�?
- 明确不做（本轮）：多实例 Redis Pub/Sub fanout（�?2 演进项，单实例进程内 Map 无需）；保活帧不�?`last_seen_at`（仅 connect 刷，规避僵尸连接掩盖离线）；不引�?WebSocket；不新增 Flyway/�?MQ 队列�?

#### 2. 实际落地

- **�?保活帧调度（本地无锁，每实例都跑�?*：新�?`DoorbellKeepaliveTask`（helloai-core/doorbell，`@Component` + `@Scheduled(fixedRateString = "${helloai.doorbell.keepalive-interval-ms:15000}")`），�?helloai-start `@EnableScheduling` 驱动�?*关键设计：与 `DutyLeaseExpirationTask` �?Redis 选主锁相反——门铃保活绝不选主�?* `SseEmitter` 是进程内连接态，�?Agent 的连接只落在持有它的那个实例，若选主只让一台跑会导致其它实例的连接被空闲超时掐断，因此每个实例必须保活自己 `DoorbellRegistry` 里的连接。任务体先判 `enabled` �?`connectionCount()==0` 早退，再 `broadcastKeepalive()`，异常整�?`catch` 吞掉（靠客户端重�?+ pullTasks 轮询兜底，永不打断调度线程）�?
- **广播实现**：`DoorbellRegistry` 新增 `forEach(BiConsumer<Long,SseEmitter>)`（委�?`ConcurrentHashMap.forEach` 弱一致遍历，遍历中允许并�?register/unregister 不抛 CME）；`DoorbellService.broadcastKeepalive()` 遍历�?`DoorbellSignal.keepalive()`，复用既�?`doSend`（失败静默注销），返回成功条数�?
- **�?双心跳（方案 A，默认关�?*：`DoorbellProperties` 新增 `refreshHeartbeat`（默�?**false**，保守）；`DoorbellService` 注入 `HeartbeatService`，`connect(agentId)` 在回�?`connected` 握手后，若开关开则调私有 `refreshSeen(agentId)` �?`heartbeatService.seen(agentId)`（刷 Redis TTL + `last_seen_at` + 三态重算），异常静默不阻断建连�?*仅建连刷一�?*（建连是客户端主动、最可信的存活证据），保活帧轮不刷——避免“僵尸连接”被持续�?ONLINE 掩盖真实离线�?
- **无循环依�?*：`DoorbellService(core) �?HeartbeatService(core) �?AgentMapper/StringRedisTemplate` 不回指；Bean 创建无环（全 reactor `install` 间接验证）�?

#### 3. 影响

- 新增 2 个文件：`DoorbellKeepaliveTask`、`DoorbellKeepaliveTaskTest`�? 用例：关闭跳�?/ 无连接跳�?/ 有连接广播一�?/ 广播异常被吞）�?
- �?5 个文件：`DoorbellRegistry`�?`forEach`）、`DoorbellService`�?`broadcastKeepalive` + 注入 `HeartbeatService` + `connect` 条件 `refreshSeen`）、`DoorbellProperties`�?`refreshHeartbeat` 默认 false）、`DoorbellServiceTest`（构造改 4 �?+ 补广�?2 �?+ 双心�?3 例）、`DoorbellRegistryTest`�?`forEach` 2 例）�?
- 对外行为变化：门铃长连接�?15s（默认）收到一�?`keepalive`；`refresh-heartbeat=true` 时建连会顺带刷一�?`last_seen_at`（默认关，不改变现有在线判定行为）�?

#### 4. 遗留

- 多实例实时性（Redis Pub/Sub fanout）为 §12 演进项，单实例部署下无需，暂不做�?

#### 5. 验证回执

- `mvn -pl helloai-core -am test -Dtest=DoorbellRegistryTest,DoorbellServiceTest,DoorbellRingerTest,DoorbellDutyListenerTest,DoorbellKeepaliveTaskTest,AgentInboxServiceTest "-Dsurefire.failIfNoSpecifiedTests=false"` �?**33 例全�?BUILD SUCCESS**（Registry 7 + Service 12 + Ringer 4 + DutyListener 4 + Keepalive 4 + Inbox 2）�?
- `mvn -pl helloai-start -am install "-Dmaven.test.skip=true"` �?**�?reactor MAIN 编译 BUILD SUCCESS**�?
- PowerShell 注意：`DoorbellProperties.refreshHeartbeat` �?helloai-common，跨模块新增字段必须 `-am` �?common 在同一 reactor 重编——首次漏 `-am` 用陈�?`.m2` common jar �?`NoSuchMethodError: isRefreshHeartbeat()`，补 `-am` 后转绿；`-am` 连带 common 跑测试无匹配需 `-Dsurefire.failIfNoSpecifiedTests=false`�?

---

### 2026-07-17 外部 Agent 一键接入补全：checkIn/checkOut 纳入默认授权 + executor SKILL 升级为全�?MCP 说明�?

#### 1. 范围

- 承接“外部第三方 AI Agent 接入 HelloAI 调度平台”的分步端到端验证（�?1 步一键注册已实测 PASS=9），推进 **�?2 步：注册 �?打卡（checkIn）→ 门铃 SSE 长连�?*�?
- 定位到的接缝断层：门铃建连闸�?`isOnDuty` 逻辑正确，但自助注册�?EXECUTOR �?`checkIn`/`checkOut` **不在 `DEFAULT_EXECUTOR_TOOLS`** 默认授权清单 �?打不了卡 �?建不起门铃长连接。此为“平台没把工具给全”的产品缺口，非外部 AI 使用问题�?
- 用户口径（路 A：修产品）：一键注册的本意是交付外�?AI 使用平台�?*完整说明�?+ 全套 MCP 工具**；用哪些/何时用是外部 AI 的事，但“没给全”是平台责任。据此：�?修默认授权；�?补全一键生成的 SKILL 说明书（打卡接口 + 如何操作 + 全套 MCP 工具）；�?补真实路径验证脚本�?
- 明确不做（本轮）：不�?Flyway/表结构（靠既有懒启用机制覆盖存量 Agent）；不动门铃闸门逻辑；不扩展 v3/v4/v5（后续步骤）�?

#### 2. 实际落地

- **�?checkIn/checkOut 纳入默认授权（`AgentMcpServerService.DEFAULT_EXECUTOR_TOOLS`�?*
  - 清单�?8 �?10：`pullTasks, ack, claimSubTask, heartbeat, uploadArtifact, submitResult, reportBlocked, getAgentStatus` 追加 `checkIn, checkOut`�?
  - **一处改动全覆盖，无需 Flyway**：`isToolEnabled(agentId, toolName)` �?`DEFAULT_EXECUTOR_TOOLS` 内的工具�?*懒启�?*逻辑——`config == null && DEFAULT_EXECUTOR_TOOLS.contains(toolName)` 时自�?insert 启用行。因此新注册 Agent �?`enableDefaultsForAgent` �?10 行；**存量 Agent 首次�?`checkIn` 时被 `isToolEnabled` 懒启用补授权**。类注释�?`enableDefaultsForAgent` 方法注释由�?/8 工具”统一为�?0 工具”�?

- **�?executor SKILL.md 升级为完整说明书（`helloai-core/.../resources/skills/executor/SKILL.md`�?*
  - 原版全是 REST curl、只字未�?MCP——正是“没给全”。重写为完整接入手册（约 183 行），占位符 `<注册后填�?`/`{{BASE_URL}}`/`{{AGENT_NAME}}`/`<你的ID>` 保持不变（由 `PromptTemplateService` 注册时替换）�?
  - 新结构：认证信息 �?两种接入方式对比（MCP 推荐 / REST 兜底）→ **一、MCP 接入**�?.1 连接配置 `/mcp/sse` + `/mcp/messages?sessionId=` + Bearer�?.2 全套 **10 �?MCP 工具**表含“何时使用”；1.3 推荐工作循环：`getAgentStatus �?checkIn �?建门�?�?�?inbox 信号 �?pullTasks �?claimSubTask �?执行 �?uploadArtifact �?submitResult �?ack �?checkOut`）→ **二、门铃长连接**�?.1 `curl -N .../doorbell/sse`，前置须 `checkIn` 否则 HTTP 500�?.2 信号类型 `connected/inbox/keepalive`�?.3 保活与重连）�?**三、REST API 参�?*（保留收件箱/规则/子任�?审查/积分/日志兜底）→ 注意事项�?

- **�?真实路径验证脚本 `scripts/powershell/verify-onboarding-doorbell.ps1`（新增）**
  - �?`verify-doorbell-e2e.ps1`（S2 直接 DB INSERT 一�?ACTIVE 租约）互补——本脚本证明**外部 AI 用自�?apiKey 通过 MCP 真能打卡**：S0 `POST /api/agents/register` �?apiKey+agentId；S1 未打卡建门铃应拒（HTTP 500 + `code=500`）；S2 MCP 握手（`/mcp/sse` �?sessionId �?initialize �?notifications/initialized �?`tools/call checkIn`，Bearer）读 SSE 流断言�?`leaseId`/`ok:true`；S3 打卡后建门铃断言 HTTP 200 + `event:connected`�?
  - 遵循规则 6：UTF-8 编码�?+ runtime 字面量纯 ASCII + CJK 只在注释；交付前踩到 line 148 **单引号内 CJK `'工具未启�?` 触发 PS 5.1 解析器提前闭合字符串**（规�?6 同类坑，此前仅记录双引号，本轮确认单引号亦然），改为�?ASCII 正向断言�?`Parser.ParseFile` �?PARSE-OK�?

#### 3. 影响

- 对外行为变化：外�?AI 一键注册即�?*全套 10 �?MCP 工具**（含值班打卡）与�?MCP/打卡/门铃说明的完�?SKILL；自助注册后可直�?`checkIn` 上岗并建立门铃长连接（此前会�?tool-authz 拦截）�?
- 代码变化�?
  - `helloai-core/.../service/AgentMcpServerService.java`：`DEFAULT_EXECUTOR_TOOLS` 8�?0�?`checkIn`/`checkOut`�? �?方法注释同步�?
  - `helloai-core/.../resources/skills/executor/SKILL.md`：重写为�?MCP 全套工具 + 打卡 + 门铃的完整说明书�?
  - `scripts/powershell/verify-onboarding-doorbell.ps1`（新增，�?2 步真实路�?E2E）�?
  - `doc/HelloAI_实现差距�?md` + `doc/log/HelloAI_迭代执行记录.md`（本轮回填）�?
- 数据结构变化：无（靠 `isToolEnabled` 懒启用覆盖存�?Agent，不新增 Flyway）�?

#### 4. 遗留

- `verify-onboarding-doorbell.ps1` �?PARSE-OK�?*真实环境 E2E 已实�?ALL PASSED**�?026-07-17，本项遗留关闭）�?
- �?2 步之后的 v3（门铃推 inbox �?外部 AI �?MCP pullTasks 取任务）、v4（连接不中断 + 双心跳保活）、v5（submitResult 反馈闭环）为后续步骤，本轮未触及�?

#### 5. 验证回执

- `mvn -pl helloai-core -am compile -DskipTests` �?**BUILD SUCCESS**（`DEFAULT_EXECUTOR_TOOLS` 改动编译通过）�?
- `verify-onboarding-doorbell.ps1` �?`[System.Management.Automation.Language.Parser]::ParseFile` 自检 �?**PARSE-OK**（修掉单引号 CJK 提前闭合后）�?
- **2026-07-17 真实环境实跑 `verify-onboarding-doorbell.ps1` �?ALL PASSED（PASS=7 FAIL=0，agentId 2078004629359747074�?*：`S0` 自助注册�?apiKey `ak_5c25e8d31...`；`S1` 未打卡建门铃 HTTP 500 `{"code":500,"msg":"Agent 未在岗…”}`；`S2` MCP `tools/call checkIn` HTTP 200 �?ACTIVE 租约建立�?*仅靠默认授权修复、未 seed DB**）；`S3` 打卡后建门铃 `HTTP/1.1 200` + `event:connected` + `data:{"type":"connected",...}`。至此第 2 步（注册→MCP checkIn→门�?connected）真实路径闭环�?

---

### 2026-07-17 外部 Agent 接入�?3 步：门铃 inbox 唤醒 �?MCP pullTasks 取任务（闭环实测通过�?

#### 1. 范围

- 承接�?2 步（注册→checkIn→门�?connected），推进 **�?3 步：门铃�?inbox 信号 �?外部 AI �?MCP pullTasks 取任�?*�?
- 本步�?*纯验�?*，无产品代码改动：触发链路（`AgentInboxService.send` �?`InboxMessageCreatedEvent` �?`DoorbellRinger` �?门铃 ring）、pullTasks、子任务分配均已实现�?

#### 2. 实际落地

- **新增验证脚本 `scripts/powershell/verify-onboarding-pull.ps1`**：S0 注册 �?S1 MCP `checkIn` 上岗 �?S2 建门�?SSE（保持）�?`connected` �?S3 `POST /api/tasks` + `POST /api/sub-tasks{assignedAgent}` 造一�?ASSIGNED 子任务（真实 inbox �?`sub_task.assigned`）→ S4 断言门铃流出�?`event:inbox`（`type=inbox`、`eventType=sub_task.assigned`）→ S5 MCP `pullTasks` 断言返回含该 `sub_task.assigned` �?`subTaskId` 匹配�?
- **关键设计**：inbox 必须�?service 层（`AgentInboxService.send`）才会发事件、才会响铃；DB 直插不触发——故脚本用真�?REST �?task+sub_task 驱动。executor apiKey 可通过 AuthInterceptor �?`/api/tasks`、`/api/sub-tasks`、MCP `pullTasks`（不区分角色）�?
- **脚本断言正则修正**：pullTasks 结果�?SSE 帧里是嵌�?*转义 JSON**（`\"subTaskId\":<id>`），首版正则 `"subTaskId"\s*:` 被字段名前的转义反斜杠卡�?�?改为容错字符�?`subTaskId[\\":\s]*` 兼容转义/非转义两种形态�?

#### 3. 影响

- 无产品代�?数据结构变动；仅新增一个验证脚�?+ 本轮文档回填�?

#### 4. 遗留

- 日志�?`sub_task.assigned` 消息 title 显示为乱码（`鬂颅件鹔″凡鈙嚇鎄`=“新任务已分配”）——为 curl 落盘 SSE 文件�?UTF-8/GBK 显示错位�?*仅日志观�?*，不影响断言（断言只匹�?ASCII �?`sub_task.assigned` 与数�?ID）�?
- v4（连接不中断 + 双心跳保活）、v5（submitResult 反馈闭环）为后续步骤�?

#### 5. 验证回执

- **2026-07-17 真实环境实跑 `verify-onboarding-pull.ps1` �?ALL PASSED（PASS=12 FAIL=0，agentId 2078007902414237698�?*：S4 门铃�?`event:inbox` `{"type":"inbox","eventType":"sub_task.assigned","refType":"sub_task","refId":"2078007941622591490"}`；S5 MCP pullTasks 返回 `{"messageId":"inbox-...","type":"sub_task.assigned","subTaskId":2078007941622591490,"taskId":2078007941509345281,...}`。门�?`refId` = pullTasks `subTaskId` = S3 创建 ID 三处一致，“响铃唤醒→拉取正文”契约闭环�?
- 附带观察：同一门铃流还抓到一�?`event:keepalive`�?4:44:34），提前印证 PR-4 `DoorbellKeepaliveTask` 保活帧在真实环境生效�?
- `verify-onboarding-pull.ps1` �?`Parser.ParseFile` 自检 �?**PARSE-OK**�?

---

### 2026-07-17 外部 Agent 接入�?4 步：连接不中�?+ 双心跳刷在线（实测通过�?

#### 1. 范围

- 承接�?3 步，推进 **�?4 步：连接不中�?+ 双心跳保�?*。“双心跳”指两条方向：方�?A（server→client）由 `DoorbellKeepaliveTask` �?`keepalive-interval-ms`（默�?15s）向活跃连接广播 `event:keepalive` 穿透反代空闲超时；方向 B（client→server）由 Agent �?MCP `heartbeat` �?`HeartbeatService.seen` �?`last_seen_at` + Redis TTL 并重算在线态�?
- 本步�?*纯验�?*，无产品代码改动（`DoorbellKeepaliveTask`、`heartbeat`、`getAgentStatus` 均已�?PR-4 / v2.4 阶段交付）�?

#### 2. 实际落地

- **新增验证脚本 `scripts/powershell/verify-onboarding-heartbeat.ps1`**：S0 注册 �?S1 MCP `checkIn` 上岗 �?S2 建门�?SSE（后台保持）�?`connected` �?S3 保持门铃 ~20s 跨一个保活周期，断言收到 `event:keepalive` 且后台连�?job �?`Running`（连接未被切断）�?S4 REST `POST /api/mcp/tools/heartbeat` 断言 `ok:true` + agentId 匹配 �?S5 MCP `getAgentStatus` 断言 `computedOnlineStatus`∈{ONLINE,IDLE} �?`lastSeenAt` 已刷新�?
- **关键设计**：S3 用后�?job �?`Running` 状态直接作为“连接未被服务端切断”的证据�?5s 保活周期内若长连接会断，20s 窗口内必现形）；heartbeat 走同�?REST（好断言），getAgentStatus REST 未暴露故�?MCP SSE 通道�?

#### 3. 影响

- 无产品代�?数据结构变动；仅新增一个验证脚�?+ 本轮文档回填�?

#### 4. 遗留

- v5（完成任�?+ submitResult 反馈闭环）为最后一步�?

#### 5. 验证回执

- **2026-07-17 真实环境实跑 `verify-onboarding-heartbeat.ps1` �?ALL PASSED（PASS=12 FAIL=0，agentId 2078010246900150274�?*：S3 `event:keepalive`@14:54:04 距建�?14:53:51 �?13s（吻�?15s 周期�? job `Running`；S4 heartbeat REST `{"ok":true,"agentId":"2078010246900150274",...}`；S5 getAgentStatus `computedOnlineStatus=IDLE`（`lastActiveAt=null` 未执行任务→按三态判定就�?IDLE）、`lastSeenAt=2026-07-17T06:54:14Z` �?heartbeat 时刻一致（心跳确实刷新�?last_seen_at）�?
- `verify-onboarding-heartbeat.ps1` �?`Parser.ParseFile` 自检 �?**PARSE-OK**�?

---

  ## 6. 下一步方案：N12 P1 剩余三项（待用户拍板�?
  
  本节�?2026-07-16 A 档收尾后双文档同步记录使用，仅作方案池与工作量参考，不包含代码落地。决定启动哪个方案后请在本节下追加�?## 已拍板：方案 X”子节，再据此拉新迭代轮次�?
  
  ### 6.1 背景
  
  A 档收尾（2026-07-16）已交付 N12 �?P0（值班租约闭环 + 值班优先调度）与 P1（只读报�?+ dashboard 前端接入 + R2 �?Publisher 清理 + R3 V22 backfill）。剩余三�?P1 能力尚未动：
  
  | �?| 字段已存�?| 语义未实�?| 触达模块 |
  |---|---|---|---|
  | STRICT 独占报锁 | `agent_duty_lease.work_mode` | Selector 未按 STRICT 拒绝非专属任�?/ 独占期间不接受其它任�?| `AgentSelector.pickAlternative` |
  | concurrency 预扣 | `agent_duty_lease.max_concurrent` | Selector 未读 `max_concurrent`，未维护 `sub_task` slot 引用计数 | `sub_task` 状态机 + slot 计数�?+ Redis SETNX �?+ Selector + Job |
  | 动�?TTL 自适应 | `agent_duty_lease.ttl_minutes` | startLease 与续约都是硬编码 TTL，未根据 `agent.score` / `consecutive_failure_count` 动态调�?| `DutyLeaseExpirationTask` + `AgentDutyLeaseService.startLease / heartbeat` |
  
  ### 6.2 五方案对�?
  
  | 方案 | 内容 | 总估�?| 风险 | 推荐�?| 适用场景 |
  |---|---|---|---|---|---|
  | **A1** | 仅做 STRICT 独占报锁 | 0.5�?h | �?| ⭐⭐�?试水 | 想知�?N12 后续怎么“调档”，先做个轻的压压轴 |
  | **A2** | STRICT �?动�?TTL �?concurrency 三项顺序从轻到重 | 5�?h（分 3 段） | 低→中→�?渐进可控 | ⭐⭐⭐⭐�?| 期望分项交付，每项独�?commit + verify + 文档回填 |
  | **A3** | 仅做 concurrency 预扣（价值最高） | 3�?h | �?| ⭐⭐ | 上来啃最难的骨，头铁专用 |
  | **A4** | 三项一次性串行做完（合并一�?round�?| 5�?h 一�?| �?| ⭐⭐ | 跨度大，不建议作为单一轮次 |
  | **A5** | A1 + Agent 管理页文案轻改（`ACTIVE/DISABLED` �?“在�?离岗”） | 1.5h | �?| ⭐⭐⭐⭐ | 兼顾 UI 概念混淆�?N12 后续，工作面最�?|
  
  ### 6.3 单项细节
  
  #### 6.3.1 STRICT 独占报锁（轻�?
  
  - `AgentSelector.pickAlternative`：当存在任一 ACTIVE �?`work_mode=STRICT` �?lease，若任务不匹配该 Agent 专业域则跳过；STRICT 期间仅专属任务可被派发到�?Agent�?
  - `McpMcpServer.checkIn` 已收 tool 入口，无需新增�?
  - 单测 1�? 个用例覆盖：STRICT Agent 接到非专属任务时不入候选；专属任务可正常派发�?
  - 验证脚本沿用 `verify-agenthub-duty-e2e.ps1` �?S6 STRICT 子场景（不新增脚本）�?
  
  #### 6.3.2 动�?TTL 自适应（中�?
  
  - 指标：优先读 `agent.score`（如已有）或 `consecutive_failure_count`；低表现 Agent 缩短 TTL�?min）以便快速回收，高表�?Agent 拉长 TTL�?�?h）减少续约开销�?
  - `AgentDutyLeaseService.startLease`：TTL 入参可空，为空时�?`Agent.score` 计算默认值�?
  - `heartbeat`/`DutyLeaseExpirationTask` 续约路径调用 `adaptiveRenew(now)`，按上次成功时间拉长或缩短�?
  - 新增 V24 `agent_duty_lease_renewal_policy` 表（`agent_id`, `consecutive_failure_count`, `recent_success_rate`, `last_score`, `effective_ttl_minutes`）作为策略落地处�?
  - 单测 2�? 个用例覆盖：低分 Agent TTL 缩短；高�?Agent TTL 延长；连续失败重�?TTL�?
  - 新增 `verify-dashboard-duty-leases.ps1` 子场�?S7 抽查续约 TTL 区间�?
  
  #### 6.3.3 concurrency 预扣（重�?
  
  - 新增 `agent_slot_inuse` 物化表（或用 `sub_task WHERE status IN (ASSIGNED, IN_PROGRESS, REVIEW, REWORK)` 实时 GROUP BY）�?
  - `AgentSelector.pickAlternative`：排�?`inuse >= max_concurrent` �?Agent，保留按 `dutyRank` 排序的语义�?
  - `sub_task` 状态机：在 `ASSIGNED �?IN_PROGRESS �?REVIEW/DONE/REWORK/CANCELLED` 转换时维�?slot 引用计数（ASSIGNED +1，DONE/CANCELLED -1）�?
  - Redis SETNX 三段式：`acquireSlot(agentId)`（预扣）/ 真扣（事务内提交�? `releaseSlot(agentId)`（归还）；任一异常路径都需要正确归还�?
  - 跨进程锁避坑：slot 计数�?Redis 主键，DB 写入�?`uk_duty_lease_agent_active` �?partial unique index 防重�?
  - 单测 3�? 个用例：预扣冲突降级、跨进程释放一致性、ABORTED/FAILED 归还、最大并发上限生效�?
  - 新增 `verify-agenthub-duty-e2e.ps1` 子场�?S7 concurrency（多 sub_task 打到同一 Agent 时不超过 max_concurrent）�?
  - 文档同步：差距表 N12 行从“保持现状”改为“部分交�?/ A2/A3 子项进行中”�?
  
  ### 6.4 推荐路径
  
  - **首�?A2**：从轻到重，渐进可控�? �?atomic round，每项独�?commit + verify + 文档回填�?
  - **次�?A5**：若想先消化“Agent 管理页面与值班租约页面 ACTIVE 同名�?的概念混淆，同步�?UI 轻改�?
  - **不推�?A4**：跨度大，单一轮次风险不可控�?
  
  ### 6.5 待用户拍�?
  
  - [x] **2026-07-17 用户拍板 A2**（从轻到重三项顺序分 3 �?atomic round），本节同步补充 A2 �?1 �?STRICT 独占报锁 交付记录�?§6.6
  - [ ] A2 �?2 段（动�?TTL 自适应）启动时�?
  - [ ] A2 �?3 段（concurrency 预扣）启动时�?
  - [ ] 选完后回写本节�?## 已拍板：方案 X�?并在差距�?§5 优先级建�?/ N12 处理建议列同步状�?
  
  ---
  
  ### 6.6 已拍板：方案 A2 �?1 段（STRICT 独占报锁）�?2026-07-17 交付
  
  #### 1. 范围
  
  按用户拍板的 A2 路径推进本轮 3 �?atomic round 中的�?1 段，语义收口为：**STRICT Agent 只接自己被初始指派的任务，不参与别人失败后的 pickAlternative 替补池抢派别人失败的任务**。本轮明确不做：A2 �?2 段（动�?TTL 自适应）、A2 �?3 段（concurrency 预扣）、按“任务域”识别专属（当前业务模型�?conversationId/sessionId/groupId，Agent 端有 specialization_slug/capabilities/labels 字段支撑�?SubTask 端无“所需域”字段，留待后续轮次）�?
  
  #### 2. 实际落地
  
  - **枚举基座**——新�?`helloai-common/.../constant/WorkMode.java`：`AUTO` / `STRICT` 两值，**双解析策�?*�?
    - `lenientParse(String raw)`：DB 读取宽容——`null` / 空串 / 未知值→返回 `AUTO`（不抛异常，避免脏数据让运行崩）
    - `strictParse(String raw)`：MCP 入参严格——非法值抛 `IllegalArgumentException`（调用方 `McpToolService.checkIn` 改包�?`BizException` 拒绝，不静默降级�?AUTO�?
  - **Selector 过滤**——`AgentSelector.pickFromCandidates` 在原 `ACTIVE` 过滤之后、熔断检查之前加一�?`.filter(a -> !isOnStrictDuty(a.getId()))`；新增私有方�?`isOnStrictDuty(Long agentId)`，读 `agentDutyLeaseService.getActiveLease(agentId)` + `WorkMode.lenientParse(lease.getWorkMode()) == STRICT` 判定�?*查询异常回退 false**（不因租约查询偶发抖动误退�?
  - **入参校验**——`McpToolService.checkIn` �?`mode = WorkMode.strictParse(workMode)`，`catch (IllegalArgumentException e) { throw new BizException(e.getMessage()); }`；落库用 `mode.name()` 字符串保证与枚举名完全一�?
  - **单测**——`AgentSelectorTest` 新增 `StrictDutyFiltering` 分组 5 个用例（`shouldSkipStrictOnDutyAgent` / `shouldReturnNullWhenAllCandidatesStrict` / `shouldTreatNoLeaseAgentAsAuto` / `shouldLenientParseDirtyWorkMode` / `shouldFallbackWhenLeaseQueryThrows`），全量 19/19 全绿
  - **E2E 脚本**——`verify-agenthub-duty-e2e.ps1` NORMAL→AUTO 5 处一致性修�?+ 追加 S6.1/S6.2/S6.3 三子场景（约 99 行）�?
    - **S6.1** workMode=STRICT checkIn �?DB `status=ACTIVE, work_mode=STRICT` 断言
    - **S6.2** workMode=`strict`（小写）checkIn �?DB `work_mode=STRICT`（大小写不敏感，证明 lenientParse / strictParse 都管用）
    - **S6.3** workMode=`BOGUS_VALUE` checkIn �?断言**�?*落库（BizException 拒绝、lease count 前后不变�?
  - **踩坑沉淀**——本轮在 e2e 脚本踩到两个独立 PS 5.1 坑，均已�?memory�?
    1. **MCP `tools/call` 返回 JSON-RPC 2.0（`{jsonrpc,id,result:{content:[{type,text}]}}`），不是平台 `{code,msg,data}` 业务包装**——断言必须�?HTTP 200 + DB 状态，**不能** `ConvertFrom-Json` 后直接拿 `$body.code`
    2. **PS 5.1 函数 `return $arr`（单元素数组）会�?unroll �?`System.String`**——调用方 `$arr[0]` 取到首字符而非首元素。修复：函数改为返回**�?string**，调用方拿到 string 后用 `.Split('|')` �?String[]�?NET String.Split �?PS 脚本层调用不 unroll）。同时捎带把所�?Write-Error / Write-Output 字符串按规则 6 改成“单引号 + `+` 拼接，runtime 字面量纯 ASCII、中文只留注释�?
  
  #### 3. 影响
  
  - **对外行为变化**：`AgentSelector.pickAlternative` 调起时，候选列表里 ACTIVE 租约 `work_mode=STRICT` �?Agent 会被过滤——它们不再抢派别人失败的任务；`checkIn` 入参非法值直�?BizException 拒绝（不会静默降级为 AUTO 让值班表里偷偷�?AUTO 模式�?
  - **配置变化**：`agent_duty_lease.work_mode` 字段已存在（`V1__init_all.sql` AgentHub V1 T3 建表），�?schema 变化；MCP `tools/call` 客户端可�?`checkIn` 入参中传 `"workMode":"STRICT"` 显式开启严格模式（缺省 `AUTO`�?
  - **代码变化**：`WorkMode.java`（新�?71 行）；`AgentSelector.java` import + 一�?`.filter` + 19 �?`isOnStrictDuty`；`McpToolService.java` import + 8 行入参校验；`AgentSelectorTest.java` 115 行新�?+ 6 �?helper；`verify-agenthub-duty-e2e.ps1` 5 �?NORMAL→AUTO + 99 �?S6 子场�?+ 函数 return 改单 string + 7 �?Write-Error/Write-Output 按规�?6 重写
  
  #### 4. 遗留
  
  - A2 �?2 段（动�?TTL 自适应）未启动
  - A2 �?3 段（concurrency 预扣）未启动
  - “专属任务”按域匹配未实现（业务模型无 conversationId 概念、SubTask 端无“所需域”字段），但已通过 §6.6 �?1 段范围说明明确口径——STRICT 退出替补池 = 不接替补；如后续要按域专属再开一�?
  
  #### 5. 验证回执
  
  - **`mvn -pl helloai-core -am compile`** BUILD SUCCESS
  - **`mvn -pl helloai-core -am test -Dtest=AgentSelectorTest`** 19/19 全绿（含 5 个新 STRICT 用例�?
  - **`scripts/powershell/verify-agenthub-duty-e2e.ps1` 真实环境实测 ALL PASSED**（S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / **S6 N12-P1 STRICT 三子场景**）：
    - S6.1 workMode=STRICT �?DB status=ACTIVE, work_mode=STRICT �?
    - S6.2 workMode=`strict` 小写 �?DB work_mode=STRICT（大小写不敏感）�?
    - S6.3 workMode=BOGUS_VALUE �?BizException 拒绝，lease count 不增（仍�?N）✓
  - 脚本�?`Parser.ParseFile` 自检 **PARSE-OK**

---

### 6.7 UI：AgentOnboardingDialog 接入弹窗按钮换位�?026-07-17�?

UI 行为变更：`helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue` �?复制 SKILL + 切换视图"两个 AI 视角按钮替换为：

- ⬇️ **下载 hello_ai_skills.md**（文件名方案 C：`hello_ai_<agentName>.md`，中�?agent 名降级为下划线，跨平台兼容）
- 🚀 **一键上班口�?*（动态拼�?`你是 HelloAI 平台�?<agentName>（ID=<agentId>），请按平台 SKILL 接入并开始工作。`�?

顺手删除 `showSkillOnly` ref + `copySkill` + `toggleView`（功能由下载按钮接管）。commit `65161ba`�?

> 说明：同 commit �?`skills/executor/SKILL.md` 按“平台外�?Agent 接入文档”域分类，不进本迭代记录；本节仅回填项目开发侧 UI 改动�?

### 2026-07 Controller 分层红线收口（�?.3 + 3.x 包归位）

#### 1. 范围

�?`doc/HelloAI_CODE_STYLE.md` §6.3 �?1 条「禁止注�?Mapper」与�?2 条「禁止书�?SQL/QueryWrapper 条件」强制收�?6 个历史违�?Controller；同步完成两项包归位：`com.helloai.config` 2 个类并入 `com.helloai.start.config`、`helloai-start/.../chat/DeepSeekProviderChatClientFactory` 移至 `helloai-core/.../core/agent/chat/provider`；Code Style §6.3 待收口清单与 3.x start 配置类待收口段落同步收口。本轮完成后提交一�?commit�?

#### 2. 实际落地

##### 2.1 6 �?Controller Mapper 收口（`helloai-api/.../controller/`�?

| Controller | �?Mapper 注入 | 改后依赖 Service | 下移查询方法 |
|---|---|---|---|
| ActivityController | ActivityLogMapper | ActivityLogService | `list(page,pageSize,level,source,subTaskId)` / `record(...)` |
| AdminDashboardController | TaskMapper/SubTaskMapper/AgentMapper/SysUserService/AgentService | AdminDashboardService | `getOverview()` / `listBlockedHighlight()` / `listReviewHighlight()` / `listLowActivityAgents()` / `getTrends(days)` |
| AgentDutyLeaseController | AgentMapper | AgentDutyLeaseService | 复用现有 `getAgentNamesByIds(...)` 去掉�?nameCache N+1 |
| AttachmentController | AttachmentMapper | AttachmentService | `list(subTaskId)` / `getByIdRequired(id)` / `getStorageUrlRequired(id)` |
| DashboardController | TaskMapper/SubTaskMapper/AgentMapper | DashboardService | `getStats()` |
| FeedController | ActivityLogMapper/AgentMapper | FeedService | `listActivityLogs(...)` / `resolveAgentNames(logs)` / `listAgentSummaries()` |

所�?Controller 现在�?Mapper 依赖；返�?DTO 装配（`ActivityLog→FeedResponse`、`Agent→AgentResponse`、`AgentDutyLease→DutyLeaseResponse`、`Map→DashboardOverview`）保留在 Controller（�?.7 原则）。`AttachmentController.getById` 错误处理�?`R.fail(...)` 改为 `BizException(404)` 统一走全局异常处理（语义等价，错误响应体不变）�?

##### 2.2 Service 调整（`helloai-core/.../`�?

- **扩展**：`ActivityLogService`（新�?`list` / `record`，事务性写入带 INFO 默认 + agent 默认 source�? `AgentDutyLeaseService`（新�?`getAgentNamesByIds`，内�?`selectBatchIds` 避免 N+1�? `AttachmentService`（新�?`list` / `getByIdRequired` / `getStorageUrlRequired`）�?
- **新建**：`AdminDashboardService`（不继承 ServiceImpl，跨 Mapper 聚合，返�?Map 避开 core→api DTO 依赖�? `DashboardService`（同样不继承 ServiceImpl�? `FeedService`（聚�?ActivityLog + Agent，复�?ActivityLogService.page）�?

##### 2.3 包归位（git mv 保留历史�?

- `helloai-start/.../config/MyBatisPlusMetaObjectHandler.java`：package `com.helloai.config` �?`com.helloai.start.config`
- `helloai-start/.../config/AdminInitializer.java`：package `com.helloai.config` �?`com.helloai.start.config`
- `helloai-start/.../start/chat/DeepSeekProviderChatClientFactory.java` �?`helloai-core/.../core/agent/chat/provider/DeepSeekProviderChatClientFactory.java`：package `com.helloai.start.chat` �?`com.helloai.core.agent.chat.provider`

##### 2.4 依赖补齐

`helloai-core/pom.xml` 新增 `spring-ai-starter-model-deepseek`（Spring AI BOM �?import，无需指定版本）——因�?`DeepSeekProviderChatClientFactory` 现位�?core，需要在 core 直接依赖 deepseek starter 才能解析 `org.springframework.ai.deepseek.*`。`helloai-start/pom.xml` 保留该依赖是透传必要（application.yml 仍声�?deepseek 字段）�?

##### 2.5 CODE_STYLE.md 文档同步

- §3.x start 模块配置类归属段落：「（待收口）」去掉，改为陈述句描述已收口事实；不再允许再出现分裂包�?
- §6.3 Controller 职责边界：「当前待收口清单 6 个」删除，替换为「✅ 收口完成」清�?+ 对应 6 �?Service 名�?

#### 3. 验证

- `mvn -DskipTests clean compile`�? 模块�?SUCCESS（HelloAI Common / MQ / Core / Job / API / Start），`Compiling 78 source files with javac [debug parameters target 17]` �?helloai-api 阶段正常通过；本次新�?改动的源文件全部编译通过，无新增警告�?
- `git status`�? Controller M + 3 Service M + 3 RM（git mv�? 3 Service 新增 + helloai-core/pom.xml M + CODE_STYLE.md M；DIFF 总计：删 Mapper 字段 6 �?/ �?selectList/selectCount/selectById/selectPage 等调�?10+ 处，新增 Service 方法调用 10+ 处�?

#### 4. 影响

- **对外行为**：完全等价。API 路径、请�?响应 schema、错误码（含 404 / 500 BizException→R.fail 映射）保持不变�?
- **架构分层**：Controller �?0 Mapper；Service 层成为对�?Controller 的唯一访问边界；�?.3 �?1 条「禁止注�?Mapper」在 6 个历史违规文件上正式生效�?
- **包结�?*：`com.helloai.config` �?`com.helloai.start.chat` 两个分裂包正式退出；新增配置类一律落 `com.helloai.start.config`，新�?ChatClient 工厂一律落 `core.agent.chat.provider`�?
- **后续约束**：任何新�?Controller 必须遵循当前模板（构造器注入 Service，不持有 Mapper）；CODE_STYLE §6.3 �?§3.x 已是终态文字，不再回退�?

#### 5. 说明

- 本轮明确不做�? �?Service 的单测补齐（独立迭代）；`ActivityLogService.record` 事务边界�?`AttachmentService.register` 现有逻辑保留原状；`AttachmentController.getById` 错误路径�?`R.fail("附件不存�?)` 改为 `BizException(404,"附件不存�?)` 是顺手统一走全局异常处理，对外响应仍�?`{code:404,msg:"附件不存�?}`，下游不受影响�?
- 提交策略：单 commit 提交本次全部改动（含 6 Controller + 6 Service + 3 包归�?+ pom + 文档）�?

---

### 2026-07-20 调度链缺陷修复（v2.6 §4.1�?

#### 1. 范围

�?`doc/design/HelloAI_调度解耦重构分�?md` v2.6 §4.1 节拍板，针对历史 commit `9e47f17` 提交前的四项调度链遗留缺陷做收口�?

- **AOP 降级未织�?*：`ResilientDispatcher` `@CircuitBreaker` 在缺 AOP starter 的环境下不触�?fallback（仅 `ResilientDispatcherTest` �?unit 验证 new 路径�?
- **心跳离线阈值不统一**：`AgentHealthCheckTask` 硬编�?`STALE_THRESHOLD_MINUTES`，与 `AgentSelector` 各自�?`heartbeatFreshMinutes` 规则漂移
- **离线重派失败后无二跳**：`AgentHealthCheckTask.reassignStaleTasks` 仅调一�?`redispatchOfflineSubTask`，抛错即放弃
- **PENDING 未指派孤儿无全局兜底**：`ExternalAgentFallbackTask` 只扫�?N11 候选，不管 PENDING + assigned_agent_id IS NULL + 有历�?record + 无活�?record 的调度链遗留

范围明确：不涉及 v3 路线图；不重�?AOP 失败语义；不�?PENDING 派发的业务编排；不替�?Reconcile 主链；外�?Agent 一键接入（M5）链路保持现状�?

#### 2. 实际落地

##### 2.1 补齐 AOP 依赖与统一心跳健康配置

- `helloai-core/pom.xml` 新增 `spring-boot-starter-aop`（让 `@CircuitBreaker`/`@Aspect` 可被 Spring 代理织入�?
- 新建 `helloai-common/.../config/AgentHealthProperties`：`prefix=helloai.agent.health`，默�?`offlineMinutes=5`（对�?Redis 心跳 TTL 30s × 10 = 5min�?
- `AgentDispatchProperties.heartbeatFreshMinutes` 字段删除（迁移注释指�?`AgentHealthProperties.offlineMinutes`），消除两套配置漂移风险

##### 2.2 统一 Selector 与回退候选心跳过�?

- `AgentSelector.isHeartbeatFresh` 改用 `AgentHealthProperties.getOfflineMinutes()`；`thresholdMinutes <= 0` 视为关闭过滤（逃生口）；API_KEY_LLM/WEB_BROWSER 始终视为新鲜（架�?§3.8 三层可用性）
- `AgentMapper.selectFallbackCandidates` 增加 `@Param("lastSeenCutoff") OffsetDateTime lastSeenCutoff`，SQL 增加 `last_seen_time IS NOT NULL AND last_seen_time > #{lastSeenCutoff}`；与 Java �?`AgentSelector` 共用同一阈�?
- `ExternalAgentFailureTracker.shouldFallback` 同步加心跳检查（`offlineMinutes <= 0` �?bypass，包�?null last_seen_time 也视为可回退�?
- `AgentHealthCheckTask` 删除硬编�?`STALE_THRESHOLD_MINUTES`，改�?`healthProperties.getOfflineMinutes()`

##### 2.3 修复离线重派�?PENDING 遗留兜底

- `AgentHealthCheckTask.reassignStaleTasks` 重构为按 Agent 维度调用�?
  - 首选路径：`subTaskDispatchService.redispatchOfflineSubTask(task.id, agentId)`（弹�?fallback 触发�?
  - 二次路径：首选失败后�?`subTaskDispatchService.dispatchPendingSubTaskAuto(task.id, fallbackRole)`，role 用原 Agent �?`agent.role`，缺失时回退 `AgentRole.EXECUTOR`
  - 统计三档：`reassignedByFallback` / `reassignedByAuto` / `failed`
- `SubTaskMapper` 新增 `selectPendingUnassignedWithoutActiveExecutionRecord(int limit)`：筛 PENDING + assigned_agent_id IS NULL + EXISTS 历史 record + NOT EXISTS 活跃 PENDING/RUNNING record
- `ExternalAgentFallbackTask.scan()` 拆分为两个独立阶段：
  - 阶段 A：`failureTracker.findFallbackCandidates()` -> `processCandidate`（N11 阈值回退�?
  - 阶段 B：`recoverPendingUnassigned()` 全局 PENDING 兜底（每次扫描独立一次，避免阶段 A 失败时不执行�?

##### 2.4 补齐核心与任务调度回归测�?

- 新增 `ResilientDispatcherAopIntegrationTest`（Spring Boot 集成测试，`@SpringBootConfiguration + @EnableAspectJAutoProxy + @ImportAutoConfiguration({AopAutoConfiguration, CircuitBreakerAutoConfiguration})`），3 个测试验证：Bean �?AOP 代理 / OFFLINE CLI_CLIENT 触发 fallback / `Advised.getTargetClass()` 暴露原类
- 新增 `AgentHealthCheckTaskTest`�?1 个测试，3 �?Nested：Precondition / ReassignStaleTasks / OfflineCasGuard），通过反射调用 `reassignStaleTasks(Agent)`：首选成�?/ 首选失�?>二次成功 / 双层失败 / OFFLINE CAS 返回 0 / Redis TTL 仍在 / `offlineMinutes <= 0` 禁用
- `ExternalAgentFailureTrackerTest` 增心跳相关测试（null last_seen / 新鲜/过期/边界 / `offlineMinutes <= 0` 旁路），遗留 2 参数 `shouldDelegateToMapper` 升级�?3 参数版本
- `ExternalAgentFallbackTaskTest` �?7 �?PENDING 兜底测试：阶�?A 无候选仍执行阶段 B / 状态变化跳�?/ 删除跳过 / 已分配跳�?/ 单条失败不中�?/ 不污�?N11 计数 / N11 成功时仍�?PENDING 兜底
- `AgentSelectorTest` �?`v2.6 心跳新鲜度过滤` Nested�? 用例）：默认 5min 边界 / 15min 过期 / 4min 新鲜 / API_KEY_LLM null 豁免 / 多候�?fresher 战胜 stale / `offlineMinutes=0` 关闭过滤 / `offlineMinutes=3` 自定义阈�?
- 顺手修复 pre-existing：`helloai-core/src/test/java/com/helloai/core/doorbell/` �?5 �?Doorbell 测试文件（`DoorbellRegistryTest` / `DoorbellServiceTest` / `DoorbellDutyListenerTest` / `DoorbellKeepaliveTaskTest` / `DoorbellRingerTest`）package 声明�?`com.helloai.core.shared.doorbell` 但放在错误目录下，迁移到正确目录后全部通过

#### 3. 验证

- `mvn -pl helloai-common install -DskipTests` SUCCESS
- `mvn -pl helloai-core test -Dtest='AgentSelectorTest,ExternalAgentFailureTrackerTest,ResilientDispatcherTest,ResilientDispatcherAopIntegrationTest'` **58/58 全绿**
- `mvn -pl helloai-job test -Dtest='AgentHealthCheckTaskTest,ExternalAgentFallbackTaskTest,SubTaskPendingOrphanTaskTest'` **38/38 全绿**
- `mvn -pl helloai-core install -DskipTests` SUCCESS
- `mvn test`（reactor 全量�?*BUILD SUCCESS**：HelloAI Common / MQ / Core / Job / API / Start 7 模块�?SUCCESS，helloai-core 216 个测试全绿（含已修复�?5 �?Doorbell 测试�?
- `mvn -pl helloai-start -am package -DskipTests` SUCCESS，`helloai-start/target/helloai-start-1.0.0-SNAPSHOT.jar` 62MB 产物可构建（沙箱�?PostgreSQL/Redis，真实链路断心跳验收需外部环境执行�?

#### 4. 影响

- **架构影响**：心跳阈值唯一源（`helloai.agent.health.offline-minutes`，默�?5min），消除 Java/SQL 规则漂移；AOP starter 上车�?`@CircuitBreaker`/`@Aspect` 注解可织�?
- **调度影响**：离线重派二次路径就绪，�?Agent 失败时按角色 EXECUTOR 回退二次选人；PENDING 未指派孤儿全局兜底（阶�?B），不会卡在历史 record + 无活�?record 的调度链遗留
- **测试影响**：AgentSelectorTest 19->26、ExternalAgentFailureTrackerTest 11->22、ExternalAgentFallbackTaskTest 8->15、AgentHealthCheckTaskTest 0->11、新�?ResilientDispatcherAopIntegrationTest 3；覆盖率从“单元验�?new 路径”提升到“真�?Spring 上下文织�?+ fallback 触发�?
- **对外行为**：API 路径、配�?key 兼容（`AgentDispatchProperties.heartbeatFreshMinutes` 删除不影响线上，因为从未�?application.yml 引用）；幂等守卫（OFFLINE CAS `IS DISTINCT FROM`）维持现�?
- **文档影响**：差距表 N7 / N11 项更新“二次选人加固 + 5min 健康阈值统一 + PENDING 兜底”子条目

#### 5. 遗留与下一�?

- 真实断心跳链路验收（启动 Spring Boot + PostgreSQL/Redis + 创建 CLI_CLIENT Agent + 等待 5 分钟超时）需在外部环境执行；沙箱内只能验�?jar 集成构建与单�?集成测试
- `OfflineAgentAutoRedispatchProperties`（如需�?offlineMinutes 提升�?per-Agent 配置）暂未抽取，本轮统一为全局默认 5min 即可覆盖 N11/N12/N7 三处使用�?
- `verify-subtask-redispatch-auto-execution.ps1` �?`-Scenario offline` 路径�?80s 超时）已可跑；本轮未在沙箱内联跑（无 DB/Redis），但脚本本身保持现�?

---

### 6.8 EXECUTOR 端到端实时性修复：SKILL §1.5 常驻值班协议 + 参�?daemon + UI 下载入口�?026-07-20�?

#### 1. 范围

针对 qoder-ceshi（EXECUTOR 外部 Agent）被调度后“打卡就走”的伪在线模式——`checkIn` 到后只跑 8 秒探针就退出，导致 22 秒认领窗口被误认为已错过、平台動辄走重派路径——推�?Agent 侧向“真常驻值班”转型，本轮重点修改�?

- **A 类（必做，本轮完成）**�?
  - `executor/SKILL.md` §1.5 新增《常驻值班协议（必读·关键）》，明确“checkIn 拿到 ACTIVE 后必须立刻拉起常驻后台进程”跳出致命前�?
  - 同文�?§1.3 推荐工作循环改为“拉起常驻值班进程”替代旧“建立门铃长连接 + 周期�?heartbeat”描�?
  - 新增 `scripts/powershell/qoder-ceshi-daemon.ps1`（PowerShell 5.1 兼容）作为参考实现骨�?
- **B 类（建议�?commit�?*�?
  - `AgentOnboardingDialog.vue` 增按钮“下载常驻值班脚本（PowerShell）”（type=info），弹窗文本补充说明
  - `helloai-ui/public/scripts/powershell/qoder-ceshi-daemon.ps1` 同步拷贝为静态资源（避免后端 DTO 改动，下轮补 `daemonScript` 字段�?
- **C 类（顺后下轮�?*�?
  - 派单过滤 OFFLINE：仅派给 `onlineStatus=ONLINE` �?Agent，跳�?OFFLINE
  - inbox 状态机：重派时给原 assignee 标记 `superseded=true`，UI 区分“待 claim / 已错过认领窗口�?
  - 错误可观测性：在收件箱 UI 区分两种状态【需后端协调 + 数据迁移 + 状态机调整，以补缺口�?

#### 2. 实际落地

##### 2.1 SKILL.md §1.5 常驻值班协议（必读·关键）

文件：`helloai-core/src/main/resources/skills/executor/SKILL.md`

- **§1.5.1 关键认知**：明确门�?SSE 是真推送（server push），不是轮询；定时任务只是补丁（heartbeat/续签/兜底�?
- **§1.5.2 常驻三件�?*：门�?SSE（实时推送）+ 30s heartbeat（健康证明）+ 30s pullTasks（兜底防漏），必须同一后台进程并行
- **§1.5.3 TTL 续签节奏**：到期前 1 分钟（`renew-before-expiry-sec=60`）自�?`checkOut + checkIn + 重连门铃`，避免服务端主动�?SSE
- **§1.5.4 退出清理剧本（必须按顺序执行）**：停轮询 �?MCP `checkOut` �?kill doorbell curl �?kill /mcp/sse curl
- **§1.5.5 反模式（不要这么做）**：`checkIn �?8s 探针 �?退出`、单轮询不心跳、不重连门铃遗漏心等�?
- **§1.5.6 正模式骨�?*：Python/Kotlin/Node/Shell 参考指�?`scripts/powershell/qoder-ceshi-daemon.ps1`

此外 §1.3 的“推荐工作循环”补了一句绑合依赖：`checkIn 后拉起常驻值班进程（见 §1.5），不允许仅探针后退出`�?

预计净增：+~70行（§1.5 主体 + §1.3 工作循环微调）�?

##### 2.2 参�?daemon 脚本（PowerShell 5.1�?

文件：`scripts/powershell/qoder-ceshi-daemon.ps1`（新建）

骨干映射计划�?

- 入口：UTF-8 编码�?+ `Get-Date` BOM 剥除
- `Start-McpSse / Stop-McpSse`：`Start-Job -ScriptBlock { & curl.exe -i -N ... } | Out-File -Encoding ascii`，`Select-String` �?sessionId
- `Start-DoorbellSse / Stop-DoorbellSse`：同上，�?query `?sessionId=<sid>`
- `Initialize-Mcp`：initialize + notifications/initialized
- `Invoke-CheckIn / Invoke-CheckOut`：调 MCP tools/call
- `Invoke-Heartbeat / Invoke-PullTasks`�?0s 心跳 + 30s 拉取
- `Read-DoorbellDelta`：基�?marker file 的增量读取，�?`event:inbox` / keepalive
- `Test-LeaseExpiringSoon / Invoke-RenewLease`：到期前 60s �?checkOut+checkIn+重连
- 主循环：30 秒一�?tick；Ctrl+C 触发退出清理剧�?

预计净增：+~180 行�?

##### 2.3 UI 下载入口（B 类）

文件：`helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue` + `helloai-ui/public/scripts/powershell/qoder-ceshi-daemon.ps1`

- 新按钮“下载常驻值班脚本（PowerShell）”（type=info）插于“下�?hello_ai_skills.md”与“一键上班口令”之�?
- �?`downloadDaemon()` 方法：`fetch('/scripts/powershell/qoder-ceshi-daemon.ps1')` �?`Blob` �?浏览器触发下载，文件�?`hello_ai_<agentName>_daemon.ps1`
- 本轮未改 DTO（`daemonScript` 字段跳到下轮 C 类一起备），UX 提示“下载后请手动改 agentId/apiKey”（对应提示信息已在脚本头部�?== 例注释方式呈现）

预计净�?UI�?~30 行�?

#### 3. 验证

- **`mvn -pl helloai-core test -Dtest=PreFlightTest`**�?6/16 全绿，�?.5 预飞行检查不被现�?doctest 拦截
- **`scripts/powershell/qoder-ceshi-daemon.ps1`**：能解析、函�?`/ Start-Job / curl / regex pipeline` 语法�?PARSE-OK（[System.Management.Automation.Language.Parser]::ParseFile�?
- **端到端股】补�?*：本轮仅 `SKILL.md` + `daemon.ps1` + UI 改动，股】为 qoder-ceshi 实测点（后续 C 类补齐后跨轮验证�?

#### 4. 影响

- **架构影响**：EXECUTOR 接入路径从“AI 主观调度”转为“标准化常驻进程”，减少外部 Agent 重复踩坑（一�?SKILL 多个 Agent 复用�?
- **设计补救**：门�?SSE “真推�?vs 轮询�?调表避免下一�?Agent 重走老路；PE门铃 +30s heartbeat 缺口
- **文档影响**：SKILL.md §1.5 作为后续 EXECUTOR Agent 接入必读范本；AgentOnboardingDialog 文本补充“下�?daemon 后门铃推送”描�?
- **UI 影响**：弹窗按钮从 4 个增�?5 个；右侧 public/ 资源体积 +12.5 KB（daemon.ps1�?
- **接口影响**：对�?API 未变（`AgentOnboardingResponse` 未增 `daemonScript` 字段；下轮顺手补�?

#### 5. 遗留与下一�?

- **C 类三项平台侧优化**：派单过�?OFFLINE、inbox `superseded` 状态机、UI "�?claim / 已错�? 双状态区分，仍顺后下轮（2 人天估算�?
- **DTO 补字�?*：下轮补 `AgentOnboardingResponse.daemonScript`（String，主体内嵌入脚本原文），下载按钮可从 DTO 里取、避免从 `public/` 冷拉静态资�?
- **多平�?daemon 骨架**：本轮只�?PS 5.1 版本（覆盖当前所有测试用例）；Linux bash 版本下轮按需补（正文架可用同一 §1.5.6 骨架�?
- **实测证据加权**：股】后续补一次以“门铃常�?vs 探针模式”两种调度路径上拍“认领耗时中位�?/ 超时率”对比，证实本轮修复价�?
- **合并策略**：A 类（SKILL.md + daemon.ps1�? B 类（UI 按钮 + public/ 拷贝）合并一�?commit：`feat(executor): add §1.5 常驻值班协议 + 参�?daemon 脚本 + UI 下载入口`；本轮文档回填随�?commit 入提�?

---

### 2026-07-20 重分配熔断（V24�?

#### 1. 范围

- 修复"同角色所�?Agent 全掉线时子任务无限重分配"的死循环 Bug
- 新增基于计数的重分配熔断机制：达到阈值后直接取消子任务，不再继续重试

#### 2. 问题背景

用户反馈：sub-task-002 无限重新分配，重新分配的 Agent 都是 OFFLINE 状态，系统持续轮询形成死循环�?

根因链路�?
1. `AgentHealthCheckTask`（每 60s）检测到 Agent 超时 �?标记 OFFLINE �?`reassignStaleTasks()`
2. `redispatchOfflineSubTask()` �?`ResilientDispatcher.assignNext()` �?OFFLINE fast-fail �?fallback `pickAlternative()` 全部 OFFLINE 返回 null �?抛异�?
3. 二次路径 `dispatchPendingSubTaskAuto()` 也选不到在�?Agent �?失败
4. 子任务退�?PENDING �?`ExternalAgentFallbackTask.recoverPendingUnassigned()` 捡起 �?再次尝试 �?失败
5. 周而复始，形成死循�?

#### 3. 实际落地

- **V24 Flyway**：`V24__sub_task_reassign_attempt_count.sql` 新增 `sub_task.reassign_attempt_count INT NOT NULL DEFAULT 0`
- **实体**：`SubTask.java` 新增 `reassignAttemptCount` 字段（`@TableField` 自动映射�?
- **Mapper**：`SubTaskMapper.xml` 新增 `incrementReassignAttemptCount` 原子累加 SQL（COALESCE +1，不依赖读后写）
  - `updateById` 覆盖 SQL 新增 `external_fallback_count`、`reassign_attempt_count` 两列（修复之前遗漏的列覆盖）
- **配置**：`AgentDispatchProperties.maxReassignAttempts`（默�?5），`application.yml` 新增 `helloai.dispatch.max-reassign-attempts: 5`
- **核心逻辑**：`SubTaskDispatchService.checkReassignCircuitBreaker(subTaskId)` 私有方法，在 4 个重分配入口前统一调用�?
  - `maxReassignAttempts <= 0` �?熔断禁用（逃生口）
  - 子任务终态（DONE/CANCELLED）→ 跳过
  - `reassign_attempt_count >= maxReassignAttempts` �?标记 CANCELLED + 记录 `sub_task_cancelled` timeline（reason=`reassign_attempt_exceeded`）→ 返回 true（跳过本次重分配�?
  - 否则 �?原子累加计数 �?返回 false（继续重分配�?
- **4 个入口全部接�?*：`redispatchOfflineSubTask`、`redispatchAssignedTimeout`、`redispatchForFallback`、`dispatchBlockedSubTask`
- **测试**：`SubTaskDispatchServiceTest` 新增 `SubTaskMapper` / `AgentDispatchProperties` mock + `@BeforeEach` 设置熔断默认关闭（保�?7 个已有测试行为不变）�?/7 全绿

#### 4. 验证

- `mvn -pl helloai-common,helloai-core -am -DskipTests compile` �?BUILD SUCCESS
- `mvn -pl helloai-core -am test -Dtest=SubTaskDispatchServiceTest` �?Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

#### 5. 影响

- **行为变化**：子任务重分配最多尝�?5 次（可配置），达到后自动取消，不再无限重�?
- **配置新增**：`helloai.dispatch.max-reassign-attempts`（默�?5，设�?0 禁用熔断�?
- **DB 新增**：`sub_task.reassign_attempt_count` 列（V24 迁移�?
- **接口影响**：对�?API 未变；CANCELLED 子任务在现有查询中自动过�?

#### 6. 遗留与下一�?

- 熔断后手动恢复：当前需管理员从 DB 手动重置 `reassign_attempt_count=0` + `status=PENDING` 后再触发重分配；未来可考虑 UI 一键恢�?
- 监控告警：建议在 `sub_task_cancelled`（reason=`reassign_attempt_exceeded`）事件上加钉�?飞书通知
- **区分计数语义**：当�?`reassign_attempt_count` 对所有重分配类型统一计数；未来如需要区分（离线重派 vs N11回退），可扩�?`reassign_attempt_reason` 字段

---

### 6.9 M4.5 派发控制台：批量派发 API + 子任务时间线 + 5s 轮询可视化（2026-07-20�?

#### 1. 范围

�?`doc/M4.5_派发控制台实施清�?md` 落地，填�?运营/调度人工快速把同一个子任务 fan-out 派给多个 EXECUTOR"链路最后一公里�?

- **后端**：同内容 fan-out 创建子任务，避免前端 N 次串行调�?
- **可视�?*：子任务详情页加执行时间线，让操作员看到 claim / submit / review / blocked �?timeline 事件不用直接�?DB
- **实时�?*：详情页打开�?5s 轮询，进入终态后自动停止，避免人工刷
- **UI 入口**：子任务列表�?刷新"按钮旁加"快速派�?按钮打开新对话框

#### 2. 实际落地

##### 2.1 后端 API（helloai-api + helloai-core Service�?

- `SubTaskController` 增两条端点：
  - `POST /api/sub-tasks/batch`（`createBatch`）：�?`List<CreateSubTaskRequest>`，逐项调现�?`create()` 装配 + 入库逻辑，单项失�?catch 隔离返回成功列表
  - `GET /api/sub-tasks/{id}/timeline`（`timeline`）：�?id 升序返回该子任务�?`TaskTimeline` 列表，映射到 `TaskTimelineItem` DTO
  - 顺手把原 `create()` 内的 DTO→Entity 装配抽出�?`toEntity()` 私有方法，避�?createBatch 重复粘代码（**复用优先原则**�?
- `SubTaskService.createBatch(List<BatchCreateItem>)`：复用现�?`create(SubTask, Long)` 单建方法，单�?`try/catch` 隔离，返回成功列�?
- `TaskTimelineService.listBySubTaskId(Long)`：新方法�?Controller 调用，按 id ASC
- 新建 DTO `helloai-api/.../dto/subtask/TaskTimelineItem.java`（与 V23 字段命名规范一致，eventType/role/agentId/payload/createTime�?

##### 2.2 前端（M4.5 实施清单�?

- 新建 `src/api/module.ts`：`moduleApi.list(taskId)` + `create(taskId, data)`，对应后�?`/api/tasks/{taskId}/modules`
- `src/api/task.ts` 新增 `create(data)`
- `src/api/subTask.ts` 新增 `createBatch(data)` + `timeline(id)` 两个方法；`CreateSubTaskPayload` �?`TaskTimelineItem` 类型补到 `src/types/index.ts`
- 新建 `src/components/QuickDispatchDialog.vue`�?
  - 字段：任务（可新建）/ 模块（可新建�? 标题 / 描述 / 验收 / 优先�?/ 执行 Agent（multiple，自动过�?role=EXECUTOR �?accessType=CLI_CLIENT�?
  - 提交�?`Promise.allSettled` 逐项派发，汇聚报�?成功 N / 失败 M"，失败项列出 Agent �?+ 错误信息
- `views/subtask/SubTaskList.vue`：页�?刷新"按钮旁加 `<el-button type="primary" @click="dispatchVisible = true">快速派�?/el-button>` + 挂载 `<QuickDispatchDialog v-model="dispatchVisible" @done="load" />`
- `views/subtask/SubTaskDetail.vue`�?
  - 新增"执行时间�?卡片（el-card + el-timeline），数据�?`subTaskApi.timeline(id)`
  - 每条节点显示 eventType + role/agentId + fmtTime(createTime)，payload �?`<el-collapse>` 折叠展示 JSON
  - **5s 轮询**：进入页面时启动；进入终态（DONE/CANCELLED）后停止；`onBeforeUnmount` clearInterval
  - eventType �?el-tag 颜色映射（assigned/created→primary, completed/submitted/review→success, blocked/rejected/failed→danger, paused/warning→warning�?

#### 3. 验证

- `npm run build`：TypeScript 类型检�?+ 构建通过
- 后端：与 v2.6 §4.1 + V24 一�?`mvn test` reactor SUCCESS（SubTaskController �?Mapper 注入、Controller 红线合规�?

#### 4. 影响

- **接口新增**：`POST /api/sub-tasks/batch`、`GET /api/sub-tasks/{id}/timeline`
- **DTO 新增**：`TaskTimelineItem`（API 层，对应实体 `TaskTimeline`�?
- **UI 新增**：`QuickDispatchDialog` + SubTaskList "快速派�?按钮 + SubTaskDetail 时间线卡�?+ 5s 轮询
- **行为变化**：前端扇出派发从"前端 N 次串行调�?改为后端批量端点（同一语义，单项失败隔离）；子任务详情页自动刷新时间线（无需手动刷新�?
- **遗留 DTO 字段**：`AgentOnboardingResponse.daemonScript` 仍未加，�?§6.8（EXECUTOR 常驻值班）合并到下轮 C 类一起补

#### 5. 遗留与下一�?

- SubTaskDetail 轮询频率 5s 硬编码：未来可改为配置项 `helloai.ui.subtask-detail-poll-interval-ms`
- QuickDispatchDialog 列表里“（值班中）”标注为本轮 TODO（涉�?duty 接口联调），下轮�?
- DTO 补字段：`AgentOnboardingResponse.daemonScript`（来�?§6.8 C 类遗留）、`TaskTimelineItem.payload` 改用强类�?V 各事件专�?DTO 而非 `Record<string, any>`

---

### 6.10 改派链路熔断收口 + 死信人工兜底（V25）（2026-07-28�?

#### 1. 背景与问题确�?

真实 AI 联调中发现：手动指派子任务给在线外部 Agent 后，若该 Agent 未及时接收，系统自动降级改派存在三大旁路�?

1. **无限改派旁路**：`dispatchPendingSubTaskAuto` �?`checkReassignCircuitBreaker`，被 3 个定时任务（AgentHealthCheckTask 二次选人 / ExternalAgentFallbackTask.recoverPendingUnassigned / SubTaskPendingOrphanTask）每 60s 反复调用 �?V24 熔断形同虚设
2. **误派窗口**：`ResilientDispatcher.assignNext` fast-fail 只查 DB `online_status`，不查心跳新鲜度 �?存在�?5-6 分钟“DB �?ONLINE �?Agent 已死”的误派窗口
3. **无人工兜�?*：V24 熔断后直�?CANCELLED（终态），无死信池、无人工恢复入口，只能手动改�?

#### 2. 实际落地

- **V25 Flyway**：`V25__sub_task_dead_letter_status.sql` 重建 `chk_sub_task_status` CHECK 约束，加�?`DEAD_LETTER`
- **枚举/状态机**：`SubTaskStatus` 新增 `DEAD_LETTER`（非终态）；`SubTaskStateMachine` 新增流转 `PENDING/ASSIGNED/IN_PROGRESS/BLOCKED/REWORK �?DEAD_LETTER`，`DEAD_LETTER �?ASSIGNED（人工指派）/ CANCELLED（人工放弃）`
- **熔断收口**：`dispatchPendingSubTaskAuto` 入口顶部�?`checkReassignCircuitBreaker`，封堵三个定时任务的无计数旁路；`checkReassignCircuitBreaker` 达阈值后改置 `DEAD_LETTER`（原 CANCELLED），timeline 事件�?`sub_task_dead_letter`，context 写入 `dead_letter_reason=reassign_attempt_exceeded`；终态跳过判断加 `DEAD_LETTER`。手动链（`POST /sub-tasks` �?assignedAgent、`claim`、`change-status`）有意不加拦截：人工判断优先
- **心跳新鲜�?fast-fail**：`AgentSelector.isHeartbeatFresh` �?public 供复用；`ResilientDispatcher.assignNext` �?SLEEPING/OFFLINE 判断后新增心跳新鲜度检查，不新鲜抛 `AgentUnavailableException` �?fallback 选替代（API_KEY_LLM / WEB_BROWSER �?isHeartbeatFresh 内部已豁免）；同时覆�?`dispatchBlockedSubTask` �?preferredAgentId 路径
- **死信人工兜底**：`SubTaskDispatchService.redispatchDeadLetter(subTaskId, agentId)`：校�?DEAD_LETTER �?`resetReassignAttemptCount` 清零（SubTaskMapper 新增）→ `changeStatus �?ASSIGNED`（自�?outbox + 收件�?+ 自动执行链）�?timeline `sub_task_dead_letter_manual_assign`；`SubTaskController` 新增 `POST /api/sub-tasks/dead-letter/redispatch/{id}`（复�?ReassignRequest）；死信列表复用现有列表接口�?`status=DEAD_LETTER` 过滤
- **ASSIGNED 超时阈值配置化**：`AgentDispatchProperties.assignedTimeoutMinutes`（默�?10），`AssignedSubTaskTimeoutTask` 删硬编码常量改读配置；`application.yml` �?`helloai.dispatch.assigned-timeout-minutes: 10`
- **前端最小适配**：`types/index.ts` SubTaskStatus 联合类型 + 状态标签映射加 `DEAD_LETTER: 死信待人�?danger`；`SubTaskDetail.vue` `TERMINAL_STATUSES` 不加 DEAD_LETTER（可人工再指派，非终态）
- **口径说明**：AgentHealthCheckTask 首�?二次路径同轮各计 1 次属两次真实改派尝试，接受该口径（只会更快进死信）；不引�?RabbitMQ 层面 DLQ（死信是业务态）

#### 3. 测试与验�?

- `SubTaskDispatchServiceTest` 新增 4 例：达阈值置 DEAD_LETTER 且不选人 / 未达阈值计数累加正常调�?/ redispatchDeadLetter 清零+ASSIGNED / �?DEAD_LETTER 报错�?1/11 全绿�?
- `ResilientDispatcherTest` 新增心跳陈旧 fast-fail 用例（setUp 默认�?isHeartbeatFresh=true 保护既有用例�?
- 新建 `SubTaskStateMachineTest`�? 例：DEAD_LETTER �?出流�?+ 非法流转 + 抽样回归�?
- `AssignedSubTaskTimeoutTaskTest` 适配新构造器（注�?AgentDispatchProperties mock�?
- 全量 `mvn test`：BUILD SUCCESS，helloai-core 226 + helloai-job 56 全绿无回�?
- 新增 `scripts/powershell/verify-subtask-deadletter.ps1`：建子任�?�?block �?连续 reassign 触发熔断计数 �?断言 DEAD_LETTER �?人工兜底接口 �?断言 ASSIGNED 且计数清零（需运行时环境，待真实环境回归）

#### 4. 影响

- **行为变化**：所有自动改派入口（含原旁路 dispatchPendingSubTaskAuto）统一受熔断管控；达阈值后�?DEAD_LETTER 死信池而非 CANCELLED，可人工恢复
- **接口新增**：`POST /api/sub-tasks/dead-letter/redispatch/{id}`
- **DB 变更**：V25 重建 CHECK 约束（加 DEAD_LETTER�?
- **配置新增**：`helloai.dispatch.assigned-timeout-minutes`（默�?10�?

#### 5. 遗留与下一�?

- 死信管理 UI 页面（列表筛�?+ 一键再指派）未做，当前复用列表接口 status=DEAD_LETTER 过滤 + API 兜底
- `verify-subtask-deadletter.ps1` 待真实环境实测回填结�?
- 监控告警：建议在 `sub_task_dead_letter` 事件上加钉钉/飞书通知（沿�?V24 遗留项）
- NotificationConsumer ack 修复、消息信封统一不在本轮范围（后续单独处理）

---

### 6.11 任务-子任务关联打�?+ 子任务列表真分页�?026-07-28�?

#### 1. 背景与问题确�?

真实使用中发现任务管理页与子任务�?看起来没有关�?：`TaskList.vue` 跳转已携�?`/sub-tasks?taskId=行id`，`sub_task.task_id` 外键与后�?`?taskId=` 过滤能力也齐全，但断点在前端——`SubTaskList.vue` 不读 `route.query.taskId`、`subTask.ts` 参数类型�?`taskId` 字段，导致过滤参数从未发出。顺带确诊两处次生问题：子任务列表为假分页（后端无分页参数、前�?`list.length` �?total）；`SubTaskResponse` 无主任务标题，全量列表无法展示归属任务�?

#### 2. 实际落地

- **前端关联打�?*：`SubTaskList.vue` �?`route.query.taskId`（LongId 保持 string 防精度丢）→ 列表过滤 + 顶部 `el-alert` 主任务信息条（标�?+ 状�?tag + "查看全部子任�?清筛按钮�? `watch(taskId)` 联动刷新；主任务查询失败降级显示 taskId 不阻断列表。`subTask.ts` list 参数�?`taskId?: LongId`；`task.ts` `getById` 参数 `number �?LongId`
- **后端 §6.3 收口 + 分页**：`SubTaskService` 新增 `list(taskId, status, assignedAgentId, page, pageSize)` 返回 `IPage<SubTask>`（条件构造从 Controller 下沉；`page` �?null/<=0 时全量包装成 Page，兼�?SKILL.md 外部 Agent 纯数组契约）；`SubTaskController.list` 删除内联 `LambdaQueryWrapper`，改 `R<?>` 双返回（不传 page 返回数组 / �?page 返回 `PageResult`，同 `TaskController` 模式），新增 `page`/`pageSize` 参数
- **主任务标题回�?*：`SubTaskResponse` 新增 `taskTitle` 冗余字段；Controller 注入 `TaskService`，新�?`attachTaskTitles`（`listByIds` 一次查询批量回填，�?N+1），list �?getById 均回�?
- **前端真分�?*：`subTask.ts` list 改传 `page`/`pageSize` 返回 `PageResult<SubTask>`；`SubTaskList.vue` `load()` �?`res.list`/`res.total`，`el-pagination` �?`currentPage`；顺手修掉模�?`@change="load"`/`@click="load"` 事件对象误传�?page 参数的隐患（�?`load(1)`/`load(currentPage)`）；`types/index.ts` `SubTask` �?`taskTitle?: string | null`，全量视图表格加"所属任�?列（�?taskId 过滤时隐藏避免与信息条重复）
- **兼容性决�?*：`GET /api/sub-tasks` 不传 page 保持纯数组返回，planner/patrol/reviewer �?SKILL.md 契约零破坏；`/available`、`/mine` �?§6.3 违规不在本轮范围

#### 3. 测试与验�?

- �?reactor `mvn -q -DskipTests install` �?BUILD SUCCESS
- `mvn -pl helloai-core,helloai-api test` �?**helloai-core 228 全绿 + BUILD SUCCESS**，无回归
- 前端 `npx vue-tsc -b --force` �?0 错误

#### 4. 影响

- **行为变化**：任务管理页"子任�?入口现在真正只展示该主任务的子任务并带信息条；子任务列表改服务端真分页；全量视图新增"所属任�?�?
- **接口变化**：`GET /api/sub-tasks` 新增可�?`page`/`pageSize` 参数（传 page 返回 PageResult，不传保持数组，向后兼容）；`SubTaskResponse` 新增 `taskTitle` 字段
- **DB 变更**：无

#### 5. 遗留与下一�?

- `/available`、`/mine` 两端点仍�?Controller 内联 QueryWrapper（�?.3 待收口清单，后续统一处理�?
- `mine` / `available` 返回未回�?`taskTitle`（外�?Agent 场景暂无展示需求）

---

### 6.12 任务管理 CRUD 收口 + 级联删除（竞态免疫）�?026-07-28�?

#### 1. 背景与问题确�?

任务管理页此前只有列�?子任务跳转，无新�?编辑/删除入口；且删除任务面临与消息链路的竞态风险：任务删除后，旧收件箱通知、在�?MQ 通知、残留执行记录可能让"已删任务"继续�?Agent 消费或幽灵执行。设计原则沿�?消息只是门铃、DB 是唯一事实�?：级�?*物理删除**后所有消费端（claimSubTask / submitResult / LocalExecutionCommandConsumer / handleReport / Poller）实时回�?DB 均得 not_found 直接丢弃，与现有防线天然兼容；唯一缺口�?删除瞬间在途的 MQ 通知落库成孤�?inbox"，在 NotificationConsumer 补防御分支兜底�?

#### 2. 实际落地

- **Mapper 物理删除 SQL**（`@TableLogic` 软删陷阱规避，全�?`@Delete` 注解 + 显式 `physicalDeleteXxx` 命名 + Javadoc 标注"仅供任务级联删除使用"）：`TaskMapper.physicalDeleteById`、`SubTaskMapper.physicalDeleteByTaskId`、`ModuleMapper.physicalDeleteByTaskId`、`TaskTimelineMapper.physicalDeleteByTaskId`、`ReviewRecordMapper.physicalDeleteByTaskId + countByTaskId`、`AgentExecutionRecordMapper.physicalDeleteByTaskId + countByTaskId`、`AgentInboxMapper.physicalDeleteByTaskRef + countUnreadByTaskRef`（ref 三段 OR：task 直引 / sub_task 子查�?/ review 双层子查询）
- **TaskService 下沉三方�?*（按 AgentService 惯例直接注入 7 �?Mapper 防循环依赖，AgentInboxService 作无回向依赖叶子服务注入复用门铃链路）：
  - `getRelatedCounts(taskId)`：子任务/在�?ASSIGNED+IN_PROGRESS)/死信/模块/审查/执行记录/未读收件�?时间�?计数
  - `deleteTaskCascade(taskId, confirmTitle)`：标题精确匹配校验（�?Agent confirmName 范式）→ `@Transactional` 内按序物理删 inbox→execution→review→timeline→sub_task→module→task（前三�?SQL 依赖 sub_task 子查询，必须先删）→ 返回删除�?counts 回显
  - `republish(taskId)`：DONE �?BizException；重�?PENDING；新 eventId `task.republish.{id}.{ts}` 通知全部 PLANNER（`(event_id,agent_id)` 唯一约束不与历史冲突）；**不触碰已有子任务**
- **TaskController 三端�?*：`POST /{id}/republish`、`GET /{id}/related-counts`（新 DTO `TaskRelatedCounts`）、`DELETE /{id}`（body �?`confirmTitle`，空�?fail 提示�?
- **NotificationConsumer 防御分支**：写 inbox �?`refTargetExists(refType, refId)` 回查（task/sub_task/review �?getById != null），目标已删�?log.info 丢弃——兜�?任务已删、在�?MQ 通知还在�?窗口
- **前端**：`types/index.ts` 新增 `TaskRelatedCounts` 接口；`task.ts` �?`update/republish/relatedCounts/deleteTask`（delete �?`{ data: { confirmTitle } }` body）；新建 `TaskFormDialog.vue`（新�?编辑共用，title 必填�? `TaskDeleteDialog.vue`（照 AgentDeleteDialog 范式：@open 加载影响面统计、activeSubTaskCount>0 显示"丢弃在途执行结�?警示、输入标题精确匹配激活危险按钮）；`TaskList.vue` header �?新建"、操作列�?编辑/重新发布（DONE 禁用 + ElMessageBox 确认�?删除
- **拍板口径**：重新发布不动子任务只重�?重通知，DONE 不允许重发；task_timeline 随任务一起物理删

#### 3. 测试与验�?

- �?reactor `mvn -q -DskipTests install` �?�?ERROR
- `mvn -pl helloai-core,helloai-api,helloai-job test` �?**56 测试全绿 + BUILD SUCCESS**，无回归
- 前端 `npx vue-tsc --noEmit` �?0 错误

#### 4. 影响

- **接口新增**：`POST /api/tasks/{id}/republish`、`GET /api/tasks/{id}/related-counts`、`DELETE /api/tasks/{id}`
- **行为变化**：任务删除为**物理级联删除**（子任务含死�?模块/审查/执行记录/收件箱引�?时间线一并清理），不�?`@TableLogic` 软删；MQ 通知消费前回查目标存在�?
- **DTO 新增**：`TaskRelatedCounts`（API 层）
- **DB 变更**：无（纯应用�?SQL�?

#### 5. 遗留与下一�?

- 改派竞�?4 个已识别漏洞未修（本轮范围外，候选下轮）：①改派入口不作废旧 inbox 通知 ②`ExecutionResultHandler.handleReport` 不校�?report.agentId==assignedAgentId ③改派不取消�?PENDING 执行记录 ④同 Agent 再改�?Poller 重放旧命�?
- 删除操作无操作人审计（当前无登录体系，后续补�?

---

### 6.13 值班租约列表�?Agent 维度展示 + 历史记录分页对话框（2026-07-28�?

#### 1. 背景

值班租约页此前平铺展示全部租约记录，同一 Agent 反复 checkIn 产生大量历史行，运营难以一眼看�?每个 Agent 当前值班状�?。改�?Agent 维度展开：每�?Agent 一行只显最新租�?+ 租约总数，点"更多"弹窗分页查看�?Agent 全部历史，Agent 维度主列表也分页�?

#### 2. 实际落地

- **Mapper**：`AgentDutyLeaseMapper` 新增 `selectLatestPerAgent(offset,size)`（PostgreSQL `DISTINCT ON (agent_id)` �?start_time 倒序取组内最新，JOIN 子查询带�?lease_count，外层按最新租约开始时间倒序�? `countDistinctAgents()`；查询行对象 `AgentDutyLeaseLatestRow extends AgentDutyLease`（非表实体，冒余 leaseCount�?
- **Service**：`AgentDutyLeaseService.listLatestPerAgent(pageNum,pageSize)`——自定义 SQL �?MP 分页插件链路，手工拼 Page（count + offset 查询�?
- **Controller**：`GET /api/admin/duty-leases/by-agent`（page/size）返�?`PageResult<DutyAgentLatestResponse>`（extends DutyLeaseResponse + leaseCount），agentName 批量回填�?N+1�?查某 Agent 全部记录"复用既有 list 端点�?agentId 过滤 + 分页，未新建端点
- **前端**：`types/duty.ts` �?`DutyAgentLatestResponse`；`api/duty.ts` �?`listByAgent`，顺手修 `list.agentId` 参数类型 `number �?LongId`（雪�?ID �?number 有精度丢失隐患）；新�?`DutyLeaseHistoryDialog.vue`（单 Agent 历史租约分页表，pageSize=10）；`DutyLeaseList.vue` 重写�?Agent 维度主表（Agent/最新状�?最新会�?模式/并发/三时�?租约总数/更多），�?status/agentId 平铺过滤区随平铺视图一并移除（历史对话框内可见全部状态）
- **兼容�?*：既�?`GET /admin/duty-leases`（平铺分页）�?`/overview` 端点零改动，Dashboard 概览卡片不受影响

#### 3. 测试与验�?

- �?reactor `mvn -q -DskipTests install` �?�?ERROR；`mvn -pl helloai-core,helloai-api test` �?**helloai-core 228 全绿 + BUILD SUCCESS**
- 前端 `npx vue-tsc --noEmit` �?0 错误
- DISTINCT ON SQL �?postgres_helloai MCP 真库验证：qoder-ceshi�? 条租约）正确返回最新一�?+ lease_count=8

#### 4. 影响

- **接口新增**：`GET /api/admin/duty-leases/by-agent`
- **行为变化**：值班租约页主视图从租约平铺改�?Agent 维度；历史记录入口下沉到每行"更多"对话�?
- **DTO 新增**：`DutyAgentLatestResponse`（API 层）、`AgentDutyLeaseLatestRow`（core 查询视图行）
- **DB 变更**：无

#### 5. 遗留与下一�?

- Agent 维度主列表暂无状态过滤（如需"只看值班�?Agent"可在 by-agent SQL 外层�?status 条件，待需求明确后补）

---

### 6.14 全站暗色主题统一（登录页 + 后台�?026-07-22�?

#### 1. 背景

登录页此前已改造为深蓝星空 + 玻璃拟态暗色风格（.login-page 局�?token 覆盖），但登录后后台仍为亮色主题，前后视觉割裂。经用户确认采用"全站永久暗色 + 深蓝底实心卡�?方案（不做亮/暗切换，后台不用玻璃拟态保表格可读性）�?

#### 2. 实际落地

- **design-system.css（主战场�?*：`:root` 亮色 token 整体替换为登录页同调性深蓝暗色值（bg #0A0E1A / surface #0F1524 / elevated #121828 / border #242D47 / ink #EEF2F8）；删除�?`prefers-color-scheme: dark` 媒体块（#131417 中性灰色板弃用），�?EP 补丁提升为常规规则；新增 `html.dark` 段将 EP 官方 dark 变量�?-el-bg-color 系）对齐项目深蓝色板；Tag 四色文字换暗色可读变体（#34D399/#FBBF24/#F87171/#60A5FA）；阴影改黑色系 + 弱紫光晕
- **基础设施**：index.html `<html>` �?`class="dark"`；main.ts 引入 `element-plus/theme-chalk/dark/css-vars.css` 兜底 message/notification/popper �?append-to-body 弹层
- **MainLayout.vue**：侧边栏从亮紫→青渐变改为深蓝渐变（#0D1220�?141B33�?0E2233，保留极光动�?网格/青色光斑）；菜单选中态改品牌紫实�?+ 紫色投影；头像底色改紫色半透明
- **硬编码残留清�?*：SubTaskDetail.vue�?909399×2�?f5f7fa �?token）、QuickDispatchDialog.vue�?909399 �?token）；AgentList.vue �?#fff 在紫色实底上保留
- **Login.vue 零改�?*：局�?token 覆盖与新全局暗色值同调性，自然兼容

#### 3. 测试与验�?

- `npm run build`（vue-tsc + vite）→ 0 错误
- 浏览器实测（admin 登录后样式探针）：Dashboard（body #0A0E1A/卡片 #121828/文字 #EEF2F8）、任务列表（表头 #0F1524）、新建弹窗（弹窗 #121828/输入�?#0F1524/标签 #A9B4C7）、侧边栏深蓝渐变 + 紫色选中态均生效
- 登录页回归：星空 Canvas、玻璃卡�?rgba(18,24,40,0.6) + blur(20px)、tab 样式均未受影�?

#### 4. 影响

- **行为变化**：全站（登录�?+ 后台）统一为深蓝暗色主题，无亮色模式；�?登录页暗�?+ 后台亮色"分层策略废弃
- **接口/DB 变更**：无（纯前端样式层）

#### 5. 遗留与下一�?

- ECharts 图表仅初始化时读�?cssVar，若未来引入主题切换需补重绘监�?
- 如需恢复亮色或加切换开关，需�?:root 暗色值回迁至 html.dark 作用域并补开关逻辑

---

### 6.15 打卡上班语义改造（值班租约改名 + Agent 注册态文案，2026-07-22�?

#### 1. 背景

用户要求两项语义重命名：①“值班租约”改为“打卡上班”，状态一一对应 ACTIVE→在线、EXPIRED→超时、CLOSED→下班；②Agent 注册状态改为“已注册/已注销”，消除“活跃”文案对“注�?在线”的误导（AgentStatus 本就是管理态，�?onlineStatus 在线监测双轨分离，一键注册链路现状本就不含在线监测）。经确认采用“界面语义层改造”：仅改文案与对外文档术语，后端枚举字符串（ACTIVE/CLOSED/EXPIRED、ACTIVE/DISABLED）、DB、MCP 协议契约零改动�?

#### 2. 实际落地

- **打卡上班前端**：`types/duty.ts` DUTY_LEASE_STATUS_MAP 改为 在线(success)/下班(info)/超时(warning)；`MainLayout.vue` 菜单�?`router/index.ts` title 改“打卡上班”（路由路径 /duty-leases 不变）；`DutyLeaseList.vue`（标�?列名/empty-text）、`DutyLeaseHistoryDialog.vue`（标题“打卡记录�?列名）、`Dashboard.vue`（“Agent 打卡概览”卡片四标签 在线/下班/超时/打卡总数）、`api/duty.ts` 注释同步
- **Agent 注册态前�?*：`AgentDetail.vue`/`AgentCard.vue` 状态文案“活�?已禁用”→“已注册/已注销”，操作按钮“禁�?启用”→“注销/恢复注册”；`AgentStatusDialog.vue` 弹窗标题/确认文案/成功消息全套同步；`AgentOnboardingDialog.vue`“常驻值班脚本/进程”→“常驻打卡�?
- **后端对外文档（不改逻辑�?*：`McpMcpServer.java` checkIn/checkOut �?@Tool description “值班租约/值班态”→打卡术语；`skills/executor/SKILL.md` 全文 11 处“值班”字样统一为打卡术语（机制描述与枚举�?ACTIVE/CLOSED/EXPIRED 原样保留�?
- **明确不做**：枚�?`AgentDutyLeaseStatus`/`AgentStatus` 及其字符串值、Flyway 迁移、API/路由路径、MCP 工具名、心�?在线监测/AgentSelector/注册链路逻辑均零改动

#### 3. 测试与验�?

- `npm run build`（vue-tsc + vite）→ 0 错误；`mvn -pl helloai-core -am compile` �?BUILD SUCCESS
- 浏览器实测（localhost:5174 探针）：打卡上班页（菜单/标题/列名/真实 EXPIRED 数据显示“超时”标签）、Dashboard 打卡概览四标签、Agent 卡片（状态点“已注册�?按钮“注销”）、注销弹窗（标�?文案/“确认注销”）全部生效

#### 4. 影响

- **行为变化**：纯展示层语义更名，无任何接�?调度/数据行为变化；已接入的外�?Agent 不受影响
- **接口/DB 变更**：无

#### 5. 遗留与下一�?

- 若未来需要枚举值与新语义完全对齐（如租�?ACTIVE→ONLINE、Agent ACTIVE→REGISTERED），需 Flyway 迁移翻写存量 + CHECK 约束/部分唯一索引同步 + 外部 Agent SKILL 文档同步，属协议级变更需单独立项
- 已下发给外部 Agent 的旧�?hello_ai_skills.md 仍含“值班”旧术语，重新生成接入内容即可刷�?

---

### 6.16 Planner 平台内自动拆解闭环（V26�?026-07-28�?

#### 1. 背景

Planner 角色此前只有枚举定义与一份约 60 行的�?REST �?SKILL.md，既无平台内自动拆解能力，也无外�?Planner 接入的完整说明书。结合参考项�?AgentTeams-main（拆解→确认→分发的交互范式）与 openMoss（task-planner 拆分四要�?防重复拆�?排障六步闭环）分析后，确定在不新增基础设施的前提下补齐“需�?�?LLM 自动拆解 �?用户确认/拒绝 �?进入既有分发链”闭环；原差距表 §5 7b“场�?1~3 全绿前不启动 planner 编排层”门禁按用户决策提前解除（拆解链与执行链经草案态硬隔离，缺陷可独立定位）�?

#### 2. 实际落地

- **领域模型（helloai-common�?*：`SubTaskStatus` 新增 `PENDING_PLAN_REVIEW` 草案态，状态机仅允�?�?`PENDING`（确认转正）/ `CANCELLED`（拒绝），任何状态不可转入草案态（只能由拆解落库产生）；`TaskStatus` 新增 `PLANNING`（拆解进行中，防重复触发）；Flyway `V26__planner_plan_review_status.sql` 重建 `chk_sub_task_status` / `chk_task_status` CHECK 约束纳入新�?
- **旁路排查结论（全链路安全�?*：`claimSubTask`/`assignNext`/�?redispatch/`ExecutionCompensationTask`/`SubTaskPendingOrphanTask`/XML mapper/dashboard 统计均精确匹配既有状态枚举，`PENDING_PLAN_REVIEW` �?claim/分发/超时回收/统计天然不可见，无需任何防御性修�?
- **Prompt 模板**：`helloai-core/resources/prompts/planner-decompose.md`，移�?openMoss 拆分四要素（目标/交付�?验收标准/优先级），要求输出严�?JSON 数组（title/content/deliverable/acceptance/priority），限定 3~10 �?
- **`PlannerAnalysisService`（新�?core/planner 包，编排收口 core 对齐 §6.3�?*：`decompose`（校�?Task PENDING + 已存在非 CANCELLED 子任务拒�?+ CAS `lambdaUpdate().eq(status,PENDING).set(status,PLANNING)` 防并�?+ `AgentSelector.pickPreferred(PLANNER)` �?API_KEY_LLM Agent（首选非 LLM 时回退候选列表筛选）+ `PlatformAgentExecutionService.executeSync` �?LLM + strip markdown fence 容错解析 + 逐条校验必填/数量上限/priority 归一�?+ 事务�?saveBatch 草案 + timeline `task_plan_generated`；失败路�?CAS 回退 PENDING + `task_plan_failed` �?LLM 原始错误�? `listDrafts` / `confirmPlan`（草案逐条 changeStatus �?PENDING，Task �?IN_PROGRESS，按 `autoAssignOnCreate` 逐条 `dispatchPendingSubTaskAuto`，与手工创建子任务分发路径完全同构）/ `rejectPlan`（草案翻 CANCELLED 保留审计，Task 回退 PENDING 可重新拆解）
- **API 入口（helloai-api�?*：`TaskController` 四个薄入口（只转发不含编排）：`POST /api/tasks/{id}/plan`、`GET /api/tasks/{id}/plan`、`POST /api/tasks/{id}/plan/confirm`、`POST /api/tasks/{id}/plan/reject`
- **外部 Planner SKILL.md 升级**：`skills/planner/SKILL.md` 重写（约 60 �?�?347 行），对�?executor 版结构（MCP 四步握手/checkIn 租约/门铃/常驻打卡三件�?退出剧�?错误码速查 + REST 兜底），新增 Planner 专属工作流（每次唤醒固定流程：查收件�?�?blocked 六步排障闭环 �?进度监控 �?�?PENDING 子任务指�?�?�?DONE 收尾�? 拆分四要素质量标�?+ 防重复拆分原�?
- **明确不做**：前端规划确认页、planner 专用 MCP 工具（decomposePlan 保持演进项）、子任务依赖 DAG；不�?`SubTaskAutoExecutionDispatcher` �?accessType 过滤（执行面语义，与拆解无关�?

#### 3. 测试与验�?

- `PlannerAnalysisServiceTest` 13 用例（正常拆解含 fence 容错 + priority 归一�?/ 首选非 LLM 回退候�?/ JSON 解析失败回退 / LLM 失败 / �?PENDING 拒绝 / 已有子任务拒�?/ CAS 失败 / �?Planner Agent / confirm 含开�?autoAssign 两路 / confirm・reject 非法�?/ reject 流转），项目内首�?`lambdaQuery`/`lambdaUpdate` 链式 mock（直�?mock 链包装类 + `lenient()` 兜底 stub）；`SubTaskStateMachineTest` �?V26 三用例（草案态仅可转 PENDING/CANCELLED、任何状态不可转入、非法转换抛 BizException�?
- `mvn -pl helloai-core -am test` �?**helloai-core 244 全绿 BUILD SUCCESS**；全模块 `mvn compile` �?�?ERROR
- **多模�?SNAPSHOT 教训**：不�?`-am` 单跑 helloai-core 测试时报 `NoSuchFieldError: PENDING_PLAN_REVIEW`（本地仓�?helloai-common 快照是旧版），须�?`mvn -pl helloai-common install -DskipTests` 再跑；以后改�?common 枚举/实体后单模块测试前必须先 install common
- 端到端脚�?`scripts/powershell/verify-planner-decompose.ps1`�?2 步：登录 �?注册 PLANNER Agent �?confirm 路径（草�?PENDING_PLAN_REVIEW + Task PLANNING �?转正 PENDING/ASSIGNED + Task IN_PROGRESS）→ reject 路径（cancelledCount + Task 回退 PENDING）→ 重复拆解拒绝），遵循 D8 规则（UTF-8 编码�?+ runtime 字面量纯 ASCII），`Parser.ParseFile` 自检 PS-SYNTAX-OK�?*待真实环境（6565 + 可用 deepseek Provider）实�?*

#### 4. 影响

- **接口新增**：`POST/GET /api/tasks/{id}/plan`、`POST /api/tasks/{id}/plan/confirm`、`POST /api/tasks/{id}/plan/reject`
- **DB 变更**：V26 重建 `sub_task`/`task` 两张�?CHECK 约束（纳�?`PENDING_PLAN_REVIEW`/`PLANNING`），无新表无新列
- **行为变化**：Task 新增 PLANNING 中间态；确认前草案子任务对整个调�?补偿/统计链不可见；既有手工创建子任务、Executor 执行链路零改�?
- **文档**：差距表新增 N16（已交付�? §5 7b 门禁解除说明 + §6 治理结论条目

#### 5. 遗留与下一�?

- `verify-planner-decompose.ps1` 待真实环境回归（需 helloai-start 运行 + deepseek Provider 可用�?
- 前端规划确认页（草案列表 + 确认/拒绝按钮）后续独立迭代，本轮�?REST + 验证脚本闭环
- planner 专用 MCP 工具 `decomposePlan`（`AgentMcpServerService` 注释预留）、子任务依赖 DAG 编排、循环任务保持演进项

---

### 6.17 管理员会�?Redis 化（修复后端重启前端掉线�?026-07-28�?

#### 1. 背景

管理员登录态（X-Admin-Token）此前只存在 `AuthService` 实例字段�?`ConcurrentHashMap` 里：后端重启内存清空 �?下一次请�?401 �?前端 `request.ts` 拦截器清 sessionStorage 强制踢回登录页；�?token �?TTL，进程存活期间永久有效（安全隐患）。用户确认方案：会话态迁 Redis，登录动作本身仍�?DB + BCrypt 不变；Redis 不可用时直接拒绝（Redis 已是心跳/MQ 幂等的强依赖，不做内存降级）�?

#### 2. 实际落地

- **`AuthService`（helloai-core/system�?*：内�?`adminTokens` Map 删除，改�?Redis（key `auth:admin:token:{token}`，对�?`agent:heartbeat:`/`mq:dedup:` 命名风格；value �?`AdminSession` JSON，专�?`new ObjectMapper()` 不复用全局 Bean 避免 Long→String 定制策略干扰；TTL 8 小时�?
- **滑动续期**：`validateAdminToken` 命中�?`redis.expire(key, TTL)` 重置 8h，活跃会话不会使用中途过期；未命中抛 401；缓存值损坏（序列化格式变�?脏数据）则清 key + 401 强制重登
- **登出/改密**：`adminLogout` 改为 Redis delete（改密踢会话链路复用同一方法，行为不变）
- **契约零变�?*：token 生成方式（SecureRandom 32 字节 hex）、`AuthInterceptor`/`McpAuthFilter`/前端/验证脚本均无需改动
- **明确不做**：Agent apiKey �?Redis 短缓存（仍每请求直查 DB，非掉线问题，纯优化项保持演进）；MCP SESSION_AUTH 保持内存 + SessionAuthCleaner（绑�?SSE 长连�?sessionId，重启后连接本身就断，存 Redis 无意义）

#### 3. 测试与验�?

- 新增 `AuthServiceTest` 9 用例（登录写 Redis key/TTL 断言 / 密码错不�?Redis / 用户不存�?/ 校验命中滑动续期 / 未命�?401 / 损坏值清 key+401 / 登出�?key / agentKey 无效 401 / 禁用 403），Mockito mock `StringRedisTemplate`+`ValueOperations`（对�?`HeartbeatServiceActiveTest` 范式），全绿
- `mvn -pl helloai-core -am test -Dtest=AuthServiceTest` 通过；全模块 `mvn compile` �?ERROR
- 坑位：PowerShell �?`-Dsurefire.failIfNoSpecifiedTests=false` 带点号的 -D 参数会被拆分，必须整体加引号

#### 4. 影响

- **行为变化**：后端重启后管理员会话不再丢失；会话新增 8h 滑动过期（此前永不过期）；Redis 不可用时鉴权报错（新增强依赖，与项目 Redis 定位一致）
- **接口/DB/前端**：零变更

#### 5. 遗留与下一�?

- Agent apiKey 校验�?Redis 短缓存（TTL 5min + 禁用时主动失效）保持演进�?
- TTL 8h 目前为代码常量，如需环境差异化再外置 `application.yml`

---

### 6.18 任务级联删除 FK 违反修复 + 拆解链前端补全（2026-07-29�?

#### 1. 背景

两个诉求合并一轮交付：

- **FK 违反 bug（用户真实环境报错）**：删除带附件子任务的任务时抛 `PSQLException: update or delete on table "sub_task" violates foreign key constraint "attachment_sub_task_id_fkey"`。取证结论：`V1__init_all.sql` �?FK 引用 `sub_task(id)` 的表�?6 张（review_record L265、patrol_record L381、conversation_archive L541、attachment L578、agent_execution_record L627、conversation_message L828），�?§6.12 �?`TaskService.deleteTaskCascade` 只删�?execution/review 两张，遗�?4 张——只要子任务有附�?巡检/会话数据，删任务必炸
- **拆解链前端断链（N16 遗留收口�?*：V26 后端四接口齐全，但前端触发拆�?查看草案/确认拒绝一步都没接，只能靠 API/脚本操作

#### 2. 实际落地

**阶段 0：FK 违反修复（helloai-core�?*

- `AttachmentMapper` / `PatrolRecordMapper` / `ConversationArchiveMapper` / `ConversationMessageMapper` 各补 `physicalDeleteByTaskId`，照 `ReviewRecordMapper` 既有范式：`@Delete("DELETE FROM {table} WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")` + Javadoc 标注仅供任务级联删除使用；attachment �?`sub_task_id` 可空，IN 子查询天然只删关联行不碰游离附件
- `TaskService.deleteTaskCascade`：新�?4 �?Mapper 构造注入，�?`subTaskMapper.physicalDeleteByTaskId` 之前依次调用 4 个新删除，同步更新方�?Javadoc 删除顺序说明
- `getRelatedCounts`/`TaskRelatedCounts` DTO/前端删除弹窗不动（影响面统计字段扩展非本 bug 范围，保持修复原子性）

**拆解链前端补全（�?helloai-ui，后端零改动�?*

- `types/index.ts`：`SubTaskStatus` �?`PENDING_PLAN_REVIEW` + `SUB_TASK_STATUS_MAP` �?草案待审"（顺带修复全量子任务列表遇草案�?tag �?undefined 的隐患）；`TaskStatus` �?`PLANNING`；新�?`TASK_STATUS_MAP` 五态中文映射；`SubTask` 接口�?`deliverable`/`acceptance`/`priority` 三个可选字段（后端已返回、前端类型缺失）
- `api/task.ts`：`taskApi` 新增 `plan`（POST /tasks/{id}/plan，单请求覆盖 `timeout: 120_000`，LLM 拆解耗时超全局 30s�? `planDrafts` / `confirmPlan` / `rejectPlan` 四方�?
- 新组�?`views/task/components/PlanReviewDialog.vue`（照 TaskDeleteDialog 对话框范式）：`@open` 拉草案；表格列序�?标题/内容/交付�?验收标准/优先�?tag/依赖（`dependsOn` 草案 id 映射为表内序号展示如"依赖 #1,#2"）；footer 双动�?确认并分�?（ElMessageBox 二次确认 �?confirmPlan �?emit done）与"拒绝重拆"（确�?�?rejectPlan 回显 cancelledCount）；空草�?el-empty 兜底引导拒绝重拆
- `TaskList.vue`：状态列废弃 `DONE?'success':'warning'` 三元硬编码改 `TASK_STATUS_MAP`；操作列按状态显示——PENDING �?AI 拆解"（确认提示约需几十�?�?按钮 loading �?成功后刷新并直接开审阅弹窗）、PLANNING �?审阅草案"�?已存在子任务""并发拆解�?等错误由后端 BizException + 拦截器统一弹错，前端不重复防御

#### 3. 测试与验�?

- `mvn -pl helloai-core -am test`（JDK 17）→ 全绿 BUILD SUCCESS（无 deleteTaskCascade 既有单测，无直接构�?TaskService 的测试，构造器变更零破坏）
- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过（chunk 体积警告为既有问题）
- ~~未完成：浏览器闭环实测（新建→拆解→审阅→确�?拒绝）与带附件子任务的删除回归——本�?6565 后端未启动~~ �?2026-07-29 同日补验（真实环�?6565 + deepseek）：
  - **脚本回归**：`verify-planner-decompose.ps1` 等价迁移�?macOS zsh �?`scripts/shell/verify-planner-decompose.sh`（curl+jq，照 verify-dashboard-duty-leases.sh 模板规范），真实环境 e2e 12 步全绿——confirm 路径拆解 5 条草案全 PENDING_PLAN_REVIEW �?Task PLANNING �?确认转正 PENDING/ASSIGNED + Task IN_PROGRESS + 草案清零；reject 路径 cancelledCount 匹配 + Task 回退 PENDING；对 IN_PROGRESS 任务重复拆解被拒
  - **迁移坑位**：zsh `status` 为只读内置变量（局部变量改�?st）；�?ps1 缺凭证绑定步骤——新注册 planner Agent �?deepseek 托管凭证时拆解必 500「Agent 未配置启用态托管凭证」，zsh 版补 STEP2.1 绑定 `/api/credentials/agents/{id}/api-key`（env `DEEPSEEK_API_KEY` 优先，缺省回退 application.yml 默认 key，对�?verify-inner-loop-e2e.ps1 做法�?
  - **浏览�?UI 闭环实测通过**：confirm 路径 `ui-e2e-confirm-01` 拆解 6 条草案（含合理优先级与拓扑依赖）�?审阅弹窗自动打开 �?确认分发后任务「进行中」；reject 路径 `ui-e2e-reject-01` 4 条草�?�?拒绝重拆 �?回「待规划」且可重拆；拆解期间状态「拆解中�? 行内「审阅草案」按钮可随时重开弹窗；全�?console 0 error
  - 仍未覆盖：带附件子任务的删除回归（需造带附件数据，见 §5 遗留�?

#### 4. 影响

- **接口/DB**：零变更（纯 Mapper 方法新增 + 前端接线�?
- **行为变化**：删任务不再因子任务带附�?巡检/会话数据而炸 FK；任务列表可视化完成"新建 �?AI 拆解 �?草案审阅 �?确认/拒绝 �?跟踪执行"全链，N16"前端规划确认�?遗留项收口（以列表内对话框形态交付，非独立页面）

#### 5. 遗留与下一�?

- **孤儿文件**：attachment 行删除后对象存储里的物理文件（bucketName/objectKey）成为孤儿，文件清理需单独立项，与本次 DB 完整性修复解�?
- ~~浏览器闭环实�?+ `verify-planner-decompose.ps1` 回归待真实环境（6565 后端 + deepseek Provider 可用）~~ �?2026-07-29 同日收口（zsh 版脚�?e2e 12 步全�?+ UI 闭环实测通过，见 §3）；带附件子任务删除回归仍待造数验证
- `dependsOn` 在草案审阅中只读展示不可编辑（依赖编辑属演进项）；第二步"对话式需求澄清窗�?已有概要设计，建议本轮验收后单独立项

---

### 6.19 对话式需求澄清窗口（V29�?026-07-29�?

#### 1. 背景

§6.18 收口后拆解链已可视化，但入口仍要求用户一次性写清需求——模糊想法没有承接面。本轮落�?第二步立�?：用户带着半成品想法进对话窗口，LLM 扮演资深需求分析师多轮追问澄清（边�?交付�?验收标准），信息足够即产出终稿，一键创建任务并顺路自动拆解，与 §6.16/§6.18 的拆解审阅链无缝衔接。用户已拍板：独立页面交付（�?TaskList 内嵌）、终稿确认后前端自动调既�?plan 接口�?

#### 2. 实际落地

**DB（Flyway V29__requirement_clarify.sql�?*

- `requirement_conversation`：title（首条用户消息截�?50 字）/ status `ACTIVE/FINALIZED/ABANDONED` + CHECK / `task_id` **软引用无 FK**（刻意不加入 `deleteTaskCascade` �?FK 引用面，删任务后允许悬挂，注释注明）/ final_title + final_description（LLM 最近一次终稿暂存，等用户确认）/ round_count（用户消息轮数）；partial 索引 `(status, create_time) WHERE deleted=0`
- `requirement_message`：conversation_id FK / role CHECK `user/assistant` / content / seq；索�?`(conversation_id, seq)`。两表均�?`V19__agent_command_outbox.sql` 范例：BIGINT 应用侧雪花主�?+ 审计列全�?+ `update_update_time_column` 触发�?+ 逐列 COMMENT

**后端（helloai-core + helloai-api�?*

- `core/planner/` 新增 entity（RequirementConversation/RequirementMessage，继�?BaseEntity�? mapper（两个空 BaseMapper，`HelloAIApplication` @MapperScan 补第四包 `com.helloai.core.planner.mapper`�? �?CRUD `RequirementConversationService`（空 ServiceImpl�? `RequirementMessageService`（`addMessage` 查最�?seq+1 落库，照 ConversationService.addMessage 范式但不需�?REQUIRES_NEW�?
- `RequirementClarifyService`（编排收�?core�?*完整复用 PlannerAnalysisService 五段�?*，类不加事务——LLM 耗时不占 DB 事务）：`create`（截断标题建会话 �?走一轮）/ `sendMessage`（requireActive + 轮数上限 20 校验 �?doRound：存 user 消息 + round_count+1 �?`pickPlannerAgent`�?2 行刻意复制不抽象，注释注明）�?transcript 渲染 `prompts/requirement-clarify.md`（占位符 `{{CONVERSATION_HISTORY}}`，`用户�?助手：` 逐行拼接）→ executeSync（context �?conversationId + scene=requirement_clarify）→ `stripToJsonObject`（照 stripToJsonArray 改花括号版）+ Jackson 解析 `ClarifyReply`——type=question �?assistant 消息；type=final �?assistant 消息（空则「已生成终稿」）+ final_title/final_description 回填会话行；LLM/解析失败 user 消息保留、抛 BizException 可重发）/ `finalize`（校�?ACTIVE + 终稿非空 �?�?Task PENDING + best-effort 通知全部 PLANNER 写收件箱（照 TaskController.create 通知段搬 core）→ 会话回填 task_id + FINALIZED �?timeline `task_created_from_clarify`�? `abandon` / `listConversations`（create_time 倒序 LIMIT 50�? `detail`
- LLM 输出协议（严�?JSON 单对象禁围栏）：追问 `{"type":"question","message":...}`；终�?`{"type":"final","title":"50字内","description":"结构化需�?,"message":"终稿说明"}`；Prompt 引导每轮最�?3 问、信息足够即出终�?
- `RequirementConversationController`（`/api/requirement-conversations` 六薄端点：POST / 创建、POST /{id}/messages、GET / 列表、GET /{id} 详情、POST /{id}/finalize、POST /{id}/abandon�? `ClarifyMessageRequest` DTO（@NotBlank�?

**前端（helloai-ui�?*

- `types/index.ts` �?RequirementConversationStatus/RequirementConversation/RequirementMessage/ClarifyConversationDetail；`api/clarify.ts` 六方法（create/send 单请�?`timeout: 120_000` �?taskApi.plan 范式�?
- 新页�?`views/requirement/RequirementChat.vue`：左栏会话列表（新会话按�?+ ABANDONED 置灰）；右栏气泡流（user �?`--ha-primary-muted` / assistant �?`--ha-surface-elevated`，全�?design-system 变量�? 发送中 loading 占位气泡 + Enter 发送；会话�?final_title 即渲染终稿卡片（标题+描述只读，不满意继续对话�?LLM 修正）——ACTIVE 态主按钮「创建任务并自动拆解」：ElMessageBox 确认 �?finalize �?task �?页内 loading �?`taskApi.plan` �?`router.push('/tasks?review={taskId}')`（plan 失败拦截器已弹错、仍�?/tasks 可手动重拆）；FINALIZED 态只�?+ 「查看任务」链�?
- 接线：`router/index.ts` �?`/requirement-chat` 路由；`MainLayout.vue` 任务管理下加菜单项（ChatDotRound）；`TaskList.vue` 工具栏加「对话新建」按�?+ onMounted �?`route.query.review` 自动 `openPlanReview`（找不到静默忽略�?

#### 3. 测试与验�?

- 单测 `RequirementClarifyServiceTest`(13，照 PlannerAnalysisServiceTest 链式 mock 范式)：question/final 双路径、fence 容错、非 JSON 报错、轮数上限、finalize 无终稿拒�?成功�?task、非 ACTIVE 拒发；`mvn -pl helloai-core -am test`（JDK 17）全�?BUILD SUCCESS；坑位：`executeSync` 有重载，verify 必须 typed matchers `any(Agent.class), any(AgentTask.class)` 否则 ambiguous 编译�?
- `npx vue-tsc --noEmit` 0 �?+ `npm run build` 通过（chunk 警告为既有）
- **zsh 脚本真实环境 10 步全�?*：`scripts/shell/verify-requirement-clarify.sh`（照 verify-planner-decompose.sh 模板�?STEP2.1 凭证绑定）——创建会话（详尽需求）�?1 轮追问后推进出终稿「内部日报统计模块开发」（conversationId=2082494629529395201）→ finalize �?task PENDING（taskId=2082494653785055233）→ 会话 FINALIZED + taskId 回填 �?FINALIZED 拒发（code!=200）→ plan 拆解 6 条草�?�?abandon 回归 �?列表断言
- **浏览器闭环实�?8 步全�?*（console 0 error）：新会话模糊需�?�?LLM 3 条追�?�?补充 �?终稿卡片「团队周报收集与自动汇总工具」→ 创建并拆�?�?�?`/tasks?review=…` 自动�?5 条草案审�?�?会话侧变「已建任务」只读。轻微现象：草案弹窗关闭偶需点两次（疑似动画时序，不影响流程�?

#### 4. 影响

- **接口/DB**：新�?V29 两张�?+ 六个新端点，既有接口零改动；`deleteTaskCascade` 零改动（task_id 软引用悬挂由产品语义接受�?
- **行为变化**：立项入口从"一次写�?扩展�?对话澄清"，与拆解审阅链（§6.16/§6.18）串�?模糊想法 �?终稿 �?任务 �?草案 �?分发"完整链路

#### 5. 遗留与下一�?

- 首期不做（已拍板裁剪）：SSE 流式输出（Doorbell SSE �?Agent 侧信号通道不可蹭）、终稿手动编辑、会话删除（�?abandon）、列表分页（LIMIT 50�?
- 草案弹窗关闭偶需点两次的动画时序问题待顺手排查（非本链路引入，�?.18 组件既有�?
- 带附件子任务删除回归仍待造数验证（继�?§6.18 遗留�?

---

### 6.20 菜单调整 + Agent 注册接入分类 + LLM Provider 手动注册入口�?026-07-30�?

#### 1. 背景

用户提出三点：①「对话新建」菜单移到「概述」下、「任务管理」上；②Agent 注册弹窗增加接入分类（外�?AI Agent / 内部 LLM / 网页�?Planner），�?PLANNER 角色可见「网页端 Planner」选项且选中即提示功能不可用；③内部 LLM Agent 缺少手动注册入口，希望按"已生效的 api-key 配置"实现（用户原话为 pom.xml，实�?`application.yml` �?`helloai.providers`），api-key 参�?`E:\yhzx\1027\springai` 项目�?application 配置，后续计划集�?minimax / kimi(moonshot) / 通义千问(dashscope)。调研中发现隐�?bug：`AgentProviderProperties` �?`@ConfigurationProperties(prefix="helloai.providers")` + 字段�?`providers`，实际绑定路径为 `helloai.providers.providers.*`，yml 里的 `helloai.providers.deepseek.*` 从未绑定成功，只�?deepseek 默认值与 `DeepSeekProviderChatClientFactory` 内置默认恰好一致而未暴露�?

#### 2. 实际落地

**后端（helloai-common + helloai-core + helloai-api + helloai-start�?*

- `AgentProviderProperties`：前缀 `helloai.providers` �?`helloai`（修复绑定路�?bug）；`ProviderConfig` 增加 `apiKey` 字段 + `hasApiKey()`（配置了平台�?API Key 即视为该 provider "已生�?�?
- `application.yml`：`helloai.providers.deepseek` �?`api-key`（`${DEEPSEEK_API_KEY:...}`）；预置 `moonshot` / `minimax` / `dashscope` 三段配置（key/base-url/model 取自 springai 项目，环境变量可覆盖；缺对应 Factory 实现前目录接口标记不可用�?
- 新增 `LlmProviderCatalogService`（helloai-core/agent/chat，编排收�?core 不进 Controller）：`ProviderCatalogItem` record（provider/defaultModel/apiKeyConfigured/factorySupported/available）；`listProviders()`（available = apiKeyConfigured && factorySupported，factory 判定�?`ProviderChatClientFactory.supports`）；`bindPlatformApiKeyIfAbsent`（不可用�?BizException；已�?ACTIVE 凭证跳过不覆盖，保护脚本注册后自行绑自定义密钥的既有链路；否�?`CredentialVaultBindingService.bindAgentApiKey` 绑平�?key）；`provisionPlatformCredential(Agent)`（`AgentProviderResolver.resolveProvider` �?modelType 解析 provider、回退 `helloai.agent.execution.provider` 默认；provider 未生效仅 log.warn 跳过不阻断注册）
- `AgentController.applyRegistrationExtras`：末尾对 `accessType=API_KEY_LLM` �?`provisionPlatformCredential`（尽力而为），注册即满�?`AgentSelector.hasUsableCredential` �?ACTIVE 凭证门槛，手动注册的 LLM Agent 立即可被调度
- `AdminAgentController`：新�?`GET /api/admin/agents/llm-providers` 目录接口（返�?ProviderCatalogItem 列表�?

**前端（helloai-ui�?*

- `MainLayout.vue`：「对话新建」（/requirement-chat）菜单项移到 /dashboard �?/tasks 之间
- `api/agent.ts`：`register` 参数扩展 `accessType`/`modelType`；新�?`listLlmProviders()`
- `AgentList.vue` 注册弹窗：新增「接入类型」下拉——外�?AI Agent（CLI 接入�?CLI_CLIENT 默认 / 内部 LLM（API Key�?API_KEY_LLM / 网页�?Planner=WEB_BROWSER（仅 `form.role==='PLANNER'` 显示）；WEB_BROWSER 选中显示 el-alert「网页端 Planner 功能暂不可用�? 注册按钮 disabled + 提交前二次校验（仅前端拦截，后端枚举通道保留与现状一致）；API_KEY_LLM 显示 provider 下拉（目录懒加载，不可用�?disabled 并标注原因「缺�?Factory 实现�?「未配置 API Key」），注册体发�?`modelType: provider:defaultModel`，成功后不开 onboarding 弹窗改为提示「平台密钥已自动绑定」；角色切走 PLANNER �?accessType 自动回退 CLI_CLIENT

#### 3. 测试与验�?

- 后端 `mvn -DskipTests compile` �?reactor BUILD SUCCESS
- 前端 `npx vue-tsc -b` EXIT=0
- 相关单测 `mvn -pl helloai-core -am test -Dtest=AgentChatClientServiceTest,PlatformAgentExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`：Tests run 2 / Failures 0 / BUILD SUCCESS（坑位：`-Dtest` 过滤�?helloai-common 无匹配用例会 BUILD FAILURE，须�?`failIfNoSpecifiedTests=false`�?

#### 4. 影响

- 新增 1 个只读接口（llm-providers 目录），注册接口 body �?`accessType`/`modelType` �?脚本专用"升级为前端正式语义；�?DB 变更、无 Flyway
- `AgentProviderProperties` 前缀修复�?yml �?providers 配置真正生效（此前静默失效吃默认值）；配置读取语义变化仅影响 `helloai.providers.*` �?
- E2E 脚本�?idempotent=true 注册 + 自行�?key 的既有链路不受影响（已有 ACTIVE 凭证不覆盖）

#### 5. 遗留与下一�?

- moonshot / minimax / dashscope 仅预置了配置段，各需补一�?`ProviderChatClientFactory` 实现类后目录自动标记可用（minimax base-url �?anthropic 兼容端点，Factory 需按对应协议实现）
- WEB_BROWSER 执行链仍未落地（N8 维持"仅枚举预�?，本轮只做前端拦截提示）
- 平台密钥当前明文存于 yml 默认值（环境变量可覆盖），生产化前应改为仅环境变量注�?

---

### 6.21 moonshot / minimax / dashscope ProviderChatClientFactory 补齐�?026-07-30�?

#### 1. 背景

闭环 §6.20 遗留第一条：前端注册内部 LLM Agent 时，moonshot / minimax / dashscope �?provider 下拉中标记「缺�?Factory 实现」不可选。用户确认参�?`E:\yhzx\1027\springai` 项目的接入方式补齐三�?Factory�?

#### 2. 实际落地

**依赖（helloai-core/pom.xml�?*

- 新增 `spring-ai-openai` + `spring-ai-anthropic`（均为非 starter 纯客户端库，无自动装配，不影响既�?deepseek starter 提供的唯一 `ChatClient.Builder`；版本由 spring-ai-bom 1.1.8 管理�?
- 未引�?spring-ai-alibaba dashscope starter：其 BOM 1.0.0.2 绑定 spring-ai 1.0.0，与本项�?1.1.8 基线有冲突风�?

**新增 Factory（helloai-core/agent/chat/provider，均�?DeepSeek 工厂同构：ProviderChatModelCache 三元组缓�?/ 超时 / RetryTemplate / ObservationRegistry / ToolCallingManager�?*

- `AbstractOpenAiCompatibleChatClientFactory`：OpenAI 兼容协议公共骨架（OpenAiApi + OpenAiChatModel），子类只提�?provider 标识 / 默认模型 / 兜底 base-url
- `MoonshotProviderChatClientFactory`：`https://api.moonshot.cn`，默�?`moonshot-v1-8k`（参�?springai KimiClientsConfig�?
- `DashScopeProviderChatClientFactory`：DashScope OpenAI 兼容模式 `https://dashscope.aliyuncs.com/compatible-mode`（拼 /v1/chat/completions），默认 `qwen-plus`
- `MinimaxProviderChatClientFactory`：Anthropic 兼容接口 `https://api.minimaxi.com/anthropic`（AnthropicApi �?/v1/messages），默认 `MiniMax-M2.5`（参�?springai MinimaxClientsConfig�?

**配置（application.yml�?*

- `helloai.providers.dashscope` �?`base-url`（`${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}`�?
- providers 段注释更新为"四个 provider 均有 Factory 实现"

#### 3. 测试与验�?

- `mvn -DskipTests compile` �?reactor BUILD SUCCESS（EXIT=0�?
- `AgentProviderResolverTest` + `ProviderChatModelCacheTest` 回归通过（EXIT=0�?
- 无需�?`LlmProviderCatalogService` / 前端：目�?available 判定�?`ProviderChatClientFactory.supports`，Factory Bean 注册后三�?provider 自动亮起

#### 4. 影响

- 前端注册弹窗 provider 下拉�?moonshot / minimax / dashscope 变为可选，注册后自动绑平台密钥并可被调度执�?
- 纯新增类 + 配置补段，deepseek 既有链路零改�?

#### 5. 遗留与下一�?

- 三个�?provider 尚未做真�?API 连通性验证（key 有效�?/ 模型名可用性），首次实际调度执行时需观察日志
- §6.20 其余遗留不变（WEB_BROWSER 执行链、平台密钥生产化注入�?

---

### 6.22 PATROL 角色移除：Agent 角色收敛为三角色�?026-07-30�?

#### 1. 背景与决�?

用户决策：整体角色从 4 个收敛为 3 个（PLANNER / EXECUTOR / REVIEWER），移除 PATROL 巡检角色——其兜底目标已由重分配熔断（V24）、死信池人工兜底（V25 DEAD_LETTER）、定时补偿任务覆盖�?

删除前核验（SearchAgent 全量引用�?+ postgres_helloai MCP 查库）：

- Java 代码零直接引�?`AgentRole.PATROL` / 字符�?"PATROL"（角色转换全�?`valueOf` 动态转换），无任何�?PATROL 分支的调度逻辑 / 定时任务 / 消费�?
- patrol MQ 队列 / 绑定为纯死拓扑（无生产者无消费者）
- 数据库中 PATROL 相关数据全为 0：PATROL 角色 agent 0 个、patrol_record 0 行、task_timeline PATROL �?0、prompt_template �?1 条未用种子行 �?采取彻底清理策略（连 patrol_record 表生态一起删，无需数据迁移�?

顺带完成上轮遗留 rename：`AbstractOpenAiCompatibleChatClientFactory` �?`AbstractOpenAiCompatibleProviderChatClientFactory`（两个子�?extends 同步）�?

#### 2. 实施内容

**后端删除**

- `AgentRole`：删 PATROL 枚举值（�?PLANNER/EXECUTOR/REVIEWER/SYSTEM�?
- `RabbitMQConfig`：删 `PATROL_QUEUE` 常量、`patrolQueue()`、`patrolBinding()` 三处死拓�?
- 删文件：`skills/patrol/SKILL.md`、`PatrolRecord`、`PatrolRecordMapper`、`PatrolRecordService`
- `AgentService`：删 patrolRecordMapper 注入、patrolCount 统计（getRelatedCounts / deleteAgentCascade）、级联删除链 patrol 步骤
- `TaskService`：删 patrolRecordMapper 注入与级联删除步骤，Javadoc "6 张表" �?"5 张表"
- `AdminAgentController` + `AgentDeleteResult` / `AgentRelatedCounts` DTO：删 patrolCount 字段�?set
- `AgentServiceTest`：同步删 Mock / 构造参�?/ 断言
- 注释清理：McpMcpServer（pullTasks 参数描述 + GetAgentStatusResult）、AgentMcpServerService、TaskTimelineService、TaskTimelineItem、TaskTimeline

**数据库（V30__remove_patrol_role.sql，V1 历史迁移不动�?*

- 重建 `chk_agent_role`（三角色）与 `chk_task_timeline_role`（三角色 + SYSTEM�?
- DELETE prompt_template PATROL 种子行（id=2000000000000000004�?
- DROP TABLE patrol_record；同步更新三�?COMMENT

**前端�? 文件�?*

- `types/index.ts`：AgentRole 联合�?PATROL、两�?DTO �?patrolCount、颜色映射去 PATROL、删 PatrolRecord 接口
- `PromptList.vue`（筛�?表单/标签�?3 处）、`AgentList.vue`�? 处）、`AgentCard.vue`、`AgentEditDialog.vue`、`AgentDeleteDialog.vue`（巡查记录统计行）、`AgentDetail.vue`、`AgentSelect.vue`、`QuickDispatchDialog.vue`

**杂项**

- `verify-subtask-deadletter.ps1` / `verify-subtask-redispatch-auto-execution.ps1` 默认 `$Role` �?EXECUTOR
- `cleanup-test-data.sql` �?patrol_record（注�?+ TRUNCATE 列表�?
- CODE_STYLE：skills 目录树、MQ 队列表、模型选型表去 PATROL 行、字段命名示例去 `patrol_agent_id`；基线文档删 "PATROL 自动巡检链路" �?
- 设计文档同步：`HelloAI_架构设计参�?md`（�?.1 角色模型标注 HelloAI 已收敛三角色、�?.3 第三阶段协作闭环�?Patrol）、`HelloAI_外部项目借鉴技术细�?md`（�?.4 / §3.2 / §4.1 中描�?HelloAI 现状的行改为三角色；OpenMOSS 自身四角色事实描述保留不动）

#### 3. 测试与验�?

- `mvn -DskipTests compile` �?reactor BUILD SUCCESS（EXIT=0�?
- `AgentServiceTest` 回归通过（EXIT=0�?
- `vue-tsc --noEmit` 类型检查通过（EXIT=0�?
- 全仓 grep 确认：残留仅历史资产（V23 历史迁移、迭代记录历史条目、archive / 借鉴文档），代码 / 配置 / 脚本零残�?

#### 4. 影响

- Agent 注册 / 编辑 / 筛选、提示词模板的角色选项收敛为三角色；已有三角色数据零影�?
- V30 随应用启动自动执行；执行前生产库 PATROL 数据已核验为 0，DROP 表无数据损失
- 兼容性说明：若外�?MCP 客户端以 role=PATROL �?pullTasks 会因 `AgentRole.valueOf` 抛异常，但库中不存在 PATROL Agent，实际无此调用方

#### 5. 遗留与下一�?

- §6.21 遗留不变（三个新 provider 真实 API 连通性验证、WEB_BROWSER 执行链、平台密钥生产化注入�?

---

### 6.23 chat.provider 归位重构：Provider 接入族自包含�?026-07-30�?

#### 1. 背景与决�?

用户观察�?chat 包下 Factory �?Service 混放显乱。核查结论：`provider` 子包本身纯净（全 Factory），乱源�?chat 父包混放接口 / Service / 缓存 / 工具四种类型，且 `ProviderChatClientFactory` 契约�?`ProviderChatModelCache` 缓存的唯一消费方就�?Factory 族，存在归属错位。决策：按项�?按职责分�?惯例做一次归位（不新增包、不按类类型拆包）�?

#### 2. 实施内容

- 移动（含 package 声明修正）：`ProviderChatClientFactory`、`ProviderChatModelCache` �?`core.agent.chat` �?`core.agent.chat.provider`；测试镜像移�?`ProviderChatModelCacheTest` 同步�?test �?provider �?
- import 修正�? �?Factory 删同包冗�?import；`AgentChatClientService`（顺带清�?2 行同包冗�?import）、`LlmProviderCatalogService`、`AgentChatClientServiceTest`、`PlatformAgentExecutionServiceTest` 改指向新 FQN
- 归位后语义：`chat` = 业务 ChatClient 服务层（AgentChatClientService / LlmProviderCatalogService / AgentProviderResolver）；`chat.provider` = Provider 接入族（契约 + 4 厂商实现 + 抽象基类 + ChatModel 缓存�?
- CODE_STYLE §3.x 语义边界补一�?chat / chat.provider 分界；`AgentProviderProperties` Javadoc �?FQN 无需�?

#### 3. 测试与验�?

- �?FQN `agent.chat.ProviderChat*` 全仓 Java 零残�?
- `mvn -DskipTests compile` �?reactor EXIT=0
- `AgentChatClientServiceTest` / `ProviderChatModelCacheTest`（@Nested 13 用例�? `PlatformAgentExecutionServiceTest` 回归全绿（surefire XML 核验 failure/error = 0�?

#### 4. 影响与遗�?

- 纯包移动零逻辑变更；后续新�?LLM 厂商只动 chat.provider 子包 + 一�?yml
- 无新遗留；�?.21 / §6.22 遗留不变

---

### 6.24 三个�?Provider 真连通验证：moonshot / minimax / dashscope × 三角色（2026-07-30�?

#### 1. 背景

闭环 §6.21 遗留�?三个�?provider（moonshot/minimax/dashscope）真�?API 连通性验�?。验证目标：平台密钥能否支撑 PLANNER / EXECUTOR / REVIEWER 三角�?Agent 的注册与真实对话�?

#### 2. 实施内容

- 新增 `tmp/verify-three-providers.ps1`�? provider × 3 角色�?9 组合，每组注�?API_KEY_LLM Agent（幂等，注册时经 `LlmProviderCatalogService.provisionPlatformCredential` 自动补绑平台密钥）→ �?`/api/agent-executions/connectivity/{agentId}` 真实对话探测
- 后端以归位重构后的新 jar 运行�?6:09 重建），间接完成 §6.23 变更的运行时冒烟

#### 3. 验证结果

- 9/9 全通过：register=OK、chat=OK、mockMode=false，各模型真实回显探测口令（moonshot-v1-8k / MiniMax-M2.5 / qwen-plus），延迟 0.5s~2.7s
- MiniMax-M2.5 为推理模型，output 含思考前缀文本，连通判定不受影�?

#### 4. 影响与遗�?

- §6.21 遗留�?三个�?provider 真实 API 连通性验�?关闭；WEB_BROWSER 执行链、平台密钥生产化注入两项遗留不变
- 产生 9 �?probe-* 探测 Agent（幂等命名，可复用或后续清理�?

---

### 6.25 内部 LLM Agent 隐藏"生成接入内容"入口�?026-07-30�?

用户建议：接入内容面向外�?AI Agent（CLI 接入），API_KEY_LLM Agent 注册即完成（平台密钥自动绑定），不应展示该按钮。实施：

- 后端：`AgentListItemVO` �?`accessType` 字段（`AgentDetailVO` 继承获得），`AdminAgentController` 列表/详情映射�?set；`onboarding-content` 接口�?API_KEY_LLM 直接 fail（防御绕过前端直调）
- 前端：`AgentListItem` 类型�?`accessType?`；`AgentCard.vue`（列表卡�?hover 操作栏）�?`AgentDetail.vue`（详情操作区）的"生成接入内容"按钮�?`v-if="agent.accessType !== 'API_KEY_LLM'"`
- 验证：`mvn compile` �?reactor EXIT=0、`vue-tsc` EXIT=0

---

### 6.26 minimax 推理模型 thinking 分离修复：parseVerdict null 根因闭环�?026-07-30�?

#### 1. 背景与根�?

minimax（MiniMax-M2.5，Anthropic 协议推理模型）担�?REVIEWER 时自动核验必�?`sub_task_auto_review_unparseable`（parseVerdict 返回 null）。裸 API 探测（`tmp/probe-minimax-format.ps1`、`tmp/probe-minimax-500.ps1`）确认根因：minimax 返回 `[thinking, text]` 两个 content block，Spring AI 1.1.8 `AnthropicChatModel.toChatResponse` 把每�?block 映射为一�?Generation（thinking 块的 AssistantMessage metadata �?`signature`，redacted_thinking �?`data`）；我方原代�?`response.getResult()` 只取 generations[0]，拿到的是思考文本，正文 JSON �?generations[1] 被丢弃——不是模型输出脏，是取错�?Generation�?

#### 2. 实施内容

- 新增 `helloai-core/.../agent/chat/ChatResponseContentExtractor.java`：遍历全�?Generation，metadata �?`signature`/`data` �?thinking，其余拼正文；两�?ChatResponse 出口（`ApiKeyAgentExecutor`、`AgentExecutionConnectivityService`）统一改走�?extractor
- thinking 全链路贯通保留（按用户决策，供后续前端动态展示）：`AgentResult.thinking`�? �?success 重载）→ `ExecutionResultReport.thinking` �?`ExecutionResultHandler` 落对话流消息 `sub_task_execute_thinking`；`SubTaskReviewService` �?verdict 消息前落 `subtask_review_thinking`；connectivity/preview API 响应 DTO �?`thinking` 字段
- `parseVerdict` 未改（既�?`stripToJsonObject` 对干净正文足够）；unparseable 停留 REVIEW 的兜底逻辑未动——重�?降级转其�?LLM 按用户指示留待下�?

#### 3. 验证结果

- `mvn test` �?reactor：Tests run 292, Failures 0, Errors 0
- `tmp/verify-minimax-thinking.ps1`（connectivity 审查场景探测 probe-minimax-reviewer）：output=干净可解�?JSON（含 pass/score/comment）、thinking 单独返回 1170 字符，`VERIFY_RESULT=PASS`；moonshot 对照�?thinking_len=0、正文照常，OpenAI 协议无回�?
- 真实审查链路重放（`tmp/replay-submit-result.ps1` �?MCP `submitResult` �?`handleReport` �?`SubTaskSubmittedForReviewEvent`）：子任�?2082747212507799554 timeline 出现 `sub_task_auto_review_passed`（此前同任务�?unparseable），verdict 为干净 JSON，状�?REVIEW→DONE 闭环
- 环境坑记录：验证期间 6565 端口�?IDEA 旧代码进程占用，jar 启动失败但探活误报，靠启动日�?`Port 6565 was already in use` + `Get-NetTCPConnection` 查占用进程定案；用户重启 IDEA 后端后验证通过。另注：管理�?`POST /api/sub-tasks/submit/{id}` 只改状态不发核验事件，重放自动核验必须走执行结果上报路�?

#### 4. 影响与遗�?

- minimax 担任 REVIEWER �?parseVerdict null 问题关闭；thinking 已在对话流与 API 层保留，前端动态展示待后续迭代
- 遗留（下轮）：核�?verdict 不可解析时的重试 / 降级转其�?LLM Agent 处理策略
- 遗留（低优先）：`MinimaxProviderChatClientFactory` 未显式设�?maxTokens（Spring AI Anthropic 默认 500），实测未触发截断，暂不�?

---

### 6.27 子任务详情展示优化（方案1�? 拆解/澄清链配�?+ 执行产出物化方案设计�?026-07-30�?

#### 1. 背景

真实 AI 执行链已连通并产出正文，但子任务详情页仅平铺原始文本、时间线为开发者事件码，非开发者难读；且执行产出目前只�?`sub_task.context.lastExecution.output` 纯文本，`attachment` 表全�?0 写入，无法沉淀可下载文件。本轮先�?零后�?的展示优化（方案1），并把"后端产出物化 + 结构化多文件产出"（方�?/3）沉淀为设计文档，代码不动�?

#### 2. 实施内容

- **前端展示优化（helloai-ui，纯前端）：**
  - 新增 `components/MarkdownView.vue`（markdown-it + dompurify 渲染富文本，XSS 净化）�?`components/ReviewVerdictView.vue`（核验分析结构化卡片：pass/score/issues/comment 分区渲染，替代裸 JSON）；`package.json` 引入 `markdown-it` / `dompurify`�?
  - `SubTaskDetail.vue`：执行对话流�?Markdown 富文本渲�?+ 超长折叠；时间线事件"人话�?（`EVENT_META` 事件�?�?中文标签 + 一句话描述，payload 折叠�?技术详�?）；Agent ID �?注册名映射（`agentNameMap`，未命中降级�?ID）；"返回列表"携带所属主任务 `taskId` 归属跳转；执行产出保�?复制/导出 .md"（前�?Blob 导出，方�?），核验请求消息剥离 HTML 注释�?
  - `SubTaskList.vue` 增补、`api/subTask.ts` / `api/clarify.ts` / `types/index.ts` 微调配套�?
- **后端配套（需求澄�?拆解链）�?*
  - `PlannerAnalysisService.orderByDependency`：新增稳�?Kahn 入度拓扑排序，草案审�?分发按依赖正序（根节点在前，`dependsOn` 恒指向更靠前行）；仅按本批次内部依赖排序，批�?悬挂 id 视为无约束，残留成环兜底按原序追加绝不丢条目�?
  - `RequirementClarifyService.regenerate`：新�?会话�?FINALIZED 且原任务已被删除"的悬挂恢复路径——复用会话终稿重�?PENDING Task（不放开 ACTIVE 校验、不重跑 LLM，原任务仍存活时拒绝），抽出 `buildTaskFromDraft` 私有方法统一 finalize/regenerate 建任务逻辑，timeline 事件区分 `task_created_from_clarify` / `task_regenerated_from_clarify`；`RequirementConversationController` �?regenerate 薄入口�?
- **设计文档（仅文档，代码未实现）：**
  - 新增 `doc/design/HelloAI_执行产出物化与结构化多文件产出方�?md`：方�?（执行产出物化为真实文件 + attachment 记录 + 前端可下载）与方�?（LLM 可�?JSON manifest 结构化多文件产出）的决策完整设计草案——本地文件系统存�?+ `ArtifactStorage` 抽象（config 门控 `helloai.storage`，未来可�?MinIO）、方�? 是方�? 的降级形态（统一 `ParsedOutput{displayText, files}` 解析器）、物化放 `afterCommit` best-effort 不阻�?REVIEW、下载接�?`local://` 流式改造、前�?产出附件"卡片�?axios blob �?token；含改动清单、时序图、风险回滚、验证计划、小步实施顺序�?
- **工程�?* 新增 `docker-compose.server.yml` / `nginx.server.conf` 服务器部署配置；`.gitignore` 忽略 `.tmp/`（临时验证日�?+ 含明文密码的一次�?`deploy-ssh.exp`，不入库）�?

#### 3. 验证结果

- 展示优化为纯前端改动，浏览器渲染观感对齐（Markdown 富文�?/ 时间线人话化 / 核验分析卡片）；提交�?`git add` 明确排除 `scripts/shell/.tmp/`（其�?`deploy-ssh.exp` 硬编�?SSH 明文密码，属安全敏感临时产物）�?
- 后端 `orderByDependency` / `regenerate` 为既有链路增量，编译�?helloai-core 现状（未新起真实环境跑本�?e2e）�?

#### 4. 影响与遗�?

- 方案1（前端导�?展示）已交付�?*方案2/3 仅为设计文档，后端代码一行未�?*，待后续按设计文�?§11 顺序落地（届时回�?N 项状态：`attachment` 表从 0 写入 �?内置执行链产出物化）�?
- 遗留：方�?/3 实现、执行产出附件前�?产出附件"卡片；服务器部署配置（docker-compose.server.yml / nginx.server.conf）真实环境验收�?

---

### 6.28 LLM 输出 JSON 非法反斜杠转义容错修复（2026-07-31�?

#### 1. 背景与根�?

需求澄清链真实报错：moonshot 返回的终�?JSON 字符串值里含未转义�?Windows 路径（`E:\workspace\AgentTeams-main`），Jackson 严格解析�?`\w` 非法转义直接�?Unrecognized character escape"，澄清会话报 500。同款风险同样存在于核验�?`parseVerdict`（同�?`objectMapper.readValue` 严格解析，命中则 unparseable 停留 REVIEW）�?

#### 2. 实施内容

- 新增 `helloai-core/.../shared/util/LlmJsonSanitizer.java`：字符扫描修�?JSON 字符串值内的非法反斜杠转义（`\w` �?字面 `\\w`，路径内容不丢）；合法转义（�?unicode 转义后接 4 位十六进制的判定）原样保留；字符串外区域透传
- 接入两处 LLM JSON 解析出口：`RequirementClarifyService.parseReply` �?`SubTaskReviewService.parseVerdict`，均为先 stripToJsonObject �?fixInvalidEscapes
- 坐标注意项：Java 编译器对注释里的 `\u` 也做 Unicode 转义预处理，Javadoc 中不可出�?`\uXXXX` 字面（本轮踩坑：首版注释引发"非法 Unicode 转义"编译错）

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run 298�?6：LlmJsonSanitizerTest 5 �?+ parseVerdict 路径场景 1 例）, Failures 0, Errors 0, BUILD SUCCESS

#### 4. 影响与遗�?

- 澄清�?核验链对 LLM 输出 Windows 路径的容错闭环；拆解�?`PlannerAnalysisService` 解析暂未接入（拆解产出为数组且未实际报错，按需再接�?
- 遗留不变：核验不可解析时的重�?降级转其�?LLM 策略仍留待后续轮�?

---

### 6.29 澄清对话重试按钮 + Planner 手动选择下拉选（2026-07-30�?

#### 1. 背景与目�?

用户提出两个前端可感知的改进：① 澄清对话 LLM 失败�?00）后页面出现「重试」按钮（类似 DeepSeek），不必重发消息；② 对话新建页增�?Planner 手动下拉选，默认「系统自动」，选项含平台内 API_KEY_LLM PLANNER 与在班外�?Agent。已确认决策：外�?Agent 展示但置灰（无同步应答桥，暂不支持对话澄清）；手动选中�?Planner 同时用于后续任务拆解（同一 Planner 从澄清跟到拆解）�?

#### 2. 实施内容

后端�?

- 新增 `helloai-core/.../planner/PlannerAgentPicker.java`：收�?`RequirementClarifyService` �?`PlannerAnalysisService` 两处原刻意复制的 pickPlannerAgent 为共享选型器。`pick(pinnedAgentId)` pinned 有效直用、失�?log.warn 回退自动；`autoPick()` 候选（PLANNER + API_KEY_LLM + ACTIVE + �?SLEEPING + 有启用态凭证）等权重、优�?inProgressCount 最小者；`pickForTask(taskId)` �?requirement_conversation.task_id 软引用反查会话钉住的 Planner（不�?Task 加字段）；`validateSelectable` �?create 严格校验；`listOptions()` 输出下拉选数据源（内�?PLANNER selectable=有凭�?+ 在班外部 Agent 置灰�?
- `RequirementConversation` 新增 `plannerAgentId` 字段 + Flyway `V31__requirement_conversation_planner_agent.sql`
- `RequirementClarifyService`：`create(firstMessage, plannerAgentId)` 非空时校验并落库钉住；新�?`retryRound(id)`（仅当最后一条消息为 user，即上轮 LLM 失败；不新增消息、不�?round_count，复�?runLlmRound）；`listPlannerOptions()`
- `PlannerAnalysisService`：删除本�?pickPlannerAgent，改 `plannerAgentPicker.pickForTask(taskId)`（拆解跟随钉�?Planner�?
- `RequirementConversationController`：create 透传 plannerAgentId，新�?`GET /requirement-conversations/planner-options` �?`POST /{id}/retry`

前端（helloai-ui）：

- `types/index.ts`：RequirementConversation �?plannerAgentId、新�?PlannerOption 接口
- `api/clarify.ts`：create �?plannerAgentId 参数、新�?retry / plannerOptions
- `RequirementChat.vue`：新会话输入�?Planner 下拉选（外部 Agent 置灰带原因）、已有会话展示钉�?Planner 标签；canRetry 数据驱动（ACTIVE 且最后一条为 user 消息，刷新后仍可重试）渲染重试条；handleSend catch 重构（create 失败�?title 找回已落库会话，避免重复建会话，条件回填输入框）

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run 312�?14：PlannerAgentPickerTest 11 例新�?+ RequirementClarifyServiceTest 新增 4 �?- 迁移收敛 1 例）, Failures 0, Errors 0, BUILD SUCCESS
- 前端 `npx vue-tsc -b`：本轮改动三文件零错误（仅存�?MarkdownView.vue �?markdown-it/dompurify 模块未安装报错，与本轮无关）
- 踩坑记录：Mockito `RETURNS_SELF` �?MyBatis-Plus `LambdaQueryChainWrapper` 泛型链式调用不生效（eq 返回 null �?NPE），须逐方�?`doReturn(chain).when(chain).xxx()`，且 orderByDesc 需显式类型实参规避重载歧义；`Stream.min` 单元素不调用比较器，单候选场�?stub inProgressCount 会触�?UnnecessaryStubbing

#### 4. 影响与遗�?

- V31 迁移与新端点需重启后端后生效（Flyway 自动执行�?
- 外部 Agent 参与对话澄清需先建同步应答桥（AgentExecutorRouter 目前�?ApiKeyAgentExecutor），置灰文案已预�?
- 遗留不变：核验不可解析的重试/降级策略、主任务交付物打包下载（待真实数据）

---

### 6.30 方案2 执行产出物化 + 主任务交付物实时聚合 zip 下载�?026-07-31�?

#### 1. 背景与决�?

用户需求：任务已由多个子任务分别完成并产出交付物，期望类似 Kimi 的附件下载或资源 zip 包下载，下载结果应把各子任务产出整理在一起。依�?`doc/design/HelloAI_执行产出物化与结构化多文件产出方�?md`（�?.27 产出的设计草案）落地方案2；已确认决策：① 主任务层采用**实时聚合 zip**（下载时现场�?sub_task.context + attachment 组包，历史任务立即可下、返工后重下即最新、无存储成本、无表结构变更）；② 两层一轮落地（主任�?zip + 方案2 子任务物化）。方�?（LLM manifest 多文件协议）本轮不做，`ExecutionOutputParser` 注释已预留扩展位�?

#### 2. 实施内容

后端（方�? 物化链）�?

- `helloai-common/.../config/ArtifactStorageProperties.java`（新建，�?DoorbellProperties，prefix=`helloai.storage`，全字段默认值：enabled=true / type=local / local-base-dir=./data/artifacts / bucket=helloai-local / max-files=10 / max-file-size=5MB�? `application.yml` 新增 storage 配置�?
- 存储抽象三件套（`core/system/storage`，新建）：`ArtifactStorage` 接口（store/load/supports，预�?minio/s3 扩展�? `LocalArtifactStorage`（storageUrl=`local://{bucket}/{objectKey}`，objectKey=`{subTaskId}/{yyyyMMdd}/{uuid8}-{safeName}`，normalize+startsWith 路径穿越防护，文件名清洗�? `StoredArtifact` record
- 解析三件套（`core/agent/output`，新建）：`ExecutionOutputParser`（纯文本→单 .md，文件名取子任务标题清洗限长60；方�? 落地后在此扩�?manifest 解析�? `ParsedOutput` / `ArtifactFile` record
- `ExecutionArtifactService`（新建）：best-effort 物化编排——parse 空跳过、maxFiles 截断、单文件�?maxFileSize 跳过；register 固定�?`subTask.assignedAgentId`（保证归属校验必过），时间线 `sub_task_artifact_materialized` 记上�?agentId；任何异常吞掉只记日志，绝不阻断执行主链�?
- `ExecutionResultHandler` 成功分支挂接：复�?failureTracker �?`TransactionSynchronizationManager.registerSynchronization` afterCommit 范式（行锁释放后物化，规避自死锁），构造器�?7 参注入；两个存量测试（Integration/Unit）同�?
- 附件下载流式改造：`AttachmentService` 新增 `isContentLoadable`/`loadContent`（仅 local:// 平台直读�? detectBucketName/detectObjectKey 识别 local:// 前缀；`AttachmentController.download` local:// 流式返回（RFC 5987 中文文件名），其余仍 302 重定�?

后端（主任务实时聚合 zip）：

- `core/shared/util/SubTaskDependencyOrder`（新建）：从 PlannerAnalysisService 私有 orderByDependency 提炼的公共稳�?Kahn 拓扑排序（统一�?`dependsOnIdList()` 归一化，成环兜底不丢条目）；PlannerAnalysisService 改为委托
- `core/task/service/TaskDeliverableService`（新建）：`buildZip(taskId)` 内存组包（UTF-8 ZipOutputStream）——`00-任务概览.md`（任务信�?+ 子任务完成情况表：状�?Agent/完成时间/最新核验结论）+ `NN-xxx` 拓扑序编号的 DONE 子任务产出；**取数规则：优先物�?local:// 附件（同名取最新一轮），无可读附件回退 context.lastExecution.output �?.md**（兼容物化上线前的历史任务，并避免新任务重复收录）；草案/已取消不入包，非 DONE 仅概览表标注；重名自�?(2)(3) 后缀；单附件读取失败不拖垮整�?
- `TaskController` 新增 `GET /api/tasks/{id}/deliverables/download`（薄入口，任何状态可下，无产出时包内仅概览）

前端（helloai-ui）：

- `api/request.ts` 响应拦截器开头放�?blob（返回完�?response 供解�?Content-Disposition）；新建 `utils/download.ts`（parseDispositionFilename：filename* 优先 + saveBlobResponse�?
- `api/attachment.ts` �?`download(id)`（blob�? id 类型 number→LongId；`api/task.ts` �?`downloadDeliverables(id)`（blob + 120s timeout�?
- `TaskList.vue` 操作列新增「交付物」按钮（loading 防重复点击）；`SubTaskDetail.vue` 新增「产出附件」卡片（文件�?大小/时间 + 单附件下载，无附件不展示，随 5s 轮询刷新�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run **333**�?12 基线 +21：ExecutionOutputParserTest 5 / LocalArtifactStorageTest 6 / ExecutionArtifactServiceTest 5 / TaskDeliverableServiceTest 5�? Failures 0, Errors 0, BUILD SUCCESS
- 前端 `npx vue-tsc -b --force`：TSC-OK（顺手修�?`tsconfig.node.json` �?`skipLibCheck` 导致 @types/markdown-it 第三方声明报错阻�?-b 构建�?
- 踩坑记录：Mockito �?`LambdaQueryChainWrapper.orderByAsc(any())` �?`AttachmentService.list(any())` 存在重载歧义，须显式类型实参 `ArgumentMatchers.<SFunction<SubTask, ?>>any()` / `anyLong()` 解歧�?

#### 4. 影响与遗�?

- 重启后端后生效（�?Flyway 变更，仅新配置段带默认值）；物化仅对新执行生效，历史任务靠 zip �?context 回退链路覆盖
- 方案3（LLM manifest 多文件协议）仍为遗留，落地时仅需扩展 `ExecutionOutputParser`，物�?打包/下载链路无感
- MinIO/S3 未引入（设计文档非目标不变），`ArtifactStorage` 抽象已预留；真实环境端到端验证（下载历史任务 2083021360376172545 �?zip）待后端重启后回�?

---

### 6.31 任务最终整合报告：Planner 整合全部子任务产出（V32�?026-07-31�?

#### 1. 背景与决�?

用户需求：交付�?zip 里各子任务产出彼此分立，希望�?Planner/Reviewer 角色�?AI Agent 把全部子任务交付物整理成一份连贯文档。已确认决策：① 触发方式�?*自动生成 + 手动重生�?*（任务自动收口时异步触发，历史已 DONE 任务/不满意时手动补生成或覆盖重生成）；② 整合角色�?*Planner**（复�?pickForTask 钉住机制，澄清→拆解→整合同一 Planner 跟随）；�?展示�?*可视�?+ zip**（前�?MarkdownView 弹窗 + zip �?`01-最终整合报�?md`）�?

存储选型关键决策：报告存 **task 专列 TEXT**（V32 三列：final_report / final_report_agent_id / final_report_time）而非 task �?context JSONB——踩点发�?MyBatis-Plus �?JSONB 列必�?XML 覆盖 insert/updateById（SubTaskMapper.xml 先例，`::jsonb` 显式转换），专列 TEXT 方案�?XML 改造�?

#### 2. 实施内容

后端�?

- `V32__task_final_report.sql`（新建）：task 表加三列；`Task` 实体同步三字段；`AgentDispatchProperties` 新增 `autoFinalReportEnabled=true`（`helloai.dispatch.auto-final-report-enabled`�?
- `prompts/task-final-report.md`（新建）：占位符 TASK_TITLE/TASK_DESCRIPTION/SUB_TASK_SECTIONS，要求非简单拼接（执行摘要+重组正文+结论建议）、忠于产出不编�?
- `TaskFinalReportService`（新建，core/task/service）：`generate(taskId)` 编排——仅 DONE 可调；取数与 zip 同源（DONE+产出非空+拓扑序，单段 8000 字符截断保护上下文）；pickForTask �?Planner �?executeSync �?lambdaUpdate 只写三列；timeline �?`task_final_report_llm_call_start/generated/failed`；不加类级事务（LLM 长耗时，与 decompose 同哲学）
- 自动触发链：新建 `TaskAutoCompletedEvent`；`SubTaskCompletionListener.tryCloseTask` CAS 赢家分支发布事件（赢家唯一天然防重复生成）；服务端 `@Async + @EventListener` 承接（发布点已无事务上下文，不能�?@TransactionalEventListener）；开关关/已有报告跳过，异常吞掉只记日志——报告是增值物非交付门�?
- `TaskController` 新增薄端点：`GET /api/tasks/{id}/final-report`（读专列�?`TaskFinalReportResponse`，agentName 回填�? `POST /api/tasks/{id}/final-report`（同步生成）
- `TaskDeliverableService.buildZip`：有报告时置顶收�?`01-最终整合报�?md`，子任务产出顺延�?02- 起；无报告时维持旧编号（向后兼容�?

前端（helloai-ui）：

- `types` 新增 `TaskFinalReport`；`api/task.ts` 新增 `getFinalReport` / `generateFinalReport`�?80s timeout�?
- 新建 `FinalReportDialog.vue`：MarkdownView 渲染 + 元信息（Planner �?生成时间�? 空�?+ 生成/重新生成（覆盖需二次确认�? 复制 + 导出 .md
- `TaskList.vue` 操作列新增「报告」按钮（�?DONE 任务展示�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run **343**�?33 基线 +10：TaskFinalReportServiceTest 8 / TaskDeliverableServiceTest 新增 2�? Failures 0, Errors 0, BUILD SUCCESS
- 前端 `npx vue-tsc -b --force`：TSC-OK
- 踩坑回顾：`AgentTask`/`AgentResult` 实际�?`com.helloai.core.agent.domain` 包（�?execution/executor），首次 import 写错编译报错后修�?

#### 4. 影响与遗�?

- 需重启后端�?Flyway V32 生效；历史已 DONE 任务无报告，靠弹窗内手动「生成报告」补�?
- 自动生成仅覆盖新收口任务；手动重生成�?last-write-wins 覆盖，无历史版本保留（如需版本化另议）
- 真实环境端到端验证（收口自动生成 + 手动重生�?+ zip 含报告）待后端重启后回归

#### 5. 修复补记：小上下文模�?token 超限降档重试�?026-07-31�?

- 真实环境首测报错：Planner �?moonshot�?k 上下文）�?3 个子任务各截 8000 字符�?prompt 总量�?45640 token，命�?`exceeded model token limit: 8192`（逐段截断挡不住段数多的总量爆炸�?
- 修复：`SECTION_OUTPUT_LIMIT` 常量改为阶梯 `{8000, 2000, 500}`；`generate` 命中 token 超限类错误（isTokenLimitError 覆盖 moonshot/openai/deepseek 措辞）且还有更紧档位时收紧截断重试，其余错误直接失败；timeline 三类事件 payload 增记 `sectionOutputLimit`
- 大上下文模型首档即成功、行为不变；单测 +2（降档重试成�?/ 全阶梯仍失败），`mvn -pl helloai-core -am test` 345 全绿
- 同轮第二修：Planner 换绑 minimax（Anthropic 协议推理模型）后生成报告耗时�?60s，命�?provider HTTP 读超时（`SocketTimeoutException: Read timed out`，被 Spring 误报�?content-type application/octet-stream 解析失败）。修复：`AgentProviderProperties.readTimeoutMs` 默认 60000 �?180000（四 provider 共享），yml deepseek 显式值同�?180000，前�?generateFinalReport 超时 180s �?240s 留余量；需重启后端生效（ProviderChatModelCache 重建后新超时才落地）

---

### 6.32 结构化选项式需求澄清引擎（V33�?026-07-31，同日第二轮�?

#### 1. 背景与决�?

用户提出下一步计划（P0 结构化选项式澄�?/ P1 多轮对话策略 / P2 浏览器检�?/ P3 ASR-TTS），本轮实施 P0：澄清追问从「纯文本问答」升级为「选项点选为主、自由输入兜底」，用户面对模糊需求不再需要打字长文回答。评审阶段确�?6 处修正后落地�?

- **payload 一列两�?*：`requirement_message` 只加一�?`payload`，assistant 行存结构化问�?JSON（`{"mode","progress","questions":[...]}`），user 行存选择快照（`{"selections":[...]}`），纯文本消�?NULL——不为两种行各开一�?
- **TEXT 而非 JSONB**：与 V32 同款约定（JSONB 写入需 JacksonTypeHandler + XML 覆盖改造），payload 只整存整取、无库内查询需�?
- **progress 仅展�?*：LLM 自评 0~100 只驱动前端进度条，无任何 `if (progress >= x)` 业务分支；FINALIZED 前端直接显示 100
- **降级 freeform 一等公�?*：LLM 输出�?JSON 且不�?`"type"` 字样 �?原文�?freeform 追问落库不报错（判据 `rawOutput.contains("\"type\"")`，含 type 的破�?JSON 仍抛 BizException 走既�?retry 链路）；structured 校验失败（无问题/无选项/label 空）�?降级 freeform 丢弃 questions
- **weight 留字段缓�?*：`ClarifyOption.weight` 预留无业务消费，注释明示
- **content/payload 职责分离**：content �?LLM transcript 可读文本（structured 时由引导�?问题+选项�?`composeAssistantContent` 合成），payload 是前端渲染快照；payload 丢失不影�?LLM 上下�?

#### 2. 实施内容

后端�?

- `V33__requirement_message_payload.sql`（新建）：`ADD COLUMN IF NOT EXISTS payload TEXT` + 一列两�?COMMENT；`RequirementMessage` 实体�?`payload` 字段
- `prompts/requirement-clarify.md`（重写）：三形态输出协议——structured question（`mode/progress/message/questions[{id,text,multiple,allowCustom,customPlaceholder,options[{label,value,recommended}]}]`�? freeform question / final（补 `progress:100`）；structured 约束（每�?�? 问、每�?2~4 选项、recommended 每题最多一个、allowCustom 默认 true）；五维度自检清单（业务场�?功能范围/性能并发/安全合规/交付预算）；保留 `{{CONVERSATION_HISTORY}}` 占位符与 description 分段要求
- `RequirementMessageService.addMessage` 4 参重载（payload 尾参�? 参委托保兼容�?
- `RequirementClarifyService`：`sendMessage(id,message,selections)` 重载（`buildSelectionPayload` 序列化快照落 user 行）；`runLlmRound` question 分支�?`composeAssistantContent` + `buildQuestionPayload` �?assistant 行；`parseReply` 加降级分支；新增 `normalizeQuestionReply`/`isStructuredValid`/`fillStructuredDefaults`（id 缺省�?`q{idx}`、value 缺省�?label）等 6 个私有方法；`ClarifyReply` 扩展 mode/progress/questions + 新增 `ClarifyQuestion`/`ClarifyOption`/`ClarifySelection` 三个 `@Data @JsonIgnoreProperties` 嵌套�?
- `ClarifyMessageRequest` �?`selectedOptions`；`RequirementConversationController.sendMessage` 传三�?

前端（helloai-ui）：

- `types/index.ts` �?`RequirementMessage.payload` + `ClarifyOption`/`ClarifyQuestion`/`ClarifyAssistantPayload`/`ClarifySelection` 四接口；`api/clarify.ts` send �?`selectedOptions` 第三�?
- 新建 `StructuredQuestionCard.vue`：选项 chip（单�?多�?toggle + recommended 推荐标签 + 可多�?tag�? 自定义补充输�?+ 每题至少选一项或填补充才可提�?+ 提交时同时产出可读文本（`问题：label、label（补充：xx）`）与 selections 快照
- `RequirementChat.vue`：顶部澄清进度条（从后向前找最近带 progress �?assistant payload，FINALIZED 直接 100�? 最后一�?assistant �?structured 时渲染卡片（`:key` 绑最后消�?ID 换轮重置选择态）+ `handleStructuredSubmit`；旧消息 payload NULL 自然走纯文本气泡向后兼容

#### 3. 验证结果

- `mvn -q -DskipTests compile` 全模块通过；`RequirementClarifyServiceTest` **21/21 全绿**（Mockito 对重载敏感，全部 verify �?4 参签名；�?`shouldFailWhenOutputIsNotJson` 语义�?V33 更新�?`shouldDegradeToFreeformWhenOutputIsNotJson`；新�?4 例：structured 落库 payload 断言 / 校验失败降级 freeform / �?type 破碎 JSON 仍报�?/ 选项快照�?user payload�?
- `npx vue-tsc --noEmit` 0 �?
- `verify-requirement-clarify-structured.ps1`（新建）真实环境实测 **PASSED**：admin login �?幂等注册 PLANNER + 绑托管凭�?�?模糊需求创建会�?�?assistant payload `mode=structured progress=25` 两问结构完整（硬断言 id/text/options/label/value 全非空）�?第一题第一选项构�?selectedOptions 提交 �?user payload �?selections 快照�?questionId 一�?�?abandon 清理；freeform/�?payload 走软断言路径（LLM 形态不可控，freeform 是合法一等公民）
- 脚本踩坑修复：LLM 回包的中文选项 label 回填进请求体后，若按控制台默认编码（GBK）发送触发后�?`Invalid UTF-8 middle byte 0x5c`——`Invoke-Json` 改为 JSON 先转 UTF-8 字节数组再发（`ContentType application/json; charset=utf-8`），呼应 AGENTS.md 规则 6
- 环境踩坑：`javapath` shim �?java.exe 在沙箱下静默无输出导�?`start-sb.ps1` 起的进程秒退且零日志；改�?`JAVA_HOME\bin\java.exe` 显式路径启动成功（V33 Flyway 实测已生效，`flyway_schema_history` version=33 success=true�?

#### 4. 影响与遗�?

- 旧会�?旧消息（payload NULL）零迁移成本，自然走纯文本渲染分�?
- 本轮明确不做：weight 权重业务消费、多轮对话策略（P1 独立轮次）、progress 驱动业务分支、SSE 流式
- `start-sb.ps1` 依赖 PATH 上的 javapath shim，在受限环境下可能静默失败，后续可考虑改用 JAVA_HOME 显式路径（本轮未改，避免影响既有工作流）

---

### 6.33 子任务依赖可视化：分层流水线 DAG 视图 + 列表/详情依赖字段补全�?026-07-31，同日第三轮�?

#### 1. 背景与决�?

V27 依赖编排（dependsOn + Kahn 拓扑 + ready 守卫）后端已闭环，但前端仅草案审阅弹窗有一处纯文本「依�?#1,#2」，子任务列表页与详情页完全不展示依赖。用户最初提议甘特图，评审后放弃（子任务无计划工�?计划起止数据，甘特图横轴时间无意义），改�?*分层流水�?DAG 视图**：横�?= 执行批次（Kahn 入度分层，同批可并行），与调度器 isReady 语义一一对应。纯前端改动，零后端修改、零新依赖（复用已有 echarts ^5.5.0）�?

#### 2. 实施内容

前端（helloai-ui）：

- 新建 `utils/subTaskDag.ts`：`computeDagLayers`（Kahn 入度分层，跨集合脏依赖忽略、成环兜底不死循环）+ `orderByDependency`（稳定拓扑正序，供全局 #序号 展示复用�?
- 新建 `components/SubTaskDagView.vue`：echarts graph series + cartesian2d 坐标系（x �?category「第 N 批」置顶，y 轴隐藏批内居中），节点按 SUB_TASK_STATUS_MAP 状态着色、roundRect 128x40、edgeSymbol 箭头指向后继、emphasis 高亮邻接、tooltip 展示序号/标题/状�?负责�?前置依赖，高度随最大批次节点数自适应，节点点�?emit node-click
- `api/subTask.ts` �?`listAllByTask`（不�?page 走后端全量数组契约，SubTaskController L184 已支持，dependsOn 已回传）
- `SubTaskList.vue`：taskId 过滤�?header 出现「列�?依赖图」radio 切换 + 表格新增「依赖」列（可点击 `#序号` tag 跳详情）；fullList/seqMap/depItems 基于全量数据计算，watch taskId 清空时重�?
- `SubTaskDetail.vue`：descriptions 加「前置依赖」（空时显示「无（就绪后即可分发）」）与「被依赖」两行，tag 格式 `#序号 标题（状态）`按状态着色、点击跳兄弟详情；onMounted 重构�?`initPage()` + `watch(route.params.id)` 支持同组件路由复用刷�?

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过（chunk 体积警告为既有问题）
- 浏览器端到端实测（task_id=2083171401380065281�? 子任务真实五层依赖，admin/admin123 登录）：列表页视图切换与依赖列渲染正确；依赖�?5 批分层、DONE 全绿、箭头连线与数据一致（截图 `.dbg/dag-e2e-01/02`）；canvas 节点点击跳详情路由正确；详情页前�?被依赖两行与 API 数据逐项比对一致（`.dbg/dag-e2e-03`�?
- 兄弟跳转双向复测�?195�?198）：route watch 重载后真�?DOM 数据均正确；期间 a11y 快照一度出现旧页面残留 tag，经真实 DOM �?API 双重比对确认为快照陈旧节点，非代�?bug

#### 4. 影响与遗�?

- 纯前端展示层补全，不改任何调�?分发行为；无 taskId 过滤（全量子任务列表）时不出现依赖列与切换按钮，避免跨任务序号歧�?
- 本轮明确不做：甘特图（无工期数据）、依赖编辑（拆解草案阶段已有确认/驳回流程）、DAG 视图内实时轮询刷�?

---

### 6.34 DAG 视图交互优化：箭头不遮挡 + 状态专属色 + 活跃边流动虚�?+ 完成时间�?026-07-31，同日第四轮�?

#### 1. 背景与决�?

6.33 落地后用户提四点体验优化：①箭头头部被目标节点矩形遮挡（echarts graph 内置连线按圆形半径裁剪端点，矩形节点会盖住箭头）；②tooltip 完成状态未显示完成时刻；③不同状态节点用 el-tag type 归并后同色不可辨（如 REVIEW/PAUSED 都归 warning）；④希望「进行中」的依赖边有跑马�?流动效果提示链路推进中�?

#### 2. 实施内容（`components/SubTaskDagView.vue`，纯前端�?

- **箭头不遮�?*：弃�?graph 内置 `edgeSymbol` 连线，改 `custom` series 自绘边——贝塞尔曲线从源节点右缘画到目标节点左缘外侧，箭头三角尖端停在目标左缘外 2px、连线止于箭头底边；节点 graph series �?`z:2`、自绘边 `z:1` �?`silent:true`（不抢节点点击事件）
- **状态专属色**：新�?`STATUS_COLOR`�?1 个状态一对一色值），替换原「el-tag type �?色值」两级映射，PENDING �?/ ASSIGNED 浅蓝 / IN_PROGRESS �?/ PAUSED �?/ REVIEW �?/ DONE �?/ REWORK 浅红 / BLOCKED �?/ CANCELLED 暗灰 / DEAD_LETTER 深红 / PENDING_PLAN_REVIEW 浅橙
- **活跃边流动虚�?*：`ACTIVE_EDGE_STATUS`（ASSIGNED/IN_PROGRESS/REVIEW）的入边渲染为目标状态色虚线（`lineDash [6,5]`�? `keyframeAnimation` 循环递减 `lineDashOffset`�?�?11�?00ms loop）实现向目标方向流动的跑马灯；普通边为中性灰实线
- **完成时间**：tooltip �?DONE 节点状态后追加 `（HH:MM:SS）`（取 `updateTime` 时分秒，终态即完成时刻�?

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过
- 浏览器端到端实测（task_id=2083171401380065281）：�?DONE 态截图确认箭头尖端干净落在各节点左缘、不被遮挡（`.dbg/dag-e2e-06`）；�?Vue 组件 props 注入一�?IN_PROGRESS 节点�?6）触�?deep watch 重渲染，截图确认该节点变蓝、其两条入边�?4�?6�?5�?6）为蓝色虚线（`.dbg/dag-e2e-05`），canvas 像素直方图证实绿(#67c23a)/�?#409eff)两色共存；tooltip 实测 DONE 节点显示「已完成�?2:43:21）」、IN_PROGRESS 节点显示「执行中」无时间后缀
- 环境注记：browser-use 视口一度被压至 185×116 �?take_screenshot 超时，属工具环境问题非代码问题，视口恢复后截图正�?

#### 4. 影响与遗�?

- 注入 IN_PROGRESS 仅为验证的客户端临时态（未落库），刷新即回真实全 DONE
- 自绘边未做曲线避让重叠（当前批间跨度足够、无视觉交叉困扰），后续如节点密集可再引入布局避让

---

### 6.35 DAG 视图传递归约：冗余依赖边不画，图形更接近流程图�?026-07-31，同日第五轮�?

#### 1. 背景与决�?

用户反馈末端汇聚节点（如 #8 依赖 #3/#4/#5/#7）入边太多显乱，建议只从倒数第二个任务指过去。评审后按图论「传递归约」实现通用规则而非硬编码末节点：仅去除被更长路径完全覆盖的直连边（#4�?8�?5�?8 �?#6�?7�?8 可推导，去除）；并行分支边必须保留（#3 不在 #7 上游�?3�?8 去掉会丢失�?8 还需�?#3」的信息——同批完成先后无保证）�?

#### 2. 实施内容（纯前端�?

- `utils/subTaskDag.ts` 新增 `reduceDependencies`：记忆化 DFS 求各节点祖先集合，边 u→v 冗余判据为「存�?v 的另一前置 w，且 u �?anc(w)」；visiting 标记防成环死递归
- `SubTaskDagView.vue`：画边改用归约后依赖；tooltip「前置依赖」仍显示完整直接依赖（展示层简化不失真），列表依赖列与详情页前�?被依赖不受影响（展示真实数据�?

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过
- 浏览器实测（task_id=2083171401380065281）：#8 入边�?4 条减�?2 条（#7 主干 + #3 并行分支），冗余长线消失、无交叉，整图呈标准左右流程图形态（`.dbg/dag-e2e-07`�?

#### 4. 影响与遗�?

- 仅影�?DAG 视图画了几条线，调度语义/接口数据/其他页面依赖展示零变�?

---

### 6.36 任务管理入口收敛：新�?编辑/交付物按钮调�?+ 报告弹窗 footer 重排�?026-07-31，同日第六轮�?

#### 1. 背景与决�?

用户提出五点 UI 调整 + 两点链路诉求。经调查确认两点链路诉求 V32 已交付、无需开发：①末子任务完成→Planner 自动生成整合报告（`TaskFinalReportService.onTaskAutoCompleted`，`autoFinalReportEnabled` 默认开，失败吞异常�?warn、手动按钮兜底）；②交付�?zip 已含 `01-最终整合报�?md` 置顶条目（报告非空时收录）。本轮仅实施 UI 五点，用户已确认接受两项行为变化：任务标�?描述不再有修改入口（后端 PUT 接口保留）；交付物仅 DONE 任务可下载（入口收进报告弹窗）�?

#### 2. 实施内容（纯前端�?

- `TaskList.vue`：去掉顶�?新建"（统一走对话新建，改为 primary 样式）、操作栏"编辑"�?交付�?按钮；清�?TaskFormDialog 引用、openCreate/openEdit、handleDownload/saveBlobResponse；操作列 380�?00
- `FinalReportDialog.vue`：footer �?关闭"（右上角 X 承担关闭），按钮定为 复制/导出.md/交付�?重新生成 四个；交付物下载逻辑（taskApi.downloadDeliverables + saveBlobResponse）自 TaskList 迁入
- `TaskFormDialog.vue` 组件文件保留未删（后端接口在，恢复入口成本低�?

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过
- 浏览器实测：列表页仅�?对话新建/刷新 + AI拆解/审阅草案/报告/重新发布/删除；报告弹�?footer �?复制/导出 .md/交付�?重新生成、无"关闭"、X 保留
- 链路核验（task_id=2083171401380065281）：final-report 接口返回 14887 字报告（planner-decompose 生成）；zip 实测 10 条目，`01-最终整合报�?md` 置顶

#### 4. 影响与遗�?

- 交付物下载入口收敛后，非 DONE 任务无法下载部分产出（用户确认接受）；任务标�?描述无修改入口（接口保留�?
- 后端日志未落盘，无法追溯历史任务报告是自动还是手动触发；自动链路代码与开关均在位，如需实证可跑一个新任务观察收口后报告是否自动出�?

---

### 6.37 子任务列表标题前拓扑序号小徽标（2026-07-31，同日第七轮�?

#### 1. 背景与决�?

用户希望在子任务列表中直观看到每条子任务在依赖关系中的序号，且不单起一列——参考电�?new"角标样式，以小徽标形式放在标题前。序号与依赖�?#N、依赖图节点 #N、草案审阅弹窗同口径（orderByDependency 拓扑正序）。仅按主任务过滤时展示（全局列表跨任务序号无意义）�?

#### 2. 实施内容（纯前端�?

- `SubTaskList.vue` 标题列：标题前插�?`.seq-badge` 小胶囊徽标（`#N`，复用已�?seqMap），`v-if="taskId && seqMap.get(...)"`
- 样式�?1px/600 白字、主题蓝实底�?px 圆角胶囊，右�?6px

#### 3. 验证结果

- `vue-tsc --noEmit` 0 错、`npm run build` 通过
- 浏览器实测（task_id=2083171401380065281）：8 行标题前均带 #1~#8 徽标，序号与依赖�?依赖图一致；计算样式确认蓝底/8px 圆角/11px 生效

#### 4. 追加：按主任务过滤时列表按拓扑序号正序排�?

同轮追加用户诉求：从主任务点入的子任务列表按 #1�?n 从上到下展示。`SubTaskList.vue` 新增 `displayList` computed——taskId 存在时对当前页按 seqMap 正序排序（seqMap 未就绪回退原序，无序号项排末尾），全局列表维持后端顺序。实�?8 行按 #1~#8 正序展示；vue-tsc/build 通过。注：排序作用于当前分页页内，拆解子任务规模（≤20/页）下等价全局有序�?

---

### 6.38 对话式需求澄清联网搜索开关（V34�?026-08-01�?

#### 1. 背景与决�?

N17 澄清链路�?V29（多轮追�?+ 终稿�? V33（结构化选项 + progress）后端闭环已较为稳定，但实践暴露一个明显短板：模型在“我想做类似 Notion 的协作文档”这种行业已成型的需求上依然会反复追问“具体要哪些功能�?“目标用户是谁�?“性能要求”——本质是因为不知道行业默认边界。DeepSeek/Kimi 网页版的「联网搜索」开关正是面向这类痛点：首轮前先以用户问题为 query 拉一次行业资料，注入 Prompt 提供行业术语与默认维度参考�?

用户原始速记“都按照你的推荐来做吧：�?`web_search_enabled` 列；Tavily 和博查，两个都抽象成接口、默认走博查，因为我的服务器上国内的”，拍板如下五个设计取舍�?

- **会话级而非回合�?*：开关状态由前端用户在新建会话前决定，落库到 `requirement_conversation.web_search_enabled`；后续追问不再重复检索，避免 token 浪费与上下文漂移
- **首轮注入而非多轮**：仅�?`rounds==0` 那一�?LLM 调用前预检索（首轮后上下文已演化，重检只会偏离�?
- **失败降级而非事务回滚**：联网是增强而非核心，搜索失败一�?warn 日志 + 返回空串，不阻断澄清流程
- **抽象为接�?+ Router**：业务侧只依�?`WebSearchService` 接口，新�?切换供应商零业务改动
- **默认走博查（bochaai）而非 Tavily**：用户服务器在境内，博查国内可用稳定；Tavily 仅作为配置可切换备�?

#### 2. 实施内容

后端（helloai-common + helloai-core + helloai-start）：

- `WebSearchProperties`（helloai-common/config，`@ConfigurationProperties(prefix="helloai.web-search")` + `@Component`）：`enabled=true / provider=bocha / timeout-ms=3000 / max-results=5 / max-snippet-chars=200 / query-keyword-limit=40` + `bocha{base-url,api-key}` �?`tavily{base-url,api-key}`——Spring Boot 配置元数据承担注入校�?
- `WebSearchResult` / `WebSearchService`（helloai-core/planner/search）：供应商无关归一化模�?`title/url/snippet` + 接口契约 `provider()` + `search(query, maxResults)`
- `BochaWebSearchService`：`@ConditionalOnProperty(name="helloai.web-search.provider", havingValue="bocha", matchIfMissing=true)`（默认激活），博�?API `https://api.bochaai.com/v1/web-search` POST，`WebClient` + 3s 超时 + 错误降级空列�?
- `TavilyWebSearchService`：`@ConditionalOnProperty(name="helloai.web-search.provider", havingValue="tavily")`，Tavily `https://api.tavily.com/search` POST，错误同样降级空列表
- `WebSearchServiceRouter`（`@Primary implements WebSearchService`）：`ObjectProvider<WebSearchService>` 收集候�?�?�?provider 配置�?delegate，未匹配回退首候�?/ 返回 null（屏蔽）；`provider()` 返回 `router-><delegate.provider()>`；`enabled=false` 短路返回空列�?
- Flyway `V34__requirement_conversation_web_search_enabled.sql`：单�?`web_search_enabled BOOLEAN` + COMMENT（明�?NULL/true=默认开启，false=关闭�?
- `RequirementConversation` 实体�?`Boolean webSearchEnabled`
- `prompts/requirement-clarify.md`：新增「联网检索资料」节（占位符 `{{WEB_SEARCH_CONTEXT}}`�? 引用资料三大原则（核心需求以用户描述为准 / 无资料等价于无外部信�?/ 不在 JSON 字段加“参考资料”键�?
- `RequirementClarifyService`：注�?`WebSearchService` + `WebSearchProperties`；`create(message, plannerAgentId, webSearchEnabled)` 三参签名（新会话透传落库�?+ 二参重载保兼容（默认 NULL）；`doRound` 首轮且开关开启时�?`doWebSearch(firstUserMessage)` �?`webSearchContext`；新�?`isWebSearchEnabled(NULL/true 视为开�?` + `doWebSearch(关键词截 40 �?+ try/catch 降级)` + `renderWebSearchContext(�? 条；空列表输出“（无可用联网资料）”` 三个私有方法；`runLlmRound` 改两�?`(conversation, webSearchContext)`；`renderPrompt` 改双占位符替换；`retryRound` 显式传空串不复用首轮预检索（避免失败路径副作用）
- `ClarifyMessageRequest`（helloai-api/dto）：�?`Boolean webSearchEnabled` 字段（仅 create 接口生效，append 消息接口服务端忽略）
- `RequirementConversationController.create`：透传 `req.getWebSearchEnabled()` �?Service 三参方法

前端（helloai-ui）：

- `types/index.ts` `RequirementConversation` 接口�?`webSearchEnabled?: boolean | null`
- `api/clarify.ts` `create` �?`webSearchEnabled` 第三参（不传/null 一律透传�?
- `views/requirement/RequirementChat.vue`：仿 ima copilot——`webSearchEnabled` ref 默认 true；输入栏左侧「联网搜索�? Connection 图标 + tooltip + `el-switch` inline-prompt（开/关）；`activeId==null` 时可改，已有 ACTIVE 会话置灰（开关仅建会话生效）；`watch(detail.conversation.webSearchEnabled)` 已存会话同步原值（不可改）；`handleSend` 新会话分支透传 `webSearchEnabled.value`
- `vue-tsc` lint 修复两处：`watch` 补入 `import { computed, ..., watch } from 'vue'`；`watch` 回调参数 `v` 添型注解 `(v: boolean | null | undefined)`

#### 3. 验证结果

- 后端编译验证：`bash -n` + `zsh -n` `scripts/shell/verify-websearch-e2e.sh` �?shell 语法 OK（BASH-OK / ZSH-OK）；`mvn -pl helloai-core -am compile` 沙箱�?mvn，需 IDE 重启后验证；单测增量（联网降�?+ 占位符替�?+ Provider 路由多实现解析）待补
- 前端：`vue-tsc --noEmit` 需 IDE 验证（已修两�?lint：缺 `watch` 导入 + `v` 隐式 any�?
- 端到端：`scripts/shell/verify-websearch-e2e.sh`（新建，UTF-8 �?+ `set -euo pipefail` + `curl` + `jq`）覆盖三条路径：
  - STEP3 关路径（`webSearchEnabled:false`）→ 会话落库 `webSearchEnabled=false`，`roundCount=1`，后�?`sendMessage` 不受影响（开关仅建会话生效）
  - STEP4 开路径（`webSearchEnabled:true`）→ 会话落库 `webSearchEnabled=true`，`roundCount=1`；服务端日志会输�?`澄清联网搜索结束: provider=<bocha|tavily>, query=<...>, results=N, costMs=...`
  - STEP5 NULL 路径（不�?`webSearchEnabled`）→ 会话落库 `webSearchEnabled=null`（读取侧按默认开启语义处理，保老会话兼容）
- **质化对比**（人工对终端 LLM 输出）：开启路径下模型更多会援引行业术语“在线协�?/ 富文�?/ 版本历史 / 企业研发团队 / 多人实时编辑”以及默认边界“个人为�?/ 小中型团�?/ SaaS”类推断，不再反复追问“具体要哪些功能�?“性能要求”；关闭路径保持原有纯对话行为。该质化对比带主观性，本轮不设硬阈值；后续可考虑在终�?`progress >= 85` 后交业务采样对比�?

#### 4. 影响与遗�?

- 老会�?`web_search_enabled` �?NULL 自动视为默认开启；开关状态与会话生命周期绑定（不实现 mid-stream toggle，已�?ACTIVE 会话不可改）
- 首轮检索关键词取首条用户消息前 40 字（适合一句话级别需求；超长 prompt 截断保守，可�?`queryKeywordLimit` 调）
- Provider 单实现切换（bocha↔tavily）仅�?`helloai.web-search.provider` 一行配置，业务零改动；新增供应商只需新增 `@ConditionalOnProperty` 实现类（接口 + Router 抽象的关键收益）
- 默认激活策略：bocha �?`matchIfMissing=true` 默认；不�?bocha api key 但配 tavily 也能自动切到 tavily（`matchIfMissing` 仅指“未�?provider 时默认”，apiKey 缺失仍要切）
- 服务端日志副作用：每次首轮联网会增加�?1�?s 延迟上限（`timeoutMs=3000`），�?LLM 调用串行；后续可考虑并行检�?+ 超时叠加
- 本轮明确不在范围：① 每轮重新检索（首轮已含完整上下文，重检会偏离且�?token）；�?多供应商并行 failover（增加延迟与复杂度）；③ 按用户角色区分检索策略（个人 vs 团队需求检索偏好无足够样本先验证）；④ 检索词 LLM 改写（首轮关键词足够泛化可工作中，后续如遇不命中再上）；�?JSON 字段注入“参考资料”序号（保持现有协议稳定，不动）
- **后补注记�?026-08-21�?*：上条“本轮明确不在范围”中两项已在后续轮次因实际命中问题再评估后落地，不再是永久决策——① 每轮重新检索已�?V41 放宽�?CLARIFY 每轮触发（见 §6.105）并扩展�?CHAT 模式（见 §6.111）；�?检索词 LLM 改写已自 V45 落地为“规则清�?+ 条件 LLM 改写 + 多候选词顺序降级”（触发条件即本条预设的“遇不命中”：博查零结果真实案例，�?§6.131）。其�?②③�?项仍维持不实现口径�?

---

### 6.39 执行链依赖上下文注入：执�?Agent 真正参考上游产出（V35�?026-08-01，同日第二轮�?

#### 1. 背景与决�?

用户审查子任务执行时序图后发现严重缺环：子任务间 `depends_on` 依赖关系（V27）只解决�?*调度排序**（解锁下�?/ 拓扑排序 / ready 守卫 / 跳过分发），执行 Agent 组装 Prompt �?`buildUserPrompt` 只含子任务自身四要素（标�?描述/交付�?验收标准），**完全不含任何上游子任务的交付结果**——依赖关系“只排序、不传上下文”。时序图上只见“领取任务、执行任务”，不见“参考依赖执行结果”�?

用户速记“执�?的子任务的时候，agent真的有看1任务完成后上交的内容么”，拍板如下五个设计取舍�?

- **执行入口注入而非调度侧传�?*：在纯执行入�?`SubTaskExecutionService.executeOnce` 内装配，调度层（分发/解锁/ready 守卫）零改动，职责边界清�?
- **按声明顺序注入直接前�?*：按 `dependsOnIdList()` 声明顺序逐条渲染，与调度�?ready 语义的前置顺序一致；不做多级透传（前置的前置由各自下游消费）
- **截断而非摘要**：单条产出超 4000 字符截断并显式标注“以已提供部分为准”，避免多依赖叠加撑爆小上下文模型；不做 LLM 摘要（增加一次调用与失败面）
- **失败降级而非阻断执行**：依赖查�?渲染异常一�?warn + 返回空上下文，产出参考是增强信息不是交付门槛（沿�?V34 联网搜索降级哲学）；降级仍保�?`hasDeps=true` 供观�?
- **可观测先�?*：声明依赖时记录�?timeline 事件 `sub_task_deps_context_loaded`（depCount/loadedCount/truncatedCount/degraded），时序图与时间线能看出“读取上游产出”环节；无依赖零噪音

#### 2. 实施内容

后端（helloai-core）：

- `SubTaskOutputExtractor`（新，shared/util）：静态方法统一读取 `sub_task.context.lastExecution.output`（null 安全 + Map 类型守卫），消除多消费方同款先例漂移
- `TaskFinalReportService` / `TaskDeliverableService`：各自私�?`extractExecutionOutput` 替换为调 `SubTaskOutputExtractor`（行为零变化，先例收敛）
- `SubTaskExecutionService`�?
  - 常量 `DEP_OUTPUT_LIMIT = 4000`（单条前置产出截断上限）
  - `loadDependencyContext(subTask)`：`dependsOnIdList()` �?�?`DependencyContext.EMPTY`；`subTaskService.listByIds` 批量�?+ HashMap 映射；按声明顺序渲染 `## 上游产出参考（前置子任务的交付结果，你的工作必须建立在这些内容之上）` + `### 前置 N：标题（状态：X）` + 产出正文（超限截断标注）；DONE 无产�?�?`（该前置子任务无可用产出内容）`；异�?catch �?warn + `new DependencyContext(true, depIds.size(), 0, 0, "", true)` 降级
  - `DependencyContext` 内部类（不可变，全参构造）：hasDeps/depCount/loadedCount/truncatedCount/promptSection/degraded + 静�?EMPTY
  - `buildUserPrompt` 重载：旧签名委托 `DependencyContext.EMPTY` 保兼容，新签名四要素后追�?`depCtx.promptSection`
  - `executeOnce`：调 `loadDependencyContext` 后装�?`AgentTask.userPrompt`；`depCtx.hasDeps` 时记�?`sub_task_deps_context_loaded` timeline 事件（AgentRole.EXECUTOR，payload 四指标）

前端（helloai-ui）：

- `sequenceFlow.ts`：LABEL �?`sub_task_deps_context_loaded: '装配依赖产出'`；`classifySwimlane` EXT 分支加该事件（归执行 Agent 泳道�?
- `SubTaskDetail.vue`：EVENT_META 加同 key（`参考上游产出` + “执�?Agent 已读取前置子任务的交付结果，作为本次执行的参考”）

#### 3. 验证结果

- 后端：`SubTaskExecutionServiceTest` 新增 5 例（无依赖不查库 `never().listByIds` / 有依赖注入产出正�?+ `sub_task_deps_context_loaded` 事件 / 前置 DONE 无产出占�?/ 超长产出截断标注 / `listByIds` 抛异常降级不阻断�?payload `degraded=true`），**16/16 全绿**（ArgumentCaptor 捕获 AgentTask 断言 userPrompt；降级用例捕�?payload Map 断言 depCount/degraded�?
- 全模�?`mvn compile` SUCCESS（JDK 17 + IntelliJ 内置 maven）；`vue-tsc --noEmit` 0 �?
- �?Flyway 无配置项，重启即生效；真实环�?E2E 回归待做（可复用既有执行链脚�?+ 人工抽查 LLM 输出是否援引上游内容�?

#### 4. 影响与遗�?

- 行为兼容：无依赖子任务与旧版完全一致（EMPTY 短路 + 不查�?+ 不记录事件）；依赖查询失败时执行照常，仅 warn
- �?DONE 前置（死信人工指派等旁路绕过 ready 守卫）也注入状态说明，执行 Agent 能感知“前置未完成”而非蒙在鼓里
- 截断只截正文不截结构；`DEP_OUTPUT_LIMIT` 为常量，后续如需可按任务/角色配置�?
- 本轮明确不在范围：① 产出摘要�?向量化（多一�?LLM 调用与失败面）；�?跨任务依赖上下文（depends_on 限定�?Task 内）；③ 按依赖层级多级透传（各层由自己的直接前置负责）；④ 执行 Agent 主动“拉取”上游（保持注入式单向）；⑤ 问题一（planner 关键词触发拆解）未在本轮处理，另行评�?

---

### 6.40 子任�?LLM 对话消息可视�?+ reviewHistory 多轮累积（V38�?026-08-02�?

#### 1. 背景与决�?

用户盯子任务执行可观测性时发现两个互补的缺环：

1. **LLM 对话流黑�?*：V28 已把 assistant 输出（`sub_task_execute` / `sub_task_execute_thinking` / `sub_task_execute_failed` / `subtask_review_prompt|thinking|verdict`）落�?`conversation_message`，但**实际送给 LLM �?user prompt 一条都没落�?*。前端“执行对话流”只能展�?LLM 返回，看不到发生了什么给 LLM�?
2. **单轮驳回信息丢失**：`context.lastAutoReview` 是单 Map，驳回第二轮时直接覆盖——上一�?reviewer �?issue 被静默替换，prompt 拼接 `appendReworkContext` 只能拿到最新一轮意见，agent 看不到累积史�?

用户拍板以下设计取舍�?

- **拦截点下沉到 `SubTaskExecutionService.executeOnce`**：在 `executeSync` 调用前落 user prompt，失败路径（LLM 抛异常）仍保留输入；与既�?`ExecutionResultHandler.handleFailure`（输出错误信息）形成完整 caller 输入 + LLM 输出对偶�?*不下沉到 `ApiKeyAgentExecutor` / `AgentChatClientService`**，拦截点保持唯一
- **reviewHistory 多轮累积而非覆盖**：`sub_task.context.reviewHistory` �?`Map` 改为 `List<Map>`；每�?`rejectAndRework` append 一�?`{round, ts, reviewerAgentId, issues, comment, score, executorDoneIssues}`；`executorDoneIssues` 字段预留但本轮不主动写（语义相似度比对留待后�?hook�?
- **向下兼容 0 成本**：`appendReworkContext` 优先�?`reviewHistory`（List），缺失时回退 `lastAutoReview`（Map）包成单轮；`rejectAndRework` 同时写两字段保证旧读路径不中断；V38 Flyway 把全表历�?`lastAutoReview` 回填�?`reviewHistory[1]`，幂等可重跑
- **不做时点重试**：`conversationService.addMessage` 异常时仅 `log.warn`，不阻断主链路（沿用 ExecutionResultHandler 范式 `REQUIRES_NEW` 事务隔离）。一�?prompt 4-8KB，单�?DB 写成本可�?
- **N6 差距为已交付**：本轮作�?N6（自动核验闭环）的子增强不开 N 编号；review_record 表已�?`round` 字段，审计链不破

#### 2. 实施内容

后端（helloai-core）：

- `SubTaskExecutionService`�?
  - 构造器注入 `ConversationService`（同 `ExecutionResultHandler` 同款，sub_task_id scope 复用即可�?
  - `executeOnce` �?`recordEvent(sub_task_llm_call_start)` 之后、`executeSync` 之前插入：`try { conversationService.addMessage(subTaskId, agent.getId(), "user", "agent", task.getUserPrompt(), "sub_task_execute_user_prompt"); } catch (Exception e) { log.warn(...); }`。失败路�?prompt 已落库（前面 try 先执行），与 `ExecutionResultHandler.handleFailure` 写入 `sub_task_execute_failed` 互补（前者保输入、后者保错误�?
  - `appendReworkContext` 重构：识�?`reviewHistory`（List，优先）/`lastAutoReview`（Map，兜底）；按 `### �?N 轮` 铺开 reviewer 意见，`executorDoneIssues` 字段预留读取但不主动写；issues 字段同时支持 `List`（新）与 `String`（旧 lastAutoReview 形态）
- `SubTaskReviewService.rejectAndRework` 重构：覆盖式改为 append，读已有 `reviewHistory` List，不存在时把�?`lastAutoReview` 包成首轮 `round=1`；append 当前�?`round=history.size()+1`；同时写 `reviewHistory` �?`lastAutoReview`（最新值）保完全向后兼容；字段�?`OffsetDateTime.now().toString()` �?ts

数据库（helloai-start）：

- `V38__review_history_backfill.sql`（新建）：幂等回填——`WHERE deleted=0 AND context->'reviewHistory' IS NULL AND context->'lastAutoReview' IS NOT NULL` 的子任务，统一�?`lastAutoReview` 包成 `reviewHistory[1]`（round=1 + ts=update_time::text 兜底 + executorDoneIssues=[]）；两字段都有的不动

前端（helloai-ui）：

- `SubTaskDetail.vue`：`CONV_TAG_MAP` 新增 `sub_task_execute_user_prompt: { label: '执行请求', type: 'info' }`；现有「执行对话流」组件按 toolName 自动渲染气泡 + 折叠 + MarkdownView，无需新增卡片/tab

#### 3. 验证结果

- 后端单测�?
  - `SubTaskExecutionServiceTest` 新增 `@Nested ExecuteOnceUserPromptAndReworkHistory`�?*5 例全�?*
    - TC-1 `executeOnce` �?`conversationService.addMessage` 被调�?1 次，参数 (subTaskId, agentId, "user", "agent", userPrompt, "sub_task_execute_user_prompt")
    - TC-2 `executeSync` 抛异常时 user prompt 仍落库（异常路径不阻断对话流�?
    - TC-3 `appendReworkContext` reviewHistory �?2 轮时按轮次铺开 `### �?N 轮` 段，�?ts/issues/comment/score
    - TC-4 `appendReworkContext` reviewHistory + lastAutoReview 全空时不注入返工�?
    - TC-5 `appendReworkContext` legacy lastAutoReview �?Map 形态时仍能注入返工段（�?issues String 兼容�?
  - `SubTaskReviewServiceTest` 新增 4 例全�?
    - TC-1 首次驳回：`reviewHistory.length==1, round=1, issues/comment/score/reviewerAgentId` 全量 + `executorDoneIssues==[]`
    - TC-2 第二次驳回：`reviewHistory.length==2`，第二轮 `round=2`，第一轮保�?
    - TC-3 兼容：context �?`lastAutoReview` �?`reviewHistory` 时，新写入包�?`reviewHistory[0]` + `lastAutoReview` 同�?
    - TC-4 `executorDoneIssues` 初始化为空列�?
- `mvn -pl helloai-core test -Dtest=SubTaskExecutionServiceTest,SubTaskReviewServiceTest`�?3 �?9 全过（其�?24 �?V35 既有测试，本轮未引入回归：其�?4 �?pre-existing V35 loadDependencyContext 用例 fail 为提测问题，不属本轮范围�?
- `mvn -pl helloai-core -am -DskipTests clean compile`：SUCCESS
- 前端 `npx vue-tsc -b --force` 0 错；`npm run build` 成功（`SubTaskDetail-COMACOSA.js` 含新映射键）
- PS1 验证脚本 `scripts/powershell/verify-llm-conversation-stream.ps1` 新建�? 场景：S1 对话�?user+assistant 双气�?/ S2 首次驳回 reviewHistory=1 / S3 二次驳回 reviewHistory=2+userPrompt>=3 / S4 dist �?user-prompt 标签�?/ S5 V38 回填 SQL 由调用方�?MCP postgres_helloai 验证）；`Parser.ParseFile` 静态自检 `PARSE_OK`
- 数据清理：脚本不直接 DELETE/UPDATE，收尾清�?SQL 由用户在 psql / MCP 端执�?

#### 4. 影响与遗�?

- 行为兼容：旧子任�?`context.lastAutoReview` �?Map 数据不丢，V38 一键回填；新驳回同时写两字段，老读代码无需改动
- 防失控：`maxRework=3` 自动核验上限 + 人工兜底，单子任务最�?3-5 轮，�?Map ~500B 累计 < 3KB（reviewHistory 无界增长风险被消除）
- 增强可观测：前端「执行对话流」现按时间序展示 user→assistant→user→user→assistant...，配�?V35 deps �?+ 本轮 rework 段可直观审计“是否参考上�?/ 是否反思修正�?
- 本轮明确不做：① `executorDoneIssues` 自动回填 hook（语义相似度对比留作专门迭代）；�?PLANNER 对话流（PLANNER �?`requirement_message` �?V29-V33，不混用 conversation_message）；�?ApiKeyAgentExecutor / AgentChatClientService 下沉改造（拦截点在 `executeOnce` 已足够）；④ user prompt 流式预览（一�?4-8KB TEXT 字段够用）；�?review_record 表改动（既有 round 足够，审计链不破�?
- N6 已交付状态不变，不在 N 列表新开条目

---

### 6.41 Snowflake ID 全链路字符串�?+ 执行对话流按轮次展示�?026-08-02�?

#### 1. 背景与决�?

用户�?对话新建 �?终稿确认 �?查看任务/自动拆解"链路连续遇到 `400 Bad Request`�?

- `GET /api/requirement-conversations/2083818000152453122`
- `/api/tasks/{id}/plan`

根因�?Snowflake 长整�?ID 超出 JavaScript `Number` 安全整数范围（`2^53-1 �?9e18`），前端 JSON 解析后精度截断，回传 URL 路径参数�?Spring 无法解析被截断的值。同时用户提�?执行对话�?应按"请求 �?响应"成对展示，并把审核结论、返�?Prompt 也纳入可视化，以验证关键节点 LLM 上下文�?

设计取舍�?

- **字符串化而非改�?ID 生成策略**：保�?`BIGINT` 主键与雪花算法，仅在 JSON 序列化层�?`Long` 输出为字符串；URL 路径参数仍用字符串接收（Spring 自动兼容）�?
- **基类收口**：`BaseEntity.id` 统一�?`@JsonSerialize(using = ToStringSerializer.class)`，避免逐个实体补注解�?
- **DTO 全部兜底**：关键返�?DTO 中所�?`Long` / `List<Long>` 字段显式�?`JsonSerialize`/`JsonSerialize(contentUsing)`，防止基类未覆盖的投影字段再次出错�?
- **前端 String() 防御**：所有拼�?URL、传 API �?ID 统一 `String(id)`；Vue 路由/状态中�?ID 不再依赖 number�?
- **执行对话流按轮次分组**：`SubTaskDetail.vue` �?`conversation_message` �?`toolName` 分组�?执行轮次"�?核验轮次"，user prompt �?assistant 返回成对可见，返工轮次可展开查看完整 Prompt 含历史审核意见�?
- **审核结论落库**：`SubTaskReviewService` 把结构化审核结论（通过/驳回、评分、问题、评语）写一�?`subtask_review_result` 对话消息，前端直接渲染�?

#### 2. 实施内容

后端（helloai-common / helloai-core / helloai-api）：

- `helloai-common/pom.xml`：新�?`jackson-databind` 依赖，支�?`BaseEntity` 注解�?
- `BaseEntity.id`：加 `@JsonSerialize(using = ToStringSerializer.class)`，全局实体主键统一字符串化�?
- `RequirementConversation`：`taskId`、`plannerAgentId` �?`@JsonSerialize(using = ToStringSerializer.class)`�?
- `RequirementMessage`：`conversationId` �?`@JsonSerialize(using = ToStringSerializer.class)`�?
- DTO 全面加固�?
  - `TaskResponse.id`
  - `SubTaskResponse.id` / `taskId` / `moduleId` / `assignedAgent` / `dependsOn(contentUsing)`
  - `AgentResponse.id`
  - `ReviewResponse.id` / `subTaskId` / `reviewerAgent`
  - `ModuleResponse.id` / `taskId`
  - `ConversationMessageItem.id` / `senderId`
  - `TaskTimelineItem.id` / `agentId`
- `SubTaskReviewService`：新�?`formatReviewResult(ReviewVerdict)` + verdict 解析成功�?`conversationService.addMessage(subTaskId, reviewer.getId(), "assistant", "agent", resultText, "subtask_review_result")`�?

前端（helloai-ui）：

- `src/api/clarify.ts`：所�?`${id}` 改为 `${String(id)}`�?
- `src/views/requirement/RequirementChat.vue`：`activeId` 全程保持 string；所�?API 调用�?`String(id)`；跳转任�?自动拆解处加 String() 防御�?
- `src/views/task/TaskList.vue`：`row.id` 使用处加 `String()`�?
- `src/views/task/components/PlanReviewDialog.vue`、`FinalReportDialog.vue`、`TaskDeleteDialog.vue`、`TaskFormDialog.vue`：`props.task.id` 使用处加 `String()`�?
- `src/views/subtask/SubTaskDetail.vue`�?
  - 新增 `CONV_TAG_MAP`：`subtask_review_result: { label: '审核结论', type: 'warning' }`�?
  - 新增 `convRounds` computed：按执行轮次/核验轮次分组，`sub_task_execute_user_prompt` + `sub_task_execute`/`sub_task_execute_thinking` 成对；`subtask_review_prompt` + `subtask_review_result` 成对；返工轮次可展开�?

#### 3. 验证结果

- 后端：`mvn clean compile -pl helloai-common,helloai-core,helloai-api,helloai-start -am -DskipTests` SUCCESS�?
- 前端：`npm run build` SUCCESS（无 TS 错误）�?
- 单元测试：本轮未新增单测；既�?`SubTaskExecutionServiceTest` / `SubTaskReviewServiceTest` 未引入回归�?
- 运行时：必须重启后端�?Jackson 注解才生效；前端刷新�?String() 防御生效�?

#### 4. 影响与遗�?

- 影响：新创建的任�?子任�?会话/消息 ID 在前后端间全�?string，JS 精度丢失问题消除；审核结论与执行请求在对话流中可视�?
- 兼容：后端接�?`Long` 路径参数时仍自动�?string �?`Long`；数据库主键类型不变�?
- 遗留�?
  1. 已运行的旧会�?子任务历史数据中，前端本地缓存可能仍�?number，刷新页面后重建即可�?
  2. 其它 DTO / 临时接口中若仍有 `Long` 字段未加注解，后续遇�?400 需继续补漏�?
  3. 用户仍需在后端重启后验证"对话新建 �?查看任务"链路是否还有 400�?

---

### 6.42 文档治理：CODE_STYLE V1.5 + doc 全目录代码事实一致性核查（2026-08-03�?

#### 1. 背景与决�?

用户要求两件连续的事：①补充「接口路径规范」并严格检查代码执行情况（上轮完成，本轮收尾）；②�?doc 目录全部文档仔细核查一遍，与代码有出入的调整修改，拿不准的向用户确认�?

用户拍板三项处理策略�?

- **design/ 文档（架构设计参�?/ 调度解耦重构分�?/ 外部项目借鉴）头部加状态注�?*，正文保留历史拍板原貌（符合"设计参考只读不维护"规则）；
- **项目进度.md 补全粒度 = 骨架 + 摘要**（细节指向迭代执行记录）�?
- **archive/ 8 个历史文档完全不�?*（定�?已交付专项与历史草案，禁止作为开发依�?，历史快照无需对齐）�?

#### 2. 实际落地

**CODE_STYLE.md V1.4 �?V1.5（上轮完成，本轮记录�?*：新增第 8 章「接口路径规范」（8.1 描述性风�?/ 8.2 路径命名规则�?/ 8.3 UriCleanFilter 代码核查注记）；6.4 标记废弃�?.5 �?`POST /{action}ById/{id}`�?.6 分页�?`POST /page`；原 8~20 章顺延为 9~21�?0 章校验清单新�?3 条路径条目�?

**doc 全目录核查修�?*�?

- `HelloAI_实现差距�?md`：修复首�?`a#` 笔误（Markdown 标题失效）；内容本身已含 2026-08-02 最新状态，无需大改
- `README.md`（文档地图）：CODE_STYLE 版本 V1.4 �?V1.5；design/ 清单补《执行产出物化与结构化多文件产出方案》；archive/ 清单补「当前能力确认矩阵�?
- `HelloAI_项目基线文档.md`：�? 闭环能力�?7-20 后新交付（值班租约/门铃 N12+N13、重分配熔断 V24/V25、N15 红线收口、N16 Planner 拆解、N17 需求澄清、产出物�?zip+V32 报告、V35 依赖注入、V38 可视�?Snowflake 字符串化）；§4 删除已过时的 `agent_duty_lease 尚未接入 checkIn/checkOut` �?�?Provider 完整复用"条目（改�?moonshot �?Factory 待补口径），MQ 消费载体�?已交�?；�? 能力边界 `PLANNER 自动拆解` 部分支持 �?已支持；§6 文档矩阵补执行产出物化方案与登录页提示词两文�?
- `项目进度.md`：M5 �?进行�?（门禁已解除）、M6 �?已交�?、新�?M4.5（调度链加固+派发控制台）/M7（需求澄清）/M8（任务管理收�?产出物化+执行链可观测）；当前待办重写�?M5 场景矩阵推进 + REVIEWER 审查补强 + 差距表遗留项
- `design/HelloAI_架构设计参�?md`：头部加状态注记（§5.0 Planner 暂缓已推�?�?V26 交付；�?.1 ②a/②b/�?均已交付�?
- `design/HelloAI_调度解耦重构分�?md`：头部加状态注记（正文"现状"�?2026-07-10 快照，目标态已�?N1/N6/N12/N13 落地�?
- `design/HelloAI_外部项目借鉴技术细�?md`：头部加状态注记（§1.1/§3.2/§6 速查表状态列已过时，以差距表为准�?

#### 3. 验证结果

- 全部修改基于代码事实交叉核对：Flyway V1~V38�?8 个迁移文件）、差距表 N1~N17 状态、迭代记�?§6.9~§6.41 各轮验证证据
- design 注记仅追加头�?blockquote，不触碰正文，正文与注记共存无冲�?
- 未改任何代码文件，无编译/测试影响

#### 4. 影响与遗�?

- 文档事实等级链恢复一致：事实源（差距�?基线/进度/README）与代码同步；design 只读带状态注记；archive 保持历史快照
- 遗留：CODE_STYLE §8.3 UriCleanFilter 实现待补（规范已写入，代码无实现，见注记）；REVIEWER 自动审查仍为"部分支持"（基�?§9�?

---

### 6.43 依赖感知双轨上下文注�?+ Task Running Spec 全貌补记�?V35~V37 编号勘误�?026-08-03�?

#### 1. 背景与决�?

用户本意�?前置做了什�?+ 本轮任务综合分析"：下游子任务执行时，Prompt 应同时获�?*直接前置**�?结构化摘�?+ 完成内容本体"，与 Baseline 全局上下文、本轮任务四要素合并，供 LLM 综合分析后执行。现状存在两个缺环：

- `buildExecutorPromptSection` 注入任务�?*全部** executionRecords 的一句话摘要——不按依赖选择、无内容本体，且多前置时信息混杂�?
- V35（�?.39）`loadDependencyContext` 注入的是**原始 LLM 产出**（`## 上游产出参考`），无结构化收口、无摘要提炼�?

决策：依赖段改为**双轨**——每个直接前置同时提�?�?结构化摘要（EXECUTION_RECORD，`findRecord` 精确取单条）�?完成内容本体（物化附件优先，`context.lastExecution.output` 回退），�?`dependsOnIdList` 声明顺序全量收集渲染，杜�?只记录最后一次前�?�?

并发缺陷决策：Phase A JSONB `appendExecutionRecord` 是读-�?写非原子，多前置并行完成时后写覆盖先写（丢失更新）——本轮加 taskId 粒度分段锁锁住整段；Phase B 独立表行级天然无此问题�?

文档勘误背景：Task Running Spec 体系（Flyway V35~V37）此�?*无任何迭代节记录**；�?.39 标号"V35"时（2026-08-01）Flyway V35 尚未创建（实际提�?2026-08-02 01:04 `ad8176f`），�?§6.39 �?�?Flyway"�?V35 迁移事实冲突——本节一并补记全貌并勘误�?

#### 2. 实际落地

**（A）Task Running Spec 全貌补记（V35~V37 真实内容�?*

- **Flyway V35 `task_context_jsonb.sql`**（Phase A 存储底座）：`task` 表加 `context JSONB NOT NULL DEFAULT '{}'`；`task.context.runningSpec` 为结构化运行态文档三件套——`baseline`（Planner 拆解确认时写入的目标/约束/DAG 结构）、`executionRecords[]`（每�?executor 回填的结构化摘要）、`contextSummary`（系统自动编译的下游上下文）。领域模型：`TaskRunningSpec`（不可变，`toMap/fromMap` JSONB 序列化边�?+ `toBuilder` 增量更新）、`TaskBaseline`、`ExecutionRecord`�?*EXECUTION_RECORD 协议**：`subTaskId/title/agentId/summary/keyDecisions/downstreamNotes/deliverables/completedAt`，builder 强制 subTaskId+summary）。配套：`ExecutionRecordParser`（解�?executor 协议输出）、`ExecutionResultHandler`（统一回填入口）、`TaskRunningSpecJsonbService`（Phase A 实现：`initialize` �?baseline / `appendExecutionRecord` 回填 / `compileContextSummary` 编译 / `buildExecutorPromptSection` 渲染）�?
- **Flyway V36 `task_running_spec_tables.sql`**（Phase B 前置建表，`0db1076`）：`task_running_spec`（task_id UNIQUE、version、baseline JSONB、context_summary TEXT�? `task_execution_record` 独立表，当时仅建表为 Phase B 做准备�?
- **Flyway V37 `task_running_spec_add_deleted.sql`**（Phase B 收尾，`6eaa02c`）：Phase B 实体继承 `BaseEntity` �?MyBatis-Plus logic-delete 全局配置（`WHERE deleted=0`）导致启动期 `BadSqlGrammarException: column "deleted" does not exist`——为两表�?`deleted SMALLINT NOT NULL DEFAULT 0`；同提交落地 `TaskRunningSpecTableService`（Phase B 独立表实现）+ `TaskRunningSpecDataMigrator`（`ApplicationRunner` 数据迁移）。Phase B 渲染复用 Phase A �?`JsonbPromptRenderer`（`TaskRunningSpecTableService` 私有静态类），两实�?prompt 输出一致�?

**（B）本轮依赖双轨改造（helloai-core�?*

- `TaskRunningSpecService` 接口新增 `findRecord(Long taskId, Long subTaskId)`：按 (taskId, subTaskId) 精确取单条结构化摘要，无�?null；契约注�?每次调用返回一条，调用方必须按集合收集，禁止单变量复用"�?
- `TaskRunningSpecJsonbService`：`findRecord` 遍历 `executionRecords` �?subTaskId 匹配返回；`appendExecutionRecord` / `initialize` �?taskId 粒度分段锁（`ConcurrentHashMap<Long, Object>`，锁�?�?�?�?整段；按 subTaskId 去重——rework 覆盖旧记录、不�?subTaskId 互不覆盖全部保留；注释说明单实例安全、多实例需�?Phase B �?Redis 锁）；`buildExecutorPromptSection` **去掉全量"前置任务摘要"�?*，只保留 Baseline（总体目标/平台约束�? ContextSummary（全局进度）�?
- `TaskRunningSpecTableService`：`findRecord` �?taskId+subTaskId 查独立表（行级天然无覆盖竞态，无需锁）；共�?`JsonbPromptRenderer` 同步去掉全量前置段�?
- `SubTaskExecutionService` 新增 `buildDependencySection(SubTask)`：`dependsOnIdList` �?�?返回空串（零注入）；`listByIds` 批量查前�?�?HashMap 全量收集 �?�?*声明顺序**循环�?append 渲染（`## 依赖产出参考（直接前置）` + 每前�?`### 前置 N：标题（状态：X）` + "产出摘要" + "内容"）；内容本体取数优先级：`AttachmentService.list` �?`isContentLoadable` �?`loadContent` �?UTF-8 文本（二进制/读取失败跳过）→ 回退 `SubTaskOutputExtractor` �?`context.lastExecution.output`；单条超 `DEP_CONTENT_MAX_CHARS=4000` 截断并显式标注；异常一�?warn + 返回空串（V34/V35 降级哲学），降级仍记�?timeline。`executeOnce`：`promptSection` = 全局�?+ 依赖段；timeline `sub_task_spec_context_loaded` payload �?depCount/loadedCount/truncatedCount/degraded；构造器注入 `AttachmentService`�?

**（C）前端（helloai-ui�?*

- `utils/sequenceFlow.ts`：LABEL �?`sub_task_spec_context_loaded: '装配依赖产出'`，泳道归 EXT（执�?Agent）�?
- `views/subtask/SubTaskDetail.vue`：EVENT_META �?`sub_task_spec_context_loaded`�?参考前置产�? + 描述）�?

#### 3. 验证结果

- **单测**：`SubTaskExecutionServiceTest` 新增用例——多前置并存�? 前置各有摘要+内容，断言 prompt **同时含两�?*，防"只留第二�?回归�? 附件优先�?output / 附件读取失败回退 output / 无附件走 output / 超长截断 / 异常降级不阻�?/ 无依赖零注入（never �?listByIds）；`TaskRunningSpecJsonbServiceTest` 并发用例——顺�?append 两个不同 subTaskId 记录断言两条都在（模拟多前置回填不互覆）。全绿（�?1 �?pre-existing V35 �?loadDependencyContext 用例，属提测问题非本轮回归）�?
- **E2E（真实环�?PASS�?*：新�?`scripts/shell/verify-deps-context-e2e.sh`——建任务 + 3 子任务（sub3 依赖 sub1+sub2 双前置，SQL 直写 depends_on）→ 并行 claim sub1/sub2（API_KEY_LLM agent **claim 即自动执�?*，两前置并发完成、EXECUTION_RECORD 并发回填不互覆）�?双前置完成后 claim sub3 �?�?`conversation_message` �?`sub_task_execute_user_prompt`。断言全部通过：prompt �?`## 依赖产出参考（直接前置）` 章节、`### 前置 1/前置 2` 两个块（无前�?3）、两前置产出内容**同时同现**（sub1 `## 竞品资料收集报告` �?sub2 `## 前置二：收集用户反馈` 首行均在）；timeline `sub_task_spec_context_loaded` payload `depCount=2 / loadedCount=2 / truncatedCount=0 / degraded=false`。实�?taskId=2084259396090843138�?
- 脚本两处修正记录：① 初版假设"claim 不自动分发、需手动 execute"与真实行为不符（`SubTaskAutoExecutionDispatcher` �?API_KEY_LLM agent �?ASSIGNED 后自动派发，手动 execute 会命�?`hasPendingOrRunning` �?500"已有进行中的执行记录"）→ 改为 claim 即执行、按前置顺序串行等待；② `head -c` 按字节截�?UTF-8 中文产生非法字节序列�?BSD grep �?`illegal byte sequence` �?改先取首行再�?zsh 字符切片（多字节安全）�?
- **构建**：`mvn -pl helloai-start -am package -DskipTests` 产出 `helloai-start-1.0.0-SNAPSHOT.jar`（含全部改动），启动后端 `/api/health` 200；前�?`vue-tsc --noEmit` 0 错�?

#### 4. 影响与遗�?

- 行为兼容：无依赖子任务零注入与旧版完全一致（EMPTY 短路 + 不查�?+ 不记录事件）；有依赖子任�?Prompt 新增"依赖产出参�?章节，且不再包含全量 executionRecords 摘要段�?
- Phase A 分段锁仅单实例安全；多实例部署需切换 Phase B（独立表行级安全）或升级�?Redis 锁�?
- 版本编号勘误落地：�?.39"V35"标号超前�?Flyway V35 实际创建时间�?026-08-02 01:04），�?�?Flyway"记录仅对 08-01 当天成立；V35~V37 真实内容（Task Running Spec Phase A JSONB / Phase B 建表 / deleted 修复）以本节为唯一事实源�?
- 遗留（沿�?§6.39 范围外结论）：多级依赖透传（仅直接前置）、产出摘要化/向量化、跨任务依赖上下文；E2E 脚本依赖"claim 即自动执�?的环境行为，若未来关闭自动分发需同步调整脚本�?

### 6.44 Planner 对话双模式：CHAT 自由对话 + CLARIFY 方案澄清（V39�?026-08-03�?

#### 1. 背景与决�?

需求澄清会话此前只有单一"澄清"形态（V29 首版 �?V33 结构化选项 �?V34 联网搜索），用户闲聊式咨询（技术选型对比、概念解释）会被生硬拽回澄清协议。本轮把单会话升级为 Kimi/DeepSeek 式双模式�?*CHAT 自由对话**（通用 AI 助手，纯文本问答�? **CLARIFY 方案澄清**（保留既有全部行为：首轮联网搜索 + progress 自评 + JSON 三选一协议），同一会话由用户主导切换，不拆两个独立入口�?

关键决策�?

- **单会�?`mode` 字段而非双会�?*：`requirement_conversation.mode`（CHAT/CLARIFY，NULL 老数据按 CLARIFY 语义读取兼容），切换只改模式不迁移消息�?
- **CHAT �?降级协议"模式**：走通用助手模板纯文本（�?JSON 输出协议、无澄清轮自检清单、无 progress 自评），不做首轮联网搜索（阶�?2 计划再评估按需检索）；独�?`MAX_CHAT_ROUNDS=50` 上限（CLARIFY 沿用 20）�?
- **意图词自动切换（CHAT→CLARIFY 单向�?*：正则命中「整理成方案/做成方案/生成方案/转为方案/变成方案/整理成任�?做成任务/落地实施/出一份方�?写个方案/方案化」即先落库切 CLARIFY 再走澄清轮（该条消息即澄清首轮）；意图词永远放行（不�?CHAT 轮数上限判定），保证"转方�?出口不被 50 轮上限挡住�?
- **切换 API 语义**：`to-clarify` = 置位落库 + 立即用澄清模板基于全量历史跑一�?LLM（LLM 失败�?mode 已持久化，可�?retry 续跑）；`to-chat` = 仅置位不�?LLM�?
- **新会话缺�?CHAT**，创建接�?`initialMode` 可快捷直�?CLARIFY；非法值抛 BizException�?
- 明确不做：SSE 流式、CHAT 模式联网搜索（阶�?2）、意图词反向自动切换（CLARIFY→CHAT 无自动切换，避免方案进度被打断）、多轮策略优化�?

#### 2. 实际落地

- **Flyway V39 `requirement_conversation_add_mode.sql`**：`mode VARCHAR(16)`（IF NOT EXISTS�? 重建 `chk_requirement_conversation_mode` CHECK（NULL �?CHAT/CLARIFY�? �?COMMENT + V34 同款 DO $$ 验证块（启动日志输出 `[V39] requirement_conversation.mode 列与 CHECK 约束已就位`）�?
- **`RequirementClarifyService`（helloai-core/planner�?*�?
  - 常量 `MODE_CHAT/MODE_CLARIFY`、`MAX_CHAT_ROUNDS=50`、`CHAT_PROMPT_TEMPLATE_PATH=prompts/requirement-chat.md`、`INTENT_TO_CLARIFY_PATTERN` 意图词正则�?
  - `create(firstMessage, plannerAgentId, webSearchEnabled, initialMode)` 四参重载 + `normalizeInitialMode`（null/缺省→CHAT、CLARIFY 直达、非法抛 BizException）；旧三�?二参重载委托保兼容�?
  - `sendMessage` 轮数上限按模式分派：CHAT（且非意图词）超 50 �?自由对话轮数已达上限…可输入「整理成方案」转为方案模�?；CLARIFY（含 NULL 老数据）沿用 20 上限；意图词消息跳过 CHAT 上限判定�?
  - `doRound` 意图切换：CHAT 模式下命中意图词 �?`setMode(CLARIFY)` + `updateById` 落库 �?该轮即澄清轮；首轮联网搜索条件收紧为 `isClarifyMode && rounds==0 && webSearchEnabled`�?
  - `runLlmRound` 分派：`isClarifyMode` 选澄清模板（scene=requirement_clarify，JSON 协议解析�? CHAT 选通用助手模板（scene=requirement_chat，纯文本直接落库，`addMessage` 显式 4 �?payload=NULL）�?
  - `switchToClarify`：requireActive �?置位落库 �?`runLlmRound(conversation, "")`（切换轮不做首轮联网搜索，澄清模板基于全量历史直接产草案/追问；LLM 失败 mode 已持久化�?retry）；`switchToChat`：仅置位 + 返回 detail�?
- **`prompts/requirement-chat.md` 新模�?*：不锁定"需求分析师"角色、无 JSON 输出协议、无澄清轮自检清单段、无 progress 自评；占位符 `{{CONVERSATION_HISTORY}}`/`{{WEB_SEARCH_CONTEXT}}` 与澄清模板同构（CHAT 当前渲染"（无可用联网资料�?）；�?4 条职责明�?用户表达转方案意图时系统自动切换，本轮只需一句话提示"�?
- **API �?*：`ClarifyMessageRequest.initialMode`；Controller `create` 透传四参 + `POST /{id}/to-clarify` + `POST /{id}/to-chat`�?
- **前端（helloai-ui�?*：`types` �?`mode?: 'CHAT'|'CLARIFY'|null`；`clarify.ts` create �?initialMode + `toClarify/toChat`；`RequirementChat.vue`——标�?对话新建（AI 助手�?+ 模式徽标 el-tag（对�?info / 方案 warning）、conv-meta 小标签、进度条与终稿卡条件化（`!isChatMode`）、`isChatMode` computed、新会话 el-radio-group 模式选择（默�?CHAT）、「转为方案」warning 按钮（ElMessageBox.confirm �?toClarify，失败靠重试条续跑）、输入占位随模式切换（CHAT"自由提问，可随时转为方案模式" / CLARIFY 沿用澄清引导）�?

#### 3. 验证结果

- **单测**：`RequirementClarifyServiceTest` 新增 `@Nested ChatModeAndSwitch` 14 例全绿——chatRoundStoresPlainTextWithoutPayload（payload 显式 NULL�? chatRoundUsesChatPromptTemplate / legacyNullModeTreatedAsClarify（老数据兼容）/ intentPhraseAutoSwitchesMode / chatRoundDoesNotTriggerWebSearch / chatRoundFortyNineAllowed / chatRoundAtLimitRejected / intentAtChatLimitStillSwitchesMode（意图词永远放行�? createDefaultsToChatMode / createRejectsInvalidInitialMode / switchToClarifyRunsClarifyRound / switchToClarifyPersistsModeEvenOnLlmFailure / switchToChatFlipsModeOnly / finalizedCannotSwitchMode；既�?2 �?create 用例改显式传 `MODE_CLARIFY`。模�?35/35 全绿；helloai-core 全量 384 例仅 1 �?pre-existing Error（PlannerAnalysisServiceTest 拆解草案重加载，`git stash` �?stash 本轮两个 core 文件后重跑依然失败，确认非本轮回归）�?
- **构建**：`mvn -pl helloai-start -am package -DskipTests` 产出 `helloai-start-1.0.0-SNAPSHOT.jar`�?8M），启动�?Flyway 迁移日志确认 `[V39] requirement_conversation.mode 列与 CHECK 约束已就位`，`/api/health` 200；前�?`vue-tsc --noEmit` 0 错�?
- **E2E（真实环�?PASS�?*：新�?`scripts/shell/verify-planner-chat-dual-mode.sh`（UTF-8 �?+ set -euo pipefail + curl + jq，照 verify-requirement-clarify.sh 模板�? 步全绿——CHAT 建会（initialMode=CHAT）断言 mode=CHAT + 末条 assistant 回复 payload=NULL（纯文本）；二轮普通问题仍 CHAT�?2 消息）；to-clarify 断言 mode=CLARIFY + 新增一轮（messages 5）；推进一轮即产终�?`finalTitle=技术选型：微服务与单体架构对比分析` �?finalize 建任�?PENDING + 会话 FINALIZED + taskId 回填；意图词新会话（缺省 CHAT）首�?整理成方案�?自动�?CLARIFY；to-chat 反向切回断言仅置位不加消息。实�?chatConversationId=2084282161971728385 / intentConversationId=2084282265231298561 / taskId=2084282263423553538�?
- 脚本弹性设计：终稿未产出时降级断言"最后一�?assistant payload 为合�?JSON 且含 questions �?（结构化追问协议），避免 LLM 输出不确定性导致脚本假失败�?

#### 4. 影响与遗�?

- 行为兼容：老数�?mode=NULL �?CLARIFY 语义读取，既有澄清链路零改动；CLARIFY 分支代码路径�?V33/V34 一致�?
- 轮数语义变化：CHAT 会话 50 轮上限（意图词放行），CLARIFY �?20 轮；超限提示引导输入「整理成方案」转模式或新建会话�?
- 遗留：CHAT 模式联网搜索（阶�?2 计划，需评估按需检索时机与成本）、意图词正则覆盖度（可后续按用户话术补充）、切换轮不做首轮联网搜索（阶�?2 再评估）�?

### 6.45 意图词二次确认：去掉「转为方案」按钮，对话内确认转方案（V40�?026-08-03�?

#### 1. 背景与决�?

V39 的意图词命中即自动切 CLARIFY，前端另有「转为方案」按钮。用户反馈：误表�?误触会直接进入方案模式，缺少确认环节。产品决策（用户明确要求，确认形态经 AskUserQuestion 选定为「对话内确认」）�?*去掉「转为方案」按钮，意图词只触发二次确认**——命中意图词后不切模式、不�?LLM，服务端回复固定确认询问；用户回复确认词或再次表达意�?�?转入 CLARIFY（该条消息即澄清首轮）；回复其他内容 �?清标记继续自由对话�?

关键决策�?

- **意图词命中不再自动切 CLARIFY（V39 行为变更�?*：置 `pending_clarify_confirm` 标记 + 回复固定确认询问文案（`CONFIRM_ASK_MESSAGE`，不�?LLM、不加轮数、payload NULL）�?
- **确认词正�?*：`^(确认|确定|好的|可以|开始吧|开始|是的|没错|没问题|行|嗯|OK|ok|Yes|yes)([。！�??,.;；\s]|$)`——开头命中且后随标点/空白/结尾，避免「好的，但我还想先聊聊」这类误判；**仅待确认状态生�?*，普通对话不受影响�?
- **放行语义**：待确认状态的确认词（或再次意图词）跳�?CHAT 50 轮上限判定——确认消息转�?CLARIFY，不�?CHAT 轮，保证转方案出口不被上限挡住（�?V39 意图词放行同思路）�?
- **SMALLINT(0/1) 持久�?*（按代码规范 §9.3 不用 BOOLEAN）：实体保持 Java `Boolean` 字段 + 自定�?`SmallIntBooleanTypeHandler`（写�?`setInt(0/1)`、读�?smallint→Boolean），不注册全局映射�?`@TableField` 显式指定，避免影�?BOOLEAN 类型�?`web_search_enabled`。直接用 MyBatis 内置 BooleanTypeHandler 会报 `column is of type smallint but expression is of type boolean`�?
- **切换端点按代码规�?§8.2 整改**：V39 �?`POST /{id}/to-clarify`、`POST /{id}/to-chat` 违反「新代码必须 `POST /{action}ById/{id}`」规范，本轮一并整改为 `/toClarifyById/{id}`、`/toChatById/{id}`（前�?clarify.ts 同步；E2E 脚本同步）�?
- 前端移除「转为方案」按钮与 `handleToClarify`；`toClarify/toChat` 端点保留（无前端入口，供内部/测试用）�?

#### 2. 实际落地

- **Flyway V40 `requirement_conversation_add_pending_clarify_confirm.sql`**：`pending_clarify_confirm SMALLINT NOT NULL DEFAULT 0`（IF NOT EXISTS + �?COMMENT 明示 0=无待确认/1=等待确认 + V34 同款 DO $$ 验证块，启动日志输出 `[V40] ... 列与默认值已就位`）�?
- **`SmallIntBooleanTypeHandler`（helloai-core/shared/handler，新建）**：`BaseTypeHandler<Boolean>`，写�?`ps.setInt(parameter ? 1 : 0)`、读�?`getObject` �?1；Javadoc 说明 §9.3 背景与不注册全局映射的原因（对齐 `PgJsonbTypeHandler` 先例）�?
- **`RequirementClarifyService`**�?
  - 常量 `CONFIRM_PHRASE_PATTERN`（确认词正则）、`CONFIRM_ASK_MESSAGE`（固定确认询问文案，public 供单测断言）、`INTENT_TO_CLARIFY_PATTERN` 注释更新�?命中即进入二次确�?�?
  - `sendMessage` 上限分派：`intent`/`confirm`（待确认 + 确认词或意图词）均放�?CHAT 上限；CLARIFY（含 NULL 老数据）沿用 20 上限�?
  - `doRound` 三段状态机（仅 CHAT 模式）：意图词且无待确认 �?置位 + updateById + user 消息落库 + assistant 落固定确认询问（payload null�? 直接 return（不�?LLM 不加轮数）；待确�?+ 确认�?再次意图�?�?`setMode(CLARIFY)` + 清标�?+ updateById（该条消息即澄清首轮，rounds==0 时触发首轮联网搜索）；待确认 + 其他 �?清标记继�?CHAT 轮�?
  - `switchToClarify`/`switchToChat` 均防御�?`setPendingClarifyConfirm(false)`（手动切换清残留标记）�?
  - 辅助方法 `isPendingClarifyConfirm`（仅显式 true�?`isConfirmPhrase`（trim 后正�?find）�?
- **实体 `RequirementConversation`**：`private Boolean pendingClarifyConfirm` + `@TableField(typeHandler = SmallIntBooleanTypeHandler.class)` + Javadoc 说明 V40 语义�?
- **Controller**：`@PostMapping("/toClarifyById/{id}")`、`@PostMapping("/toChatById/{id}")`（�?.2 合规整改，`@PathVariable("id")` 显式命名�?c00d15f 先例）�?
- **前端（helloai-ui�?*：`clarify.ts` 路径�?`toClarifyById`/`toChatById` + 头注�?V40 说明；`RequirementChat.vue` 删除「转为方案」按钮块�?`handleToClarify` 函数（ElMessageBox 仍在 handleAbandon/handleFinalize 使用�?import 保留；`isChatMode` computed 保留用于进度�?终稿卡条件化）�?

#### 3. 验证结果

- **单测**：`RequirementClarifyServiceTest` �?`@Nested ChatModeAndSwitch` 14 �?19 例全绿—�? 例意图词用例改为待确认语义（`intentPhraseEntersPendingConfirm` / `intentAtChatLimitStillEntersPendingConfirm`：断言 mode �?CHAT + 标记置位 + 轮数不变 + executeSync never）、`switchToClarifyRunsClarifyRound` 增强（前置置�?+ 断言清标记）�? 例新增（`confirmPhraseSwitchesToClarifyRound` 确认词转 CLARIFY 走澄清模�?/ `confirmAtChatLimitStillSwitches` 50 轮放�?/ `nonConfirmMessageClearsPendingAndContinuesChat` 非确认内容清标记�?CHAT / `intentDuringPendingConfirmEntersClarify` 待确认中再次意图词直�?/ `createIntentPhraseEntersPendingConfirm` 建会首条意图词即待确认）�?
- **既有测试修复**：`PlannerAnalysisServiceTest.shouldDecomposeAndPersistDrafts`（V27 依赖校验引入的重加载防御门，pre-existing Error）补 `subTaskService.list(any(Wrapper.class))` stub（返回带 id/priority/context �?重加载结�?）——�?.44 记录�?pre-existing Error 本轮闭合�?
- **构建**：`mvn -pl helloai-core -am package -DskipTests=false` 全量 389/389 全绿；`mvn -pl helloai-start -am package -DskipTests` 产出 jar 启动�?Flyway 日志 `[V40] requirement_conversation.pending_clarify_confirm 列与默认值已就位`；前�?`vue-tsc -b` 0 错�?
- **E2E（真实环�?PASS�?*：`verify-planner-chat-dual-mode.sh` 改造后 8 步全绿——STEP5 `/toClarifyById`、STEP8 `/toChatById`（规范路径）；STEP7 意图词路径改�?V40 全流程断言：建会发意图�?�?mode=CHAT + `pendingClarifyConfirm=true` + roundCount=0 + �?2 条消�?+ 末条 assistant 为固定确认询问（含「回复「确认」」）�?回复「确认」→ mode=CLARIFY + 标记清除 + roundCount=1 + 消息 +2 走澄清轮。实�?chatConversationId=2084300744164569089 / intentConversationId=2084300858627125250 / taskId=2084300856865517569�?
- 真实环境还捕获并修复�?SMALLINT �?Boolean 映射问题（见 §2 TypeHandler），一次通过修复后全链路无回归�?

#### 4. 影响与遗�?

- 行为变更：意图词不再立即转方案（需对话内确认）；「转为方案」按钮移除，转方案入口收敛为意图词；CHAT 模式 50 轮上限的引导文案�?可输入「整理成方案」转为方案模�?）仍准确�?
- 老数据兼容：`pending_clarify_confirm` NULL/0 均视为无待确认（`Boolean.TRUE.equals` 判定），无迁移负担�?
- 遗留：确认词正则对「好的，开始吧」这�?确认词后直接跟内�?的话术暂不命中（需用户回短确认词，避免误判的设计取舍）；意图词/确认词正则覆盖度可后续按用户话术补充；`toClarify/toChat` 端点保留但无前端入口（V40 起语义收敛为内部/测试用）�?

#### 5. 同日追加优化（V40.1）：口语化意图词扩展 + 澄清首轮强制 structured

用户实测反馈：发「帮我整理方案吧」后 LLM 回复"切换到方案整理模�?但页面始终无推荐选项卡片，疑为功能被删。核实代码确�?V33 structured 全链路（prompt 双模协议 / `normalizeQuestionReply` 解析 / 前端 `StructuredQuestionCard` 渲染）完整保留；根因是「帮我整理方案吧」不命中意图词正则（固定词「整理成方案」为连续子串匹配，「整理方案」缺「成」字）→ 未触�?V40 待确认状态机 �?会话仍停�?CHAT 模式（LLM 那句"切换到方案整理模�?只是 CHAT 模板�?4 条的提示话术，系统实际未切换），CHAT 为纯文本协议故无选项卡片。据此追加两项优化：

- **意图词正则扩�?*：`INTENT_TO_CLARIFY_PATTERN` 追加口语化话�?`整理方案|出个方案|出方案|写方案|做个方案|做方案|方案整理`（Javadoc 注明放宽理由：误触有二次确认把关，回复其他内容即继续自由对话，无额外代价）�?
- **澄清首轮强制 structured**：`prompts/requirement-clarify.md` �?2 条追加�?*首次追问必须使用 structured**（判定：对话历史中尚无任�?助手追问"记录时即为首次追问，不得�?freeform 开场）」，保证进入 CLARIFY 后的第一轮必有推荐选项卡片（LLM 可自主决定后续轮次形态）�?

验证：`RequirementClarifyServiceTest` 新增 `colloquialIntentPhraseEntersPendingConfirm`（「帮我整理方案吧」→ 待确�?+ 固定确认询问 + 不调 LLM 不加轮数），helloai-core 全量 390/390 全绿；`verify-planner-chat-dual-mode.sh` STEP7 意图词改为口语化「帮我整理方案…」真实环境重�?8 �?PASS（待确认 �?回复确认 �?CLARIFY 链路不变）；服务已停止端口已释放�?

**口嗨切换治理（同日二次追加）**：用户手动会话实测「帮我整理方案吧」（V40 旧代码未命中）→ LLM 回复"切换到方案整理模�?但系统未切换（数据库实锤 mode=CHAT、payload=null），后续「帮我整理成技术方案文档吧」在新正则下也不命中（「整理成方案」为连续子串，中间隔「技术」）——LLM 反复口嗨的根源是 `requirement-chat.md` �?4 条引�?LLM"系统会自动切换，你只需提示"，而意图词命中时系统根本不�?LLM（直接回固定确认询问），该指令只在未命中时被执行。已把第 4 条改�?系统自动处理切换，你无需提及/预告/扮演方案整理模式，正常回答即�?（prompt 每次调用�?ClassPathResource 读取，同步到 target/classes �?IDEA 服务无需重启即生效）。同时实证：E2E 会话 2084318559537963009 �?CLARIFY 首轮 payload=`{"mode":"structured","progress":35,"questions":[2题，每题4选项，含 recommended=true]}`——V40.1 首轮强制 structured 真实生效，推荐卡片链路（prompt→服务解析→payload 落库→前端卡片）完整可用�?

### 6.46 /planner 斜杠命令直达方案模式 + CHAT 追问推荐卡片（V40.2�?026-08-04�?

#### 1. 背景与决�?

用户实测反馈两件事：①「帮我整理成技术方案文档吧」在 V40.1 正则下仍不命中（「整理成方案」为连续子串，中间隔「技术」，无法穷举口语话术）；�?希望所�?需要用户回答的问题"尽量以推荐卡片（structured）呈现，无论是否�?planner 模式。据此产品决策（经确认）�?

- **兜底入口 `/planner` 斜杠命令**（大小写不敏感）：输入框识别 `^/planner(\s+附加文本)?$`，命中即显式进入方案澄清（CLARIFY）模式——不依赖意图词命中率；命令前缀本身不落消息，附加文本（支持多行）先落库 user 消息�?LLM 上下文，再切 CLARIFY 跑一轮（V40.1 首轮强制 structured �?推荐卡片必出）�?
- **阶段 2 增强（LLM 引导型）**：CHAT 模板新增「输出形态」节——普通聊天一律纯文本，仅当需要向用户追问关键决策信息（技术选型/偏好/业务规模/可枚举场景）时输�?structured JSON（复�?CLARIFY 协议格式，每�?�? 题、每�?2~4 选项、recommended 每题至多 1 个）；服务端 CHAT 轮宽松解析（解析成功且合法才�?payload 出卡片，否则原样纯文本落库，零破坏）；前端放开 CHAT 模式交互卡限制（activeStructured 不再要求�?CHAT）�?
- **明确不做**：不锁定模式（CLARIFY 状态机天然保持）、不做命令历�?提示列表 UI、无表结构变化、CHAT 联网搜索�?LLM 流式输出维持现状�?

#### 2. 实际落地

- **后端（helloai-core + helloai-api�?*�?
  - `RequirementClarifyService.switchToClarify(Long, String)` 重载：extraMessage 非空 �?`addMessage(conversationId, ROLE_USER, extraMessage.trim(), null)` 落库（即�?LLM 上下文，不走意图�?确认词判定、不�?payload）→ 委托既有 `switchToClarify(Long)`（置 MODE_CLARIFY + �?pendingClarifyConfirm + `runLlmRound(conversation, "")`，V40.1 首轮强制 structured）。既有单参重载保持不动�?
  - `RequirementConversationController.toClarify`：body �?`@RequestBody(required = false) ClarifyMessageRequest req`（不�?@Valid），`req != null ? req.getMessage() : null` 透传；现有无 body 调用（E2E 曾传 "{}"）兼容�?
  - CHAT 轮容错双模（`runLlmRound` CHAT 分派处）：LLM 输出�?`tryParseChatStructured` 宽松提取（复�?``` 围栏/首字�?{ 处理 + `LlmJsonSanitizer`）→ 仅当 `type=question && mode=structured && isStructuredValid` �?`composeAssistantContent` 可读拼接 + payload 落库（模式仍 CHAT、不触发联网搜索）；其余（解析失�?�?question/freeform/final/纯文本）一律原样纯文本落库（payload NULL），异常捕获返回 null 不抛——与 CLARIFY �?parseReply 严格路径完全隔离�?
- **前端（helloai-ui�?*�?
  - `clarify.ts toClarify(id, message?)`：body `{ message: message ?? null }`，注释补 V40.2 语义�?
  - `RequirementChat.vue`：常�?`PLANNER_COMMAND_RE = /^\/planner(?:\s+([\s\S]+))?$/i`；`handleSend` 开头命�?�?`handlePlannerCommand(cmd[1]?.trim() ?? '')` �?return；`handlePlannerCommand(extra)`：无 ACTIVE 会话 �?`clarifyApi.create(extra || '请帮我整理一份技术方�?, plannerId, webSearchEnabled, 'CLARIFY')` 新会�?initialMode=CLARIFY 直达，已有会�?�?`clarifyApi.toClarify(activeId, extra || null)`，错误路径复�?handleSend �?catch 模式（刷新详�?按标题找回）；CHAT 模式输入�?placeholder 追加「输�?/planner 可直接进入方案整理」；`activeStructured` 去掉 `isChatMode` 条件（会�?ACTIVE 且末�?assistant 为合�?structured payload 即可交互，历史只读卡逻辑不动）�?
- **Prompt**：`prompts/requirement-chat.md` 新增「输出形态（V40.2，重要）」节（普通聊天纯文本不输�?JSON；仅追问关键决策信息时优�?structured JSON 并给出协议示例与约束；无法枚举选项�?freeform）�?

#### 3. 验证结果

- **单测**：`RequirementClarifyServiceTest` ChatModeAndSwitch 19 �?23 例全绿——新�?4 例：`switchToClarifyWithExtraMessage`（CHAT 会话 + extra �?mode=CLARIFY、pending=false、user 消息落库 content=extra、roundCount+1、消�?+2、LLM stub 输出 structured 追问�? `switchToClarifyWithBlankExtraEqualsLegacy`（extra �?空白 �?不加消息与既有单参一致）/ `chatRoundStructuredQuestionStoresPayload`（CHAT stub 输出 structured �?payload 落库 + content 可读拼接 + mode �?CHAT + 不触发联网搜索）/ `chatRoundFreeformJsonStillPlain`（freeform/非结构化 �?payload NULL 降级）。helloai-core 全量 `mvn -pl helloai-core -am test -DskipTests=false` 394/394 全绿�?
- **前端**：`vue-tsc --noEmit` 0 错�?
- **E2E（真实环�?PASS�?*：`verify-planner-chat-dual-mode.sh` 改造后全流�?PASS——STEP4.1 新增 CHAT 轮宽松断言（发「需要你问我几个问题帮我做选型」→ mode �?CHAT + 消息 +2；payload 非空则必须合�?structured，不强求出现）；STEP5 改造为 `toClarifyById` �?`{"message":"补充：团�?0人，单体优先"}` �?断言消息 +2（user 附加文本 + assistant 澄清轮）、附加文本确已作�?user 消息落库、mode=CLARIFY、末�?assistant；STEP6 追推终稿 �?finalize 建任�?PENDING �?FINALIZED + taskId 回填；STEP7/7.1/8 意图词确认流与反�?toChat 回归通过。实�?chatConversationId=2084324277456347138 / taskId=2084324405969821698。本次真�?LLM �?STEP4.1 未输�?structured（降级纯文本，记录观察），STEP5 �?CLARIFY 后首轮仍追问、追推一轮即产终稿�?

#### 4. 影响与遗�?

- 行为变更：`/planner` 命令（含附加文本）成为显式转方案入口，不受意图词命中率影响；CHAT �?LLM 追问可能出推荐卡片（LLM 引导型，不保证）�?
- 兼容性：`toClarifyById` �?body / `{}` 调用与既有语义完全一致（req �?null �?message �?null 均走单参路径）；CHAT 轮解析失败一律纯文本，零行为破坏�?
- 遗留：CHAT 结构化追问输出依�?LLM 遵循度（真实环境本次未出卡片，属可接受降级；后续可考虑 few-shot 或独立小模型，不在本轮范围）；`/planner` 无命令提示列�?UI（仅输入框识别）；意图词正则仍为有限话术集合（`/planner` 已作兜底，无需继续扩词）�?

### 6.47 子任务分发失败快速兜�?+ 整合报告生成状态防重（V41�?026-08-04�?

#### 1. 背景与决�?

用户实测反馈两件事：①�?0人小团队企业OA系统模块化单�?微服务演进技术方案」任务的子任�?#7「部署方案验证」长时间�?agent 领取，最终用户手动选择空闲 agent 才解决，问根因与兜底办法；② planner 生成最终报告时报告按钮可重复点击、主任务状态不显示"报告生成�?，会出现重复生成报告的问题�?

**Q1 根因（数据库取证闭合�?*�?7:16:36 依赖 #3「容器化部署方案设计」DONE �?`SubTaskCompletionListener.unlockDownstream` 触发 #7 分发 �?ready 守卫通过 �?`checkReassignCircuitBreaker` 累加 `reassign_attempt_count=1`�?7 timeline 完全�?`dispatch_prepare` 事件佐证：异常发生在写审计事件之前）�?`AgentSelector.pickPreferred` �?`require-idle: true` 下用 `inProgressCount==0` 过滤——当时两�?EXECUTOR（executor-deps-ctx �?#1/#2/#4、inner-deepseek-executor �?#5/#6）全部在忙（#1 17:16:44�?2 17:17:05�?4 17:17:17�?5 17:16:51�?6 17:17:03 �?DONE）→ 候选为�?�?`pickPreferred` �?BizException �?该异常在 `unlockDownstream` 逐节�?catch 中仅 warn 吞掉（无 timeline 事件）→ #7 保持 PENDING 且无 execution_record �?孤儿巡检（`SubTaskPendingOrphanTask`�?0 分钟阈值未�?�?用户 17:19:29 手动指派（timeline 第一条事件即 `sub_task_auto_execute_dispatch`，走 changeStatus 不经�?dispatch 所�?count 保持 1）。本质是「瞬时全员忙�?+ 异常静默 + 兜底窗口过长」三层叠加的小概率事件�?

**决策（Q1�?*：① 孤儿巡检阈�?30�? 分钟——`isReady` 依赖守卫保证未就绪的合法 PENDING 会被跳过不误伤，收窄阈值安全，无人兜底窗口�?30 分钟缩到 5 分钟；② `unlockDownstream` 解锁失败�?`sub_task_dispatch_deferred` timeline 事件，把"静默吞掉"变成可观测�?

**决策（Q2�?*：报告生成状态独立成 `FinalReportStatus` 四态（NONE/GENERATING/DONE/FAILED），�?`TaskStatus`（保�?DONE 语义）解耦—�?报告生成�?塞进任务状态机会破�?DONE 语义与自动收尾判定；后端 CAS 防重入保证手�?自动两条路径并发只有一个赢家，前端按钮禁用 + "报告生成�?状态展示�?

#### 2. 实际落地

- **后端（Q1 兜底�?*�?
  - `AgentExecutionProperties.pendingOrphanThresholdMinutes` 默认 30�?，Javadoc 说明收窄安全的前提（扫描命中后循环内还有 isReady 依赖守卫）；`application.yml` execution 段显式声�?`pending-orphan-threshold-minutes: 5` 并注释两种覆盖场景�?
  - `SubTaskCompletionListener.unlockDownstream` 逐节�?catch 内写 `sub_task_dispatch_deferred` timeline 事件（payload �?reason + waitFor=pending_orphan_scan，内�?try-catch 失败�?log.debug，不改变既有不阻断语义）�?
- **后端（Q2 报告状态）**�?
  - `FinalReportStatus` 枚举（helloai-common/constant）：`NONE / GENERATING / DONE / FAILED`�?
  - Flyway V41 `task.final_report_status VARCHAR(16) NOT NULL DEFAULT 'NONE'` + 存量回填（`final_report` 非空 �?`DONE`�? 逐列 COMMENT�?
  - `TaskFinalReportService.generate`：生成前 CAS 防重入（`lambdaUpdate eq id + ne GENERATING + set GENERATING`，失败抛「任务整合报告正在生成中，请稍候后再试」）；成功写�?4 列（final_report/final_report_agent_id/final_report_time/final_report_status=DONE）；最终失�?`markFailed`（置 FAILED 可手动重试，避免进程崩溃后永久卡 GENERATING 无恢复口，失败不外抛）；`onTaskAutoCompleted` �?已有报告跳过"之前�?GENERATING 跳过（自动路径不与手动路径并发触发）�?
  - `TaskController.toFinalReportResponse` �?`TaskFinalReportResponse` 增加 `status` 透出�?
- **前端（Q2�?*�?
  - `TaskList.vue`：状态列 `GENERATING` 覆盖显示「报告生成中」tag；报告按�?`:loading/:disabled="row.finalReportStatus === 'GENERATING'"`，文案动态「生成中�?「报告」�?
  - `FinalReportDialog.vue`：`reportGenerating` computed（本�?generating || 接口 status===GENERATING）；5s 轮询（非 GENERATING 即停，onBeforeUnmount 清理）；handleGenerate 前置守卫 + 同步 `props.task.finalReportStatus`；空态文案按状态区分（GENERATING→「报告正在生成中…�? FAILED→「上次生成失败，点击下方按钮重新生成」）�?
  - `types/index.ts`：`FinalReportStatus` 类型 + `Task.finalReportStatus` + `TaskFinalReport.status`�?

#### 3. 验证结果

- **单测**：helloai-core 全量 `mvn -pl helloai-core -am test -DskipTests=false` 397/397 全绿——`TaskFinalReportServiceTest` 新增 3 例（`shouldRejectWhenAlreadyGenerating` CAS 拒绝 / `shouldMarkFailedStatusWhenLlmFails` 失败�?FAILED / `shouldSkipAutoWhenGenerating` 自动路径跳过），并修复单测陷阱：`new LambdaUpdateWrapper<Task>()` �?lambda 解析需�?TableInfo 缓存，`@BeforeAll` �?`TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class)` 注册（BaseEntity �?@TableId 注解可正常注册）�?
- **前端**：`vue-tsc --noEmit` 0 错�?
- **真实环境冒烟 PASS**：V41 迁移成功（存量报告回�?DONE）；`GET /api/tasks/{id}/final-report` 返回 `status=DONE`（content 18760）；列表接口返回 `finalReportStatus`�?*并发防重全链�?*：第一�?POST �?10s �?DB=`GENERATING` �?第二�?POST 被拒 `{"code":500,"msg":"任务整合报告正在生成中，请稍候后再试: taskId=..."}` �?第一次完�?`code=200 status=DONE` content=19091（覆盖旧报告）→ DB 终�?`DONE|19091`。冒烟后已停服务释放端口�?

#### 4. 影响与遗�?

- 行为变更：孤�?PENDING 无人兜底窗口 30 分钟�? 分钟（isReady 守卫保证不误伤）；`unlockDownstream` 分发失败不再静默（timeline `sub_task_dispatch_deferred` 可见）；报告生成期间按钮禁用 + 状态「报告生成中」，重复生成被后�?CAS 拒绝�?
- 兼容性：`final_report_status` 默认 NONE 对存量零影响；FAILED 状态可重新生成；任�?DONE 语义不变（报告状态独立维度）�?
- 遗留：「全员瞬时忙碌」时子任务仍会落�?PENDING 等待孤儿巡检（现 5 分钟），未做排队等待/延迟重试策略（可后续考虑，不属本轮）；`sub_task_dispatch_deferred` 事件无前端消费（可在派发控制台时间线查看）�?

### 6.48 /planner 命令缺失 await 修复�?026-08-03�?

#### 1. 背景与决�?

用户报告 `/planner` 斜杠命令在需求澄清对话框中报错。追踪全链路：`RequirementChat.vue` `handleSend()` �?`handlePlannerCommand()` �?`clarifyApi.toClarify()` �?后端 `switchToClarify()` �?LLM 轮。定位到 `handleSend()` 中匹�?`/planner` 命令后调�?`handlePlannerCommand(cmd[1]?.trim() ?? '')` **缺少 `await`**，导致异步操�?fire-and-forget：`handleSend` 在异步完成前立即返回，`sending` 状态未及时置位，`finally` 清理逻辑跳过，并发重入风险�?

注意：PLANNER Agent �?inner API_KEY_LLM 类型（类比线程池核心线程），无在�?离线状态概念，OFFLINE 状态不是问题原因�?

#### 2. 实际落地

- 前端 `RequirementChat.vue` L428：`handlePlannerCommand(cmd[1]?.trim() ?? '')` 改为 `await handlePlannerCommand(cmd[1]?.trim() ?? '')`，纳入正�?await 链路�?

#### 3. 验证结果

- 代码审查确认：`handlePlannerCommand` 返回 Promise（async 函数内调 `clarifyApi.toClarify` / `clarifyApi.create`），缺失 await 导致 fire-and-forget�?
- 修复�?`/planner` 命令应正常走完创�?切换→LLM 调用→前端刷新全流程�?

#### 4. 影响与遗�?

- 行为修复：`/planner` 命令不再因并发状态竞态引�?sending 未置位、重复发送等问题�?
- 无新增依赖或配置变更�?

### 6.49 REVIEW 孤儿扫描兜底�?026-08-03�?

#### 1. 背景与决�?

用户报告任务「内部周报自动汇总工具开发」的两个子任务（#1 企业微信API对接�?2 需求分析与规划）卡�?审查�?（REVIEW）状态，reviewer agent（`inner-kimi-reviewer`）未被调用�?

数据库取证（dev 环境）：
- `sub_task` 表：2 �?REVIEW 子任务（`update_time` 07:55�?
- `review_record` 表：EMPTY（无任何核验记录�?
- `agent_inbox` 表：EMPTY（inner API_KEY_LLM Agent 不走 inbox，符合设计）
- `agent_outbox_event` 表：2 �?`sub_task.review` 事件已发布（routing_key=`agent.reviewer.assigned`），但无 consumer 消费
- `task_timeline` 表：只有 `sub_task_execute_submit`，没�?`sub_task_auto_review_*` 事件
- `event_consumption_log` 表：所�?consumer 均为 `MqExecutionCommandConsumer`，无 reviewer consumer

**根因**：L1 主路�?`SubTaskSubmittedForReviewEvent` �?`@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Async` 未触发（timeline �?`sub_task_auto_review_*` 证据）；L2 MQ 备份路径 `agent.reviewer.assigned` 路由已绑�?`reviewerQueue`，但代码库无 `MqReviewCommandConsumer` 消费端。双路径均断裂，子任务永久卡 REVIEW�?

**决策**：不新增 MQ 消费者（涉及队列/交换�?幂等/确认等全套基建），而是�?L3 DB 状态扫描兜底——`@Scheduled` 定时扫描 REVIEW 状态且�?`review_record` 的孤儿子任务，直接调用既�?`reviewSubTask()` 触发核验。与 ExecutionCommandPoller 孤儿扫描（�?.32 T5）同�?主路�?+ 兜底扫描"冗余容错哲学�?

#### 2. 实际落地

- **`AgentDispatchProperties`**（helloai-common/config）：新增两项配置——`reviewOrphanThresholdSeconds`（默�?60s，子任务进入 REVIEW 超过此阈值且�?review_record 视为孤儿�? `reviewOrphanBatchSize`（默�?10，每轮扫描上限）�?
- **`SubTaskService.listReviewOrphans`**（helloai-core）：查询 REVIEW 子任务（`status=REVIEW AND update_time <= threshold`，按时间升序 LIMIT batchSize），逐条 `reviewRecordMapper.selectCount` 检查是否已�?review_record，过滤掉已有记录的（防止重复触发）�?
- **`SubTaskReviewService.scanReviewOrphans`**（helloai-core）：`@Scheduled(fixedDelayString=30_000)`�?0s 间隔扫描。开�?`autoReviewEnabled` 关闭时跳过；�?`subTaskService.listReviewOrphans` 取候�?�?逐条 `reviewSubTask(st.getId(), st.getAssignedAgentId())`（pickReviewerAgent 选同角色 REVIEWER �?�?LLM �?parseVerdict �?completeOrRejectAndRework）。异常单条捕获不影响批次内其他候选�?

#### 3. 验证结果

- `mvn compile -pl helloai-common,helloai-core -am -DskipTests` BUILD SUCCESS�?
- 代码审查确认：`scanReviewOrphans` �?`ExecutionCommandPoller` 兜底模式一致，30s 间隔 + 60s 阈值确保不误伤正常流程�?

#### 4. 影响与遗�?

- 三级容错架构成型：L1 `@TransactionalEventListener(AFTER_COMMIT)` 主路�?�?L2 MQ `agent.reviewer.assigned`（无 consumer，待后续补齐）→ L3 `@Scheduled` DB 孤儿扫描兜底�?
- 行为变更：REVIEW 子任务最多等�?60s（阈值）+ 30s（扫描间隔）= 90s 即可被兜底扫描捕获并核验�?
- 遗留：L2 MQ reviewer consumer 仍缺失——当�?L3 兜底已足够（inner reviewer 无离线概念），MQ 路径待后�?If-needed 补齐�?
- 部署提示：重启后端后生效；已卡住的子任务需等待 60s 阈值窗口到达后首次扫描核验（或手动 SQL 重置状态触发即时流程）�?

### 6.50 门铃搁置下线：外�?Agent 单向执行器无法消费平台推送（2026-08-07�?

#### 1. 背景与决�?

基于对外�?AI Agent（安装版 REPL / CLI �?Headless）的调研结论：两�?Agent 均为"单向执行�?——无平台双向交互能力，任务派�?完成依赖平台 MQ 内部链路，且 Agent 端代码不可修改（无法增加推送消费逻辑）。平台门铃（AgentHub V3 SSE 推送）虽已完整交付（PR-1~PR-4，E2E 实测通过），�?*没有任何 Agent 端消费�?*，属�?平台能推、Agent 收不�?的技术瓶颈�?

**决策**（用户拍板）�?
- **任务感知方案定稿（方�?A�?*：Agent 定时轮询收件箱（`pullTasks`，建�?30s 一次）。平台内�?MQ 链路（Outbox �?AGENT_TOPIC_EXCHANGE �?notificationQueue �?NotificationConsumer �?agent_inbox）保持不动，不暴露给 Agent�?Agent 直接消费 MQ"记为远期演进项，本轮不实现�?
- **门铃处置**：Java 代码（DoorbellService/Ringer/Properties、REST 端点 `/api/agents/doorbell/sse`）全部保留运行，仅加类注释说明搁置原因；SKILL.md（executor/planner）与 PowerShell 脚本（qoder-ceshi-checkin/daemon、outer-trae-daemon）下线门铃内容（脚本仅加头部注释，功能不动）�?
- **双通道保留**：MCP（标准接入：保活 + 全套工具）与 REST（脚本轮询兜底）职责分工不变�?

#### 2. 实际落地

- **Java 注释（不改业务逻辑�?*：`DoorbellService` / `DoorbellRinger` / `DoorbellProperties` �?Javadoc 追加"状态注记（2026-08-07�?搁置说明；`AgentMcpServerService` 设计原则注释�?checkIn 工具描述同步修正（去�?门铃长连接前�?表述）；`AgentDutyLeaseService` 两处门铃断连注释�?门铃已搁�?注记�?
- **SKILL.md 改写（executor + planner�?*：接入方式表删除"门铃长连�?，改�?MCP 纯工具调�?/ REST 轮询兜底"两段式；`checkIn`/`pullTasks` 工具描述去门铃语义（pullTasks 定为"唯一任务感知通道"）；§1.3 工作循环改为纯轮询循环（getAgentStatus �?checkIn �?30s heartbeat + 30s pullTasks �?claim �?执行 �?submitResult �?checkOut）；§1.5 常驻打卡协议整节改写�?轮询值守协议"（两件套：heartbeat + pullTasks，TTL 到期�?60s 重做 checkIn，删门铃三件�?断连重连/daemon 脚本引用）；§2 门铃长连接整节替换为"已搁�?说明；�?.4(4)"门铃连上≠进程健�?改为"心跳是唯一的在线证�?（强调业务调用只�?last_active_time 不维持在线）；REST 段收敛（删积�?活动日志，保留收件箱/规则/子任�?审查）；错误码速查表删门铃语义�?00 行原因改"�?checkIn 就调用依赖在岗状态的能力"）�?
- **脚本头部注释�? �?ps1�?*：`qoder-ceshi-checkin.ps1` 追加"门铃探针步骤仅作历史链路验证参�?；`qoder-ceshi-daemon.ps1` / `outer-trae-daemon.ps1` 追加"门铃 SSE 监听逻辑仅作历史参考，值守请改用纯轮询（heartbeat + pullTasks�?。仅�?`#` 注释行，业务代码不动，保�?UTF-8 with BOM�?
- **文档回填**：`doc/archive/HelloAI_门铃通知通道设计.md` 头部�?已搁�?状态注记；`doc/HelloAI_实现差距�?md` N13 条目状态改"已搁�?并注明原因�?

#### 3. 验证结果

- `mvn -pl helloai-core -am compile -q` BUILD SUCCESS（Java 注释改动）�?
- PowerShell Parser �?3 个改动脚本静态语法自检 0 error�?
- Grep 检�?`resources/skills/` 下门铃字样：仅保�?已搁�?说明句，无操作语义残留�?
- 未运行后端服务（无行为变更）�?

#### 4. 影响与遗�?

- 任务感知时延�?秒级（门铃）"回归"轮询级（30s�?，外�?Agent 感知新任务最坏延迟约一个轮询周期�?
- 平台�?MQ 内部链路、门�?Java 代码、REST 端点全部保留，未�?Agent 端常�?daemon（官方插�?/ CLI 包装器）落地后可复用门铃通道�?
- "Agent 直接消费 MQ"记为远期演进项（优先级最后）；CLI 版免保活（Headless 单次执行无值守）为新需求，待单独设计�?

### 6.51 平台配置动态化：先启动后配�?API Key + 外网地址断层修复�?026-08-07�?

#### 1. 背景与决�?

- **目标�?*：第一次部署只需环境变量 `HELLOAI_CREDENTIAL_AES_KEY_BASE64`（凭证加密密钥，唯一无法入库的部署配置），数据库�?Flyway 自动初始化、admin 账号�?AdminInitializer 自动创建，平台即可启动；LLM Provider �?API Key 由管理员登录后在"系统设置"页填�?轮换，写�?`credential_vault`（AES-GCM 加密，PLATFORM 级），实时生效无需重启�?
- **现状问题**：yml `helloai.providers.<name>.api-key` 启动绑定一次、运行期不可变，且写死真实默�?key（隐式预�?+ 明文风险）；`spring.ai.deepseek.api-key` 置空�?`DeepSeekChatAutoConfiguration` 启动�?fail-fast（实测发现，计划外问题，�?§2 修复）；外网地址断层——Settings.vue 能写 `helloai.base-url` �?sys_config，但 `AgentController.getMySkill` / `AdminAgentController.onboarding` �?baseUrl 解析不读 sys_config，SKILL 生成�?fallback `localhost:6565`�?
- **决策**（用户拍板）：平台级密钥�?credential_vault 加密存储（非 sys_config 明文）；UI 扩展现有"系统设置"页（非新菜单）；本轮包含外网地址断层修复。明确不做：SetupWizard �?API Key 步骤、超时参数动态化、SKILL.md 内容修改、Spring Cloud Config / Actuator refresh 新依赖、独�?模型配置"菜单页、SetupController 修改�?

#### 2. 实际落地

- **DB 迁移**：`V45__credential_vault_platform_owner.sql`（沿�?V1/V14 同名约束，先 DROP IF EXISTS �?ADD）放开 `chk_credential_vault_owner_type` CHECK（`'AGENT'` �?`'AGENT','PLATFORM'`�? �?COMMENT 说明 PLATFORM �?owner_id 固定占位 0、按 provider 唯一；索引不动（V14 uk 索引名历史遗留）�?
- **枚举与凭证服务扩�?*：`CredentialOwnerType` 新增 `PLATFORM`；`CredentialVaultService` 抽出私有泛化方法（getActiveApiKey / saveApiKeyCredential / rotateApiKey），Agent 版方法全部委托私有方法，新增 5 个平台级方法（getActivePlatformApiKey / listPlatformCredentials / hasActivePlatformCredential / savePlatformApiKeyCredential / rotatePlatformApiKey）�?
- **新增 `PlatformProviderConfigService`**（core/agent/chat）：getApiKey（vault PLATFORM �?ACTIVE 凭证解密明文 > yml > null，支�?secretRef�? getBaseUrl / getDefaultModel（sys_config `llm.provider.<name>.*` > yml > Factory 内置默认�? saveApiKey（AES 加密 �?vault rotate �?`ProviderChatModelCache.clear()` 实时生效�? saveSettings（写 sys_config�? isApiKeyConfigured / maskApiKey（仅�?4 位）/ isApiKeyFromVault；参数校验统一 BizException�?
- **后端接线**：`LlmProviderCatalogService` 三处改造（`listProviders()` �?apiKeyConfigured 改调配置服务、`bindPlatformApiKeyIfAbsent` 平台 key 来源�?`getApiKey(provider)`、provisionPlatformCredential 不变）；DeepSeek / Minimax / AbstractOpenAiCompatible 三个 Factory �?buildChatModel �?baseUrl/defaultModel 改走配置服务（缓�?key �?baseUrl 指纹不变），Moonshot / DashScope 构造器同步补参；`AgentChatClientService` / `ApiKeyAgentExecutor` / `AgentExecutionConnectivityService` 不改（Agent �?vault 链路已动态化）�?
- **外网地址断层修复**：新�?`AgentBaseUrlResolver`（helloai-api/support），解析优先�?`sys_config["helloai.base-url"]`（设置页可写�? yml `helloai.agent.base-url` > 请求推导（scheme://serverName:port�? `http://localhost:6565`；`AgentController.getMySkill` �?`AdminAgentController.getOnboardingContent` 改调 resolver�?
- **管理接口**：新�?`AdminProviderConfigController`（`/api/admin/platform/providers`，鉴权沿�?AuthInterceptor �?`/api/**` 的统一保护，与 AdminConfigController 同等水平）：`GET /list`（name / defaultModel / baseUrl / apiKeyConfigured / apiKeyMasked / available / apiKeyFromVault）、`PUT /{provider}/api-key`（body {apiKey}）、`PUT /{provider}/settings`（body {baseUrl, defaultModel} 均可选，传空清除覆盖�?yml 默认）；配套 3 �?DTO（ProviderConfigItem / ProviderApiKeyRequest / ProviderSettingsRequest）�?
- **yml 清理（关键安全项�?*�? �?provider �?api-key �?`spring.ai.deepseek.api-key` 全部置空�?`${XXX_API_KEY:}`；新�?`spring.autoconfigure.exclude: DeepSeekChatAutoConfiguration`（修复置空后启动 fail-fast——Agent 执行链已 100% �?Factory 程序化构�?DeepSeekApi，该 autoconfig 仅剩 ChatClient.Builder 兜底�?`ObjectProvider.getIfAvailable` 缺失不阻断启动）；base-url / default-model / 超时保留为默认值；providers 段注释更新标�?可在系统设置页动态配置，api-key 为空�?provider 未生�?�?
- **前端**：`helloai-ui/src/api/settings.ts` 新增 listProviders / saveProviderApiKey / saveProviderSettings + ProviderConfigItem 接口；`Settings.vue` �?基础配置"�?通知配置"之间新增"模型配置（LLM Provider�?区块（el-table：Provider / 默认模型 / Base URL / API Key 脱敏或黄�?未配�? / 状�?/ 操作�? "配置 Key"对话框（password 输入，placeholder 提示可覆盖旧 Key�? "编辑"对话框（baseUrl / defaultModel 均可选），保存后提示"配置已生效，无需重启"并刷新列表�?

#### 3. 验证结果

- 单测 `PlatformProviderConfigServiceTest` 10/10 全绿（DB 优先 / yml 兜底 / 轮换幂等 + 缓存 clear / 脱敏 / 可用性判定，�?Mockito �?Spring 上下文）�?
- `mvn -pl helloai-core,helloai-api -am compile -q` �?`mvn -pl helloai-start -am package -DskipTests=true` BUILD SUCCESS；`npx vue-tsc -b` 0 错�?
- **local profile 启动冒烟**（`--spring.profiles.active=local`，连本机 docker 中间件）：后端启动成�?`/api/health` 200，Flyway 自动应用 V45 成功（日�?"Successfully applied 7 migrations to schema public, now at version v45"）�?
- `scripts/powershell/verify-platform-config.ps1 -ReadOnly`（复用运行中后端）PASS 4 / FAIL 0：admin 登录 OK�? �?provider 列表全部 `apiKeyConfigured=false / apiKeyMasked=null / available=false`（yml 置空生效）；`listLlmProviders` 目录同步正常（factorySupported=true、available=false）；脚本遵循规则 6（UTF-8 with BOM + 编码强制�?+ 单引号拼接），`Parser.ParseFile` 静态自检 0 error�?ReadOnly 模式不写库（S3 �?Key 前退出）�?
- **e2e 完整写库链路实测（local profile，用户将 `spring.profiles.active` 切为 local 后执行）**：`scripts/powershell/verify-platform-config.ps1` 由脚本自拉起 jar（不重启进程�?*PASS 22 / FAIL 0，ALL PASSED**：S2 初始 4 provider 全部未配置（yml 置空生效）→ S3 PUT api-key 写入测试 key �?S4 实时生效（available=true / apiKeyFromVault=true / 脱敏 `****0001`）→ S5 目录同步（listLlmProviders available=true）→ S6 注册 API_KEY_LLM Agent �?S7 AGENT �?ACTIVE 凭证自动补绑（hasEncryptedValue=true）→ S8 sys_config �?`helloai.base-url` �?getMySkill SKILL 内容立即包含该地址（不重启）并写回空串还原。全程单进程实时生效，无重启�?
  - **�?首轮运行环境纠偏（重要事实链�?*：首次手动执行（15:12）与第二轮复跑（15:16）时，jar 内打包的 `application.yml` 仍为 `active: dev`（src 已改 local，但改后未重�?`mvn package`；IDEA 自动构建只同步了 `target/classes` 不重�?jar），后端实际连服务器�?`39.106.204.43:15432`，两�?S3-S8 均写入服务器共享库（PLATFORM/AGENT 级测试凭�?+ `platform-config-e2e` agent，sys_config 已还原）。第三轮�?5:25）重�?`mvn package`（jar �?`active: local`）后连本�?docker local 库（localhost:15432，干净库）实现**真正�?local 全链�?22 PASS / 0 FAIL**（agentId=2085628380873048065，与服务器库残留 2085625109789908994 区分）。服务器库残留清�?SQL 已提供给用户执行（UPDATE 软删 credential_vault PLATFORM×2 / AGENT×1 + agent×1）�?
  - **教训**：修�?resources 下配置（�?`application.yml` �?profile/key）后必须重新 `mvn package` 再验证，IDE 自动构建�?`target/classes` 同步不能代表 jar 产物；e2e 脚本启动 jar 前可加一�?jar 内配置校验（如对�?jar �?application.yml �?src �?`profiles.active`）�?
- **待实测项已清�?*：唯一未在真实环境回归的是"写库后真�?LLM 调用"（Factory 用测�?key 无法真连 DeepSeek），属既�?verify-agent-llm-connectivity 范畴，不阻塞本轮�?

#### 4. 影响与遗�?

- 老环境兼容：yml 已配 key �?vault �?PLATFORM 记录�?getApiKey 回退 yml，行为与现状完全一致；删除 vault PLATFORM 记录即回�?yml 配置行为�?
- 新环境：yml 空时 provider 标记"未配�?，注册平台内 LLM Agent 下拉禁用（现有前端逻辑），不阻断平台其他功能�?
- Agent �?vault 凭证不受影响（owner_type 区分，唯一索引�?(owner_type, owner_id, provider, credential_type) 隔离）�?
- `ProviderChatModelCache.clear()` 全清：正在执行的调用持有旧实例引用不受影响，完成后旧实例无引用即�?GC；可接受�?
- 遗留：平台级凭证暂无删除接口（轮换可覆盖）；管理端鉴权与 /api/admin/* 同等水平（AuthInterceptor 统一保护，不强加新权限体系）；本�?e2e 写库实测待用户确认后执行�?

---

### 6.52 LLM Provider 动态化方案B（V46，N9 §6.51 后续）（2026-08-07�?

#### 1. 背景与决�?

- **目标�?*：LLM Provider 全部配置（`protocol_type / base_url / default_model / enabled / sort_order / extra_config`）从 `llm_provider` 表读取，运行时数据库为唯一事实源；管理员在“系统设置”页可动态添�?/ 修改 / 启用-禁用 / 删除平台供应商（�?OpenAI 兼容�?Anthropic 兼容两种 protocol，后续按需扩展）；API Key �?credential_vault 仍不变（§6.51 闭环）；外部访问地址 `sys_config["helloai.base-url"]` 不动，本轮明确其用途从“系统基本配置”调整为“生�?SKILL 接入地址”�?
- **决策**（用户拍板）：DB 驱动�?Provider 配置，全�?`llm_provider`，不拆多表；deepseek 保留专用 Factory（官�?SDK，`DeepSeekChatModel`），其他三家（moonshot/dashscope/minimax）全部走通用 ProtocolFactory；兼容协议本轮仅�?`OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE` 两种；�?yml `helloai.providers.*` 保留兜底（`AgentProviderProperties` 不动），migration 一次性把 4 �?INSERT �?builtin 记录；旧 `AdminProviderConfigController` 兼容保留 / �?`AdminLlmProviderController` 为正主；拖拽排序前端不实现（仅占�?`sort_order` 字段、后�?ready）；`from external import` 第三方批量导�?UI 不做。明确不做：API Key 动态化（已�?§6.51 闭合）、拖拽排序前端、Provider 粒度限流 / 配额、事件总线配置变更广播、第三方批量导入、Provider 配置变更审批流�?

#### 2. 实际落地

- **DB 迁移**：Flyway `helloai-start/src/main/resources/db/migration/V46__llm_provider_table.sql`�?1 行）——`CREATE TABLE llm_provider`�?0 业务�?+ `chk` 不需要走 §9.3 因为全部�?NOT NULL 或带 DEFAULT，加雪路 Id `IdType.ASSIGN_ID`�? `idx_llm_provider_enabled` 部分索引 WHERE deleted=0 + `update_update_time` 触发�?+ 幂等 `INSERT ... ON CONFLICT (provider_code) DO NOTHING` 4 �?builtin（deepseek/moonshot/minimax/dashscope；minimax �?ANTHROPIC_COMPATIBLE，其他三�?OPENAI_COMPATIBLE�? `setval` 序列同步�?
- **实体 / Mapper / Service**：`core/system/entity/LlmProvider` 继承 `BaseEntity`，`provider_code / provider_name / protocol_type / base_url / default_model / enabled / builtin / sort_order / extra_config`，`extra_config` �?`JacksonTypeHandler` 处理 JSONB；`LlmProviderMapper extends BaseMapper<LlmProvider>`；`LlmProviderService extends ServiceImpl<LlmProviderMapper, LlmProvider>`，`create()` �?`toLowerCase` 归一化后 `validateCode`（正�?`[a-z0-9][a-z0-9-]{1,63}`�? `validateProtocol`（仅两协议之二）+ 去重，`update()` 局�?patch（仅�?null 字段覆盖�? `builtin` 不可�?`provider_code`，`deleteById()` 拒绝 `builtin=1`；`LlmProviderQueryService` 提供 `findByCode / listEnabled / listAll / getBaseUrlWithFallback`，仅读作 hot path 读取入口�?
- **ProtocolFactory �?+ Registry**：`OpenAiCompatibleProtocolFactory`（原 MoonshotProviderChatClientFactory + DashScopeProviderChatClientFactory 抽取后通用化，�?`ProviderChatModelCache.getOrCompute` �?ChatModel，连接超�?5s / 读超�?180s；baseUrl/effectiveModel 三层 fallback DB > provider > PlatformProviderConfigService�? `AnthropicCompatibleProtocolFactory`（`AnthropicApi` �?/v1/messages，原 Minimax 抽出�? `LlmProviderChatClientFactoryRegistry`（按 `provider.protocolType` 分发，深�?`deepseek` 走官�?SDK 优先匹配）。`ProviderChatModelCache.buildKey` �?3 参数扩展�?`(provider, baseUrl, apiKey, protocolType)` 4 参数，保�?OpenAI / Anthropic 协议不串实例；DeepSeek factory 同步刷新�?4 参数版本。删�?`MoonshotProviderChatClientFactory / DashScopeProviderChatClientFactory / MinimaxProviderChatClientFactory / AbstractOpenAiCompatibleProviderChatClientFactory` 4 个文件（默认 yml 保留�?`AgentProviderProperties` 兌底读取）�?
- **业务服务坊接**：`LlmProviderCatalogService` �?`LlmProviderQueryService.listAll` 枚举；`PlatformProviderConfigService` baseUrl/defaultModel 读服务改�?`LlmProviderQueryService.getBaseUrlWithFallback(providerCode, ymlFallback)` 三层 fallback；`AgentChatClientService` 构造器�?`ObjectProvider<List<ProviderChatClientFactory>>` 改为 `LlmProviderChatClientFactoryRegistry`，`generate(...)` 一行改�?`registry.createChatClient(providerCode, apiKey, agent, model)`；`AgentExecutionConnectivityService / ApiKeyAgentExecutor / ChatClient.Builder Bean` 不动�?
- **Controller**：`helloai-api/.../controller/AdminLlmProviderController`（`@RequestMapping("/api/admin/llm-providers")`�? 端点：`GET /list` / `GET /getById/{id}` / `POST /`（`CreateLlmProviderRequest`�?/ `PUT /updateById/{id}`（`UpdateLlmProviderRequest`�?/ `PUT /toggleById/{id}` / `DELETE /deleteById/{id}` / `PUT /{id}/api-key`（vault�?/ `GET /{id}/api-key` mask 脱敏；`LlmProviderResponse` �?`apiKeyConfigured / apiKeyMasked / apiKeyFromVault`�?*�?`AdminProviderConfigController` 保留不动**作为迁移期兼容入口�? �?DTO：`CreateLlmProviderRequest / UpdateLlmProviderRequest / LlmProviderResponse`（全部贴 §10.2 事务边界 + §6.3 不注�?Mapper）�?
- **前端**：`helloai-ui/src/api/settings.ts` �?`LlmProviderResponse / CreateLlmProviderRequest` 接口 + `llmProviderApi.{list / getById / create / update / delete / toggle / saveApiKey}`；`Settings.vue` 重写�?Codex++ 风格（约 375 行）——顶部「基础配置」区（平台名 + 外网访问地址 + 用途文案“生�?SKILL 接入内容�?�? 中部「LLM 供应商」区左侧列表 + 右侧详情面板 + �? 添加供应商」对话框（名�?/ 协议下拉 / Base URL / 默认模型 / 可�?API Key）；内置 Provider 绝不可改，代号不可变，刪除隐藏；自定�?Provider 可启�?/ �?/ 删；API Key 输入�?el-dialog，保存后“实时生效，无需重启”提示�?
- **设计备忘**：`LlmProvider` 实体明确定位为平台级 Provider 配置 (`system.entity`)，不�?chat 域的事；ChatClient 路由分发仍走 chat.provider。`LlmProviderChatClientFactoryRegistry` 仅中介按 protocolType 路由，具体怎么�?ChatModel �?ProtocolFactory 实现�?
- **代理 Provider 创建场景验证**（设计意图）：管理员手工�?`protocol_type=OPENAI_COMPATIBLE / provider_code=gpt-4-mini / base_url=https://api.openai.com/v1 / api_key=...` 添加 �?注册 API_KEY_LLM Agent 、选该 provider 、调外部 OpenAI �?期望 200�?

#### 3. 验证结果

- `mvn clean package -DskipTests` 7 模块�?SUCCESS（HelloAI Common/MQ/Core/Job/API/Start + Root）�?
- `mvn -pl helloai-core test` 416/416 全绿（含本轮新增 / 改�?9 例的 `LlmProviderServiceTest`：`shouldCreateWithNormalizedFields / shouldRejectDuplicateCode / shouldRejectInvalidCode / shouldRejectInvalidProtocol / shouldForbidBuiltinCodeChange / shouldAllowBuiltinUpdateOtherFields / shouldOnlyOverwriteNonNullFields / shouldForbidBuiltinDeletion / shouldAllowCustomDeletion`）。重点修补點�?
  - **`ServiceImpl.baseMapper` 问题**：单测中 `ServiceImpl` 父类 `baseMapper` 字段需要由 Spring 自动注入，`mvn test` �?Spring 未启动；�?`ReflectionTestUtils.setField(service, "baseMapper", mapper)` 手动注入�?*不传 type 参数**，因�?`baseMapper` 擦除类型�?`BaseMapper` 不是 `LlmProviderMapper`，传了会�?`ReflectionTestUtils` �?"field of type [interface ...LlmProviderMapper] not found on target"）。后续测试如需�?ServiceImpl 方法仍遵此范例�?
  - **代码归一化路�?*：`service.create()` 原本�?`validateCode` 放在 `toLowerCase` 之前 �?“Custom-GPT-4�?永远会被判为非法。现改为先归一化后 validate，单元测试验证三点：(1) “null/空白�?�?“provider_code 不能为空”；(2) “MIXED-Case�?归一化为 “mixed-case”；(3) “a�?长度不足 2 / “Bad@Code�?含非法字�?�?两段独立失败。`Production` 路径·Controller 也调 `toLowerCase` 双重防御�?
- 残留检查：`grep "MoonshotProviderChatClientFactory|DashScopeProviderChatClientFactory|MinimaxProviderChatClientFactory|AbstractOpenAiCompatibleProviderChatClientFactory"` —�?只剩 OpenAiCompatibleProtocolFactory / AnthropicCompatibleProtocolFactory 类注释中“取代原 XxxFactory”说�?+ doc/log/HelloAI_迭代执行记录.md 历史足迹。零代码引用�?
- `npx vue-tsc -b` 0 错�?
- **未实测项**（高优，建访重环境上修）：验 `AdminLlmProviderController` 8 端点真实调用、新增自定义 OpenAI 兼容 provider 在真�?LLM 环境下调成功、`AdminProviderConfigController` 旧入口迁移期走通。这三件均依�?API Key / DB 环境，沙箱不能复现�?

#### 4. 影响与遗�?

- **仃能推进**：本�?N9 由“仅 Provider API Key 动态化”升级为“Provider 全零态动态化”；后续 Agent / 执行�?/ 调度反射者只需重发�?`llm_provider` 表，`LlmProviderChatClientFactoryRegistry` 会自动热刷该 provider �?ChatModel。cache key �?protocolType 维度�?OpenAI �?Anthropic 实例不会错位�?
- **老环境兼�?*：V46 幂等 INSERT 4 �?builtin，�?yml 定义 (用过6.51 �?API Key 在空) 与本轮变更零冲突；Codex++ 风格 UI 不變老行为，仅在“系统设置”页多一个「LLM 供应商」区块�?
- **明确不做**：拖拽排序前端、Provider 粒度限流 / 配额、`from external import` 第三方批量导入、事件总线配置变更广播（手�?`ProviderChatModelCache.clear()` 调用已够用）、Provider 配置变更审批流�?
- **遗留**（下一轮处理优先级建议）：�?真实环境 E2E 验证�? 场景如上）；�?�?`/api/admin/platform/providers/...` 调用方补调迁告；�?Provider 变更后分发未�?`ProviderChatModelCache.clear()` 补正（现仅在 API Key 变更处调用，baseUrl / defaultModel 变更�?ChatModel �?key 自动重建）；�?聊天协议多协议扩展点（如未来需 Gemini / Cohere）�?

### 6.53 「保存设置�?00 NPE 修复（与方案B 无关的历�?bug 顺手清）�?026-08-08�?

#### 1. 背景与决�?

- 现象：系统设�?�?“保存设置”点击后 `PUT /api/admin/config/batch` �?500�?
- 根因：`helloai-ui/src/api/settings.ts:79` `batchUpdateConfig` 直接�?`Record<string,string>` flat map 作为请求体发出去；后�?`ConfigBatchRequest` 期待 wrapper 结构 `{config:{...}}`。`req.getConfig()` �?null �?`SysConfigService.batchUpdate` �?`configMap.forEach(...)` �?`NullPointerException`�?
- 业务间：这个问题早在方案B 之前就存在；只是 `Settings.vue` 改造后 Provider 区域加了 API Key 表单，第一次在真实环境点了“保存设置”才被谁发现�?
- 决策：按用户意愿**只改前端**，不动后端。后�?DTO 契约�?NullPointerException 裸露后续可以一起收（拆 demand 到独�?bug 表）�?

#### 2. 实际落地

- �?`helloai-ui/src/api/settings.ts`：`batchUpdateConfig(map)` �?`request.put('/admin/config/batch', { config: map })`，调个调用点�?1 行注释说明“后端期�?wrapper，不能发 flat map”�?
- 不动后端、不动数据库、不动迁移�?

#### 3. 验证结果

- `npx vue-tsc --noEmit -p tsconfig.json` 0 错（项目本地 `.\node_modules\.bin\vue-tsc.cmd`，不�?npx 拉不同版本的 typescript）�?
- `mvn -DskipTests -pl helloai-api,helloai-core,helloai-common -am compile` 0 错（虽未动后端代码，但确认前端改造不影响后端编译）�?
- 真机口验：`保存设置` 走通，`system.name` / `helloai.base-url` 都写�?`sys_config`（用户可�?200 响应 + “保存成功”提示）�?

#### 4. 影响与遗�?

- 影响：解决了本轮 Settings.vue 改造后唯一遗留的真实可�?bug；前�?`/api/admin/config/batch` 调用语义与后�?DTO 对齐�?
- 遗留：后�?`SysConfigService.batchUpdate` 依然裸露 NPE（controller 未做空校验、service 未加 null guard）。下一轮建议顺手加 `if (req == null || req.getConfig() == null) return;` 避免类似改动进一步产�?500。可独立 demand，不需绑回方案B�?

### 6.54 验证链围栏落地（三角�?SKILL 围栏 + 自动核验证据信号）（2026-08-10�?

#### 1. 背景与决�?

- **来源**：用户引入两篇外部方法论——「AI 围栏五层」（L1 输出自检 / L2 事实来源 / L3 执行验证 / L4 独立复核 / L5 评审挑刺）与 `E:\workspace\verify-chain-master` 验证链（Critic 断言提取 �?Verifier 逐条核查 �?Repairer 最小修复，四态结�?✅⚠️❌❓）。分析结论：HelloAI 外部 Agent 架构�?假成�?是结构性风险（平台看不到执行过程，只见 submitResult 文本），值得选择性融入�?
- **决策**（用户拍板，计划《验证链围栏落地》）：分两阶段——①提示词软围栏（三�?SKILL.md，零风险立即生效）；②代码硬围栏检测版（Parser 解析 VERIFICATION + 自动核验 prompt 注入证据信号�?*只检测不拦截**，不�?DB 迁移，存量外�?Agent 零破坏）。明确不做：VERIFICATION 缺失硬拒收、DB 持久�?verification 列（留待观察一轮后再议）、Reviewer 并行 SubAgent（外�?Agent 无子代理机制）�?
- **联网搜索分流**（用户补充需求，融入 Planner 层）：拆解前「关键前提核查」分两类——内部前提（本项目接�?字段/机制）必须读代码/查库核实；外部前提（第三方库/外部服务/框架兼容性）条件允许时联网搜索并注明来源；无法核实的标注【前提未核实】写入子任务 content，禁止把未核实假设当已确认事实�?

#### 2. 实际落地

- **executor SKILL.md**（执行围�?+ fail-close）：EXECUTION_RECORD 协议新增 `VERIFICATION:` 段（命令/输出/结论三行，输出须原样粘贴）；新增 fail-close 硬条款（验证失败或未验证禁止声明完成，须 reportBlocked 或如实标�?未验�?）；§4.5 提交前自检清单追加 2 项；示例块同步更新�?
- **planner SKILL.md**（前提核�?+ 合规自检）：§2.1 拆解前新增「关键前提核查」步骤（3~5 条，�?外部前提分流表，引用门铃推送历史教训）；验收标准字段改为硬要求（禁�?功能正常""质量合格"类模糊表述，附正/反例）；新增「创建合规自检清单�? 项（四要�?可检�?前提已核/无重复拆�?数量与依赖序）�?
- **reviewer SKILL.md**（断言式三段审查法 + 有罪推定）：工作流程�?6 步改为——①提取断言�?~15 条，按类标注，聚�?一错就全错"硬断言）→ ②逐条核查（读文件/跑命�?查日志，四态结论逐条附证据）�?③汇总裁决（❌驳回列证据 / 仅⚠️按严重度评�?/ ❓不替执行者背书）�?④证据复核（交付物携�?VERIFICATION 时复核命�?输出/结论真实性，防伪造证据）�?⑤先记后改；审查原则新增"有罪推定""只认证据"两条�?
- **ExecutionRecord / ExecutionRecordParser**：`ExecutionRecord` 新增 `verification` 字段 + `hasVerification()` + toMap/fromMap 往返（无证据时不写键）；Parser 按协议约定截取块�?`VERIFICATION:` 段原文，缺失�?debug 日志 + 空串�?*解析仍成功不拦截**�?
- **SubTaskReviewService + subtask-review.md**：新�?`extractRawOutput`（截断前原文）与 `verificationSignal`（检�?`VERIFICATION:` 存在性），模板新�?`{{VERIFICATION_SIGNAL}}` 占位符与「验证证据信号」章节；核验要求新增�?6~8 条（有证据核对一致性防伪�?/ 无证据从严评分保�?/ 无法确定不得�?pass=true，fail-close）�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test`�?*17/17 全绿**——新�?`ExecutionRecordParserTest` 5 例（携带 VERIFICATION 完整解析 / 缺失仅检测不拦截 / �?SUMMARY 返回 null 维持 fallback 语义 / toMap-fromMap 往返不丢失 / 无证据不写键�? 回归 `SubTaskReviewServiceTest` 12/12 无破坏。BUILD SUCCESS�?
- 三个 SKILL.md 由外�?Agent �?SKILL 拉取通道动态获取，改文件即对后续上�?Agent 生效，无需重启契约�?
- **待人工实�?*（用户执行）：本地启动项�?�?真实请求走完"创建任务 �?Planner 拆解（看前提核查痕迹）→ Executor 提交�?VERIFICATION �?output �?自动核验"链路，并用只�?SQL 核对 `review_record` / `sub_task` / Task Running Spec 记录�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移、无状态机变更、无契约破坏；硬围栏仅作用于自动核验 prompt 注入，人工审查链路不受影响�?
- 遗留（观察一轮后再议）：�?VERIFICATION 缺失硬拒收；�?`task_execution_record` 表持久化 verification 列（Flyway 迁移）；�?无证据提交占比数据积累后决定是否升级为结构性拦截�?

### 6.55 人工介入兜底：返工达上限/降级能力不匹配时用户自主选择 Agent�?026-08-10�?

#### 1. 背景与决�?

- **真实事故**：子任务「实现订单超时取消校验脚�?verify-order-expire.ps1」因 trae 打卡超时离线�?inner-loop-executor（API_KEY_LLM）领取；inner 无本机执行能力，反复提交"文档化产�?而非可执行脚本，3 次驳回达 `auto-review-max-rework=3` 上限后卡�?REVIEW。日志证�?15:54:06 记录�?`sub_task_auto_review_skip_max_rework`，但当时代码只写 timeline 不写人工介入标记；叠�?`listReviewOrphans` �?已有 review_record"的任务排除（该任务有 3 条历史驳回记录），事件链丢失后孤儿扫描永远扫不到 �?**永久卡死 REVIEW，无任何自动/人工入口**�?
- **决策**（用户拍板）：返工达上限 / 降级能力不匹配时�?`context.manualIntervention` 标记；前�?REVIEW 详情页展示「人工介入」面板——全�?Agent 选择器（外部 CLI_CLIENT �?trae/qoder/claudecode + 内部 API_KEY_LLM 均可选，在线优先�? 「驳回并改派」（REJECTED + reworkAgentId 走正�?review API，触�?outbox 推送）/「直接通过」（人工验收 APPROVED 不受返工上限限制）。明确不做：自动挑�?下一个最�?Agent"（返工达上限必须人工拍板，避免再进循环）�?

#### 2. 实际落地

- **SubTaskService.markManualIntervention**：幂等写 `context.manualIntervention{reason, ts, extra}`（rework_limit / fallback_skip_execution_dense），失败不抛异常�?
- **SubTaskReviewService.reviewSubTask**：`reworkCount >= autoReviewMaxRework` 时记 timeline + 打人工介入标记后 return（不再自动打回）�?
- **SubTaskDispatchService.redispatchForFallback**（�?.52 能力预检）：执行密集任务（内�?验收/交付物含 `.ps1/.sh/.jar`、docker、启动服务等关键词）不回退给无本机能力�?API_KEY_LLM，停留原状�?+ 标记人工介入；`fallback-skip-execution-dense` 默认 true�?
- **SubTaskService.listReviewOrphans（关键修复）**：排除条件从「有 review_record」改为「有 manualIntervention 标记」——返工达上限任务同样持有 review_record，旧逻辑导致事件链丢失时永远无法兜底；新逻辑保证这类卡死任务能被孤儿扫描发现并补写标记�?
- **前端 SubTaskDetail.vue**：`needsManualIntervention`（context 有标�?�?REVIEW �?reworkCount>=3 双兜底）+ 人工介入卡片（reason 标签 + 当前负责�?+ Agent 选择�?+ 驳回改派/直接通过按钮），提交�?`reviewApi.create`（REJECTED �?reworkAgentId / APPROVED）�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test`�?*426/426 全绿**（新�?`SubTaskServiceIsReadyTest` 2 例孤儿扫描回归：�?review_record 无标记的任务保留可兜�?/ 有标记任务排除；含已存在�?`SubTaskReviewServiceTest` 超限打标�?+ `SubTaskDispatchServiceTest` 能力预检用例）。BUILD SUCCESS�?
- `vue-tsc -b --force`：TSC-OK 0 error�?
- **存量卡死任务处置（真实事故闭环）**：子任务 2086720079347281924（REVIEW/reworkCount=3）经 `POST /api/reviews` 人工驳回改派 trae-executor�?086711950328950786）：`REJECTED score=1 + reworkAgentId` �?状�?REWORK、assigned_agent 切换、`agent_outbox_event` 生成 `sub_task.rework`（status=1 已投递）；review_record round=4 �?issues/comment 中文乱码（PS 5.1 �?GBK 解析 no-BOM 源文件所致）已用 UTF-8 字节流直写修正�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移（标记内�?context）；后端需重新打包部署后新逻辑生效；存量卡死任务可被孤儿扫描自动补标（部署�?�?0s），前端 reworkCount>=3 兜底已可先行展示面板�?
- 遗留：① 人工介入面板仅出现在子任务详情页，主任务视图无聚合告警；�?改派后无"未认领提�?（依赖外�?Agent 轮询 outbox）；�?执行密集判定目前为关键词启发式，误判率观察后再议�?

### 6.56 依赖守卫 + 执行密集能力预检全链路下沉：修复"依赖未完成的任务被重派给无能�?Agent 假完�?�?026-08-10�?

#### 1. 背景与决�?

- **真实事故 2（承 §6.52/6.55 同源�?*：子任务 2086720079347281925「冷启完整环境并串行执行三个验证脚本」依�?1924（verify-order-expire.ps1）与 1922/1923，但 1924 �?REVIEW �?1925 被标 DONE。时间线：trae 16:33 提交 1924（第二次 `sub_task_auto_review_skip_max_rework`）→ 16:38:37 trae 心跳离线 �?`agent_offline` 巡检�?1925 重派�?inner-loop-executor（API_KEY_LLM，capabilities �?false 无本机能力）�?inner 19 �?幻觉执行"（编�?docker ps / netstat / 三脚�?PASS=32 的日志与订单号，全部不存在）�?probe-moonshot-reviewer 审核 APPROVED �?1925 DONE，依赖它的下游被解锁�?
- **明确结论**：不�?重新分配�?trae 的任务超过重试最大次数默认完�?—�?924 至今�?REVIEW（重试上限只�?skip_max_rework 打标记，系统无任�?默认完成"逻辑）�?
- **根因三环节叠�?*：① `redispatchOfflineSubTask`（agent_offline 重分配）�?`isReady` 依赖守卫（`dispatchPendingSubTaskAuto` 有守卫、离线路径没有）；② §6.52 能力预检只挂�?`redispatchForFallback`，`ResilientDispatcher.assignNext`/fallback 选人环节不查 capabilities；③ 审核侧无"提交者能�?校验，核�?LLM 无法辨别无能�?Agent 的幻觉证据�?
- **决策**（用户拍板修复三处缺陷）：离线重分配补依赖守卫；能力预检下沉�?ResilientDispatcher 分配主路�?+ fallback 替代选人；审核侧�?执行密集 + 无能力提交�?跳过自动核验打人工介入标记；两条 PENDING 兜底巡检跳过带人工介入标记的任务�?

#### 2. 实际落地

- **SubTaskDispatchService**：`isExecutionDense` / `hasLocalExecutionCapability` / `isManualInterventionMarked` �?private �?**public static**（供 ResilientDispatcher / SubTaskReviewService / job 兜底任务复用，避免各入口各自实现判定发散）；`redispatchOfflineSubTask` �?reset 后补 `isReady` 依赖守卫——未就绪保持 PENDING，记 `sub_task_dispatch_skip_dependency`（trigger=agent_offline），等依�?DONE 后由 SubTaskPendingOrphanTask / 自动分发链再次触发�?
- **ResilientDispatcher**（构造器新增 AgentDispatchProperties + TaskTimelineService）：`assignNext` 主路径在心跳 fast-fail 后加 `isExecutionDenseMismatch` 预检——执行密集任务命中无本机能力 Agent（API_KEY_LLM �?capabilities.supportsMCP != true）时�?`sub_task_dispatch_skip_no_capability` + `markManualIntervention("dispatch_skip_execution_dense")` + �?AgentUnavailableException �?fallback；`assignNextFallback` 对替�?Agent 同样预检，不匹配则放弃分配（任务停留 PENDING 人工处置，不再抛异常冒泡）。开关沿�?`fallback-skip-execution-dense`（默�?true）�?
- **SubTaskReviewService.reviewSubTask**：`skip_max_rework` 分支之后、�?reviewer 之前加提交者预检——执行密集任�?+ 提交者（executorAgentId 回退 assignedAgentId）无本机能力时跳过自动核验，�?`sub_task_review_skip_no_capability` + `markManualIntervention("review_skip_execution_dense_no_capability")`�?
- **SubTaskPendingOrphanTask / ExternalAgentFallbackTask.recoverPendingUnassigned**：两�?PENDING 兜底循环均跳�?`isManualInterventionMarked` 的任务（�?无能�?返工超限"人工场景被兜底链反复打回调度链）�?

#### 3. 验证结果

- `mvn -pl helloai-core,helloai-job -am test -DskipTests=false`�?*BUILD SUCCESS**。core 全量 + job 全量通过；新增回归用�?10 个：ResilientDispatcherTest +4（主路径拒绝/�?MCP 放行/fallback 替代拒绝/替代放行）、SubTaskDispatchServiceTest +1（离线重派依赖未就绪不重派）、SubTaskReviewServiceTest +2（无能力提交者跳过核�?有能力正常核验）、SubTaskPendingOrphanTaskTest +2（有标记跳过/无标记正常）、ExternalAgentFallbackTaskTest +1（有标记跳过）。`ResilientDispatcherAopIntegrationTest` �?AgentDispatchProperties/TaskTimelineService 两个 @MockBean �?3/3 恢复�?
- **测试坑位**：根 pom 默认 `<skipTests>true</skipTests>`，跑测试必须显式 `-DskipTests=false`；PowerShell �?`-Dtest=A,B` �?`-Dsurefire.failIfNoSpecifiedTests=false` 需整体加引号�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移；ResilientDispatcher 构造器新增 2 依赖（Spring 自动注入无配置变更）；行为变化——执行密集任务不会再被分给无本机能力 Agent（含 fallback 替代），审核侧不再自动核验无能力提交者的执行密集产出，两条兜底巡检不再重派带人工标记的 PENDING�?
- 遗留：① 存量卡死任务 1924/1926（REVIEW）需部署新代码后由孤儿扫描补标（�?0s），前端人工介入面板处置；② inner 幻觉执行的审核辨别仍依赖证据信号从严条款（�?.54），本修复从"源头不派"层面消除无能力执行；�?`SubTaskDispatchService.isExecutionDense` 关键词启发式误判率观察后再议（承 §6.55 遗留③）�?

### 6.57 人工驳回重置返工计数：修�?改派后新执行者提交仍命中 skip_max_rework 跳过审核、无节点流转"�?026-08-11�?

#### 1. 背景与决�?

- **真实事故 3（承 §6.52/6.55/6.56 同源 1924/1926�?*：用户反�?内部 LLM 接任务完成反馈不佳、驳�?3 次直接跳过验证，人工介入重新分配其他外部/内部 agent 后失败次数未重新计算，review 角色审核时出现跳过审核、无节点流转"�?*数据库实证（helloai 库）**�?924「verify-order-expire.ps1�?7:51 inner-loop-executor（API_KEY_LLM 无本机能力）执行 �?07:52-07:54 自动驳回 3 轮（review_record round 1-3）→ 07:54:06 `sub_task_auto_review_skip_max_rework` �?REVIEW �?08:15 人工改派 trae-executor（round 4 REJECTED）→ 08:31-08:33 trae **真实执行完成**（context.lastExecution：脚本落地并实际运行 PASS=12 FAIL=0 全绿）→ 08:33:22 提交�?*再次** `sub_task_auto_review_skip_max_rework`（reworkCount 仍是 3）→ 此后无任何事件，**合格产出无人审核、永久卡 REVIEW**�?926「生成验证报告」同构卡死（08:41:07 skip 后无人工处置记录）�?
- **根因**：自动驳回走 `SubTaskService.rework()` 累加 reworkCount，而人工驳回（`ReviewService.createReview` REJECTED）只�?`changeStatus` 不重置计数——改派后 reworkCount 残留 3，新执行者提交即命中 `reworkCount >= autoReviewMaxRework` 跳过自动核验；且 `manualIntervention` 标记在人工拍板后不清除，前端面板残留、PENDING 兜底巡检持续跳过�?
- **决策**（用户拍�?所有人工驳回都重置"）：人工驳回 = 用户拍板开启新一轮，无论是否�?agent 都重置计数并清除标记；自动驳回仍累加�? 次后停），两条路径语义分工�?

#### 2. 实际落地

- **SubTaskService.reworkFresh**（新增，�?`rework` 并列）：REVIEW→REWORK 状态校�?+ `reworkCount=0` + `assignedAgentId` 换派（可空则保持原执行者）+ 清除 `context.manualIntervention` + outbox 事件 + timeline `sub_task_manual_rework_reset`�?
- **ReviewService.createReview**：REJECTED 分支�?`changeStatus` 改走 `reworkFresh`——人工驳回统一重置计数并清除介入标记，改派后的新执行者从 0 开始计数，提交后走正常自动核验链路�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=ReviewServiceTest,SubTaskReviewServiceTest`�?*全部通过**。新�?`ReviewServiceTest` 4 用例（人工驳回改派走 reworkFresh / 不改派同样重�?/ 人工通过�?complete 不触发重�?/ 驳回�?issues �?BizException）；`SubTaskReviewServiceTest` 14 用例无回归�?
- **数据库旁�?*�?924 改派�?trae 提交（execute_submit 08:33:22）→ 08:33:22.773 skip_max_rework，两�?skip 间隔内无任何 review_record 写入——实�?跳过审核 + 无节点流�?�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移；行为变化——人工驳回后 reworkCount 归零（新执行者有完整 3 次机会）、manualIntervention 清除（前端面板自动隐藏、PENDING 兜底巡检恢复对该任务可见）；自动驳回路径不变�?
- 遗留：① 存量卡死任务 1924/1926 部署新代码后�?924 �?trae 产出实际合格（PASS=12 FAIL=0），前端面板"直接通过"即可闭环；或"驳回改派"后新执行者正常走审核�?926 需人工处置；② trae-executor `consecutive_failure_count=2` 疑似�?系统跳过审核"计为外部 agent 失败，观�?ExternalAgentFailureTracker 是否�?skip 类事件计入失败（待确认，不在本次范围）�?

### 6.58 AgentHealthCheckTask 语义修正：无在跑子任务不�?N11 失败 + executor SKILL 心跳强化�?026-08-11�?

#### 1. 背景与决�?

- **真实形�?*：trae-executor 等外�?Agent"提交产出后静默待�?（不再发心跳但也不下线）——按旧逻辑 `handleAgentOffline` 无条�?`failureTracker.recordFailure(agent.getId())`，每完成一个任务就累计 1 次失败，叠加�?N11 阈值后触发误回退（干活的 Agent 被错误替换）�?
- **决策**：离线时仅当存在在跑任务（ASSIGNED/IN_PROGRESS）才视为执行失败——心跳丢失导致任务中断，失败语义成立；无在跑任务说明客户端只�?提交后停止心�?的静默待命，不计失败�?

#### 2. 实际落地

- **AgentHealthCheckTask**：`reassignStaleTasks` �?void 改为返回在跑任务数（staleTasks.size()，空�?0）；`handleAgentOffline` �?`int inFlightCount = reassignStaleTasks(agent)`，仅 `inFlightCount > 0` �?`failureTracker.recordFailure`，其余路径（agent �?null / 无待重分配任务）一律返�?0 不计失败�?
- **executor SKILL.md**：�?.3 心跳节拍前新�?提交不等于下�?警示块——`submitResult` / `ack` 后必须回到步�?3 继续心跳轮询等待下一单；提交后静默退出会�?5 分钟内被�?OFFLINE（即使产出合格）且后续任务被重派；只有确认下线才走「下线清理剧本」�?

#### 3. 验证结果

- `AgentHealthCheckTaskTest` 12 用例全绿（含"无在跑任务离线不计失�?新增断言）�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移；N11 失败计数只统�?离线时确有在跑任�?的场景，静默待命 Agent 不再被误伤�?
- 遗留：无�?

### 6.59 任务�?agentPolicy + 能力声明落地（V47）：Planner/Executor/Reviewer 指定语义 + N11 回退策略约束 + 技能匹配（2026-08-11�?

#### 1. 背景与决�?

- **对齐目标�?*：架构参�?§4.8 目标态八「Agent 能力满足当前子任务要求」——任务可显式指定执行/拆解/核验角色，且选人链按任务要求过滤 Agent 能力，防�?无能�?Agent 被自动选中 �?返工循环"�?
- **决策**（用户拍板完整方�?P1）：任务�?`agent_policy` JSONB（plannerAgentId / executorAgentIds[] / reviewerAgentId / fallbackPolicy AUTO·RESTRICTED·NONE / difficulty LOW·MEDIUM·HIGH�? 任务 `required_skills`（AND 语义�? Agent `skills`，选人链贯穿约束；N11 回退按策略约束（NONE / HIGH 禁止自动回退改人工介入；RESTRICTED 仅回退白名单内 API_KEY_LLM）�?

#### 2. 实际落地

- **Flyway V47**：`task.agent_policy`（JSONB 默认 `{}`）、`task.required_skills`（JSONB 默认 `[]`）、`agent.skills`（JSONB 默认 `[]`）三�?+ COMMENT + DO 验证块；旧数据行为与默认值完全一致（防御式回落默认）�?
- **TaskAgentPolicy**（core/task/service 静态工具类）：全部 policy 读取/判定收口——plannerAgentId / executorAgentIds（List 防御转换�? reviewerAgentId / fallbackPolicy（非法回�?AUTO�? difficulty（非法回�?MEDIUM�? isFallbackForbidden（NONE �?HIGH�? build（null 与空键不写入，测试与写库入口复用）�?
- **AgentSelector 约束�?*：新增嵌套类 `AgentSelectionConstraints`（allowedAgentIds �?不限�?+ requiredSkills 非空=全匹�?AND，agent null 直接拒绝）；`pickPreferred` / `pickAlternative` 增加 3 参重载，`pickFromCandidates` �?exclude 过滤后追加约束过滤环（集合限�?+ 技能匹配）�?
- **ResilientDispatcher 3 参重�?*：`assignNext(agentId, subTaskId, constraints)` + 独立 fallbackMethod（规�?Spring AOP 同类内部委托失效）；`doAssignNext` 内首选不满足约束 fast-fail �?AgentUnavailableException �?走受约束 fallback（`pickAlternative(agentId, role, constraints)`），保证 fallback 不越出白名单/技能范围�?
- **Planner 指定语义**（PlannerAgentPicker.pickForTask）：`task.agent_policy.plannerAgentId` 优先于会话钉住；失效（删�?禁用）回退自动选择（由 pick 内置），不阻断拆解�?
- **Executor 五入口接约束**（SubTaskDispatchService）：dispatchBlockedSubTask / redispatchOfflineSubTask / dispatchPendingSubTaskAuto / redispatchAssignedTimeout 均解析任�?policy �?`AgentSelectionConstraints` 传入选人与派发；`resolveConstraints` / `loadAgentPolicy` / `loadTask` 辅助方法防御式读取（task 缺失按无约束处理）�?
- **N11 回退策略约束**（redispatchForFallback）：`isFallbackForbidden`（fallbackPolicy=NONE �?difficulty=HIGH）→ 跳过回退 + timeline `sub_task_fallback_skip_policy` + `markManualIntervention("fallback_skip_policy")`，不�?LLM；RESTRICTED �?仅回退 executorAgentIds �?API_KEY_LLM，目标不在集合（或集合空）等�?NONE 打人工介入标记�?
- **Reviewer 指定语义**（SubTaskReviewService.pickReviewerAgent）：`task.agent_policy.reviewerAgentId` 优先——指�?Agent 可用（存在且 ACTIVE �?API_KEY_LLM）直接采用；失效 log.warn 后回退原自动链（pickPreferred REVIEWER �?同角�?API_KEY_LLM �?PLANNER 角色 API_KEY_LLM）�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=TaskAgentPolicyTest,AgentSelectorTest,PlannerAgentPickerTest,SubTaskDispatchServiceTest,SubTaskReviewServiceTest`�?*Tests run: 90, Failures: 0, Errors: 0**（TaskAgentPolicyTest 5 / AgentSelectorTest 37 �?TaskLevelConstraints 7 用例 / PlannerAgentPickerTest 13 / SubTaskDispatchServiceTest 19 �?V47 四用�?/ SubTaskReviewServiceTest 16 含指定优先与失效回退两用例）�?

#### 4. 影响与遗�?

- 影响：V47 迁移三列（纯增量，默认值兼容旧数据）；行为变化——任务创建入口可�?policy / required_skills（当前由任务创建侧写库，平台侧提�?build 工具类）；N11 回退受任务策略约束�?
- 遗留：① 任务创建/编辑前端暂未暴露 policy 编辑 UI（API 层与工具类已就绪，留待前端迭代）；② `agent.skills` 暂由注册�?管理员维护，未做 Agent 能力自动推导；③ required_skills 技能匹配为精确字符串全匹配（AND），未做同义�?层级归一�?

### 6.60 改派/抢占撤销通知（A0-1：trae 实战反馈一.1「任务改派后�?agent 无撤销事件」）�?026-08-11�?

#### 1. 背景与结�?

- **实战痛点（trae 1925�?*：任务改�?抢占后旧 agent 只收到「分配」通知，无「改�?撤销」事件，误以为任务仍在名下继续干活（冷启动白做）�?
- **入口梳理结论**：全部改派入口（dispatchBlockedSubTask / redispatchOfflineSubTask / redispatchForFallback / redispatchAssignedTimeout / redispatchDeadLetter）共�?`resetToPendingForDispatch`（直�?updateById 清空 assignedAgentId，无任何通知）；重新 ASSIGNED 只通知�?agent——旧 agent 完全感知不到任务转移。人工改�?人工驳回换派�?changeStatus / reworkFresh，同样无撤销事件�?
- **收口设计**：不�?4 个改派入口各自补发（易漏），而在 `SubTaskService` 咽喉�?`changeStatus` + `rework` + `reworkFresh` 内做换人检测（oldAgentId != null && != newAgentId），一处覆盖全部路径（含人工改派、reworkFresh 换派、dead-letter 重派）；dead-letter 路径（changeStatus(DEAD_LETTER, null) 保留原执行�?�?old==new 不触发，redispatchDeadLetter 换人时触�?reassigned）�?

#### 2. 实现要点

- **SubTaskService.notifyAgentHandover**（新增私有方法）：换人（newAgentId != null）→ 旧执行者收 `sub_task.reassigned`（「任务已改派，请立即停止执行」）；回收（newAgentId == null）→ `sub_task.unassigned`（「任务已回收」）；初始分配（old == null）与原地保留（old == new）不通知；eventId `subtask.{id}.handover.{ts}` 保证幂等；复�?AgentInboxService.send（API_KEY_LLM 旧执行者由内部守卫跳过，消费链�?outbox→MQ）�?
- **changeStatus**：变更前快照 oldAgentId，updateById 后调�?notifyAgentHandover�?*rework / reworkFresh**：同样快�?+ 通知（reworkFresh 换派场景旧执行者收 reassigned，不换派不发）�?
- **McpToolService.pullTasks**：sub_task 消息若子任务当前执行�?�?�?agent（含已回�?null）→ 消息�?`reassigned=true` + `currentAgentId`（回收时�?true，currentAgentId �?null）�?
- **executor SKILL.md**：新�?§1.5.1.bis 收件箱消息类型与撤销语义表（reassigned / unassigned 收到即停止执行）�?
- **顺带修复真实 bug**：reworkFresh 人工驳回不换派（reworkAgentId=null）时 `Map.of("reworkAgentId", null)` �?NPE——改�?HashMap（此前不换派驳回�?500）�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=SubTaskServiceHandoverTest,McpToolServiceTest,SubTaskServiceIsReadyTest`�?*全部通过**（SubTaskServiceHandoverTest 7 用例：改派双通知 unassigned+assigned / 回收 unassigned / 初始分配不发 / 原地保留不发 / reworkFresh 换派 / reworkFresh 不换�?/ rework 换人；McpToolServiceTest 3 用例：已转移打标 / 未转移不打标 / 回收打标 currentAgentId 空；SubTaskServiceIsReadyTest 8 回归）�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移；行为变化——改�?回收后旧执行者一个轮询周期内（pullTasks 30s）可感知任务已转移；SKILL 同步消息类型语义�?
- 遗留：无（验收达成：改派后旧 agent 一个轮询周期内可感知任务已转移）�?

### 6.61 MCP 接入体验：Session 生命周期核验 + REST 别名同步通道 + 404 修复提示（A0-2：trae 实战反馈�?1/2/4「Session 复用 / 同步响应 / Schema 与错误信息」）�?026-08-11�?

#### 1. 背景与结�?

- **实战痛点（trae 两轮实战�?*：① MCP session 几十分钟就失效（Session not found），每次调用重新 4 步握手；�?`tools/call` 响应只经 SSE 推流、POST 静默，提交成功与否只能查库；�?`tools/list` 无参�?Schema、错误无修复提示�?
- **SDK 生命周期核验（反编译 io.modelcontextprotocol 0.18.3 + WebMvcSseServerTransportProvider�?*�?
  - session �?`POST /mcp/messages?sessionId=` 入参解析�?*严格绑定 SSE 长连�?*（WebMvcMcpSessionTransport 持有 sseBuilder，onComplete/onTimeout 后从 sessions map 移除）；断开即回收是协议行为，无「保留窗口」可配置�?
  - `handleMessage` 为同步执行（`McpServerSession.handle().block()`）：session==null �?同步返回 **HTTP 404 + body "Session not found: xxx"**（Jackson 序列化的 McpError 对象）；sessionId 缺失 �?400；JSON 解析失败 �?400�?
  - **关键发现（exchangeSink 串行化）**：`handleIncomingRequest` �?initialize 请求必须等待 `exchangeSink.asMono()`（Sinks.One）信号；该信号由 `notifications/initialized` 通知触发（`handleIncomingNotification` 完成 exchangeSink）�?*未发 initialized 通知�?tools/call �?永久挂死（HTTP 不返回，实测 20s+ 超时�?*——协�?4 步握手缺一不可�?
  - **断开回收有延迟窗�?*：实测断连后 +2s �?session 仍可调用�?00），回收并非即时；且断连后第二个请求偶发挂死（exchangeSink 单次发射语义疑点，未完全定性，�?SDK 内部行为）�?
- **复用决策**：SDK 不可配置保留窗口 �?**不做 transport 层改�?*（改造成本高、协议兼容风险大），�?*无状�?REST 别名通道 `POST /api/mcp/jsonrpc` 承担免握手复�?*；SESSION_AUTH�?20min TTL）与 SDK session 生命周期脱节 �?**404 时联�?evict**，避免鉴权缓存残留�?

#### 2. 实现要点

- **McpAuthFilter 增强（核心）**�?
  - `BufferedResponseWrapper`：缓�?SDK RouterFunction 直写 body（setContentLength/flushBuffer �?no-op 防提�?commit），doFilter 返回�?`flushToUnderlying()` 写回底层 response——真�?Tomcat 验证无损�?
  - `afterMessageHandled`：SDK 返回 404 时① `McpAuthContext.evict(sessionId)` 联动清理 SESSION_AUTH；② body �?"Session not found" �?JSON 解析�?`fixHint`（「重�?GET /mcp/sse 握手拿新 sessionId；或改用无状�?REST 别名 POST /api/mcp/jsonrpc」）�?
- **REST 别名同步通道**：`McpController.postJsonrpc`（@Deprecated 但承�?A0-2）——无状态（agentId 取自 request attribute _authId，无需 MCP session）、同步返回完�?`{"jsonrpc":"2.0","result":{...},"id":...}`（tools/call 直接返回工具结果而非�?body）；10 工具矩阵�?MCP 通道完全对齐（checkIn/checkOut/getAgentStatus 全部可用）�?
- **McpToolService.getAgentStatus 业务下沉**：REST 别名�?MCP 通道共用（McpMcpServer 改为委托）�?
- **tools/list Schema 确认**：spring-ai �?`@ToolParam` 声明自动生成 JSON Schema（properties + type），无需补——verify 脚本逐工具断言 inputSchema 非空通过�?
- **executor SKILL.md §1.4**：四步握手避坑强化（�?initialized 直接 tools/call 会挂死）+ Session 失效双修复路径（重握�?/ REST 别名兜底�? REST 别名通道调用示例�?

#### 3. 验证结果

- 单测：`McpAuthFilterTest` 6 用例�?04 evict + fixHint 改写 + �?404 不改�?+ 401 路径等）、`McpControllerJsonrpcTest` 8 用例（tools/list 10 工具 + Schema / submitResult 同步回执 / checkIn 租约 / heartbeat / -32601 / -32000 等）**全绿**�?
- 真实环境：`POST /mcp/messages?sessionId=no-such-xxx` �?**HTTP 404 + body �?fixHint + 日志 SESSION_AUTH 联动清理**（wrapper �?Tomcat 下无损）�?
- **verify-mcp-session-e2e.ps1（PASS=17 FAIL=0�?*：S1 SSE 握手 �?S2a initialize �?S2b notifications/initialized �?S2 tools/call heartbeat（POST 200 + SSE 推流 isError:false）→ S3/S4 未知 sessionId 404 + fixHint（含 /mcp/sse �?/api/mcp/jsonrpc 指引）→ S5 REST tools/list 10 工具 + inputSchema �?S6 REST heartbeat 同步 result �?S7 checkIn/checkOut 同步租约回执（leaseId + expiresAt）；断连后复用旧 session 为观察项（SDK 保留窗口�?200，不做硬断言）�?

#### 4. 影响与遗�?

- 影响：MCP SSE 通道行为不变（协议标准）；REST 别名通道新增免握手同步能力；404 响应体附 fixHint �?SESSION_AUTH 联动清理（无 DB 迁移）�?
- 遗留：① SDK 断连回收延迟窗口（保留窗口内�?session 仍可调用）与断连后第二请求挂死为 SDK 内部行为，未处理（外�?agent �?404 即切 REST 别名，无需感知）；�?若未来仍需「同一 session 跨连接复用」，需自定�?McpServerTransport 或升�?spring-ai 版本，留作专门迭代；�?a02 验收达成：外�?agent 一�?REST 调用即可免握手复用，提交有同步回执（accepted/resultId/status），错误信息可操作（fixHint 指引）�?

### 6.62 工具面统一 + SKILL 逐动作速查�?+ 405 语义修复（A0-3：三通道 10 工具对齐 / SKILL 双通道分工�?/ verify-tool-matrix 防漂�?/ MethodNotSupported�?05）（2026-08-11~12�?

#### 1. 背景与结�?

- **盘点结论（A0-3-1�?*：MCP SSE 通道 10 工具 ✓、REST 别名 `POST /api/mcp/jsonrpc` 10 工具 ✓（A0-2 补齐）�?*REST 直�?`/api/mcp/tools/*` 声明 10 个但只有 7 个实�?*（getAgentStatus / checkIn / checkOut 缺失，调 `/api/mcp/tools/checkIn` �?404）——`GET /api/mcp/tools` 列表与真实可调路由不一致，外部 agent 按声明调用会踩空�?
- **SKILL.md 漂移盘点**�? 处错误路径（下线剧本 `/api/agents/<id>`、注意事�?`/api/rules/merged`、错误码�?404/Unknown tool 行过时）；全文无「动�?�?方法 + 路径 + 请求�?+ 返回结构」的机器可解析速查表，agent 需在多章节间拼凑调用姿势�?
- **统一策略决策**：不�?MCP 工具（startById 等维�?REST 业务端点语义，避�?MCP 工具面膨胀）→ �?�?REST 直�?3 个缺失端点（三通道 10 工具完全对齐，listTools 不再撒谎）；�?SKILL 新增「〇、工具与动作速查总表」（0.1 三通道执行工具�?+ 0.2 REST 业务端点表，机器可解析）；③ `verify-tool-matrix.ps1` 校验脚本做声�?vs 文档 diff（防漂移）�?

#### 2. 实现要点

- **McpController**：新�?`TOOL_NAMES` 常量�?0 工具唯一事实源，防声明与实现漂移�? 3 个直通端�?`POST /api/mcp/tools/getAgentStatus`（无 body�? `checkIn`（可�?workMode / maxConcurrent / ttlMinutes�? `checkOut`（closeReason 兼容 reason 回退），全部委托 McpToolService�?
- **executor SKILL.md**：新增�?# 〇、工具与动作速查总表（A0-3 新增，机器可解析）」—�?.1 三通道执行工具表（列：工具 | MCP SSE | REST 别名 jsonrpc | REST 直�?| 请求�?JSON | 返回要点�? 0.2 REST 业务端点�?13 条（动作 | 方法+路径 | 参数 | 返回要点）；L31 三通道表述修正；第三节 REST 参考逐动�?curl 重写（含「startById 必须 POST，GET �?405」「submitById �?body 不带产出，产出走 submitResult」等避坑）；下线剧本 2 �?`/api/agents/<id>` �?`/api/agents/getById/<id>`；注意事�?`/api/rules/merged` �?`/api/rules/getMergedRules`；错误码表更新（404 Session not found + fixHint / 404 旧路�?/ 405 startById / 500 Unknown tool 三通道 10 工具）�?
- **GlobalExceptionHandler（顺带修复真实缺陷）**：补 `HttpRequestMethodNotSupportedException` �?**HTTP 405**（此前被 Exception 兜底�?500——GET �?POST-only 路由返回 500「服务内部错误」，�?SKILL 错误码表�?05 startById」表述不一致；修复�?NoResourceFoundException�?04 同模式）�?
- **verify-tool-matrix.ps1（A0-3-3�?*：S1 REST 别名 tools/list 10 工具 + 逐工�?inputSchema(type=object)；S2 `GET /api/mcp/tools` �?S1 集合 Compare-Object diff 空；S3 直�?getAgentStatus 探活；S4 SKILL 0.1 表工具名（区域限�?`### 0.1`~`### 0.2` 排除 1.2 �?+ 错误码表）与服务�?tools/list diff 空；S5 SKILL 0.2 �?13 条路径探活（GET 期望 200；POST-only 路由 GET 探期�?405 = 路由存在）；S6 SKILL 旧路径检查（豁免错误码表教学区——旧路径作为「错误示例」刻意保留）；S7 直�?checkIn→checkOut 真实调用（同步租约回执）�?
  - 正则落地避坑（PS 5.1）：`(?m)` 行首锚点（Get-Content -Raw 多行�?`^` 默认不匹配行首）；正则与断言字符�?*禁用�?ASCII**（✓ 等字符经 GBK 解析 .ps1 会破坏正则字面量）；区域锚点用纯 ASCII（`### 0.1` / `\n## `——注�?`## ` 会匹�?`### 0.2` 标题自身字符 2-4，必须带换行前缀）�?
- **测试修复（V47 遗留，与 A0-3 无关但顺带闭环）**：全量测试发�?5 个失败（ResilientDispatcherTest 4 + ResilientDispatcherAopIntegrationTest 1）——V47 �?`AgentSelector.pickAlternative` 增加 3 参（constraints 贯穿 fallback）且主代码已�?3 参调用，�?6 处测�?stub/verify �?mock 2 �?�?Mockito stub 失效返回 null �?抛「无可用替代 Agent」。已全部补第 3 �?`any()`�?

#### 3. 验证结果

- 单测：`McpControllerJsonrpcTest` 12 用例全绿�? 原有 JSON-RPC + 4 新增直通：listTools 10 工具断言 / getAgentStatus 委托 / checkIn 租约 workMode+leaseId / checkOut closeReason 回退 + closedCount）；全量 `mvn -pl helloai-api -am test -DskipTests=false`�?*Tests run: 486�?74 core + 12 api），Failures: 0, Errors: 0**�?
- 真实环境�?*verify-tool-matrix.ps1 PASS=23 FAIL=0 ALL PASSED**（S1~S7 全绿：三通道 10 工具同名集合、SKILL 0.1 �?diff 空、SKILL 0.2 �?13 条路由全部存在、旧路径 0 残留、checkIn/checkOut 同步回执）�?
- 405 修复实证：GET `/api/sub-tasks/startById/1` 由修复前 HTTP 500「服务内部错误」→ 修复�?HTTP 405（body code=405「请求方法不支持」），与 SKILL 错误码表表述一致�?

#### 4. 影响与遗�?

- 影响：REST 直通补�?3 端点（三通道 10 工具完全对齐）；405 语义修复影响所有「路径存在但方法不支持」请求（此前 500，属正确性修复）；SKILL §0.1/§0.2 速查表成为外�?agent 的唯一动作依据（验收：只读 SKILL 即可零试错调用）�?
- 遗留：无（a03 验收达成：外�?agent 只读 SKILL §0.1/§0.2 即可正确调用，零试错；校验脚本已入库可重复执行防漂移）�?

### 6.63 外部 Agent 信息获取能力补齐：getDepsSummary 主动拉依赖摘�?+ review 反馈通知 + 未读/已读状态位（A0-4）（2026-08-12�?

#### 1. 背景与结�?

- **盘点结论（A0-4-1�?*：① 外部 agent 无法主动拉前置产出摘要——依赖摘要只在执行链 `buildDependencySection` 内部消费，agent 侧无工具可取；② 评分反馈缺口——`rework()`/`complete()` 不产生收件箱通知，驳�?通过只能另查 review 接口；③ `pullTasks` 不区分未�?已读，ack 语义对轮�?agent 不透明，轮询逻辑需自行过滤�?
- **落地决策**：① 新增 MCP 工具 `getDepsSummary`（复�?buildDependencySection 摘要逻辑，数据口径与执行链同源）；② `rework()`/`reworkFresh()` 统一补发 `sub_task.rejected`、`complete()` 补发 `sub_task.approved`（summary 携带最近一�?review 评分/评语）；�?`pullTasks` 消息�?`read` 状态位 + `includeRead` 参数（未读优先，已读�?read_time 倒序补齐配额）�?
- **过程中发现新缺口**：McpController JSON-RPC 别名通道 `tools/list` �?*独立硬编码声�?*�?0 个），与 `TOOL_NAMES` 漂移——A0-4 同步补齐 `getDepsSummary` 声明 + `pullTasks.includeRead` 参数 + dispatch 分支，三通道（MCP SSE / REST 别名 / REST 直通）真正 11 工具对齐�?

#### 2. 实现要点

- **McpToolService.getDepsSummary(agentId, subTaskId)**：`dependsOnIdList �?listByIds �?taskRunningSpecService.findRecord(taskId, depId).summary() �?loadUpstreamContent`（物化附�?local:// 优先，回退 `SubTaskOutputExtractor.extractExecutionOutput`）；`DEP_CONTENT_MAX_CHARS=4000` 截断 + `truncated` 标记；收集失败降�?`degraded=true` 不阻断返回�?
- **pullTasks 4 参重�?+ includeRead**：未读优先，已读�?read_time 倒序补齐配额；Message �?`read` 状态位（false=未读�?ack，true=�?ack）；`AgentInboxService.getRecentRead`（is_read=1 & is_archived=0，orderByDesc read_time，LIMIT min(limit,500)）�?
- **SubTaskService 通知补发**：`rework()`/`reworkFresh()` 补发 `sub_task.rejected`（`buildReworkSummary` �?`context.reviewHistory` 最新轮提取 score/comment/issues，无历史回退「请查审查记录了解具体问题」）；`complete()` 补发 `sub_task.approved`（`buildApprovedSummary` �?review_record �?round desc LIMIT 1 �?score/comment）�?
- **三通道同步**：`McpMcpServer` @Tool `getDepsSummary` + `pullTasks.includeRead`（MCP SSE）；`McpController` `TOOL_NAMES` 11 + JSON-RPC tools/list 11 + dispatch `getDepsSummary` case / pullTasks 4 参（REST 别名）；REST 直�?`/api/mcp/tools/getDepsSummary` + pullTasks includeRead（直通上一轮已补，本轮保持对齐）；`AgentMcpServerService.DEFAULT_EXECUTOR_TOOLS` 11（新工具默认启用，isToolEnabled 自动建行）�?
- **executor SKILL.md**�?.1 �?11 工具 + `getDepsSummary` 行（请求/返回结构�? pullTasks 行补 `includeRead`/`read`/`summary` 要点 + §1.2 后新增「�?ack 语义（A0-4 澄清）」块；`verify-tool-matrix.ps1` S1/S2 断言 10�?1�?

#### 3. 验证结果

- 单测：全�?`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 503（core 487 + api 16），Failures: 0, Errors: 0**（McpToolServiceTest 6 新用例：默认未读 / includeRead 合并 / 无依�?/ 摘要+内容加载 / 4000 截断 / 降级与回退；SubTaskServiceHandoverTest 4 新用例：rejected 补发 / 回退文案 / reviewHistory 摘要提取 / approved 补发；AgentInboxServiceTest 2 用例：倒序返回 / limit 500；McpControllerJsonrpcTest 4 新用例：includeRead 透传与缺�?/ getDepsSummary 委托与缺�?+ 工具数断言 10�?1）�?
- 真实环境 `verify-tool-matrix.ps1` **PASS=23 FAIL=0 ALL PASSED**（S1/S2 11 工具同名集合、S4 SKILL 0.1 �?diff 空，�?getDepsSummary）�?
- **getDepsSummary 直�?+ JSON-RPC 别名**：子任务 2087076796930322438�? 依赖）返�?`depCount=3 loadedCount=3 truncatedCount=0 degraded=false`，每依赖�?title/status/summary/content（完整执行摘�?+ 内容本体）；�?subTaskId �?R.fail「subTaskId 不能为空」�?
- **pullTasks 未读/已读**：默认只回未读（read=false）；ack 一条后 `includeRead=true` 返回未读 4 + 已读 1（read=true）——未读优先、已读倒序补齐，REST 直通与 JSON-RPC 别名结果一致�?
- **完整 review 闭环（真实链路）**：新建测试任务→子任务（assigned A03-test-executor）→start→submit→人工驳�?REJECTED（score=2）→ pullTasks 拉到 `sub_task.rejected`（read=false，summary 回退文案「请查审查记录了解具体问题」——人工驳回无 reviewHistory，符合预期）�?rework 循环 start→submit→APPROVED（score=5）→ pullTasks 拉到 `sub_task.approved`（read=false�?*summary=「审查通过，评�?5/5；评�? A0-4 e2e approve verification�?*）�?
- 测试数据已清理（task/sub_task/review_record/agent_inbox/reward_log + agent.score 回滚）�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移；工具面 10�?1（三通道对齐）；收件箱消息新�?`read` 状态位（历史消息按未读处理）；新增 rejected/approved 两种通知类型（summary 携带评分反馈）；REST 别名通道 tools/list �?TOOL_NAMES 重新对齐（消除声明漂移）�?
- 遗留：① MCP SSE 通道未做实连验证（@Tool 签名与单测覆盖，三通道共用 McpToolService 同一实现，差异仅在参数绑定）；② 人工驳回（无 reviewHistory）时 rejected 摘要回退默认文案，自动核验链（SubTaskReviewService �?reviewHistory）才有完整评分摘要；�?a04 验收达成：外�?agent 可主动拉前置产出摘要（getDepsSummary）与评分反馈（rejected/approved summary），轮询无需自行过滤（read 状态位区分未读/已读）�?

### 6.64 审核真实性核验：自动核验证据硬检�?+ 物化附件清单注入（A0-5：trae 实战反馈一.1「审核真实性」）�?026-08-12�?

#### 1. 背景与结�?

- **盘点结论（A0-5�?*：AUTO_REVIEW 只比对子任务文字描述（如「文�?203 �?errors=0」），不验证产出是否真实存在——编造证据也能通过初筛（trae 1923 案例：声称写了脚本但无实际文件）；且 LLM 核验 prompt 不含真实附件信息，无法核对「声称的交付�?�?实际物化产物」�?
- **落地决策**：① `reviewSubTask` 在能力预检之后插入**服务端证据硬检�?*——复�?§6.30 ArtifactStorage 物化链（`ExecutionArtifactService` 产出物化 + `AttachmentService` 注册），无产出支撑直接跳过自动核验并打人工介入，不再�?LLM 初筛；② `subtask-review.md` prompt 注入**物化附件清单**，LLM 按清单逐项核对声称交付物，文件类交付物无对应附件即使文字声称�?03 �?errors=0」也�?pass=false；③ �?§6.56 能力预检衔接：预检在前拦「无本机能力的提交者」，证据检查在后拦「有能力但编造产出的提交」�?
- **边界**：仅「空产出（无 output 且无附件）」与「执行密�?+ 无可读附件」两类被硬拦；非执行密集任务�?output 文字产出即视为产出支撑放行（避免误伤文档类任务）�?

#### 2. 实现要点

- **`AgentDispatchProperties.reviewEvidenceCheckWaitMs`（默�?1000ms�?*：物化与核验竞态补偿——产出物化在结果回报事务 afterCommit 同步执行，自动核验在 AFTER_COMMIT 异步线程启动，两者存在毫秒级竞态；执行密集任务证据检查未发现可读附件时先等待本窗口再重查一次，避免物化未完成被误判为无证据�? 表示不等待（测试/联调可关闭）�?
- **`SubTaskReviewService.checkEvidence(subTask)`**：`attachmentService.list` + `isContentLoadable` 过滤出平台可读附件（local:// 物化产物；minio:// 等外部存储不可直读，不算证据）；产出文本�?`SubTaskOutputExtractor.extractExecutionOutput`。拦截原因两类：`no_output_no_attachment`（无产出文本且无附件）、`execution_dense_no_attachment`（执行密�?+ 无可读附件，仅文字描述）�?
- **拦截动作**：`taskTimelineService.recordEvent(sub_task_review_skip_no_evidence)`（payload：reason/submitterAgentId/attachmentCount/outputPresent�? `subTaskService.markManualIntervention(subTaskId, "review_skip_no_evidence", ...)`，子任务停留 REVIEW 等人工介入面板处理，不进�?LLM 初筛�?
- **附件清单注入**：`buildAttachmentList(subTask)` 逐行生成 `- fileName（type, size bytes, 平台可直�?外部存储（平台不可直读））`，空则「（无物化附件）」；`renderPrompt` 注入 `{{ATTACHMENT_LIST}}`；`subtask-review.md` 新增�?# 物化附件清单」章�?+ 核验要求�?9 条（声称交付物与附件清单对应；文件类交付物无附件即使声称�?03 �?errors=0」也�?pass=false；外部存储标注不可作为可验证证据）�?
- **§6.56 衔接**：能力预检（`isExecutionDense` + `hasLocalExecutionCapability`，跳过时 `review_skip_execution_dense_no_capability`）仍在证据检查之前，两者各自独立记�?timeline 与人工介入，互不覆盖�?

#### 3. 验证结果

- 单测：`SubTaskReviewServiceTest` **20/20 全绿**（新�?4 用例：① �?output 无附�?�?skip + `markManualIntervention(review_skip_no_evidence, reason=no_output_no_attachment)` + timeline；② 执行密集仅文字描�?+ external 附件不可�?�?skip（execution_dense_no_attachment）；�?执行密集无附�?+ waitMs=5 重查路径（`attachmentService.list` 调用 2 次）；④ prompt 注入断言（`AgentTask.userPrompt` 含�?# 物化附件清单�?「平台可直读�?「声称的交付物必须与**物化附件清单**对应」）；存�?16 用例全部适配（helper 默认携带 output，执行密集用例补附件 mock））�?
- 全量回归：`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 507（core 491 + api 16），Failures: 0, Errors: 0**�?
- 真实环境（后端重启加载新代码，`verify-a05.ps1`）：
  - **S1 空产出拦�?*：普通任务子任务 �?claim/start �?`submitResult`（success=true�?*�?output 无附�?*的编造提交）�?6s 后子任务停留 **REVIEW**（未自动 DONE）；timeline 出现 `sub_task_review_skip_no_evidence`（payload: reason=no_output_no_attachment, outputPresent=false, attachmentCount=0）；`context.manualIntervention` 落库（reason=no_output_no_attachment, submitterAgentId, ts）；后端日志 `自动核验跳过：无产出证据支撑` + `人工介入标记写入: reason=review_skip_no_evidence`�?
  - **S2 有附件通过**：`uploadArtifact` 注册 `local://helloai-local/1/api-docs.md` �?`submitResult`（success=true + output）→ 10s �?*�?* `sub_task_review_skip_no_evidence`（证据检查放行），LLM 正常核验�?`sub_task_auto_review_rejected`（reviewer 判定不达�?�?REWORK）——证据硬检查无误伤�?
  - 验证数据为独立测试任务（S1/S2 各一），验证后已�?taskId 精准清理（task/sub_task/task_timeline/attachment/review_record/agent_inbox/conversation_message �?18 行），与 A0-4 先例一致�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移；新增配�?`reviewEvidenceCheckWaitMs`（默�?1000ms）；自动核验链新增证据硬检查（空产�?执行密集无附件两类拦截，均转人工介入）；`subtask-review.md` prompt 结构变化（新增附件清单章节与核验要求�?9 条）；自动核验不再对无证据提交调 LLM，减少无效调用成本�?
- 遗留：① 非执行密集任务「有 output 无附件」仍放行（output 文字视为产出支撑），若需严格化可�?deliverable 类型（脚�?文件类）加强为必须附件；�?`minio://` 外部存储附件平台不可直读，即使真实存在也被当作无证据（`isContentLoadable=false`），靠人工介入兜底；�?a05 验收达成：无附件支撑的编造提交不再自动通过初筛（S1 实测拦截），有物化附�?产出支撑的提交正常进�?LLM 核验（S2 实测放行）�?

### 6.65 值班/心跳语义对称：三工具返回体语义完�?+ Agent 可自检续约（A0-6：trae 实战反馈�?5/6）（2026-08-12�?

#### 1. 背景与结�?

- **盘点结论（A0-6�?*：① `checkIn` 已返�?`leaseId`/`sessionId`/`workMode`/`maxConcurrent`/`expiresAt`（子任务 1 已满足，仅缺单测锁定）；�?`checkOut` 对「无 ACTIVE 租约」只�?`closedCount=0`，无法区分「已过期无需签退」与「从未打卡」，Agent 无法自检；③ `heartbeat` 只回 `serverTime`，不暴露租约剩余时间，「续约是否生�?还剩多久」不可见；④ SKILL.md 未说明租�?`session_id` �?MCP transport session 的映射关系，断连重连�?Agent 无法判断租约是否仍有效�?
- **落地决策**：① `heartbeat` 增强为返�?`onDuty`/`leaseId`/`leaseExpiresAt`/`remainingTtlSeconds`（有 ACTIVE 租约时计�?`Duration.between(now, expireTime)` 剩余秒数，无租约返回 `onDuty=false, remainingTtlSeconds=0`）——每次心跳即一次租约自检，Agent 据此在到期前自行重做 `checkIn` 续约；② `checkOut` 增强为幂等返回当前状态：`closeLease` 后经新增�?`getLatestLease(agentId)` 取最近一条租约，`currentStatus` = `CLOSED`（刚签退�? `EXPIRED`（已过期无需签退�? `NONE`（从未打卡），并附带 `latestLeaseId`/`latestLeaseExpiresAt`/`latestLeaseCloseReason`；③ SKILL.md 澄清租约 `session_id`（平台签发、标识租约）�?MCP transport session（SSE 长连接）相互独立，断连不失效租约，重连后�?`getAgentStatus`/`heartbeat` 自检�?
- **边界**：不改租约生命周期本身（仍为一次性签发、到�?EXPIRED、续�?�?checkOut �?checkIn）；不引入自动续约；`renewLease()` 保持无调用方（仅作为能力预埋）�?

#### 2. 实现要点

- **`McpToolService.heartbeat()` 增强**：`heartbeatService.seen` 刷在线态不变；追加 `getActiveLease(agentId)` 查询 ACTIVE 租约，命中时返回 `onDuty=true` + `leaseId` + `leaseExpiresAt`（ISO8601�? `remainingTtlSeconds`（`Duration.between` 计算，≤0 �?0），未命中返�?`onDuty=false` + `remainingTtlSeconds=0`（`leaseId`/`leaseExpiresAt` �?null）�?
- **`McpToolService.checkOut()` 增强**：`closeLease` 语义不变；追�?`agentDutyLeaseService.getLatestLease(agentId)`（按 `start_time` 倒序取最近一条，`AgentDutyLeaseService` 新增方法）填�?`currentStatus`/`latestLeaseId`/`latestLeaseExpiresAt`/`latestLeaseCloseReason`，无任何租约�?`currentStatus="NONE"`。三态自检语义：CLOSED=刚签退成功 / EXPIRED=租约早已到期无需再签（closedCount=0 的原因可解释�? NONE=从未打卡�?
- **Result 类扩�?*：`HeartbeatResult` 新增 `onDuty`/`leaseId`/`leaseExpiresAt`/`remainingTtlSeconds`；`CheckOutResult` 新增 `currentStatus`/`latestLeaseId`/`latestLeaseExpiresAt`/`latestLeaseCloseReason`（均 `@lombok.Data` 自动生成访问器）。三通道（MCP SSE / REST 别名 jsonrpc / REST 直通）共用同一 `McpToolService`，一处改动三通道一致�?
- **SKILL.md 文档**：�?.1 总表更新 `checkOut`/`heartbeat` 返回要点；�?.2 租约机制块新增三�?A0-6 澄清——租�?`session_id` �?MCP transport session 相互独立（SSE 断连不失效租约，重连后自检再决定续约或重新 checkIn）、心跳可自检续约（`remainingTtlSeconds` 到期�?1 分钟重做 checkIn）、checkOut 幂等三态自检�?

#### 3. 验证结果

- 单测：`McpToolServiceTest` **17/17 全绿**（新�?6 用例：① checkIn 基线——`leaseId`/`sessionId`/`workMode`/`maxConcurrent`/`expiresAt` 同步返回；② checkOut 正常签退 �?`currentStatus=CLOSED` + 租约事实；③ checkOut 幂等（租�?EXPIRED）→ `currentStatus=EXPIRED` + `latestLeaseCloseReason=lease_expired`；④ checkOut 幂等（从未打卡）�?`currentStatus=NONE`；⑤ heartbeat 持有 ACTIVE �?`onDuty=true` + `remainingTtlSeconds` �?(540,600]；⑥ heartbeat �?ACTIVE �?`onDuty=false` + `remainingTtlSeconds=0`）�?
- 全量回归：`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 513（core 497 + api 16），Failures: 0, Errors: 0**�?
- 真实环境（后端重启加载新代码 PID 33660，`verify-a06.ps1` 六场景全过）�?
  - **S1**：`checkIn`（ttlMinutes=1）返�?`leaseId`/`sessionId`（UUID�?`workMode=AUTO`/`maxConcurrent=3`/`expiresAt` ISO8601�?
  - **S2**：`heartbeat` 返回 `onDuty=true`、`leaseId` �?checkIn 一致、`remainingTtlSeconds=59`�? 分钟 TTL 实测剩余）�?
  - **S3**：`checkOut`（reason=verify-a06）→ `closedCount=1`、`currentStatus=CLOSED`、`latestLeaseCloseReason=verify-a06`�?
  - **S4**：重�?`checkOut` �?`closedCount=0`、`currentStatus=CLOSED`（幂等，最近租约仍为已关闭）�?
  - **S5**：psql 将租约翻 `EXPIRED` �?`checkOut` �?`closedCount=0`、`currentStatus=EXPIRED`、`latestLeaseCloseReason=lease_expired`�?
  - **S6**：从未打卡的 `inner-loop-executor` �?`checkOut` �?`closedCount=0`、`currentStatus=NONE`、`latestLeaseId=null`�?
  - 验证产生�?2 条测试租约（close_reason=verify-a06 / lease_expired）验证后已精准删除�?

#### 4. 影响与遗�?

- 影响：无 DB 迁移、无配置新增；三工具返回体向后兼容扩展（仅新增字段，不改既有字段语义）；`AgentDutyLeaseService` 新增只读方法 `getLatestLease`；SKILL.md �?Agent 补充租约自检指引（心�?TTL + checkOut 三�?+ sessionId 映射澄清）�?
- 遗留：① `renewLease()` 仍无调用方——当前续约范式是「先 checkOut �?checkIn」（DB 唯一索引约束），若未来需要原位续约可�?heartbeat 侧接�?`renewLease` 并同步扩展返回体；② 租约 `expiresAt` �?checkIn（新对象�?08:00 表示）与 heartbeat/checkOut（DB 读回，UTC 表示）的时区表示不同，语义一致均为同一时刻，客户端�?ISO8601 解析不受影响；③ a06 验收达成：三工具返回体语义完整（checkIn 租约信息 / checkOut 幂等三�?/ heartbeat 剩余 TTL），Agent 可凭 heartbeat �?`remainingTtlSeconds` 自检续约，断连重连后�?`getAgentStatus`/`heartbeat` 自检租约有效性�?

### 6.66 时区�?SLA：deadline 全链路下�?+ ISO8601 带时区说明（A0-7：反馈一.6，低）（2026-08-12�?

#### 1. 背景与结�?

- **盘点结论（A0-7�?*：① 全链路实�?DTO 均为 `OffsetDateTime`（无 `LocalDateTime`），Jackson 默认序列�?ISO8601 �?offset——技术面已满足「统一带时区」，真实痛点是文档缺�?+ PostgreSQL timestamptz 读回 UTC（`Z`）与新建对象本地偏移（`+08:00`）的双字面表示，外部 Agent 按字符串字面比较会误判（§6.65 遗留②即此问题）；② `sub_task.deadline` 列自 V1 起存在但 `setDeadline()` 零调用——恒�?null，外�?Agent 无法感知任务时限，`ImplicitScoreCalculator` 只能�?`max(actualMs*2, 60000)` 兜底�?
- **落地决策**：① 子任�?1 不做全局转换——ISO8601 �?offset 本身无歧义，采用「文档明�?+ 单测锁定格式」（SKILL.md §0.3 时间�?SLA 语义：`Z` �?`±HH:MM` 等价按绝对时刻解析、服务器时区 Asia/Shanghai）；�?子任�?2 落地 SLA 链路：任务创建可�?`slaMinutes`（V48 新列 `task.sla_minutes`）→ confirmPlan �?**确认时刻 + slaMinutes** 下发各子任务 `deadline` �?pullTasks 已有透传（补格式断言锁定）�?
- **边界**：deadline 从「计划确认时刻」起算（规划耗时不计入执�?SLA）；`recoverAlreadyConfirmed` 恢复路径不补�?deadline；手工创建子任务路径不动（控制范围）；`slaMinutes` 可空（null=无时限，旧行为完全不变）�?

#### 2. 实现要点

- **Flyway V48**：`task` 表新�?`sla_minutes INT`（可空，null=无时限），COMMENT 说明 confirmPlan 下发语义�?
- **`TaskService.createTask` 3 参重�?*：`createTask(title, description, slaMinutes)` �?`slaMinutes`；原 2 参委�?3 参（null），既有调用方零改动�?
- **`CreateTaskRequest.slaMinutes`**：可选字段，向后兼容；`TaskController.create` 透传�?
- **`PlannerAnalysisService.confirmPlan` 下发**：主循环内先 `if (slaMinutes > 0) { draft.setDeadline(now.plusMinutes(slaMinutes)); subTaskService.updateById(draft); }` �?`changeStatus`——因 `changeStatus` 内部�?id 重查库后全字�?`updateById`，未落库�?deadline 会被覆盖丢失（必须先持久化再转正）�?
- **SKILL.md §0.3**：新增「时间与 SLA 语义」块——所有时间字�?ISO8601 带时区偏移、`Z` �?`±HH:MM` 按绝对时刻解析（DB 读回 `Z` / 新建 `+08:00` 双表示等价）、`deadline` 来源（slaMinutes �?confirmPlan 下发）与超时处置（`reportBlocked` 说明原因，不静默拖延）�?

#### 3. 验证结果

- 单测：`PlannerAnalysisServiceTest` **16/16 全绿**（新�?confirmPlan deadline 下发用例：`ArgumentCaptor` 断言 `updateById` �?SubTask 参数 deadline 落在 `[now+59min, now+60min]`、序列化 ISO8601 �?offset、且 changeStatus 照常逐条转正）；`McpToolServiceTest` **18/18 全绿**（新�?pullTasks deadline 透传用例：非 null �?ISO8601 �?offset 正则匹配，无 deadline 透传 null）�?
- 全量回归：`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 515（core 499 + api 16），Failures: 0, Errors: 0**�?
- 真实环境（后端重启加载新代码 PID 17736，V48 自动迁移「now at version v48」成功，`verify-a07.ps1` 六场景全过）�?
  - **S1**：`POST /api/tasks` �?`slaMinutes=60` �?响应 `slaMinutes=60` + `task.sla_minutes=60` 落库�?
  - **S2/S3**：�?PLANNING + 2 �?`PENDING_PLAN_REVIEW` 草稿 �?confirmPlan 返回子任�?`deadline` �?null �?ISO8601 �?offset（实�?`2026-08-12T04:22:24.938325Z` = 确认时刻 11:22+08:00 + 60min，换算正确），DB 全部持久化；
  - **S4**：executor `pullTasks` 消息 `deadline=2026-08-12T04:22:24.938325Z`（ISO8601 �?offset）；DB 字面 `2026-08-12 04:22:24.938325+00` �?API 字面解析�?*同一绝对时刻**——双表示等价实测闭环（`[DateTimeOffset]` 换算断言通过）；
  - **S5**：无 SLA 对照任务 confirmPlan �?`deadline` 保持 null�?无时限语义）�?
  - **S6**：验证数据按 taskId 全引用表链清理（agent_inbox/task_timeline/task_execution_record/task_running_spec/task_iteration/agent_execution_record/review_record/activity_log/attachment/conversation_message/reward_log/sub_task/task �?15 表，含自动分发产生的 `agent_execution_record` 外键引用，需先删子表再删主表）�?

#### 4. 影响与遗�?

- 影响�? 个新 DB 列（`task.sla_minutes`，可空，向后兼容）；`createTask` 3 参重载（2 参兼容）；confirmPlan 仅对�?SLA 任务多一�?`updateById` 批量写入（无 SLA 任务零开销）；SKILL.md 新增时间语义说明（外�?Agent 不再误判 deadline）；`ImplicitScoreCalculator` 时间分在 deadline 下发后真实生效（此前恒走兜底分支）�?
- 遗留：① 超时后的自动处理（超时重�?告警）不在本项范围，deadline 仅作为感知字段下发，超时处置依赖后续轮次（`reportBlocked` 语义已具备）；② REST 详情端点（`getById` 等）时间字段同为 ISO8601，SKILL.md §0.3 已统一定义；③ a07 验收达成：任务创建可�?SLA，confirmPlan 统一下发子任�?deadline，pullTasks 透传 ISO8601 带时区偏移，外部 Agent 按绝对时刻解析不再误判�?

### 6.67 长任�?TTL 自动续租：工具调用即保活（A0-8：反馈四.2，顺带）�?026-08-12�?

#### 1. 背景与结�?

- **背景（反馈四.2�?*：trae-executor 冷启�?10+ 分钟 / 串行验证 5 分钟，任务周期接近租�?TTL（默�?30min）时，无内建续约线程的外�?Agent 会因租约到期�?`DutyLeaseExpirationTask`�?0s 周期）翻 EXPIRED 而掉线；旧范式要�?Agent「TTL 到期前主动重�?checkIn」，依赖 Agent 自身纪律�?
- **勘察结论**：在线态（`HeartbeatService.seen/active`，刷 Redis TTL + `last_seen_time` + 三态）�?*值班租约**（`agent_duty_lease.expire_time`）是两套机制——工具调用此前只刷在线态、不续租约；`AgentDutyLeaseService.renewLease(agentId, ttlMinutes)`（A0-6 预埋：延�?ACTIVE 租约 `expire_time`，无租约返回 null）自预埋�?*零调用方**，A0-8 正是其接入点�?
- **落地决策**：�?plan 子任�?1（工具调用自动续租）——除 `checkIn`（签发新租约�?`checkOut`（结束租约）外，任一工具调用顺带 `renewLease`�?*不做**子任�?2（difficulty 放宽 TTL）——与 E1「动�?TTL 自适应」（差距�?A2 �?2 段，§6.3 为设计参考）重叠，留待其完整设计，避免重复建设；子任�?1 已满足验收「长任务执行期间工具调用即可保活」�?
- **边界**：无 ACTIVE 租约时不自动打卡（保�?checkIn 的打卡语义）；续租窗口沿用租约原 TTL（`start_time→expire_time` 推算），异常兜底 30min，上�?7 天防异常�?TTL；续租失败仅告警不阻断工具调用（顺带动作）�?

#### 2. 实现要点

- **`McpToolService.refreshDutyLease(agentId)` 私有 helper**：`getActiveLease` 判空 �?推算�?TTL �?`renewLease`；整�?try-catch（续租失�?log.warn，不影响主操作）�?
- **9 个工具方法接�?*（assert 鉴权后首行）：`pullTasks` / `ack` / `claimSubTask` / `heartbeat` / `uploadArtifact` / `submitResult` / `reportBlocked` / `getAgentStatus` / `getDepsSummary`；`checkIn` / `checkOut` 不接入（前者签发新租约，后者结束租约）�?
- **heartbeat 语义微调**：心跳顺带续租后返回 `remainingTtlSeconds` �?*续租�?*的剩�?TTL——外�?Agent 只要保持轮询 heartbeat 即可持续在岗；A0-6 的自检语义保留（返回体字段不变，仅数值口径为续租后）�?
- **`McpMcpServer` checkIn 工具描述**：新�?A0-8 说明（任一工具调用自动续约，长任务执行期间正常调用工具即可保活，无需周期性重�?checkIn）�?

#### 3. 验证结果

- 单测：`McpToolServiceTest` **22/22 全绿**（新�?4 用例：pullTasks 按原 TTL=90min 续租 / heartbeat 续租 60min 且返回续租后 TTL / �?ACTIVE 租约不调 renewLease / renewLease 抛异常不阻断工具调用）�?
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` **Tests run: 519（core 503 + api 16），Failures: 0, Errors: 0**�?
- 真实环境（后端重启加载新代码 PID 36440，`verify-a08.ps1` 六场�?ALL PASSED）：
  - **S1**：checkIn ttl=1min �?ACTIVE 租约，DB `expire_time` E0=12:09:06+08:00�?
  - **S2**：sleep 20s �?pullTasks �?DB `expire_time` 推至 12:09:26�?调用时刻+60s，剩�?60s）——续租生效实测；
  - **S3**：heartbeat �?`onDuty=true` + `remainingTtlSeconds=59`（续租后剩余）；
  - **S4**：sleep 50s（累�?70s > �?60s TTL，跨过原过期�?12:09:06）→ heartbeat �?`onDuty=true` + `expire_time` 刷新�?12:10:17（≈now+60s）—�?*跨过期点仍保活，A0-8 验收闭环**�?
  - **S5**：checkOut �?`closedCount=1` + `currentStatus=CLOSED`�?
  - **S6**：清理测试租约行（DELETE 1）�?

#### 4. 影响与遗�?

- 影响�? 个工具每次调用多 1 �?`getActiveLease` 查询 + 有租约时 1 �?`expire_time` 单行 UPDATE（低频轮询场景可忽略）；SKILL.md 租约机制段重写（「一次性签�?/ 不会自动续约 / 需 checkOut �?checkIn」→「工具调用自动续约」，外部 Agent 无需再手动重�?checkIn）；heartbeat 返回 `remainingTtlSeconds` 口径变为续租后剩余�?
- 遗留：① 动�?TTL（按任务在跑/空闲调整，E1）与 difficulty 放宽不做——与 A0-8 自动续租互补但范围独立，留待 E1 完整设计；② 极端高频工具调用会让租约持续延长（活跃即保活，语义与心跳一致，恶意死循环拉取需靠外部机制约束）；③ 租约「逻辑过期但未被扫描翻」窗口内（≤30s）工具调用仍可复活租约——对保活更友好，属预期行为�?

### 6.68 SKILL 模板与交付编码规范：EXECUTION_RECORD 字段说明 + 交付编码约定（A0-9：反馈三.2/5，中低）�?026-08-12�?

#### 1. 背景与结�?

- **背景（反馈三.2/5�?*：EXECUTION_RECORD 五块此前无模板无示例（trae 1921/1922 首轮产出为空被驳�?923 二次提交才补全）；交付物编码规范缺失——外�?Agent 交付�?PowerShell 脚本踩「双�?BOM 坑」（文件�?`EF BB BF EF BB BF`），而验收标准要求「UTF-8 声明」，声明与实际字节不符导致解析失败�?
- **勘察结论**：SKILL.md §4.4 已有基础模板 + 1 �?Java 示例，但缺「每字段 1 句说明」（仅模板占位符）；交付编码全套约定（规�?6 五子项）只沉淀�?helloai-preflight skill（开发者侧），executor SKILL.md（外�?Agent 侧）完全缺失；`ExecutionRecordParser` 五块解析规则（SUMMARY 必填、列表段须「标题行+换行+`- `列表」、VERIFICATION 必须块尾）与文档模板之间无绑定关系，示例漂移无感知�?
- **落地决策**：只�?executor SKILL.md + 解析器单测绑定，**不动 Java 解析逻辑**（解析规则已正确，缺的是文档）；不新增独立文档（避免文档碎片化）�?
- **边界**：planner SKILL.md 不同步（EXECUTION_RECORD �?executor 产出协议，planner 不产出）；Python/JS 等脚本编码约定不在本轮（先覆�?PowerShell/bash 两个实际交付形态）�?

#### 2. 实现要点

- **SKILL.md §4.4 增强**：模板后新增「字段说明」表—�? 字段每字�?1 句说�?+ 解析约束（SUMMARY 必填缺失即整块解析失�?/ 三个列表段须换行 + `- ` �?/ VERIFICATION 必须块尾且其后内容全部视为证据），逐条对齐 `ExecutionRecordParser` 正则实现；新增第 2 个示例（PowerShell 交付场景，与 §4.5 编码约定联动展示）�?
- **SKILL.md 新增 §4.5 交付物编码与环境约定（A0-9 新增�?*：统一 UTF-8；含中文 `.ps1` 必须 **UTF-8 with BOM**（PS 5.1 �?GBK 解析 no-BOM 文件的中文会�?`字符串缺少终止符`）；**�?BOM 限制**（二次写 BOM �?`EF BB BF EF BB BF` 双重 BOM，解析直接失败，交付前十六进制确认文件头）；PowerShell 强制编码头模板（`[Console]::OutputEncoding` + `$OutputEncoding`）；`Parser.ParseFile` 语法自检命令�? error 才提交）；单引号 + `+` 拼接输出风格（PS 5.1 双引号嵌中文提前闭合字符串坑）；Bash 脚本 `LANG/LC_ALL` 声明 + `bash -n` 自检�?
- **�?§4.5 依赖链检查清单顺延为 §4.6**，追�?1 条「交付物编码是否�?§4.5 约定」自检项�?
- **`ExecutionRecordParserTest` 新增 2 用例�?/7�?*：SKILL.md §4.4 两个官方示例原文（Java + PowerShell）作为解析输入，断言五块字段与文档示例完全一致—�?*文档示例与解析器行为绑定，示例一旦漂移立即红�?*（防再漂移机制，�?A0-3 verify-tool-matrix �?diff 思想）�?

#### 3. 验证结果

- 单测：`ExecutionRecordParserTest` **7/7 全绿**（原 5 + �?2：Java 示例五块完整解析 / PowerShell 示例五块完整解析）�?
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` �?**Tests run: 521（core 505 + api 16），Failures: 0, Errors: 0**（较 A0-8 �?519 +2）�?
- `verify-tool-matrix.ps1` 真实环境 **PASS 23 / FAIL 0，ALL PASSED**（S4 SKILL 0.1 �?== tools/list 11 工具 / S5 0.2 端点 13 路由 / S6 禁用旧路�?/ S7 checkIn-checkOut 实测）——SKILL 结构编辑未破坏工具矩阵契约�?
- SKILL.md 文件完整性：UTF-8 no-BOM + LF 行尾保持（编辑前后字节级一致）�?15 行（+59）�?

#### 4. 影响与遗�?

- 影响：外�?Agent 首轮提交即可读到完整模板 + 逐字段说�?+ 两个填充示例 + 交付编码约定与自检命令，预期降低「产出格式不合格」与「编码不符」导致的 REJECTED 轮次（A0-9 验收口径）；SKILL.md §4.4 文档示例已与解析器单测绑定，后续改示例会红测提醒同步�?
- 遗留：① 编码约定暂覆�?PowerShell/bash，Python/JS 等脚本可后续按需补充；② SKILL.md 持续增长�?15 行），若外部 Agent 上下文窗口受限可考虑拆「快速开�?+ 完整手册」；�?`EXECUTION_RECORD` 示例与解析器绑定仅限 core 单测层，运行期无校验（示例仅文档用途，符合预期）�?

### 6.69 任务执行策略前端编辑：TaskFormDialog 执行策略折叠区块 + 创建/编辑全链透传（A1：V47 收尾，优先级最高）�?026-08-12�?

#### 1. 背景与结�?

- **背景（V47 遗留①）**：V47 已落�?`task.agent_policy`/`task.required_skills`/`agent.skills` 三列�?`TaskAgentPolicy` 工具类、选人链约束（拆解/分发/核验/回退），但任务创�?编辑**前端未暴�?policy 表单**——创�?编辑接口不透传 policy，平台内只能�?RequirementChat �?planner 钉住机制（V31）间接指定，executor 白名�?reviewer 指定/回退策略/技能要求全部不可配�?
- **勘察结论**：后端缺口——`CreateTaskRequest` �?title/description/slaMinutes 三字段，`TaskService.createTask`（三参）/`updateTask`（三参，且不更新 slaMinutes）均不写 policy/requiredSkills；前端缺口——`TaskFormDialog.vue` 是孤儿组件（仓库内无引用），`TaskList.vue` 无新�?编辑入口，`types.Task`/`types.Agent` �?V47 字段�?
- **落地决策**：DTO/Service/Controller 全链透传（缺则补）；`updateTask` 采用「null 字段�?set（保持现状）+ �?Map/空列表显式清空」语义（初次实现直接 set 会把实体原值覆盖为 null，单测暴露后改为防御式）；前端复�?`listPlannerOptions`（V31 在班/可选判定）作为 planner 数据源�?
- **边界**：不�?LLM 拆解真实链路验证（deepseek 密钥可用性不确定；planner/executor/reviewer 三链�?policy 指定语义已由 V47 既有单测 `PlannerAgentPickerTest`/`AgentSelectorTest` 覆盖）；`slaMinutes` 编辑语义为「null=不更新」，暂无「显式清�?SLA」入口（已知限制）�?

#### 2. 实现要点

- **后端透传�?*：`CreateTaskRequest` 新增 `agentPolicy`（Map，键结构�?`TaskAgentPolicy`�?`requiredSkills`（List）；`TaskService` 新增 `createTask(title, description, slaMinutes, agentPolicy, requiredSkills)` 五参重载（原三参委托，null=不设置落库走 DB 默认 `{}`/`[]`）与 `updateTask(id, title, description, slaMinutes, agentPolicy, requiredSkills)` 六参（null �?set、空集合=清空）；`TaskController.create/update` 透传�?
- **前端**：`types` 扩展——`Task` �?`slaMinutes/agentPolicy/requiredSkills`、`Agent` �?`skills`、新�?`TaskAgentPolicy` 接口；`taskApi` 新增公共载荷类型 `TaskFormPayload`�?*TaskFormDialog 重写**——「执行策略（V47，可选）」`el-collapse` 折叠区块：拆�?Planner 下拉（`listPlannerOptions`，selectable=false 置灰�? 核验 Reviewer 下拉（按角色拉取�? 执行白名单多�?/ 回退策略与任务难度单选（可清空，缺省回落默认�? 要求技�?`el-tag` 标签输入（回车添加、关闭删除）/ SLA 分钟 `el-input-number`；编辑态回显（`initForm` �?`task.agentPolicy/requiredSkills/slaMinutes` 填充）；提交时仅组装非空键（全空返回 null）�?*TaskList.vue 接入**——header「新建任务」按�?+ 操作列「编辑」（DONE 禁用）�?
- **新增单测**：`TaskServiceTest`（core�? 用例）——五参创�?policy/技�?SLA 落库、三参旧入口不设置、空集合显式清空、null 字段保持现状、任务不存在返回 null 不落库�?

#### 3. 验证结果

- 单测：`TaskServiceTest` **5/5 全绿**（首�?2 失败——updateTask 直接 set null 覆盖实体原值，改防御式 null �?set 后通过，测试先行捕获设计缺陷）�?
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` �?**Tests run: 526（core 510 + api 16），Failures: 0, Errors: 0**（较 A0-9 �?521 +5）�?
- 前端：`vue-tsc --noEmit` **exit 0**；`npm run build` 成功（chunk >500kB 警告为既有现象）�?
- 真实环境：重新打包启动后端（PID 46888）后 `verify-a1-task-policy.ps1` **7 步全 PASS**——S3 创建带五�?policy + 技�?+ SLA 任务回显断言 / S4 getById 回显（DB 落库证明�? S5 编辑整体替换（planner 保留、fallback/difficulty 更新、executorAgentIds 移除、技能替换）/ S6 空集合清空（policy �?`{}`、skills �?`[]`、省略的 sla 保持 120�? S7 级联删除清理�?
- 脚本自修：S6 断言首跑失败——PS 5.1 �?`PSCustomObject` �?`PSObject.Properties.Count` 返回 `$null` 而非 0（`$null -eq 0` �?false），�?`@(...).Count` 包装后通过（后端行为本就正确）�?

#### 4. 影响与遗�?

- 影响：V47 遗留①关闭——任务创�?编辑全链透传 policy 五键 + requiredSkills + SLA，平台内可表单直建「指定拆�?Planner / 执行白名�?/ 指定核验 Reviewer / 回退策略 / 难度 / 技能要求」任务；TaskFormDialog 从孤儿组件转�?TaskList 正式入口；`updateTask` 六参的「null=不更新、空集合=清空」语义与前端表单行为（仅组装非空键）一致�?
- 遗留：① LLM 真实拆解链验证未做（密钥可用性），planner/executor/reviewer 三链 policy 指定语义依赖 V47 既有单测覆盖，后续有密钥可补 `verify-a1` 扩展步；�?slaMinutes 无「显式清除」入口（null=不更新），如需清除需加独立开关；�?脚本注册的固定名 Agent（a1-policy-*）幂等保留在库，供后�?A1 相关验证复用�?

### 6.70 agent.skills 自动推导：注�?管理端保存链�?best-effort 补全（A2：V47 收尾，优先级最高）�?026-08-12�?

#### 1. 背景与结�?

- **背景（V47 遗留②）**：V47 已落�?`agent.skills`（JSONB[] NOT NULL DEFAULT '[]'）与任务 `required_skills` �?AND 匹配（`AgentSelectionConstraints.allows()` �?`skills.containsAll(requiredSkills)`），�?**agent.skills 存在零写入路�?*——注�?管理端保�?Agent 均不�?skills（entity 注释「注册时按接入方式声明」是未实现目标态），能力声明全靠手�?DB 维护，技能匹配形同虚设�?
- **勘察结论**：`AgentService.register` �?setSkills（全仓库 0 �?setSkills 调用）；`AgentController.register` �?Map body + `applyRegistrationExtras`（处�?accessType/specializationSlug/modelType/labels/capabilities�?*�?skills**）；管理�?`AgentUpdateRequest` �?skills �?`AdminAgentController.update` �?`AgentService.updateAgentDetail` 六参（null �?set 防御式）；`AgentResponse` �?skills 字段；`AgentCapability.mergeDefaults`（「默认�?覆盖值」模式）�?`AgentAccessType.defaultCapabilities`（CLI_CLIENT/API_KEY_LLM/WEB_BROWSER 三型）是推导范本�?
- **落地决策**：新�?`AgentSkillDeriver` 静态工具类，实现「显式值优�?�?否则 accessType 基础技�?+ 名称/描述关键词命中合并」的 best-effort 推导，注册与管理端保存链路接入；**显式手工�?已有技能不被推导覆�?*（幂等复用）�?
- **边界**：不做「执行历史产出类型」第三信号源（A2 定义中的推导信号之一，本轮只落地 accessType + 名称/描述关键词两个信号）；不做存�?Agent skills 批量回填（如需可脚本补）�?

#### 2. 实现要点

- **`AgentSkillDeriver`（core/agent 新建�?*：`derive(accessType, name, description, explicitSkills)`——显式技�?clean 后非空直接返回；否则 `BASE_SKILLS`（CLI_CLIENT→shell / API_KEY_LLM→code-review / WEB_BROWSER→web-search�? `KEYWORD_SKILLS` 19 组关键词（docker/容器、python、java、sql/数据库、shell/bash/powershell/脚本、cli、web/search/搜索、浏览器、爬虫、review/审查/评审）命中合并，LinkedHashSet 去重保序；`clean(raw)` 统一 trim/过滤空白/去重�?
- **注册链路**：`AgentController.applyRegistrationExtras` 新增�?5 步——body 显式�?skills �?`clean` 落库；否则已有技能为空时 `derive` 推导落库（幂等复用路径因「已有技能非空」天然不被覆盖）�?
- **管理端链�?*：`AgentUpdateRequest` +skills（List，显式传入整体替�?/ null 保持现状）→ `AdminAgentController.update` 透传 �?`AgentService.updateAgentDetail` 六参改七参（skills �?null �?`AgentSkillDeriver.clean` 后整体替换，null �?set）�?
- **响应补全**：`AgentResponse` +skills 字段，`toResponse` 回填（前端详�?验证脚本可见）�?
- **关键缺陷修复（真实环境验证暴露）**：`AgentMapper.xml` 自定�?`insert`/`updateById`（覆�?BaseMapper 处理 PG JSONB 字段）列清单**写死且未�?V47 新增 skills �?*——无�?entity 怎么 setSkills，UPDATE/INSERT SQL 都不�?skills 列，DB skills 恒为 `[]`。对比实验：Task �?MP 默认 `updateById`（含 `required_skills` 列，风格 `title=?`）与 Agent（`name = ?` 风格）SQL 来源不同，读 Mapper 源码确认。修复：两处 SQL �?`skills` 列，�?`PgJsonbTypeHandler` + `COALESCE(#{...skills...}::jsonb, '[]'::jsonb)` 兜底 NOT NULL 约束（register 等路径实�?skills �?null 时不炸库）�?

#### 3. 验证结果

- 单测：`AgentSkillDeriverTest` **11/11 全绿**——显式优�?/ �?accessType 基础技�?/ 关键词合并去重（"devbox"+"擅长 Python 脚本�?Docker 容器"→[shell, docker, python]�? 大小写归一 / 名称描述双扫描同标签去重 / 空显式走推导 / clean trim+空白过滤+去重 / null 防御�?
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` �?**Tests run: 537（core 521 + api 16），Failures: 0, Errors: 0**（较 A1 �?526 +11）�?
- 真实环境：重新打包启动后 `verify-a2-skill-derive.ps1` **7 步全 PASS**——S2 CLI_CLIENT 无关键词注册 skills 恰为 [shell]（验收点�? S3 API_KEY_LLM "Docker 审查专家" �?[code-review, docker] / S4 显式 [kubernetes, golang] 恰好 2 �?/ S5 幂等复用保持显式技�?/ S6 管理�?PUT 整体替换 + 只改 remark 保持 / S7 级联删除清理�?
- 调试过程沉淀：skills 不落库根因为 AgentMapper.xml 自定�?SQL 缺列（见实现要点），修复后首跑即�?PASS；调试残留（agent `a2-dbg-cli`、task `a2-skill-dbg-task`）已走级联删除接口清理，库中�?a2 前缀残留�?

#### 4. 影响与遗�?

- 影响：V47 遗留②关闭—�?*新注册外�?Agent 自动带基础技能标签，`required_skills` 技能过滤开始有实际效果**；管理端详情编辑可整体替�?skills（null 保持）；AgentResponse 暴露 skills 供前端展�?脚本断言�?
- 遗留：① 执行历史产出类型推导（第三信号源）未做，后续如需可按 sub_task 产出物类型反推技能；�?存量 Agent skills 仍为空（`[]`），如需让旧 Agent 参与技能匹配可补一次性回填脚本；�?技能词典（19 组关键词）为静�?Map，后续可外置配置�?

### 6.71 required_skills 技能同义词归一：匹配前归一化，同义词技能互相命中（A3：V47 收尾，优先级最高）�?026-08-12�?

#### 1. 背景与结�?

- **背景（V47 遗留③衔接）**：V47 的技�?AND 匹配（`AgentSelectionConstraints.allows()` �?`containsAll`）是**精确字符串全匹配**——任�?`required_skills=["powershell"]` 无法命中声明 `skills=["shell"]` �?Agent�?shell 脚本"�?powershell"被视为不同技能；A2 解决了技�?*声明�?*（注册自动推导），A3 解决**匹配�?*（匹配前归一化）�?
- **勘察结论**：技能匹配唯一入口�?`AgentSelector.AgentSelectionConstraints.allows()`（全仓库 `getSkills()` 仅此一处消费）；调用链�?`SubTaskDispatchService.resolveConstraints()`（初始分�?+ ASSIGNED 超时重分配）�?`ResilientDispatcher`（熔断降级替代），全部经 `pickPreferred/pickAlternative` �?`allows()`；`TaskAgentPolicy` 只解�?policy 键（planner/executor/reviewer/fallback/difficulty），不承担技能判定（计划子任�?2 �?TaskAgentPolicy 技能判定接�?按代码事实校正为 `AgentSelectionConstraints` 接入点）；executor SKILL.md 无技能匹配语义说明（grep 确认），无需同步文档�?
- **落地决策**：新�?`SkillNormalizer` 静态工具类（`core/agent`，与 `AgentSkillDeriver` 同域同风格），内置同义词映射，`allows()` 匹配前对双方技能标签归一化（trim + 小写 + 同义词归并）；AND 语义不放松（归一化后仍缺技能照常过滤）�?
- **边界**：不做技能词�?DB 表（维持内置静�?Map，A2 遗留③的外置配置化仍留后续）；不做多级层级体系（�?编程语言归脚本类"多级分类）；不改 `AgentSkillDeriver` 推导逻辑（其产出已是规范标签，无需归一）；不做存量数据回填�?

#### 2. 实现要点

- **`SkillNormalizer`（core/agent 新建�?*：`SYNONYMS` 14 组同义词映射（bash/powershell/脚本/cli→shell、容器→docker、数据库→sql、web/search/搜索/浏览�?爬虫→web-search、review/审查/评审→code-review，与 `AgentSkillDeriver.KEYWORD_SKILLS` 非恒等项语义对齐）；`normalize(String)`——null/空白→null，trim + `toLowerCase(Locale.ROOT)` 后命中同义词表返回规范标签，未命中原样小写返回（自定义技�?kubernetes/golang 保持可精确匹配）；`normalizeAll(List)`——逐项归一 + LinkedHashSet 去重保序；`matches(agentSkills, requiredSkills)`——归一�?`containsAll`，requiredSkills �?null 视为不约束（与调用方"�?不限�?语义一致）�?
- **`AgentSelectionConstraints.allows()` 改�?*：`skills.containsAll(requiredSkills)` 替换�?`SkillNormalizer.matches(skills, requiredSkills)`；字段注释与行内注释同步补充归一化语义（"A3：匹配前归一化，powershell/bash �?shell 互相命中"）�?
- **新增单测**：`SkillNormalizerTest`（core/agent�?3 用例）——英�?中文同义词归一、大小写�?trim 归一、未命中自定义技能原样、归一幂等、normalizeAll 去重保序�?null/空白防御、matches 同义词交叉命中（powershell↔shell 双向）、中英文混合 AND、缺技能不命中、空约束语义；`AgentSelectorTest.TaskLevelConstraints` 新增 4 用例——requiredSkills=powershell 命中 skills=shell、[bash, 容器] 命中 [shell, docker]（中英文交叉）、Python 命中 python（大小写）、归一化后仍缺技能不命中（AND 不放松）�?

#### 3. 验证结果

- 单测：`SkillNormalizerTest` **13/13 全绿**（首�?1 失败为测试自�?bug——`List.of("  ", null)` 的不可变列表工厂禁止 null 元素，改 `Arrays.asList` 后通过，被测代码零改动）；`AgentSelectorTest` 含新�?4 用例全绿�?
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` �?**Tests run: 554（core 538 + api 16），Failures: 0, Errors: 0**（较 A2 �?537 +17，即 SkillNormalizerTest 13 + AgentSelectorTest 4）�?
- A3 验收达成�?*同义词技能可命中**（任务要�?powershell/bash/容器/搜索/审查 等可命中声明 shell/docker/web-search/code-review �?Agent），判定逻辑有单测（13+4 用例锁定）�?

#### 4. 影响与遗�?

- 影响：`required_skills` 技能过滤从"精确字符�?升级�?规范化字符串"——任务创建侧�?Agent 声明侧只要一方使用同义词即可互相命中，A2 推导的规范标签（shell/code-review/web-search 等）与手工声明的同义写法（powershell/审查/搜索 等）不再互相排斥；自定义技能（kubernetes/golang 等）归一后小写精确匹配，行为不变；AND 语义不放松（缺技能仍过滤，既�?5 �?V47 精确匹配用例全部保持通过，向后兼容）�?
- 遗留：① 同义词词典与 A2 关键词表均为静�?Map 且独立维护（内容语义对齐），后续外置配置化时应合并为单一数据源，避免两处漂移；② 层级归一（多级技能分类）未做，当前仅单层同义词归并；�?未做真实环境 e2e 验证（技能匹配是选人链内部逻辑，`AgentSelectorTest` 4 个真实候选场景用例已等价覆盖，真实分发链路需造数走完整拆解链，留待有密钥时与 A1 扩展步一并验证）�?

### 6.72 Agent 编辑弹窗技能编辑：管理端列表回�?skills + 标签增删保存整体替换（A3B：V47 前端缺口补齐）（2026-08-12�?

#### 1. 背景与结�?

- **背景**：A2 已打通管理端 `PUT /admin/agents/updateById/{id}` �?skills 整体替换（`AgentUpdateRequest.skills`，null 保持现状）与 `AgentResponse.skills` 回填，但前端消费不全——`AgentEditDialog.vue` 无技能编辑项，且管理端分页列表返回的 `AgentListItemVO` 未映�?skills 字段（前�?`AgentListItem` 类型也没有该字段），编辑弹窗打开时无法回显已有技能；上次合规检查时识别为可选小补齐，用户确认补上�?
- **勘察结论**：`agentApi.updateProfile` 实际已指向管理端 `updateById` 端点（仅类型定义未含 skills）；`AdminAgentController.list` 返回 `AgentListItemVO`（与 `AgentResponse` 不同 DTO），映射代码�?setSkills；`AgentListItem` 类型�?skills 字段；`updateProfile` 全仓库仅 `AgentEditDialog` 一处调用，类型扩展无破坏面�?
- **落地决策**：最小闭环三件套——后�?VO 补字段映射（一行）、前端类型补字段（types + api 定义）、编辑弹窗加技能标签编辑（回显/回车添加/标签删除/保存整体替换），交互完全复用任务表单「要求技能」的既有模式（TaskFormDialog 同款 skills-box/skill-tag/skill-input + addSkill/removeSkill）�?
- **边界**：不�?AgentDetail 详情页技能展示（列表页可编辑已满足管理诉求）；不�?role 下拉的既有保存语义（`AgentUpdateRequest` �?role 字段，编辑不生效为既有行为，不在本轮范围）；不改后端 updateAgentDetail 逻辑（A2 已实现整体替�?+ null 保持，前端仅消费）�?

#### 2. 实现要点

- **后端 `AgentListItemVO`**：新�?`List<String> skills` 字段（带 V47/A2 注释）；`AdminAgentController.list` 映射�?`vo.setSkills(a.getSkills())`�?
- **前端类型**：`types/index.ts` `AgentListItem` �?`skills?: string[]`（注释同 V47/A2）；`api/agent.ts` `updateProfile` 请求类型�?`skills?: string[]`（注释：显式传入整体替换，不传则后端保持现状）�?
- **`AgentEditDialog.vue`**：表单加 `skills: string[]` �?`newSkill` 输入；打开弹窗回显 `Array.isArray(a.skills) ? [...a.skills] : []`；「技能」form-item 置于「描述」之前，el-tag 标签 + 小输入框（回车添加、防重复、可删除），样式与任务表单完全同款；保存 payload 始终�?`skills: form.skills`（回显保证表单值初始等于当前值，用户所见即所得：没动 = 传回原值等效保持，删光 = `[]` 清空技能）�?
- **验收脚本**：新�?`scripts/powershell/verify-a3b-agent-edit-skills.ps1`（UTF-8 with BOM + 单引号输�?+ ASCII 运行时字面量，规�?6 合规），覆盖列表回显 / 整体替换 / 清空 / null 保持 / 级联清理�?

#### 3. 验证结果

- 静态检查：`vue-tsc --noEmit` 无类型错误；`mvn -pl helloai-api -am compile -DskipTests` 编译通过�?
- 全量打包：`mvn -pl helloai-start -am -DskipTests package` 成功�?1.5MB jar）；`npm run build` 成功�?8.57s）�?
- 真实环境：重新打包重启（PID 23048�?565 就绪）后 `verify-a3b-agent-edit-skills.ps1` **14/14 �?PASS**——S3 adminList 记录�?skills（kubernetes,golang 原样回显，VO 映射生效�? S4 PUT 整体替换�?[shell, docker] / S5 �?`[]` 清空 / S6 不传 skills 只改 remark 保持 / S7 级联删除清理。首�?3 失败为脚本自身字段名写错（`PageResult.records` �?实际�?`list`，与前端类型一致），修正后全绿，被测代码零改动�?
- 环境备注：本次重启踩�?start-sb.ps1 两个沙箱环境问题——① 脚本�?`mvn` 在受限环境「拒绝访问」（改为 Node fallback 直接执行 mvn package）；�?`Start-Process -FilePath 'java'` 依赖 PATH，受�?PowerShell PATH �?java（改�?`$env:JAVA_HOME\bin\java.exe` 完整路径启动并写 PID 文件）�?

#### 4. 影响与遗�?

- 影响：V47 前端缺口补齐——管理端 Agent 列表 �?编辑弹窗现在可查�?增删技能标签并保存，保存走 A2 已就绪的 `updateById` skills 整体替换语义（删�?= 清空，不�?= 保持），与任务表单「要求技能」同交互同视觉；技能声明侧的前端闭环完成（注册自动推导 �?列表回显 �?编辑维护 �?required_skills 归一匹配）�?
- 遗留：① `AgentDetailVO`（管理端 getById 详情）仍未映�?skills，详情页若要展示技能需再补一行映射（本轮未做）；�?AgentEditDialog �?modelType/specializationSlug 回显仍为空（`AgentListItem` 无这两个字段，保存时�?undefined 后端保持现状，行为安全但编辑态观感不完整），�?role 下拉不生效同属既有缺口，未在本轮扩散；③ 新增验收脚本未纳�?CI，与既有 verify-*.ps1 一致为手动/按需执行�?

### 6.73 技能输入交互升级：规范标签多选下�?+ 自定义回车（A3B 用户反馈微调）（2026-08-12�?

#### 1. 背景与结�?

- **背景**：A3B 交付后用户核验反馈——技能标签手动输入（el-tag + 输入框）体验一般，建议改为可多选的下拉选项；平台技能本质是「规范词表（6+1 个标签）+ 自定义技能（kubernetes/golang 等）」双层语义，不能退化成纯枚举多选�?
- **落地决策**：`el-select multiple + filterable + allow-create + default-first-option`——下拉多选规范标签、可搜索过滤、输入不在选项中的词按回车创建自定义标签，两全其美；技能选项抽为共享常量供两处消费端复用�?
- **边界**：不改后端（词表仍在后端静�?Map，前端常量与后端注释对齐，遗留的外置配置化同时覆盖两端）；仅�?UI 交互，数据模型（string[] 整体替换语义）不变�?

#### 2. 实现要点

- **`src/constants/agentSkills.ts` 新建**：`AGENT_SKILL_OPTIONS` 常量—�? 项规范标签（shell/docker/sql/web-search/code-review/python/java，带中文说明 label），注释标明与后�?`AgentSkillDeriver.KEYWORD_SKILLS` / `SkillNormalizer.SYNONYMS` 规范标签对齐�?
- **`AgentEditDialog.vue`**：技能区 el-tag+输入�?�?el-select multiple 多选下拉；移除 addSkill/removeSkill/newSkill �?skills-box 样式；回�?保存语义不变（string[] 整体替换，删�?= 清空）�?
- **`TaskFormDialog.vue`**：「要求技能」同构改造（同一套词的另一消费端，保持一致交互）；移除手动输入逻辑与样式�?
- 保留 `field-hint` 说明文案（AND 语义提示）�?

#### 3. 验证结果

- `vue-tsc --noEmit` 无类型错误；`npm run build` 成功�?8.69s）�?
- 纯前端改动，后端无变更；UI 交互（下拉多�?搜索/自定义回车）留待用户浏览器核验�?

#### 4. 影响与遗�?

- 影响：技能输入从"自由手填"升级�?规范标签多�?+ 自定义兜�?——Agent 技能区与任务要求技能区交互统一，规范标签带中文说明降低填错概率（如 web-search 不再手打�?web_search）；自定义能力保留（回车创建任意标签）�?
- 遗留：① 前端选项常量与后端词表为两处独立维护（与 A2 遗留③同源），外置配置化时应前后端统一收口；② 选项仅覆盖当�?7 个规范标签，未来后端词表扩展时前端常量需同步；③ 下拉「选择或输入」模式下，自定义标签的大小写/空白由保存链�?trim 兜底（A2 clean），无额外校验�?

### 6.74 移除 executor 专业化下拉与模型选择：specializationSlug 全链路清理（用户拍板）（2026-08-12�?

#### 1. 背景与结�?

- **背景**：用户核�?A3B 后拍板三点——① executor 角色的「专业化」下拉没有实际用处，专业�?prompt（AGENT_SPECIALIZATION 模板机制）实际未接入任何链路，要求连代码一起全量移除；�?外部 AI agent（CLI 接入）注册后再编辑不需要填模型类型（模型取决于外部 agent 自身正在使用的模型），内�?LLM 注册的模型统一按系统配置（llm_provider > sys_config > yml �?default-model 三级兜底）决定；�?技能在新建 Agent 时就能填写（注册表单加技能多选）�?
- **勘察结论**：`PromptTemplateService.getBySlug/composeBySlug`（AGENT_SPECIALIZATION 机制）全仓库仅定义无调用，确认为死代码，用户判断正确；specializationSlug 剩余消费点仅注册/编辑表单 UI �?VO/DTO/Controller 透传；模型缺省链已就绪——`AgentProviderResolver.resolveProvider(agent, fallback)` �?modelType 为空时回退系统默认 provider，各 Provider Factory �?defaultModel �?DB > sys_config > yml 三级兜底，`LlmProviderCatalogService.provisionPlatformCredential` 按解析出�?provider 自动补绑平台密钥，注册表单去掉模型选择后内�?LLM 链路依然闭环�?
- **落地决策**：后端删�?specializationSlug �?DTO/VO/Controller 透传�?composeBySlug/getBySlug 死代码方法；前端删除 AgentEditDialog 模型类型+专业化、AgentList 注册表单专业化下�?模型 provider 下拉（内�?LLM 不再�?provider，统一走系统默认），注册表单新增技能多选（复用 §6.73 �?AGENT_SKILL_OPTIONS）�?
- **边界**：DB �?specialization_slug 与实体字段保留（历史数据兼容，不做迁移）；prompt_template �?AGENT_SPECIALIZATION 分类保留（模板管理页通用功能，不扩散）；后端 `/admin/agents/listLlmProviders` 端点保留（对�?API 面不收缩，仅前端消费移除）�?

#### 2. 实现要点

- **后端�? 文件�?*：`AgentService.registerWithExtras/updateAgentDetail` �?specializationSlug 参数；`AgentCreateRequest`/`AgentUpdateRequest`/`AgentResponse`/`AgentDetailVO` 删字段；`AdminAgentController`/`AgentController` �?`setSpecializationSlug` 调用�?`applyRegistrationExtras` 中读取逻辑；`PromptTemplateService` �?`getBySlug`/`composeBySlug` 死代码方法（compose() 保留，ROLE_TEMPLATE 角色模板链路不受影响）�?
- **前端�? 文件�?*：`api/agent.ts` �?`listLlmProviders`（注册表单不再用）；`AgentEditDialog.vue` 删模型类型输入与专业化下拉（form/回显/保存全链路移除，保存 payload �?name/remark/skills）；`AgentList.vue` 注册表单删专业化下拉与模�?provider 下拉（含 LlmProviderItem/loadLlmProviders/onAccessTypeChange 相关逻辑），新增技能多选（AGENT_SKILL_OPTIONS 复用，注册即填写，A2 显式技能优先），提交不再传 modelType（内�?LLM 后端按系统默�?provider+default-model 补绑）�?
- **验收脚本**：新�?`scripts/powershell/verify-674-remove-specialization.ps1`（UTF-8 with BOM + 单引号输出，规则 6 合规），覆盖响应契约�?specializationSlug 字段 / 注册�?skills / 编辑不带 modelType / 内部 LLM 注册缺省 modelType �?null / 级联清理�?

#### 3. 验证结果

- 静态检查：`mvn -pl helloai-api -am compile -DskipTests` BUILD SUCCESS（改造后全量 `mvn -pl helloai-start -am package` 29s 成功）；`vue-tsc --noEmit` 无类型错误；`npm run build` 成功�?8.00s）�?
- 真实环境：重新打包重启（PID 17124�?565 就绪）后 `verify-674-remove-specialization.ps1` **16/16 �?PASS**——列�?详情/注册响应均无 specializationSlug 字段（契约层面确认移除）/ 注册�?skills 显式生效 / 编辑不带 modelType 更新成功 / 内部 LLM 注册缺省 modelType=null（系统默�?provider 兜底）�?

#### 4. 影响与遗�?

- 影响：Agent 管理链路去掉无效的专业化选择（executor 专业化下拉、编辑模型类型字段），内�?LLM 注册简化——不再�?provider，模型统一由系统配置决定；技能在注册时即可填写（显式优先，不填仍按接入类�?关键词自动推导）�?
- 遗留：① DB �?specialization_slug 与实体字段保留但已无任何业务消费，可随大版本迁移一并清理；�?prompt_template �?AGENT_SPECIALIZATION 分类仍可创建�?Agent 侧不再消费（模板管理页保留）；③ §6.72 遗留�?AgentDetailVO 仍未映射 skills，本轮未扩散；④ 内部 LLM 注册不再展示实际生效�?provider/模型，如需可在详情页补只读展示（未做）�?
### 6.75 MinIO 附件存储集成 + 附件目录路径规范（A0-5 遗留②收口）�?026-08-12�?

#### 1. 背景与结�?

- **背景**：用户拍板把 MinIO 用起来——① A0-5 遗留②「minio:// 外部存储附件平台不可直读（isContentLoadable=false），即使真实存在也被当作无证据」要求收口，agent 返回结果需要平台侧验证附件文件或脚本；�?附件管理此前无明确路径要求，要求以后生成的附件按「归属�?username �?�?�?�?�?主任务」分文件夹组织，便于按规律检索哪些文件属于哪些主任务�?
- **勘察结论**：MinIO 早已�?docker-compose�?9000 S3 API / 29001 Console），`ArtifactStorage` 抽象�?§6.30 已预�?minio 扩展位；attachment 元数据表 `detectBucketName/detectObjectKey` 已支�?minio:// 前缀解析；本地物化链（local://）objectKey 原为 `{subTaskId}/{yyyyMMdd}/{uuid8}-{safeName}`，无归属�?主任务维度�?
- **落地决策**：① 引入 `io.minio:minio:8.5.12`（版本集中管理在�?pom），实现 `MinioArtifactStorage`（storageUrl=`minio://{bucket}/{objectKey}`，懒创建客户�?+ 首次写入自动 makeBucketIfNotExists）；�?新增 `CompositeArtifactStorage`（@Primary 路由，ObjectProvider 懒解析避免自引用循环）：store �?`helloai.storage.type` 路由主存储，load/supports 按协议前缀分派——存�?local:// 附件与新 minio:// 附件同时可读/可下�?可作执行证据；③ objectKey 统一规范�?`{ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{safeName}`（Local �?Minio 双实现一致），归属者目录取执行 Agent 注册名（agent.name，接�?static 方法清洗防路径穿越）；④ 默认 `helloai.storage.type` 切为 minio（bucket=helloai-artifacts，端�?凭证走环境变量兜底）�?
- **边界**：外�?Agent 自己 PUT �?MinIO 的路径不由平台强制（SKILL 建议按规范组织）；存�?local:// 附件不迁移；agent.name 无唯一索引，重�?Agent 的目录会合并（uuid 前缀保证文件不冲突）�?

#### 2. 实现要点

- **依赖**：根 pom �?`minio.version=8.5.12` + dependencyManagement 条目；helloai-core 引用�?
- **配置**：`ArtifactStorageProperties` 扩展 `minioEndpoint/minioAccessKey/minioSecretKey/minioBucket` 四字段（带默认值，yml 未配置可跑）；`application.yml` storage 段重写（type=minio + `${MINIO_*}` 环境变量兜底 + 注释说明目录规范）�?
- **存储层（helloai-core/system/storage�?*：`ArtifactStorage` 接口�?`storageType()` 默认方法 + `sanitizeOwnerName/sanitizeFileName` 两个 static 清洗方法（原 Local �?sanitizeFileName 上移共用�? store 签名扩展�?`store(ownerName, taskId, subTaskId, fileName, content)`；`LocalArtifactStorage` objectKey 改新规范；`MinioArtifactStorage` 新建（putObject/getObject、bucket ensure、contentType 探测、包级测试构造器注入 mock client）；`CompositeArtifactStorage` 新建（@Primary，store 路由主存储，load/supports 前缀分派）�?
- **物化�?*：`ExecutionArtifactService` 注入 `AgentService`，`resolveOwnerName` �?assignedAgentId 对应 Agent 注册名（缺失兜底 `agent-{id}`），store �?`(ownerName, subTask.getTaskId(), subTask.getId(), ...)`�?
- **直读链路**：`AttachmentService.isContentLoadable/loadContent` �?Composite 路由天然支持 minio://（下载流式返�?+ A0-5 证据检查生效），类注释同步更新；`McpMcpServer` uploadArtifact Gotchas �?`McpToolService` 注释补「v2.7 起平台可直读 minio:// 附件 + 建议路径规范」�?
- **SKILL 同步**：executor/planner SKILL.md �?uploadArtifact 行更新（storageUrl 示例�?minio://、说明平台可直读�?`{注册名}/{yyyy}/{MM}/{taskId}/{subTaskId}/` 目录规范）�?
- **验收脚本**：新�?`scripts/powershell/verify-minio-artifact.ps1`（UTF-8 �?+ 单引号输出，规则 6 合规）：G1 MinIO health / G2 附件列表存在 minio:// �?objectKey 符合目录规范 / G3 minio:// 附件下载 200 + 非空 + �?302 重定向（�?minio 附件时输�?SKIP 与产生指引）�?

#### 3. 验证结果

- 单测：`LocalArtifactStorageTest` 更新（新 store 签名 + 目录规范断言 + ownerName 清洗用例）；新建 `MinioArtifactStorageTest`（store 上传参数/URL 协议/objectKey 分层/load 读取/supports）与 `CompositeArtifactStorageTest`（store 路由 local/minio、未知类型抛错、load/supports 前缀分派）；`ExecutionArtifactServiceTest` 适配新构造器�?store 签名。`mvn -pl helloai-core -am test` 全量 **551 个测试全�?*（含修复 4 个新测试自身缺陷：mock Stream 单次消费需 thenAnswer 重建、mock GetObjectResponse 需 stub readAllBytes、verifyNoInteractions �?stubbing 调用冲突�?never() 验证）�?
- 真实环境：未重启后端，MinIO 实链（物化落�?+ 下载直读 + 证据核验）待 `verify-minio-artifact.ps1` 实测（G2/G3 需先有 minio:// 附件，可跑一次执行任务产生）�?

#### 4. 影响与遗�?

- 影响：① A0-5 遗留②关闭——minio:// 附件平台可直读，`SubTaskReviewService.checkEvidence` �?`TaskDeliverableService` zip 打包�?MinIO 附件生效；② 附件目录统一「归属�?�?�?主任�?子任务」五层规范（local �?minio 一致），MinIO Console 可按路径规律直接检索；�?默认存储�?minio 后，未启�?MinIO 的环境物化失败仅记日志（best-effort 不阻断主链路），可改 `type: local` 回退�?
- 遗留：① agent.name 无唯一索引，重�?Agent 目录合并（可后续加唯一约束或目录后缀 agentId）；�?外部 Agent uploadArtifact �?storageUrl 路径�?SKILL 约定不强制；�?存量 local:// 附件保留可读不迁移；�?真实环境 MinIO E2E 回归（verify-minio-artifact.ps1 G2/G3）待后端重启后执行�?
### 6.76 登录链路脚本化验�?+ MinioArtifactStorage 启动缺陷修复�?026-08-12�?

#### 1. 背景与结�?

- **背景**：用户拍板登录页去掉 api 登录（系统以「注�?+ 账号密码登录」为主），登录页已改造为「登�?注册」双入口且登录类型固�?admin；用户要求不再用浏览器手动点，改为脚本模拟登录做验证�?
- **勘察结论**：`POST /api/auth/login`（type=admin 账号密码 / type=agent API Key）仍保留 agent 通道（MCP/CLI 依赖），前端已不再暴露；`/api/auth/me`、`/api/auth/logout` 提供登录态校验与登出；业务异常码 4xx/5xx �?`GlobalExceptionHandler` 映射�?HTTP 状态码�?00 业务失败 = HTTP 500�?01 会话过期 = HTTP 401）�?
- **顺带发现并修复启动缺�?*：真实环�?jar 启动�?`BeanInstantiationException: No default constructor found`——`MinioArtifactStorage` 里手写的包级测试构造器（properties, client）导�?Lombok `@RequiredArgsConstructor` 被跳过（Lombok 规则：类中已存在任何构造器即不再生成），Spring 无法实例化。该缺陷�?v2.7（�?.75）引入，单测直接调用包级构造器所以没暴露�?

#### 2. 实现要点

- **验收脚本**：新�?`scripts/shell/verify-login-e2e.sh`（macOS zsh 风格 + UTF-8 声明 + 单引号输出，规则 6 合规；`ADMIN_USER/ADMIN_PASSWORD` 环境变量可覆盖，默认 admin/admin123），11 项用例：0 健康检�?/ 1 空用户名拒绝 / 2 空密码拒�?/ 3 未知用户（HTTP 500 + 用户不存在或已禁用）/ 4 错误密码（HTTP 500 + 密码错误�? 5 非法登录类型 apikey（HTTP 200 + 登录类型无效，验证旧 api 入口服务端拒绝）/ 6 账号密码登录成功（token+type=admin+role�? 7 /me �?token 返回身份（type=admin + displayName 非空�? 8 /me �?token 返回 code=401 / 9 logout / 10 登出后旧 token �?401 拒绝�?
- **启动缺陷修复**：`MinioArtifactStorage.java` 删除包级测试构造器（恢�?Lombok 生成 public 单参构造器，Spring 构造器注入生效）；`MinioArtifactStorageTest` 改为同包直接注入包级 `client` 字段（跳过懒创建），保持 mock 语义�?

#### 3. 验证结果

- 真实环境：JDK 17 + `mvn -pl helloai-start -am package -DskipTests` 构建，`java -jar helloai-start-1.0.0-SNAPSHOT.jar` 启动 6565 成功后，`verify-login-e2e.sh` **11/11 �?PASS**�?
- 回归：`MinioArtifactStorageTest/CompositeArtifactStorageTest/LocalArtifactStorageTest` BUILD SUCCESS，存储层无回归�?

#### 4. 影响与遗�?

- 影响：① v2.7 引入的应用启动缺陷关闭（此前 jar 无法启动，minio 存储链路实际不可用）；② 登录链路（注册入�?+ 账号密码登录 �?/me 鉴权 �?登出失效）获得脚本化回归保障，后续改动可直接跑脚本�?
- 遗留：空用户�?空密码的参数校验异常（`@Valid` 失败）被 `GlobalExceptionHandler` 兜底�?HTTP 500 而非 400，语义不准确，本轮未修；`type=agent` 通道保留�?MCP/CLI 使用，登录页已不暴露�?
### 6.77 MinIO 附件 E2E 真实环境验证 + 登录页前端构建验证（2026-08-12�?

#### 1. 背景与结�?

- **背景**：�?.76 收口后遗留两件事——① 前端登录页改造（�?api 登录）未做构建验证；�?§6.75 遗留④「真实环�?MinIO E2E（verify-minio-artifact.ps1 G2/G3）待后端重启后执行」，且该脚本�?PowerShell，macOS �?pwsh 跑不了。用户要求：跑前端构�?+ 移植 zsh 版并触发最小执行任务产生附件，验证物化落桶与直读下载�?
- **勘察结论**：平�?4 �?LLM provider 全部 `apiKeyConfigured=false`（credential_vault 无平台级凭证），**内部 LLM 执行链不可行**；但物化�?`ExecutionResultHandler` �?`submitResult`（外�?Agent REST 直通即可调用，无需 LLM）触发——success=true �?afterCommit �?`ExecutionArtifactService.materialize` �?`artifactStorage.store` 写入主存储（minio），output 非空即解析为单个 .md 文件。由此确定「最小执行物化」路径：�?Agent �?建任�?�?建子任务（指派）�?claim �?submitResult(output 非空)�?

#### 2. 实现要点

- **前端构建**：`npx vue-tsc --noEmit` 0 错误 + `npm run build` 成功�?.82s，仅 chunk 体积提示，无类型/构建错误），登录页改造（入口�?tab：登�?注册，type 固定 admin）构建侧通过�?
- **验收脚本**：新�?`scripts/shell/verify-minio-artifact.sh`（macOS zsh 版，UTF-8 声明 + 单引号输出，规则 6 合规；`ADMIN_USER/ADMIN_PASSWORD` 可覆盖）——自动完成：G1 MinIO health �?admin 登录 �?�?EXECUTOR Agent �?建任�?�?batch 建子任务并指�?�?agent claim（Bearer apiKey）→ submitResult 触发物化 �?等待 afterCommit 异步落桶 �?G2 附件列表存在 minio:// �?objectKey 匹配 `归属�?�?�?taskId/subTaskId/uuid8-文件名` �?G3 下载 200 + 非空 + Content-Disposition attachment + �?302。每次运行新建独�?Agent（名带时间戳），与既有数据零冲突�?
- **实现细节**：claim/submit �?REST 直�?`POST /api/mcp/tools/*`（免 MCP 握手）；submit �?output 用单行字符串构�?JSON（多行字符串�?zsh 命令替换中解析会失败，踩坑后改单行规避）�?

#### 3. 验证结果

- 真实环境（jar 启动 6565 + docker MinIO�?*9/9 �?PASS**：G1 健康 �?/ P1 �?Agent �?/ P2 claim �?/ P3 submit 触发物化 �?/ G2 附件落库（minio:// 1 条，objectKey=`minio-e2e-executor-{ts}/2026/08/{taskId}/{subTaskId}/d0173465-MinIO 附件物化验证子任�?md` 完全符合规范）✓ / G3 下载 200 + 90 字节 + Content-Disposition ✓�?
- 桶内实证：`docker exec helloai-minio mc ls -r local/helloai-artifacts/` 确认对象真实存在�?9B，路径与附件元数据一致）—�?*物化落桶全链路闭�?*�?

#### 4. 影响与遗�?

- 影响：① A0-5 遗留②完整闭环——minio:// 附件平台直读下载在真实环境实测通过，�?.75 遗留④关闭；�?获得可重复执行的 MinIO 附件回归脚本（macOS zsh 版），无需 LLM 凭证即可触发物化链�?
- 遗留：① 内部 LLM 执行物化（平台级凭证）未实测——配置任一 provider API Key 后可跑真�?LLM 执行任务复核；② 脚本每次运行会新增测�?Agent/任务/子任务（幂等设计，无清理动作）；�?空表单校验异�?HTTP 500 语义问题（�?.76 遗留，未扩散）�?
### 6.78 参数校验异常语义修复：@Valid 校验失败 500 �?400�?026-08-12�?

#### 1. 背景与结�?

- **背景**：�?.76/6.77 遗留③——`@Valid @RequestBody` 校验失败（如登录空密码）�?`MethodArgumentNotValidException`，此前无专门 handler，被 `GlobalExceptionHandler` �?`@ExceptionHandler(Exception.class)` 兜底�?HTTP 500，语义不准确（客户端参数问题不是服务端错误）。用户确认修复�?
- **勘察结论**：前�?`request.ts` 拦截器完全基�?body.code 判断�?00 成功 / 401�?03 特殊处理 / 其余 ElMessage.error(res.msg)），400 �?500 走同一分支—�?*HTTP 状态码变更对前端行为零影响**，且校验消息会直接展示（体验更准确）。项目未使用 `@Validated` 类级校验（无 ConstraintViolationException 场景），只需处理 MethodArgumentNotValidException�?

#### 2. 实现要点

- `GlobalExceptionHandler` 新增 `@ExceptionHandler(MethodArgumentNotValidException.class)`：HTTP 400 + `R.fail(400, 首条字段错误消息)`（取 FieldError.getDefaultMessage，如「凭证不能为空」；无字段错误时兜底「参数校验失败」）�?
- 同步更新 `scripts/shell/verify-login-e2e.sh`：[2] 空密码由宽松断言改为明确断言 HTTP 400 + body.code=400 + 消息含「凭证」；[1] 空用户名明确�?HTTP 500（username 字段无校验注解，�?type/credential 必填，空用户名走业务层「用户不存在或已禁用」——脚本注释说明字段注解边界，防止误判）�?

#### 3. 验证结果

- 手动验证：空密码 �?HTTP 400 + `{"code":400,"msg":"凭证不能为空"}`；空 type �?HTTP 400�?
- 回归：`verify-login-e2e.sh` 11 项全 PASS（[2] 新断言生效）；`verify-minio-artifact.sh` 9/9 �?PASS（物化链无回归，minio:// 附件累积 5 �?objectKey 均符合规范）�?
- 测试代码无依赖旧 500 行为的断言（grep 确认）�?

#### 4. 影响与遗�?

- 影响：全�?`@Valid` 校验失败统一返回 HTTP 400 + 具体字段消息（此�?500 + 兜底文案），错误语义与前端提示同时改善；日志�?error 级兜底变�?debug 级字段消息�?
- 遗留：无新增。�?.76/6.77 遗留③关闭；IllegalArgumentException handler 返回�?code=500 �?HTTP 400（body.code 与状态码不一致）为既有行为，未扩散�?
### 6.79 批次 B 收口：N11 失败计数语义确认 + isExecutionDense 误判率观察（2026-08-13�?

#### 1. 背景与结�?

- **背景**：a0-plan 批次 B 两项观察项——B1 疑点「trae-executor consecutive_failure_count=2 疑似把系统跳过审核计为外�?agent 失败」（§6.56/6.57 遗留）；B2 §6.52 引入的关键词启发�?`isExecutionDense` 误判率观察（误判会影响能力预检与回退方向）�?
- **B1 勘察结论（代码层�?*：`recordFailure/recordSuccess` 全仓库唯一调用点是 `ExecutionResultHandler.applyFailureTracking`（按 `report.isSuccess()` 分支，success=false �?block + recordFailure）。skip 类事件——`sub_task_auto_review_skip_max_rework`（SubTaskReviewService 审核侧）、`sub_task_fallback_skip_policy` / `sub_task_fallback_skip_need_human`（SubTaskDispatchService N11 回退侧）、`sub_task_dispatch_skip_no_capability`（ResilientDispatcher 分配预检侧）——分别产生于三个不同模块，均不经�?ExecutionResultHandler 入口�?*系统决策类事件不计入失败计数**，plan 疑点从代码路径上证伪�?
- **B1 数据实证（MCP 查库只读�?026-08-13�?*：① `sub_task_auto_review_skip_max_rework` 全库 2 条（2026-08-02/08-11 同一子任务），agent_id 均为 null（系�?REVIEWER 侧，�?agent 归属）；�?trae-executor�?086711950328950786）名�?timeline �?`sub_task_execute_failed` / `sub_task_execute_result_discarded` 事件，全�?`execute_failed` 最新仅 2026-07-16 历史测试数据�? 月无任何执行失败事件）；�?8-10~8-11 �?1 个实战任务（trade-cloud E2E�?087076754479771649），事件流全程成功（plan_generated→plan_confirmed�?×dispatch_prepare�?×execute_submit�?×auto_review_passed�?×artifact_materialized→task_auto_completed→final_report），trae-executor 5 次成功提交触�?recordSuccess 归零，当�?`consecutive_failure_count=0 / last_failure_time=null`；④ plan 疑点「计�?2」的历史来源已不可复现（现库无对应失败事件），与 skip 审核无因果关系�?
- **B2 勘察结论**：`isExecutionDense` �?4 个调用点（ResilientDispatcher 分配预检 / SubTaskDispatchService N11 回退预检 / SubTaskReviewService 提交者预检 + checkEvidence 竞态补偿等待窗口），判定文�?= content/acceptance/deliverable 拼接，EXECUTION_DENSE_PATTERN �?15 个关键词（`.ps1/.sh/.bat/.py/.jar` 词边�?+ docker/kubectl/npm run/mvn /gradle + 五个中文词）�?
- **B2 数据实证**：全�?65 个子任务�?2 条命中（3.1%）：①「环境冷启与基线确认」（2087076796930322434）命�?docker—�?*判定正确**（真实需本机 docker-compose 操作的任务；8-11 �?2 �?`sub_task_dispatch_skip_no_capability` 全部针对它，inner-loop-executor/probe-moonshot（API_KEY_LLM 无本机能力）被正确跳过，最终由 trae-executor（CLI_CLIENT）接手完成，链路符合设计意图）；②「编写README文档」（2083857076507279366）命中「启动服务」—�?*理论误判�?*（验收文案是描述性文本，本质文档写作任务），但该任务 8-02 创建、在 §6.52 预检上线前已完成分配（assigned inner-loop-executor �?DONE），未产生实际影响�?
- **落地决策**：两项均无需代码修复。B1 疑点证伪（skip 类不计入失败，计数语义与代码路径一致）；B2 真实样本判定正确、误判样本未产生实际影响，不加白/黑名单、不改配置化，保留观察�?

#### 2. 实现要点

- 纯勘�?+ MCP 查库（只读），零代码改动；全�?9 条只�?SQL（agent / task_timeline / task / information_schema）�?
- 结论回填：本迭代记录 + a0-plan.md 勾除 B1/B2�?

#### 3. 验证结果

- 代码层：`recordFailure/recordSuccess` grep 全仓库唯一调用点确认（ExternalAgentFailureTracker L57/L80 �?ExecutionResultHandler L313/L315 调用）；`isExecutionDense` 4 个调用点与事件名确认�?
- 数据层：skip 事件 agent_id=null 实证、trae-executor 零失败事件、计数归零与成功事件一一对应、dense 命中样本全部人工核验——证据链完整闭环�?

#### 4. 影响与遗�?

- 影响：① 批次 B 收口，a0-plan 剩余批次变为 C~H；② N11 失败计数语义明确——仅「执行结�?success=false」计入失败，平台自身决策（跳过审�?跳过回退/能力预检跳过）不计入，外�?agent 不会被系统决策误伤触发阈值回退�?
- 遗留：① B2 理论误判面（描述性文本含「启动服�?部署/docker」等词）仍在，样本量增大后如出现实际误伤，可用配置化�?黑名单处置；�?trae-executor 历史「计�?2」来源已不可复现（现库无对应失败事件），若再观察到计数与事件不匹配，需排查 recordFailure 调用链外来源（如直改 DB/旧版本残留）；③ 批次 B 结论未入差距表（观察类项，差距表无对应行）�?

### 6.80 C1 Provider 生态补全收口：协议工厂/Registry/目录服务单测 41 �?+ 路由大小写归一修复�?026-08-13�?

#### 1. 背景与结�?

- **背景**：a0-plan 批次 C1「moonshot/minimax/dashscope Provider Factory」——plan 原假设「yml 已预置三 provider 配置段但�?Factory 实现，目录接口标记不可用」。预检发现该假设已�?§6.52 方案 B（V46）取代：OpenAiCompatibleProtocolFactory / AnthropicCompatibleProtocolFactory 通用协议工厂已落地（Moonshot/DashScope/Minimax 三个专用 Factory 已删除），llm_provider �?4 provider 配置齐全，目录接口早已可用—�?*C1 子任�?1/2 为过时项**，真实缺口是 Provider 域零单测覆盖（协议工�?/ Registry 路由 / CatalogService 均无测试）�?
- **顺带修复判定不一致缺�?*：`LlmProviderChatClientFactoryRegistry` 路由侧对 protocol_type 精确匹配（`Collectors.toMap(ProtocolFactory::protocolType)`），�?`LlmProviderCatalogService.isFactorySupported` �?toUpperCase——DB 若写入小写协议类型会出现「目录显示可用、路由实际失败」的判定不一致。修复：Registry 新增 `normalizeProtocolType`（null 安全 + Locale.ROOT 归一），路由与目录判定口径统一为大小写不敏感�?

#### 2. 实现要点

- 代码修复 1 处：`LlmProviderChatClientFactoryRegistry` 路由改用 `protocolFactoryMap().get(normalizeProtocolType(provider.getProtocolType()))`�?
- 新增 4 个测试文件共 41 用例（JUnit5 + @Nested + @DisplayName + AssertJ + Mockito，项目范式）�?
  - `OpenAiCompatibleProtocolFactoryTest` 9 例：apiKey null/空白拒绝（BizException）；ChatClient 创建成功（OpenAiChatModel 类型断言 + model 三级兜底：请求�?> llm_provider.defaultModel > sys_config，verify 兜底层不触发）；平台 baseUrl 缺失回退 llm_provider.baseUrl 不抛错；yml 配置段缺失走默认超时；同四元组缓存复用（ChatModel 同实例，ChatClient 包装每次新建�? apiKey 隔离（size=2）�?
  - `AnthropicCompatibleProtocolFactoryTest` 9 例：同构（minimax，AnthropicChatModel 断言）�?
  - `LlmProviderChatClientFactoryRegistryTest` 6 例：provider 未找到抛 BizException；未知协议（GEMINI_NATIVE）抛 BizException；deepseek 专用 Factory 优先（协议工�?verify never）；OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE 分发到对应工厂；小写协议类型仍可路由（归一修复的直接验证）�?
  - `LlmProviderCatalogServiceTest` 17 例：listProviders factorySupported（deepseek code 特判 / 已知协议 / 未知协议 / protocolType null 拦截 / providerCode 小写归一）；available = enabled && apiKeyConfigured && factorySupported 组合（enabled=0、key 未配置两分支）；isProviderAvailable（null/blank 拒绝、大小写不敏感匹配、不可用返回 false）；bindPlatformApiKeyIfAbsent 四分支（不可用抛�?/ 已有 ACTIVE 凭证幂等 false / 平台 Key 缺失抛错 / 正常绑定 verify bindAgentApiKey 五参�?remark）；provisionPlatformCredential（modelType 空回退 execution.provider / modelType 前缀解析 / 不可用静默跳过不抛错）�?
- 顺带修复 §6.75 用户 MinIO 改动引入的既有测试回归：`CompositeArtifactStorageTest.shouldDispatchLoadByPrefix` �?stub `supports()`（实现改为按 supports 分派�?mock 默认 false 导致「无存储实例支持该地址」）�?�?2 �?supports stub�?

#### 3. 验证结果

- `mvn test -pl helloai-core -am -DskipTests=false`�?*592/592 全绿**（C1 新增 41 + 既有 551 回归，含 §6.75~6.78 存储/登录相关测试）；注意�?pom 默认 `skipTests=true`，跑测试需显式 `-DskipTests=false`；本�?helloai-common 需 `-am` 从源码构建（本地仓库 jar �?install，直�?`-pl helloai-core` 会引用旧 common �?12 个编译错误）�?
- 真实环境（java -jar 启动 6565，profile=local）：登录 admin �?`GET /api/admin/agents/listLlmProviders` 实测 4 provider 全部返回——deepseek/moonshot/dashscope（OPENAI_COMPATIBLE�? minimax（ANTHROPIC_COMPATIBLE），**factorySupported=true、available=true �?4/4**；apiKeyConfigured 已全�?true（平台级 Key 已配置，优于勘察时「全部未配置」预期，可用性全绿）�?

#### 4. 影响与遗�?

- 影响：① 批次 C �?C1 收口，a0-plan C1 标记已收口（子任�?1/2 标注过时项）；② Registry 路由与目录可用性判定口径统一（协议类型大小写不敏感，消除 DB 小写写入的隐性不一致）；③ Provider 域单测从 0 �?41，后续新增厂�?改协议路由有回归护栏�?
- 遗留：① plan C1 子任�?1/2（「补三个 Factory」）为过时项，由 §6.52 方案 B 交付，不重复建设；② `LlmProviderChatClientFactoryRegistry.protocolFactoryMap` �?volatile 懒初始化，极端并发首路由存在重复构建（结果一致、无业务影响），后续可改初始化钩子；�?本轮代码（Registry 修复 + 4 测试 + 1 测试修复）与 §6.79/§6.80 文档�?git 提交，待用户确认后提交；�?后端实例已由本轮回填验证启动�?565），用户可直接使用�?

### 6.81 C2 credential_vault 迁移收口：盘�?读取优先级单�?17 �?+ 权限颗粒度审�?+ 孤儿凭证清理 SQL�?026-08-13�?

#### 1. 背景与结�?

- **背景**：a0-plan 批次 C2「credential_vault 迁移收口」——N10 部分落地（最小模�?绑定/托管已具备），迁移、过渡期双活策略与权限颗粒度未收口�?
- **盘点结论（代�?+ MCP 查库实证�?*：① **无明文密钥存量需迁移**——`agent.api_key` �?V1 起就是工�?consumerToken（`AgentService.register` 自动签发，V1 列注释「API_KEY_LLM 不存真实 LLM 凭证」），`sys_config` �?llm/provider/api 键，真实 LLM Key 全部 AES-GCM 加密存于 `credential_vault`（无散落明文）；�?**读取路径已全�?vault �?*——`ApiKeyAgentExecutor` / `AgentExecutionConnectivityService` �?`CredentialVaultBindingService.getAgentApiKeyPlaintext`（secretRef 环境变量优先 > encrypted_value 解密，无 vault 返回 null，`requireVault=true` 时直接拒绝，**�?agent.api_key 回退**）；`AgentSelector` / `PlannerAgentPicker` �?`hasActiveAgentCredential` 过滤候选；�?**过渡期双活仅存在于平台级**——`PlatformProviderConfigService.getApiKey` = vault PLATFORM �?ACTIVE 凭证（secretRef > encrypted_value�? yml 兜底 > null（�?.52 已实现）；④ **存量凭证 77 �?*（deleted=0）：PLATFORM 4 ACTIVE�? provider 系统设置页写入）+ AGENT 73（ACTIVE 44 + DISABLED 29）；�?**孤儿凭证 61 �?*�?4 ACTIVE + 27 DISABLED，owner 已物理删除未清理，多�?verify-* e2e 临时 agent 与历史轮换链残留）——本轮治理项；⑥ 现存 agent 凭证 12 条（10 ACTIVE 覆盖全部 10 �?API_KEY_LLM agent + 2 DISABLED 轮换残留），**无「API_KEY_LLM �?vault」执行缺�?*�?

#### 2. 实现要点

- **读取优先级单�?17 �?*（锁�?C2 验收「读取路径单测覆盖优先级」）�?
  - `CredentialVaultBindingServiceTest` 6 例（Agent 级读取语义）：无 ACTIVE vault 返回 null 不回落兜底；secretRef 环境变量优先（用系统必然存在�?PATH 验证，Mockito 禁止 mock System 静态方法）；secretRef 空环境变量抛 BizException（fail-close 不回退 encrypted_value）；encrypted_value 解密路径；vault 缺双值抛错；bindAgentApiKey 加密 + 五参透传�?
  - `PlatformProviderConfigServiceTest` 11 例（平台级过渡期双活）：vault encrypted_value 优先�?yml；vault secretRef 环境变量优先；vault secretRef 空环境变量回退 yml 不抛错；�?vault 回退 yml（老环境平滑迁移）；双无返�?null；isApiKeyConfigured 三态；isApiKeyFromVault；maskApiKey �?4 位脱�?/ null�?
- **权限颗粒度审计收�?*（结论性，不新开接口）：`/api/**` 全量鉴权（AuthInterceptor：admin token �?agent Bearer，无凭证 401）；`CredentialController`（bind/listByAgentId）全�?`requireAdmin()`；平台级凭证管理（AdminLlmProviderController / AdminProviderConfigController）走 admin 拦截器；**MCP 工具集零凭证接口**（外�?agent 无密钥读写通道）；执行链内部读取按 owner 隔离（`getActiveAgentApiKey(agentId, provider)` 仅查�?owner）；API_KEY_LLM 自助注册仅触发平�?key 副本绑定（`provisionPlatformCredential`，托管语义，agent 不拿明文）。结论：vault 读写权限已按「仅管理�?+ 执行�?owner 维度」收口，�?agent 侧越权通道；「按 owner/角色开放自助管理」明确不做（托管语义，防明文外流）�?
- **孤儿凭证清理 SQL**（写操作，交付用户执行，AI 不代执行）：逻辑删除 61 条孤儿凭证（NOT EXISTS agent 表，owner_type=AGENT），清理后剩�?12 条现�?agent 凭证�?0 ACTIVE + 2 DISABLED 轮换链，保留审计）�?

#### 3. 验证结果

- 定向：`mvn test -pl helloai-core -am -DskipTests=false -Dtest=CredentialVaultBindingServiceTest,PlatformProviderConfigServiceTest` **17/17 全绿**�?
- 全量回归：`mvn test -pl helloai-core -am -DskipTests=false` **599/599 全绿**（新�?17 �?+ 既有 582 回归，含 C1 �?41 �?Provider 域用例）�?
- 踩坑记录：① Mockito **禁止 mockStatic(System.class)**（class loading 冲突�?infinite loops），secretRef 用例改用系统必然存在�?PATH 环境变量断言；② Node fallback shell 下带点号�?`-Dsurefire.failIfNoSpecifiedTests=false` 必须整体加引号，否则�?PowerShell 拆成未知 lifecycle phase�?

#### 4. 影响与遗�?

- 影响：① 批次 C 全部收口（C1 §6.80 + C2 本轮），a0-plan 剩余批次�?D~H；② N10 由「部分落地」推进为「已收口」——迁移无需做（无明文存量，双活已实现且单测锁定）、权限颗粒度审计闭环（admin-only + owner 隔离 + MCP 零暴露）、存量治理交付清�?SQL；③ vault 读取优先级从此有 17 例回归护栏（Agent 级无兜底 / 平台�?vault > yml）�?
- 遗留：① **清理 SQL 待用户执�?*�?1 条孤儿凭证逻辑删除，SQL 见差距表 N10 增量条目/汇报）；�?现存 agent �?2 �?DISABLED 轮换残留保留（审计链语义）；�?本轮新增 2 个测试文件与 §6.81 文档�?git 提交，与 C1（�?.79/§6.80）一并待用户确认后提交；�?后端实例仍在运行�?565），用户可直接使用�?

### 6.82 批次 D REVIEWER 自动审查 L2 MQ consumer 补齐：MqReviewCommandConsumer + 核验互斥锁防双审�?026-08-13�?

#### 1. 背景与结�?

- **背景**：a0-plan 批次 D「REVIEWER 自动审查 L2 MQ consumer（M9 遗留）」。审查链三级容错（�?.40 架构）——L1 `SubTaskSubmittedForReviewEvent` AFTER_COMMIT + @Async 主路径、L2 MQ `agent.reviewer.assigned` �?`reviewerQueue`（�?.49 遗留：无 consumer）、L3 `@Scheduled` DB 孤儿扫描兜底；L2 缺失�?MQ 备份路径悬空，L1 事件链丢失时只能�?L3 �?60s 阈值窗口�?
- **勘察结论**�?
  - 生产侧已存在：`AgentOutboxService.createEvent`（REVIEW �?routing_key=`agent.reviewer.assigned`）→ `AgentEventCompensationTask`（helloai-job�?5s 轮询 + Redis 锁）�?`DomainEventPublisher` �?`agentExchange`；`reviewerQueue` 已绑�?`agent.reviewer.*` 通配�?+ DLX 死信（`RabbitMQConfig`），消费侧零代码（全库无 MqReviewCommandConsumer）�?
  - **生产侧缺�?*：payload �?eventId（仅 subTaskId/taskId/status/agentId），无法支撑「同事件重投不重复消费、同子任务多�?REVIEW 各自独立」的消息级幂等�?
  - **双审风险**：L1/L2/L3 三路并发触发同一子任务核验时，`reviewSubTask` 的「getById 读状态」防重在 LLM 调用窗口（数秒）内不互斥，并发下可能双审（双 LLM 调用 + 判定竞态）�?
- **结论**：补 L2 consumer + 生产�?eventId 幂等�?+ 核验互斥锁，三级容错闭环�?

#### 2. 实际落地

- **生产�?*：`AgentOutboxService.createEvent` payload �?`eventId`�? 处，向后兼容——老消息无 eventId 时消费侧回退幂等键）�?
- **消费�?*：新�?`helloai-core/.../review/mqconsumer/MqReviewCommandConsumer`�?45 行，`@ConditionalOnProperty("helloai.mq.review.consumer-enabled")`，yml 默认 true）：
  - `@RabbitListener(queues=REVIEWER_QUEUE, ackMode="MANUAL")` 解析 payload Map �?`tryConsume(messageId, "MqReviewCommandConsumer", () -> subTaskReviewService.reviewSubTask(subTaskId, agentId))`�?
  - 幂等键：payload.eventId 优先（新消息），回退 `sub_task.review:{subTaskId}`（老消息）�?
  - MANUAL ACK 语义�?`MqExecutionCommandConsumer`：解析失�?�?subTaskId �?ACK；消费失�?�?NACK(requeue=false) �?DLX�?
  - agentId=0（null 占位）归一�?null；Jackson 反序列化小整数统一 toLong�?
- **防双�?*：`SubTaskReviewService.reviewSubTask` 拆壳 + `doReview` 主体，入�?Redis `setIfAbsent("review:lock:"+subTaskId, ttl=120s)` 互斥，finally 释放——L1/L2/L3 三路并发仅一路进�?LLM 核验窗口（TTL 兜底崩溃残留）�?
- **配置**：application.yml `helloai.mq.review.consumer-enabled: true`（默认开启，对齐 execution-command 范式）�?

#### 3. 验证结果

- 定向：`mvn test -pl helloai-core -am -DskipTests=false -Dtest=MqReviewCommandConsumerTest,SubTaskReviewServiceTest` **30/30 全绿**（MqReviewCommandConsumerTest 7 + SubTaskReviewServiceTest 23）�?
  - `MqReviewCommandConsumerTest` 7 例：正常消息（eventId 幂等�?+ reviewSubTask(11,22) + ACK�? 老消息回退幂等�?/ agentId=0 归一 null / �?JSON ACK / �?subTaskId ACK / 幂等命中直接 ACK / 核验异常 NACK→DLX�?
  - `SubTaskReviewServiceTest` 新增 3 例：锁占用跳过（不调 LLM/getById/complete + 不删他人锁）/ 正常核验 finally 释放�?/ LLM 异常锁仍释放�?
- 全量回归：`mvn test -pl helloai-core -am -DskipTests=false` **609/609 全绿**（C2 599 + D 新增 10）�?
- 踩坑记录：锁占用用例首版 stub �?`subTaskService.getById` 触发 UnnecessaryStubbing（锁未持有成功根本不读子任务）→ �?stub + �?`verify(subTaskService, never()).getById(anyLong())` 正向断言�?

#### 4. 影响与遗�?

- 影响：① 批次 D 收口，a0-plan 剩余批次 E~H；② 三级容错 L2 补齐——L1 事件链丢失时 Outbox 补偿投递（15s）即触发核验，不再等 L3 �?60s 阈值窗口；�?核验互斥锁覆�?L1/L2/L3 三路，消�?LLM 双审竞态；�?eventId 幂等键使同事件重投不重复消费、同子任务多�?REVIEW（返工后再次提交）各自独立核验�?
- 遗留：① reviewerQueue 绑定 `agent.reviewer.*` 通配符，未来若新增其�?reviewer 路由消息需评估消费语义；② �?TTL 120s �?LLM 调用超时（sync-timeout-seconds 600s）不匹配——LLM 调用�?120s 时锁提前过期，极端场景仍可能双审（后续可把锁 TTL 提到与超时同量级）；�?本轮代码（AgentOutboxService 1 �?+ MqReviewCommandConsumer 新建 + SubTaskReviewService 互斥�?+ yml + 2 测试文件）与 §6.82 文档�?git 提交，待用户确认后提交；�?真实环境 MQ 链路（reviewerQueue 消费 + event_consumption_log 记录）待后端重启后实测�?

---

### 6.83 批次 E1 动�?TTL 自适应：AgentDutyLeaseProperties + resolveTtlMinutes/adaptiveRenew + S7 实测�?026-08-13�?

#### 1. 范围

- a0-plan 批次 E1（N12 A2 �?2 段）：租�?TTL 不再静态固定，�?Agent 表现与在跑任务动态调整；配置化开关�?
- 明确不做：策略落库（plan 未要求，score→TTL 映射为确定性纯函数）；TTL 变更落审计（沿用租约表原有字段）�?

#### 2. 实际落地

- **配置**：新�?`AgentDutyLeaseProperties`（`helloai-common/config/`，prefix=`helloai.agent.duty-lease`）——adaptive-ttl-enabled（默�?true�? min-ttl-minutes=5 / max-ttl-minutes=240 / default-ttl-minutes=30 / full-score=100；application.yml `helloai.agent` 下新�?`duty-lease` 配置段�?
- **服务**：`AgentDutyLeaseService` 新增两个方法�?
  - `resolveTtlMinutes(agentId, explicitTtlMinutes)`：显�?TTL 优先；否则按 agent.score 线性映�?[0,fullScore]→[min,max]；无 score �?consecutive_failure_count×20 折算表现分；开关关�?/ agent 缺失回退 default�?
  - `adaptiveRenew(agentId)`：无 ACTIVE 租约返回 null；有在跑子任务（`SubTaskMapper.selectInFlightByAgent`，ASSIGNED/IN_PROGRESS/REWORK）→ 最大窗口（任务在跑延长）；空闲 �?`resolveTtlMinutes` 动态窗口（空闲缩短）�?
- **MCP 联动**：`McpToolService.checkIn` 未传 ttlMinutes 时改�?`resolveTtlMinutes`（不再固�?30）；A0-8 工具自动续租 `refreshDutyLease` 改调 `adaptiveRenew`，删�?`DEFAULT_RENEW_MINUTES`/`MAX_RENEW_MINUTES` 常量�?
- **脚本**：`verify-agenthub-duty-e2e.ps1` 追加 S7 场景（S7.0 score 复位起点 / S7.1 score=0 �?断言窗口 [3,8]min / S7.2 score=100 �?断言窗口 [236,244]min / S7.3 checkOut + score 复位）；顺带修复 admin agents 列表接口失配（`GET /api/admin/agents` �?`GET /api/admin/agents/list`，`pageNum` �?`page`）�?

#### 3. 验证结果

- 单测：新�?`AgentDutyLeaseAdaptiveTtlTest` **12/12 全绿**（显�?TTL 优先 / score=100�?40 / score=0�? / score=50�?22 / �?score 零失败→240 / �?score 失败 5 次→5 / 开关关�?0 / agent 缺失�?0 / �?ACTIVE→null / 在跑�?40 / 空闲高分�?40 / 空闲低分�?）；`McpToolServiceTest` A0-8 用例适配 adaptiveRenew 语义�?**22/22 全绿**�?
- 全量回归：`mvn test -DskipTests=false` **341/341 全绿**�?0 个测试类，FAIL=0 ERROR=0）�?
- 真实环境：`verify-agenthub-duty-e2e.ps1` **ALL PASSED**（S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / S6 N12-P1 STRICT 回归 / **S7 动�?TTL：score=0 �?~5min 短窗口、score=100 �?~240min 长窗�?*）�?
- 踩坑记录�?
  - 脚本 lookup 405：列表接口早已迁�?`/api/admin/agents/list`，脚本仍调根路径 GET �?修正两处 URL 后通过�?
  - RabbitMQ 积压历史 Java 序列化消息（Phase 2F 修正�?`convertAndSend(POJO)` 产物）导致新进程启动�?`SecurityException: Attempt to deserialize unauthorized class java.util.LinkedHashMap` 无限 requeue 循环——该异常发生在消息转换层（listener 方法体之前），代码内「坏消息直接 ACK」兜底接不住；处理：停消费�?�?purge 积压队列（reviewer 155 / executor 599 / planner 186 / dlx 1）→ 重启，队列全清零�?

#### 4. 影响与遗�?

- 影响：① 批次 E1 收口，a0-plan 剩余批次 E2~H；② checkIn 动态窗�?+ 续约自适应——低表现 Agent 5min 短窗口快速回收值班态，高表�?Agent 240min 长窗口减少续约开销；③ 续约语义由「固�?30min」升级为「score/在跑任务自适应」，A0-8 工具调用自动续租链路无感知兼容�?
- 遗留：① score→TTL 映射为线性纯函数未落策略表（plan 未要求，可后续演进）；② 本轮代码（AgentDutyLeaseProperties 新建 + AgentDutyLeaseService/McpToolService 修改 + yml + 单测 + 脚本）与 §6.83 文档�?git 提交，待用户确认后提交；�?A2 �?3 段（concurrency 预扣）为 a0-plan E2，待续；�?RabbitMQ 若再次出现旧格式积压消息（如重放历史测试消息），需先停消费者再 purge，无法在线清 in-flight�?

---

### 6.84 批次 b0-b4：Service 接口/impl 分层拆分重构�?026-08-13�?

#### 1. 范围

- **背景**：core �?Service 层长�?类即服务"（`XxxService` 直接�?`@Service` 类，不拆接口），跨域引用�?Controller 直接依赖具体类，测试只能 mock 类本身。按分层契约与可测试性目标，启动 Service �?接口 + impl"成对拆分（CODE_STYLE §4.x/§7.1 v2.8 起强制）�?
- **批次规划**：b0 盘点引用�?+ 组件扫描范围 + 测试结构；b1 system �?13 拆；b2 task 域（11 + spec 3 拆、policy/migrator 归位）；b3 planner+review 域（4 拆、picker/router 归位、search 迁移）；b4 agent 域（10 移入�?+ 10 现有�?+ 3 归位）�?
- 明确不做：本次不新增任何业务功能、不改数据库结构；b4 �?10 现有�?�?b5（shared DoorbellService + mq MessageDeduplicationService 拆）留待后续批次�?

#### 2. 实际落地

- **拆分形�?*（统一范式）：接口 `XxxService` �?`{domain}.service`（继�?`IService<Entity>`），实现 `XxxServiceImpl` �?`{domain}.service.impl`（继�?`ServiceImpl<Mapper, Entity>`，`@Service` + `@RequiredArgsConstructor` 构造器注入）；Controller 与跨域引用全部改依赖接口�?
- **b1 system �?13 �?*：AdminDashboard / Attachment / Auth / CredentialVaultBinding / CredentialVault / Dashboard / LlmProviderQuery / LlmProvider / Module / PromptTemplate / Rule / SysConfig / SysUser 全部拆接�?+ impl�?
- **b2 task 域（11 + spec 3 �?+ 归位�?*：ActivityLog / Feed / Review / Reward / SubTaskDispatch / SubTask / TaskDeliverable / TaskFinalReport / TaskIteration / Task / TaskTimeline 11 个拆接口 + impl；`task/spec` �?TaskRunningSpecService / TaskRunningSpecJsonbService / TaskRunningSpecTableService 三拆合一迁至 `task/service`（`TaskRunningSpecService` 接口 + 2 �?impl）；`TaskAgentPolicy` �?`task/service` 归位 `task/policy`（纯静态策略工具类，测试类同步随迁）�?
- **b3 planner + review �?*：PlannerAnalysis / RequirementClarify / WebSearch / WebSearchServiceRouter 4 拆（`planner/service` + impl）；search 两实�?BochaWebSearch / TavilyWebSearch 迁移�?`planner/service/impl`（`planner/search` 仅剩 WebSearchResult 值对象）；RequirementConversation / RequirementMessage 2 个已接口�?service �?impl；PlannerAgentPicker 归位 `planner/picker`；SubTaskReviewService 归位 `review/service` + impl�?
- **b4 agent �?10 移入�?*：从 chat（AgentChatClient / LlmProviderCatalog / PlatformProviderConfig）、command（ExecutionCommand）、execution（PlatformAgentExecution / SubTaskExecution）、observability（CircuitBreakerAlert / Heartbeat）、output（ExecutionArtifact）、mcp（McpTool）六个散包子包统一移入 `agent/service` + impl，与 agent/service 既有 11 个接口汇合，业务引用全部改接口�?
- **测试调整**：全部相关测�?import 迁移 + `spy(new XxxServiceImpl(...))` 构造改接口依赖；task/policy 测试随迁；新�?`AttachmentServiceImplTest`（�?.85）�?

#### 3. 验证结果

- `mvn -q compile -pl helloai-start -am -DskipTests` 7 模块 EXITCODE=0 全绿�?
- 批次 4b import 修复闭环定向 **201 tests 全过**（含 lambdaQuery 链式 mock 先例：`doReturn(chain).when(service).lambdaQuery()` + `orderByDesc` �?`ArgumentMatchers.<SFunction<T, ?>>any()` 消歧义）�?
- 全量回归（b6�?41/609 级）留待 b4 剩余 + b5 完成后一并执行�?

#### 4. 影响与遗�?

- 影响：① Service 层分层契约落地——跨域引用与 Controller 只依赖接口，impl 可独立测试（mock 接口而非 mock 类）；② 代码结构收口——`{domain}.service.impl` 成为唯一业务逻辑实现位，chat/command/execution/observability/output 散包子包�?Service 全部归位；③ CODE_STYLE v2.8 同步：�?.x 业务域分包（6 域实际子�?+ service.impl 语义）、�?.1 包命名、�?.2 类命名、�?.x 接口使用原则（Service 层改为强制）、�?.1/7.2 标准编写模式、�?0 校验清单、�?1.2 Service 实现测试规则�?
- 遗留：① b4 剩余"10 现有�?（agent/service 既有单类形�?Service：AgentExecutionConnectivityService / AgentExecutionPreviewService �?11 个中�?10 个）�?3 归位"未做，b4 批次未完全收口；�?b5（shared DoorbellService + mq MessageDeduplicationService 拆）待续；③ b6 全量回归�?b4/b5 完成后执行；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.85 附件管理双分类逐级下钻：MinIO/本地两类文件�?+ 任务/子任务标题回显（2026-08-13�?

#### 1. 范围

- **背景**：附件管理页原为单层表格，无法按存储类型浏览 MinIO 产物层级（A0-5 遗留②的浏览侧缺口）。用户拍板方案：附件分两类顶级文件夹（MinIO 附件 / 本地附件），逐级下钻（Windows 资源管理器式点击跳转，非树展开），主任�?子任务目录虽�?ID 存储、回显用标题（name）�?
- **明确不做**：MinIO 浏览器方案（已否决）、附件删�?移动、存储类型迁移�?

#### 2. 实际落地

- **后端**：`Attachment` 实体 +3 transient 回填字段（`taskId` / `taskTitle` / `subTaskTitle`，`@TableField(exist=false)` 不落库）；`AttachmentServiceImpl.list` 批量回填——listByIds �?SubTask（Set 去重）→ �?listByIds �?Task，Map 装配标题，无 N+1，子任务已删容错留空；新�?`AttachmentServiceImplTest` 3 例（空列表不查询 / 标题回填断言 / 子任务已删容错）�?
- **前端**：`types/index.ts` Attachment +3 可选字段；`AttachmentList.vue` 重写为面包屑 + 逐级下钻——根视图「MinIO 附件 / 本地附件」两固定文件夹（�?storageUrl 前缀计数，无数据不显示）�?agent �?年月 �?任务标题（ID 灰色副文本）�?子任务标题（ID 灰色副文本）�?文件行下载；兼容 minio 6 段新格式�?local 3 段老格�?objectKey；文件夹显示计数�?
- **顺带修复**：下�?404 �?bug——原 `/api/attachments/{id}/download` 后端实际端点只有 `/downloadById/{id}`，已改正确路�?+ `saveBlobResponse` 落盘�?

#### 3. 验证结果

- 后端：`mvn -pl helloai-core -am test -Dtest=AttachmentServiceImplTest` **Tests run: 3, Failures: 0, Errors: 0**（踩坑：`service.list(null)` 重载歧义需 `(Long) null` 强转；`orderByDesc` 需显式 SFunction 泛型；stub 参数 List 与实现实�?Set equals �?false 需 `any()`）�?
- 前端：`npx vue-tsc -b --force` 0 错误�?
- 真实环境：用�?IDEA 重启后端后刷新附件管理页验证（标题回显依赖新代码生效）�?

#### 4. 影响与遗�?

- 影响：① 附件管理从单层表格升级为存储类型可感知的层级浏览，MinIO 产物可逐级定位下载；② 任务/子任务目录回显标�?+ ID 副文本，�?§6.75 objectKey 规范（ID 锚点）互补；�?下载路径 bug 闭环�?
- 遗留：① 真实环境页面效果待用户验证（后端需重启加载�?list 回填逻辑）；�?b6 全量回归时一并回归附件相关用例；�?本轮代码与本文档�?git 提交，待用户确认后提交�?


### 6.86 E2 并发额度派发即占用：ConcurrencyQuotaService + 选人�?落库双防线（2026-08-13�?

#### 1. 范围

- **背景**：N12 A2 �?3 段（E2）——checkIn 声明�?maxConcurrent 仅记录在租约上，派发链从不读取，Agent 可被无限并发派发。目标语义：派发即占用额度、完�?改派/回收自动释放、选人跳过满额 Agent。方案经多轮论证收敛�?DB 实时统计一条线"（额度判定属写时判定数据，不建缓存、不双删、不引入 Redis/分布式锁；企业版 Redis 预扣留接口位）�?
- **明确不做**：Redis/Redisson 实现（仅�?`ConcurrencyQuotaService` 接口位）；死信人工指派不受额度约束（人工兜底例外）；前端展示；b6 全量回归�?

#### 2. 实际落地

- **接口**：`ConcurrencyQuotaService`（agent/service）——`inFlightCount`（在飞占用）/ `resolveQuota`（额度，null=不限制）/ `canAccept` 默认判定�?
- **默认实现**：`InFlightDbQuotaService`（agent/service/impl）——占�?= `SubTaskMapper.countInFlightByAgent`（ASSIGNED/IN_PROGRESS/REWORK，与 E1 租约在飞同口径）；额度优先级：ACTIVE 租约 maxConcurrent（值班承诺�? capabilities 显式 `maxConcurrentTasks`（能力声明，无租约时生效�? null（不限制，与 E2 前行为完全兼容）�?
- **Mapper**：`SubTaskMapper.countInFlightByAgent`（COUNT 变体）；`AgentMapper.selectByIdForUpdate`（FOR UPDATE 行锁）�?
- **选人�?*：`AgentSelector.pickFromCandidates` 过滤链新增额度过滤（requireIdle 之后、ACTIVE 之前），满额 Agent 跳过；`enforceMaxConcurrent=false` 跳过本检查�?
- **落库原子防线**：`SubTaskServiceImpl.assignNext` 在状态校验后、changeStatus �?`selectByIdForUpdate(agentId)` �?agent �?�?同一 Agent 并发派发�?PostgreSQL 行锁上串行化（多实例同样成立）→ 锁内重新 `canAccept` 判定，满额抛 `AgentUnavailableException`（不计熔断统计，ResilientDispatcher �?fallback 换人；并发窗口下 fallback 内仍满额则异常冒泡，任务保持 PENDING 由定时兜底重试）�?
- **配置**：`AgentDispatchProperties.enforceMaxConcurrent`（默�?true�? yml `dispatch.enforce-max-concurrent: true`�?
- **释放语义**：DB 实时统计天然覆盖——完�?取消/死信（终态不占）、回收（`resetToPendingForDispatch` �?assigned_agent_id）、改派（assigned_agent_id 迁移）、租约过期（resolveQuota 回退 capabilities/null），无需显式 release 钩子�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=...` 5 测试�?**78 tests 全过**：新�?`InFlightDbQuotaServiceTest`�?1 例：租约优先/capabilities 数字/字符�?未声�?agent 不存�?非数�?+ 占用边界）、`SubTaskServiceQuotaTest`�? 例：满额拒派不落�?未满正常 ASSIGNED/开关关闭放�?状态校验先于加锁）、`AgentSelectorTest` �?E2 额度过滤 3 例（满额跳过/未满选中/开关关闭）；HandoverTest 11 + IsReadyTest 8 回归�?
- 踩坑：pom 默认 `skipTests=true`，跑测试需 `-DskipTests=false`；surefire 3.2.5 多模块指�?`-Dtest` 需 `-Dsurefire.failIfNoSpecifiedTests=false`；Mockito STRICT_STUBS �?setUp 公共 stub 需移入实际用到的用例（UnnecessaryStubbingException）�?

#### 4. 影响与遗�?

- 影响：① 有租�?Agent �?maxConcurrent �?记录"变为"强制"（选人跳过 + 落库拒派双防线）；② 无租约且未显式声�?maxConcurrentTasks �?Agent 行为完全不变（向后兼容）；③ 企业版可替换 Redis 预扣实现而不动调用方�?
- 遗留：① 并发窗口�?fallback 内仍满额时异常冒泡边界（任务�?PENDING 由定时兜底，可接受，已注释标注）；② b6 全量回归待做（本轮已�?5 测试类定向回归）；③ 本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.87 E2 b6 全量回归脚本落地：PS 版补 S8 场景 + shell 全量版（2026-08-13�?

#### 1. 范围

- **背景**：b6 全量回归（E2 并发额度场景）此前只�?PS �?S1-S7，缺 S8（并发额度派发即占用）；且无 macOS/Linux 可跑�?shell 版。本轮：�?PS 版补 S8 场景；② 新建 `scripts/shell/verify-agenthub-duty-e2e.sh`（S1-S8 全量 zsh 版），与 §6.86 �?E2 实现配套�?
- **明确不做**：真�?AI 接入（脚本用模拟 CLI_CLIENT Agent，无需外部 AI）；�?b6 场景的其他回归项�?

#### 2. 实际落地

- **PS �?*（`verify-agenthub-duty-e2e.ps1`）：�?`Invoke-Json` 扩展 DELETE �?body（`SendAsync(HttpRequestMessage)` 兼容 PS 5.1，因 HttpClient �?`DeleteAsync(Uri, HttpContent)` 重载）——任务级联删除接口需 `confirmTitle`；② S7 后插�?S8 段落（S8.0 残留清理 �?S8.1 checkIn(maxConcurrent=1) �?S8.2 �?t1 白名单自动派发（�?auto-assign-on-create 行为自检）→ S8.3 �?t2 断言满额保持 PENDING �?S8.4 submitResult 释放后建 t3 断言重派�?�?S8.5 并发�?t4/t5（Start-Job）断言在飞�?<=1 �?S8.6 checkOut + 任务级联删除）；�?teardown 与头部注释同步更新�?
- **shell �?*（新�?`verify-agenthub-duty-e2e.sh`�?32 行）：S1-S8 全量 zsh 迁移（UTF-8 编码�?+ `set -euo pipefail` + jq 解析 + `run_psql_one_row` eval 导出换行字段数组 + `http_request` 全局 HTTP_CODE/HTTP_BODY + 后台 curl 并发 + trap cleanup），风格对齐 `verify-dashboard-duty-leases.sh`�?
- **S8 关键设计**：① 白名单隔离——任�?body �?`agentPolicy.executorAgentIds=[�?agent]`，选人链只在白名单内，环境其他 ACTIVE Agent 不干扰断言；② 前置条件 `auto-assign-on-create=true`（默�?false�? 脚本行为自检（t1 创建 2s 未派发则报错提示改配置）；③ 断言�?DB 为准（满额时 pickPreferred 返回 null �?BizException，HTTP 可能 500）；�?S8.0 残留清理�?COALESCE 子查询保�?psql 恒返回一行�?

#### 3. 验证结果

- shell：`zsh -n` 语法通过；`chmod +x` 已设�?
- PS：无 pwsh 环境（macOS），�?UTF-8 with BOM + 编码强制�?+ 去字符串后括号配对粗检（{} / () / [] 全配对），完整语法需 Windows/pwsh 实测时确认�?
- **真实环境实测�?026-08-13�?*：docker 中间�?+ fat jar 启动后端（`--helloai.dispatch.auto-assign-on-create=true` 启动参数覆盖，未改配置文件）�?`./scripts/shell/verify-agenthub-duty-e2e.sh` **ALL PASSED**（S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / S6 N12-P1 STRICT / S7 E1 dynamic TTL / S8 E2 concurrency quota�?7 项断言 0 错误）。S8 关键验证点全绿：t1 白名单自动派发、t2 满额保持 PENDING（选人链软跳过）、submitResult 释放�?t3 重派、并�?t4/t5 双请�?HTTP 500 �?DB 在飞数恒 �?（FOR UPDATE 原子防线）�?
- **实测发现�?shell �?bug（已修）**：S8.0 残留清理�?COALESCE 空串技巧失效——无残留�?psql 输出空行，被 `run_psql_one_row` �?`awk 'NF && ...'` 过滤�?无结�?导致 `fail "psql returned empty result"`。修复：COALESCE 改哨兵�?`'NONE'`（PS 版用 `if ($s80Line)` 判空无此问题，未改）；另 `fail()` 增加 `print ... >&2`——命令替换内 fail �?stdout 被捕获，只有�?stderr 外层日志才可见（本次排错盲区的根因）�?

#### 4. 影响与遗�?

- 影响：① b6 全量回归（S1-S8）在 Windows �?macOS/Linux 均有脚本可跑；② E2 并发额度场景具备可重复、环境无关的回归验证手段；③ 实测确认 E2 双防线（选人链软跳过 + FOR UPDATE 原子防线）在真实环境行为与单测一致�?
- 遗留：① PS 版真实环境实测待�?Windows/pwsh 环境时执行；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.88 批次 b4 收口 + b5：agent �?10 现有�?+ 3 归位 + shared/mq 2 �?+ b6 全量回归�?026-08-14�?

#### 1. 范围

- **背景**：�?.84 遗留①（b4 剩余"10 现有�?�?3 归位"）与 b5（shared DoorbellService + mq MessageDeduplicationService 拆）本轮全部收口，b4 批次完全关闭；b6 全量回归补跑（此�?§6.84/6.85 仅做了模块编译与定向测试，全量测试因 pom 默认 `skipTests=true` 未真正执行）�?
- **明确不做**：不新增任何业务功能、不改数据库结构；不处理 §6.86 已交付的 `InFlightDbQuotaService` 命名形态（既有事实，保持不动）�?

#### 2. 实际落地

- **b4 剩余 10 �?*（agent/service 既有单类形�?Service 全部拆接�?+ impl）：AgentService / AgentOutboxService / AgentCommandOutboxService / AgentInboxService / AgentExecutionRecordService / AgentDutyLeaseService / AgentMcpServerService / ConversationService / AgentExecutionConnectivityService / AgentExecutionPreviewService。统一范式�?§6.84：接口放 `agent/service`（实体型 extends `IService<Entity>`，编排型不继承），impl �?`agent/service/impl`（实体型 extends `ServiceImpl<Mapper, Entity>`，`@Service` + `@RequiredArgsConstructor`，方法级 `@Override` + `@Transactional` 保留�?impl）�?
  - AgentService 为最大拆分（接口 26 方法 / impl 662 行），构造注�?8 依赖：SubTaskMapper / RewardLogMapper / ActivityLogMapper / ReviewRecordMapper / AgentInboxMapper / AgentDutyLeaseMapper / TaskTimelineService / AgentMcpServerService；保�?直接注入 Mapper 避免循环依赖"类注释；3 �?task �?Mapper（RewardLog / ActivityLog / ReviewRecord）实际包路径�?`com.helloai.core.task.mapper`（非 agent.mapper，import 已修正）�?
  - AgentMcpServerService.DEFAULT_EXECUTOR_TOOLS 收为 `private static final`（grep 确认全仓无外部引用）�?
  - AgentCommandOutboxService 接口 9 方法（含 Phase 2H ②b �?CONFIRMED 扩展：createPending / listReadyForRelay / listExpiredSentForRetry / markSent / markConfirmed / markFailed / markFailedFromSent / markFinalFailed / markFinalFailedFromSent），createPending 不加 @Transactional 的契约注释保留�?
- **b4 3 归位**�?
  - `ExternalAgentFailureTracker`：agent/service �?`agent/observability`（与 CircuitBreakerEventRecorder 同包），9 个引用点 import 更新�? main + 5 test）�?
  - `WebSearchServiceRouter`：planner/service �?`planner/search`（与 WebSearchResult 值对象同包），补 `import planner.service.WebSearchService` 接口，无外部引用�?
  - `TaskRunningSpecDataMigrator`：确�?`task/spec` 为合法完整子域包（与 ExecutionRecord / TaskRunningSpec / TaskBaseline 同包协作），无需移动�?
- **b5 2 �?*�?
  - `DoorbellService`（shared/doorbell）拆接口�? 方法：connect / ring / disconnect / connectionCount / broadcastKeepalive�? `DoorbellServiceImpl`�?70 行，注入 DoorbellProperties / DoorbellRegistry / AgentDutyLeaseService / HeartbeatService，私�?refreshSeen / doSend）�?
  - `MessageDeduplicationService`（helloai-mq）拆接口�? 方法：isDuplicate / markConsumed / markFailed�? `MessageDeduplicationServiceImpl`�?5 行，显式构造器�?DEDUP_KEY_PREFIX / DEDUP_TTL 常量保留）�?
- **测试适配**�? 处构造点 `new XxxService(...)` �?`new XxxServiceImpl(...)`（DoorbellServiceTest / AgentDutyLeaseAdaptiveTtlTest / AgentInboxServiceTest / AgentServiceTest�? import 迁移；grep 全仓库确认无残留单类形态构造点�?

#### 3. 验证结果

- 全量编译：`mvn compile` 7 模块（common / mq / core / job / api / start + �?pom�?*BUILD SUCCESS**�?
- 全量测试：`mvn test -pl helloai-core,helloai-mq,helloai-job -DskipTests=false` **全绿**——core 全部测试�?+ job 60 tests，Failures=0 / Errors=0 / Skipped=0；关键回归：DoorbellServiceTest 12 / DoorbellRegistryTest 7 / DoorbellRingerTest 4 / DoorbellDutyListenerTest 4 / DoorbellKeepaliveTaskTest 4 / AgentInboxServiceTest 6 / AgentServiceTest 6 / AgentDutyLeaseAdaptiveTtlTest 12 / ExternalAgentFailureTrackerTest 0（无测试方法，编译通过�? ExecutionResultHandlerTest 4 + IntegrationTest 5 / AttachmentServiceImplTest 3（�?.85 附件单测一并回归）�?
- 踩坑：① pom 默认 `skipTests=true`（�?.86 已记录），直�?`mvn test` 输出 "Tests are skipped." 假绿，必�?`-DskipTests=false`；② IDE �?程序�?com.helloai.mq.service 不存�?�?Maven 项目模型未刷新（Maven �?test-compile 实际全绿），`mvn install -pl helloai-mq -am -DskipTests` 同步本地仓库�?IDE 可解析�?

#### 4. 影响与遗�?

- 影响：① b4 批次完全收口——agent/service 现为 21 接口 + 21 impl 完全成对，`{domain}.service.impl` 成为唯一业务逻辑实现位；�?3 归位完成——observability / planner/search 语义包纯净，无跨域残留；③ b5 完成——shared �?mq 模块也纳入接�?+ impl 范式；④ 全仓库无 `new XxxService(` 测试构造残留，测试全部依赖接口�?Impl 构造�?
- 遗留：① §6.85 附件管理真实环境页面效果仍待用户验证（后端需重启加载�?list 回填逻辑）；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.89 LLM 供应商模型多选配置重构收口：V49 模型�?+ 前后端多选配�?+ e2e 38/38�?026-08-14�?

#### 1. 范围

- **背景**：实施计划《LLM供应商模型多选配置重构》收口。需求：每个 Provider 可配置多个可用模型（Trae 式），必须有一个默认模型；内置 Provider 模型只可选不可改，自定义 Provider 支持任意模型名；同一模型在同一角色下全局唯一（跨 Provider 不冲突）�?
- **本轮内容**：V49 迁移（模型表 + 内置种子 + �?default_model 迁移）、后端模型管理全套（实体/Mapper/�?Service/QueryService + Admin 端点 + Agent 注册校验）、前�?Settings.vue 模型多�?UI（内置只�?+ 自定�?+ 默认模型下拉）、Agent 可用模型接口、单测补齐、e2e 脚本真实环境回归�?
- **明确不做**：实施计�?4.4 连通性测试按钮（test-connection 端点）；4.3 前端注册弹窗的实时唯一性提示（后端强制校验兜底，前端仅展示服务端错误）；Agent 注册弹窗模型下拉本身沿用既有能力（�?.51 已交付的 Provider 选择链）�?

#### 2. 实际落地

- **V49 `llm_provider_model` �?*：id/deleted/审计�?+ provider_id/provider_code/model_name/is_default/enabled/sort_order；`uk_provider_model UNIQUE(provider_id, model_name)` + FK `ON DELETE CASCADE`（设计意图：�?Provider 级联删模型，但应用层逻辑删除�?FK 不触发，�?§3 修复③）+ 三个部分索引（enabled / default / code 查询）；内置种子 4 厂商 11 模型（deepseek-v4-flash/pro、kimi-k3~k2.5、qwen3.8-Max~3.6-Flash、MiniMax-M2.5�?026-08-14 官网口径）；老数据迁移：无模型记录的 Provider �?`default_model` 迁为默认模型；`ON CONFLICT DO NOTHING` 保证幂等�?
- **后端模型管理全套**：`LlmProviderModel` 实体（extends BaseEntity�? `LlmProviderModelMapper`（含 `@Delete` 物理清理方法�? `LlmProviderModelService/Impl`（saveProviderModels 批量多�?/ setDefaultModel / addModel / deleteModel / toggleModel / validateProviderHasEnabledModels�? `LlmProviderModelQueryService/Impl`（listByProviderId / listEnabledByProviderCode / isModelAvailable / findModelType）；`AdminLlmProviderController` 六个模型端点：`GET /{id}/models/list`、`POST /{id}/models`、`PUT /{id}/models/saveAll`、`DELETE /{id}/models/deleteByName/{modelName}`、`PUT /{id}/models/toggleByName/{modelName}`、`PUT /{id}/models/setDefaultByName/{modelName}`（实施计�?3.5 端点�?CODE_STYLE §8 动词形式落地）；`AgentController` 新增 `GET /api/agents/listAvailableModels`（目录接口过�?available 厂商 + 有启用模型的 Provider，Agent 注册下拉用）；注�?编辑链路接入 `validateModelUniqueInRole`（实施计�?3.4：同 provider:model 同角色全局唯一，`AgentService.validateModelType` 提升为接口方法，格式/可用�?角色唯一性三段校验）�?
- **前端**：`settings.ts` 新增模型管理 API + 类型（listModels / saveAllModels / addModel / deleteModel / toggleModel / setDefaultModel）；`Settings.vue` 模型多选区块——内�?Provider 模型 Checkbox 只读（仅展示预设模型）、自定义 Provider 支持输入回车添加任意模型、默认模型从已选模型单选、校验规则对齐实施计划六（至少一个模�?+ 必有默认模型 + 内置只读）；Agent 注册相关类型 `modelType` 沿用�?
- **单测**：`LlmProviderModelServiceImplTest`（saveProviderModels 空列�?默认不在列表/正常保存、setDefaultModel 未启用拒绝、addModel、deleteModel 默认/最后一个拒绝、toggleModel 含最后启用保护）+ `LlmProviderModelQueryServiceImplTest` + `LlmProviderServiceTest` 补模型校�?+ `AgentServiceTest` �?validateModelUniqueInRole；共 48 个全部通过�?
- **e2e 脚本**：`scripts/powershell/verify-llm-provider-models.ps1`（S0-S11：列�?创建/模型增删�?默认模型/启用禁用/角色唯一�?脏注册拒�?saveAll 幂等/Provider 重建），遵循规则 6 UTF-8 with BOM + 单引号拼�?+ `Parser.ParseFile` 自检�?

#### 3. 验证结果

- 后端 `mvn test -DskipTests=false` 相关测试类全绿（48 个），前�?`vue-tsc` 0 error；重启后端后 ps1 脚本 **38 PASS / 0 FAIL ALL PASSED**（S0-S11 全场景，含重跑幂等验证）�?
- 本轮修复 6 个缺陷：
  �?**404 尾斜�?*：`AdminLlmProviderController` `@PostMapping("/")` �?Spring 6 PathPatternParser 下只匹配带斜杠路�?�?�?`@PostMapping`，手�?POST 验证 CREATE_OK�?
  �?**物理唯一约束 vs 逻辑删除**（V50）：`uk_provider_model UNIQUE(provider_id, model_name)` �?MyBatis-Plus 逻辑删除冲突——软删模型后重建同名 INSERT duplicate key 500 �?删约束改部分唯一索引 `uk_provider_model_active ... WHERE deleted = 0`（saveAll 幂等重跑安全）；
  �?**同源修复**（V51）：`uk_llm_provider_code` 同样冲突（软�?Provider 无法重建�?code）→ 部分唯一索引 `uk_llm_provider_code_active ... WHERE deleted = 0`�?
  �?**注册脏数�?*：registerOrGet 先创�?Agent �?applyRegistrationExtras 校验，modelType 校验失败留脏 Agent �?`validateModelType` 接口�?+ AgentController 注册前预校验�?
  �?**toggleModel 保护漏洞**：原只保护默认模型，不禁用非默认的最后一个启用模�?�?改为通用“最后一个启用模型”检查（�?deleteModel 语义一致）+ 单测�?1 例；
  �?**deleteById 级联**：Provider 软删不触�?FK CASCADE（逻辑删除�?UPDATE），模型记录残留导致 `isModelAvailable` 误判 �?`deletePhysicalByProviderId` 物理清理 + 单测覆盖�?

#### 4. 影响与遗�?

- 影响：① Provider 模型从单 default_model 升级为多选关联表，Agent 注册/编辑与平台模型配置共�?`llm_provider_model` 口径；② 内置 Provider 模型列表�?V49 种子固定（后续官网更新走新迁移，与实施计划八风险缓解一致）；③ 角色模型唯一性收紧为服务端强制校验（注册/编辑两条入口）�?
- 遗留：① e2e 脚本产生�?probe 残留数据（probe-404-check / probe-addmodel-debug / probe-saveall-idem 等软删记录）待用户执行清�?SQL（本轮已交付）；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.90 MQ 消息格式链路修复：Java 序列�?�?显式 JSON + NotificationConsumer 手动 ACK�?026-08-14�?

#### 1. 范围

- **背景**：启动后 RabbitMQ 消费者持续报 `ListenerExecutionFailedException: Failed to convert message`，根因是队列积压旧格�?Java 序列化消息（Phase 2F �?`convertAndSend(POJO/Map)` 遗留，body �?`LinkedHashMap` �?`application/x-java-serialized-object`），当前消费端反序列化被安全白名单拦截（`SecurityException: Attempt to deserialize unauthorized class java.util.LinkedHashMap`），listener 转换层即失败（方法体不执行、代码内 ACK 兜底无效）→ 无限 requeue 刷日�?�?30 分钟 ack 超时�?channel �?broker 关闭�?
- **本轮内容**：① 停应�?�?`rabbitmqctl purge_queue` 清理 reviewer/executor/planner 三队列积压旧消息 �?命令行重启；�?排查发现当前代码仍存在两�?Java 序列�?ack 缺陷路径，一并修复（�?§2）；�?修复后完整闭环验证（生产�?JSON 发出 �?消费端解�?�?幂等 �?手动 ACK �?队列清零）�?
- **明确不做**：不动全局 RabbitTemplate converter（避免波及其�?RabbitListener，与 Phase 2F 修正原则一致）；不�?reviewer/executor/planner 消费端（已按 JSON 解析，天然兼容）；不改调�?执行链逻辑�?

#### 2. 实际落地

- **清理**：`rabbitmqctl purge_queue` 清空 `helloai.reviewer.queue`�? 条）/ `helloai.executor.queue`�? 条）/ `helloai.planner.queue`�? 条）积压；后端以 `~/.jdks/ms-17.0.19/bin/java.exe -jar` 全路径重启（系统 PATH java 为失�?stub）�?
- **修复①生产端 `DomainEventPublisher`**：原 `convertAndSend(Map)` �?SimpleMessageConverter Java 序列�?�?改为显式 `ObjectMapper.writeValueAsBytes` + `RabbitTemplate.send` + ContentType JSON + PERSISTENT（与 Phase 2F `ExecutionCommandMqPublisher` 修正同款，Javadoc 注明修正原因）；调用�?`AgentEventCompensationTask` 签名不变�?
- **修复②消费端 `NotificationConsumer`**：原 `onNotification(Map)` 方法签名依赖 SimpleMessageConverter 反序列化（Java 序列化），且未显�?ackMode 继承全局 `spring.rabbitmq.listener.simple.acknowledge-mode: manual`（application.yml）却从不�?`basicAck` �?消息永久 unacked（此前队列无消息未暴露）�?改为 `(Message, Channel, @Header DELIVERY_TAG)` + `ackMode = "MANUAL"` + JSON 解析（解析失�?�?eventId 直接 ACK，消费失�?NACK 不重投走 DLX，与 `MqReviewCommandConsumer` 同款）�?

#### 3. 验证结果

- 重启�?`GET /api/health` 200；日志无任何 `Failed to convert message` / `SecurityException`；全队列 0 积压、消费者在线（reviewer/execution-command/notification �?5）�?
- 闭环验证（真实链路）：向 `agent_outbox_event` 插入 PENDING 测试行（routing_key=`agent.notification.test`）→ 补偿任务 15s 轮询发出 JSON（日�?`Publishing event ... bodyBytes=69`）→ `NotificationConsumer` 消费（elapsed=7ms）→ outbox �?status=SUCCESS �?队列 unacked=0（ACK 生效）。幂等验证：重启后旧 unacked 消息 requeue 再消费被幂等跳过�?ACK。测试数据（outbox �?+ event_consumption_log 记录）已清理�?
- 打包验证：`mvn -pl helloai-mq,helloai-job,helloai-start -am -DskipTests package` 通过（编译期即暴�?NotificationConsumer 残留声明，修复后 0 error）�?

#### 4. 影响与遗�?

- 影响：① 领域事件生产端统一 JSON 序列化，消费端全部按 JSON 解析，消�?SecurityException 复发路径（outbox PENDING 再出现也不复发）；② NotificationConsumer 补齐手动 ACK，消�?unacked 累积；③ 消费失败语义对齐：坏消息 ACK 不阻塞队列、业务失�?NACK �?DLX�?
- 遗留：① 历史 FAILED outbox 残留（agent_outbox_event status=2 �?agent_command_outbox status=3 共百余条）未清理，属历史失败快照，不影响链路；② executor/planner 队列当前无消费者（历史 agent.exchange 绑定），本轮只清积压未改拓扑；③ 本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.91 版本测试准备：注册选模型前端最小改�?+ 同角色同模型唯一性实�?+ 全量清理 SQL 交付�?026-08-14�?

#### 1. 范围

- **背景**：用户计划进行一次版本测试，需先清理全部业务数据（但不包括 credential_vault �?api-key 信息�?sys_user �?admin 信息）；同时补齐注册�?Agent 的前端功能——内�?LLM（API_KEY_LLM）注册时必须能选择模型，且同一角色同一模型不能重复注册（如 deepseek-v4-flash 不能出现两个 PLANNER，但可同时存�?deepseek-v4-flash �?deepseek-v4-pro �?PLANNER）�?
- **本轮内容**：① 前端注册弹窗模型分组下拉（最小改动，后端 V49 链路零改动）；② 同角色同模型唯一性约束实测确认（后端 V49 `validateModelUniqueInRole` 已实现，本轮实测验证）；�?注册失败业务提示前端修复（BizException �?HTTP 500 返回时拦截器只显示笼统错误）；④ 版本测试全量清理 SQL 交付�?
- **明确不做**：不动后端注册链路（V49 已完整）；不做编辑弹窗模型选择、连通性测试按钮、Settings 模型管理页改动；不代执行数据库写操作（清�?SQL 由用户执行）�?

#### 2. 实际落地

- **agent.ts**：新�?`AvailableModelGroup` 接口（providerCode/providerName/defaultModel/models�? `listAvailableModels()`，对接后�?V49 既有 `GET /api/agents/listAvailableModels`�?
- **AgentList.vue**：注册弹窗在 `accessType === 'API_KEY_LLM'` 时显示模型分组下拉（`el-option-group` �?Provider 分组，value �?`providerCode:modelName`，clearable + filterable，留空走系统默认 provider+default-model，兼�?§6.74 口径）；`watch(registerDialog)` 打开时加载模型目录；`handleRegister` �?`modelType`；表单重置补 `modelType=''`�?
- **request.ts**：response 拦截�?error 分支�?`error.response?.data?.msg` 提取——后端业务异常（BizException）以 HTTP 4xx/5xx 返回时优先展�?R 包裹体里的中�?msg（此前只显示 `Request failed with status code 500`，注册模型唯一性校验提示不可见）�?
- **同角色同模型唯一性（确认已有，零改动�?*：`AgentServiceImpl.validateModelUniqueInRole`（V49）按 `role + accessType=API_KEY_LLM + modelType + deleted=0` 查重，命中抛 `角色 X 已存在使用模�?Y 的Agent，同一模型在同一角色下只能注册一个`；`AgentController.register` L64 创建前预校验（失败不落脏数据�? `applyRegistrationExtras` 兜底，语义与用户要求完全一致（同角色同模型唯一、同角色不同模型允许）�?
- **清理 SQL**：`tmp/cleanup-business-data-20260814.sql` 事务包裹 21 �?~5900 行（任务�?12 �?/ Agent �?4 �?/ 需求对�?2 �?/ MQ 流水 3 表），按外键依赖排序�?*保留** credential_vault 全部 79 条（AGENT 74 + PLATFORM 5，版本测试需�?api-key�? sys_user admin + llm_provider/llm_provider_model/sys_config�?

#### 3. 验证结果

- `npm run build` 通过（vue-tsc 0 error + vite build 23.75s）�?
- API 实测 `GET /api/agents/listAvailableModels`�? 供应�?11 模型（deepseek 2 / moonshot 5 / minimax 1 / dashscope 3）�?
- 角色模型唯一性实测（`.tmp/verify-role-model-unique2.ps1`，curl + body 文件规避 PS 5.1 引号剥离）：S1 第二�?PLANNER+deepseek-v4-flash 被拒（msg=`角色 PLANNER 已存在使用模�?deepseek-v4-flash 的Agent...`）✅；S2 PLANNER+deepseek-v4-pro 注册成功（同角色不同模型允许）✅；S3 第二�?PLANNER+deepseek-v4-pro 被拒 ✅�?
- 实测产生�?2 �?probe-uq-* agent（PLANNER+flash / PLANNER+pro）已确认落库，随清理 SQL 一并清除�?

#### 4. 影响与遗�?

- 影响：① 前端注册内部 LLM 可选模型，留空仍走系统默认（与 §6.74 兼容）；�?同角色同模型唯一性为服务端强制校验（注册/编辑两入口），前端通过拦截器修复可见完整中文提示；�?版本测试前清�?SQL 已就绪，用户执行后即可从零态冒烟�?
- 遗留：① 清理 SQL 待用户执行（`docker cp` + `docker exec psql -f`，执行后 DELETE 计数反馈即开始完结校验：数据库空�?�?后端健康 �?MQ 队列归零 �?从零链路冒烟）；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.92 V52 技能能力校�?e2e 收口：getById skills 修复 + e2e 脚本 UTF-8 body 编码修复�?026-08-14�?

#### 1. 范围

- **背景**：V52 技能能力驱动校验链路（显式技能白名单 / 自定义豁�?/ 未识别模型放�?/ 关键词兜底）此前已有实现�?e2e 脚本，但 e2e 存在两处未闭环：�?`AdminAgentController.getById` 未回�?`skills`（V52 引入�?getById 返回空，脚本被迫走列表接�?fallback）；�?脚本 `Invoke-Api` �?PS 5.1 字符串直接作 `-Body` 发送，中文 body（如描述"负责代码审查与联网检�?）被�?ANSI 编码转换，后端收�?`??` 乱码，关键词兜底永不命中，S5 断言必败�?
- **本轮内容**：① getById 回填 skills；② 脚本发送中�?body 改为 UTF-8 字节数组；③ 词表�?检�?联网"（与"搜索"同义映射 web-search，原词表已有"搜索"即可满足，补词仅为更完整）�?
- **明确不做**：不�?`deriveWithCapabilities` 推导逻辑与校验语义；不改前端；不做数据库变更�?

#### 2. 实际落地

- **AdminAgentController.getById / AgentListItemVO**：`getById` 组装响应�?`setSkills(agent.getSkills())`（此前字段未回填导致 getById 恒为空，列表接口 skill 字段正常）。修复后 e2e �?`Get-AgentSkills` 直读 getById，删�?fallback 依赖�?
- **AgentSkillDeriver.keywordSkills()**：新�?`map.put("检�?, "web-search")`、`map.put("联网", "web-search")`�?
- **verify-agent-skill-capability.ps1**：`Invoke-Api` �?body 改为 `$script:Utf8NoBom.GetBytes($BodyJson)` 字节数组发送（`Utf8NoBom = New-Object System.Text.UTF8Encoding($false)`），头部按规�?6 �?`[Console]::InputEncoding` �?`$OutputEncoding = Utf8NoBom`�?

#### 3. 验证结果

- 定位过程关键证据：getById 修复生效（skills 非空）但纯中文描述仍未推�?�?�?只做search联网"探测，getById 显示 `description:"??search??"` + `skills:[thinking,code-review,web-search]` �?坐实脚本发送层中文损坏（`??`），非后端逻辑问题；`javap` 反编�?`AgentSkillDeriver.class` 确认运行类含"检�?搜索/联网"词条�?`deriveWithCapabilities`（含净�?lambda），排除编译产物陈旧�?
- 修复�?e2e 重跑�?*28/28 ALL PASSED**（S0 登录 / S1 deepseek+shell / S2 kimi+web-search / S3 deepseek+web-search 拒绝 / S4 自定义技能豁�?/ S5 kimi 无显式技�?�?描述"负责代码审查与联网检�?推导�?thinking,code-review,web-search / S6 编辑拒绝 / S7 换模�?双技�?/ S8 清理）�?
- 控制台仍�?`不支持技?` 尾字显示乱码（PS 5.1 管道重定向层 artifact，断言基于内存字符串匹配已通过，不影响判定）�?

#### 4. 影响与遗�?

- 影响：① getById 返回 skills 后，管理端详情展示与脚本断言均可直读；② e2e 脚本对含中文 body 的请求统一�?UTF-8 字节发送（规则 6 �?HTTP 发送层的落地，�?verify-agenthub-duty-e2e.ps1 �?Run-Psql 剥离 BOM 范式互补）；�?词表"检�?联网"补齐后描述含这些词即可推�?web-search�?
- 遗留：① 本轮代码与本文档�?git 提交，待用户确认后提交；�?其余 verify-*.ps1 若仍以字符串 -Body 发送中文，后续遇到同类"后端收到乱码"问题应优先按本轮范式修复�?

### 6.93 执行产出物化方案3 + Reviewer 附件内容级核验（F1-F3 全链路收口，2026-08-14�?

#### 1. 范围

- **背景**：执行产出物化设计文档（§6.27 编写）的方案2 已于 2026-07-31 落地（�?.30），方案3（LLM manifest 结构化多文件协议）与核验�?Reviewer 只看产出文本、看不到物化附件正文"一直是遗留缺口——Reviewer 的核�?Prompt 不含附件内容，无法做"声称交付�?�?文件正文 �?验收标准"的内容级核验�?
- **本轮内容**（按 .qoder/plans/产出物化方案3与Reviewer内容级核验_a4f2c9d7.md 依次执行）：�?F1 交付侧——manifest DTO + `ExecutionOutputParser` 扩展 + `ParsedOutput.displayText` + `buildUserPrompt` 追加 manifest 协议指令 + `ExecutionResultHandler` 挂接 displayText；② F2 核验侧——`buildAttachmentContent` + Prompt 模板占位 + 每附�?8000 / 总计 24000 字符限额 + 核验 Prompt 组装接线核验；③ F3 收口——e2e 脚本 `verify-artifact-content-review.ps1` 真实环境全绿 + 本文档回填�?
- **明确不做**：物化存储链不动（沿�?§6.30 物化 + §6.75 MinIO 主存�?+ §6.77 e2e）；不改核验触发条件�?checkEvidence 判定语义；不�?`attachment` 表结构；不做前端改动�?

#### 2. 实际落地

- **F1.1 manifest DTO + 解析扩展**：`Manifest`（summary + files�? `ManifestFile`（name/type/content）record �?`agent/output`；`ExecutionOutputParser.parse` 扩展——从 raw 提取 ```json 围栏�?JSON 对象（复�?`SubTaskReviewService.stripToJsonObject` 同款剥离思路，`@JsonIgnoreProperties(ignoreUnknown=true)` 容忍多余字段），命中�?files 非空 �?多文件结构化形态；未命�?/ files �?/ JSON 非法 �?降级纯文本单 .md（方�? 形态不变）。`ParsedOutput` 重构�?`(files, displayText)` 双字段：结构化时 `displayText = summary + "## 产出文件概览" + "- {name}" 逐行 + JSON 块之后尾部文本（EXECUTION_RECORD 回填块保留）`，纯文本�?`displayText = raw`�?
- **F1.2 Prompt 协议指令**：`SubTaskExecutionServiceImpl.buildUserPrompt` �?产出回填要求"段后追加**可�?*指令—�?可以用如�?JSON 结构返回多文件产出（放在 ```json 代码块中，位�?EXECUTION_RECORD 块之前）；若无需拆分文件，直接输出正文即�?；共存格式约�?manifest JSON 块在前、EXECUTION_RECORD 回填块在后（既有回填要求不变）�?
- **F1.3 挂接 displayText**：`ExecutionResultHandler` 构造器注入 `ExecutionOutputParser`，物化开启（`helloai.storage.enabled`）时 `lastExecution.output` 与对话流 `sub_task_execute` �?displayText（对话流不刷文件正文），关闭时保持原文；afterCommit 物化链不变（`ExecutionArtifactServiceImpl.materialize` 内部�?parser，多文件自动逐条物化）�?
- **F2.1 附件内容注入**：`SubTaskReviewServiceImpl.buildAttachmentContent(subTask)`——按 sub_task_id 查可直读附件（attachment 表，isContentLoadable），逐附件输�?`### {fileName}` �?+ 正文，每附件 8000 字符截断标注、总计 24000 字符停止注入后续附件正文；不可直�?/ 读取失败 / 为空 �?显式标注"内容不可�?为空"（不臆断）。`prompts/subtask-review.md` 新增�?# 物化附件内容（平台直读，已按限额截断）」节 + `{{ATTACHMENT_CONTENT}}` 占位�?+ �?10 条判定规则："声称交付�?�?文件正文 �?验收标准"三者一致性是判定依据，附件正文与声称结论矛盾或标注不可读时不得臆断�?
- **F2.2 接线核验**：核�?Prompt 组装�?`{{ATTACHMENT_CONTENT}}` �?`buildAttachmentContent(subTask)` 替换；核�?Prompt �?LLM 调用成功后落�?`conversation_message`（tool_name=`subtask_review_prompt`），供审计与 e2e 断言�?
- **F3.1 e2e 脚本**：`scripts/powershell/verify-artifact-content-review.ps1`（规�?6 UTF-8 头模�?+ `Add-Type -AssemblyName System.Net.Http` + HttpClient；S0 pre-flight �?S1 admin 登录 �?S2 agent 复用 �?S3 task+t1 幂等清理 �?S4 claim �?S5 submitResult manifest 产出 �?S6 多附件物化断言�? 附件 / mime / size / minio:// / 各自可下载且内容匹配 / displayText 含概览不�?JSON 与文件体）→ S7 核验 Prompt 断言（环境无绑定 vault �?REVIEWER/PLANNER agent �?SKIP 兜底）→ S8 纯文本降级回归（�?.md + output 原样）→ S9 teardown 级联删除）�?

#### 3. 验证结果

- 单测/编译：`ExecutionResultHandlerIntegrationTest` �?`new ExecutionOutputParser()` 构造器参数后全�?test-compile BUILD SUCCESS + package BUILD SUCCESS�?
- 后端启动链：PATH �?`javapath` 转发器在沙箱下崩溃（0xC0000409），改用 `JAVA_HOME`（`~/.jdks/ms-17.0.19`）完整路�?`java.exe -jar` 启动成功，health 200�?
- e2e 真实环境重跑（runTag 20260814）：**PASS=23 FAIL=0 SKIP=1 ALL PASSED**——S6 manifest 多文件物化全过（README.md text/markdown size=39 + main.py text/x-python，storage_url �?`minio://helloai-artifacts/{owner}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{name}`，下�?200 且正文含 'echo hello from readme' / 'hello from main'，displayText �?'## 产出文件概览' + '- README.md' + '- main.py' + EXECUTION_RECORD 尾、不含原�?JSON 与文件体）；S7 SKIP（当前环境无绑定 vault 凭证�?REVIEWER/PLANNER agent，脚本自检 SQL �?SKIP 兜底，绑定后重跑可断言 Prompt 注入）；S8 降级回归全过（纯文本 �?1 �?`{title}.md` text/markdown + 对话�?output 原样无概览）；S9 级联删除�?sub_task 残留 0�?
- 调试要点（沉淀）：�?子任务详情接口是 `GET /api/sub-tasks/getById/{id}`（`/api/sub-tasks/{id}` 返回 404 �?401，带 token 复现确认）；�?`downloadById` 返回 `application/octet-stream`，PS 5.1 `Invoke-WebRequest` �?`Content` �?byte[]，须 `[System.Text.Encoding]::UTF8.GetString` 后再断言（直�?`.Contains(string)` �?false）；�?外层 shell 执行 `-Command` 会吞 `$` 变量，调试一律走脚本文件�?

#### 4. 影响与遗�?

- 影响：① LLM 可按 manifest 协议一次产出多文件（README/main.py/config.json 等），平台物化多附件、各自可下载；② Reviewer 核验 Prompt 注入物化附件正文（限额截断），内容级核验（声称交付物 �?文件正文 �?验收标准）具备事实基础；③ 对话流不再刷 manifest JSON 与文件正文（displayText 概览）�?
- 遗留：① S7 核验 Prompt 内容断言待绑�?REVIEWER/PLANNER agent �?vault 凭证（API_KEY_LLM + ACTIVE）后实测（脚本自检 SQL 命中即自动执行断言，无需改脚本）；② 本轮代码与本文档�?git 提交，待用户确认后提交；�?tmp 调试脚本（debug-*.ps1 / check-parse.ps1）与 e2e 日志为临时资产，可清理�?

### 6.94 M5 场景 1：happy path 真实 AI 自主闭环�?026-08-14�?

#### 1. 范围

- **背景**：差距表 N14 / M5 场景矩阵场景 1（happy path）——「真实外�?AI 自主理解 SKILL.md、按规则完成注册→值班→感知→认领→执行→提交→签退全环」一直未实测：此前均为脚本化闭环（verify-onboarding-submit.ps1 / verify-mcp-e2e.ps1 固定步骤）或仅打卡链路（M4），缺真�?AI 在协议细节上的自主决策实证�?
- **本次落地**：本会话 AI（Qoder）作为真�?EXECUTOR，通读 `helloai-core/src/main/resources/skills/executor/SKILL.md` 后按 §1.3 推荐工作循环逐步决策、逐步调用真实接口完成全环；管理员侧仅建任务与 PENDING 子任务（不指派），执行侧全部自主�?
- **明确不做**：不写新代码、不改后端行为（纯协议链实证）；不做 blocked / 超时替补 / 附件 / 双值班场景（场�?2~5 留待后续）；不启动外�?LLM 核验（REVIEW 流转即可，核验链另见 §6.93）�?

#### 2. 实际落地（执行链实录�?

- **S0 管理员登�?*：`POST /api/auth/login`（admin/admin123）→ adminToken�?
- **S1 自助注册**：`POST /api/agents/register`（body `{name:h1-qoder-executor, role:EXECUTOR, description}`）→ `data.id=2088261489367584770` + `data.apiKey`（注册返回即 apiKey，无需二次签发）�?
- **S2 getAgentStatus 自检**（SKILL.md 1.3 step 1）：`ACTIVE` 账户 + `dbOnlineStatus=OFFLINE`（未打卡，符合预期）�?决策：先打卡�?
- **S3 checkIn 打卡**�?.3 step 2）：`{workMode:AUTO, maxConcurrent:3, ttlMinutes:30}` �?`ok=true`，`leaseId=2088261655818539009`，`expiresAt=21:49:11+30min`（ACTIVE 租约）�?
- **S4 pullTasks 值守�?�?*：`messages:[]`（空收件箱，值守中）�?
- **S5 管理员建任务 + 子任�?*：taskId=2088261744993636353（agentPolicy.executorAgentIds 白名单）；subTaskId=2088261745186574337�?*PENDING + assignedAgentId=null，不指派**）�?
- **S6 pullTasks 值守�?�?*：仍 `messages:[]` �?**自主决策**：PENDING 未指派任务不进收件箱，改�?SKILL.md §0.2 可认领通道：`GET /api/sub-tasks/listAvailable` 确认子任务可�?�?`claimSubTask` 原子认领�?
- **S7 claimSubTask**：`{ok:true, claimed:true, assignedAgent:本人, version:1}`（PENDING→ASSIGNED）�?
- **S8 getDepsSummary**：`depCount=0`（无前置，无需拉上游产出）�?
- **S9 heartbeat + uploadArtifact**：heartbeat `onDuty=true` �?**remainingTtlSeconds=14399（≈4h�?*——实�?E1 动�?TTL + A0-8 自动续约：认领在跑子任务�?`adaptiveRenew` �?`maxTtlMinutes=240` 长窗口（`AgentDutyLeaseServiceImpl.hasInFlightSubTask` 分支），执行期无需手动重打卡；uploadArtifact 登记 `execution-notes.md` 元数据（attachmentId=2088262013366177793）�?
- **S10 submitResult（manifest 多文件）**：`{accepted:true, resultId:h1-happy-20260814215116123}` �?状态机流转 **REVIEW**，afterCommit 物化 2 附件：protocol-notes.md�?52B�? sample.py�?5B），objectKey �?`{owner}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{name}` 组织�?
- **S11 checkOut 签退**�?.3 结束）：`{ok:true, closedCount:1, currentStatus:CLOSED}`，租�?DB �?status=CLOSED（close_reason=shutdown）�?
- **S12 teardown**：`DELETE /api/tasks/deleteById/{taskId}` 级联删除（subTaskCount=1 / timelineCount=2 清理�? 残留）�?

#### 3. 验证结果

- **状态机全链**：PENDING →（claimSubTask）→ ASSIGNED →（submitResult）→ REVIEW；sub_task �?version=4、assigned_agent_id=2088261489367584770、rework_count=0�?
- **物化证据**�? 附件下载 200 且内容逐字匹配（protocol-notes.md �?'pullTasks is the only task sensing channel'；sample.py �?'h1 happy path sample'）；对话�?`sub_task_execute` displayText = summary + '## 产出文件概览' + '- protocol-notes.md' + '- sample.py' + EXECUTION_RECORD 尾部（不�?manifest JSON 与文件正文，�?§6.93 F1 一致）�?
- **时间线事�?*：`sub_task_execute_submit`（payload �?idempotencyKey=h1-happy-20260814215116123、source=EXTERNAL�? `sub_task_artifact_materialized`（count=2、fileNames 列表）�?
- **在线与租�?*：agent �?`online_status=ONLINE`、last_seen_time/last_active_time 随工具调用刷新（HeartbeatServiceImpl 双写契约）；租约 ACTIVE 期间 expire_time �?heartbeat 续延�?
- **协议事实（复验确认，�?bug�?*：① 自主认领�?`claimAtomic` 原子 SQL 直改状态，**不触�?`notifyStatusChange` �?`sub_task.assigned` 收件箱消�?*——pullTasks �?claim 前后均空为预期行为，ack 步骤仅适用于管理员指派通道；② submitResult→REVIEW 时平台向全部 PLANNER �?`sub_task.review` 通知（本环境 1 �?PLANNER agent：v52-e2e-ds-bad，teardown unreadInboxCount 计数佐证）；�?任务感知双通道：指派消息走 inbox+pullTasks，自主认领走 listAvailable+claimSubTask�?

#### 4. 影响与遗�?

- 影响：① 场景 1 已勾除，「真�?AI 自主理解 SKILL.md 按规则执行」实证成立（含空收件箱→切换 listAvailable 通道、无消息跳过 ack、无依赖跳过依赖注入三处自主决策）；�?E1 动�?TTL 执行期长窗口�?A0-8 自动续约在真实调用链上得到佐证；�?SKILL.md 协议文档与代码行为在「自主认领无收件箱消息」点上存在文档口径差异（SKILL.md �?pullTasks 描述为唯一任务感知通道），已在本节记录协议事实，SKILL.md 口径优化留待后续批次�?
- 遗留：① 场景 2 blocked path / 3 超时替补 / 4 附件 path / 5 �?Agent 值班未开始；�?本轮无代码改动，仅文档回填（项目进度 M5、差距表 N14、本条目），�?git 提交；③ tmp 驱动脚本（h1-happy-path.ps1 / h1-recheck-inbox.ps1 / h1-state.json / q-agent-status.ps1）为临时资产，已清理�?

### 6.95 购物车任务实战复盘：Reviewer 内容级核验真实读取附件实证（2026-08-16�?

#### 1. 范围

- **背景**�?026-08-15 用户�?Trae 作为真实外部 EXECUTOR（agent=trae-excutor，CLI_CLIENT，人工注册）开定时任务自主轮询完成真实任务「修复购物车页面进入时仅选中第一个商品的 bug」（taskId=2088630823147409409），5 子任务全 DONE�?026-08-16 应要求整体复盘，重点核查「REVIEW 角色审查任务时是否真正读取了附件」——即 §6.93 方案3 F2 内容级核验在真实任务中的实战验证�?
- **本次落地**：只读取证（DB 查询 + conversation_message 核验 Prompt 原文比对 + downloadById 实测），无代码改动�?
- **明确不做**：不修改代码与协议行为；不启�?M5 场景 2~5（blocked / 超时替补 / 附件 / 双值班）�?

#### 2. 实际落地（取证链�?

- **任务全链**�? 子任务（2088631218330537986~90）全 DONE，执行�?trae-excutor；审查�?inner-deepseek-pro-reviewer（REVIEWER / API_KEY_LLM / deepseek:deepseek-v4-pro）；review_record 7 条（2 REJECTED + 5 APPROVED）；task_timeline 33 事件全链无断链（clarify �?plan �?5×submit/物化/审查 �?task_auto_completed �?final_report 27722 字符/5 段）；attachment 21 条（物化�?.md ×7 + uploadArtifact 登记 ×14）�?
- **四层证据链（Reviewer 确实读取附件内容�?*�?
  1. 代码链路：`SubTaskReviewServiceImpl.buildAttachmentContent` �?`readAttachmentContent` �?`attachmentService.loadContent(id)` �?`artifactStorage.load(storageUrl)`——真实读 MinIO 字节后注入核�?Prompt（每附件 8000 / 总计 24000 字符限额），非文件名/摘要臆断�?
  2. Round1 核验 Prompt 原文（conversation_message，content_type=text，platform 来源）：物化 `.md` 正文完整注入（EXECUTION_RECORD + VERIFICATION 验证输出 + 前端构建日志），�?cart-index.vue / cart-init-selection-fix.patch / 两个验证脚本**均标注「内容不可读/为空�?*�?
  3. Round1 verdict（inner-deepseek-pro-reviewer）：「关键交付物 cart-index.vue �?cart-init-selection-fix.patch 的物化附件均标注为“内容不可读/为空”，两个验证脚本也无法读取。无法核对初始化逻辑是否覆盖全部商品、修改范围是否仅限购物车」→ **REJECTED score=2**——完全符�?Prompt 模板规则 10（附件标注不可读时不得臆断文件内容、从严判定）�?
  4. Round2 Prompt 原文：cart-index.vue **完整源码注入**（`<template><el-card>…我的购物车`�? 物化 md �?**git diff 原文**（`created() { this._syncing = false …`、`syncTableSelection() �?_syncing = true`）；verdict analysis 引用 diff 细节（「diff 显示 syncTableSelection() 改为�?clearSelection…」）�?**APPROVED score=4**；downloadById 实测 6 附件现全�?HTTP 200（对象已补传，与 Reviewer 同一�?loadContent 链路）�?
- **实战拦截案例**：子任务 2088631218330537988 Round1 �?4 个文件为 `uploadArtifact` **纯登�?*（storage_url=minio:// �?MinIO 对象当时未上传，物化事件仅含 .md），Reviewer 读不到内容即驳回；Trae 补传对象 + 补交物化 md（含完整 diff）后 Round2 通过——A0-5 fail-close 在真实任务中拦截「声称有附件但内容读不到」的提交�?

#### 3. 验证结果

- **Reviewer 内容级核验链路真实生�?*：真实读取附件字节；读不到的明确标注并拒绝放行（不得臆断），读到的用于逐条核对（verdict analysis 引用文件正文�?diff 细节）�?
- **整体检�?*：任�?DONE + final report 生成�? 子任务全 DONE�? 次返工闭环（7986 声称交付 verify-cart-selection.js 但附件仅 .md �?驳回补交 �?通过�?988 附件内容不可�?�?补传+补交 �?通过）；时间线无断链；review_record �?timeline 一致�?

#### 4. 影响与遗�?

- 影响：① 方案3 F2 + A0-5 在真实外�?AI 任务上完成实战闭环验证（§6.93 e2e 之外的活体案例，且首次实�?inner-deepseek-pro-reviewer 真实审查）；�?uploadArtifact「纯登记、不校验 MinIO 对象存在」语义被内容级核验正确兜住（fail-close 实战价值）；③ 观察项（�?bug）：附件清单「平台可直读」（isContentLoadable 仅查 storageUrl scheme）与正文「内容不可读/为空」并存，对执行者略有误导——Reviewer 判定正确，可选优化为清单标注区分「可直读-已验证」�?
- 遗留：① M5 场景 2 blocked / 3 超时替补 / 4 附件 path / 5 双值班未测；② 本轮无代码改动，文档回填（本条目 + 差距�?N14 + 项目进度 M5）随 F 批次（�?.93 代码）一�?git 提交（含上轮 §6.94 未提交的文档改动）；�?场景 2 预置脚本 tmp/prepare-scene2.ps1 为临时资产（已登�?agent 凭证，未提交）�?

### 6.96 executor SKILL.md 补「值班闭环最小示例（§1.5.7）」（2026-08-16�?

#### 1. 范围

- **背景**：Trae 用后体验反馈——文�?§1.3/§1.5 已把「轮询值守协议」讲全（时间轴、两件套、反模式、正模式骨架），但缺一个把「上�?�?轮询 �?收件箱有任务 �?执行 �?提交 �?继续轮询 �?下班」串起来�?*完整可照抄示�?*；外�?AI 从完整示例学习远比从协议文字快。用户确认有必要加强�?
- **本次落地**：executor SKILL.md 新增 §1.5.7「值班闭环最小示例（可照抄）」（REST 别名通道 PowerShell 一段式脚本 + 照抄要点 5 条）；�?.3 工作循环末尾加指针；§0.1 表注修正（别名通道响应格式失真）�?
- **明确不做**：不改任�?Java 代码；planner SKILL.md 不同步（见遗留①）�?

#### 2. 实际落地（契约核�?+ 示例修正�?

- **示例以服务端真实契约为准**（本机无 Trae �?`helloai-register/excutor-poll.ps1`，该目录不在工作区）：核�?`McpController.jsonrpc`（`jsonrpcOk`/`jsonrpcError` 返回 JSON-RPC 原生 `{jsonrpc, result/error, id}`，HTTP �?200）与 `tools/list` 11 工具 inputSchema，工具名/参数�?Trae 草稿全部一致�?
- **�?Trae 草稿示例�?5 处契约修�?*�?
  1. **ack 顺序**：草稿「先 ack �?claim」改�?`claimSubTask �?执行 �?submitResult �?ack`——先 ack �?claim 时，claim 失败（任务已被抢）或执行中崩溃会让消息提前翻已读、任务丢失；�?§1.3 工作循环顺序一致；
  2. **error 字段检�?*：`Invoke-Tool` 内显�?`if ($r.error) throw`——别名通道失败�?HTTP �?200（error �?body 里），不检查会被当成功吞掉�?
  3. **API Key 占位�?*：草稿里的真�?key（`ak_121e...`）不写进仓库文档，改�?`<你的API_KEY>`（与 §认证信息/§三风格一致）�?
  4. **heartbeat onDuty 自检**：每轮心跳后�?`onDuty`，false 即重�?checkIn（A0-8 工具调用自动续约，长任务期间无需手动重签）；
  5. **reassigned/unassigned 分支**：按 §1.5.1.bis 补「立即停止执行、只 ack」分支，其余类型注释指向 §1.5.1.bis�?
- **§0.1 表注失真修正**：原「REST 直�?别名的响应是 R 包装 {code, msg, data}」与实测矛盾——REST 直通（`/api/mcp/tools/*`）才�?R 包装，REST 别名（`/api/mcp/jsonrpc`）返�?JSON-RPC 原生格式。已按通道区分表述（与 §1.5.7 照抄要点�?1 条互相印证）�?

#### 3. 验证结果

- 契约核对�?1 工具�?参数�?`McpController.dispatch` + `tools/list` 逐一对上（checkIn/checkOut/heartbeat/pullTasks/ack/claimSubTask/submitResult 等）；响应格式以 `jsonrpcOk`/`jsonrpcError` 实现为准�?
- 文档改动�?executor SKILL.md�?83 行）与迭代记录本条目；无代码改动，无需编译/测试�?

#### 4. 影响与遗�?

- 影响：① executor SKILL.md 由「协议完备」升级为「协�?+ 可运行示例」双轨，外部 AI（Trae/Qoder）拿�?SKILL 即可照抄值班闭环；② §0.1 三通道响应格式表述与真实实现对齐�?
- 遗留：① planner SKILL.md §1.4(3) 仍保留过时描述「不要走 /api/mcp/jsonrpc �?REST 通道，dispatch 不含 checkIn/checkOut」（A0-3 §6.61 后已补齐，与 executor 表述不一致），建议下轮同步修正；�?示例未做真机运行验证（M5 场景 2 起可�?Trae 实测交叉验证）�?

### 6.97 三角�?SKILL.md 同步：planner 失真修正 + 值班闭环示例 / reviewer 按代码事实重写（2026-08-16�?

#### 1. 范围

- **背景**：�?.96 只更新了 executor SKILL.md，用户确认�? 个角色的 skills 都应更新」。planner �?executor 同构（有 §1.5 值守协议但缺示例且多处失真）；reviewer 是旧版结构（�?MCP/值守概念）且其「每次唤醒查收件箱」流程与代码现实不符。用户选择 reviewer「按代码事实重写」�?
- **本次落地**：planner SKILL.md 失真修正 5 �?+ �?1.5.1.bis 消息类型�?/ 1.5.5 反模�?/ 1.5.6 骨架 / 1.5.7 值班闭环示例（PLANNER 版）；reviewer SKILL.md 整份重写（两种工作形�?+ REST 审查入口）�?
- **明确不做**：不改任�?Java 代码；不改变平台�?REVIEWER 角色的投递行为（`sub_task.review` 只投 PLANNER 属代码事实，仅如实写入文档）�?

#### 2. 实际落地（契约核�?+ 文档同步�?

- **planner 失真修正（与 A0-2/A0-3/A0-4/A0-8 现实对齐�?*：① §0 提醒「checkIn/checkOut 只存在于 MCP SSE�?0 工具�?REST �?7 工具」改为三通道对齐 + REST 别名兜底表述；② §1.2 工具�?10�?1（补 `getDepsSummary`）；�?checkIn 租约机制「不会自动续约」改�?A0-8 工具调用自动续约表述；④ §1.4(3)「不要走 /api/mcp/jsonrpc �?REST 通道」改为「Session 失效�?REST 别名兜底（A0-2）」，�?JSON-RPC 原生响应格式说明；⑤ 错误码速查表删两行失真行（404 只列 7 工具 / 500 Unknown tool: checkIn），改为 Session not found / Unknown tool: xxx�?
- **planner §1.5 补全**：新�?1.5.1.bis 收件箱消息类型表（PLANNER 视角 4 类，代码实证：`task.created` TaskController L62 / `task.republished` TaskServiceImpl L243 / `sub_task.blocked` SubTaskServiceImpl L453 / `sub_task.review` L472——均只投 PLANNER）；1.5.5 反模式�?.5.6 骨架�?.5.7 值班闭环示例（PLANNER 版，消息分派指向 §2.1 拆解 / §2.2 六步排障 / 审查兜底，写操作�?REST）；§1.3 加指针�?
- **reviewer 整份重写（按代码事实�?*：两种工作形态——形�?A 平台内自动核�?agent（API_KEY_LLM：`pickReviewerAgent` 选择�?+ 核验 Prompt 直接调用模型 + **不消费收件箱、不需�?checkIn/心跳**——选人过滤�?API_KEY_LLM 豁免在线与心跳新鲜度检查，AgentSelector L90-96 代码实证；入选条�?= REVIEWER/PLANNER 角色 + API_KEY_LLM + Agent 状�?ACTIVE + 托管凭证启用）；形�?B 外部人工审查�?*`sub_task.review` 收件箱通知当前只投 PLANNER，外�?REVIEWER 收不�?*——SubTaskServiceImpl L470-477 注释与代码不一致，注释�?通知所�?PLANNER/REVIEWER"但代码只 `listByRole(PLANNER)`；人工审查入口为 REST）。保留旧版精华（断言式三段审查法 / 审查原则 / 评分标准），审查操作�?`X-Agent-Id` 头（ReviewController L28-45 代码实证）与 `reworkAgentId` 参数，补「附件不可读不得臆断」原则（§6.95 实战教训）�?

#### 3. 验证结果

- 契约核对：planner 收件�?4 类消息类型逐一与投递代码对上；reviewer 两种形态的每个断言（收件箱跳过 API_KEY_LLM / 选人豁免在线 / POST /api/reviews 参数�?X-Agent-Id）均有代码行号依据�?
- planner SKILL.md �?2 �?powershell 代码块（1.5.6 骨架 + 1.5.7 示例 60 行）ParseFile 语法自检 PARSE-OK�?
- 无代码改动，无需编译/测试�?

#### 4. 影响与遗�?

- 影响：① 三角�?SKILL.md 全部与当前平台现实对齐——executor/planner 双轨（协�?+ 可照抄示例），reviewer 两种形态如实（不再给外�?REVIEWER 不存在的「收件箱接审查单」流程）；② planner �?5 处失真修正消除了「外�?Agent 照旧文档�?/api/mcp/jsonrpc �?Unknown tool」的误导源�?
- 遗留：① `sub_task.review` 投递注释与代码不一致（注释称通知 PLANNER/REVIEWER，代码只�?PLANNER）——是否把外部 REVIEWER 纳入审查通知属产品决策，未改代码仅文档如实；�?三份 SKILL.md 示例均未做真机运行验证（M5 场景 2 起可交叉验证）�?

### 6.98 对话新建并发优化止血：心�?active 节流 + LLM 并发限流（A+B�?026-08-17�?

#### 1. 范围

- **背景**：用户提出「平台能否并发进行多个对话新建」的性能问题。诊断结论：后端无全局锁、无串行队列，对话新建本身天然支持并发；真正的瓶颈有三——① 对话新建链路（RequirementClarifyServiceImpl.create �?doRound �?runLlmRound �?PlatformAgentExecutionServiceImpl.executeSync �?ApiKeyAgentExecutor.execute �?AgentChatClientServiceImpl.generate）同步阻塞等�?LLM 返回，长时间占用 Tomcat 线程；② 每轮 LLM 调用�?`heartbeatService.active()` 对同一 Agent 行做 2 �?selectById + 2 次全字段 updateById，并发对话下 DB 行锁排队与写放大；③ 上游 DeepSeek 对单 Key �?RPS/并发限流，突发并发直�?429�?
- **方案**：用户确认「先�?A+B 止血（推荐）」——A. 心跳节流（active() 加时间窗口去重，默认 30s 同一 Agent 只做一次完整双写）；B. LLM 并发限流（JVM 内公平信号量，默�?8 并发、超�?60s 友好报错）�?
- **明确不做**：对话轮次异步化（C 项，改造大、需改接口契约与前端轮询语义）；应用�?429 重试（已核实 Spring Boot 自动配置�?RetryTemplate 在框架层覆盖三个协议工厂，避免重复建设）；多 Key/�?Provider 负载均衡；Redis 分布式锁；不改接口契约与前端�?

#### 2. 实际落地

- **A. 心跳节流**：新�?`HeartbeatProperties`（helloai-common/config，前缀 `helloai.heartbeat`，`active-throttle-ms` 默认 30000�?=0 不节流）；`HeartbeatServiceImpl.active()` 入口加节流（`ConcurrentHashMap<Long, Long> lastActiveWriteAt` 时间窗口，窗口内直接 return）。语义：心跳节流仅影响写放大，不影响可用性判定——外�?Agent 的心跳续约走独立路径（checkIn/heartbeat 接口），本处 active() 仅用于平台内 Agent 状态新鲜度刷新�?
- **B. LLM 并发限流**：`AgentExecutionProperties` 新增 `max-concurrent-llm-calls`（默�?8�?=0 不限流）�?`llm-acquire-timeout-seconds`（默�?60）；新建 `LlmCallConcurrencyGuard`（agent/chat，公�?Semaphore，构造时按配置创建）；`AgentChatClientServiceImpl.generate()` 收口接入——非 mock 模式 acquire �?doGenerate �?finally release，超时抛 BizException「LLM 调用并发过高」，中断时恢复中断位并抛 BizException�?
- **配置**：application.yml 新增 `helloai.heartbeat.active-throttle-ms: 30000` �?`helloai.execution.max-concurrent-llm-calls: 8` / `llm-acquire-timeout-seconds: 60`（含注释）�?

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false "-Dtest=AgentChatClientServiceTest,LlmCallConcurrencyGuardTest,PlatformAgentExecutionServiceTest,HeartbeatServiceActiveTest" -Dsurefire.failIfNoSpecifiedTests=false` **4 测试�?16 tests 全绿**�?
  - `HeartbeatServiceActiveTest` 10 例（�?7 例回�?+ 新增 Throttle 嵌套�?3 例：窗口内跳过写 times(2) / 窗口过期后恢复写 times(4) / �?Agent 独立窗口互不影响）；
  - `LlmCallConcurrencyGuardTest` 4 例（许可获取/释放、超限超时抛 BizException、配�?<=0 不限流�?2 线程 4 许可并发进入数不超上限）�?
  - `AgentChatClientServiceTest` 1 �?+ `PlatformAgentExecutionServiceTest` 1 例回归，构造点已适配新依赖�?
- 踩坑：① `BizException` �?(String, Throwable) 构造器，中断分支改单参数；�?测试构造点�?`LlmCallConcurrencyGuard` import 与参数；�?多模块测试需 `-am` 保证 helloai-common 新类�?classpath；④ PowerShell �?`-Dsurefire.failIfNoSpecifiedTests=false` 需引号包裹防止被拆 token�?

#### 4. 影响与遗�?

- 影响：① 并发对话时心�?DB 写放大消除（30s 窗口内同一 Agent 只写一次，多轮对话/多会话并发下效果显著）；�?LLM 调用被信号量约束在上�?RPS 限流之下，突发并发从「上�?429 无差别失败」变为「排队等待或友好报错」；�?心跳节流对可用性判定零影响（外�?Agent 走独立续约路径，last_active_time 窗口 5 分钟远大于节流窗�?30s）�?
- 遗留：① 对话轮次异步化（C 项）未做——同步阻塞仍�?Tomcat 线程，Tomcat 200 线程上限仍是天花板，需时按 C 项方案推进（SSE 流式 + 前端轮询，改接口契约，属产品决策）；�?信号量限流为 JVM 级，多实例部署时需分布式限流（Redis 预留接口位未做）；③ 本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.99 服务器版 MinIO 产物上传修复：平台代理上传接�?POST /api/artifacts/upload�?026-08-17�?

#### 1. 范围

- **背景**：用户反馈「本�?AI 无法向服务器版平台上传产出，单机版可以」。根因：�?`uploadArtifact` 契约要求 AI 先直�?MinIO PUT 文件再注册元数据（McpToolServiceImpl 注释明示「实际文件内容由客户端直接上传到 MinIO，本工具只注�?DB 元数据」）；② 服务器版 `docker-compose.server.yml` �?MinIO 端口绑定 `127.0.0.1:29000/29001`（公网不可达），而单机版绑定 `0.0.0.0:29000`——「单机版能传、服务器版不能传」的直接原因；③ 公网唯一入口 nginx�?0）只反代 /api�?mcp，无 MinIO 路径；④ SKILL.md 未给�?MinIO 端点/凭据，AI 无地址可传�?
- **方案（用户确认「平台代理上传接口」）**：新�?`POST /api/artifacts/upload`（multipart），AI �?`Authorization: Bearer <API_KEY>` 上传文件内容，平台用服务端凭据转存主存储（MinIO）并注册附件元数据，一步到位返�?`{attachmentId, storageUrl}`；不暴露 MinIO、不开新端口、AI 无需知道 MinIO 地址与凭据�?
- **明确不做**：改 docker-compose.server.yml 开�?MinIO 端口（凭据暴露公网，仅靠安全�?IP 白名单，不选）；nginx 反代 /minio（S3 签名 Host 头兼容成本）；不改变 `uploadArtifact` 工具契约（保留「仅登记已有对象」场景）�?

#### 2. 实际落地

- **ArtifactUploadService**（helloai-core/system/service 新接�?+ `ArtifactUploadServiceImpl`）：校验 Agent 存在�?ACTIVE（与 `McpToolServiceImpl.assertAgentActive` 同口径）�?子任务存在且 `assigned_agent_id=agentId`（与 `AttachmentService.register` 内置校验同口径，register 复用防漂移）�?fileName 安全清洗（`sanitizeFileName`）→ `artifactStorage.store(ownerName=Agent 注册�? taskId, subTaskId, fileName, content)` �?`attachmentService.register` 一步到位；store 先于 register，register 失败残留孤儿对象（与 `ExecutionArtifactServiceImpl` 物化链路现状一致，注释说明）�?
- **ArtifactUploadController**（helloai-api/controller 新）：`POST /api/artifacts/upload`，multipart/form-data（`file` + `subTaskId` + 可�?`mimeType`），走现�?`AuthInterceptor` Bearer 鉴权（`@RequestAttribute(_authId)` 注入 agentId），�?MCP session，三通道外部 AI 均可调用；file �?/ subTaskId �?/ fileName �?/ 读取失败均有友好 BizException�?
- **配置**：application.yml 新增 `spring.servlet.multipart.max-file-size/max-request-size=8MB`（略高于 `storage.max-file-size` 5MB 留余量，默认 1MB 太小）�?
- **SKILL.md（executor/planner�?*：`uploadArtifact` 描述从「文件先 PUT �?MinIO」改为「文件内容先�?`POST /api/artifacts/upload` 上传，平台转�?MinIO 并注册一步到位」；executor 新增 🧭「产物文件内容上传（服务器版必读）」提示块（端�?参数/示例，含「不要尝试直�?MinIO PUT 文件」警示），�?.1 通道说明、�?.3 工作循环�?7 步、�?.5.7 示例注释同步更新；planner 工具表行同步�?

#### 3. 验证结果

- `mvn -pl helloai-api -am compile` 通过（新 Controller/Service 全模块编译）�?
- `mvn -pl helloai-core -am test` 定向�?*ArtifactUploadServiceImplTest 8 tests 全绿**（正常上�?store+register 一步到�?/ fileName 清洗 `../报告.md` �?`_报告.md` / Agent 不存�?/ Agent 未激�?/ 子任务不存在 / 非本人子任务不写存储 / fileName �?/ 内容空）；回�?**McpToolServiceTest + AttachmentServiceImplTest + CompositeArtifactStorageTest + MinioArtifactStorageTest 45 tests 全绿**�?
- 踩坑：① `sanitizeFileName` 先替换路径分隔符再剥点前缀（`../报告.md` �?`.._报告.md` �?`_报告.md`），测试断言按实际清洗行为校准；�?PowerShell �?`-Dsurefire.failIfNoSpecifiedTests=false` 需引号包裹（重复踩坑，已记）�?

#### 4. 影响与遗�?

- 影响：① 外部 AI 上传产出不再依赖 MinIO 直连，服务器版与单机版行为一致（统一走平台接口）；② MinIO 凭据不暴露公网，上传链路纳入平台 Bearer 鉴权与子任务所有权校验；③ `uploadArtifact` 工具契约不变，保留「仅登记已有对象」语义�?
- 遗留：① 服务�?app 容器�?`MINIO_ENDPOINT` 未覆盖（默认 `localhost:29000` 在容器内自指），后端产出物化�?MinIO 在服务器版可能同样失败——需用户确认服务器实际部署时是否 export `MINIO_ENDPOINT`，未配则需�?docker-compose.server.yml �?`SPRING_*` 覆盖或改用容器网络地址；② 新接口未做真机端到端验证（需服务器重新部�?jar 后由外部 AI 实测）；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.100 运行时卡死三根因修复：人工驳回补发执行命�?+ 拆解前物理清理旧草案 + 幽灵依赖存在性校验（2026-08-17�?

#### 1. 范围

- **背景**：任�?Spec-Driven Development 极简方案"运行期暴露三个问题：�?3 次自动返工驳回后人工改派 inner-deepseek-flash-executor，子任务永久卡死 REWORK——根�?`reworkFresh` 只重置状�?换人/发通知，漏发执行命令（执行链完全由 `agent_execution_record` 驱动，无命令即永不执行，与自动驳�?`rejectAndRework` 补发范式不对称）；② 任务两批拆解�?+7 子任务）�?`depends_on` 全部引用**不存在的幽灵 ID**（与真实 ID 同毫秒窗口偏移），`isReady` 对不存在依赖恒判未就绪，6 �?PENDING 子任务永远无法调度；�?重新拆解�?CANCELLED 旧草案残留（`rejectPlan` 设计保留审计），幽灵依赖持续污染下一批草案�?
- **本轮内容**：① `ReviewServiceImpl.createReview` 人工驳回路径�?API_KEY_LLM 执行者补发执行命令（trigger=manual-review-rework），失败�?warn 不阻断；�?`PlannerAnalysisServiceImpl.decompose` 拆解前若仅存�?CANCELLED 旧草案则物理删除后再拆解；③ `applyDependsOn` 写入前校验依�?ID 真实存在，缺失整批拒绝回退（去重比对，兼容 LLM 输出 [2,2,3] 重复引用）；�?顺带修复 §6.93 遗留测试缺口：`ExecutionResultHandlerTest` 未补构造器新增�?2 �?mock（taskRunningSpecService/executionOutputParser）导�?2 个既有测�?NPE�?
- **明确不做**：不恢复 6 �?CANCELLED 子任务（第一版废弃计划，恢复会与第二版重复执行）；不�?`rejectPlan` �?CANCELLED 审计语义（保留时间线证据）；不动 `isReady` 依赖判定逻辑；不改前端�?

#### 2. 实际落地

- **ReviewServiceImpl.createReview（REJECTED 分支�?*：`reworkFresh` 之后�?`reworkAgentId ?? subTask.assignedAgentId` 为目标执行者，`agentService.getById` 判定 `accessType == API_KEY_LLM` 才补�?`executionCommandService.createAssignedCommand(subTaskId, targetExecutor, "manual-review-rework")`，异常仅 `log.warn`（子任务停留 REWORK 等兜底扫描），与 `SubTaskReviewServiceImpl.rejectAndRework` 自动驳回范式对齐�?
- **PlannerAnalysisServiceImpl.decompose（L119-129�?*：既�?�?CANCELLED 子任务存在即拒绝"校验之后，追�?CANCELLED 残留计数；`cancelled > 0` �?`log.info` + `subTaskMapper.physicalDeleteByTaskId(taskId)` 物理清理（该场景草案从未执行、无关联执行记录，删除安全），再�?CAS 推进 PLANNING�?
- **PlannerAnalysisServiceImpl.applyDependsOn（L552-567�?*：`depIds.stream().distinct()` 去重�?`subTaskService.listByIds` 校验存在性；数量不匹配时计算 missing 列表，`log.error` + �?BizException"依赖指向不存在的草案 ID"，由 decompose 外层 catch 回退 PENDING 并记 `task_plan_failed`。防御注释说明幽�?ID 历史成因与去重原因�?

#### 3. 验证结果

- **ReviewServiceTest 8/8 通过**（新�?4）：API_KEY_LLM 改派补发命令 / 不改派对原执行者补�?/ CLI_CLIENT 不补�?/ 下发失败仅告警不阻断驳回链路（reward 照常落账）�?
- **PlannerAnalysisServiceTest 20/20 通过**（新�?4）：仅残�?CANCELLED 时拆解前物理删除且新草案正常生成 / 无残留不触发删除 / 幽灵依赖（listByIds 查不到）整批拒绝 + 回退 PENDING + `task_plan_failed` + 不执行任何依赖回�?/ 依赖全部存在时序号→真实 id 正常回写（updateDependsOn(12, [11])）�?
- **helloai-core 全量 735 tests 全过（BUILD SUCCESS�?*；修复前全量存在 2 �?Error，定位为 §6.93 遗留测试缺口（ExecutionResultHandlerTest �?mock），�?2 �?`@Mock` + `@BeforeEach` stub `ParsedOutput.empty()` 后恢复绿色�?
- **数据清理（用户执行）**：任务域/需求澄清对话域/MQ 流水全部 DELETE 清空（task/sub_task/task_timeline/execution_record/review_record/attachment/outbox �?19 张表），基础配置（agent×4/llm_provider×4/sys_user/prompt_template/agent_mcp_server 等）完好；仅 `agent_inbox` 残留 5 条孤儿通知可选清理�?

#### 4. 影响与遗�?

- 影响：① 人工驳回改派 API_KEY_LLM 执行者不再卡死（执行命令必达，触发链与自动驳回一致）；② 重新拆解前物理删�?CANCELLED 旧草案，幽灵 depends_on 不再跨批污染；③ applyDependsOn 存在性校验成为幽�?ID 的最后防线（即使服务器旧版代码行为差异再次产生，也会整批拒绝而非静默写库卡死）�?
- 遗留：① **服务器部署包需同步更新**——本机代码修复后须重新打包部署服务器 jar，否则浏览器请求仍走服务器旧实例（旧逻辑）；�?`agent_inbox` 5 条孤儿通知可选清理（`DELETE FROM agent_inbox;`）；�?幽灵 ID 产生机制未在服务器日志直接证实（双实例日志分散，服务器实例日志不可达），本轮以防御性校�?+ 物理清理收口；④ 本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.101 人工介入面板改派候选修复：新增「原执行者重做」选项�?026-08-18�?

#### 1. 背景与决�?

- **真实事故（承 §6.55/6.57 同源�?*：子任务「创�?specs 目录并编�?00-prd.md」返�?3 次达上限（`manualIntervention=rework_limit`）后停在 REVIEW，人工介入面板「改派给」下�?*只剩外部 Agent（trae-executor），内部 inner agent 不可�?*。数据库实证（dev �?agent 表）：环境内 EXECUTOR 角色�?2 个——`inner-deepseek-flash-executor`（API_KEY_LLM 内部，恰为当前负责人 assigned_agent_id）与 `trae-executor`（CLI_CLIENT 外部）；前端 `manualCandidates` 的「排除当前负责人」过滤把唯一内部执行者过滤掉，与 §6.55「外�?内部 Agent 均可选」决策相悖�?
- **决策**（用户拍板）：保留「改派候选排除当前负责人」语义（下拉仍是可选新 Agent），另在面板新增**「原执行者重做」固定选项**（选择器内特殊�?`__KEEP_CURRENT__`，提交时映射 `reworkAgentId=null`，走后端已有「原执行者重做」语义：`reworkFresh` 重置计数 + API_KEY_LLM 原执行者补发执行命令）。后端零改动�?
- **明确不做**：不放开「排除当前负责人」过滤（避免改派列表混入现任执行者与「原执行者重做」语义重叠）；不改后端接口契约（`createReview.reworkAgentId` 可空语义已存在）�?

#### 2. 实际落地

- **SubTaskDetail.vue（�?.52 人工介入面板�?*：`manualCandidates` 维持「EXECUTOR + ACTIVE + 排除当前负责人」过滤与在线优先排序；el-select 追加「原执行者重做（当前负责人名）」固�?option（`v-if="currentExecutorName"`）；`submitManualRework` 提交�?`manualTargetAgentId === KEEP_CURRENT` �?`reworkAgentId=null`（issues 文案区分改派/重做，成功提示同步区分），否则原逻辑传目�?Agent id�?
- **后端兼容确认**（无需改动）：`ReviewServiceImpl.createReview` REJECTED 分支 `reworkFresh(subTaskId, null)` 重置计数并清人工介入标记；`targetExecutor = reworkAgentId ?? assignedAgentId` �?API_KEY_LLM 补发 `manual-review-rework` 执行命令（�?.100），原执行者重做链路完整�?

#### 3. 验证结果

- `vue-tsc --noEmit` 类型检查：0 error�?
- `npm run build`（vue-tsc -b && vite build）：BUILD SUCCESS�?2s），dist 产物就绪�?
- 闭环推演：面板选「原执行者重做」→ REJECTED + reworkAgentId=null �?reworkCount 归零 + 清除 manualIntervention �?inner 执行者收到补发命令重新执�?�?重新进入自动核验（计数从 1 起）�?

#### 4. 影响与遗�?

- 影响：纯前端修复（SubTaskDetail.vue 单文件），无 DB 迁移、无状态机变更、无后端契约变化；后端零改动无需重新打包 jar，前�?dist 需重新构建部署（`dist/` 已生成）�?
- 遗留：① 服务器前�?dist 需更新后人工介入面板才生效（需用户构建/上传 dist 后重�?web 容器或随 compose 挂载刷新）；�?存量卡死任务（本子任�?reworkCount=3 REVIEW）可由面板「原执行者重做」或改派外部 Agent 处置；③ 本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.102 等保加固：agent.api_key AES-GCM 加密落库 + nginx TLS/UTF-8（V53�?026-08-18�?

#### 1. 背景与决�?

- **问题提出**：用户问“外�?AI 获取任务、读取任务内容乱码，是否�?AES �?XML-base64 加密统一外部 AI 收发流程，符合等保三级”�?
- **分析结论（三条）**：① 加密不解决乱码（AES 解密后字节原样，base64 只是编码，均不修�?GBK/UTF-8 链路错位）；�?等保三级“通信传输保密性”标准解法是 TLS/HTTPS，应用层自研加密协议是评审大忌；�?真正缺口是存储层——`agent.api_key`（consumerToken 工牌）与 LLM 凭证此前明文落库，证书验�?`credential_vault` 已有 24 �?AES-GCM 加密数据�?`agent.api_key` 仍是明文�?
- **决策**（AskUserQuestion 选定）：�?传输层走 TLS（nginx 443 模板，证书就绪后启用）；�?存储�?`agent.api_key` �?AES-GCM 密文落库（复用既�?`CredentialCryptoService`）；�?同时修复服务�?MinIO endpoint（承 §6.101 子任务事故根因：app 容器未配 MINIO_ENDPOINT，默�?localhost:29000 在容器内不可达，MinIO 数据卷全空、附件元数据全部空壳，核验报“内容不可读”）�?
- **明确不做**：业务内容不包自研密文协议（外部 AI 接入契约不变，仍明文 Bearer 认证）；不做前后端展示改造；不换 AES 密钥（轮换需同步重加�?vault �?api_key，属独立治理项）�?

#### 2. 实际落地

- **V53 Flyway**：`agent` �?`api_key_hash VARCHAR(64)` �?+ 存量 SHA-256 回填（PG 内置 `sha256(bytea)`，`undefined_function` 异常时跳过）+ 部分唯一索引（`deleted=0 AND api_key_hash IS NOT NULL`）。AES-GCM 密文每次 nonce 随机不可 SQL eq 匹配，hash 列是认证点查主路径�?
- **AgentApiKeyCipher**（`core/system/crypto` 新组件）：存储形�?`enc:v1:{AES-GCM-Base64}`（版本前缀）；`matches` �?`MessageDigest.isEqual` 恒定时间比对，无前缀视为存量明文直接兼容；`sha256Hex` 供点查；null 全链安全�?
- **AgentServiceImpl**：`register`/`resetApiKey` 生成明文后加�?+ hash 双写（明文仅响应返回一次）；`getByApiKey` �?hash 点查 �?命中后解密比对防碰撞 �?hash 为空的老数据逐条明文比对兜底 �?命中且未加密时惰性迁移（加密 + hash 回写，失败仅告警不影响认证）�?
- **AuthServiceImpl.validateAgentKey**：改为复�?`AgentService.getByApiKey`�?01/403 语义不变），消除明文 SQL eq 查询�?
- **API 回显解密**：`AdminAgentController`（列�?详情/注册响应/onboarding 渲染）与 `AgentController`（详�?注册响应）统一 `agentApiKeyCipher.decrypt`——存库密文、出参明文，管理端展示与 SKILL 渲染零感知�?
- **nginx.server.conf**�?0 块加 `charset utf-8`（外�?AI 乱码防护：响应头统一携带 charset，防 CLI/SDK �?GBK 误读中文任务内容）；新增 443 TLS1.2/1.3 server 注释模板（证书路�?协议套件/`X-Forwarded-Proto`，启用步骤在注释内）�?
- **docker-compose.server.yml**：app 服务 environment �?`MINIO_ENDPOINT: http://minio:9000` + `MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BUCKET`（compose 内网服务名互访，绕开容器�?localhost 陷阱）�?

#### 3. 验证结果

- `mvn -pl helloai-common,helloai-core,helloai-api -am compile -DskipTests`：BUILD SUCCESS；`-am test` �?pom 默认 `skipTests=true` 需显式 `-DskipTests=false`，跨模块 `-Dtest` 需 `-Dsurefire.failIfNoSpecifiedTests=false`（且 PowerShell 下参数必须加引号，`.failIfNoSpecifiedTests` 曾被拆成 lifecycle phase）�?
- helloai-core 单测 26/26 全绿：`AgentApiKeyCipherTest` 5 例（roundtrip、密文随机但 matches 稳定、存量明文兼容、null 安全、SHA-256 确定性）+ `AuthServiceTest` 9 例（�?mock AgentService�? `AgentServiceTest` 12 例（构造器补参）�?

#### 4. 影响与遗�?

- 影响：存量明�?api_key 认证不受影响（明文兼�?+ 惰性迁移逐次回写，无停机窗口）；外部 AI 接入契约零变化；管理�?onboarding 展示不变�?
- 部署要求：服务器 jar 升级触发 V53 迁移 + compose 环境变量生效；惰性迁移在首次认证时逐个完成，无需数据批处理�?
- 遗留：① TLS 证书申请�?443 启用（阿里云免费证书/acme.sh，外�?AI 接入地址需同步�?https，sys_config 外网地址）；�?服务�?MinIO 幽灵附件清理 SQL（`UPDATE attachment SET deleted=1 WHERE sub_task_id='2089260943795032065' AND storage_url LIKE 'minio://trae-executor/%'`，写操作用户执行）；�?AES 密钥轮换治理（轮换必须同步重加密 credential_vault 24 �?+ 全部 agent.api_key，否则解密失败）；④ 本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.103 拆解异步化改造：提交即返�?+ 异步执行 + 前端轮询 + PLANNING 超时兜底�?026-08-19�?

#### 1. 范围

- **背景**：用户反映「对话生成方�?�?任务拆解」耗时长、常超时报错。根因：`POST /tasks/planById/{id}` �?HTTP 线程同步�?LLM（最坏约 245s），前端 axios 超时�?120s，超时倒挂导致「前端先报错、后端还在跑」。用户选定方案 1（异步优化）�?
- **本轮内容**：decompose 三段式异步化——同步守卫（校验 + CAS �?PLANNING + timeline + 提交异步）→ `@Async` 异步执行 LLM �?�?`PlanningTimeoutTask` 兜底回收卡死任务；前端三文件轮询改造；测试拆分迁移 + 新增两组测试�?
- **明确不做**：API 契约不改（`planById` 保持 `R<List<SubTask>>` 返回空列表，�?CODE_STYLE §6.7 的有意例外，TaskController 零改动）；不�?SSE 推送草案结果（前端轮询够用）；不改拆解业务逻辑本身（LLM 段原样迁移）�?

#### 2. 实际落地

- **PlannerAnalysisServiceImpl.decompose 同步守卫�?*：保留校验（PENDING / 无存活子任务 / CANCELLED 残留物理清理�? CAS �?PLANNING，之后记�?`task_plan_async_submitted` timeline、调 `plannerDecomposeAsyncService.executeDecompose(taskId)` 立即返回空列表；捕获 `TaskRejectedException`（线程池满）�?CAS 回退 PENDING + BizException「拆解排队已满，请稍后重试」。LLM 段整体迁出，构造器�?9 参改�?7 参（去掉 PlannerAgentPicker / PlatformAgentExecutionService / ObjectMapper，注�?PlannerDecomposeAsyncService）�?
- **PlannerDecomposeAsyncService / Impl（core/planner�?*：接�?+ impl 成对（CODE_STYLE §7）；`@Async("plannerDecomposeExecutor")` 标注 `executeDecompose`（跨类调用避开代理失效）；入口幂等守卫（task 为空或非 PLANNING 直接 return，与 Job 兜底 CAS 天然互斥）；doDecompose 承接迁出的完�?LLM 段（picker �?renderPrompt �?executeSync �?parseDraftItems �?validateDependencies �?buildDrafts �?saveBatch �?applyDependsOn �?`task_plan_generated`）；新增 `task_plan_llm_call_end` timeline（costMs/finishReason/tokenUsage/success，补�?LLM 调用观测缺口）；失败 catch 内闭环（log.error + CAS 回退 PENDING + `task_plan_failed`，void 异步方法不重抛）�?
- **线程�?*：`PlannerDecomposeExecutorConfig`（helloai-start，Bean `plannerDecomposeExecutor` core 2 / max 4 / queue 20 / AbortPolicy�? `PlannerDecomposeProperties`（prefix `helloai.planner.decompose`：core-pool-size / max-pool-size / queue-capacity / planning-timeout-minutes�? application.yml 配置段�?
- **兜底任务**：`TaskMapper.selectTimedOutPlanning`（注解式 SQL，仅�?id/title/status/create_time/update_time，避开 JSONB 大字�?typeHandler 问题�? `PlanningTimeoutTask`（helloai-job，`fixedRate=30s` + `scheduler:lock:PlanningTimeout` UUID token + Lua 比对解锁，照 AssignedSubTaskTimeoutTask 范式）；超时阈值读 `PlannerDecomposeProperties.planningTimeoutMinutes`（默�?10 分钟，实施偏差：原计划放 AgentDispatchProperties，统一收口到拆解配置段）；recover CAS PLANNING→PENDING 成功才记 `task_plan_timeout_recovered` timeline，BATCH_LIMIT=50�?
- **前端三文�?*：`TaskList.vue` handlePlan 提交即返回（「拆解已提交，草案生成中�? 立即开审阅弹窗，不�?await 草案）；`PlanReviewDialog.vue` 打开即轮询（3s 间隔�? 分钟上限，并行查 planDrafts + getById：草案非空停 / 任务回退 PENDING 提示「拆解失败，请回任务列表重试�? �?PLANNING 或超时停；弹窗关闭与组件卸载均停定时器）+ 生成中提示；`RequirementChat.vue` finalize/regenerate 文案改异步语义（「正在后台拆解…」）；`task.ts` plan 注释同步�?

#### 3. 验证结果

- **测试拆分迁移**：`PlannerAnalysisServiceTest` 重写�?13 例（新构造器 mock PlannerDecomposeAsyncService，核心新用例：异步提交返回空 / 线程池拒绝回退 PENDING + BizException）；新建 `PlannerDecomposeAsyncServiceImplTest` 12 例（幂等守卫 / 成功落库�?timeline / llm_call_end 观测字段断言 / 失败回退不抛异常 / 幽灵依赖拒绝 / validateDependencies）；新建 `PlanningTimeoutTaskTest` 6 例（@Nested：锁占用 / 无记�?/ 单条回收 / CAS 失败跳过 / 单条失败不中�?/ Lua 解锁）�?
- **helloai-core 指定测试 25/25 全绿 + helloai-job PlanningTimeoutTaskTest + AssignedSubTaskTimeoutTaskTest 全绿**；`vue-tsc --noEmit` 0 �?+ eslint 改动文件 0 error�?
- **环境要点固化**：根 pom `skipTests=true` 默认跳测试，跑测试必须显�?`-DskipTests=false`；多模块跑测试需 `-am`（否则用本地仓库�?jar �?IncompatibleClassChangeError）；测试命令模板 `mvn -pl helloai-core test "-Dtest=类名" -DskipTests=false "-Dsurefire.failIfNoSpecifiedTests=false"`�?

#### 4. 影响与遗�?

- 影响：① 拆解提交从最�?245s 同步等待变为毫秒级返回，前端不再有超时倒挂；② LLM 耗时不再占用 HTTP 线程�?DB 事务；③ 卡死 PLANNING 任务 10 分钟后自动回收可重试；④ API 契约与既�?confirm/reject/listDrafts 链路零变化�?
- 部署注意：无 Flyway、无 MQ 变更，重启后端生效；`helloai.planner.decompose` 配置段有默认值可不改 yml�?
- 遗留：① 真实环境端到端回归（提交→轮询→草案展示 / 超时兜底路径）待用户重启后端后实测；�?草案结果推送可后续升级 SSE（当�?3s 轮询已满足体验）；③ 本轮代码与本文档�?git 提交，待用户确认后提交�?

---

### 6.104 附件同名去活版本�?+ 核验死信显式化：Reviewer 版本冲突死循环收口（2026-08-19�?

#### 1. 范围

- **背景**：用户实测「关键提醒与风险预案」子任务（�?0天每日每周执行表整合.md》附件）同名 3 版本共存且字节数不同，Reviewer LLM 以「交付物版本冲突」反复驳�?6 次——附件无版本语义，同名重复上传全�?ACTIVE，核验侧按全量附件把多版本并列视为冲突；核验达上限后无显式死信事件、无人工介入标记，时序图也不呈现该操作，AI Agent 完成复杂任务会一直死循环。用户方案：�?附件增加有效/无效状态，每次提交同名附件优先把前面版本置无效再提交最新版，历史记录可回查被打回附件（被打回默认无效）；② 3 次错误后「人工介入并改派」按死信队列操作一并写入时序图（显�?`sub_task_review_dead_letter` 死信事件，与调度死信对称）�?
- **本轮内容**：附件版本化（register 同名去活 + 双查询语义拆分）+ 核验�?4 处消费方切平台可信视�?+ 核验达上限显式死信事件与人工介入 timeline + Prompt 模板版本豁免 + 前端时序�?OPS/DLQ 泳道补全与历史回�?+ 测试与验证脚�?+ CODE_STYLE 两条准则 + **驳回打回附件自动失效（用户后续诉求：SKILL 同步告知 Agent 旧附件被打回会失效，须重新上传）+ 三角�?SKILL.md + uploadArtifact 工具描述补版本语�?*�?
- **明确不做**：不改附件存�?物化链路；不改核验计分逻辑本身；不做附件软删除入口（DELETE 状态枚举预留，本轮不落删除接口）；不做「同名去�?+ 打回�?INACTIVE」之外的其他状态联动规则（�?DELETED 自动迁移、过期清理等）�?

#### 2. 实际落地

- **附件版本化（后端�?*：`AttachmentStatus` 枚举（ACTIVE/INACTIVE/DELETED）落 attachment.status 语义；`AttachmentServiceImpl.register` �?save 前对�?`subTaskId` + �?`fileName` + ACTIVE 的存量附件批量置 INACTIVE（`lambdaUpdate().eq(...).eq(...).set(INACTIVE).update()` 单链，不误伤不同名附件），最新版自然成为唯一 ACTIVE；`list(subTaskId)` 保持全量（含 INACTIVE，管理页目录归类与历史回查），新�?`listActive(subTaskId)` 平台可信视角（仅 ACTIVE）�?
- **4 处消费方切换 listActive**：`SubTaskReviewServiceImpl.buildAttachmentList/readableAttachments`（核验证据）、`SubTaskExecutionServiceImpl.loadUpstreamContent`（上游装载）、`TaskDeliverableServiceImpl.appendSubTaskDeliverables`（交付物打包）、`McpToolServiceImpl` 附件查询（MCP 通道）——核�?装载/打包只见唯一有效版，同名历史版本只留作审计回查�?
- **Prompt 模板版本豁免**：`prompts/subtask-review.md` 补版本说明——同名多版本存在时按各文件名 ACTIVE 版本核验，不得以「版本冲突」单一理由驳回（配�?register 去活后核验侧本就收口到唯一 ACTIVE）�?
- **核验死信显式�?*：`SubTaskReviewServiceImpl` �?reworkCount 达上限（默认 3）分支记 `sub_task_review_dead_letter` timeline（AgentRole.SYSTEM，payload `reason=rework_limit_exceeded` + `reworkCount` + `maxRework`），与调度死�?`sub_task_dead_letter` 对称；同时调 `SubTaskServiceImpl.markManualIntervention` �?`sub_task_manual_intervention_required` timeline（payload �?reason，人工介入原因全�?7 类：rework_limit / review_skip_execution_dense_no_capability / review_skip_no_evidence / fallback_skip_policy / fallback_skip_policy_restricted / fallback_skip_execution_dense / dispatch_skip_execution_dense）；`sub_task_manual_rework_reset`（人工改派重置返工计数）沿用，三事件构成「核验熔�?�?人工打捞 �?改派重做」闭环�?
- **前端**：`sequenceFlow.ts` 时序图补 OPS/DLQ 泳道映射——LABEL 新增 `sub_task_review_dead_letter`（核验熔断入死信，归 DLQ�? `sub_task_manual_intervention_required`（人工介入待处理，归 OPS�? `sub_task_manual_rework_reset`（人工驳回改派，�?OPS�? INTERVENTION_REASON 映射人话�?reason + inferNote 三类注释 + 死信/核验死信事件循环折叠；`SubTaskDetail.vue` EVENT_META �?3 �?+ 产出附件卡片历史回查（`showHistoryAtt` 开�?+ 旧版本行灰显/删除�?+ 「旧版本」标记）；`AttachmentList.vue` 顶部 `activeOnly` 开关（默认开启）+ INACTIVE 行灰显，目录计数同步�?activeOnly 视角�?
- **CODE_STYLE 两条准则**：�? 代码修改必须符合本规范；测试优先�?ps1 脚本验证（而非仅单测用例）�?
- **驳回打回附件自动失效（用户后续诉求）**：用户实测发现「打回后旧附件仍�?ACTIVE 参与下次核验」是 register 同名去活未覆盖到的另一面——若 Agent 被打回后只修改本地文�?+ �?output + submitResult（不重新 uploadArtifact），或上传不同名新文件，�?ACTIVE 附件仍被核验�?listActive 命中，复现死循环。落地：�?`AttachmentService` 接口新增 `invalidateBySubTask(Long subTaskId)`，实现用 `lambdaUpdate().eq(subTaskId).eq(ACTIVE).set(INACTIVE).update()` 单链批量去活；② `SubTaskServiceImpl` 通过 `ObjectProvider<AttachmentService>` 懒解析打破与 `AttachmentServiceImpl` 的构造器循环（后者依赖本服务�?register 归属校验，项目惯例见 CompositeArtifactStorage / WebSearchServiceRouter / ExecutionDispatchValidator）；�?rework()（自动核验驳回返工）+ reworkFresh()（人工驳回改派）两个打回入口在状态迁移后统一调私�?`invalidateAttachmentsOnRework(subTaskId)`（best-effort + warn 不阻断返工主链路，与 sendReworkInboxNotification 哲学一致）；④ 三角�?SKILL.md 同步告知外部 Agent——`executor/SKILL.md` §0.1 `uploadArtifact` 工具行补「版本语义（§6.104）」段、「注意事项」加「返工时附件版本语义」一条；`planner/SKILL.md` §0.1 `uploadArtifact` 工具行补版本语义一句；`reviewer/SKILL.md` 审查原则补「按 ACTIVE 版本核验、不得以版本冲突驳回（�?.104）」一条（�?prompt 模板版本豁免对齐）；�?`McpMcpServer.uploadArtifact` @Tool description Gotchas 补「版本语义（2026-08-19，�?.104）」段，工具描述直接进 LLM 上下文，�?SKILL 之外最强约束�?

#### 3. 验证结果

- **测试 5 �?+ 驳回失效 4 类共 99 用例全绿**：`AttachmentServiceImplTest` 17�?3 上一轮：listActive �?ACTIVE / register 同名去活 / 不同名不触发去活；本�?+2：invalidateBySubTask 主路�?+ nullId 跳过；Mockito 陷阱——SFunction 方法引用每次编译为新实例，verify �?`any(SFunction.class)+eq(�?`；spy �?ServiceImpl.save 触碰 baseMapper，须 `doReturn(true).when(x).save(any())` 拦截）；`SubTaskReviewServiceTest` 28�?1：reworkCount 达上�?ArgumentCaptor 断言 `sub_task_review_dead_letter` 事件 payload �?reason/reworkCount/maxRework）；`SubTaskExecutionServiceTest` / `McpToolServiceTest` / `TaskDeliverableServiceTest` list→listActive 切换�?/3/3 处）�?*本轮 `SubTaskServiceHandoverTest` 11 用例 0 失败，新�?`verify(attachmentService).invalidateBySubTask(SUB_TASK_ID)` 断言 reworkFresh 触发附件去活**；`SubTaskServiceQuotaTest` 4 用例 + `SubTaskServiceIsReadyTest` 8 用例构造参数适配 ObjectProvider<AttachmentService>，无新增用例�?
- **前端**：`vue-tsc --noEmit` 0 �?+ eslint 改动文件 0 error（el-tag 多行格式修复 warning）�?
- **验证脚本**：`scripts/powershell/verify-attachment-version.ps1`（规�?6 UTF-8 �?+ 单引号拼接；S0 健康 / S1 上传 v1 / S2 同名 v2 / S3 断言同名校两�?INACTIVE+ACTIVE / S4 不同名不触发去活 / S5 ACTIVE 视角�?2 行且同名取最新）`ParseFile` 静态自检 0 error，待真实环境跑（需 AgentId/SubTaskId/ApiKey）�?

#### 4. 影响与遗�?

- 影响：① 同名附件重复提交自动版本化，核验/装载/打包只见唯一有效版，「版本冲突」死循环根除；② 核验熔断在时序图 DLQ 泳道显式可见，人工介�?改派（OPS 泳道）可追溯、可恢复（重置返工计数后重新分发）；�?附件的标�?字节数不再参与「冲突」判定，Reviewer 聚焦 ACTIVE 版内容一致性�?
- 部署注意：无 Flyway、无 MQ 变更，重启后端生效；旧数据中多版本并�?ACTIVE 的历史附件在首次 listActive 视角下会同时可见（不做存量迁移），管理页可手动感知�?
- 用户已手动停止「关键提醒与风险预案」子任务，改动完成后可重新分发验证：同名附件多轮提交应不再触�?Reviewer 反复驳回，核验熔断后应可见「人工介入待处理」→「人工驳回改派」闭环�?
- 遗留：① `verify-attachment-version.ps1` 真实环境实测；② 重新分发后的人工介入 �?改派 �?重做端到端观察；�?DELETED 状态的删除入口（预留未做）；④ 本轮代码与本文档�?git 提交，待用户确认后提交�?

---

### 6.105 Planner 对话完善：意图确认弹�?+ 联网搜索可视�?多轮 + 选项单列（V41�?026-08-19�?

#### 1. 范围

- **背景**：用户对 planner 对话提出四项完善：① 联网搜索无可查验信息（是否真搜了、搜了什么、来源是否正确均不可见）；② 联网搜索仅首轮触发，希望多轮都能用；�?转方案意图词过少，二次确认希望改为弹窗（仅确�?取消、无推荐项）；④ 澄清选项挤在一行，希望每选项独占一行。查验形态经调研对齐 DeepSeek「已搜索 xx 个网页�? Kimi「已联网检�?· N 个信源」折叠条形态（挂在 assistant 回复上，不走独立时间线事件）�?
- **本轮内容**：选项纵向单列 CSS；意图词组合正则扩充�? 组模式）+ 二次确认�?structured 确认卡（selections 快照三通道判定）；新增 `WebSearchOutcome` 归一化记录，搜索结果�?assistant payload `webSearch` �?+ 前端 `WebSearchBar.vue` 折叠查验条；CLARIFY 搜索从仅首轮放宽为每轮�?
- **明确不做**：thinking 内容透传（需推理模型 + executeSync 链路改造，另立项）；正文内联引用角�?`[citation:X]`（二期演进）；DeepSeek 原生 `enable_search`（Spring AI 1.1.8 未暴露该字段，路�?B）；�?Flyway（`requirement_message.payload` 既有列）、零新增 REST 端点�?

#### 2. 实际落地

- **选项单列（问�?4�?*：`StructuredQuestionCard.vue` `.sq-options` �?`flex-direction: column` + `.sq-option` `width: 100%`，追问卡/确认�?历史只读回显三处统一生效�?
- **意图词扩充（问题 3�?*：`INTENT_TO_CLARIFY_PATTERN` 保留既有 18 个方案系变体，追�?8 组「动作词 + 可选量�?+ 计划/任务/方案」组合正则（新建/创建/建立/建、帮�?给我/给、生成、做、来、出、帮我总结、总结�?�?�?一下），覆盖「新建个计划吧」「给一个方案」「帮我总结一下」「帮我生成计划」等表达；组合匹配避免「任务」「计划」裸词子串误触，误触由确认弹窗兜底�?
- **确认卡化（问�?3�?*：新�?`buildConfirmAskPayload()` 构�?1 �?2 选项 structured payload（id `confirm-switch`，仅确认/取消、无 recommended、allowCustom=false，NON_NULL 序列化保�?recommended 字段不出现）；意图命中分�?assistant 消息正文仍落 `CONFIRM_ASK_MESSAGE`（transcript 不变），payload 改为确认卡�?*关键实施发现**：卡片提交文本形如「问题：确认」不命中 `CONFIRM_PHRASE_PATTERN` 开头锚定，故新�?selections 快照判定通道（`isConfirmCardAccept` + `confirmCardValueOf`）：点「确认」走切换 CLARIFY 分支、点「取消」清标记继续 CHAT；手写确认词与再次意图词仍兼容，状态机其余零改动；CHAT 50 轮上限放行条件同步纳入卡片确认�?
- **搜索查验条（问题 1�?*：新�?`WebSearchOutcome`（`planner/search` 包，`@Data @Builder`：provider/query/costMs/total/results/failed/reason + `toContextText()` 承接�?renderWebSearchContext 注入文本）；`doWebSearch` 返回�?String→WebSearchOutcome，异常降�?failed outcome、查询词空白返回 null（未搜不落键）；`runLlmRound` 签名改收 outcome（retry/switchToClarify �?null）；assistant payload 合并 `webSearch` 键（structured/freeform 追问分支 + **终稿分支**——终稿时�?payload 重载，未搜索保持�?3 参形态）；前端新�?`WebSearchBar.vue`（scoped 折叠条：「�?已联网搜�?N 个来源（供应�?· Xms）」，failed/total=0 态可见，展开显示搜索�?+ 来源标题超链�?站点/摘要�? `types/clarify.ts` �?`WebSearchTrace`/`WebSearchSource` + `RequirementChat.vue` renderMessages 随行渲染（实时与历史回显统一）�?
- **多轮搜索（问�?2，路�?A�?*：doRound 触发条件去掉 `rounds == 0`，CLARIFY 每轮且开关开启即搜索，查询词取当前轮消息�?40 字；不做数据库级去重（成本由 MAX_ROUNDS=20 封顶，每轮折叠条可见搜索词供查验）；switchToClarify 切换轮维持不搜索�?
- **搜索查询词语义守卫（第二轮修复，用户实测发现�?*：点确认卡切入澄清首轮时，当前轮消息是卡片提交文本（「检测到你想把讨论整理成落地方案…：确认」），被直接截前 40 字当搜索词发给博�?�?搜不到任何网页，查验条如实显示「未搜到相关网页」暴露缺陷。修复：新增 `resolveSearchSource` + `lacksSearchSemantics`（无检索语义判定：确认�?/ 确认卡提交文本（题面前缀或卡选快照）/ 长度 �?`INTENT_ONLY_QUERY_LIMIT=20` 的纯意图短句），命中则倒序回退最近一条有实际内容�?user 消息（通常是触发意图前的讨论主题）作查询词来源；全部无意义时返回空�?�?`doWebSearch` 视为未搜不落查验条；长句携带主题内容（如「我�?60 天备考架构师考试，帮我整理成方案」）不受守卫影响仍可作查询词�?

#### 3. 验证结果

- **后端**：`RequirementClarifyServiceTest` 55/55 全绿�?3 �?+ 第二轮修复新�?2 例：确认卡切入时搜索词回退历史主题消息断言 / 历史无可回退主题时不发起搜索断言；含新增 8 例：新意图词置位 + 确认�?payload 结构断言 / 5 个新意图词覆�?/ 裸词防误�?/ 卡片点确认经快照�?CLARIFY / 点取消继�?CHAT / CLARIFY �?2 轮触发搜�?+ payload 断言 / 搜索异常 failed=true 不阻断主流程；既�?3 处确认消息断言�?payload=null �?anyString）。踩坑：`-pl helloai-core` 单跑需 `-am` 否则用本地仓库旧 jar 报「找不到 HeartbeatProperties」（§6.103 已固化的坑再次验证）；Jackson 默认序列�?null 字段导致 `recommended:null` 出现，确认卡改用 `objectMapper.copy().setSerializationInclusion(NON_NULL)`�?
- **前端**：`vue-tsc --noEmit` 0 错�?

#### 4. 影响与遗�?

- 影响：① 联网搜索全程可查验（是否搜了、搜了什么词、来源内容与耗时，失�?空结果也可见）；�?CLARIFY 每轮都能用联网搜索（成本与轮数成正比，已知代价）；③ 转方案确认从手打改为点选弹窗，误触成本更低；④ 选项单列提升可读性；�?API 契约不变（仅消息 payload JSON 扩展），老消息无 webSearch 键不受影响�?
- 部署注意：无 Flyway、无 MQ 变更，重启后端生效�?
- 遗留：① 手工回归清单（确认弹窗交�?/ 折叠条展开回显 / 多轮搜索）待用户重启后端实测；② thinking 透传与内联引用角标为二期项；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

---

### 6.106 DeepSeek 原生联网搜索 adapter：把 DeepSeek �?搜索引擎"替换博查（V42�?026-08-19�?

#### 1. 范围

- **背景**：用户实测博查搜索效果不理想，调研两类增强方案（API 代理 search2ai / MCP 服务器）后确认：代理方案会切断查验条数据源，项目仅是 MCP Server 无客户端能力；而四�?Provider 原生搜索能力核实结果为——DeepSeek �?Anthropic 兼容端点提供 `web_search_20250305` 服务端工具（单次调用返回结构�?`web_search_tool_result`），Kimi 需客户�?tool_calls 回显循环，MiniMax 亦支持同构服务端工具。用户选定第一步方案：只接 DeepSeek 原生搜索且当"搜索引擎"用�?
- **本轮内容**：新�?`DeepSeekNativeSearchServiceImpl`（实现既�?`WebSearchService` 接口，provider=`deepseek-native`�? `WebSearchProperties` �?5 �?deepseek-* 配置字段 + 接口/路由 Javadoc 同步 + 新增 11 例单测。澄清主 LLM 调用链、prompt 模板、`WebSearchOutcome`、查验条、前�?*零改�?*（`WebSearchResult` 归一化契约不变）�?
- **明确不做**：Kimi/MiniMax 原生搜索适配（二期）；per-planner 动态路由搜索源（当前按会话级开�?+ 全局 provider 配置）；正文内联引用角标（二期）；不新增 Flyway / REST 端点�?

#### 2. 实际落地

- **`DeepSeekNativeSearchServiceImpl`**（`planner/service/impl`，照博查/Tavily 同构）：`@ConditionalOnProperty(havingValue="deepseek-native")` 条件装配，Router �?provider 匹配零改动自动生效。请求：POST `{deepseekBaseUrl}`（默�?`https://api.deepseek.com/anthropic/v1/messages`），�?`x-api-key` + `anthropic-version: 2023-06-01`，体�?Anthropic messages 格式：单�?user 消息「Perform a web search for query: X�? `tools:[{type:"web_search_20250305", name:"web_search", max_uses:1}]`（限单次检索控成本），`max_tokens` 压低（默�?1024，正文是副产品不消费）。解析：遍历响应 content 块找首个 `web_search_tool_result`，其 content 列表逐条映射 `WebSearchResult`（title/url/content 引用原文，缺则回退 snippet 字段；siteName �?URL host 推导；snippet �?maxSnippetChars 截断）。失败语义照契约：Key 未配�?/ 空查�?/ �?2xx / 解析异常一律降级空列表不抛异常�?
- **`WebSearchProperties` �?5 字段**：`deepseekBaseUrl` / `deepseekApiKey`（建�?env DEEPSEEK_API_KEY 注入�? `deepseekModel`（默�?deepseek-chat�? `deepseekMaxTokens`�?024�? `deepseekTimeoutMs`�?5s，独立于全局 timeoutMs：该路径是一次完�?LLM 调用，耗时显著高于普通搜�?API）�?
- **切换方式**：`helloai.web-search.provider=deepseek-native` + `deepseek-api-key` 即切搜索源，重启生效；回退博查只需改回 provider 配置�?

#### 3. 验证结果

- **新增 `DeepSeekNativeSearchServiceImplTest` 11/11 全绿**：JDK 内置 `HttpServer` 起本地桩端点模拟 Anthropic 兼容端点（不引新依赖），覆盖：结构化结果块解析映射（title/url/host 推导/未超阈不截断�? 超长 snippet 截断 / 请求报文断言（web_search_20250305 声明 + 查询�?+ x-api-key 头）/ limit 截断 / snippet 字段回退 / �?2xx / 无结果块 / �?Key 不发请求 / 空查询不发请�?/ 非法 JSON�?
- **回归**：`RequirementClarifyServiceTest` 55/55 全绿（主链路零改动验证）�?
- **踩坑再验�?*：`surefire:test` 不带 `-am` �?`NoSuchMethodError: setDeepseekApiKey`——本地仓库旧 helloai-common jar，加 `-am` 重跑即绿（�?.103/6.105 已固化的坑第三次验证）�?

#### 4. 影响与遗�?

- 影响：① 搜索源从博查切换�?DeepSeek 原生后，结构化结果（查询�?来源明细）完整落 payload，查验条渲染链路不变；② 省去独立搜索服务订阅，复�?DeepSeek API Key；③ 代价：每次搜索是一次完�?LLM 调用，耗时（超时已独立放宽�?15s）与成本高于普通搜�?API，成本随 CLARIFY 轮数线性�?
- 部署注意：无 Flyway、无 MQ 变更；需配置 `helloai.web-search.provider=deepseek-native` + `helloai.web-search.deepseek-api-key`（或 env）后重启后端生效�?
- 遗留：① 真实 DeepSeek Key 端到端回归（查验条展�?DeepSeek 来源）待用户配置后实测；�?Kimi/MiniMax 原生搜索适配�?per-planner 动态路由为二期项；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

---

### 6.107 联网搜索 URL 分离 + 网页直取：用户给出的站点直接访问而非当搜索词（V43�?026-08-19�?

#### 1. 范围

- **背景**：用户实测发现新缺陷——消息含 URL 时（「给我一份快速上�?https://open.maic.chat/ 的操作手册」），整段输入含�?URL 被截�?40 字直接当搜索词发给搜索引擎，查验条显示「未搜到相关网页」：搜索引擎�?URL 文本检索效果极差。用户要求：应自动提取输入中的网址�?*分离后直接访�?*，而非全部当搜索内容�?
- **本轮内容**：新�?`WebPageFetchService`（直接访问用户给出的网页抓取正文�? `doWebSearch` URL 分离改造（提取 URL �?直取页面注入上下文；搜索词改用剥�?URL 后的语义文本，纯 URL 消息回退域名�? `WebSearchOutcome.fetchedPages` + payload 新增 `fetched` 查验�?+ `WebSearchProperties` �?4 �?urlFetch-* 配置。查验条/前端零改动（直取页面映射为来源置顶合并进 results；`fetched` 为未知键前端天然忽略）�?
- **明确不做**：前�?fetched 键专属展示（二期，当前查验条来源列表已含直取页面）；SPA 页面 JS 渲染拓取（正则式抓取�?SPA 空壳页降�?ok=false 可查验）；多页深度爬取（限前 2 �?URL）；不新�?Flyway / REST 端点�?

#### 2. 实际落地

- **`WebPageFetchService` 接口 + `WebPageFetchServiceImpl`**（planner/service �?impl，纯 JDK 无新依赖）：GET 目标 URL（跟随重定向 + 伪浏览器 UA）→ 限流读响应体�?MB 上限防超大页面）�?仅接受文本类 Content-Type �?轻量 HTML 转纯文本（正则剔�?script/style/noscript/svg/head 块与注释 �?去标�?�?解码高频实体 �?折叠空白）→ 提取 `<title>`（缺失回退 host）→ 正文�?`urlFetchMaxTextChars`（默�?4000）截断。失败语义照搜索契约：非 2xx / 非文�?/ 空正�?/ 异常一�?ok=false + reason，绝不抛异常�?
- **`doWebSearch` URL 分离改�?*（`RequirementClarifyServiceImpl`）：�?`URL_IN_TEXT_PATTERN` 提取消息中全�?http(s) 链接（尾随中文标�?括号不计入）；② 搜索�?= `extractQueryKeyword(stripUrls(消息))`（剥�?URL 的语义文本），剥离后空白且含 URL �?回退首个 URL 的域名作搜索词；�?`fetchUserPages` 直取�?N 个去�?URL（N=`urlFetchMaxPages` 默认 2，总开�?`urlFetchEnabled` 默认开）；�?直取成功页面映射�?`WebSearchResult`（snippet 取正文前 maxSnippetChars�?*置顶**合并�?results（总条�?cap �?maxResults 内，页面优先），搜索结果补后；⑤ 查询词空白且无成功直取时才返�?null（纯 URL 消息域名回退后总会搜索）；搜索异常降级分支也携�?fetchedPages�?
- **`WebSearchOutcome.fetchedPages`** + `toContextText()` 增「直接访问用户提供的网页后抓取的内容」节（仅 ok 页面，第一手资料优先）置于搜索结果节之前；两节均空维持原占位符�?
- **payload `fetched` �?*（buildWebSearchMap）：每条 `{url,title,ok,textChars,reason?}`，含失败记录可查验；�?URL 时不落键�?
- **`WebSearchProperties` �?4 字段**：`urlFetchEnabled`（true�? `urlFetchTimeoutMs`�?s�? `urlFetchMaxPages`�?�? `urlFetchMaxTextChars`�?000）�?

#### 3. 验证结果

- **新增 `WebPageFetchServiceImplTest` 10/10 全绿**：JDK HttpServer 桩站点，覆盖 title 提取/剔噪转纯文本（script/style/注释断言不泄漏）/实体解码/超长截断/�?title 回退 host/�?2xx/非文本类�?SPA 空壳/非法 URL/连接拒绝全部降级不抛�?
- **`RequirementClarifyServiceTest` 59/59 全绿**�?5 + 新增 4 例：URL 分离搜索词断言 + 直取置顶来源 + fetched 落键 / �?URL 回退域名 / 直取失败不进来源但记录可查验 / 开关关闭不发起抓取）；`DeepSeekNativeSearchServiceImplTest` 11/11 回归全绿。既�?mock 零改动兼容（`isUrlFetchEnabled` mock 默认 false 自然走原路径）�?
- **踩坑再验�?*：`surefire:test` 不带 `-am` 再用本地仓库�?jar �?NoSuchMethodError（第四次验证，结论：**跑测试永远用 `mvn -pl helloai-core -am test` 完整命令**）；`-Dtest` 多类用逗号分隔（`+` 号会静默不执行）�?

#### 4. 影响与遗�?

- 影响：① 用户给出的站点被直接访问，正文（�?4000 �?页）注入 Prompt，LLM 可基于第一手站点资料作答（如生�?openMaic 操作手册）；�?搜索词不再被�?URL 污染；③ 查验条来源列表置顶展示直取页面（标题可点击跳转），payload fetched 键可审计抓取成败；④ 前端/API 契约零变化�?
- 部署注意：无 Flyway、无 MQ，重启后端生效；urlFetch-* 均有默认值可不改 yml；若目标站点反爬严格会降�?ok=false（查验条可见原因），不阻断主流程�?
- 遗留：① 真实环境回归（含 URL 的澄清消息：直取来源置顶/正文注入效果/反爬降级路径）待用户重启后端实测；② SPA 站点可二期引入无头浏览器或站�?sitemap 拓取；③ 前端 fetched 专属展示（如「已直接访问 N 个网页」独立状态行）为二期项；�?本轮代码与本文档�?git 提交，待用户确认后提交�?

### 6.108 uploadArtifact 工具口径更新：文件内容先走代理上传、本工具仅登记（2026-08-19�?

#### 1. 范围

- **背景（承 §6.99/6.102�?*：`McpMcpServer.uploadArtifact` �?`@Tool description` 仍残留旧契约文案「实际文件请�?PUT �?MinIO 再把 storageUrl 传进来」，�?SKILL.md §6.99 新指引（文件内容先经 `POST /api/artifacts/upload` 代理上传，平台转�?MinIO 并注册一步到位）矛盾，会误导外部 Agent 直连服务器版不可达的 MinIO（仅绑定内网），是外�?Agent「无法上传附件、只能登记空�?metadata」的诱因之一�?
- **本轮内容**：纯文档口径修正（工具描述与 Javadoc 文本，零功能/零契约改动）——`McpMcpServer.uploadArtifact` 描述改为「文件内容场景先�?`POST /api/artifacts/upload`（multipart + Bearer，返�?{attachmentId, storageUrl}）；本工具仅适用于『对象已在别处可访问』的登记场景，只注册 DB 元数据不传输文件内容」；`storageUrl` �?`@ToolParam` 描述同步；`McpToolServiceImpl.uploadArtifact` Javadoc 同步为同口径�?
- **明确不做**：不改工具签�?逻辑/参数约束；不�?REST 路径�?SKILL.md（其口径已是新版）；不动 `ArtifactUploadService` 命名（用户已确认保持现状，�?.108 命名决策：Artifact 为平台领域术语族，AgentUploadService 语义错误）�?

#### 2. 实际落地

- **McpMcpServer.java**：`@Tool(name="uploadArtifact")` description �?Gotchas 首条由「实际文件请�?PUT �?MinIO 再把 storageUrl 传进来」改为「文件内容场景：先走 POST /api/artifacts/upload（multipart/form-data + Authorization: Bearer <API_KEY>，参�?file + subTaskId + 可�?mimeType）上传文件内容，平台转存 MinIO 并注册附件一步到位，返回 {attachmentId, storageUrl}；服务器�?MinIO 仅绑定内网（公网不可达），不要直�?MinIO PUT 文件�? 新增「本工具仅适用于『对象已在别处可访问』的登记场景…不传输文件内容」；`storageUrl` @ToolParam 描述改为「已有可访问对象的存储地址（仅登记场景；文件内容场景请�?POST /api/artifacts/upload），必填」。�?.104 版本语义段保留原样�?
- **McpToolServiceImpl.java**：uploadArtifact Javadoc 同步为「文件内容场景请先经 POST /api/artifacts/upload 上传（平台转�?MinIO 并注册一步到位）；本工具仅适用于『对象已在别处可访问』时的登记（只注�?DB 元数据记录，不传输文件内容）」�?

#### 3. 验证结果

- IDE 问题面板：两文件无语法错误（仅历史遗留未使用 import 警告，与本轮无关不扩散清理）�?
- 说明：本轮为纯文本描述改动，不涉及逻辑，未�?mvn 编译（Node 沙箱�?mvn 会触�?JVM 崩溃，编译验证由用户侧执行或随下次打包天然覆盖）�?

#### 4. 影响与遗�?

- 影响：外�?Agent �?MCP 工具清单看到�?uploadArtifact 描述�?SKILL.md §6.99 一致，不再被旧文案引导直连 MinIO；文件内容上传路径明确指�?`POST /api/artifacts/upload`�?
- 部署注意：需重新打包后端 jar（helloai-core 变更）并部署服务器后生效�?
- 遗留：① 已登记的空壳附件（�?.102 遗留 SQL）与服务�?MinIO 数据仍待用户清理；② 本轮代码与本文档�?git 提交，待用户确认后提交�?

---

### 6.109 联网搜索 V44：SPA 空壳元数据兜�?+ 直取失败域名前置�?026-08-19�?

#### 1. 范围

- **背景**：用户实测日志暴�?V43 两个叠加缺陷——① `open.maic.chat` �?JS 渲染�?SPA，直取拿到的 HTML 只有空壳（textChars=0），而空壳页通常携带�?`<title>`/meta 描述被失败路径整体丢弃；�?UA「HelloAI-WebPageFetch」自曝爬虫身份易被反爬拦截；�?直取全部失败时搜索词不含域名（「给我一份如何快速上�?，使用openMaic的操作手册…」），搜索引擎无从检索该站点公开资料；叠加博�?API Key 未配置（搜索被跳过）�?results=0，LLM 无任何资料可用�?
- **本轮内容**：直取服�?SPA 元数据兜�?+ 浏览器风�?UA；搜索链路直取失败域名前置增强搜索词；`WebPageContent` �?`metaOnly` 字段并落 payload。属真实缺陷修复�?
- **明确不做**：无头浏览器渲染 SPA（引 Playwright 太重，元数据兜底+域名前置已够用）；搜�?Key 配置属环境配置不在代码范围（已给用户两条配置路径）�?

#### 2. 实际落地

- **WebPageFetchServiceImpl（V44�?*：① UA 换浏览器风格（Chrome/126�? �?Accept-Language；② 正文为空时新�?`salvageMetaText` 兜底路径：提�?`<title>` / meta description / og:description / og:site_name（属性序双向兼容 content 在前/在后），拼作「站点名称：X；站点描述：Y」最低限度资料，ok=true + metaOnly=true；全部缺失才按失败（reason 改为「页面正文为空且无元数据」）�?
- **WebPageContent**：新�?`metaOnly` 布尔字段区分元数据兜底与真实正文抓取�?
- **RequirementClarifyServiceImpl.doWebSearch（V44�?*：搜索词构建改三分支——纯 URL 消息回退域名（保�?V43）；语义文本存在但直取无一成功 �?搜索词前置首个域名（如「open.maic.chat 介绍下这个平台」）让搜索引擎检索站点公开资料；直取成功（�?metaOnly 兜底）则不加前缀（第一手资料已在手）。payload �?fetched 记录 metaOnly=true 时落 `"metaOnly":true` 键（前端忽略未知键零兼容成本）�?
- **WebSearchOutcome** Javadoc �?metaOnly 语义说明（渲染链路无变化：metaOnly 直取按成功页注入 Prompt 直取节）�?

#### 3. 验证结果

- `WebPageFetchServiceImplTest` 12/12 全绿（原 SPA 失败用例改造为 V44 三例：title+meta 兜底成功 / og 属性序反转提取 / 无元数据仍失败）�?
- `RequirementClarifyServiceTest` 61/61 全绿（新�?2 例：直取失败域名前置断言搜索词以「open.maic.chat 」开�?/ metaOnly 兜底进来源且 payload �?metaOnly 标记且不加域名前缀）�?
- `DeepSeekNativeSearchServiceImplTest` 11/11 回归全绿�?
- 环境坑再次验证：`surefire:test` 不带 `-am` 用本地仓库旧 helloai-common jar �?`NoSuchMethodError: isUrlFetchEnabled()`，换完整生命周期 `-am test` 即绿（�?.103 已固化）；surefire 嵌套类报告根 testsuite 属�?tests=0 �?testcase 元素实际存在，统计须�?testcase 元素而非根属性�?

#### 4. 影响与遗�?

- 影响：① SPA 站点（如 open.maic.chat）不再零资料，至少注入站点名+描述�?LLM 参考；�?直取失败时搜索词带域名，搜索引擎可检索该站点公开介绍/教程；③ 反爬拦截概率降低�?
- 部署注意：无 Flyway 无新配置项，重启后端生效�?*环境配置提示（用户侧待办�?*：日志中博查跳过搜索是因 `helloai.web-search.bocha-api-key`（env BOCHA_API_KEY）未配置——配置博�?Key 或切 `provider=deepseek-native` + DeepSeek Key（�?.106）二选一，否则搜索段仍无结果�?
- 遗留：① 真实环境�?URL 对话端到端回归待实测；② SPA 深度渲染（无头浏览器）为二期可选项；③ 本轮代码与本文档�?git 提交，待用户确认后提交�?

#### 5. 第二轮补充（博查 Key 到位 + 超时上调 + deepseek-native 停用决策�?

- **博查 Key 实测通过**：用户将 bochaApiKey 暂时写死�?`WebSearchProperties`；真实调�?`https://api.bochaai.com/v1/web-search` 验证 599ms 返回 code=200，响应结构（data.webPages.value[].name/url/snippet/siteName/summary）与 `BochaWebSearchServiceImpl` 解析完全匹配，重启后端即恢复联网搜索�?
- **超时默认�?3s �?8s**：发现真正影响效果的隐患——博查开�?AI 摘要（summary=true）时耗时波动大，�?3s 超时易静默降级空列表（可能是此前「效果不理想」的原因之一），`timeoutMs` 默认上调�?8s�?
- **deepseek-native 停用决策（用户拍板）**：用户此前用其他工具尝试 DeepSeek 自带联网未实现，决定停用。事实上无需删代码：`DeepSeekNativeSearchServiceImpl` �?`@ConditionalOnProperty(havingValue="deepseek-native")`，provider=bocha 时该 Bean 不装配、零干扰，处于休眠状态保留可回切；另注意其协议前提（`web_search_20250305` 服务端工具）实为 Anthropic 官方工具类型名，DeepSeek 是否真提供该兼容端点未经真实 Key 验证，启用前须先验证�?
- **安全提示**：bochaApiKey 写死在代码里，git 提交前应改回 env BOCHA_API_KEY 注入或确认仓库私有�?

---

### 6.110 高频轮询 Mapper SQL 日志刷屏治理�?026-08-19�?

#### 1. 范围

- **背景**：用户反馈日志被 `[job-scheduler-1] DEBUG c.h.c.a.m.A.selectList` 刷屏，查日志困难。根因：`logging.level.com.helloai.core: DEBUG` �?MyBatis SQL 日志（logger 名为�?Mapper 接口 FQCN，logback `%logger{36}` 缩写�?`c.h.c.a.m.A`）整体打开，而三个高频轮询任务每轮都�?`selectList` DEBUG——ExecutionCommandPoller�?s，`AgentExecutionRecordMapper.listOrphanPending`）、OutboxRelayTask�?s，`AgentCommandOutboxEventMapper`）、AgentEventCompensationTask�?5s，`AgentOutboxEventMapper.pollPending`）�?
- **本轮内容**：配置级日志治理——application.yml `logging.level` 下将上述 3 个高频轮�?Mapper 调高�?INFO，业务链�?Mapper SQL 日志保留。用户已确认选择「精准关闭轮�?Mapper」粒度（其余两个粒度：agent 域全�?/ 全局所�?mapper 全关，未采用）�?
- **明确不做**：不�?`com.helloai.core` 整体 DEBUG（业�?DEBUG 日志仍要）；不动 logback-spring.xml（Spring Boot `logging.level.*` 优先级高�?XML �?`<logger>`，只�?yaml 即生效）；不改轮询频率�?

#### 2. 实际落地

- **helloai-start/src/main/resources/application.yml**：`logging.level` 追加 3 �?+ 2 行注释（§6.110 编号说明）：
  - `com.helloai.core.agent.mapper.AgentExecutionRecordMapper: INFO`（poller 1s�?
  - `com.helloai.core.agent.mapper.AgentCommandOutboxEventMapper: INFO`（outbox relay 1s�?
  - `com.helloai.core.agent.mapper.AgentOutboxEventMapper: INFO`（outbox 补偿 15s�?

#### 3. 验证结果

- 文件保存成功；三�?Mapper 类名�?helloai-core 实际接口一一对应（Glob 确认）�?
- 生效机制：Spring Boot `logging.level.*` �?logback 初始化后�?logger 名覆盖，�?`com.helloai.core` 包级 DEBUG 更具体，优先级更高�?
- 说明：未�?mvn 编译（配置变更不涉及编译；Node 沙箱�?mvn 会触�?JVM 崩溃），生效需重新打包部署后端 jar（application.yml �?jar 内）�?

#### 4. 影响与遗�?

- 影响：job 轮询线程不再产生 Mapper SQL DEBUG；业务请求链路（�?submitResult/handleReport 涉及的表）SQL 日志保留，问�?A 排查能力不受影响。若后续仍有低频率任务（30s/60s）SQL 刷屏，可沿用同样式追加�?
- 遗留：本轮改动未 git 提交，待用户确认后提交；重新打包部署后生效（�?§6.108 工具描述改动）�?

---

### 6.111 CHAT 自由对话模式联网搜索：任意模式每�?+ 开关开启（V45�?026-08-19�?

#### 1. 范围

- **背景**：用户实测：新会话默�?CHAT 模式（V39）下开启联网开关提问「给我一份如何快速上�?https://open.maic.chat/ 的操作手册」，Planner 回复「没有联网浏览能力（无可用联网资料）」。根因：V41 放宽为每轮后联网搜索触发条件仍为 `isClarifyMode(conversation) && isWebSearchEnabled(conversation)`，CHAT 分支直接跳过 �?`webSearchContext` 为空 �?模板渲染「（无可用联网资料）」占位符 �?LLM 据实告知。`requirement-chat.md` 模板注释已标注「阶�?2 计划：CHAT 也消费检索结果」�?
- **本轮内容**：doRound 搜索条件放宽为「任意模�?+ 开关开启」；runLlmRound CHAT 分支消费 `WebSearchOutcome`（结构化追问�?payload 合并 webSearch 键、纯文本回复�?`buildWebSearchOnlyPayload` 携带查验键）；模板与 8 文件注释/文案口径同步；单测用例反�?+ 新增�?
- **明确不做**：thinking 透传、正文内联引用角标（二期项维持）；不改搜索供应商与配置项；无 Flyway / 无新 REST 端点�?

#### 2. 实际落地

- **RequirementClarifyServiceImpl.doRound（V45�?*：搜索触发条件由 `isClarifyMode(conversation) && isWebSearchEnabled(conversation)` 放宽�?`isWebSearchEnabled(conversation)`（NULL/true 视为开启），CHAT/CLARIFY 任意模式每轮都检索；成本由各自轮数上限封顶（CHAT 50 / CLARIFY 20）；查询词来源沿�?`resolveSearchSource`（URL 剥离直取 / 确认词回退历史主题消息 / 空白不搜）�?
- **runLlmRound CHAT 分支**：`webSearchOutcome` 由恒�?null 改为透传——结构化追问�?`buildQuestionPayload(chatReply, webSearchOutcome)` 合并 webSearch 键；纯文本回复在�?outcome 时走 `buildWebSearchOnlyPayload(webSearchOutcome)`（与终稿轮同形态），无 outcome 保持�?3 �?null 形态，API 契约零破坏�?
- **requirement-chat.md**：注释头更新为「{{WEB_SEARCH_CONTEXT}} 为联网资料节（V45 起：CHAT/CLARIFY 任意模式每轮按需检索后注入；未检�?检索失败时该节渲染『（无可用联网资料）』，保持 Prompt 语义节稳定）」，阶段 2 标注移除�?
- **注释口径同步�? 文件�?*：`RequirementClarifyServiceImpl` create/runLlmRound Javadoc（「首轮」→「每轮」）；`RequirementClarifyService` 接口、`RequirementConversation`（「V45 �?CHAT/CLARIFY 任意模式都生效」）、`ClarifyMessageRequest`（「首轮」→「每轮」、「不阻断澄清流程」→「不阻断对话流程」）、`WebSearchProperties` 类注释；前端 `RequirementChat.vue` webSearchTooltip（「每轮对话自动联网检索行业资�?/ 竞品 / 技术方案，注入 Prompt 增强回答质量；失败自动降级」）�?

#### 3. 验证结果

- `RequirementClarifyServiceTest` **63/63 全绿**：原 `chatRoundDoesNotTriggerWebSearch` 反转 �?`chatRoundTriggersWebSearch`（stub 搜索参数，断言 `webSearchService.search(eq("你好"), eq(5))` + prompt 注入检索结�?+ payload �?webSearch/total）；新增 `chatRoundWebSearchDisabled`（开关关闭不�?+ payload null）、`chatRoundWithUrl_fetchesPageAndInjects`（用户原始场景「给我一份快速上�?https://open.maic.chat/ 的操作手册」回归：URL 剥离进直取、`pageFetchService.fetch` 被调、prompt 含直取正文、payload �?fetched/webSearch）；既有 CHAT 用例零破坏（�?stub queryKeywordLimit �?mock 默认 0 �?查询词空 �?`doWebSearch` 返回 null �?不搜不落键）�?
- 实测日志实证：`澄清联网搜索 URL 直取成功: url=https://open.maic.chat/, textChars=9`、`query=给我一份快速上�?的操作手册`、`自由对话回复落库`�?
- 踩坑（�?.103 延续）：�?pom `<skipTests>true</skipTests>` 需 `-DskipTests=false`；`-Dtest` 需 `-Dsurefire.failIfNoSpecifiedTests=false` �?PowerShell 下参数必须整体加引号；最终命�?`mvn -pl helloai-core -am test "-Dtest=RequirementClarifyServiceTest" "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`�?

#### 4. 影响与遗�?

- 影响：① CHAT 自由对话开启联网开关后每轮真实检索并注入 Prompt，回复不再「无可用联网资料」；�?折叠查验条在 CHAT 轮同样可见（搜索�?来源/耗时，失败与空结果也可见）；�?成本与对话轮数成正比（CHAT 上限 50 轮，已知代价）；�?API 契约不变（payload 扩展，老消息无 webSearch 键不受影响）�?
- 部署注意：无 Flyway、无新配置项，重启后端生效�?*提交前安全收口（2026-08-19，git 提交前执行）**：按 §6.109 安全提示，`WebSearchProperties.bochaApiKey` 由写死值改�?`env BOCHA_API_KEY` 注入（`${BOCHA_API_KEY:}`，未配置=供应商未启用），真实 Key 不再进入仓库；部署环境需设置 BOCHA_API_KEY 后重启生效�?
- 遗留：① 真实环境 CHAT 模式�?URL 对话端到端回归待用户重启后端实测；② 本轮代码与本文档�?git 提交，待用户确认后提交�?

---

### 6.112 PLANNING 超时回收误伤已产草案任务修复�?026-08-19�?

#### 1. 范围

- **背景**：用户反馈任务确认草案报错「只�?PLANNING 状态的任务才能确认草案: taskId=2089994365468286978, status=PENDING」。数据库取证（timeline 全轨�?+ sub_task 残留 + 代码三重交叉验证）：
  - �?拆解**成功**�?8:34:22 异步提交 �?08:35:39 `task_plan_llm_call_end`（costMs=77038, finishReason=STOP�? `task_plan_generated`（draftCount=7），7 �?PENDING_PLAN_REVIEW 草案落库且依赖拓扑已回写�?
  - �?超时**误伤**�?8:44:29 `task_plan_timeout_recovered`（planningTimeoutMinutes=10）——`selectTimedOutPlanning` 只按 `status='PLANNING' AND update_time < deadline` 判定卡死，�?`task_plan_generated` 只写 sub_task 不刷�?task.update_time，超时从「进�?PLANNING」起算，把「草案已就绪、等用户人工确认」的任务当成「异步拆解卡死」回收回退 PENDING，confirmDrafts �?PLANNING 校验随即失败�?
  - �?排除用户猜测：非同一事务问题（拆解早已异步化�?7s LLM 调用�?plannerDecomposeExecutor 线程，不�?HTTP 事务内）；LLM 实际成功，非「默认任务拆解失败」�?
- **本轮内容**：`selectTimedOutPlanning` SQL �?`NOT EXISTS` 排除已有 PENDING_PLAN_REVIEW 草案的任务——有草案 = 等人工确认，永不超时回收；无草案�?PLANNING 任务（真卡死）照常回收。用户已确认选择此方案（其余候选：刷新 update_time 治标 / 新增 PLAN_REVIEW 中间态状态机细化，均未采用）�?
- **明确不做**：不引入 outbox 状态机/saga 编排（用户建议评估：本场景无 MQ 可靠性缺口、无跨服务分布式事务，拆解触发已是「DB 状�?CAS + 异步线程 + timeline 收敛 + 超时兜底」最小闭环，outbox/saga 不对症）；不�?confirmDrafts 校验；不加「确认超时自动拒绝」需求�?

#### 2. 实际落地

- **helloai-core/src/main/java/com/helloai/core/task/mapper/TaskMapper.java**：`selectTimedOutPlanning` SQL 由单表查询改为带别名 + NOT EXISTS 子查询（sub_task �?task_id 关联、status='PENDING_PLAN_REVIEW'、deleted=0 软删过滤），Javadoc 补充 §6.112 误伤修复说明�?

#### 3. 验证结果

- MCP 只读验证（postgres_helloai）：完整�?SQL（含 `update_time < now() - interval '10 minutes'`）执行无语法错误；语义验�?`EXISTS(...)=true` for taskId=2089994365468286978（has_draft=true），若该任务�?PLANNING 会被�?SQL 正确排除。当前库�?PLANNING 任务（该任务已被回退 PENDING），无法跑真实超时扫描，待部署后观察�?
- 说明：PlanningTimeoutTaskTest �?mock 层测试（stub mapper 返回值），无法覆盖注�?SQL 逻辑，SQL 正确性以上述 MCP 直验为准；未�?mvn 编译（Node 沙箱�?mvn 会触�?JVM 崩溃），编译验证由用户侧执行或随下次打包天然覆盖�?

#### 4. 影响与遗�?

- 影响：有草案�?PLANNING 任务不再�?10 分钟兜底误回收，用户可从容确认草案；真卡死（异步线程丢失）任务仍�?30s 巡检回收，兜底能力不降�?
- 数据修复（当前受影响任务，写操作由用户执行）：`UPDATE task SET status='PLANNING', update_time=now() WHERE id=2089994365468286978 AND status='PENDING';` 执行后即可在前端确认草案�? 条草案原样保留）�?
- 部署注意：TaskMapper 变更需重新打包部署后端 jar 生效�?*�?jar 部署期间**该任务改�?PLANNING �?10 分钟内不确认仍会被旧逻辑回收，可临时调大 `helloai.planner.decompose.planning-timeout-minutes`（env）或部署后操作�?
- 遗留：本轮代码与本文档未 git 提交，待用户确认后提交�?

---

### 6.113 DeepSeek Harness Skills 借鉴 P0：Reviewer 双轨纪律�?+ prompt 模板 cot-leakage 清洗�?026-08-20�?

#### 1. 范围

- **背景**：用户下�?DeepSeek Harness 官方仓库（`E:\workspace\deepseek-harness-master\.agents\skills`�?1 �?SKILL.md 逐文件核对），给出三角色借鉴分析——Reviewer 嵌入 dsh-code-review / dsh-prose-standard 工程纪律、Planner 借鉴文档标准与目标拆解、Executor 作为外部 Agent 能力插件（checkIn 上报技能列�?/ Task Running Spec 注入 / 值班能力分级），要求输出完整调整方案。方案经 4 项决策拍板后写入 `doc/design/HelloAI_DeepSeek_Harness_Skills借鉴方案.md`：① Reviewer 全面升级为纪律制（纪律清单与验收标准**并列**成为判定依据，改变「验收标准唯一判定依据」旧产品行为）；�?最终报告完全拥�?trim 哲学（废�?50% 字数红线）；�?外部技能规范库采用平台自命�?`eng-` 前缀（不保留 dsh- 前缀，摆脱上游命名演进耦合）；�?方案落盘 + P0 同步实施�?
- **事实校准**：常被引用的 dsh-planning / dsh-goals / dsh-subagent 在当前仓库不存在；「文档规范」职能实际由 dsh-doc-standards 承担，「目标可验证性」分散在 dsh-prose-standard �?contract 概念�?dsh-code-review �?evidence 检查中。价值分级：★★�?直接嵌入 2 个（code-review / prose-standard）、★�?概念借鉴 2 个（doc-standards / trim-cot-leakage）、★ 单维�?3 个（find-simplifications / pre-push-checks / archive-agent-notes）、✗ 不适用 4 个（merging-stacked-prs / doc-site-sync / translate-docs / record-browser-gif）。层级结论：Harness 是单 Agent 运行时（「给模型一双手」），HelloAI 是多 Agent 调度平台（「AI 项目经理」），skills 作为外部 Agent 能力插件引入，不替代调度、熔断、死信池、上下文注入等核心机制�?
- **本轮内容（P0�?*：`subtask-review.md` 重写为双轨纪律制 + blocker/nit 分层驳回 + issues 四元组格式；排查 5 �?prompt 模板 cot-leakage（实际清�?2 个）；单测回归�?
- **明确不做**：P1�? �?eng- 规范�?+ Spec 注入 + decompose 验收可检查�?+ clarify 第六维）�?P2（checkIn 技能上�?+ task-final-report trim 重写）本轮不启动；后�?Java 零改动（JSON schema 五字段不变，`ReviewVerdict` Jackson 解析无感知）；Harness �?GitHub/VitePress 专用 4 �?skill 明确不借鉴�?

#### 2. 实际落地

- **方案文档**（`doc/design/HelloAI_DeepSeek_Harness_Skills借鉴方案.md`�?09 行）：�? 事实校准与价值分级表、�? 层级差异边界表、�? 已拍板决�?4 项、�? 分角色方案（Reviewer P0 / Planner P1 / Executor P1~P2）、�? P0-P2 路线图与文档回填约定、�? 明确不借鉴项�?
- **`prompts/subtask-review.md` 重写（双轨纪律制�?*：角色定义改为「从两条独立轨道核验子任务执行产出：轨道 A 对照验收标准判定达标情况，轨�?B 按工程纪律清单核验产出自身质量缺陷，任一轨道 blocker 级问题均可驳回（pass=false）」。轨�?B 按交付物类型条件激活——代码类 C1 接口契约（签�?返回值区�?异常约定/边界条件文档化）、C2 生命周期与并发（资源创建/释放成对、竞态、取消与错误上报）、C3 验证强度（断言真会失败于目标回归）、C4 范围与必要性（投机泛化、过度抽象）；文档类 D1 契约与命题完整（必须/不得/失败模式/归属/后果）、D2 无思维链泄漏（8 �?taxonomy 速查）、D3 结构清晰（tutorial/reference 混写、层级混乱）。分层驳回：blocker（验收未满足 / 纪律缺陷实际造成误用、泄漏、竞态、文档代码矛盾）�?pass=false；nit（风�?命名/格式）→ 仅进 comment 不驳回。issues 四元组格�?`[defect] 缺陷 [location] 位置（文件名/行号/字段�?章节�?[impact] 影响 [evidence] 依据`，验收类注明标准条目、纪律类注明 C1-C4/D1-D3 编号。JSON schema 五字段（pass/score/issues/comment/analysis）不变。注释头清理死引用（V27/V1.7/A0-5/方案3 F2 迭代日志）改为现状陈�?+ 指向方案文档�?
- **cot-leakage 清洗（排�?5 模板，实际改 2 个）**：`requirement-chat.md` 删「（V39）」「V45 起：」「（V40.2，重要）」版本戳改现状陈述；`requirement-clarify.md` 注释与正文「首轮」→「每轮」（对齐 V45 每轮检索语义）。`planner-decompose.md` / `task-final-report.md` �?cot-leakage 痕迹未动（后者留 P2 trim 重写）�?

#### 3. 验证结果

- **单测回归 35/35 全绿**：`SubTaskReviewServiceTest` 28 �?+ `MqReviewCommandConsumerTest` 7 例（嵌套类），`mvn -pl helloai-core test -DskipTests=false "-Dtest=SubTaskReviewServiceTest,MqReviewCommandConsumerTest"` BUILD SUCCESS（零后端改动，纯 prompt 模板变更的回归验证）�?
- **踩坑**：PowerShell 5.1 �?`-Dsurefire.failIfNoSpecifiedTests=false` 被拆成独立参数报 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"`（点号参数须整体引号或移除，本轮直接移除）；pom 默认 `<skipTests>true</skipTests>` 需 `-DskipTests=false` 覆盖，否则「Tests are skipped」假绿（§6.111 已固化坑再次验证）�?

#### 4. 影响与遗�?

- 影响：① Reviewer 判定依据从「验收标准唯一」升级为「验�?+ 工程纪律」双轨，驳回意见可精确到「第 X 行接口缺异常约定（C1）」粒度，从「代码写得不好」升级为规范锚定的可行动意见；② issues 四元组为 P1 规范库与「执行侧产出 / 审查侧解析」闭环打底；�?prompt 模板 cot-leakage 基线化，�?P2 报告 trim 重写铺路�?
- 部署注意：纯 prompt 模板 + 文档变更，零 Flyway、零 Java、零 schema，重新打包部署后�?jar（resources �?jar 内）生效�?
- 遗留：① P1（`eng-code-review` / `eng-doc-standard` / `eng-verification` 3 份规范库�?`helloai-core/src/main/resources/skills/plugins/` + executor SKILL 注册示例�?skills + `TaskRunningSpecService.buildExecutorPromptSection` Spec 注入 + planner-decompose 验收必须可检�?+ clarify 第六维「边界与排除项」）；② P2（值班 checkIn 技能上报合并进 agent.skills 取并�?+ task-final-report.md trim 重写）；�?差距�?N18 条目已回填（P0 段落），P1/P2 收口后增量更新；�?本轮代码与文档未 git 提交，待用户确认后提交�?

---

### 6.114 DeepSeek Harness Skills 借鉴 P1：eng- 规范�?3 �?+ Spec 注入 + 模板升级�?026-08-20�?

#### 1. 范围

- **背景**：P0（�?.113）收�?Reviewer 双轨纪律制后，按方案 §6 路线图启�?P1�?
- **本轮内容**：① 平台自命�?`eng-` 前缀外部技能规范库 3 份（classpath `skills/plugins/`）；�?`task.required_skills` 命中插件标签时向执行 Prompt 注入「平台技能规范」段；③ `planner-decompose.md` 验收标准必须可检查；�?`requirement-clarify.md` 五维自检加第六维「边界与排除项」；�?executor SKILL.md 补技能标签与规范语义节；�?单测回归�?
- **实现偏差说明**：方案文档写「`TaskRunningSpecService.buildExecutorPromptSection` 已有注入点」，实际评估后选择�?`SubTaskExecutionServiceImpl` 装配点新�?`PluginSkillSpecService` 拼接——理由：规范库渲染与 Spec 存储职责分离、与 `buildDependencySection`「调用方按需组装」既有模式一致、避免改接口签名 + �?Impl + 渲染器；`TaskRunningSpecService` 接口与两�?Impl 零改动�?
- **明确不做**：不重建选人链路（V47/A2/A3 已具备：agent.skills + task.required_skills AND 匹配 + `SkillNormalizer` 同义词归一 + `AgentSelectionConstraints` 全链注入）；只注入速览不注入完整规范正文（�?token）；P2 不启动�?

#### 2. 实际落地

- **3 份规范库**（`helloai-core/src/main/resources/skills/plugins/`）：`eng-code-review.md`�?2 行，来源 dsh-code-review；速览 5 条：C1 接口契约 / C2 生命周期与并�?/ C3 验证强度 / C4 范围与必要�?/ 四元组产出格式）、`eng-doc-standard.md`�?6 行，来源 dsh-prose-standard + dsh-doc-standards；速览 4 条：D1 命题完整保留 / D3 tutorial-reference 分离 / D2 无思维链泄�?8 类速查 / 信息密度）、`eng-verification.md`�?4 行，来源 dsh-pre-push-checks，与 VERIFICATION 围栏对齐；速览 4 条：最小证据集 / 证据真实可复�?/ 断言有效�?/ 环境可复现）。文件约定「执行速览在前、首�?`---` 分隔详细规范」�?
- **`PluginSkillSpecService` 接口（task/service�?6 行）+ `PluginSkillSpecServiceImpl`（task/service/impl�?17 行）**：`renderSection(taskId)` = �?`Task.required_skills` �?`SkillNormalizer.normalizeAll` �?�?`KNOWN_SPECS`（LinkedHashMap 保序 eng-code-review / eng-doc-standard / eng-verification）求�?�?`ClassPathResource` 读速览（首�?`\n---\n` 截断 + �?h1 标题行）�?拼�?# 平台技能规范（任务所需技能命�?eng-* 规范）」章节；失败语义 best-effort（taskId null / 任务不存�?/ �?required_skills / 未命�?/ 文件缺失均返回空串，log.warn 不抛异常，绝不阻断执行链）�?
- **`SubTaskExecutionServiceImpl`**：新�?`pluginSkillSpecService` 依赖 + `executeOnce` 装配段改为三步组装（1) 全局�?= Baseline + ContextSummary�?) 插件规范段；3) 依赖段）+ `mergeSpecSections` 私有方法（任一为空原样返回另一�? timeline `sub_task_spec_context_loaded` payload �?`pluginSpec` 布尔观测字段�?
- **`planner-decompose.md`**：验收标准第 3 条「尽量可量化、可验证」→�?*每条必须可检�?*——含可观察的验证点（判定动作 + 预期结果），�?运行 X 命令输出�?Y"�?接口 Z 返回 200 且字�?W 存在"；不得写"功能正常""体验良好"这类无法验证的表述。执行侧按此验收、审查侧按此核验（subtask-review.md 轨道 A），写不出验证点的子任务说明边界不清，应重新界定或合并」；拆解原则新增同主题闭环条目�?
- **`requirement-clarify.md`**：「五维度自检清单」→「六维度自检清单」；�?2 条去掉「明确不做什么（边界）」；新增�?6 条「边界与排除项：明确不做什么——不覆盖的场景、不支持的平�?用户、不含的交付物，必须显式写清，杜绝隐含承诺（scope 必须显式）」；progress 说明「五维度」→「六维度」�?
- **executor SKILL.md**：新增「技能标签（skills）与平台技能规范（eng-*）」节——注册时声明 skills（示�?`"skills": ["shell", "eng-code-review"]`）�? 份规范清单、命中即注入语义（产出必须按规范执行，审查侧按同一清单核验，不达标驳回）、四元组格式示例�?

#### 3. 验证结果

- **单测 31/31 全绿**：`PluginSkillSpecServiceImplTest` 新建 7 例（taskId null / 任务不存�?/ requiredSkills �?/ 未命�?/ 命中渲染速览且不含详细规范与 h1 / 多命中按声明�?/ 未知技能忽略，走真�?classpath 资源文件�?mock�? `SubTaskExecutionServiceTest` 24 例回归（新增 `@Mock PluginSkillSpecService` 字段，默�?mock 返回 null �?`mergeSpecSections` 原样返回，既有断言不受影响）。命�?`mvn -pl helloai-core -am test "-Dtest=PluginSkillSpecServiceImplTest,SubTaskExecutionServiceTest" "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`�?
- **缺陷修复**：首轮回归暴�?CRLF 行尾�?`indexOf("\n---\n")` 分隔符匹配失�?�?详细规范被整体注入（渲染体含�?# 详细规范」断言失败）；修复为读取后 `content.replace("\r\n", "\n")` 归一（�?.111 脚本编码坑的 Java 侧同源教训）�?
- **踩坑**：`-pl helloai-core -am` �?helloai-common 无匹配测试报 surefire 失败，需 `-Dsurefire.failIfNoSpecifiedTests=false` 且整体引号（PowerShell 5.1）；clean 全量编译后首轮组合跑 `SubTaskExecutionServiceTest` 出现「Tests run: 0」一次性怪癖（@Nested 主类无直�?@Test），单独重跑与后续组合跑�?24/24 稳定，未复现�?
- **真实验证缺口**：注入段�?LLM 产出的实际质量提升待真实环境任务运行观察；速览截断语义已被单测锁定�?

#### 4. 影响与遗�?

- 影响：① 任务声明 required_skills �?eng-* 标签时，执行 Prompt 自动携带对应规范速览，执行侧/审查侧共用同一清单（subtask-review 轨道 B 同源 C1-C4/D1-D3），形成「注�?�?产出 �?审查 �?驳回」闭环；�?timeline `pluginSpec` 观测字段可定位「命中注入但产出不达标」案件；�?decompose 验收可检查性与 subtask-review 轨道 A 形成拆解�?审查侧闭环；�?clarify 第六维堵「scope 隐含承诺」类误解。未声明标签的任务零成本（renderSection 返回空串）�?
- 部署注意：新�?Java �?+ 资源文件，重新打包部署后�?jar 生效；无 Flyway、无新配置项�?
- 遗留：① P2（值班 checkIn 技能上报合并进 agent.skills 取并�?+ task-final-report.md trim 重写，方向已拍板）；�?差距�?N18 已回�?P1 段落；③ 本轮代码与文档未 git 提交，待用户确认后提交�?

---

### 6.115 DeepSeek Harness Skills 借鉴 P2：值班 checkIn 技能上�?+ task-final-report.md trim 重写�?026-08-20�?

#### 1. 范围

- **背景**：P1（�?.114）收口后，按方案 §6 路线图启�?P2（两项：值班 checkIn 技能上报、task-final-report.md trim 重写，方向均已在方案拍板）�?
- **本轮内容**：① checkIn 打卡时顺带上报已加载技能标签，平台与既�?`agent.skills` 取并集（只增不减），任务 `required_skills` AND 匹配立即生效；② `task-final-report.md` 废除 50% 字数红线改信息密度优先（契约性事�?100% 保留、叙事压缩）；③ executor SKILL.md 工具文档同步；④ 单测回归�?
- **设计要点（技能上报）**：不新建�?枚举/选人链路（V47/A2/A3 已有 agent.skills + required_skills AND 匹配 + SkillNormalizer 归一 + AgentSelectionConstraints 全链注入），只补「能力从执行侧反哺平台」的最后一环；MCP 参数�?String CSV（`skills`）而非 List——spring-ai @ToolParam 全库�?List 参数先例，CSV + 服务端解析最稳�?
- **明确不做**：不做技能删�?减员语义（某次漏报不清历史技能）；不�?checkOut 上报；合并失败不阻断打卡（best-effort）�?

#### 2. 实际落地

- **`McpToolService` 接口**：`checkIn` 5 参重�?`checkIn(agentId, workMode, maxConcurrent, ttlMinutes, List<String> reportedSkills)`（Javadoc 写明 P2 §6.115 并集语义）；`CheckInResult` �?`mergedSkills` 字段（合并后的完整技能列表，null = 本次未上报或合并失败）�?
- **`McpToolServiceImpl`**：原 4 �?`checkIn` 改为转调 5 参传 null（保�?@Transactional 入口）；5 参实�?= 原打卡链 + `mergeReportedSkills` + `result.setMergedSkills`；新增私有方�?`mergeReportedSkills`——reportedSkills 空返�?null；`SkillNormalizer.normalizeAll` 归一既有与上报技�?�?`LinkedHashSet` 取并集（保序、只增不减）�?与既有列表比较，无新增不写库，有新增 `agentService.updateById` 回写�?log.info；整�?try-catch，失�?log.warn 返回 null 不阻断打卡�?
- **`McpMcpServer` checkIn 工具**：加 `@ToolParam String skills`（description「本次打卡上报的已加载技能标签（逗号分隔，如 eng-code-review,shell；与既有技能取并集，只增不减），可为空」，required=false）；工具 description Gotchas 补「P2 技能上报」条目；新增私有 `parseCsvSkills`（`split("[,，\\s]+")` 兼容逗号/中文逗号/空白，空项忽略，null/空白/全空返回 null）；`import java.util.ArrayList/List`�?
- **executor SKILL.md 三处同步**：�?.1 三通道工具�?checkIn 行请求体�?`"skills":"shell,eng-code-review"`、返回要点补 `mergedSkills`（含未上报为 null 说明）；§1.2 工具�?checkIn「何时使用」补技能上报语义；🧭 checkIn 租约机制块新增「技能上报（P2 §6.115）」条目（归一/取并�?只增不减/回显/best-effort 五要点）�?
- **`task-final-report.md` trim 重写**：铁�?「字数红线（50% 绝对值强制）」→「信息密度优先（叙事压缩，事实保留）」——契约性事�?100% 保留、叙事文字压缩至必要最小（删铺�?重复�?过程叙事/轮次痕迹�?8 类思维链泄漏，速查 eng-doc-standard D2）、删除标准「删掉一句话信息不减少则必须删」；铁律3「技术元素全量抄录」→「契约性事实完整保留（一字不改）」（四类元素清单不变，动机从凑字数改为事实保真）；执行摘�?�?00→≤200 字且「只陈述结果不描述过程」；绝对禁止清单新增「叙事膨胀」行；自检清单删「≥50% 字数」检查项、加「叙事压缩�?「事实保留」两项；删输入数�?`{{TOTAL_OUTPUT_LENGTH}}` 占位符；强制全覆盖指令「章节长度自平衡」→「章节内容自平衡」（压缩叙事而非事实）�?
- **`TaskFinalReportServiceImpl` 同步**：`renderPrompt` �?`totalOutputLength` 计算�?`.replace("{{TOTAL_OUTPUT_LENGTH}}", ...)`（占位符已删，防死代码）；systemPrompt 由「少�?50% 判定不合格并触发重写」改为「按信息密度优先原则整合——契约性事实必须完整保留，叙事文字压缩至必要最小，禁止用过程叙事或铺垫填充篇幅」�?

#### 3. 验证结果

- **单测 46/46 全绿**：`McpToolServiceTest` 26/26（新�?4 例：同义词归一 bash/Shell→shell + 去重保序取并集且 updateById 回写 / 未上�?mergedSkills=null 不写�?/ 上报已全部存在（powershell→shell）不写库但回显完整列�?/ DB 异常合并失败不阻断打�?mergedSkills=null；既�?checkIn 用例�?mergedSkills null 断言�? `TaskFinalReportServiceTest` 13/13 回归（模板占位符�?systemPrompt 改动零影响）+ `PluginSkillSpecServiceImplTest` 7/7 顺带回归。命�?`mvn -pl helloai-core -am test "-Dtest=McpToolServiceTest,TaskFinalReportServiceTest" "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`�?
- **踩坑（测试侧�?*：新用例自己 new �?Agent 需 `setStatus(ACTIVE)`（`assertAgentActive` 校验状态，覆盖 lenient stub �?status=null 直接抛「Agent 未激活」）；「合并失败不阻断」用例中 `assertAgentActive` �?`mergeReportedSkills` 都调 `getById`，需 `thenReturn(agent).thenThrow(...)` 链式 stub 区分两次调用（否则打卡前置校验先炸）�?
- **真实验证缺口**：MCP checkIn �?skills 参数的真实环境打�?+ 合并后派单匹配未实测（待下次值班会话顺带验证）；trim 后报告质量待真实任务生成观察�?

#### 4. 影响与遗�?

- 影响：① 外部 Agent 值班打卡即可把「本次会话已加载技能」反哺平台，`required_skills` 精确派单从此不依赖注册时声明（注册漏报可�?checkIn 补齐）；�?取并集只增语义下「读-�?写」竞态最多丢失本次并集条目（下次打卡补上），�?startLease 会话级串行一致，可接受；�?报告模板不再逼迫 LLM 注水凑字数，产出更贴�?eng-doc-standard 信息密度规范�?
- 部署注意：新�?Java 字段/方法 + 2 个资源文件（task-final-report.md、executor SKILL.md），重新打包部署后端 jar 生效；无 Flyway、无新配置项；旧客户端不�?skills 参数完全兼容（required=false）�?
- 遗留：① 差距�?N18 已回�?P2 段落、方案文�?§6 路线图表待更新；�?本轮代码与文档未 git 提交，待用户确认后提交�?

---

### 6.116 executor SKILL.md 重复/相悖口径治理：EXECUTION_RECORD 单一权威定义�?026-08-20�?

#### 1. 范围

- **背景**：用户复�?executor SKILL.md，指�?§0.3/§4.2~§4.5 区域存在「相似规则多处、多种解读」。核对后确认 4 类问题：SUMMARY 缺失后果两处说法不一（表格「整块解析失败」vs 🔴 块「解析失败（fallback �?200 字）」）；VERIFICATION 一处写「可选」、围栏处写「必须」；EXECUTION_RECORD 格式�?§4.2/§4.3/§4.4/§4.6 四处重复定义；�?.5.7「按 §4.5 �?Invoke-WebRequest + UTF-8 字节解码」引用断链（§4.5 只讲交付物编码，无响应解码内容）�?
- **事实校准（以代码为准�?*：① `ExecutionRecordParser` SUMMARY 缺失/为空 �?parse 返回 null �?`ExecutionResultHandler` 捕获后以产出�?200 字兜底摘要（「失败」与「兜底」是同一流程两步，文档各写一半）；② `SubTaskReviewServiceImpl.verificationSignal` **仅检测不拦截**——无证据提交不拒收，但向 Reviewer 注入「从严核验、评分保守、不得判 pass=true」指令；�?`PromptTemplateServiceImpl` �?classpath `skills/{role}/SKILL.md` 读取 + 变量替换，本文件即唯一源头；④ `ExecutionRecordParserTest` �?§4.4 两个官方示例原文做解析输入（文档-解析器绑定），示例不可随意删�?
- **整改原则**：每个规则只�?§4.4 定义一次，其余处引用；示例保留（单测绑定）；�?.3 时间语义本身自洽，不动�?
- **明确不做**：不�?Java 代码与解析器行为（口径对齐以现状代码为准）；不动 §一/§�?§三；不动 §4.4 两个官方示例内容�?

#### 2. 实际落地

- **§4.4 字段表格口径统一**：SUMMARY 解析约束「缺失或为空 �?整块解析失败」→「缺失或为空 �?整块解析失败，平台以产出�?200 字兜底为摘要」；VERIFICATION 说明「（可选但强烈建议）」→「（平台解析不强制，但缺失会触发审查侧从严核验，见下方围栏）」�?
- **§4.4 两个 🔴 �?*：强制格式块删「（fallback 用产出前 200 字做摘要）」的歧义表述，统一为「SUMMARY 缺失或为�?�?整块解析失败，平台以产出�?200 字兜底为摘要——下游读到的将不再是你的原话」；VERIFICATION 围栏块第 3 条补「（仅检测不拦截）」与真实后果（无证据不拒收，但注入「从严核验、评分保守」指令，无法确认验收不得判通过）�?
- **§4.2 Step 3 引用�?*：三行字段清单（SUMMARY/DOWNSTREAM_NOTES/DELIVERABLES）改为引用「格式见 §4.4」，并注�?SUMMARY 必填、老产出可能没有�?
- **§4.3 截断规则去重**：模板行「超 2000 字截断并标注」删去，尾句保留为唯一截断规则（每件事只说一遍）�?
- **§4.5 编码口径**：尾句「验收标准要求『UTF-8 声明』时……」→「验收标准若显式声明编码要求，以声明为准且文件实际字节必须与声明一致——口头声�?UTF-8、实际按 GBK 保存同样会被驳回」（消除「统一 UTF-8」与「以声明为准」的张力）�?
- **§1.5.7 引用断链修复**：「按 §4.5 �?Invoke-WebRequest + UTF-8 字节解码」→自包含表述「PS 5.1 �?JSON 响应的解码问题——改�?Invoke-WebRequest 取原始字节后�?UTF-8 解码」�?
- **续签窗口口径统一**：�?.3.bis �?§1.5.3 两处「T+(ttl-1)m」→「租约到期前 60s」，�?§1.5.2 表格「ttlMinutes 到期�?60s」、�?.5.6「now+60s」单一口径（ttlMinutes 可自定义时「ttl-1 分钟」有歧义）�?

#### 3. 验证结果

- **单测回归**：`mvn -pl helloai-core -am test "-Dtest=ExecutionRecordParserTest" "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` �?7/7 全绿（含 §4.4 两个官方示例的文�?解析器绑定用例，示例未改动故绑定不漂移）�?
- 纯资源文件变更（SKILL.md + 本文档），零 Java 改动，无编译风险�?

#### 4. 影响与遗�?

- 影响：外�?Agent 读到�?EXECUTION_RECORD 格式与平台行为口径完全一致（一处定义、多处引用）；VERIFICATION「可�?vs 必填」统一为「平台不拦截但从严核验、执行侧纪律必须附证据」的单一解读；修�?§1.5.7 引用断链（此�?Agent 按指引去 §4.5 找不到响应解码方法）�?
- 部署注意：资源文件在 jar 内，重新打包部署后端 jar 生效（`getSkillForAgent` 运行时读取）；无 Flyway、无配置项�?
- 遗留：① 差距�?N18 已覆�?P0-P2 与本文档规范互指关系，本轮为文档质量治理，不�?N18 状态；�?本轮代码与文档未 git 提交，待用户确认后提交�?

---

### 6.117 executor SKILL.md 反馈驱动修订：逐条事实核查后的 17 项采纳（2026-08-20�?

#### 1. 范围

- **背景**：用户提供一份外部审阅意见（24 项：4 严重 / 11 中等 / 9 小问题），要求据此再调整 executor SKILL.md。按守则先逐条对代码事实核查，再采纳成立项——核查结论：4 项基于旧�?渲染后文档不成立（硬编码密钥、localhost 写死、「证据链」描述均不在当前文件；当前已是占位符注入），2 项反馈建议值与代码不符（workMode �?MANUAL，实�?AUTO/STRICT；finishReason/closeReason 为自由字符串），其余成立或部分成立�?
- **本轮内容**：采�?17 项修订（详见 §2）；其中最有价值的是核查中证实的两个隐藏坑：返工重提旧 resultId 会被判幂等重复而静默丢弃新产出、REWORK 状�?submitResult 不自动推进会�?invalid_status�?
- **明确不做**：不改任�?Java 代码（全部是文档口径对齐与补全）；不批量移除 A0-x/§6.x 内部追踪编号（保留可追溯锚点，迭代记录多处交叉引用）；不�?`.bis` 章节编号（重排会波及十余处交叉引用，收益仅美观）；planner/reviewer SKILL.md 不同步（本轮反馈仅针�?executor 说明书）�?

#### 2. 实际落地

- **事实核查不采纳项�?+2�?*：① 密钥泄露：当前文件第 10 行即 `<注册后填�?` 占位符（`PromptTemplateService` 注册时注入），全文无 `ak_` 实值；�?localhost 写死：全文均 `{{BASE_URL}}` 占位符；�?reportBlocked「带证据链」：当前文档无此表述，平台确实只�?reason（`McpToolServiceImpl.reportBlocked(agentId, subTaskId, reason)`）；�?workMode 值域反馈称含 MANUAL——代�?`WorkMode.strictParse` �?AUTO/STRICT，非法值拒绝；finishReason/closeReason 无枚举校验（默认 manual_close）�?
- **§0 �?*：认证信息区补占位符语义注解（ak_ 前缀、注册注入、勿硬编码外传）+「�?最快上手」导航（直指 §1.5.7 最小闭环，缓解上下文窗口压力）；�?.1 后新�?*值域速查�?*（workMode AUTO/STRICT、maxConcurrent 在飞口径�?LLM 型建�?1、ttlMinutes、finishReason/closeReason 建议取值）；�?.2 速查�?*�?`listConversationBySubTaskId` 对话流端点行**（真实存在于 `SubTaskController` 但此前未入表）、startById 行补「正常流程无需调，仅返工重提必须调」、submitById 行补「只翻状态不带产出」�?
- **续租口径统一（消�?A0-8 自动续约与旧「提�?60s 重签」建议的矛盾�?*：�?.3.bis 心跳节拍、�?.5.2 两件套表、�?.5.3 新增 💡 块（仅三种异常场景需手动 checkOut+checkIn：onDuty=false / 换参�?/ 断网恢复自检失效）、�?.5.6 骨架注释四处同步改为「正常轮询自动续租，无需手动重签」�?
- **§�?依赖装配重构（消�?getDepsSummary 与手�?fetch 的结构性矛盾）**：�?.2 改「方�?A 首�?getDepsSummary 一键拉取（�?truncated=4000 字截�?/ degraded 降级语义与回退指引�? 方式 B 兜底手动逐条 fetch（原 Step 1-3 保留，兼作截断补拉）」；章节�?4.1~4.6 不动，既有交叉引用零破坏；§四顶部警告同步改「首选方�?A，degraded/截断回退方式 B」�?
- **返工重提四步（本轮最重修订，两个隐藏坑均经代码证实）**：`ExecutionResultHandler` 幂等判定在状态检查之前且 `rework()`/`reworkFresh()` 不清 `context.lastExecution.idempotencyKey` �?返工沿用�?resultId 返回 `accepted=true, idempotent=true` 但新产出不写入；`McpToolServiceImpl.submitResult` 仅自动推�?ASSIGNED→IN_PROGRESS，REWORK 直提返回 `invalid_status:REWORK`（状态机 REWORK→IN_PROGRESS 合法，需 `startById`）。§注意事项新增四步流程（查意见重传附�?�?startById 拉回 IN_PROGRESS �?�?resultId 提交 �?仍附 EXECUTION_RECORD）；§1.2 submitResult 行、�?.5.1.bis rejected/rework 行、�?.5.7 脚本 resultId 注释三处同步指向�?
- **其余采纳�?*：�?.2 reportBlocked 行补「证据内嵌进 reason」（平台无附件字段）；�?上传提示补标准流程四步编号（①生�?�?②upload �?③DELIVERABLES 列路�?�?④submit）；§1.5.1.bis assigned 行补「assigned 是资格通知，claim 才锁执行权」、reassigned/unassigned 行补「终止进行中调用、不�?submitResult、只 ack」；§1.5.1 补收件箱多消息按 priority 降序 + deadline 临近优先；�?.5.7 �?MCP SSE 通道参数相同说明 + `trap` 兜底 checkOut（注明与 finally 都不 100% 拦截强杀�? 执行段补 getMergedRules 注释（与§注意事项「必须」口径闭环）；�?.4 �?VERIFICATION 多行输出粘贴规则（续行原样粘贴无需围栏，但证据内不得出现独�?`---` 行——解析器 BLOCK_END）与 DELIVERABLES 相对项目根路径约定�?

#### 3. 验证结果

- **代码事实核验（本轮全部修订的依据�?*：`SubTaskController.listConversationBySubTaskId` 存在；`McpToolServiceImpl.submitResult` L392-402 ASSIGNED 自动推进 + REWORK 拒绝；`ExecutionResultHandler.handleReport` L109-120 幂等检查先于状态检查、`SubTaskServiceImpl.rework` 不清 context；`WorkMode` �?AUTO/STRICT；`ExecutionRecordParser` BLOCK_END=`---`、VERIFICATION 截取至块尾；`getDepsSummary` 4000 字截断与 degraded 降级语义（`McpToolServiceImpl.getDepsSummary` Javadoc + `McpToolService` 返回 DTO 注释）�?
- 纯资源文件变更，�?Java 改动；�?.4 两个官方示例未动，`ExecutionRecordParserTest` 文档-解析器绑定不漂移�?

#### 4. 影响与遗�?

- 影响：外�?Agent 接入文档消除四类困惑源（依赖读取双路径无主次、续租新旧口径并存、返工重提无指引、值域无定义）；两个会导致产出静默丢失的坑（旧 resultId / REWORK 状态）首次显式写入文档�?
- 部署注意：资源文件在 jar 内，重新打包部署后端 jar 生效；无 Flyway、无配置项、无 API 变更�?
- 遗留：① 反馈中「提�?50 行精简�?Quick Start 独立文档」未做（已用顶部「最快上手」导�?+ §1.5.7 最小闭环轻量覆盖，独立简版待真实接入方反馈上下文压力后再评估）；�?A0-x/§6.x 编号�?.bis 章节号经权衡保留（可追溯�?> 轻微噪声）�?

---

### 6.118 Agent 模态能力建模与核验附件注入硬化�?026-08-20�?

#### 1. 背景与决�?

- **背景**：外�?executor 提交截图类证据时，自动核验的 reviewer 完全看不到图片——核验链路纯文本，且 `SubTaskReviewServiceImpl.readAttachmentContent` �?`new String(bytes, UTF_8)` 读附件正文：若图片附件被上传，二进制乱码会被注入核验 Prompt（吞�?24000 字符限额并污染判定）。同�?`agent.capabilities` 只有接入通道能力键（supportsPull/supportsMCP 等），无模态维度，无法表达「底层模型是否具备图片理解能力」�?
- **决策**：分三阶段推进，本轮落地阶段�?②（用户确认）：�?模态能力建模（capabilities 新增 supportsImageUnderstanding/supportsAudioUnderstanding/supportsVideoUnderstanding，三�?accessType 统一默认 false——模态能力取决于底层模型而非接入通道，注册时可覆盖）；② 核验注入硬化（仅文本类附件注入正�?+ 含媒体附件时注入「无法查看原内容、从严核验」显式标注，标注独立�?attachment-content-enabled 开关）；③ 多模态核验调用链（Spring AI Media 注入 + reviewer 挑选偏好）另立项，因涉�?ChatClient 调用链改造与 provider 兼容性（DeepSeek 通道不支持图片，�?OpenAI 兼容/Anthropic 兼容可用）�?
- **明确不做**：不�?reviewer 挑选逻辑（pickReviewerAgent 仍按角色 + API_KEY_LLM）；不改前端注册表单（能力声明走既有注册 API �?capabilities 覆盖）；音频/视频仅预留能力键，核验链路本轮不做处理�?

#### 2. 实际落地

- **模态能力建�?*：`AgentAccessType.defaultCapabilities()` 三类统一新增 3 个模态键（默�?false）并持有 `CAP_SUPPORTS_IMAGE_UNDERSTANDING` 等常量；`AgentCapability` 新增对应常量（指�?common 侧定义避免字符串重复�? javadoc，读取复用既�?`hasCapability`（未配置视为 false）；注册通道零改动（`AgentController` 已支�?capabilities 覆盖合并）�?
- **核验注入硬化**（`SubTaskReviewServiceImpl`）：新增 `isTextualAttachment`（mimeType 优先：text/* 与文本族 application 类型；缺失或 octet-stream 回退扩展名；无法判定 fail-close 按非文本）、`isMediaAttachment`（image/ audio/ video/ 前缀优先 + 扩展名兜底）、`buildMediaVisibilityNote`（含媒体附件时输出「本提交�?N 个媒体附件（文件名）。当前核验链路无法查看其原始内容；与之相关的文字声称请从严核验、评分保守」）；`buildAttachmentContent` 循环内非文本附件直接 continue（不再调 `readAttachmentContent`，杜绝二进制�?Prompt），返回文本前置 mediaNote（开关关�?无可读附件两条早退路径同样带标注）。A0-5 证据硬检查的 `readableAttachments` 不变——图片仍是有效物化证据，继续计入 attachmentCount�?

#### 3. 验证结果

- **单测**：`mvn -pl helloai-core -am test -DskipTests=false -Dtest=SubTaskReviewServiceTest,AgentModalityCapabilityTest` �?34/34 全绿（SubTaskReviewService 32 例含 4 个新增硬化用例：图片不注入二进制 + 标注点名文件 / mimeType 缺失按扩展名识别 / 开关关闭标注仍注入 / 纯文本组合无标注；AgentModalityCapability 2 例：三类默认值含键且 false、常量一�?+ 覆盖可读）。既�?28 例核验用例零回归�?

#### 4. 影响与遗�?

- 行为变更：图�?音视频附件不再以二进制乱码注入核�?Prompt；含媒体附件的提交核�?LLM 会收到显式「看不到原内容、从严核验」指令（此前只能凭文字声称盲判）�?
- 兼容性：存量 Agent capabilities 无模态键�?`hasCapability` 返回 false，等价于默认值，零迁移；注册时传 `supportsImageUnderstanding=true` 即可声明�?
- 遗留：阶段③（多模态核验调用链：Spring AI Media 注入 + 含图附件优先多模�?reviewer）另立项；当�?reviewer 未声明多模态能力前，行为退化为「文本注�?+ 媒体从严标注」�?

---

### 6.119 RabbitMQ 监听�?MessageConverter �?Jackson，毒消息自动�?DLX�?026-08-20�?

#### 1. 背景与决�?

- **背景**：启动日志反复出�?`ListenerExecutionFailedException: Failed to convert message`（根�?`SecurityException: Attempt to deserialize unauthorized class java.util.LinkedHashMap`）。定位：Phase 2F 修复前旧发布�?`convertAndSend(Map)` �?Java 序列化（content-type=`application/x-java-serialized-object`）投递的遗留毒消息仍驻留�?durable 队列（executor/reviewer 队列实测确认）；spring-amqp 默认 `SimpleMessageConverter` 对其�?Java 反序列化被安全白名单拦截，且异常抛在 `MessagingMessageListenerAdapter` 转换层（进入 `@RabbitListener` 方法体之前，反编�?spring-amqp 3.2.7 `AbstractAdaptableMessageListener.extractMessage` 确认无条件走 converter），消费端方法内的「坏消息 ACK」防御够不着；MANUAL ACK 模式下消息永久停�?unacked，每次启动重�?2 �?WARN，手�?purge 只能�?ready 消息，治标不治本�?
- **决策**：`RabbitMQConfig` 新增全局 `MessageConverter` Bean（`Jackson2JsonMessageConverter`）。Boot 自动装配�?`rabbitListenerContainerFactory` �?`ObjectProvider` 消费�?Bean（字节码确认 `AbstractRabbitListenerContainerFactoryConfigurer` �?`factory.setMessageConverter`），yml �?`spring.rabbitmq.listener.simple.*`（manual / concurrency=5 / max-concurrency=20）照常生效；项目 `rabbitTemplate` 是自定义 Bean 未设 converter，且全部发布端均为显�?`RabbitTemplate.send(Message)`（grep 确认�?convertAndSend 调用），发布侧行为不变�?
- **明确不做**：不自建 `rabbitListenerContainerFactory` Bean（会�?yml listener 配置）；不改消费端解析逻辑（仍�?`message.getBody()` 自行 objectMapper 解析）；不开 `SPRING_AMQP_DESERIALIZATION_TRUST_ALL`（安全倒退）�?

#### 2. 实际落地

- **helloai-mq/src/main/java/com/helloai/mq/config/RabbitMQConfig.java**：新�?`rabbitMessageConverter()` Bean（`Jackson2JsonMessageConverter`�? 完整 Javadoc（背�?/ 效果 / 为什么定义为全局 Bean）。效果链路：正常 JSON 消息照常转换（提取的 payload 被丢弃，消费端行为不变）；非 JSON 消息�?`MessageConversionException`，被 `ConditionalRejectingErrorHandler` 默认策略判定 fatal（字节码确认 `DefaultExceptionStrategy` 含该类型）→ 拒投不重�?�?走队列已绑定�?DLX 隔离�?

#### 3. 验证结果

- **编译**：`mvn compile`（JDK 17 + IDEA 自带 Maven，离线）全模块通过�?
- **队列实测**：`helloai.executor.queue` 4 �?Java 序列化毒消息�?purge；`helloai.reviewer.queue` 2 �?unacked 毒消息待本次修复生效后的重启自动�?DLX（旧清理循环未赶上时序，毒消息被新消费者再次持有）�?
- **运行时验证待�?*：用户重启后端后确认两点——① 启动日志不再出现 `Failed to convert message`；② 管理后台 `helloai.dlx.queue` 收到 2 条毒消息、`reviewer.queue` 归零�?

#### 4. 影响与遗�?

- 行为变更：监听端对非 JSON content-type 消息从严处理（fatal �?DLX），符合项目「MQ 消息契约 = 显式 JSON」现状；未来若出现新的非 JSON 发布端会�?DLX 显式暴露而非静默失败�?
- 部署注意：无 Flyway、无新配置项，重启后端生效�?
- 遗留：本轮改动未 git 提交，待用户确认后提交；运行时验证（①②）依赖用户重启后端�?

---

### 6.120 前端 UI 一致性审计与 P0+P1 修复（纯 helloai-ui�?026-08-20�?

#### 1. 背景与决�?

- **背景**：对 helloai-ui 做全�?UI 审计（design-taste 审计维度：主题锁�?/ 色彩一致�?/ 字体落地 / 对比�?/ token 纪律），发现两类硬伤：① Settings.vue 供应商配置区整块残留 Element Plus 默认亮色�?fafbfc / #ecf5ff / #ebeef5 / #303133 等），在全站暗底中像误入另一个网站；�?字体/品牌色断链——Inter 原本靠运行时注入 Google Fonts CDN link 加载（国内网络不可靠），�?EP 组件主色 `--el-color-primary` 从未覆盖，switch/radio/checkbox 等仍�?EP �?#409EFF 与紫色按钮割裂�?
- **决策**：Redesign-Preserve 模式，保留既�?`--ha-*` token 体系与紫色品牌，仅做 P0+P1 一致性修复；青色 #06B6D4（侧边栏光斑/登录页光�?初始化渐变三处装饰）收敛为新 token `--ha-accent-cyan`�?
- **明确不做**：P2 控制台体验项（TaskList 操作�?7 按钮分组、侧边栏/登录页无限循环动效削减、表格边框弱化）�?22 �?`!important` 结构性还债、`LoginCharacters.vue` SVG 插画重绘、路�?菜单/文案变更、后端改动�?

#### 2. 实际落地

- **字体自托�?*：新增依�?`@fontsource-variable/inter`；`main.ts` 移除运行�?Google Fonts CDN link 注入，改为构建期 import（dist 产物�?7 �?unicode-range 分片 woff2，中文自动回退系统字体）；`design-system.css` 字体栈改�?`'Inter Variable', 'Inter', system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif`�?
- **Settings.vue 亮色改暗**：供应商列表/详情/模型分区/表单提示全部替换�?`--ha-*` token（含计划外的 `#67c23a` / `#c0c4cc` / `#606266` / `#e6a23c` / `#fff` �?8 组色值）�?
- **EP 主色族对�?*：`design-system.css` �?`:root` �?`html.dark`（特异性更高需重申）覆�?`--el-color-primary: #7C3AED` + light-3/5/7/8/9 紫色坡道 + dark-2（对�?`--ha-primary-active`）�?
- **fallback 修正**：WebSearchBar / StructuredQuestionCard / AttachmentList �?5 �?`var(--ha-primary, #409eff)` 去掉错误蓝色 fallback�?
- **Dashboard 收敛**�? �?`el-icon color=` 硬编码改语义 token�?0EA5E9 归入 `var(--ha-info)`）；stat-card/chart-card 重复定义�?hover 提升效果删除，复�?`animations.css` 既有 `.ha-card-lift` 工具类�?

#### 3. 验证结果

- `npm run lint:check` �?0 errors�?70 条既�?`any` warning，与本轮无关）�?
- `npm run build`（vue-tsc + vite）→ 构建成功；dist �?Inter woff2 分片；src 全库 grep �?#409eff/#ecf5ff/#fafbfc/fonts.googleapis 残留（仅 SubTaskDagView IN_PROGRESS 状态蓝为有意语义色，不在本轮范围）�?
- `npm run dev` 启动正常，预览已提供用户目检 Settings / Login / Setup / Dashboard / 附件页�?

#### 4. 影响与遗�?

- 行为变更：EP 组件主色全站统一为紫；Inter 字体不再依赖外网 CDN�?
- 差距表无对应前端 UI 条目，本轮不�?`doc/HelloAI_实现差距�?md`�?
- 遗留：P2 项与 `!important` 还债留待后续独立轮次；本轮改动�?git 提交，待用户确认后提交�?

---

### 6.121 前端 P2 控制台体验项：操作列分组 + 网格线弱�?+ 常驻动效削减（纯 helloai-ui�?026-08-20�?

#### 1. 背景与决�?

- **背景**：�?.120 审计遗留�?P2 项：�?TaskList 操作�?300px 宽塞最�?7 个按钮（编辑/AI 拆解/审阅草案/报告/重新发布/停止/删除），密度失控；② 表格全网格线（th/td 均有竖线）在暗底下视觉噪声大；③ 侧边�?aurora 渐变 18s 无限循环 + 登录页双 blur 光斑 14s/18s 无限循环，常�?UI �?GPU 与注意力成本高�?
- **决策**：操作列改「状态驱动主操作 + 更多下拉」；表格去竖线仅留行分隔线（�?border 变体）；无限循环动效改静态（保留渐变/光斑视觉元素）�?
- **明确不做**：「报告生成中」el-tag type="primary" 经核查符合项目约定（TASK_STATUS_MAP �?IN_PROGRESS 等「进行中」态统一�?primary，且 §6.120 �?EP 主色已变紫），不改；`LoginCharacters.vue` / `AnimatedCharacter.vue` 的交互动效为有动机反馈（跟随聚焦/输入），保留；`StarfieldBackground.vue` canvas 星空循环待单独评估�?

#### 2. 实际落地

- **TaskList.vue 操作列重�?*：列�?300 �?150；内联主按钮按状态互斥驱动（PENDING→AI 拆解 / PLANNING→审阅草�?/ DONE→报告，其余状态无内联主按钮）；编辑（DONE 置灰�?重新发布/停止（仅�?DONE/CANCELLED�?删除收进 el-dropdown「更多」，删除�?divided 分隔 + `--ha-danger` 危险色，新增 `handleCommand` 统一分派；所�?loading/confirm 逻辑不变，「更多」按钮带 aria-label�?
- **design-system.css 表格弱化**：th/td �?border-right 全部�?none，th 补回底部分隔线；新增 `.el-table--border .el-table__cell { border-right: none }` 压掉 border 属性自带的竖线，外框保留�?
- **MainLayout.vue**：删�?sidebar-aurora 18s 背景位移动画�?sidebar-blur-float 16s 光斑漂浮动画（及对应 keyframes/reduced-motion 块），渐变与光斑保留为静态�?
- **Login.vue**：删�?deco-blur-1/2 �?14s/18s 漂浮动画（及对应 keyframes/reduced-motion 块）�?will-change，光斑保留为静态�?

#### 3. 验证结果

- `npm run lint:check` �?0 errors（TaskList 仅余 5 条既�?`any` warning）�?
- `npm run build`（vue-tsc + vite）→ 构建成功�?
- dev server�?5173）HMR 热更新无报错，待用户目检任务列表操作�?表格�?侧边栏与登录页静态效果�?

#### 4. 影响与遗�?

- 行为变更：任务列表次要操作（编辑/重新发布/停止/删除）从内联按钮改为「更多」下拉两步操作；全部表格不再显示列竖线�?
- 遗留：`StarfieldBackground.vue` canvas 循环�?22 �?`!important` 还债待后续轮次；本轮改动未 git 提交，待用户确认后提交�?

---

### 6.122 修复 plain 按钮文字不可见回�?+ 操作列「更多」裁切（�?helloai-ui�?026-08-21�?

#### 1. 背景与决�?

- **背景**：用户报告任务管理操作列与系统设置页按钮「鼠标移入才显示汉字」，且「更多」按钮被截断。浏览器实测定位两个根因：① design-system �?`.el-button--primary` 实心紫底覆盖（带 `!important`）同时命�?`is-plain` 变体，紫字叠紫底导致文字不可见，hover �?EP 切白字才显现——�?.120 主色覆盖（蓝→紫）把原本勉强可见的蓝字变成同色，触发该回归；�?§6.121 操作�?150px 放不下主按钮 + 「更多」，被单元格裁切。另发现连带隐患：�?.120 把亮�?light-N 坡道写进�?`html.dark`，plain 按钮/tag 等以 light-N 为底的组件在暗页会出现亮块�?

#### 2. 实际落地

- **design-system.css**：`.el-button--primary` 实心覆盖收窄�?`.el-button--primary:not(.is-plain)`，plain primary 回退 EP 原生暗色样式；`html.dark` �?`--el-color-primary-light-3/5/7/8/9` 从亮色坡道改为�?7C3AED �?#141414 �?(100-N)% 混合」的暗色坡道�?5D2FAC/#482781/#331F55/#291C3F/#1E182A），`:root` 亮色坡道保留不变�?
- **TaskList.vue**：操作列�?150 �?184；`.action-cell .el-button { flex: none }` �?flex 压缩�?

#### 3. 验证结果

- `npm run build` 成功�?
- 浏览器实测：is-plain 按钮 color�?7C3AED）与 background（rgb(30,24,42) 深紫调暗底）已分离，默认态文字可见；「更多」按钮完整落�?184px 单元格内无裁切；状态列 tag 底色均为深色（待规划深灰/已完成深绿），无亮块；任务页与设置页截图�?`.dbg/btn-fix-*.png`�?

#### 4. 影响与遗�?

- 行为变更：dark �?EP 组件�?light-N 为底的场景（plain 按钮、primary tag 等）统一呈现暗色坡道底，不再出现亮紫底�?
- 本轮改动�?git 提交，待用户确认后提交�?

---

### 6.123 亮暗双主题系�?+ 主题切换按钮（纯 helloai-ui�?026-08-21�?

#### 1. 背景与决�?

- **背景**：控制台此前仅有暗色一套。用户要求新增亮色主题并提供切换按钮；经 AskUserQuestion 确认：默认暗色（选择持久化到 localStorage）、登录页一起适配�?
- **决策**：token 架构不动，仅拆分�?暗两套值——`:root` 为亮色基层，`html.dark` 为暗色挂载点（EP 暗色 css-vars �?§6.122 暗色坡道均作用域�?html.dark，移除类即自动回退亮色）；新增 Pinia 主题 store + index.html �?FOUC 内联脚本。亮色板采用冷调白灰（白/冷灰�?+ 紫主色），与暗色深海军蓝同属冷调，品牌连续�?
- **固定暗色区决�?*：登录页左侧品牌区（星空 canvas + 角色插画）、SubTaskSequenceFlow 时序图保持刻意暗色设计不随主题切换（前者是品牌面板，后者是「监控面板」语义），仅在组件头部注释声明约束；规避 LoginCharacters.vue 151 �?SVG 色值与 Starfield canvas 的亮色重绘�?
- **明确不做**�?22 �?`!important` 结构性还债、Starfield canvas 性能评估、全局 `*` 过渡（避免污染动效）、布局结构与新动效、后端改动�?

#### 2. 实际落地

- **主题状�?*：新�?`src/stores/theme.ts`（Pinia），`theme: 'dark' | 'light'`，初始化�?`localStorage['helloai-theme']` 缺省 dark，`setTheme()/toggleTheme()` 同步写存储并切换 `document.documentElement` �?dark 类�?
- **�?FOUC**：`index.html` head 内联脚本�?Vue 挂载前按 localStorage 移除 dark 类（�?light 时动作，暗色靠既�?class 兜底）�?
- **token 拆分（design-system.css�?*：原 `:root` 暗色值整体迁�?`html.dark`；`:root` 定义亮色板（bg #F6F7FB / surface �?/ border #E2E6F0 / ink #1A2233 / muted #8B96AB），新增 `--ha-success-text` �?4 个语义文字变体、`--ha-link/-hover`、`--ha-sidebar-bg-mid/-grid/-border`；侧边栏亮色板为白底 + 紫调 active；阴影改 `rgba(16,24,40,0.06~0.12)`；tag/链接/card hover/sidebar-menu 等规�?token 化�?
- **MainLayout.vue**：侧边栏渐变/网格�?边框/文字全部改走 `--ha-sidebar-*` token�? 处硬编码收敛）；sidebar-header logo 右侧新增圆形切换按钮（Moon/Sunny 图标随主题切换，�?title/aria-label，collapsed 态纵向布局）�?
- **Login.vue**：页�?`--ha-*` 覆盖拆亮/暗两版，新增 `--login-input-*` 表单 token 组（输入框背�?边框/文字/占位/图标）；右上角固定同款切换按钮；左侧品牌区保持固定暗色；LoginForm/EntryTabs/RegisterStack 三个子组件硬编码色改 token�?
- **Dashboard.vue**：`watch` theme store 变化 �?nextTick 后用缓存 rawData dispose 重建两张 ECharts（复用既�?`cssVar()` 机制自动读新主题色）�?
- **切换过渡**：仅 shell 级容器（body�?app-sidebar�?app-content）加 background-color/border-color 250ms 过渡�?

#### 3. 验证结果

- `npm run lint:check` �?0 errors；`npm run build`（vue-tsc + vite）→ 构建成功�?
- 浏览器实测（vite :5173，经 /api 代理到后�?:6565，admin/admin123 真实登录�? 步全通过：登录页�?暗、工作台�?暗、任务列表、系统设置双主题目检；切换按钮双向可用；localStorage 持久化、刷新无闪暗；ECharts 重建正常；无残留暗块/白字白底/不可见文字。截�?6 张存 `.dbg/theme-01-login-dark.png` ~ `theme-06-settings-light.png`�?

#### 4. 影响与遗�?

- 行为变更：新�?localStorage key `helloai-theme`；缺省仍为暗色，用户选择后持久；登录页左侧品牌区与时序图为固定暗色设计（已注释声明）�?
- 遗留�?22 �?`!important` 还债、Starfield canvas 性能评估、LoginCharacters SVG 亮色重绘（如后续需要）留待独立轮次；本轮改动未 git 提交，待用户确认后提交�?---

### 6.124 反馈回路�?1 层落地：质量画像 + 调度回灌 + 动�?TTL 复合�?+ executorDoneIssues LLM 回填�?026-08-21�?
#### 1. 背景与决�?
- **背景**：《HelloAI设计评审与改进建议》交叉验证报告指出两条反馈回路缺口：�?质量画像未回灌调度选人（executor 历史一次通过�?驳回原因不参与选人与租�?TTL）；�?上一�?review 驳回的问题（executorDoneIssues）从未回流给 executor 作为"已修复清�?。按《反馈回路与契约先行落地计划》Phase 1 落地（用户拍板：画像=新表增量维护、executorDoneIssues=LLM 语义相似度对比、契约回�?TaskRunningSpec 新增 contract 字段——契约先行拆解为 Phase 2）�?- **决策**：画像随 review_record 落库同事务增量维护（QualityProfileUpdater 收口�?ReviewServiceImpl.recordAutoReview/createReview 两处）；qualityRank 插在 dutyRank 之后、score 之前，低权重�?.1）起步可配置关闭；动�?TTL 复合�?= 失败折算�?+ 质量分加权（同源 quality-weight 配置，开关可回退）；executorDoneIssues 回填走平台级凭证 LLM 语义对比，无凭证静默跳过（best-effort 不阻断回写主链路）�?
#### 2. 实际落地

- **V54 `agent_quality_profile` 画像�?*：agent_id 部分唯一索引 WHERE deleted=0 + 审计列全套；reviewed_count/approved_count/first_reviewed_count/first_pass_count/total_score/rework_round_sum/issue_defect_stats JSONB（[defect] 标签计数�? reviewer_reviewed_count/reviewer_disagreement_count（Phase 4 预留�? last_review_record_id（增量幂等判�?+ 对账起点）；DO 验证块�?- **V55/V56**：`task_running_spec.contract JSONB`、`sub_task.is_contract SMALLINT NOT NULL DEFAULT 0`（契约先�?Phase 2 预留）�?- **agent/quality �?*：`AgentQualityProfile` 实体 + `AgentQualityProfileMapper`（incrementCore 单条 UPDATE 原子增量 + mergeDefectStats JSONB LATERAL 原子合并，规避并发读改写竞态）；`AgentQualityProfileService/Impl`（getProfile / computeQualityScore（首轮通过�?0.5 + 平均分归一 0.5，无数据�?null�? renderHistorySection（Phase 3 预留�? rebuild 全量重算兜底）；`QualityProfileUpdater`（执行者维度取 sub_task.assigned_agent_id 落库时刻归属；防�?last_review_record_id 递增判定；失�?best-effort 不阻�?review 主链路）；`DefectLabelParser`（纯函数，[defect] 标签提取 + 空白折叠 + 30 字符截断，增�?重算同口径）�?- **调度回灌**：`AgentSelector.resolveComparator` dutyRank 之后插入 qualityRank（qualityScore/20 档位 × quality-weight，默�?0.1�?=关闭；null 安全降级不参与排序）；`AgentDutyLeaseServiceImpl.resolveTtlMinutes` performanceScore 升级为复合分（clamp [0,100]，质量分缺失回退原逻辑）�?- **executorDoneIssues 回填**：`ExecutorIssueResolutionAssessor`（严�?JSON 协议 + strip fence 容错，失败降级跳过）+ classpath 模板 `prompts/executor-done-issues.md`；LLM 通道平台级凭�?> 无凭证跳过，超时独立配置默认 30s；挂�?`ExecutionResultHandler` 成功回写路径（异步执行，写入前重�?context 防覆盖）+ timeline `sub_task_executor_done_issues` 三�?payload�?- **验证基建**：`AdminQualityController`（POST /api/admin/quality/rebuild/{agentId} + /dispatch/{subTaskId}，薄透传零编排，�?ps1 实测）；`scripts/powershell/verify-quality-profile.ps1` S1~S5（画像增量初始行 / 首轮通过�?返工轮数+缺陷标签口径 / qualityRank 回灌选人 / 动�?TTL 复合�?/ rebuild 对账），UTF-8 with BOM + Parser.ParseFile 0 error�?
#### 3. 验证结果

- 单测 helloai-core 868/868 全绿（AgentQualityProfileServiceTest 14 / QualityProfileUpdaterTest 13 / ExecutorIssueResolutionAssessorTest 21 新增 + AgentSelectorTest / AgentDutyLeaseAdaptiveTtlTest 增补）；helloai-job 66/66 无回归�?- `mvn -pl helloai-api -am compile` BUILD SUCCESS（AdminQualityController）�?- verify-quality-profile.ps1 静态自检通过（BOM EF BB BF + Parse 0 error）；真实环境实测待用户执行（docker compose up + helloai-start :6565 运行后跑脚本）�?
#### 4. 影响与遗�?
- 行为变更：调度选人新增质量维度（qualityRank）、动�?TTL 变为复合分——quality-weight 默认 0.1 低权重起步防抖动，可配置 0 完全关闭回退原行为�?- 遗留：Phase 3 历史表现摘要注入 Prompt（renderHistorySection 已预置）/ Phase 4 Reviewer 双审 + 抽检（reviewer_* 计数列已预置）按计划依次推进；本轮改动未 git 提交，待用户确认后提交�?
---

### 6.125 契约先行拆解：spec contract 双实�?+ 完成回流 + 全局渲染�?026-08-22�?
#### 1. 背景与决�?
- **背景**：《反馈回路与契约先行落地计划》Phase 2：多模块接口/多组件协作任务在拆解阶段先生成「契约定义」子任务（接口签�?数据模型/错误码表），�?DAG 最上游；契约子任务完成后产出回�?`task_running_spec.contract`（V55 已预置），全局注入所有下游执�?Prompt，下游按契约实现、Reviewer 按契约验收，从源头减少模块间口径漂移与返工�?- **决策**：拆解侧�?LLM 协议驱动（planner-decompose.md �?`"contract": true` 可选字段，解析�?`sub_task.is_contract` V56 SMALLINT）；契约存储双实现齐动（Jsonb 分段�?+ Table 行级天然无竞态）；回流挂�?`SubTaskCompletionListener`（REVIEW→DONE 事务提交后异步，best-effort 不阻断解锁下�?Task 收尾主链路）；渲染节�?# 任务契约」在 Baseline 之后、contract 非空时渲染（零噪音原则：非契约任务不加节）�?
#### 2. 实际落地

- **拆解协议与落�?*：`prompts/planner-decompose.md` 拆解要求加第 6 点（多模块接�?多组件协作任务强制生�?1 个契约定义子任务，deliverable=接口签名/数据模型/错误码表，验收标准可检查）+ 拆解原则「契约先行」规�?+ 输出 JSON �?`"contract": false` 字段；`PlanDraftItem` �?`Boolean contract`；`PlannerDecomposeAsyncServiceImpl.buildDrafts` 解析落库 `SubTask.isContract`（`Boolean.TRUE.equals` 归一 1/0；非法值整批解析失败走既有回退 PENDING 闭环，可重拆恢复）�?- **spec contract 字段双实�?*：`TaskRunningSpec` 不可变领域模型加 `contract`（Map）——builder/toMap/fromMap/toBuilder 完整往返，isEmpty() 计入 contract（修复：契约写入�?spec 判空返回空串导致渲染节丢失）；`TaskRunningSpecService` 接口�?`updateContract(Long taskId, Map<String,Object> contract)`；Jsonb 实现（taskId 粒度分段�?synchronized + toBuilder().contract() 重建 + writeToTask �?task.context.runningSpec，appendExecutionRecord 同步改为重建 builder 保留 contract）与 Table 实现（`TaskRunningSpecEntity.contract` PgJsonbTypeHandler + `specMapper.updateContract` @Update + assembleDomain 组装）双实现同步；渲染节�?# 任务契约」（契约来源 title + content 正文，Jsonb buildExecutorPromptSection �?Table JsonbPromptRenderer 双处一致）�?- **契约产出回流**：`SubTaskCompletionListener.onSubTaskCompleted` 首步 `backfillContract`——isContract=1 检测（非契约零动作）→ 产出提取（物化附�?ACTIVE 版本优先、`SubTaskOutputExtractor` �?`context.lastExecution.output` 回退，与执行链依赖装载同源口径）�?`updateContract` �?`{subTaskId, title, content, backfilledAt}` �?timeline `sub_task_contract_backfilled` success/skipped/failed 三�?payload；失�?best-effort 绝不阻断解锁下游 / Task 收尾主链路�?- **验证基建**：`AdminQualityController` �?GET /api/admin/quality/spec-section/{taskId}（薄透传 `buildExecutorPromptSection`，供 ps1 S3/S4 断言契约节渲染）；`scripts/powershell/verify-contract-first.ps1` S1~S4（S1 planById 真实 LLM 拆解轮询草案断言契约子任�?is_contract=1 + confirmPlan / S2 SQL 预置契约子任�?REVIEW→APPROVED 触发回流，断言 contract 非空 + 内容保真 + timeline success / S3 spec-section 含�?# 任务契约」节与契约正�?/ S4 非契约任�?contract 保持 NULL 且无契约节零噪音；双存储模式兼容断言 context.runningSpec.contract �?task_running_spec.contract 任一非空；UTF-8 with BOM + Parser.ParseFile 0 error）�?
#### 3. 验证结果

- 单测 helloai-core 883/883 全绿（PlannerDecomposeAsyncServiceImplTest 14 / TaskRunningSpecJsonbServiceTest 8 / TaskRunningSpecTableServiceTest 4 / SubTaskCompletionListenerContractTest 5 新增，含 contract 往�?渲染�?append 保留/重复覆盖/附件回流/output 回退/skipped/非契约零动作/失败三态）；helloai-job 66/66 无回归�?- `mvn -pl helloai-api -am compile` BUILD SUCCESS（AdminQualityController spec-section 端点）�?- verify-contract-first.ps1 静态自检通过（BOM EF BB BF + Parse 0 error）；真实环境实测待用户执行（docker compose up + helloai-start :6565 运行后跑脚本，S1 依赖平台�?LLM 凭证）�?
#### 4. 影响与遗�?
- 行为变更：多模块协作任务的拆解草案可能包含契约子任务（DAG 最上游）；契约子任�?DONE 后所有下游执�?Prompt 自动附加�?# 任务契约」节；非契约任务与契约缺失场景零噪音�?- 遗留：Phase 3 历史表现摘要注入 Prompt / Phase 4 Reviewer 双审 + 抽检 / Phase 5 质量度量看板按计划依次推进；本轮改动�?git 提交，待用户确认后提交�?
### 6.126 启动失败修复：@MapperScan 登记 agent.quality.mapper 包（2026-08-21�?
#### 1. 背景与根�?
- **现象**：真实环境启动失败，链路�?adminAgentController �?�?�?mcpToolServiceImpl(param 3=subTaskService) �?subTaskServiceImpl(param 11=concurrencyQuotaService) �?agentDutyLeaseServiceImpl(param 5) �?agentQualityProfileServiceImpl baseMapper 注入失败：`No qualifying bean of type 'com.helloai.core.agent.quality.mapper.AgentQualityProfileMapper' available`�?- **根因**：Phase 1.4/1.5 新增�?`AgentQualityProfileMapper` 位于 `agent.quality.mapper` 包（�?`agent.mapper` 平级），�?`HelloAIApplication` �?`@MapperScan` 是显式包清单（agent/task/system/planner 四个 mapper 包），MyBatis `@MapperScan` 不递归子包；且显式 `@MapperScan` 存在�?`@Mapper` 注解�?starter 自动扫描不再生效，新包完全无 bean。单测未暴露：core 单测不加�?helloai-start 启动类上下文�?
#### 2. 修复与验�?
- `HelloAIApplication` `@MapperScan` 清单�?`com.helloai.core.agent.quality.mapper`（全仓库 mapper �?5 个包，逐一核对后仅此遗漏）�?- `mvn -pl helloai-start -am compile -DskipTests` BUILD SUCCESS；真实启动验证待用户重启应用确认�?
### 6.127 循环依赖修复：ExecutorIssueResolutionAssessor 懒解析断�?+ CODE_STYLE §7.7�?026-08-21�?
#### 1. 背景与根�?
- **现象**：修�?§6.126 mapper 遗漏后真实启动暴露第二层问题——`The dependencies of some of the beans in the application context form a cycle`，环�?`LlmProviderChatClientFactoryRegistry �?deepSeekProviderChatClientFactory �?toolCallingManager �?toolCallbackResolver �?mcpToolConfig �?mcpMcpServer �?mcpToolServiceImpl �?executionResultHandler �?executorDoneIssuesBackfiller �?executorIssueResolutionAssessor �?回到 Registry`�?- **根因**：Phase 1.3 executorDoneIssues 回填链路引入三条新依赖边闭合构造器环——`ExecutionResultHandler �?ExecutorDoneIssuesBackfiller �?ExecutorIssueResolutionAssessor �?LlmProviderChatClientFactoryRegistry`，�?Registry 依赖链经 MCP tool 链反向回�?`mcpToolServiceImpl �?executionResultHandler`。Spring Boot 3.4 默认禁循环（allow-circular-references=false），启动直接失败。单测未暴露：单测不加载完整容器上下文，构造器环只在真实装配时显形。项目已有先例（SubTaskServiceImpl.attachmentServiceProvider 懒解析打�?AttachmentService 反向环）未沿用�?
#### 2. 修复与验�?
- **断环**：`ExecutorIssueResolutionAssessor` 注入 `LlmProviderChatClientFactoryRegistry` 改为 `ObjectProvider` 懒解析——构造不解析、运行时 `getIfAvailable()` 取容器已完成单例，取不到�?best-effort 降级跳过回填（与既有降级哲学一致）；字段注释标明循环链路（CODE_STYLE §7.7 要求）�?- **测试适配**：`ExecutorIssueResolutionAssessorTest` 构造器入参�?ObjectProvider mock，`setUp` �?`lenient()` 包装 provider stub（stripFence 纯函数用例不触碰 provider，避�?UnnecessaryStubbing）�?- **规范沉淀**：CODE_STYLE 新增 §7.7 循环依赖处理规范（禁构造器环、禁 allow-circular-references 放开、ObjectProvider 断点选择、注释要求、两处先例）；�?.2 启动类示�?@MapperScan �?planner/quality 两包消除文档失真�?- **验证**：ExecutorIssueResolutionAssessorTest 21/21 全绿；helloai-core 883/883 全量无回归；`mvn -pl helloai-start -am compile` BUILD SUCCESS。真实启动验证待用户重启应用确认�?
### 6.128 真实环境修复：画�?jsonb 类型映射 + NESTED savepoint 事务隔离�?026-08-21�?
#### 1. 背景与根�?
- **现象**（用户真实环境实测）：POST /api/reviews 请求 500。画�?INSERT �?`column "issue_defect_stats" is of type jsonb but expression is of type character varying`；随后同事务内后续语句报 `current transaction is aborted`�?5P02），review 主链路（reworkFresh）被拖死�?- **根因 1（类型映射）**：`AgentQualityProfile.issueDefectStats` 误用 MyBatis-Plus 内置 `JacksonTypeHandler`（write �?setString，PG 拒绝 varchar 隐式�?jsonb）；项目 jsonb 列先例是 `PgJsonbTypeHandler`（PGobject type=jsonb，TaskRunningSpecEntity.baseline/contract 同款）未沿用�?- **根因 2（事务隔离缺陷）**：`QualityProfileUpdater.onReviewRecordPersisted` �?review 落库同事务，best-effort �?catch 在同事务内无效——PG 事务内任一语句失败即整�?aborted，catch 后主事务后续 SQL 必炸。并发首次建画像�?DuplicateKeyException 退化路径同样潜伏此问题�?
#### 2. 修复与验�?
- `AgentQualityProfile.issueDefectStats` typeHandler �?`PgJsonbTypeHandler`�?- `onReviewRecordPersisted` �?`@Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)`：savepoint 隔离——与 review 同提交（主事务回滚画像一并回滚，保持同事务口径），画�?SQL 失败仅回�?savepoint，主事务�?aborted；无事务上下文等�?REQUIRED�?- CODE_STYLE §7.5 补「best-effort 副链路隔离例外」：同事务降级类副链路必�?NESTED 而非 REQUIRES_NEW（先�?QualityProfileUpdater）�?- 验证：QualityProfileUpdaterTest/AgentQualityProfileServiceTest/ExecutorIssueResolutionAssessorTest/ReviewServiceTest 56/56 全绿；helloai-core 883/883 无回归；helloai-start compile 成功。真实环境复测待用户重启后重�?review 请求�?
### 6.129 真实环境修复：mergeDefectStats JSONB 合并覆盖语义�?026-08-21�?
#### 1. 背景与根�?
- **现象**（数据库对账发现）：任务「Python学习+项目搭建双线并行4周方案生成」跑完后，`agent_quality_profile.issue_defect_stats`（agent 2090277886093889538）只�?1 个标�?`{"无法确认验收标准�?/6条在 plan.md 中真实存在：�?: 1}`；但�?agent 名下 6 �?REJECTED 评审共含 15+ 个不�?[defect] 标签，理应是多键累积 map�?- **根因**：`AgentQualityProfileMapper.mergeDefectStats` �?SQL �?`jsonb_each_text(入参)` 为遍历域聚合写回——只遍历本次入参 key，旧 map 中本次未出现�?key 被整体丢弃，实际语义是「新标签集合覆盖」而非注释声称的「�?key 累加」。最后一�?REJECTED（子任务6 round1，仅 1 �?defect）合并后把此前所有标签冲掉，与数据吻合。重算路径（rebuild）不受影响（Java �?stats.merge 累积正确）�?
#### 2. 修复与验�?
- SQL 改为�?key �?�?key 并集遍历域�?key 相加：`FROM (SELECT CAST(#{statsJson} AS jsonb)) incoming CROSS JOIN LATERAL (SELECT jsonb_object_keys(p.issue_defect_stats) UNION SELECT jsonb_object_keys(incoming.json)) all_keys`，同 key 相加、旧独有 key 保留、新 key 追加；仍为单�?UPDATE 原子合并�?- 修复前用 MCP 只读 SQL 在真�?PG 验证新表达式语义（并集合并输�?`{"A":1,"B":7,"C":3}`；LATERAL + 外层行旧值引用在标量子查询上下文可用）�?- 验证：QualityProfileUpdaterTest/AgentQualityProfileServiceTest/ExecutorIssueResolutionAssessorTest 48/48 全绿；helloai-start compile 成功�?- **历史数据自愈**：存量画像的 issue_defect_stats 已被覆盖语义污染，可调用 `POST /api/admin/quality/rebuild/{agentId}`（agentId=2090277886093889538）重算修正——rebuild 路径口径正确�?
### 6.130 Phase 3：历史表现摘要注入执�?Prompt（反馈回路第 2 层）�?026-08-21�?
#### 1. 背景与方�?
- **目标**：反馈回路第 2 层——把执行者质量画像（Phase 1 产物）渲染成「你的历史表现」摘要段注入每次执行 Prompt，让 LLM 执行前感知自身历史通过率与常见驳回原因，形成「评�?�?画像 �?下次执行改进」的闭环�?- **渲染�?*（Phase 1 已预�?`renderHistorySection`，本轮接通执行装配点）：画像缺失�?reviewed=0 返回空串；有数据输出�?# 你的历史表现�? 累计评审/通过�?一次通过�?+ 最常见驳回原因 TOP N（含计数�? 本轮自查提醒�?- **装配�?*：`SubTaskExecutionServiceImpl.executeOnce` �?pluginSpec 段拼接后追加 historySection（`mergeSpecSections` 同款空值语义）；新增私有方�?`renderHistorySectionSafely` try-catch 包一层——画像查�?渲染任何异常降级空串，绝不让副链路拖死执行主链路（N18 P1 + §6.128 best-effort 哲学，�?.128 教训再落地）�?- **可观�?*：timeline `sub_task_spec_context_loaded` payload 新增 `historySummary` 布尔（画像段是否注入），与既�?pluginSpec / depCount 等装配事实并列�?
#### 2. 实现与测�?
- 主代码：`SubTaskExecutionServiceImpl`（装配点 2 �?+ 私有降级方法 + 注入 `AgentQualityProfileService`）；�?Controller 改动；无 Flyway�?- 单测：`SubTaskExecutionServiceTest` 新增 @Nested ExecuteOnceHistorySection 3 用例（TC-1 画像注入 �?prompt 含历史表现段 + payload historySummary=true；TC-2 空串 �?prompt 零噪�?+ historySummary=false；TC-3 渲染异常 �?降级空串执行不失败）。首次运行踩 IDE 增量编译残留坑（`renderHistorySectionSafely undefined` �?test-compile SUCCESS）→ `mvn clean test` 解决；helloai-core 886/886�?83+3）全�?+ helloai-job 66/66 无回�?+ helloai-start compile BUILD SUCCESS�?- S6 场景脚本：`verify-quality-profile.ps1` 新增 `Run-Scenario6`——预�?REWORK 子任务（幂等建任�?+ SQL �?REWORK）→ `POST /api/sub-tasks/executeById/{id}`（admin 触发异步执行）→ Wait-Until 轮询 `conversation_message` �?`sub_task_execute_user_prompt`（addMessage �?executeSync 之前落库——LLM 调用失败 prompt 也在，断言链条稳定）断言「你的历史表现」「累计评�?N 次」「本轮提醒」三段标�?+ `task_timeline` `historySummary=true`；超时二态区分：无执行记�?�?SKIP（本地消费链未启动，环境依赖），有执行记录但�?prompt �?FAIL（真实缺陷）。`-Scene` ValidateSet �?S6、场景调度区�?teardown（含 conversation_message 清理）同步接线；UTF-8 with BOM + Parser.ParseFile 0 error�?- 真实环境装配自检待用户重启应用后执行 `verify-quality-profile.ps1`（S1~S6）�?
### 6.131 联网搜索 V45：搜索查询规划器——规则清�?+ 条件 LLM 改写 + 多候选词顺序降级�?026-08-21�?
#### 1. 背景与根�?
- **现象**：用户提问「能否给我提供一份快速学习Python + 快速搭建项目的完整方案，按"�?�?做？」，博查返回零结果（「未查询到相关网页」），联网搜索形同虚设�?- **根因**：`doWebSearch` �?`extractQueryKeyword` 把用户原消息截前 40 字直接当搜索词——敬语（能否给我提供一份）、疑问前缀、标点、连接符�? / 以及）全是检索噪音；且单查询零结果即放弃，无第二候选词补救�?- **方案**（用户确认两个取舍）：① 规则清洗总是执行（零成本�? LLM 改写条件触发；② LLM 改写走轻量直连快模型（JDK HttpClient 直连 DeepSeek chat/completions，独�?5s 超时，不�?executeSync + Planner 重链路）；③ 多候选词顺序降级搜索（首个命中即停），不做无条件并行�?
#### 2. 实现

- **`WebSearchProperties` 新增 5 配置**（helloai-common）：`queryRewriteEnabled`（默�?true�? `queryRewriteBaseUrl`（默�?DeepSeek 官方 chat/completions�? `queryRewriteModel`（deepseek-chat�? `queryRewriteTimeoutMs`�?000�? `maxQueries`�?）；复用既有 `deepseekApiKey` 作改�?Key，空 Key 自动禁用改写（零配置降级）�?- **新增 `SearchQueryPlannerService` / `SearchQueryPlannerServiceImpl`**（按 CODE_STYLE §4.2 接口+impl 成对拆分；原计划命名 SearchQueryPlanner/DefaultSearchQueryPlannerImpl 按用户「修改要符合代码规范」要求整改为规范命名）：三层结构——规则清洗（去敬�?疑问前缀 �?按连接符拆分多主�?�?去标点语气词 �?20 字截断去重）�?条件触发判定（仅当规则产出单候选词且原�?>30 字或含疑问句式才�?LLM）→ LLM 改写（`prompts/websearch-query-rewrite.md` 模板，temperature=0 / max_tokens=256，宽�?JSON 解析，失败降级规则结果）；契�?`planQueries` 绝不抛异常（搜索是增强不是门槛，V34 降级哲学延续）�?- **`WebSearchOutcome` 新增 `queries` 字段**（@Builder.Default 空列表）：记录实际尝试的全部候选词�?- **`RequirementClarifyServiceImpl.doWebSearch` 改�?*：构造器注入 `SearchQueryPlannerService`（显式全参构造器）；先由规划器产出多候选词（规划器返回�?�?回退 `extractQueryKeyword` 截断旧逻辑），候选词逐个 `webSearchService.search` 首个非空即停；纯 URL 域名回退 / 直取失败域名前置（V43/V44 逻辑）继续作用于首个候选词；`buildWebSearchMap` �?`queries` 键进 payload�?- **前端查验�?*：`WebSearchTrace` �?`queries?: string[]`；`WebSearchBar.vue` 新增 `queryLine` computed——多搜索词显示「已依次搜索 N 个关键词：A、B」，旧消息无 queries 键回退单词 query（向前兼容）�?
#### 3. 测试与验�?
- 新增 `SearchQueryPlannerServiceImplTest` 7 例（原句拆多候选词 / 短消�?/ 空白输入 / 敬语剥离 / maxQueries 封顶 / LLM 端点不可达降�?/ 开关关闭纯规则）；`RequirementClarifyServiceTest` +3 例（顺序降级首命中即�?/ 全零结果 queries �?payload / 规划器空回退截断词），存量用例�?Mockito 默认�?List 自动走兜底路径行为不变�?- `mvn test -pl helloai-core -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` 全量 **896/896 全绿 BUILD SUCCESS**；前�?`npm run type-check`（vue-tsc�? 错�?- �?Flyway，重启后端生效；真实环境用户原问端到端回归待实测（需博查 Key 有效或切 provider）�?
### 6.132 真实环境修复：MyBatisPlusConfig 全局 Map→JacksonTypeHandler 劫持 rebuild 数据源查询导�?500�?026-08-21�?
#### 1. 现象与根�?
- **现象**：Phase 3 装配自检�?`POST /api/admin/quality/rebuild/2090277886093889538` �?500（traceId 284a77d640de4edc），异常栈为 `JacksonTypeHandler �?MismatchedInputException: Cannot deserialize LinkedHashMap from Integer value`，`Error attempting to get column 'record_id'`�?- **排查�?*：MCP 只读确认 `review_record` 表结构与数据全正常（复现 SQL 返回 3 行）�?javap 反编�?MybatisConfiguration/TypeHandlerRegistry 证实构造器�?Map 注册 �?定位 `helloai-start` `MyBatisPlusConfig.mybatisPlusConfigurationCustomizer` 历史遗留全局注册 `Map.class/HashMap.class/List.class/ArrayList.class �?JacksonTypeHandler`（为 JSONB �?SELECT �?null 而加）�?- **根因**：`AgentQualityProfileMapper.selectRebuildSource` 返回 `List<Map<String, Object>>`，MyBatis 注解查询推断 resultType=Map.class，`hasTypeHandlerForResultObject` 因全局注册命中（jdbcType=null 通配）为 true �?`createPrimitiveResultObject` �?*第一�?* record_id 裸数字当�?JSON 反序列化�?Map �?MismatchedInputException �?500。全项目仅此一�?Map 返回自定义查询，此前从未被真实调用故未暴露�?
#### 2. 修复与验�?
- 新建 `RebuildSourceRow` DTO（`com.helloai.core.agent.quality.dto`，字�?recordId/result/score/round/issues），Mapper 返回类型改为 `List<RebuildSourceRow>`，ServiceImpl 循环体改 getter 访问并删�?str/intVal/longVal 三个 Map helper；类 Javadoc 记录根因防复发�?- `MyBatisPlusConfig` 注册块补副作用警告注释：返回值被推断�?Map.class 的查询会被整行按 JSON 解析�?*Mapper 自定义查询禁止返�?List&lt;Map&gt;，必须用具体 DTO**�?- 单测同步：`AgentQualityProfileServiceTest` row() helper �?`Map.of(...)` 改为构�?`RebuildSourceRow`；`mvn test -pl helloai-core -am "-DskipTests=false" -Dtest=AgentQualityProfileServiceTest` 14/14 全绿（含 rebuildAggregatesConsistently 口径一致性）�?- 至今全项目仅 `selectRebuildSource` 一处踩中，Grep 确认无其�?`List<Map<String, Object>>` Mapper 返回�?- 生效方式：重启应用后重试 rebuild 即可（无 Flyway 变更）�?
### 6.133 admin 授权拦截收口：AdminOnlyInterceptor + /api/admin/** 授权防线（评审报�?P0）（2026-08-21�?
#### 1. 背景与结�?
- **背景**：《HelloAI 后端代码评审报告》（@9442102）P0 安全问题——`AuthInterceptor` 只做认证（X-Admin-Token �?admin，Bearer �?agent），`WebMvcConfig` 无任何授权拦截，`/api/admin/**` 全部端点仅凭认证即可访问，agent 身份（外�?AI �?API Key）可越权调用所有管理端点。本轮按批准计划落地，仅修该 P0，报告其余条目不做�?- **事实核对（计划阶段逐项验证�?*：① `/api/admin/**` 实际�?**8 �?* Controller（报告列 7 个，漏了 `AgentDutyLeaseController` `/api/admin/duty-leases`）：AdminAgent / AdminLlmProvider / AdminProviderConfig / AdminConfig / AdminPrompt / AdminDashboard / AdminQuality / AgentDutyLease；② 全仓�?`_authType == "admin"` 检查仅 3 处（AuthController 改密�?/ CredentialController / SubTaskController），路径均不�?`/api/admin/**` 下，拦截器覆盖不到，作纵深防御保留；�?`sys_user.role` �?ADMIN/SUPER_ADMIN（Flyway 默认 'ADMIN'），单角色现状下校验 `_authType == "admin"` 即足够，role 字段按报告选项 B 处理——文档标注「单角色，role 预留」，不写 role 校验逻辑�?- **历史陈述纠偏**：�?.81「权限颗粒度审计收口」中「平台级凭证管理（AdminLlmProviderController / AdminProviderConfigController）走 admin 拦截器」的表述�?2026-08-21 之前**不属�?*（当时不存在任何 admin 拦截器，admin 端点只过认证不过授权）。差距表 N10 同步存在该引用。历史行不改写，以本条目与差距表 §6 治理结论 2026-08-21 条澄清为准；本轮落地后该表述才真正成立�?
#### 2. 实现要点

- **新增 `AdminOnlyInterceptor`**（`helloai-api/.../interceptor/`）：`preHandle` 校验 `_authType == "admin"`（复�?`AuthInterceptor.AUTH_TYPE_KEY` 常量），�?admin �?`BizException(403, "需要管理员权限")`，由 `GlobalExceptionHandler` 统一�?HTTP 403 + `R.fail`，与 AuthInterceptor �?401 同构，全局处理器零改动�?- **`WebMvcConfig.addInterceptors`**：AuthInterceptor 之后追加注册 `AdminOnlyInterceptor`，`addPathPatterns("/api/admin/**")`。注册顺序保证执行时 `_authType` 已由认证阶段写入；语义分层：无凭证先�?Auth �?401，agent 身份�?AdminOnly �?403�? �?admin Controller 一次性全覆盖，后续新�?`/api/admin/**` 端点自动纳入�?- **`AgentController.register` 门控**：复�?`agentConfig.isAllowRegistration()` 开关（�?`registerWithToken` 同口径、同 403 文案「Agent 自注册已关闭，请联系管理员创建」），关闭后公开注册通道同样不可用�?- **单测** `AdminOnlyInterceptorTest` 3 例（Mockito �?Mock HttpServletRequest）：admin 放行 / agent 403 / `_authType` 缺失 403�?- **防回归脚�?* `scripts/powershell/verify-admin-authz.ps1`：admin 登录 �?创建/复用测试 Agent �?agent Bearer �?8 �?`/api/admin/**` GET 端点（各前缀一个，零副作用�? �?providerCode �?PUT 写端点断言 403 �?admin token 同批端点断言 200（确认不误伤管理员）�?无凭证断言 401 �?汇�?PASS/FAIL，FAIL>0 退出码 1。遵�?AGENTS.md 脚本规范（UTF-8 编码头、单引号拼接、CJK 只进注释）�?- **文档回填**：CODE_STYLE V1.8 新增 §6.8「授权拦截红线」（认证/授权分离、`/api/admin/**` 强制�?AdminOnlyInterceptor、agent 禁访 admin 端点、非该前缀�?admin 端点�?requireAdmin() 纵深防御、单角色 role 预留说明、verify-admin-authz.ps1 为验证手段）；差距表新增 N21「admin 授权拦截」→ 已交�?+ §6 治理结论含上述纠偏声明�?
#### 3. 验证结果

- `mvn -pl helloai-api -am compile`锛欱UILD SUCCESS銆?- `AdminOnlyInterceptorTest`锛?/3 鍏ㄧ豢锛堟牴 pom 榛樿 skipTests=true锛岄渶鏄惧紡 `-DskipTests=false`锛夈€?- `verify-admin-authz.ps1` 瀹炶窇锛堢敤鎴锋湰鍦扮粓绔紝2026-08-22锛夛細**17/17 鍏ㄧ豢**鈥斺€攁gent Bearer 鎺?8 涓?GET + 1 涓?PUT 鍐欑鐐瑰叏閮?403锛沘dmin token 鍚屾壒 7 绔偣鍏ㄩ儴 200锛堜笉璇激绠＄悊鍛橈級锛涙棤鍑瘉 401銆傝涓虹骇楠屾敹闂幆锛堣剼鏈緭鍑哄嵆浜嬪疄婧愶級銆傛敞锛欰I 鎵ц鐜锛圛DE 鍛戒护娌欑锛夌殑 loopback 鎺㈡祴瀵瑰涓绘湇鍔′笉鍙揪锛屽疄璺戝彧鑳藉湪鐢ㄦ埛鏈湴缁堢瀹屾垚锛屽悗缁悓绫昏涓虹骇楠屾敹鍚岀悊銆?
#### 4. 褰卞搷涓庨仐鐣?
- 褰卞搷锛氣憼 璇勫鎶ュ憡 P0銆宎dmin 鎺堟潈缂哄彛銆嶅叧闂€斺€擿/api/admin/**` 鍏ㄩ噺绔偣寮哄埗 admin 韬唤锛宎gent 瓒婃潈閫氶亾鍫垫锛孧CP 閫氶亾锛坄/mcp/**`锛変笉鍦ㄨ鍓嶇紑涓嬩笉鍙楀奖鍝嶏紱鈶?鎺堟潈闃茬嚎鏈夊崟娴?+ e2e 鑴氭湰鍙屽眰鍥炲綊鎶ゆ爮锛屽悗缁柊澧?admin 绔偣鑷姩琚鐩栵紱鈶?鎶ュ憡鍙戠幇浣嗛仐婕忕殑 `AgentDutyLeaseController` 涓€骞剁撼鍏ャ€?- 閬楃暀锛氣憼 鏃狅紙`verify-admin-authz.ps1` 宸蹭簬 2026-08-22 鐢ㄦ埛鏈湴缁堢瀹炶窇 17/17 鍏ㄧ豢锛夛紱鈶?鎶ュ憡鍏朵綑鏉＄洰锛堜緷璧栨柟鍚戙€佸法鍨嬬被鎷嗗垎銆佷簨鍔＄己澶便€佺姸鎬佹灇涓炬敹鍙ｃ€丏ELETE body銆?.4 娴嬭瘯绔偣闂ㄦ帶锛変笉鍦ㄦ湰杞寖鍥达紱鈶?宸叉湁 3 澶勬墜鍔?requireAdmin() 淇濈暀涓嶅姩锛堣矾寰勪笉鍦?`/api/admin/**`锛屼綔绾垫繁闃插尽锛夛紱鈶?鏈疆鏀瑰姩鏈湴 git commit锛屼笉 push銆?### 6.134 依赖方向红线落地：system 域搬迁 + AgentAuthPort/TaskPlannerPickerPort 端口反转（评审报告 P0，阶段一）（2026-08-22）

#### 1. 背景与决策

- **背景**：《HelloAI 后端代码评审报告》P0「核心领域依赖方向失控」——core 六业务域（planner/review → task → agent → system → shared）应为单向依赖，实测 system 域有 6 个文件反向 import task/agent 类型（Attachment* / Module* / Dashboard* / AdminDashboard* / ArtifactUpload* / AuthService+Impl），另有 task→planner 孤点 1 处（TaskFinalReportServiceImpl→PlannerAgentPicker）与 agent→task.mapper 直捅 4 处（阶段五目标，不在本轮）。
- **决策**：按批准计划（评审报告整改计划阶段一）冻结红线 + 逐项搬迁，每项独立小闭环（编译 + 相关单测 + 依赖脚本红色递减）。搬迁只改包归属与 import，不改业务行为，测试随迁。
- **归属判断**：附件（Attachment）与产出物上传（ArtifactUpload）归 task 域（owner 是 sub_task）；模块（Module）归 task 域；看板聚合（Dashboard/AdminDashboard）归 task 域 observability 子包；认证内核校验（validateAgentKey）整体下沉 agent 域端口，而非在 system 域注入 agent Service。
- **端口反转**：task→planner 孤点不搬类（PlannerAgentPicker 依赖 planner.entity.RequirementConversation 且被 planner 域 3 处自用，归属合理），改由 task 域定义 `TaskPlannerPickerPort`（仅抽象 pickForTask）、PlannerAgentPicker `implements`——consuming 域零 import、实现域合法下依赖。AgentAuthPort 同法（定义在 agent.port，AgentServiceImpl 实现）。

#### 2. 实现要点

- **红线冻结**：CODE_STYLE V1.9 新增 §3.x「依赖方向红线」（单向依赖图 + 4 条规则：向下依赖 / 跨域禁直捅 Mapper / 端口反转合法化 / ObjectProvider 登记制）+ 归属判断注记；新建 `scripts/powershell/verify-dependency-direction.ps1`（9 条断言：system × 4、task × 2、agent × 2、agent→task.mapper 零目标），红色命中 exit 1。
- **搬迁批次**（每批新文件 Write + 旧文件删除 + `.tmp/rewrite-*.ps1` 批量改 import）：
  - Attachment*（entity/service/mapper/impl）→ `task` 域；
  - Module*（4 文件）→ `task` 域（引用方 TaskServiceImpl/TaskServiceTest/ModuleController 改 import）；
  - Dashboard*/AdminDashboard*（4 文件）→ `task.observability` 子包，顺带消除对 agent.mapper 直捅（改注入 `AgentService.listAllOrderByScoreDesc/listActive/countAll/getById`，排行改 stream limit 10，countAll/listActive 返回值 `(long)` 强转）；
  - ArtifactUpload*（2 文件）→ `task` 域，测试随迁并补同包隐性 import（AttachmentService）；
  - AuthServiceImpl 摘除 agentMapper/agentService：新建 `agent.port.AgentAuthPort`（`Agent validateApiKey(String)` 401/403），AgentServiceImpl implements 并新增 validateApiKey（复用 getByApiKey），AuthService/Impl 删除 validateAgentKey 与 agent import，4 个调用方（McpAuthFilter/McpAuthFilterConfig/AuthInterceptor/WebMvcConfig/AuthController）改注入 AgentAuthPort，测试契约平移（AuthServiceTest 删嵌套类，AgentServiceTest 补 401/403，McpAuthFilterTest 改三参构造）。
  - TaskPlannerPickerPort（`task.port`，仅 `Agent pickForTask(Long)`）← PlannerAgentPicker implements，TaskFinalReportServiceImpl 注入端口，测试 mock 换端口类型。
- **遗留（登记）**：agent 域对 task.mapper 直捅 4 处（AgentDutyLeaseServiceImpl/AgentServiceImpl/InFlightDbQuotaService/McpToolServiceImpl）→ 阶段五「task?agent 事件解耦」目标（TaskDispatchPort + 领域事件），agent→task.mapper import 清零后脚本全绿。

#### 3. 验证结果

- 每步小闭环：`mvn -pl helloai-api -am compile`（覆盖 core+api）+ 相关单测（`-DskipTests=false` 显式，pom 默认 skipTests=true）+ 脚本红色递减全程追踪，system 域 4 向依赖先后归零 ?。
- core 全量：**534/534 全绿**（Failures=0 Errors=0）；TaskFinalReportServiceTest 13/13 + PlannerAgentPickerTest 13/13 回归（端口反转后）。
- 脚本最终态：8/9 PASS，唯一 FAIL 为 agent→task.mapper（阶段五目标，登记）。
- 打包：`mvn -pl helloai-start -am -DskipTests package` 出包（91MB；首次失败因旧服务进程占用 jar，停服后成功）。
- 重启 + 行为回归：`verify-admin-authz.ps1` 实测 **PASS=17 FAIL=0**（agent Bearer 403 证明 AgentAuthPort 认证链正常，admin 200 证明搬迁后 Dashboard/Module/Provider 链路正常）。

#### 4. 影响与遗留

- 影响：① system 域对 task/agent/planner/review import 全清零，红线冻结并有脚本门禁；② AgentAuthPort 使认证内核（AuthService+拦截器）只依赖 agent 域端口，方向合法；③ PlannerAgentPicker 双向受益（task 域不再反向依赖，planner 域使用零改动）。
- 遗留：① agent→task.mapper 4 处按计划归阶段五（TaskDispatchPort + 事件解耦）；② 阶段二（P1 事务三方法）、阶段三（巨型类拆分）、阶段四（P2 规范收口）、阶段五（事件解耦）待后续批次；③ 本轮改动未 git 提交，待用户确认后提交。

### 6.135 P1 事务缺失三方法收口（评审报告 P1，阶段二）（2026-08-22）

#### 1. 背景与决策

- **背景**：《HelloAI 后端代码评审报告》P1 指出三个方法的事务语义缺口：`getByIdForUpdate`（FOR UPDATE 锁无事务承托）、`updateDependsOn`（依赖回写无事务）、`markManualIntervention`（人工介入标记写多表无事务）。
- **决策**：`getByIdForUpdate` **不补** @Transactional——`SELECT ... FOR UPDATE` 的行锁随事务存续，方法自开事务会在返回瞬间释放锁，失去互斥意义；正确形态是"调用方在事务内调用"，故只审计调用方 + Javadoc 注明红线。`updateDependsOn` / `markManualIntervention` 按项目惯例（与 changeStatus/block 一致）在接口方法上补 `@Transactional(rollbackFor = Exception.class)`；markManualIntervention 内部 best-effort catch 吞异常语义保留（声明降级，异常不传播即不触发回滚）。

#### 2. 实现要点

- `SubTaskService.getByIdForUpdate` Javadoc：注明「必须在事务内调用」，说明行锁随调用方事务存续、自开事务立刻释放锁；当前唯一主代码调用方 `ExecutionCommandServiceImpl.createAssignedCommand`（已 `@Transactional`）满足前提。
- `SubTaskService.updateDependsOn` / `markManualIntervention`：接口方法补 `@Transactional(rollbackFor = Exception.class)`（调用方均跨类注入，代理生效）。

#### 3. 验证结果

- 审计：`getByIdForUpdate` 主代码调用方全仓库仅 1 处（createAssignedCommand），测试桩 2 类（ExecutionCommandServiceTest / ExecutionCommandServiceDispatchTest）不受影响。
- 回归：9 个相关测试类 `mvn -pl helloai-core -am test -DskipTests=false -Dtest=SubTaskServiceTest,SubTaskServiceHandoverTest,SubTaskServiceIsReadyTest,SubTaskServiceQuotaTest,SubTaskDispatchServiceTest,SubTaskReviewServiceTest,PlannerDecomposeAsyncServiceImplTest,ExecutionCommandServiceTest,ExecutionCommandServiceDispatchTest` → **108/108 全绿**（Failures=0 Errors=0）。

#### 4. 影响与遗留

- 影响：事务语义补齐，依赖回写与人工介入标记不再依赖隐式自动提交；FOR UPDATE 使用红线文档化。
- 遗留：阶段三（巨型类拆分）、阶段四（P2 规范收口）、阶段五（事件解耦）待后续批次；本轮改动未 git 提交。

### 6.136 P1 巨型类拆分：RequirementClarify 四拆 + SubTaskReview 两拆 + AgentServiceImpl 四组件（评审报告 P1，阶段三）（2026-08-22）

#### 1. 背景与结论

- **背景**：《HelloAI 后端代码评审报告》P1 指出三个 ServiceImpl 越过类规模红线（CODE_STYLE §7.8：>500 行触发拆分评审）：`RequirementClarifyServiceImpl`（1342 行）、`SubTaskReviewServiceImpl`（888 行）、`AgentServiceImpl`（856 行）。超大类的完整上下文无法装入模型窗口，是 AI 协作改错率的主要来源。
- **结论**：三巨头全数拆完且零行为变更——RequirementClarify 四拆（675 行）、SubTaskReview 两拆（546 行）、AgentServiceImpl 四组件（553 行）；每拆独立小闭环（编译 + 相关单测 + 依赖方向脚本）。

#### 2. 实现要点

- **RequirementClarifyServiceImpl 四拆**（`planner.clarify` 子包）：`IntentDetectionService`（意图识别，含意图词组正则，95 行）/ `ClarifyWebSearchOrchestrator`（联网搜索编排与 WebSearchOutcome 组装，235 行）/ `ClarifyReplyParser`（用户回复解析，352 行）/ `ConfirmCardProtocol`（确认卡 payload 协议，144 行）；主类 1342→675 行，仅保留对话状态机与编排。
- **SubTaskReviewServiceImpl 两拆**（`review.support` 子包）：`ReviewEvidenceAssembler`（核验证据装载组装，352 行）/ `VerdictParser`（裁决判定解析，127 行）；主类 888→546 行。
- **AgentServiceImpl 四组件**（`agent.service` 包，按职责聚类）：`AgentCredentialService`（API Key 键管）/ `AgentSkillPolicyService`（技能推导）/ `AgentLifecycleService`（状态机）/ `AgentStatsService`（统计）；主类删 4 族方法 + 12 参构造器，改构造委托 4 组件；856→553 行。
- **测试同步**：`AgentServiceTest` setUp 改为 `@Mock AgentMapper` + 4 组件（原 12 参构造 mock 全量替换），并补新增组件路径用例。

#### 3. 验证结果

- 每拆独立 `mvn -pl helloai-core -am compile` + 相关单测（`-DskipTests=false` 显式，根 pom 默认 skipTests=true）。
- helloai-core 全量 **896/896 全绿**（Failures=0 Errors=0），AgentServiceTest 14 用例全绿。

#### 4. 影响与遗留

- 影响：类规模红线存量清单（CODE_STYLE §7.8）三巨头清零；拆分后各组件可独立测试，AI 协作改错率下降。
- 遗留：阶段四（P2 规范收口）、阶段五（事件解耦）待执行；本轮改动未 git 提交，待用户确认后提交。

### 6.137 P2 规范收口 5 项：SysUserStatus 枚举 + deleteById 改 POST + paths.ts + Quality 门控 + catch 审计（评审报告 P2，阶段四）（2026-08-22）

#### 1. 背景与结论

- **背景**：《HelloAI 后端代码评审报告》P2 五项规范问题：状态字符串硬编码、DELETE 带 body、前端路径字符串散落、管理侧质量实测端点裸奔、catch 吞异常风险。
- **结论**：五项全部收口——SysUserStatus 枚举替换硬编码；deleteById 改 POST 语义化；前端路径常量集中单一事实源；Quality 端点配置门控（默认关闭）；catch 二分审计结论"无无意吞噬"。

#### 2. 实现要点

- **SysUserStatus 枚举**：新建 `common.constant.SysUserStatus`（ACTIVE 等），替换 `"ACTIVE"` 硬编码 3 处（AdminInitializer/AuthServiceImpl/SysUserServiceImpl）。
- **TaskController.deleteById 改 POST**：`POST /api/tasks/deleteById/{id}`（DELETE 语义不支持 body，地址/代理兼容风险）；前端 `task.ts` 同步 `request.post`；6 个验证脚本同改。
- **前端 paths.ts 路径常量收口**：新建 `src/api/paths.ts`——按资源分 16 组路径字典，静态路径直接字符串、带参路径箭头函数统一 `enc()=encodeURIComponent(String(s))`；16 个 api 文件（task/agent/subTask/settings/clarify/auth/activity/attachment/dashboard/duty/inbox/module/prompt/review/reward/rule）全部替换为 `paths` 引用，内联路径字符串清零。
- **AdminQualityController 配置门控**：新增 `admin.quality.enabled` 配置键（sys_config 置 `"true"` 才开放 rebuild/dispatch/spec-section 三端点，默认关闭）；`verify-quality-profile.ps1` / `verify-contract-first.ps1` 插 A1.5 步骤（`PUT /api/admin/config/updateByKey/admin.quality.enabled` 运行时开启）；`verify-admin-authz.ps1` 不受影响（拦截器层先于 Controller 门控执行）。
- **catch 吞噬二分审计**：全量 107 个 catch 块二分——空 catch 13 处（core 12 + job 1）全数补理由注释（10 处 dbg best-effort 调试上报 / AgentCapability parseInt 容错 / AgentMcpServerServiceImpl DuplicateKeyException 并发幂等 / OutboxRelayTask 重试计数保守降级）；非空 catch (Exception) 25 处抽查 16 处全部合格（带 e 日志 / rethrow / wrap 或明确 best-effort 降级注释）。结论：无无意吞噬。

#### 3. 验证结果

- 全仓库 `mvn -q -DskipTests compile` EXIT=0（门控改动先经 `-pl helloai-api -am` 编译验证）。
- 前端 `npm run type-check`（vue-tsc）EXIT=0；grep 双正则验证内联路径零残留。
- 行为级：verify-quality-profile.ps1 / verify-contract-first.ps1 已同步 A1.5 门控开启步骤，实跑待用户本地终端（AI 执行环境 loopback 对宿主服务不可达，与 §6.133 同）。

#### 4. 影响与遗留

- 影响：质量实测端点默认关闭防越权误用，验证脚本运行时一键开启；前端路径单一事实源，后续新增接口只改 paths.ts；catch 审计结论"无无意吞噬"。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.138 阶段五 task↔agent 事件解耦：TaskDispatchPort 端口反转 + agent→task.mapper 清零 + ObjectProvider 减半登记（评审报告 P1，阶段五）（2026-08-22）

#### 1. 背景与结论

- **背景**：《HelloAI 后端代码评审报告》P1 阶段五目标——task↔agent 双向耦合：agent 域 6 处直捅 `task.mapper`（SubTaskMapper），task 域 `SubTaskDispatchServiceImpl` 直依赖 agent 域 `ResilientDispatcher` 具体类；主代码 8 处 `ObjectProvider` 注入缺乏登记。
- **结论**：端口反转落地——task 域定义 `task.port.TaskDispatchPort`，agent 域 `ResilientDispatcher` 实现；agent 域对 `task.mapper` import 清零（含测试）；ObjectProvider 8 处减半为 4 处，其余 4 处登记保留理由；`verify-dependency-direction.ps1` 9 项全 PASS（阶段一唯一 FAIL 转绿）。

#### 2. 实现要点

- **TaskDispatchPort 端口反转**：task 域 `task.port.TaskDispatchPort`（消费方域定义接口），agent 域 `ResilientDispatcher` 实现；端口自带纯数据 `record DispatchConstraints(List<Long> allowedAgentIds, List<String> requiredSkills)`，`of()` 双空返回 null；`SubTaskDispatchServiceImpl` 注入端口替换具体类直依赖；`SubTaskService`/`SubTaskDispatchService` Javadoc `@link` 同步替换。
- **agent→task.mapper 清零（6 处）**：`AgentStatsService`（3 mapper → SubTaskService/RewardService/ActivityLogService 接口）；`AgentServiceImpl`（getRelatedCounts 收口 statsService；deleteAgentCascade 统计/unlink/物理删除经 task 域服务接口）；`McpToolServiceImpl`（claimAtomic → `SubTaskService.claimAtomic` boolean 适配）；`AgentDutyLeaseServiceImpl`/`InFlightDbQuotaService`（selectInFlightByAgent/countInFlightByAgent → SubTaskService 新增 2 方法）；`SubTaskService` 接口净增 7 方法承接（含 `@Transactional` 标注的 unlinkByAssignedAgent/claimAtomic，实现走 baseMapper）。
- **ObjectProvider 8 处减半**：4 处 Optional 化（`AgentChatClientServiceImpl`/`McpAuthFilterConfig`/`ExecutionDispatchValidator`/`OutboxRelayTask`——存在性探测场景，`Optional` 注入与 `getIfAvailable()==null` 语义等价，`orElse(null)`/`isEmpty()`/`orElseGet(Supplier)` 三种适配写法）；4 处登记保留理由（`CompositeArtifactStorage`/`WebSearchServiceRouter` 需 `orderedStream` 取多候选路由；`SubTaskServiceImpl`/`ExecutorIssueResolutionAssessor` 懒解析打破构造器循环）。
- **回归风险修复**：`AgentStatsService.getRelatedCounts` 计数强制 `(int)`（task 域服务接口返回 long，Long 装箱会让 AdminAgentController 侧 `(Integer)` 强转 500）。
- **测试同步**：AgentServiceTest（11 参构造器 + AgentStatsService 4 参新构造器 + agentMapper stub）、McpToolServiceTest（删 SubTaskMapper）、AgentDutyLeaseAdaptiveTtlTest/InFlightDbQuotaServiceTest（mapper→service）、ExecutionDispatchValidatorTest（Optional 字段 + 6 处重建）、OutboxRelayTaskTest（Optional 注入）、AgentChatClientServiceTest/PlatformAgentExecutionServiceTest（Optional.empty）。

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -o`：896/896 全绿（ExecutionDispatchValidatorTest 17 / AgentServiceTest 14 / McpToolServiceTest 26 / AgentDutyLeaseAdaptiveTtlTest 17 等全部通过）。
- `mvn -pl helloai-job -am test -DskipTests=false -o`：66/66 全绿（OutboxRelayTaskTest 7 含 Optional 注入适配；注意单跑 job 模块测试必须 `-am`，否则用本地仓库旧 core jar 报 IncompatibleClassChangeError）。
- `verify-dependency-direction.ps1`：9 项全 PASS（含 agent→task.mapper 清零断言，阶段一 FAIL 转绿）。

#### 4. 影响与遗留

- 影响：agent 域对 task 域只经服务接口/端口，任务分发方向仍 task→agent 单向；Optional 注入为 Spring 标准用法，Bean 缺失语义不变；验证脚本 agent→task.mapper FAIL 已闭合。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.139 启动回归修复：Optional 注入语义陷阱（立即解析候选 vs ObjectProvider 完全惰性）+ 循环依赖真相确认（2026-08-22）

#### 1. 背景与结论

- **背景**：§6.138 阶段五落地后启动失败，报 mcpAuthFilterConfig → agentServiceImpl → subTaskServiceImpl 循环引用；修复 Optional 化后再次启动报 ChatModel 缺失。
- **结论**：循环报错来自**旧编译产物/旧 jar**（13:08 target/classes 中 SubTaskServiceImpl 构造器 param 2 直接注入 AgentService；13:24/13:28 旧 jar 中 InFlightDbQuotaService 直接构造器依赖 SubTaskService/AgentDutyLeaseService），当前源码两条边均已被 ObjectProvider 断开（§6.138 已改），重新打包后环确认不存在；真实阻塞是 `AgentChatClientServiceImpl` 的 `Optional<ChatClient.Builder>`——**Optional 注入是"立即解析候选 bean 定义并创建"语义**（非惰性），候选 `chatClientBuilder`（ChatClientAutoConfiguration）依赖 `ChatModel`，而 DeepSeekChatAutoConfiguration 已被 application.yml 排除（api-key 置空 fail-fast 防护），导致启动期 UnsatisfiedDependencyException。改回 ObjectProvider 后启动成功。

#### 2. 实现要点

- **循环依赖真相**：Spring Boot 环渲染不显示重复节点——13:08 的 `mcpAuthFilterConfig → agentServiceImpl → subTaskServiceImpl` 实际回边是 subTaskServiceImpl(param 2 AgentService 直接) → agentServiceImpl；13:16 已编译的 ObjectProvider 版本（javap 验证构造器签名）与 13:32 启动日志（`Filter 'mcpAuthFilter' configured for use`）证明环已断。
- **语义陷阱修复**：`AgentChatClientServiceImpl.chatClientBuilderProvider` 由 `Optional<ChatClient.Builder>` 改回 `ObjectProvider<ChatClient.Builder>`（`orElse(null)` → `getIfAvailable()`）。理由：`ChatClientAutoConfiguration.chatClientBuilder` 的 bean 定义**始终存在**，Optional 注入会立即创建它并传递解析 `ChatModel` → 启动期直接炸；ObjectProvider 完全惰性，运行期 `getIfAvailable()` 返回 null 由既有 BizException（"未检测到 ChatClient.Builder"）兜底，与 application.yml "该 autoconfig 仅剩 ChatClient.Builder 兜底（缺失不阻断启动）"的设计意图一致。
- **其余 2 处 Optional 化核验为安全**：`McpAuthFilterConfig.Optional<MeterRegistry>`（Micrometer 候选创建成功、无业务依赖，13:32 日志实证）；`ExecutionDispatchValidator`/`OutboxRelayTask.Optional<ExecutionCommandMqPublisher>`（候选带 `@ConditionalOnProperty(producer-enabled=true)`，条件不满足时无候选 → empty）。
- **可选注入通用教训**：`ObjectProvider<T>` 与 `Optional<T>` 不等价——前者完全惰性（注入不触发创建），后者会立即解析容器中该类型的候选 bean 定义并触发创建（创建失败直接传播）。存在性探测目标若"有 bean 定义但创建可能失败/有依赖链"，必须用 ObjectProvider。

#### 3. 验证结果

- `mvn -pl helloai-start -am package -DskipTests` EXIT=0（全量打包）。
- `java -jar helloai-start-1.0.0-SNAPSHOT.jar --spring.profiles.active=local`：`Started HelloAIApplication in 15.548 seconds`，Tomcat 6565 正常监听，定时巡检（markOfflineIfStale/SessionAuthCleaner/DutyLeaseExpiration/超时巡检）全部运行，stderr 无异常。
- 相关单测：AgentChatClientServiceTest / InFlightDbQuotaServiceTest 通过。
- 同步遗留修复：`AgentChatClientServiceTest` / `PlatformAgentExecutionServiceTest` 仍按 §6.138 用 `Optional<ChatClient.Builder> builderProvider = Optional.empty()` 构造主类 → 编译报"Optional 无法转换为 ObjectProvider"；已同步改为 `mock(ObjectProvider.class)`（空 provider，mock 模式不触达 Builder），`mvn -pl helloai-core -am test '-Dtest=AgentChatClientServiceTest,PlatformAgentExecutionServiceTest'` 通过（各 1/1）。注意：`-pl helloai-core` 单独构建须带 `-am`，否则用本地仓库旧 helloai-common jar（缺 HeartbeatProperties/AgentQualityProperties）编译失败。

#### 4. 影响与遗留

- 影响：启动链路恢复；§6.138 的"Optional 化 4 处"修正为 3 处（AgentChatClientServiceImpl 保持 ObjectProvider 并登记理由）；应用内无全局 ChatModel bean 是设计预期，Agent 执行 100% 走 ProviderChatClientFactory 程序化构建。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.140 评审整改：task→agent.mapper 双向红线清零 + updateDependsOn 事务注解 + @MapperScan 登记断言（评审报告 P1/P2）（2026-08-22）

#### 1. 背景与结论

- **背景**：评审发现红线只执行了一个方向——§6.138 清零了 agent→task.mapper，但反方向 task→agent.mapper 仍在直捅（SubTaskServiceImpl / TaskServiceImpl / FeedServiceImpl 共 7 处 import），规范说一套代码做另一套；updateDependsOn 无 `@Transactional`（事务整改只做了一半）；@MapperScan 登记完整性无脚本断言（agent.quality.mapper 漏登记曾炸启动）。
- **结论**：按评审建议走①——禁直捅 Mapper 对所有方向生效（合法向下依赖不豁免），7 处直调全部收口为 AgentService 接口方法；updateDependsOn 补事务注解（单语句原子写不豁免口径同步进规范）；verify-dependency-direction.ps1 升级 v2 新增 task→agent.mapper 双向断言与 @MapperScan 登记断言，11 项全 PASS。

#### 2. 实现要点

- **AgentService 接口 +5 方法承接**：`lockByIdForUpdate`（行锁读 agent，必须在调用方事务内使用，与 SubTaskService.getByIdForUpdate 同规则）/ `listSummaries`（id/name/role/status/score 摘要，前端活动流数据源）/ `countExecutionByTaskId` / `countUnreadInboxByTaskRef`（删除前风险提示）/ `physicalDeleteTaskTrace`（inbox/execution_record/archive/message 四表物理删除，inbox DELETE 依赖 sub_task/review_record 子查询，调用方必须同一事务内先于子任务/审查记录执行）。AgentServiceImpl 构造器 11→14 参（+3 Mapper 注入），类注释同步说明 §6.140 承接。
- **TaskServiceImpl 去 5 个 agent Mapper**：getRelatedCounts 2 处（executionCount/unreadInboxCount → countExecutionByTaskId/countUnreadInboxByTaskRef）；deleteTaskCascade 4 表清理（inbox/execution_record/archive/message）收口为 `physicalDeleteTaskTrace` 单调用（日志 inboxCleaned → traceCleaned），删除顺序语义保持（先 agent 域痕迹、再 review/attachment/timeline、后 sub_task/module/task）；republish 的 PLANNER 列表改 `listByRole(AgentRole.PLANNER)`（@TableLogic 自动过滤 deleted=0，与原 selectList 语义一致）。类注释更新（AgentService 经 §6.140 收口承接 agent 域数据访问）。
- **FeedServiceImpl 去 AgentMapper**：resolveAgentNames 改 `agentService.listByIds(ids)`（IService 既有）；listAgentSummaries 改 `agentService.listSummaries()`。
- **SubTaskServiceImpl 去 AgentMapper**：assignNext 行锁改经既有 `ObjectProvider<AgentService>` 懒解析（遵循 RewardServiceImpl 判空惯例：getIfAvailable()==null 抛 BizException），不新增构造器依赖、不断环。
- **updateDependsOn 补 `@Transactional(rollbackFor = Exception.class)`**：当前实现是单条 baseMapper 原子 UPDATE 正确性无碍，但写方法一律带注解是规范总则；CODE_STYLE §7.1 明确"单语句原子写不豁免 @Transactional，防止后续追加第二处写操作时悄悄破窗"。
- **verify-dependency-direction.ps1 v2**：新增 `task 不得 import agent.mapper` 双向断言；新增 @MapperScan 登记断言（扫描 helloai-core 下全部 *Mapper.java 的 package 声明 vs HelloAIApplication @MapperScan 显式清单，当前 5 个 mapper 包全覆盖）；头注释版本 v1→v2。
- **CODE_STYLE V1.12**：§3.x 规则 2 双向化（禁令对所有方向生效，含合法向下依赖，附 §6.140 收口清单）；规则 5 补 @MapperScan 登记断言说明；§7.1 事务注解补单语句原子写不豁免口径。
- **测试同步**：TaskServiceTest（12→8 参构造器，删 5 个 agent Mapper mock）；AgentServiceTest（11→14 参构造器 +3 Mapper mock）；SubTaskServiceHandoverTest / IsReadyTest（去 AgentMapper）；SubTaskServiceQuotaTest（行锁断言 `agentMapper.selectByIdForUpdate` → `agentService.lockByIdForUpdate`，setUp 加 ObjectProvider stub 且 lenient——状态校验失败路径不触发行锁）。

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false`：896/896 全绿（含 AgentServiceTest / TaskServiceTest / SubTaskService 三测 / AgentInboxServiceTest）。
- `verify-dependency-direction.ps1`：11 项全 PASS（10 条 import 方向断言含双向 agent.mapper 红线 + @MapperScan 登记断言）。
- 启动验证：`mvn -pl helloai-start -am install -DskipTests` + `mvn -pl helloai-start spring-boot:run`：`Started HelloAIApplication in 13.492 seconds`，端口 6565 监听，`GET /api/feed` 200（FeedServiceImpl→AgentService 链路实测），无 BeanCreationException。
- 循环依赖确认：TaskServiceImpl / FeedServiceImpl 直接注入 AgentService 无环（agentServiceImpl 依赖 subTaskServiceImpl / rewardServiceImpl / activityLogServiceImpl，不依赖 Task / FeedService；SubTaskServiceImpl 侧 ObjectProvider 懒解析保持断环）。

#### 4. 影响与遗留

- 影响：task→agent.mapper 双向清零，跨域数据访问全部收敛到 service 接口；@MapperScan 漏登记风险脚本化防回归；规范与代码事实对齐（CODE_STYLE V1.12）。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.141 评审确认点：FOR UPDATE 锁语义 Javadoc 强制注明 + 事务豁免口径修正（2026-08-22）

#### 1. 背景与结论

- **背景**：§6.140 整改后评审顺带确认两点——① `SubTaskServiceImpl.getByIdForUpdate` 无事务但用 `.last("FOR UPDATE")`，行锁依赖调用方事务存续，接口注释已有"必须在事务内调用"语义但**实现类无 Javadoc**，直接看实现容易误以为方法自带锁；② `markManualIntervention` 无事务（getById + updateById 单次写 + try-catch 降级）评审判定可接受，但 §6.140 刚写进 CODE_STYLE §7.1 的口径是"单语句原子写**不**豁免"，与豁免诉求直接冲突。
- **结论**：getByIdForUpdate 保持无事务，实现类补 Javadoc 强制注明调用方事务前提；markManualIntervention 保持无事务（best-effort 降级写），CODE_STYLE §7.1 口径从"不豁免"修正为"单语句原子写可豁免 + 触发条件"，版本升 V1.13。

#### 2. 实现要点

- **SubTaskServiceImpl.getByIdForUpdate 补 Javadoc**：明确"只发行锁 SQL 不自行开启事务，行锁随调用方事务存续而释放（方法返回即释放），自开事务会立刻释放锁失去互斥意义，唯一主代码调用方 ExecutionCommandServiceImpl.createAssignedCommand 已满足前提"——与接口注释同款语义，实现类补齐防误读。
- **CODE_STYLE V1.13 §7.1 口径修正**：写方法一律带注解为总则；豁免条件为"仅对单个实体执行单条 UPDATE/DELETE（含 getById + updateById 组合）且无跨实体一致性诉求"，豁免时须在方法 Javadoc 注明"单语句原子写，无事务"；一旦追加第二条写操作（第二张表 / 跨 Service 写）必须补注解；best-effort 降级写（try-catch 包裹、失败仅告警）同样豁免但须声明降级语义（参考 markManualIntervention）；已有注解的写方法（updateDependsOn）保持注解不倒退。
- **markManualIntervention 补 Javadoc（自洽）**：作为 §7.1 豁免参考案例，按新口径声明"best-effort 降级写（getById + updateById 单次原子更新，无跨实体一致性诉求，不加 @Transactional；try-catch 降级失败仅告警；追加第二条写必须补注解）"——规范立的规矩自身先遵守，避免重蹈"说一套做一套"。

#### 3. 验证结果

- 纯注释 + 文档改动，不影响编译逻辑与运行时行为（getByIdForUpdate / markManualIntervention 方法体零改动）；改动前 §6.140 已验证 896/896 全绿 + 启动验证通过，本轮无新增行为变更。

#### 4. 影响与遗留

- 影响：行锁方法实现类具备强制语义提示，误用风险下降；规范口径与评审确认结论一致（豁免条款有明确触发条件，防破窗初衷保留）。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.142 反馈回路 Phase 4：Reviewer 双审共识 + 抽检复审机制（2026-08-22）

#### 1. 背景与结论

- **背景**：反馈回路第 1 层（质量画像 + 调度回灌）已交付，但评审环节仍是单一 Reviewer 判定，无双审共识、无事后抽检、reviewer 维度画像无计数——评审质量本身缺闭环（§6.124/§6.125/§6.130 已覆盖执行者画像，评审者画像与抽检留白）。
- **结论**：Phase 4 落地双审共识 + 抽检复审：双审只落一条 review_record（reviewer1 为记录归属，reviewer2 判定保留在对话流与 timeline payload），防 QualityProfileUpdater 按 record 逐条增量导致执行者画像重复计数；抽检由 helloai-job 定时任务按比例抽样复审已 APPROVED 记录，分歧回写 timeline 并计数画像。

#### 2. 实现要点

- **V57 迁移 + 实体/Mapper**：新增 `review_recheck_log` 表（抽检日志），`ReviewRecheckLog` 实体/Mapper 归 task 域，Flyway 只增不改。
- **ReviewProperties（helloai-common，`helloai.review.*`）**：dual-review-enabled / dual-review-consensus-policy（REQUIRE_BOTH｜ANY）/ recheck-enabled / recheck-interval-ms / recheck-sample-ratio / recheck-max-batch / recheck-window-days；application.yml 未显式声明，走默认值（双审开、REQUIRE_BOTH、抽检开、1h、5%、20、7d）。
- **ReviewerPicker 接口 + Impl（`review/picker/`）**：pickSingle / pickDual / isDualReviewRequired，候选选取三段逻辑从 SubTaskReviewServiceImpl 搬入，对齐 planner/picker/PlannerAgentPicker 先例；CODE_STYLE §3.x review 域清单补 `picker` 子包。
- **SubTaskReviewServiceImpl 改造**：抽取 `doReviewWith`（渲染 Prompt → executeSync → 对话流双写 subtask_review_prompt/thinking/verdict → verdictParser.parseVerdict）；新增 `doDualReview`——v1/v2 任一 null 落 `sub_task_dual_review_incomplete` 停留 REVIEW；REQUIRE_BOTH 分歧 → `markManualIntervention("reviewer_disagreement")` + `sub_task_reviewer_disagreement` timeline + `recordReviewerStats(1,1)`；共识 → `applyVerdict(reviewer1, chosen)` + `recordReviewerStats(1,0)` + `sub_task_dual_review_consented`（payload 含 consensus/policy/pass1/pass2/score1/score2）；ANY 任一通过即过；新增 `recheckReviewRecord`（前置校验：record 存在/APPROVED/subTask 存在/pickSingle 有值/verdict 可判定 → `reviewService.recordRecheck` 落库 best-effort → 画像计数 → `sub_task_recheck_consistent` / `sub_task_recheck_discrepancy` timeline）。
- **AgentQualityProfileService.incrementReviewerStats + Mapper 增量 SQL**：UPDATE 无行 → INSERT 仅 reviewer 维度画像兜底（唯一索引冲突 catch 回退 UPDATE），外层 catch best-effort 记 warn。
- **ReviewService 抽检候选**：countRecheckCandidates / listRecheckCandidateIds（APPROVED + create_time>=since + NOT EXISTS 排除已抽检）/ recordRecheck（@Transactional 直插 review_recheck_log）。
- **ReviewerRecheckTask（helloai-job）**：`@Scheduled(fixedDelayString="${helloai.review.recheck-interval-ms:3600000}")` + Redis 锁（LOCK_KEY="scheduler:lock:ReviewerRecheck"，token + Lua 安全解锁，与 PlanningTimeoutTask 同构）；抽样批量 = ceil(候选×比例)，下限 1 上限 maxBatch；单条失败只记日志不中断。
- **单测**：SubTaskReviewServiceTest 增补双审 5 例（一致通过 / 分歧转人工 / 候选不足降级单审 / 未开启跳过 / ANY 任一通过）+ 抽检 3 例（一致 / 分歧 / 不可复审跳过）；AgentQualityProfileServiceTest 增补；ReviewerRecheckTaskTest 7 例（前置 3 + 抽样 4）。
- **验收脚本**：`scripts/powershell/verify-reviewer-dual.ps1`（S1 双审一致 / S2 分歧转人工 / S3 抽检计数）。

#### 3. 验证结果

- `mvn -pl helloai-job -am test -DskipTests=false`：helloai-job 模块 73/73 全绿（ReviewerRecheckTask 抽样执行 4 + 前置条件短路 3 在内）。
- core 聚焦测试（`-Dtest=SubTaskReviewServiceTest,AgentQualityProfileServiceTest,ReviewServiceTest`）：40/19/8 全绿。
- 排查并修复历史问题：job 模块部分 @Nested 测试类 0 run（AgentHealthCheckTaskTest 等，含本轮 ReviewerRecheckTaskTest）——根因是 target/test-classes 过期编译产物导致 JUnit Platform 发现 0 测试且不报错；触发 testCompile 全量重编译后全部恢复，与 Phase 4 代码本身无关。
- verify-reviewer-dual.ps1 真实环境实测（2026-08-22 晚，6565 实例 + deepseek 双模型 v4-flash/v4-pro）：
  - **S1 双审一致路径全绿（最终轮 PASS=8 FAIL=0 SKIP=0）**：sub_task_dual_review_consented consensus=APPROVED → 子任务 DONE → 恰好 1 条 review_record（reviewer1 归属）→ verdict senders=rd-reviewer-b + rd-reviewer-a → 两审 reviewer_reviewed_count 均 >=+1（动态断言，实测 11→12、1→2）。
  - **S3 抽检链路全绿（PASS=7 FAIL=0 SKIP=0）**：APPROVED 记录在候选中可见（count=22）→ 模拟一轮抽检落 review_recheck_log 后从候选窗口排除（NOT EXISTS 语义）→ log 行 shape=APPROVED|APPROVED|0|reviewer → review_recheck_log 6 核心列 + agent_quality_profile reviewer 维度 2 列就绪（V57/V54）。
  - **其余路径均有真实证据（多轮运行，修复后脚本 0 FAIL）**：分歧路径（manual_intervention reason=reviewer_disagreement + 双方 reviewed/disagreement 计数 +1，sub_task_reviewer_disagreement timeline）；REJECTED 共识路径（单 record 纪律断言 PASS）；incomplete 降级路径（LLM 失败停留 REVIEW，编排触发与 timeline 断言 PASS）。
- 实测暴露并修复脚本 3 处问题：① 画像断言原硬编码 rd-reviewer-a/b，但 pickDual 首位=AgentSelector.pickPreferred（质量分最高）人选不定，改为按 verdict senders 动态断言；② Get-DualReviewEvent 事件匹配原只覆盖 sub_task_dual_review_% 前缀，分歧事件 sub_task_reviewer_disagreement 匹配不到导致等待超时，已补入；③ 画像断言原为"恰好 +1"，实测发现自动抽检（ReviewerRecheckTask）可能在快照间隙给参与者额外 +1（实证 rd-reviewer-b 4→6=抽检+1+双审+1），放宽为">=+1"（双审 0 计数仍 FAIL）。
- 实测暴露环境/产品问题（未改产品代码）：rd-reviewer-a 原配 dashscope:qwen3.8-Max 调用恒 404 model_not_found（目录数据与 dashscope API 不一致）；改 qwen3.7-plus 后仍 404——ProviderChatModelCache.buildKey 不含 model 维度，ChatModel 按 (protocolType,provider,baseUrl,apiKey) 永久缓存，改 model_type 不生效直到重启（产品级缺陷，建议后续修复：buildKey 增加 model 维度 5 参重载 + 3 个 factory 传 model + 测试同步）；实测改用 deepseek 双模型（deepseek-v4-flash/pro）+ 为 rd-reviewer-a 复制 deepseek 托管凭据（同 provider 加密值可复用）后全链路跑通。

#### 4. 影响与遗留

- 影响：评审环节从单一判定升级为双审共识 + 定时抽检，reviewer 维度画像计数闭环（incrementReviewerStats），分歧/不一致可见于 timeline 与人工干预记录；抽检配置全部走 helloai.review.* 默认值，未显式声明。
- 遗留：本轮改动未 git 提交，待用户确认后提交；verify-reviewer-dual.ps1 实测已全绿（S1 PASS=8/S3 PASS=7，S2 分歧未复现轮按 SKIP 设计）；ProviderChatModelCache 缓存 key 不含 model 的产品缺陷待用户决策是否修复（涉及 core 代码 + 测试同步 + 重启 6565 验证）。

### 6.143 ProviderChatModelCache 缓存 key 补 model 维度（产品缺陷修复）（2026-08-22）

#### 1. 背景与结论

- **背景**：§6.142 实测暴露的产品级缺陷——`ProviderChatModelCache.buildKey` 仅含 (provider, baseUrl, apiKey, protocolType) 四元组，不含 model 维度；Agent 修改 model_type 后命中同一 key 复用旧 ChatModel 实例，新模型配置不生效直到重启（实测 rd-reviewer-a 由 qwen3.8-Max 改 qwen3.7-plus 后请求仍 404 qwen3.8-Max）。
- **结论**：用户确认修复。buildKey 升级五元组主签名（含 model），3 个 factory 调用点同步补 model 参数；model 变更自动落入新桶重建 ChatModel，无需重启。

#### 2. 实现要点

- **buildKey 5 参主签名**（`provider, apiKey, baseUrl, protocolType, model`）：model 段追加到 key 尾部，**按原样参与 key（不归一化大小写）**——模型名可能大小写敏感（如 MiniMax-Text-01），归一化会导致大小写不同的配置误共享同一实例；null/blank 归 `default` 桶（与显式字面量 `default` 同桶，model 段直接拼接非 hash）。
- **3 参 / 4 参旧签名保留为兼容委托**（model 归 default 桶），既有测试与潜在调用方不受破坏。
- **3 个 factory 调用点补 model**：`OpenAiCompatibleProtocolFactory` / `AnthropicCompatibleProtocolFactory`（buildKey 传调用方 model）/ `DeepSeekProviderChatClientFactory`（注释同步更新为五元组口径，注明 2026-08-22 修复）。
- **类注释更新**：缓存粒度改为五元组；失效策略注明「key 含 model 维度，Agent 修改 model_type 后自动落入新桶重建实例，无需重启」。
- **明确不做**：不做 LRU 容量上限、不做 model 变更主动驱逐旧实例（旧 key 无引用即 GC，与 clear() 语义一致）。

#### 3. 验证结果

- `mvn -pl helloai-core -am clean test -Dtest=ProviderChatModelCacheTest,OpenAiCompatibleProtocolFactoryTest,AnthropicCompatibleProtocolFactoryTest,LlmProviderChatClientFactoryRegistryTest`：**42/42 全绿**（ProviderChatModelCacheTest 15 含 3 个新增 model 用例：不同 model 不同 key / null-blank 与显式 default 同桶 / 大小写敏感；两个 factory 测试各含 `shouldIsolateByModel`：同四元组不同 model 各自建桶不共享实例，cache.size()=2 且各自 defaultOptions.model 正确）。
- 期间再次踩到 §6.142 记载的 target/test-classes 过期编译产物导致 @Nested 测试类 0 run 问题，`clean` 全量重编译后恢复（与本次代码无关，属已知环境坑）。
- 测试断言修正记录：首版断言「null model 与显式 default 字面量不同」与实际语义冲突（model 段直接拼接，null/blank 与字面量 default 同桶），已修正为同桶断言。

#### 4. 影响与遗留

- 影响：Agent 修改 model_type 后即时生效（新 key 自动建桶重建实例），无需重启后端；缓存日志可见 `key=...::model` 尾部 model 段；旧模型实例滞留内存直至无引用 GC，可接受。
- 遗留：真实环境需重启 6565 实例后验证模型切换即时生效（此前 qwen3.7-plus 场景可作回归素材）；verify-reviewer-dual.ps1 无需变更；本轮改动未 git 提交，待用户确认后提交。

### 6.144 verify-quality-profile.ps1 真实环境全绿：反馈回路 Phase 1/3 验收通过（2026-08-22）

#### 1. 背景与结论

- **背景**：verify-quality-profile.ps1（S1~S6 反馈回路 Phase 1 质量画像 + Phase 3 历史表现注入验收脚本）在 6565 实例上首轮真实环境运行连续暴露 4 类脚本侧问题——preset agent 注册失败连锁 SQL 报错、Invoke-Tool 参数绑定崩溃、lease 查询列名不存在、S6 prompt 多行断言错位；S3/S4/S6 三个场景先后 FAIL，无一场景一次通过。
- **结论**：全部修复后 **S1~S6 = PASS 42 / FAIL 0 / SKIP 0 ALL PASSED**；产品代码零改动，服务端功能（画像增量、指标语义、qualityRank 回灌、动态 TTL 复合分、rebuild 对账、历史表现摘要注入）经真实环境验收通过，反馈回路 Phase 1/3 实测闭环。

#### 2. 实现要点

- **前置修复（agent 注册失败连锁）**：脚本原写死 `modelType='gpt-4o'`（无冒号格式）注册三个 preset agent，服务端 `AgentSkillPolicyService.validateModelType` 要求 `providerCode:modelName` 冒号格式 → 注册全部失败（日志三次「modelType 格式错误」实锤）→ Ensure-TestAgent 返回空 → cleanSql 拼出 `agent_id IN (, )` psql 语法错误。修复：新增三个参数 `-ExecutorModelA/-ExecutorModelB/-ReviewerModel`（从 llm_provider_model enabled 目录选出 dashscope:qwen3.6-Flash / qwen3.7-plus、moonshot:kimi-k3，避开同角色同模型唯一冲突）+ Ensure-TestAgent 补 SQL fallback（强制 ACTIVE + model_type，对齐 verify-reviewer-dual.ps1）。
- **① Invoke-Tool 参数名 `$Args` → `$ToolArgs`**：`param([hashtable]$Args = @{})` 的参数名与 PS 自动变量 `$args`（未绑定参数数组）同名冲突——PS 5.1 参数绑定器把默认值覆盖为 Object[]，任何调用（含无参）都抛 `ConvertToFinalInvalidCastException`（Object[] → Hashtable）。先用独立 ps1 最小复现（错误 ID 与线上完全一致：F-Args 全部报错、F-ToolArgs 全部正常）再改 5 处调用点；顺带函数内 12 处 Write-Output 改 Write-Host 防返回流污染（同类隐患，对齐 verify-reviewer-dual.ps1 先例）。
- **② S4 lease 查询列名 `started_at/expires_at` → `start_time/expire_time`**：agent_duty_lease 实际列名是 `_time` 后缀（项目惯例），`column does not exist` 实锤；用 MCP information_schema 核对 review_record / conversation_message / agent_execution_record / task_timeline 全部列名，其余 SQL 无同类问题。
- **③ S6 prompt 多行截断 → SQL regexp_replace 单行化**：`Get-PsqlFields` 只取输出文件首行（Select-Object -First 1），psql -A 模式保留 text 字段内换行 → 只拿到「## 你的历史表现」首行，heading PASS / stats+remind FAIL 的错位特征实锤截断；查询 SQL 加 `regexp_replace(content, '[[:space:]]+', ' ', 'g')` 把整段 prompt 单行化（MCP 实测输出正确）。

#### 3. 验证结果

- **最终全绿：PASS=42 FAIL=0 SKIP=0 ALL PASSED**——S1 画像增量 7（reviewed/approved/first_reviewed/first_pass/total_score/rework/last_id 全对，row=1|1|1|1|5|0）；S2 指标语义 6（rework_round_sum=1、missing-unit-test=2 等）；S3 qualityRank 回灌 4（winner=exec-a，profile-rich 胜出）；S4 动态 TTL 4（exec-a=134min vs exec-b=122min，差 12≥8 期望）；S5 rebuild 对账 8（4|1|2|1|11|3 全对，total_score=11=5+3+2+1、rework=3=1+2）；S6 历史注入 4（prompt 含「你的历史表现」+ 统计行 + 提醒行 + historySummary=true）。
- **每轮修复均先复现/验证再改**：① $Args 冲突用独立 ps1 最小复现（错误 ID 与线上完全一致）→ 改名后同脚本 0 错误；② 列名用 MCP information_schema 实锤 + 真实 lease 数据模拟查询（134/122 与断言期望一致）；③ 单行化 SQL 用真实 PG 验证输出。每次修改后 Parser::ParseFile 语法自检 SYNTAX OK。

#### 4. 影响与遗留

- 影响：反馈回路 Phase 1（质量画像 + 调度回灌 + 动态 TTL 复合分 + rebuild 对账）与 Phase 3（历史表现摘要注入 executor prompt）真实环境验收通过；脚本侧沉淀 3 条 PS 5.1 陷阱教训（参数名不得与自动变量同名、`_time` 列名惯例、psql 多行字段截断需单行化）。
- 遗留：本轮改动（verify-quality-profile.ps1 + 差距表 N20 §6.144 增量）未 git 提交，待用户确认后提交；Phase 5 质量度量看板按《反馈回路与契约先行落地计划》推进。

### 6.145 Phase 5 前置合规：SubTaskReviewServiceImpl 组合拆分 + AdminQualityController 路径 ById 化（2026-08-23）

#### 1. 背景与结论

- **背景**：Phase 5 质量度量看板推进前的 CODE_STYLE 合规检查发现两个观察点：① `SubTaskReviewServiceImpl` 674 行 / 14 个构造器依赖，超出 §7.8 类规模红线（500 行 / 8 依赖）；② `AdminQualityController` 三个端点路径（`/rebuild/{agentId}`、`/dispatch/{subTaskId}`、`/spec-section/{taskId}`）不遵循 §8.2 接口路径命名（状态操作应 `POST /xxxById/{id}`、查询子资源应 `GET /findXxxByYyyId/{id}`）。
- **结论**：用户确认"组合拆分"方案——剥离核验执行（`ReviewExecutionEngine`，5 依赖）与抽检复审（`ReviewRecheckExecutor`，6 依赖）为两个独立组件，新组件均达标（≤8 依赖）；剩余编排（三入口 + 互斥锁 + 状态机 + 判定落地）在类 Javadoc 按 §7.8 选项二书面声明不继续拆分；路径全部改 ById 风格并与 3 个 verify 脚本同步。

#### 2. 实现要点

- **ReviewExecutionEngine（`review/support/`，新建）**：从 SubTaskReviewServiceImpl 迁出 `doReviewWith` + `renderPrompt`（渲染 Prompt → `executeSync` → 对话流双写 subtask_review_prompt/thinking/verdict → `verdictParser.parseVerdict`），5 依赖（PlatformAgentExecutionService / ConversationService / TaskTimelineService / VerdictParser / ReviewEvidenceAssembler）；`execute(subTask, reviewer)` 返回 `ReviewVerdict`，失败/不可解析返回 null 不改状态；单审/双审/抽检三方共用同一执行口径。
- **ReviewRecheckExecutor（`review/support/`，新建）**：迁出 `recheckReviewRecord`（一致性/放水/跳过三分支 + recordRecheck + incrementReviewerStats + timeline），6 依赖；入口 `ReviewerRecheckTask` 由注入 `SubTaskReviewService` 改为注入本类。
- **SubTaskReviewServiceImpl 收编**：删除 3 个方法（doReviewWith/renderPrompt/recheckReviewRecord）后由 674 行降至约 524 行；字段/构造器 `PlatformAgentExecutionService` → `ReviewExecutionEngine`；类 Javadoc 补 §7.8 拆分评审结论（已剥离清单 + 剩余职责 + 不拆理由：三入口共享同一把锁与状态机决策、判定落地与编排共享 verdict 流转）。
- **SubTaskReviewService 接口**：移除 `recheckReviewRecord` 抽象方法（抽检职责迁出），保留 L1/L2/L3 三入口 + parseVerdict + ReviewVerdict。
- **AdminQualityController 路径 ById 化（§8.2）**：`POST /rebuild/{agentId}` → `/rebuildById/{agentId}`；`POST /dispatch/{subTaskId}` → `/dispatchById/{subTaskId}`；`GET /spec-section/{taskId}` → `/findSpecSectionByTaskId/{taskId}`；类 Javadoc 同步。前端零引用（helloai-ui 已确认），仅 3 个 verify 脚本 11 处引用同步（verify-quality-profile.ps1 ×6、verify-contract-first.ps1 ×4、verify-admin-authz.ps1 ×1，含头部注释与断言字符串）。
- **测试迁移**：SubTaskReviewServiceTest 构造器改真实引擎注入（底层 mock 不变，37 例语义零改动）；recheck 3 例迁移至新建 ReviewRecheckExecutorTest（引擎真实组件 + 底层 mock）；ReviewerRecheckTaskTest mock 换 ReviewRecheckExecutor（7 例）。

#### 3. 验证结果

- 编译：`mvn -pl helloai-core,helloai-job,helloai-api -am test-compile` 通过（`-q` 无错误）。
- 单测：`-Dtest=SubTaskReviewServiceTest,ReviewRecheckExecutorTest,ReviewerRecheckTaskTest` 47/47 全绿（37/3/7，注意项目默认 skipTests=true 需 `-DskipTests=false`）。
- 真实环境回归（6565 实例 + docker 四件套）：verify-reviewer-dual.ps1 **PASS=12 FAIL=0 SKIP=2**（S1 双审触发/降级守卫 PASS，S2 分歧未复现 SKIP 属 LLM 环境相关，S3 抽检候选链路 7 项 PASS）；verify-quality-profile.ps1 **PASS=42 FAIL=0 SKIP=0**（S3-dispatch-http 与 S5-rebuild-http 均走改名路径 HTTP 200，质量画像/调度回灌/动态 TTL/rebuild 对账/历史注入全量通过）。

#### 4. 影响与遗留

- 影响：核验执行与抽检复审职责独立可测（引擎 5 依赖 / 抽检 6 依赖均达标）；编排类约 524 行仍略超 500 行红线，但已有 §7.8 书面声明覆盖（与 AgentServiceImpl 583 行 / 14 依赖先例同口径）；管理侧实测端点路径风格与 §8.2 对齐，前端与既有脚本无破坏（脚本已同步）。
- 遗留：本轮改动未 git 提交，待用户确认后提交；Phase 5 质量度量看板按《反馈回路与契约先行落地计划》推进。

### 6.146 Review 域迁移：审核产物归 review 域（2026-08-23）

#### 1. 背景与结论

- **背景**：§3.x 依赖方向红线（planner/review → task → agent → system → shared）下，review 域仅有 service 族，评审相关实体（ReviewRecord/ReviewRecheckLog）与审核服务（ReviewService）滞留 task 域，形成历史归属错位；task 域 5 处（SubTaskServiceImpl / TaskDeliverableServiceImpl / TaskServiceImpl / TaskIterationServiceImpl / ImplicitScoreCalculator）直接持有 ReviewRecord 实体，是本次迁移最大的隐藏面（不只 TaskIterationServiceImpl 一处）。
- **结论**：按 CODE_STYLE §3.x 修订（V1.14，"审核产物归审核域"）将 6 个文件同批迁入 review 域（ReviewService 与实体/Mapper 强绑定必须随迁）；task 域消费侧全部收口到 	ask.port.ReviewPort 端口（值对象 ReviewFact/ReviewSummary 防实体泄漏）；QualityProfileUpdater（agent 域）签名改原子参数去 ReviewRecord 依赖；ReviewResult 枚举实测在 common 域（所有域合法引用，无需迁移）。

#### 2. 实现要点

- **规范先行**：CODE_STYLE §3.x L292 修订为"review 域完整子包（entity/mapper/service/service.impl/mqconsumer/picker/support），评审相关实体与审核服务归 review 域"，同步更新"当前各域完整子包"清单与 @MapperScan 示例。
- **6 文件同批迁移（git mv）**：	ask.entity.ReviewRecord/ReviewRecheckLog → eview.entity；	ask.mapper.ReviewRecordMapper/ReviewRecheckLogMapper → eview.mapper；	ask.service.ReviewService → eview.service；	ask.service.impl.ReviewServiceImpl → eview.service.impl；package/import 全量适配（两 Mapper 为注解 SQL，无 XML namespace 同步项）。
- **端口三件套（task 域定义、review 域实现）**：	ask.port.ReviewPort（6 方法：isLatestReviewApproved / listReviewFactsBySubTaskId / latestReviewSummary / countByReviewerAgentId / countByTaskId / physicalDeleteByTaskId）+ 	ask.port.ReviewFact(score, approved) + 	ask.port.ReviewSummary(result, score, comment)；端口实现独立为 ReviewPortAdapter（review.service.impl，仅依赖同域 ReviewRecordMapper）——若挂在 ReviewServiceImpl 上会构成 SubTaskServiceImpl → ReviewPort 实现 → SubTaskService 构造器环（启动装配自检实测暴露）。
- **task 域 5 处实体依赖收口**：SubTaskServiceImpl（complete 迭代历史组装 / buildApprovedSummary 摘要）、TaskDeliverableServiceImpl（latestReviewSummary）、TaskServiceImpl（isLatestReviewApproved 等）、TaskIterationServiceImpl（ReviewRecord 引用改 ReviewPort）、ImplicitScoreCalculator（listReviewFactsBySubTaskId）全部改走 ReviewPort，零实体泄漏。
- **QualityProfileUpdater 签名**：onReviewRecordPersisted(Long executorAgentId, Long reviewRecordId, Integer round, ReviewResult result, Integer score, String issues) 原子参数化，两处调用方（ReviewServiceImpl.createReview / recordAutoReview）同步。
- **@MapperScan 登记** com.helloai.core.review.mapper（@MapperScan 不递归子包，漏登记启动才炸）。
- **测试适配 7 文件**：ReviewServiceTest 迁 review.service 包（补 SubTaskService/RewardService import）；QualityProfileUpdaterTest 14 处原子参数化；SubTaskServiceHandoverTest / IsReadyTest / QuotaTest / TaskDeliverableServiceTest / TaskServiceTest 的 ReviewRecordMapper mock 全部换 ReviewPort；ReviewRecheckExecutorTest import 路径。

#### 3. 验证结果

- 编译：mvn -pl helloai-core,helloai-job,helloai-api -am test-compile 通过。
- 单测：core 916/916 全绿 + job 7/7 全绿。
- 依赖方向：verify-dependency-direction.ps1 11 项全 PASS（含 @MapperScan 6 包断言）；review 相关 import 23 处全部指向 com.helloai.core.review.*，task/agent 旧路径零残留。
- 启动装配自检（硬性约束第 4 条）：helloai-start 实际启动成功；首次启动暴露 subTaskServiceImpl → reviewServiceImpl → subTaskServiceImpl 构造器环，由 ReviewPortAdapter 独立实现断环。

#### 4. 影响与遗留

- 影响：审核产物归 review 域，task 域不再持有审核持久层；task 消费侧全部经 ReviewPort 值对象，实体零泄漏；agent 域 QualityProfileUpdater 不再 import review 实体。
- 遗留：双审并行化（§6.146 续）已完成待提交，与迁移分两个 commit 提交。

### 6.146 续：双审并行化（reviewDualExecutor 线程池 + deadline 等待）（2026-08-23）

#### 1. 背景与结论

- **背景**：doDualReview 串行执行两次 LLM 核验（v1 完成后才发起 v2），单侧最坏耗时 120s 时双审整体最坏 240s，拉长核验互斥锁（TTL 120s）占用窗口，锁释放后残留核验线程仍在跑的边界紧张。
- **结论**：两路核验在专用线程池（reviewDualExecutor，2/4/20 + CallerRunsPolicy）上以 CompletableFuture.supplyAsync 并行发起，共用同一 deadline（helloai.review.dual-review-timeout-seconds，默认 120s）各以剩余时间等待；超时/中断/异常按不可判定走既有 sub_task_dual_review_incomplete 路径；future 不取消（LLM 调用已在途，取消无收益且会中断共享连接池），残留线程自然跑完由线程池回收。判定/落库/timeline 逻辑零改动。

#### 2. 实现要点

- **ReviewDualExecutorConfig（helloai-start/config，新建）**：ThreadPoolTaskExecutor core=2 / max=4 / queue=20，拒绝策略 CallerRunsPolicy（双审核验为编排内联调用，队列满由调用线程兜底直跑，不静默丢核验），@Bean("reviewDualExecutor")，与执行命令池/拆解池/门铃池相互隔离。
- **ReviewProperties**：新增 dualReviewTimeoutSeconds = 120（与互斥锁 TTL 对齐：超时边界 ≤ 锁 TTL，防锁释放后核验线程仍在跑）。
- **SubTaskReviewServiceImpl**：显式构造器 14→15 参（@Qualifier("reviewDualExecutor") Executor reviewDualExecutor）；doDualReview 两路 supplyAsync + 新增 waitVerdict(future, deadline) helper（remain = deadline - now，uture.get(remain, MILLISECONDS)，异常返回 null）。
- **测试（SubTaskReviewServiceTest）**：构造器同步（默认同步直跑 Executor 保证确定性，uildService(Executor) 参数化）；新增 2 用例：① 超时不可判定（timeout=0 → incomplete 观测，不落 record/不改状态/不记画像）；② 真实并行（2 线程池 + CountDownLatch 断言两路 executeSync 并发 in-flight=2，串行实现下最大并发只能到 1）。

#### 3. 验证结果

- 编译：mvn test-compile 通过；打包 + 实际启动 6565 实例成功（16.1s，无循环依赖）。
- 单测：SubTaskReviewServiceTest 39/39 全绿（37 既有 + 2 新增）；core 916/916 全绿。
- 依赖方向：verify-dependency-direction.ps1 11 项全 PASS。
- E2E：verify-reviewer-dual.ps1 S1 **PASS=3 FAIL=0 SKIP=1**（双审触发无降级，LLM 分歧属环境依赖）、S2 **PASS=2 FAIL=0 SKIP=1**（本轮 LLM 一致走 consented）、S3 **PASS=7 FAIL=0 SKIP=0**（抽检候选链路）——ALL PASSED。

#### 4. 影响与遗留

- 影响：双审最坏耗时由 2×单侧收敛为单侧 + 汇合等待，互斥锁占用窗口减半；超时口径统一（共用 deadline 剩余时间，非 2×timeout）。
- 遗留：双审并行化与 Review 域迁移分两个 commit 提交（本 commit 为并行）；PATH 下 java（Oracle javapath）已失效，启动需用 $env:JAVA_HOME\bin\java.exe（ms-17.0.19）。

### 6.147 Phase 5 质量度量看板 + P2 双审超时修正 / P3 规范修正（2026-08-23）

#### 1. 背景与结论

- **背景**：Phase 5 计划（.qoder/plans/HelloAI反馈回路落地计划.md Phase 5 + 5.1 设计稿 L163-198）要求交付质量度量看板：AdminQualityController 三端点（/overview、/agents?limit=、/dashboard?days=），聚合放 Service（§6.7 语义），Controller 零编排；用户核对计划后确认事实全部属实放行，并提出 2 个不阻塞问题：P2（dualReviewTimeoutSeconds=120 与核验锁 TTL 120 相等，deadline 加锁后才起算，双审窗口必然超出锁有效期，L1/L2/L3 触发源可抢到已过期锁重复双审→重复 review_record→画像重复计数+可能重复返工）与 P3（CODE_STYLE L331 端口反转示例把 ReviewPort 实现方写成 ReviewServiceImpl，实际是独立 ReviewPortAdapter）。
- **结论**：Phase 5 全链路交付（review/agent 两域统计 + 聚合服务 + 3 端点 + 前端 5 图看板 + 单测增补 + ps1 真实环境 6/6 全绿）；P2 一行修复默认 120→90s + 3 处注释同步；P3 在 CODE_STYLE V1.15 顺手修正。

#### 2. 实现要点

- **review 域统计**：ReviewRecordMapper 4 投影 SQL（selectTrendSource / selectIssuesForStats / selectReworkDistribution / selectReviewerLeniency），窗口 `create_time >= now() - #{days} * interval '1 day'` + deleted=0；投影返回具体 DTO record（防 §6.132 Map→JacksonTypeHandler 劫持复发）；review/dto 新增 4 record（QualityTrendPoint / DefectDistribution / ReworkRoundPoint / ReviewerLeniency，SQL 侧 reviewerName 空串占位 + Java 补名重建）；ReviewService 4 方法（statsTrendSource / statsDefectDistribution / statsReworkDistribution / statsReviewerLeniency），缺陷标签聚合复用 DefectLabelParser 同口径（与画像表对账一致），补名走 AgentService.listByIds 缺失回退 ID 字符串，days<=0 归一 30。
- **agent 域统计**：AgentQualityProfileMapper 2 投影 SQL（selectOverviewRow COALESCE 兜底单行必返 / selectRankingRows 动态 LIMIT，agentName 空串占位 + qualityScore=0 占位）；agent/quality/dto 新增 2 record（QualityOverview / AgentQualityRank）；AgentQualityProfileService 2 方法（statsOverview / statsAgentRankings），qualityScore 逐行调 computeQualityScore（口径唯一防 SQL 漂移）；新增 AgentService 注入（reviewer 补名无环——注入点交叉核验无交集 + SubTaskServiceImpl 对 AgentService 走 ObjectProvider 双保险）。
- **聚合与端点**：QualityDashboardService（review 域新建接口 + Impl，依赖 AgentQualityProfileService + ReviewService 无环）+ QualityDashboardResponse（review/dto，overview + trends/defectDistributions/reworkRounds/reviewers 四数组，rankings 单独 /agents 端点）；AdminQualityController 3 薄透传端点（admin.quality.enabled 门控复用，关闭返回业务码 403）。
- **前端**：paths.ts admin 段 + 3 路径；api/quality.ts + types/quality.ts（6 接口）；QualityDashboard.vue（543 行，5 图：趋势双线 / 排行横向 bar / 驳回原因 / 返工轮次 / 放水率，windowDays 7/30/90，echarts init 模式对齐 Dashboard.vue：cssVar 取色 + dispose 重建 + theme watch + el-empty 空态；initChart option 参数用 any——echarts 类型声明对 bar.borderRadius 校验过严，vue-tsc 0 错为硬门槛）；MainLayout 菜单 + router 路由。
- **P2 修复（4 文件）**：ReviewProperties.dualReviewTimeoutSeconds 默认 120→90 + 注释重写（deadline 加锁后才起算，90s 保证双审窗口（等待 + 落库 + 时间线）收进锁 TTL 120s 内）；ReviewDualExecutorConfig / SubTaskReviewServiceImpl / SubTaskReviewServiceTest 注释同步（测试 mock 120L 保留）；yml 无显式覆盖（grep 零匹配，纯默认值生效）。
- **ps1（verify-quality-dashboard.ps1，262 行）**：S1 门控关闭断言业务码 403 / S2 开闸 / S3 overview 字段（firstPassRate 0-100 边界）/ S4 /agents?limit=5 数组 ≤5 + 字段 / S5 dashboard 四数组 + overview 嵌套 / S6 days=0/-7 仍 200（服务端默认 30）；规则 6 合规（UTF-8 头 + 单引号拼接 + 全 ASCII 运行时字面量 + ParseFile 0 错）；真实环境修复：getByKey 返回 data 为 Map{key:value} 而非 {value:...}，S1 解析改取首个属性值。

#### 3. 验证结果

- 单测：helloai-core 922/922 全绿（ReviewServiceTest +4、AgentQualityProfileServiceTest +2，构造器参数化 @Mock AgentService）+ helloai-job 66/66 无回归；期间修复 shouldAggregateDefectDistribution 测试数据格式——DefectLabelParser 正则 `\[defect]\s*([^\[]+)` 要求 defect 后直接 `]`，真实格式为 `[defect] 描述 [location] ...` 四元组。
- 依赖方向：verify-dependency-direction.ps1 全 PASS（含 @MapperScan 6 包登记断言）。
- 前端：vue-tsc --noEmit 0 错（修复 4 个 TS2322：initChart option 改 any 对齐 Dashboard.vue 先例）。
- 装配自检：mvn -pl helloai-start -am -DskipTests package BUILD SUCCESS → java -jar（$env:JAVA_HOME\bin\java.exe，javapath stub 已失效）启动 6565 成功，Started 无异常；verify-quality-dashboard.ps1 真实环境 S1~S6 = PASS 6 / FAIL 0 / SKIP 0（S4 排行 3 条补名正常：qp-exec-a / TeleAgent-executor / rd-exec；S5 reviewers 含已删 agent 回退 ID 字符串符合预期）。

#### 4. 影响与遗留

- 影响：质量看板三端点可观测（overview / 排行 / 30 天窗口聚合），缺陷标签与画像表同口径；P2 竞态窗口关闭（双审窗口 90s < 锁 TTL 120s）。
- 遗留：CODE_STYLE V1.15 已回填（§3.x review 域 dto 子包 + 看板聚合归属 + P3 修正）；差距表 N20 状态补 08-23；verify-quality-dashboard.ps1 依赖 docker 容器 + 6565 运行实例。
- **实测修复（浏览器复验，2026-08-23）**：前端看板首载报「加载质量看板失败」——design-system.css 未定义 `--ha-success-light`（仅 primary 系有 -light 令牌），`initReworkChart` 渐变第二个 stop 取到空串，ECharts CanvasGradient.addColorStop('') 抛 SyntaxError，被 loadDashboard catch 后整页错误态；修复：亮色 `rgba(16,185,129,0.10)` / 暗色 `rgba(16,185,129,0.14)` 补令牌；浏览器复验：5 图全部渲染 + 统计卡片 + 7/30/90 窗口切换 + 明暗主题切换均正常，Console 0 报错。

### 6.148 CODE_STYLE 合规审计修复（2026-08-23）

#### 1. 背景与结论

- **背景**：对照 CODE_STYLE V1.15 全量合规审计发现 4 类违规：① §8.2 描述性驼峰路径违规 11 处（AdminLlmProviderController 8 + AdminProviderConfigController 2 + AdminQualityController 1）；② §7.8 类规模红线超线 5 个 ServiceImpl（SubTaskServiceImpl 879 行 / McpToolServiceImpl 807 / SubTaskExecutionServiceImpl 597 / RequirementClarifyServiceImpl 675 / AgentServiceImpl 583）；③ `ImplicitScoreCalculator` 缺类 Javadoc（§1.x）+ `LlmProviderModelQueryService` 3 个方法 Optional 作返回值（§7.6 禁止）；④ `verify-code-style-p1-ui-sync.ps1` 字面量匹配在 paths.ts 单一事实源收口后 100% 失效（提取器还把 `'ACTIVE'`、`responseType: 'blob'` 等当路径误报）。
- **结论**：按用户「1-4 依次修复」全部落地：路径全部 ById/ByProvider 化并同步调用方；5 个超线类按 §7.8 选项二书面声明不拆分（对齐 SubTaskReviewServiceImpl 先例）；Optional 全部收口 + Javadoc 补齐；ui-sync 脚本升级为 paths.ts 常量双通道匹配。

#### 2. 实现要点

- **① §8.2 路径整改（11 处 + 调用方同步）**：`AdminLlmProviderController` 8 处（saveApiKeyById/{id} / listModelsByProviderId/{id} / addModelByProviderId/{id} / saveAllModelsByProviderId/{id} / deleteModelByProviderIdAndName/{providerId}/{modelName} / toggleModelByProviderIdAndName/... / setDefaultModelByProviderIdAndName/... / getSkillOptionsByModelType/{modelType}）；`AdminProviderConfigController` 2 处（saveApiKeyByProvider/{provider} / saveSettingsByProvider/{provider}）；`AdminQualityController` 1 处（findSpecSectionByTaskId/{taskId}）。调用方同步：paths.ts 11 行 + verify-llm-provider-models.ps1 20 处 + verify-admin-authz.ps1 2 处 + verify-platform-config.ps1 2 处；全仓库旧路径 grep 0 残留。
- **② §7.8 书面声明（5 个超线类）**：每个类 Javadoc 补「§7.8 类规模拆分评审结论（2026-08-23）」三节（已剥离清单 + 剩余职责 + 不拆理由），格式对齐 SubTaskReviewServiceImpl 先例（如 SubTaskServiceImpl 已剥离状态机/审查/执行/评分/结果处理器 5 组件，剩余状态流转 + 事件发布 + 评分联动，不拆理由=状态机与事件序共享）；`SubTaskExecutionServiceImpl` 顺带修正类 Javadoc 错位（原挂在字段区）。
- **③ §7.6 Optional 收口 + Javadoc**：`LlmProviderModelQueryService` 3 方法（findDefaultByProviderId / findDefaultModelNameByProviderCode / findCapabilityByModelType，审计点名 1 处 + 同接口同类 2 处一并收口）Optional→null 语义，同步 Impl / AgentSkillPolicyService / AdminLlmProviderController 及 2 个测试（AgentServiceTest 5 处 mock、LlmProviderModelQueryServiceImplTest 7 处断言与 DisplayName）；`ImplicitScoreCalculator` 补类 Javadoc（五维因子 + 调用链 + 评分失败不阻断 DONE 语义）。
- **④ ui-sync 脚本双通道升级**：通道 A = paths.ts 119 个路径字面量 ↔ 后端 163 个路径（方法无关匹配，`${...}` 模板统一归一 {id}，豁免后端未实现的 /rules/updateById/{id}、/rules/deleteById/{id}）；通道 B = api 文件 125 个 request 调用必须引用 paths.&lt;block&gt;.&lt;key&gt;（§19.0 防内联路径回归，支持泛型跨行 `request.post&lt;T,R&gt;(\n` 形式）；规则 6 合规（UTF-8 头 + 单引号拼接 + 全 ASCII 运行时字面量）。

#### 3. 验证结果

- P1 paths 脚本：146 端点 0 违规 ALL PASSED。
- 新 ui-sync：双通道 ALL SYNCED（通道 A 119 字面量 0 未匹配、通道 B 125 调用 0 违规）。
- 编译：`mvn -pl helloai-core,helloai-api -am test-compile` BUILD SUCCESS（覆盖 ②③ 全部改动文件）。

#### 4. 影响与遗留

- 影响：§8.2 路径全量对齐；5 个超线类具备 §7.8 书面声明；§7.6 Optional 返回值清零；ui-sync 防回归能力恢复（可捕获内联路径 / 死键 / 方法不匹配）。
- 遗留：审计观察项（非违规项）按用户确认不处理；本轮未 git 提交。

### 6.149 设置页 UX 收口：polish / layout / typeset 三连（2026-08-23）

#### 1. 背景与结论

- **背景**：`$impeccable critique` 对设置页（Settings.vue）评分 20/40，快照（.impeccable/critique/2026-08-23T12-55-42Z__helloai-ui-src-views-settings-vue.md）列 2 项 P1 + 3 项 P2。本轮按快照收口三组：P1 对比度与语义统一（polish）、sticky 保存条 + 侧边栏入口（layout）、字号收敛与分区标题层级（typeset）。
- **结论**：三组全部落地并浏览器实测通过（含交互脏状态往返与现场恢复）；过程中额外发现并修复 1 个设计系统级既有债务（EP 双类选择器压掉 --ha-* tag 语义色）与 1 个 sticky 滚动上下文陷阱。

#### 2. 实现要点

- **polish**：`.form-hint` 与 `.provider-item-code` 由 `--ha-muted`（3.0:1 不达标）改 `--ha-ink-secondary`（亮 #4A5568 / 暗 #A9B4C7，均 ≥4.5:1），hint 字号 12→13px；博查 Key 未配置 tag 由灰色 info 改橙色 warning「未配置 · 联网搜索不可用」，与 Provider Key 未配置语义统一（缺凭证=功能不可用=需要行动）。
- **layout**：保存条移出表单为 `.page` 直接子级（EP 卡片 `overflow:hidden` + body `overflow:auto` 会截断卡片内 sticky 的滚动上下文，首轮放卡片内实测不吸附），`position: sticky; bottom: 0` 全程吸底；左侧新增「有未保存更改 / 全部已保存」状态（加载快照 vs 表单值 computed，保存成功后同步快照，博查掩码防呆逻辑不变），保存按钮补 `:loading` 态；MainLayout 侧边栏底部新增「系统设置」一级菜单项（`v-if="isAdmin"`，Tools 图标，激活态走既有 `.is-active`），头像下拉保留作冗余入口。
- **typeset**：四处分区标题由裸 `el-divider` 嵌文字改 `.section-heading`（16px/600 标题 + 底部细线 + 28px 上间距，消除文字紧贴分隔线），「添加供应商」按钮随标题右对齐；设置页自定义字号收敛至 12/13/14/16（18px detail-title 降为 16）；4 处 3px/6px 圆角归一到 `--ha-radius-sm/md` 刻度（检测器 advisory 清零）。
- **设计系统债务修复（detect.js 实测暴露）**：EP 自身用 `.el-tag.el-tag--X` 双类选择器（特异性 0-2-0）设类型色，design-system.css 原单类覆盖被压掉——「已启用」success tag 实际一直是 EP 默认绿 #67C23A/#F0F9EB（2.1:1）。修复：tag 四型覆盖全部升双类选择器（补 `--error` 别名），新增 `el-message--success/warning/error` 三型 toast 语义色覆盖（同一低对比根因）。

#### 3. 验证结果

- 类型检查：`vue-tsc --noEmit` 0 错；检测器 `detect.mjs` Settings.vue 0 命中（改前 4 条 advisory）。
- 浏览器实测（5173 + 6565 真实环境）：侧边栏入口激活态正常；保存条顶部/中部/底部三次读数均吸底可视（top 恒 < 视口高）；切开关→橙色「有未保存更改」→保存→「全部已保存」往返正常；「已启用」tag computed color = rgb(4,120,87)、博查标签 = rgb(180,83,9)，均为 --ha-* 语义色；「保存成功」toast 文字同语义色；亮暗双主题样式均正常；测试开关现场已恢复（admin.quality.enabled=true 前后一致）。
- 遗留读数说明：页内检测仍有 4 条来自 app shell / EP 默认动效的发现（网格背景、紫色光晕为登录页/侧边栏刻意为之；11 档字号主因 EP 组件内置尺寸），非设置页本身债务。

#### 4. 影响与遗留

- 影响：设置页从「能用的表单」向「可信的控制台」收口——对比度达标、状态语义统一、保存操作常驻可视、管理员一级入口可达。
- 遗留：快照中未认领项——键盘快捷键 / 分区就近保存、通知区单一禁用复选框噪声、内置 Provider 禁用复选框改只读标签、详情表 7px 内边距、保存失败透传后端 msg、质量看板门控关闭时的页内引导；本轮未 git 提交。

---

### 6.150 设置页 critique 复评（22/40）与 harden/clarify Top 3 收口（2026-08-23）

#### 1. 背景与结论

- 三连收口后复评（dual-agent + 父级裁决轮）：总分 20 → 22/40。上轮成果全部经受住实测（吸底保存条、语义色、侧边栏入口）；裁决修正两条误报（暗色画布错位系过渡中途读数、亮色琥珀标签实测 #B45309 达标）。
- 复评 P0「el-switch 视觉与逻辑背离」经专项诊断**裁定为测量假象**：Qoder 内置浏览器 visibilityState 恒为 hidden，document.timeline 冻结在 0，开关翻转时 300ms CSS transition 被创建但永不推进、停在第 0 帧（OFF 外观），且 CSS transition origin 优先级凌驾作者规则（含 !important）——这同时解释了此前截图超时与焦点环"读灰"伪影。项目 CSS 级联本身正确，真实浏览器可见紫色 ON 态。
- 按用户选择执行 Top 3：P0 兜底（开关文字态 + 开启确认）、键盘可达性、文案泄露。

#### 2. 实现要点

- Settings.vue：质量分区标签「质量实测端点」→「质量门控」，提示去掉配置键 `admin.quality.enabled` 与裸路径，改为「质量看板」router-link 跳转；开关加 `:before-change` 二次确认（仅开启方向，before-change 返回 false 不污染脏状态）；开关旁新增「已开启/已关闭」文字态（第二状态通道，不依赖动画/颜色）。
- Settings.vue：供应商列表改 `role="listbox"` + option（tabindex=0 / aria-selected），Enter/空格选中、↑↓ 焦点漫游；`.provider-item:focus-visible` 2px 主色 outline。
- design-system.css：`.el-input__wrapper` 焦点环补 `:focus-within` 选择器，键盘聚焦路径不再依赖 EP is-focus 时机。

#### 3. 验证结果

- `vue-tsc --noEmit` 0 错。
- 浏览器回归全部通过：确认对话框开启/取消/往返行为正确且取消不产生脏状态；键盘漫游后详情区随 Enter 切换；焦点环禁过渡实测 2px 紫；亮暗双主题文字态可读性正常。
- 脏检测语义确认：往返复归后端值即判「全部已保存」，为取值比对的正确行为（非粘滞脏）。
- 现场红线保持：全程未点保存，后端 `admin.quality.enabled` 实测仍为 "true"。

#### 4. 影响与遗留

- 影响：设置页状态展示从"仅动画视觉"升级为"动画 + 文字态"双通道；核心交互（供应商切换）对键盘用户可达；文案不再泄露实现细节。
- 遗留：快照 P2 项（分区就近保存、信息层级平塌、装饰性禁用复选框、默认模型三重冗余）；ElMessageBox 离场过渡在 hidden 标签页环境卡住（环境特性，真实浏览器无影响）；本轮未 git 提交。

### 6.151 质量看板默认开放 + 前端 403 不再误登出（2026-08-24）

#### 1. 背景与结论

- **背景**：质量看板（AdminQualityController）默认关闭（`admin.quality.enabled` 无任何初始化，V1 种子仅 3 个键），但前端菜单无条件可见；用户首次点击 → 后端返回 HTTP 200 + 业务码 403 → 前端 request.ts 把 `res.code === 403` 与 401 同等对待触发 `auth.logout()` → 直接退出登录，误判为系统 Bug。
- **根因**：三层叠加——① 配置键缺省即关（`getValue` 返回 null → `isEnabled()` = false）；② 前端 403 语义误用（403 是"已认证但无权限/功能未开启"，401 才是未认证应登出）；③ 菜单未按开关状态隐藏（隐藏方案改动面大，默认开放后无需隐藏）。
- **结论**：按评审建议走"默认开放 + 初始化"路线——门控语义反转为"缺省/空值视为开放，仅显式 false 关闭"，Flyway V58 落库默认值 true；前端 403 只提示不登出。安全面无损：全部端点已在 `/api/admin/**` 下受 AdminOnlyInterceptor 强制 admin 鉴权，门控仅是可关闭功能开关而非安全边界。

#### 2. 实现要点

- **AdminQualityController.isEnabled 语义反转**：`"true".equalsIgnoreCase(value)` → `!"false".equalsIgnoreCase(value)`（缺省/空/任意非 false → 开放，仅显式 "false" → 关闭），gateDenied 保留；类注释/常量注释同步（§6.151 引用）。
- **Flyway V58__seed_admin_quality_enabled.sql**：`INSERT sys_config (id=1000000000000000004, 'admin.quality.enabled', 'true') ON CONFLICT (config_key) WHERE deleted = 0 DO NOTHING`——幂等（partial unique index idx_sys_config_key 为冲突目标，**必须带 WHERE 谓词**，裸列名推断会报 42P10），不覆盖用户手动创建/历史遗留值；新老部署开箱即用。
- **V58 首版启动失败修正**：首版 `ON CONFLICT (config_key) DO NOTHING` 在 PostgreSQL 16 报 `42P10 no unique or exclusion constraint matching the ON CONFLICT specification`——partial unique index 无法通过裸列名推断为冲突目标；已修正为 `ON CONFLICT (config_key) WHERE deleted = 0 DO NOTHING`（与 V2/V13/V21 既有 partial index 写法一致），失败迁移已回滚、schema 版本停在 57，修复后重启自动重试。
- **前端 request.ts**：403 从登出分支拆出——401 仍 `auth.logout()`，403 改为 `ElMessage.error` 只提示（"无权限或功能未开启"）；修复面覆盖质量门控 / AuthController 改密非 admin / AgentController 自注册关闭等全部业务码 403 场景（HTTP 403 走 error 分支本就不登出，不受影响）。
- **前端文案同步**：Settings.vue 门控 hint「默认开启；关闭后…不可用（§6.151 起默认开放）」+ 开启确认弹窗文案（开启=恢复默认）；quality.ts 头注释更新（403 提示不登出）。
- **验证脚本注释同步**：verify-quality-dashboard.ps1 S1 与 verify-quality-profile.ps1 / verify-contract-first.ps1 A1.5 的"生产默认关闭"口径更新为"默认开放，仅显式 false 关闭"；脚本逻辑兼容（S1 读 gateValue=true 时自动 skip，显式关闭时仍断言 403）。

#### 3. 验证结果

- `vue-tsc --noEmit` 0 错（request.ts 逻辑拆分 + Settings.vue 文案）。
- 3 个改动脚本 `[Parser]::ParseFile` 静态自检 0 错误（规则 6）。
- 后端仅一行语义反转 + 注释 + 新增 SQL，无行为面扩大；Node fallback 下不跑 mvn（已知 JVM 崩溃陷阱），编译验证待用户环境确认。
- 兼容性：设置页开关加载（`config['admin.quality.enabled'] === 'true'`）与保存（true/false 双向）不受影响；Flyway 幂等不覆盖既有值。

#### 4. 影响与遗留

- 影响：质量看板开箱即用，首次点击不再登出；业务码 403 全局不再误登出（语义对齐 401/403 边界）；显式关闭能力保留（设置页开关 + 脚本 S1 断言仍有效）。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.152 设置页保存区形态调整：吸底悬浮条融入表单内部 + 保存成功 toast 化（2026-08-24）

#### 1. 背景与结论

- **背景**：设置页保存条为 §6.149/6.150 critique 收口引入的 sticky 吸底悬浮条（卡片外、跟随 .app-content 滚动吸底），用户反馈"单独悬浮"视觉干扰；经用户澄清，目标形态不是"独立条块"，而是**融入表单内部**——保存按钮作为表单的收尾行存在，而非独立在外的区域。同时用户要求"全部已保存"不再常驻文字提示，改为**保存成功后的自动消失 toast**。
- **结论**：保存区移入 `el-form` 内部（表单最后一个元素），去掉独立卡片外观（背景/边框/圆角/阴影），仅保留细分隔线与表单内容区分；"全部已保存"常驻文字移除——保存成功反馈复用 `handleSave` 已有的 `ElMessage.success('保存成功')`（toast 自动消失），仅保留"有未保存更改"防遗漏提醒（dirty 态才显示）。

#### 2. 实现要点

- **HTML**：`.save-bar` div 从卡片外移入 `</el-form>` 之前，作为表单收尾行；状态 span 改为 `v-if="isDirty"` 单态（class 固定 `save-bar-status dirty`，去三元 `dirty/clean` 双态绑定），clean 态不再渲染任何文字。
- **CSS .save-bar**：删除 `position: sticky / bottom / z-index`、`background / border / border-radius / box-shadow / max-width` 等独立块外观；`justify-content` 由 `space-between` 改为 `flex-end`（clean 态无状态文字时 space-between 失效导致按钮落左，与页内其他右对齐按钮不一致），`.save-bar-status` 补 `margin-right: auto`（dirty 态状态文字撑左侧、按钮仍贴右）；新增 `margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--ha-border-light)`——宽度继承 `.settings-form`（920px），与表单内容自然对齐；`.save-bar-status` 基础样式保留（dirty 态仍使用）。
- 保存成功 toast（`ElMessage.success('保存成功')`）为既有逻辑，未改动；isDirty 脏检测、保存流程、`saving` loading 均不变。
- 不做的项：分区就近保存（此前 P2 快照项）改动面大，本次仅形态调整，保持全局保存语义。

#### 3. 验证结果

- `vue-tsc --noEmit` 0 错（纯 HTML/CSS 改动，无 JS 逻辑变化）。
- 行为不变：isDirty 脏检测、保存流程、状态文字逻辑均未触碰；保存成功 toast 自动消失为 Element Plus 默认行为。

#### 4. 影响与遗留

- 影响：保存区不再是独立悬浮/独立条块，视觉上成为表单的一部分（分隔线收尾行）；保存成功反馈为瞬时 toast，无常驻"全部已保存"文字；与 §6.149/6.150 的"吸底"验收结论相悖系用户主动偏好变更，记录在案。
- 遗留：本轮改动未 git 提交，待用户确认后提交。

### 6.153 添加模型两步式弹窗 + 计费类型字段 + API Key 连通性验证（2026-08-24）

#### 1. 背景与结论

- **背景**：系统设置页“添加供应商”为单表单直填（code/名称/协议/Base URL/Key），与产品期望的两步式“添加模型”体验不符（第一步供应商双列网格选择、第二步表单填类型与密钥）；且缺少“类型”字段（按量付费 / Token Plan / Coding Plan）与 API Key 有效性验证（保存后无从得知 Key 是否可用，博查 Key 同样缺验证）。
- **结论**：① Flyway V59 为 llm_provider 增加 billing_type 列（默认 API_KEY，TOKEN_PLAN / CODING_PLAN 预留，应用层仅放行 API_KEY）；② 前端新增两步式弹窗（ProviderPickerDialog 双列网格 monogram 徽标卡片 + AddModelFormDialog 供应商下拉/类型下拉带置灰预留项/密钥输入与“获取 API 密钥”绿链），预置供应商“添加”语义 = 覆盖写既有内置行 Key，目录外选“自定义供应商”才新建；③ 新增 API Key 连通性验证：`POST /api/admin/llm-providers/verifyApiKeyById/{id}`（raw HTTP 最小请求 max_tokens=1 直探 Provider 端点，不走 ChatClient 工厂）与 `POST /api/admin/config/verifyWebSearchApiKey`（博查最小搜索 query=ping/count=1），保存 Key 后自动验证；④ Agent 编辑弹窗为内部 LLM Agent 只读展示所选模型。
- 全部改动遵循 CODE_STYLE：验证服务落 agent 域（依赖方向红线，需 PlatformProviderConfigService）、Service 接口+impl 拆分、描述性驼峰路径（§8.2）、V59 幂等迁移 + RAISE NOTICE 验证块（§9.4）、paths.ts 单一事实源（§19.0）、测试命名方法名_场景_预期（§21）、脚本 UTF-8 头 + 单引号拼接（规则 6）。

#### 2. 实现要点

- **后端 billingType 全链路**：V59 迁移（ADD COLUMN IF NOT EXISTS billing_type VARCHAR(32) NOT NULL DEFAULT 'API_KEY'）；LlmProvider 实体 + Create/Update DTO（@Pattern 仅放行 API_KEY）+ LlmProviderResponse；LlmProviderServiceImpl 增 validateBillingType + create 空值兜底 API_KEY；AdminLlmProviderController create/update/toResponse 映射（历史无值兜底 API_KEY，与 V59 DEFAULT 口径一致）。
- **后端 Key 验证**：新服务 `LlmProviderKeyVerifyService`（agent 域，接口+impl 拆分）——OPENAI_COMPATIBLE 拼 {baseUrl}/v1/chat/completions（已含 /v1 不重拼）、ANTHROPIC_COMPATIBLE 拼 /v1/messages + x-api-key + anthropic-version；连接 5s / 请求 20s；模型取 default_model > 内置供应商兜底表；失败收敛为 success/message/model/elapsedMs，绝不抛异常。博查侧：WebSearchService 新增 default `verifyApiKey()`（默认不支持），BochaWebSearchServiceImpl 覆写（校验 HTTP 2xx + body code=200），WebSearchServiceRouter 委托转发且不受总开关短路；AdminConfigController 新增验证端点。
- **前端两步式弹窗**：`src/views/settings/providerCatalog.ts`（deepseek/moonshot/minimax/dashscope 品牌目录：monogram 徽标色 + 缺省端点 + 官方 Key 获取页）；ProviderPickerDialog（双列网格卡片按钮，role=listbox 可键盘操作）；AddModelFormDialog（供应商下拉带“已添加”标记、类型下拉置灰预留项、密钥框 + 绿色获取链接、已配置覆盖告警、保存后自动验证并内嵌结果面板与“重新验证”）。
- **前端接线**：Settings.vue “添加供应商”按钮改“添加模型”接入两步弹窗；详情区增“验证 Key”按钮与“计费类型”描述项；配置 Key 弹窗保存后自动验证；博查 Key 非空保存后自动验证；AgentEditDialog 对 accessType=API_KEY_LLM 只读展示 modelType（外部 Agent 不展示）。paths.ts 新增 llmProviderVerifyApiKey / verifyWebSearchApiKey 两键（无内联路径）。
- **测试与脚本**：新增 LlmProviderKeyVerifyServiceTest（4 个前置守卫用例）+ LlmProviderServiceTest 补 3 个 billingType 用例（兜底/拒绝/局部更新）；新增冒烟脚本 `scripts/powershell/verify-api-key-verify.ps1`（S0-S7：billingType 回显/假 Key 验证返回结构良好/TOKEN_PLAN 400/博查验证/清理，幂等可重跑）。

#### 3. 验证结果

- 后端：`mvn -DskipTests compile` 全模块通过；`mvn -pl helloai-core -am test -Dtest=LlmProviderServiceTest,LlmProviderKeyVerifyServiceTest` BUILD SUCCESS。
- 前端：`npm run lint:check` 仅剩 1 个存量 error（QualityDashboard.vue 151:3 no-extra-semi，`;[...]` ASI 守卫，非本轮引入）；`npm run build`（含 vue-tsc）通过。
- 冒烟脚本需后端运行态执行（`verify-api-key-verify.ps1`），本轮未启动后端未实跑。

#### 4. 影响与遗留

- 影响：“添加模型”交互对齐参考截图（两步式 + 类型字段默认按量付费）；Key 保存即验证（LLM 与博查），无效密钥即时暴露；Agent 编辑信息可见所选模型。
- 遗留：TOKEN_PLAN / CODING_PLAN 仅预留置灰；外部 AI Agent 模型不在管理范围（用户明确不管）；冒烟脚本未实跑；本轮改动未 git 提交，待用户确认后提交。

### 6.154 执行对话流链路来源区分：单审/双审/抽检消息类型三分 + 双审共识摘要 + 抽检结论消息（2026-08-25）

#### 1. 背景与结论

- **背景**：§6.142 Phase 4 落地双审共识 + 抽检复审后，单审/双审/抽检三方复用同一 ReviewExecutionEngine 执行口径，对话流消息统一落 `subtask_review_prompt/thinking/verdict`，执行对话流无法分辨三条链路；且抽检结论只落 review_recheck_log + timeline，对话流完全不可见。Dev 库实证（任务「AI 核心概念内部培训方案文档」子任务）：抽检 discrepancy 后用户只能从 timeline 事件间接判断，误读为「双审一过一不过最终通过」，引发对综合评判机制的疑问。
- **结论**：新增 ReviewChannel 链路枚举（SINGLE/DUAL/RECHECK），核验对话流消息类型随链路三分（`subtask_review_*` / `subtask_dual_review_*` / `subtask_recheck_*`）；双审共识落定消息附加「双审共识」摘要（两位评审观点 + 共识策略）；抽检补一条 `subtask_recheck_result` 结论消息（显式声明只度量不改状态）；前端标签/时序图/timeline 字典同步，核验轮次分组识别前缀扩展。

#### 2. 实现要点

- **ReviewChannel 枚举（review/support 包新增）**：SINGLE/DUAL/RECHECK 三值，`toolName(kind)` 按链路返回 `subtask_review_{kind}` / `subtask_dual_review_{kind}` / `subtask_recheck_{kind}`，作为对话流消息类型与前端标签的单一事实源。
- **ReviewExecutionEngine**：新增 3 参 `execute(subTask, reviewer, channel)`（2 参默认委托 SINGLE 保留），prompt/thinking/verdict 双写消息类型改 `channel.toolName(...)`；单审/双审/抽检共用执行口径不变（§7.8/§6.142 语义保持）。
- **SubTaskReviewServiceImpl**：doDualReview 两侧 execute 传 DUAL；applyVerdict 增加 channel + consensusSummary 参数（原签名委托 SINGLE），结果消息类型随链路切换；双审共识落地时构造「## 双审共识」摘要行（策略 REQUIRE_BOTH/ANY + 评审1/评审2 通过/驳回与评分 + 共识结论），附在结果消息首部；评审1/2 各自 verdict 原文与 sender_id 仍完整保留在对话流（§6.142 口径不破）。
- **ReviewRecheckExecutor**：execute 传 RECHECK；判定后可读结论以 `subtask_recheck_result` 落对话流（best-effort，含结果/评分/原判定 reviewRecordId/问题/评语，标题显式标注「只度量，不改变子任务状态」）；新增 ConversationService 构造器注入。
- **前端**：SubTaskDetail.vue CONV_TAG_MAP 与 timeline 共用字典、sequenceFlow.ts 节点名各补三链路 14+ 项（双审请求/分析/结论、抽检请求/分析/结论，及双审共识/缺失/降级/分歧、抽检一致/分歧等 timeline 事件文案）；核验轮次分组 isReview 前缀扩展（`subtask_review | subtask_dual_review | subtask_recheck`）；ReviewVerdictView 结构化视图兼容三种 verdict 类型（原单审标签与历史消息不变）。

#### 3. 验证结果

- 后端：`mvn -pl helloai-core -am test -DskipTests=false -Dtest=SubTaskReviewServiceTest,ReviewRecheckExecutorTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false` BUILD SUCCESS，**42/42 全绿**（SubTaskReviewServiceTest 39 + ReviewRecheckExecutorTest 3，覆盖双审一致/分歧转人工/候选不足降级/ANY 与抽检一致/分歧/跳过全链路；ReviewRecheckExecutorTest 新增 2 处对话流结论消息断言）。
- 前端：`npm run type-check` EXIT=0（vue-tsc --noEmit 0 错）；顺序 Flow 节点映射与轮次分组前缀变更无类型错误。
- 历史兼容：存量 `subtask_review_*` 消息标签与渲染路径不变，仅新产生消息按新链路类型生效。

#### 4. 影响与遗留

- 影响：执行对话流可一眼分辨单审/双审/抽检；双审共识摘要让「一过一不过最终通过」类疑问在对话流内自解释（评审1/评审2 观点 + 共识策略）；抽检结论（含分歧缺陷）直接在对话流可见，不再依赖 timeline 间接推断；双审/抽检相关 timeline 事件获得前端文案。
- 遗留：双审两条 verdict 消息头未展示 Reviewer 名称（sender_id 可查证，如需展示可后续加 agent 名解析）；真实环境效果待后端重启后实测；本轮改动未 git 提交，待用户确认后提交。

### 6.155 子任务详情页改版（版本三精简版）：概览统计卡 + 双栏布局 + 时间线里程碑收纳（2026-08-25）

#### 1. 背景与结论

- **背景**：子任务详情页（SubTaskDetail.vue）为单列堆叠的日志式布局：el-descriptions 元信息表 + 对话流 + 13 条全量时间线顺序铺陈，关键结论（核验通过/产出附件）淹没在长页中；时间线里分发/指令流转/上下文装配等例行内部事件与关键里程碑享受同等视觉权重，每条还挂「技术详情（开发者）」折叠。用户提供三版设计稿对比后选定版本三（精简版）：概览统计卡 + 头部信息带 + 双栏（左对话流 / 右附件 + 时间线）。
- **结论**：纯前端改版（无后端 / Flyway / 配置变更），页面结构改为「状态徽标 + 标题 + 依赖标签头部卡 → 4 张可点击概览统计卡 → 任务摘要 → （人工介入卡）→ 左主栏对话流 / 右栏附件 + 时间线」；时间线默认只呈现关键里程碑，例行事件收纳进「展开全部」，技术详情仅在全量视图露出；数据源与轮询/人工介入/导出等既有能力零改动。

#### 2. 实现要点（仅 `helloai-ui/src/views/subtask/SubTaskDetail.vue`）

- **布局**：`.page` 改 CSS Grid（`minmax(0,1fr) 360px` 双栏 + 16px gap），头部/统计/摘要/人工介入用 `.full-row` 通栏，对话流 `.main-col`（grid-row: span 2）占左主栏，附件 + 时间线 `.side-card` 自然堆叠右栏——DOM 顺序不变、卡片内容零重排，靠网格定位完成双栏，规避 vue/html-indent 大面积重缩进。≤1024px 收单列（对齐项目 1024/768 断点口径），≤768px 统计卡变 2 列。
- **头部卡**：替代 el-descriptions——「子任务详情」小标 + 状态 tag + 评分 tag + 返回列表；h1 标题；负责人/创建时间元信息行；前置依赖/被依赖标签行（V27 能力保留，点击跳兄弟子任务）。原「内容」行独立为任务摘要卡。
- **概览统计卡**：对话轮次 / 核验轮次 / 产出附件 / 时间线事件 4 张（数字与下方区块同源：convRounds 按 execute/review 分计、viewAttachments、timeline.length），左边框色走 `--ha-*` 语义令牌（primary/warning/success/info），点击 scrollIntoView 定位对应卡片（`sec-conv/sec-att/sec-tl` 锚点 + scroll-margin-top）。
- **时间线精简**：新增 `COMPACT_HIDDEN_EVENTS`（20 个例行事件：分发准备/派单流转/指令生成领取/上下文装配/思考过程/核验请求与思考等）+ `timelineFull` 开关；`viewTimeline` 默认过滤例行事件（典型 13 条 → 约 5-6 条关键节点），卡头「展开全部 N 条 / 只看关键节点」切换；「技术详情（开发者）」折叠仅在全量视图露出（默认面向非开发者，开发者一键展开回查）。「时间线列表 / 执行时序图」V35 双视图保留。
- **样式纪律**：全部色值走 `--ha-*` 令牌（未硬编码十六进制）；统计卡悬停/按压动效走 `--ha-duration-fast`/`--ha-ease-out`；入场沿用 `ha-stagger-entrance`；`margin-top:16px` 内联样式全部移除改由 grid gap 接管。
- **不做项**：后端 / API / Flyway 零改动；时间线事件字典（EVENT_META）、对话流轮次分组、人工介入面板、附件下载、轮询逻辑均不变。

#### 3. 验证结果

- `npm run type-check`（vue-tsc --noEmit）0 错。
- `npx eslint src/views/subtask/SubTaskDetail.vue`：0 error，1 warning 为存量 `@typescript-eslint/no-explicit-any`（manualReasonText 既有代码，非本轮引入）。

#### 4. 影响与遗留

- 影响：子任务详情页从「日志视角」转为「用户视角」——首屏即状态 + 评分 + 概览数字，核验结论与产出附件同屏可达；例行事件不再噪声化，开发者可一键回查全量 + 技术详情；窄屏自动退化为原单列形态。
- 遗留：真实环境视觉效果待用户浏览器复验；若后续右栏新增第三张卡，`.main-col` 的 `grid-row: span 2` 需同步调整（样式注释已标注）；本轮改动未 git 提交，待用户确认后提交。

### 6.156 子任务详情页版本三第二轮：对话流卡片化 + 核验分析折叠 + 时间线去技术详情 + 时序图迁对话流页签（2026-08-25）

#### 1. 背景与结论

- **背景**：§6.155 版本三改版后用户对照新设计稿提出四点迭代：① 执行对话流按设计图卡片化（轮次头 = #N · type 徽标 + Agent 标签 + 状态标签 + 时间右对齐）；② 「核验分析 #N · assistant/agent · …」类长文消息要可展开/收缩（默认收起）；③ 时间线列表隐藏「技术详情（开发者）」，只展示标题 + 一句话描述 + 时间；④ 执行时序图从右栏时间线卡迁到执行对话流卡的页签（右栏 360px 太窄，mermaid 图显示太小看不全）。
- **结论**：纯前端迭代（仅 SubTaskDetail.vue），四点全部落地：对话流卡内页签「执行对话流 / 执行时序图」（时序图获得左主栏宽画布）；消息块统一折叠交互（分析/思考类默认收起 + 单行摘要预览，长文 >300 字按需展开）；时间线右栏纯列表化；数据源/轮询/人工介入/导出能力零改动。

#### 2. 实现要点（仅 `helloai-ui/src/views/subtask/SubTaskDetail.vue`）

- **对话流页签化**：对话流卡头改为 `el-tabs`（convView: 'conv' | 'seq'），执行时序图（SubTaskSequenceFlow，mermaid 自适应宽度）从时间线卡迁入；时间线卡移除双视图与「展开全部」按钮，退化为纯里程碑列表。
- **轮次头卡片化（对齐设计图）**：`#{{roundNo}} · {{type}}` 徽标（execute 紫 / review 橙）+ Agent 名标签（首条带 senderId 的消息解析，回退当前负责人）+ 状态标签（已完成/失败，按是否含 sub_task_execute_failed）+ 末条消息时间右对齐（tabular-nums）。
- **消息块折叠**：`ANALYSIS_TOOLS`（单审/双审/抽检 verdict + thinking + 执行思考 7 类）默认收起，`msgToggleOverride` 记录逐条用户开关（轮询刷新不重置已打开块）；收起态渲染单行摘要预览（首个非空行去 Markdown 符号截断 120 字），头部 ArrowRight caret 旋转 90° 指示状态；分析类块底色走 `--ha-warning-bg` 语义令牌（亮/暗自适应）；长文 >300 字非分析类沿用展开逻辑；复制/导出按钮 `@click.stop` 防冒泡误触折叠。
- **时间线纯列表化**：移除「技术详情（开发者）」collapse 与 payload 展示（`.tl-collapse/.tl-payload` 样式一并删除），每条 = 事件标题（14px/600）+ 右侧时间 + 一句话人话描述；移除 el-timeline-item 自带 `:timestamp`（与右侧时间重复）；例行事件仍按 `COMPACT_HIDDEN_EVENTS` 静默过滤（`timelineFull` 保留作回查开关，当前无模板入口）；清理不再使用的 `ROLE_LABEL/roleLabel/hiddenEventCount`。
- **样式纪律**：新增样式全部走 `--ha-*` 令牌（warning-bg 底色、muted 预览文字、duration-fast caret 动效）；缩进由 `eslint --fix` 对齐 vue/html-indent（块嵌入 tab-pane 深一层）。
- **不做项**：后端 / API / Flyway 零改动；EVENT_META 事件字典、轮次分组逻辑、统计卡、双栏布局均不变。

#### 3. 验证结果

- `npm run type-check`（vue-tsc --noEmit）0 错。
- `npx eslint src/views/subtask/SubTaskDetail.vue`：0 error，1 warning 为存量 `@typescript-eslint/no-explicit-any`（manualReasonText 既有代码，非本轮引入）。

#### 4. 影响与遗留

- 影响：对话流从「平铺长文」转为「卡片 + 按需下钻」，核验分析黄块默认收起一行摘要，页面显著变短；时序图获得左主栏全宽画布，不再被右栏压小；时间线对非开发者完全去噪。
- 遗留：`timelineFull` 开关当前无模板入口（保留代码作未来回查出口）；真实环境视觉效果待用户浏览器复验；本轮改动未 git 提交，待用户确认后提交。

### 6.157 子任务详情页版本三第三轮：时间线截断防溢出 + 附件行式缩略图布局 + 右栏卡顶对齐（2026-08-25）

#### 1. 背景与结论

- **背景**：§6.156 后用户浏览器复验提出三点：① 无字典事件名（如 `sub_task_artifact_materialized`）在 360px 右栏换行撑宽，产生横向滚动条，期望 `sub_task…` 式单行截断；② 产出附件平铺式（名称+大小时间+按钮）与卡片语言不协调，按参考图改为「左侧类型缩略块 + 右侧文件名/类型·大小双行」；③ 附件卡夹在中间导致执行时间线下沉，与执行对话流卡顶错位，期望两卡对齐。
- **结论**：纯前端收口（仅 SubTaskDetail.vue）：事件名单行省略截断（悬停 title 回查原名）+ 补 `sub_task_artifact_materialized` 字典（产出物化）；附件卡改行式缩略图布局（扩展名大写缩略块）；右栏 DOM 顺序调整为「时间线先、附件后」+ 自动堆叠，时间线恒与对话流卡顶对齐且附件条件渲染不产生空行间隙。

#### 2. 实现要点（仅 `helloai-ui/src/views/subtask/SubTaskDetail.vue`）

- **时间线截断**：`.tl-title` 加 `flex:1; min-width:0` + `overflow:hidden; text-overflow:ellipsis; white-space:nowrap`（flex 子项不设 min-width:0 时省略号不生效），`.tl-row` 同步 `min-width:0`，`:title` 悬停回查完整事件名；`.tl-desc` 补 `word-break:break-word` 防长描述横向溢出；EVENT_META 补 `sub_task_artifact_materialized`（产出物化）使标题与描述均人话化。
- **附件行式布局（对齐参考图）**：新增 `attExt()` 取扩展名大写（>5 字符/无扩展名降级 FILE）；每行 = 44px 类型缩略块（`--ha-surface-elevated` 底 + 边框 + 大写扩展名）+ 文件名（单行截断）/「类型 · 大小」双行 + 旧版本标 + 下载按钮；原元信息行中的创建时间移除（信息密度让位给类型层级）。
- **右栏卡顶对齐**：曾尝试显式行定位（附件 row 1 / 时间线 row 2）——但附件条件渲染缺失时空行仍产生 16px gap 导致错位，已回退；最终方案 = DOM 顺序重排（时间线卡移到附件卡前）+ `.side-card` 自动堆叠，时间线恒在右栏首位与对话流卡顶对齐，无附件时零间隙；≤1024px 单列回退不变。
- **样式纪律**：新增样式全部走 `--ha-*` 令牌；未引入图标库（扩展名文字缩略块即可表达文件类型，避免新增依赖）。
- **不做项**：后端 / API / Flyway 零改动；折叠交互、页签结构、统计卡均不变。

#### 3. 验证结果

- `npm run type-check`（vue-tsc --noEmit）0 错。
- `npx eslint src/views/subtask/SubTaskDetail.vue`：0 error，1 warning 为存量 `@typescript-eslint/no-explicit-any`（manualReasonText 既有代码）。
- 用户已手动微调对话流卡头（移除「执行对话流」标题 span，仅留条数/轮数计数），本轮改动保留该调整。

#### 4. 影响与遗留

- 影响：右栏彻底消除横向滚动条；时间线与对话流两卡顶对齐，附件沉底不再突兀；附件展示与参考图形态一致。
- 遗留：右栏顺序变为「时间线在上、附件在下」，与版本三初版（附件在上）相反，属用户本轮明确要求的对齐优先级取舍；真实环境待用户浏览器复验；本轮改动未 git 提交，待用户确认后提交。
- **复验修正（用户反馈，2026-08-25）**：① 时间线卡头恢复「展开全部 N 条 / 只看关键节点」切换按钮（§6.156 精简时误删，`timelineFull`/`COMPACT_HIDDEN_EVENTS` 逻辑一直在，仅模板入口丢失；同步恢复 `hiddenEventCount` computed 与 `.tl-head-right` 样式，后者在 §6.156 清理时被一并删除）；② 对话流消息默认态由「分析/思考类收起」改为**全部展开**（`isMsgExpanded` 默认返 true，覆盖 §6.156 的 `!isAnalysisMsg` 默认口径），折叠能力保留（点头部可收起为单行摘要预览）。修正后验证：vue-tsc 0 错、eslint 0 error（1 存量 warning）。
- **复验修正第二轮（用户确认，2026-08-25）**：① 时间线「收起」按钮消失是 `hiddenEventCount` 口径 bug——原实现用「总数 − 当前显示数」，展开后差值为 0 导致 `v-if` 不成立按钮消失；已改为基于 `COMPACT_HIDDEN_EVENTS` 的全量稳定计数，展开/收起两态按钮恒在；② 对话流默认态修正为**全部收起**（用户确认「默认收起没问题」，上轮将「默认都收起」误读为「应全部展开」并已反向回改；`isMsgExpanded` 默认返 false，点击头部展开），折叠交互与轮询不重置特性不变。二次修正后验证：vue-tsc 0 错、eslint 0 error（1 存量 warning）。

---

### 6.158 子任务详情页版本三第四轮：时间线设计图润色（事件卡片化 + 语义着色）
_时间：2026-08-25（用户提供设计图，要求保留布局只润色元素样式）_

#### 1. 背景与结论
用户提供执行时间线设计图（彩色圆点轴 + 类型标签徽标 + 语义淡底事件卡），要求执行时间线与执行时序图保持现有布局不变，仅对按钮与元素样式按设计图润色。定性为**纯视觉层精修**：不动布局、不动数据结构、不动事件过滤逻辑。实施后时间线每个事件呈现为「语义色淡底卡片 + 顶部分类徽标/时间 + 标题 + 人话描述」，与右栏窄宽适配（截断/换行防护保留）。
明确不做：不恢复「技术详情（开发者）」链接（设计图含此项，但前轮用户已明确要求隐藏，用户当前指令优先）；不新增「执行者」行（设计图含，但属新增数据展示，超出「只润色样式」边界）；时序图 mermaid 画布与主题变量不动。
#### 2. 实现要点
- **事件卡片化**：`el-timeline-item` 内容包入 `.tl-item`（`--ha-radius-md` 圆角 + 细边框 + 10px/12px 内边距 + hover 边框提亮），结构改为三行：顶行分类徽标 + 右对齐时间 → 标题（单行截断保留）→ 人话描述。
- **语义色淡底**：新增 `tone-primary/success/warning/danger` 四组类，全部用 `color-mix(in srgb, var(--ha-*) 7~30%, transparent)` 基于设计令牌混透明，零硬编码色值，暗/亮主题自动跟随；`info` 态走默认 surface 不上色。
- **分类徽标**：新增 `eventCategory()`（分发/执行/核验/人工介入/任务/流程 六类，关键词正则判定），`el-tag size=small effect=light` 跟随 `eventTypeColor` 语义色。
- **`eventTypeColor` 修正**：异常分支前置（修复存量误判：`sub_task_auto_review_rejected` 含 review 关键字被误归 success）；warning 扩充 unparseable/degraded/disagreement/discrepancy/incomplete；success 改按 passed/ok/materialized/consistent/consented 等正向词；primary 扩充 dispatch/command（分发类着主色，对齐设计图）。
- **按钮润色**：卡头「展开全部 / 只看关键节点」由 link 按钮改为胶囊形 `round plain` 小按钮（展开态 info、收起态 primary），对齐设计图圆角矩形按钮风格。
- **时序图配套**：`SubTaskSequenceFlow` 摘要条五个 `el-tag` 统一加 `round` 胶囊化，与时间线徽标形态呼应；mermaid 画布/主题变量/语法折叠均不动。
#### 3. 验证结果
- `npm run type-check`（vue-tsc --noEmit）0 错。
- `npx eslint`（SubTaskDetail.vue + SubTaskSequenceFlow.vue）：0 error，2 warning 均为存量 `no-explicit-any`。
#### 4. 影响与遗留
- 影响：时间线节点语义一目了然（紫=分发、绿=成功、红=异常、黄=降级/分歧），事件卡与轴点颜色同调；交互与信息结构零变化。
- 遗留：设计图中「执行者：xxx」行与「技术详情（开发者）」入口本轮按边界未纳入，如后续需要再单独提；本轮改动未 git 提交，待用户确认后提交。
- **复验修正（用户反馈，2026-08-25）**：移除「一眼概览」四张统计卡（对话轮次/核验轮次/产出附件/时间线事件）——数字与各卡头「共 N 条 · M 轮」计数完全重复，用户判定冗余；同步下线 `statCards` computed 与 `scrollToSection` 锚点滚动函数、`.stat-grid/.stat-card/.stat-num/.stat-label/.tone-*` 样式与 768px 媒体查询规则；点击锚点定位能力随卡片一并移除（页面不长，直接滚动即可）；`.full-row` 保留（头部卡/摘要卡/人工介入卡仍在用）。修正后验证：vue-tsc 0 错、eslint 0 error（1 存量 warning）。
- **复验修正（续，2026-08-25）**：任务摘要并入头部卡（用户要求「任务摘要和子任务详情放在一个框中」）——独立 `summary-card` 移除，摘要块作为头部卡底部区块（`.head-summary`，虚线分隔 + 小标签），`v-if="item.content"` 条件渲染不变；`.summary-text` 样式复用，新增 `.head-summary/.head-summary-label`。修正后验证：vue-tsc 0 错、eslint 0 error（1 存量 warning，4 个 singleline-content 告警经 --fix 自动修复）。
