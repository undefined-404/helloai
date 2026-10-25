package com.helloai.core.planner;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.core.planner.policy.AcceptanceKindValidator;
import com.helloai.core.planner.policy.AcceptanceKindValidator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcceptanceKindValidator} 单测（TD-5 验收①：四类 kind 各造一正一反用例）。
 *
 * <p>正例必须 {@code Pass}；反例（kind 与文本写法不匹配）必须 {@code Fail}。
 * 另覆盖回落路径：kind 缺失回落 AUTOMATED 时，文本是「命令 + 预期结果」→ Pass；
 * 文本是「可数量词」→ Warn（疑似 STRUCTURAL 错标，flash 遵从率观察项）；文本是空话 → Fail。</p>
 */
class AcceptanceKindValidatorTest {

    @Test
    @DisplayName("AUTOMATED 正例：命令/断言 + 预期结果")
    void automatedPass() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.AUTOMATED,
                "运行 pytest tests/test_api.py 输出含 3 passed");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("AUTOMATED 反例：只有空话，无命令无预期结果")
    void automatedFailVague() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.AUTOMATED,
                "确保文档质量良好，结构规范");
        assertThat(r).isInstanceOf(Result.Fail.class);
    }

    @Test
    @DisplayName("STRUCTURAL 正例：可数量词（章节数/条目数/字数下限）")
    void structuralPass() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "报告必须含 ≥5 个章节，每章有结论；全文不少于 3000 字");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("STRUCTURAL 反例：写命令而非结构断言")
    void structuralFailCommand() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "运行 pytest tests/ 输出含 5 passed");
        assertThat(r).isInstanceOf(Result.Fail.class);
    }

    // ===== 2026-10-25 事故修复（LOG-20261024-049）=====
    // 事故：DOCUMENT 任务「撰写现状与挑战…输出完整文档」第 2 条声明 STRUCTURAL，文本含
    // 「输出/撰写」等中性写作动词被宽泛命令特征误判「疑似 AUTOMATED」→ fail-close 整批拒绝。
    // 修复：STRUCTURAL 反例只认「强命令特征」（可执行动作），结果性动词（输出/返回/检查/验证）
    // 在文档语境是中性写作动作，不触发「疑似 AUTOMATED」；AUTOMATED 仍用宽集保证接口断言式不回归。

    @Test
    @DisplayName("STRUCTURAL 事故回归：文档写作式（输出/撰写，无强命令）走「不含可数量词」Fail，而非「疑似 AUTOMATED」")
    void structuralDocWritingWithoutCountable_failsOnCountableNotAutomated() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "撰写现状与挑战、AI落地目标原则、OA接入AI总体蓝图及总体架构图，输出完整文档");
        assertThat(r).isInstanceOf(Result.Fail.class);
        assertThat(((Result.Fail) r).reason())
                .doesNotContain("疑似应为 AUTOMATED")
                .contains("不含可数量词");
    }

    @Test
    @DisplayName("STRUCTURAL 事故回归：文档结构断言（输出+数量词）→ Pass（不再被误判 AUTOMATED）")
    void structuralDocWithCountable_passes() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "输出《现状与挑战》章节，涵盖 AI 落地目标与 OA 接入蓝图，含总体架构图，每章不少于 500 字且覆盖全部必填要素");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("STRUCTURAL 不回归：真命令（运行 pytest）仍判 Fail「疑似 AUTOMATED」")
    void structuralRealCommand_stillFailsAsAutomated() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "运行 pytest tests/ 输出含 5 passed");
        assertThat(r).isInstanceOf(Result.Fail.class);
        assertThat(((Result.Fail) r).reason()).contains("疑似应为 AUTOMATED");
    }

    @Test
    @DisplayName("AUTOMATED 不回归：接口断言式（返回 200 + 字段非空）仍 Pass（宽集保留结果性动词）")
    void automatedApiAssertion_stillPasses() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.AUTOMATED,
                "调用 GET /api/v1/users 返回 200 且 data.users 字段非空");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("STRUCTURAL 事故深层修复：汉字数字 + 结构单位算可数量词（涵盖四个部分/五个章节/共四项/三张图）")
    void structuralChineseNumerals_countable() {
        // 中文 LLM 写文档验收的常见写法，只认阿拉伯数字会漏判成「无可数量词」→ 误拒
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "涵盖四个部分：现状、目标、蓝图、架构图，每部分有结论"))
                .isInstanceOf(Result.Pass.class);
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "报告须含五个章节且每章有结论"))
                .isInstanceOf(Result.Pass.class);
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "覆盖全部必填要素：目标、原则、蓝图、架构图共四项"))
                .isInstanceOf(Result.Pass.class);
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "含总体架构图、流程图、时序图三张图"))
                .isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("STRUCTURAL 不误伤：普通汉字数词/日期（第三阶段/第一步/四月）不算可数量词")
    void structuralChineseNumerals_notCountable() {
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "第三阶段完成后验收，输出完整文档"))
                .isInstanceOf(Result.Fail.class);
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "第一步先做调研，再输出结论文档"))
                .isInstanceOf(Result.Fail.class);
        assertThat(AcceptanceKindValidator.validate(AcceptanceKind.STRUCTURAL,
                "四月前完成全部内容"))
                .isInstanceOf(Result.Fail.class);
    }

    @Test
    @DisplayName("REVIEWED 正例：≥2 条独立检查项")
    void reviewedPass() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.REVIEWED,
                "审查项1——每条建议都能回指证据编号（逐条 pass/fail）；审查项2——结论区分事实与主张");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("REVIEWED 反例：只有 1 条检查项")
    void reviewedFailSingle() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.REVIEWED,
                "审查项1——检查报告是否完整");
        assertThat(r).isInstanceOf(Result.Fail.class);
    }

    @Test
    @DisplayName("ATTESTED 正例：判定人 + 时点 + 留痕方式")
    void attestedPass() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.ATTESTED,
                "由产品负责人 10-25 前拍板，决策记录存 task_timeline");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("ATTESTED 反例：缺留痕方式")
    void attestedFailNoTrace() {
        Result r = AcceptanceKindValidator.validate(AcceptanceKind.ATTESTED,
                "由产品负责人确认");
        assertThat(r).isInstanceOf(Result.Fail.class);
    }

    @Test
    @DisplayName("回落路径：kind 缺失（null）但文本是命令 + 预期结果 → Pass（AUTOMATED 是改造前唯一语义）")
    void fallbackNullAutomatedPass() {
        Result r = AcceptanceKindValidator.validate(null,
                "运行 check.sh 输出含 all ok");
        assertThat(r).isInstanceOf(Result.Pass.class);
    }

    @Test
    @DisplayName("回落路径：kind 缺失但文本含可数量词 → Warn（疑似 STRUCTURAL 错标，放行不阻断）")
    void fallbackNullStructuralWarn() {
        Result r = AcceptanceKindValidator.validate(null,
                "STRUCTURAL：1) 证据条目总数 ≥15 条；2) A/B/C 三家各 ≥4 条");
        assertThat(r).isInstanceOf(Result.Warn.class);
    }

    @Test
    @DisplayName("回落路径：kind 缺失且文本是空话 → Fail")
    void fallbackNullVagueFail() {
        Result r = AcceptanceKindValidator.validate(null,
                "确保质量良好");
        assertThat(r).isInstanceOf(Result.Fail.class);
    }
}
