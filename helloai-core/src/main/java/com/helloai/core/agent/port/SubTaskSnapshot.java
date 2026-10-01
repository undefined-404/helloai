package com.helloai.core.agent.port;

import com.helloai.common.constant.SubTaskStatus;

import java.util.Map;

/**
 * 子任务只读快照 —— agent 域自有的数据契约，**零 task 实体泄漏**。
 *
 * <p><b>为什么需要它</b>：agent 域多处需要子任务数据，此前直接 import
 * {@code com.helloai.core.task.entity.SubTask}，构成 CODE_STYLE §6 反向依赖
 * （链路 {@code planner > review > task > agent > system > shared}，agent 低于 task）。
 * 改为由 <b>task 域把实体映射为本快照</b>后经服务/端口契约传入，agent 侧不再持有 task 实体。</p>
 *
 * <p><b>归属判据（CODE_STYLE §7.2）</b>：消费方 {@code agent} <b>低于</b>提供方 {@code task}，
 * 故契约落消费方 {@code agent.port}；映射（{@code SubTask → SubTaskSnapshot}）由提供方
 * {@code task} 侧完成，实现侧依赖 {@code task → agent} 属 <b>顺向合法</b>。</p>
 *
 * <p><b>字段按需增长（重要约定）</b>：只纳入 agent 侧实际使用到的字段，避免把 task 实体整体
 * 抄成快照（那只是换名不换耦合）；<b>新增字段一律追加到末尾</b>——record 构造器是位置参数，
 * 追加可保持既有调用点的实参位置稳定，降低误传风险。</p>
 *
 * @param id              子任务 ID
 * @param status          子任务当前状态（事件对账等消费方按状态判定；枚举在 {@code common}，无域耦合）
 * @param taskId          所属主任务 ID
 * @param assignedAgentId 已分配的 Agent ID（可空 —— 未分配时为 {@code null}）
 * @param context         子任务上下文（可空）
 * @param title           子任务标题（产出物化按标题生成文件名；W6 追加）
 */
public record SubTaskSnapshot(Long id, SubTaskStatus status, Long taskId, Long assignedAgentId,
                              Map<String, Object> context, String title) {
}
