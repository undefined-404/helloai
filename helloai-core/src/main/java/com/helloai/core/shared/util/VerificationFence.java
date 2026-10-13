package com.helloai.core.shared.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内部 LLM 输出契约的 VERIFICATION 围栏解析器（REF-8.2.2 输出契约 / REF-8.4 出口分类）。
 *
 * <p>一个形状同时喂三件事（§1.5.4）：评审要的证据（{@code VERIFICATION:} 段）、做不到通道
 * （{@code STATUS: BLOCKED}）、早停判据（{@code NEXT_STEPS} 重复）。本类只做<b>结构化读取</b>，
 * 不做任何状态迁移。</p>
 *
 * <p><b>唯一事实源</b>：围栏判定（{@code containsMarker}）是评审侧与执行侧<b>共用</b>的匹配器，
 * 防「两处各写一份围栏判定」漂移（`ReviewEvidenceAssembler.verificationSignal` 与本类同源）。</p>
 */
public final class VerificationFence {

    /** 围栏标记（与 {@code ReviewEvidenceAssembler.verificationSignal} 同一匹配器）。 */
    public static final String MARKER = "VERIFICATION:";

    // STATUS 允许 Markdown 前置装饰（`**STATUS: DONE**` / `- STATUS: DONE` / `> STATUS: DONE` 等），
    // 与宽松的 VERIFICATION contains 判定对齐；值取「到空白/冒号/逗号前的词」，兼容中文值（STATUS：完成）。
    private static final Pattern STATUS_PATTERN =
            Pattern.compile("(?im)^\\s*[#>*-]*\\s*STATUS\\s*[:：]?\\s*([^\\s：:*，]+).*$");
    private static final Pattern NEXT_STEPS_PATTERN =
            Pattern.compile("(?i)NEXT_STEPS\\s*[:：]?");

    private VerificationFence() {
    }

    /** 是否携带 VERIFICATION 段（评审侧/执行侧同源匹配，见类 javadoc）。 */
    public static boolean containsMarker(String output) {
        return output != null && output.contains(MARKER);
    }

    /** 解析结果；{@code adequate = present && status 非空}（结构化合格的最低门槛）。 */
    public record Parsed(boolean present, String status, boolean hasNextSteps) {

        public boolean adequate() {
            return present && status != null && !status.isBlank();
        }
    }

    /**
     * 结构化读取围栏：{@code present}（含 VERIFICATION 段）、{@code status}（STATUS 值，
     * 缺失为 {@code null}）、{@code hasNextSteps}（是否含 NEXT_STEPS 段）。缺失/空白返回空结果。
     */
    public static Parsed parse(String output) {
        if (output == null || output.isBlank()) {
            return new Parsed(false, null, false);
        }
        boolean present = containsMarker(output);
        Matcher statusMatcher = STATUS_PATTERN.matcher(output);
        String status = statusMatcher.find() ? statusMatcher.group(1).trim() : null;
        boolean hasNextSteps = NEXT_STEPS_PATTERN.matcher(output).find();
        return new Parsed(present, status, hasNextSteps);
    }
}