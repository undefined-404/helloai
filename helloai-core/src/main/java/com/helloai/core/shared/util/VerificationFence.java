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

    // STATUS 允许 Markdown 前置装饰（粗体 `**` / 无序 `- *` / 引用 `>` / 有序 `1.` 及其混写，如 `- **STATUS: DONE**`、
    // `1. **STATUS: DONE**`）——统一吃进单个前缀字符类 `[\s\d#>*.+-]*`，避免「列表符+粗体」混写解析不出；
    // 值取「到空白/冒号/逗号/* 前的词」，兼容中文值（STATUS：完成）。
    private static final Pattern STATUS_PATTERN =
            Pattern.compile("(?im)^[\\s\\d#>*.+-]*STATUS\\s*[:：]?\\s*([^\\s：:*，]+).*$");
    private static final Pattern NEXT_STEPS_PATTERN =
            Pattern.compile("(?i)NEXT_STEPS\\s*[:：]?");

    private VerificationFence() {
    }

    /** 是否携带 VERIFICATION 段（评审侧/执行侧同源匹配，见类 javadoc）。 */
    public static boolean containsMarker(String output) {
        return output != null && output.contains(MARKER);
    }

    /**
     * 剥掉围栏段（自<b>最后一个</b> {@code VERIFICATION:} 标记起至末尾）后的正文。
     *
     * <p>与 {@link #containsMarker} 同属「唯一事实源」围栏结构读取，供交付物的<b>结构性判据</b>
     * 复用（例如「整合报告只含自检围栏、正文为空」须 fail-close，而非静默当成功）。
     * 无标记时原样返回（{@code null} 返回空串）。</p>
     */
    public static String bodyWithoutFence(String output) {
        if (output == null) {
            return "";
        }
        int idx = output.lastIndexOf(MARKER);
        return idx < 0 ? output : output.substring(0, idx);
    }

    /**
     * 取<b>结尾处的围栏段</b>（自最后一个 {@code VERIFICATION:} 标记起至末尾）；无标记返回空串。
     *
     * <p>与 {@link #bodyWithoutFence} 互为补充（一个是「去掉围栏留正文」，一个是「只取围栏」）。
     * 收敛在此是为了让「沙箱正文回传」与「收尾轮回退」共用同一段提取逻辑——
     * 回传时若原对话里有自审围栏，需把它接在回传正文末尾，否则评审侧看不到证据声明
     * （`LOG-20261025-010`）。</p>
     */
    public static String fenceBlock(String output) {
        if (output == null) {
            return "";
        }
        int idx = output.lastIndexOf(MARKER);
        return idx < 0 ? "" : output.substring(idx).trim();
    }

    /**
     * 剥离<b>结尾处</b>的自检围栏段（交付物净化，2026-10-16）。
     *
     * <p>与 {@link #bodyWithoutFence}（无条件结构切分，供完整性判据用）不同，本方法是
     * <b>条件化</b>剥离：仅当「末个 {@code VERIFICATION:} 之后的尾段自身构成<b>合格</b>围栏」
     * 且「其前正文<b>非空</b>」时才剥，否则原样返回 —— 使正文里正常出现的 {@code VERIFICATION:}
     * 字样（例如讲围栏协议的章节）不会被误截断。</p>
     *
     * <p><b>为什么需要</b>：围栏是「提交前自审」的<b>行为契约</b>，不是交付物内容 ——
     * 整合报告模板（{@code prompts/task-final-report.md}）与报告审查提示词都<b>没有</b>要求围栏，
     * 但 {@code FinalReportFenceHook} 收尾轮会把它追加到产出末尾，前端与归档会原样展示给用户。</p>
     *
     * @return 剥离后的正文；无围栏 / 尾段非合格围栏 / 前文为空时原样返回（{@code null} 原样返回）
     */
    public static String stripTrailingFence(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        int idx = output.lastIndexOf(MARKER);
        if (idx <= 0) {
            return output;
        }
        String body = output.substring(0, idx);
        if (body.isBlank()) {
            // 纯围栏（无正文）：不在此处处理，交「有没有正文」的完整性判据 fail-close
            return output;
        }
        if (!parse(output.substring(idx)).adequate()) {
            return output;
        }
        return body.stripTrailing();
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
