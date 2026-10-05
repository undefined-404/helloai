package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.AgentCapability;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.AgentProfileSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code agent.entity.Agent} → {@code agent.port.AgentProfileSnapshot} 的映射（agent 域侧）。
 *
 * <p><b>为什么由提供方映射</b>：映射方向是 {@code agent → agent.port}（域内同源），
 * 且快照契约已归属提供方 agent 域；集中一处可避免 planner / review / task 三域各自
 * 复制映射口径造成漂移。快照类本身位于 {@code agent.port}，<b>不得</b> import 消费方实体。</p>
 *
 * <p><b>映射纪律</b>：快照是全量<b>读投影</b>，映射<b>不做业务判定</b>——只有一处
 * <b>提供方派生</b>例外：</p>
 * <ul>
 *     <li>{@code localExecutionCapable} = {@link AgentCapability#hasLocalExecutionCapability(Agent)}
 *         （CLI_CLIENT / WEB_BROWSER 天然可本机操作；API_KEY_LLM 需 {@code capabilities.supportsMCP=true}）。
 *         <b>严格口径</b>：能力值非 {@code Boolean.TRUE} 即视为无能力；
 *         <b>null 实体视为 true</b>（与原 {@code hasLocalExecutionCapability(null)==true} 逐字一致）。</li>
 * </ul>
 * <p>其余字段仅逐项原样投影，<b>不解释、不校验、不归一化</b>。</p>
 */
public final class AgentProfileSnapshotMapper {

    private AgentProfileSnapshotMapper() {
    }

    /**
     * 单条映射；入参为 {@code null} 时返回 {@code null}（保持调用方原空值语义）。
     */
    public static AgentProfileSnapshot toSnapshot(Agent agent) {
        if (agent == null) {
            return null;
        }
        return AgentProfileSnapshot.builder()
                .id(agent.getId())
                .name(agent.getName())
                .role(agent.getRole())
                .accessType(agent.getAccessType())
                .status(agent.getStatus())
                .onlineStatus(agent.getOnlineStatus())
                .score(agent.getScore())
                .modelType(agent.getModelType())
                // 提供方派生字段：口径见类 javadoc（null 已在上方提前返回，此处实体非 null）
                .localExecutionCapable(AgentCapability.hasLocalExecutionCapability(agent))
                .build();
    }

    /**
     * 批量映射；入参为 {@code null} / 空时返回空列表（绝不返回 {@code null}），
     * 逐条 {@code null} 元素跳过。
     */
    public static List<AgentProfileSnapshot> toSnapshots(List<Agent> agents) {
        if (agents == null || agents.isEmpty()) {
            return List.of();
        }
        List<AgentProfileSnapshot> snapshots = new ArrayList<>(agents.size());
        for (Agent agent : agents) {
            AgentProfileSnapshot snapshot = toSnapshot(agent);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }
}
