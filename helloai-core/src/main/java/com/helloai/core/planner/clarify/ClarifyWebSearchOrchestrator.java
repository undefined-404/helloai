package com.helloai.core.planner.clarify;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.SearchQueryPlannerService;
import com.helloai.core.planner.service.WebPageFetchService;
import com.helloai.core.planner.service.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 澄清联网搜索编排组件：URL 分离 + 查询规划 + 直取页面 + 顺序降级搜索 + 归一化结果记录。
 *
 * <p>从 {@link com.helloai.core.planner.service.impl.RequirementClarifyServiceImpl} 拆分
 * （CODE_STYLE §7.8 类规模红线）：搜索编排族（依赖 4 个搜索服务）独立成组件后，
 * 意图状态机主类只保留状态流转决策，搜索细节可独立单测。</p>
 */
@Slf4j
@Component
public class ClarifyWebSearchOrchestrator {

    /** 消息中 http(s) 链接提取（尾随中文标点/括号/引号不算 URL 一部分）。 */
    private static final Pattern URL_IN_TEXT_PATTERN = Pattern.compile(
            "https?://[^\\s<>\"'，。；、（）()【】\\[\\]{}]+");

    private final WebSearchService webSearchService;
    private final WebSearchProperties webSearchProperties;
    private final WebPageFetchService pageFetchService;
    private final SearchQueryPlannerService searchQueryPlannerService;

    /**
     * 显式构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑，与主类口径一致）。
     */
    public ClarifyWebSearchOrchestrator(WebSearchService webSearchService,
                                        WebSearchProperties webSearchProperties,
                                        WebPageFetchService pageFetchService,
                                        SearchQueryPlannerService searchQueryPlannerService) {
        this.webSearchService = webSearchService;
        this.webSearchProperties = webSearchProperties;
        this.pageFetchService = pageFetchService;
        this.searchQueryPlannerService = searchQueryPlannerService;
    }

    /**
     * 联网搜索一次：URL 分离 + 查询规划 → 直取页面 + 顺序降级搜索 → 归一化结果记录。
     *
     * <p>查询规划（引入）：剥离 URL 后的语义文本不再原样截前 40 字当搜索词
     * （疑问句式/敬语/标点/多主题长句对关键词检索命中率极低），改由
     * {@link SearchQueryPlannerService} 产出 1~N 个候选词（规则清洗总是执行，
     * LLM 改写条件触发，失败降级规则结果）；规划器无产出时兜底规则截断。</p>
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
     */
    public WebSearchOutcome doWebSearch(String userMessage) {
        List<String> urls = extractUrls(userMessage);
        List<String> candidates = searchQueryPlannerService.planQueries(stripUrls(userMessage));
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
        if (candidates.isEmpty() && !hasOkPage) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        List<String> attempted = new ArrayList<>();
        try {
            // 顺序降级：候选词逐个尝试，首个非空结果即停；全零结果时 attempted 完整记录已尝试词
            List<WebSearchResult> searched = Collections.emptyList();
            for (String q : candidates) {
                if (q == null || q.isBlank()) {
                    continue;
                }
                attempted.add(q);
                searched = webSearchService.search(q, webSearchProperties.getMaxResults());
                if (!searched.isEmpty()) {
                    break;
                }
                log.info("澄清联网搜索：零结果，顺序降级尝试下一候选词: tried={}", q);
            }
            long costMs = System.currentTimeMillis() - t0;
            List<WebSearchResult> merged = mergeFetchedIntoResults(pages, searched);
            log.info("澄清联网搜索结束: provider={}, queries={}, pages={}, results={}, costMs={}",
                    webSearchService.provider(), attempted, pages.size(), merged.size(), costMs);
            return WebSearchOutcome.builder()
                    .provider(webSearchService.provider())
                    .query(attempted.isEmpty() ? "" : attempted.get(0))
                    .queries(attempted)
                    .costMs(costMs)
                    .total(merged.size())
                    .results(merged)
                    .fetchedPages(pages)
                    .build();
        } catch (Exception e) {
            log.warn("澄清联网搜索异常降级（不动澄清主流程）: queries={}, err={}", attempted, e.getMessage());
            return WebSearchOutcome.builder()
                    .provider(webSearchService.provider())
                    .query(attempted.isEmpty() ? "" : attempted.get(0))
                    .queries(attempted)
                    .costMs(System.currentTimeMillis() - t0)
                    .fetchedPages(pages)
                    .failed(true)
                    .reason(e.getMessage())
                    .build();
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
}
