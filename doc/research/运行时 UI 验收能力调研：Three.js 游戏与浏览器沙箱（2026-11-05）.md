# 运行时 UI 验收能力调研：Three.js 游戏与浏览器沙箱（2026-11-05）

> **Status: `Analysis`**（只读调研，不改变任何实现、不承诺排期）。
> **性质**：外部项目 / 技术调研 + HelloAI 现状对照；回答「别人怎么做、哪些值得借鉴、哪些明确不抄」。
> **缘起**：用户对「星际弹幕 Three.js 小游戏开发与完整验收交付」只能人工审核提出疑问——平台若要自动核验运行时 UI / 游戏交付，需要新增哪些技能与工具？开源项目在沙箱中如何完成、如何鉴别这类任务？内部 LLM 需要哪些能力（如 `web_search`）？
> **关联**：`doc/plan/HelloAI 核验双域改造实施计划（2026-11-04）.md`（RV/SR 改造）、`doc/log/2026-11.md`（2026-11-05 核验双域批次）。
> **口径**：外部源码为本机下载副本 + 公开仓库；`[实测]` 附路径 / 行号，未直接核实的标 `[推断]` / `待核`。

---

## §0 三句话结论

1. **HelloAI 缺的不是一个「游戏技能」，而是三层能力**：运行时工具（浏览器 / 截图 / 录屏）、验收类型（运行时 UI）、审查侧视觉能力（VLM）。只加 `web_search` 或一份 `game` skill，模型能写脚本但跑不了页面、看不了截图。
2. **开源项目的共同做法是「浏览器工具 + 截图 / 视觉模型 + 脚本化断言」**：OpenHands / BrowserGym / WebArena 用 Playwright 驱动无头 Chromium，Design2Code 用渲染截图做视觉指标；DeepSeek Harness 把浏览器做成实验性 MCP provider，Octop 把 `browser_use` 做成内置工具并返回多模态截图。**没有任何项目用「游戏技能」替代运行时验证。**
3. **对 HelloAI 的最小切片**：新增 `AcceptanceKind.RUNTIME_UI` + Playwright MCP / 浏览器工具 + 截图落附件 + 视觉 reviewer；L0 静态、L1 渲染、L2 交互自动验，L3 截图交给 VLM 辅助，L4「好玩 / 手感 / 美术」仍归人工。复用现有沙箱与核验引擎，不建第二套 Runtime。

---

## §1 问题定义与 HelloAI 现状

### 1.1 触发案例

「星际弹幕 Three.js 小游戏开发与完整验收交付」包含多类运行时交付：单文件 Three.js 游戏源码（≥800 行）、mp3 音效、Chrome / Safari 实机录屏 mp4、10 个 Playwright E2E 用例、公网 HTTPS 部署、最终汇总核对。该任务最终**人工审核通过**。

人工审核的直接原因不是「没有验收标准」，而是**平台没有运行时证据面**：

- reviewer 只能看到执行产出文本 + 物化附件清单 + 文本附件正文（图片 / 音视频被跳过）；
- 沙箱只提供命令执行，没有浏览器、渲染截图、WebGL 检查或交互录制；
- 验收类型没有「运行时 UI」这一档，无法把「页面能开、canvas 存在、按键有反应」写成机器可执行的判据。

### 1.2 平台现状盘点（[实测]）

| 能力面 | 现状 | 证据 |
|---|---|---|
| 验收类型 | 只有 `AUTOMATED / STRUCTURAL / REVIEWED / ATTESTED`，没有运行时 UI / 浏览器档 | [AcceptanceKind.java:21](file:///Users/shihang/IdeaProjects/helloai/helloai-common/src/main/java/com/helloai/common/constant/AcceptanceKind.java#L21) |
| 任务原型 | `BUILD / DOCUMENT / RESEARCH / DATA / OPERATE / DECIDE`；BUILD 覆盖 Web/游戏，但验收仍按命令 / 测试描述 | [TaskArchetype.java:20](file:///Users/shihang/IdeaProjects/helloai/helloai-common/src/main/java/com/helloai/common/constant/TaskArchetype.java#L20) |
| 内部 LLM 工具 | 有 `internal_run_in_sandbox`、`internal_read_attachment`、技能加载、产物导出等；**没有浏览器 / 截图 / 录屏工具** | [InternalLlmExecutorTools.java:389](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/runtime/tool/InternalLlmExecutorTools.java#L389)（沙箱工具）及同类 `@Tool` 定义 |
| 沙箱能力 | 命令级执行（OpenSandbox），不是渲染环境 | [SandboxExecutionToolContributor.java:39](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/runtime/sandbox/SandboxExecutionToolContributor.java#L39) |
| 核验证据 | 执行产出 + 物化附件 + 验证信号 + 自审信号；**媒体附件只给提示，无法看图 / 看视频** | [ReviewEvidenceAssembler.java:414](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewEvidenceAssembler.java#L414) |
| 浏览器会话 | `browser_session` 只有登记 / 展示语义，不持有浏览器实例 | [BrowserSessionServiceImpl.java:22](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/service/impl/BrowserSessionServiceImpl.java#L22) |
| WEB_BROWSER | 接入类型存在但已禁用；其原语义是网页版 AI 的 Playwright 桥接，不是平台托管浏览器 | [RemoteAgentEnvironment.java:10](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/runtime/RemoteAgentEnvironment.java#L10) |
| 技能目录 | `eng-code-review / eng-doc-standard / eng-verification / eng-web-research` + `archify / biz-process-lifecycle`；无浏览器验证 / 游戏 QA | [PromptTemplateServiceImpl.java:37](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/system/service/impl/PromptTemplateServiceImpl.java#L37) |
| 既有 review 升级 | RV-1~7、SR-1~5 已覆盖判定校验 / 三态 / 覆盖批评 / 抽检 / 自审 / 编码检测 / 驳回冻结；但全是**文本与元数据**证据 | 见 `plan/HelloAI 核验双域改造实施计划（2026-11-04）.md` §3 |

> 说明：RV-6 已实现「驳回快照冻结五段证据 + 复判直接用冻结对象」（`ReviewEvidenceView` / `frozenEvidenceView`），RV-7 已实现编码损坏 WARN；这些能力可复用，但**不解决「看运行时」**。

### 1.3 缺口的本质

- **执行侧有手，但没有眼睛**：内部 LLM 能跑 shell / 读文本，不能开页面、点按钮、截图。
- **审查侧有尺，但没有画面**：reviewer 能对照验收标准判文本，但看不到 canvas / 截图 / 录屏。
- **验收侧有意愿，但没有判据类型**：`REVIEWED` 只能写「≥2 条可打勾审查项」，无法表达「打开页面、无 console error、按键后得分增加」。
- **鉴别侧没有显式分类**：任务是否属于运行时 UI，目前只能靠人读描述；平台没有 `RUNTIME_UI` 这个显式声明。

---

## §2 开源项目调研（外部）

### 2.1 总览

| 项目 | GitHub | 语言 / 许可 | 最值得借鉴 | 明确不抄 |
|---|---|---|---|---|
| DeepSeek Harness（DSH） | https://github.com/deepseek-ai/deepseek-harness | TypeScript / MIT（本机 `package.json`） | 浏览器 provider 作为会话级工具；截图需附件存储 + 视觉模型；auto-review 风险门禁；技能 registry | 不做它的全套插件框架 / 会话日志 |
| Octop Harness | https://github.com/TencentCloud/octop-harness | Python / MIT（本机 `pyproject.toml`） | `browser_use` 动作集 + 多模态截图 + `eval_js` + profile 登录态 + metrics；hermetic 测试 | 不直接照搬 host Chrome 控制，必须放进沙箱 |
| OpenHands | https://github.com/All-Hands-AI/OpenHands | Python / MIT | Docker 沙箱 + 终端 + Playwright / ACP + 截图；云端多模态看屏 | 不引入它的完整 agent runtime |
| BrowserGym | https://github.com/ServiceNow/BrowserGym | Python（许可待核） | Playwright Gym：观察 = DOM / AXTree / 截图；统一动作空间 | 不把它当生产运行时 |
| WebArena | https://github.com/web-arena-x/webarena | Python（许可待核） | Docker 站点 + Playwright；**功能正确性评估**，不是 LLM 自评 | 不复刻 812 个基准环境 |
| Design2Code | https://github.com/noviscl/Design2Code | Python（许可待核） | 渲染截图 + Block-Match / Text / Position / Color / CLIP；**视觉指标单独不够** | 不把像素相似度当游戏质量判据 |
| Agent Infra Sandbox | https://github.com/agent-infra/sandbox | Python（许可待核） | Playwright 通过 CDP 连浏览器沙箱；录屏 / VNC；资源隔离 | 不引入其整套沙箱平台 |
| LobsterAI | https://github.com/netease-youdao/LobsterAI | TypeScript / MIT（本机 `package.json`） | 验收标准按功能 / 安全 / 数据一致性拆分；权限缺失明确报错；残余风险披露 | 不照搬桌面 App 的 OpenClaw 集成 |
| AgentTeams | https://github.com/agentscope-ai/AgentTeams | Python / Apache-2.0（本机 `LICENSE`） | 验收是显式状态迁移；Leader 独占 accept；`submission_id` 栅栏；状态映射 | 不照搬多 agent 协作 runtime |

> 本机源码位置：DSH `~/Downloads/deepseek-harness-master`；Octop `~/Downloads/octop-harness-main`；LobsterAI `~/Downloads/LobsterAI-main`；AgentTeams `~/Downloads/AgentTeams-main`。许可已按本机清单核实；未下载项目标注「待核」。

### 2.2 DeepSeek Harness：浏览器是会话级 MCP provider

[实测] 本机 `packages/experimental/browser-use-playwright-mcp/README.md`：

- 浏览器能力提供三套实验包：**Playwright MCP**、**Chrome DevTools MCP**、**Stagehand native**；通过 `ctx.browserUse` 注册，工具按 `mcp__playwright-mcp__<tool>` 进正常工具管线。
- 每个 **live Agent/Session 独占浏览器**；`mode: launch` 或 `mode: attach`（接管已登录浏览器），重载 / 分叉不继承浏览器状态。
- **截图不是普通文本**：README 明确「Screenshots require an attachment store and an image-capable model route; other routes receive the MCP image diagnostic」——即：没有附件存储 + 视觉模型，截图进不了模型。
- `auto-review`（`2026-08-28-auto-review.md`）是**工具调用前的风险审查**（low / medium / high，allow / ask / deny，fail-closed），不是交付验收。若浏览器要访问外部站点，可借它的「动作风险分级」；但不能拿它替代 reviewer。

**借鉴点**：浏览器 provider 的会话归属、launch/attach 二态、截图必须落附件 + 视觉路由；**技能是 instruction，工具是 runtime**，两者分开。

### 2.3 Octop Harness：最接近可抄的 `browser_use` 工具面

[实测] 本机 `src/octop_harness/builtin/tools/browser_use.py`：

- 支持动作：`navigate`、`dom_tree`（minimal / interactive / full / structured，元素带稳定 `ref`）、`click`、`type`、`hover`、`scroll`、`screenshot`、`eval_js`、`go_back/forward/reload`、`new_tab/switch_tab/close_tab/list_tabs`、`close_session`。
- `screenshot` 成功时返回 **`[image_block, text_block]`** 多模态结果，视觉模型可直接看图；其他动作返回 JSON（`success/content/error/metrics/action_history`）。
- 按 `profile` 持久化登录态；截图保存到 agent workspace；`eval_js` 可直接读取页面内 JS 状态（游戏状态断言的关键）。
- 测试用 fake `octop_browser.browser_tool` 做 hermetic 单测（`tests/test_browser_use.py`），不依赖真实 Chrome。
- 本机 `examples/09_browser_screenshot.py` 演示 direct / wrapper / agent 三种用法；`octop-browser` 是底层 Chrome CDP 驱动（https://github.com/TencentCloud/octop-browser）。

**借鉴点**：HelloAI 的浏览器工具可优先按这套动作集设计；截图走附件 + 视觉模型；`eval_js` 作为游戏状态断言入口；测试用 fake browser 工具。

### 2.4 OpenHands / BrowserGym / WebArena / Design2Code

- **OpenHands** [实测，公开仓库]：Docker 沙箱 + 终端 + Playwright / ACP + 截图；浏览器是工具，模型通过 DOM / 截图观察页面；真正的「看图」由云端多模态模型完成，本地无需 GPU。
- **BrowserGym** [实测，公开仓库]：Playwright 驱动的 Gym 环境；观察空间支持 DOM / 可访问性树 / 截图；动作空间统一，WebArena / WorkArena 建在其上。
- **WebArena** [实测，公开仓库]：Docker 托管站点 + Playwright；评估靠任务配置里的**功能正确性断言**（URL / DOM / 状态），不是让 LLM 自评。安全提醒：它曾被 `file://` 导航读取参考答案的漏洞绕过，说明浏览器沙箱必须限制 `file://`。
- **Design2Code** [实测，公开仓库]：把生成页面渲染成截图，再用 Block-Match / Text / Position / Color / CLIP 等指标对比；论文明确**视觉指标单独不足以评估功能与代码质量**，需结合 DOM / 文本 / 结构。

**借鉴点**：验证必须「渲染 + 脚本断言 + 结构检查」组合；单看截图会被背景图 / 绝对定位欺骗。

### 2.5 LobsterAI：验收标准文档化 + 能力门禁 + 残余风险

[实测] 本机 specs：

- 每个 feature spec 有独立「验收标准 / 验证计划」，按**功能 / 安全 / 数据一致性**拆分，并分别列单元测试与手工验证。例：[browser-web-access 验收](file:///Users/shihang/Downloads/LobsterAI-main/specs/features/browser-web-access/2026-05-20-browser-web-access-settings-design.md) 覆盖代理开关、SSRF / 内网拦截、托管 profile、i18n、常量约束。
- [computer-use-kit](file:///Users/shihang/Downloads/LobsterAI-main/specs/features/kit/2026-06-08-computer-use-kit-design.md)：`check_permissions` 返回缺失项与修复指引；**缺少辅助功能时输入类动作明确报错，而不是报告成功**；安装 / 卸载 / MCP 存在性都在验收项里。
- 产物类 spec（HTML / 视频 / Office / Mermaid）把验收拆「功能 / 安全 / 数据一致性」，并**显式披露残余风险**：SVG 首版只做文本 / 结构审核、组件级浏览器测试不等同于完整客户端人工验收。

**借鉴点**：`RUNTIME_UI` 验收标准要写成可执行清单 + 明确「哪些自动可证、哪些必须人工」；能力 / 权限缺失要 fail-closed，不能把「没跑」报成「通过」。

### 2.6 AgentTeams：验收是显式状态迁移，提交 ≠ 验收

[实测] 本机 `docs/design/teamharness/project-task-runtime-design.md`：

- **Leader 独占验收权**：Worker 只能 `ack_task` / `submit_task`，不能 accept / cancel；`accept_task_result` 是 result 验收后推进 project 的唯一入口；`check_task` 只读校验、不写终态。
- **`submission_id` 栅栏**：accept / cancel 必须携带当前 `submissionId`；缺失、过期、不同决定的重试一律拒绝；相同决定重试幂等。这与 HelloAI RV-5 的「快照绑定原判对象」同一思路。
- **状态映射**：`SUCCESS / SUCCESS_WITH_NOTES / REVISION_NEEDED / BLOCKED / INTERRUPTED`；`FAILED` / `PARTIAL` 跨 runtime 不支持，写入前直接拒绝。
- **反模式明示**：`leader.md` 写明「Do not treat a worker completion message as automatic project acceptance.」
- **测试**：[acceptance observer](file:///Users/shihang/Downloads/AgentTeams-main/tests/check-teamharness-acceptance-observer.sh) 验证 `submitted/accepted` 能过，而 stale marker、错误 status、缺 result 必须被拒。

**借鉴点**：验收必须是显式、带身份栅栏的状态迁移；执行者完成消息不能自动触发验收；状态语义可映射到 HelloAI 的 APPROVED / REJECTED / BLOCKED / INCONCLUSIVE。

### 2.7 浏览器工具生态（可直接选型）

| 项目 | GitHub | 用途 |
|---|---|---|
| Playwright MCP | https://github.com/microsoft/playwright-mcp | MCP 形式的浏览器工具；DSH 直接复用 |
| Chrome DevTools MCP | https://github.com/ChromeDevTools/chrome-devtools-mcp | CDP / DevTools 能力，适合性能与网络诊断 |
| Stagehand | https://github.com/browserbase/stagehand | 自然语言浏览器动作 / 提取；DSH 有 native provider |
| browser-use | https://github.com/browser-use/browser-use | 浏览器 agent 框架 |
| browser-harness | https://github.com/browser-use/browser-harness | 轻量 CDP harness（实验性） |
| OpenClaw | https://github.com/openclaw/openclaw | LobsterAI 依赖的浏览器 / 工具运行时 |

---

## §3 Three.js 这类任务到底怎么验

### 3.1 验收分级（建议作为 `RUNTIME_UI` 的判据模板）

| 层级 | 验什么 | 手段 | 判定者 |
|---|---|---|---|
| L0 静态 | 文件 / 资源齐全、HTML 可解析、行数、Three.js 依赖可达、mp3/mp4 可解码 | 文件检查 + `ffprobe` + 依赖探测 | 机器 |
| L1 渲染 | 页面能开、canvas 存在、WebGL context 非空、console 0 error、截图非空白（像素方差） | headless Chromium + WebGL（SwiftShader / EGL） | 机器 |
| L2 交互 | 按键 / 鼠标有反应、30s smoke、分数 / 角色位置变化、暂停恢复、音频加载 | Playwright 输入 + `eval_js` 状态断言 | 机器 |
| L3 视觉 | HUD / 场景是否像样、是否错位 / 空白、关键元素是否出现 | 截图交给 VLM，按 rubric 打分 | VLM（advisory） |
| L4 主观 | 好不好玩、手感、美术、节奏 | 人工试玩 / 录屏复核 | 人工 `ATTESTED` |

> **像素 diff / CLIP 只作辅助**：动画 / 随机种子 / 字体渲染会让像素差异不稳定；L2 的状态断言比 L3 的相似度可靠。录屏只能作证据，不能证明「好玩」。

### 3.2 具体检查与脚本轮廓

```text
1. 启动本地静态服务（不要用 file://，避免同源 / 安全限制）
2. browser.navigate("http://127.0.0.1:<port>/index.html")
3. 等 canvas；eval_js: !!document.querySelector('canvas')?.getContext('webgl2')
4. 收集 console / pageerror / network 失败；要求 error = 0（白名单字体 / 音频解码除外）
5. eval_js: window.__gameState（任务要求暴露）
6. keyboard.press("ArrowUp") × N；等待 1s；eval_js 断言 playerY / score 变化
7. 30s smoke：断言帧循环未停、分数 / 敌人数按预期变化
8. screenshot(full_page=False)；写附件；交给 VLM
9. 可选：recordVideo 30s，人工复核手感 / 画面
10. 输出结构化证据：{consoleErrors, webgl, canvas, stateBefore/After, screenshots, video}
```

### 3.3 验收标准模板（可直接用于拆解）

```text
① 交付物为可运行的单文件 HTML / JS（≥800 行），页面通过本地 HTTP 打开；
② L1：headless Chromium 打开后 canvas 存在、WebGL context 非空、console error=0；
③ L2：脚本按下方向键 2s 后 window.__gameState.playerY 发生变化，30s 内 score > 0；
④ L3：截图包含 HUD（分数 / 生命）与游戏主画面，非空白；VLM 复核无布局破损；
⑤ L4：人工试玩 3 分钟确认可玩性；录屏 mp4 可解码，时长 ≥30s；
⑥ 所有自动断言失败时不得判通过；L3/L4 不能互相替代。
```

### 3.4 状态与身份映射（借 AgentTeams）

| AgentTeams | HelloAI 对应 |
|---|---|
| `SUCCESS_WITH_NOTES` | `APPROVED` + nit |
| `REVISION_NEEDED` | `REJECTED` / `REWORK` |
| `BLOCKED` | `BLOCKED` |
| `INTERRUPTED` / 不支持的 result | `INCONCLUSIVE` → 人工 |
| `submission_id` 栅栏 | RV-5 快照 + 执行记录 id，验收必须绑定具体提交 |

---

## §4 对 HelloAI 的借鉴结论（不写排期）

### 4.1 工具层：浏览器工具必须真实存在

- **优先方案**：接 Playwright MCP（DSH 模式），或按 Octop 的动作集实现 `internal_browser_*`（navigate / dom_tree / click / type / key / screenshot / eval_js / tabs / close）。MCP 方案代码少、生态成熟；原生方案可控性高、与 Spring AI `@Tool` / 沙箱更直连。
- **截图必须落附件**：复用现有 MinIO / attachment service；reviewer 侧要把截图作为 image block 注入，而不是「媒体不可见」提示。
- **浏览器放沙箱**：不要 attach 用户已登录的浏览器去跑不可信页面；沙箱内启动 Chromium（无头、WebGL 软件渲染），限制出网白名单、CPU / 内存 / 时长，禁用 `file://`。

### 4.2 验收层：新增 `RUNTIME_UI`

- 新增 `AcceptanceKind.RUNTIME_UI`（或 `BROWSER`），在澄清终稿时**显式选择**，不从 acceptance 文本自动推断。
- 校验：`RUNTIME_UI` 的 acceptance 必须包含可执行断言（打开页面 / console / canvas / 输入后状态 / 截图），否则拆解侧直接拒绝或降级为 `REVIEWED`。
- 任务识别：显式 kind + 交付物类型登记（html/js/canvas/webgl/three）；关键词只作提示，不作判据。只写「好看 / 好玩」的仍归 `REVIEWED/ATTESTED`。

### 4.3 技能层：skills 是 instruction，不是 runtime

建议新增（不急于一次性全上）：

- `eng-browser-verification`：Playwright 断言、console / network、截图证据、无头浏览器注意事项。
- `eng-game-qa`：canvas / WebGL、确定性输入脚本、状态断言、资产 / FPS 检查、录屏规范。
- `eng-visual-review`：截图评审 rubric、VLM 与人工边界、非空白 / 布局 / HUD 检查。
- `eng-verification` 扩「运行时证据」一节。

技能包应像 DSH `skill-office` 一样**带检查脚本 / 模板**，不要只写一段提示词。

### 4.4 审查层：让 reviewer 看见运行时证据

- 扩展 `ReviewEvidenceView`：增加 `runtimeEvidence`（console、WebGL、state before/after、截图附件 refs、录屏 refs）。
- 截图以 image block 进 reviewer；没有视觉模型的路由明确标注「本轮无法看图，不得判视觉类通过」。
- 复用 RV-6 冻结机制：驳回记录的运行时证据也冻结，复判不依赖返工后的页面状态。
- 可借 DSH auto-review 的风险分级，但只用于「浏览器动作是否可执行」，不替代交付判定。

### 4.5 `web_search` 的定位

- `web_search` 用于执行 / 调研：Three.js API、CDN 可达性、素材规范、错误码。
- 不能替代运行时证据；reviewer 侧不应以「搜索结果说可行」判游戏可玩。
- 真正需要的是 `browser tools + eval_js + screenshot + VLM`，`web_search` 只是辅助。

### 4.6 明确不抄 / 不做

- **不建第二套 Review Runtime**：复用现有 `ReviewExecutionEngine` / `ReviewService` / 沙箱。
- **不以像素相似度单独判死**：Design2Code 已证明视觉指标单独不够；游戏动画更甚。
- **不让执行者完成消息自动验收**：AgentTeams 的反模式；HelloAI 保持 review_record 显式判定。
- **不做关键词自动分类**：与既有「AcceptanceKind 不由文本推断」原则一致。
- **不把用户浏览器 attach 给不可信任务**：DSH 的 attach 是给可信工作流用的，沙箱任务应 launch 隔离浏览器。

---

## §5 明日待核清单（调研问题，不是排期）

| # | 待核问题 | 怎么核 | 通过判据 |
|---|---|---|---|
| 1 | 内部 model 路由是否支持图片输入？哪些 provider / model 有 vision 能力？ | 查 `llm_provider_model.capabilities` / provider catalog / 实际调用 | 至少 1 条 reviewer 路由能收 image block，且失败时明确降级 |
| 2 | Spring AI / HelloAI 的 MCP 客户端能否把图片结果作为多模态内容传给模型？ | 查 MCP client 投影 + `InternalToolCallbacks` / `RuntimeTurnExecutor` | 截图不被转成文本 / 不丢图 |
| 3 | OpenSandbox 能否安装 / 运行 Chromium？WebGL 是否可用（SwiftShader / EGL）？ | 在沙箱跑 `chromium --headless` + WebGL 检测页 | canvas / WebGL context 非空，30s 不崩 |
| 4 | 选 Playwright MCP 还是原生 `internal_browser_*`？ | 对照 MCP 工具发现、权限、截图附件、Spring AI 兼容性 | 工具可被内部 LLM 调用；截图可入库；失败可观测 |
| 5 | 截图 / 录屏存哪里、保留多久、大小上限？ | 对照 attachment / MinIO 现有策略 | 不撑爆 review_record；可被冻结快照引用 |
| 6 | reviewer prompt 如何注入图片？是否需要单独的 VLM reviewer agent？ | 查看 `prompts/subtask-review.md` 渲染 + 模型路由 | 图片进 Prompt；无视觉路由时明确「不看图」 |
| 7 | `AcceptanceKind` 新增值要改哪些地方？ | DB CHECK、enum、拆解提示词、review 分流、前端标签 | 全链路可编译、旧数据不受影响 |
| 8 | 浏览器沙箱的安全边界 | 出网白名单、`file://`、host secrets、资源上限 | 不可信页面不能读本机 / 内网 / 平台密钥 |
| 9 | 成本 | 截图 token、VLM 评审、录屏存储、浏览器启动时间 | 单次运行时验收有可接受上限 |
| 10 | 测试策略 | fake browser tool（Octop 模式）+ 真实 Chromium e2e lane | 单测不依赖 Chrome；e2e 可重复 |

---

## §6 源码与仓库索引

### 6.1 外部仓库

| 项目 | GitHub | 本机路径 | 重点读 |
|---|---|---|---|
| DeepSeek Harness | https://github.com/deepseek-ai/deepseek-harness | `~/Downloads/deepseek-harness-master` | `packages/experimental/browser-use-playwright-mcp/README.md`、`.agents/notes/implemented/feature/2026-08-28-auto-review.md`、`packages/skill/README.md` |
| Octop Harness | https://github.com/TencentCloud/octop-harness | `~/Downloads/octop-harness-main` | `src/octop_harness/builtin/tools/browser_use.py`、`tests/test_browser_use.py`、`examples/09_browser_screenshot.py` |
| Octop 平台 | https://github.com/TencentCloud/Octop | — | 沙箱 / 多 agent 编排 |
| octop-browser | https://github.com/TencentCloud/octop-browser | — | Chrome CDP 驱动 |
| LobsterAI | https://github.com/netease-youdao/LobsterAI | `~/Downloads/LobsterAI-main` | `specs/features/browser-web-access/`、`specs/features/kit/2026-06-08-computer-use-kit-design.md`、`specs/features/artifacts/` |
| AgentTeams | https://github.com/agentscope-ai/AgentTeams | `~/Downloads/AgentTeams-main` | `docs/design/teamharness/project-task-runtime-design.md`、`plugins/teamharness/skills/team/project-management/SKILL.md`、`tests/check-teamharness-acceptance-observer.sh` |
| OpenHands | https://github.com/All-Hands-AI/OpenHands | — | Docker 沙箱 / 浏览器 / 截图 |
| BrowserGym | https://github.com/ServiceNow/BrowserGym | — | Playwright Gym / 观察空间 |
| WebArena | https://github.com/web-arena-x/webarena | — | 功能正确性评估 / 安全边界 |
| Design2Code | https://github.com/noviscl/Design2Code | — | 渲染 + 视觉指标；论文 https://arxiv.org/abs/2403.03163 |
| Agent Infra Sandbox | https://github.com/agent-infra/sandbox | — | CDP / 录屏 / VNC |
| Playwright MCP | https://github.com/microsoft/playwright-mcp | — | MCP 浏览器工具 |
| Chrome DevTools MCP | https://github.com/ChromeDevTools/chrome-devtools-mcp | — | DevTools / 性能 |
| Stagehand | https://github.com/browserbase/stagehand | — | 自然语言浏览器动作 |
| browser-use | https://github.com/browser-use/browser-use | — | 浏览器 agent |
| browser-harness | https://github.com/browser-use/browser-harness | — | 轻量 CDP harness |
| OpenClaw | https://github.com/openclaw/openclaw | — | LobsterAI 的浏览器 / 工具运行时 |

### 6.2 HelloAI 现有代码锚点

- 验收类型：[AcceptanceKind.java:21](file:///Users/shihang/IdeaProjects/helloai/helloai-common/src/main/java/com/helloai/common/constant/AcceptanceKind.java#L21)
- 任务原型：[TaskArchetype.java:20](file:///Users/shihang/IdeaProjects/helloai/helloai-common/src/main/java/com/helloai/common/constant/TaskArchetype.java#L20)
- 内部工具：[InternalLlmExecutorTools.java:389](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/runtime/tool/InternalLlmExecutorTools.java#L389)
- 沙箱工具：[SandboxExecutionToolContributor.java:39](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/runtime/sandbox/SandboxExecutionToolContributor.java#L39)
- 媒体不可见提示：[ReviewEvidenceAssembler.java:414](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewEvidenceAssembler.java#L414)
- 浏览器会话登记：[BrowserSessionServiceImpl.java:22](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/service/impl/BrowserSessionServiceImpl.java#L22)
- WEB_BROWSER 桥接语义：[RemoteAgentEnvironment.java:10](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/agent/runtime/RemoteAgentEnvironment.java#L10)
- 技能清单：[PromptTemplateServiceImpl.java:37](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/system/service/impl/PromptTemplateServiceImpl.java#L37)
- 核验证据视图：[ReviewEvidenceView.java](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewEvidenceView.java)
- 证据装配与冻结：[ReviewEvidenceAssembler.java:151](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewEvidenceAssembler.java#L151)
- 复判使用冻结对象：[ReviewRecheckExecutor.java:88](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewRecheckExecutor.java#L88)
- 快照 v2：[ReviewSnapshotBuilder.java:36](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewSnapshotBuilder.java#L36)
- 编码检测：[ReviewEncodingDetector.java:41](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewEncodingDetector.java#L41)
- 核验引擎证据注入：[ReviewExecutionEngine.java:132](file:///Users/shihang/IdeaProjects/helloai/helloai-core/src/main/java/com/helloai/core/review/support/ReviewExecutionEngine.java#L132)

### 6.3 关联文档

- `doc/plan/HelloAI 核验双域改造实施计划（2026-11-04）.md`（RV/SR 现状与三个不可自然采样分支）
- `doc/log/2026-11.md`（2026-11-05 RV-6/RV-7/SR-3 真机记录）
- `doc/research/DeepSeek dsh-superagent 原型借鉴分析（2026-11-02）.md`
- `doc/research/AgentTeams_Octop_源码复核与helloai借鉴对照.md`

---

## §7 参考链接（外部）

- OpenHands：https://github.com/All-Hands-AI/OpenHands
- BrowserGym：https://github.com/ServiceNow/BrowserGym
- WebArena：https://github.com/web-arena-x/webarena
- Design2Code：https://github.com/noviscl/Design2Code（论文 https://arxiv.org/abs/2403.03163）
- Agent Infra Sandbox：https://github.com/agent-infra/sandbox
- DeepSeek Harness：https://github.com/deepseek-ai/deepseek-harness
- Octop Harness：https://github.com/TencentCloud/octop-harness
- LobsterAI：https://github.com/netease-youdao/LobsterAI
- AgentTeams：https://github.com/agentscope-ai/AgentTeams
- Playwright MCP：https://github.com/microsoft/playwright-mcp
- Chrome DevTools MCP：https://github.com/ChromeDevTools/chrome-devtools-mcp
- Stagehand：https://github.com/browserbase/stagehand
- browser-use：https://github.com/browser-use/browser-use
- browser-harness：https://github.com/browser-use/browser-harness