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
     * 注册时的类型推导（`G-040`）。
     *
     * <p><b>为什么注册要推导而编辑不用</b>：编辑入口的类型是用户在表单里显式选的（缺省即「不改」），
     * 而注册入口的 {@code accessType} 允许留空 —— 留空时若沿用 {@code register} 的
     * {@code CLI_CLIENT} 默认值，再配上同时写入的 {@code modelType}，就得到一个
     * 「外部 CLI 客户端却绑着平台托管模型」的非法组合：{@code usesCredentialVault()} 为 false
     * ⇒ 执行时不去凭证库取 key，{@code requiresRuntimeLiveness()} 为 true ⇒ 调度器要求心跳在线。
     * 该 Agent 建得出来、但永远跑不起来，且报错点在很远的下游。</p>
     *
     * <p><b>推导规则（有模型即内部 LLM）</b>：显式给了 {@code accessType} 就用给的（含非法组合的
     * 反向校验，见 {@link #validateCombination}）；没给则 {@code modelType} 非空判
     * {@code API_KEY_LLM}、为空判 {@code CLI_CLIENT}。判据取自「谁填了 modelType」——
     * 只有内部 LLM 才需要模型，这个信号比「前端有没有记得传 accessType」可靠。</p>
     *
     * @param explicit  调用方显式声明的类型，可为 null（表示交给推导）
     * @param modelType 本次要写入的 modelType，可为 null/blank
     * @return 落库用的接入类型，绝不为 null
     */
    public static AgentAccessType deriveForRegistration(AgentAccessType explicit, String modelType) {
        boolean hasModel = modelType != null && !modelType.isBlank();
        if (explicit != null) {
            return explicit;
        }
        return hasModel ? AgentAccessType.API_KEY_LLM : AgentAccessType.CLI_CLIENT;
    }

    /**
     * 反向校验：类型与模型/接入方式是否自相矛盾（`G-040` ②）。
     *
     * <p>{@code CLI_CLIENT} 不允许带 {@code modelType} —— 外部 CLI 客户端自带模型，平台侧的
     * {@code model_type} 对它无意义，写了只会让人误以为「平台在替它选模型」。反向不做
     * 「{@code API_KEY_LLM} 必须有 modelType」的校验：注册允许先建后绑（编辑入口
     * {@link #apply} 已对切换场景做了这条硬校验），此处只拦「静默建出不可能跑的组合」。</p>
     *
     * @param accessType 即将落库的接入类型
     * @param modelType  即将落库的 modelType，可为 null/blank
     * @throws BizException 类型与模型自相矛盾
     */
    public static void validateCombination(AgentAccessType accessType, String modelType) {
        if (accessType == AgentAccessType.CLI_CLIENT
                && modelType != null && !modelType.isBlank()) {
            throw new BizException(400,
                    "CLI_CLIENT 接入类型不允许绑定 modelType（外部 CLI 客户端自带模型）；"
                            + "如需用平台托管模型，请把 accessType 设为 API_KEY_LLM");
        }
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