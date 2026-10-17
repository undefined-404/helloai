package com.helloai.core.shared.util;

import java.util.Map;

/**
 * 子任务执行产出读取工具。
 *
 * <p>统一读取子任务 context 中的 {@code lastExecution.output}（执行成功回写时由
 * {@code ExecutionResultHandler} 写入），供执行上下文注入、交付物聚合、最终整合报告
 * 等消费方复用，避免各消费方各自复制解析逻辑导致口径漂移。</p>
 *
 * <p>入参为子任务 context（{@code subTask.getContext()}）而非 task 域实体本身，
 * 以保持 shared 域为叶子域、不反向依赖 task 域。</p>
 */
public final class SubTaskOutputExtractor {

    private SubTaskOutputExtractor() {
    }

    /**
     * 读取子任务最近一次成功执行的产出正文；无产出（未执行/失败/缺字段）返回 null。
     *
     * <p>★契约边界（去实体化后）：仅保证「{@code context} 为 null ⇒ 返回 null」。
     * 参数已由 task 域实体收窄为 context 映射 —— 调用方需**自行保证来源实体**
     * （{@code subTask} / {@code dep} 等）非空后再取其 {@code getContext()}
     * （旧签名 {@code extractExecutionOutput(SubTask)} 首行的
     * {@code if (subTask == null) return null;} 守卫已随之移除）。</p>
     *
     * @param context 子任务 context（{@code subTask.getContext()}），可为 null
     * @return 产出文本；不存在时返回 null
     */
    public static String extractExecutionOutput(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        if (context.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output instanceof String text) {
                return text;
            }
        }
        return null;
    }
}
