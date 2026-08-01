package com.helloai.core.shared.util;

import com.helloai.core.task.entity.SubTask;

import java.util.Map;

/**
 * 子任务执行产出读取工具。
 *
 * <p>统一读取 {@code sub_task.context.lastExecution.output}（执行成功回写时由
 * {@code ExecutionResultHandler} 写入），供执行上下文注入、交付物聚合、最终整合报告
 * 等消费方复用，避免各消费方各自复制解析逻辑导致口径漂移。</p>
 */
public final class SubTaskOutputExtractor {

    private SubTaskOutputExtractor() {
    }

    /**
     * 读取子任务最近一次成功执行的产出正文；无产出（未执行/失败/缺字段）返回 null。
     *
     * @param subTask 子任务实体，可为 null
     * @return 产出文本；不存在时返回 null
     */
    public static String extractExecutionOutput(SubTask subTask) {
        if (subTask == null) {
            return null;
        }
        Map<String, Object> ctx = subTask.getContext();
        if (ctx != null && ctx.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output instanceof String text) {
                return text;
            }
        }
        return null;
    }
}
