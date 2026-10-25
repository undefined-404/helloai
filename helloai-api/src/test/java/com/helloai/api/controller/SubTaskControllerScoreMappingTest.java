package com.helloai.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.subtask.SubTaskResponse;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.SubTaskViewGuard;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@link SubTaskController} 评分字段映射回归（2026-10-18）。
 *
 * <p><b>为什么必须有用例</b>：`sub_task` 表的 `composite_score` / `score_grade` /
 * `score_factors` 三列**一直有值**（DONE 时由隐式评分写入），但 {@code toResponse}
 * 从未映射它们 ⇒ 子任务列表「评分」列恒为 `-`，而详情页（走 review 接口）有分，
 * 形成「同一子任务两处说法不一致」。这类「实体有值、DTO 漏字段」的缺陷编译器抓不到，
 * 只能靠断言 DTO 真实产出。</p>
 *
 * <p>本用例同时锁定语义：<b>未完成时为空属预期</b>（不是缺数据），
 * 故只断言「有值时有值」，不要求 DONE 以外状态给默认分。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskController 评分字段映射")
class SubTaskControllerScoreMappingTest {

    @Mock
    private SubTaskService subTaskService;
    @Mock
    private TaskService taskService;
    @Mock
    private AgentService agentService;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private ExecutionCommandService executionCommandService;
    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private AgentDispatchProperties agentDispatchProperties;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private SubTaskViewGuard subTaskViewGuard;
    @Mock
    private PlannerAnalysisService plannerAnalysisService;

    @InjectMocks
    private SubTaskController controller;

    @Test
    @DisplayName("list：DONE 子任务的评分三件套随行返回")
    void listCarriesScoreFields() {
        SubTask done = new SubTask();
        done.setId(11L);
        done.setTitle("已完成子任务");
        done.setStatus(SubTaskStatus.DONE);
        done.setCompositeScore(90);
        done.setScoreGrade("S");
        done.setScoreFactors(Map.of("timeScore", 88, "qualityScore", 75, "coopScore", 100));

        Page<SubTask> page = new Page<>(1, 20);
        page.setRecords(List.of(done));
        page.setTotal(1);

        when(subTaskService.list(isNull(), isNull(), isNull(), eq(1), eq(20))).thenReturn(page);

        R<?> res = controller.list(null, null, null, 1, 20);

        assertThat(res.getCode()).isEqualTo(200);
        PageResult<?> pageResult = (PageResult<?>) res.getData();
        SubTaskResponse row = (SubTaskResponse) pageResult.getList().get(0);
        assertThat(row.getCompositeScore()).isEqualTo(90);
        assertThat(row.getScoreGrade()).isEqualTo("S");
        assertThat(row.getScoreFactors())
                .containsEntry("timeScore", 88)
                .containsEntry("qualityScore", 75);
    }

    @Test
    @DisplayName("list：未完成子任务评分为空（空态属预期，不造默认值）")
    void listKeepsScoreEmptyWhenNotDone() {
        SubTask running = new SubTask();
        running.setId(12L);
        running.setTitle("执行中");
        running.setStatus(SubTaskStatus.IN_PROGRESS);

        Page<SubTask> page = new Page<>(1, 20);
        page.setRecords(List.of(running));
        page.setTotal(1);

        when(subTaskService.list(isNull(), isNull(), isNull(), eq(1), eq(20))).thenReturn(page);

        R<?> res = controller.list(null, null, null, 1, 20);

        SubTaskResponse row = (SubTaskResponse) ((PageResult<?>) res.getData()).getList().get(0);
        assertThat(row.getCompositeScore()).isNull();
        assertThat(row.getScoreGrade()).isNull();
        assertThat(row.getScoreFactors()).isNull();
    }
}
