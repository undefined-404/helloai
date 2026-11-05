package com.helloai.core.planner.assistant;

import java.util.List;

/**
 * 助理「最近任务与子任务」概览渲染（`PA-15`，纯函数）。
 *
 * <p><b>为什么单独成类</b>：概览是喂给模型的**事实摘要**，超预算必须可预测地截断；
 * 把它写成纯函数（输入结构化 brief、输出文本）才能单测「截断了没有、标注了没有」，
 * 而不是在服务里拼字符串靠肉眼检查。</p>
 *
 * <p><b>预算口径</b>：整段字符数上限由调用方给出（默认 3000）；逐行累加，超限即停止并追加
 * 「（已截断）」标记。只含标题/状态/验收摘要，不含正文（避免挤占上下文）。</p>
 */
public final class AssistantWorkBriefFormatter {

    /** 标题行（无任务时不输出）。 */
    static final String HEADER = "【最近任务与子任务】（平台内数据，回答「在做什么 / 在测什么」以本段为准）";

    /** 截断标记。 */
    static final String TRUNCATED = "（已截断）";

    private AssistantWorkBriefFormatter() {
    }

    /** 任务级摘要。 */
    public record TaskBrief(String title, String status, List<SubTaskBrief> subTasks) {
        public TaskBrief {
            subTasks = subTasks == null ? List.of() : List.copyOf(subTasks);
        }
    }

    /** 子任务级摘要。 */
    public record SubTaskBrief(String title, String status, String acceptance) {
    }

    /**
     * 渲染概览。
     *
     * @param tasks    任务摘要（调用方已按上限取好）
     * @param maxChars 整段字符上限（&lt;=0 视为不限）
     * @return 文本；无任务返回空串
     */
    public static String render(List<TaskBrief> tasks, int maxChars) {
        if (tasks == null || tasks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        boolean truncated = false;
        for (TaskBrief task : tasks) {
            String taskLine = "- [" + nullSafe(task.status()) + "] " + nullSafe(task.title())
                    + "（" + task.subTasks().size() + " 个子任务）\n";
            if (exceeds(sb, taskLine, maxChars)) {
                truncated = true;
                break;
            }
            sb.append(taskLine);
            for (SubTaskBrief sub : task.subTasks()) {
                String subLine = "  - [" + nullSafe(sub.status()) + "] " + nullSafe(sub.title());
                String acceptance = acceptanceSnippet(sub.acceptance());
                if (!acceptance.isEmpty()) {
                    subLine += " ｜ 验收：" + acceptance;
                }
                subLine += '\n';
                if (exceeds(sb, subLine, maxChars)) {
                    truncated = true;
                    break;
                }
                sb.append(subLine);
            }
            if (truncated) {
                break;
            }
        }
        if (truncated) {
            sb.append(TRUNCATED).append('\n');
        }
        return sb.toString().trim();
    }

    private static boolean exceeds(StringBuilder sb, String line, int maxChars) {
        return maxChars > 0 && sb.length() + line.length() > maxChars;
    }

    /** 验收摘要：压平空白并限长，避免一条长验收吃掉整段预算。 */
    static String acceptanceSnippet(String acceptance) {
        if (acceptance == null || acceptance.isBlank()) {
            return "";
        }
        String flat = acceptance.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}