package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * SubTaskService.isReady 依赖判定单元测试（V27 内循环依赖编排）：
 * 空依赖直接就绪 / 全部 DONE 就绪 / 部分未 DONE 阻塞 / null 防御。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskService.isReady")
class SubTaskServiceIsReadyTest {

    private SubTaskService subTaskService;

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> queryChain = mock(LambdaQueryChainWrapper.class);

    @BeforeEach
    void setUp() {
        SubTaskService real = new SubTaskService(
                mock(AgentOutboxService.class), mock(AgentInboxService.class),
                mock(AgentService.class), mock(HeartbeatService.class),
                mock(ReviewRecordMapper.class), mock(ImplicitScoreCalculator.class),
                mock(RewardService.class), mock(ApplicationEventPublisher.class),
                mock(TaskTimelineService.class));
        subTaskService = spy(real);
        // lambdaQuery 链式 mock：绕开无 Spring 上下文时的 baseMapper 依赖
        lenient().doReturn(queryChain).when(subTaskService).lambdaQuery();
        lenient().when(queryChain.in(any(), any(List.class))).thenReturn(queryChain);
        lenient().when(queryChain.eq(any(), any())).thenReturn(queryChain);
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
        verify(subTaskService, never()).lambdaQuery();
    }

    @Test
    @DisplayName("依赖全部 DONE 时就绪")
    void shouldBeReadyWhenAllDependenciesDone() {
        lenient().when(queryChain.count()).thenReturn(2L);
        assertThat(subTaskService.isReady(withDeps(List.of(1L, 2L)))).isTrue();
    }

    @Test
    @DisplayName("依赖部分未 DONE 时阻塞")
    void shouldNotBeReadyWhenSomeDependencyNotDone() {
        lenient().when(queryChain.count()).thenReturn(1L);
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
}
