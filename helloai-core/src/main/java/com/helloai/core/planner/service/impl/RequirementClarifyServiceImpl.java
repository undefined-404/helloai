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
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 对话式需求澄清编排服务实现（需求模糊 → 多轮追问 → 终稿 → 创建任务）。
 *
 * <p>复用 {@link com.helloai.core.planner.service.PlannerAnalysisService} 的五段式范式：
 * 选 Agent → classpath 模板渲染 → executeSync → strip fence 容错解析 → BizException。
 * 类不加事务：LLM 调用耗时较长，不能占用数据库事务；单条消息落库走 ServiceImpl 自带事务。</p>
 *
 * <p>LLM/解析失败时：user 消息保留（round_count 已加），抛 BizException，
 * 前端弹错后可重发一条消息重试；会话本身无状态回退需求（始终 ACTIVE）。</p>
 *
 * <p><b>§7.8 类规模拆分评审结论（2026-08-23）</b>：本类为澄清编排汇聚点，超 500 行 /
 * 8 依赖红线，按 §7.8 选项二书面声明不继续拆分：</p>
 * <ul>
 *     <li>已剥离：PlannerAgentPicker（选 Agent）、ClarifyReplyParser（回复解析）、
 *         ConfirmCardProtocol（确认卡协议）、IntentDetectionService（意图识别）、
 *         ClarifyWebSearchOrchestrator（联网检索）；</li>
 *     <li>剩余职责：会话生命周期（create / sendMessage / retryRound / finalize / regenerate /
 *         abandon / switchToClarify / switchToChat）+ 轮次编排（doRound / runLlmRound）+
 *         终稿转任务（buildTaskFromDraft）；</li>
 *     <li>不拆理由：轮次编排与消息持久化 / 终稿转任务共享会话状态与轮数上限决策，拆分将导致
 *         会话状态在类间传递；已外置部分均为可独立测试的无状态组件，剩余编排逻辑为
 *         单会话状态机的自然整体。</li>
 * </ul>
 */
@Slf4j
@Service
public class RequirementClarifyServiceImpl implements RequirementClarifyService {

    /** 用户消息轮数硬上限（Prompt 侧引导尽早出终稿，服务端只做兜底）。 */
    private static final int MAX_ROUNDS = 20;

    /** CHAT 自由对话轮数硬上限（独立于 CLARIFY 的 MAX_ROUNDS；达上限引导转方案或新会话）。 */
    private static final int MAX_CHAT_ROUNDS = 50;

    /** 确认卡提交轮次搜索词回退时的短句阈值（<N 字视为无检索语义的意图句，仅影响搜索词质量）。 */
    private static final int SHORT_INTENT_LEN = 8;

    /** CHAT 自由对话 Prompt 模板（通用 AI 助手角色，纯文本回复，无 JSON 协议）。 */
    private static final String CHAT_PROMPT_TEMPLATE_PATH = "prompts/requirement-chat.md";

    /** CHAT 轮前置联合决策 Prompt 模板（意图路由 + 联网搜索决策，低预算调用）。 */
    private static final String CHAT_DECISION_TEMPLATE_PATH = "prompts/requirement-decision.md";

    /** 联合决策 AgentTask 的 scene 标识（测试 stub 与日志可观测区分决策轮/主回复轮）。 */
    private static final String DECISION_SCENE = "requirement_chat_decision";

    /** 联合决策历史裁剪条数（决策只看近期上下文，控制 token 预算）。 */
    private static final int DECISION_HISTORY_LIMIT = 6;

    /** 计划类斜杠命令前缀（与前端 PLANNER_COMMAND_RE 对齐：前缀后只接受空白或结束）。 */
    private static final List<String> PLANNER_COMMAND_PREFIXES = List.of("/planner", "/plan", "/task");

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
    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;
    private final ChatRoundDecisionParser decisionParser;

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
                                         ClarifyWebSearchOrchestrator webSearchOrchestrator,
                                         ChatRoundDecisionParser decisionParser) {
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
        this.webSearchOrchestrator = webSearchOrchestrator;
        this.decisionParser = decisionParser;
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
        String trimmed = firstMessage.trim();
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(trimmed.length() <= TITLE_LIMIT
                ? trimmed : trimmed.substring(0, TITLE_LIMIT));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        // NULL 落库为 NULL（兼容老数据默认开启语义由读取侧判定），false/true 严格落库
        conversation.setWebSearchEnabled(webSearchEnabled);
        // 新会话始终 CHAT 自由对话（auto 意图路由 + /planner 命令触发转方案）
        conversation.setMode(MODE_CHAT);
        conversationService.save(conversation);
        log.info("澄清会话创建: id={}, title={}, plannerAgentId={}, webSearchEnabled={}, mode={}",
                conversation.getId(), conversation.getTitle(), plannerAgentId, webSearchEnabled, MODE_CHAT);
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
        String trimmed = message.trim();
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        // 确认卡待确认状态处理（LLM auto 意图路由触发）：
        //   确认卡点「确认」（selections 快照）→ 切 CLARIFY 并清标记
        //   其他消息 → 清标记继续自由对话
        boolean pendingConfirm = isChatMode(conversation)
                && Boolean.TRUE.equals(conversation.getPendingClarifyConfirm());
        boolean confirmAccepted = pendingConfirm && confirmCardProtocol.isAcceptSelected(selections);
        // 逃生通道（不被轮数上限挡住）：计划类斜杠命令（/planner|/plan|/task）、确认卡点「确认」
        // —— 上限提示语本身引导用户输 /planner 转方案，命令/确认必须放行
        boolean plannerCommand = isChatMode(conversation) && isPlannerCommand(trimmed);
        if (!confirmAccepted && !plannerCommand) {
            // 轮数上限按模式分派：CHAT 用独立上限；CLARIFY（含 NULL 老数据）沿用既有 20 轮上限
            if (isChatMode(conversation) && rounds >= MAX_CHAT_ROUNDS) {
                throw new BizException("自由对话轮数已达上限 " + MAX_CHAT_ROUNDS
                        + "，可输入 /planner 转为方案模式，或新建会话");
            } else if (isClarifyMode(conversation) && rounds >= MAX_ROUNDS) {
                throw new BizException("澄清轮数已达上限 " + MAX_ROUNDS
                        + "，请放弃本会话并在任务管理中手动创建任务");
            }
        }
        if (pendingConfirm) {
            if (confirmAccepted) {
                conversation.setMode(MODE_CLARIFY);
                conversation.setPendingClarifyConfirm(false);
                conversationService.updateById(conversation);
                log.info("用户确认转方案（确认卡）: conversationId={}", conversationId);
            } else {
                conversation.setPendingClarifyConfirm(false);
                conversationService.updateById(conversation);
                log.info("用户取消转方案，继续自由对话: conversationId={}", conversationId);
            }
        }
        return doRound(conversation, trimmed, replyParser.buildSelectionPayload(selections));
    }

    /**
     * 重试上一轮 LLM：仅当最后一条消息是 user（即上轮 LLM 失败、助手回复缺失）时可用；
     * 不新增 user 消息、不加轮数（失败那轮已计入 round_count）。
     *
     * <p>与 sendMessage 完全同语义（§6.166 起）：经 {@link #runRoundCore} 重跑
     * 决策 → 分发（CLARIFY 模式规则搜索 / CHAT 联合决策 + 三态搜索），失败那轮
     * 通常与外部 API 副作用相关，重跑时基于最后一条 user 消息重新决策与检索。</p>
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
        RequirementMessage lastUser = messages.get(messages.size() - 1);
        return runRoundCore(conversation,
                lastUser.getContent() == null ? "" : lastUser.getContent(),
                lastUser.getPayload());
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
     * 删除已放弃会话：校验 ABANDONED → 消息逻辑删 → 会话逻辑删，两表同事务。
     * <p>仅 ABANDONED 放行：ABANDONED 会话按 abandon 前置校验必然无 task_id（终稿未确认），
     * 删除不触碰任务侧；FINALIZED 会话承载任务追溯，禁止删除。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        if (!STATUS_ABANDONED.equals(conversation.getStatus())) {
            throw new BizException("仅已放弃的会话可删除，当前状态: " + conversation.getStatus()
                    + ", conversationId=" + conversationId);
        }
        messageService.removeByConversation(conversationId);
        conversationService.removeById(conversationId);
        log.info("澄清会话已删除（软删）: conversationId={}", conversationId);
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
        // 计划类斜杠命令检测（/planner|/plan|/task，跳过决策）：直接 switchToClarify
        if (isChatMode(conversation) && isPlannerCommand(userMessage)) {
            String extraText = plannerCommandExtra(userMessage);
            log.info("CHAT 会话斜杠命令触发转方案: conversationId={}, extraLen={}",
                    conversationId, extraText.length());
            return switchToClarify(conversationId, extraText.isEmpty() ? null : extraText);
        }
        messageService.addMessage(conversationId, ROLE_USER, userMessage, userPayload);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);
        return runRoundCore(conversation, userMessage, userPayload);
    }

    /**
     * 轮次核心：前置决策 → 分发（CHAT 联合决策 / CLARIFY 规则搜索）。
     * doRound（新用户消息，已落库加轮数）与 retryRound（重试，不落库不加轮数）共用；
     * retry 继续跑决策与搜索，与 sendMessage 完全同语义。
     *
     * <p>CLARIFY 模式轮次绕过决策（保持 V45 语义：开关开启则每轮规则搜索）；
     * CHAT 轮先做一次低预算联合决策调用（intent + 澄清问题 + 搜索决策），
     * intent=clarify → 落库单条确认卡即返回（不生成主回复，对齐 ZLAgent「响应即问题」）；
     * intent=chat → 按三态搜索策略（AUTO/ALWAYS_ON/OFF）检索后走主回复。</p>
     */
    private ClarifyConversationDetail runRoundCore(RequirementConversation conversation,
                                                   String userMessage, String userPayload) {
        if (isClarifyMode(conversation)) {
            return runClarifySearchRound(conversation, userMessage, userPayload);
        }
        RoundDecision roundDecision = makeRoundDecision(conversation, userMessage);
        if (roundDecision.decision().isClarify()) {
            return applyClarifyDecision(conversation, roundDecision.decision());
        }
        WebSearchOutcome webSearchOutcome = resolveChatSearchOutcome(conversation, userMessage, userPayload,
                roundDecision.decision(), roundDecision.degraded());
        return runLlmRound(conversation, webSearchOutcome);
    }

    /** CLARIFY 模式轮次（V45 语义）：绕过决策，开关开启则按规则搜索后主回复。 */
    private ClarifyConversationDetail runClarifySearchRound(RequirementConversation conversation,
                                                            String userMessage, String userPayload) {
        WebSearchOutcome webSearchOutcome = null;
        if (isWebSearchEnabled(conversation)) {
            webSearchOutcome = webSearchOrchestrator.doWebSearch(
                    resolveSearchSource(conversation.getId(), userMessage, userPayload));
        }
        return runLlmRound(conversation, webSearchOutcome);
    }

    /**
     * CHAT 轮联合决策调用（对齐 ZLAgent JsonIntentRouter 前置路由）：低预算 executeSync
     * 输出 intent + 澄清问题 + 搜索决策；执行异常或 JSON 校验失败一律降级
     * {@link ChatRoundDecisionParser#defaults()}（intent=chat、不搜索），绝不阻塞主流程。
     *
     * @return 决策 + 是否降级标志（降级时搜索策略按模式兜底：ALWAYS_ON/AUTO 规则搜索，不丢搜索机会）
     */
    private RoundDecision makeRoundDecision(RequirementConversation conversation, String userMessage) {
        Long conversationId = conversation.getId();
        Agent planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(renderDecisionPrompt(conversation, userMessage))
                .context(Map.of("conversationId", conversationId, "scene", DECISION_SCENE))
                .requiredCapabilities(Map.of())
                .build();
        try {
            AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
            if (!result.isSuccess()) {
                log.warn("联合决策 LLM 调用失败，降级 chat 决策: conversationId={}, err={}",
                        conversationId, result.getErrorMessage());
                return RoundDecision.degradedDefaults();
            }
            return new RoundDecision(decisionParser.parse(result.getOutput()), false);
        } catch (Exception e) {
            log.warn("联合决策解析失败，降级 chat 决策: conversationId={}, err={}",
                    conversationId, e.getMessage());
            return RoundDecision.degradedDefaults();
        }
    }

    /** 本轮联合决策结果：决策本体 + 降级标志（降级=非 LLM 决策，AI 仍可观测）。 */
    private record RoundDecision(ChatRoundDecisionParser.ChatRoundDecision decision, boolean degraded) {

        static RoundDecision degradedDefaults() {
            return new RoundDecision(ChatRoundDecisionParser.defaults(), true);
        }
    }

    /**
     * 应用 clarify 决策：落库单条确认卡（内容与卡片题面均承载澄清问题），
     * 置待确认标记后返回——不生成主回复，对齐 ZLAgent「响应即问题」。
     */
    private ClarifyConversationDetail applyClarifyDecision(RequirementConversation conversation,
                                                           ChatRoundDecisionParser.ChatRoundDecision decision) {
        Long conversationId = conversation.getId();
        String question = decision.clarificationQuestion();
        messageService.addMessage(conversationId, ROLE_ASSISTANT,
                confirmCardProtocol.buildAskText(question),
                confirmCardProtocol.buildAskPayload(question));
        conversation.setPendingClarifyConfirm(true);
        conversationService.updateById(conversation);
        log.info("联合决策 intent=clarify，落库单条确认卡: conversationId={}, question={}",
                conversationId, question);
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    /**
     * CHAT 轮 chat 意图的搜索策略分发：AUTO 按 LLM 决策（降级时规则搜索兜底，不丢搜索机会）、
     * ALWAYS_ON 每轮搜、OFF 不搜。
     */
    private WebSearchOutcome resolveChatSearchOutcome(RequirementConversation conversation,
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

    /** 渲染联合决策 Prompt：本轮消息 + 最近 ≤6 条历史（剔除本轮消息，已单独给出）+ 搜索模式提示。 */
    private String renderDecisionPrompt(RequirementConversation conversation, String userMessage) {
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
            transcript.append(ROLE_USER.equals(msg.getRole()) ? "用户：" : "助手：")
                    .append(msg.getContent()).append('\n');
        }
        String historyText = transcript.toString().trim();
        String policyHint = switch (resolveSearchPolicy(conversation)) {
            case ALWAYS_ON -> "本轮必须发起联网搜索（ALWAYS_ON），请给出优化搜索词";
            case OFF -> "本轮不发起联网搜索（OFF），need_search 必须为 false";
            default -> "由你自主决策是否搜索（AUTO）";
        };
        return template
                .replace("{{USER_MESSAGE}}", userMessage == null ? "" : userMessage)
                .replace("{{CONVERSATION_HISTORY}}",
                        historyText.isEmpty() ? "（无历史）" : historyText)
                .replace("{{SEARCH_POLICY}}", policyHint);
    }

    /**
     * 解析联网搜索查询词来源：普通轮次直接用当前用户消息；
     * 确认卡提交轮次（selections 快照通道）userMessage 是卡片题面+选项文本（无检索语义），
     * 回退最近一条有检索语义的 user 消息作搜索词，无可回退返回空串（doWebSearch 视为未发起）。
     * 短句阈值仅作用于搜索词质量，不参与任何状态判定（LLM auto 意图路由已取代意图词正则）。
     */
    private String resolveSearchSource(Long conversationId, String userMessage, String userPayload) {
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

    /** NULL/true 视为开启；只有严格的 false 走关闭语义。 */
    private boolean isWebSearchEnabled(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        return v == null || v;
    }

    /** 搜索三态策略（不落库，由 web_search_enabled 持久值派生）：NULL=AUTO / true=ALWAYS_ON / false=OFF。 */
    private enum SearchPolicy {
        /** LLM 决策 need_search + 优化词。 */
        AUTO,
        /** 每轮搜索，LLM 只生成优化词（不决策是否需要）。 */
        ALWAYS_ON,
        /** 不搜索。 */
        OFF
    }

    /** 搜索三态语义映射：NULL 新语义 AUTO（CHAT 轮由 LLM 决策；CLARIFY 轮由 isWebSearchEnabled 规则搜索）。 */
    private SearchPolicy resolveSearchPolicy(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        if (v == null) {
            return SearchPolicy.AUTO;
        }
        return v ? SearchPolicy.ALWAYS_ON : SearchPolicy.OFF;
    }

    /** 是否计划类斜杠命令（/planner|/plan|/task，忽略大小写；前缀后只接受空白或结束，防误伤普通文本）。 */
    private boolean isPlannerCommand(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        String lower = trimmed.toLowerCase();
        for (String prefix : PLANNER_COMMAND_PREFIXES) {
            if (lower.startsWith(prefix)
                    && (trimmed.length() == prefix.length()
                    || Character.isWhitespace(trimmed.charAt(prefix.length())))) {
                return true;
            }
        }
        return false;
    }

    /** 斜杠命令后的附加文本（如「/planner 帮我建电商系统」→「帮我建电商系统」）；非命令返回空串。 */
    private String plannerCommandExtra(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        String lower = trimmed.toLowerCase();
        for (String prefix : PLANNER_COMMAND_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
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

        // CHAT 模式（纯回复落库，意图/搜索决策已前置到 runRoundCore）：
        //   决策=clarify 的轮次在 runRoundCore 已落单条确认卡返回，不会走到这里；
        //   主回复 LLM 不再输出/解析 __intent__ 标记，只承担回答正文（结构化追问 / 纯文本）
        if (!clarifyMode) {
            String output = result.getOutput();
            if (output == null || output.isBlank()) {
                throw new BizException("自由对话 LLM 返回内容为空");
            }
            String visible = output.trim();
            // 正常 CHAT 回复（结构化追问 / 纯文本）
            ClarifyReply chatReply = replyParser.tryParseChatStructured(visible);
            if (chatReply != null) {
                messageService.addMessage(conversationId, ROLE_ASSISTANT,
                        replyParser.composeAssistantContent(chatReply),
                        replyParser.buildQuestionPayload(chatReply, webSearchOutcome));
                log.info("自由对话结构化追问落库: conversationId={}", conversationId);
            } else {
                if (webSearchOutcome != null) {
                    messageService.addMessage(conversationId, ROLE_ASSISTANT, visible,
                            replyParser.buildWebSearchOnlyPayload(webSearchOutcome));
                } else {
                    messageService.addMessage(conversationId, ROLE_ASSISTANT, visible, null);
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

