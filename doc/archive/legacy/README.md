# archive/legacy/ — 旧文档体系快照

> **状态：FROZEN / 只读归档**
>
> 本目录存放 V2 文档体系重组（2026-09）之前的**旧事实源与旧体系文档**，即「已被新版本文档整体取代」的内容。

## 存放什么

| 类别 | 文件 |
|---|---|
| V1 时代事实源快照 | `V1_HelloAI 项目基线文档.md`、`V1_HelloAI 实现差距表.md`、`V1_HelloAI 迭代执行记录.md`、`V1_README.md`、`HelloAI_项目基线文档_V1.md`、`HelloAI_实现差距表_V1.md`、`HelloAI_迭代执行记录_V1.md` |
| AgentHub 时代 | `HelloAI_agenthub.md`、`helloai_agenthub_complete.md`、`agent_communication_architecture_analysis.md` |
| 已执行的迁移 / 重构清单 | `HelloAI_core结构重构执行清单.md`、`V23_字段命名规范化迁移与同步清单.md` |
| 旧链路分析 / 能力矩阵 | `HelloAI_当前能力确认矩阵.md`、`HelloAI_执行链路架构分析.md`、`HelloAI_门铃通知通道设计.md` |

## 准入规则

1. 只收**已被新版本文档整体取代**的文档 —— 判据：权威位置已存在同名新版本，或其描述的事实已完全过期。
2. **未被取代、仍有技术参考价值**的历史分析 → `reference/`，不放本目录。
3. `V1_*` 与 `*_V1.md` 为**历史快照**，其结论不得直接用于当前判断。

## 纪律

- **只读冻结**，禁止回填内容、禁止当作当前事实源。
- 引用本目录内容作论据时，必须标注其为**历史资料**并复核当前代码。

---

上级索引：`doc/README.md` §3 ｜ 分类规则：`doc/文档体系分类与治理规则.md` §3.9
