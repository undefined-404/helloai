package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库（RAG，REF-4.x）配置。
 *
 * <p>落点：helloai-common · com.helloai.common.config（全仓 20+ 个 @ConfigurationProperties
 * 的既定落点），前缀 {@code helloai.kb}。</p>
 *
 * <aside>嵌入式维度（{@link #getDimensions()}）是**单向门**（ADR-002 §3）：一经上线即冻结，
 * 换维度须全量重算。默认 1024 与 ADR-002 冻结值一致。</aside>
 */
@Data
@ConfigurationProperties(prefix = "helloai.kb")
public class KbProperties {

    /** 总开关（REF-6.13：新能力默认关闭）；false ⇒ search_knowledge 不可用、管理端点拒服。 */
    private boolean enabled = false;

    /** 知识库文本分片大小（字符）。 */
    private int chunkSize = 600;

    /** 相邻分片重叠（字符），缓解切在句中被切断导致的语义割裂。 */
    private int chunkOverlap = 100;

    /** 单次检索返回分片上限（maxResults clamp 上界）。 */
    private int maxResults = 8;

    /** 单次检索默认返回分片数。 */
    private int defaultResults = 4;

    /** 注入模型上下文的 KB 内容总字符预算（REF-4.1 char_budget 契约）。 */
    private int charBudget = 4000;

    /** 嵌入模型（DashScope，复用 平台 LLM 供应商既有凭据）。 */
    private String model = "text-embedding-v4";

    /** 嵌入维度 —— 单向门，上线即冻结（默认 1024）。 */
    private int dimensions = 1024;

    /** 嵌入选用的既有 provider 代码（平台供应商之一）。 */
    private String provider = "dashscope";

    /** 嵌入服务连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** 嵌入服务读超时（毫秒）。 */
    private int readTimeoutMs = 60000;
}