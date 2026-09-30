# Sandbox Provider

> **Status: `Planned`** · **Scope: 执行环境隔离 Provider 契约（文件 / 网络 / 进程 / 资源 / 凭证五边界）；当前仅契约，无真实隔离** · **差距锚点：`G-005`**
>
> 最后更新：2026-09-30（**Document V2.1 治理**：补 Status / Scope 头。状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界，不复述进度）

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
