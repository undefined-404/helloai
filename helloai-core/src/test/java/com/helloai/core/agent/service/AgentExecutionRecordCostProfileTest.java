package com.helloai.core.agent.service;

import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.service.impl.AgentExecutionRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5.2 Fleet 成本选人：{@code averageRecentSuccessTokens} 聚合单测。
 *
 * <p>用 spy 桩掉 {@code baseMapper}，隔离 MyBatis-Plus / 数据库。核心契约是
 * <b>best-effort</b>：任何「无数据」形态（无样本 / 查询异常 / agentId 缺失）
 * 一律返回 {@code null}（= 无成本画像），绝不向选人主链路抛出。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentExecutionRecordService 成本画像聚合（B5.2）")
class AgentExecutionRecordCostProfileTest {

    private static final long AGENT_ID = 7L;

    @Mock
    private AgentExecutionRecordMapper executionRecordMapper;

    private AgentExecutionRecordService service;

    @BeforeEach
    void setUp() {
        AgentExecutionRecordServiceImpl impl = spy(new AgentExecutionRecordServiceImpl());
        ReflectionTestUtils.setField(impl, "baseMapper", executionRecordMapper);
        service = impl;
    }

    @Test
    @DisplayName("有样本：返回四舍五入后的 token 均值")
    void shouldReturnRoundedAverageWhenSamplePresent() {
        when(executionRecordMapper.selectRecentAvgTokenUsage(AGENT_ID, 5)).thenReturn(42000.6);

        assertThat(service.averageRecentSuccessTokens(AGENT_ID, 5)).isEqualTo(42001);
    }

    @Test
    @DisplayName("无样本（SQL AVG 返回 NULL）→ null（= 无成本画像）")
    void shouldReturnNullWhenNoSample() {
        when(executionRecordMapper.selectRecentAvgTokenUsage(AGENT_ID, 5)).thenReturn(null);

        assertThat(service.averageRecentSuccessTokens(AGENT_ID, 5)).isNull();
    }

    @Test
    @DisplayName("查询异常 → null（best-effort，不向选人主链路抛出）")
    void shouldReturnNullWhenMapperThrows() {
        when(executionRecordMapper.selectRecentAvgTokenUsage(AGENT_ID, 5))
                .thenThrow(new RuntimeException("db down"));

        assertThat(service.averageRecentSuccessTokens(AGENT_ID, 5)).isNull();
    }

    @Test
    @DisplayName("agentId 为 null → 直接 null，不触达 mapper")
    void shouldReturnNullForNullAgentId() {
        assertThat(service.averageRecentSuccessTokens(null, 5)).isNull();

        verify(executionRecordMapper, never()).selectRecentAvgTokenUsage(anyLong(), anyInt());
    }

    @Test
    @DisplayName("limit ≤ 0 → 归一为 1（不产生非法 LIMIT）")
    void shouldNormalizeNonPositiveLimitToOne() {
        when(executionRecordMapper.selectRecentAvgTokenUsage(AGENT_ID, 1)).thenReturn(1000.0);

        assertThat(service.averageRecentSuccessTokens(AGENT_ID, 0)).isEqualTo(1000);

        verify(executionRecordMapper).selectRecentAvgTokenUsage(AGENT_ID, 1);
    }
}
