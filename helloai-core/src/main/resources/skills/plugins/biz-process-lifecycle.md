---
# 平台技能目录项：业务状态机建模（执行包在 skills/packages/archify/3.1.0/，本文件只写建模纪律）
# 与 archify.md 的分工：archify.md 讲「怎么调 CLI」，本文件讲「业务状态机该怎么建」。
name: biz-process-lifecycle
version: "1.0.0"
description: "业务状态机建模：主进度 / 等待中断 / 终态三区分离，事件标注每条边，终态穷举且来路不合并"
requiredTools:
  - internal_run_skill
  - internal_read_artifact
  - internal_export_artifact
# G-022 引用边有意留空（理由同 archify / eng-verification：声明 toolCalling 会拒掉能力不足的模型）
requiredBaseCapabilities: []
dependencies:
  - archify
inputSchema: {}
outputSchema:
  type: object
  properties:
    htmlPath:
      type: string
    sha256:
      type: string
    stateCount:
      type: integer
validationRules:
  - "三区必须分离：主进度（active）、等待/中断（waiting|external）、终态（success|failure|neutral）"
  - "每条转换必须有事件名，写「下一步」「继续」这类无信息量的词视为未标注"
  - "终态必须穷举：成功、失败、取消、超时关闭、对账关闭，一个都不省略"
  - "每个终态的每条来路必须有不同事件名，防止渲染器合并箭头导致来路隐形"
  - "可恢复失败必须有真实回边；用说明卡写「会重试」不算拓扑"
  - "不得画 lifecycle 不支持的能力（并行、泳道、分组、手工路由）"
  - "交付必须在 Linux 容器/沙箱内跑 deliver，并 export 成附件"
---

# 业务状态机建模（lifecycle）

> 执行包：`archify` 3.1.0（`skills/packages/archify/3.1.0`），本技能只规定**怎么建模**，不重复 CLI 用法。
> 适用：支付、订单、工单、审批、任务、设备连接等「有明确状态与事件」的业务。

## 执行速览

1. **先定三区**（不进 JSON，先在纸上/回复里列清）：
   - 主进度：读者要跟着走的阶段，**≤ 6 个**
   - 等待或中断：会停下来、但**能走出去**的状态（等人 / 等外部 / 等下一次窗口）
   - 终态：**所有**结束方式，一个都不能省
2. **写 JSON**：`mainPath` = 主进度；每个候选状态选 `type`；每条转换给 `label`（事件名）。
3. **`validate`** 通过（布局与语义一起校验）→ **`deliver`** 出 HTML → **`check`** 要 `provenance=current` 且 `issues: []`
   **（`deliver` 与 `check` 必须在 Linux 容器/沙箱里跑，见下文「宿主限制」）**
4. **`internal_export_artifact`** 导出成附件 —— 不导出，沙箱一销毁图就没了。

## 一、三区怎么分（`type` 的选择就是分区）

| 区 | `type` | 判定 |
|---|---|---|
| 主进度 | `active` | 正常推进的工作步骤。`mainPath` 只放这些 + 起点 + 成功 |
| 等待 | `waiting` | 停下来等人 / 等时间 / 等输入；**必须有回边** |
| 外部持有 | `external` | 主动权在外部方（渠道未给终态、第三方审核中） |
| 判定点 | `decision` | 真正的分支判定（本例未用：支付的分支由事件本身表达，不再加一层判定节点） |
| 成功终态 | `success` | 资金/业务正向成立 |
| 失败终态 | `failure` | 明确失败、被取消、超时关闭 |
| 中性终态 | `neutral` | 停摆或「不成立但不等于失败」（如对账确认无单） |

- **起点只能是 `start`，且必须出现在 `mainPath[0]`。**
- **`mainPath` 相邻两两必须有转换**，否则 validate 报错。
- **颜色由 `type` 决定**，所以「分区」不是画框画线，而是选对 `type`；再用 `meta.legend.entries` 把类型名改成业务读者看得懂的中文（示例：`active` → 「主进度」、`waiting` → 「等待 / 中断」）。

## 二、终态必须穷举（这张图最容易被做坏的地方）

一个业务状态机通常有 5 类出口，缺任何一类都是「隐藏结束方式」：

| 出口 | 语义 | 常见漏法 |
|---|---|---|
| 成功 | 业务正向成立 | — |
| 失败 | 对方明确拒绝 / 校验不通过 | 只画「支付失败」一个，把所有拒绝原因都塞进去 |
| 取消 | 用户或运营主动终止 | 常常整个缺失，理由是「这是异常路径」 |
| 超时关闭 | 等不到结果，按超时结束 | 与失败混为一谈（**超时不是失败**：资金未动过） |
| 对账关闭 | 结果未知，对账确认对方无单后关闭 | 最常被省略，导致 `unknown` 状态悬空出不去 |

**判定标准**：把图给业务方看，问「有没有哪种情况这张图没法结束？」——答案必须是「没有」。

## 三、来路不合并（否则箭头会骗人）

archify 有「等价出口共享一条箭头」的优化：**当多个来路的 label、note、variant 完全一致时**，它会画成一条。
于是「3 条不同的超时来路」会被压成一条，读者看到的是「只有一个超时」——这就是隐形的信息丢失。

**做法**：每个终态的每条来路**给不同的事件名**。

```
挂起超时 → 已关闭
鉴权超时 → 已关闭
支付超时 → 已关闭      ← 三条独立箭头，读者知道关闭有四种原因
对账无单 → 已关闭
```

反过来也成立：**不要为了「图好看」而手动删 note 或复制 label 去凑合并**（作者契约明令禁止）。

## 四、可恢复失败必须有真实回边

契约原话：*a recoverable failure needs a real transition back to an active state; a card saying “retry” is not topology.*

- ✅ `退避重试 --(到期)--> 提交`：真实回边
- ❌ 只画 `提交 --(失败)--> 失败`，然后在卡片里写「失败会重试三次」——图上没有这条路径
- 同时必须画「重试耗尽 → 失败」，否则重试成了无限循环

## 五、不编造（硬边界）

1. **lifecycle 没有并行/泳道/分组/列宽/手工路由**（作者契约原文：*There are no lanes, columns, sizes, or routing controls*）。
   要表达并发的两个子系统 ⇒ **画两张 lifecycle**，不要硬塞成一个。
2. **不画工具跑不出来的东西**：平台不装 Chromium，`browser-check` / `visual-check` / `preview` 不可用
   ⇒ 不写「已做浏览器渲染验证」，视觉复核写「待人工」。
3. **不臆造状态**：图上每个状态都要能回答「谁把它推进来、谁把它推出去」。答不出来的状态删掉。
4. **不臆造字段**：只用 `schemas/lifecycle.schema.json` 里的字段（`id/type/label/sublabel/tag/icon/brand/sources/step`）。
   加 `sources` 需要 `meta.repository` + `--repo-root`，没有仓库证据就别写。

## 六、可读性预算（实测硬数字，2026-10-26）

这不是建议，是 `validate` 会直接拒绝的红线：

| 约束 | 实测 |
|---|---|
| 画布预算 | **1395px**（超了报 `lifecycle/too-wide`） |
| **`sublabel` 是宽度杀手** | 13 节点**带** sublabel ⇒ 1406px（差 11px 被拒）；**去掉** sublabel ⇒ 通过 |
| 节点规模跳变 | 13 节点（下层 7）正常布局；**14 节点（下层 8）跳变到 2028px**（下层被摊成一行） |
| 主进度 | ≤ 6 个；`mainPath` 的 label 直接决定步间距 |
| 结论 | **总节点 ≤ 13，主进度 ≤ 6，不要 sublabel**；分类信息放到 `tag`（`tag` 不影响宽度） |

超过 13 个节点怎么办（按优先级）：
1. **合并同一业务终态的多个节点，保留多条来路**（把「超时关闭 + 对账关闭」合成「已关闭」，四条来路各自带 label）——这是唯一「不丢信息」的压缩方式
2. 把内部子循环（账务重试、风控重算）移出本图，用一张卡片说明它的存在与归属
3. 仍超 ⇒ **拆两张**（主流程一张、异常出口一张），并在两张图之间互相指名

## 七、宿主限制：`deliver` 必须在 Linux 里跑（2026-10-26 实测）

在 **macOS / Windows 宿主**上直接跑 `deliver` 会失败，两个典型报错：

```
delivery/journal-recovery-required  ... (delivery-journal-candidate-link-count-changed)
render → output/target-indeterminate  Output target stability could not be determined safely
```

原因：`deliver` 用「暂存 journal + 硬链接 + 原子改名」保证产物不半成品，硬链接计数在 APFS / NTFS 上不稳定。
`validate`（只读）在宿主上是好的，所以「validate 通过但 deliver 报 journal 错」不是 JSON 的问题。

**正确做法**：在技能沙箱（Linux 容器）里跑，产物用 `internal_export_artifact` 取回——
这本来就是生产链路，不需要额外适配。

## 八、可复用模板（支付为例，13 节点 / 21 条边）

```json
{
  "schema_version": 3,
  "diagram_type": "lifecycle",
  "meta": {
    "title": "<业务名>生命周期",
    "subtitle": "主进度 / 等待与中断 / 全部终态 —— 一条边一个真实事件",
    "locale": "zh-CN",
    "output": "out/<name>.html",
    "quality_profile": "showcase",
    "animation": "trace",
    "legend": {
      "mode": "all",
      "entries": {
        "start": { "label": "起点" },
        "active": { "label": "主进度" },
        "waiting": { "label": "等待 / 中断" },
        "external": { "label": "外部持有" },
        "success": { "label": "成功终态" },
        "failure": { "label": "失败终态" }
      }
    }
  },
  "mainPath": ["created", "routing", "submitting", "awaiting_result", "settling", "succeeded"],
  "states": [
    { "id": "created", "type": "start", "label": "创建", "tag": "受理", "step": "01" },
    { "id": "routing", "type": "active", "label": "路由", "tag": "风控", "step": "02" },
    { "id": "submitting", "type": "active", "label": "提交", "tag": "渠道", "step": "03" },
    { "id": "awaiting_result", "type": "active", "label": "等结果", "tag": "查单", "step": "04" },
    { "id": "settling", "type": "active", "label": "记账", "tag": "账务", "step": "05" },
    { "id": "succeeded", "type": "success", "label": "成功", "tag": "终态", "step": "06" },

    { "id": "held", "type": "waiting", "label": "风控挂起", "tag": "等待", "icon": "security" },
    { "id": "awaiting_user", "type": "waiting", "label": "待鉴权", "tag": "等待", "icon": "person" },
    { "id": "retrying", "type": "waiting", "label": "退避重试", "tag": "等待", "icon": "clock" },
    { "id": "unknown", "type": "external", "label": "结果未知", "tag": "外部", "icon": "external" },

    { "id": "failed", "type": "failure", "label": "失败", "tag": "终态", "icon": "stop" },
    { "id": "cancelled", "type": "failure", "label": "取消", "tag": "终态", "icon": "stop" },
    { "id": "closed", "type": "failure", "label": "已关闭", "tag": "终态", "icon": "clock" }
  ],
  "transitions": [
    { "from": "created", "to": "routing", "label": "受理" },
    { "from": "routing", "to": "submitting", "label": "选定" },
    { "from": "submitting", "to": "awaiting_result", "label": "受理" },
    { "from": "awaiting_result", "to": "settling", "label": "成功" },
    { "from": "settling", "to": "succeeded", "label": "入账" },

    { "from": "routing", "to": "held", "label": "命中", "variant": "security" },
    { "from": "held", "to": "routing", "label": "放行", "variant": "dashed" },
    { "from": "held", "to": "failed", "label": "拒绝", "variant": "security" },
    { "from": "held", "to": "closed", "label": "挂起超时", "variant": "security" },

    { "from": "submitting", "to": "awaiting_user", "label": "需鉴权", "variant": "security" },
    { "from": "awaiting_user", "to": "awaiting_result", "label": "通过" },
    { "from": "awaiting_user", "to": "failed", "label": "拒签", "variant": "security" },
    { "from": "awaiting_user", "to": "closed", "label": "鉴权超时", "variant": "security" },
    { "from": "awaiting_user", "to": "cancelled", "label": "用户取消" },

    { "from": "submitting", "to": "retrying", "label": "限流", "variant": "dashed" },
    { "from": "retrying", "to": "submitting", "label": "到期", "variant": "dashed" },
    { "from": "retrying", "to": "failed", "label": "耗尽", "variant": "security" },

    { "from": "awaiting_result", "to": "unknown", "label": "回调超时", "variant": "security" },
    { "from": "unknown", "to": "awaiting_result", "label": "查单恢复", "variant": "dashed" },
    { "from": "unknown", "to": "closed", "label": "对账无单", "variant": "dashed" },
    { "from": "awaiting_result", "to": "failed", "label": "渠道失败", "variant": "security" },
    { "from": "awaiting_result", "to": "closed", "label": "支付超时", "variant": "security" },

    { "from": "created", "to": "cancelled", "label": "用户撤销" }
  ],
  "cards": [
    { "dot": "emerald", "title": "主进度：6 步，只有渠道成功才推进", "items": ["..."] },
    { "dot": "amber", "title": "等待与中断：4 个，每一个都能走出去", "items": ["..."] },
    { "dot": "rose", "title": "终态：4 个节点，来路一条不合并", "items": ["..."] }
  ]
}
```

`cards` 用来说明「为什么图是这样」：三张卡分别讲主进度、等待与中断、终态（含被移出本图的内容与原因）。

## 九、自检清单（交付前逐条过）

- [ ] 三区是否都能一眼看出？（颜色 + 图例中文名）
- [ ] `mainPath` ≤ 6，总节点 ≤ 13，**没有 `sublabel`**
- [ ] 每条转换都有**具体事件名**（不是「下一步」）
- [ ] 5 类终态是否齐全（成功/失败/取消/超时关闭/对账关闭）？合并过度的请说明合并理由
- [ ] 每个终态的来路是否**各不相同**（防合并）？
- [ ] 可恢复失败是否有**真实回边**？重试是否有「耗尽」出口？
- [ ] 每个非终态是否有出路？每个终态是否可达？
- [ ] 有没有画 lifecycle 不支持的并行/泳道/分组？
- [ ] 图上有没有「代码/事实无法支持」的表述？
- [ ] `deliver` 在 Linux 里跑、`check` 是 `provenance=current` + `issues: []`、已 `internal_export_artifact`

## 十、常见失败与对策

| 症状 | 原因 | 对策 |
|---|---|---|
| `lifecycle/too-wide`（1406 / 2028px） | 有 `sublabel`，或节点 ≥ 14 | 删 `sublabel`（信息进 `tag`）；合并同一终态节点、保留全部来路 |
| `lifecycle/label-unplaced` | 某条边的 label 放不进可达走廊 | 缩短该 label（4 字 → 2 字），或让该边不跨过中间节点 |
| `delivery/journal-recovery-required` | 在 macOS/Windows 宿主直接 `deliver` | 换到 Linux 容器/技能沙箱跑 |
| `output/target-indeterminate` | 同上（宿主文件系统硬链接不稳） | 同上 |
| `check` 报 `provenance` 非 current | HTML 被手改过 / 拿旧文件充数 | 重新 `deliver`，不改产物 |
| `check` 报 `issues` 非空 | 节点重叠或越界 | 减节点、缩 label；仍不行则拆图 |
