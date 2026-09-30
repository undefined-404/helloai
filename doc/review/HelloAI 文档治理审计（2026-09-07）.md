# HelloAI 文档治理审计

> 日期：2026-09-07

## 1. 结论

当前文档体系的主要问题不是缺少设计，而是“当前事实、已完成方案、未来目标、外部参考、历史过程”混在一起。

因此本次不是简单润色，而是一次**文档架构重构**。

## 2. 处置结果

### 重构为权威文档

```text
HelloAI 项目基线文档.md
HelloAI 目标架构.md
HelloAI 实现差距表.md
HelloAI 重构实施计划.md
```

### 新增专项设计

```text
design/Agent_Event_Stream.md
design/Agent_Runtime.md
design/Skill_Capability.md
design/Sandbox_Provider.md
```

### 降级为历史参考

原 Phase0 / Phase1 / Phase2 专项方案，以及长期思路、架构参考、调度解耦等文档均不再作为当前 Roadmap 入口。

## 3. 本次架构纠偏

### Event Stream
不是重新做一套业务状态机，而是统一执行事实。

### Skill
不是把 Markdown Prompt 全部推倒，而是在兼容现状的基础上增加 Capability Package 元数据。

### Sandbox
当前 Environment Provider 不是完整安全沙箱；安全隔离属于后续能力。

### Dual Executor
只是迁移方法，不是长期双轨架构。

### Harness
作为 Runtime 参考，而不是 HelloAI 产品目标。

## 4. 后续开发读取顺序

```text
README
→ Current Baseline
→ Target Architecture
→ Gap
→ Implementation Plan
→ Specialized Design
→ Code
```
