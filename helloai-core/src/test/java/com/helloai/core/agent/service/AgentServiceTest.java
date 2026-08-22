package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentDutyLeaseMapper;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.mapper.ConversationArchiveMapper;
import com.helloai.core.agent.mapper.ConversationMessageMapper;
import com.helloai.core.agent.port.AgentAuthPort;
import com.helloai.core.agent.service.impl.AgentServiceImpl;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.task.service.ActivityLogService;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.system.crypto.AgentApiKeyCipher;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private SubTaskService subTaskService;
    @Mock
    private RewardService rewardService;
    @Mock
    private ActivityLogService activityLogService;
    @Mock
    private AgentInboxMapper agentInboxMapper;
    @Mock
    private AgentDutyLeaseMapper agentDutyLeaseMapper;
    @Mock
    private AgentExecutionRecordMapper agentExecutionRecordMapper;
    @Mock
    private ConversationArchiveMapper conversationArchiveMapper;
    @Mock
    private ConversationMessageMapper conversationMessageMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private AgentMcpServerService agentMcpServerService;
    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;
    @Mock
    private AgentApiKeyCipher agentApiKeyCipher;

    private AgentService newSpyService() {
        return spy(new AgentServiceImpl(subTaskService, rewardService, activityLogService,
                agentInboxMapper, agentDutyLeaseMapper,
                agentExecutionRecordMapper, conversationArchiveMapper, conversationMessageMapper,
                agentMcpServerService, agentApiKeyCipher,
                new AgentCredentialService(agentMapper, agentApiKeyCipher),
                new AgentSkillPolicyService(agentMapper, llmProviderModelQueryService),
                new AgentLifecycleService(agentMapper, taskTimelineService),
                new AgentStatsService(agentMapper, subTaskService, rewardService, activityLogService)));
    }

    @Test
    @DisplayName("getRelatedCounts：计数值必须是 Integer（Controller 侧 (Integer) 强转，Long 会 500）")
    void shouldReturnIntegerCounts() {
        AgentService service = newSpyService();
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setName("agent-a");
        // 阶段五：getRelatedCounts 收口到 AgentStatsService，经 AgentMapper 取 Agent
        when(agentMapper.selectById(1L)).thenReturn(agent);

        when(subTaskService.countByAssignedAgent(1L)).thenReturn(3L);
        when(subTaskService.countReviewByReviewerAgent(1L)).thenReturn(2L);
        when(rewardService.countByAgent(1L)).thenReturn(5L);
        when(activityLogService.countByAgent(1L)).thenReturn(7L);

        Map<String, Object> counts = service.getRelatedCounts(1L);

        assertThat(counts.get("agentId")).isEqualTo(1L);
        assertThat(counts.get("agentName")).isEqualTo("agent-a");
        // 回归断言：四个计数必须是 Integer 实例，且数值正确
        assertThat(counts.get("subTaskCount")).isInstanceOf(Integer.class).isEqualTo(3);
        assertThat(counts.get("reviewCount")).isInstanceOf(Integer.class).isEqualTo(2);
        assertThat(counts.get("rewardCount")).isInstanceOf(Integer.class).isEqualTo(5);
        assertThat(counts.get("activityCount")).isInstanceOf(Integer.class).isEqualTo(7);
    }

    @Test
    @DisplayName("getRelatedCounts：Agent 不存在抛 BizException")
    void shouldThrowWhenAgentNotFound() {
        AgentService service = newSpyService();
        // statsService 经 AgentMapper 取 Agent，@Mock 默认返回 null 即触发不存在分支
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getRelatedCounts(999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Agent 不存在");
    }

    // ════════════════════════════════════════════════════════════
    //  AgentAuthPort.validateApiKey（认证内核；由 system 域 AuthService 下沉）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateApiKey：无效 Key 抛 401")
    void validateApiKey_invalid_shouldThrow401() {
        AgentService service = newSpyService();
        doReturn(null).when(service).getByApiKey("bad-key");

        BizException ex = assertThrows(BizException.class,
                () -> ((AgentAuthPort) service).validateApiKey("bad-key"));
        assertThat(ex.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("validateApiKey：已禁用 Agent 抛 403")
    void validateApiKey_disabled_shouldThrow403() {
        AgentService service = newSpyService();
        Agent agent = new Agent();
        agent.setId(2001L);
        agent.setStatus(AgentStatus.DISABLED);
        doReturn(agent).when(service).getByApiKey("disabled-key");

        BizException ex = assertThrows(BizException.class,
                () -> ((AgentAuthPort) service).validateApiKey("disabled-key"));
        assertThat(ex.getCode()).isEqualTo(403);
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

    // ════════════════════════════════════════════════════════════
    //  validateAgentSkills / deriveSkillsForRegistration（能力驱动）
    // ════════════════════════════════════════════════════════════

    private LlmProviderModel capability(String modelType, List<String> capability, List<String> available) {
        String[] parts = modelType.split(":", 2);
        LlmProviderModel m = new LlmProviderModel();
        m.setProviderCode(parts[0]);
        m.setModelName(parts[1]);
        m.setCapabilitySkills(capability);
        m.setAvailableOptionalSkills(available);
        return m;
    }

    @Test
    @DisplayName("validateAgentSkills：标准技能超出模型白名单 → 抛 BizException")
    void validateSkills_standardSkillOutOfWhitelist_throws() {
        AgentService service = newSpyService();
        when(llmProviderModelQueryService.findCapabilityByModelType("deepseek:deepseek-v4-flash"))
                .thenReturn(Optional.of(capability("deepseek:deepseek-v4-flash",
                        List.of("thinking"), List.of("shell", "code-review"))));

        assertThatThrownBy(() -> service.validateAgentSkills(
                "deepseek:deepseek-v4-flash", List.of("python", "shell")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持技能")
                .hasMessageContaining("python");
    }

    @Test
    @DisplayName("validateAgentSkills：白名单内标准技能 + 自定义技能 → 通过")
    void validateSkills_whitelistAndCustom_pass() {
        AgentService service = newSpyService();
        when(llmProviderModelQueryService.findCapabilityByModelType("deepseek:deepseek-v4-flash"))
                .thenReturn(Optional.of(capability("deepseek:deepseek-v4-flash",
                        List.of("thinking"), List.of("shell", "code-review"))));

        service.validateAgentSkills("deepseek:deepseek-v4-flash",
                List.of("thinking", "shell", "kubernetes"));
    }

    @Test
    @DisplayName("validateAgentSkills：未识别模型 / modelType 为空 / skills 为空 → 直接放行")
    void validateSkills_unknownOrEmpty_pass() {
        AgentService service = newSpyService();
        when(llmProviderModelQueryService.findCapabilityByModelType("legacy:old-model"))
                .thenReturn(Optional.empty());

        service.validateAgentSkills("legacy:old-model", List.of("python"));   // 未识别模型放行
        service.validateAgentSkills(null, List.of("python"));                 // modelType 空放行
        service.validateAgentSkills("  ", List.of("python"));
        service.validateAgentSkills("deepseek:deepseek-v4-flash", null);       // skills 空放行
        service.validateAgentSkills("deepseek:deepseek-v4-flash", List.of(" "));
    }

    @Test
    @DisplayName("deriveSkillsForRegistration：API_KEY_LLM + 已识别模型 → 能力驱动（thinking 锁定 + 白名单过滤）")
    void deriveSkills_capabilityDriven() {
        AgentService service = newSpyService();
        when(llmProviderModelQueryService.findCapabilityByModelType("deepseek:deepseek-v4-flash"))
                .thenReturn(Optional.of(capability("deepseek:deepseek-v4-flash",
                        List.of("thinking"), List.of("shell", "code-review"))));

        Agent agent = new Agent();
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setName("planner");
        agent.setRemark("计划编排");
        agent.setModelType("deepseek:deepseek-v4-flash");

        List<String> skills = service.deriveSkillsForRegistration(agent, List.of("thinking", "python", "shell"));

        assertThat(skills).containsExactly("thinking", "code-review", "shell");
    }

    @Test
    @DisplayName("deriveSkillsForRegistration：API_KEY_LLM + 未识别模型 → A2 原推导（code-review 兜底）")
    void deriveSkills_unknownModel_fallsBackToDerive() {
        AgentService service = newSpyService();
        when(llmProviderModelQueryService.findCapabilityByModelType("legacy:old-model"))
                .thenReturn(Optional.empty());

        Agent agent = new Agent();
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setName("legacy");
        agent.setModelType("legacy:old-model");

        List<String> skills = service.deriveSkillsForRegistration(agent, List.of("python"));

        // 显式优先（ 语义）：非空显式值原样返回
        assertThat(skills).containsExactly("python");
    }

    @Test
    @DisplayName("deriveSkillsForRegistration：非 API_KEY_LLM 走 A2 原推导；agent 为 null 返回空")
    void deriveSkills_cliClientAndNull() {
        AgentService service = newSpyService();

        Agent cli = new Agent();
        cli.setAccessType(AgentAccessType.CLI_CLIENT);
        cli.setName("executor");

        assertThat(service.deriveSkillsForRegistration(cli, null))
                .containsExactly("shell");   // CLI_CLIENT 基础技能
        assertThat(service.deriveSkillsForRegistration(null, List.of("docker"))).isEmpty();
    }
}
