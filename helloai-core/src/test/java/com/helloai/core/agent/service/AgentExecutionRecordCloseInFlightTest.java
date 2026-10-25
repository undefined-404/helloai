package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.service.impl.AgentExecutionRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1：改派前收口在飞执行记录（{@code closeInFlight}）。
 *
 * <p>核心契约（现场见子任务 {@code 2113947248843329537}）：子任务被 {@code block()} 后改派，
 * 旧记录仍 {@code RUNNING} ⇒ 建命令被「已有进行中的执行记录」守卫拒掉 ⇒ 改派永久失败。
 * 收口必须做到三件事：<b>只动平台内记录</b>、<b>逐条带 status CAS</b>、<b>不覆盖他路已写的终态</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentExecutionRecordService 在飞记录收口（D1）")
class AgentExecutionRecordCloseInFlightTest {

    @Mock
    private AgentExecutionRecordMapper executionRecordMapper;

    private AgentExecutionRecordService service;

    @BeforeEach
    void setUp() {
        AgentExecutionRecordServiceImpl impl = spy(new AgentExecutionRecordServiceImpl());
        ReflectionTestUtils.setField(impl, "baseMapper", executionRecordMapper);
        service = impl;
    }

    private AgentExecutionRecord record(long id, ExecutionStatus status) {
        AgentExecutionRecord r = new AgentExecutionRecord();
        r.setId(id);
        r.setSubTaskId(9001L);
        r.setAgentId(7L);
        r.setAccessType(AgentAccessType.API_KEY_LLM);
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("没有在飞记录 → 返回 0，且不做任何写")
    void shouldReturnZeroWhenNoInFlight() {
        when(executionRecordMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.closeInFlight(9001L, "改派顶替旧执行")).isZero();
        verify(executionRecordMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("subTaskId 为空 → 返回 0，不查库")
    void shouldReturnZeroWhenSubTaskIdNull() {
        assertThat(service.closeInFlight(null, "改派顶替旧执行")).isZero();
        verify(executionRecordMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("PENDING + RUNNING 两条在飞 → 各收口一次，返回 2")
    void shouldCloseEachInFlightRecordWithStatusCas() {
        when(executionRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ExecutionStatus.RUNNING),
                record(12L, ExecutionStatus.PENDING)));
        when(executionRecordMapper.update(any(), any())).thenReturn(1);

        assertThat(service.closeInFlight(9001L, "改派顶替旧执行")).isEqualTo(2);
        verify(executionRecordMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("某条已被他路推进到终态（CAS 失败）→ 只计成功条数，不重复覆盖")
    void shouldCountOnlySucceededCas() {
        when(executionRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ExecutionStatus.RUNNING),
                record(12L, ExecutionStatus.RUNNING)));
        // 第一条收口成功，第二条在收口瞬间已被执行侧写成 SUCCESS（CAS 失配）
        when(executionRecordMapper.update(any(), any())).thenReturn(1, 0);

        assertThat(service.closeInFlight(9001L, "改派顶替旧执行")).isEqualTo(1);
        verify(executionRecordMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("写入内容：FAILED + 原因 + 结束时间")
    void shouldWriteFailedStatusWithReason() {
        when(executionRecordMapper.selectList(any())).thenReturn(List.of(record(11L, ExecutionStatus.RUNNING)));
        when(executionRecordMapper.update(any(), any())).thenReturn(1);

        service.closeInFlight(9001L, "改派顶替旧执行（MANUAL）");

        ArgumentCaptor<AgentExecutionRecord> captor = ArgumentCaptor.forClass(AgentExecutionRecord.class);
        verify(executionRecordMapper).update(captor.capture(), any(Wrapper.class));
        AgentExecutionRecord patch = captor.getValue();
        assertThat(patch.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(patch.getErrorMsg()).isEqualTo("改派顶替旧执行（MANUAL）");
        assertThat(patch.getEndTime()).isNotNull();
        // 只打补丁，不把整行（含 token / 时间戳）回写覆盖
        assertThat(patch.getTokenUsage()).isNull();
    }
}
