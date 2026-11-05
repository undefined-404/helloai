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
        Agent agent = agent(AgentAccessType.CLI_CLIENT, "deepseek:deepseek-v4-flash");
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
        Agent agent = agent(AgentAccessType.API_KEY_LLM, "deepseek:deepseek-v4-flash");
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
        Agent agent = agent(AgentAccessType.CLI_CLIENT, "deepseek:deepseek-v4-flash");
        agent.setOnlineStatus(AgentOnlineStatus.IDLE);

        AgentAccessTypeSwitch.apply(agent, AgentAccessType.CLI_CLIENT);
        AgentAccessTypeSwitch.apply(agent, null);

        assertThat(agent.getOnlineStatus()).isEqualTo(AgentOnlineStatus.IDLE);
        assertThat(agent.getCapabilities()).isNull();
    }
}