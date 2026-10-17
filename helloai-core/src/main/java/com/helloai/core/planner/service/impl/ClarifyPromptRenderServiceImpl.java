package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.SystemTimeContextBuilder;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.entity.LongTermMemory;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ClarifyPromptRenderService} 实现：澄清/自由对话/终稿直出的<b>模板渲染 + 描述段守卫</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的<b>只读簇</b>：渲染为纯函数，不写会话状态）。
 *
 * <p>承接三块：</p>
 * <ul>
 *     <li><b>主模板渲染</b>：CLARIFY / CHAT / FINALIZE 三模板共用（N-012 Context 分层：
 *         Current User Input 独立层 + 窗口历史 + 联网资料 + 系统时间 + 长期记忆 recall）；</li>
 *     <li><b>决策模板渲染</b>：CHAT 轮低预算联合决策（近 6 条历史 + 搜索策略提示）；</li>
 *     <li><b>描述段守卫</b>：P1-3 终稿 description 四小节纠偏重试，fail-open 不阻断建任务 + 审计写 timeline。</li>
 * </ul>
 *
 * <p><b>RM9-c 实现层微调</b>：{@code resolveMemoryContext} / {@code truncateContent}（N-009 记忆 recall，
 * 原随渲染消费）与 {@code windowHistory}（N-012 历史窗口裁剪，原为门面包内静态）随「渲染」职责一并归入本类。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyPromptRenderServiceImpl implements ClarifyPromptRenderService {

    /** 澄清方案模板（CLARIFY 模式主回复，JSON 协议）。 */
    private static final String PROMPT_TEMPLATE_PATH = "prompts/requirement-clarify.md";

    /** CHAT 自由对话 Prompt 模板（通用 AI 助手角色，纯文本回复，无 JSON 协议）。 */
    private static final String CHAT_PROMPT_TEMPLATE_PATH = "prompts/requirement-chat.md";

    /** CHAT 轮前置联合决策 Prompt 模板（意图路由 + 联网搜索决策，低预算调用）。 */
    private static final String CHAT_DECISION_TEMPLATE_PATH = "prompts/requirement-decision.md";

    /** /task 直达拆解终稿直出模板（信息严重不足时最多一轮结构化追问，其余直接产 final）。 */
    private static final String FINALIZE_TEMPLATE_PATH = "prompts/requirement-finalize.md";

    /** 联合决策历史裁剪条数（决策只看近期上下文，控制 token 预算）。 */
    private static final int DECISION_HISTORY_LIMIT = 6;

    /**
     * 主回复历史窗口条数（N-012 Planner Context 管理收口）。
     *
     * <p>长会话（&gt;窗口）只保留最近 {@code HISTORY_WINDOW} 条 + 首条用户消息锚点，
     * 消除全量拼接导致的 O(n²) token 膨胀；决策调用另有更小窗口
     * （{@link #DECISION_HISTORY_LIMIT}，6 条已证近期上下文足够意图识别）。</p>
     */
    private static final int HISTORY_WINDOW = 40;

    /**
     * 终稿 description 四小节分组关键词（P1-3，宽容匹配）：每组命中任一关键词即视为该组齐全，
     * 四组全中才判定「四小节完整」。不硬编码「## 背景与目标」这类标题措辞——LLM 变体极多，
     * 硬匹配误判率高到会白耗重试额度。
     */
    private static final List<List<String>> DESCRIPTION_SECTION_KEYWORDS = List.of(
            List.of("背景", "目标"),
            List.of("范围", "边界"),
            List.of("交付物", "交付"),
            List.of("验收"));

    /** 终稿 description 缺小节时的单次纠偏指令（追加到原 prompt 之后，仅重试轮使用）。 */
    private static final String DESCRIPTION_SECTION_RETRY_HINT = """

            补充要求（上一轮输出被判定不合格，请只重出一次完整 JSON）：
            你上一次返回的 description 没有完整覆盖四个小节。请重新输出完整的 final JSON，
            其中 description 必须用 Markdown 小节**完整写清**下列四部分（缺一不可）：
            背景与目标 / 范围与边界 / 交付物 / 验收标准。description 是完整规格正文，
            不得用 package 字段概括代替。
            """;

    private final RequirementMessageService messageService;
    private final RequirementConversationService conversationService;
    private final LongTermMemoryService longTermMemoryService;
    private final SystemTimeContextBuilder systemTimeContextBuilder;
    private final TaskTimelineService taskTimelineService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final ClarifyReplyParser replyParser;
    private final ClarifyCommandSearchService commandSearch;

    // ══════════════════════════════════════════════════════════════
    //  主模板渲染（三模板共用）
    // ══════════════════════════════════════════════════════════════

    @Override
    public String renderClarifyPrompt(Long conversationId, String webSearchContext) {
        return renderPrompt(conversationId, webSearchContext, PROMPT_TEMPLATE_PATH);
    }

    @Override
    public String renderChatPrompt(Long conversationId, String webSearchContext) {
        return renderPrompt(conversationId, webSearchContext, CHAT_PROMPT_TEMPLATE_PATH);
    }

    @Override
    public String renderFinalizePrompt(Long conversationId, String webSearchContext) {
        return renderPrompt(conversationId, webSearchContext, FINALIZE_TEMPLATE_PATH);
    }

    /**
     * 加载 classpath 模板并替换占位符（N-012 Context 分层收口）：
     * <ul>
     *   <li>{@code {{CURRENT_USER_MESSAGE}}} — 本轮最新用户消息，独立分层注入
     *       （从历史中抽离，避免与历史末行重复）；</li>
     *   <li>{@code {{CONVERSATION_HISTORY}}} — 窗口内历史（最近 {@link #HISTORY_WINDOW} 条
     *       + 首条用户消息锚点，服务端裁剪，不含本轮最新用户消息）；</li>
     *   <li>{@code {{WEB_SEARCH_CONTEXT}}} — 首轮预检索的联网资料；空串代表无资料，
     *       渲染为"（无可用联网资料）"占位符，保证 Prompt 该节语义节稳定。</li>
     * </ul>
     *
     * @param templatePath 按会话模式选模板（CLARIFY=requirement-clarify.md / CHAT=requirement-chat.md）
     */
    private String renderPrompt(Long conversationId, String webSearchContext, String templatePath) {
        ClassPathResource resource = new ClassPathResource(templatePath);
        if (!resource.exists()) {
            throw new BizException("未找到 Prompt 模板: " + templatePath);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取 Prompt 模板失败: " + e.getMessage());
        }
        List<RequirementMessage> messages = messageService.listByConversation(conversationId);
        // Current User Input 层：抽离最新用户消息（从后往前找第一条 user 角色）
        String currentUserInput = null;
        List<RequirementMessage> history = new ArrayList<>(messages);
        for (int i = history.size() - 1; i >= 0; i--) {
            if (ClarifyCommandSearchServiceImpl.ROLE_USER.equals(history.get(i).getRole())) {
                currentUserInput = history.get(i).getContent();
                history.remove(i);
                break;
            }
        }
        // Conversation 层：窗口裁剪（最近 HISTORY_WINDOW 条 + 首条用户消息锚点）
        List<RequirementMessage> windowed = windowHistory(history, HISTORY_WINDOW);
        StringBuilder transcript = new StringBuilder();
        for (RequirementMessage msg : windowed) {
            transcript.append(ClarifyCommandSearchServiceImpl.ROLE_USER.equals(msg.getRole()) ? "用户：" : "助手：")
                    .append(msg.getContent()).append('\n');
        }
        String contextSection = (webSearchContext == null || webSearchContext.isBlank())
                ? "（无可用联网资料）" : webSearchContext;
        return template
                .replace("{{CONVERSATION_HISTORY}}", transcript.toString().trim())
                .replace("{{CURRENT_USER_MESSAGE}}", currentUserInput != null ? currentUserInput : "")
                .replace("{{WEB_SEARCH_CONTEXT}}", contextSection)
                // 系统当前时间逐轮注入（第一层防线），保证 CHAT/CLARIFY/FINALIZE
                // 三个模板在回答时效性问题时均以服务器实时日期为基准
                .replace("{{SYSTEM_TIME_CONTEXT}}", systemTimeContextBuilder.build())
                // 长期记忆受控 recall（N-009，C5-S3）：有限条数 + 无结果占位 + 失败降级不阻断
                .replace("{{LONG_TERM_MEMORY_CONTEXT}}", resolveMemoryContext(conversationId));
    }

    @Override
    public String renderDecisionPrompt(RequirementConversation conversation, String userMessage) {
        ClassPathResource resource = new ClassPathResource(CHAT_DECISION_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到 Prompt 模板: " + CHAT_DECISION_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取 Prompt 模板失败: " + e.getMessage());
        }
        List<RequirementMessage> history = messageService.listByConversation(conversation.getId());
        // 最后一条是刚处理的本轮 user 消息（{{USER_MESSAGE}} 已单独给出），从历史中剔除
        int from = Math.max(0, history.size() - DECISION_HISTORY_LIMIT - 1);
        int to = Math.max(0, history.size() - 1);
        StringBuilder transcript = new StringBuilder();
        for (int i = from; i < to; i++) {
            RequirementMessage msg = history.get(i);
            transcript.append(ClarifyCommandSearchServiceImpl.ROLE_USER.equals(msg.getRole()) ? "用户：" : "助手：")
                    .append(msg.getContent()).append('\n');
        }
        String historyText = transcript.toString().trim();
        String policyHint = switch (commandSearch.resolveSearchPolicy(conversation)) {
            case ALWAYS_ON -> "本轮必须发起联网搜索（ALWAYS_ON），请给出优化搜索词";
            case OFF -> "本轮不发起联网搜索（OFF），need_search 必须为 false";
            default -> "由你自主决策是否搜索（AUTO）";
        };
        return template
                .replace("{{USER_MESSAGE}}", userMessage == null ? "" : userMessage)
                .replace("{{CONVERSATION_HISTORY}}",
                        historyText.isEmpty() ? "（无历史）" : historyText)
                .replace("{{SEARCH_POLICY}}", policyHint)
                // 每轮调用前注入系统当前时间（第一层防线：LLM 无系统时钟，
                // 跨天对话时须以实时时间锚定"今天"，不得用历史日期推断）
                .replace("{{SYSTEM_TIME_CONTEXT}}", systemTimeContextBuilder.build());
    }

    // ══════════════════════════════════════════════════════════════
    //  终稿 description 四小节守卫（P1-3）
    // ══════════════════════════════════════════════════════════════

    @Override
    public ClarifyReply applyDescriptionSectionGuard(RequirementConversation conversation,
                                                     AgentProfileSnapshot planner, String prompt,
                                                     String scene, ClarifyReply firstReply) {
        if (!isFinalMissingSections(firstReply)) {
            return firstReply;
        }
        Long conversationId = conversation.getId();
        log.warn("终稿 description 四小节不全，触发单次纠偏重试: conversationId={}, descriptionLength={}",
                conversationId, firstReply.getDescription() != null
                        ? firstReply.getDescription().length() : 0);
        ClarifyReply retried;
        try {
            AgentTask retryTask = AgentTask.builder()
                    .systemPrompt("")
                    .userPrompt(prompt + DESCRIPTION_SECTION_RETRY_HINT)
                    .context(Map.of("conversationId", conversationId, "scene", scene))
                    .requiredCapabilities(Map.of())
                    .build();
            AgentResult retryResult = platformAgentExecutionService.executeSync(planner.id(), retryTask);
            if (!retryResult.isSuccess()) {
                throw new BizException("重试 LLM 调用失败: " + retryResult.getErrorMessage());
            }
            retried = replyParser.parseReply(retryResult.getOutput());
        } catch (Exception e) {
            log.warn("终稿小节纠偏重试失败，放行首轮结果: conversationId={}, err={}",
                    conversationId, e.getMessage());
            recordDescriptionSectionWarn(conversationId, "retry_failed");
            return firstReply;
        }
        if (!"final".equals(retried.getType())) {
            // 重试降级为追问：不覆盖首轮终稿（追问不建任务，终稿才是用户要的结果）
            log.warn("终稿小节纠偏重试未产出 final，放行首轮结果: conversationId={}, type={}",
                    conversationId, retried.getType());
            recordDescriptionSectionWarn(conversationId, "retry_not_final");
            return firstReply;
        }
        if (isFinalMissingSections(retried)) {
            log.warn("终稿小节纠偏重试后仍缺小节，放行首轮结果: conversationId={}", conversationId);
            recordDescriptionSectionWarn(conversationId, "still_missing");
            return firstReply;
        }
        log.info("终稿 description 四小节纠偏重试成功: conversationId={}", conversationId);
        return retried;
    }

    /** final 形态且 description 未覆盖四小节（非 final / 空 description 由各自的必填校验负责）。 */
    private boolean isFinalMissingSections(ClarifyReply reply) {
        return reply != null && "final".equals(reply.getType())
                && !hasAllDescriptionSections(reply.getDescription());
    }

    /** 宽容匹配四小节：按 {@link #DESCRIPTION_SECTION_KEYWORDS} 分组，命中组数达 4 即视为完整。 */
    private boolean hasAllDescriptionSections(String description) {
        if (description == null || description.isBlank()) {
            return false;
        }
        int hit = 0;
        for (List<String> group : DESCRIPTION_SECTION_KEYWORDS) {
            for (String keyword : group) {
                if (description.contains(keyword)) {
                    hit++;
                    break;
                }
            }
        }
        return hit >= DESCRIPTION_SECTION_KEYWORDS.size();
    }

    /** 小节缺失审计：会话尚未建任务，按系统级事件记（taskId 可空 + payload 带 conversationId）。 */
    private void recordDescriptionSectionWarn(Long conversationId, String reason) {
        try {
            taskTimelineService.recordEvent(null, null, TimelineEventCode.REQUIREMENT_DESCRIPTION_SECTION_MISSING.code(),
                    AgentRole.PLANNER, null,
                    Map.of("conversationId", conversationId, "reason", reason));
        } catch (Exception e) {
            log.warn("终稿小节缺失审计写入失败（不阻断）: conversationId={}, err={}",
                    conversationId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  长期记忆 recall（N-009）
    // ══════════════════════════════════════════════════════════════

    /**
     * 受控 recall（N-009，C5-S3）：按会话标题检索历史记忆摘要（≤5 条，单条截断）；
     * 无结果占位"（无相关历史记忆）"；检索失败降级不阻断。
     */
    private String resolveMemoryContext(Long conversationId) {
        try {
            RequirementConversation conv = conversationService.getById(conversationId);
            String keyword = conv != null && conv.getTitle() != null ? conv.getTitle() : null;
            if (keyword == null || keyword.isBlank()) {
                return "（无相关历史记忆）";
            }
            List<LongTermMemory> memories = longTermMemoryService.searchForRecall(keyword, 5);
            if (memories.isEmpty()) {
                return "（无相关历史记忆）";
            }
            StringBuilder sb = new StringBuilder();
            for (LongTermMemory m : memories) {
                sb.append("- ").append(m.getTitle()).append("：")
                        .append(truncateContent(m.getContent(), 500)).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("记忆 recall 检索失败（降级不阻断）: conversationId={}, err={}", conversationId, e.getMessage());
            return "（无相关历史记忆）";
        }
    }

    private static String truncateContent(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    /**
     * 对话历史窗口裁剪（N-012 Context 分层收口，纯函数可单测）。
     *
     * <p>窗内（size ≤ window）原样返回；超窗保留最近 {@code window} 条，
     * 并把首条用户消息（需求起点）作为锚点前置——长会话不丢失原始意图。</p>
     */
    static List<RequirementMessage> windowHistory(List<RequirementMessage> history, int window) {
        if (history == null || history.isEmpty() || history.size() <= window) {
            return history;
        }
        List<RequirementMessage> tail = new ArrayList<>(history.subList(history.size() - window, history.size()));
        RequirementMessage firstUser = null;
        for (RequirementMessage m : history) {
            if (ClarifyCommandSearchServiceImpl.ROLE_USER.equals(m.getRole())) {
                firstUser = m;
                break;
            }
        }
        if (firstUser != null && tail.indexOf(firstUser) < 0) {
            tail.add(0, firstUser);
        }
        return tail;
    }
}
