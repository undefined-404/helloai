> **状态：SUPERSEDED / V1**。

# HelloAI 实现差距表

> **文档版本：V2**
>
> 本文档只记录 HelloAI **当前仍然存在的实现差距、风险、待决策事项和未完整交付能力**。
>
> 已完成事项不在本文档长期保留。
>
> 历史实现过程请查看 `doc/log/`。
>
> 具体设计请查看 `doc/design/`。
>
> 最终事实以当前代码、数据库及可复现实验为准。
>
> 最后更新：2026-09-06

---

# 1. 文档定位

本文档用于回答：

```text
现在还缺什么？
为什么说它还缺？
优先级是什么？
下一步应该做什么？
如何验证完成？
```

本文档不负责：

- 记录历史版本
- 保存已经完成的需求
- 记录完整代码修改
- 保存详细设计方案
- 保存开发流水账
- 替代项目基线
- 替代 Issue / Git

---

# 2. 状态定义

| 状态 | 含义 |
|---|---|
| `TODO` | 尚未开始 |
| `DESIGNING` | 正在设计 |
| `DOING` | 开发中 |
| `VERIFYING` | 已开发，等待验证 |
| `BLOCKED` | 存在明确阻塞 |
| `PARTIAL` | 已有主路径，但能力不完整 |
| `DONE` | 已完成并验证 |
| `WONTFIX` | 当前明确不做 |

其中：

> `DONE` 项原则上应从当前差距表移除，仅在 Log 中保留历史记录。

---

# 3. 优先级定义

| 优先级 | 含义 |
|---|---|
| P0 | 阻断主链路 / 严重数据风险 |
| P1 | 核心能力缺失 / 明显可靠性问题 |
| P2 | 重要能力增强 |
| P3 | 优化项 / 体验项 |

---

# 4. 当前总体结论

当前 HelloAI 已经形成：

```text
Planner
→ Task
→ SubTask
→ Agent
→ Execution
→ Review
→ Artifact
```

的核心闭环。

当前主要差距集中在：

- 功能差距批次（N-001~N-012）已全部按完成规则移除
- 架构演进缺口（2026-09-07 回填 N-013~N-019）：Harness 内部抽象（执行循环 / 工具回路 / Skill 结构化）、Fleet 能力化选人、Governance 治理面（Event Stream 消费侧 / Quality Gate）、待决断项（Sandbox）、远期形态（Workflow Engine 增强）

---

# 5. 当前 Gap 总览

| ID | 能力 | 状态 | 优先级 | Gap |
|---|---|---|---|---|
| N-013 | Harness 执行循环（AgentLoop / ToolExecutor） | TODO | P1 | 平台内执行为单发 LLM 调用，无工具调用回路；八件套内部完成度 3/8 |
| N-014 | Skill 元数据结构化（Capability Package 第一步） | TODO | P2 | KNOWN_SPECS 为标签→文本，无 version / requiredTools / Schema |
| N-015 | Sandbox 承诺决断（Docker 立项或 WONTFIX） | TODO | P3 | 「DockerSandbox 推迟 P2」无回流；现状 ExecutionEnvironment 为场所标签非沙箱 |
| N-016 | Event Stream 消费侧（Replay / Fork / Audit） | TODO | P2 | 事件写侧闭环，消费侧零实现 |
| N-017 | Agent Fleet 能力化选人 | TODO | P2 | agent.role 单值硬过滤，选人无 capability 打分 |
| N-018 | Quality Gate（Rule + Test + LLM） | TODO | P2 | 审查链闭环，无统一验收决策门 |
| N-019 | Workflow Engine 增强（DSL / 运行期引擎 / 动态分支） | TODO | P3 | 模板物化已交付；运行期引擎与动态分支为零 |

---

# 6. 当前 Gap 明细（2026-09-07 回填）

> 来源：架构对齐核查——对照 `doc/design/HelloAI_架构改造长期思路.md` 主线 + 全库代码盘点。本批为设计文档内「推迟 / 降级 / 划远期」承诺形成的孤儿项与长期主线缺口，非功能缺失批次；事实以代码为准。

## N-013 Harness 执行循环（AgentLoop / ToolExecutor）

- 状态：TODO / 优先级：P1
- 当前状态：AgentRuntime 契约层已收口（AgentContext / AgentRuntime / LegacyExecutorAdapter 唯一执行入口），但 runtime 包无 AgentLoop / ToolExecutor 类；ApiKeyAgentExecutor.execute 为单发 LLM 调用（无工具调用循环）；完整编排仍在 SubTaskExecutionServiceImpl 旧链（约 790 行）
- 差距：平台内执行中无法自主调用工具（工具仅为 ToolRegistry 元数据注入 prompt 的描述文本，无执行回路）；按八件套口径（Context / SkillResolver / ToolRegistry / ToolExecutor / AgentLoop / Session / Sandbox / EventRecorder）内部完成度 3/8（落地 3、缩水 3、缺失 2）
- 目标：从 executeOnce 旧链内逐步提取组件——先 ToolExecutor、后 AgentLoop；LegacyExecutorAdapter 继续作为接缝，不建第二执行链（演进，不推倒）
- 下一步：「Phase 1.5 Harness 深化」设计预研（工具执行协议口径：哪些工具可执行、结果如何回写、事件如何埋点）
- 验证方式：单发执行行为回归不变 + 工具调用回路定向单测 + 事件埋点对账

## N-014 Skill 元数据结构化

- 状态：TODO / 优先级：P2
- 当前状态：KNOWN_SPECS 为 `Map<String, String>`（标签 → markdown 文本）；资源按角色目录组织（skills/planner、executor、reviewer）；CODE_STYLE §50.7 已登记为显式降级（不建平行 Registry）
- 差距：无 version / requiredTools / inputSchema / outputSchema，Skill 未达 Capability Package 标准
- 目标：KNOWN_SPECS 结构化（name / version / requiredTools / description），markdown 保留为 instructions 载体，向后兼容增量升级
- 下一步：与 N-013 联动设计（requiredTools 依赖工具回路存在才有意义）
- 验证方式：既有 skill resolve 行为回归 + 结构化元数据解析单测

## N-015 Sandbox 承诺决断

- 状态：TODO / 优先级：P3（待拍板：Docker 立项或 WONTFIX 二选一）
- 当前状态：全库零 Sandbox 代码；Phase 1 Step 4 交付的 ExecutionEnvironment 是「执行场所标签」（remote-agent / local-process），非沙箱；其 javadoc 自述「DockerSandbox 推迟 P2、K8sSandbox 推迟 P3」
- 差距：「DockerSandbox 推迟 P2」承诺无回流、无人认领；场所标签占位易被误读为沙箱已交付
- 目标：显式决策并落档（立项进 design 预研，或 WONTFIX 登记理由），消除挂空
- 下一步：用户拍板
- 验证方式：决策落档（本表状态变更 + 对应设计文档口径同步）

## N-016 Event Stream 消费侧（Replay / Fork / Audit）

- 状态：TODO / 优先级：P2
- 当前状态：写侧闭环（agent_event 三层 + AgentEventRecorder write-only + step 槽位）；全库无任何 Replay / Fork / 审计工具类
- 差距：Event Stream 的价值面（Audit / Replay / Fork 等）仅 Timeline / Review 兑现，治理面为零
- 目标：消费侧最小闭环（只读 Replay 回放 + 审计查询），不建第二控制面
- 下一步：随 Governance 批次设计预研
- 验证方式：指定 run 的 Turn / Step 序列回放断言

## N-017 Agent Fleet 能力化选人

- 状态：TODO / 优先级：P2
- 当前状态：agent.role 为 V30 CHECK 单值列（PLANNER / EXECUTOR / REVIEWER 三选一）；AgentSelector 主链 listByRole 硬过滤（role=null 才走 listActive）；比较器仅 dutyRank / accessTypeRank / qualityRank / score
- 差距：无 capabilityMatch / health / latency / cost / historicalSuccessRate 打分；同一执行体多角色须注册多条 agent 记录（inner-kimi-2.6-executor 类命名的由来）
- 目标：role 降级为多值标签（能力属性之一），选人主链改 capability + skills 打分；业务流程角色语义（Planner 拆解 / Reviewer 审查）哪些保留需口径设计
- 下一步：口径设计（哪些业务流程跟着松绑、哪些保持角色语义）
- 验证方式：capability 选人定向单测 + 既有 role 槽位派发全量回归

## N-018 Quality Gate（Rule + Test + LLM）

- 状态：TODO / 优先级：P2
- 当前状态：审查链已闭环（ReviewFact / 双审 / 核验熔断转死信）；无 Rule Check / Test Check / LLM Review 汇聚的统一验收决策抽象
- 差距：无 PASS / REWORK / HUMAN_REVIEW / BLOCK 统一决策口径
- 目标：在现有审查链上叠加 Quality Gate 决策层，不建第二审查链
- 下一步：随 Governance 批次设计预研
- 验证方式：三类检查汇聚决策单测 + 既有审查回归

## N-019 Workflow Engine 增强

- 状态：TODO / 优先级：P3（远期）
- 当前状态：C1 已交付「模板实例化生成器」（一次性物化，不建第二运行时）；引擎六职责（依赖 / 并发 / 重试 / 超时 / 补偿 / 取消）散装于收敛层（depends_on ready / SubTaskTimeoutTask / ExecutionCompensationTask / LeaseReconcilerTask / 熔断重派）
- 差距：无 DSL、无运行期逐节点解释引擎、无 LLM 动态分支
- 目标：长期思路 Phase 4 形态——Planner → Workflow DSL → Engine，LLM 仅在特定节点动态分支，保留人工可预测性
- 下一步：远期立项时另出设计预研，当前不排期
- 验证方式：立项时定义

---

# 7. Gap 新增规则

新增 Gap 必须至少包含：

```text
ID
能力
状态
优先级
当前状态
差距
目标
下一步
验证方式
```

禁止新增：

- 纯想法
- 模糊愿望
- 已完成事项
- 历史记录
- 完整代码
- 大段技术方案

如果还没有形成明确 Gap：

> 不要进入本文档。

---

# 8. Gap 完成规则

当一个 Gap 满足：

```text
代码完成
+
测试完成
+
关键路径验证完成
```

则：

```text
Gap → DONE
```

随后从当前表移除。

历史信息写入：

```text
doc/log/
```

如果产生长期有效的架构知识：

```text
doc/design/
```

---

# 9. Gap 与 Design 的关系

推荐：

```text
Gap（如 N-013）
  ↓
design/xxx.md
  ↓
代码实现
  ↓
E2E
  ↓
log/2026-xx.md
```

而不是：

```text
Gap
  ↓
不断向表格追加 500 行实现细节
```

---

# 10. Gap 与 Log 的关系

Gap：

> 现在还有什么问题？

Log：

> 这个问题过去怎么处理过？

因此：

```text
Gap = 当前状态
Log = 历史状态
```

两者不能混用。

---

# 11. 当前 Gap 使用原则

AI Agent 读取本文件时：

1. 可以用于判断当前缺失能力。
2. 可以用于判断项目正在解决什么问题。
3. 不得据此推断具体代码结构。
4. 不得把 Gap 描述当成已经存在的代码。
5. 修改代码前必须检查实际代码。

---

# 12. 最终原则

实现差距表不是：

> “HelloAI 做过什么的百科全书”。

而是：

> **HelloAI 当前待解决问题的实时索引。**

因此：

```text
完成 → 移除
失效 → 移除
改为设计 → 链接 design/
进入开发 → 更新状态
```

保持表格小、准、当前。

---