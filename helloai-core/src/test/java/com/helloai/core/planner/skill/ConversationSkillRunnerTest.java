package com.helloai.core.planner.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConversationSkillRunner} 失败信息展示的聚焦测试（2026-10-27）。
 *
 * <p>覆盖「技能渲染失败」时用户/日志看到的错误文本：archify 的 {@code --json} 失败输出是
 * 格式化 JSON receipt（首行就是孤零零的 {@code {}），直接取首行只会看到「{」——本测试锁定
 * {@code firstLine} 对 receipt 的解析行为，防止回归成「用户看不到真实错误」。</p>
 *
 * <p>为什么用反射：{@code firstLine}/{@code receiptErrorSummary} 是 private static 工具，
 * 没有独立 public 出口；反射测最小行为，代价是命名耦合——方法改名时本测试同步改。</p>
 */
@DisplayName("ConversationSkillRunner 失败信息展示")
class ConversationSkillRunnerTest {

    private static String firstLine(String output) throws Exception {
        Method m = ConversationSkillRunner.class.getDeclaredMethod("firstLine", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, output);
    }

    @Test
    @DisplayName("JSON receipt：展示 error 字段而非孤零零的「{」")
    void firstLine_jsonReceipt_showsError() throws Exception {
        String receipt = """
                {
                  "schemaVersion": 1,
                  "ok": false,
                  "command": "validate",
                  "stage": "render",
                  "error": "workflow schema validation failed:\\n  /nodes/0/type must be equal to one of the allowed values",
                  "diagnostics": [
                    {
                      "code": "schema/enum",
                      "severity": "error",
                      "message": "/nodes/0/type must be equal to one of the allowed values",
                      "subject": { "diagramType": "workflow", "path": "/nodes/0/type", "identity": "n1" }
                    }
                  ]
                }
                """;
        String result = firstLine(receipt);
        assertThat(result)
                .as("必须展示 receipt.error 而非「{」")
                .contains("workflow schema validation failed")
                .contains("/nodes/0/type must be equal to one of the allowed values")
                .doesNotStartWith("{");
    }

    @Test
    @DisplayName("receipt.error 超长（多节点枚举报错）：截断到 300 字符")
    void firstLine_jsonReceipt_longError_truncated() throws Exception {
        String longError = "workflow schema validation failed:\n" + "  /nodes/%d/type must be equal to one of the allowed values\n".repeat(40);
        String receipt = "{\n  \"schemaVersion\": 1,\n  \"ok\": false,\n  \"error\": \""
                + longError.replace("\n", "\\n").replace("\"", "\\\"")
                + "\",\n  \"diagnostics\": []\n}";
        String result = firstLine(receipt);
        assertThat(result).startsWith("workflow schema validation failed");
        assertThat(result.length()).isLessThanOrEqualTo(301);
    }

    @Test
    @DisplayName("JSON receipt 只有 diagnostics：展示首条 diagnostic 的 message")
    void firstLine_jsonReceipt_fallsBackToDiagnosticMessage() throws Exception {
        String receipt = """
                {
                  "schemaVersion": 1,
                  "ok": false,
                  "command": "deliver",
                  "stage": "prepare",
                  "diagnostics": [
                    { "code": "render/input", "severity": "error", "message": "spec.json 不是合法 JSON" }
                  ]
                }
                """;
        String result = firstLine(receipt);
        assertThat(result).isEqualTo("spec.json 不是合法 JSON");
    }

    @Test
    @DisplayName("普通文本输出：回落首行（非 JSON 场景）")
    void firstLine_plainText_fallsBackToFirstLine() throws Exception {
        assertThat(firstLine("node: not found\nsome stderr")).isEqualTo("node: not found");
        assertThat(firstLine("   \n  ")).isEqualTo("（无输出）");
        assertThat(firstLine(null)).isEqualTo("（无输出）");
    }

    @Test
    @DisplayName("JSON 但非 receipt（如合法 spec 输出）：不回退到「{」，取首行原文")
    void firstLine_jsonButNotReceipt_returnsFirstRawLine() throws Exception {
        String jsonSpec = "{\n  \"chartType\": \"workflow\",\n  \"spec\": {}\n}";
        String result = firstLine(jsonSpec);
        // 不是 receipt（无 error/diagnostics 字段）→ 回落首行逻辑（取第一行）
        assertThat(result).isEqualTo("{");
        // 但 receipt 场景必须展示真实错误（见上两个用例），此处只是确认非 receipt 不误解析
        assertThat(result).doesNotContain("schema_version");
    }

    // ===== 布局门判据：决定「要不要自动重试 / 降档」（2026-10-27） =====

    private static boolean isLayoutGateFailure(String output) throws Exception {
        Method m = ConversationSkillRunner.class.getDeclaredMethod("isLayoutGateFailure", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, output);
    }

    @Test
    @DisplayName("布局门判据：命中 composition/* 与 clean-flow/*（含本次线上真实失败文案）")
    void layoutGate_matchesDiagnosticCodes() throws Exception {
        // 原样取 2026-10-27 服务器上的真实失败输出
        String real = "Workflow layout validation failed:\n"
                + "[composition/ambiguous-corridor] showcase workflow edges[1] id \"e_register_validate\" "
                + "\"n_register\" -> \"n_validate\" shares a 28px corridor with edges[6] "
                + "id \"e_supplement_validate\" \"n_supplement\" -> \"n_validate\" at [264, 243] -> [292, 243] "
                + "(segments 0 and 2; minimum 8px) — adjust route/via.";
        assertThat(isLayoutGateFailure(real)).isTrue();
        assertThat(isLayoutGateFailure("[clean-flow/orphan-node] workflow n_x has no edge")).isTrue();
    }

    @Test
    @DisplayName("布局门判据：schema / 语法类失败不命中（重试与降档都救不了，不得白烧一次渲染）")
    void layoutGate_ignoresNonLayoutFailures() throws Exception {
        assertThat(isLayoutGateFailure(
                "spec.nodes[0].type must be one of frontend|backend|database")).isFalse();
        assertThat(isLayoutGateFailure("SyntaxError: Unexpected token } in JSON at position 12")).isFalse();
        // 前缀相近但不是诊断码族（防正则写宽了把无关失败也当布局问题）
        assertThat(isLayoutGateFailure("compose/whatever")).isFalse();
        assertThat(isLayoutGateFailure(null)).isFalse();
        assertThat(isLayoutGateFailure("")).isFalse();
    }
}
