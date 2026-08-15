package com.helloai.core.agent.service;

import com.helloai.core.agent.output.ParsedOutput;
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

    /**
     * 物化已解析结果（方案3：与 displayText 写入共用一次解析，避免重复解析）。
     *
     * @param parsed 调用方已解析的产出（含 files 与 displayText）
     */
    void materialize(SubTask subTask, Long agentId, ParsedOutput parsed);

    /**
     * 物化开关状态（{@code helloai.storage.enabled}）；关闭时调用方应保持 output 原文写入。
     */
    boolean isEnabled();
}
