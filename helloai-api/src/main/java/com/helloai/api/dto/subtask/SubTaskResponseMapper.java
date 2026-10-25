package com.helloai.api.dto.subtask;

import com.helloai.core.task.entity.SubTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link SubTask} → {@link SubTaskResponse} 的**唯一**映射点。
 *
 * <p><b>为什么单独成类</b>（2026-10-18）：此前映射是写在 {@code SubTaskController} 里的私有方法，
 * 逐字段手抄。它导致了 `LOG-20261018-018` 的线上表现——`sub_task` 表里
 * `composite_score / score_grade / score_factors` 一直有值，DTO 却没这三个字段，
 * 于是子任务列表「评分」列恒为 `-`，而详情页（走 review 接口）有分。
 * <b>编译器对这种「少搬一个字段」完全不报错</b>，只能靠结构约束兜住：</p>
 *
 * <ol>
 *   <li>映射集中到本类，{@code list / getById / listAvailable / listMine} 共用一处；</li>
 *   <li>{@link #NOT_EXPOSED} 是**显式不暴露清单**——实体新增字段时，
 *       {@link SubTaskResponseMapperContractTest} 会失败，强制开发者二选一：
 *       「映射出去」或「登记为不暴露」，不允许「悄悄漏掉」。</li>
 * </ol>
 *
 * <p><b>与「直接投影实体」的取舍</b>：响应不直接复用实体，是因为两者变化节奏不同——
 * 实体受库表与并发控制约束（`version` / `leaseUntil` / `owner` 属内部状态，
 * 对外暴露会被前端误当业务字段），而响应受 UI 契约约束。保持独立、用契约测试绑定，
 * 比「让实体兼任 DTO」更能表达意图。</p>
 */
public final class SubTaskResponseMapper {

    private SubTaskResponseMapper() {
    }

    /**
     * 实体字段名 → 响应字段名（**仅登记同名不同名的**；同名直搬不登记）。
     *
     * <p>两处不一致的由来：{@code assignedAgentId → assignedAgent} 是历史前端契约
     * （`assignedAgent` 早已是 API 字段名，改名会破坏外部 Agent 调用）；
     * {@code completeTime → completedAt} 同理（库列 `complete_time`，UI 字段 `completedAt`）。
     * 二者都不便再改，故在映射层吸收差异。</p>
     */
    public static final Map<String, String> RENAMED = Map.of(
            "assignedAgentId", "assignedAgent",
            "completeTime", "completedAt");

    /**
     * **明确不对外暴露**的实体字段 —— 每加一个实体字段，必须在此登记或映射出去。
     *
     * <p>分组说明：</p>
     * <ul>
     *   <li>审计/逻辑删除：{@code deleted / createBy / updateBy / remark}（{@code id / createTime / updateTime} 已映射）；</li>
     *   <li>并发控制：{@code version}（乐观锁，进跨域契约会诱导前端回传）；</li>
     *   <li>执行租约内部状态：{@code owner / leaseUntil}（Worker 崩溃恢复用，UI 无消费方）；</li>
     *   <li>重试与熔断计数：{@code externalFallbackCount / reassignAttemptCount / attemptTotal / lastAttemptTime}
     *       （调度侧决策依据，暴露会被误读成「进度」；{@code reassignAttemptCount} 已由 V64 停用仅留审计）；</li>
     *   <li>其余暂不消费：{@code timeoutCount / isContract}（如需在 UI 展示契约定义子任务，改映射并补用例）。</li>
     * </ul>
     */
    public static final Set<String> NOT_EXPOSED = Set.of(
            "deleted", "createBy", "updateBy", "remark",
            "version",
            "owner", "leaseUntil",
            "externalFallbackCount", "reassignAttemptCount", "attemptTotal", "lastAttemptTime",
            "timeoutCount", "isContract");

    /**
     * 映射（纯函数，无副作用）。
     *
     * <p>{@code taskTitle} / {@code assignedAgentName} 是**冗余展示字段**，
     * 由 Controller 批量回填（`attachTaskTitles` / `attachAgentNames`），此处不赋值。</p>
     */
    public static SubTaskResponse toResponse(SubTask subTask) {
        SubTaskResponse response = new SubTaskResponse();
        // 标识
        response.setId(subTask.getId());
        response.setTaskId(subTask.getTaskId());
        response.setModuleId(subTask.getModuleId());
        // 内容
        response.setTitle(subTask.getTitle());
        response.setDeliverable(subTask.getDeliverable());
        response.setAcceptance(subTask.getAcceptance());
        response.setAcceptanceKind(subTask.getAcceptanceKind());
        response.setContent(subTask.getContent());
        response.setPriority(subTask.getPriority());
        response.setStatus(subTask.getStatus());
        // 指派
        response.setAssignedAgent(subTask.getAssignedAgentId());
        response.setRequiredSkills(subTask.getRequiredSkills());
        response.setConstraints(subTask.getConstraints());
        response.setUncertainties(subTask.getUncertainties());
        // 执行上下文与依赖
        response.setContext(subTask.getContext());
        response.setDependsOn(subTask.dependsOnIdList());
        // 进度与时间
        response.setReworkCount(subTask.getReworkCount());
        response.setDeadline(subTask.getDeadline());
        response.setCompletedAt(subTask.getCompleteTime());
        response.setCreateTime(subTask.getCreateTime());
        response.setUpdateTime(subTask.getUpdateTime());
        // 评分三件套（2026-10-18 补：漏映射导致列表评分列恒空，见 LOG-20261018-018）
        response.setCompositeScore(subTask.getCompositeScore());
        response.setScoreGrade(subTask.getScoreGrade());
        response.setScoreFactors(subTask.getScoreFactors());
        return response;
    }

    /**
     * 响应字段名 → 实体字段名 的反查表（供契约测试与后续排查使用）。
     *
     * @return 不可变映射，仅含**改名**的字段
     */
    public static Map<String, String> responseToEntityField() {
        Map<String, String> reversed = new LinkedHashMap<>();
        RENAMED.forEach((entityField, responseField) -> reversed.put(responseField, entityField));
        return Map.copyOf(reversed);
    }
}
