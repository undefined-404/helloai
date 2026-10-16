package com.helloai.core.task.service.impl;

import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.UncertaintyView;

import java.util.List;

/**
 * {@code task.entity.SubTask} ⇄ 只读视图（{@link SubTaskView}）/ 写入物料（{@link SubTaskDraft}）的映射（task 域侧）。
 *
 * <p><b>为什么集中一处</b>：与 {@link SubTaskSnapshotMapper} 同款模式 —— 该快照映射器把「事件发布 + 只读查询」
 * 两份口径收敛到一处，本类把「视图投影 + 物料反投影」收敛到一处。RM9-b 拆分后视图投影有<b>三处</b>调用方
 * （只读查询簇 {@code SubTaskQueryService}、租约巡检簇 {@code SubTaskLeaseService#listReviewOrphanViews}、
 * 门面草稿写入 {@code SubTaskServiceImpl#saveDrafts}），集中一处可避免多份映射口径漂移。</p>
 *
 * <p><b>为什么必须独立成类（RM9-b 微调①）</b>：原两个映射方法是 {@code SubTaskServiceImpl} 的
 * {@code private static}；<b>Java 静态成员不可跨类继承</b>，新建的职责类无法复用，故随「主要调用方」
 * 外置为同包工具类（与 RM9-a「{@code parseInboxId} 随唯一调用方 {@code ack} 迁移」同族判据）。
 * 本类为<b>纯函数</b>（零状态、零依赖），不是 §9.2 所禁的「{@code FooHelper} 相互转发」。</p>
 *
 * <p><b>映射纪律</b>：只做逐字段投影，<b>不做业务判定</b>；{@code uncertainties} 逐项投影、null 元素跳过，
 * 空值语义与调用方原状保持一致（入参 null 返回 null）。</p>
 */
public final class SubTaskProjectionMapper {

    private SubTaskProjectionMapper() {
    }

    /**
     * 实体 → 只读快照（提供方映射，消费方零映射）。
     * 入参 {@code null} 返回 {@code null}（保持调用方原空值语义）；{@code uncertainties} 逐项投影、null 元素跳过。
     */
    public static SubTaskView toView(SubTask st) {
        if (st == null) {
            return null;
        }
        List<UncertaintyView> uncertainties = st.getUncertainties() == null ? List.of()
                : st.getUncertainties().stream()
                        .filter(u -> u != null)
                        .map(u -> new UncertaintyView(u.getKind(), u.getNote()))
                        .toList();
        return new SubTaskView(st.getId(), st.getTaskId(), st.getStatus(), st.getAssignedAgentId(),
                st.getReworkCount(), st.getTitle(), st.getContent(), st.getDeliverable(),
                st.getAcceptance(), st.getConstraints(), st.getContext(), uncertainties,
                st.dependsOnIdList(), st.getDeadline(), st.getPriority(), st.getIsContract(),
                st.getRequiredSkills(), st.getModuleId(), st.getScoreFactors(), st.getCompositeScore(),
                st.getScoreGrade(), st.getTimeoutCount(), st.getCreateTime(), st.getUpdateTime());
    }

    /** 写入物料 → 实体（提供方内部映射，消费方零实体依赖）；{@code uncertainties} 逐项投影、null 元素跳过。 */
    public static SubTask toEntity(SubTaskDraft d) {
        SubTask st = new SubTask();
        st.setTaskId(d.taskId());
        st.setTitle(d.title());
        st.setContent(d.content());
        st.setDeliverable(d.deliverable());
        st.setAcceptance(d.acceptance());
        st.setPriority(d.priority());
        st.setIsContract(d.isContract());
        st.setRequiredSkills(d.requiredSkills());
        st.setConstraints(d.constraints());
        st.setStatus(d.status());
        st.setContext(d.context());
        st.setUncertainties(d.uncertainties() == null ? null : d.uncertainties().stream()
                .filter(u -> u != null)
                .map(u -> {
                    Uncertainty entity = new Uncertainty();
                    entity.setKind(u.kind());
                    entity.setNote(u.note());
                    return entity;
                })
                .toList());
        return st;
    }
}
