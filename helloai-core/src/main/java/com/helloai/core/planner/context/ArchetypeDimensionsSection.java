package com.helloai.core.planner.context;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.TaskArchetype;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拆分维度小节的按原型择一器（`TD-3`）。
 *
 * <p><b>为什么要「六块全在模板里，渲染时择一」</b>：六套拆分维度是<b>提示词内容</b>，不是代码逻辑。
 * 把六块写成 Java 字符串常量会让它脱离 {@code planner-decompose.md}——改一句话要改代码、
 * 评审时看不到逐字差异、也没法与设计文档 §3.1 对读。所以六个子块原样写在模板里，用
 * {@code <!-- ARCHETYPE:X -->…<!-- /ARCHETYPE -->} 包裹，渲染时把非选中块整块剔除。</p>
 *
 * <p><b>为什么缺块要抛异常（fail-close）</b>：若目标原型的小节在模板里缺失（标记写错 / 被误删），
 * 若静默放行，渲染出的提示词会<b>一条拆分维度都没有</b>——模型照样能输出一份看起来正常的 JSON，
 * 但结构是错的，事后极难发现。这是本项目「模板替换后必须验证目标标记存在」判据的落地：
 * 宁可拆解显式失败（可经 {@code planById} 重触发），也不要静默降级。</p>
 *
 * <p>剔除后不留空行堆积：匹配段连同其后的单个换行一并去掉，保持渲染产物的行距稳定。</p>
 */
public final class ArchetypeDimensionsSection {

    /**
     * 块标记：{@code <!-- ARCHETYPE:BUILD -->} … {@code <!-- /ARCHETYPE -->}
     * （第 1 组 = 原型名，第 2 组 = 块内正文；非贪婪 + DOTALL 跨行）。
     */
    private static final Pattern BLOCK = Pattern.compile(
            "<!--\\s*ARCHETYPE:([A-Z]+)\\s*-->\\s*(.*?)\\s*<!--\\s*/ARCHETYPE\\s*-->[ \\t]*\\r?\\n?",
            Pattern.DOTALL);

    /** 连续空行折叠阈值：块被剔除后会留下成片空行，折叠成单个空行保持提示词可读。 */
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");

    private ArchetypeDimensionsSection() {
    }

    /**
     * 只保留 {@code archetype} 对应的小节，其余整块剔除。
     *
     * @param template 已替换其余占位符的模板全文
     * @param archetype 目标原型；{@code null} 按 {@link TaskArchetype#DEFAULT()} 处理
     * @return 择一后的模板全文
     * @throws BizException 目标原型的块在模板内不存在（模板与枚举已失配）
     */
    public static String select(String template, TaskArchetype archetype) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        TaskArchetype target = archetype != null ? archetype : TaskArchetype.DEFAULT();
        Matcher matcher = BLOCK.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            // 刻意不用 TaskArchetype.parse：它对未知值回落 BUILD，会把错标记误记成「BUILD 块存在」
            String name = matcher.group(1);
            seen.add(name);
            // 只替换为块内正文（不含 HTML 标记本身）——标记对模型是噪声，剔除后提示词更干净
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(name.equals(target.name()) ? matcher.group(2) : ""));
        }
        matcher.appendTail(out);
        if (!seen.contains(target.name())) {
            throw new BizException("拆解 Prompt 模板缺少 " + target.name()
                    + " 的拆分维度小节（标记 <!-- ARCHETYPE:" + target.name() + " --> 未找到），"
                    + "拒绝以无拆分维度的提示词执行拆解");
        }
        return BLANK_RUN.matcher(out.toString()).replaceAll("\n\n");
    }
}
