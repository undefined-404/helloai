package com.helloai.core.planner.support;

import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.task.port.ConstraintRefDraft;
import com.helloai.core.task.port.ConstraintRefView;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.port.UncertaintyView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * `AD-5a-3b-2b`：暂存区视图 → 门禁/物化使用的 {@link SubTaskView} 形状。
 *
 * <p><b>id 用暂存区 id</b>：停止写执行表副本后，草案没有 {@code sub_task.id}；
 * 门禁的依赖图需要一组唯一 id，暂存区 id 天然满足，且 `ref` 仍由 code 承担。</p>
 */
public final class PlanDraftViewMapper {

    private PlanDraftViewMapper() {
    }

    /** 暂存区实体 → 只读视图（唯一映射点，读服务与读侧回落共用）。 */
    public static PlanDraftView toView(PlanDraft d) {
        return new PlanDraftView(
                d.getId(), d.getTaskId(), d.getCode(), d.getRound(), d.getDraftSeq(),
                d.getTitle(), d.getContent(), d.getDeliverable(), d.getAcceptance(),
                d.getAcceptanceKind(), d.getPriority(), d.getContract(),
                d.getRequiredSkills(), d.getConstraints(), d.getUncertainties(),
                d.getDependsOnCodes(), d.getContext(), d.getUpdateTime(), toConstraintRefDrafts(d.getConstraintRefs()));
    }

    /** 暂存区实体（{@code task.entity}）→ 写入物料形状（task.port）。 */
    private static List<ConstraintRefDraft> toConstraintRefDrafts(
            List<com.helloai.core.task.entity.ConstraintRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<ConstraintRefDraft> drafts = new ArrayList<>(refs.size());
        for (com.helloai.core.task.entity.ConstraintRef r : refs) {
            if (r != null) {
                drafts.add(new ConstraintRefDraft(r.getKind(), r.getRef(), r.getScope()));
            }
        }
        return drafts;
    }

    public static List<SubTaskView> toSubTaskViews(List<PlanDraftView> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        Map<String, Long> codeToId = new HashMap<>();
        for (PlanDraftView d : drafts) {
            if (d.code() != null && d.id() != null) {
                codeToId.put(d.code(), d.id());
            }
        }
        List<SubTaskView> views = new ArrayList<>(drafts.size());
        for (PlanDraftView d : drafts) {
            List<Long> deps = List.of();
            if (d.dependsOnCodes() != null) {
                deps = d.dependsOnCodes().stream().map(codeToId::get)
                        .filter(Objects::nonNull).toList();
            }
            views.add(new SubTaskView(
                    d.id(), d.taskId(), com.helloai.common.constant.SubTaskStatus.PENDING,
                    null, 0, d.title(), d.content(), d.deliverable(), d.acceptance(),
                    d.acceptanceKind(), d.constraints(), d.context(), toUncertaintyViews(d.uncertainties()),
                    deps, null, d.priority() == null ? null : d.priority().name(),
                    d.contract(), d.requiredSkills(), null, null, null, null,
                    0, null, d.updateTime(), d.code(), toConstraintRefViews(d.constraintRefs())));
        }
        return views;
    }

    private static List<ConstraintRefView> toConstraintRefViews(List<ConstraintRefDraft> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<ConstraintRefView> views = new ArrayList<>(refs.size());
        for (ConstraintRefDraft r : refs) {
            if (r != null) {
                views.add(new ConstraintRefView(r.kind(), r.ref(), r.scope()));
            }
        }
        return views;
    }

    private static List<UncertaintyView> toUncertaintyViews(List<UncertaintyDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<UncertaintyView> views = new ArrayList<>(drafts.size());
        for (UncertaintyDraft u : drafts) {
            if (u != null) {
                views.add(new UncertaintyView(u.kind(), u.note(), u.ref(), u.blockedBy()));
            }
        }
        return views;
    }
}