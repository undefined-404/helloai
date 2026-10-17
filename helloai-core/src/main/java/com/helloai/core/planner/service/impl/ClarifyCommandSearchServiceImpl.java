package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ClarifyCommandSearchService} 实现：澄清会话的<b>命令解析 + 联网搜索决策 + 会话状态守卫</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的<b>只读簇</b>，纯函数 + 已有
 * {@link ClarifyWebSearchOrchestrator} 依赖，零状态写入）。
 *
 * <p><b>本类为整条澄清链的叶子</b>：不依赖其余职责实现，因此被它们安全共享——这是「无环」落地的关键：</p>
 * <ul>
 *     <li>斜杠命令协议（门面建会话、编排器分发、流式拒绝三处共用）；</li>
 *     <li>搜索策略与检索执行；</li>
 *     <li>会话状态守卫与模式谓词（<b>RM9-c 实现层微调</b>：原在门面内，因门面 ↔ 编排器互为调用
 *         而必须下移至叶子类破环，详见 {@code RM计划 §4.3.3}「与旧表的实现层微调」）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyCommandSearchServiceImpl implements ClarifyCommandSearchService {

    /** 确认卡提交轮次搜索词回退时的短句阈值（<N 字视为无检索语义的意图句，仅影响搜索词质量）。 */
    private static final int SHORT_INTENT_LEN = 8;

    /** 转方案类斜杠命令前缀（/planner|/plan，与前端 PLANNER_COMMAND_RE 对齐：前缀后只接受空白或结束）。 */
    private static final List<String> PLANNER_COMMAND_PREFIXES = List.of("/planner", "/plan");

    /** 直达拆解命令（/task：需求描述即终稿输入，跳过多轮澄清直接产出终稿并建任务）。 */
    private static final String TASK_COMMAND_PREFIX = "/task";

    /** 对话角色常量（同包职责类共享；本类为叶子，集中定义避免多处漂移）。 */
    static final String ROLE_USER = "user";
    static final String ROLE_ASSISTANT = "assistant";

    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;
    private final RequirementMessageService messageService;
    private final ConfirmCardProtocol confirmCardProtocol;
    private final RequirementConversationService conversationService;

    // ══════════════════════════════════════════════════════════════
    //  斜杠命令解析
    // ══════════════════════════════════════════════════════════════

    @Override
    public boolean isPlannerCommand(String message) {
        for (String prefix : PLANNER_COMMAND_PREFIXES) {
            if (commandMatches(message, prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isTaskCommand(String message) {
        return commandMatches(message, TASK_COMMAND_PREFIX);
    }

    /** 斜杠命令通用匹配：忽略大小写，前缀后只接受空白或结束（防误伤普通文本）。 */
    private boolean commandMatches(String message, String prefix) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        String lower = trimmed.toLowerCase();
        return lower.startsWith(prefix)
                && (trimmed.length() == prefix.length()
                || Character.isWhitespace(trimmed.charAt(prefix.length())));
    }

    @Override
    public String plannerCommandExtra(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        for (String prefix : PLANNER_COMMAND_PREFIXES) {
            if (trimmed.toLowerCase().startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    @Override
    public String taskCommandExtra(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        if (trimmed.toLowerCase().startsWith(TASK_COMMAND_PREFIX)) {
            return trimmed.substring(TASK_COMMAND_PREFIX.length()).trim();
        }
        return "";
    }

    // ══════════════════════════════════════════════════════════════
    //  会话状态守卫 / 模式谓词
    // ══════════════════════════════════════════════════════════════

    @Override
    public RequirementConversation requireActive(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        if (!RequirementClarifyService.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new BizException("会话已结束（" + conversation.getStatus()
                    + "），请新建会话: conversationId=" + conversationId);
        }
        return conversation;
    }

    @Override
    public boolean isClarifyMode(RequirementConversation conversation) {
        return conversation.getMode() == null
                || RequirementClarifyService.MODE_CLARIFY.equals(conversation.getMode());
    }

    @Override
    public boolean isChatMode(RequirementConversation conversation) {
        return RequirementClarifyService.MODE_CHAT.equals(conversation.getMode());
    }

    // ══════════════════════════════════════════════════════════════
    //  联网搜索策略与检索
    // ══════════════════════════════════════════════════════════════

    @Override
    public boolean isWebSearchEnabled(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        return v == null || v;
    }

    @Override
    public SearchPolicy resolveSearchPolicy(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        if (v == null) {
            return SearchPolicy.AUTO;
        }
        return v ? SearchPolicy.ALWAYS_ON : SearchPolicy.OFF;
    }

    @Override
    public WebSearchOutcome resolveChatSearchOutcome(RequirementConversation conversation,
                                                     String userMessage, String userPayload,
                                                     ChatRoundDecisionParser.ChatRoundDecision decision,
                                                     boolean degraded) {
        switch (resolveSearchPolicy(conversation)) {
            case ALWAYS_ON:
                // 每轮搜索，LLM 只提供优化词（优先候选），不决策是否需要
                return doChatWebSearch(conversation, userMessage, userPayload,
                        decision.webSearch() == null ? null : decision.webSearch().searchQuery());
            case OFF:
                return null;
            default:
                // AUTO：LLM 决策 need_search；need=false 或未输出搜索决策 → 不搜；
                // 决策降级（解析失败/调用异常）时规则搜索兜底，继承既有每轮检索行为
                if (degraded) {
                    return doChatWebSearch(conversation, userMessage, userPayload, null);
                }
                if (decision.webSearch() == null || !decision.webSearch().needSearch()) {
                    return null;
                }
                return doChatWebSearch(conversation, userMessage, userPayload,
                        decision.webSearch().searchQuery());
        }
    }

    /** 执行一次 CHAT 轮搜索：LLM 优化词作优先候选（空白/空串跳过），规则词兜底。 */
    private WebSearchOutcome doChatWebSearch(RequirementConversation conversation,
                                             String userMessage, String userPayload,
                                             String priorityQuery) {
        List<String> priority = (priorityQuery == null || priorityQuery.isBlank())
                ? null : List.of(priorityQuery);
        return webSearchOrchestrator.doWebSearch(
                resolveSearchSource(conversation.getId(), userMessage, userPayload), priority);
    }

    @Override
    public String resolveSearchSource(Long conversationId, String userMessage, String userPayload) {
        if (userPayload != null && userPayload.contains("\"selections\"")) {
            List<RequirementMessage> history = messageService.listByConversation(conversationId);
            for (int i = history.size() - 1; i >= 0; i--) {
                RequirementMessage m = history.get(i);
                if (!ROLE_USER.equals(m.getRole()) || m.getContent() == null) {
                    continue;
                }
                String content = m.getContent().trim();
                if (confirmCardProtocol.isQuestionPrefix(content) || content.length() < SHORT_INTENT_LEN) {
                    continue;
                }
                return content;
            }
            return "";
        }
        return (userMessage != null && !userMessage.isBlank()) ? userMessage : "";
    }
}
