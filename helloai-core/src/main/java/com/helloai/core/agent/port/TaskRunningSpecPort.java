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

    /**
     * 解析 executor 原始输出中的 {@code EXECUTION_RECORD} 块并回填执行记录（W10 新增）。
     *
     * <p><b>为什么是「解析 + 回填」整体不透明，而不是让消费方自己解析</b>：回填用的
     * {@code task.spec.ExecutionRecord} 与 {@code task.spec.ExecutionRecordParser} 都是
     * <b>task 域协议类型</b>；若把它们暴露给消费方，{@code agent → task} 的反向依赖只是
     * 从 {@code service} 换成 {@code spec}，计数不会下降（W1 已证）。故消费方只交出
     * 「原始输出 + 归因标识」，解析、fallback 与落库全部留在 task 域。</p>
     *
     * <p><b>语义</b>（与原 {@code ExecutionResultHandler} 内联块逐字一致）：</p>
     * <ol>
     *     <li>{@code ExecutionRecordParser.parse} 解析成功 ⇒ 以解析结果回填；</li>
     *     <li>解析失败且原始输出非空白 ⇒ 取<b>前 200 字符 + {@code "..."}</b> 作 fallback
     *         summary 回填（并打 warn）；</li>
     *     <li>解析失败且输出为空白 ⇒ 不落任何记录。</li>
     * </ol>
     *
     * <p><b>异常语义</b>：本方法<b>可能抛出异常</b>（如底层存储异常）——「是否降级不阻断主链路」
     * 属<b>调用方的编排决策</b>，故由调用方自行 try-catch（原实现亦如此）。</p>
     *
     * @param taskId    主任务 ID（来自子任务快照，非调用方主键）
     * @param subTaskId 子任务 ID
     * @param title     子任务标题（解析结果与 fallback 记录都要带上）
     * @param agentId   执行 Agent ID
     * @param rawOutput executor 完整原始输出，可为 {@code null}
     */
    void parseAndAppendExecutionRecord(Long taskId, Long subTaskId, String title, Long agentId, String rawOutput);

    /**
     * 构建 executor Prompt 的<b>全局上下文段</b>（W11 新增）。
     *
     * <p>语义与 {@code TaskRunningSpecService#buildExecutorPromptSection(Long)} 逐字一致：
     * 返回 Markdown 格式的「Baseline 全局目标 + Context Summary（全局进度）」章节文本，
     * <b>不含</b>各子任务执行记录明细（明细由消费方按 {@code dependsOn} 经
     * {@link #findExecutionSummary(Long, Long)} 逐条收集）。无内容时返回空串（绝不返回 {@code null}）。</p>
     *
     * <p>消费方：{@code AgentRuntimeContextAssembler} 的 Prompt 装配（W11）。</p>
     *
     * @param taskId 主任务 ID
     * @return Prompt 全局上下文段；无内容返回空串
     */
    String buildExecutorPromptSection(Long taskId);
}
