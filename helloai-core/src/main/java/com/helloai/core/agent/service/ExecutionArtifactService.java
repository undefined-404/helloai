package com.helloai.core.agent.service;

import com.helloai.core.task.entity.SubTask;

/**
 * 执行产出物化服务：解析 LLM 产出中的 artifact 声明，将内嵌内容落盘为平台附件
 * （物化附件作为执行证据供自动核验与下游读取）。
 */
public interface ExecutionArtifactService {

    /**
     * 物化子任务执行产出：解析 {@code [artifact]} 声明，内嵌内容写入存储并登记附件。
     */
    void materialize(SubTask subTask, Long agentId, String output);
}
