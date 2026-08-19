package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.service.WebPageFetchService;
import com.helloai.core.planner.service.WebSearchService;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * CHAT → CLARIFY 意图词：用户表达"把讨论整理成可落地方案"的常见说法，正则命中即进入二次确认。
     * V40.1 追加口语化话术（整理方案/出个方案/写方案/做个方案等），覆盖"帮我整理方案吧"这类表达；
     * V41 追加"动作词 + 可选量词 + 计划/任务/方案"组合模式（新建个计划/给一个方案/帮我总结等），
     * 组合匹配避免"任务""计划"裸词子串误触；误触有二次确认弹窗把关（点取消即继续自由对话），
     * 故放宽匹配不设额外代价。
     */
    private static final Pattern INTENT_TO_CLARIFY_PATTERN = Pattern.compile(
            "整理成方案|做成方案|生成方案|转为方案|变成方案|整理成任务|做成任务|落地实施|"
                    + "出一份方案|写个方案|方案化|整理方案|出个方案|出方案|写方案|做个方案|做方案|方案整理|"
                    + "(新建|创建|建立|建)(一?个|一?份)?(计划|任务|方案)|"
                    + "(帮我|给我|给)(一?个|一?份)?(计划|方案)|"
                    + "生成(一?个|一?份)?(计划|任务|方案)|"
                    + "做(一?个|一?份)(计划|任务)|"
                    + "来(一?个|一?份)(计划|任务|方案)|"
                    + "出(一?个|一?份)(计划|任务)|"
                    + "帮我总结|"
                    + "总结(成|为|个|一下)(方案|计划|任务)");

    /**
     * 意图词二次确认的确认词（V40）：仅会话处于待确认状态时生效；
     * 开头命中且后随标点/空白/结尾，避免"好的，但我还想先聊聊"这类误判。
     */
    private static final Pattern CONFIRM_PHRASE_PATTERN = Pattern.compile(
            "^(确认|确定|好的|可以|开始吧|开始|是的|没错|没问题|行|嗯|OK|ok|Yes|yes)([。！？!?,.;；\\s]|$)");

    /** 会话标题取首条用户消息的截断长度。 */
    private static final int TITLE_LIMIT = 50;

    /** 会话列表单次返回上限（首期不做分页）。 */
    private static final int LIST_LIMIT = 50;

    /** BizException 附带的 LLM 原始输出摘要截断长度。 */
    private static final int RAW_OUTPUT_SUMMARY_LIMIT = 500;

    private static final String PROMPT_TEMPLATE_PATH = "prompts/requirement-clarify.md";

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** 追问形态（V33 双模协议）：structured 结构化选项式 / freeform 自由文本。 */
    private static final String MODE_STRUCTURED = "structured";
    private static final String MODE_FREEFORM = "freeform";

    /** V41 意图确认卡（structured 形态的二次确认弹窗）：问题 id 与题面文案。 */
    private static final String CONFIRM_QUESTION_ID = "confirm-switch";
    private static final String CONFIRM_QUESTION_TEXT = "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？";

    /** V41 意图确认卡选项：仅确认/取消两项，均不带推荐标记。 */
    private static final String CONFIRM_OPTION_ACCEPT = "确认";
    private static final String CONFIRM_OPTION_CANCEL = "取消";

    /**
     * V41 搜索查询词语义守卫：纯意图话术（不含主题）的长度上限。
     * 长度 ≤ 该值且命中意图词的消息视为无检索主题（如「帮我生成计划」），
     * 长句携带主题内容（如「我想 60 天备考架构师考试，帮我整理成方案」）仍可作查询词。
     */
    private static final int INTENT_ONLY_QUERY_LIMIT = 20;

    /** V41 assistant 消息 payload 的联网搜索查验键（与 mode/progress/questions 同级）。 */
    private static final String PAYLOAD_KEY_WEB_SEARCH = "webSearch";

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final TaskService taskService;
    private final AgentService agentService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final AgentInboxService agentInboxService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ObjectMapper objectMapper;
    private final WebSearchService webSearchService;
    private final WebSearchProperties webSearchProperties;
    private final WebPageFetchService pageFetchService;

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
                                         ObjectMapper objectMapper,
                                         WebSearchService webSearchService,
                                         WebSearchProperties webSearchProperties,
                                         WebPageFetchService pageFetchService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.taskService = taskService;
        this.agentService = agentService;
        this.plannerAgentPicker = plannerAgentPicker;
        this.agentInboxService = agentInboxService;
        this.platformAgentExecutionService = platformAgentExecutionService;
        this.taskTimelineService = taskTimelineService;
        this.objectMapper = objectMapper;
        this.webSearchService = webSearchService;
        this.webSearchProperties = webSearchProperties;
        this.pageFetchService = pageFetchService;
    }

    // ══════════════════════════════════════════════════════════════
    //  会话生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @param plannerAgentId   手动指定的 Planner Agent ID（空=系统自动选择）；
     *                         指定时严格校验可选性，澄清与后续拆解均跟随该 Planner
     * @param webSearchEnabled 会话级联网搜索开关（V34 新增；NULL=默认开启）；
     *                         每轮 LLM 调用前若 true 服务端会预检索行业资料并注入
     *                         {@code {{WEB_SEARCH_CONTEXT}}} 占位符，失败降级跳过
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled) {
        return create(firstMessage, plannerAgentId, webSearchEnabled, null);
    }

    /**
     * 新建会话（V39 双模式入口）。
     *
     * @param initialMode 初始对话模式（V39）：'CHAT'=自由对话（缺省）/ 'CLARIFY'=方案澄清快捷直达；
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
        // V39：新会话默认 CHAT 自由对话；initialMode=CLARIFY 快捷直达澄清链路
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
     * @param selections 结构化选项回答快照（V33，可为 null/空=纯文本回答）；
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
        // V39 轮数上限按模式分派：CHAT 用独立上限（意图词「整理成方案」永远放行，保证转方案出口）；
        // V40 待确认状态的确认词（或再次意图词）同样放行；V41 确认卡点「确认」（selections 快照）也放行——
        // 确认消息会转入 CLARIFY，不算 CHAT 轮；CLARIFY（含 NULL 老数据）沿用既有 20 轮上限
        boolean intent = isIntentToClarify(message);
        boolean confirm = isPendingClarifyConfirm(conversation)
                && (isConfirmPhrase(message) || intent || isConfirmCardAccept(selections));
        if (isChatMode(conversation) && !intent && !confirm) {
            if (rounds >= MAX_CHAT_ROUNDS) {
                throw new BizException("自由对话轮数已达上限 " + MAX_CHAT_ROUNDS
                        + "，可输入「整理成方案」转为方案模式，或新建会话");
            }
        } else if (isClarifyMode(conversation) && rounds >= MAX_ROUNDS) {
            throw new BizException("澄清轮数已达上限 " + MAX_ROUNDS
                    + "，请放弃本会话并在任务管理中手动创建任务");
        }
        return doRound(conversation, message.trim(), buildSelectionPayload(selections));
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
     * 切换到方案澄清模式（V40.2 斜杠命令路径）：先落库附加文本（用户消息，进 LLM 上下文），
     * 再切 CLARIFY 并跑一轮澄清（V40.1 首轮强制 structured → 推荐卡片必出）。
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
     * 切换到方案澄清模式（V39）：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail switchToClarify(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        String from = conversation.getMode();
        conversation.setMode(MODE_CLARIFY);
        // V40：手动切换时一并清除意图词待确认标记，避免残留状态影响后续轮次
        conversation.setPendingClarifyConfirm(false);
        conversationService.updateById(conversation);
        log.info("澄清会话切换模式: conversationId={}, from={}, to={}",
                conversationId, from, MODE_CLARIFY);
        // 切换轮不做联网搜索（阶段 2 再评估）；澄清模板基于全量历史直接产草案/追问
        return runLlmRound(conversation, null);
    }

    /**
     * 切回自由对话模式（V39）：仅置位，不调用 LLM；
     * 历史消息全部保留，后续切回 CLARIFY 时作为全量澄清上下文。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail switchToChat(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        String from = conversation.getMode();
        conversation.setMode(MODE_CHAT);
        // V40：切回 CHAT 时防御性清除意图词待确认标记（该状态仅 CHAT 模式语义存在）
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
        // V40 意图词二次确认状态机（仅 CHAT 模式）：
        //   意图词命中且无待确认 → 置位 + 回复固定确认询问（不调 LLM、不加轮数）
        //   待确认 + 确认词/再次意图词 → 切 CLARIFY 并清标记，该条消息即澄清首轮
        //   待确认 + 其他消息 → 清标记继续自由对话（用户放弃转方案）
        if (isChatMode(conversation)) {
            boolean intent = isIntentToClarify(userMessage);
            boolean pendingConfirm = isPendingClarifyConfirm(conversation);
            if (intent && !pendingConfirm) {
                conversation.setPendingClarifyConfirm(true);
                conversationService.updateById(conversation);
                messageService.addMessage(conversationId, ROLE_USER, userMessage, userPayload);
                // V41：确认询问改为结构化选项卡（前端渲染为确认/取消弹窗），
                // 可读正文仍落 CONFIRM_ASK_MESSAGE 保证 transcript 上下文不变
                messageService.addMessage(conversationId, ROLE_ASSISTANT, CONFIRM_ASK_MESSAGE,
                        buildConfirmAskPayload());
                log.info("澄清会话意图词命中，等待用户确认转方案: conversationId={}", conversationId);
                return new ClarifyConversationDetail(conversation,
                        messageService.listByConversation(conversationId));
            }
            // V41 确认判定三通道：手打确认词 / 再次意图词 / 确认卡点「确认」（selections 快照）；
            // 卡片提交文本形如「问题：确认」不命中 CONFIRM_PHRASE_PATTERN 开头锚定，须走快照判定
            if (pendingConfirm) {
                String cardValue = confirmCardValueOf(userPayload);
                boolean confirmed = isConfirmPhrase(userMessage) || intent
                        || CONFIRM_OPTION_ACCEPT.equals(cardValue);
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

        // V41 联网搜索（V34 引入）：V45 起 CHAT/CLARIFY 任意模式每轮且开关开启
        // （NULL/true 视为开启）都检索；成本由各自轮数上限封顶（CHAT 50 / CLARIFY 20），
        // 每轮折叠查验条可见搜索词；确认词/确认卡提交文本等无检索语义的消息回退历史主题消息作查询词
        WebSearchOutcome webSearchOutcome = null;
        if (isWebSearchEnabled(conversation)) {
            webSearchOutcome = doWebSearch(resolveSearchSource(conversationId, userMessage, userPayload));
        }
        return runLlmRound(conversation, webSearchOutcome);
    }

    /**
     * 解析联网搜索查询词来源（V41 修复）：当前轮消息无检索语义时（确认词 / 确认卡提交文本 /
     * 纯意图话术），倒序回退最近一条有实际内容的 user 消息（通常是触发意图前的讨论主题）；
     * 全部无意义时返回空白串，doWebSearch 视为未发起搜索（不落查验条）。
     */
    private String resolveSearchSource(Long conversationId, String userMessage, String userPayload) {
        if (!lacksSearchSemantics(userMessage, userPayload)) {
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
                if (Objects.equals(c, userMessage) || lacksSearchSemantics(c, null)) {
                    continue;
                }
                log.info("澄清联网搜索查询词回退历史主题消息: conversationId={}, fallbackLen={}",
                        conversationId, c.length());
                return c;
            }
        }
        return "";
    }

    /**
     * 无检索语义判定：确认词 / 确认卡提交文本（题面前缀或卡选快照）/ 纯意图短句。
     * userPayload 仅当前轮可传（历史消息回退扫描传 null）。
     */
    private boolean lacksSearchSemantics(String message, String userPayload) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String trimmed = message.trim();
        if (isConfirmPhrase(trimmed)) {
            return true;
        }
        if (trimmed.startsWith(CONFIRM_QUESTION_TEXT)) {
            return true;
        }
        if (userPayload != null && CONFIRM_OPTION_ACCEPT.equals(confirmCardValueOf(userPayload))) {
            return true;
        }
        return isIntentToClarify(trimmed) && trimmed.length() <= INTENT_ONLY_QUERY_LIMIT;
    }

    /** NULL/true 视为开启；只有严格的 false 走关闭语义。 */
    private boolean isWebSearchEnabled(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        return v == null || v;
    }

    /** V40 意图词二次确认标记：仅显式 true 视为待确认（老数据 NULL/0 均为无待确认）。 */
    private boolean isPendingClarifyConfirm(RequirementConversation conversation) {
        return Boolean.TRUE.equals(conversation.getPendingClarifyConfirm());
    }

    /** V40 确认词判定：开头命中确认词且后随标点/空白/结尾（待确认状态专用，普通对话不受影响）。 */
    private boolean isConfirmPhrase(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return CONFIRM_PHRASE_PATTERN.matcher(message.trim()).find();
    }

    /**
     * V41 确认卡点选判定（selections 快照）：包含 confirm-switch 题且选中「确认」视为确认。
     * 卡片提交文本形如「问题：确认」不命中 {@link #CONFIRM_PHRASE_PATTERN} 开头锚定，
     * 故点选确认须走快照通道；点「取消」返回 false → 走清标记继续对话分支。
     */
    private boolean isConfirmCardAccept(List<ClarifySelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return false;
        }
        for (ClarifySelection selection : selections) {
            if (CONFIRM_QUESTION_ID.equals(selection.getQuestionId())
                    && selection.getValues() != null
                    && selection.getValues().contains(CONFIRM_OPTION_ACCEPT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * V41 从 user payload（{@code {"selections":[...]}}）解析确认卡选择：
     * 返回选中值（确认/取消）；无确认卡选择/解析失败返回 null（回退文本判定）。
     */
    private String confirmCardValueOf(String userPayload) {
        if (userPayload == null || userPayload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(userPayload);
            JsonNode selections = root.get("selections");
            if (selections == null || !selections.isArray()) {
                return null;
            }
            for (JsonNode selection : selections) {
                if (!CONFIRM_QUESTION_ID.equals(selection.path("questionId").asText(null))) {
                    continue;
                }
                JsonNode values = selection.get("values");
                if (values != null && values.isArray() && !values.isEmpty()) {
                    return values.get(0).asText(null);
                }
            }
        } catch (Exception e) {
            log.warn("确认卡选择解析失败，回退文本判定: {}", e.getMessage());
        }
        return null;
    }

    /** 是否方案澄清模式：NULL 老数据按 CLARIFY 兼容（V39）。 */
    private boolean isClarifyMode(RequirementConversation conversation) {
        return conversation.getMode() == null || MODE_CLARIFY.equals(conversation.getMode());
    }

    /** 是否自由对话模式：仅显式 CHAT（NULL 老数据不算，按 CLARIFY）。 */
    private boolean isChatMode(RequirementConversation conversation) {
        return MODE_CHAT.equals(conversation.getMode());
    }

    /** 是否命中 CHAT → CLARIFY 意图词（"整理成方案"等常见说法，正则子串命中即切换）。 */
    private boolean isIntentToClarify(String message) {
        return message != null && INTENT_TO_CLARIFY_PATTERN.matcher(message).find();
    }

    /**
     * 意图词二次确认的 structured payload（V41）：1 题 2 选项（确认/取消），
     * 均不带 recommended → 前端不渲染"推荐"按钮；allowCustom=false → 隐藏自定义补充输入框。
     *
     * <p>为什么用 structured 卡片替代纯文本确认：用户点选后经 selections 快照通道判定
     * （点「确认」走切换分支；点「取消」走继续对话分支，见 {@link #isConfirmCardAccept}），
     * 手写确认词仍兼容 {@link #CONFIRM_PHRASE_PATTERN}，后端状态机零改动，
     * 交互形态与方案细则确认卡片一致。</p>
     *
     * @return payload JSON；序列化失败降级 null（回退纯文本确认，不阻断主流程）
     */
    private String buildConfirmAskPayload() {
        ClarifyOption accept = new ClarifyOption();
        accept.setLabel(CONFIRM_OPTION_ACCEPT);
        accept.setValue(CONFIRM_OPTION_ACCEPT);
        ClarifyOption cancel = new ClarifyOption();
        cancel.setLabel(CONFIRM_OPTION_CANCEL);
        cancel.setValue(CONFIRM_OPTION_CANCEL);

        ClarifyQuestion question = new ClarifyQuestion();
        question.setId(CONFIRM_QUESTION_ID);
        question.setText(CONFIRM_QUESTION_TEXT);
        question.setMultiple(false);
        question.setAllowCustom(false);
        question.setOptions(List.of(accept, cancel));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", MODE_STRUCTURED);
        payload.put("questions", List.of(question));
        try {
            // 跳过 null 字段（weight/recommended 不序列化）：保证卡片确实不带推荐标记
            return objectMapper.copy()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("意图确认卡 payload 序列化失败，降级纯文本确认", e);
            return null;
        }
    }

    /**
     * 初始模式归一化（V39）：缺省/显式 CHAT → CHAT（新会话默认自由对话）；
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
     * 联网搜索一次：URL 分离 + 关键词提取 → 直取页面 + 调用搜索服务 → 归一化结果记录。
     *
     * <p>V43 URL 分离：消息中的 http(s) 链接被提取后直接访问抓取页面正文（用户给出的
     * 站点是第一手资料），搜索词改用剥离 URL 后的语义文本——裸 URL 文本当搜索词
     * 检索效果极差（用户实测：「给我一份快速上手 https://open.maic.chat/ …」未搜到相关网页）；
     * 纯 URL 消息回退域名作搜索词。直取页面映射为来源置顶合并进结果（总条数 maxResults 内）。</p>
     *
     * <p>V44 直取失败域名前缀：消息带 URL 但直取无一成功（SPA 空壳无元数据/反爬拦截）时，
     * 搜索词前置首个域名，让搜索引擎检索该站点的公开资料（介绍/教程/文档），
     * 避免用户实测的「直取空 + 搜索词不含域名 → results=0」双失败叠加。</p>
     *
     * <p>异常降级为 failed outcome（落 payload 可查验，不阻断澄清主流程）；
     * 查询词空白且无成功直取页面时返回 null（未获得任何资料，不落 webSearch 键）。</p>
     */
    private WebSearchOutcome doWebSearch(String userMessage) {
        List<String> urls = extractUrls(userMessage);
        String query = extractQueryKeyword(stripUrls(userMessage));
        List<WebPageContent> pages = fetchUserPages(urls);
        boolean hasOkPage = pages.stream().anyMatch(WebPageContent::isOk);
        if (query.isBlank() && !urls.isEmpty()) {
            // 纯 URL 消息：回退域名作搜索词（无论直取成败）
            query = hostOfUrl(urls.get(0));
            log.info("澄清联网搜索：纯 URL 消息回退域名作搜索词: query={}", query);
        } else if (!urls.isEmpty() && !hasOkPage) {
            // V44：语义文本存在但直取全部失败 → 域名前置增强搜索词，
            // 让搜索引擎检索该站点的公开资料（介绍/教程/文档）
            String host = hostOfUrl(urls.get(0));
            if (!host.isBlank()) {
                query = host + " " + query;
                log.info("澄清联网搜索：直取失败，域名前置增强搜索词: query={}", query);
            }
        }
        if (query.isBlank() && !hasOkPage) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            List<WebSearchResult> searched = query.isBlank()
                    ? Collections.emptyList()
                    : webSearchService.search(query, webSearchProperties.getMaxResults());
            long costMs = System.currentTimeMillis() - t0;
            List<WebSearchResult> merged = mergeFetchedIntoResults(pages, searched);
            log.info("澄清联网搜索结束: provider={}, query={}, pages={}, results={}, costMs={}",
                    webSearchService.provider(), query, pages.size(), merged.size(), costMs);
            return WebSearchOutcome.builder()
                    .provider(webSearchService.provider())
                    .query(query)
                    .costMs(costMs)
                    .total(merged.size())
                    .results(merged)
                    .fetchedPages(pages)
                    .build();
        } catch (Exception e) {
            log.warn("澄清联网搜索异常降级（不动澄清主流程）: query={}, err={}", query, e.getMessage());
            return WebSearchOutcome.builder()
                    .provider(webSearchService.provider())
                    .query(query)
                    .costMs(System.currentTimeMillis() - t0)
                    .fetchedPages(pages)
                    .failed(true)
                    .reason(e.getMessage())
                    .build();
        }
    }

    /** 消息中 http(s) 链接提取（尾随中文标点/括号/引号不算 URL 一部分）。 */
    private static final Pattern URL_IN_TEXT_PATTERN = Pattern.compile(
            "https?://[^\\s<>\"'，。；、（）()【】\\[\\]{}]+");

    /** 提取消息中全部 URL（出现顺序，供直取）。 */
    private List<String> extractUrls(String message) {
        if (message == null || message.isBlank()) return Collections.emptyList();
        List<String> urls = new ArrayList<>();
        Matcher m = URL_IN_TEXT_PATTERN.matcher(message);
        while (m.find()) {
            urls.add(m.group());
        }
        return urls;
    }

    /** 剥离 URL 后的语义文本（搜索词来源），空白归一。 */
    private String stripUrls(String message) {
        if (message == null) return "";
        return URL_IN_TEXT_PATTERN.matcher(message).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** URL 的 host（纯 URL 消息的域名回退搜索词 / 直取来源的 siteName）。 */
    private static String hostOfUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 直取用户给出的页面（前 N 个去重 URL，N=urlFetchMaxPages）；总开关关闭或无 URL 时
     * 返回空列表；fetcher 异常已内部降级（ok=false 记录也保留，payload 可查验）。
     */
    private List<WebPageContent> fetchUserPages(List<String> urls) {
        if (!webSearchProperties.isUrlFetchEnabled() || urls.isEmpty()) {
            return Collections.emptyList();
        }
        int max = Math.max(1, webSearchProperties.getUrlFetchMaxPages());
        List<WebPageContent> out = new ArrayList<>();
        for (String url : urls.stream().distinct().limit(max).toList()) {
            WebPageContent page = pageFetchService.fetch(url);
            if (page == null) continue; // 契约保证非 null，防御性兜底
            out.add(page);
            log.info("澄清联网搜索 URL 直取{}: url={}, textChars={}",
                    page.isOk() ? "成功" : "失败(" + page.getReason() + ")",
                    url, page.getText() == null ? 0 : page.getText().length());
        }
        return out;
    }

    /** 直取页面映射为来源置顶 + 搜索结果补后，总条数 cap 在 maxResults 内。 */
    private List<WebSearchResult> mergeFetchedIntoResults(List<WebPageContent> pages,
                                                          List<WebSearchResult> searched) {
        int cap = webSearchProperties.getMaxResults();
        int snippetMax = webSearchProperties.getMaxSnippetChars();
        List<WebSearchResult> merged = new ArrayList<>();
        for (WebPageContent p : pages) {
            if (!p.isOk() || merged.size() >= cap) continue;
            String text = p.getText() == null ? "" : p.getText();
            merged.add(WebSearchResult.builder()
                    .title(p.getTitle() == null || p.getTitle().isBlank() ? "(无标题)" : p.getTitle())
                    .url(p.getUrl())
                    .snippet(text.length() <= snippetMax ? text : text.substring(0, snippetMax) + "…")
                    .siteName(hostOfUrl(p.getUrl()))
                    .build());
        }
        if (searched != null) {
            for (WebSearchResult r : searched) {
                if (merged.size() >= cap) break;
                merged.add(r);
            }
        }
        return merged;
    }

    /** 关键词提取：用户消息前 queryKeywordLimit 字符（去两端空白）。 */
    private String extractQueryKeyword(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        int limit = webSearchProperties.getQueryKeywordLimit();
        if (trimmed.length() <= limit) return trimmed;
        return trimmed.substring(0, limit);
    }

    /**
     * LLM 一轮（不落 user 消息）：选 Planner → 全量历史渲染模板 → LLM →
     * 解析 question/final 分支落库；doRound 与 retryRound 共用。
     *
     * @param webSearchOutcome 本轮联网搜索归一化记录（V41；未搜索/重试/切换轮传 null）；
     *                         已在 doRound 里根据开关限定过；注入 Prompt 与落 payload 两用
     */
    private ClarifyConversationDetail runLlmRound(RequirementConversation conversation,
                                                  WebSearchOutcome webSearchOutcome) {
        Long conversationId = conversation.getId();
        // V39 双模式分派：CLARIFY（含 NULL 老数据）走澄清模板 + JSON 协议解析；
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

        // CHAT 模式（V40.2 容错双模）：优先尝试宽松解析 structured 追问（LLM 需要用户回答
        // 关键决策问题时输出选项卡片）；解析失败/非追问一律纯文本直落（payload NULL），零行为破坏
        if (!clarifyMode) {
            String output = result.getOutput();
            if (output == null || output.isBlank()) {
                throw new BizException("自由对话 LLM 返回内容为空");
            }
            ClarifyReply chatReply = tryParseChatStructured(output);
            if (chatReply != null) {
                // V45：CHAT 轮结构化追问卡同样携带本轮联网搜索查验信息
                messageService.addMessage(conversationId, ROLE_ASSISTANT,
                        composeAssistantContent(chatReply), buildQuestionPayload(chatReply, webSearchOutcome));
                log.info("自由对话结构化追问落库: conversationId={}", conversationId);
            } else {
                // V45：CHAT 轮联网搜索后纯文本回复同样携带 webSearch 查验键（与终稿轮同形态）
                if (webSearchOutcome != null) {
                    messageService.addMessage(conversationId, ROLE_ASSISTANT, output.trim(),
                            buildWebSearchOnlyPayload(webSearchOutcome));
                } else {
                    messageService.addMessage(conversationId, ROLE_ASSISTANT, output.trim(), null);
                }
                log.info("自由对话回复落库: conversationId={}", conversationId);
            }
            return new ClarifyConversationDetail(conversation,
                    messageService.listByConversation(conversationId));
        }

        ClarifyReply reply = parseReply(result.getOutput());
        if ("final".equals(reply.getType())) {
            String note = reply.getMessage() != null && !reply.getMessage().isBlank()
                    ? reply.getMessage() : "已生成终稿";
            // V41：终稿轮同样落联网搜索查验信息（未发生搜索时保持原 3 参形态）
            if (webSearchOutcome != null) {
                messageService.addMessage(conversationId, ROLE_ASSISTANT, note,
                        buildWebSearchOnlyPayload(webSearchOutcome));
            } else {
                messageService.addMessage(conversationId, ROLE_ASSISTANT, note);
            }
            conversation.setFinalTitle(reply.getTitle());
            conversation.setFinalDescription(reply.getDescription());
            conversationService.updateById(conversation);
            log.info("澄清会话产出终稿: conversationId={}, finalTitle={}",
                    conversationId, reply.getTitle());
        } else {
            messageService.addMessage(conversationId, ROLE_ASSISTANT, composeAssistantContent(reply),
                    buildQuestionPayload(reply, webSearchOutcome));
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

    /**
     * 解析 LLM 输出：strip fence 容错 + type/message 必填校验。
     *
     * <p>V33 降级策略（降级是一等公民路径，不是异常）：
     * <ul>
     *   <li>输出完全不是 JSON 且不含 {@code "type"} 字样 → 原文作 freeform 追问落库；</li>
     *   <li>含 {@code "type"} 但解析失败 → 保持抛 BizException 走现有 retry 链路；</li>
     *   <li>structured 追问校验不过（无问题/无选项）→ 降级 freeform。</li>
     * </ul>
     */
    private ClarifyReply parseReply(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("澄清 LLM 返回内容为空");
        }
        String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(rawOutput));
        ClarifyReply reply;
        try {
            reply = objectMapper.readValue(cleaned, ClarifyReply.class);
        } catch (Exception e) {
            if (!rawOutput.contains("\"type\"")) {
                log.warn("澄清 LLM 输出非 JSON，降级为 freeform 追问: {}", summarize(rawOutput));
                ClarifyReply fallback = new ClarifyReply();
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
    private void normalizeQuestionReply(ClarifyReply reply, String rawOutput) {
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
    private boolean isStructuredValid(ClarifyReply reply) {
        if (reply.getQuestions() == null || reply.getQuestions().isEmpty()) {
            return false;
        }
        for (ClarifyQuestion question : reply.getQuestions()) {
            if (question == null || question.getText() == null || question.getText().isBlank()) {
                return false;
            }
            if (question.getOptions() == null || question.getOptions().isEmpty()) {
                return false;
            }
            for (ClarifyOption option : question.getOptions()) {
                if (option == null || option.getLabel() == null || option.getLabel().isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** structured 合法后补齐缺省值：问题 id 缺失用 q{序号}，选项 value 缺失用 label。 */
    private void fillStructuredDefaults(ClarifyReply reply) {
        int idx = 1;
        for (ClarifyQuestion question : reply.getQuestions()) {
            if (question.getId() == null || question.getId().isBlank()) {
                question.setId("q" + idx);
            }
            idx++;
            for (ClarifyOption option : question.getOptions()) {
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
    private String composeAssistantContent(ClarifyReply reply) {
        if (!MODE_STRUCTURED.equals(reply.getMode())
                || reply.getQuestions() == null || reply.getQuestions().isEmpty()) {
            return reply.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        if (reply.getMessage() != null && !reply.getMessage().isBlank()) {
            sb.append(reply.getMessage().trim());
        }
        int idx = 1;
        for (ClarifyQuestion question : reply.getQuestions()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            List<String> labels = new ArrayList<>();
            for (ClarifyOption option : question.getOptions()) {
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
     * @param outcome 本轮联网搜索记录（V41，可为 null）；非空时合并 {@code webSearch} 键，
     *                前端据此渲染折叠查验条
     */
    private String buildQuestionPayload(ClarifyReply reply, WebSearchOutcome outcome) {
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
    private String buildWebSearchOnlyPayload(WebSearchOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of(PAYLOAD_KEY_WEB_SEARCH, buildWebSearchMap(outcome)));
        } catch (Exception e) {
            log.warn("澄清终稿轮 webSearch payload 序列化失败，降级纯文本消息", e);
            return null;
        }
    }

    /**
     * 搜索记录 → payload 嵌套 Map：provider/query/costMs/total/results，
     * 失败时附 failed/reason；results 每条含 title/url/snippet/siteName（前端查验条展开用）。
     */
    private Map<String, Object> buildWebSearchMap(WebSearchOutcome outcome) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("provider", outcome.getProvider());
        trace.put("query", outcome.getQuery());
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
        // V43 URL 直取记录（含失败记录可查验）；无 URL 时不落键，前端忽略未知键零兼容成本
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
    private String buildSelectionPayload(List<ClarifySelection> selections) {
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
     * CHAT 轮结构化追问宽松解析（V40.2）：仅认可 type=question 且 mode=structured 且校验合法；
     * 其余（freeform/final/非 JSON/解析失败）一律返回 null → 调用方按纯文本落库。
     * 不抛异常：CHAT 是自由对话，LLM 输出 JSON 仅是引导型增强（需要用户回答时出推荐卡片），
     * 失败降级为普通聊天，与 CLARIFY 的 parseReply 严格路径完全隔离。
     */
    private ClarifyReply tryParseChatStructured(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        try {
            String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(rawOutput));
            ClarifyReply reply = objectMapper.readValue(cleaned, ClarifyReply.class);
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
