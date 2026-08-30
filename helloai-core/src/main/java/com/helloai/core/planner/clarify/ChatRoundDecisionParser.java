package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 对话轮次联合决策的 LLM 输出解析（意图路由 + 联网搜索决策）。
 *
 * <p>CHAT 轮主回复之前由 RequirementClarifyServiceImpl 做一次低预算前置决策调用
 * （prompts/requirement-decision.md），本类校验其 JSON 输出。设计对齐 ZLAgent
 * {@code JsonIntentRouter}:</p>
 * <ul>
 *   <li>严格白名单：未知字段一律拒绝（抛 {@link DecisionParseException}，调用方捕获降级，绝不阻塞主流程）；</li>
 *   <li>词表约束：intent（chat/clarify）与 intent_reason（4 值）按 {@link #REASONS_BY_INTENT} 映射表强制匹配；</li>
 *   <li>澄清问题约束：clarify 必须携带非空澄清问题，chat 必须为 null（带非空问题宽容忽略并告警）；</li>
 *   <li>搜索宽容：web_search 缺失/非对象按不搜索处理，need_search 缺失/非布尔按 false 处理，
 *       need_search=true 但搜索词缺失/空白时以空串表达「未提供 LLM 优化词」，调用方按规则词兜底，不丢搜索机会。</li>
 * </ul>
 *
 * <p>解析失败路径与成功路径同样是一等公民：{@link #defaults()} 提供降级决策
 * （intent=chat、不搜索），主流程可观测性由调用方记录失败原因。</p>
 */
@Slf4j
@Component
public class ChatRoundDecisionParser {

    /** intent 词表：chat 普通对话 / clarify 建议转入方案澄清模式。 */
    public static final String INTENT_CHAT = "chat";
    public static final String INTENT_CLARIFY = "clarify";

    /** intent_reason 词表（稳定词表，供可观测性与测试断言，不允许扩展新值）。 */
    public static final String REASON_DIRECT_ANSWER = "direct_answer";
    public static final String REASON_AMBIGUOUS = "ambiguous";
    public static final String REASON_TASK_ORIENTED = "task_oriented";
    public static final String REASON_NEED_CLARIFICATION = "need_clarification";

    /** intent 与 intent_reason 的允许映射（与 requirement-decision.md 第二节表格一致）。 */
    private static final Map<String, Set<String>> REASONS_BY_INTENT = Map.of(
            INTENT_CHAT, Set.of(REASON_DIRECT_ANSWER, REASON_AMBIGUOUS),
            INTENT_CLARIFY, Set.of(REASON_TASK_ORIENTED, REASON_NEED_CLARIFICATION));

    /** 顶层字段白名单（未知字段即拒绝，防 LLM 输出漂移）。 */
    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("intent", "intent_reason", "clarification_question", "web_search");

    /** web_search 子对象字段白名单。 */
    private static final Set<String> WEB_SEARCH_KEYS = Set.of("need_search", "search_query", "reason");

    /** 决策输出摘要的截断长度（异常与告警日志用）。 */
    private static final int RAW_OUTPUT_SUMMARY_LIMIT = 200;

    private final ObjectMapper objectMapper;

    /**
     * 显式构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑，与同域解析类口径一致）。
     */
    public ChatRoundDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 联合决策结果（不可变）。webSearch 为 null 表示 LLM 未输出搜索决策（按不搜索处理）。 */
    public record ChatRoundDecision(
            String intent, String intentReason, String clarificationQuestion, SearchDecision webSearch) {

        /** 是否为建议转入澄清模式的意图。 */
        public boolean isClarify() {
            return INTENT_CLARIFY.equals(intent);
        }
    }

    /** 联网搜索决策（不可变）。needSearch=false 时 searchQuery/reason 恒为 null。 */
    public record SearchDecision(boolean needSearch, String searchQuery, String reason) {
    }

    /**
     * 解析失败专用异常（调用方捕获后按 {@link #defaults()} 降级）。
     * 消息内附原始输出摘要，便于排查 LLM 模板遵循率问题。
     */
    public static class DecisionParseException extends RuntimeException {

        public DecisionParseException(String message) {
            super(message);
        }

        public DecisionParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 降级默认决策：intent=chat 保持对话、不搜索，intentReason 为 null 表示非 LLM 决策。
     * 调用方按当前搜索模式自行决定规则搜索兜底（ALWAYS_ON/AUTO 降级不丢搜索机会）。
     */
    public static ChatRoundDecision defaults() {
        return new ChatRoundDecision(INTENT_CHAT, null, null, new SearchDecision(false, null, null));
    }

    /**
     * 解析联合决策 JSON 输出（strip fence 容错 + 白名单词表校验）。
     *
     * @param raw LLM 原始输出
     * @return 联合决策；任一严格校验失败抛 {@link DecisionParseException}
     */
    public ChatRoundDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DecisionParseException("联合决策输出为空");
        }
        String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(raw));
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new DecisionParseException("联合决策输出不是合法 JSON: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(raw));
        }
        if (root == null || !root.isObject()) {
            throw new DecisionParseException("联合决策输出必须是 JSON 对象; 原始输出摘要: " + summarize(raw));
        }
        rejectUnknownFields(root, TOP_LEVEL_KEYS, "顶层");

        JsonNode intentNode = root.get("intent");
        if (intentNode == null || !intentNode.isTextual()) {
            throw new DecisionParseException("intent 缺失或非字符串; 原始输出摘要: " + summarize(raw));
        }
        String intent = intentNode.asText();
        if (!REASONS_BY_INTENT.containsKey(intent)) {
            throw new DecisionParseException("intent 非法（必须为 chat|clarify）: " + intent);
        }

        JsonNode reasonNode = root.get("intent_reason");
        if (reasonNode == null || !reasonNode.isTextual()) {
            throw new DecisionParseException("intent_reason 缺失或非字符串; 原始输出摘要: " + summarize(raw));
        }
        String intentReason = reasonNode.asText();
        if (!REASONS_BY_INTENT.get(intent).contains(intentReason)) {
            throw new DecisionParseException("intent_reason 与 intent 不匹配: intent=" + intent
                    + ", intent_reason=" + intentReason);
        }

        String question = null;
        JsonNode questionNode = root.get("clarification_question");
        if (questionNode != null && !questionNode.isNull()) {
            if (!questionNode.isTextual()) {
                throw new DecisionParseException("clarification_question 必须是字符串或 null");
            }
            question = questionNode.asText();
        }
        if (INTENT_CLARIFY.equals(intent)) {
            if (question == null || question.isBlank()) {
                throw new DecisionParseException("intent=clarify 必须携带非空 clarification_question");
            }
        } else if (question != null && !question.isBlank()) {
            // 宽容：chat 带非空澄清问题属格式漂移，忽略问题保留聊天语义
            log.warn("intent=chat 但携带澄清问题，已忽略: {}", summarize(question));
            question = null;
        }

        return new ChatRoundDecision(intent, intentReason, question, parseWebSearch(root.get("web_search"), raw));
    }

    /** 解析 web_search 子对象；宽容策略见类注释，任何告警不改变「不丢搜索机会」原则。 */
    private SearchDecision parseWebSearch(JsonNode node, String raw) {
        if (node == null || node.isNull()) {
            // 省略 web_search 视为不搜索（合法简写，不告警）
            return new SearchDecision(false, null, null);
        }
        if (!node.isObject()) {
            log.warn("web_search 必须是对象，按不搜索处理; 原始输出摘要: {}", summarize(raw));
            return new SearchDecision(false, null, null);
        }
        rejectUnknownFields(node, WEB_SEARCH_KEYS, "web_search");

        boolean needSearch = false;
        JsonNode needNode = node.get("need_search");
        if (needNode == null) {
            log.warn("web_search.need_search 缺失，按 false 处理");
        } else if (!needNode.isBoolean()) {
            log.warn("web_search.need_search 必须是布尔值，按 false 处理");
        } else {
            needSearch = needNode.asBoolean();
        }

        String query = null;
        JsonNode queryNode = node.get("search_query");
        if (queryNode != null && !queryNode.isNull()) {
            if (queryNode.isTextual()) {
                query = queryNode.asText();
            } else {
                log.warn("web_search.search_query 必须是字符串或 null，按缺失处理");
            }
        }
        if (needSearch && (query == null || query.isBlank())) {
            // 搜索词缺失不丢搜索机会：空串表达「未提供 LLM 优化词」，调用方以规则词兜底
            log.warn("need_search=true 但 search_query 缺失或空白，以规则兜底词搜索");
            query = "";
        }
        // need_search=false 时 query 原样保留：ALWAYS_ON 模式忽略 need 字段、仅取 search_query
        // 作优化词（LLM 只贡献优化词、不决策 need），AUTO 分支不读 query 无副作用——收敛为 null
        // 会把 ALWAYS_ON 的优化词一并丢掉，故不收敛

        String reason = null;
        JsonNode reasonNode = node.get("reason");
        if (reasonNode != null && reasonNode.isTextual()) {
            reason = reasonNode.asText();
        }
        return new SearchDecision(needSearch, query, reason);
    }

    /** 白名单校验：出现白名单之外字段即拒绝（对齐 ZLAgent 未知字段拒绝策略）。 */
    private void rejectUnknownFields(JsonNode object, Set<String> allowedKeys, String scope) {
        var fieldNames = object.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (!allowedKeys.contains(key)) {
                throw new DecisionParseException(scope + "字段白名单外出现未知字段: " + key);
            }
        }
    }

    /** 剥离 markdown 代码块围栏，并兜底截取首尾花括号之间的 JSON 对象（与 ClarifyReplyParser 同款实现）。 */
    private String stripToJsonObject(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("{")) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    private String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= RAW_OUTPUT_SUMMARY_LIMIT
                ? trimmed : trimmed.substring(0, RAW_OUTPUT_SUMMARY_LIMIT) + "...";
    }
}