package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.planner.search.BochaRerankService;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.search.WebModalCard;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.SearchQueryPlannerService;
import com.helloai.core.planner.service.WebPageFetchService;
import com.helloai.core.planner.tool.WebSearchToolResult;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 澄清联网搜索编排组件：URL 分离 + 查询规划 + 直取页面 + 顺序降级搜索 + 归一化结果记录。
 *
 * <p>从 {@link com.helloai.core.planner.service.impl.RequirementClarifyServiceImpl} 拆分
 * （CODE_STYLE §7.8 类规模红线）：搜索编排族（依赖 4 个搜索服务）独立成组件后，
 * 意图状态机主类只保留状态流转决策，搜索细节可独立单测。</p>
 *
 * <p>阶段四 Capability 化：搜索动作按工具名经 {@link ToolExecutor} 调平台工具
 * {@code web_search}（能力注册在 planner 域，见 web_search 工具定义），
 * 失败/无结果保持「空列表降级」语义——webSearchEnabled 会话开关在编排层之外
 * （RequirementClarifyService 侧），与能力注册无关。</p>
 */
@Slf4j
@Component
public class ClarifyWebSearchOrchestrator {

    /** 消息中 http(s) 链接提取（尾随中文标点/括号/引号不算 URL 一部分）。 */
    private static final Pattern URL_IN_TEXT_PATTERN = Pattern.compile(
            "https?://[^\\s<>\"'，。；、（）()【】\\[\\]{}]+");

    /** W-5 重排语义锚的最大字符数（rerank 模型按 512 token 评分，长文本无增益）。 */
    private static final int RERANK_ANCHOR_MAX_CHARS = 200;

    /**
     * W-2 并行搜索共享线程池：多候选词并行调 web_search 工具。
     * 池大小 = 2×CPU 核数（搜索是 IO 密集，略超核数容忍长尾），最小 4。
     * 类级懒加载单例（编排器 Spring 单例，反复创建池会泄漏线程）。
     */
    private static volatile ExecutorService searchExecutor;

    private static ExecutorService searchExecutor() {
        ExecutorService s = searchExecutor;
        if (s == null) {
            synchronized (ClarifyWebSearchOrchestrator.class) {
                s = searchExecutor;
                if (s == null) {
                    int size = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
                    searchExecutor = s = Executors.newFixedThreadPool(size, r -> {
                        Thread t = new Thread(r, "web-search-parallel");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return s;
    }

    private final ToolExecutor toolExecutor;
    private final WebSearchProperties webSearchProperties;
    private final WebPageFetchService pageFetchService;
    private final SearchQueryPlannerService searchQueryPlannerService;
    private final RelativeTimeNormalizer relativeTimeNormalizer;
    private final SearchGapAssessor searchGapAssessor;
    /** W-5 语义重排（默认关；只排搜索引擎结果，直取页面固定置顶）。 */
    private final BochaRerankService bochaRerankService;
    /** web_search 工具输出 JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 显式构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑，与主类口径一致）。
     */
    public ClarifyWebSearchOrchestrator(ToolExecutor toolExecutor,
                                        WebSearchProperties webSearchProperties,
                                        WebPageFetchService pageFetchService,
                                        SearchQueryPlannerService searchQueryPlannerService,
                                        RelativeTimeNormalizer relativeTimeNormalizer,
                                        SearchGapAssessor searchGapAssessor,
                                        BochaRerankService bochaRerankService,
                                        ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.webSearchProperties = webSearchProperties;
        this.pageFetchService = pageFetchService;
        this.searchQueryPlannerService = searchQueryPlannerService;
        this.relativeTimeNormalizer = relativeTimeNormalizer;
        this.searchGapAssessor = searchGapAssessor;
        this.bochaRerankService = bochaRerankService;
        this.objectMapper = objectMapper;
    }

    /**
     * 联网搜索一次（无优先候选词版本，委托 {@link #doWebSearch(String, List)}）。
     */
    public WebSearchOutcome doWebSearch(String userMessage) {
        return doWebSearch(userMessage, null);
    }

    /**
     * 联网搜索一次：URL 分离 + 候选词合并（优先词 + 查询规划）→ 直取页面 + 顺序降级搜索 → 归一化结果记录。
     *
     * <p>查询规划（引入）：剥离 URL 后的语义文本不再原样截前 40 字当搜索词
     * （疑问句式/敬语/标点/多主题长句对关键词检索命中率极低），改由
     * {@link SearchQueryPlannerService} 产出 1~N 个候选词（规则清洗总是执行，
     * LLM 改写条件触发，失败降级规则结果）；规划器无产出时兜底规则截断。</p>
     *
     * <p>优先候选词（引入，前置联合决策的 LLM 优化词注入）：priorityQueries 非空过滤后
     * 置于候选词列表头部，规则规划词补后——LLM 优化词命中即停，优化词零结果时顺序降级
     * 规则词；两源合并去重，避免同一词重复检索。空串/空白即跳过（决策降级侧约定搜索词
     * 缺失以空串表达，此处直接落到规则词）。</p>
     *
     * <p>顺序降级（引入）：候选词逐个尝试，首个非空结果即停（命中场景成本不变）；
     * 全零结果时 outcome.total=0 且已尝试词全量落 payload，不再静默放弃。</p>
     *
     * <p>URL 分离：消息中的 http(s) 链接被提取后直接访问抓取页面正文（用户给出的
     * 站点是第一手资料），候选词改用剥离 URL 后的语义文本——裸 URL 文本当搜索词
     * 检索效果极差；纯 URL 消息回退域名作搜索词。直取页面映射为来源置顶合并进结果
     * （总条数 maxResults 内）。</p>
     *
     * <p>直取失败域名前缀：消息带 URL 但直取无一成功（SPA 空壳无元数据/反爬拦截）时，
     * 首个候选词前置首个域名，让搜索引擎检索该站点的公开资料（介绍/教程/文档），
     * 避免「直取空 + 搜索词不含域名 → results=0」双失败叠加。</p>
     *
     * <p>异常降级为 failed outcome（落 payload 可查验，不阻断澄清主流程）；
     * 候选词全部空白且无成功直取页面时返回 null（未获得任何资料，不落 webSearch 键）。</p>
     *
     * @param userMessage    用户消息（URL 分离与规则兜底的语义文本来源，语义与旧版本一致）
     * @param priorityQueries 优先候选词（如 LLM 联合决策的优化搜索词），可 null；内部空白项忽略
     */
    public WebSearchOutcome doWebSearch(String userMessage, List<String> priorityQueries) {
        return doWebSearch(userMessage, priorityQueries, null);
    }

    /**
     * 联网搜索一次（带权威时钟注入版本，供测试固定日期）：委托
     * {@link #doWebSearch(String, List, LocalDate)}；生产路径 today 传 null 用服务器实时日期。
     */
    public WebSearchOutcome doWebSearch(String userMessage, List<String> priorityQueries, LocalDate today) {
        LocalDate anchor = today == null ? LocalDate.now() : today;
        List<String> urls = extractUrls(userMessage);
        List<String> candidates = new ArrayList<>();
        mergeCandidates(candidates, priorityQueries);
        mergeCandidates(candidates, searchQueryPlannerService.planQueries(stripUrls(userMessage)));
        if (candidates.isEmpty()) {
            // 规划器无产出（极端消息清洗后为空）时兜底规则截断，不丢搜索机会
            String fallback = extractQueryKeyword(stripUrls(userMessage));
            if (!fallback.isBlank()) {
                candidates = List.of(fallback);
            }
        }
        List<WebPageContent> pages = fetchUserPages(urls);
        boolean hasOkPage = pages.stream().anyMatch(WebPageContent::isOk);
        if (candidates.isEmpty() && !urls.isEmpty()) {
            // 纯 URL 消息：回退域名作搜索词（无论直取成败）
            String host = hostOfUrl(urls.get(0));
            if (!host.isBlank()) {
                candidates = List.of(host);
                log.info("澄清联网搜索：纯 URL 消息回退域名作搜索词: query={}", host);
            }
        } else if (!urls.isEmpty() && !hasOkPage && !candidates.isEmpty()) {
            // 语义文本存在但直取全部失败 → 首个候选词前置域名，
            // 让搜索引擎检索该站点的公开资料（介绍/教程/文档）
            String host = hostOfUrl(urls.get(0));
            if (!host.isBlank()) {
                List<String> prefixed = new ArrayList<>(candidates);
                prefixed.set(0, host + " " + prefixed.get(0));
                candidates = prefixed;
                log.info("澄清联网搜索：直取失败，域名前置增强搜索词: query={}", prefixed.get(0));
            }
        }
        // 第二层防线：候选词统一做相对时间词→绝对日期归一化（LLM 优化词/规则词/兜底词
        // 全量覆盖，幂等）。搜索引擎不理解"今天/上周五"等相对时间语义，跨天对话时
        // LLM 可能以历史日期为锚，规则层兜底修正后才发起检索
        candidates = normalizeCandidates(candidates, anchor);

        if (candidates.isEmpty() && !hasOkPage) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        List<String> attempted = new ArrayList<>();
        // provider / answer 用 Holder 承载：absorb() 在并行/串行两分支内更新，
        // 后续日志与 outcome 构建统一读取（Java 闭包需 final 引用才能被 lambda 捕获）
        // provider 初始 null = 未解析到真实供应商，outcome 构建时兜底 "web_search_tool"
        Holder<String> providerRef = new Holder<>();
        Holder<String> answerRef = new Holder<>();
        // W-7：模态卡跨词收集（按 type+title 去重），最终落 outcome.modalCards
        List<WebModalCard> modalCards = new ArrayList<>();
        try {            // 多查询词合并（对齐 Kimi/DeepSeek 网页版 query expansion）：
            // 对全部候选词都搜索、按 URL 去重合并，总条数 cap 到 maxResults；
            // 每词条数 = ceil(maxResults / 候选词数)，避免单次吃满上限、多词覆盖不全。
            int maxResults = Math.max(1, webSearchProperties.getMaxResults());
            int perQuery = Math.max(1, (int) Math.ceil((double) maxResults / Math.max(1, candidates.size())));
            List<WebSearchResult> searched = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            long budgetMs = webSearchProperties.getSearchBudgetMs();
            // W-2 并行搜索：多候选词 CompletableFuture 并行调工具（替代串行 for），
            // 受 searchBudgetMs 总预算约束（超时未完成的 future 丢弃，保留已完成结果）。
            // answer 取首个有 answer 的词（按候选词顺序遍历 future，服务层 per-query 槽保证各词不串扰）。
            boolean withAnswer = webSearchProperties.isAiSearchAnswer();
            List<String> queryList = candidates.stream()
                    .filter(q -> q != null && !q.isBlank())
                    .toList();
            if (queryList.size() <= 1) {
                // 单候选词（含退化场景）：保持串行，避免线程池开销与多词竞态
                for (String q : queryList) {
                    if (budgetMs > 0 && System.currentTimeMillis() - t0 > budgetMs) {
                        log.info("澄清联网搜索：时间预算 {}ms 耗尽，停止后续候选词: tried={}", budgetMs, attempted);
                        break;
                    }
                    attempted.add(q);
                    WebSearchToolResult out = callWebSearchTool(q, perQuery, withAnswer);
                    int hits = absorb(out, searched, seenUrls, maxResults, providerRef, answerRef, modalCards);
                    log.info("澄清联网搜索：候选词命中 {} 条: query={}", hits, q);
                    if (searched.size() >= maxResults) {
                        break;
                    }
                }
            } else {
                // 多候选词并行：每词一个 future，全部提交后按候选词顺序 await（保留确定性输出顺序）。
                // exceptionally 把单词异常转 null（该词丢弃），allOf().get() 永不抛——
                // 一个词失败不阻断整批（与串行「异常落 failed outcome」语义分治：并行是尽力而为）
                List<CompletableFuture<WebSearchToolResult>> futures = new ArrayList<>();
                for (String q : queryList) {
                    attempted.add(q);
                    futures.add(CompletableFuture
                            .supplyAsync(() -> callWebSearchTool(q, perQuery, withAnswer), searchExecutor())
                            .exceptionally(e -> {
                                log.warn("澄清联网搜索：候选词并行执行异常，丢弃该词: query={}, err={}",
                                        q, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                                return null;
                            }));
                }
                try {
                    // 总预算内等全部完成；超时未完成的 future 丢弃（保留已完成结果，不阻断主流程）
                    long waitMs = budgetMs > 0 ? Math.max(0, budgetMs - (System.currentTimeMillis() - t0)) : Long.MAX_VALUE;
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(waitMs, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.info("澄清联网搜索：时间预算 {}ms 内并行候选词未全部完成，保留已完成结果: tried={}",
                            budgetMs, attempted);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("澄清联网搜索：并行等待被中断，保留已完成结果: err={}", e.getMessage());
                } catch (ExecutionException e) {
                    log.warn("澄清联网搜索：并行任务异常（exceptionally 已转 null，此处不应到达）: err={}",
                            e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
                for (int i = 0; i < futures.size() && searched.size() < maxResults; i++) {
                    WebSearchToolResult out = futures.get(i).getNow(null);
                    if (out == null) {
                        // 可查验：该词是「超时未完成」或「异常被 exceptionally 转 null」，
                        // 与「搜了但 0 结果」语义不同——原实现静默 continue，日志里只剩
                        // 「只有第一个词命中」，无法判断另外两个词到底发生了什么
                        log.info("澄清联网搜索：候选词无结果返回（超时未完成或执行异常），已丢弃: query={}",
                                queryList.get(i));
                        continue;
                    }
                    int hits = absorb(out, searched, seenUrls, maxResults, providerRef, answerRef, modalCards);
                    log.info("澄清联网搜索：候选词命中 {} 条: query={}", hits, queryList.get(i));
                }
            }
            int rounds = 1;
            // Deep Research 风格补搜：首轮有结果且配置允许多轮时，LLM 评估缺口 → 补搜 1 轮
            //
            // ★预算闸（2026-10-18 修复）：原判据是「elapsed <= budgetMs」——只要没超预算
            // 就补搜，哪怕只剩 1ms。实测代价：首轮并行 3 词耗时 21s（预算 30s，剩 9s），
            // 补搜启动后单次请求仍按 aiSearchTimeoutMs=25s 跑，全部超时 ⇒ 总耗时 47s、
            // 补搜 0 命中。改为「剩余预算 ≥ 补搜最小预算」才启动：预算不足直接跳过，
            // 用已有结果出回复（用户等 21s 而非 47s，且结果条数不变）。
            long remainingMs = budgetMs <= 0 ? Long.MAX_VALUE : budgetMs - (System.currentTimeMillis() - t0);
            long minGapBudget = Math.min(webSearchProperties.getAiSearchTimeoutMs(),
                    Math.max(1L, webSearchProperties.getMinGapSearchBudgetMs()));
            boolean budgetLeft = remainingMs >= minGapBudget;
            if (!searched.isEmpty() && webSearchProperties.getAiSearchMaxRounds() > 1 && budgetLeft) {
                String gapQuery = searchGapAssessor.assessGap(
                        stripUrls(userMessage), new ArrayList<>(searched), attempted);
                if (gapQuery != null && !gapQuery.isBlank()) {
                    attempted.add(gapQuery);
                    WebSearchToolResult gap = callWebSearchTool(gapQuery, perQuery,
                            webSearchProperties.isAiSearchAnswer());
                    if (gap != null) {
                        for (WebSearchResult r : gap.results()) {
                            if (r == null || r.getUrl() == null || r.getUrl().isBlank()
                                    || !seenUrls.add(r.getUrl())) {
                                continue;
                            }
                            searched.add(r);
                            if (searched.size() >= maxResults) {
                                break;
                            }
                        }
                    }
                    rounds = 2;
                    log.info("澄清联网搜索：缺口补搜完成 gapQuery={}, hits={}, total={}",
                            gapQuery, gap == null ? 0 : gap.results().size(), searched.size());
                }
            } else if (!searched.isEmpty() && webSearchProperties.getAiSearchMaxRounds() > 1) {
                // 可查验：不是「没找到缺口」而是「预算不够补」——两者对排障的意义完全不同
                log.info("澄清联网搜索：跳过缺口补搜（剩余预算 {}ms < 补搜最小预算 {}ms）: tried={}",
                        remainingMs, minGapBudget, attempted);
            }
            long costMs = System.currentTimeMillis() - t0;
            // W-5：语义重排只作用于搜索引擎结果——用户直取页面是第一手资料，
            // 在 mergeFetchedIntoResults 里固定置顶，不参与重排（否则会被语义分挤下去）。
            // 语义锚用用户原始意图文本（而非拆出的候选词）：rerank 是语义模型，
            // 天然意图比关键词组合更能代表「用户到底想找什么」。
            List<WebSearchResult> ranked = bochaRerankService.rerank(rerankAnchor(userMessage), searched);
            List<WebSearchResult> merged = mergeFetchedIntoResults(pages, ranked);
            String provider = providerRef.value == null ? "web_search_tool" : providerRef.value;
            log.info("澄清联网搜索结束: provider={}, queries={}, rounds={}, answer={}, modalCards={}, pages={}, results={}, costMs={}",
                    provider, attempted, rounds,
                    answerRef.value != null ? "有" : "无", modalCards.size(), pages.size(), merged.size(), costMs);
            return WebSearchOutcome.builder()
                    .provider(provider)
                    .query(attempted.isEmpty() ? "" : attempted.get(0))
                    .queries(attempted)
                    .costMs(costMs)
                    .total(merged.size())
                    .results(merged)
                    .fetchedPages(pages)
                    .answer(answerRef.value)
                    .modalCards(modalCards)
                    .rounds(rounds)
                    .build();
        } catch (Exception e) {
            log.warn("澄清联网搜索异常降级（不动澄清主流程）: queries={}, err={}", attempted, e.getMessage());
            return WebSearchOutcome.builder()
                    .provider(providerRef.value == null ? "web_search_tool" : providerRef.value)
                    .query(attempted.isEmpty() ? "" : attempted.get(0))
                    .queries(attempted)
                    .costMs(System.currentTimeMillis() - t0)
                    .fetchedPages(pages)
                    .failed(true)
                    .reason(e.getMessage())
                    .build();
        }
    }

    /**
     * 吸收一次工具调用的结果：provider 取首个真实供应商（非占位）、answer 取首个成功词、
     * 结果按 URL 去重并入 searched（cap 在 maxResults 内）、模态卡按 (type+title) 去重并入（W-7）。
     *
     * @param modalCards 模态卡收集容器（并行/串行两分支共用；多词同卡去重，保持候选词顺序）
     * @return 本次命中条数（去重后实际并入数）
     */
    private int absorb(WebSearchToolResult out, List<WebSearchResult> searched, Set<String> seenUrls,
                       int maxResults, Holder<String> providerRef, Holder<String> answerRef,
                       List<WebModalCard> modalCards) {
        if (out == null) {
            return 0;
        }
        if (out.provider() != null && !out.provider().isBlank()
                && !"web_search_tool".equals(out.provider()) && providerRef.value == null) {
            providerRef.value = out.provider();
        }
        // 大模型总结取首个成功候选词的（withAnswer=true 且供应商支持才返回）
        if (answerRef.value == null && out.answer() != null && !out.answer().isBlank()) {
            answerRef.value = out.answer();
        }
        // W-7：模态卡按「卡类型 + 标题」去重（多词常命中同一张卡，如两个词都问金价）
        if (modalCards != null && out.modalCards() != null) {
            for (WebModalCard card : out.modalCards()) {
                if (card == null || card.getType() == null) continue;
                boolean dup = modalCards.stream().anyMatch(c ->
                        card.getType().equals(c.getType())
                                && java.util.Objects.equals(card.getTitle(), c.getTitle()));
                if (!dup) {
                    modalCards.add(card);
                }
            }
        }
        int hits = 0;
        for (WebSearchResult r : out.results()) {
            if (r == null || r.getUrl() == null || r.getUrl().isBlank()
                    || !seenUrls.add(r.getUrl())) {
                continue; // 跨词同 URL 去重
            }
            searched.add(r);
            hits++;
            if (searched.size() >= maxResults) {
                break;
            }
        }
        return hits;
    }

    /** 可变引用小容器（absorb 多返回值用，避免引元组/数组）。 */
    private static final class Holder<T> {
        T value;
    }

    /**
     * 经平台工具执行一次联网搜索（web_search，阶段四 Capability 化）。
     *
     * <p>入参序列化 + 工具执行 + 输出反序列化；任意环节失败（工具失败/非 JSON/缺字段）
     * 返回 null，调用方按「空结果」降级——与旧直调契约（失败返回空列表）语义一致。</p>
     *
     * <p>W-1：{@code withAnswer} 为 true 时工具侧同步请求供应商大模型总结（answer），
     * 结果回传 {@code WebSearchToolResult.answer}——博查 AI Search 请求层本就
     * {@code answer=true} 生成总结，此前丢失在工具调用参数层（工具默认 false 不取）。</p>
     */
    private WebSearchToolResult callWebSearchTool(String query, int maxResults, boolean withAnswer) {
        String argsJson;
        try {
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("query", query);
            args.put("maxResults", maxResults);
            args.put("withAnswer", withAnswer);
            argsJson = objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("澄清联网搜索：web_search 入参序列化失败，降级空结果: query={}, err={}",
                    query, e.getMessage());
            return null;
        }
        // 注意：toolExecutor.execute 异常不在本方法内兜住——异常向上传播，
        // 串行下由 doWebSearch 顶层 catch 落 failed outcome（可查验）；
        // 并行下由 future.exceptionally 转 null 丢弃该词（不阻断整批）。
        // 两种路径语义分治，避免「并行吞异常导致失败不可查验」。
        ToolExecutionResult result = toolExecutor.execute("web_search", argsJson);
        if (result == null || !result.success()
                || result.output() == null || result.output().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(result.output(), WebSearchToolResult.class);
        } catch (Exception e) {
            log.warn("澄清联网搜索：web_search 输出解析失败，降级空结果: err={}", e.getMessage());
            return null;
        }
    }

    /** 候选词统一时间归一化（原样返回不可变列表时新建 ArrayList，不原地修改）。 */
    private List<String> normalizeCandidates(List<String> candidates, LocalDate today) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        List<String> normalized = new ArrayList<>(candidates.size());
        for (String q : candidates) {
            if (q == null) {
                continue;
            }
            String n = relativeTimeNormalizer.normalize(q, today);
            if (n != null && !n.isBlank() && !normalized.contains(n)) {
                normalized.add(n);
            }
        }
        return normalized;
    }

    /** 候选词合并（非空过滤 + 去重，保持源内顺序）：优先词在前、规则规划词补后。 */
    private void mergeCandidates(List<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String q : source) {
            if (q != null && !q.isBlank() && !target.contains(q)) {
                target.add(q);
            }
        }
    }

    /** 提取消息中全部 URL（出现顺序，供直取）。 */
    private List<String> extractUrls(String message) {
        if (message == null || message.isBlank()) return Collections.emptyList();
        List<String> urls = new ArrayList<>();
        Matcher m = URL_IN_TEXT_PATTERN.matcher(message);
        while (m.find()) {
            urls.add(m.group());
        }
        return urls;
    }

    /** 剥离 URL 后的语义文本（搜索词来源），空白归一。 */
    private String stripUrls(String message) {
        if (message == null) return "";
        return URL_IN_TEXT_PATTERN.matcher(message).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** URL 的 host（纯 URL 消息的域名回退搜索词 / 直取来源的 siteName）。 */
    private static String hostOfUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 直取用户给出的页面（前 N 个去重 URL，N=urlFetchMaxPages）；总开关关闭或无 URL 时
     * 返回空列表；fetcher 异常已内部降级（ok=false 记录也保留，payload 可查验）。
     */
    private List<WebPageContent> fetchUserPages(List<String> urls) {
        if (!webSearchProperties.isUrlFetchEnabled() || urls.isEmpty()) {
            return Collections.emptyList();
        }
        int max = Math.max(1, webSearchProperties.getUrlFetchMaxPages());
        List<WebPageContent> out = new ArrayList<>();
        for (String url : urls.stream().distinct().limit(max).toList()) {
            WebPageContent page = pageFetchService.fetch(url);
            if (page == null) continue; // 契约保证非 null，防御性兜底
            out.add(page);
            log.info("澄清联网搜索 URL 直取{}: url={}, textChars={}",
                    page.isOk() ? "成功" : "失败(" + page.getReason() + ")",
                    url, page.getText() == null ? 0 : page.getText().length());
        }
        return out;
    }

    /** 直取页面映射为来源置顶 + 搜索结果补后，总条数 cap 在 maxResults 内。 */
    private List<WebSearchResult> mergeFetchedIntoResults(List<WebPageContent> pages,
                                                          List<WebSearchResult> searched) {
        int cap = webSearchProperties.getMaxResults();
        int snippetMax = webSearchProperties.getMaxSnippetChars();
        List<WebSearchResult> merged = new ArrayList<>();
        for (WebPageContent p : pages) {
            if (!p.isOk() || merged.size() >= cap) continue;
            String text = p.getText() == null ? "" : p.getText();
            merged.add(WebSearchResult.builder()
                    .title(p.getTitle() == null || p.getTitle().isBlank() ? "(无标题)" : p.getTitle())
                    .url(p.getUrl())
                    .snippet(text.length() <= snippetMax ? text : text.substring(0, snippetMax) + "…")
                    .siteName(hostOfUrl(p.getUrl()))
                    .build());
        }
        if (searched != null) {
            for (WebSearchResult r : searched) {
                if (merged.size() >= cap) break;
                merged.add(r);
            }
        }
        return merged;
    }

    /** 关键词提取（兜底）：用户消息前 queryKeywordLimit 字符（去两端空白）；
     * 查询规划器候选词为空时启用，保留旧行为不丢搜索机会。 */
    private String extractQueryKeyword(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        int limit = webSearchProperties.getQueryKeywordLimit();
        if (trimmed.length() <= limit) return trimmed;
        return trimmed.substring(0, limit);
    }

    /**
     * W-5 重排的语义锚：剥离 URL 后的用户原始意图文本（截断到 200 字控 token）。
     *
     * <p>为什么不用候选词：候选词已经是「为搜索引擎优化过的关键词组合」，而 rerank 是
     * 语义模型——喂它的应该是用户真实问句，这样「这条结果是否回答了用户的问题」才判得准。
     * 纯 URL 消息（语义文本为空）返回空串 ⇒ 重排侧跳过，保持原顺序，不误排。</p>
     */
    private String rerankAnchor(String userMessage) {
        String text = stripUrls(userMessage);
        return text.length() <= RERANK_ANCHOR_MAX_CHARS
                ? text : text.substring(0, RERANK_ANCHOR_MAX_CHARS);
    }
}
