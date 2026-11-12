package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.service.impl.SubTaskQueryServiceImpl;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * SubTaskService.mergeSkills 单元测试（G-010 能力感知传递链）：
 * 技能并集装箱去重保序 / 存量退化零变化。
 *
 * <p><b>RM9-b 装配口径变更（断言语义未改）</b>：{@code mergeSkills} 已随只读簇迁入
 * {@link SubTaskQueryService}，故 {@code requiredSkillsOf} 的桩改设在查询职责类上（spy）；
 * {@code updateDraft} 仍留门面，其 {@code getById}/{@code updateById} 桩位置不变。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskService.mergeSkills")
class SubTaskServiceMergeSkillsTest {

    @Mock private SubTaskMapper subTaskMapper;
    @Mock private ObjectProvider<TaskService> taskServiceProvider;
    @Mock private ReviewPort reviewPort;

    private SubTaskQueryService queryService;
    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        queryService = spy(new SubTaskQueryServiceImpl(subTaskMapper, taskServiceProvider, reviewPort));
        SubTaskStateMachineService stateMachineService = mock(SubTaskStateMachineService.class);
        SubTaskLeaseService leaseService = mock(SubTaskLeaseService.class);
        subTaskService = spy(new SubTaskServiceImpl(
                subTaskMapper, queryService, stateMachineService, leaseService,
                mock(TaskDerivationService.class), mock(RuntimeContractGateService.class)));
    }

    /**
     * 存量草案实体测试夹具。
     *
     * <p>2026-11-08 起 {@code updateDraft} / {@code updateDraftContent} 落库前跑
     * {@code DraftGateChecker#checkOne}（写侧即时复检），四要素为空的夹具会命中 {@code G2_BLANK}
     * 而被拒绝。真实链路里实体来自 DB、字段本就齐全，故这里也按「齐全且合规」构造；
     * 需要构造不合格输入的用例自行覆盖对应字段。</p>
     */
    private SubTask draft(List<String> subTaskSkills) {
        SubTask subTask = new SubTask();
        subTask.setId(1L);
        subTask.setTaskId(100L);
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setTitle("草案标题");
        subTask.setContent("草案内容说明");
        subTask.setDeliverable("交付物清单");
        subTask.setAcceptance("运行 pytest 输出 5 passed");
        subTask.setRequiredSkills(subTaskSkills);
        return subTask;
    }

    @Nested
    @DisplayName("mergeSkills 并集装箱")
    class MergeSkills {

        @Test
        @DisplayName("子任务级在前、任务级追加在后、去重保序")
        void shouldMergeWithSubTaskLevelFirst() {
            doReturn(List.of("eng-verification", "shell")).when(queryService).requiredSkillsOf(100L);
            SubTask subTask = draft(List.of("eng-doc-standard", "shell", "eng-doc-standard"));

            List<String> merged = subTaskService.mergeSkills(subTask);

            assertThat(merged).containsExactly("eng-doc-standard", "shell", "eng-verification");
        }

        @Test
        @DisplayName("存量子任务 required_skills 为空时退化为纯任务级（行为零变化）")
        void shouldDegradeToTaskLevelWhenSubTaskSkillsEmpty() {
            doReturn(List.of("eng-verification")).when(queryService).requiredSkillsOf(100L);

            assertThat(subTaskService.mergeSkills(draft(null))).containsExactly("eng-verification");
            assertThat(subTaskService.mergeSkills(draft(List.of()))).containsExactly("eng-verification");
        }

        @Test
        @DisplayName("null 子任务安全：不 NPE，返回任务级或空列表")
        void shouldHandleNullSubTask() {
            doReturn(List.of()).when(queryService).requiredSkillsOf(null);
            assertThat(subTaskService.mergeSkills((SubTask) null)).isEmpty();
        }

        @Test
        @DisplayName("任务级 null / 子任务级空白标签均被过滤，结果恒非 null")
        void shouldFilterBlankAndNullSkills() {
            doReturn(List.of()).when(queryService).requiredSkillsOf(100L);
            SubTask subTask = draft(java.util.Arrays.asList("shell", " ", null));
            assertThat(subTaskService.mergeSkills(subTask)).containsExactly("shell");
        }
    }
}
