package com.helloai.core.planner.search;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 一次联网搜索的归一化结果记录：搜索过程与结局的完整快照。
 *
 * <p>纯内存领域对象（不映射表、不属 entity 包）：既承担 Prompt 注入文本的渲染
 * （{@link #toContextText()}，沿用原 renderWebSearchContext 格式），又随 assistant
 * 消息 payload 落库（{@code webSearch} 键），供前端渲染可折叠查验条——
 * 对齐 DeepSeek「已搜索 xx 个网页」/ Kimi「已联网检索 · N 个信源」形态，
 * 让用户可查验"是否真正搜索了、搜了什么词、来源内容是否正确"。</p>
 *
 * <p>失败/空结果不再静默吞掉：{@code failed=true} + {@code reason} 或 {@code total=0}
 * 同样落 payload，界面可查验异常结局。</p>
 */
@Data
@Builder
public class WebSearchOutcome {

    /** 搜索供应商标识（如 bocha / tavily，取 {@code WebSearchService.provider()}）。 */
    private String provider;

    /** 实际发起搜索的首个查询词（老数据兼容；多候选词全量见 {@link #queries}）。 */
    private String query;

    /**
     * 本轮实际尝试过的搜索词（按顺序，含顺序降级重试的词）：
     * 查询规划器产出多候选词时逐个尝试，首个命中即停；
     * 落 payload 的 {@code queries} 键供查验条展示"实际搜了哪几个词"。
     */
    @Builder.Default
    private List<String> queries = Collections.emptyList();

    /** 搜索耗时（毫秒）。 */
    private long costMs;

    /** 结果条数（0 表示搜了但无结果）。 */
    private int total;

    /** 归一化结果列表（无结果/失败时为空列表）。 */
    @Builder.Default
    private List<WebSearchResult> results = Collections.emptyList();

    /**
     * 用户消息 URL 直取记录：消息含 http(s) 链接时直接访问抓取的页面快照
     * （含失败记录，payload 可查验）；无 URL 时为空列表。
     * SPA 空壳页正文为空时以 title/meta 描述兜底（{@code metaOnly=true}），
     * 仍按成功直取注入 Prompt。
     */
    @Builder.Default
    private List<WebPageContent> fetchedPages = Collections.emptyList();

    /** 搜索是否失败（调用抛异常时为 true，主流程不阻断）。 */
    private boolean failed;

    /** 失败原因摘要（failed=true 时填充，异常 message 截断）。 */
    private String reason;

    /**
     * 渲染为注入 Prompt 的资料文本：直取页面节（用户给出的站点第一手资料）+
     * 搜索引擎结果节（每条双行：标题（链接）+ 摘要）。两节均空时输出占位符
     * 保证 Prompt 该节语义稳定。
     */
    public String toContextText() {
        boolean hasPages = fetchedPages != null && fetchedPages.stream().anyMatch(WebPageContent::isOk);
        boolean hasResults = results != null && !results.isEmpty();
        if (!hasPages && !hasResults) {
            return "（无可用联网资料）";
        }
        StringBuilder sb = new StringBuilder();
        if (hasPages) {
            long okCount = fetchedPages.stream().filter(WebPageContent::isOk).count();
            sb.append("以下是直接访问用户提供的网页后抓取的内容（共 ").append(okCount).append(" 页，第一手资料优先）：\n");
            int idx = 1;
            for (WebPageContent p : fetchedPages) {
                if (!p.isOk()) continue;
                sb.append('[').append(idx++).append("] ").append(p.getTitle());
                if (p.getUrl() != null && !p.getUrl().isBlank()) {
                    sb.append("（").append(p.getUrl()).append("）");
                }
                sb.append('\n').append(p.getText()).append("\n\n");
            }
        }
        if (hasResults) {
            sb.append("以下是联网检索到的行业资料（上限 ").append(results.size()).append(" 条，按相关性排序）：\n");
            for (int i = 0; i < results.size(); i++) {
                WebSearchResult r = results.get(i);
                sb.append('[').append(i + 1).append("] ").append(r.getTitle());
                if (r.getUrl() != null && !r.getUrl().isBlank()) {
                    sb.append("（").append(r.getUrl()).append("）");
                }
                sb.append('\n').append(r.getSnippet());
                if (i < results.size() - 1) {
                    sb.append("\n\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }
}
