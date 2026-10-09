package com.helloai.core.agent.skill;

/**
 * 「执行速览」渲染：把规范正文切成「速览」段并去掉文件 h1 标题行。
 *
 * <p>等价于改造前 {@code AgentSkillSpecServiceImpl#loadSpeedSummary} 的
 * 「切分隔符 → 剔 {@code "# "} 行 → trim」三步（REF-1.1a 要求改造前后<b>逐字一致</b>）。
 * 抽成独立纯函数的目的：让「剥离 frontmatter」（{@link SkillFrontMatter}）与
 * 「渲染速览」各自可单测，且两者的组合在无 frontmatter 时是完全恒等的。</p>
 *
 * <p><b>输入约定</b>：{@code body} 必须是已经过 {@link SkillFrontMatter#stripBody} 归一化
 * （BOM 已剥离、CRLF 已转 LF）的正文——本类不再重复归一化。</p>
 */
final class SkillSpeedSummaryRenderer {

    private SkillSpeedSummaryRenderer() {
    }

    /** 规范文件内「执行速览」与「详细规范」的分隔标记（速览在前）。 */
    static final String DETAIL_SEPARATOR = "\n---\n";

    /**
     * @param body 已剥离 frontmatter 的规范正文
     * @return 速览正文（可能为空串）；{@code body} 为 {@code null} 时返回 {@code null}
     */
    static String render(String body) {
        if (body == null) {
            return null;
        }
        String content = body;
        int cut = content.indexOf(DETAIL_SEPARATOR);
        if (cut >= 0) {
            content = content.substring(0, cut);
        }
        StringBuilder summary = new StringBuilder();
        for (String line : content.split("\r?\n")) {
            if (line.startsWith("# ")) {
                continue;
            }
            summary.append(line).append('\n');
        }
        return summary.toString().trim();
    }
}
