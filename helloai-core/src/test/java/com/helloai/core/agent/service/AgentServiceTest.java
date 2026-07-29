package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentDutyLeaseMapper;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.system.mapper.PatrolRecordMapper;
import com.helloai.core.task.mapper.ActivityLogMapper;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.mapper.RewardLogMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentService 关联统计单测。
 * 回归背景：getRelatedCounts 曾把 selectCount 结果装箱为 Long 存入 Map，
 * AdminAgentController 取值时 (Integer) 强转抛 ClassCastException → related-counts 接口稳定 500。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private SubTaskMapper subTaskMapper;
    @Mock
    private RewardLogMapper rewardLogMapper;
    @Mock
    private ActivityLogMapper activityLogMapper;
    @Mock
    private PatrolRecordMapper patrolRecordMapper;
    @Mock
    private ReviewRecordMapper reviewRecordMapper;
    @Mock
    private AgentInboxMapper agentInboxMapper;
    @Mock
    private AgentDutyLeaseMapper agentDutyLeaseMapper;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private AgentMcpServerService agentMcpServerService;

    private AgentService newSpyService() {
        return spy(new AgentService(subTaskMapper, rewardLogMapper, activityLogMapper,
                patrolRecordMapper, reviewRecordMapper, agentInboxMapper, agentDutyLeaseMapper,
                taskTimelineService, agentMcpServerService));
    }

    @Test
    @DisplayName("getRelatedCounts：计数值必须是 Integer（Controller 侧 (Integer) 强转，Long 会 500）")
    void shouldReturnIntegerCounts() {
        AgentService service = newSpyService();
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setName("agent-a");
        doReturn(agent).when(service).getById(1L);

        when(subTaskMapper.selectCount(any())).thenReturn(3L);
        when(reviewRecordMapper.selectCount(any())).thenReturn(2L);
        when(rewardLogMapper.selectCount(any())).thenReturn(5L);
        when(activityLogMapper.selectCount(any())).thenReturn(7L);
        when(patrolRecordMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> counts = service.getRelatedCounts(1L);

        assertThat(counts.get("agentId")).isEqualTo(1L);
        assertThat(counts.get("agentName")).isEqualTo("agent-a");
        // 回归断言：五个计数必须是 Integer 实例，且数值正确
        assertThat(counts.get("subTaskCount")).isInstanceOf(Integer.class).isEqualTo(3);
        assertThat(counts.get("reviewCount")).isInstanceOf(Integer.class).isEqualTo(2);
        assertThat(counts.get("rewardCount")).isInstanceOf(Integer.class).isEqualTo(5);
        assertThat(counts.get("activityCount")).isInstanceOf(Integer.class).isEqualTo(7);
        assertThat(counts.get("patrolCount")).isInstanceOf(Integer.class).isEqualTo(0);
    }

    @Test
    @DisplayName("getRelatedCounts：Agent 不存在抛 BizException")
    void shouldThrowWhenAgentNotFound() {
        AgentService service = newSpyService();
        doReturn(null).when(service).getById(999L);

        assertThatThrownBy(() -> service.getRelatedCounts(999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Agent 不存在");
    }

    // ════════════════════════════════════════════════════════════
    //  registerOrGet（name 幂等复用）
    // ════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private AgentService serviceWithNameQuery(Agent existing) {
        AgentService service = newSpyService();
        LambdaQueryChainWrapper<Agent> chain = mock(LambdaQueryChainWrapper.class);
        doReturn(chain).when(service).lambdaQuery();
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.one()).thenReturn(existing);
        return service;
    }

    @Test
    @DisplayName("registerOrGet：同名同角色已存在 → 复用并归位 ACTIVE/OFFLINE，不重新创建")
    void shouldReuseExistingAgentAndReset() {
        Agent existing = new Agent();
        existing.setId(11L);
        existing.setName("inner-loop-executor");
        existing.setRole(AgentRole.EXECUTOR);
        existing.setStatus(AgentStatus.DISABLED);
        existing.setOnlineStatus(AgentOnlineStatus.SLEEPING);

        AgentService service = serviceWithNameQuery(existing);
        doReturn(true).when(service).updateById(any(Agent.class));

        Agent result = service.registerOrGet("inner-loop-executor", AgentRole.EXECUTOR, "desc");

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getStatus()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(result.getOnlineStatus()).isEqualTo(AgentOnlineStatus.OFFLINE);
        verify(service).updateById(existing);
        verify(service, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("registerOrGet：已归位的同名 Agent → 直接返回不落库")
    void shouldReuseWithoutUpdateWhenAlreadyNormalized() {
        Agent existing = new Agent();
        existing.setId(12L);
        existing.setName("inner-loop-planner");
        existing.setRole(AgentRole.PLANNER);
        existing.setStatus(AgentStatus.ACTIVE);
        existing.setOnlineStatus(AgentOnlineStatus.OFFLINE);

        AgentService service = serviceWithNameQuery(existing);

        Agent result = service.registerOrGet("inner-loop-planner", AgentRole.PLANNER, "desc");

        assertThat(result).isSameAs(existing);
        verify(service, never()).updateById(any(Agent.class));
        verify(service, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("registerOrGet：同名但角色不一致 → 抛 BizException")
    void shouldThrowWhenRoleMismatch() {
        Agent existing = new Agent();
        existing.setId(13L);
        existing.setName("inner-loop-reviewer");
        existing.setRole(AgentRole.REVIEWER);
        existing.setStatus(AgentStatus.ACTIVE);

        AgentService service = serviceWithNameQuery(existing);

        assertThatThrownBy(() -> service.registerOrGet("inner-loop-reviewer", AgentRole.EXECUTOR, "desc"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法以");
    }

    @Test
    @DisplayName("registerOrGet：不存在 → 委派 register 创建")
    void shouldDelegateToRegisterWhenNotFound() {
        AgentService service = serviceWithNameQuery(null);
        Agent created = new Agent();
        created.setId(14L);
        doReturn(created).when(service).register("brand-new", AgentRole.EXECUTOR, "desc");

        Agent result = service.registerOrGet("brand-new", AgentRole.EXECUTOR, "desc");

        assertThat(result).isSameAs(created);
        verify(service).register("brand-new", AgentRole.EXECUTOR, "desc");
    }
}
