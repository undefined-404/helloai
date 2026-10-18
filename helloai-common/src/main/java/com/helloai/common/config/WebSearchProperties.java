package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 联网搜索配置（需求对话每轮 LLM 调用前预检索行业资料注入提示词）。
 *
 * <p>作为 <code>RequirementClarifyService</code> 对话轮次增强的可选外部依赖集中管理，
 * 仿 {@link DoorbellProperties} 风格，避免供应商、API Key、超时等散落在业务代码里。</p>
 *
 * <p>任意对话模式（CHAT/CLARIFY）每轮 LLM 调用前按会话级开关触发，
 * 预检索行业资料 / 竞品 / 技术方案后注入 <code>{{WEB_SEARCH_CONTEXT}}</code> 占位符；
 * 失败一律降级跳过，不阻断对话流程。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.web-search")
public class WebSearchProperties {

    /** 总开关。false 时链路完全短路（不调任何下游），方便回归纯对话模式。 */
    private boolean enabled = true;

    /**
     * 供应商选择：bocha（默认，国内）/ tavily（境外）/ deepseek-native
     * （DeepSeek 原生 web_search 服务端工具，当"搜索引擎"用）/ bocha-ai-search
     * （博查 AI Search：内置 Query 改写 + 多模态卡 + 更大结果容量，最接近 Kimi/DeepSeek
     * 网页版检索体验；复用博查 API Key）。
     * 切换时不存在的 Bean 自动跳过，避免启动期 fail-fast 阻塞。
     */
    private String provider = "bocha";

    /**
     * 单次搜索请求超时（毫秒）。默认 8 秒：博查开启 AI 摘要（summary=true）时耗时波动大，
     * 原 3 秒易静默超时降级为空列表（从 3s 上调）；搜索是辅助增强，体验优先。
     */
    private long timeoutMs = 8_000L;

    /** 每次搜索最多返回的条目数。默认 15 条（博查/Tavily 单次最多 50），每条截断到 {@link #maxSnippetChars} 字符。 */
    private int maxResults = 15;

    /** 单条 snippet 最大字符数。超出截断，防止注入占位符后总长爆 token。 */
    private int maxSnippetChars = 200;

    /** 关键词提取长度上限：规则兜底截断的首条用户消息前 N 字（查询规划器候选词为空时启用）。 */
    private int queryKeywordLimit = 40;

    /**
     * 搜索词 LLM 改写开关：规则清洗后仍只有单候选词且消息长/含疑问句式时，
     * 用快模型改写出 1~3 个关键词。false=纯规则运行；
     * {@link #deepseekApiKey} 未配置时也自动禁用（空 Key=未启用语义）。
     */
    private boolean queryRewriteEnabled = true;

    /** 查询改写 LLM 端点（OpenAI 兼容 chat/completions，区别于 native 搜索的 Anthropic 端点）。 */
    private String queryRewriteBaseUrl = "https://api.deepseek.com/chat/completions";

    /** 查询改写使用的模型（轻任务，快模型即可，单次几百 token）。 */
    private String queryRewriteModel = "deepseek-chat";

    /** 查询改写 LLM 请求超时（毫秒）：独立于主链路，宁降级不拖慢对话。 */
    private long queryRewriteTimeoutMs = 5_000L;

    /** 单轮最多候选搜索词条数（规则拆分 / LLM 改写共同上限，顺序降级逐个尝试）。 */
    private int maxQueries = 3;

    /** 博查 Web Search API 端点。 */
    private String bochaBaseUrl = "https://api.bochaai.com/v1/web-search";

    /** 博查 AI Search API 端点（高级搜索：内置 Query 改写 + 多模态卡，单次最多 50 条）。 */
    private String aiSearchBaseUrl = "https://api.bochaai.com/v1/ai-search";

    /**
     * 搜索阶段总时间预算（毫秒，默认 30 秒）。
     *
     * <p>多候选词为串行搜索，单次可能耗时较长（AI Search + 总结 25 秒量级）；
     * 预算耗尽即停止后续候选词与补搜，保留已得结果——保证澄清轮不会被搜索拖成分钟级。
     * 设 0 或负值关闭预算限制（不推荐）。</p>
     */
    private long searchBudgetMs = 30_000L;

    /**
     * 触发缺口补搜所需的最小剩余预算（毫秒，默认 8 秒）。
     *
     * <p><b>为什么必须有这道闸</b>（2026-10-18 实测）：原判据只检查「首轮是否超预算」，
     * 首轮 21s / 预算 30s ⇒ 剩 9s 也照样启动补搜，而补搜单次请求仍按
     * {@link #aiSearchTimeoutMs}（25s）跑 ⇒ 必然超时，白等 25s（实测总耗时 47s、
     * 补搜 0 命中）。改为「剩余 ≥ max(minGapSearchBudgetMs, ...) 才补搜」后，
     * 预算不足直接跳过，用已有结果出回复。</p>
     *
     * <p>取值口径：补搜要「评估缺口（LLM）+ 一次检索」两步，8 秒是真实能跑完的下限；
     * 实际生效值 = min(本值, aiSearchTimeoutMs)，不会超过单次请求超时。</p>
     */
    private long minGapSearchBudgetMs = 8_000L;

    /**
     * 博查 AI Search 请求超时（毫秒，默认 25 秒）。
     *
     * <p>独立于 {@link #timeoutMs}（基础搜索 8 秒）：AI Search 是「检索 + 博查侧大模型总结」
     * 两步，开启 {@link #aiSearchAnswer} 后耗时显著高于基础搜索（实测 8 秒超时全量降级），
     * 需放宽到 25 秒量级；仍失败则降级空列表，不阻断澄清主流程。</p>
     */
    private long aiSearchTimeoutMs = 25_000L;

    /**
     * 博查 AI Search 单次返回条数（专用容量配置，默认 15，API 上限 50）。
     * 独立于 {@link #maxResults}：AI Search 是「高容量检索」专用供应商，走 provider 级
     * 容量上限而非通用护栏，让同一需求拿到的参考网页显著多于基础搜索。
     */
    private int aiSearchMaxResults = 15;

    /**
     * 博查 AI Search 是否启用大模型总结（{@code answer=true}，默认开）。
     * 开启后博查侧模型对搜索结果生成一段总结答案，随结果注入 Prompt——
     * 对齐 DeepSeek 网页版「搜索后最后给出总结」；代价为每次搜索的额外 LLM 计费。
     */
    private boolean aiSearchAnswer = true;

    /**
     * 联网搜索最大轮数（Deep Research 风格补搜，默认 2 = 首轮 + 最多 1 轮补搜）。
     * 首轮结果信息不足时由 LLM 缺口评估生成补充查询词再搜一轮，合并去重；
     * 设 1 关闭补搜（单轮）。每轮均为独立搜索 API 调用 + 评估 LLM 调用，注意成本。
     */
    private int aiSearchMaxRounds = 2;

    /** 博查 API Key（env BOCHA_API_KEY 注入；未配置/空字符串=该供应商未启用）。 */
    private String bochaApiKey = "${BOCHA_API_KEY:}";

    /** Tavily Search API 端点。 */
    private String tavilyBaseUrl = "https://api.tavily.com/search";

    /** Tavily API Key（建议通过 env TAVILY_API_KEY 注入；空字符串=该供应商未启用）。 */
    private String tavilyApiKey = "";

    /** DeepSeek 原生联网搜索（Anthropic 兼容端点 + web_search_20250305 服务端工具）端点。 */
    private String deepseekBaseUrl = "https://api.deepseek.com/anthropic/v1/messages";

    /** DeepSeek API Key（env DEEPSEEK_API_KEY 注入；未配置/空字符串=该供应商未启用）。 */
    private String deepseekApiKey = "";

    /** DeepSeek 原生搜索使用的模型（单次调用仅取结构化搜索结果块，正文为副产品）。 */
    private String deepseekModel = "deepseek-chat";

    /** DeepSeek 原生搜索生成 token 上限（搜索结果块本身不大，压低以控成本）。 */
    private int deepseekMaxTokens = 1024;

    /**
     * DeepSeek 原生搜索请求超时（毫秒）。独立于 {@link #timeoutMs}：该路径是一次完整
     * LLM 调用（服务端检索 + 生成），耗时显著高于普通搜索 API，默认 15 秒。
     */
    private long deepseekTimeoutMs = 15_000L;

    /**
     * 用户消息 URL 直取开关：消息含 http(s) 链接时直接访问抓取页面正文
     * 注入上下文，而非把裸 URL 文本当搜索词。false 时回退纯搜索引擎行为。
     */
    private boolean urlFetchEnabled = true;

    /** 单个网页抓取超时（毫秒）。 */
    private long urlFetchTimeoutMs = 8_000L;

    /** 单轮最多直取的页面数（控成本，取消息中前 N 个 URL）。 */
    private int urlFetchMaxPages = 2;

    /** 单页抓取正文注入上下文的最大字符数（超出截断，防 token 爆炸）。 */
    private int urlFetchMaxTextChars = 4_000;

    /**
     * 是否允许**明文 http** 抓取（默认 {@code false}，只放 https）。
     *
     * <p>`REF-5.4` 出站守卫的协议白名单开关（裁定 `D-2026-10-09-6②`）：默认只放 https，
     * 本地/内网调试确需明文时显式打开。<b>与「私网地址」无关</b> —— 回环 / 私网 / 链路本地 /
     * 保留段由 {@code OutboundUrlGuard} 无条件拒绝，本开关**不能**放行它们。</p>
     */
    private boolean urlFetchAllowInsecureHttp = false;

    /**
     * 结构化模态卡消费开关（W-7，默认 {@code true}）。
     *
     * <p><b>与 W-5 的灰度口径不同</b>（plan §四 原写「W-5/W-7 默认关」）：模态卡与网页结果
     * 在同一次 HTTP 响应内返回，解析它**零额外请求、零额外计费**；消费侧有严格白名单前缀
     * 过滤（未知卡不注入）+ 单测覆盖。默认关等于「功能未交付」，故默认开，
     * 保留本开关作为 kill switch（上游卡形态异常时一键停用，无需回滚代码）。</p>
     */
    private boolean modalCardsEnabled = true;

    // ---------------------------------------------------------------- W-5 语义重排（Rerank）

    /**
     * 语义重排总开关（W-5，默认 {@code false} 灰度）。
     *
     * <p>开启后对合并后的网页检索结果调用博查 Semantic Reranker（{@code gte-rerank}，
     * 80M 参数）按「与用户意图的语义相关度」精排，替代供应商原始顺序（原始顺序是
     * BM25/热度混合，长尾查询相关性差）。<b>默认关</b>：rerank 是额外计费调用
     * （约 0.005 元/次，官方定价），且失败虽有降级但仍增一处外部依赖——
     * 由管理员显式开启。用户直取页面不参与重排（第一手资料固定置顶）。</p>
     *
     * <p>判据：开启后真实日志出现 {@code rerank} 相关行；结果顺序与原始顺序不同即生效。</p>
     */
    private boolean rerankEnabled = false;

    /** 博查 Semantic Reranker API 端点（与搜索同域，路径 {@code /v1/rerank}）。 */
    private String rerankBaseUrl = "https://api.bochaai.com/v1/rerank";

    /**
     * 重排模型（官方可用：{@code gte-rerank}；{@code bocha-semantic-reranker-cn/-en}
     * 需申请邀测）。默认 {@code gte-rerank}。
     */
    private String rerankModel = "gte-rerank";

    /** 重排请求超时（毫秒，默认 5 秒）：80M 模型推理快；超时降级原顺序，不拖慢澄清轮。 */
    private long rerankTimeoutMs = 5_000L;

    /**
     * 送重排的最大文档数（API 上限 50）。超出只取前 N 条送排，其余按原顺序附在尾部
     * ——既控制单次成本，也不丢弃已有结果。
     */
    private int rerankMaxDocuments = 50;

    /**
     * 相关性下限（0~1，默认 0 不过滤）：重排后丢弃 {@code relevance_score} 低于该值的条目。
     *
     * <p>官方分档参考：0.75~1 高度相关 / 0.5~0.75 相关但缺细节 / 0.2~0.5 部分相关 /
     * 0.1~0.2 仅小部分相关 / 0~0.1 无关。<b>默认 0 = 只重排不过滤</b>（最保守）：
     * 阈值需要按真实语料调，先观察再开；设 0.1~0.2 可滤掉明确无关条目。</p>
     */
    private double rerankMinScore = 0.0;
}
