# Skill Capability Package

> 状态：ACTIVE / P1

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
