# magpie 借鉴分析（供应商注册信息为主）

> **状态：Analysis**
> 最后更新：2026-10-13
>
> **目的**：用户要求分析开源项目 **magpie**（Go 本地 AI Agent 网关），重点回答三件事：
> ① magpie 的**供应商 / 模型注册信息**（`presets` + `catalog`）能否照搬进 helloai；② **API Key 注册**内容有哪些值得借鉴；
> ③ helloai 的 **AI Agent 注册**能否借 magpie 优化。用户明确：**不需要一键切换账号 / 自动 failover**，只取其「注册信息」。
>
> **勘察对象**：`E:/workspace/magpie-main`（Go 单二进制本地网关，`internal/{catalog,provider,agent,gateway}` 分层）。
>
> **对照基线**：helloai（Java Spring Boot 分布式任务编排平台，`e:/yhzx/1027/helloai`）。
>
> **勘察口径（2026-10-13 按《治理规则》§3.6 补齐）**：凡标 `[实测]` 的论断均由本地逐文件读取源码得出并附 `路径:行号`；`[推断]` 为基于上游用法与文档的反推，已单独说明；**未核实的写「未核实」，不用「通常/一般」充当事实**。helloai 侧现状均逐文件核实。
>
> **路径基准**：对 magpie 的路径引用**以仓库根为基准、写全 `internal/...`**；对 helloai 的路径引用以仓库根为基准。
>
> **分析日期**：2026-10-13。
>
> **复核与订正（2026-10-13，第二轮）**：对全文锚点逐条抽验（magpie 15+ 处 / helloai 10+ 处，**原表全部命中**），并据此订正三处结论：
> ① 优先级口径 `P0`~`P3` → **`PR0`~`PR3`**（`P0`~`P3` 在《差距表》§0 唯一含义是「架构重构主线阶段」）；
> ② `M-5` 补「`extraConfig` 只写不读，须连带新增消费逻辑」；
> ③ **新增 §六第 2b 行**——「预置取值陈旧」是**已在现网造成故障的缺陷**（含 HTTP 404 直接根因），`M-2` 因此从「补维度」升级为「去陈旧硬编码 + 加三方一致性守卫」，并已随本次复核**直接修好 4 个文件**（明细见 §九复核条目）。
>
> **复核与订正（2026-10-13，第三轮 —— `M-1`/`M-2` 落地后）**：① 订正上一轮的口径偏差——本文件曾把 `application.yml` 列为「平台规范真值」，实则**它自己也是陈旧源**（`default-model` 2/4，见 §六第 2b 行订正块）；真值口径改为**唯一 `llm_provider_model.is_default=1`、四方均可漂移**。② 修订实施入口章节号：`§14` → **`§11`**（`§14` 是「裁定记录」）。③ 同源扩散点由 3 处更正为 **4 处**（增 `application.yml`）。④ 落地状态：`M-2①` 全清 · `M-2③` 守卫已立并扩为**四方比对 + 覆盖完整性 + 双向反证**（`d20a0160`）· `M-1` 首波 9 条（`b580cff4`）· 余 `M-2②`/`M-3`~`M-9` 未动。
>
> **复核与订正（2026-10-13，第四轮 —— minimax 全系列换代）**：参考项目三处独立同口径表明 `MiniMax-M2.5`/`M2.7` 即将下线、现役统一为 **`MiniMax-M3`**（`magpie/internal/provider/workbuddy.go:91` + `factory.go:625` vs `:626` + `presets.go:273,281`；`octop-harness provider_template.json:267`；`deepseek-harness model-picker.expected.md:15`）。据此：① 新增迁移 `V108__minimax_model_deprecation.sql`（提升 `MiniMax-M3` 为默认 + 显式写能力列避免 `V52` 不回填丢 `web-search`；`M2.5`/`M2.7` 全系列 `LIKE 'MiniMax-M2.5%'`/`'MiniMax-M2.7%'` 禁用让位；事务内 dry-run 两遍验证幂等 + `ROLLBACK` 复位）；② 同步 **4 处载体**（`providerCatalog.ts` / `application.yml` / `FALLBACK_MODELS` / 守卫 `SEED_DEFAULT`）；③ **`MiniMax-M3.1-Flash-Preview` 已并入 `V108`**（用户提供 MiniMax 官方口径，2026-10-13 二次确认；初版曾判「不采纳」——仅在官方错误串中出现，见 §九「第四轮」订正行）；④ `-highspeed` 仍不采纳。详见 §九「第四轮」。
>
> **复核与订正（2026-10-13，第五轮 —— 预置覆盖口径实测订正）**：应「api-key 厂商是否已全部借鉴」之问，对 `presets.go` 做**精确解析**（顶层条目 = 恰好一个 tab 缩进的 `{ID:`；naive 计数会因条目内嵌子预置虚高到 87）。结论：① 顶层 **56 条**，`Kind` 实测分布 = `KindVendor` **33** / `KindRelay` **18** / `KindLocal` 4 / `KindRemote` 1（上一版目测「~28 / ~11」已订正）；② **51/56 含 `KeysURL`**（api-key 类）；③ helloai 现为 **9 条**（非旧述「4 条」），⇒ **已借鉴 9/56、余 47**（api-key 类 9/51）。④ 关键**前置约束**再度确认：`M-1` 余量卡在 `M-3`（模型目录种子）——无 `llm_provider_model` 行则 Agent 注册必 400。详见 §二 2.2/2.3 与 §六第 1 行。
>
> **实施侧入口**：`plan/HelloAI 借鉴落地实施计划.md` §11（`M-1`~`M-9` 的执行次序 / 验收 / 收口）。

---

## 〇、结论速览（先读这段）

| 主题 | magpie 做法 | helloai 现状 | 借鉴价值 | 借鉴优先级 |
|---|---|---|---|---|
| **供应商预置目录（presets）** | 56 条预置：每家厂商的协议端点 / models.dev 目录 id / 官网 / **API Key 获取页** | `providerCatalog.ts` 仅 **4 条** | **最高——结构同构、直接照搬扩充** | **PR0** |
| **模型目录（catalog）** | 动态同步 `models.dev` 全量目录 + 多源投票归一（context / max output / modality / 推理等级 / 价格） | `llm_provider_model` 有模型行但能力元数据靠手工回填 | **高——补模型清单 + 能力元数据** | **PR1** |
| **API Key 注册** | key 永不读环境变量、0600 存储、SHA-256 指纹脱敏标识、批量粘贴去重、多 key（主/备/off） | `credential_vault`（PLATFORM 级统一存 Secret，**安全性更强**） | **中——缺指纹标识与批量录入去重** | **PR2** |
| **Agent 注册** | 把「网关 provider + 模型能力表」**写进各 Agent 自身 config**（本地网关范式） | Agent 注册 = 平台侧声明 `modelType + capabilities + skills`（平台派单范式） | **低——范式不同，仅能力建模可借鉴** | **PR3** |

> **优先级口径**：本文件一律用 **`PR0`~`PR3`**（借鉴/审计优先级档）。《差距表》§0 编号表已裁定 `P0`~`P3` 的**唯一含义是「架构重构主线阶段」**，两者不可混用。

**三条红线（明确不抄）**：

1. **账号自动切换 / routing / failover**——用户明示不需要；多 key 只保留「主/备 + 手动」形态。
2. **改写 Agent 自身配置文件注入网关**——helloai 是平台派单，不是本地网关；机制完全不适用。
3. **价格 tier 分层计费**（长上下文分段计价）——helloai 无按 token 计费场景，不引入。

---

## 一、magpie 是什么、借鉴定位

**核心事实** `[实测]`（`README.zh-CN.md` 与仓库结构）：magpie 是单二进制 Go 本地网关，让多种 Agent（Claude Code / Codex / Gemini CLI / OpenCode / Kimi Code / Cursor CLI … 数十种）在菜单栏一键切换，**共用一个本地网关**，额度用完自动切下一账号。它本身不产生模型，而是**注册**各 Agent 能用的模型与各家供应商。

**架构三层** `[实测]`（仓库目录 `internal/`）：

| 层 | 目录 | 职责 | 对 helloai 的借鉴面 |
|---|---|---|---|
| 模型目录 | `internal/catalog/` | 知道**存在哪些模型**（models.dev 同步 + 聚合归一） | 模型能力元数据 |
| 供应商/Key | `internal/provider/` | 供应商端点、协议、**API Key 注册**、预置目录 | **presets 注册信息 + key 管理细节** |
| Agent 接入 | `internal/agent/` | 把网关写进各 Agent 自身配置 | 能力建模（借鉴有限） |

**范式差异（决定借鉴边界）** `[推断]`：magpie 是「本地网关」——把 Agent 的请求收拢到本机端口再转发给各家供应商；helloai 是「平台派单」——任务编排后由 Agent 自行领活执行。因此 **magpie 的"Agent 配置文件注入"机制不适用**，但其**"模型/供应商注册信息"是纯数据、与范式无关，可整体照搬**。

---

## 二、供应商预置目录（presets）——最高价值借鉴点

### 2.1 结构：PresetDef

**核心事实** `[实测]`（`internal/provider/presets.go:23-65`）：每个预置是一个 `PresetDef`，字段即「一家厂商怎么注册」：

```go
type PresetDef struct {
    ID        string   // 预置 id，如 "deepseek"
    Name      string   // 显示名
    Icon      string   // 品牌图标
    Kind      Kind     // vendor(厂商直连) / relay(中转站) / local(本地) / remote(远端 magpie)
    Chat      string   // OpenAI Chat Completions 端点
    Responses string   // OpenAI Responses 端点
    Anthropic string   // Anthropic Messages 端点
    Decide    string   // 决策 API（路由专用，可选）
    Catalog   string   // models.dev 目录 id（用于模型名与推理等级）
    Website   string   // 官网
    KeysURL   string   // **API Key 获取页**
    NoKey     bool     // 本地服务免 Key
    KeyHint   string   // key 输入框占位提示
    Regions   []Region // 区域端点选项（每区可带独立 KeysURL/Website/Catalog/Models）
    HeaderHints []string // 厂商文档推荐的可选请求头
    Only      string   // Plan 类 key 只暴露前缀匹配的模型
    Models    []string // Plan 的固定模型清单（厂商无 /models 列表时用）
    NoList    bool     // 厂商没有模型列表可拉
    Endpoint  string   // 需用户自填资源端点（Azure 式），非预置 URL
    Hosts     bool     // 厂商还服务别家模型（列表不能当制造商口径）
}
```

### 2.2 规模：56 条核心预置 + 订阅类补充

**核心事实** `[实测]`：`var presets`（`internal/provider/presets.go:115`）共 **56 条**（Grep `^\t{ID:` 计数；其中 52 个字符串 ID + 4 个常量 ID，后者在 `presets.go` 内的**使用行**为 `VertexPreset`(:134) / `AzurePreset`(:396) / `OMLXPreset`(:546) / `RemoteMagpiePreset`(:557)，其**声明处**分别在 `provider/vertex.go:54` / `provider/azure.go:24` / `provider/omlx_decide.go:12` / `provider/remote_magpie.go:67`）。另在 companion 文件补充**订阅类**预置（`cline.go` 4 条 / `zcode.go` 4 条 / `google.go` 17 条等）——这类与「账号切换」强相关，**不在本次借鉴范围**。

**覆盖矩阵**（按 `Kind` 归纳 `[实测]` `presets.go:115-550`）：

| Kind | 代表 | 条数 |
|---|---|---|
| `KindVendor` | Anthropic / OpenAI / Google / Vertex AI / DeepSeek / xAI / Kimi(±cn) / Kimi Code(±cn) / 智谱 / Z.AI / MiniMax(±cn) / StepFun(±cn) / 小米 / 百度千帆 / 腾讯云 / 华为云 / 火山 / 通义(±cn·token-plan) / Mistral / Groq / Bedrock / Azure / Ollama Cloud / TypeSafe | **33** |
| `KindRelay` | OpenRouter / OpenCode Go / ClinePass / OpenCode Zen / Kilo / CommandCode / Together / Fireworks / SiliconFlow / NVIDIA NIM / ModelScope / AiHubMix / PipeLLM / 302.AI / CherryIN / YYLX / Vercel Jev / Cloudflare Jev | **18** |
| `KindLocal` | Ollama / LM Studio / oMLX / MLX-Serve | 4 |
| `KindRemote` | Remote magpie（另一台机器上的 magpie） | 1 |
| **合计** | — | **56** |

> **口径（2026-10-13 精确复核订正）**：顶层条目 = 文件中**恰好一个 tab 缩进**的 `{ID:`（过滤条目内嵌的子预置——它们同为 `{ID:` 但缩进更深，naive 计数会虚高到 87）。上一版「vendor ~28 / relay ~11」为目测估值，实测为 **33 / 18**。另有 **51/56 条含 `KeysURL`**（api-key 类；无 Key 的 5 条 = `VertexPreset` / `ollama` / `lmstudio` / `OMLXPreset` / `mlx-serve`）。

### 2.3 与 helloai 的对照（同构、差数量）

**核心事实** `[实测]`（`helloai-ui/src/views/settings/providerCatalog.ts:27-46`）：helloai 的 `CatalogProvider` 结构与 magpie `PresetDef` **同构**——`providerCode / providerName / monogram / brandColor / tagline / protocolType / baseUrl / defaultModel / catalogId / apiKeyUrl`，其中 `baseUrl+protocolType` ≈ magpie 的单端点、`apiKeyUrl` ≈ magpie `KeysURL`、**`catalogId` ≈ magpie `Catalog`**、`defaultModel` ≈ magpie `Only/Models` 的最小形态。**2026-10-14 补**：`M-2②` 已补 `catalogId`（models.dev 目录 id，必填）并把 `apiKeyUrl` 改可选（对齐 magpie `omitempty`）；**多协议端点（magpie `Chat/Responses/Anthropic`）按 §七 与 `M-5` 同批处理，暂不落前端字段**——magpie 存完整端点、helloai 存基址，且 `extraConfig` 现为「只写不读」，提前落字段即复刻该反模式。

**核心事实** `[实测]`（`providerCatalog.ts:43-150`，9 个 `providerCode`）：**helloai 现有 9 条预置**（原 4 条 deepseek / moonshot / minimax / dashscope + `M-1` 首波 5 条 openai / anthropic / xai / mistral / groq），对 magpie 顶层 **56 条**为 **9/56（≈16%）**。**数量仍是最大差距，结构零阻力。**

**缺口分布（2026-10-13 实测：`presets.go` 顶层 56 条 ↔ `providerCatalog.ts` 9 条 ⇒ 余 47 条）**：

| Kind | 顶层 | 已借鉴 | 余 | 未借鉴的阻碍 |
|---|---|---|---|---|
| `KindVendor` | 33 | 9 | **24** | `google` / `zhipu` / `zai` / `tencent-cloud` 端点不符 `<base>/v1` 工厂约定（`v1beta/openai` / `paas/v4` / `plan/v3`）⇒ 需 `M-5`；`VertexPreset` / `AzurePreset` / `bedrock` 走云凭据链（**无 `KeysURL`**，非纯 api-key）；`-cn` 区域镜像 / token-plan 类与「账号·计划」强相关 |
| `KindRelay` | 18 | 0 | **18** | 聚合器**无单一默认模型** ⇒ `defaultModel` 无从取值，依赖 `M-3` 目录种子 |
| `KindLocal` | 4 | 0 | 4 | helloai 是平台侧配置，本地模型不在范围 |
| `KindRemote` | 1 | 0 | 1 | magpie 自指，不适用 |
| **合计** | **56** | **9** | **47** | 其中 api-key 类 51 条 ⇒ 已借鉴 **9/51** |

> 已借鉴 9 条的映射（magpie ID → helloai `providerCode`）：`anthropic`→`anthropic` · `openai`→`openai` · `deepseek`→`deepseek` · `xai`→`xai` · `moonshot`→`moonshot` · `minimax`→`minimax` · `qwen`→`dashscope` · `mistral`→`mistral` · `groq`→`groq`。**区域镜像（`-cn`）与 token-plan / code-plan 类不计入已借鉴**（`moonshot-cn` / `minimax-cn` / `kimi-code(-cn)` / `qwen-cn` / `qwen-token-plan` / `bailian-token-plan` / `stepfun-cn` 等）。
>
> ⚠️ **规模差距只是表象，真正的前置是「模型目录」**：新增预置若不同批补 `llm_provider_model` 行，密钥可配但 **Agent 注册必被拒**（`AgentSkillPolicyService.validateModelType:126` → `isModelAvailable` ⇒ 400）；而 magpie **不硬编码默认模型**（靠 models.dev 目录）⇒ `defaultModel` 无从照抄。故 `M-1` 的余量实质上**依赖 `M-3` 目录种子**（见 §七与 `plan` §11.2）。

**举例（DeepSeek 注册信息对照）** `[实测]`：

| 字段 | magpie（`presets.go:142-144`） | helloai（`providerCatalog.ts:45-54`） |
|---|---|---|
| Chat 端点 | `https://api.deepseek.com/v1` | `https://api.deepseek.com` |
| Anthropic 端点 | `https://api.deepseek.com/anthropic` | （无——helloai 仅 OPENAI_COMPATIBLE） |
| Responses 端点 | `https://api.deepseek.com/v1` | （无） |
| 目录 id | `deepseek` | （无） |
| Key 获取页 | `https://platform.deepseek.com/api_keys` | `https://platform.deepseek.com/api_keys`（一致） |
| 默认模型 | （由 models.dev 目录给出） | `deepseek-chat`（硬编码）→ **已于 2026-10-13 订正为 `deepseek-v4-flash`** |

> ⚠️ **端点字段的粒度差异（易踩，2026-10-13 复核补充）**：magpie 的 `Chat/Responses/Anthropic` 存的是**完整端点 URL**（如 `https://api.deepseek.com/v1`），helloai 的 `baseUrl` 存的是**基址**——「OpenAI 兼容模式实际端点为 `{base-url}/v1/chat/completions`」（`application.yml:305`）。**两者不可直接照抄**：本目录曾在 moonshot/dashscope 两条上误带了 `/v1` 后缀，会拼出 `/v1/v1/chat/completions`（已订正，见 §六第 2b 行）。

> **照搬结论**：magpie 56 条 `PresetDef` 可映射进 helloai `CatalogProvider`，主要差异处理：① 多协议端点 → 存 `llm_provider.extraConfig`（见 §6.4）—— **但注意 `extraConfig` 目前只写不读，本项须连带新增消费逻辑，见 §七 `M-5`**；② `defaultModel` → magpie 无硬编码，由模型目录给出（见 §3）；③ 端点字段**粒度不同**（见上），照搬时必须去掉 `/v1` 后缀；④ 本地免 Key / 区域 / HeaderHints 按需裁剪（helloai 是平台侧配置，暂不需要）。

---

## 三、模型目录（catalog）——动态同步 + 多源聚合

### 3.1 数据来源：不硬编码，同步社区目录

**核心事实** `[实测]`（`internal/catalog/catalog.go:397`）：模型目录来自社区维护的 **`https://models.dev/api.json`**，`Sync()`（:593-627）下载后缓存到 `~/.cache/magpie/models.json`（`CachePath()` :436）。

**回退链** `[实测]`（`catalog.go:438-451, 1362-1365`）：

```text
magpie 自己缓存的 models.dev 目录 → OpenCode 的 ~/.cache/opencode/models.json → 内置小清单兜底（保证选择器永不为空）
```

### 3.2 模型字段：能力元数据的完整集

**核心事实** `[实测]`（`catalog.go:25-115`）：每个模型一条 `Model`，关键字段：

| 字段 | 含义 | 对 helloai 的落点 |
|---|---|---|
| `ID / Name / Provider` | 模型 id / 显示名 / 所属厂商 | `llm_provider_model.model_name` |
| `Released` | 发布日期（排序用） | — |
| `Efforts` | 推理等级（none/low/medium/high/xhigh/max/ultra） | `capabilitySkills=["thinking"]` 的细化 |
| `Temperature *bool` | 是否拒绝 temperature/top_p | — |
| `Price` | input/output/cache_read/cache_write（USD/百万 token）+ 长上下文 tier | 不用（无计费场景） |
| `Context / MaxContext` | 上下文窗口（prompt 上限） | 可入 `extraConfig` |
| `Output` | 最大回复 token 数 | 可入 `extraConfig` |
| `Input/OutputModalities` | 支持 text/image/audio/video/pdf | 可推导 `web-search` / 图像能力 |
| `Images` | 是否接受图像输入 | 可入 `availableOptionalSkills` |

### 3.3 多源投票归一：同一模型多家报不同口径时的裁决

**核心事实** `[实测]`（`catalog.go:453-581`）：models.dev 里同一模型可能被多家厂商列出且口径不一（context 窗口、是否思考、是否支持图像），magpie 的 `load()` 对每个模型**按厂商逐条投票，取多数**（window 取 `mostGiven`、推理等级取 `mostListed`、名称取 `mostNamed`，:547-573），并把结果缓存为 `windows / outputs / efforts / thinks / images / names` 六张表。

**归一化技巧** `[实测]`（`catalog.go:1016-1077, 1217-1240`）：

- `bareID`：剥 `vendor/` 前缀、转小写（`openai/GPT-6-Sol` → `gpt-6-sol`）
- `canonical_model_id`：厂商说某别名其实是某个标准模型（DeepSeek 的 `deepseek-flash` = `deepseek/deepseek-v4.1-flash`）
- `renamed`：中转站加 `claude-` 前缀改名（`claude-deepseek-v4.1-flash`）→ 剥前缀还原
- `unmarked`：模型 id 尾部窗口标记（`[1m]`/`[200k]`）→ 拆出真实窗口
- `unprofiled`：Bedrock 区域前缀剥离

> **借鉴结论**：helloai 的 `llm_provider_model` 目前是**静态手工行**，能力元数据（`capabilitySkills` 默认 `["thinking"]`、`availableOptionalSkills` 默认 `["shell","code-review"]`）靠迁移/脚本回填。借 magpie 的 models.dev 数据可**自动灌满模型清单 + 能力元数据**，且 `PR1` 阶段可退化为「一次性种子 + 静态快照」，无需上动态同步。
>
> **反面佐证（2026-10-13 复核新增）**：正是「手工静态行 + 手写预置」这套机制，让 `providerCatalog.ts` 的 4 个 `defaultModel` 全部漂移成了目录外的陈旧值（其中一个已下线 404，见 §六第 2b 行）。**「手工回填」本身就是这个缺陷的成因**——这是 `M-3`（目录种子）从「补清单」升级为「治漂移」的实证依据。

---

## 四、API Key 注册与管理

### 4.1 存储与安全口径

**核心事实** `[实测]`（`internal/provider/provider.go:5-7`）：**key 永不从环境变量读取**；供应商就是用户输入的内容，存 `~/.config/magpie/providers.json`（**0600 权限**）。写入走 `writePrivate` + `lastgood.Keep`（:459-484），崩溃时可回退上一好世代。

### 4.2 Key 指纹（脱敏标识）

**核心事实** `[实测]`（`internal/provider/keys.go:68-74`）：key 的标识是 **SHA-256 前 5 字节的 hex**——界面只显示指纹与掩码（`Mask()` 只留头 4 尾 4，`provider.go:1501-1506`），既能区分"是不是同一个 key"，又永不暴露明文。

### 4.3 批量录入与去重

**核心事实** `[实测]`（`keys.go:165-178, 183-222`）：`SplitKeys` 支持一次粘贴多把 key（换行 / 逗号 / 分号 / 空格分隔，剥引号，自动去重），`AddKeys` 按指纹跳过已有 key、返回「新增 N 把 / 已存在 M 把」。

### 4.4 多 Key 与轮换（裁剪版可借鉴）

**核心事实** `[实测]`（`keys.go:21-32, 346-365, 488-517`）：一个供应商可持多把 key（主 key + `Keys[]`），每把带 `Name/Off/Protocol/Weight`；`UseKey` 换主、`ReplaceKey` 换值且**保留该 key 指纹关联的设置**（模型/代理/并发）。**routing/failover 部分（`Routing` 字段、quota 自动切换）用户明确不抄。**

### 4.5 与 helloai 对照

**核心事实** `[实测]`（`helloai-core/.../system/entity/LlmProvider.java:16` javadoc + `agent/entity/Agent.java:43-53`）：helloai 的凭据走 **`credential_vault`（PLATFORM 级统一存储）**，Agent 只留 consumerToken 工牌，密钥 AES-GCM 密文落库 + SHA-256 哈希列定位——**安全模型比 magpie 更强**（magpie 是本地单机 0600 文件，helloai 是服务端加密存储）。

**helloai 缺失、可补的两点**：

1. **Key 指纹标识**——`ApiKeyResponse` 目前只有 `apiKeyMasked`；加指纹可支持「同 key 去重」「多 key 区分」而不暴露明文。
2. **批量录入去重**——添加模型弹窗（`AddModelFormDialog.vue`）目前一次一把 key；magpie 的 `SplitKeys+AddKeys` 形态可直接移植。

---

## 五、Agent 注册（借鉴有限）

### 5.1 magpie 的做法：改写 Agent 自身配置

**核心事实** `[实测]`（`internal/agent/kimi.go:243-312`）：每个 Agent 一个注册文件，核心动作是**把网关写进 Agent 自己的 config**：

- 写 `providers.magpie` 表（`base_url` + `api_key`）到 Kimi 的 `config.toml`；
- 为每个目录模型写一张 `models."magpie/<provider>/<model>"` 表，带 `provider/model/max_context_size/display_name`；
- 按模型能力写 `capabilities`（`thinking` / `image_in` / `tool_use`，`kimi.go:96-108`）与推理等级 `support_efforts`（`kimi.go:175-196`）；
- 退出时把用户原 default 还原、删除 magpie 表（`dropMagpie`）。

### 5.2 为什么 helloai 不抄

**核心事实** `[实测]`（`helloai-core/.../agent/entity/Agent.java:38-95`）：helloai 的 Agent 注册是**平台侧声明**——`name/role/accessType/modelType/modelConfig/capabilities/skills/labels`，注册后由平台按任务 `required_skills` 匹配派单。二者范式不同：magpie 改的是「Agent 本地文件」，helloai 写的是「平台数据库」。

### 5.3 唯一可借鉴点：模型能力 → Agent 能力声明的建模

**核心事实** `[实测]`：magpie 把模型能力拆成 `capabilities`（thinking / image_in / tool_use）+ 推理等级 efforts 两张维度（`kimi.go:96-108,175-196`）；helloai 已有等价物——`llm_provider_model.capabilitySkills`（锁定技能，默认 `["thinking"]`）与 `availableOptionalSkills`（可选技能白名单，默认 `["shell","code-review"]`，联网模型回填 `web-search`）。**方向一致，借 magpie/models.dev 数据喂满即可，结构不动。**

---

## 六、helloai 现状 ↔ magpie 差距总表

| # | 主题 | magpie | helloai 现状 | 差距 | 借鉴优先级 |
|---|---|---|---|---|---|
| 1 | 供应商预置数 | 56 条 + 订阅补充 | 9 条（`providerCatalog.ts:43-150`，`M-1` 首波前为 4 条） | **≈6 倍差距（9/56）** | **PR0** |
| 2 | 预置信息维度 | 多协议端点 / 目录 id / KeysURL / 区域 | 单端点 / 无目录 id / KeysURL / 无区域 | 中 | PR0 |
| 2b | **预置取值正确性**（2026-10-13 复核新增） | **不硬编码模型**：由 models.dev 目录给出；端点按协议分列 | **4 条 `defaultModel` 全为陈旧值**、**3 条 `baseUrl` 与平台口径冲突**、1 条 `protocolType` 与内置声明相反 | **高——已是现网缺陷**（含 HTTP 404 直接根因） | **PR0** |
| 3 | 模型清单来源 | models.dev 动态同步 | 手工静态行（`llm_provider_model`） | 高 | **PR1** |
| 4 | 模型能力元数据 | context / max output / modality / efforts / price | `capabilitySkills` + `availableOptionalSkills`（手工回填） | 中 | **PR1** |
| 5 | Key 标识 | SHA-256 指纹 + 掩码 | 仅掩码（`apiKeyMasked`） | 低 | PR2 |
| 6 | Key 批量录入 | 粘贴多把自动去重 | 一次一把 | 低 | PR2 |
| 7 | 多 Key | 主/备/off/按协议 | 无 | 中（不做自动切换） | PR2 |
| 8 | 凭据存储 | 本地 0600 文件 | credential_vault 加密存储 | helloai 更强，**不抄** | — |
| 9 | 账号切换/failover | routing/fallback/quota | 无 | 用户明确**不抄** | — |
| 10 | Agent 注册 | 改写 Agent 本地 config | 平台声明式 | 范式不同，**不抄** | — |

> **第 2b 行的实测证据（2026-10-13 复核，全部带 `path:line`）**——「预置值陈旧」不是风格问题，是已在现网造成故障的缺陷：
>
> | 项 | `providerCatalog.ts` 原值 | 平台规范真值 | 证据 |
> |---|---|---|---|
> | deepseek `defaultModel` | `deepseek-chat` | `deepseek-v4-flash` | `V101` 注释：「目录内已无该模型……注册直接被拒」 |
> | moonshot `defaultModel` | `moonshot-v1-8k` | `kimi-k3` | `V61` 注释：「已下线（调用返回 404）……**验证失败（HTTP 404）的直接根因**」 |
> | dashscope `defaultModel` | `qwen-turbo` | `qwen3.7-plus` | `V101` 注释 + `V49:101-103` 目录行 |
> | minimax `defaultModel` | `MiniMax-Text-01` | `MiniMax-M3` | `V49:115`（`is_default=1`）→ **2026-10-13 第四轮订正为 `MiniMax-M3`**（`V108`） |
> | moonshot `baseUrl` | `https://api.moonshot.cn/v1` | `https://api.moonshot.cn` | `application.yml:296`；`:305` 注「实际端点为 `{base-url}/v1/chat/completions`」⇒ 原值拼出 `/v1/v1/...` |
> | dashscope `baseUrl` | `.../compatible-mode/v1` | `.../compatible-mode` | `application.yml:306`（同上） |
> | minimax `protocolType` | `OPENAI_COMPATIBLE` | `ANTHROPIC_COMPATIBLE` | `application.yml:301` + `V46:50`（两处独立同口径） |
>
> ⚠️ **「平台规范真值」的正确口径（2026-10-13 第三轮复核订正）**：上表末列引用的 `application.yml` **不是独立真值来源——它自己也会漂移**。`git show 794cd631^:helloai-start/src/main/resources/application.yml` 实测其 `default-model` **2/4 陈旧**（deepseek `deepseek-chat`、dashscope `qwen-plus`），已于 `794cd631` 订正。因此**唯一真值只能是 `llm_provider_model.is_default=1`**（`V49` 目录 + `V61`/`V101` 订正）；预置 / 迁移种子 / `application.yml` / **Java 兜底常量**四方**均可漂移**，由 `scripts/shell/verify-provider-catalog-parity.sh` 守卫（`d20a0160` 起含覆盖完整性校验与双向反证）。
>
> **写入路径**：`AddModelFormDialog.vue:311-313` 在**创建**预置供应商时直接取 `entry.defaultModel`（编辑分支 `:296` 只改 billingType、不碰 defaultModel）⇒ 删除后重加会把迁移已订正的值写回旧值。注意既有内置 provider 命中 `existingProvider` 分支（`:300-303` 只 `updateLlmProvider(billingType)`）⇒ 该风险仅在「provider 行不存在」时触发。
>
> **同源扩散点**（同一批陈旧值另有 4 处，均已一并订正，见 §九）：`LlmProviderKeyVerifyServiceImpl.java:45-51`（**密钥验证兜底模型表**——正是「验证失败 404」的命门）· `DeepSeekProviderChatClientFactory.java:25`（末位兜底）· `AgentProviderResolver.java:9`（javadoc 示例）· **`application.yml` 的 `default-model`（2/4）**。（其中 minimax 兜底值随后又因 `MiniMax-M2.5`/`M2.7` 全系列即将下线，于 `V108` 再次订正为 `MiniMax-M3`，见 §九「第四轮」。）

---

## 七、借鉴项索引（M-x.y）与优先级

> 只登记「可执行结论」的**内容与落点**，不承载排期与进度（《治理规则》§3.6）。进入实施时另立 `plan/` 或 `design/` 文档
> —— 实施侧已立：`plan/HelloAI 借鉴落地实施计划.md` §11（`M-1`~`M-9` 的执行次序 / 验收 / 收口）。
>
> **优先级一律 `PR0`~`PR3`**（禁用 `P0`~`P3`：后者在《差距表》§0 编号表中唯一含义为「架构重构主线阶段」）。

| 编号 | 借鉴项 | 内容摘要 | helloai 落点 | 借鉴优先级 |
|---|---|---|---|---|
| `M-1` | **供应商预置目录扩充** | 把 magpie 56 条 `PresetDef` 映射为 helloai `CatalogProvider`（vendor 直连优先，relay 按需）。⚠️ 新增条目**须同批补 `llm_provider_model` 模型行**，否则只能配密钥、不能注册 Agent | `helloai-ui/src/views/settings/providerCatalog.ts` + `llm_provider_model` 种子 | **PR0** |
| `M-2` | **预置信息正确性与维度补全** | ① **去陈旧硬编码**（4 条 `defaultModel` + 3 条 `baseUrl` + 1 条 `protocolType` + `application.yml` 2 条 ⇒ 共 **5 处载体 / D-a~D-f**，见 §六第 2b 行实证）；② **补模型目录 id（`catalogId`，已完 2026-10-14）+ `KeysURL` 可选化（已完）**；多协议端点按 §七 与 `M-5` 同批（未落）；③ **加「预置 ↔ 种子 `is_default=1` ↔ `application.yml` ↔ Java 兜底常量」四方一致性守卫 + 覆盖完整性**（防再次漂移，且防新增条目静默不受保护） | `providerCatalog.ts` + `LlmProviderKeyVerifyServiceImpl.java:45-51` + `DeepSeekProviderChatClientFactory.java:25` + `application.yml` + `scripts/shell/verify-provider-catalog-parity.sh` | **PR0** |
| `M-3` | **模型目录种子** | 借 models.dev 快照生成各内置 provider 的模型清单（id/context/output/modality），消除「手工静态行」。**已完（2026-10-14，`V109`）**：9 家 150 行；口径 = `text 输出 + context>0 + status≠deprecated + tool_call=true`；并为 `M-1` 首波 5 家补 `llm_provider` 行 | `llm_provider_model`（一次性种子 SQL） | **PR1** |
| `M-4` | 模型能力元数据回填 | 由 modality/efforts 推导 `capabilitySkills` / `availableOptionalSkills`（thinking/image/web-search）。**已完（2026-10-14，`V110`）**：**订正承载形态** —— 按 `D-2026-10-13-9` 裁定的**形态乙**，落点是**新增能力谓词列 `capabilities`(JSONB)**（`{input,output,reasoning,toolCalling,webSearch}`），**而非**继续回填 V52 的 `capabilitySkills`/`availableOptionalSkills`（该两列待 `M-9` 切换消费方后退役）；模态/reasoning/toolCalling ← models.dev，`webSearch` ← V52 已确立的 provider 口径 | `llm_provider_model.capabilities`（新列）+ 5 条 `DO $$` 断言 | **PR1** |
| `M-5` | 多协议端点承载 | magpie 4 协议 → helloai 2 协议的补充端点（Anthropic/Responses/Gemini）放扩展位。**⚠️ 落点是"半成品"**：`extraConfig` 目前是**只写不读的透传字段**（全仓 0 处消费，`completionsPath`/`messagesPath` 仅存在于 `V46:27,63` 列注释与 `LlmProvider.java:59` javadoc），故本项**必须包含"新增 `extraConfig` 消费逻辑"**，否则只是"登记进 JSONB 但运行时不认" | `llm_provider.extraConfig`（表结构不用改，**但须新增读取方**：`OpenAiCompatibleProtocolFactory:82` / `AnthropicCompatibleProtocolFactory:83` 的 `baseUrl` 装配处） | PR1 |
| `M-6` | Key 指纹标识 | SHA-256 前缀指纹用于同 key 去重 / 多 key 区分（不暴露明文） | `ApiKeyResponse` + 添加模型弹窗 | PR2 |
| `M-7` | Key 批量录入去重 | 粘贴多把 key 自动拆分去重 | `AddModelFormDialog.vue` + `saveLlmProviderApiKey` 批量接口 | PR2 |
| `M-8` | 多 Key（主/备，无自动切换） | 一个 provider 多把 key，手动切换/停用；**不做** routing/failover | `llm_provider` 关联 key 表或 credential_vault 多条目 | PR2 |
| `M-9` | 模型能力 → Agent skills 细化 | efforts/modality 驱动的能力声明（当前只有 thinking 开关） | `Agent.skills` 注册校验 + `llm_provider_model` 白名单 | PR3 |

**协议映射备注** `[推断]`：magpie `Protocol`（chat/responses/anthropic/gemini，`provider.go:33-40`）与 helloai `ProtocolType`（`OPENAI_COMPATIBLE`/`ANTHROPIC_COMPATIBLE`）是**两档粒度**。PR0 阶段只需把「OpenAI 兼容」与「Anthropic 兼容」两类端点对上，其余端点进 `extraConfig` 备用，不扩枚举（并与 `M-5` 的"须新增消费逻辑"同批处理）。

---

## 八、明确不抄（N-x）

> **编号说明**：`N-x` 为本文件**局部**编号（不承载任务、不进入排期）。
> 注意 `doc/archive/implemented/HelloAI_全面复测清单（2026-10-06）.md:123` 另有一个**同名** `N-1`（复测用例号，archive 冻结不改），
> 二者含义不同，已在《差距表》§0 编号表登记说明以防误读。

| 编号 | 不抄项 | 理由 |
|---|---|---|
| `N-1` | 账号一键切换 / 自动 failover / routing / quota 轮换 | 用户明示不需要；多 key 仅保留手动主/备 |
| `N-2` | 改写 Agent 本地配置文件注入网关 | 本地网关范式，helloai 是平台派单范式，机制不适用 |
| `N-3` | 价格 tier 分段计费 | helloai 无按 token 计费场景 |
| `N-4` | Key 存本地明文文件 | helloai credential_vault 加密存储更强，保持现状 |
| `N-5` | **端点 URL 原样照抄**（magpie 存完整端点，helloai 存基址） | 粒度不同，直接照抄会在 moonshot/dashscope 上拼出 `/v1/v1/chat/completions`（已实证，见 §六第 2b 行） |

---

## 九、事实核查表

> 勘察口径：`[实测]` 附 `路径:行号`；`[推断]` 单列。全部基于本地源码读取（2026-10-13）。
>
> **2026-10-13 二次复核**：对全文 magpie 侧 15+ 处、helloai 侧 10+ 处锚点逐条抽验，**原表全部命中**；下表下半段为复核**新增/订正**条目（标注「复核」）。

| 论断 | 口径 | 出处 |
|---|---|---|
| magpie 是本地网关、Agent 菜单栏切换、共用一个网关 | `[实测]` | 仓库 `README.zh-CN.md` |
| `PresetDef` 结构含协议端点/Catalog/KeysURL/Regions/HeaderHints/Models | `[实测]` | `magpie/internal/provider/presets.go:23-65` |
| presets.go 核心预置 56 条（52 字符串 ID + 4 常量 ID） | `[实测]` | `presets.go:115`（Grep `^\t{ID:` 计数 56） |
| Vertex/Azure/oMLX/Remote 4 个常量 ID 预置（`presets.go` 内为**使用行**） | `[实测]` | 使用 `presets.go:134/396/546/557`；**声明** `provider/vertex.go:54` / `azure.go:24` / `omlx_decide.go:12` / `remote_magpie.go:67`（复核补） |
| DeepSeek 预置：chat+responses+anthropic 三端点与 KeysURL | `[实测]` | `presets.go:142-144` |
| magpie Protocol 枚举 = chat/responses/anthropic/gemini | `[实测]` | `provider/provider.go:33-40`（复核补） |
| 订阅类预置分散在 companion 文件（cline/zcode/google） | `[实测]` | `presets.go` 同目录 `cline.go:4 / zcode.go:4 / google.go:17` |
| 模型目录来自 models.dev/api.json，Sync 下载缓存 | `[实测]` | `magpie/internal/catalog/catalog.go:397,436,593-627` |
| 回退链：magpie 缓存 → OpenCode 缓存 → 内置兜底 | `[实测]` | `catalog.go:438-451` |
| Codex 模型清单读 Codex CLI 的 models_cache.json | `[实测]` | `catalog.go:1362-1406` |
| Model 字段集（context/max/output/modalities/images/efforts/price） | `[实测]` | `catalog.go:26-115` |
| 价格 tier 分段（长上下文） | `[实测]` | `catalog.go:126-154` |
| 多源投票归一（windows/outputs/efforts/thinks/images/names） | `[实测]` | `catalog.go:453-581`（投票 :481-520，落表 :547-574） |
| 归一化：bareID/canonical/renamed/unmarked/unprofiled | `[实测]` | `catalog.go:1016-1077,1217-1240` |
| key 永不读环境变量、0600 存储 | `[实测]` | `magpie/internal/provider/provider.go:5-7,386` |
| Key 指纹 = SHA-256 前 5 字节 hex | `[实测]` | `magpie/internal/provider/keys.go:68-74` |
| Key 掩码只留头 4 尾 4 | `[实测]` | `provider.go:1501-1506` |
| 批量粘贴拆分去重 + 按指纹跳过已有 | `[实测]` | `keys.go:165-178,183-222` |
| 多 Key（主/备/off/protocol/weight）与 UseKey/ReplaceKey | `[实测]` | `keys.go:21-32,346-365,488-517` |
| Agent 注册 = 改写 Agent 自身 config（kimi 例） | `[实测]` | `magpie/internal/agent/kimi.go:243-312` |
| Kimi 模型表带 capabilities（thinking/image_in/tool_use）与 efforts | `[实测]` | `kimi.go:96-108,175-196` |
| helloai 供应商目录 9 条预置（`M-1` 首波前为 4 条） | `[实测]` | `helloai-ui/src/views/settings/providerCatalog.ts:43-150` |
| helloai `CatalogProvider` 与 magpie `PresetDef` 同构 | `[实测]` | `providerCatalog.ts:27-41` |
| helloai `llm_provider` 字段（code/name/protocol/baseUrl/defaultModel/extraConfig） | `[实测]` | `helloai-core/.../system/entity/LlmProvider.java:30-61` |
| helloai 模型能力字段 capabilitySkills/availableOptionalSkills | `[实测]` | `helloai-core/.../system/entity/LlmProviderModel.java:48-49,56-57` |
| helloai Agent 注册为平台声明式（modelType/capabilities/skills） | `[实测]` | `helloai-core/.../agent/entity/Agent.java:38-95` |
| helloai 凭据走 credential_vault（PLATFORM 级），Agent 只存工牌 | `[实测]` | `LlmProvider.java:16` javadoc + `Agent.java:43-53` |
| helloai 添加模型弹窗一次一把 key + 保存后最小探测验证 | `[实测]` | `helloai-ui/src/views/settings/AddModelFormDialog.vue:290-346` |
| 协议两档粒度映射（4 协议 → 2 ProtocolType）为推断 | `[推断]` | 本节 §七「协议映射备注」 |
| **【复核】helloai `baseUrl` 语义 = 基址**（实际端点 `{baseUrl}/v1/chat/completions`） | `[实测]` | `application.yml:305` 注释 + `OpenAiCompatibleProtocolFactory.java:80-82` |
| **【复核】`extraConfig` 全仓无消费点**（仅字段/DTO 声明） | `[实测]` | `LlmProvider.java:61`（core 内唯一出现）+ DTO `CreateLlmProviderRequest:49`/`LlmProviderResponse:47`/`UpdateLlmProviderRequest:40` |
| **【复核】`completionsPath`/`messagesPath` 仅存在于注释**（0 处代码消费） | `[实测]` | `V46:27` 列注释、`V46:63` `COMMENT ON COLUMN`、`LlmProvider.java:59` javadoc |
| **【复核】预置 4 条 `defaultModel` 全部为目录外陈旧值** | `[实测]` | 原值见 `providerCatalog.ts`（订正前 `:36,47,58,69`）；目录真值 `V49:72-73,85-89,101-103,115`（moonshot 默认位另经 `V61` 改为 `kimi-k3`）；订正依据 `V61`（moonshot 下线 404 直接根因）与 `V101`（deepseek/dashscope 注册被拒） |
| **【复核】预置 3 条 `baseUrl` 与 yml/种子口径冲突** | `[实测]` | yml 真值 `application.yml:296,301,306`；种子真值 `V46:49,50`,(dashscope `V46:51`) |
| **【复核】预置 minimax `protocolType` 与平台内置相反** | `[实测]` | 预置原为 `OPENAI_COMPATIBLE`；平台为 `ANTHROPIC_COMPATIBLE`（`V46:50` + `application.yml:301` + `:285` 注释「minimax 走 Anthropic 兼容接口」） |
| **【复核】预置值存在写回路径**（创建预置供应商时直接用 `entry.defaultModel`） | `[实测]` | `AddModelFormDialog.vue:311-313`（编辑分支 `:296` 只改 billingType） |
| **【复核】密钥验证兜底模型表同源陈旧** | `[实测]` | `LlmProviderKeyVerifyServiceImpl.java:45-51`（moonshot 已在 V61 时修、deepseek/minimax/dashscope 三条于 2026-10-13 一并订正） |
| **【复核】DeepSeek 末位兜底模型同源陈旧** | `[实测]` | `DeepSeekProviderChatClientFactory.java:25`（已订正） |
| **【复核】已按上表订正 5 处载体** | `[实测]` | `providerCatalog.ts`（当时 4 条预置；`M-1` 首波后为 9 条）、`LlmProviderKeyVerifyServiceImpl.java:45-51`、`DeepSeekProviderChatClientFactory.java:25`、`AgentProviderResolver.java:9`、**`application.yml`（deepseek/dashscope `default-model`）**；验证：`vue-tsc --noEmit` EXIT=0、`helloai-core` 2037 例 0 失败、`LlmProviderKeyVerifyServiceTest` 4 例全绿 |
| **【第三轮】`application.yml` 自身即陈旧源**（`default-model` 2/4），故不得作为「规范真值」 | `[实测]` | `git show 794cd631^:helloai-start/src/main/resources/application.yml`（`DEEPSEEK_CHAT_MODEL:deepseek-chat` / `DASHSCOPE_CHAT_MODEL:qwen-plus`）；订正 `794cd631` |
| **【第三轮】一致性守卫已扩为四方 + 覆盖完整性 + 双向反证** | `[实测]` | `scripts/shell/verify-provider-catalog-parity.sh`（正验 EXIT=0 / `--inject-deviation` EXIT=2 / `--inject-catalog` EXIT=1）；`d20a0160` |
| **【第三轮】`M-1` 首波新增 5 条预置**（openai/anthropic/xai/mistral/groq）**无 `llm_provider_model` 种子行** | `[实测]` | `V49` 仅 4 家（deepseek/moonshot/minimax/dashscope），后续迁移无新增 → `AgentSkillPolicyService.validateModelType:126` → `isModelAvailable`（`LlmProviderModelQueryServiceImpl:131-142`）⇒ `M-3` 前注册 Agent 必 400「模型不可用」 |
| **【第四轮】minimax 全系列换代：`M2.5`/`M2.7` 即将下线，现役统一为 `MiniMax-M3`** | `[实测]` | 参考项目三处独立同口径：`magpie/internal/provider/workbuddy.go:91`（`{ID:"minimax-m3", Context:512_000}`）· `magpie/internal/provider/factory.go:625`（`minimax-m3` **可用**）vs `:626`（`minimax-m2.7` **不可用**）· `magpie/internal/provider/presets.go:273,281`（`minimax-m3` 列于 `minimax-m2.7` 之前）· `octop-harness-main/src/octop_harness/providers/provider_template.json:267`· `deepseek-harness-master/apps/web/tests/expected/models-settings/model-picker.expected.md:15`（`checkbox "MiniMax-M3" [checked]`） |
| **【第四轮】minimax 换代已落 4 处载体 + 1 迁移** | `[实测]` | `providerCatalog.ts:78`（`defaultModel:'MiniMax-M3'`）· `application.yml:302`（`MINIMAX_CHAT_MODEL:MiniMax-M3`）· `LlmProviderKeyVerifyServiceImpl` `FALLBACK_MODELS`（`minimax→MiniMax-M3`）· `scripts/shell/verify-provider-catalog-parity.sh` `SEED_DEFAULT[minimax]` + 证据锚点 · 迁移 `V108__minimax_model_deprecation.sql`；守卫正验 `EXIT=0`（`minimax 一致 defaultModel='MiniMax-M3'（含 Java 兜底）`） |
| **【第四轮】`MiniMax-M3.1-Flash-Preview` 已并入 `V108`（结论订正）** | `[实测]` | MiniMax 现役两个模型（用户 2026-10-13 提供官方口径）：`MiniMax-M3.1-Flash-Preview`（原生多模态 / 1M 上下文 / Frontier Coding / 思考深度可调）+ `MiniMax-M3`。**订正**：初版判「不采纳」的依据是「全仓仅现于官方错误串（`magpie/internal/gateway/anthropic.go:354` + `gateway_test.go:680` + `wire_effort_test.go:108`）」——该证据仍成立，但**不足以否定其存在**（属「参考项目未启用」而非「不存在」）。故并入 `V108` 第 5 条：`enabled=1`、`is_default=0`（默认仍为 M3）、`sort_order=6`、能力列显式写 `["thinking"]` / `["shell","code-review","web-search"]` |
