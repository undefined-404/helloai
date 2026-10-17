package com.helloai.core.review.support;

import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FinalReportFidelityChecker 单元测试（3A-V2 机械前置门）。
 *
 * <p>覆盖：空壳引用硬违规（子任务/分册/前文类）、代码围栏失衡硬违规、空报告硬违规、
 * 覆盖追溯表缺失软违规、跨章逐字重复软违规、干净报告零违规、附件类表述不误判。</p>
 */
@DisplayName("FinalReportFidelityChecker 报告交付保真确定性校验")
class FinalReportFidelityCheckerTest {

    @Test
    @DisplayName("干净报告（覆盖表齐备、无空壳引用、围栏闭合、无跨章重复）零违规")
    void shouldPassCleanReport() {
        String report = "# 报告\n"
                + "## 0. 覆盖追溯表\n"
                + "| 章节编号 | 章节主题 | 核心产出类型 | 覆盖子任务（原始标题） |\n"
                + "|---|---|---|---|\n"
                + "| §1 | 甲主题 | 方案 | 子任务1（甲） |\n\n"
                + "## 1. 甲主题\n本章讲甲。\n\n"
                + "## 2. 乙主题\n本章讲乙。\n";

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isFalse();
        assertThat(result.hasSoft()).isFalse();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("空壳引用「见分册」：硬违规，且 issue 文本含规则名与命中片段")
    void shouldFlagShellReferenceToVolume() {
        String report = "# 报告\n## 1. 开源矩阵\n完整 11×8 矩阵取值见分册第4章。\n";

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.hardIssueList()).contains("shell_reference");
        assertThat(result.violations())
                .anyMatch(v -> v.severity() == FinalReportFidelityChecker.Severity.HARD
                        && v.rule().equals("shell_reference"));
    }

    @Test
    @DisplayName("空壳引用「详见子任务X」：硬违规")
    void shouldFlagShellReferenceToSubTask() {
        String report = "# 报告\n## 1. 甲\n接口清单详见子任务2。\n";

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.hardIssueList()).contains("详见子任务");
    }

    @Test
    @DisplayName("代码围栏失衡（奇数 ```）：硬违规")
    void shouldFlagUnbalancedCodeFence() {
        String report = "# 报告\n## 1. 配置\n```yaml\nspring.redis.host=127.0.0.1\n";

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.rule().equals("code_fence_unbalanced"));
    }

    @Test
    @DisplayName("空报告：硬违规")
    void shouldFlagEmptyReport() {
        assertThat(FinalReportFidelityChecker.check(null).hasHard()).isTrue();
        assertThat(FinalReportFidelityChecker.check("   ").hasHard()).isTrue();
    }

    @Test
    @DisplayName("覆盖追溯表缺失：软违规（不机械驳回）")
    void shouldFlagMissingCoverageTableAsSoft() {
        String report = "# 报告\n## 1. 甲\n本章讲甲。\n";

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isFalse();
        assertThat(result.hasSoft()).isTrue();
        assertThat(result.softIssueList()).contains("coverage_table_missing");
    }

    @Test
    @DisplayName("跨章逐字重复（同一 4 行内容块出现在 2 章）：软违规")
    void shouldFlagCrossChapterDuplicateAsSoft() {
        String block = "- 风险A\n- 风险B\n- 风险C\n- 风险D\n";
        String report = "# 报告\n## 0. 覆盖追溯表\n| a | b |\n\n"
                + "## 1. 结论\n" + block + "\n"
                + "## 2. 建议\n" + block;

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isFalse();
        assertThat(result.softIssueList()).contains("cross_chapter_duplicate");
    }

    @Test
    @DisplayName("正文提及附件（二进制/大文件交付物）：不判为空壳引用硬违规")
    void shouldNotFlagAttachmentMentionAsHard() {
        String report = "# 报告\n## 1. 附件清单\n完整核验脚本见附件。\n";

        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);

        assertThat(result.hasHard()).isFalse();
    }

    // ────────────────────────────────────────────────────────────
    //  S-9：脚本类交付物可执行性判定（纯静态，沙箱执行链设计方案 §3.5）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S-9 证据有效（ExecuteResponse exitCode=0 未截断）→ 零违规")
    void shouldPassWhenScriptExecutable() {
        ExecuteResponse ok = new ExecuteResponse("done", 0, false);

        FinalReportFidelityChecker.Result result =
                FinalReportFidelityChecker.checkScriptExecutability("echo ok", ok);

        assertThat(result.hasHard()).isFalse();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("S-9 执行失败（exitCode=1）→ HARD script_execution_failed 机械驳回")
    void shouldBlockWhenScriptFails() {
        ExecuteResponse failed = new ExecuteResponse("Traceback...", 1, false);

        FinalReportFidelityChecker.Result result =
                FinalReportFidelityChecker.checkScriptExecutability("python -c '1/0'", failed);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.hardIssueList()).contains("script_execution_failed");
    }

    @Test
    @DisplayName("S-9 输出截断（truncated=true）→ HARD 机械驳回（无法确认完整执行）")
    void shouldBlockWhenScriptTruncated() {
        ExecuteResponse truncated = new ExecuteResponse("...", 0, true);

        FinalReportFidelityChecker.Result result =
                FinalReportFidelityChecker.checkScriptExecutability("echo x", truncated);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.hardIssueList()).contains("script_execution_failed");
    }

    @Test
    @DisplayName("S-9 沙箱证据不可得（null）→ HARD script_execution_unavailable 机械驳回（fail-closed）")
    void shouldBlockWhenExecutionUnavailable() {
        FinalReportFidelityChecker.Result result =
                FinalReportFidelityChecker.checkScriptExecutability("echo ok", null);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.hardIssueList()).contains("script_execution_unavailable");
    }

    @Test
    @DisplayName("S-9 兼容 Map 结构执行结果（exitCode/truncated）→ 判定一致")
    void shouldAcceptMapExecution() {
        assertThat(FinalReportFidelityChecker.checkScriptExecutability("x", Map.of("exitCode", 0, "truncated", false))
                .hasHard()).isFalse();
        assertThat(FinalReportFidelityChecker.checkScriptExecutability("x", Map.of("exitCode", 2, "truncated", false))
                .hasHard()).isTrue();
        assertThat(FinalReportFidelityChecker.checkScriptExecutability("x", Map.of())
                .hasHard()).isTrue();
    }
}
