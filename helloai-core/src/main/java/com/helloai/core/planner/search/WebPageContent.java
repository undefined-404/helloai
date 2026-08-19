package com.helloai.core.planner.search;

import lombok.Builder;
import lombok.Data;

/**
 * 一次用户消息 URL 直取的归一化记录（V43）：直接访问用户给出的网页后抓取的页面快照。
 *
 * <p>纯内存领域对象（不映射表）：成功时 {@code text} 为剥离标签后的正文（已按
 * {@code WebSearchProperties#urlFetchMaxTextChars} 截断），随 {@link WebSearchOutcome}
 * 注入 Prompt 与落 payload 供查验；失败时 {@code ok=false} + {@code reason}，
 * payload 可查验，不阻断澄清主流程。</p>
 */
@Data
@Builder
public class WebPageContent {

    /** 实际访问的 URL（用户消息中提取）。 */
    private String url;

    /** 抓取是否成功（2xx + 文本类 Content-Type + 正文非空）。 */
    private boolean ok;

    /** 失败原因摘要（ok=false 时填充）。 */
    private String reason;

    /** 页面标题（&lt;title&gt; 提取，缺失时回退 URL host）。 */
    private String title;

    /** 剥离标签/脚本后的正文纯文本（已截断；ok=false 时为空串）。 */
    private String text;

    /**
     * V44 SPA 空壳元数据兜底标记：正文为空但 &lt;title&gt;/meta 描述存在时，
     * {@code text} 为拼合的元数据文本（站点名+描述），本标记区分于真实正文抓取。
     */
    private boolean metaOnly;
}
