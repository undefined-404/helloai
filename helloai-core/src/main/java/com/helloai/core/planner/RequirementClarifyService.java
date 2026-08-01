package com.helloai.core.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.search.WebSearchService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话式需求澄清编排服务（需求模糊 → 多轮追问 → 终稿 → 创建任务）。
 *
 * <p>复用 {@link PlannerAnalysisService} 的五段式范式：选 Agent → classpath 模板渲染 →
 * executeSync → strip fence 容错解析 → BizException。类不加事务：LLM 调用耗时较长，
 * 不能占用数据库事务；单条消息落库走 ServiceImpl 自带事务。</p>
 *
 * <p>LLM/解析失败时：user 消息保留（round_count 已加），抛 BizException，
 * 前端弹错后可重发一条消息重试；会话本身无状态回退需求（始终 ACTIVE）。</p>
 */
@Slf4j
@Service
public class RequirementClarifyService {

    /**
     * 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑：显式列为 Spring DI 唯一依据）。
     */
    @Autowired
    public RequirementClarifyService(RequirementConversationService conversationService,
                                     RequirementMessageService messageService,
                                     TaskService taskService,
                                     AgentService agentService,
                                     PlannerAgentPicker plannerAgentPicker,
                                     AgentInboxService agentInboxService,
                                     PlatformAgentExecutionService platformAgentExecutionService,
                                     TaskTimelineService taskTimelineService,
                                     ObjectMapper objectMapper,
                                     WebSearchService webSearchService,
                                     WebSearchProperties webSearchProperties) {
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
    }

    /** 会话状态常量（与 V29 CHECK 约束对齐）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FINALIZED = "FINALIZED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    /** 用户消息轮数硬上限（Prompt 侧引导尽早出终稿，服务端只做兜底）。 */
    private static final int MAX_ROUNDS = 20;

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

    // ══════════════════════════════════════════════════════════════
    //  会话生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @param plannerAgentId   手动指定的 Planner Agent ID（空=系统自动选择）；
     *                         指定时严格校验可选性，澄清与后续拆解均跟随该 Planner
     * @param webSearchEnabled 会话级联网搜索开关（V34 新增；NULL=默认开启）；
     *                         首轮 LLM 调用前若 true 服务端会预检索行业资料并注入
     *                         {@code {{WEB_SEARCH_CONTEXT}}} 占位符，失败降级跳过
     * @return 会话 + 全部消息
     */
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled) {
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
        conversationService.save(conversation);
        log.info("澄清会话创建: id={}, title={}, plannerAgentId={}, webSearchEnabled={}",
                conversation.getId(), conversation.getTitle(), plannerAgentId, webSearchEnabled);
        return doRound(conversation, trimmed);
    }

    /** 兼容重载：旧调用方未传开关时默认开启（NULL 代表默认开启，与老数据语义一致）。 */
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId) {
        return create(firstMessage, plannerAgentId, null);
    }

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清（纯文本，无选项快照）。
     *
     * @return 会话 + 全部消息
     */
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
    public ClarifyConversationDetail sendMessage(Long conversationId, String message,
                                                 List<ClarifySelection> selections) {
        if (message == null || message.isBlank()) {
            throw new BizException("消息不能为空");
        }
        RequirementConversation conversation = requireActive(conversationId);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        if (rounds >= MAX_ROUNDS) {
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
    public ClarifyConversationDetail retryRound(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        List<RequirementMessage> messages = messageService.listByConversation(conversationId);
        if (messages.isEmpty()
                || !ROLE_USER.equals(messages.get(messages.size() - 1).getRole())) {
            throw new BizException("最后一条消息已有助手回复，无需重试: conversationId=" + conversationId);
        }
        log.info("澄清会话重试一轮 LLM: conversationId={}", conversationId);
        // 重试场景不复用首轮预检索资料：失败那轮通常与外部 API 副作用相关，重跑前重新准备
        return runLlmRound(conversation, "");
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    public List<PlannerAgentPicker.PlannerOption> listPlannerOptions() {
        return plannerAgentPicker.listOptions();
    }

    /**
     * 终稿确认：创建 Task（PENDING）→ best-effort 通知 PLANNER →
     * 会话回填 task_id、状态 FINALIZED → timeline 记录。
     *
     * @return 创建的任务
     */
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
    public void abandon(Long conversationId) {
        RequirementConversation conversation = requireActive(conversationId);
        conversation.setStatus(STATUS_ABANDONED);
        conversationService.updateById(conversation);
        log.info("澄清会话已放弃: conversationId={}", conversationId);
    }

    /** 会话列表（按创建时间倒序，LIMIT 50，首期不分页）。 */
    public List<RequirementConversation> listConversations() {
        return conversationService.lambdaQuery()
                .orderByDesc(RequirementConversation::getCreateTime)
                .last("LIMIT " + LIST_LIMIT)
                .list();
    }

    /** 会话详情：会话 + 消息按 seq 升序。 */
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
        messageService.addMessage(conversationId, ROLE_USER, userMessage, userPayload);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);

        // V34 联网搜索：仅首轮（rounds == 0）且开关开启（NULL/true 视为开启）时检索
        String webSearchContext = "";
        if (rounds == 0 && isWebSearchEnabled(conversation)) {
            webSearchContext = doWebSearch(userMessage);
        }
        return runLlmRound(conversation, webSearchContext);
    }

    /** NULL/true 视为开启；只有严格的 false 走关闭语义。 */
    private boolean isWebSearchEnabled(RequirementConversation conversation) {
        Boolean v = conversation.getWebSearchEnabled();
        return v == null || v;
    }

    /**
     * 联网搜索一次：提取关键词 → 调用搜索服务 → 渲染为注入文本。
     * 任一环节异常/失败降级为空串。
     */
    private String doWebSearch(String firstUserMessage) {
        String query = extractQueryKeyword(firstUserMessage);
        if (query.isBlank()) return "";
        long t0 = System.currentTimeMillis();
        try {
            List<WebSearchResult> results = webSearchService.search(query, webSearchProperties.getMaxResults());
            log.info("澄清联网搜索结束: provider={}, query={}, results={}, costMs={}",
                    webSearchService.provider(), query, results.size(), System.currentTimeMillis() - t0);
            return renderWebSearchContext(results);
        } catch (Exception e) {
            log.warn("澄清联网搜索异常降级（不动澄清主流程）: query={}, err={}", query, e.getMessage());
            return "";
        }
    }

    /** 关键词提取：首条用户消息前 queryKeywordLimit 字符（去两端空白）。 */
    private String extractQueryKeyword(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        int limit = webSearchProperties.getQueryKeywordLimit();
        if (trimmed.length() <= limit) return trimmed;
        return trimmed.substring(0, limit);
    }

    /** 联网检索结果 → Prompt 文本（≤ 5 条，每条双行）。空列表输出占位符保证 Prompt 语义节稳定。 */
    private String renderWebSearchContext(List<WebSearchResult> results) {
        if (results == null || results.isEmpty()) return "（无可用联网资料）";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是联网检索到的行业资料（上限 ").append(results.size()).append(" 条，按相关性排序）：\n");
        for (int i = 0; i < results.size(); i++) {
            WebSearchResult r = results.get(i);
            sb.append('[').append(i + 1).append("] ").append(r.getTitle());
            if (r.getUrl() != null && !r.getUrl().isBlank()) {
                sb.append("（").append(r.getUrl()).append("）");
            }
            sb.append('\n').append(r.getSnippet());
            if (i < results.size() - 1) sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * LLM 一轮（不落 user 消息）：选 Planner → 全量历史渲染模板 → LLM →
     * 解析 question/final 分支落库；doRound 与 retryRound 共用。
     *
     * @param webSearchContext 首轮预检索的联网资料文本（重试场景传空串）；
     *                         已在 doRound 里根据首轮/开关限定过
     */
    private ClarifyConversationDetail runLlmRound(RequirementConversation conversation, String webSearchContext) {
        Long conversationId = conversation.getId();

        Agent planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = renderPrompt(conversationId, webSearchContext);
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId, "scene", "requirement_clarify"))
                .requiredCapabilities(Map.of())
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
        if (!result.isSuccess()) {
            throw new BizException("需求澄清 LLM 调用失败: " + result.getErrorMessage());
        }

        ClarifyReply reply = parseReply(result.getOutput());
        if ("final".equals(reply.getType())) {
            String note = reply.getMessage() != null && !reply.getMessage().isBlank()
                    ? reply.getMessage() : "已生成终稿";
            messageService.addMessage(conversationId, ROLE_ASSISTANT, note);
            conversation.setFinalTitle(reply.getTitle());
            conversation.setFinalDescription(reply.getDescription());
            conversationService.updateById(conversation);
            log.info("澄清会话产出终稿: conversationId={}, finalTitle={}",
                    conversationId, reply.getTitle());
        } else {
            messageService.addMessage(conversationId, ROLE_ASSISTANT, composeAssistantContent(reply),
                    buildQuestionPayload(reply));
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
     */
    private String renderPrompt(Long conversationId, String webSearchContext) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到澄清 Prompt 模板: " + PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取澄清 Prompt 模板失败: " + e.getMessage());
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
     * assistant 消息 payload：{@code {"mode","progress","questions"}}；
     * freeform 且无 progress 时返回 null（纯文本消息）；序列化失败降级 null 不阻断。
     */
    private String buildQuestionPayload(ClarifyReply reply) {
        boolean structured = MODE_STRUCTURED.equals(reply.getMode())
                && reply.getQuestions() != null && !reply.getQuestions().isEmpty();
        if (!structured && reply.getProgress() == null) {
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
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("澄清 assistant payload 序列化失败，降级纯文本消息", e);
            return null;
        }
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

    /** 会话 + 全部消息的组合视图（create/sendMessage/detail 统一返回）。 */
    @Data
    public static class ClarifyConversationDetail {
        private final RequirementConversation conversation;
        private final List<RequirementMessage> messages;
        /** 会话关联任务是否仍存在（仅 detail 计算填充）；前端据此判断 FINALIZED 会话能否重新生成。 */
        private Boolean taskExists;
    }

    /** LLM 结构化输出（未知字段容忍）：type=question 追问（双模） / type=final 终稿。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClarifyReply {
        private String type;
        /** 追问模式：structured 结构化选项式 / freeform 自由文本（缺省按 freeform） */
        private String mode;
        /** LLM 对需求澄清程度的 0~100 自评（仅展示，不做任何业务分支） */
        private Integer progress;
        private String message;
        private String title;
        private String description;
        /** mode=structured 时的问题列表 */
        private List<ClarifyQuestion> questions;
    }

    /** 结构化追问单题（V33）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClarifyQuestion {
        private String id;
        private String text;
        /** 是否多选 */
        private Boolean multiple;
        /** 是否允许自定义文本补充 */
        private Boolean allowCustom;
        private String customPlaceholder;
        private List<ClarifyOption> options;
    }

    /** 结构化追问选项（V33）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClarifyOption {
        private String label;
        private String value;
        /** 权重预留字段：schema 容忍透传，当前无业务消费 */
        private Integer weight;
        /** LLM 推荐选项标记（每题最多一个） */
        private Boolean recommended;
    }

    /** 用户结构化选项回答快照（user 消息 payload 的 selections 元素）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClarifySelection {
        private String questionId;
        private String questionText;
        /** 选中选项的 value 列表 */
        private List<String> values;
        /** 选中选项的 label 列表（回显用） */
        private List<String> labels;
        /** 是否含自定义补充 */
        private Boolean custom;
        private String customText;
    }
}
