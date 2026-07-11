# HelloAI 项目基线文档

## 1. 文档定位

本文档只回答三件事：

- 当前项目是什么
- 当前哪些能力可以视为现实基线
- 文档冲突时以什么作为事实源

本文档不承担实施流水账、历史路线图或逐条差距对表的职责。

---

## 2. 当前基线结论

- 当前项目是一套基于 Spring Boot + Spring AI MCP 的多 Agent 协作调度平台。
- 当前主线已具备 MCP SSE 接入、双通道鉴权、工具调用、在线状态三件套、熔断降级、Reconcile 健康检查、管理后台与基础前端能力。
- 当前工程运行基线保持在 `JDK 17 + Spring Boot 3.4.x + Spring AI 1.1.x`。
- 涉及调度、执行链、异步回写、MQ 解耦的后续设计与实现，统一优先参考 `doc/HelloAI_调度解耦重构分析.md` 与 `E:\workspace\AgentTeams-main` 的分层思想。

---

## 3. 当前已形成闭环的能力

- MCP SSE 接入与消息链路
- 管理员 Token / Agent API Key 双通道鉴权
- MCP 工具注册与业务工具调用
- Agent 在线状态三件套：`last_seen_at` / `last_active_at` / `online_status`
- 熔断降级与同角色替补
- Reconcile 健康检查与离线重分配
- Session TTL 清理
- 基础管理后台与前端主流程

---

## 4. 当前不默认视为“已完整交付”的能力

以下内容即使在历史文档中被展开描述，也默认属于目标态、部分落地或待补能力，不能直接按“已交付”理解：

- 工作流模板与 Team 编排
- 独立 MQ / DB poller 版执行命令消费载体
- `credential_vault` 的完整轮换、迁移与权限模型
- 浏览器型 Agent 的真实接入链路
- 多 Provider 的完整配置复用与平台内执行统一抽象

---

## 5. 文档矩阵

### 5.1 核心三层

- `doc/HelloAI_项目基线文档.md`：项目是什么
- `doc/HelloAI_实现差距表.md`：差在哪里
- `doc/HelloAI_迭代执行记录.md`：做了什么

### 5.2 专项分析

- `doc/HelloAI_调度解耦重构分析.md`
- `doc/HelloAI_执行链路架构分析.md`

### 5.3 历史资产

- `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`
- `doc/HelloAI_vs_OpenMOSS_功能对比与实现方案.md`
- `doc/HelloAI_技术方案与补齐清单_v1.1.md`
- `doc/HelloAI_Agent接入内容生成功能开发清单_v2.0.md`

### 5.4 设计参考

- `doc/HelloAI_架构设计参考.md`

---

## 6. 事实源优先级

文档冲突时，按以下优先级判定：

1. 代码与运行结果
2. Flyway 初始化脚本与数据库结构
3. 验收脚本与可复现实验结果
4. `doc/HelloAI_实现差距表.md`
5. 本文档
6. README
7. 历史路线图 / 技术方案 / 对比文档

---

## 7. 工程红线

- JDK 固定为 `17`
- Spring AI 保持当前项目运行基线，任何升级或回退都必须重新做 MCP 鉴权与端到端回归
- 后端数据库初始化以 `helloai-start/src/main/resources/db/migration/V1__init_all.sql` 为单一初始化入口
- Controller 只做参数接收、DTO 转换与返回封装
- 代码事实与文档不一致时，优先修正文档误导，而不是用文档掩盖现状
