# Sandbox Provider

> 状态：ACTIVE / P1

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
