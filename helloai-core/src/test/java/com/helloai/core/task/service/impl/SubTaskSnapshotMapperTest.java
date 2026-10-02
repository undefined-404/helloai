package com.helloai.core.task.service.impl;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.UncertaintySnapshot;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SubTaskSnapshotMapper} 单测（2026-10-01 W7 新建）。
 *
 * <p>本类此前只被 {@code SubTaskQueryPortAdapter} 间接覆盖（字段透传）。W7 把快照扩为
 * <b>全量读投影</b>并新增不确定性投影后，映射本身成为独立且易错的逻辑，故补专属单测：</p>
 * <ul>
 *     <li>字段全量透传（含 W7 新增的内容 / 交付物 / 验收标准 / 约束 / 优先级 / 契约位 /
 *         截止时间 / 返工计数 / 尝试次数 / 版本 / 依赖）；</li>
 *     <li>空值边界：{@code null} 入参 → {@code null}；批量入参与不确定性列表
 *         <b>绝不返回 {@code null}</b>，且逐条 {@code null} 元素跳过；</li>
 *     <li><b>映射不做业务判定</b>：{@code kind} 原样透传，不做归一化/校验
 *         （校验责任在 task 域拆解落库侧，单源语义不得复制）；</li>
 *     <li><b>两处提供方派生</b>（W7 {@code dependsOn} Long 归一化；W11 {@code uncertainty.assumption}
 *         —— 由 task 域常量 {@code Uncertainty.KIND_ASSUMPTION} 判定后透传，避免消费方复制常量）。</li>
 * </ul>
 */
@DisplayName("SubTaskSnapshotMapper 实体 → agent 域快照")
class SubTaskSnapshotMapperTest {

    @Test
    @DisplayName("toSnapshot：null 入参返回 null（保持调用方原空值语义）")
    void shouldReturnNullForNullEntity() {
        assertThat(SubTaskSnapshotMapper.toSnapshot(null)).isNull();
    }

    @Test
    @DisplayName("toSnapshot：字段全量透传（含 W7 新增的全量读投影字段）")
    void shouldMapAllFields() {
        SubTask subTask = new SubTask();
        subTask.setId(7L);
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setTaskId(100L);
        subTask.setAssignedAgentId(9L);
        subTask.setContext(Map.of("k", "v"));
        subTask.setTitle("接口契约");
        subTask.setContent("做什么与边界");
        subTask.setDeliverable("OpenAPI 文档");
        subTask.setAcceptance("字段齐备且可编译");
        subTask.setConstraints("不得改动既有签名");
        subTask.setPriority("HIGH");
        subTask.setIsContract(1);
        OffsetDateTime deadline = OffsetDateTime.now();
        subTask.setDeadline(deadline);
        subTask.setReworkCount(2);
        subTask.setAttemptTotal(3);
        subTask.setVersion(5);
        subTask.setDependsOn(List.of(11L, 12L));

        SubTaskSnapshot snapshot = SubTaskSnapshotMapper.toSnapshot(subTask);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.id()).isEqualTo(7L);
        assertThat(snapshot.status()).isEqualTo(SubTaskStatus.REWORK);
        assertThat(snapshot.taskId()).isEqualTo(100L);
        assertThat(snapshot.assignedAgentId()).isEqualTo(9L);
        assertThat(snapshot.context()).containsEntry("k", "v");
        assertThat(snapshot.title()).isEqualTo("接口契约");
        assertThat(snapshot.content()).isEqualTo("做什么与边界");
        assertThat(snapshot.deliverable()).isEqualTo("OpenAPI 文档");
        assertThat(snapshot.acceptance()).isEqualTo("字段齐备且可编译");
        assertThat(snapshot.constraints()).isEqualTo("不得改动既有签名");
        assertThat(snapshot.priority()).isEqualTo("HIGH");
        assertThat(snapshot.isContract()).isEqualTo(1);
        assertThat(snapshot.deadline()).isEqualTo(deadline);
        assertThat(snapshot.reworkCount()).isEqualTo(2);
        assertThat(snapshot.attemptTotal()).isEqualTo(3);
        assertThat(snapshot.version()).isEqualTo(5);
        assertThat(snapshot.dependsOn()).containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("toSnapshot：dependsOn 取 dependsOnIdList 结果（空依赖收敛为空列表而非 null）")
    void shouldNormalizeEmptyDependsOn() {
        SubTask subTask = new SubTask();
        subTask.setId(1L);

        SubTaskSnapshot snapshot = SubTaskSnapshotMapper.toSnapshot(subTask);

        assertThat(snapshot.dependsOn()).isNotNull().isEmpty();
        assertThat(snapshot.uncertainties()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("toUncertaintySnapshots：null / 空入参返回空列表，绝不返回 null")
    void shouldReturnEmptyUncertaintiesForBlankInput() {
        assertThat(SubTaskSnapshotMapper.toUncertaintySnapshots(null)).isEmpty();
        assertThat(SubTaskSnapshotMapper.toUncertaintySnapshots(List.of())).isEmpty();
    }

    @Test
    @DisplayName("toUncertaintySnapshots：kind/note 原样透传，不做归一化（单源语义留 task 域）")
    void shouldPassThroughUncertaintyVerbatim() {
        List<Uncertainty> source = List.of(
                new Uncertainty(Uncertainty.KIND_UNCONFIRMED, "存量调用方是否受影响未确认"),
                new Uncertainty("weird_kind_未归一化", "note"));

        List<UncertaintySnapshot> snapshots = SubTaskSnapshotMapper.toUncertaintySnapshots(source);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).kind()).isEqualTo("UNCONFIRMED");
        assertThat(snapshots.get(0).note()).isEqualTo("存量调用方是否受影响未确认");
        // 非法 kind 原样透传 —— 本层不做 fail-close 降级（那是 task 域拆解落库侧的职责）
        assertThat(snapshots.get(1).kind()).isEqualTo("weird_kind_未归一化");
    }

    @Test
    @DisplayName("toUncertaintySnapshots：W11 派生字段 assumption 由 task 域常量判定后透传")
    void shouldDeriveAssumptionFlag() {
        List<Uncertainty> source = List.of(
                new Uncertainty(Uncertainty.KIND_ASSUMPTION, "假设可用"),
                new Uncertainty(Uncertainty.KIND_UNCONFIRMED, "接口待确认"),
                new Uncertainty("weird_kind_未归一化", "note"));

        List<UncertaintySnapshot> snapshots = SubTaskSnapshotMapper.toUncertaintySnapshots(source);

        assertThat(snapshots.get(0).assumption()).isTrue();
        assertThat(snapshots.get(1).assumption()).isFalse();
        // 非 ASSUMPTION 一律 false —— 消费方无需（也不得）自行比较 kind 字符串
        assertThat(snapshots.get(2).assumption()).isFalse();
    }

    @Test
    @DisplayName("toUncertaintySnapshots：列表含 null 元素时跳过而不抛异常")
    void shouldSkipNullUncertaintyElements() {
        List<Uncertainty> source = new ArrayList<>();
        source.add(null);
        source.add(new Uncertainty(Uncertainty.KIND_ASSUMPTION, "假设可自证"));

        List<UncertaintySnapshot> snapshots = SubTaskSnapshotMapper.toUncertaintySnapshots(source);

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).kind()).isEqualTo("ASSUMPTION");
    }

    @Test
    @DisplayName("toSnapshots：null / 空入参返回空列表，逐条 null 元素跳过")
    void shouldMapBatchWithNullTolerance() {
        assertThat(SubTaskSnapshotMapper.toSnapshots(null)).isEmpty();
        assertThat(SubTaskSnapshotMapper.toSnapshots(List.of())).isEmpty();

        List<SubTask> source = new ArrayList<>();
        source.add(null);
        SubTask subTask = new SubTask();
        subTask.setId(3L);
        source.add(subTask);

        List<SubTaskSnapshot> snapshots = SubTaskSnapshotMapper.toSnapshots(source);

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).id()).isEqualTo(3L);
    }
}
