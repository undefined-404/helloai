package com.helloai.core.planner.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 联网搜索单条结果（供应商无关的归一化模型）。
 *
 * <p>博查 / Tavily 各自返回结构不同，由具体实现负责解析成本模型；
 * 业务侧（{@code RequirementClarifyService}）只依赖本模型注入提示词，
 * 避免被供应商 API 形态绑死，便于后续扩展。</p>
 *
 * <p>web_search 工具输出 JSON 模型（阶段四）：作为平台工具 WebSearchToolResult
 * 的嵌套条目会被 Jackson 反序列化（orchestrator 侧裸 ObjectMapper 解析工具输出），
 * 故显式声明 {@code @NoArgsConstructor + @AllArgsConstructor} 构造器契约——
 * 仅靠 {@code @Builder} 生成的私有全参构造器无法被 Jackson 探测（no Creators）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResult {

    /** 标题。前端 / Prompt 都用。 */
    private String title;

    /** 链接。可选是否注入 Prompt（默认注入便于引用溯源）。 */
    private String url;

    /** 摘要正文（已截断到 {@link com.helloai.common.config.WebSearchProperties#maxSnippetChars} 字内）。 */
    private String snippet;

    /** 来源站点名（若供应商提供）。 */
    private String siteName;
}
