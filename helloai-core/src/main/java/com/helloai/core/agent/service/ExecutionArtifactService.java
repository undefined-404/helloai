package com.helloai.core.agent.service;

import com.helloai.core.agent.output.ParsedOutput;

/**
 * 执行产出物化服务：解析 LLM 产出中的 artifact 声明，将内嵌内容落盘为平台附件
 * （物化附件作为执行证据供自动核验与下游读取）。
 *
 * <p><b>契约为「ID 契约」（2026-10-01 W6 改造）</b>：本接口此前以
 * {@code com.helloai.core.task.entity.SubTask} 为形参，构成 CODE_STYLE §6 反向依赖
 * {@code agent → task}。现改为只接收 {@code subTaskId}，由实现侧经
 * {@link com.helloai.core.agent.port.SubTaskQueryPort} 读取 agent 域自有快照。</p>
 *
 * <p><b>为什么取 ID 而不是快照</b>：本服务唯一消费方
 * {@code ExecutionResultHandler} 在 W6 时点<b>仍必须持有可变实体</b>——它要做
 * {@code subTask.setContext(ctx)} + {@code subTaskService.updateById(subTask)} 的读改写回写，
 * 属 W10 才迁移的范围。即「**迁移时点由该类型的消费方决定，不由持有者决定**」
 * （W4 铁律 ② 的反向形态）。取 ID 使消费方零类型改动，且物化发生在**主事务提交后**
 * （afterCommit），由实现侧重读一份**已提交**快照，比沿用提交前的对象图更不易产生脏读。</p>
 */
public interface ExecutionArtifactService {

    /**
     * 物化子任务执行产出：解析 {@code [artifact]} 声明，内嵌内容写入存储并登记附件。
     *
     * @param subTaskId 执行完成的子任务 ID（子任务不存在时静默跳过）
     * @param agentId   上报结果的 Agent id（仅用于时间线记录）
     * @param output    lastExecution.output 原文
     */
    void materialize(Long subTaskId, Long agentId, String output);

    /**
     * 物化已解析结果（方案3：与 displayText 写入共用一次解析，避免重复解析）。
     *
     * @param subTaskId 执行完成的子任务 ID（子任务不存在时静默跳过）
     * @param agentId   上报结果的 Agent id（仅用于时间线记录）
     * @param parsed    调用方已解析的产出（含 files 与 displayText）
     */
    void materialize(Long subTaskId, Long agentId, ParsedOutput parsed);

    /**
     * 物化开关状态（{@code helloai.storage.enabled}）；关闭时调用方应保持 output 原文写入。
     */
    boolean isEnabled();
}
