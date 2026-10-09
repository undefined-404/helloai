# helloai 四项能力完善优先级与借鉴路线（用户裁定版）

> **Status: `Analysis`**（借鉴预研，不改变任何实现、不承诺排期；本文件为优先级裁定与落点，不写进度）
> **背景**：2026-10-09 对 `E:/workspace/AgentTeams-main` 与 `E:/workspace/Octop-main` 完成源码复核（见 `AgentTeams_Octop_源码复核与helloai借鉴对照.md`），用户据此裁定需要完善的四项能力及优先级。
> **裁定优先级**（用户，2026-10-09）：**① skills 技能可装配 > ② Fork/Return 回退 + 备份/恢复 > ③ 沙箱 > ④ RAG 知识库**（RAG 可后置）。
> **额外裁定**：外部 AI agent 对接**可借鉴内容有限**（两者均未实现真 A2A，AgentTeams 为封闭 runtime 枚举 `interface.go:34-39`），本规划不含该项。
> **性质**：本文件是**规划/预研**（借鉴落点 + 拆解思路 + 与既有规划的衔接），不改任何代码、不承诺排期。

---

## 一、四项能力的「借鉴来源 → helloai 落点 → 验收」总表

| # | 能力 | 借鉴来源（带实证） | helloai 现状（已核实） | 落点 |
|---|---|---|---|---|
| **1** | **skills 技能可装配** | Octop `skill/workspace_catalog.py:131-170`（目录即真相）· `skill/skill_packages.py:10-11`（摄入闸门）· `skill/skill_transfer.py:54-72`（origin/locked）· `docs/expert-teams.md`（主持人工具收窄） | `AgentSkillSpecServiceImpl.KNOWN_SPECS` 编译期硬编码（`:31`）；classpath 4 个 `eng-*.md` 无 frontmatter；`ToolDefinition(name, description)` 仅两字段；`ToolRegistry.resolve(启用名)` 单向解析 | 补 frontmatter → 目录扫描 → 摄入闸门 → 来源标记；**同步给 `ToolRegistry` 加「按条件可用」「按上下文动态描述」语义位**（见 §2） |
| **2** | **Fork/Return 回退 + 备份/恢复** | Fork：Octop `threads/fork.py`（checkpoint 前缀→新线程，`aupdate_state` 灌入）；Return：AgentTeams `server.py:5390-5403`（re-dispatch 保留审计）+ `task-transition-engine.md`（history cap 50）；备份：Octop `backup/`（pg_dump -Fc + manifest 前置 peek + 单飞锁 + 仅淘汰自动备份） | Fork 半截：`AgentEventForkService`（快照复制完成，驱动执行/API/冻结三处后置）；Return 已完整：REWORK→驳回→改派闭环；**备份完全空白**（helloai-core/api grep backup 零命中） | Fork 驱动执行接线（F3）；备份/恢复（pg_dump + MinIO 清单 + manifest + Redisson 单飞锁） |
| **3** | **沙箱** | Octop `backend/docker_spec.py:10-29`（17 键 spec）+ `backend/probe.py:118-214`（写→读→删真实探针）；AgentTeams `sandbox/plugin.go`（Capabilities=min(声明,配置)、Hibernate/Resume） | `ExecutionPolicy` 五边界 record 已设计、注释诚实标注「无真实沙箱」；`SandboxProvider` 唯一实现只做环境路由（53 行）；`Sandbox_Provider.md` `Status: Planned` | spec 声明化（S1-S2）→ DockerSandboxProvider（S3）→ 探针（S4）→ scope 生命周期（S5）；**技能脚本执行依赖沙箱完成**（硬约束） |
| **4** | **RAG 知识库** | Octop `knowledge/` 全链路：`chunk.py`→`parse.py`→`embed.py`（默认 ONNX）→`index.py`（每库 SQLite 侧库）→`retrieve.py`（k=8, char_budget=6000）→`citations.py`（引用 marker）→`hint.py:94-99`（无 KB 即摘工具） | **完全空白**（pgvector/embedding/知识库 零命中） | pgvector（**不抄 SQLite 侧库**，PG 单后端是优势）→ 先定「无 KB 即摘工具 + 注入预算」→ 引用 marker 最后补 |

---

## 二、★ ToolRegistry 语义位 —— 同时是「skills 可装配」与「拆巨类」的汇合点

**用户指出**：报告中「`ToolRegistry` 应增加『按条件可用』与『按上下文动态描述』两个语义位，这是把 planner 工具收窄从硬编码变成配置的手段」——这是拆解现有巨类的重要思路。

**helloai 实测现状**（2026-10-09 核实）：

| 组件 | 现状 | 证据 |
|---|---|---|
| `ToolDefinition` | **只有 `name + description`** 两字段，注释自述「参数 schema / 频率限制等扩展元数据留待 P1 Capability System」 | `ToolDefinition.java:11` |
| `ToolRegistry.resolve()` | 仅「按启用工具名 → 命中定义」单向解析，best-effort | `ToolRegistry.java:33-38` |
| `ToolRegistryImpl` | 从 spring-ai `ToolCallbackProvider` 收集 @Tool（11 MCP + 1 Echo），懒加载 | `ToolRegistryImpl.java:29-48` |
| **RM9 待拆巨类** | `RequirementClarifyServiceImpl`(1558) / `SubTaskServiceImpl`(1379) / `McpToolServiceImpl`(1181)——**「拆法须与用户确认」** | `doc/log/2026-10.md:1891` |
| planner 工具收窄 | **现状无显式「收窄」机制**（grep planner/agent 域无 excludeTool/disabledTool 命中；`McpMcpServer.java:83` 只有角色参数） | 本报告勘察 |

### 2.1 两个语义位怎么加（最小增量，不动现有调用）

```java
// ToolDefinition 扩展（字段追加，非破坏性）
public record ToolDefinition(
        String name,
        String description,
        // 新增：按条件可用 —— 条件为「平台/运行时事实」，返回 false 时工具从列表摘除
        Predicate<ToolContext> availability,
        // 新增：按上下文动态描述 —— 每轮注入前重写 description（如「本轮可见的 KB 目录」）
        Function<ToolContext, String> dynamicDescription) {

    // 兼容旧构造：name+description → 两个默认恒真/恒原样
    public ToolDefinition(String name, String description) {
        this(name, description, ctx -> true, ctx -> null);
    }
}
```

- **「按条件可用」**＝Octop `hint.py:67-99`「无 KB 即从工具列表摘掉 `search_knowledge`」的 Java 形状：`availability == false` ⇒ `ToolRegistry.resolve()` 不返回该工具（**摘除 > 在描述里写不要调用**）；
- **「按上下文动态描述」**＝Octop 每轮重写工具 description 注入「本轮实际可见目录」——模型不可能知道运行时有哪几个知识库/技能；
- **关键纪律**（来自两份报告一致性结论）：**「禁用了」与「不具备」是两个不同的事实，分开表达**（denylist 默认全开 + 不可关闭清单 `CRITICAL_TOOLS` + 条件可用），不要混成一个位。

### 2.2 为什么这是拆巨类的钥匙（用户判断的展开）

三个待拆巨类的「工具/技能相关」切片正好都能被语义位消化：

| 巨类 | 与工具/技能的耦合 | 语义位如何接走 |
|---|---|---|
| `McpToolServiceImpl`(1181) | 13 处 `requireAuthId` 重复覆写 + 工具白名单 + `startIfNeeded` 状态推进 | **身份覆写收进拦截层**（单一覆写点）；工具可用性/描述从语义位取，不再在方法体内硬判 |
| `RequirementClarifyServiceImpl`(1558) | 澄清轮次里的 web_search 等工具编排（`planner/tool/WebSearchToolCallback`） | planner 工具收窄 = 配置化的「可用性过滤」，巨类里的硬编码工具判断剥出 |
| `SubTaskServiceImpl`(1379) | 状态机 12 处 `updateById` + 3 个独立 inbox 通知（报告 A1 指认） | 状态转换史收口（F1）是拆它的**第一刀**——转换逻辑与业务逻辑分离 |

**拆法建议（与 RM9 既有登记衔接）**：RM9 已登记「一类一笔、拆法须与用户确认」。**建议把「ToolRegistry 语义位」作为拆 `McpToolServiceImpl` 的前置**——先让工具判断从方法体收敛到注册元数据，再拆类，切面更干净。

### 2.3 与 skills 可装配的汇合

语义位补上后，skills 侧可以直接消费同一套元数据面（`ToolRegistry` javadoc 已明示「Skill/Tool 两侧共用同一元数据消费面，不另建 SkillRegistry 平行类」）：

```
AgentSkillSpecService.resolve(requiredSkills) → 命中技能 → 声明 requiredTools
        │                                              │
        ▼                                              ▼
ToolRegistry.resolve(启用工具) ── 语义位过滤/改写 ──► 最终注入 AgentContext.tools
```

技能目录扫描（K2）后，`KNOWN_SPECS` 不再是编译期 Map，技能与工具两条线都走「目录/注册表 + 条件可用 + 动态描述」，**planner 工具收窄从硬编码变配置**的目标自然达成。

---

## 三、四项能力的落地顺序与依赖（用户优先级 + 既有规划衔接）

```
第 1 步：skills 技能可装配（最高优先）
  K1 补 YAML frontmatter（现有 4 个 eng-*.md）──► K2 目录扫描替代 KNOWN_SPECS
  ──► 同步加 ToolRegistry 语义位（§2，拆巨类前置）──► K3 来源标记 origin/locked
  ──► K4 第三方摄入安全闸门（zip bomb/../symlink/无 SKILL.md 各一必失败用例）
  ⚠️ 硬约束：技能脚本执行依赖沙箱 ⇒ 沙箱未完成前只做「安装/解析/校验」，不开放脚本执行

第 2 步：Fork/Return + 备份/恢复
  F3 Fork 驱动执行接线（AgentEventForkService + sub_task/execution command 带 fork run_id）
  B2 备份/恢复（pg_dump -Fc + MinIO 对象清单 + manifest 前置 peek + Redisson 单飞锁
      + 仅淘汰自动备份 + 停机恢复流程文档）——纯增量、可独立交付

第 3 步：沙箱（B2 能力层，用户裁定最后做）
  S1-S2 spec 声明化（对齐 Sandbox_Provider.md）──► S3 DockerSandboxProvider
  ──► S4 探针（照抄 Octop probe.py 写→读→删真实往返）──► S5 scope/生命周期

第 4 步：RAG 知识库（可后置）
  R1 先定「无 KB 即摘工具 + 注入预算」──► R2 pgvector 存储与检索 ──► R3 引用 marker
```

---

## 四、除四项外，报告与独立分析一致、值得借鉴的内容（补充）

以下为两份报告与本次独立源码复核**结论一致**、且**不在四项内**但 ROI 高的借鉴项，按主题归并：

### 4.1 横切纪律（判据级，零代码成本，建议入 MEMORY.md「可复用判据」）

| # | 判据 | 来源实证 | 说明 |
|---|---|---|---|
| X1 | **「通知是注意力信号，不是回执」**：同步失败=retryable 且扣住通知；通知失败=best-effort 不阻断 | AgentTeams `task-completion-notification.md`（P0 ordering）；`server.py` submit 顺序 | 与 helloai outbox 现状需核对「产物先落盘再发通知」 |
| X2 | **幂等键必须 status-scoped**：否则「先报 BLOCKED 后报 SUCCESS」被复用分支静默吞掉 | AgentTeams `submit-<task-id>-<status>` | helloai `agentOutboxService.createEvent(snapshot, newStatus)` 需核对去重键 |
| X3 | **审计事件必须闭合 schema——不允许任何自由文本字段** | AgentTeams `capability-foundation.md`（TestEventJSONHasClosedSchema pin） | helloai 只有凭据域审计，无通用审计 |
| X4 | **append-only 存储必须 keyset 分页**（offset 并发追加下漂移） | AgentTeams `audit-events-api.md` | helloai timeline/事件表若用 offset 需核对 |
| X5 | **能力不可用 ⇒ 从工具列表摘除，＞在描述里写「不要调用」** | Octop `hint.py:94-99` docstring | 即 §2 语义位的「按条件可用」 |
| X6 | **「禁用了」与「不具备」是两个事实，分开表达** | Octop `tool_catalog.py:10-19`（CRITICAL_TOOLS） | 不可关闭清单 + 条件可用 |
| X7 | **身份从「本次调用上下文」取，不依赖客户端参数/进程级注册表** | Octop `browser_profile.py:26-44`（fail-closed 覆写） | helloai 13 处 `requireAuthId` 已做；`_sessionId` 输入源是「多实例前置条件」 |
| X8 | **失败必须回叫，平台不替用户拉起未运行执行体** | Octop `team_manager.py:284` + `expert-teams.md:30` | **helloai 最优先缺口**：`ResilientDispatcher.doAssignNextFallback` 只 log.warn |
| X9 | **水位四判据**：一 marker 不得两语义 / 失败不推进 / 检测范围=推送范围 / 「扫描 0 B」≠「无 I/O」 | AgentTeams `issue-1107`（真实复现报告） | MinIO 附件同步、工作区同步直接适用 |
| X10 | **「全量 X」须按「执行工具的显式枚举」核对并回扫**（先 diff 再下结论） | 本会话复核方法论 | 已入工作记忆 |

### 4.2 工程基建（小成本、高收益）

| # | 项 | 来源 | helloai 落点 |
|---|---|---|---|
| X11 | **失败回叫闭环语义**：派工失败 ⇒ 必须产出一条面向用户的说明（timeline + 可读原因）定为不变量 | Octop `agent-interop-mailbox.md:148` | 不是逐点补日志，是定不变量 |
| X12 | **载荷旁路（数据面/控制面分离）**：`ToolResult` 双视图 modelView/uiPayload，全量落 MinIO（按内容哈希去重），摘要带 ref | Octop `octop_ui_offload.py:56-85` + `docs/octop-ui-payload-offload.md` | **唯一正在真实丢数据的地方**（`UpstreamAttachmentRenderer` 截断）——但注意：这属于执行结果链路，用户四项未含，建议单独立项 |
| X13 | **SSRF 出站 URL 校验**（含 DNS-rebinding pinning） | Octop `utils/ssrf_guard.py` | `WebPageFetchServiceImpl` + 未来 Connector/MCP 外联 |
| X14 | **首次运行锁定**（无用户 ⇒ 只放行 setup） | Octop `api/middleware/setup_lockdown.py:1-21` | 廉价安全默认 |
| X15 | **key parity 守卫**：事件码/错误码服务端单一来源 + 前后端 key 匹配测试 | Octop `AGENTS.md:255-295` | 治 `eventMeta.ts`/`sequenceFlow.ts`/`SubTaskDetail.vue` 三处登记漂移，靠门禁不靠人记 |
| X16 | **目录守卫 + 结构化拒绝码** | Octop `utils/host_dirs.py` | 若开放「用户指定工作目录」，拒绝原因可读 |

### 4.3 明确不抄（与报告 C 清单一致）

- Octop 单进程/双后端/进程内调度（C1-C3）——路线分歧；
- AgentTeams CRD/Helm/leader-election/Matrix 房间/power level——helloai 是 Java 单集群 MQ 编排；
- CDP 逐帧直播、桌面/移动/语音交付面（C4/C7）——定位无关；
- **外部 agent 对接**（用户裁定：可借鉴内容有限，两者未实现真 A2A）。

---

## 五、与两份报告一致性说明（用户要求补充「报告与我观点一致的」）

本规划中 **§2 语义位、§4.1 判据 X1-X10、§4.2 X11-X16** 全部是**两份报告结论 + 本次独立源码复核双重验证一致**的内容：

- **Octop 报告 §2.6/§2.8**（能力摘除式治理 + CRITICAL_TOOLS）→ 本规划 §2 语义位与 X5/X6；
- **Octop 报告 §2.3**（失败必须回叫）→ X8/X11；
- **Octop 报告 A1**（载荷旁路）→ X12（用户四项未含，单独标注）；
- **AgentTeams 报告 §4.3**（sync-first + status-scoped 幂等）→ X1/X2；
- **AgentTeams 报告 §4.5**（审计闭合 schema + keyset 分页）→ X3/X4；
- **AgentTeams 报告 §4.6**（水位四判据）→ X9；
- **Octop 报告 §8.1/8.3/8.4**（key parity / 首次运行锁定 / 目录守卫）→ X13-X16；
- **Octop 报告 §3.2/3.3 + AgentTeams 报告 §4.8**（沙箱 spec + 探针 + 能力位）→ 本规划第 3 步；
- **Octop 报告 §6.4**（备份/恢复）→ 本规划第 2 步 B2；
- **Octop 报告 §4.2**（技能 frontmatter + 目录扫描 + 摄入闸门 + origin/locked）→ 本规划第 1 步 K1-K4；
- **Octop 报告 §2.5/§2.7**（引用 marker + 注入预算）→ 本规划第 4 步 R1-R3。

**唯一重大分歧**仍是 **Fork/Return**：两份报告均未专门覆盖（AgentTeams 报告只提 `REVISION_NEEDED` 状态枚举，Octop 报告未读 `fork.py`）——本规划第 2 步按用户裁定补齐。

---

## 六、下一步建议

1. **确认本规划的优先级与拆法**：尤其 RM9 拆 `McpToolServiceImpl` 是否采用「先加 ToolRegistry 语义位、再拆类」的顺序（用户裁定「拆法须与用户确认」）；
2. **K1 可立即开工**：给 4 个 `eng-*.md` 补 frontmatter 是零风险增量（保留正文、回归不变），不依赖任何后续；
3. **X8 失败回叫**可独立先做（`ResilientDispatcher` 一行级改动，用户可感知）；
4. RAG 后置确认后，`Sandbox_Provider.md` 与 `Skill_Capability.md` 两个既有设计的 Status 可更新为「已纳入四能力规划」。
