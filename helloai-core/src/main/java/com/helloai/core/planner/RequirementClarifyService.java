package com.helloai.core.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
@RequiredArgsConstructor
public class RequirementClarifyService {

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

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final TaskService taskService;
    private final AgentService agentService;
    private final AgentSelector agentSelector;
    private final AgentInboxService agentInboxService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ObjectMapper objectMapper;

    // ══════════════════════════════════════════════════════════════
    //  会话生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @return 会话 + 全部消息
     */
    public ClarifyConversationDetail create(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            throw new BizException("首条消息不能为空");
        }
        String trimmed = firstMessage.trim();
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(trimmed.length() <= TITLE_LIMIT
                ? trimmed : trimmed.substring(0, TITLE_LIMIT));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversationService.save(conversation);
        log.info("澄清会话创建: id={}, title={}", conversation.getId(), conversation.getTitle());
        return doRound(conversation, trimmed);
    }

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清。
     *
     * @return 会话 + 全部消息
     */
    public ClarifyConversationDetail sendMessage(Long conversationId, String message) {
        if (message == null || message.isBlank()) {
            throw new BizException("消息不能为空");
        }
        RequirementConversation conversation = requireActive(conversationId);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        if (rounds >= MAX_ROUNDS) {
            throw new BizException("澄清轮数已达上限 " + MAX_ROUNDS
                    + "，请放弃本会话并在任务管理中手动创建任务");
        }
        return doRound(conversation, message.trim());
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
     * 一轮澄清：存 user 消息、round_count+1 → 全量历史渲染模板 → LLM →
     * 解析 question/final 分支落库。
     */
    private ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage) {
        Long conversationId = conversation.getId();
        messageService.addMessage(conversationId, ROLE_USER, userMessage);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);

        Agent planner = pickPlannerAgent();
        String prompt = renderPrompt(conversationId);
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
            messageService.addMessage(conversationId, ROLE_ASSISTANT, reply.getMessage());
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
     * 选平台内 API_KEY_LLM Planner Agent；无可用时报错并附操作指引。
     *
     * <p>与 {@link PlannerAnalysisService} 的同名私有方法逐行一致（12 行）；
     * 刻意复制不抽象：两处调用方语义独立演化，提前抽公共方法反而耦合。</p>
     */
    private Agent pickPlannerAgent() {
        Agent preferred = agentSelector.pickPreferred(AgentRole.PLANNER);
        if (preferred != null && preferred.getAccessType() == AgentAccessType.API_KEY_LLM) {
            return preferred;
        }
        // 首选非平台内执行面时，从同角色候选中过滤 API_KEY_LLM
        return agentService.listByRole(AgentRole.PLANNER).stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .findFirst()
                .orElseThrow(() -> new BizException(
                        "无可用的平台内 Planner Agent（需要 role=PLANNER 且 accessType=API_KEY_LLM）；"
                                + "请先在 Agent 管理中注册，或改用外部 Planner Agent 手工创建子任务"));
    }

    /** 加载 classpath 模板并替换 {{CONVERSATION_HISTORY}}（transcript 全量历史）。 */
    private String renderPrompt(Long conversationId) {
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
        return template.replace("{{CONVERSATION_HISTORY}}", transcript.toString().trim());
    }

    /** 解析 LLM 输出：strip fence 容错 + type/message 必填校验。 */
    private ClarifyReply parseReply(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("澄清 LLM 返回内容为空");
        }
        String cleaned = stripToJsonObject(rawOutput);
        ClarifyReply reply;
        try {
            reply = objectMapper.readValue(cleaned, ClarifyReply.class);
        } catch (Exception e) {
            throw new BizException("澄清 LLM 输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (reply == null || reply.getType() == null) {
            throw new BizException("澄清 LLM 输出缺少 type 字段; 原始输出摘要: " + summarize(rawOutput));
        }
        if ("question".equals(reply.getType())) {
            if (reply.getMessage() == null || reply.getMessage().isBlank()) {
                throw new BizException("澄清 LLM 追问缺少 message 字段");
            }
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

    /** LLM 结构化输出（未知字段容忍）：type=question 追问 / type=final 终稿。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClarifyReply {
        private String type;
        private String message;
        private String title;
        private String description;
    }
}
