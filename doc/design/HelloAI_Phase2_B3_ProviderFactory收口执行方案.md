# HelloAI Phase 2 B3：Provider Factory 收口（N-005）执行方案

> 主轴：把差距表 N-005（Provider Factory，PARTIAL P1）收口。盘点结论：**Factory 体系已在 N9 先例（LOG-20260905-00x）协议级闭环，差距表描述属文档失真（落后于代码）**——本轮为口径登记 + 差距移除，零代码改动。
>
> 对应 P2-B（P1 PARTIAL 清账批）B3 项（B1 = N-007 / B2 = N-004 已收口）。
>
> **状态**：已收口（2026-09-05，LOG-20260905-012；代码零改动，纯口径登记）。

---

## §0 事实盘点（2026-09-05 代码核查）

### 0.1 ChatClient 创建链路（已协议级统一）

```text
AgentChatClientServiceImpl.createChatClient（执行链）
ExecutorIssueResolutionAssessor（质量链）
        │  providerCode + apiKeyPlaintext + agent + model
        ▼
LlmProviderChatClientFactoryRegistry.createChatClient
        │  1) deepseek providerCode → DeepSeekProviderChatClientFactory（官方 SDK）
        │  2) protocolType → OpenAiCompatibleProtocolFactory / AnthropicCompatibleProtocolFactory
        ▼
ChatClient（ProviderChatModelCache 五元组缓存：provider/baseUrl/apiKey/protocolType/model 协议级隔离）
```

### 0.2 协议约束与覆盖

| 项 | 现状 |
|---|---|
| `llm_provider.protocol_type` 白名单 | `LlmProviderServiceImpl.validateProtocol` 硬约束：仅 `OPENAI_COMPATIBLE` / `ANTHROPIC_COMPATIBLE` |
| 种子内置（V46） | deepseek（官方 SDK）/ moonshot / dashscope（OpenAI 兼容）/ minimax（Anthropic 兼容） |
| 协议 → 工厂 | `OPENAI_COMPATIBLE` → OpenAiCompatibleProtocolFactory；`ANTHROPIC_COMPATIBLE` → AnthropicCompatibleProtocolFactory；deepseek → 专用 |
| 目录可用性 | `LlmProviderCatalogServiceImpl.isFactorySupported`：deepseek 特判 + 协议白名单，与 Registry 路由一致 |
| 密钥验证 | `LlmProviderKeyVerifyServiceImpl`：协议级端点/认证头（Anthropic x-api-key，其余 Bearer），非 provider 级 |
| 新增 provider | **仅需插 `llm_provider` 表一条记录，零新增 Java 类**（目录注释明示） |

### 0.3 测试覆盖（完整）

`LlmProviderChatClientFactoryRegistryTest` / `OpenAiCompatibleProtocolFactoryTest` / `AnthropicCompatibleProtocolFactoryTest` / `ProviderChatModelCacheTest` / `LlmProviderCatalogServiceTest` / `LlmProviderServiceTest` / `LlmProviderKeyVerifyServiceTest` / `AgentChatClientServiceTest` / `PlatformAgentExecutionServiceTest` / `ExecutorIssueResolutionAssessorTest`。

### 0.4 无 provider 级特判残留

业务层无 `if (provider == xxx)` 分支；仅存协议级判定（anthropic 认证头 / 端点），符合"统一 Factory"原则。

---

## §1 收口判定

**preflight 四问**：改什么——差距表 N-005 收口（口径登记 + 移除），零代码改动；为什么——N-005 描述"部分 Provider 尚未形成完整统一 Factory"，但代码事实是协议级 Factory 体系（注册中心 + 三工厂 + 白名单 + 五元组缓存）已在 N9 先例闭环且测试覆盖完整，**属文档失真**；类型——改口径（按事实源优先级：代码 > 差距表）；不做什么——不新增任何 Factory / 不重构现有路由 / 不扩展新协议（Gemini 原生协议为明确边界，登记留演进）。

**判定结论：N-005 按完成规则移除（Gap → DONE），依据为代码事实 + 测试完整 + 关键路径（执行链/质量链/密钥验证/目录）协议级闭环。**

---

## §2 口径登记

| 子项 | 收口口径 |
|---|---|
| 统一创建 | ChatClient 创建统一收敛于 `LlmProviderChatClientFactoryRegistry`（deepseek 专用 > 协议工厂），执行链/质量链零 provider 特判 |
| Provider + Model + ChatClient + Config | Provider（`llm_provider` 表）+ Model（`llm_provider_model`）+ ChatClient（Registry）+ Config（平台级凭证 vault > sys_config > yml 三段兜底） |
| 协议白名单 | `OPENAI_COMPATIBLE` / `ANTHROPIC_COMPATIBLE` 硬约束（`validateProtocol`），白名单协议必有对应工厂——表数据不可能出现"有配置无工厂" |
| 新增 provider | 插 `llm_provider` 表即可（协议范围内），零 Java 类；`LlmProviderCatalogServiceImpl` 目录自动可用 |
| Gemini 原生协议 | **明确边界（不实现）**：不在协议白名单，目录显示 factorySupported=false 不可注册；未来需要时补 Registry 协议工厂 + 白名单放行 + 密钥验证端点（三处同步） |
| 密钥验证 | 协议级探测（Anthropic x-api-key / 其余 Bearer），与 Factory 路由协议一致，无 provider 级重复逻辑 |

---

## §3 Step 分解

| Step | 内容 | 验收口径 |
|---|---|---|
| S0 | 本方案定稿（盘点 + 判定 + 口径） | §0 代码事实与现状一致；§1 判定明确；§2 口径齐备 |
| S1 | 差距表 N-005 移除（总览行 + §9 章节，重排编号）+ 基线 §16「完整 Provider Factory」行移除 | 差距表无 N-005 残留；编号连续；基线能力边界与代码一致 |
| S2 | LOG-20260905-012 + 提交 | LOG 完整记录盘点与判定；代码零改动（git diff 确认仅文档） |

---

## §4 提交约定

- 纯文档笔（本方案 + 差距表 + 基线 + LOG），单笔提交
- commit message 走 UTF-8 文件 + `git commit -F`；git push 由用户执行

---

## 修订记录

### R1（2026-09-05）：B3 收口定稿

- **背景**：P2-B 清账批 B3。差距表 N-005（PARTIAL P1）描述与代码现实不符。
- **判定**：Factory 体系已协议级闭环（N9 先例），无功能缺口，属文档失真——改口径收口，零代码。
- **范围**：口径登记 + 差距移除；Gemini 原生协议登记为明确边界。
