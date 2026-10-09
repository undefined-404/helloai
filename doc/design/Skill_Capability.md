# Skill Capability Package

> **Status: `Partial`** · **Scope: Skill Capability Package 元数据 + 生命周期（Discover→Resolve→Load→Execute→Validate）；Instructions 结构化未动** · **差距锚点：`G-004`**
>
> 最后更新：2026-10-09（**元数据事实源迁移（REF-1.1/1.2）**：`SkillPackage` 元数据由 Java `KNOWN_SPECS` 编译期硬编码改为 **classpath `skills/plugins/*.md` 的 YAML frontmatter + 目录扫描**。状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界，不复述进度）
>
> **元数据契约（2026-10-09 起）**：
>
> ```text
> frontmatter 已知键白名单（8 个，逐字匹配）：
>   name / version / description / requiredTools / dependencies
>   / inputSchema / outputSchema / validationRules
> 必填：name（必须 == 文件名去 .md）/ version（三段式，须加引号）/ description
> 禁止：fileName —— 由目录扫描推导（写进 frontmatter 即第二事实源，判 corrupt）
> 空值语义：[] 与「省略」等价；{} 与「省略」等价
> 未知键 / 重复键 / 围栏未闭合 → corrupt（显式报错，不静默跳过）
> 目录条目排序：name 升序（确定性；不依赖文件系统返回顺序）
> ```
>
> **Discover 面的口径诚实边界**：本轮达成「**新增技能 = 丢一个 md，零改 Java 代码**」；「零发版」需外部技能目录（与摄入能力 REF-1.5/1.6 同批），**未达成**。

## 当前形态

```text
requiredSkills
    ↓
resolve
    ↓
resolvedSpecs
    ↓
instructions / context
```

当前主要能力仍然以 Markdown / instructions 为载体。

## 目标形态

```text
Skill Package
├── Name
├── Version
├── Description
├── Instructions
├── RequiredTools
├── Dependencies
├── InputSchema
├── OutputSchema
└── ValidationRules
```

## 原则

- Markdown 可以继续作为 Instructions 载体；
- Skill 与 Role 解耦；
- Skill 不直接拥有全局调度职责；
- Tool 依赖必须可声明；
- 先兼容现有 Skill，再逐步增加结构化元数据；
- 不因为 Capability Package 而建立第二套运行时。

## 生命周期

```text
Discover
→ Resolve
→ Load
→ Execute
→ Validate
```
