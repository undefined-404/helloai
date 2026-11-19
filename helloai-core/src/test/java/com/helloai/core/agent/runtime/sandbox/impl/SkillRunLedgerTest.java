package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.entity.SubTaskSkillRun;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能执行台账单测（`MA-3`）。
 *
 * <p><b>本类守的是两件事</b>：</p>
 * <ol>
 *   <li><b>每次执行都留痕</b>：成功 / 非零退出 / 超时 / SDK 异常四条路径都要落一行，
 *       且 {@code exit_code} 是真实值——只记成功的话，失败率永远算不出来；</li>
 *   <li><b>台账坏了不许影响执行</b>（最关键的一条）：让 recorder 抛异常，
 *       断言 {@code run} 的返回值与正常情况<b>逐字相同</b>、异常不外泄。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MA-3 子任务技能执行台账")
class SkillRunLedgerTest {

    private static final SkillExecutionSupport.SkillTarget ARCHIFY =
            new SkillExecutionSupport.SkillTarget("archify", "3.1.0", null);

    private static final SkillExecutionSupport.SkillRunContext CTX =
            SkillExecutionSupport.SkillRunContext.ofSubTask(22L, 33L, 11L, 2);

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    @Mock
    private SkillPackageLoader skillPackageLoader;

    @Mock
    private SkillRunLedgerRecorder recorder;

    private SkillExecutionSupport support;

    @BeforeEach
    void setUp() {
        support = new SkillExecutionSupport(new OpenSandboxProperties(), skillPackageLoader,
                sandboxExecutionService, recorder);
    }

    private void stubExecute(int exitCode, boolean truncated) {
        when(sandboxExecutionService.execute(eq(22L), isNull(), anyString(), eq(60),
                eq(SandboxCallOrigin.EXECUTOR), eq(2)))
                .thenReturn(new ExecuteResponse("output", exitCode, truncated, "call-1"));
    }

    @Test
    @DisplayName("成功执行 ⇒ 落一行，身份四元组 / 技能版本 / 耗时 / callId 齐全")
    void shouldRecordSuccessfulRun() {
        stubExecute(0, false);

        support.run(CTX, ARCHIFY, "node bin/archify.mjs doctor", 60);

        SubTaskSkillRun row = captureRow();
        assertThat(row.getSubTaskId()).isEqualTo(22L);
        assertThat(row.getTaskId()).isEqualTo(33L);
        assertThat(row.getAgentId()).isEqualTo(11L);
        assertThat(row.getRound()).isEqualTo(2);
        assertThat(row.getSkillName()).isEqualTo("archify");
        assertThat(row.getSkillVersion()).isEqualTo("3.1.0");
        assertThat(row.getExitCode()).isZero();
        assertThat(row.getTruncated()).isZero();
        assertThat(row.getCallId()).isEqualTo("call-1");
        assertThat(row.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(row.getCommandPrefix()).isEqualTo("node bin/archify.mjs doctor");
    }

    @Test
    @DisplayName("非零退出 ⇒ 也落一行，exit_code 是真实值（不是 0、不是 -1）")
    void shouldRecordRealExitCode() {
        stubExecute(2, false);

        support.run(CTX, ARCHIFY, "node bin/archify.mjs validate broken.json", 60);

        assertThat(captureRow().getExitCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("超时（124）⇒ 落一行，exit_code = 124")
    void shouldRecordTimeout() {
        stubExecute(ExecuteResponse.EXIT_CODE_TIMEOUT, false);

        support.run(CTX, ARCHIFY, "node bin/archify.mjs render", 60);

        assertThat(captureRow().getExitCode()).isEqualTo(124);
    }

    @Test
    @DisplayName("输出被截断 ⇒ 落一行且 truncated=1（「成功但结果不完整」必须可与完整成功区分）")
    void shouldRecordTruncatedFlag() {
        stubExecute(0, true);

        support.run(CTX, ARCHIFY, "node bin/archify.mjs render", 60);

        assertThat(captureRow().getTruncated()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 台账写入抛异常 ⇒ 执行结果不变、异常不外泄（只有 log.warn）")
    void shouldNotAffectExecutionWhenLedgerFails() {
        stubExecute(0, false);
        doThrow(new IllegalStateException("jsonb 写入失败")).when(recorder).record(any());

        ExecuteResponse response = support.run(CTX, ARCHIFY, "node bin/archify.mjs doctor", 60);

        assertThat(response.exitCode()).isZero();
        assertThat(response.output()).isEqualTo("output");
        assertThat(response.callId()).isEqualTo("call-1");
    }

    @Test
    @DisplayName("沙箱 SDK 直接抛异常 ⇒ exit_code 记 -1（结构化失败），异常照旧外抛")
    void shouldRecordSdkErrorWhenExecuteThrows() {
        when(sandboxExecutionService.execute(eq(22L), isNull(), anyString(), eq(60),
                eq(SandboxCallOrigin.EXECUTOR), eq(2)))
                .thenThrow(new IllegalStateException("容器不可用"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> support.run(CTX, ARCHIFY, "node bin/archify.mjs doctor", 60));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(captureRow().getExitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
    }

    @Test
    @DisplayName("同一子任务跑 3 次 ⇒ 3 行，round 可区分（轮次不被覆盖）")
    void shouldRecordEachRunSeparately() {
        stubExecute(0, false);

        support.run(CTX, ARCHIFY, "run-1", 60);
        support.run(CTX, ARCHIFY, "run-2", 60);
        support.run(CTX, ARCHIFY, "run-3", 60);

        ArgumentCaptor<SubTaskSkillRun> captor = ArgumentCaptor.forClass(SubTaskSkillRun.class);
        verify(recorder, times(3)).record(captor.capture());
        List<SubTaskSkillRun> rows = captor.getAllValues();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(SubTaskSkillRun::getCommandPrefix)
                .containsExactly("run-1", "run-2", "run-3");
        assertThat(rows).extracting(SubTaskSkillRun::getRound).containsOnly(2);
    }

    @Test
    @DisplayName("业务面技能执行（会话作用域、无子任务）⇒ 不写子任务台账")
    void shouldSkipLedgerForConversationScope() {
        when(sandboxExecutionService.execute(eq(77L), isNull(), anyString(), eq(60),
                eq(SandboxCallOrigin.EXECUTOR), eq(0)))
                .thenReturn(new ExecuteResponse("ok", 0, false));

        support.run(SkillExecutionSupport.SkillRunContext.ofConversationScope(77L),
                ARCHIFY, "node bin/x.mjs", 60);

        verify(recorder, times(0)).record(any());
    }

    private SubTaskSkillRun captureRow() {
        ArgumentCaptor<SubTaskSkillRun> captor = ArgumentCaptor.forClass(SubTaskSkillRun.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }
}
