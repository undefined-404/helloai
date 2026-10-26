# HelloAI LLM 额度与用量监控设计

> **Status**: `Planned`
> **Scope**：
> - **含**：外部供应商的**余额查询**（DeepSeek / Moonshot / MiniMax / 博查）+ 余额快照缓存 + 只读悬浮控件。
> - **不含**：AGENT 级凭据余额（22 条）、通义千问（无公开余额接口）、调度联动、阈值告警、MCP 工具、峰谷价精算、token 五分量拆分。
> **差距锚点**: `G-026`
> **最后更新**: 2026-10-26
>
> 本文是**设计**（怎么建）。实施顺序与批次另立 `plan/`。外部项目的详细调研结论见 §2，其原文仓库不再单独归档（结论已在此固化，拆分存储会产生「设计引用了过期调研」的漂移）。

---

# 1. 目标与边界

## 1.1 要解决的问题

平台同时接入 4 家外部供应商（LLM 3 家 + 搜索 1 家），余额分散在各自控制台。当前只有「调用失败」这一种方式能感知额度耗尽，属于事后发现。

**目标**：在不登录各家控制台的前提下，一个悬浮控件内看到「还剩多少钱、上次什么时候查的、哪家快没了」。

## 1.2 成功的判据

| 判据 | 度量 |
|---|---|
| 不用登录控制台 | 4 家中 3 家 LLM + 1 家搜索的余额都可在平台内看到 |
| 数据可信 | 每个数字都带「抓取时间」；抓取失败时显示上次值 + 失败标记，**绝不显示 0** |
| 不假装精确 | 无接口的供应商（通义）不出现在列表里，不做估算伪装 |
| 可定位 | 某家余额查询失败时，能说出失败原因（HTTP 码 / 超时 / 密钥缺失） |

---

# 2. 依据：外部调研结论

## 2.1 同类开源项目（形态与架构参考）

| 项目 | 技术栈 | 形态 | 对本次的可用价值 |
|---|---|---|---|
| `SaraiNoQ/token-monitor`（Token Monitor） | Electron / Node | 桌面悬浮气泡 | **悬浮 UI 形态**（折叠胶囊 / 点击展开 / 拖动吸附）；但其数据源是**本地日志文件**（`~/.claude/projects/` 等），Web 端用不上 |
| `open-tecmz/quotapanel`（QuotaPanel，Apache-2.0） | Go + Wails + Vue3 | 桌面面板 | ⭐ **架构范式**：`backend/quota/` 每供应商一适配器 + 统一查询服务 + 抓取规则外置 JSON；5 分钟轮询 |
| `hanmumuHL/check_balance` | Python CLI / MCP | 命令行 | ⭐ **端点清单**：14 家供应商的余额 API 与可用性分类，可直接当调研结论用 |
| `ruanyf/weekly#12604` 中的 ai-credit-bar 讨论 | Swift | 菜单栏 | `ProviderRegistry + ProviderFetching` 的注册式抽象，对应本文 §4.2 的 SPI |

**结论**：三个项目都是桌面应用，**没有可直接复用的代码**。可复用的是两件事：① 适配器 + 注册表的架构；② 余额端点清单。

## 2.2 余额接口清单（本文采用的版本）

| 供应商 | 端点 | 认证 | 返回结构 | 状态 |
|---|---|---|---|---|
| DeepSeek | `GET https://api.deepseek.com/user/balance` | `Bearer <key>` | `data.balance_infos[].total_balance`（含 `currency` / `granted_balance` / `topped_up_balance`） | ✅ 官方文档 |
| Moonshot / Kimi | `GET https://api.moonshot.cn/v1/users/me/balance` | `Bearer <key>` | 可用余额（`available_balance`） | ✅ |
| MiniMax | `GET https://api.minimaxi.com/v1/token_plan/remains` | `Bearer <key>` | 剩余额度（次数 / 百分比，非金额） | ✅ |
| 博查 | `GET https://api.bochaai.com/v1/fund/remaining` | `Bearer <key>` | `data.remaining`（单位待实测确认，见 §7） | ✅ 官方文档 |
| 通义千问（DashScope） | 无公开余额接口 | — | — | ❌ 仅百炼控制台；需阿里云 AK/SK 签名，本平台只有 `sk-` key |

> 通义的做法：**不显示**。用户已裁定（2026-10-26）。原因是"显示一个无法取数的行"比"不显示这一行"更容易被视为缺陷。

## 2.3 三条被采纳的外部经验

1. **「官方值」与「估算值」必须在 UI 上分开** —— 本次不展示估算值，因此规避了这个问题；未来做 token 估算时需保留该区分。
2. **抓取失败保留上一次读数，绝不回填 0** —— 余额 0 会引发错误的处置动作（如误判额度耗尽）。
3. **适配器 + 注册表，而非 if/else 分支** —— 加供应商只加一个类，不改调用方。

---

# 3. 现状实测（决定设计形态的事实）

以下均为 2026-10-26 服务器库实测，非推断。

## 3.1 凭据分布

`credential_vault`（`deleted=0`）按 `provider / owner_type`：

| provider | PLATFORM | AGENT |
|---|---|---|
| deepseek | 3 | 16 |
| moonshot | 3 | 3 |
| minimax | 2 | 2 |
| dashscope | 2 | 1 |

**决策**：M1 **只查 PLATFORM 级**（10 条 → 4 家）。AGENT 级 22 条全查会产生数十次外部请求，且那属于「谁的 Agent 在花钱」，是另一个问题。

## 3.2 token 数据现状（决定了用量侧做不了什么）

```
agent_event.agent_completed            18 条（6 空 source / 6 EXTERNAL / 6 INTERNAL）
  payload->'usage'                      0 条   ← 五分量拆分未落库
  典型 payload  {"source":"INTERNAL","tokens":10455,"executor":"RuntimeTurnExecutor","finishReason":"STOP"}

agent_execution_record                 13 条，11 条有 token，合计 166,889
时间跨度                               2026-10-07 ~ 2026-10-26（19 天）
```

**结论**：
- token 只有单值 `tokens`，**无 prompt/completion 拆分、无 `model` 字段** ⇒ 「按模型看用量」「缓存命中率」不可做。
- 19 天 18 条事件 ⇒ 埋点本身不完整，token 看板在补埋点前没有决策价值。

**因此 M1 的用量侧只做一件事**：博查的消耗（用余额基线差值算，因为它只被本平台调用，差值即消耗）。LLM 的 token 总量仅作参考展示，不做趋势与拆分。

## 3.3 可复用的基建

| 需要的能力 | 现有 | 用法 |
|---|---|---|
| HTTP 客户端 | `BochaRerankService` 已有出站调用范式 | 沿用同一写法，不引入新 HTTP 框架 |
| 分布式缓存 | Redisson `RBucket`（已用于 MCP 会话、心跳） | 余额缓存用 Redis 而非本地，**多实例不重复打外部接口** |
| 熔断 | Resilience4j（已配 `agentDispatch` 实例） | 新增 `quotaFetch` 实例 |
| 加密凭据 | `CredentialCryptoService` + `credential_vault` | 直接解密使用，不新增存储 |
| 定时任务 | `helloai-job` + ShedLock | 采集任务落 `helloai-job`，多实例单飞 |

---

# 4. 设计

## 4.1 落点与分层

```
helloai-core/src/main/java/com/helloai/core/system/quota/
├── BalanceProvider.java              SPI 接口
├── BalanceSnapshot.java              统一快照（含失败态）
├── BalanceProviderRegistry.java      注册表 + 按 key 路由
├── BalanceCredentialResolver.java    密钥来源抽象（vault / sys_config）
├── impl/
│   ├── DeepSeekBalanceProvider.java
│   ├── MoonshotBalanceProvider.java
│   ├── MiniMaxBalanceProvider.java
│   └── BochaBalanceProvider.java
└── BalanceQueryService.java          编排：取凭据 → 查缓存 → 调 provider → 写快照

helloai-api/src/main/java/com/helloai/api/controller/admin/QuotaController.java
    GET  /api/admin/quota/overview          聚合 4 家
    POST /api/admin/quota/refresh/{key}     手动刷新单家

helloai-ui/src/components/quota/QuotaFloatWidget.vue   挂在 MainLayout.vue
```

**不新建 Maven module**。理由：新增模块要改 `scripts/ci/check-arch-freeze.sh` 的 `DOMAINS` 数组并更新基线；`system` 域已是平台级能力的落点（`system/backup` 同型），`quota` 归入其下不破坏依赖方向（`system` 处于链尾，只依赖更低层）。

## 4.2 SPI 契约

```java
public interface BalanceProvider {
    /** 供应商标识，与 credential_vault.provider 对齐（小写） */
    String key();

    /** 是否支持查询；不支持时 overview 会跳过而非报错 */
    boolean supports(BalanceQueryContext ctx);

    /** 查询；任何失败都返回 failed(...) 而非抛异常 */
    BalanceSnapshot fetch(BalanceQueryContext ctx);
}
```

**三条硬约束**（每条都有具体原因）：

| 约束 | 原因 |
|---|---|
| `fetch` **不抛异常**，失败返回 `BalanceSnapshot.failed(reason)` | 单家失败不能拖垮整个 overview；注册表遍历时不写 try/catch |
| `key()` 与 `credential_vault.provider` 同名 | 两个标识体系分叉后，加供应商要改两处 |
| `supports()` 独立于 `fetch()`，用于「有凭据但没有接口」的情形 | 通义未来若补上 AK/SK，改成 `supports=true` 即可，不动调用方 |

`BalanceSnapshot` 字段：

```java
record BalanceSnapshot(
    String providerKey,
    boolean available,          // 是否有可用的金额/额度数
    BigDecimal amount,          // 金额（available=true 时非空）
    String currency,            // CNY / USD / null（额度型无币种）
    String rawUnit,             // 额度型供应商的单位描述，如 "次" / "百分比"
    BigDecimal quotaPercent,    // 额度型供应商的剩余百分比（MiniMax）
    Instant fetchedAt,
    String error,               // available=false 时的原因
    Source source               // LIVE / CACHED / FAILED
) {}
```

`Source` 三态是给前端用的：`LIVE` = 本次实时查；`CACHED` = 缓存命中；`FAILED` = 抓取失败且无缓存。
**前端必须按 `source` 决定是否显示「数值」**——`FAILED` 且无缓存时显示「—」而非 0。

## 4.3 密钥来源抽象

这次有个特殊点：博查的 key **不在** `credential_vault`。

| 供应商 | 密钥位置 | 读取器 |
|---|---|---|
| deepseek / moonshot / minimax | `credential_vault`（`owner_type=PLATFORM`, `credential_type=API_KEY`） | `CredentialCryptoService.decryptFromBase64` |
| **博查** | `sys_config["web-search.bocha.api-key"]`（加密） | `WebSearchCredentialKeyStore` |

因此需要一层：

```java
public interface BalanceCredentialResolver {
    /** 返回可直接放进 Authorization 头的明文 key；无凭据返回 Optional.empty() */
    Optional<String> resolve(String providerKey);
}
```

两个实现：
- `VaultCredentialResolver`：查 `credential_vault`，取 `ACTIVE` 那条（与 `PlatformProviderConfigService` 同源，不另建查询逻辑）
- `SysConfigCredentialResolver`：委托 `WebSearchCredentialKeyStore`（**不重写解密逻辑**）

**注册表按 provider 选择 resolver**，而不是让每个 provider 自己去找 key —— 因为「key 在哪」是部署形态问题，不是协议问题。

## 4.4 数据模型

**只建一张表**：

```sql
CREATE TABLE llm_balance_snapshot (
    id            BIGSERIAL PRIMARY KEY,
    provider_key  VARCHAR(64)  NOT NULL,
    currency      VARCHAR(8),
    amount        NUMERIC(20,2),
    quota_percent NUMERIC(5,2),
    raw_payload   JSONB,                 -- 原始响应，出问题时不用重新抓
    fetched_at    TIMESTAMPTZ  NOT NULL,
    error         VARCHAR(512),
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_llm_balance_snapshot_provider_time
    ON llm_balance_snapshot (provider_key, fetched_at DESC);
```

**为什么不建另外两张表**（对外部建议的有意否决）：

| 建议的表 | 不建的理由 |
|---|---|
| `llm_account`（厂商/名称/凭据引用/开关） | `credential_vault` 已有 PLATFORM 级凭据；再建账号表 = **同一事实两处写**。名称/开关可放 `sys_config` 或直接由 `provider_key` 约定 |
| `llm_usage_daily`（日聚合 token + 成本） | token 已在 `agent_event` / `agent_execution_record`；日聚合表与事件源**双写必然漂移**，且事件补录后无法自动重算 |

**保留策略**：只保留最近 30 天的快照（定时任务清理）。历史趋势需要时从快照表按天取末条，不额外聚合。

## 4.5 缓存与刷新

```
用户打开悬浮球
  → 前端 GET /api/admin/quota/overview
  → BalanceQueryService
       → Redis RBucket 命中（TTL 60s）→ 返回 CACHED
       → 未命中 → 遍历 registry（4 家）→ 并发 fetch（有超时）
            → 成功：写快照表 + 写 Redis（60s）
            → 失败：写快照表（error 非空）+ Redis 存失败态（30s，避免失败时反复重试）
```

**参数**（都可配，给默认值）：

| 参数 | 默认 | 理由 |
|---|---|---|
| 缓存 TTL | 60s | 前端 30s 轮询时，一半命中缓存；余额是日级变化数据 |
| 抓取超时 | 5s | 与 `deepseek.connect-timeout-ms` 同量级 |
| 定时采集间隔 | 15 min | 4 家 × 96 次/天 = 384 次/天，可控 |
| 失败缓存 TTL | 30s | 失败时不要每次点开都重打 |
| 熔断 | `quotaFetch`：失败率 50% / 窗口 10 / 断开 60s | 单家长期挂掉时不拖慢 overview |

**并发**：4 家**并行**查询（`CompletableFuture` + 统一超时），overview 总耗时 ≈ 最慢一家（≤5s），而非 4 家之和。

## 4.6 前端形态

```
折叠态（默认）
┌──────────────┐
│ ¥ 128.40  ●  │   ← 总额（可配置为「最紧张那家」）+ 状态色点
└──────────────┘

展开态
┌─────────────────────────────┐
│ 额度                    刷新 │
├─────────────────────────────┤
│ DeepSeek    ¥ 92.30   2 分钟前 │
│ Moonshot    ¥ 36.10   2 分钟前 │
│ MiniMax     剩余 68%  2 分钟前 │  ← 额度型，非金额
│ 博查        ¥ 100.00  刚刚     │
├─────────────────────────────┤
│ 今日搜索消耗  ¥ 0.40（基线差值） │
└─────────────────────────────┘
```

**交互约定**：

| 项 | 约定 |
|---|---|
| 位置 | 右下角，可拖动，位置存 `localStorage` |
| 刷新 | 打开面板时刷新一次 + 30s 轮询（页面可见时）；隐藏时不轮询 |
| 失败显示 | 灰色文字「上次 ¥92.30 · 刷新失败」，不显示 0 |
| 额度型供应商 | 显示百分比 + 进度条，不强行换算成金额 |
| 开关 | 系统设置里的一个 `sys_config` 布尔项，关闭后不渲染组件（也不发请求） |

**不采用 SSE**。理由：余额是 15 分钟级数据，SSE 要处理连接管理、重连、多实例广播；而项目已经踩过「MCP 会话跨实例」的坑。30s 轮询 + 60s 后端缓存，代码量不到 SSE 的 1/3，且无状态。

---

# 5. 供应商适配矩阵

| 供应商 | 金额型 | 额度型 | 密钥源 | M1 是否显示 |
|---|---|---|---|---|
| DeepSeek | ✅ `total_balance` | — | vault | ✅ |
| Moonshot | ✅ `available_balance` | — | vault | ✅ |
| MiniMax | — | ✅ 剩余次数/百分比 | vault | ✅ |
| 博查 | ✅ `remaining` | — | sys_config | ✅ |
| 通义千问 | ❌ | ❌ | vault（有 key 无接口） | ❌ 不显示 |

**「金额型 / 额度型」必须在 DTO 与 UI 上区分**。把「剩余 68%」塞进 `amount` 字段会导致：无法求和算总额、无法判断「快没了」的阈值（68% 和 ¥68 语义完全不同）。

---

# 6. 非目标（本次明确不做）

| 非目标 | 何时重新考虑 |
|---|---|
| AGENT 级凭据余额 | 需要「谁的 Agent 在花钱」时（当前 22 条，逐个查成本高） |
| 调度联动（额度低停止派单） | 有真实的额度耗尽事故后 |
| MCP 工具 `queryBalance` | 有外部 Agent 主动查询余额的需求时；且须走 `ToolCallbackContributor` + `ToolRegistry`，默认关闭 |
| token 五分量拆分与趋势图 | `agent_completed` 的 `usage` 字段真正落库之后（当前 0 条） |
| 峰谷价精算 | 确认当前主力模型（`deepseek-v4-flash`）是否仍有峰谷价之后 |
| 阈值告警（飞书 / 钉钉） | 已有 `helloai.alert.*` 通道，可后接 |

---

# 7. 待验证事项（写 plan 前必须先确认）

| # | 待验证 | 方法 | 影响 |
|---|---|---|---|
| V1 | 博查 `remaining` 的单位是元还是分 | 用真实 key 打一次，对照控制台数字 | 若为分，前端需 /100，且差值计算口径随之变 |
| V2 | 博查余额接口在 `api.bochaai.com` 是否可用（新文档写 `api.bocha.cn`） | 两个域名各打一次 | 决定 base-url 用哪个 / 是否需要回落 |
| V3 | MiniMax `token_plan/remains` 的实际返回结构 | 用真实 key 打一次 | 决定 `quotaPercent` 还是「剩余次数」字段 |
| V4 | `deepseek-v4-flash` 是否仍适用峰谷价 | 查官方定价页脚注 | 只影响是否做峰谷标记（M1 不做） |
| V5 | `credential_vault` 同 provider 多条 ACTIVE 时的取数规则 | 读 `PlatformProviderConfigService` 现有逻辑 | 决定 `VaultCredentialResolver` 是复用还是新增选择逻辑 |

---

# 8. 验收判据（M1）

1. **4 家全部出数**：deepseek / moonshot / minimax / bocha 在 overview 里都有非空响应（即使某家失败，也要有失败原因）。
2. **通义不出现**：overview 的列表里没有 dashscope 行。
3. **失败不回填 0**：断网模拟下，前端显示「上次值 + 刷新失败」，数值字段不是 `0.00`。
4. **缓存生效**：连续两次 overview 请求间隔 < 60s 时，第二次的 `fetchedAt` 不变（走缓存）。
5. **多实例不重复抓**：两个 app 实例并发请求 overview，外部接口只被调用一次（Redis 缓存命中）。
6. **单家失败不拖垮**：把 Moonshot 的 key 改成非法值，overview 仍能在 5s 内返回其余 3 家。
7. **开关生效**：`sys_config` 关闭后，前端不渲染组件且不发请求。

---

# 9. 与后续文档的关系

```text
本文（设计：怎么建）
  → plan/（实施：按什么顺序建，M1/M2/M3 批次与验证集）
  → 差距表 G-026（状态：设计中 → 实现中 → 验证中 → 已完成）
```

本文只描述**稳定设计**。实施批次、任务拆分、验证脚本放在 `plan/`；进度只写差距表。
