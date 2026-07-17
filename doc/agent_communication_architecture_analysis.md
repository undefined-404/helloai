# AI Agent调度系统通信架构分析

> 归档说明
>
> - 本文件保留为早期通信架构对比草案，记录了 HelloAI 长连接唤醒机制的原始方案选型过程（方案 A SSE / 方案 B TCP+Bridge / 方案 C 混合），以及三类接入 AI（API Key 类 / 外部成熟 Agent / 网页版 AI）的现状评估。
> - 其中方案选型结论（SSE 首选 + 门铃负责唤醒不送 payload）已被 `doc/HelloAI_门铃通知通道设计.md` 继承并落到当前代码基线（`DoorbellRegistry` + `DoorbellService` + `AgentDoorbellController` + `DoorbellRinger`）。本文件后续不再作为扩展主参考。
> - 仓库引用（gitee.com/undefined_404/helloai）为早期外部仓库路径，与当前本地路径 `e:\yhzx\1027\helloai` 不一致，仅作为历史追溯。
> - 若本文件与当前代码、基线文档或 `doc/HelloAI_门铃通知通道设计.md` 存在冲突，优先以后者为准。

## 项目背景
- 仓库：https://gitee.com/undefined_404/helloai
- 核心问题：第三方AI Agent（Qoder/Trae/Claude Code/Codex）如何"第一时间"获知分配的任务

---

## 三类接入AI的现状

### 1. API Key类（内部注册的AI Agent）
- **状态**：已解决
- **机制**：内部消息通知 + 任务推送 + MQ消费
- **特点**：随时连接，平台可控，走标准调度流程

### 2. 外部成熟AI Agent产品（Qoder/Trae/Claude Code/Codex）
- **状态**：核心痛点
- **问题**：MCP解决了"能做什么"，但没解决"什么时候做"
- **本质矛盾**：平台主动推送任务 ↔ 第三方Agent被动等待用户输入
- **已有方案**：双心跳检测 + 任务编排兜底，但无法做到实时唤醒

### 3. 网页版AI网站（DeepSeek/Kimi/MiniMax/豆包/元宝等）
- **状态**：可放弃实时性
- **机制**：MCP浏览器工具输入内容 + 定时轮询抓取结果
- **备注**：有笨办法处理，非核心问题

---

## 核心问题定义

> MCP协议实现了能力暴露（Tool/Resource），但缺乏"服务端主动唤醒客户端"的标准机制。
> 第三方Agent作为被动消费方，不会主动轮询任务队列。

---

## 方案对比

### 方案A：SSE MCP Transport（推荐首选）
- **机制**：MCP Server-Sent Events，服务端通过HTTP长连接主动Push
- **流程**：任务到达 → SSE Push通知 → 客户端收到后主动调用get_task工具
- **优势**：标准协议，无需额外组件
- **局限**：依赖客户端是否支持SSE Transport（Claude Desktop支持，Claude Code/Trae可能仅stdio）

### 方案B：TCP长连接 + 自定义唤醒协议
- **机制**：Netty/WebSocket Notify Server + 本地TCP Client守护进程
- **架构**：
  ```
  调度平台(Java) → TCP Notify Server → 长连接 → 本地Bridge脚本(Python)
                                                     ↓
                                            调用本地MCP Client(stdio)
                                                     ↓
                                            触发Claude Code工具执行
  ```
- **定位**：TCP只做"门铃"（唤醒通知），MCP做"开门后的工具调用"
- **成本**：需用户安装Bridge守护进程（pip install）

### 方案C：混合架构（最终推荐）
```
调度平台
   ├── 内部Agent → MQ消费（现有，无需改动）
   ├── 外部Agent → 双通道接入：
   │       ├── SSE MCP（客户端支持时，实时推送）
   │       └── TCP Notify + MCP Bridge（降级兜底）
   └── 网页版AI → MCP浏览器插件 + 定时轮询（放弃实时）
```

---

## 关键结论

| 问题 | 结论 |
|---|---|
 TCP是否替代MCP？ | ❌ 不替代，互补关系 |
 TCP的角色？ | 通知通道（唤醒），MCP是能力接口（执行） |
 Claude Code能直接连TCP？ | ❌ 不能，需本地Bridge中转 |
 最优路径？ | 先验证SSE MCP可行性；不可行则补TCP Notify Daemon |

---

## 下一步行动建议

1. **调研客户端MCP Transport支持情况**
   - Claude Code：stdio only？SSE支持？
   - Qoder/Trae：官方文档确认MCP接入方式

2. **如果均为stdio**
   - 开发Python Bridge脚本（~100行，pip installable）
   - Bridge保持TCP到平台，收到通知后本地调用MCP Client

3. **文档补充**
   - 明确区分"调度中心"（Java服务）与"Agent接入层"（MCP/TCP Bridge）
   - 补充架构图，标注各层职责

---

## 待讨论问题

- 本地Bridge脚本是否应开源为独立pip包？
- TCP Notify Server是否复用现有Netty组件，还是新起服务？
- 是否需要考虑Agent离线时的消息堆积与重推策略？
