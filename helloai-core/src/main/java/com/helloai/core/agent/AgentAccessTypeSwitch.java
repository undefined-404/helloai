package com.helloai.core.agent;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.core.agent.entity.Agent;

import java.time.OffsetDateTime;

/**
 * 接入类型切换（编辑入口，纯静态）。
 *
 * <p><b>为什么允许改</b>：注册时选错类型（如给外部 CLI Agent 选了不可用的浏览器接入）此前只能
 * 重建 Agent 并丢历史；放开编辑让「类型选错」可纠正。</p>
 *
 * <p><b>三条规则</b>：</p>
 * <ul>
 *     <li>切到 {@code API_KEY_LLM} 必须已有模型（模型唯一性校验由调用方按角色做，需要查库）；</li>
 *     <li>能力声明按新类型默认值<b>补齐</b>、保留既有显式覆盖（不整体重置，避免丢用户配置）；</li>
 *     <li>切到 {@code CLI_CLIENT} 先判离线：该类型靠运行时心跳驱动在线态，沿用旧类型的新鲜度会让
 *         未打卡的 Agent 被调度选中。</li>
 * </ul>
 */
public final class AgentAccessTypeSwitch {

    private AgentAccessTypeSwitch() {
    }

    /**
     * 应用切换（同类型空操作）。
     *
     * @param agent  待更新实体（就地修改）
     * @param target 目标接入类型
     * @throws BizException 切到内部 LLM 但未绑定模型
     */
    public static void apply(Agent agent, AgentAccessType target) {
        if (agent == null || target == null || target == agent.getAccessType()) {
            return;
        }
        if (target == AgentAccessType.API_KEY_LLM
                && (agent.getModelType() == null || agent.getModelType().isBlank())) {
            throw new BizException("切换为内部 LLM 需先绑定模型（当前 Agent 无 modelType）");
        }
        agent.setAccessType(target);
        agent.setCapabilities(AgentCapability.mergeDefaults(target, agent.getCapabilities()));
        if (target == AgentAccessType.CLI_CLIENT) {
            agent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
            agent.setOfflineReason("access_type_switched");
            agent.setOfflineTime(OffsetDateTime.now());
        }
    }
}