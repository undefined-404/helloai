package com.helloai.core.task.port;

import java.util.List;

/**
 * 子任务分发端口（task↔agent 事件解耦，阶段五）。
 *
 * <p>按 §3.x 依赖方向红线与依赖倒置先例（同 {@code TaskPlannerPickerPort}）：
 * 端口定义在 task 域、由 agent 域 {@code ResilientDispatcher} 实现，
 * task 域（SubTaskDispatchService 及各补偿任务）只依赖本接口，
 * 不再直接引用 agent.dispatcher 具体类。</p>
 *
 * <p>实现侧语义（ResilientDispatcher）：首选 Agent fast-fail（SLEEPING/OFFLINE/
 * 心跳陈旧/执行密集不匹配）+ per-agent 熔断 + fallback 同角色替代选人。</p>
 *
 * <p>端口契约不引用 agent 域类型：任务级选人约束以纯数据 record
 * {@link DispatchConstraints} 表达，agent 域实现侧自行转换为内部约束。</p>
 */
public interface TaskDispatchPort {

    /**
     * 弹性分配任务给指定 Agent（无任务级约束）。
     *
     * @param agentId   目标 Agent ID
     * @param subTaskId 待分配的子任务 ID
     */
    void assignNext(Long agentId, Long subTaskId);

    /**
     * 带任务级选人约束的弹性分配（白名单 + 技能 AND 匹配，fallback 同样受约束）。
     *
     * @param agentId     目标 Agent ID
     * @param subTaskId   待分配的子任务 ID
     * @param constraints 任务级选人约束；null 表示不约束（与旧行为一致）
     */
    void assignNext(Long agentId, Long subTaskId, DispatchConstraints constraints);

    /**
     * 任务级选人约束（纯数据，端口契约）。
     *
     * <p>由任务 {@code agent_policy.executorAgentIds} 与 {@code required_skills}
     * 构建：白名单限定 + 技能 AND 匹配。{@link #of} 在两者均空时返回 null
     * （不约束，等价于 {@code unrestricted} 语义），与历史行为完全一致。</p>
     *
     * @param allowedAgentIds 执行者白名单；null/空 = 不限定
     * @param requiredSkills  任务要求技能；null/空 = 不限定
     */
    record DispatchConstraints(List<Long> allowedAgentIds, List<String> requiredSkills) {

        /** 构建约束；白名单与技能均空时返回 null（不约束，与旧行为一致）。 */
        public static DispatchConstraints of(List<Long> allowedAgentIds, List<String> requiredSkills) {
            boolean noIds = allowedAgentIds == null || allowedAgentIds.isEmpty();
            boolean noSkills = requiredSkills == null || requiredSkills.isEmpty();
            return (noIds && noSkills) ? null : new DispatchConstraints(allowedAgentIds, requiredSkills);
        }
    }
}
