# Sandbox Provider

> **Status: `Partial`** · **Scope: 执行环境隔离 Provider 契约（文件 / 网络 / 进程 / 资源 / 凭证五边界）；OpenSandbox Provider 已落地并被执行链消费，S-10 危险命令守卫未实现** · **差距锚点：`G-005`**
>
> 最后更新：2026-11-06（从归档计划 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md` 并入 S-1~S-9 落地事实与 SKX-9 真机证据；状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界与落地事实，不复述任务进度）

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
