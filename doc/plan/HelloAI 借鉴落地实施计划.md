# HelloAI 借鉴落地实施计划

> **状态：ACTIVE**
> **编号体系：`REF-xxx`**（借鉴落地项，批次.序号），与既有 `BASE-xxx` / `RMx` / `P0-x` 完全错开。
> **依据**：`doc/research/` 5 份调研（2026-10-09）——用户裁定的四项优先级 + 四项之外 A 档独立交付项。
> **性质**：本计划只收**可执行结论**（借鉴落点 → 验收），不含调研过程（在 `research/`）与稳定设计（在 `design/`）。
> **完成迁移**：执行完成后迁入 `doc/archive/implemented/` 并标 `Done`。
> 最后更新：2026-10-09

---

# 1. 优先级总览（用户裁定，2026-10-09）

```text
第 1 步：skills 技能可装配（最高优先）
第 2 步：Fork/Return 回退 + 备份/恢复
第 3 步：沙箱（B2 能力层，用户裁定最后做）
第 4 步：RAG 知识库（可后置）
并行/插队：四项之外 A 档（独立交付，互不依赖）
```

**依赖约束**：
- 技能脚本执行依赖沙箱 ⇒ 沙箱未完成前只做「安装/解析/校验」，不开放脚本执行；
- `SESSION_AUTH` 进程级注册表必须先于多实例部署解决（故障现象像鉴权 bug）。

---

# 2. 第 1 步：skills 技能可装配（REF-1.x）

> 调研依据：`research/helloai四能力完善优先级与借鉴路线.md` §2（K1~K4）+ Octop `skill/` 系列。
> helloai 现状：`AgentSkillSpecServiceImpl.KNOWN_SPECS` 编译期硬编码（`:31`）；4 个 `eng-*.md` 无 frontmatter；`ToolDefinition(name, description)` 仅两字段。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-1.1 | 给现有 4 个 `eng-*.md` 补 YAML frontmatter（name/description/version/required_tools），正文不变 | 现有渲染行为不变（回归）；新增解析器能读出 frontmatter |
| REF-1.2 | `KNOWN_SPECS` 硬编码 → 目录扫描（`skills/plugins/` + 可选外部目录），解析失败显式报 corrupt | 单测：坏文件出现在列表且带 `error`，不静默跳过 |
| REF-1.3 | **`ToolRegistry` 加两个语义位**——「按条件可用」「按上下文动态描述」；`ToolDefinition` 追加字段（兼容旧构造） | 无 KB 即摘工具；每轮重写 description；**planner 工具收窄从硬编码变配置** |
| REF-1.4 | 技能来源标记：`origin` + `locked`，拷贝进 Agent 工作区时打标 | 单测：带标技能被下游改写时能被识别 |
| REF-1.5 | 第三方摄入安全闸门（文件数 ≤2000 / 解压 ≤64MB / 压缩比 >100 拒 / 路径含 `..` 拒 / symlink 拒 / 清单必须根 SKILL.md） | 针对每类攻击各写一个必失败用例 |
| REF-1.6 | 导出/安装闭环（打包 zip → 过闸门 → 可解析且 requiredTools 一致） | 端到端：导出再导入可解析 |

**拆巨类联动**：REF-1.3 语义位是拆 `McpToolServiceImpl`(1181) 的前置——先让工具判断从方法体收敛到注册元数据，再拆类。RM9 拆法仍须用户确认。

---

# 3. 第 2 步：Fork/Return 回退 + 备份/恢复（REF-2.x）

> 调研依据：`research/AgentTeams_Octop_源码复核与helloai借鉴对照.md` §1（F1~F5）+ Octop `backup/`。
> helloai 现状：`AgentEventForkService` 快照复制完成、驱动执行后置；REWORK 闭环已完整；**备份完全空白**。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-2.1 | **Fork 驱动执行接线**：`AgentEventForkService` + `sub_task`/execution command 带 fork run_id → fork 后的 Run 能真的跑起来 | fork 后新 Run 可执行；原 Run 不被扰动 |
| REF-2.2 | Fork 快照可观测：`remark = forked from <originRunId>` 已带；补 timeline 事件 | 前端可见 fork 来源 |
| REF-2.3 | 备份/恢复：`pg_dump -Fc` + MinIO 对象清单 + manifest 前置 peek + Redisson 单飞锁 + 仅淘汰自动备份 | 备份可恢复；运行中备份不锁库；手动备份永不被自动清理误删 |
| REF-2.4 | 停机恢复流程文档化（诚实边界：运行中备份不保证多文件同一瞬间） | 文档成文 |

**Return 部分**：REWORK→驳回→改派→重开工闭环已完整，**不新做**；仅建议核对改派时 `task_timeline` 是否保留（review 驳回路径已有 `REVIEW_REJECTED` 落 timeline）。

---

# 4. 第 3 步：沙箱（REF-3.x，B2 能力层）

> 调研依据：`research/helloai四能力完善优先级与借鉴路线.md` 第 3 步（S1~S5）+ Octop `backend/probe.py` + AgentTeams `sandbox/plugin.go`。
> helloai 现状：`ExecutionPolicy` 五边界 record 已设计、诚实标注「无真实沙箱」；`SandboxProvider` 唯一实现只做环境路由（53 行）；`Sandbox_Provider.md` `Status: Planned`。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-3.1 | `ExecutionPolicy` 补 `DockerPolicy`/`BubblewrapPolicy` 静态工厂（只声明事实）；`EnvironmentSandboxProvider` 增加 docker 分支（先解析不执行） | 单测：`SandboxContext(docker 配置)` → 返回带非 NONE 五边界的 `Sandbox` |
| REF-3.2 | 定义 `SandboxSpec`（声明式）：type + 五边界字段 + scope；来源可为 Agent 配置或平台默认 | 同一份 spec 能渲染出容器创建参数；配置可被单测断言 |
| REF-3.3 | 实现 `DockerSandboxProvider`：起容器 + 白名单 env（≤4 变量）+ `allow_network=false` 默认 + 资源上限 | 集成测试（Testcontainers）：容器内 ls/read/write/execute 可用；宿主 env 不出现在容器内 |
| REF-3.4 | **照抄 probe**：写→读→删真实往返 + 失败路径也 `destroy()` | 探针结果落 timeline（可观测登记） |
| REF-3.5 | `scope`（agent/user/fixed）与容器生命周期：不自动销毁，显式回收 | 单测：「同 Agent 复用同一容器」「删除 Agent 不删容器」 |

**硬约束**：REF-1.5 技能脚本执行依赖 REF-3.3 完成；自持浏览器（若做）也依赖沙箱。

---

# 5. 第 4 步：RAG 知识库（REF-4.x，可后置）

> 调研依据：`research/helloai四能力完善优先级与借鉴路线.md` 第 4 步（R1~R3）+ Octop `knowledge/`。
> helloai 现状：**完全空白**（pgvector/embedding/知识库 零命中）。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-4.1 | 先定「什么不许进上下文」：无 KB 即摘工具（REF-1.3 语义位消费）+ 注入预算（`char_budget` 契约 + 单测断言） | 无 KB 时 `search_knowledge` 不在工具列表 |
| REF-4.2 | pgvector 存储与检索（**不抄 Octop SQLite 侧库**，PG 单后端优势） | 检索命中正确；预算截断生效 |
| REF-4.3 | 引用溯源 marker：检索结果尾部附 marker，前端渲染卡片、喂模型前剥掉 | 报告/核验意见可点开引用来源 |

---

# 6. 四项之外 A 档（REF-5.x，独立交付，可插队）

> 调研依据：`research/helloai借鉴清单_四项之外_完整版.md` A 档（A1~A6）。

| 项 | 动作 | 验收 | 备注 |
|---|---|---|---|
| REF-5.1 | **失败回叫闭环**：`ResilientDispatcher.doAssignNextFallback` 失败必落 timeline + 可读原因 | 派工失败用户可见原因 | 一行级，可先做 |
| REF-5.2 | **载荷旁路**：`ToolResult` 双视图（modelView 摘要+ref / uiPayload 全量），全量落 MinIO 按内容哈希去重 | 第二个附件全文不再丢 | 唯一真丢数据处；建议单独立项 |
| REF-5.3 | **首次运行锁定**：无用户时锁死除 setup 外全部端点 | 初始化中实例不对外裸奔 | 廉价 |
| REF-5.4 | **SSRF 出站校验**（含 DNS-rebinding pinning） | 外联 URL 必过校验 | 落点 `WebPageFetchServiceImpl` |
| REF-5.5 | **目录守卫 + 结构化拒绝码** | 拒绝原因可读 | 开放工作目录前做 |
| REF-5.6 | **备份/恢复**（= REF-2.3，此处仅索引） | — | 与第 2 步合并 |

---

# 7. B 档判据登记（REF-6.x，零成本，入 MEMORY.md「可复用判据」）

> 调研依据：`research/helloai借鉴清单_四项之外_完整版.md` B 档（B1~B13）。

| 项 | 判据 | 落地 |
|---|---|---|
| REF-6.1 | 幂等键必须 status-scoped（「先报 BLOCKED 后报 SUCCESS」不被静默吞） | 核对 `agentOutboxService` 去重键 |
| REF-6.2 | 审计闭合 schema（无自由文本字段）+ append-only keyset 分页 | 核对 timeline 分页是否 offset |
| REF-6.3 | 能力摘除式治理（无 KB 即摘工具 / 不可关闭清单 / 条件可用） | 随 REF-1.3 |
| REF-6.4 | 水位四判据（一 marker 不得两语义 / 失败不推进 / 检测范围=推送范围） | MinIO 附件同步 |
| REF-6.5 | 身份调用上下文（`_sessionId` 进程级 = 多实例前置） | 登记，多实例前解决 |
| REF-6.6 | 探活两类区分（对象在连接死 vs 对象在别 JVM） | 登记 |
| REF-6.7 | 错误语义 domain 层，HTTP 层只映射 | 生成式校验候选 |
| REF-6.8 | key parity 守卫（事件码服务端单一来源 + 前后端 key 匹配测试） | 治 eventMeta 三处漂移 |
| REF-6.9 | 探针纪律（「配好了」必须能被机器验证 + 保证回收） | 随 REF-3.4 |
| REF-6.10 | 状态面最小化（有没有「事后查」的查询方） | 登记 |
| REF-6.11 | denylist 默认全开 + 不可关闭清单 | 随 REF-1.3 |
| REF-6.12 | sentinel 区分「未传」/「传 null」 | PATCH 语义 |
| REF-6.13 | 新能力默认关闭（通过注入启用） | Sandbox/Skill 上线时 |

---

# 8. 明确不做（C 档，与治理红线一致）

- Octop 单进程 / 双后端 / 进程内调度（C1~C3）——路线分歧；
- AgentTeams CRD / Helm / leader-election / Matrix 房间（C4）——K8s 原生平台实现细节；
- CDP 逐帧直播 / Python IM 网关 / 桌面移动语音交付面（C5~C7）——定位无关；
- **外部 agent 对接**（C8）——用户裁定可借鉴内容有限（两者未实现真 A2A；helloai 的 MCP+SSE+心跳接单是当前最优解）。

---

# 9. 建议执行顺序（第一批）

```text
REF-5.1 失败回叫（一行级，可立即）──► REF-1.1 补 frontmatter（零风险）──► REF-1.3 语义位（拆巨类前置）
──► REF-2.1 Fork 驱动执行 ──► REF-5.3 首次运行锁定 ──► REF-5.2 载荷旁路（单独立项评估）
```

**待用户拍板**：RM9 拆 `McpToolServiceImpl` 是否采用「先 REF-1.3 语义位、再拆类」顺序（原裁定「拆法须确认」）。
