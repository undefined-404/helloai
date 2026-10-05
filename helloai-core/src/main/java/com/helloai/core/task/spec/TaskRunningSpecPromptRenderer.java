package com.helloai.core.task.spec;

import java.util.Map;

/**
 * {@link TaskRunningSpec} → 执行 Prompt 全局上下文段的渲染器。
 *
 * <p>自 {@code TaskRunningSpecServiceImpl} 抽出（§38 Prompt 规范：复杂 Prompt 不应散落在
 * Service 内拼接字符串；§8.3：ServiceImpl 不应承担「协议构造」职责）。本类为<b>纯函数</b>——
 * 只依赖 {@link TaskRunningSpec} 的值，无状态、无 Spring 依赖，可被任意执行侧装配点复用。</p>
 *
 * <p>渲染形态（保持与历史输出逐字一致，避免下游 Prompt 漂移）：</p>
 * <ul>
 *   <li>{@code ## 任务全局上下文（Task Running Spec）} + {@code ### 总体目标} / {@code ### 平台约束}
 *       / {@code ### 全局进度与关键事实}</li>
 *   <li>任务契约存在时追加 {@code ## 任务契约} 二级节，全局注入所有下游执行 Prompt</li>
 * </ul>
 *
 * <p>子任务执行记录明细不在此铺开：依赖上下文由调用方按 {@code dependsOnIdList} 经
 * {@code findRecord} 逐条收集渲染。</p>
 */
public final class TaskRunningSpecPromptRenderer {

    private TaskRunningSpecPromptRenderer() {
    }

    /** 渲染 Prompt 段；spec 为空时返回空串（调用方无需判空）。 */
    public static String render(TaskRunningSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 任务全局上下文（Task Running Spec）\n");
        TaskBaseline bl = spec.baseline();
        if (bl != null) {
            sb.append("\n### 总体目标\n");
            sb.append(bl.goal()).append('\n');
            if (bl.constraints() != null && !bl.constraints().isBlank()) {
                sb.append("\n### 平台约束\n");
                sb.append(bl.constraints()).append('\n');
            }
        }
        String cs = spec.contextSummary();
        if (cs != null && !cs.isBlank()) {
            sb.append("\n### 全局进度与关键事实\n");
            sb.append(cs).append('\n');
        }
        Map<String, Object> contract = spec.contract();
        if (contract != null && !contract.isEmpty()) {
            sb.append("\n## 任务契约\n\n");
            Object title = contract.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                sb.append("契约来源：").append(title).append("\n\n");
            }
            Object content = contract.get("content");
            if (content != null && !String.valueOf(content).isBlank()) {
                sb.append(content).append('\n');
            }
        }
        return sb.toString();
    }
}
