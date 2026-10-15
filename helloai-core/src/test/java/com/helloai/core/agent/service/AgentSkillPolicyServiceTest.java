package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.AgentSkillDeriver;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.common.constant.AgentAccessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSkillPolicyService} 能力谓词校验 / 推导单测（`M-9`）。
 *
 * <p>覆盖：越界项（thinking←reasoning、web-search←webSearch）仍抛 {@code BizException(400)} 且
 * message 逐字含「模型 X 不支持技能: …」/ 恒真作业类型技能放行 / 自定义技能豁免 /
 * 未识别模型降级放行 / modelType 与 skills 空值放行 / 多越界项聚合。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentSkillPolicyServiceTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;
    @Mock
    private AgentSkillSpecService agentSkillSpecService;

    private static final String MODEL = "deepseek:deepseek-v4-flash";

    private AgentSkillPolicyService service() {
        return new AgentSkillPolicyService(agentMapper, llmProviderModelQueryService, agentSkillSpecService);
    }

    private void stubCaps(String modelType, boolean reasoning, boolean webSearch) {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(modelType))
                .thenReturn(new LlmProviderModelProfile(
                        new ModelCapabilities(List.of("text"), List.of("text"), reasoning, true, webSearch)));
    }

    @Test
    @DisplayName("越界①：webSearch=false 模型 + 显式 web-search ⇒ BizException(400)，message 逐字")
    void webSearchNotSupportedThrows400() {
        stubCaps(MODEL, true, false);
        assertThatThrownBy(() -> service().validateAgentSkills(MODEL, List.of("shell", "web-search")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模型 " + MODEL + " 不支持技能: web-search")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("越界②：reasoning=false 模型 + 显式 thinking ⇒ BizException(400)（裁定①新增拒绝面）")
    void reasoningNotSupportedThrows400() {
        stubCaps(MODEL, false, true);
        assertThatThrownBy(() -> service().validateAgentSkills(MODEL, List.of("thinking")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持技能: thinking")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("放行①：webSearch=true 模型 + web-search 通过")
    void webSearchSupportedPasses() {
        stubCaps(MODEL, true, true);
        assertThatCode(() -> service().validateAgentSkills(MODEL, List.of("web-search")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("放行②：reasoning=true 模型 + thinking 通过")
    void reasoningSupportedPasses() {
        stubCaps(MODEL, true, false);
        assertThatCode(() -> service().validateAgentSkills(MODEL, List.of("thinking")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("放行③：作业类型技能恒真（最弱模型下 python/docker/sql/java/shell/code-review 全通过）")
    void jobTypeSkillsAlwaysPass() {
        stubCaps(MODEL, false, false);
        assertThatCode(() -> service().validateAgentSkills(MODEL,
                List.of("shell", "docker", "sql", "code-review", "python", "java")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("放行④：自定义技能豁免（非标准词表）")
    void customSkillExempted() {
        stubCaps(MODEL, false, false);
        assertThatCode(() -> service().validateAgentSkills(MODEL, List.of("kubernetes", "golang")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("放行⑤：未识别模型（profile == null）降级放行，即使是 web-search")
    void unknownModelPasses() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType("legacy:old-model")).thenReturn(null);
        assertThatCode(() -> service().validateAgentSkills("legacy:old-model", List.of("web-search", "thinking")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("放行⑥：modelType null/blank 或 skills 空 ⇒ 提前返回")
    void nullModelTypeOrEmptySkillsPass() {
        AgentSkillPolicyService s = service();
        assertThatCode(() -> s.validateAgentSkills(null, List.of("web-search"))).doesNotThrowAnyException();
        assertThatCode(() -> s.validateAgentSkills("  ", List.of("web-search"))).doesNotThrowAnyException();
        assertThatCode(() -> s.validateAgentSkills(MODEL, null)).doesNotThrowAnyException();
        assertThatCode(() -> s.validateAgentSkills(MODEL, List.of("  "))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("多越界项聚合：message 同时含 thinking 与 web-search（保序）")
    void multipleInvalidSkillsAggregated() {
        stubCaps(MODEL, false, false);
        assertThatThrownBy(() -> service().validateAgentSkills(MODEL, List.of("thinking", "shell", "web-search")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持技能: thinking, web-search");
    }

    @Test
    @DisplayName("越界 + 自定义混合：错误信息只含越界项，不含自定义技能")
    void mixedInvalidAndCustomReportsOnlyInvalid() {
        stubCaps(MODEL, false, false);
        assertThatThrownBy(() -> service().validateAgentSkills(MODEL, List.of("kubernetes", "web-search")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持技能: web-search")
                .hasMessageNotContaining("kubernetes");
    }

    @Test
    @DisplayName("推导①：reasoning=false ⇒ 结果不含 thinking")
    void deriveExcludesThinkingWhenNoReasoning() {
        stubCaps(MODEL, false, false);
        Agent agent = apiKeyAgent();
        List<String> skills = service().deriveSkillsForRegistration(agent, null);
        assertThat(skills).contains("code-review").doesNotContain("thinking");
    }

    @Test
    @DisplayName("推导②：reasoning=true ⇒ 结果含 thinking（锁定不可取消）")
    void deriveIncludesThinkingWhenReasoning() {
        stubCaps(MODEL, true, false);
        List<String> skills = service().deriveSkillsForRegistration(apiKeyAgent(), null);
        assertThat(skills).contains("thinking", "code-review");
    }

    @Test
    @DisplayName("推导③：未识别模型走基础推导（不净化，保留显式）")
    void deriveFallsBackForUnknownModel() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType("legacy:old-model")).thenReturn(null);
        Agent agent = apiKeyAgent();
        agent.setModelType("legacy:old-model");
        assertThat(service().deriveSkillsForRegistration(agent, List.of("python")))
                .containsExactly("python");
    }

    @Test
    @DisplayName("推导④：非 API_KEY_LLM 走基础推导（显式优先）")
    void deriveNonApiKeyLlmUsesPlainDerive() {
        Agent agent = apiKeyAgent();
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        assertThat(service().deriveSkillsForRegistration(agent, null))
                .containsExactlyElementsOf(AgentSkillDeriver.derive(
                        AgentAccessType.CLI_CLIENT, agent.getName(), agent.getRemark(), null));
    }

    @Test
    @DisplayName("推导⑤：agent == null ⇒ 空列表")
    void deriveNullAgentReturnsEmpty() {
        assertThat(service().deriveSkillsForRegistration(null, List.of("shell"))).isEmpty();
    }

    private static Agent apiKeyAgent() {
        Agent agent = new Agent();
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setName("deepseek");
        agent.setRemark("通用分析");
        agent.setModelType(MODEL);
        return agent;
    }
}
