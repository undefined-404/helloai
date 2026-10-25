package com.helloai.core.task.projection;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.port.UncertaintyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubTaskProjection} 的字段保真测试（`TD-11` 补建）。
 *
 * <p><b>为什么补这个类</b>：2026-10-25 真机实测发现 {@code ref} 在 {@code toEntity} 里没被映射，
 * 于是 {@code sub_task.uncertainties} 的 JSONB 里 {@code ref} 全是 {@code null}，而
 * {@code context.acceptancePreconditions} 已经是 {@code ["U3"]}——引用指着空。
 * 当时单测全绿、编译通过，因为**没有任何测试覆盖这条投影**，而拆解侧的单测用 mock 的
 * {@code saveDrafts} 只断言内存对象、看不见实体映射。</p>
 *
 * <p>判据：<b>凡新增跨层字段，必须验「写入→实体→再读出」闭环</b>，不能只测写入侧拿到的对象。</p>
 */
class SubTaskProjectionTest {

    private static SubTaskDraft draftWithRef(String ref) {
        return new SubTaskDraft(
                1L, "竞品 A 事实采集", "content", "deliverable", "acceptance",
                "MEDIUM", 0, List.of(), null, SubTaskStatus.PENDING_PLAN_REVIEW,
                Map.of(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS, List.of("U1")),
                List.of(new UncertaintyDraft(UncertaintyDraft.KIND_UNCONFIRMED, "竞品 A 对应哪款真实产品待拍板", ref)),
                AcceptanceKind.REVIEWED);
    }

    @Test
    @DisplayName("TD-11：toEntity 必须把 uncertainties 的 ref 落进实体（否则 JSONB 里恒为 null）")
    void shouldCarryRefIntoEntity() {
        SubTask entity = SubTaskProjection.toEntity(draftWithRef("U1"));

        assertThat(entity.getUncertainties()).hasSize(1);
        Uncertainty saved = entity.getUncertainties().get(0);
        assertThat(saved.getKind()).isEqualTo(UncertaintyDraft.KIND_UNCONFIRMED);
        assertThat(saved.getNote()).isEqualTo("竞品 A 对应哪款真实产品待拍板");
        assertThat(saved.getRef()).isEqualTo("U1");
    }

    @Test
    @DisplayName("TD-11：toView 必须把实体的 ref 带回快照（核验侧靠它定位缺口原文）")
    void shouldCarryRefIntoView() {
        SubTask entity = SubTaskProjection.toEntity(draftWithRef("U2"));

        SubTaskView view = SubTaskProjection.toView(entity);

        assertThat(view.uncertainties()).hasSize(1);
        UncertaintyView u = view.uncertainties().get(0);
        assertThat(u.ref()).isEqualTo("U2");
        assertThat(u.note()).isEqualTo("竞品 A 对应哪款真实产品待拍板");
    }

    @Test
    @DisplayName("TD-11 闭环：draft → entity → view，ref 与 acceptancePreconditions 同时保真")
    void shouldPreserveRefAndPreconditionsRoundTrip() {
        SubTaskView view = SubTaskProjection.toView(SubTaskProjection.toEntity(draftWithRef("U1")));

        assertThat(view.context().get(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS))
                .isEqualTo(List.of("U1"));
        // 引用与缺口必须能在同一份快照里对上（这正是 TD-12 的取数前提）
        assertThat(view.uncertainties()).extracting(UncertaintyView::ref).containsExactly("U1");
    }

    @Test
    @DisplayName("存量兼容：ref 为 null 的老数据投影不抛异常，也不制造假引用")
    void shouldTolerateNullRef() {
        SubTask entity = SubTaskProjection.toEntity(draftWithRef(null));

        assertThat(entity.getUncertainties().get(0).getRef()).isNull();
        assertThat(SubTaskProjection.toView(entity).uncertainties().get(0).ref()).isNull();
    }
}
