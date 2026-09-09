package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.WatchdogProperties;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * SubTaskService.mergeSkills / updateDraft 单元测试（G-010 能力感知传递链）：
 * 并集装箱去重保序 / 存量退化零变化 / 草案态 fail-close 编辑门禁。
 * G-011 扩展：uncertainties 归一化（空白丢弃 / 非法 kind 降级 / null 不改 / 空数组清空）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskService.mergeSkills / updateDraft")
class SubTaskServiceMergeSkillsTest {

    private SubTaskService subTaskService;

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> queryChain = mock(LambdaQueryChainWrapper.class);

    @BeforeEach
    void setUp() {
        SubTaskService real = new SubTaskServiceImpl(
                mock(AgentOutboxService.class), mock(AgentInboxService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class), mock(HeartbeatService.class),
                mock(ReviewPort.class), mock(ImplicitScoreCalculator.class),
                mock(RewardService.class), mock(ApplicationEventPublisher.class),
                mock(TaskTimelineService.class),
                new AgentDispatchProperties(), mock(ConcurrencyQuotaService.class),
                new WatchdogProperties(),
                mock(AgentSessionService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                // requiredSkillsOf 懒解析 TaskService（mergeSkills 测试中经 spy stub，不触达真实逻辑）
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(AgentEventRecorder.class),
                mock(SubTaskMapper.class));
        subTaskService = spy(real);
        lenient().doReturn(queryChain).when(subTaskService).lambdaQuery();
        lenient().when(queryChain.in(any(), any(List.class))).thenReturn(queryChain);
        lenient().when(queryChain.eq(any(), any())).thenReturn(queryChain);
        lenient().when(queryChain.le(any(), any())).thenReturn(queryChain);
        lenient().when(queryChain.orderByAsc(any(SFunction.class))).thenReturn(queryChain);
        lenient().when(queryChain.last(anyString())).thenReturn(queryChain);
        lenient().when(queryChain.list()).thenReturn(List.of());
    }

    private SubTask draft(List<String> subTaskSkills) {
        SubTask subTask = new SubTask();
        subTask.setId(1L);
        subTask.setTaskId(100L);
        subTask.setStatus(SubTaskStatus.PENDING_PLAN_REVIEW);
        subTask.setRequiredSkills(subTaskSkills);
        return subTask;
    }

    @Nested
    @DisplayName("mergeSkills 并集装箱")
    class MergeSkills {

        @Test
        @DisplayName("子任务级在前、任务级追加在后、去重保序")
        void shouldMergeWithSubTaskLevelFirst() {
            doReturn(List.of("eng-verification", "shell")).when(subTaskService).requiredSkillsOf(100L);
            SubTask subTask = draft(List.of("eng-doc-standard", "shell", "eng-doc-standard"));

            List<String> merged = subTaskService.mergeSkills(subTask);

            assertThat(merged).containsExactly("eng-doc-standard", "shell", "eng-verification");
        }

        @Test
        @DisplayName("存量子任务 required_skills 为空时退化为纯任务级（行为零变化）")
        void shouldDegradeToTaskLevelWhenSubTaskSkillsEmpty() {
            doReturn(List.of("eng-verification")).when(subTaskService).requiredSkillsOf(100L);

            assertThat(subTaskService.mergeSkills(draft(null))).containsExactly("eng-verification");
            assertThat(subTaskService.mergeSkills(draft(List.of()))).containsExactly("eng-verification");
        }

        @Test
        @DisplayName("null 子任务安全：不 NPE，返回任务级或空列表")
        void shouldHandleNullSubTask() {
            doReturn(List.of()).when(subTaskService).requiredSkillsOf(null);
            assertThat(subTaskService.mergeSkills(null)).isEmpty();
        }

        @Test
        @DisplayName("任务级 null / 子任务级空白标签均被过滤，结果恒非 null")
        void shouldFilterBlankAndNullSkills() {
            doReturn(List.of()).when(subTaskService).requiredSkillsOf(100L);
            SubTask subTask = draft(java.util.Arrays.asList("shell", " ", null));
            assertThat(subTaskService.mergeSkills(subTask)).containsExactly("shell");
        }
    }

    @Nested
    @DisplayName("updateDraft 草案修订")
    class UpdateDraft {

        @Test
        @DisplayName("PENDING_PLAN_REVIEW 允许编辑：两字段均覆盖")
        void shouldUpdateDraftFields() {
            SubTask stored = draft(List.of("old-skill"));
            stored.setConstraints("old");
            doReturn(stored).when(subTaskService).getById(1L);
            doReturn(true).when(subTaskService).updateById(any(SubTask.class));

            subTaskService.updateDraft(1L, List.of("eng-doc-standard"), "不得改接口", null);

            assertThat(stored.getRequiredSkills()).containsExactly("eng-doc-standard");
            assertThat(stored.getConstraints()).isEqualTo("不得改接口");
        }

        @Test
        @DisplayName("null 字段不覆盖（局部更新语义）")
        void shouldSkipNullFields() {
            SubTask stored = draft(List.of("keep"));
            stored.setConstraints("keep-c");
            doReturn(stored).when(subTaskService).getById(1L);
            doReturn(true).when(subTaskService).updateById(any(SubTask.class));

            subTaskService.updateDraft(1L, null, null, null);

            assertThat(stored.getRequiredSkills()).containsExactly("keep");
            assertThat(stored.getConstraints()).isEqualTo("keep-c");
        }

        @Test
        @DisplayName("非草案态 fail-close 拒绝编辑")
        void shouldRejectNonDraftStatus() {
            SubTask assigned = draft(null);
            assigned.setStatus(SubTaskStatus.ASSIGNED);
            doReturn(assigned).when(subTaskService).getById(1L);

            assertThatThrownBy(() -> subTaskService.updateDraft(1L, List.of("x"), null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("仅草案待审状态可编辑");
        }

        @Test
        @DisplayName("草案不存在抛业务异常")
        void shouldRejectMissingDraft() {
            doReturn(null).when(subTaskService).getById(2L);

            assertThatThrownBy(() -> subTaskService.updateDraft(2L, null, null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("草案不存在");
        }

        @Test
        @DisplayName("uncertainties 归一化：空白/null 条目丢弃、非法 kind 降级 UNCONFIRMED、合法值保序")
        void shouldNormalizeUncertainties() {
            SubTask stored = draft(List.of());
            doReturn(stored).when(subTaskService).getById(1L);
            doReturn(true).when(subTaskService).updateById(any(SubTask.class));

            Uncertainty ok = new Uncertainty();
            ok.setKind(Uncertainty.KIND_UNCONFIRMED);
            ok.setNote("分区口径待确认");
            Uncertainty assumed = new Uncertainty();
            assumed.setKind(Uncertainty.KIND_ASSUMPTION);
            assumed.setNote("历史数据可忽略");
            Uncertainty blank = new Uncertainty();
            blank.setKind(Uncertainty.KIND_UNCONFIRMED);
            blank.setNote("  ");
            Uncertainty weird = new Uncertainty();
            weird.setKind("WEIRD");
            weird.setNote("非法形态");

            // List.of 拒 null 元素，用 Arrays.asList 覆盖「null 条目丢弃」分支（同 L126 全限定风格）
            subTaskService.updateDraft(1L, null, null,
                    java.util.Arrays.asList(ok, assumed, blank, weird, null));

            assertThat(stored.getUncertainties())
                    .extracting(Uncertainty::getKind, Uncertainty::getNote)
                    .containsExactly(
                            tuple(Uncertainty.KIND_UNCONFIRMED, "分区口径待确认"),
                            tuple(Uncertainty.KIND_ASSUMPTION, "历史数据可忽略"),
                            tuple(Uncertainty.KIND_UNCONFIRMED, "非法形态"));
        }

        @Test
        @DisplayName("uncertainties null 不覆盖（保留存量申报）")
        void shouldKeepUncertaintiesWhenNull() {
            SubTask stored = draft(List.of());
            Uncertainty existing = new Uncertainty();
            existing.setKind(Uncertainty.KIND_UNCONFIRMED);
            existing.setNote("存量");
            stored.setUncertainties(List.of(existing));
            doReturn(stored).when(subTaskService).getById(1L);
            doReturn(true).when(subTaskService).updateById(any(SubTask.class));

            subTaskService.updateDraft(1L, null, null, null);

            assertThat(stored.getUncertainties())
                    .extracting(Uncertainty::getNote)
                    .containsExactly("存量");
        }

        @Test
        @DisplayName("uncertainties 空数组清空存量申报")
        void shouldClearUncertaintiesWhenEmptyList() {
            SubTask stored = draft(List.of());
            Uncertainty existing = new Uncertainty();
            existing.setKind(Uncertainty.KIND_UNCONFIRMED);
            existing.setNote("存量");
            stored.setUncertainties(List.of(existing));
            doReturn(stored).when(subTaskService).getById(1L);
            doReturn(true).when(subTaskService).updateById(any(SubTask.class));

            subTaskService.updateDraft(1L, null, null, List.of());

            assertThat(stored.getUncertainties()).isEmpty();
        }
    }
}
