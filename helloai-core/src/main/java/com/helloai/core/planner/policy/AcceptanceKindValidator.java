package com.helloai.core.planner.policy;

import com.helloai.common.constant.AcceptanceKind;

import java.util.regex.Pattern;

/**
 * 验收判据按 kind 分流校验（`TD-5`，设计 §4.2）。
 *
 * <p><b>解决的问题</b>：{@code acceptance} 自由文本「假可判定」——模型写「质量良好」也能落库。
 * 本校验器把「谁来判、怎么判」的声明（{@code acceptanceKind}）落到文本写法硬约束，
 * kind 与写法不匹配在落库前拦下。</p>
 *
 * <p><b>四套规则（设计 §4.2 逐字）</b>：</p>
 * <ul>
 *   <li>{@code AUTOMATED}：acceptance 必须含「命令 / 断言」与「预期结果」两个成分；</li>
 *   <li>{@code STRUCTURAL}：必须含至少一个可数量词（数字 + 单位 / 条数 / 字段名）；</li>
 *   <li>{@code REVIEWED}：必须能被拆成 ≥2 条独立可判定的检查项；</li>
 *   <li>{@code ATTESTED}：必须含判定人、时点、留痕方式三要素。</li>
 * </ul>
 *
 * <p><b>接线策略（计划 §6 风险 2 应对 + `LOG-20261024-039` 观察项）</b>：
 * LLM 显式声明了 kind 的条目走 <b>fail-close</b>（错配即拒——这是设计意图「挡在落库前」）；
 * kind 缺失回落 {@code AUTOMATED} 的条目走 <b>WARN 放行</b>（记录问题描述，不阻断）——
 * 因为存量产物与 flash 当前产物大量「文本写 STRUCTURAL 但字段回落 AUTOMATED」，
 * 若一律 fail-close 会大面积误伤（验收②「存量零新失败」）。WARN 路径为后续收紧留观测数据。</p>
 *
 * <p><b>本校验器只判文本写法，不做内容质量判定</b>——写作质量仍只能靠 {@code REVIEWED} /
 * {@code ATTESTED}（设计 §0 ④「STRUCTURAL 覆盖有限」）。</p>
 */
public final class AcceptanceKindValidator {

    private AcceptanceKindValidator() {
    }

    /** 判定结果：通过 / 失败（含原因） / 放行（回落默认值时的 WARN 路径）。 */
    public sealed interface Result {
        /** 校验通过。 */
        record Pass(String kind) implements Result {
        }

        /** 校验失败（fail-close 路径）：kind 与文本写法不匹配，携带可读原因。 */
        record Fail(String kind, String reason) implements Result {
        }

        /**
         * 放行（WARN 路径）：kind 缺失回落 {@code AUTOMATED}，但文本写法与 AUTOMATED
         * 不符——按「存量零新失败」原则不阻断，携带建议观察的提示。
         */
        record Warn(String kind, String hint) implements Result {
        }
    }

    /**
     * 命令 / 断言特征（宽）：可执行命令式动词 + 结果性动词（输出 / 返回 / 检查 / 验证）。
     *
     * <p>宽集用于 {@link #validateAutomated} 的 Pass 判定——「运行 X 输出含 Y」「接口 Z 返回 200 且
     * 字段 W 存在」都是合法 AUTOMATED 写法，「输出/返回」在这里是「预期结果」的引子词，算命令成分。
     * <b>★不要把结果性动词从这里移除</b>（2026-10-25 实测）：「接口返回 200 且 data.users 字段非空」
     * 去掉「返回」后命令成分归零 → 显式 AUTOMATED 被误拒（回归）。</p>
     */
    private static final Pattern COMMAND_LIKE = Pattern.compile(
            "(运行|执行|调用|执行命令|pytest|mvn|npm|curl|grep|断言|assert|编译|构建|部署|启动|停止|安装|配置|查询|校验|核对|输出|返回|检查|验证)");

    /**
     * 强命令特征（窄）：只有「无歧义的可执行动作」才算——用于 {@link #validateStructural} 的
     * 「疑似 AUTOMATED」反例触发。
     *
     * <p><b>★收窄的动机（2026-10-25 事故修复，{@code LOG-20261024-049}）</b>：「输出 / 返回 / 检查 /
     * 验证 / 编译 / 构建 / 配置 / 查询 / 校验 / 核对」是<b>结果性 / 中性措辞</b>——在文档语境
     * （STRUCTURAL）是正常写作动作（「输出《现状》章节」「撰写…输出完整文档」「输出部署文档」），
     * 把它们当命令特征会把 STRUCTURAL 验收误判成「疑似 AUTOMATED」→ fail-close 整批拒绝
     * （真实事故：DOCUMENT 任务「撰写现状与挑战…输出完整文档」第 2 条被误拒）。
     * 真·AUTOMATED 的特征是<b>无歧义的可执行动作</b>（运行 / 执行 / 调用 / 命令名 pytest / mvn /
     * npm / curl / grep / 断言 / assert）——「运行 pytest 输出含 5 passed」标 STRUCTURAL 才是错配，
     * 应由本窄集拦截。两集分工：宽集保 AUTOMATED Pass 不回归（接口断言式），窄集保 STRUCTURAL
     * 反例不误伤（文档写作式）。</p>
     */
    private static final Pattern COMMAND_STRONG = Pattern.compile(
            "(运行|执行|调用|执行命令|pytest|mvn|npm|curl|grep|断言|assert)");

    /** 预期结果特征：数字 / 布尔 / 状态码 / 时间等可判定产物。 */
    private static final Pattern EXPECTED_OUTCOME = Pattern.compile(
            "(\\d+\\s*[%条页个次份项章节秒分钟天月年]|[0-9]+\\s*passed|passed|failed|all ok|\\bok\\b|成功|通过|失败|200|404|500|true|false|为空|非空|存在|缺失|0 条|0 项)");

    /**
     * 可数量词：数字 + 单位 / 明确的数量词头（STRUCTURAL 硬约束）。
     *
     * <p><b>单位必须与数字组合出现</b>（{@code \d+单位}），不设裸单位——「运行」「执行」里的
     * 「行」、普通句子里的「条 / 项 / 章」都会误命中（2026-10-24 实测：COUNTABLE 的裸「行」
     * 把「运行 pytest」误判为 STRUCTURAL 写法）。「不少于 / 至少 / 上限」等数量词头单独出现
     * 也算（它们必然引出数量），但「字段 / 清单 / 条目数」这类名词不算（无数量语义）。</p>
     *
     * <p><b>★汉字数字 + 结构单位也必须算可数量词（2026-10-25 事故修复，{@code LOG-20261024-049}）</b>：
     * 中文 LLM 写文档验收常用汉字数字（「涵盖四个部分」「五个章节」「共四项」「三张图」），
     * 只认阿拉伯数字 {@code \d+} 会把这类正当的 STRUCTURAL 结构断言漏判成「无可数量词」→ fail-close
     * 整批拒绝。汉字数字须与<b>结构单位</b>（条 / 页 / 个 / 次 / 份 / 项 / 章节 / 张 / 篇 / 部分 / 处 /
     * 卷 / 段）组合，避免「第三阶段」「第一步」「四月」等普通数词 / 日期误命中。</p>
     */
    private static final Pattern COUNTABLE = Pattern.compile(
            "(\\d+\\s*[条页个次份项章节秒分钟天月年%行列]|不少于|至少|至多|上限|下限|\\d+\\s*字|字数下限"
                    + "|[一二两三四五六七八九十百千万]+\\s*[条页个次份项章节张篇部分处卷段])");

    /** 审查项分隔：数字序号列表（1) / ① / 1. / 一、/ 审查项）。 */
    private static final Pattern REVIEW_ITEMS = Pattern.compile(
            "(\\d+[).、\\)]|[①②③④⑤⑥⑦⑧⑨⑩]|[一二三四五六七八九十]+、|审查项)");

    /** ATTESTED 三要素。 */
    private static final Pattern ATTESTED_WHO = Pattern.compile("(由|经|负责人|主管|用户|经理|总监|本人|人工确认|拍板|签字)");
    private static final Pattern ATTESTED_WHEN = Pattern.compile("(\\d{1,2}[月日]|\\d{4}[-年]|时点|日期|时间|前|之前|当日|本周|本月底|季度末|验收日)");
    private static final Pattern ATTESTED_TRACE = Pattern.compile("(留痕|时间线|记录|日志|存档|归档|快照|签收|纪要|系统|邮件|审批)");

    /** 不可判定措辞（AUTOMATED 反例特征：写「质量良好 / 体验良好 / 合理 / 规范」这类空话）。 */
    private static final Pattern VAGUE_QUALITY = Pattern.compile(
            "(质量良好|体验良好|功能正常|效果良好|合理|规范|完善|顺畅|美观|简洁|清晰|完整无误|无明显问题|一切正常)");

    /**
     * 校验入口：按 {@code declaredKind} 对 {@code acceptance} 文本做分流校验。
     *
     * @param declaredKind 模型/人工声明的 kind；{@code null} 视为「未声明」（回落 AUTOMATED，
     *                     走 Warn 放行路径而非 Fail——存量与 flash 产物大量「文本写 STRUCTURAL
     *                     但字段缺失」，见 {@code LOG-20261024-039} 观察项）
     * @param acceptance   acceptance 文本
     */
    public static Result validate(AcceptanceKind declaredKind, String acceptance) {
        String text = acceptance == null ? "" : acceptance.trim();
        boolean explicit = declaredKind != null;
        AcceptanceKind effective = declaredKind == null ? AcceptanceKind.DEFAULT() : declaredKind;
        return switch (effective) {
            case AUTOMATED -> validateAutomated(text, explicit);
            case STRUCTURAL -> validateStructural(text);
            case REVIEWED -> validateReviewed(text);
            case ATTESTED -> validateAttested(text);
        };
    }

    /**
     * AUTOMATED：含「命令 / 断言」与「预期结果」两个成分。
     * <ul>
     *   <li>显式声明（{@code explicit=true}）：不匹配即 Fail（fail-close，设计意图「挡在落库前」）；</li>
     *   <li>未声明（回落 AUTOMATED）：文本含可数量词 → Warn（疑似 STRUCTURAL 错标，放行观察）；
     *       文本是空话 → Fail（空话在任何 kind 下都不可判定）；普通描述 → Warn（存量产物常态，
     *       放行避免误伤）。</li>
     * </ul>
     */
    private static Result validateAutomated(String text, boolean explicit) {
        boolean hasCommand = COMMAND_LIKE.matcher(text).find();
        boolean hasOutcome = EXPECTED_OUTCOME.matcher(text).find();
        if (hasCommand && hasOutcome) {
            return new Result.Pass("AUTOMATED");
        }
        if (VAGUE_QUALITY.matcher(text).find()) {
            return new Result.Fail("AUTOMATED",
                    "acceptance 含不可判定空话（质量良好 / 体验良好等），且无命令/断言 + 预期结果——"
                            + "AUTOMATED 要求「运行 X 输出含 Y」式写法");
        }
        if (COUNTABLE.matcher(text).find()) {
            return new Result.Warn("AUTOMATED",
                    "文本含可数量词但无命令/断言成分——疑似应为 STRUCTURAL 但 kind 未显式声明（回落 AUTOMATED）");
        }
        if (!explicit) {
            return new Result.Warn("AUTOMATED",
                    "acceptance 无命令/断言与预期结果，且 kind 未显式声明（回落 AUTOMATED）——"
                            + "存量产物常态，放行待回归；显式声明 AUTOMATED 时应写「运行 X 输出含 Y」");
        }
        return new Result.Fail("AUTOMATED",
                "acceptance 不含「命令/断言 + 预期结果」——AUTOMATED 要求「运行 X 输出含 Y」或「接口返回 200 且字段存在」");
    }

    /** STRUCTURAL：必须含至少一个可数量词；无数量词判 Fail（文本写法与 kind 不匹配）。 */
    private static Result validateStructural(String text) {
        if (COUNTABLE.matcher(text).find()) {
            return new Result.Pass("STRUCTURAL");
        }
        // ★只认强命令特征（可执行动作）判「疑似 AUTOMATED」：结果性动词（输出/返回/检查/验证）
        // 在文档语境是中性写作动作，不能触发反例（2026-10-25 事故，LOG-20261024-049）
        if (COMMAND_STRONG.matcher(text).find()) {
            return new Result.Fail("STRUCTURAL",
                    "文本含可执行命令/断言但无可数量词——疑似应为 AUTOMATED（如「运行 pytest 输出含 3 passed」），"
                            + "与声明的 STRUCTURAL 不匹配（STRUCTURAL 要求可数的结构断言：章节数/条目数/字数下限/字段齐全）");
        }
        return new Result.Fail("STRUCTURAL",
                "acceptance 不含可数量词（数字+单位 / 条数 / 字段名）——STRUCTURAL 要求可数的结构断言，"
                        + "如「报告须含 ≥5 个章节且每章有结论」「全文不少于 3000 字」「覆盖全部必填要素（共 N 项）」");
    }

    /** REVIEWED：必须能被拆成 ≥2 条独立可判定的检查项。 */
    private static Result validateReviewed(String text) {
        int itemCount = countReviewItems(text);
        if (itemCount >= 2) {
            return new Result.Pass("REVIEWED");
        }
        if (itemCount == 1) {
            return new Result.Fail("REVIEWED",
                    "acceptance 只有 1 条检查项——REVIEWED 要求 ≥2 条独立可判定的检查项（逐条 pass/fail）");
        }
        return new Result.Fail("REVIEWED",
                "acceptance 无编号检查项——REVIEWED 要求给出可逐条打勾的审查清单（≥2 条）");
    }

    /** ATTESTED：必须含判定人、时点、留痕方式三要素。 */
    private static Result validateAttested(String text) {
        boolean who = ATTESTED_WHO.matcher(text).find();
        boolean when = ATTESTED_WHEN.matcher(text).find();
        boolean trace = ATTESTED_TRACE.matcher(text).find();
        if (who && when && trace) {
            return new Result.Pass("ATTESTED");
        }
        StringBuilder missing = new StringBuilder();
        if (!who) {
            missing.append("判定人");
        }
        if (!when) {
            missing.append(missing.length() > 0 ? "、时点" : "时点");
        }
        if (!trace) {
            missing.append(missing.length() > 0 ? "、留痕方式" : "留痕方式");
        }
        return new Result.Fail("ATTESTED",
                "acceptance 缺少「" + missing + "」——ATTESTED 要求写明谁 / 什么时点 / 留什么痕 三要素");
    }

    /** 审查项计数：数字序号（1) / 1. / ① / 一、）与「审查项」关键词出现次数。 */
    private static int countReviewItems(String text) {
        java.util.regex.Matcher m = REVIEW_ITEMS.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
}
