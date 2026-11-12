package com.helloai.core.planner.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.agent.port.SemanticJudgePort;
import com.helloai.core.task.port.SubTaskView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义门 `G4_UNSATISFIABLE_CONTRACT` 的判据测试。
 *
 * <p><b>不打桩整类</b>：要测的是「模型输出 → findings」这条映射链与三种降级路径，
 * mock 掉 gate 就只剩「调用发生过」这一个事实。模型本身用端口替身（lambda），不联网。</p>
 */
@DisplayName("SemanticContractGate（语义门 G4）")
class SemanticContractGateTest {

    private static final Long TASK_ID = 200L;
    private static final Long PLANNER_AGENT_ID = 9001L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 夹具 ─────────────────────────────────────────────────────

    private SubTaskView draft(long id, String code, String acceptance, String constraints, List<Long> dependsOn) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务" + id, "内容描述", "交付物完整清单", acceptance, null, constraints, Map.of("plannerAgentId", PLANNER_AGENT_ID), List.of(), dependsOn, null, null, null, List.of(), null, null, null, null, null, null, null, code);
    }

    /** 单条上游 → 下游的最小 DAG：1（约束「不得引用外部资料」）← 2（验收「必须附 URL」）。 */
    private List<SubTaskView> twoDrafts() {
        return List.of(
                draft(1L, "202611090001", "运行 X 命令输出预期 Y", "不得引用任何外部资料，不得联网检索", List.of()),
                draft(2L, "202611090001-001", "必须引用至少 3 条外部资料并附可打开 URL",
                        "不得编造未确认的事实", List.of(1L)));
    }

    private SemanticContractGate gate(SemanticJudgePort port) {
        return new SemanticContractGate(port, objectMapper);
    }

    // ── 解析 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("模型输出解析")
    class Parse {

        @Test
        @DisplayName("约定 JSON → 冲突逐字段解析")
        void shouldParseConflict() {
            String raw = "{\"conflicts\":[{\"draftRef\":\"202611090001-001\",\"upstreamRef\":\"202611090001\","
                    + "\"acceptanceQuote\":\"必须附 URL\",\"constraintQuote\":\"不得引用外部资料\","
                    + "\"reason\":\"下游要外部资料，上游禁止\"}]}";
            SemanticContractGate g = gate((a, s, u) -> "");
            List<SemanticContractGate.Conflict> conflicts = g.parse(raw, g.buildNodes(twoDrafts()));

            assertThat(conflicts).hasSize(1);
            assertThat(conflicts.get(0).draftRef()).isEqualTo("202611090001-001");
            assertThat(conflicts.get(0).upstreamRef()).isEqualTo("202611090001");
            assertThat(conflicts.get(0).reason()).contains("上游禁止");
        }

        @Test
        @DisplayName("空数组 → 无冲突（不是「解析失败」）")
        void shouldParseEmpty() {
            SemanticContractGate g = gate((a, s, u) -> "");
            assertThat(g.parse("{\"conflicts\":[]}", g.buildNodes(twoDrafts()))).isEmpty();
        }

        @Test
        @DisplayName("带前后说明文字 / 代码围栏 → 仍能取出 JSON")
        void shouldTolerateProseAndFence() {
            SemanticContractGate g = gate((a, s, u) -> "");
            String raw = "判定结果如下：\n```json\n{\"conflicts\":[]}\n```\n以上。";
            assertThat(g.parse(raw, g.buildNodes(twoDrafts()))).isEmpty();
        }

        @Test
        @DisplayName("不是 JSON / 缺 conflicts 数组 → null（按「未执行」处理，不是「无冲突」）")
        void shouldReturnNullWhenNotJson() {
            SemanticContractGate g = gate((a, s, u) -> "");
            List<SemanticContractGate.Node> nodes = g.buildNodes(twoDrafts());
            assertThat(g.parse("没有冲突", nodes)).isNull();
            assertThat(g.parse("{\"result\":[]}", nodes)).isNull();
            assertThat(g.parse("", nodes)).isNull();
        }

        @Test
        @DisplayName("draftRef 不在本批 → 丢弃（否则会得到用户无法定位的 blocking）")
        void shouldDropUnknownRef() {
            SemanticContractGate g = gate((a, s, u) -> "");
            String raw = "{\"conflicts\":[{\"draftRef\":\"子任务2\",\"upstreamRef\":\"202611090001\","
                    + "\"acceptanceQuote\":\"x\",\"constraintQuote\":\"y\",\"reason\":\"z\"}]}";
            assertThat(g.parse(raw, g.buildNodes(twoDrafts()))).isEmpty();
        }

        @Test
        @DisplayName("draftRef 与 upstreamRef 相同 → 丢弃（自撞不是「上游约束」）")
        void shouldDropSelfReference() {
            SemanticContractGate g = gate((a, s, u) -> "");
            String raw = "{\"conflicts\":[{\"draftRef\":\"202611090001\",\"upstreamRef\":\"202611090001\","
                    + "\"acceptanceQuote\":\"x\",\"constraintQuote\":\"y\",\"reason\":\"z\"}]}";
            assertThat(g.parse(raw, g.buildNodes(twoDrafts()))).isEmpty();
        }
    }

    // ── 材料装配 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("判定材料装配")
    class Prompt {

        @Test
        @DisplayName("只有「有上游」的草案进材料，且带上游约束原文与本人验收原文")
        void shouldRenderOnlyDraftsWithUpstream() throws Exception {
            SemanticContractGate g = gate((a, s, u) -> "");
            String prompt = g.renderUserPrompt(g.buildNodes(twoDrafts()));

            assertThat(prompt).contains("### Ref: 202611090001-001");
            assertThat(prompt).contains("上游 Ref 202611090001");
            assertThat(prompt).contains("不得引用任何外部资料");
            assertThat(prompt).contains("必须引用至少 3 条外部资料");
            // 无上游的那条不进材料（它没有可比对的约束，进材料只会让模型发散）
            assertThat(prompt).doesNotContain("### Ref: 202611090001 |");
            // 模板占位符必须被替换掉，否则模型看到的是 {{DRAFTS}}
            assertThat(prompt).doesNotContain("{{DRAFTS}}");
        }

        @Test
        @DisplayName("传递上游：A ← B ← C 时，C 的材料里含 A 的约束（只看直接上游会漏）")
        void shouldIncludeTransitiveUpstream() throws Exception {
            List<SubTaskView> drafts = List.of(
                    draft(1L, "202611090001", "验收A", "不得引用外部资料", List.of()),
                    draft(2L, "202611090001-001", "验收B", "数据须来自内部台账", List.of(1L)),
                    draft(3L, "202611090001-001-001", "必须附 3 条外部 URL", "无", List.of(2L)));
            SemanticContractGate g = gate((a, s, u) -> "");
            String prompt = g.renderUserPrompt(g.buildNodes(drafts));

            assertThat(prompt).contains("上游 Ref 202611090001《");
            assertThat(prompt).contains("不得引用外部资料");
        }
    }

    // ── 端到端（端口替身） ───────────────────────────────────────

    @Nested
    @DisplayName("判定结论")
    class Judge {

        @Test
        @DisplayName("命中 → 一条 block finding：gate3_semantic / G4 / subject=acceptance / fixClass=C")
        void shouldProduceBlockFinding() {
            SemanticContractGate g = gate((agentId, system, user) -> {
                assertThat(agentId).isEqualTo(PLANNER_AGENT_ID);
                assertThat(user).contains("202611090001-001");
                return "{\"conflicts\":[{\"draftRef\":\"202611090001-001\",\"upstreamRef\":\"202611090001\","
                        + "\"acceptanceQuote\":\"必须引用至少 3 条外部资料并附可打开 URL\","
                        + "\"constraintQuote\":\"不得引用任何外部资料\",\"reason\":\"两者不能同时满足\"}]}";
            });

            DraftGateChecker.Result result = g.judge(twoDrafts(), PLANNER_AGENT_ID);

            assertThat(result.hasBlocking()).isTrue();
            DraftGateChecker.Finding f = result.blocking().get(0);
            assertThat(f.ruleId()).isEqualTo(DraftGateChecker.G4_UNSATISFIABLE_CONTRACT);
            assertThat(f.gate()).isEqualTo(DraftGateChecker.GATE_SEMANTIC);
            assertThat(f.severity()).isEqualTo(DraftGateChecker.SEVERITY_BLOCK);
            assertThat(f.draftRef()).isEqualTo("202611090001-001");
            assertThat(f.subject()).isEqualTo("acceptance");
            assertThat(f.fixClass()).isEqualTo("C");
            assertThat(f.ruleVersion()).isEqualTo(DraftGateChecker.RULE_VERSION);
            // 消息要能让用户对上「哪条验收 / 哪条约束的哪句话」
            assertThat(f.evidence()).contains("不可能同时满足")
                    .contains("不得引用任何外部资料");
        }

        @Test
        @DisplayName("模型报无冲突 → 空结论")
        void shouldReturnEmptyWhenNoConflict() {
            DraftGateChecker.Result result =
                    gate((a, s, u) -> "{\"conflicts\":[]}").judge(twoDrafts(), PLANNER_AGENT_ID);
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("端口抛异常 → fail-open：warn「未执行」，无 block")
        void shouldFailOpenOnPortError() {
            DraftGateChecker.Result result = gate((a, s, u) -> {
                throw new BizException("未配置可用的平台级或 Agent 级 API Key");
            }).judge(twoDrafts(), PLANNER_AGENT_ID);

            assertThat(result.hasBlocking()).isFalse();
            assertThat(result.warnings()).hasSize(1);
            DraftGateChecker.Finding w = result.warnings().get(0);
            assertThat(w.severity()).isEqualTo(DraftGateChecker.SEVERITY_WARN);
            // 批次级结论（挂首条草案由 store 决定，这里必须是 null）
            assertThat(w.draftRef()).isNull();
            assertThat(w.evidence()).contains("语义门未执行");
        }

        @Test
        @DisplayName("输出不可解析 → 也是 fail-open 的「未执行」，不是「无冲突」")
        void shouldFailOpenOnUnparseableOutput() {
            DraftGateChecker.Result result = gate((a, s, u) -> "我认为没有问题").judge(twoDrafts(), PLANNER_AGENT_ID);

            assertThat(result.hasBlocking()).isFalse();
            assertThat(result.warnings()).hasSize(1);
            assertThat(result.warnings().get(0).evidence()).contains("不是约定 JSON");
        }

        @Test
        @DisplayName("没有 planner agent → 未执行（不猜一个模型来判）")
        void shouldSkipWhenNoPlannerAgent() {
            DraftGateChecker.Result result = gate((a, s, u) -> {
                throw new AssertionError("没有 planner agent 时不得调用模型");
            }).judge(twoDrafts(), null);

            assertThat(result.hasBlocking()).isFalse();
            assertThat(result.warnings().get(0).evidence()).contains("未取到 planner agent");
        }

        @Test
        @DisplayName("整批无依赖 → 不调模型、不给结论（没有「上游约束」这个输入）")
        void shouldSkipWhenNoDependency() {
            List<SubTaskView> drafts = List.of(
                    draft(1L, "202611090001", "验收A", "约束A", List.of()),
                    draft(2L, "202611090002", "验收B", "约束B", List.of()));
            DraftGateChecker.Result result = gate((a, s, u) -> {
                throw new AssertionError("无依赖时不得调用模型");
            }).judge(drafts, PLANNER_AGENT_ID);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("空批 / null 批 → 空结论，不抛异常")
        void shouldTolerateEmptyInput() {
            SemanticContractGate g = gate((a, s, u) -> "{\"conflicts\":[]}");
            assertThat(g.judge(List.of(), PLANNER_AGENT_ID).isEmpty()).isTrue();
            assertThat(g.judge(null, PLANNER_AGENT_ID).isEmpty()).isTrue();
        }
    }
}
