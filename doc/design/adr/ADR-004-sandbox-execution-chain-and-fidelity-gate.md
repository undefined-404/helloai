# ADR-004：沙箱执行链与机械判定接入（S-7/S-8/S-9 定稿）

| 项目       | 内容 |
| -------- | ---- |
| **编号**   | ADR-004 |
| **状态**   | Accepted（2026-10-17，S-7/S-8/S-9 落地） |
| **日期**   | 2026-10-17 |
| **关联文档** | [`plan/HelloAI 沙箱执行链设计方案（2026-10-17）.md`](../../plan/HelloAI%20沙箱执行链设计方案（2026-10-17）.md)（用户裁定方案 C）、[`HelloAI 项目基线文档.md`](../../HelloAI%20项目基线文档.md) §7、`ADR-003 §6`（触发条件 ①）、`LOG-20261017-013` |
| **决策类型** | 沙箱执行链形态 + 机械判定接入（方案 C 全链路 + 判定接入） |

> **本 ADR 定「沙箱从契约变执行链」的口径**：执行通道形态 / 工具接线 / 判定接入点 /
> 纯静态 checker 不动 / 默认关闭 / G-005 边界不变。`ADR-003 §6 触发条件 ①`（沙箱内执行
> agent 工具链）在本 ADR 部分命中——但**仅限「验证脚本」受控通道**，非「agent 工具链整体
> 进沙箱」；后者的完整评估（Credential Vault 双层隔离等）仍留待 ADR-003 条件。

---

## 1. 背景与问题

`ADR-003` 完成 OpenSandbox 的部署拓扑 + 契约落地（S-1~S-6），但沙箱**只是「契约已备」**：
`RuntimeTurnExecutor.observeSandbox` 只打日志，内部 LLM 自检 / Reviewer 验证脚本**没有任何执行通道**——
「验证」只能是模型把「已跑通 / 无错误」写进报告的**声称**，平台无从核实。

用户指令（2026-10-17）：调研开源沙箱（octop-harness / Octop / DSH）+ 知名产品（Claude Code /
Codex / WorkBuddy）后，**把沙箱真正接成执行通道**，让沙箱参与「自检 / Reviewer 验证脚本」——
这是开源项目与知名产品的基础功能档。用户裁定：**方案 C = 全链路 + 判定接入**。

## 2. 决策一：执行通道 = `SandboxExecutionService`（S-7，短 TTL 独立 scope）

**决策**：新增执行通道接口 + 实现（`OpenSandboxExecutionService`），返回统一
`ExecuteResponse(output, exitCode, truncated)`：

- **每次执行创建独立短命沙箱**（短 TTL 900s）并 finally **无条件 `kill()`**（对齐探针语义：
  SDK `close()` 不删远端沙箱，`kill()` 才删）——不跨调用复用，防「上一个命令的残留文件影响下一个」；
- **超时钳制**：命令默认 30s、上限 300s（服务端超时返回 `exitCode=124`）；
- **输出截断**：100KB 上限 + `truncated=true`；
- **失败也结构化返回**：SDK 异常 ⇒ `ExecuteResponse("<err>", -1, false)`，不抛异常
  （与 `SandboxProvider.resolve` 的 null 语义一致的容错哲学）。

**理由**：对齐 octop-harness 的 `ExecuteResponse` 三字段与 `sandbox_scope` 隔离语义；
统一出口让 LLM / Reviewer / 机械判定三消费方拿到同一事实。

## 3. 决策二：工具接线 = `internal_run_in_sandbox`（S-8，fail-closed）

**决策**：

- `InternalLlmExecutorTools` 新增 `internal_run_in_sandbox(command, timeoutSeconds)`，入口
  `requireInternalChain()` 闸门（仅内部执行链可调，外部 MCP 不可见、不可执行）；
- `INTERNAL_LLM_TOOLS` 授权集追加工具名（`AgentMcpServerServiceImpl`）——内部 LLM 与
  Reviewer（`subtask_review` 已进 `businessLoopScenes` 白名单）在循环内可见；
- **条件可用（fail-closed）**：`SandboxExecutionToolContributor`（`ToolCallbackContributor`）
  声明 `toolAvailability() = properties.isEnabled()`——沙箱未启用（默认）⇒ 工具从模型可见列表
  **摘除**，不静默降级（对齐 Codex `failIfUnavailable` 硬门禁 / Claude Code `sandbox.failIfUnavailable`）；
- `subtask-review.md` 核验 Prompt 追加「脚本可经沙箱验证」指引（轨道 A 第 4 条）。

**理由**：`internal_` 前缀 + `requireInternalChain()` 是既有 REF-8.1 强制闸门，天然受执行链绑定
保护；fail-closed 摘除防「模型调用一个必然返回 disabled 的工具却无从判断原因」。

## 4. 决策三：机械判定接入 = `FinalReportFidelityGate` 扩展点（S-9，纯静态 checker 不动）

**决策**：

- `FinalReportFidelityChecker`（纯静态、零 Spring 依赖）**原样保持静态**，追加纯静态方法
  `checkScriptExecutability(scriptText, execution)`——脚本正文 + 执行结果 → 确定性判定；
- `FinalReportFidelityGate`（Spring Bean，`@Autowired` 注入 `SandboxExecutionService`，无参构造
  = 未接线）在 `evaluate()` 中检测「报告含可执行语言围栏（sh/bash/python/py/ps1/powershell）」
  时提取脚本正文（≤3 个）送沙箱实测，结果交 checker 静态判定；
- **判定语义（沙箱是证据提供者不是判定者）**：
  - 证据有效（exitCode=0 且未截断）⇒ 不新增违规（脚本确实跑通，作为 Reviewer 一手证据）；
  - 证据不足 / 执行失败（沙箱未启用 / 不可达 / exitCode!=0 / 截断）⇒ **HARD 机械驳回**
    （同 `shell_reference` 语义——「报告声明脚本可执行却拿不出可执行证据」不给 LLM 放过机会）；
  - exitCode=0 不自动判通过（可能测的是无关属性），exitCode!=0 不自动驳回（可能命令写错）——
    内容达标与否仍由 Reviewer / LLM 判定。

**理由**：沿用 RM12 裁定「纯静态工具保持静态、由 Gate 持有」——checker 零 Spring 依赖不破坏
既有 3A 判定主链；沙箱执行经 Gate 构造器注入，无参构造 = 未接线 = 行为逐字不变。

## 5. 决策四：默认关闭 + G-005 边界不变

**决策**：

- `helloai.sandbox.execution.enabled` 默认 `false`（对齐 REF-6.13 新能力默认关闭）；
  开启后才注入 `internal_run_in_sandbox` 工具；
- **`G-005` 三个触发条件仍均未命中**（① 不碰宿主工具——执行在 OpenSandbox 容器内；
  ② 不执行技能包正文——工具只执行现场写的命令/脚本；③ 不改浏览器）⇒ 整体仍 DEFERRED 不排期，
  仅补注「沙箱执行链已接线作为受控验证通道」。

**理由**：沙箱执行链是「受控验证通道」而非「平台增加碰宿主能力」；技能包被执行是 `G-005`
条件②的独立议题（`REF-1.6` 只做安装/解析/校验，本 ADR 不越界）。

## 6. 触发重新评估的条件

```text
① 沙箱执行链被真实业务高频使用（误用面显现）   ⇒ 启动 S-10 危险命令守卫（block/warn/require_approval 三态，后置登记不启动）
② 「技能包正文要被执行」出现具体场景            ⇒ 独立评估（G-005 条件②，含 Credential Vault 双层隔离）
③ 需要沙箱内访问白名单外网络                    ⇒ 评估 egress 白名单扩展 + 审批流
```

## 7. 后果

- **正面**：沙箱从「契约已备、仅打日志」变成「执行链已接、真正执行验证」——自检证据围栏
  从声称变实证，Reviewer 拿一手证据，机械层对「声明脚本可执行却无证据」硬驳回。
- **代价**：① 沙箱执行可能被 LLM 滥用（无脑调 / 写恶意命令）——已用 fail-closed 摘除 +
  `requireInternalChain()` 闸门 + 容器隔离（默认禁网 / 零凭据 / 短 TTL）+ 超时钳制 + 输出截断收敛，
  危险命令守卫登记 S-10 后置；② 判定接入评审主链的回归面——已用「纯静态 checker 不动 +
  Gate 扩展点注入」缓解，无脚本交付物时行为零变化（单测钉死）。
