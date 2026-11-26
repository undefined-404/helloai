package com.helloai.core.agent;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.core.agent.entity.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentAccessTypeSwitch} 单测（编辑入口放开接入类型）。
 */
@DisplayName("AgentAccessTypeSwitch（接入类型切换）")
class AgentAccessTypeSwitchTest {

    private static Agent agent(AgentAccessType type, String modelType) {
        Agent agent = new Agent();
        agent.setAccessType(type);
        agent.setModelType(modelType);
        return agent;
    }

    @Test
    @DisplayName("切到内部 LLM 但未绑定模型 ⇒ 拒收并给出可读原因")
    void rejectsSwitchToLlmWithoutModel() {
        Agent agent = agent(AgentAccessType.CLI_CLIENT, null);

        assertThatThrownBy(() -> AgentAccessTypeSwitch.apply(agent, AgentAccessType.API_KEY_LLM))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需先绑定模型");
        assertThat(agent.getAccessType()).isEqualTo(AgentAccessType.CLI_CLIENT);
    }

    @Test
    @DisplayName("切到内部 LLM 且已有模型 ⇒ 放行，能力默认值补齐且保留既有覆盖")
    void switchesToLlmKeepingOverrides() {
        Agent agent = agent(AgentAccessType.CLI_CLIENT, "deepseek:deepseek-flash");
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("supportsMCP", false);   // 显式覆盖：切换后必须保留
        agent.setCapabilities(overrides);

        AgentAccessTypeSwitch.apply(agent, AgentAccessType.API_KEY_LLM);

        assertThat(agent.getAccessType()).isEqualTo(AgentAccessType.API_KEY_LLM);
        assertThat(agent.getCapabilities()).containsEntry("supportsMCP", false);
        assertThat(agent.getCapabilities().size())
                .isGreaterThanOrEqualTo(AgentAccessType.API_KEY_LLM.defaultCapabilities().size());
    }

    @Test
    @DisplayName("切到 CLI ⇒ 先判离线（等心跳打卡再上线），能力按新类型补齐")
    void switchesToCliAsOffline() {
        Agent agent = agent(AgentAccessType.API_KEY_LLM, "deepseek:deepseek-flash");
        agent.setOnlineStatus(AgentOnlineStatus.ONLINE);

        AgentAccessTypeSwitch.apply(agent, AgentAccessType.CLI_CLIENT);

        assertThat(agent.getAccessType()).isEqualTo(AgentAccessType.CLI_CLIENT);
        assertThat(agent.getOnlineStatus()).isEqualTo(AgentOnlineStatus.OFFLINE);
        assertThat(agent.getOfflineReason()).isEqualTo("access_type_switched");
        assertThat(agent.getOfflineTime()).isNotNull();
        assertThat(agent.getCapabilities())
                .containsEntry("supportsMCP", AgentAccessType.CLI_CLIENT.defaultCapabilities().get("supportsMCP"));
    }

    @Test
    @DisplayName("同类型 / null ⇒ 不改动（编辑弹窗原值提交不产生副作用）")
    void noOpForSameOrNullType() {
        Agent agent = agent(AgentAccessType.CLI_CLIENT, "deepseek:deepseek-flash");
        agent.setOnlineStatus(AgentOnlineStatus.IDLE);

        AgentAccessTypeSwitch.apply(agent, AgentAccessType.CLI_CLIENT);
        AgentAccessTypeSwitch.apply(agent, null);

        assertThat(agent.getOnlineStatus()).isEqualTo(AgentOnlineStatus.IDLE);
        assertThat(agent.getCapabilities()).isNull();
    }

    // ══════════════════════════════════════════════════════════════
    //  `G-040` 注册推导与反向校验
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-040 注册：显式给了 accessType 就用给的，不被 modelType 覆盖")
    void deriveForRegistration_shouldRespectExplicitType() {
        assertThat(AgentAccessTypeSwitch.deriveForRegistration(
                AgentAccessType.API_KEY_LLM, null))
                .isEqualTo(AgentAccessType.API_KEY_LLM);
        // 显式 CLI + 无模型（合法），推导不得把它改成 API_KEY_LLM
        assertThat(AgentAccessTypeSwitch.deriveForRegistration(
                AgentAccessType.CLI_CLIENT, null))
                .isEqualTo(AgentAccessType.CLI_CLIENT);
    }

    @Test
    @DisplayName("G-040 注册：accessType 留空 + 有 modelType ⇒ API_KEY_LLM（不得沿用 register 的 CLI 默认值）")
    void deriveForRegistration_shouldInferLlmWhenModelPresent() {
        assertThat(AgentAccessTypeSwitch.deriveForRegistration(null, "deepseek:deepseek-flash"))
                .isEqualTo(AgentAccessType.API_KEY_LLM);
    }

    @Test
    @DisplayName("G-040 注册：accessType 留空 + 无 modelType ⇒ CLI_CLIENT")
    void deriveForRegistration_shouldInferCliWhenNoModel() {
        assertThat(AgentAccessTypeSwitch.deriveForRegistration(null, null))
                .isEqualTo(AgentAccessType.CLI_CLIENT);
        assertThat(AgentAccessTypeSwitch.deriveForRegistration(null, "   "))
                .isEqualTo(AgentAccessType.CLI_CLIENT);
    }

    @Test
    @DisplayName("G-040 反向校验：CLI_CLIENT 带 modelType ⇒ 400 拒收（非法组合，建得出但跑不起来）")
    void validateCombination_shouldRejectCliWithModel() {
        assertThatThrownBy(() -> AgentAccessTypeSwitch.validateCombination(
                AgentAccessType.CLI_CLIENT, "deepseek:deepseek-flash"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("CLI_CLIENT")
                .hasMessageContaining("modelType");
    }

    @Test
    @DisplayName("G-040 反向校验：CLI 无模型 / LLM 有模型 ⇒ 放行")
    void validateCombination_shouldAllowLegitPairs() {
        AgentAccessTypeSwitch.validateCombination(AgentAccessType.CLI_CLIENT, null);
        AgentAccessTypeSwitch.validateCombination(AgentAccessType.CLI_CLIENT, "  ");
        AgentAccessTypeSwitch.validateCombination(AgentAccessType.API_KEY_LLM, "deepseek:deepseek-flash");
    }

    @Test
    @DisplayName("G-040 全链：留空 + 有模型 ⇒ 推导为 LLM 且反向校验放行（这才是建 LLM 执行器的正常路径）")
    void deriveThenValidate_shouldPassForLlmRegistration() {
        AgentAccessType resolved = AgentAccessTypeSwitch.deriveForRegistration(
                null, "deepseek:deepseek-flash");

        assertThat(resolved).isEqualTo(AgentAccessType.API_KEY_LLM);
        // 不抛即通过
        AgentAccessTypeSwitch.validateCombination(resolved, "deepseek:deepseek-flash");
    }
}