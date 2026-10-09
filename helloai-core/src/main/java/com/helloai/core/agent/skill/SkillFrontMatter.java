package com.helloai.core.agent.skill;

/**
 * 技能规范 md 的 YAML frontmatter 切分工具（纯函数、无状态、<b>不抛异常</b>）。
 *
 * <p><b>为什么需要它</b>：渲染链路用 {@code indexOf("\n---\n")} 切「执行速览 / 详细规范」，
 * 而 md 引入 frontmatter 后，<b>闭合围栏本身就是 {@code \n---\n}</b>，会被先命中，
 * 导致注入内容退化成 frontmatter 块。因此必须<b>先剥 frontmatter、再切分隔符</b>
 * （REF-1.1a）。</p>
 *
 * <p><b>归一化</b>：入参先做 UTF-8 BOM 剥离 + CRLF→LF 归一。后者与既有
 * {@code loadSpeedSummary} 的行尾兼容处理一致且幂等；无 frontmatter 时
 * {@link #stripBody} 原样返回归一化结果，故<b>纯正文 md 的行为与改造前逐字相同</b>。</p>
 *
 * <p><b>失败处理</b>：本类不抛异常，异常形态记在 {@link Split#error()}，由调用方决定降级——
 * 渲染链路取空速览（等价于既有「速览为空 ⇒ 跳过该技能」分支），扫描链路报 corrupt。</p>
 */
public final class SkillFrontMatter {

    private SkillFrontMatter() {
    }

    /** 围栏行：<b>整行等于它</b>才算（避免 `---foo` 被误判为围栏）。 */
    private static final String FENCE = "---";

    /**
     * UTF-8 BOM。用 char 值表达，而非在源码里写不可见字符或 unicode 转义序列——
     * 后者会被 Java 词法分析在解析前处理（<b>注释里也一样</b>），格式稍有不符即编译失败。
     */
    private static final char BOM = 0xFEFF;

    /**
     * 切分结果。
     *
     * @param yaml  frontmatter 的 YAML 文本；无 frontmatter 时为 {@code null}
     * @param body  正文（闭合围栏之后的一切）；无 frontmatter 时为归一化后的全文
     * @param error 异常形态说明；正常时为 {@code null}（此时 {@code body} 可能为空串）
     */
    public record Split(String yaml, String body, String error) {

        public boolean hasFrontMatter() {
            return yaml != null;
        }

        public boolean broken() {
            return error != null;
        }
    }

    /**
     * 切分原文。不抛异常。
     *
     * <p>异常形态（{@code error != null}，此时 {@code body} 为空串）：
     * 围栏未闭合 / frontmatter 为空 / frontmatter 之后紧跟 YAML 文档分隔符。</p>
     */
    public static Split split(String rawContent) {
        String content = normalize(rawContent);
        if (content.isEmpty()) {
            return new Split(null, "", null);
        }
        String[] lines = content.split("\n", -1);
        if (!FENCE.equals(lines[0].trim())) {
            return new Split(null, content, null);
        }
        for (int i = 1; i < lines.length; i++) {
            if (!FENCE.equals(lines[i].trim())) {
                continue;
            }
            String yaml = join(lines, 1, i);
            if (yaml.isBlank()) {
                return new Split(null, "", "frontmatter 为空（--- 围栏之间无内容）");
            }
            int bodyStart = i + 1;
            if (FENCE.equals(firstNonBlankLine(lines, bodyStart))) {
                return new Split(null, "", "frontmatter 之后紧跟 YAML 文档分隔符 ---，围栏定位不可信");
            }
            return new Split(yaml, join(lines, bodyStart, lines.length), null);
        }
        return new Split(null, "", "frontmatter 未闭合：找不到结束围栏 ---");
    }

    /**
     * 渲染链路入口：返回正文，供后续「切分隔符 → 剔 h1 → trim」使用。
     *
     * <p>异常形态返回<b>空串</b>——降级为「速览为空 ⇒ 跳过该技能」，与既有
     * best-effort 语义一致（技能资产质量问题不击穿执行链）。</p>
     */
    public static String stripBody(String rawContent) {
        Split split = split(rawContent);
        return split.broken() ? "" : split.body();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String content = (!raw.isEmpty() && raw.charAt(0) == BOM) ? raw.substring(1) : raw;
        return content.replace("\r\n", "\n");
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String firstNonBlankLine(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return null;
    }
}
