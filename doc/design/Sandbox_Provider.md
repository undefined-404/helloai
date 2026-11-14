# Sandbox Provider

> **Status: `Partial`** · **Scope: 执行环境隔离 Provider 契约（文件 / 网络 / 进程 / 资源 / 凭证五边界）；OpenSandbox Provider 已落地并被执行链消费，S-10 危险命令守卫未实现** · **差距锚点：`G-005`**
>
> 最后更新：2026-11-13（补「任务级沙箱与证据引用」「沙箱运行时接口与隔离」两节，`SL-6` 真机收口；2026-11-06 从归档计划 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md` 并入 S-1~S-9 落地事实与 SKX-9 真机证据；状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界与落地事实，不复述任务进度）

## 目标

将 Agent 执行环境从 AgentRuntime 中解耦。

```text
AgentRuntime
     ↓
SandboxProvider
     ↓
Execution Environment
```

## Provider 方向

```text
Local
Docker
Remote
K8s
```

## 当前状态

已有：

```text
ExecutionEnvironment
ExecutionEnvironmentProvider
RemoteAgent
LocalProcess
```

它们首先解决的是**执行环境抽象**，不能直接等价描述成“完整安全沙箱”。

## 当前落地事实（2026-11-06）

- **Provider 实现**：`OpenSandboxProvider` 已按 `SandboxProvider` 契约落地（声明式 spec、诚实五边界策略、探针、部署拓扑）；`S-1`~`S-9` 全部完成，实施记录归档于 `doc/archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md`。
- **执行链消费**：`RuntimeTurnExecutor` 通过 `SandboxProvider.resolve` 使用沙箱；`SKX-9` 真机实测模型在工具循环中自主触发 `internal_run_skill`，于开沙箱容器内执行（`G-005` 条件②命中）。
- **未实现**：`S-10` 危险命令守卫（登记不启动）；`G-005` 条件①「平台增加碰宿主工具」、条件③「平台自持浏览器」仍未命中。
- **诚实边界**：Provider 可用 ≠ `G-005` 目标达成；五边界中的网络 / 资源 / 凭证策略与生产化排期仍以差距表 `G-005` 的剩余动作与后续专项计划为准。

## 任务级沙箱与证据引用（`G-032`）

**作用域**：沙箱按 **任务** 复用（`task:{taskId}`），不是按子任务、也不是按 turn。同一任务的全部子任务共享一个 `/workspace`：上游写下的文件下游可读、返工轮上一轮的产物可读；容器在任务终态（`DONE` / `CANCELLED` / 删除）时销毁，turn 结束不释放。进程重启会丢失槽池（文件随容器仍在，但下一次访问按新槽重建）。

**寿命口径**：`keep-alive-seconds`（默认 8h）是 `renew` 的**绝对**过期目标；`idle-ttl-seconds`（默认 900s）只用于「无终态释放点」的槽（会话技能 `scope:{id}`、`scope=subtask` 回滚槽）判活。任务级槽的空闲阈值取 `keep-alive`——等人工审查、等下游都不该丢工作区。命令超时（`exitCode=124`/`-1`）不销毁容器，只记 `sandbox_command_timed_out`；只有传输层异常才丢弃重建。

**证据引用**：台账每条沙箱调用由平台生成 `callId`（`c<seq>`），并记录 `origin`（`executor` / `reviewer` / `platform`）与 `round`（执行轮次）。自审证据写引用（`{"kind":"sandbox_exec","callId":"c17"}`）而不是手抄命令；证据闸只对被引用的条目判成功，`origin` 非执行者只记观测告警。每子任务台账上限 200 条，模型可用 `internal_list_sandbox_calls` 自查坐标。

**真机边界**：`/workspace` 是**工作区**，不是交付物通道；平台登记交付物仍走对话产物 / 附件。沙箱文件在任务终态随容器消失，需要留存的产物必须在终态前物化。

## 沙箱运行时接口与隔离（`G-032`）

**句柄化**：调用方构造 `WorkspaceRef`（`kind` / `ownerId` / `contextId` / `specFingerprint` / `isolation` / `access` / `lifetime`），运行时不再猜「这个 `Long` 是子任务、会话还是 `null`」。构造期校验两条不变式：`ONESHOT` ⇔ `EPHEMERAL_VERIFY` 必须同时出现；每种 `Kind` 只有一种合法 `Lifetime`。

**接口**：`SandboxRuntime` 六方法——`exec` / `readFile` / `stat`（只读探测不建沙箱）/ `writeFile`（与命令共用槽锁）/ `listFiles`（SDK 目录只读通道，不用 `find` 污染台账）/ `release`。实现按动词族分片：`OpenSandboxRuntime`（执行）/ `SandboxWorkspaceRegistry`（槽池与寿命）/ `SandboxFactory`（建销）/ `SandboxObservation`（记账）/ `SkillPackageInstaller`（包注入）/ `DeliverableReader`（选交付物）。**唯一查库点**是 `SandboxRuntimeAdapter`，架构守卫硬拦截 `runtime/sandbox/impl` 反向依赖业务层。

**隔离**：执行者与审核者命中**同一任务级工作区的两个实例**，槽键含 `Access`（`READ_WRITE` / `READ_ONLY`）。`/workspace` 卷化后，执行槽挂 `readOnly=false`、审核槽挂 `readOnly=true`（容器层 EROFS，`/tmp` 仍可写）；一次性校验走 `EPHEMERAL_VERIFY`，创建→执行→销毁、不进槽池。开启卷需要两侧同时配置：应用侧 `volume-enabled` + 卷源，sandbox server 侧 `storage.allowed_host_paths` 放行宿主路径。

**角色不入沙箱**：沙箱不承载 Agent 角色、不按角色换镜像；「审核隔离」是同一份工作区的只读视图，不是另一个沙箱世界——跨文档核验必须能读到执行侧产物。

## 真正 Sandbox 的边界

```text
Filesystem
Network
Process
Resource
Credential
```

## 实施原则

1. 先稳定 Provider Contract；
2. Runtime 不直接绑定 Docker/K8s API；
3. 具体隔离策略由 Provider 实现；
4. 安全沙箱的引入必须有权限与资源策略，而不是只增加一个 Docker 类。
