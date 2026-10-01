package com.helloai.core.agent.port;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 子任务只读查询端口（agent 域消费，task 域实现）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：消费方是 <b>agent</b>、
 * 提供方是 <b>task</b>；按依赖链 {@code planner > review > task > agent > system > shared}，
 * agent <b>低于</b> task ⇒ 属「消费方低于提供方」情形 ⇒ 端口落<b>消费方 {@code agent.port}</b>、
 * 适配器落提供方 task 域，实现侧依赖 {@code task → agent} 属<b>顺向合法</b>。</p>
 *
 * <p><b>为什么返回快照而不是实体</b>：若返回 {@code task.entity.SubTask}，消费方仍要
 * import task 实体，{@code agent → task} 反向依赖计数<b>不会下降</b>（只是把 service import
 * 换成 entity import）。故一律经 {@link SubTaskSnapshot} 值对象传递；快照字段<b>按需增长</b>。</p>
 *
 * <p>实现见 {@code task.service.impl.SubTaskQueryPortAdapter}（薄委托 {@code SubTaskService}，
 * <b>不改动任何 SQL、参数顺序与语义</b>）。</p>
 */
public interface SubTaskQueryPort {

    /**
     * 按 ID 读取单个子任务快照。
     *
     * <p>语义与 {@code SubTaskService#getById(Long)} 一致：不存在返回 {@code null}
     * （消费方原本就是「取实体后判空」，故保持 null 语义而非 {@code Optional}）。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 子任务快照；不存在返回 {@code null}
     */
    SubTaskSnapshot findById(Long subTaskId);

    /**
     * 列出最近有变更的子任务（Phase 0 B3 事件对账候选源）。
     *
     * <p>语义与 {@code SubTaskService#listRecentlyChanged(OffsetDateTime, int)} 逐字一致，
     * 仅把返回类型由 task 实体收敛为 {@link SubTaskSnapshot}（对账消费方实际只用
     * {@code id} 与 {@code status} 两个字段）。</p>
     *
     * <p>事件是业务状态的投影，对账必须<b>以业务表为候选源</b>：只扫描最近变更的子任务，
     * 校验其是否发出了与当前状态匹配的终态事件；若以事件流为候选源，埋点失败/缺失的
     * 子任务永远不会进入对账视野。</p>
     *
     * @param since 只统计 update_time &gt;= since 的子任务（对账窗口）
     * @param limit 返回上限（窗口内变更量超限时本轮截断，下一轮继续）
     * @return 最近变更子任务快照列表（按更新时间倒序，绝不返回 null）
     */
    List<SubTaskSnapshot> listRecentlyChanged(OffsetDateTime since, int limit);
}
