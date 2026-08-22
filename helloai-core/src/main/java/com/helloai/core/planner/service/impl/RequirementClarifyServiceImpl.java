package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
import com.helloai.core.planner.clarify.IntentDetectionService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对话式需求澄清编排服务实现（需求模糊 → 多轮追问 → 终稿 → 创建任务）。
 *
 * <p>复用 {@link com.helloai.core.planner.service.PlannerAnalysisService} 的五段式范式：
 * 选 Agent → classpath 模板渲染 → executeSync → strip fence 容错解析 → BizException。
 * 类不加事务：LLM 调用耗时较长，不能占用数据库事务；单条消息落库走 ServiceImpl 自带事务。</p>
 *
 * <p>LLM/解析失败时：user 消息保留（round_count 已加），抛 BizException，
 * 前端弹错后可重发一条消息重试；会话本身无状态回退需求（始终 ACTIVE）。</p>
 */
@Slf4j
@Service
public class RequirementClarifyServiceImpl implements RequirementClarifyService {

    /** 用户消息轮数硬上限（Prompt 侧引导尽早出终稿，服务端只做兜底）。 */
    private static final int MAX_ROUNDS = 20;

    /** CHAT 自由对话轮数硬上限（独立于 CLARIFY 的 MAX_ROUNDS；达上限引导转方案或新会话）。 */
    private static final int MAX_CHAT_ROUNDS = 50;

    /** CHAT 自由对话 Prompt 模板（通用 AI 助手角色，纯文本回复，无 JSON 协议）。 */
    private static final String CHAT_PROMPT_TEMPLATE_PATH = "prompts/requirement-chat.md";

    /** 会话标题取首条用户消息的截断长度。 */
    private static final int TITLE_LIMIT = 50;

    /** 会话列表单次返回上限（首期不做分页）。 */
    private static final int LIST_LIMIT = 50;

    private static final String PROMPT_TEMPLATE_PATH = "prompts/requirement-clarify.md";

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final TaskService taskService;
    private final AgentService agentService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final AgentInboxService agentInboxService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ClarifyReplyParser replyParser;
    private final ConfirmCardProtocol confirmCardProtocol;
    private final IntentDetectionService intentDetectionService;
    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;

    /**
     * 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑：显式列为 Spring DI 唯一依据）。
     */
    @Autowired
    public RequirementClarifyServiceImpl(RequirementConversationService conversationService,
                                         RequirementMessageService messageService,
                                         TaskService taskService,
                                         AgentService agentService,
                                         PlannerAgentPicker plannerAgentPicker,
                                         AgentInboxService agentInboxService,
                                         PlatformAgentExecutionService platformAgentExecutionService,
                                         TaskTimelineService taskTimelineService,
                                         ClarifyReplyParser replyParser,
                                         ConfirmCardProtocol confirmCardProtocol,
                                         IntentDetectionService intentDetectionService,
                                         ClarifyWebSearchOrchestrator webSearchOrchestrator) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.taskService = taskService;
        this.agentService = agentService;
        this.plannerAgentPicker = plannerAgentPicker;
        this.agentInboxService = agentInboxService;
        this.platformAgentExecutionService = platformAgentExecutionService;
        this.taskTimelineService = taskTimelineService;
        this.replyParser = replyParser;
        this.confirmCardProtocol = confirmCardProtocol;
        this.intentDetectionService = intentDetectionService;
        this.webSearchOrchestrator = webSearchOrchestrator;
    }

    // ══════════════════════════════════════════════════════════════
    //  会话生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @param plannerAgentId   手动指定的 Planner Agent ID（空=系统自动选择）；
     *                         指定时严格校验可选性，澄清与后续拆解均跟随该 Planner
     * @param webSearchEnabled 会话级联网搜索开关（NULL=默认开启）；
     *                         每轮 LLM 调用前若 true 服务端会预检索行业资料并注入
     *                         {@code {{WEB_SEARCH_CONTEXT}}} 占位符，失败降级跳过
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled) {
        return create(firstMessage, plannerAgentId, webSearchEnabled, null);
    }

    /**
     * 新建会话（双模式入口）。
     *
     * @param initialMode 初始对话模式：'CHAT'=自由对话（缺省）/ 'CLARIFY'=方案澄清快捷直达；
     *                    非法值抛 BizException
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled,
                                            String initialMode) {
        if (firstMessage == null || firstMessage.isBlank()) {
            throw new BizException("首条消息不能为空");
        }
        if (plannerAgentId != null) {
            plannerAgentPicker.validateSelectable(plannerAgentId);
        }
        String mode = normalizeInitialMode(initialMode);
        String trimmed = firstMessage.trim();
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(trimmed.length() <= TITLE_LIMIT
                ? trimmed : trimmed.substring(0, TITLE_LIMIT));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        // NULL 落库为 NULL（兼容老数据默认开启语义由读取侧判定），false/true 严格落库
        conversation.setWebSearchEnabled(webSearchEnabled);
        // 新会话默认 CHAT 自由对话；initialMode=CLARIFY 快捷直达澄清链路
        conversation.setMode(mode);
        conversationService.save(conversation);
        log.info("澄清会话创建: id={}, title={}, plannerAgentId={}, webSearchEnabled={}, mode={}",
                conversation.getId(), conversation.getTitle(), plannerAgentId, webSearchEnabled, mode);
        return doRound(conversation, trimmed);
    }

    /** 兼容重载：旧调用方未传开关时默认开启（NULL 代表默认开启，与老数据语义一致）。 */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId) {
        return create(firstMessage, plannerAgentId, null);
    }

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清（纯文本，无选项快照）。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message) {
        return sendMessage(conversationId, message, null);
    }

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清。
     *
     * @param selections 结构化选项回答快照（可为 null/空=纯文本回答）；
     *                   序列化为 {@code {"selections":[...]}} 存入 user 消息 payload，
     *                   仅作前端回显快照，LLM 上下文仍用 content 可读文本
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message,
                                                 List<ClarifySelection> selections) {
        if (message == null || message.isBlank()) {
            throw new BizException("消息不能为空");
        }
        RequirementConversation conversation = requireActive(conversationId);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        // 轮数上限按模式分派：CHAT 用独立上限（意图词「整理成方案」永远放行，保证转方案出口）；
        // 待确认状态的确认词（或再次意图词）同样放行；确认卡点「确认」（selections 快照）也放行——
        // 确认消息会转入 CLARIFY，不算 CHAT 轮；CLARIFY（含 NULL 老数据）沿用既有 20 轮上限
        boolean intent = intentDetectionService.isIntentToClarify(message);
        boolean confirm = isPendingClarifyConfirm(conversation)
                && (intentDetectionService.isConfirmPhrase(message) || intent || confirmCardProtocol.isAcceptSelected(selections));
        if (isChatMode(conversation) && !intent && !confirm) {
            if (rounds >= MAX_CHAT_ROUNDS) {
                throw new BizException("自由对话轮数已达上限 " + MAX_CHAT_ROUNDS
                        + "，可输入「整理成方案」转为方案模式，或新建会话");
            }
        } else if (isClarifyMode(conversation) && rounds >= MAX_ROUNDS) {
            throw new BizException("澄清轮数已达上限 " + MAX_ROUNDS
                    + "，请放弃本会话并在任务管理中手动创建任务");
        }
        return doRound(conversation, message.trim(), replyParser.buildSelectionPayload(selections));
    }

    /**
     * 重试上一轮 LLM：仅当最后一条消息是 user（即上轮 LLM 失败、助手回复缺失）时可用；
     * 不新增 user 消息、不加轮数（失败那轮已计入 round_count）。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail retryRound(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        List<RequirementMessage> messages = messageService.listByConversation(conversationId);
        if (messages.isEmpty()
                || !ROLE_USER.equals(messages.get(messages.size() - 1).getRole())) {
            throw new BizException("最后一条消息已有助手回复，无需重试: conversationId=" + conversationId);
        }
        log.info("澄清会话重试一轮 LLM: conversationId={}", conversationId);
        // 重试场景不复用预检索资料：失败那轮通常与外部 API 副作用相关，重跑前重新准备
        return runLlmRound(conversation, null);
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    @Override
    public List<PlannerAgentPicker.PlannerOption> listPlannerOptions() {
        return plannerAgentPicker.listOptions();
    }

    /**
     * 终稿确认：创建 Task（PENDING）→ best-effort 通知 PLANNER →
     * 会话回填 task_id、状态 FINALIZED → timeline 记录。
     *
     * @return 创建的任务
     */
    @Override
    public Task finalize(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        if (conversation.getFinalTitle() == null || conversation.getFinalTitle().isBlank()) {
            throw new BizException("会话尚无终稿，请先对话至 LLM 产出终稿: conversationId=" + conversationId);
        }
        conversation.setStatus(STATUS_FINALIZED);
        return buildTaskFromDraft(conversation, "task_created_from_clarify");
    }

    /**
     * 重新生成任务：会话已 FINALIZED 且原任务已被删除时，复用会话终稿重建 PENDING Task，
     * 回填新的 task_id（会话保持 FINALIZED）。前端随后自动调 plan 拆解并打开草案审阅。
     *
     * <p>不放开 ACTIVE 校验，也不重跑 LLM：仅在“终稿仍在、任务已被清理”的悬挂场景下重建，
     * 避免误覆盖仍存活的任务。</p>
     *
     * @return 重新创建的任务
     */
    @Override
    public Task regenerate(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        if (!STATUS_FINALIZED.equals(conversation.getStatus())) {
            throw new BizException("仅已建任务（FINALIZED）的会话可重新生成: conversationId=" + conversationId
                    + ", status=" + conversation.getStatus());
        }
        if (conversation.getFinalTitle() == null || conversation.getFinalTitle().isBlank()) {
            throw new BizException("会话缺少终稿，无法重新生成: conversationId=" + conversationId);
        }
        Long oldTaskId = conversation.getTaskId();
        if (oldTaskId != null && taskService.getById(oldTaskId) != null) {
            throw new BizException("原任务仍然存在（taskId=" + oldTaskId
                    + "），请先在任务管理中删除后再重新生成");
        }
        return buildTaskFromDraft(conversation, "task_regenerated_from_clarify");
    }

    /**
     * 复用会话终稿创建 PENDING Task、best-effort 通知全部 PLANNER、回填 task_id 并写 timeline。
     * 会话状态沿用调用方已设置的值（finalize 置 FINALIZED，regenerate 保持 FINALIZED）。
     */
    private Task buildTaskFromDraft(RequirementConversation conversation, String timelineEvent) {
        Long conversationId = conversation.getId();
        Task task = new Task();
        task.setTitle(conversation.getFinalTitle());
        task.setDescription(conversation.getFinalDescription());
        task.setStatus(TaskStatus.PENDING);
        taskService.save(task);
        log.info("澄清终稿建任务: conversationId={}, taskId={}, title={}, event={}",
                conversationId, task.getId(), task.getTitle(), timelineEvent);

        // 照 TaskController.create 的通知段：best-effort 通知全部 PLANNER，失败不阻断
        try {
            List<Agent> planners = agentService.listByRole(AgentRole.PLANNER);
            String eventId = "task.create." + task.getId() + "." + System.currentTimeMillis();
            for (Agent planner : planners) {
                agentInboxService.send(planner.getId(), eventId, "task.created",
                        "新任务需要规划: " + task.getTitle(),
                        task.getDescription() != null ? task.getDescription() : "请查看详情",
                        "task", task.getId(), "HIGH");
            }
            log.info("已通知 {} 个 PLANNER Agent", planners.size());
        } catch (Exception e) {
            log.warn("澄清建任务后通知 PLANNER 失败: taskId={}", task.getId(), e);
        }

        conversation.setTaskId(task.getId());
        conversationService.updateById(conversation);

        taskTimelineService.recordEvent(task.getId(), null, timelineEvent,
                AgentRole.PLANNER, null, Map.of("conversationId", conversationId));
        return task;
    }

    /** 放弃会话：ACTIVE → ABANDONED。 */
    @Override
    public void abandon(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        conversation.setStatus(STATUS_ABANDONED);
        conversationService.updateById(conversation);
        log.info("澄清会话已放弃: conversationId={}", conversationId);
    }

    /**
     * 切换到方案澄清模式（斜杠命令路径）：先落库附加文本（用户消息，进 LLM 上下文），
     * 再切 CLARIFY 并跑一轮澄清（首轮强制 structured → 推荐卡片必出）。
     * 附加文本不走意图词/确认词判定、不设 payload。
     *
     * @param extraMessage 斜杠命令后的附加文本；空/空白则不加消息（与既有 switchToClarify 等价）
     */
    @Override
    public ClarifyConversationDetail switchToClarify(Long conversationId, String extraMessage) {
        if (extraMessage != null && !extraMessage.isBlank()) {
            messageService.addMessage(conversationId, ROLE_USER, extraMessage.trim(), null);
            log.info("澄清会话斜杠命令附加文本落库: conversationId={}", conversationId);
        }
        return switchToClarify(conversationId);
    }

    /**
     * 切换到方案澄清模式：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail switchToClarify(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        String from = conversation.getMode();
        conversation.setMode(MODE_CLARIFY);
        // 手动切换时一并清除意图词待确认标记，避免残留状态影响后续轮次
        conversation.setPendingClarifyConfirm(false);
        conversationService.updateById(conversation);
        log.info("澄清会话切换模式: conversationId={}, from={}, to={}",
                conversationId, from, MODE_CLARIFY);
        // 切换轮不做联网搜索（后续再评估）；澄清模板基于全量历史直接产草案/追问
        return runLlmRound(conversation, null);
    }

    /**
     * 切回自由对话模式：仅置位，不调用 LLM；
     * 历史消息全部保留，后续切回 CLARIFY 时作为全量澄清上下文。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail switchToChat(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        String from = conversation.getMode();
        conversation.setMode(MODE_CHAT);
        // 切回 CHAT 时防御性清除意图词待确认标记（该状态仅 CHAT 模式语义存在）
        conversation.setPendingClarifyConfirm(false);
        conversationService.updateById(conversation);
        log.info("澄清会话切换模式: conversationId={}, from={}, to={}",
                conversationId, from, MODE_CHAT);
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    /** 会话列表（按创建时间倒序，LIMIT 50，首期不分页）。 */
    @Override
    public List<RequirementConversation> listConversations() {
        return conversationService.lambdaQuery()
                .orderByDesc(RequirementConversation::getCreateTime)
                .last("LIMIT " + LIST_LIMIT)
                .list();
    }

    /** 会话详情：会话 + 消息按 seq 升序。 */
    @Override
    public ClarifyConversationDetail detail(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        ClarifyConversationDetail view = new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
        // 供前端判断 FINALIZED 会话的原任务是否已被删除（悬挂软引用），从而决定能否重新生成
        view.setTaskExists(conversation.getTaskId() != null
                && taskService.getById(conversation.getTaskId()) != null);
        return view;
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /**
     * 一轮澄清：存 user 消息（含可选的选择快照 payload）、round_count+1 →
     * LLM 一轮（选人/渲染/解析/落库）。
     */
    private ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage) {
        return doRound(conversation, userMessage, null);
    }

    private ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage,
                                              String userPayload) {
        Long conversationId = conversation.getId();
        // 意图词二次确认状态机（仅 CHAT 模式）：
        //   意图词命中且无待确认 → 置位 + 回复固定确认询问（不调 LLM、不加轮数）
        //   待确认 + 确认词/再次意图词 → 切 CLARIFY 并清标记，该条消息即澄清首轮
        //   待确认 + 其他消息 → 清标记继续自由对话（用户放弃转方案）
        if (isChatMode(conversation)) {
            boolean intent = intentDetectionService.isIntentToClarify(userMessage);
            boolean pendingConfirm = isPendingClarifyConfirm(conversation);
            if (intent && !pendingConfirm) {
                conversation.setPendingClarifyConfirm(true);
                conversationService.updateById(conversation);
                messageService.addMessage(conversationId, ROLE_USER, userMessage, userPayload);
                // 确认询问改为结构化选项卡（前端渲染为确认/取消弹窗），
                // 可读正文仍落 CONFIRM_ASK_MESSAGE 保证 transcript 上下文不变
                messageService.addMessage(conversationId, ROLE_ASSISTANT, CONFIRM_ASK_MESSAGE,
                        confirmCardProtocol.buildAskPayload());
                log.info("澄清会话意图词命中，等待用户确认转方案: conversationId={}", conversationId);
                return new ClarifyConversationDetail(conversation,
                        messageService.listByConversation(conversationId));
            }
            // 确认判定三通道：手打确认词 / 再次意图词 / 确认卡点「确认」（selections 快照）；
            // 卡片提交文本形如「问题：确认」不命中 CONFIRM_PHRASE_PATTERN 开头锚定，须走快照判定
            if (pendingConfirm) {
                String cardValue = confirmCardProtocol.acceptValueOf(userPayload);
                boolean confirmed = intentDetectionService.isConfirmPhrase(userMessage) || intent
                        || confirmCardProtocol.isAcceptValue(cardValue);
                if (confirmed) {
                    conversation.setMode(MODE_CLARIFY);
                    conversation.setPendingClarifyConfirm(false);
                    conversationService.updateById(conversation);
                    log.info("澄清会话用户确认转方案: conversationId={}, from={}, to={}",
                            conversationId, MODE_CHAT, MODE_CLARIFY);
                } else {
                    conversation.setPendingClarifyConfirm(false);
                    conversationService.updateById(conversation);
                    log.info("澄清会话确认被取消，继续自由对话: conversationId={}", conversationId);
                }
            }
        }
        messageService.addMessage(conversationId, ROLE_USER, userMessage, userPayload);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);

        // 联网搜索（引入）： CHAT/CLARIFY 任意模式每轮且开关开启
        // （NULL/true 视为开启）都检索；成本由各自轮数上限封顶（CHAT 50 / CLARIFY 20），
        // 每轮折叠查验条可见搜索词；确认词/确认卡提交文本等无检索语义的消息回退历史主题消息作查询词
        WebSearchOutcome webSearchOutcome = null;
        if (isWebSearchEnabled(conversation)) {
            webSearchOutcome = webSearchOrchestrator.doWebSearch(resolveSearchSource(conversationId, userMessage, userPayload));
        }
        return runLlmRound(conversation, webSearchOutcome);
    }

    /**
     * 解析联网搜索查询词来源（修复）：当前轮消息无检索语义时（确认词 / 确认卡提交文本 /
     * 纯意图话术），倒序回退最近一条有实际内容的 user 消息（通常是触发意图前的讨论主题）；
     * 全部无意义时返回空白串，doWebSearch 视为未发起搜索（不落查验条）。
     */
    private String resolveSearchSource(Long conversationId, String userMessage, String userPayload) {
        if (!intentDetectionService.lacksSearchSemantics(userMessage, userPayload)) {
            return userMessage;
        }
        List<RequirementMessage> msgs = messageService.listByConversation(conversationId);
        if (msgs != null) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                RequirementMessage m = msgs.get(i);
                String c = m.getContent();
                if (!ROLE_USER.equals(m.getRole()) || c == null || c.isBlank()) {
                    continue;
                }
                if (Objects.equals(c, userMessage) || intentDetectionService.lacksSearchSemantics(c, null)) {
                    continue;
                }
                log.info("澄清联网搜索查询词回退历史主题消息: conversationId={}, fallbackLen={}",
                        conversationId, c.length());
                return c;
            }
        }
        return "";
    }

    /** NULL/true 视为开启；只有严格的 false 走关闭语义。 */
    private boolean isWebSearchEnabled(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        return v == null || v;
    }

    /** 意图词二次确认标记：仅显式 true 视为待确认（老数据 NULL/0 均为无待确认）。 */
    private boolean isPendingClarifyConfirm(RequirementConversation conversation) {
        return Boolean.TRUE.equals(conversation.getPendingClarifyConfirm());
    }

    /** 是否方案澄清模式：NULL 老数据按 CLARIFY 兼容。 */
    private boolean isClarifyMode(RequirementConversation conversation) {
        return conversation.getMode() == null || MODE_CLARIFY.equals(conversation.getMode());
    }

    /** 是否自由对话模式：仅显式 CHAT（NULL 老数据不算，按 CLARIFY）。 */
    private boolean isChatMode(RequirementConversation conversation) {
        return MODE_CHAT.equals(conversation.getMode());
    }

    /**
     * 初始模式归一化：缺省/显式 CHAT → CHAT（新会话默认自由对话）；
     * 显式 CLARIFY → 快捷直达方案澄清；非法值拒绝。
     */
    private String normalizeInitialMode(String initialMode) {
        if (initialMode == null || initialMode.isBlank() || MODE_CHAT.equals(initialMode)) {
            return MODE_CHAT;
        }
        if (MODE_CLARIFY.equals(initialMode)) {
            return MODE_CLARIFY;
        }
        throw new BizException("非法的初始对话模式: " + initialMode + "（仅支持 CHAT/CLARIFY）");
    }

    /**
     * LLM 一轮（不落 user 消息）：选 Planner → 全量历史渲染模板 → LLM →
     * 解析 question/final 分支落库；doRound 与 retryRound 共用。
     *
     * @param webSearchOutcome 本轮联网搜索归一化记录（未搜索/重试/切换轮传 null）；
     *                         已在 doRound 里根据开关限定过；注入 Prompt 与落 payload 两用
     */
    private ClarifyConversationDetail runLlmRound(RequirementConversation conversation,
                                                  WebSearchOutcome webSearchOutcome) {
        Long conversationId = conversation.getId();
        // 双模式分派：CLARIFY（含 NULL 老数据）走澄清模板 + JSON 协议解析；
        // CHAT 走通用助手模板，纯文本直接落库
        boolean clarifyMode = isClarifyMode(conversation);

        String webSearchContext = webSearchOutcome != null ? webSearchOutcome.toContextText() : "";
        Agent planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = renderPrompt(conversationId, webSearchContext,
                clarifyMode ? PROMPT_TEMPLATE_PATH : CHAT_PROMPT_TEMPLATE_PATH);
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId,
                        "scene", clarifyMode ? "requirement_clarify" : "requirement_chat"))
                .requiredCapabilities(Map.of())
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
        if (!result.isSuccess()) {
            throw new BizException("需求澄清 LLM 调用失败: " + result.getErrorMessage());
        }

        // CHAT 模式（容错双模）：优先尝试宽松解析 structured 追问（LLM 需要用户回答
        // 关键决策问题时输出选项卡片）；解析失败/非追问一律纯文本直落（payload NULL），零行为破坏
        if (!clarifyMode) {
            String output = result.getOutput();
            if (output == null || output.isBlank()) {
                throw new BizException("自由对话 LLM 返回内容为空");
            }
            ClarifyReply chatReply = replyParser.tryParseChatStructured(output);
            if (chatReply != null) {
                // CHAT 轮结构化追问卡同样携带本轮联网搜索查验信息
                messageService.addMessage(conversationId, ROLE_ASSISTANT,
                        replyParser.composeAssistantContent(chatReply),
                        replyParser.buildQuestionPayload(chatReply, webSearchOutcome));
                log.info("自由对话结构化追问落库: conversationId={}", conversationId);
            } else {
                // CHAT 轮联网搜索后纯文本回复同样携带 webSearch 查验键（与终稿轮同形态）
                if (webSearchOutcome != null) {
                    messageService.addMessage(conversationId, ROLE_ASSISTANT, output.trim(),
                            replyParser.buildWebSearchOnlyPayload(webSearchOutcome));
                } else {
                    messageService.addMessage(conversationId, ROLE_ASSISTANT, output.trim(), null);
                }
                log.info("自由对话回复落库: conversationId={}", conversationId);
            }
            return new ClarifyConversationDetail(conversation,
                    messageService.listByConversation(conversationId));
        }

        ClarifyReply reply = replyParser.parseReply(result.getOutput());
        if ("final".equals(reply.getType())) {
            String note = reply.getMessage() != null && !reply.getMessage().isBlank()
                    ? reply.getMessage() : "已生成终稿";
            // 终稿轮同样落联网搜索查验信息（未发生搜索时保持原 3 参形态）
            if (webSearchOutcome != null) {
                messageService.addMessage(conversationId, ROLE_ASSISTANT, note,
                        replyParser.buildWebSearchOnlyPayload(webSearchOutcome));
            } else {
                messageService.addMessage(conversationId, ROLE_ASSISTANT, note);
            }
            conversation.setFinalTitle(reply.getTitle());
            conversation.setFinalDescription(reply.getDescription());
            conversationService.updateById(conversation);
            log.info("澄清会话产出终稿: conversationId={}, finalTitle={}",
                    conversationId, reply.getTitle());
        } else {
            messageService.addMessage(conversationId, ROLE_ASSISTANT, replyParser.composeAssistantContent(reply),
                    replyParser.buildQuestionPayload(reply, webSearchOutcome));
        }
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    /** 校验会话存在且处于 ACTIVE。 */
    private RequirementConversation requireActive(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        if (!STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new BizException("会话已结束（" + conversation.getStatus()
                    + "），请新建会话: conversationId=" + conversationId);
        }
        return conversation;
    }

    /**
     * 加载 classpath 模板并替换两个占位符：
     * <ul>
     *   <li>{@code {{CONVERSATION_HISTORY}}} — transcript 全量历史；</li>
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
        StringBuilder transcript = new StringBuilder();
        for (RequirementMessage msg : messageService.listByConversation(conversationId)) {
            transcript.append(ROLE_USER.equals(msg.getRole()) ? "用户：" : "助手：")
                    .append(msg.getContent()).append('\n');
        }
        String contextSection = (webSearchContext == null || webSearchContext.isBlank())
                ? "（无可用联网资料）" : webSearchContext;
        return template
                .replace("{{CONVERSATION_HISTORY}}", transcript.toString().trim())
                .replace("{{WEB_SEARCH_CONTEXT}}", contextSection);
    }

}

