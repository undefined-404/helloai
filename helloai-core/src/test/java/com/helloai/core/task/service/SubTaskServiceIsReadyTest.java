package com.helloai.core.task.service;

import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.service.impl.SubTaskLeaseServiceImpl;
import com.helloai.core.task.service.impl.SubTaskQueryServiceImpl;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubTaskService.isReady 依赖判定单元测试（内循环依赖编排）：
 * 空依赖直接就绪 / 全部 DONE 就绪 / 部分未 DONE 阻塞 / null 防御。
 *
 * <p><b>RM9-b 装配口径变更（断言语义未改）</b>：{@code isReady} / {@code listReviewOrphans}
 * 分别随只读簇与租约簇迁出，原先经门面 spy 拦截 MyBatis-Plus 链式 {@code lambdaQuery()}
 * 的做法改为直接对同域 {@code SubTaskMapper} 设桩 —— 被断言的行为（依赖未就绪不发查询 /
 * 人工介入标记剔除）与原先逐条一致。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskService.isReady")
class SubTaskServiceIsReadyTest {

    @Mock private SubTaskMapper subTaskMapper;
    @Mock private ObjectProvider<TaskService> taskServiceProvider;
    @Mock private ReviewPort reviewPort;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private AgentSessionService agentSessionService;

    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        SubTaskQueryService queryService = new SubTaskQueryServiceImpl(subTaskMapper, taskServiceProvider, reviewPort);
        SubTaskStateMachineService stateMachineService = mock(SubTaskStateMachineService.class);
        SubTaskLeaseService leaseService = new SubTaskLeaseServiceImpl(
                subTaskMapper, taskTimelineService, agentSessionService, stateMachineService);
        subTaskService = new SubTaskServiceImpl(
                subTaskMapper, queryService, stateMachineService, leaseService);
    }

    private SubTask withDeps(List<Long> deps) {
        SubTask subTask = new SubTask();
        subTask.setId(9L);
        subTask.setDependsOn(deps);
        return subTask;
    }

    @Test
    @DisplayName("null 子任务返回 false")
    void shouldReturnFalseForNull() {
        assertThat(subTaskService.isReady(null)).isFalse();
    }

    @Test
    @DisplayName("空/null 依赖直接就绪（旧数据行为不变），不发起 DB 查询")
    void shouldBeReadyWhenNoDependencies() {
        assertThat(subTaskService.isReady(withDeps(null))).isTrue();
        assertThat(subTaskService.isReady(withDeps(List.of()))).isTrue();
        verify(subTaskMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("依赖全部 DONE 时就绪")
    void shouldBeReadyWhenAllDependenciesDone() {
        when(subTaskMapper.selectCount(any())).thenReturn(2L);
        assertThat(subTaskService.isReady(withDeps(List.of(1L, 2L)))).isTrue();
    }

    @Test
    @DisplayName("依赖部分未 DONE 时阻塞")
    void shouldNotBeReadyWhenSomeDependencyNotDone() {
        when(subTaskMapper.selectCount(any())).thenReturn(1L);
        assertThat(subTaskService.isReady(withDeps(List.of(1L, 2L)))).isFalse();
    }

    @Test
    @DisplayName("dependsOnIdList：Jackson 反序列化 Integer 归一化为 Long")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldNormalizeIntegerIdsToLong() {
        SubTask subTask = new SubTask();
        // 模拟 JacksonTypeHandler 反序列化产物：List 内实际是 Integer
        subTask.setDependsOn((List) List.of(1, 2));
        assertThat(subTask.dependsOnIdList()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("dependsOnIdList：历史字符串 id（全局 Long→String 序列化遗留）归一化为 Long")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldNormalizeStringIdsToLong() {
        SubTask subTask = new SubTask();
        // 模拟旧数据：updateDependsOn 曾经用带 ToStringSerializer 的全局
        // ObjectMapper 写库，depends_on 存成字符串数组
        subTask.setDependsOn((List) List.of("2082308539519516674", "5"));
        assertThat(subTask.dependsOnIdList()).containsExactly(2082308539519516674L, 5L);
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.52 孤儿扫描：返工达上限卡死任务必须能被兜底发现
    //  ══════════════════════════════════════════════════════════════

    private SubTask reviewTask(Long id, Map<String, Object> context) {
        SubTask subTask = new SubTask();
        subTask.setId(id);
        subTask.setContext(context);
        return subTask;
    }

    @Test
    @DisplayName("§6.52: 有 review_record 但无人工介入标记的 REVIEW 任务不被排除（返工上限卡死可被兜底）")
    void shouldKeepReviewTaskWithReviewRecordButNoManualMark() {
        // 返工达上限的卡死任务：context 仅含历史核验意见，无 manualIntervention
        SubTask stuck = reviewTask(11L, Map.of("lastAutoReview", Map.of("round", 3)));
        // 已人工介入的任务：应被排除
        SubTask intervened = reviewTask(12L, Map.of("manualIntervention", Map.of("reason", "rework_limit")));
        // 可变列表：listReviewOrphans 内部会 removeIf
        when(subTaskMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(stuck, intervened)));

        List<SubTask> orphans = subTaskService.listReviewOrphans(60, 10);

        assertThat(orphans).extracting(SubTask::getId).containsExactly(11L);
    }

    @Test
    @DisplayName("§6.52: 无人工介入标记的纯孤儿 REVIEW 任务正常保留")
    void shouldKeepPlainOrphanReview() {
        SubTask plain = reviewTask(13L, null);
        // 可变列表：listReviewOrphans 内部会 removeIf
        when(subTaskMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(plain)));

        List<SubTask> orphans = subTaskService.listReviewOrphans(60, 10);

        assertThat(orphans).extracting(SubTask::getId).containsExactly(13L);
    }
}
