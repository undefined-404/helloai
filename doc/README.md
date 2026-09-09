# HelloAI 文档体系说明

> **文档治理版本：V2**
>
> 本目录采用“当前事实 → 目标架构 → 演进差距 → 实施计划 → 架构决策/日志”的单一主线。
>
> **权威优先级：代码/可验证事实 > 项目基线 > 目标架构 > 实现差距 > 实施计划 > 历史归档。**

## 1. 权威文档

| 文档 | 职责 | 当前开发是否直接依据 |
|---|---|---|
| `HelloAI 项目基线文档.md` | 当前代码和已落地能力 | ✅ |
| `HelloAI 目标架构.md` | 稳定目标边界 | ✅ |
| `HelloAI 实现差距表.md` | Current → Target 差距 | ✅ |
| `HelloAI 重构实施计划.md` | 当前实施顺序 | ✅ |
| `HelloAI_CODE_STYLE.md` | 代码与工程规范 | ✅ |
| `HelloAI_AI开发协作规约.md` | AI Agent 开发行为规约（生命周期 / 验证体系 / 红线） | ✅ |
| `log/HelloAI 架构变更记录.md` | 架构决策 | 参考 |

## 2. 专项设计

`design/` 只保留仍然具有独立技术价值且会影响后续实现的专项设计。

当前主线：

```text
design/
├── Agent_Event_Stream.md
├── Agent_Runtime.md
├── Skill_Capability.md
├── Sandbox_Provider.md
├── Planner_Capability_Awareness.md
├── Requirement_Package_Uncertainty.md
└── adr/
    └── ADR-001-run-turn-step-model.md
```

## 3. 历史文档

`archive/` 中的文档只用于追溯背景、已完成的设计和被替代的方案。

**禁止将 archive 中的文档重新当作当前 Roadmap 或架构入口。**

## 4. 读取顺序

AI 编程 Agent 修改代码前必须按以下顺序建立上下文：

```text
README.md
→ HelloAI 项目基线文档.md
→ HelloAI 目标架构.md
→ HelloAI 实现差距表.md
→ HelloAI 重构实施计划.md
→ 对应 design/ 专项文档
→ 代码
```

若历史文档与当前文档冲突，以当前文档和代码为准。
