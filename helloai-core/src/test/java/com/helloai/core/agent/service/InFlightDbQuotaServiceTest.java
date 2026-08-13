package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.service.impl.InFlightDbQuotaService;
import com.helloai.core.task.mapper.SubTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * InFlightDbQuotaService 单元测试（E2 并发额度）。
 *
 * <p>验证额度解析优先级：值班租约 maxConcurrent &gt;
 * capabilities.maxConcurrentTasks 显式值 &gt; null（不限制），
 * 以及 canAccept 的占用边界（count &lt; quota 通过、count == quota 拒绝）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InFlightDbQuotaService")
class InFlightDbQuotaServiceTest {

    private static final long AGENT_ID = 7L;

    @Mock
    private SubTaskMapper subTaskMapper;

    @Mock
    private AgentDutyLeaseService agentDutyLeaseService;

    @Mock
    private AgentService agentService;

    private ConcurrencyQuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new InFlightDbQuotaService(subTaskMapper, agentDutyLeaseService, agentService);
    }

    @Nested
    @DisplayName("inFlightCount：占用透传 mapper 实时统计")
    class InFlightCountTest {

        @Test
        @DisplayName("返回 mapper 统计的在飞数")
        void shouldReturnMapperCount() {
            when(subTaskMapper.countInFlightByAgent(AGENT_ID)).thenReturn(3);

            assertThat(quotaService.inFlightCount(AGENT_ID)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("resolveQuota：额度解析优先级")
    class ResolveQuotaTest {

        @Test
        @DisplayName("有 ACTIVE 租约：以租约 maxConcurrent 为准（忽略 capabilities）")
        void shouldPreferLeaseMaxConcurrent() {
            AgentDutyLease lease = new AgentDutyLease();
            lease.setMaxConcurrent(5);
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(lease);

            assertThat(quotaService.resolveQuota(AGENT_ID)).isEqualTo(5);
        }

        @Test
        @DisplayName("无租约 + capabilities 显式数字：使用声明的额度")
        void shouldUseCapabilityNumber() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("maxConcurrentTasks", 2));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);

            assertThat(quotaService.resolveQuota(AGENT_ID)).isEqualTo(2);
        }

        @Test
        @DisplayName("无租约 + capabilities 显式字符串数字：解析后使用")
        void shouldParseCapabilityString() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("maxConcurrentTasks", "4"));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);

            assertThat(quotaService.resolveQuota(AGENT_ID)).isEqualTo(4);
        }

        @Test
        @DisplayName("无租约 + capabilities 未声明：返回 null（不限制，E2 前行为）")
        void shouldReturnNullWhenNotDeclared() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("other", "x"));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);

            assertThat(quotaService.resolveQuota(AGENT_ID)).isNull();
        }

        @Test
        @DisplayName("无租约 + agent 不存在：返回 null（防御式）")
        void shouldReturnNullWhenAgentMissing() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            when(agentService.getById(AGENT_ID)).thenReturn(null);

            assertThat(quotaService.resolveQuota(AGENT_ID)).isNull();
        }

        @Test
        @DisplayName("无租约 + capabilities 非数字字符串：返回 null（防御式）")
        void shouldReturnNullWhenCapabilityNotNumeric() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("maxConcurrentTasks", "abc"));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);

            assertThat(quotaService.resolveQuota(AGENT_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("canAccept：占用边界")
    class CanAcceptTest {

        @Test
        @DisplayName("额度未声明（null）：始终可接收")
        void shouldAcceptWhenNoQuota() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of());
            when(agentService.getById(AGENT_ID)).thenReturn(agent);

            assertThat(quotaService.canAccept(AGENT_ID)).isTrue();
        }

        @Test
        @DisplayName("占用 < 额度：可接收")
        void shouldAcceptWhenBelowQuota() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("maxConcurrentTasks", 3));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);
            when(subTaskMapper.countInFlightByAgent(AGENT_ID)).thenReturn(2);

            assertThat(quotaService.canAccept(AGENT_ID)).isTrue();
        }

        @Test
        @DisplayName("占用 == 额度：拒绝（满额边界）")
        void shouldRejectWhenAtQuota() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("maxConcurrentTasks", 3));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);
            when(subTaskMapper.countInFlightByAgent(AGENT_ID)).thenReturn(3);

            assertThat(quotaService.canAccept(AGENT_ID)).isFalse();
        }

        @Test
        @DisplayName("占用 > 额度：拒绝（超发修复后仍拒绝直至回落）")
        void shouldRejectWhenOverQuota() {
            when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
            Agent agent = new Agent();
            agent.setCapabilities(Map.of("maxConcurrentTasks", 2));
            when(agentService.getById(AGENT_ID)).thenReturn(agent);
            when(subTaskMapper.countInFlightByAgent(AGENT_ID)).thenReturn(5);

            assertThat(quotaService.canAccept(AGENT_ID)).isFalse();
        }
    }
}
