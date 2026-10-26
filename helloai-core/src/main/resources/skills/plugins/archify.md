---
# 平台技能目录项（`skills/plugins/*.md`）：只承载「何时用 / 怎么调 / 边界」，不复制执行包本身。
# 执行包在 skills/packages/archify/3.1.0/（122 文件 / 4.13MB），两级目录分工见计划 SKX-4。
name: archify
version: "3.1.0"
description: "架构图/流程图即代码：把结构化 JSON 渲染成离线单文件 HTML（10 种图表类型，含校验与回执）"
requiredTools:
  - internal_run_skill
  - internal_read_artifact
  - internal_export_artifact
# G-022 引用边**有意留空**：本技能需要「能在容器内执行命令」的工具，而工具维度已由
# requiredTools 表达。若在此声明 toolCalling，实测有 8 个模型 toolCalling=false 会被整批拒绝
# ——那是超出「消除越界」范围的新增拒绝面。同 eng-verification 的口径。
requiredBaseCapabilities: []
dependencies: []
inputSchema: {}
outputSchema:
  type: object
  properties:
    htmlPath:
      type: string
    sha256:
      type: string
    type:
      type: string
validationRules:
  - "图必须有可核验的输入：先落 JSON 再渲染，不凭记忆手写 HTML"
  - "渲染后必须 check 通过（provenance=current、issues 为空）才算产出"
  - "交付给用户的产物必须经 internal_export_artifact 导出，否则子任务结束即丢失"
  - "不声称跑过浏览器检查——平台不装 Chromium，browser-check/visual-check 不可用"
---

# archify 图表产出技能

> 版本：`3.1.0`（上游 `github.com/tt-a1i/archify`，commit `c21c31ff`，MIT）
> 定位：把「想画的结构」先写成结构化 JSON，再由技能 CLI 渲染成**离线单文件 HTML**——
> 无 CDN、无外链字体，双击可看、可归档、可随交付物一起发出。

## 执行速览（任务 `required_skills` 命中 `archify` 时按此执行）

1. **选类型**：从下方 10 种里选最贴近的一张图（不要为了一张图硬塞两种结构）。
2. **写输入 JSON**：按 `schemas/<type>.schema.json` 写，存到 `/workspace/out/<name>.json`。
3. **校验**：`node bin/archify.mjs validate <type> /workspace/out/<name>.json --json`
   —— 不通过就改 JSON，别改渲染参数。
4. **渲染**：`node bin/archify.mjs deliver <type> /workspace/out/<name>.json /workspace/out/<name>.html --json`
5. **自检**：`node bin/archify.mjs check /workspace/out/<name>.html --json`
   —— 要求 `provenance=current` 且 `issues` 为空。
6. **导出**：`internal_export_artifact(path="/workspace/out/<name>.html", fileName="<name>.html")`
   —— 不导出就等于没交付（沙箱随子任务结束销毁）。

## 图表类型（`<type>` 取值）

| type | 用于 |
|---|---|
| `architecture` | 系统/服务架构、模块与依赖边界 |
| `workflow` | 业务流程、决策分支 |
| `sequence` | 时序交互（谁先调谁、消息往返） |
| `dataflow` | 数据从产生到消费的流转与变换 |
| `lifecycle` | 状态机与状态迁移 |
| `erd` | 实体关系（表结构/领域模型） |
| `tree` | 层级结构（目录、组织、分类） |
| `class` | 类图（继承/组合/接口） |
| `timeline` | 时间轴（事件、里程碑） |
| `waterfall` | 耗时分解（请求各阶段耗时） |

## 输入 JSON 从哪查（不要凭印象写）

- **机器可读规范**：`schemas/<type>.schema.json`（另有 `common.schema.json` 放公共定义）
- **人读契约**：`references/authoring-contract.md`、`references/authoring-defaults.md`
- **交互式提问**：`node bin/archify.mjs guide "<你的场景>" --json` —— 不确定该用哪个类型时先问它
- **内置样例**：`node bin/archify.mjs examples`（样例文件本身已在裁剪时剔除，此处只列清单）

## 命令语义（都在技能根目录执行）

`internal_run_skill` 已自动 `cd /workspace/skills/archify` 并导出 `TMPDIR=/workspace/tmp`，所以：

```
mkdir -p /workspace/out
node bin/archify.mjs validate <type> /workspace/out/g.json --json
node bin/archify.mjs deliver  <type> /workspace/out/g.json /workspace/out/g.html --json
node bin/archify.mjs check    /workspace/out/g.html --json
node bin/archify.mjs doctor            # 包完整性自检（26 项，正常应为全绿）
```

- 多步操作**写在同一条命令里**（`mkdir -p … && node … && node …`）比拆成多次调用省轮次：
  每次 `internal_run_skill` 有约 0.1~1s 固定开销。
- `--quality standard`（默认）/ `showcase`：后者版式更精致、体积略大，对外交付建议 `showcase`。
- `deliver` 的 `--json` 会打印回执，其中 `artifact.sha256` 就是产物的哈希——可用来和
  `internal_read_artifact` 的 `sha256` 对账。

## 三条硬边界

1. **不装 Chromium**：`browser-check` / `visual-check` / `preview`（会拉起浏览器）**不可用**，
   不要调用、也不要声称「已做浏览器渲染验证」。视觉复核由人做。
2. **沙箱默认禁网**（egress deny）：`brands capture <url>` 之类需要联网的子命令不可用；
   需要品牌图标时用 `brands <name>` 的**离线内置**能力，不要试图抓网页。
3. **沙箱内文件不是交付物**：不调用 `internal_export_artifact` 的产物会随子任务销毁。
   产物超过单文件上限（默认 5MB）时导出会被拒绝——精简节点数量，而不是分片导出。

## 常见失败与对策

| 症状 | 原因 | 对策 |
|---|---|---|
| `validate` 报 schema 不符 | JSON 缺必填字段 / 类型写错 | 按 `schemas/<type>.schema.json` 逐字段对齐；`common.schema.json` 里的节点定义别自造 |
| `check` 报 `provenance` 不是 `current` | HTML 不是本条输入渲染出来的（被手改过 / 拿旧文件充数） | 重新 `deliver`，不要手改产物 |
| `check` 报 `issues` 非空 | 布局重叠 / 节点越界 | 减节点、缩短标签，或换 `--quality showcase` 重渲染 |
| `node: not found` | 用了非 node 规格的沙箱 | 技能规格由平台按 `skill-spec-preset` 决定，不要自己换镜像 |
| `Temporary directory identity changed` | 容器 `/tmp` 落在 overlay 上 | 平台已注入 `TMPDIR=/workspace/tmp`；自己拼命令时**不要覆盖**这个变量 |
| 导出报「产物不存在」 | 沙箱已回收 / 路径写错 | 产物必须写在 `/workspace/**` 下，且在**同一轮执行内**导出 |

## 验收标准（本技能的交付算「完成」的条件）

- `<type>` 与任务要求一致，JSON 通过 `validate`；
- HTML 通过 `check`：`provenance=current`、`issues` 为空；
- 已 `internal_export_artifact` 导出，返回值里有 `attachmentId` 与 `sha256`；
- 正文中引用该产物时说明「图见附件 `<fileName>`」，不复述整张图的内容。
