package com.helloai.core.agent.port;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Builder;

/**
 * Agent 画像只读快照（agent 域对外只读投影）。
 *
 * <p><b>契约归属（CODE_STYLE §7.2 情形②「消费方高于提供方 ⇒ 契约放提供方」）</b>：
 * 提供方为 agent 域，消费方为 planner / review / task 三域。三域只读消费 Agent 的九项
 * 画像字段，将其收口为本快照后，依赖方向由 {@code planner/review/task → agent.port}
 * 的顺向只读依赖表达，<b>不再 import 提供方实体</b> {@code agent.entity.Agent}。</p>
 *
 * <p><b>字段范围（刻意最小）</b>：只纳入三域 22 个消费站点实际读取的九项——
 * {@code id / name / role / accessType / status / onlineStatus / score / modelType /
 * localExecutionCapable}；<b>不含</b> {@code apiKey / modelConfig / skills / labels /
 * lastSeenTime} 等未读字段（避免契约面被动膨胀，也避免把凭证/配置类敏感字段带出域外）。</p>
 *
 * <p><b>派生字段</b>：{@code localExecutionCapable} 由提供方派生，取值口径
 * = {@code AgentCapability.hasLocalExecutionCapability(entity)}（CLI_CLIENT / WEB_BROWSER
 * 天然具备；API_KEY_LLM 需 {@code capabilities.supportsMCP=true}），<b>null 实体视为 true</b>。
 * 消费方直接读派生布尔，无需复制 agent 域判定规则。</p>
 *
 * @see com.helloai.core.agent.service.impl.AgentProfileSnapshotMapper
 */
@Builder
public record AgentProfileSnapshot(
        Long id,
        String name,
        AgentRole role,
        AgentAccessType accessType,
        AgentStatus status,
        AgentOnlineStatus onlineStatus,
        Integer score,
        String modelType,
        boolean localExecutionCapable
) {
}
