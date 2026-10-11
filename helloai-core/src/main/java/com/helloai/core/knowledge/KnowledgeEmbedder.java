package com.helloai.core.knowledge;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.config.KbProperties;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库（RAG）嵌入器 —— 复用 {@code spring-ai-openai} 的 {@link OpenAiEmbeddingModel} 走
 * DashScope OpenAI 兼容端点（ADR-002 §6「客户端复用既有 spring-ai-openai 兼容路径」，
 * 与 dashscope chat 同源，不新增依赖形态）。
 *
 * <p>凭据：运行时经 {@link PlatformProviderConfigService#getApiKey(String)} 取平台级
 * dashscope 条目（与内部 LLM Agent 共用，ADR-002 §4），不物化、不入档。
 * 缺失 ⇒ 显式抛「RAG 向量化不可用」（不静默降级，对齐 ADR-002 §6 前置判据）。</p>
 *
 * <p>分批：DashScope compatible-mode 单请求行数上限 ≤ 10（ADR-002 §6）。</p>
 */
@Component
public class KnowledgeEmbedder {

    /** DashScope compatible-mode 单请求行数上限（ADR-002 §6）。 */
    private static final int MAX_LINES_PER_REQUEST = 10;

    private final KbProperties kbProperties;
    private final PlatformProviderConfigService platformProviderConfigService;
    private final AgentProviderProperties providerProperties;
    private final Map<String, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    public KnowledgeEmbedder(KbProperties kbProperties,
                             PlatformProviderConfigService platformProviderConfigService,
                             AgentProviderProperties providerProperties) {
        this.kbProperties = kbProperties;
        this.platformProviderConfigService = platformProviderConfigService;
        this.providerProperties = providerProperties;
    }

    /** 嵌入单条文本 → double[]。 */
    public double[] embedOne(String text) {
        List<double[]> batch = embed(List.of(text));
        if (batch.isEmpty()) {
            throw new BizException("RAG 向量化不可用：嵌入返回空（provider=" + kbProperties.getProvider() + "）");
        }
        return batch.get(0);
    }

    /** 嵌入一批文本 → 保持输入顺序的向量数组。 */
    public List<double[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String provider = kbProperties.getProvider();
        String apiKey = platformProviderConfigService.getApiKey(provider);
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("RAG 向量化不可用：provider 凭据未配置（provider=" + provider + "）");
        }
        String baseUrl = resolveBaseUrl(provider);
        EmbeddingModel model = modelCache.computeIfAbsent(
                cacheKey(provider, apiKey, baseUrl, kbProperties.getModel()),
                k -> buildModel(apiKey, baseUrl, provider));

        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_LINES_PER_REQUEST) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + MAX_LINES_PER_REQUEST));
            EmbeddingResponse resp = model.call(new EmbeddingRequest(batch, options()));
            for (Embedding embedding : resp.getResults()) {
                out.add(toDoubleArray(embedding.getOutput()));
            }
        }
        return out;
    }

    private EmbeddingModel buildModel(String apiKey, String baseUrl, String provider) {
        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig(provider);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(kbProperties.getConnectTimeoutMs(), 1));
        requestFactory.setReadTimeout(Math.max(kbProperties.getReadTimeoutMs(), 1));
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        // dimensions 由配置固定（单向门，线上冻结 = kbProperties.dimensions），dashscope compatible 接受该参数
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options());
    }

    private OpenAiEmbeddingOptions options() {
        return OpenAiEmbeddingOptions.builder()
                .model(kbProperties.getModel())
                .dimensions(kbProperties.getDimensions())
                .build();
    }

    private String resolveBaseUrl(String provider) {
        String platformUrl = platformProviderConfigService.getBaseUrl(provider);
        if (platformUrl != null && !platformUrl.isBlank()) {
            return platformUrl;
        }
        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig(provider);
        if (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            return config.getBaseUrl();
        }
        throw new BizException("RAG 向量化不可用：provider base-url 未配置（provider=" + provider + "）");
    }

    private String cacheKey(String provider, String apiKey, String baseUrl, String model) {
        // apiKey 入 key：轮换后自动重建模型，避免用旧 key 的缓存实例
        return provider + "|" + apiKey.length() + ":" + apiKey.hashCode() + "|" + baseUrl + "|" + model;
    }

    private double[] toDoubleArray(float[] values) {
        if (values == null || values.length == 0) {
            throw new BizException("RAG 向量化不可用：嵌入维度为空");
        }
        int expect = kbProperties.getDimensions();
        if (values.length != expect) {
            throw new BizException("RAG 向量化不可用：嵌入维度=" + values.length + "，期望=" + expect
                    + "（维度为单向门，须对齐 ADR-002 冻结值）");
        }
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }
}