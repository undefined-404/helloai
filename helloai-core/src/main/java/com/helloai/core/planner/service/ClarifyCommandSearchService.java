package com.helloai.core.planner.service;

import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.search.WebSearchOutcome;

/**
 * 澄清会话的<b>命令解析 + 联网搜索决策 + 会话状态守卫</b>接口（RM9-c 从
 * {@link RequirementClarifyService} 实现类拆出的职责契约）。
 *
 * <p>实现见 {@code com.helloai.core.planner.service.impl.ClarifyCommandSearchServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1}「{@code {domain}.service} 放 {@code XxxService}
 * 接口、{@code {domain}.service.impl} 放 {@code XxxServiceImpl} 实现」组织：本接口为
 * 整条澄清链的<b>叶子契约</b>（不被其余职责接口反向依赖），供门面 / 轮次编排 / 流式 /
 * 终稿守卫 / 模板渲染共同依赖。</p>
 */
public interface ClarifyCommandSearchService {

    /** 搜索三态策略（不落库，由 web_search_enabled 持久值派生）：NULL=AUTO / true=ALWAYS_ON / false=OFF。 */
    enum SearchPolicy {
        /** LLM 决策 need_search + 优化词。 */
        AUTO,
        /** 每轮搜索，LLM 只生成优化词（不决策是否需要）。 */
        ALWAYS_ON,
        /** 不搜索。 */
        OFF
    }

    /** 是否转方案类斜杠命令（/planner|/plan，忽略大小写；前缀后只接受空白或结束，防误伤普通文本）。 */
    boolean isPlannerCommand(String message);

    /** 是否直达拆解命令（/task）。 */
    boolean isTaskCommand(String message);

    /** 斜杠命令后的附加文本；非命令返回空串。 */
    String plannerCommandExtra(String message);

    /** /task 命令附加文本（需求描述体，即终稿直出轮的输入）；非 /task 命令返回空串。 */
    String taskCommandExtra(String message);

    /** 校验会话存在且处于 ACTIVE，返回会话实体；否则抛 {@code BizException}。 */
    RequirementConversation requireActive(Long conversationId);

    /** 是否方案澄清模式：NULL 老数据按 CLARIFY 兼容。 */
    boolean isClarifyMode(RequirementConversation conversation);

    /** 是否自由对话模式：仅显式 CHAT（NULL 老数据不算，按 CLARIFY）。 */
    boolean isChatMode(RequirementConversation conversation);

    /** NULL/true 视为开启；只有严格的 false 走关闭语义。 */
    boolean isWebSearchEnabled(RequirementConversation conversation);

    /** 搜索三态语义映射：NULL=AUTO / true=ALWAYS_ON / false=OFF。 */
    SearchPolicy resolveSearchPolicy(RequirementConversation conversation);

    /** CHAT 轮 chat 意图的搜索策略分发（AUTO 按 LLM 决策，降级时规则搜索兜底）。 */
    WebSearchOutcome resolveChatSearchOutcome(RequirementConversation conversation,
                                              String userMessage, String userPayload,
                                              ChatRoundDecisionParser.ChatRoundDecision decision,
                                              boolean degraded);

    /** 解析联网搜索查询词来源（普通轮直接用本轮消息；确认卡提交轮回退历史主题消息）。 */
    String resolveSearchSource(Long conversationId, String userMessage, String userPayload);
}
