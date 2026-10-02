package com.helloai.core.agent.port;

/**
 * 子任务「不确定性申报」只读快照 —— agent 域自有的数据契约，**零 task 实体泄漏**。
 *
 * <p>对应 {@code task.entity.Uncertainty}（JSONB 列 {@code sub_task.uncertainties} 的元素）。
 * 此前 agent 侧直接引用 task 实体（{@code McpToolService.SubTaskDetail.uncertainties}），
 * 2026-09-11 曾以「§6.1 豁免记录」显式豁免，并注明「回收方向：随 §6.1 AgentRuntime 改造
 * 统一处理」——本类即该豁免的**回收落点**（2026-10-01 W7）。</p>
 *
 * <p><b>为什么不复制 {@code kind} 常量</b>：task 侧 {@code Uncertainty.KIND_ASSUMPTION} /
 * {@code KIND_UNCONFIRMED} 的常量语义必须与拆解写入、执行注入、审查核验链**保持单源**。
 * 快照只做**透传投影**（{@code kind} / {@code note} 原样搬），不在 agent 侧定义任何常量、
 * 不做 kind 归一化或校验，从而既摘掉实体依赖，又不引入「双源漂移」——这正是原豁免记录最担心的点。</p>
 *
 * <p><b>{@code assumption} 为什么是「提供方派生」的快照字段（2026-10-01 W11 追加）</b>：
 * 执行 Prompt 装配需按「是否已申报假设」渲染两种不同的行动指引后缀，而该判定就是
 * {@code kind == Uncertainty.KIND_ASSUMPTION}。若让消费方自己比字符串，要么**复制常量**
 * （双源漂移，正是上面刚否掉的），要么**硬编码字面量**（常量值一改就静默失效）。
 * 故由 **task 侧映射器顺带算好**一个布尔透传 —— 与 {@link AttachmentRef#contentLoadable}
 * 完全同款（判定责任留在提供方、消费方零判定、实现细节不外泄）。</p>
 *
 * @param kind       类别（{@code ASSUMPTION} / {@code UNCONFIRMED}；原样透传，不在本域解释）
 * @param note       描述（自由文本，原样透传）
 * @param assumption 是否「已申报假设」（= {@code Uncertainty.KIND_ASSUMPTION.equals(kind)}，
 *                   由 task 侧映射器判定后透传）
 */
public record UncertaintySnapshot(String kind, String note, boolean assumption) {
}
