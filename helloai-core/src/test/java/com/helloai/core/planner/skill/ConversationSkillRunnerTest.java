package com.helloai.core.planner.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.SkillPackageExecutionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.ChatAttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // ===== 产物自检（check）判定 + 执行级目录（2026-10-27 P0） =====

    /**
     * 用给定 CLI 输出构造 runner，并反射调用 {@code checkArtifact}。
     *
     * <p>为什么 mock 其余 7 个依赖：本组只验「回执怎么判」，真跑会牵出 LLM 与沙箱；
     * 判定逻辑是纯函数性质，注入桩输出即可覆盖全部分支。</p>
     */
    private static Object check(String cliOutput) throws Exception {
        SkillPackageExecutionService exec = mock(SkillPackageExecutionService.class);
        when(exec.run(anyString(), anyLong(), anyString(), anyInt()))
                .thenReturn(new SkillPackageExecutionService.RunResult(true, 0, cliOutput, false));
        ConversationSkillRunner runner = new ConversationSkillRunner(
                mock(AgentSkillSpecService.class), exec, mock(SkillSpecExtractor.class),
                mock(PlatformAgentExecutionService.class), mock(PlannerAgentPicker.class),
                mock(ChatAttachmentService.class), new ObjectMapper(), mock(ResourceLoader.class));
        Method m = ConversationSkillRunner.class.getDeclaredMethod(
                "checkArtifact", Long.class, String.class, String.class, SkillRunControl.class);
        m.setAccessible(true);
        return m.invoke(runner, -1L, "archify", "/workspace/runs/abc12345", SkillRunControl.NOOP);
    }

    private static boolean attr(Object attempt, String accessor) throws Exception {
        Method m = attempt.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (Boolean) m.invoke(attempt);
    }

    private static String strAttr(Object attempt, String accessor) throws Exception {
        Method m = attempt.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (String) m.invoke(attempt);
    }

    @Test
    @DisplayName("★ 自检通过：回执 ok + provenance=current ⇒ 取出产物哈希用于三方对账")
    void check_passesAndExtractsSha() throws Exception {
        Object attempt = check("""
                {"ok": true, "artifact": {"sha256": "c014b6e4aa", "bytes": 840706},
                 "composition": {"profile": "showcase", "status": "pass", "summary": {"errors": 0, "warnings": 0}},
                 "provenance": "current", "deliveryReceiptId": "r1"}
                """);
        assertThat(attr(attempt, "ok")).isTrue();
        assertThat(strAttr(attempt, "artifactSha256")).isEqualTo("c014b6e4aa");
    }

    @Test
    @DisplayName("★ provenance 不是 current ⇒ 硬失败且不重试（降档糊不过去，属正确性问题）")
    void check_provenanceNotCurrent_hardFails() throws Exception {
        // 不带 --require-provenance 时 CLI 的真实返回就是这个（archify.mjs:1994）
        Object attempt = check("""
                {"ok": true, "artifact": {"sha256": "x"}, "composition": {"status": "pass"},
                 "provenance": "unknown"}
                """);
        assertThat(attr(attempt, "ok")).isFalse();
        assertThat(attr(attempt, "retryable")).as("provenance 问题重试也不会变好").isFalse();
        assertThat(strAttr(attempt, "message")).contains("provenance=unknown");
    }

    @Test
    @DisplayName("★ 构图不过但来源正常 ⇒ 可重试（与 deliver 布局门同一族，走重生成 / 降档）")
    void check_compositionErrors_retryable() throws Exception {
        Object attempt = check("""
                {"ok": false, "artifact": {"sha256": "x"},
                 "composition": {"profile": "showcase", "status": "fail", "summary": {"errors": 2}},
                 "provenance": "current"}
                """);
        assertThat(attr(attempt, "ok")).isFalse();
        assertThat(attr(attempt, "retryable")).isTrue();
        assertThat(strAttr(attempt, "message")).contains("composition=fail").contains("errors=2");
    }

    @Test
    @DisplayName("回执不可解析（非 JSON / 空）⇒ 硬失败，不得当成布局门白重试")
    void check_unparsableOutput_hardFails() throws Exception {
        assertThat(attr(check("node: not found"), "retryable")).isFalse();
        assertThat(attr(check(""), "ok")).isFalse();
        assertThat(attr(check(null), "ok")).isFalse();
    }

    @Test
    @DisplayName("★ 执行级目录：每次 runId 不同，路径落在 /workspace/runs/<runId>/ 下")
    void runIdPaths_arePerExecution() throws Exception {
        Method id = ConversationSkillRunner.class.getDeclaredMethod("newRunId");
        id.setAccessible(true);
        String a = (String) id.invoke(null);
        String b = (String) id.invoke(null);
        assertThat(a).hasSize(8).matches("[0-9a-f]{8}");
        assertThat(a).as("两次执行必须落在不同目录，否则仍会串产物").isNotEqualTo(b);

        Method html = ConversationSkillRunner.class.getDeclaredMethod("htmlPathOf", String.class);
        Method spec = ConversationSkillRunner.class.getDeclaredMethod("specPathOf", String.class);
        html.setAccessible(true);
        spec.setAccessible(true);
        assertThat((String) html.invoke(null, "/workspace/runs/" + a))
                .isEqualTo("/workspace/runs/" + a + "/artifact.html");
        assertThat((String) spec.invoke(null, "/workspace/runs/" + a))
                .isEqualTo("/workspace/runs/" + a + "/spec.json");
    }

    // ===== 类型识别与 schema 注入（2026-10-29 P1：外部评审 §2 第 3 条） =====

    @SuppressWarnings("unchecked")
    private static List<String> guessTypes(String need) throws Exception {
        Method m = ConversationSkillRunner.class.getDeclaredMethod("guessTypes", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, need);
    }

    @SuppressWarnings("unchecked")
    private static List<String> pickInjection(List<String> hits) throws Exception {
        Method m = ConversationSkillRunner.class.getDeclaredMethod("pickInjection", List.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, hits);
    }

    private static ConversationSkillRunner newRunner() {
        return new ConversationSkillRunner(
                mock(AgentSkillSpecService.class), mock(SkillPackageExecutionService.class),
                mock(SkillSpecExtractor.class), mock(PlatformAgentExecutionService.class),
                mock(PlannerAgentPicker.class), mock(ChatAttachmentService.class),
                new ObjectMapper(), new DefaultResourceLoader());
    }

    @Test
    @DisplayName("★ 识别不截断：「支付审批调用链时序图」三个类型全保留（sequence 不得被丢）")
    void guessTypes_keepsAllHits() throws Exception {
        List<String> hits = guessTypes("画一张支付审批的调用链时序图");
        assertThat(hits)
                .as("三种信号都命中，识别层不得丢弃任何一类——丢 sequence 正是 validate 必失败的成因")
                .contains("lifecycle", "workflow", "sequence");
        assertThat(pickInjection(hits)).as("注入全文仍只取 2 份（prompt 预算）")
                .hasSize(2).containsExactly("lifecycle", "workflow");
    }

    @Test
    @DisplayName("识别退化：无关键词时给 arch／workflow 两张最通用的")
    void guessTypes_fallsBackToGeneric() throws Exception {
        assertThat(guessTypes("随便画点什么")).containsExactly("architecture", "workflow");
        assertThat(pickInjection(List.of("lifecycle", "workflow", "sequence", "erd")))
                .as("超过预算只切注入集，不切识别结果").hasSize(2);
    }

    @Test
    @DisplayName("★ 未注入全文的候选类型给出顶层必填摘要（字段名对齐，避免盲写）")
    void requiredFieldsSummary_listsTopLevelRequired() throws Exception {
        Method m = ConversationSkillRunner.class.getDeclaredMethod(
                "requiredFieldsSummary", String.class, List.class);
        m.setAccessible(true);
        String out = (String) m.invoke(newRunner(),
                "classpath:skills/packages/archify/3.1.0/schemas/", List.of("sequence"));
        assertThat(out)
                .contains("sequence")
                .contains("participants")
                .contains("messages")
                .as("摘要只是字段名级提示，不得展开成 schema 全文").doesNotContain("$ref");
    }

    // ===== 降档留痕（2026-10-29 P3，外部评审 §2 第 4 条） =====

    @Test
    @DisplayName("★ 降档留痕：payload 带 degraded/qualityProfile/degradeReason，未降档时为 false")
    void payload_marksDegraded() throws Exception {
        ConversationSkillRunner runner = newRunner();
        ConversationSkillRunner.Result strict = new ConversationSkillRunner.Result(
                1L, "a.html", 100L, "sha", "lifecycle", "showcase", null);
        ConversationSkillRunner.Result degraded = new ConversationSkillRunner.Result(
                2L, "b.html", 100L, "sha", "lifecycle", "standard", "composition=fail, errors=2");

        assertThat(strict.degraded()).isFalse();
        assertThat(degraded.degraded()).isTrue();

        var om = new ObjectMapper();
        var p1 = om.readTree(runner.payloadOf(strict));
        var p2 = om.readTree(runner.payloadOf(degraded));
        assertThat(p1.path("artifact").path("degraded").asBoolean()).isFalse();
        assertThat(p2.path("artifact").path("degraded").asBoolean()).isTrue();
        assertThat(p2.path("artifact").path("qualityProfile").asText()).isEqualTo("standard");
        assertThat(p2.path("artifact").path("degradeReason").asText()).contains("composition=fail");
    }
}
