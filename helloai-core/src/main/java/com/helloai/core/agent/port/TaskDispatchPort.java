package com.helloai.core.agent.port;

import java.util.List;

/**
 * 子任务分发端口（agent 域提供，task 域消费）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：本端口原先落
 * {@code task.port}，属检验判据时发现的<b>反教科书反例</b>——消费方 {@code task}
 * <b>高于</b>提供方 {@code agent}，端口却放在消费方，强迫提供方反向依赖 {@code task}
 * （即存量 {@code agent->task} 的一笔）。按完整判据（「让依赖落在顺向那一侧；
 * 端口放在顺向依赖的被依赖方」）归位到<b>提供方</b> {@code agent.port}：由 {@code agent}
 * 自身（{@code ResilientDispatcher}）实现，消费方 {@code task} 依赖它 = {@code task → agent}，
 * 属<b>顺向合法</b>（2026-10-01 W8 归位）。</p>
 *
 * <p>实现侧语义（{@code ResilientDispatcher}）：首选 Agent fast-fail（SLEEPING/OFFLINE/
 * 心跳陈旧/执行密集不匹配）+ per-agent 熔断 + fallback 同角色替代选人。</p>
 *
 * <p>端口契约不引用 task 域类型：任务级选人约束以纯数据 record
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
     * 派发前置：作废该子任务<b>在飞</b>的平台执行记录（旧执行的结果不再被采信）。
     *
     * <p><b>为什么挂在本端口</b>：这是「派发」这一动作的前置条件，不是通用执行记录管理——
     * 只有准备<b>顶替</b>旧执行时才需要。端口方向与 {@link #assignNext} 相同
     * （agent 域提供、task 域消费），不新增反向依赖。</p>
     *
     * <p><b>不做的后果（2026-10-25 实测）</b>：子任务被 {@code block()} 后改派，
     * 旧记录仍 RUNNING ⇒ {@code ExecutionCommandService} 的「已有进行中的执行记录」守卫
     * 直接拒建命令 ⇒ 改派 100% 失败，子任务停在 ASSIGNED 无命令可消费，
     * 连 10 分钟的 {@code AssignedSubTaskTimeoutTask} 回收也会被同一条记录二次挡住。</p>
     *
     * <p>实现侧口径（{@code ResilientDispatcher}）：只收口平台内记录
     * （{@code access_type} 为空或 {@code API_KEY_LLM}），外部 Agent 的 attempt 行不碰；
     * 旧进程若仍在跑，其终态写入会被执行记录的 CAS 拒绝、结果回写会被子任务状态守卫丢弃。</p>
     *
     * @param subTaskId 子任务 ID
     * @param reason    作废原因（审计口径，落执行记录 error_msg）
     * @return 实际收口的记录条数；0 表示本就没有在飞记录（正常路径）
     */
    int releaseInFlightExecution(Long subTaskId, String reason);

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
