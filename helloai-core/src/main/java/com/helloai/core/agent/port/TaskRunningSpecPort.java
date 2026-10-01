package com.helloai.core.agent.port;

/**
 * Task Running Spec 执行记录端口（agent 域消费，task 域实现，W7 新增）。
 *
 * <p><b>为什么需要它</b>：MCP 前置产出摘要（{@code getDepsSummary}）要用
 * {@code task.spec.ExecutionRecord} 的摘要文本，此前 agent 侧直接 import
 * {@code task.spec.ExecutionRecord} + {@code task.service.TaskRunningSpecService}，
 * 构成 CODE_STYLE §6 反向依赖 {@code agent → task}。</p>
 *
 * <p><b>为什么契约只暴露摘要字符串</b>：消费方实际只读 {@code record.summary()} 一个字段。
 * 按「端口返回类型按全部消费方实际需要收敛」（W6 固化）的判据，直接返回摘要文本，
 * 既不引入 {@code ExecutionRecord} 类型、也不为它另建快照 record —— 少一个类型就少一处漂移面。
 * 后续若真出现需要更多字段的消费方，再加方法即可（端口「扩方法」不新增适配器文件）。</p>
 *
 * <p><b>归属判据（§7.2）</b>：消费方 {@code agent} 低于提供方 {@code task}，契约落
 * {@code agent.port}；提供方适配器依赖 {@code task → agent} 属顺向合法。</p>
 */
public interface TaskRunningSpecPort {

    /**
     * 取指定子任务在指定主任务下的执行记录摘要。
     *
     * <p>语义与 {@code TaskRunningSpecService#findRecord(Long, Long)} 一致：
     * 记录不存在返回 {@code null}（消费方原本就判空后再取 {@code summary()}）。</p>
     *
     * @param taskId    主任务 ID
     * @param subTaskId 子任务 ID
     * @return 执行记录摘要；不存在返回 {@code null}
     */
    String findExecutionSummary(Long taskId, Long subTaskId);
}
