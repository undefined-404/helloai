package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.mapper.RequirementConversationMapper;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.policy.TaskArchetypeResolver;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyFinalizeService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.port.TaskDraft;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ClarifyFinalizeService} 实现：澄清会话的<b>成任务链路</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的副作用簇）。
 *
 * <p>终稿 → {@code Task}：幂等校验 + CAS 推进 {@code ACTIVE → FINALIZED} + 建 PENDING Task +
 * best-effort 通知全部 PLANNER + 回填 task_id + timeline + 会话摘要归档（N-009，C5-S2）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyFinalizeServiceImpl implements ClarifyFinalizeService {

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final TaskService taskService;
    private final AgentService agentService;
    private final AgentInboxService agentInboxService;
    private final TaskTimelineService taskTimelineService;
    private final LongTermMemoryService longTermMemoryService;
    private final RequirementConversationMapper requirementConversationMapper;
    private final ObjectMapper objectMapper;
    private final ClarifyCommandSearchService commandSearch;

    @Override
    public TaskView finalize(Long conversationId) {
        return finalizeAfterDraft(commandSearch.requireActive(conversationId),
                TimelineEventCode.TASK_CREATED_FROM_CLARIFY.code());
    }

    @Override
    public TaskView finalizeAfterDraft(RequirementConversation conversation, String timelineEvent) {
        Long conversationId = conversation.getId();
        if (conversation.getFinalTitle() == null || conversation.getFinalTitle().isBlank()) {
            throw new BizException("会话尚无终稿，请先对话至 LLM 产出终稿: conversationId=" + conversationId);
        }
        // 幂等防御：已关联存活任务则拒绝重复创建（防竞态脏数据 / 重复点击造成双任务）
        if (conversation.getTaskId() != null && taskService.getView(conversation.getTaskId()) != null) {
            throw new BizException("会话已创建关联任务，请勿重复创建: taskId=" + conversation.getTaskId());
        }
        // CAS 推进 ACTIVE → FINALIZED：失败即并发已推进，拒绝建任务避免重复
        if (!casFinalize(conversationId)) {
            throw new BizException("会话状态已变化（可能已被终稿确认），请刷新后重试: conversationId=" + conversationId);
        }
        conversation.setStatus(RequirementClarifyService.STATUS_FINALIZED);
        return buildTaskFromDraft(conversation, timelineEvent);
    }

    /**
     * CAS 推进会话状态：ACTIVE → FINALIZED（定点更新，只写 status）。
     *
     * <p>全字段 updateById 会用加载期陈旧快照整体写回，与正在跑的澄清轮并发时互相覆盖
     * （FINALIZED 被回退成 ACTIVE 的丢失更新事故）；CAS 按行条件推进，仅一方可成功。</p>
     *
     * @return true=推进成功；false=状态已变化（并发已终稿确认 / 放弃等）
     */
    private boolean casFinalize(Long conversationId) {
        return conversationService.lambdaUpdate()
                .eq(RequirementConversation::getId, conversationId)
                .eq(RequirementConversation::getStatus, RequirementClarifyService.STATUS_ACTIVE)
                .set(RequirementConversation::getStatus, RequirementClarifyService.STATUS_FINALIZED)
                .update();
    }

    @Override
    public TaskView regenerate(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        if (!RequirementClarifyService.STATUS_FINALIZED.equals(conversation.getStatus())) {
            throw new BizException("仅已建任务（FINALIZED）的会话可重新生成: conversationId=" + conversationId
                    + ", status=" + conversation.getStatus());
        }
        if (conversation.getFinalTitle() == null || conversation.getFinalTitle().isBlank()) {
            throw new BizException("会话缺少终稿，无法重新生成: conversationId=" + conversationId);
        }
        Long oldTaskId = conversation.getTaskId();
        if (oldTaskId != null && taskService.getView(oldTaskId) != null) {
            throw new BizException("原任务仍然存在（taskId=" + oldTaskId
                    + "），请先在任务管理中删除后再重新生成");
        }
        return buildTaskFromDraft(conversation, "task_regenerated_from_clarify");
    }

    /**
     * `B3`：记录 archetype 回落时间线事件（**观测位，不阻断建任务**）。
     *
     * <p>事件语义 = 「本任务的交付物原型不是模型判出来的，是兜底的」。下游看时间线即可判断
     * 拆解维度是否可能错位（{@code BUILD} 与 {@code DOCUMENT} 的拆分维度不同），
     * 不必再去猜 {@code task_archetype} 那个值是判断还是兜底。</p>
     */
    private void recordArchetypeFallback(Long taskId, Long conversationId, TaskArchetypeResolver.Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("fallbackTo", decision.value());
        payload.put("reason", decision.fallbackReason());
        if (decision.rawValue() != null) {
            payload.put("rawValue", decision.rawValue());
        }
        log.warn("终稿未给出合法 archetype，回落 {}: taskId={}, conversationId={}, reason={}, raw={}",
                decision.value(), taskId, conversationId, decision.fallbackReason(), decision.rawValue());
        // 事件写失败不得让建任务回滚：整条链路是 fail-open，B3 只加可见性
        try {
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_ARCHETYPE_FALLBACK.code(),
                    AgentRole.PLANNER, null, payload);
        } catch (Exception e) {
            log.warn("记录 archetype 回落事件失败（不阻断建任务）: taskId={}, reason={}, err={}",
                    taskId, decision.fallbackReason(), e.getMessage());
        }
    }

    /**
     * 复用会话终稿创建 PENDING Task、best-effort 通知全部 PLANNER、回填 task_id 并写 timeline。
     * 状态由调用方提前推进（finalize / /task 直达走 CAS，regenerate 保持 FINALIZED）；
     * task_id 定点字段写入，避免全字段 updateById 用陈旧快照覆盖并发写入。
     *
     * <p>`B3`：{@code archetype} 的判定抽到 {@link TaskArchetypeResolver}（纯函数、可单测），
     * 本方法只负责「回落时记一条时间线事件」——回落允许，但不能不可见。</p>
     */
    private TaskView buildTaskFromDraft(RequirementConversation conversation, String timelineEvent) {
        Long conversationId = conversation.getId();
        // G-011 双写：会话终稿需求包原样进 task.context.requirementPackage（S3 拆解链经
        // RequirementPackageParser.fromContext 防御式读取；与 runningSpec 键空间隔离互不影响）。
        // TD-6：本方法同时写入 conversationId（拆解期上下文快照据此取会话附件清单）。
        // 注：context 因此恒非空——原「无需求包则不初始化 context」的口径随之取消；
        // 下游读取方一律防御式取值（缺键与 null 行为一致），故不改变既有语义。
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(TaskContextKeys.CONVERSATION_ID, conversationId);
        if (conversation.getFinalPackage() != null && !conversation.getFinalPackage().isEmpty()) {
            context.put(RequirementPackageParser.CONTEXT_KEY_REQUIREMENT_PACKAGE,
                    conversation.getFinalPackage());
        }
        // TD-4 / B3：终稿 package.archetype（六值）→ task.task_archetype；判不出 / 未给 / 非法
        // 一律回落 BUILD（与改造前语义一致，不做猜测性判定）。B3 只补「回落必须可见」。
        TaskArchetypeResolver.Decision archetypeDecision =
                TaskArchetypeResolver.resolve(conversation.getFinalPackage());
        String archetype = archetypeDecision.value();
        TaskView task = taskService.createFromDraft(new TaskDraft(conversation.getFinalTitle(),
                conversation.getFinalDescription(), TaskStatus.PENDING, context, archetype));
        log.info("澄清终稿建任务: conversationId={}, taskId={}, title={}, event={}, archetype={}, fallback={}",
                conversationId, task.id(), task.title(), timelineEvent, archetype,
                archetypeDecision.fallbackReason());
        if (archetypeDecision.fallback()) {
            recordArchetypeFallback(task.id(), conversationId, archetypeDecision);
        }

        // 照 TaskController.create 的通知段：best-effort 通知全部 PLANNER，失败不阻断
        try {
            List<AgentProfileSnapshot> planners = agentService.listProfilesByRole(AgentRole.PLANNER);
            String eventId = "task.create." + task.id() + "." + System.currentTimeMillis();
            for (AgentProfileSnapshot planner : planners) {
                agentInboxService.send(planner.id(), eventId, "task.created",
                        "新任务需要规划: " + task.title(),
                        task.description() != null ? task.description() : "请查看详情",
                        "task", task.id(), "HIGH");
            }
            log.info("已通知 {} 个 PLANNER Agent", planners.size());
        } catch (Exception e) {
            log.warn("澄清建任务后通知 PLANNER 失败: taskId={}", task.id(), e);
        }

        conversation.setTaskId(task.id());
        // 定点更新只写 task_id：全字段 updateById 会用加载期陈旧快照覆盖并发澄清轮
        // 已落库的终稿字段 / 轮数（丢失更新竞态），只写本路径负责的状态字段
        conversationService.lambdaUpdate()
                .eq(RequirementConversation::getId, conversationId)
                .set(RequirementConversation::getTaskId, task.id())
                .update();

        taskTimelineService.recordEvent(task.id(), null, timelineEvent,
                AgentRole.PLANNER, null, Map.of("conversationId", conversationId));
        // 会话摘要归档（N-009，C5-S2）：finalize 建任务后 best-effort 归档，失败不阻断
        archiveSessionMemory(conversationId);
        return task;
    }

    /**
     * 会话摘要归档（N-009，C5-S2）：finalize 建任务后 best-effort 归档，失败不阻断主链路。
     */
    private void archiveSessionMemory(Long conversationId) {
        try {
            RequirementConversation conv = conversationService.getById(conversationId);
            List<RequirementMessage> messages = messageService.listByConversation(conversationId);
            longTermMemoryService.archiveSession(conv, messages);
        } catch (Exception e) {
            log.warn("会话摘要归档失败（不阻断）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    @Override
    public void updateFinalDraftFields(Long conversationId, String title, String description,
                                       Map<String, Object> finalPackage) {
        String packageJson = null;
        if (finalPackage != null) {
            try {
                packageJson = objectMapper.writeValueAsString(finalPackage);
            } catch (Exception e) {
                log.warn("终稿需求包序列化失败，跳过 final_package 列写入: conversationId={}",
                        conversationId, e);
            }
        }
        requirementConversationMapper.updateFinalDraftFields(
                conversationId, title, description, packageJson);
    }
}
