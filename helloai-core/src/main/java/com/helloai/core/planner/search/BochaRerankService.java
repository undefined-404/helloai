package com.helloai.core.planner.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查 Semantic Reranker 语义重排（W-5）。
 *
 * <p>端点 {@code POST https://api.bochaai.com/v1/rerank}，模型 {@code gte-rerank}
 * （80M 参数，官方定位「接近一线 280M/560M 模型效果、推理更快成本更低」）。
 * 对 candidate 文档与用户意图做语义相关性打分（{@code relevance_score} 0~1），
 * 按分降序返回——替代搜索引擎原始顺序（原始顺序是检索相关性 + 热度的混合，
 * 长尾查询下与真实意图偏差大）。</p>
 *
 * <p><b>定位：可选增强，永不阻断</b>。以下情形一律原样返回入参列表（保持原顺序）：
 * 开关关闭（{@code rerankEnabled=false}，默认）/ 无博查 API Key / 结果不足 2 条
 * （无可排空间）/ 请求失败 / 响应不可解析 / 超时。搜索是澄清轮的前置增强，
 * 重排再失败也不得让本轮对话失去已有检索结果。</p>
 *
 * <p><b>不参与重排的对象</b>：用户消息中 URL 直取的页面（第一手资料）——由调用方
 * （{@code ClarifyWebSearchOrchestrator}）只把「搜索引擎检索结果」送进来，直取页面
 * 在合并阶段固定置顶。</p>
 *
 * <p><b>计费</b>：官方定价约 0.005 元/次（按请求计，与文档条数无关到 50 上限），
 * 故默认关闭、由管理员按成本意愿开启。</p>
 */
@Slf4j
@Service
public class BochaRerankService {

    /** 单次请求最大文档数（官方上限）。 */
    private static final int MAX_DOCUMENTS = 50;

    /** 送排文档的文本长度上限（rerank 模型按最高 512 token 评分，超长截断控 token）。 */
    private static final int MAX_DOC_TEXT_CHARS = 1_000;

    private final WebSearchProperties properties;
    private final WebSearchCredentialKeyStore credentialKeyStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BochaRerankService(WebSearchProperties properties,
                              WebSearchCredentialKeyStore credentialKeyStore,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.credentialKeyStore = credentialKeyStore;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1L, properties.getRerankTimeoutMs())))
                .build();
    }

    /**
     * 当前是否具备重排能力（开关开启 + 博查密钥就绪）。
     *
     * <p>便宜判定（不探测网络），供编排器在无必要时完全跳过本组件。</p>
     */
    public boolean isAvailable() {
        if (!properties.isRerankEnabled()) {
            return false;
        }
        String key = credentialKeyStore.resolveBochaApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * 按与查询意图的语义相关度重排结果。
     *
     * <p>只重排「送排子集」（受 {@link WebSearchProperties#getRerankMaxDocuments()} 约束），
     * 超出部分按原顺序附在重排结果之后——既控制成本，也不丢弃已有结果。
     * {@code rerankMinScore > 0} 时丢弃低于阈值的条目（默认 0 = 只排不过滤）。</p>
     *
     * @param query   语义锚（调用方传用户真实意图文本，非拆词后的候选词）
     * @param results 待重排的检索结果（调用方保证不含用户直取页面）
     * @return 重排后的新列表；不具备能力/失败/无排空间时返回**入参原列表**
     */
    public List<WebSearchResult> rerank(String query, List<WebSearchResult> results) {
        if (results == null || results.size() < 2 || !isAvailable()
                || query == null || query.isBlank()) {
            return results;
        }
        int maxDocs = Math.max(1, Math.min(properties.getRerankMaxDocuments(), MAX_DOCUMENTS));
        List<WebSearchResult> head = results.size() <= maxDocs ? results : results.subList(0, maxDocs);
        List<WebSearchResult> tail = results.size() <= maxDocs ? List.of() : results.subList(maxDocs, results.size());

        List<String> documents = new ArrayList<>(head.size());
        for (WebSearchResult r : head) {
            documents.add(documentText(r));
        }
        try {
            String body = objectMapper.writeValueAsString(bodyMap(query, documents));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getRerankBaseUrl()))
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getRerankTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credentialKeyStore.resolveBochaApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("博查 Rerank 返回非 2xx，降级原顺序: status={}, body={}",
                        response.statusCode(), truncate(response.body(), 200));
                return results;
            }
            List<WebSearchResult> reranked = parseReranked(response.body(), head);
            if (reranked.isEmpty()) {
                log.warn("博查 Rerank 响应无可解析结果，降级原顺序");
                return results;
            }
            List<WebSearchResult> merged = new ArrayList<>(reranked);
            merged.addAll(tail);
            log.info("博查 Rerank 完成: query={}, in={}, out={}, filtered={}",
                    query, results.size(), merged.size(), results.size() - merged.size());
            return merged;
        } catch (Exception e) {
            // 契约：重排失败绝不抛（调用方拿到原顺序，澄清主流程不受影响）
            log.warn("博查 Rerank 失败，降级原顺序: query={}, err={}", query, e.getMessage());
            return results;
        }
    }

    /** 组装请求体（不请求文档原文：结果对象本地已有，只要 index+score）。 */
    private Map<String, Object> bodyMap(String query, List<String> documents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getRerankModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);
        return body;
    }

    /**
     * 解析 {@code data.results[]}（已按 relevance_score 降序）→ 原结果列表重排。
     *
     * <p>{@code index} 是送排 documents 数组的下标（指向 head），据此映射回原对象；
     * 下标越界/重复丢弃（防上游脏数据）。低于 {@code rerankMinScore} 的条目跳过。</p>
     */
    private List<WebSearchResult> parseReranked(String body, List<WebSearchResult> head) {
        List<WebSearchResult> out = new ArrayList<>();
        boolean[] used = new boolean[head.size()];
        double minScore = properties.getRerankMinScore();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode arr = root.path("data").path("results");
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode item : arr) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= head.size() || used[index]) {
                    continue;
                }
                double score = item.path("relevance_score").asDouble(0);
                if (minScore > 0 && score < minScore) {
                    log.debug("Rerank 过滤低分条目: index={}, score={}, min={}", index, score, minScore);
                    continue;
                }
                used[index] = true;
                out.add(head.get(index));
            }
        } catch (Exception e) {
            log.warn("博查 Rerank 响应解析失败，降级原顺序: err={}", e.getMessage());
        }
        return out;
    }

    /** 送排文本：标题 + 摘要（只有标题太短，语义模型需要足够上下文）。 */
    private static String documentText(WebSearchResult r) {
        if (r == null) return "";
        String title = r.getTitle() == null ? "" : r.getTitle();
        String snippet = r.getSnippet() == null ? "" : r.getSnippet();
        String text = snippet.isBlank() ? title : title + "。 " + snippet;
        return text.length() <= MAX_DOC_TEXT_CHARS ? text : text.substring(0, MAX_DOC_TEXT_CHARS);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
