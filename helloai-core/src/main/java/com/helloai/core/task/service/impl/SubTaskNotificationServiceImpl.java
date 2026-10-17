package com.helloai.core.task.service.impl;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.shared.event.SubTaskAssignedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.port.ReviewSummary;
import com.helloai.core.task.service.SubTaskNotificationService;
import com.helloai.core.task.service.SubTaskQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 子任务<b>出站副作用面</b>职责类（RM9-b 切分线 ②「再抽副作用簇」）：
 * 收件箱投递 + 摘要渲染 + 领域事件发布。
 *
 * <p><b>为什么单独成类</b>：这一簇是<b>纯出站</b>——只把已定稿的实体状态渲染成对外可见的
 * 文本/事件，<b>不参与任何状态判定</b>，也从不写库。原类里它与状态机耦合在同一份 1592 行文件中，
 * 但两者的可测面完全不同（e-mail 摘要渲染 vs 事务内状态写入），拆开后通知簇可独立单测。</p>
 *
 * <p><b>best-effort 纪律（原样保留）</b>：全部投递口失败只 {@code warn}/{@code error} 日志、
 * <b>绝不向上抛</b>——返工、完成、阻塞等主链路的优先级高于通知，与既有行为逐字一致。</p>
 *
 * <p><b>依赖方向（无环）</b>：本类 → {@link SubTaskQueryService}（仅为 {@code mergeSkills}
 * 取 G-010 下行技能标签），方向单向；状态机类 → 本类。全链
 * {@code 门面 → 状态机 → 通知 → 查询} 为有向无环图，满足 §4.3.2 验收口径 (i)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskNotificationServiceImpl implements SubTaskNotificationService {

    private final AgentInboxService agentInboxService;
    /** 懒解析打破循环：AgentServiceImpl 依赖 SubTaskService（阶段五级联清理收口）。 */
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ReviewPort reviewPort;
    /** 技能标签下行通道（G-010）复用查询簇口径，避免两份合并规则漂移。 */
    private final SubTaskQueryService subTaskQueryService;

    // ══════════════════════════════════════════════════════════════
    //  收件箱投递（状态变更后的统一出站口）
    // ══════════════════════════════════════════════════════════════

    /**
     * 向相关 Agent 投递收件箱通知（按新状态分派）。整段 try-catch：通知失败不影响主流程。
     */
    @Override
    public void sendInboxNotification(SubTask subTask, SubTaskStatus newStatus, SubTaskStatus oldStatus) {
        String eventId = "subtask." + subTask.getId() + "." + System.currentTimeMillis();
        Long agentId = subTask.getAssignedAgentId();
        String title = subTask.getTitle();

        try {
            switch (newStatus) {
                case ASSIGNED -> {
                    if (agentId != null) {
                        // G-010 下行通道：合并技能标签随通知摘要下发（文本形态，不动 inbox 契约面）；
                        // 外部 agent 据此按需装配自身技能包，未升级的 agent 对该行无感
                        List<String> mergedSkills = subTaskQueryService.mergeSkills(subTask);
                        String skillLine = mergedSkills.isEmpty() ? ""
                                : "\n技能要求: " + String.join(", ", mergedSkills);
                        // P0 验收标准下发：摘要不再只是「交付物」——审查侧按 acceptance 逐条核验
                        // （subtask-review 轨道 A），执行者必须从收件箱即可看到验收标准与硬边界；
                        // 纯文本形态与技能要求行同款，不动 inbox 契约面
                        String acceptanceLine = "\n验收标准: "
                                + (subTask.getAcceptance() != null && !subTask.getAcceptance().isBlank()
                                        ? subTask.getAcceptance()
                                        : "（未填写，开工前请调用 getSubTaskDetail 核对）");
                        String constraintsLine = subTask.getConstraints() != null && !subTask.getConstraints().isBlank()
                                ? "\n执行约束（不许改的事）: " + subTask.getConstraints() : "";
                        // G-011 D6 升级：uncertainties 逐条下发正文（此前只给「待确认: N 项」计数，
                        // 执行者不知道要验证什么）；空/全空白零注入
                        String uncertaintyLine = buildUncertaintyLines(subTask.getUncertainties());
                        agentInboxService.send(agentId, eventId, "sub_task.assigned",
                                "新任务已分配: " + title,
                                "交付物: " + (subTask.getDeliverable() != null ? subTask.getDeliverable() : "待确认")
                                        + acceptanceLine + constraintsLine + skillLine + uncertaintyLine
                                        + "\n完整执行内容与边界请调用 getSubTaskDetail 查看",
                                "sub_task", subTask.getId(), "HIGH");
                    }
                }
                case REWORK -> {
                    if (agentId != null) {
                        agentInboxService.send(agentId, eventId, "sub_task.rejected",
                                "任务需返工: " + title,
                                "请查审查记录了解具体问题",
                                "sub_task", subTask.getId(), "HIGH");
                    }
                }
                case BLOCKED -> {
                    String reason = null;
                    if (subTask.getContext() != null && subTask.getContext().get("blockedReason") instanceof String r) {
                        reason = r;
                    }
                    String summary = reason != null && !reason.isBlank()
                            ? ("阻塞原因: " + reason)
                            : "需要 Planner 排障处理";
                    AgentService agentService = agentServiceProvider.getIfAvailable();
                    List<AgentProfileSnapshot> planners = agentService == null
                            ? List.of() : agentService.listProfilesByRole(AgentRole.PLANNER);
                    for (AgentProfileSnapshot planner : planners) {
                        agentInboxService.send(planner.id(), eventId, "sub_task.blocked",
                                "任务阻塞: " + title,
                                summary,
                                "sub_task", subTask.getId(), "URGENT");
                    }
                }
                case PAUSED -> {
                    if (agentId != null) {
                        agentInboxService.send(agentId, eventId, "sub_task.paused",
                                "任务已暂停: " + title,
                                "请保存当前进度，等待恢复通知",
                                "sub_task", subTask.getId(), "HIGH");
                    }
                }
                case REVIEW -> {
                    // 修复: EXECUTOR 提交后，通知所有 PLANNER/REVIEWER 来审查
                    try {
                        AgentService agentService = agentServiceProvider.getIfAvailable();
                        List<AgentProfileSnapshot> planners = agentService == null
                                ? List.of() : agentService.listProfilesByRole(AgentRole.PLANNER);
                        for (AgentProfileSnapshot planner : planners) {
                            agentInboxService.send(planner.id(), eventId, "sub_task.review",
                                    "任务已提交审查: " + title,
                                    "请 PLANNER/REVIEWER 评分并完成审查",
                                    "sub_task", subTask.getId(), "HIGH");
                        }
                        log.info("REVIEW 通知发送: subtaskId={}, planners={}", subTask.getId(), planners.size());
                    } catch (Exception e) {
                        log.error("REVIEW 通知发送失败: subtaskId={}", subTask.getId(), e);
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            log.error("收件箱通知发送失败: subTaskId={}, status={}", subTask.getId(), newStatus, e);
            // 通知失败不影响主流程
        }
    }

    /**
     * 撤销通知：旧执行者收不到「任务已转移」事件时，会误以为任务仍在名下继续干活
     * （trae 1925 冷启动白做）。
     *
     * <p>在换人（旧执行者 A → 新执行者 B）或回收（执行者被清空）时，向旧执行者补发
     * {@code sub_task.reassigned} / {@code sub_task.unassigned} 收件箱通知：
     * 换人 → 「已改派给其他 Agent，立即停止执行」；回收 → 「已从你名下回收，立即停止执行」。
     * 初始分配（{@code oldAgentId == null}）与原地保留（old == new）不通知。
     * API_KEY_LLM 旧执行者由 {@link AgentInboxService#send} 内部守卫跳过（其消费链走 outbox→MQ）。</p>
     */
    @Override
    public void notifyAgentHandover(SubTask subTask, Long oldAgentId) {
        Long newAgentId = subTask.getAssignedAgentId();
        if (oldAgentId == null || oldAgentId.equals(newAgentId)) {
            return;
        }
        String eventId = "subtask." + subTask.getId() + ".handover." + System.currentTimeMillis();
        String title = subTask.getTitle();
        if (newAgentId != null) {
            agentInboxService.send(oldAgentId, eventId, "sub_task.reassigned",
                    "任务已改派: " + title,
                    "该任务已改派给其他 Agent，请立即停止执行并等待新任务",
                    "sub_task", subTask.getId(), "HIGH");
        } else {
            agentInboxService.send(oldAgentId, eventId, "sub_task.unassigned",
                    "任务已回收: " + title,
                    "该任务已从你名下回收，请立即停止执行并等待新任务",
                    "sub_task", subTask.getId(), "HIGH");
        }
    }

    /** 领域事件：仅在落点 {@code ASSIGNED} 且有执行者时发布（与既有口径逐字一致）。 */
    @Override
    public void publishAssignmentEvent(SubTask subTask, SubTaskStatus newStatus) {
        if (newStatus != SubTaskStatus.ASSIGNED || subTask.getAssignedAgentId() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(
                new SubTaskAssignedEvent(subTask.getId(), subTask.getAssignedAgentId()));
    }

    // ══════════════════════════════════════════════════════════════
    //  驳回 / 通过 补发通知与摘要渲染
    // ══════════════════════════════════════════════════════════════

    /**
     * 驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工驳回 rework/reworkFresh 共用），
     * 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因。
     * 发送失败只 warn 不阻断（返工主链路优先）。
     */
    @Override
    public void sendReworkInboxNotification(SubTask subTask) {
        try {
            Long agentId = subTask.getAssignedAgentId();
            if (agentId == null) {
                return;
            }
            String eventId = "subtask." + subTask.getId() + ".rejected." + System.currentTimeMillis();
            agentInboxService.send(agentId, eventId, "sub_task.rejected",
                    "任务需返工: " + subTask.getTitle(),
                    buildReworkSummary(subTask),
                    "sub_task", subTask.getId(), "HIGH");
        } catch (Exception e) {
            log.warn("驳回收件箱通知发送失败（不阻断返工）: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
    }

    /**
     * 从 {@code context.reviewHistory} 最新一轮（格式 {@code {round,ts,reviewerAgentId,issues,comment,score,...}}）
     * 提取驳回摘要；无历史（如人工直接驳回）时回退默认文案。
     */
    @SuppressWarnings("unchecked")
    public String buildReworkSummary(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx != null && ctx.get("reviewHistory") instanceof List<?> history && !history.isEmpty()) {
            Object last = history.get(history.size() - 1);
            if (last instanceof Map<?, ?> m) {
                StringBuilder sb = new StringBuilder("审查未通过");
                Object score = m.get("score");
                if (score instanceof Number n) {
                    sb.append("，评分 ").append(n.intValue()).append("/5");
                }
                Object comment = m.get("comment");
                if (comment instanceof String c && !c.isBlank()) {
                    sb.append("；评语: ").append(clip(c, 150));
                }
                Object issues = m.get("issues");
                if (issues instanceof List<?> issueList && !issueList.isEmpty()) {
                    String joined = issueList.stream().map(Object::toString).collect(Collectors.joining("、"));
                    sb.append("；问题: ").append(clip(joined, 150));
                } else if (issues instanceof String issueStr && !issueStr.isBlank()) {
                    sb.append("；问题: ").append(clip(issueStr, 150));
                }
                Object missingEvidence = m.get("missingEvidence");
                if (missingEvidence instanceof List<?> evidenceList && !evidenceList.isEmpty()) {
                    String joined = evidenceList.stream()
                            .map(SubTaskNotificationServiceImpl::formatMissingEvidence)
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.joining("；"));
                    if (!joined.isBlank()) {
                        sb.append("；缺失证据清单: ").append(clip(joined, 300));
                    }
                }
                return sb.toString();
            }
        }
        return "请查审查记录了解具体问题";
    }

    /**
     * 渲染单条缺失证据（P2-4）：引用验收条目原文子串 + 缺什么证据 + 怎样补齐。
     * 键缺失/非文本一律跳过（与 {@code VerdictParser.normalizeMissingEvidence} 同源的防御口径）。
     */
    private static String formatMissingEvidence(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Object acceptanceRef = m.get("acceptanceRef");
        if (acceptanceRef instanceof String ref && !ref.isBlank()) {
            sb.append("「").append(ref).append("」");
        }
        Object missing = m.get("missing");
        if (missing instanceof String missingStr && !missingStr.isBlank()) {
            sb.append("缺: ").append(missingStr);
        }
        Object howTo = m.get("howTo");
        if (howTo instanceof String howToStr && !howToStr.isBlank()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("补齐: ").append(howToStr);
        }
        return sb.toString();
    }

    /**
     * 审查通过补发收件箱通知（携带最新一轮 review 评分/评语），
     * 外部 Agent 轮询 pullTasks 即可感知交付结果反馈。发送失败只 warn 不阻断。
     */
    @Override
    public void sendApprovedInboxNotification(SubTask subTask) {
        try {
            Long agentId = subTask.getAssignedAgentId();
            if (agentId == null) {
                return;
            }
            String eventId = "subtask." + subTask.getId() + ".approved." + System.currentTimeMillis();
            agentInboxService.send(agentId, eventId, "sub_task.approved",
                    "任务审查通过: " + subTask.getTitle(),
                    buildApprovedSummary(subTask),
                    "sub_task", subTask.getId(), "NORMAL");
        } catch (Exception e) {
            log.warn("审查通过收件箱通知发送失败（不阻断完成）: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
    }

    /** 从 {@code review_record} 取最新一轮（round 最大）的评分/评语拼摘要；无记录时回退默认文案。 */
    @Override
    public String buildApprovedSummary(SubTask subTask) {
        try {
            // §6.146：最新一轮评分/评语经 ReviewPort 摘要收口，不直捅 review 域
            ReviewSummary latest = reviewPort.latestReviewSummary(subTask.getId());
            if (latest != null) {
                StringBuilder sb = new StringBuilder("审查通过");
                if (latest.score() != null) {
                    sb.append("，评分 ").append(latest.score()).append("/5");
                }
                if (latest.comment() != null && !latest.comment().isBlank()) {
                    sb.append("；评语: ").append(clip(latest.comment(), 150));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("审查通过摘要读取失败，回退默认: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
        return "审查通过，请查看详情";
    }

    private String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    /**
     * 不确定性申报下行渲染（P0 增量）：逐条「[kind] note + 分级后缀」正文，替代此前的
     * 「待确认: N 项」计数——计数无法告诉执行者要验证什么。后缀语义与执行侧注入同源
     * （{@code AgentRuntimeContextAssembler#buildUserPrompt}）：ASSUMPTION 可自行验证 / 推翻；
     * 其余（含人工编辑引入的非法值）一律按 UNCONFIRMED 的 fail-close 语义处理。
     *
     * @return 每条前缀换行；空列表 / 全空白返回空串（零注入，存量行为不变）
     */
    @Override
    public String buildUncertaintyLines(List<Uncertainty> uncertainties) {
        if (uncertainties == null || uncertainties.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Uncertainty uncertainty : uncertainties) {
            if (uncertainty == null || uncertainty.getNote() == null || uncertainty.getNote().isBlank()) {
                continue;
            }
            String suffix = Uncertainty.KIND_ASSUMPTION.equals(uncertainty.getKind())
                    ? "（可自行验证，推翻即上报）"
                    : "（须先验证再动手，无法验证则 reportBlocked 上报）";
            sb.append("\n- [").append(uncertainty.getKind()).append("] ")
                    .append(uncertainty.getNote()).append(suffix);
        }
        return sb.toString();
    }
}
