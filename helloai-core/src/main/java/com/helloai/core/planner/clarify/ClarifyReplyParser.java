package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 澄清对话的 LLM 输出解析与消息协议构造（双模解析 + assistant/user payload 组装）。
 *
 * <p>从 {@link com.helloai.core.planner.service.impl.RequirementClarifyServiceImpl} 拆分
 * （CODE_STYLE §7.8 类规模红线）：主类是意图状态机与 LLM 编排的宿主，本类承载
 * 无状态纯函数族（fence/strip 容错、question/final 双路径归一、structured 校验补缺、
 * 可读正文合成、webSearch 查验键 payload 构造），职责单一且可独立单测。</p>
 *
 * <p>协议类型（{@link RequirementClarifyService.ClarifyReply} 等）为
 * {@link RequirementClarifyService} 接口嵌套类：实现类经成员类型继承可用裸名，
 * 本类非实现类，一律用限定名引用。</p>
 */
@Slf4j
@Component
public class ClarifyReplyParser {

    /** 追问形态（双模协议）：structured 结构化选项式 / freeform 自由文本。
     *  public 供同域协议类（确认卡构造等）跨类引用。 */
    public static final String MODE_STRUCTURED = "structured";
    public static final String MODE_FREEFORM = "freeform";

    /** BizException 附带的 LLM 原始输出摘要截断长度。 */
    private static final int RAW_OUTPUT_SUMMARY_LIMIT = 500;

    /** assistant 消息 payload 的联网搜索查验键（与 mode/progress/questions 同级）。 */
    private static final String PAYLOAD_KEY_WEB_SEARCH = "webSearch";

    private final ObjectMapper objectMapper;

    /**
     * 显式构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑，与主类口径一致）。
     */
    public ClarifyReplyParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 LLM 输出：strip fence 容错 + type/message 必填校验。
     *
     * <p>降级策略（降级是一等公民路径，不是异常）：
     * <ul>
     *   <li>输出完全不是 JSON 且不含 {@code "type"} 字样 → 原文作 freeform 追问落库；</li>
     *   <li>含 {@code "type"} 但解析失败 → 保持抛 BizException 走现有 retry 链路；</li>
     *   <li>structured 追问校验不过（无问题/无选项）→ 降级 freeform。</li>
     * </ul>
     */
    public RequirementClarifyService.ClarifyReply parseReply(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("澄清 LLM 返回内容为空");
        }
        String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(rawOutput));
        RequirementClarifyService.ClarifyReply reply;
        try {
            reply = objectMapper.readValue(cleaned, RequirementClarifyService.ClarifyReply.class);
        } catch (Exception e) {
            if (!rawOutput.contains("\"type\"")) {
                log.warn("澄清 LLM 输出非 JSON，降级为 freeform 追问: {}", summarize(rawOutput));
                RequirementClarifyService.ClarifyReply fallback = new RequirementClarifyService.ClarifyReply();
                fallback.setType("question");
                fallback.setMode(MODE_FREEFORM);
                fallback.setMessage(rawOutput.trim());
                return fallback;
            }
            throw new BizException("澄清 LLM 输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (reply == null || reply.getType() == null) {
            throw new BizException("澄清 LLM 输出缺少 type 字段; 原始输出摘要: " + summarize(rawOutput));
        }
        if ("question".equals(reply.getType())) {
            normalizeQuestionReply(reply, rawOutput);
        } else if ("final".equals(reply.getType())) {
            if (reply.getTitle() == null || reply.getTitle().isBlank()) {
                throw new BizException("澄清 LLM 终稿缺少 title 字段");
            }
            if (reply.getDescription() == null || reply.getDescription().isBlank()) {
                throw new BizException("澄清 LLM 终稿缺少 description 字段");
            }
        } else {
            throw new BizException("澄清 LLM 输出 type 非法: " + reply.getType());
        }
        return reply;
    }

    /**
     * 追问形态归一化：mode 缺省按 freeform；structured 校验不过降级 freeform；
     * structured 合法时补齐缺省 id/value；freeform 形态 message 必填。
     */
    private void normalizeQuestionReply(RequirementClarifyService.ClarifyReply reply, String rawOutput) {
        if (MODE_STRUCTURED.equals(reply.getMode())) {
            if (isStructuredValid(reply)) {
                fillStructuredDefaults(reply);
                return;
            }
            log.warn("澄清 structured 追问校验失败，降级 freeform: {}", summarize(rawOutput));
            reply.setQuestions(null);
        }
        reply.setMode(MODE_FREEFORM);
        if (reply.getMessage() == null || reply.getMessage().isBlank()) {
            throw new BizException("澄清 LLM 追问缺少 message 字段");
        }
    }

    /** structured 追问最低要求：至少一题，每题有文本且至少一个带 label 的选项。 */
    private boolean isStructuredValid(RequirementClarifyService.ClarifyReply reply) {
        if (reply.getQuestions() == null || reply.getQuestions().isEmpty()) {
            return false;
        }
        for (RequirementClarifyService.ClarifyQuestion question : reply.getQuestions()) {
            if (question == null || question.getText() == null || question.getText().isBlank()) {
                return false;
            }
            if (question.getOptions() == null || question.getOptions().isEmpty()) {
                return false;
            }
            for (RequirementClarifyService.ClarifyOption option : question.getOptions()) {
                if (option == null || option.getLabel() == null || option.getLabel().isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** structured 合法后补齐缺省值：问题 id 缺失用 q{序号}，选项 value 缺失用 label。 */
    private void fillStructuredDefaults(RequirementClarifyService.ClarifyReply reply) {
        int idx = 1;
        for (RequirementClarifyService.ClarifyQuestion question : reply.getQuestions()) {
            if (question.getId() == null || question.getId().isBlank()) {
                question.setId("q" + idx);
            }
            idx++;
            for (RequirementClarifyService.ClarifyOption option : question.getOptions()) {
                if (option.getValue() == null || option.getValue().isBlank()) {
                    option.setValue(option.getLabel());
                }
            }
        }
    }

    /**
     * assistant 消息可读正文：structured 时把引导语 + 问题/选项文本合成进 content，
     * 保证 transcript 历史对 LLM 完整可读（payload 丢失也不影响上下文）。
     */
    public String composeAssistantContent(RequirementClarifyService.ClarifyReply reply) {
        if (!MODE_STRUCTURED.equals(reply.getMode())
                || reply.getQuestions() == null || reply.getQuestions().isEmpty()) {
            return reply.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        if (reply.getMessage() != null && !reply.getMessage().isBlank()) {
            sb.append(reply.getMessage().trim());
        }
        int idx = 1;
        for (RequirementClarifyService.ClarifyQuestion question : reply.getQuestions()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            List<String> labels = new ArrayList<>();
            for (RequirementClarifyService.ClarifyOption option : question.getOptions()) {
                labels.add(option.getLabel());
            }
            sb.append(idx++).append(". ").append(question.getText())
                    .append("（选项：").append(String.join(" / ", labels)).append("）");
        }
        return sb.toString();
    }

    /**
     * assistant 消息 payload：{@code {"mode","progress","questions","webSearch"}}；
     * freeform 且无 progress 且无搜索记录时返回 null（纯文本消息）；序列化失败降级 null 不阻断。
     *
     * @param outcome 本轮联网搜索记录（可为 null）；非空时合并 {@code webSearch} 键，
     *                前端据此渲染折叠查验条
     */
    public String buildQuestionPayload(RequirementClarifyService.ClarifyReply reply, WebSearchOutcome outcome) {
        boolean structured = MODE_STRUCTURED.equals(reply.getMode())
                && reply.getQuestions() != null && !reply.getQuestions().isEmpty();
        if (!structured && reply.getProgress() == null && outcome == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", structured ? MODE_STRUCTURED : MODE_FREEFORM);
        if (reply.getProgress() != null) {
            payload.put("progress", reply.getProgress());
        }
        if (structured) {
            payload.put("questions", reply.getQuestions());
        }
        if (outcome != null) {
            payload.put(PAYLOAD_KEY_WEB_SEARCH, buildWebSearchMap(outcome));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("澄清 assistant payload 序列化失败，降级纯文本消息", e);
            return null;
        }
    }

    /** 终稿轮的纯搜索查验 payload：{@code {"webSearch":{...}}}；序列化失败降级 null。 */
    public String buildWebSearchOnlyPayload(WebSearchOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of(PAYLOAD_KEY_WEB_SEARCH, buildWebSearchMap(outcome)));
        } catch (Exception e) {
            log.warn("澄清终稿轮 webSearch payload 序列化失败，降级纯文本消息", e);
            return null;
        }
    }

    /**
     * 搜索记录 → payload 嵌套 Map：provider/query/queries/costMs/total/results，
     * 失败时附 failed/reason；queries 为本轮实际尝试过的搜索词（多候选词顺序降级时多条），
     * results 每条含 title/url/snippet/siteName（前端查验条展开用）。
     */
    private Map<String, Object> buildWebSearchMap(WebSearchOutcome outcome) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("provider", outcome.getProvider());
        trace.put("query", outcome.getQuery());
        if (outcome.getQueries() != null && !outcome.getQueries().isEmpty()) {
            trace.put("queries", outcome.getQueries());
        }
        trace.put("costMs", outcome.getCostMs());
        trace.put("total", outcome.getTotal());
        if (outcome.isFailed()) {
            trace.put("failed", true);
            trace.put("reason", outcome.getReason());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (WebSearchResult r : outcome.getResults()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", r.getTitle());
            item.put("url", r.getUrl());
            item.put("snippet", r.getSnippet());
            item.put("siteName", r.getSiteName());
            items.add(item);
        }
        trace.put("results", items);
        // URL 直取记录（含失败记录可查验）；无 URL 时不落键，前端忽略未知键零兼容成本
        if (outcome.getFetchedPages() != null && !outcome.getFetchedPages().isEmpty()) {
            List<Map<String, Object>> fetched = new ArrayList<>();
            for (WebPageContent p : outcome.getFetchedPages()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("url", p.getUrl());
                f.put("title", p.getTitle());
                f.put("ok", p.isOk());
                if (p.isMetaOnly()) {
                    f.put("metaOnly", true);
                }
                f.put("textChars", p.getText() == null ? 0 : p.getText().length());
                if (!p.isOk()) {
                    f.put("reason", p.getReason());
                }
                fetched.add(f);
            }
            trace.put("fetched", fetched);
        }
        return trace;
    }

    /** user 消息 payload：{@code {"selections":[...]}}；无选择时返回 null；序列化失败降级 null。 */
    public String buildSelectionPayload(List<RequirementClarifyService.ClarifySelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("selections", selections));
        } catch (Exception e) {
            log.warn("澄清选择快照序列化失败，降级纯文本消息", e);
            return null;
        }
    }

    /**
     * CHAT 轮结构化追问宽松解析：仅认可 type=question 且 mode=structured 且校验合法；
     * 其余（freeform/final/非 JSON/解析失败）一律返回 null → 调用方按纯文本落库。
     * 不抛异常：CHAT 是自由对话，LLM 输出 JSON 仅是引导型增强（需要用户回答时出推荐卡片），
     * 失败降级为普通聊天，与 CLARIFY 的 parseReply 严格路径完全隔离。
     */
    public RequirementClarifyService.ClarifyReply tryParseChatStructured(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        try {
            String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(rawOutput));
            RequirementClarifyService.ClarifyReply reply =
                    objectMapper.readValue(cleaned, RequirementClarifyService.ClarifyReply.class);
            if (!"question".equals(reply.getType()) || !MODE_STRUCTURED.equals(reply.getMode())) {
                return null;
            }
            if (!isStructuredValid(reply)) {
                return null;
            }
            fillStructuredDefaults(reply);
            return reply;
        } catch (Exception e) {
            return null;
        }
    }

    /** 剥离 markdown 代码块围栏，并兜底截取首尾花括号之间的 JSON 对象（照 stripToJsonArray 改花括号版）。 */
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
