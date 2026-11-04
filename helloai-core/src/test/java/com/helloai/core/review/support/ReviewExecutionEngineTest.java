package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewExecutionEngine} 埋点单测：`RV-1` 判定静态校验（Warn 档）、
 * `RV-3` 静态覆盖批评接线与 `RV-4` 调用失败可观测。
 *
 * <p>守三类契约：</p>
 * <ol>
 *   <li><b>失败不再静默</b>：异常 / 结果失败 / 不可解析三条「返回 null」路径，时间线上必须能区分
 *       （{@code reason=exception|result_failed|null_result}，{@code channel} 标明单审/双审/抽检）；</li>
 *   <li><b>Warn 档不改判定</b>：校验器命中只记 {@code payload.enforced=false} 的告警事件，
 *       返回的 verdict 与未开启校验时<b>完全一致</b>（实施计划 §0④：Warn 档不得宣称契约已强制）；</li>
 *   <li><b>Block 只发信号不改写判定</b>：{@code RV-2}/{@code RV-3} 的 BLOCK 命中 ⇒
 *       {@code inconclusive=true} 且 {@code enforced=true}，但 {@code verdict.pass} 保持模型原值，
 *       落 {@code INCONCLUSIVE} 与否由调用方决定（引擎「不改状态不落库」边界不破）。</li>
 * </ol>
 */
@DisplayName("ReviewExecutionEngine 埋点（RV-1 / RV-3 / RV-4）")
class ReviewExecutionEngineTest {

    private static final Long TASK_ID = 100L;
    private static final Long SUB_TASK_ID = 200L;
    private static final Long REVIEWER_ID = 7L;

    private static final String ACCEPTANCE = "① 接口清单覆盖全部端点；② 每个端点标注请求参数";

    private PlatformAgentExecutionService platformAgentExecutionService;
    private ConversationService conversationService;
    private TaskTimelineService taskTimelineService;
    private ReviewEvidenceAssembler reviewEvidenceAssembler;
    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        platformAgentExecutionService = mock(PlatformAgentExecutionService.class);
        conversationService = mock(ConversationService.class);
        taskTimelineService = mock(TaskTimelineService.class);
        reviewEvidenceAssembler = mock(ReviewEvidenceAssembler.class);
        subTaskService = mock(SubTaskService.class);
        // renderPrompt 会用返回值做 String.replace，null 会 NPE ⇒ 全部给占位串
        lenient().when(reviewEvidenceAssembler.extractExecutionOutput(any())).thenReturn("（产出）");
        lenient().when(reviewEvidenceAssembler.buildAttachmentList(any())).thenReturn("（清单）");
        lenient().when(reviewEvidenceAssembler.buildAttachmentContent(any())).thenReturn("（正文）");
        lenient().when(reviewEvidenceAssembler.extractRawOutput(any())).thenReturn("（原始输出）");
        lenient().when(reviewEvidenceAssembler.verificationSignal(anyString())).thenReturn("（证据信号）");
        lenient().when(subTaskService.mergeSkills(anyLong())).thenReturn(List.of());
    }

    private ReviewExecutionEngine newEngine(ReviewProperties properties) {
        return new ReviewExecutionEngine(platformAgentExecutionService, conversationService,
                taskTimelineService, new VerdictParser(new ObjectMapper()),
                reviewEvidenceAssembler, subTaskService, properties);
    }

    private SubTaskView subTask() {
        return new SubTaskView(SUB_TASK_ID, TASK_ID, SubTaskStatus.REVIEW, null, 0,
                "写接口文档", "整理 REST 接口清单", "接口文档", ACCEPTANCE, null, Map.of(), List.of());
    }

    private AgentProfileSnapshot reviewer() {
        return AgentProfileSnapshot.builder()
                .id(REVIEWER_ID)
                .name("reviewer")
                .role(AgentRole.REVIEWER)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .score(90)
                .modelType("deepseek")
                .localExecutionCapable(true)
                .build();
    }

    @Test
    @DisplayName("LLM 调用抛异常 → 返回 null，并记 execution_failed（reason=exception，channel=SINGLE）")
    void shouldRecordExecutionFailureOnException() {
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenThrow(new IllegalStateException("boom"));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(new ReviewProperties())
                .execute(subTask(), reviewer());

        assertThat(outcome.verdict()).isNull();
        assertThat(outcome.inconclusive()).isFalse();
        Map<String, Object> payload = captureSingleEvent(
                TimelineEventCode.SUB_TASK_AUTO_REVIEW_EXECUTION_FAILED.code());
        assertThat(payload.get("channel")).isEqualTo("SINGLE");
        assertThat(payload.get("reason")).isEqualTo("exception");
        assertThat((String) payload.get("error")).contains("IllegalStateException").contains("boom");
    }

    @Test
    @DisplayName("LLM 返回失败结果 → 返回 null，并记 execution_failed（reason=result_failed，带原始错误）")
    void shouldRecordExecutionFailureOnResultFailed() {
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.failure("provider timeout", "STOP", "executor"));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(new ReviewProperties())
                .execute(subTask(), reviewer());

        assertThat(outcome.verdict()).isNull();
        Map<String, Object> payload = captureSingleEvent(
                TimelineEventCode.SUB_TASK_AUTO_REVIEW_EXECUTION_FAILED.code());
        assertThat(payload.get("reason")).isEqualTo("result_failed");
        assertThat(payload.get("error")).isEqualTo("provider timeout");
    }

    @Test
    @DisplayName("命中静态校验（缺 analysis）→ 记 verdict_warn（enforced=false），判定结果保持原样")
    void shouldRecordWarnFindingsWithoutChangingVerdict() {
        // 本例只验 RV-1 Warn 档：关掉 RV-3 覆盖批评，避免「无 coverage」的 BLOCK 混进同一条断言
        ReviewProperties properties = new ReviewProperties();
        properties.setCoverageCriticEnabled(false);
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"无\", \"comment\": \"很好\"}",
                        "STOP", "executor", 10));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(properties)
                .execute(subTask(), reviewer());
        SubTaskReviewService.ReviewVerdict verdict = outcome.verdict();

        // Warn 档不拦截：原判定原样返回
        assertThat(outcome.inconclusive()).isFalse();
        assertThat(verdict).isNotNull();
        assertThat(verdict.getPass()).isTrue();
        assertThat(verdict.getScore()).isEqualTo(5);

        Map<String, Object> payload = captureSingleEvent(
                TimelineEventCode.SUB_TASK_AUTO_REVIEW_VERDICT_WARN.code());
        assertThat(payload.get("enforced")).isEqualTo(false);
        assertThat(payload.get("channel")).isEqualTo("SINGLE");
        assertThat(payload.get("pass")).isEqualTo(true);
        assertThat(payload.get("score")).isEqualTo(5);
        assertThat(payload.get("looseParse")).isEqualTo(false);
        assertThat(payload.get("blockCount")).isEqualTo(0L);
        assertThat(payload.get("warnCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) payload.get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).get("severity")).isEqualTo("WARN");
        assertThat(findings.get(0).get("code")).isEqualTo(ReviewVerdictValidator.ANALYSIS_MISSING);
    }

    @Test
    @DisplayName("校验器开关关闭 → 无 verdict_warn 事件（回滚口径：关配置即回原行为）")
    void shouldNotRecordWarnWhenValidatorDisabled() {
        ReviewProperties properties = new ReviewProperties();
        properties.setVerdictValidatorEnabled(false);
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"无\"}",
                        "STOP", "executor", 10));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(properties)
                .execute(subTask(), reviewer());

        assertThat(outcome.verdict()).isNotNull();
        assertThat(outcome.inconclusive()).isFalse();
        verify(taskTimelineService, never()).recordEvent(any(), any(),
                eq(TimelineEventCode.SUB_TASK_AUTO_REVIEW_VERDICT_WARN.code()),
                any(), any(), any());
    }

    @Test
    @DisplayName("RV-2：命中 BLOCK（驳回却满分）→ outcome.inconclusive=true，事件 enforced=true")
    void shouldMarkInconclusiveOnBlockFinding() {
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 5, \"issues\": \"[defect] 缺端点\", \"comment\": \"驳回\"}",
                        "STOP", "executor", 10));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(new ReviewProperties())
                .execute(subTask(), reviewer());

        assertThat(outcome.inconclusive()).isTrue();
        assertThat(outcome.blockFindings()).hasSize(1);
        assertThat(outcome.blockFindings().get(0).code())
                .isEqualTo(ReviewVerdictValidator.REJECT_WITH_FULL_SCORE);
        Map<String, Object> payload = captureSingleEvent(
                TimelineEventCode.SUB_TASK_AUTO_REVIEW_VERDICT_WARN.code());
        assertThat(payload.get("enforced")).isEqualTo(true);
        assertThat(payload.get("blockCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("RV-2：校验器开关关闭时 BLOCK 规则不生效 → outcome.inconclusive=false，判定原样返回")
    void shouldNotMarkInconclusiveWhenValidatorDisabled() {
        ReviewProperties properties = new ReviewProperties();
        properties.setVerdictValidatorEnabled(false);
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 5, \"issues\": \"[defect] 缺端点\", \"comment\": \"驳回\"}",
                        "STOP", "executor", 10));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(properties)
                .execute(subTask(), reviewer());

        assertThat(outcome.inconclusive()).isFalse();
        assertThat(outcome.verdict()).isNotNull();
        assertThat(outcome.verdict().getPass()).isFalse();
    }

    @Test
    @DisplayName("RV-3：通过判定缺 coverage 清单 → inconclusive=true，事件 enforced=true")
    void shouldMarkInconclusiveOnMissingCoverage() {
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 4, \"analysis\": \"逐条对照已完成\", \"coverage\": []}",
                        "STOP", "executor", 10));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(new ReviewProperties())
                .execute(subTask(), reviewer());

        assertThat(outcome.inconclusive()).isTrue();
        assertThat(outcome.blockFindings()).hasSize(1);
        assertThat(outcome.blockFindings().get(0).code())
                .isEqualTo(ReviewCoverageCritic.COVERAGE_ABSENT);
        // 判定本身不被改写：由调用方落 INCONCLUSIVE，而非引擎静默改 pass
        assertThat(outcome.verdict().getPass()).isTrue();
        Map<String, Object> payload = captureSingleEvent(
                TimelineEventCode.SUB_TASK_AUTO_REVIEW_VERDICT_WARN.code());
        assertThat(payload.get("enforced")).isEqualTo(true);
        assertThat(payload.get("blockCount")).isEqualTo(1L);
        assertThat(payload.get("warnCount")).isEqualTo(0L);
    }

    @Test
    @DisplayName("RV-3：逐条验收点都有附件/沙箱合格覆盖 → 零 finding、零事件")
    void shouldRecordNothingWhenCoverageIsComplete() {
        when(platformAgentExecutionService.executeSync(anyLong(), any()))
                .thenReturn(AgentResult.success("""
                        {"pass": true, "score": 4, "analysis": "逐条对照已完成",
                         "coverage": [
                           {"criterionRef": "接口清单覆盖全部端点", "status": "covered", "method": "attachment"},
                           {"criterionRef": "每个端点标注请求参数", "status": "covered", "method": "sandbox"}
                         ]}
                        """, "STOP", "executor", 10));

        ReviewExecutionEngine.ExecutionOutcome outcome = newEngine(new ReviewProperties())
                .execute(subTask(), reviewer());

        assertThat(outcome.inconclusive()).isFalse();
        assertThat(outcome.findings()).isEmpty();
        verify(taskTimelineService, never()).recordEvent(any(), any(),
                eq(TimelineEventCode.SUB_TASK_AUTO_REVIEW_VERDICT_WARN.code()),
                any(), any(), any());
    }

    private Map<String, Object> captureSingleEvent(String eventCode) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID), eq(eventCode),
                eq(AgentRole.REVIEWER), eq(REVIEWER_ID), payloadCaptor.capture());
        return payloadCaptor.getValue();
    }
}
