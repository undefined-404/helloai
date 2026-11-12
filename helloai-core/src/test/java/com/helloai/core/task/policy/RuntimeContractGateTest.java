package com.helloai.core.task.policy;

import com.helloai.common.util.RuntimeContractGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("G5 运行期契约门（确定性）")
class RuntimeContractGateTest {

    private static RuntimeContractGate.Downstream downstream(long id, String title, String text) {
        return new RuntimeContractGate.Downstream(id, title, text);
    }

    @Test
    @DisplayName("下游引用的产出标识在整改后消失 → block")
    void shouldBlockWhenDownstreamTokenNoLongerProduced() {
        RuntimeContractGate.Result result = RuntimeContractGate.check(
                "输出 matchResult 字段与 README.md", "含 matchResult",
                "输出 dataResult 字段", "含 dataResult",
                List.of(downstream(9L, "渲染结果页", "页面读取 matchResult 渲染列表")));

        assertThat(result.hasBlocking()).isTrue();
        assertThat(result.findings()).anyMatch(f ->
                RuntimeContractGate.G5_DOWNSTREAM_REF_BREAK.equals(f.ruleId())
                        && f.evidence().contains("matchResult")
                        && f.evidence().contains("渲染结果页"));
    }

    @Test
    @DisplayName("整改后仍产出该标识 → 无 finding")
    void shouldPassWhenTokenStillProduced() {
        RuntimeContractGate.Result result = RuntimeContractGate.check(
                "输出 matchResult 字段", "含 matchResult",
                "输出 matchResult 字段（改名不影响）", "含 matchResult",
                List.of(downstream(9L, "渲染结果页", "读取 matchResult")));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("纯小写英文词与普通中文词不参与匹配（避免自然语言误报）")
    void shouldIgnorePlainWords() {
        RuntimeContractGate.Result result = RuntimeContractGate.check(
                "输出 report 与 周报", "每周提交 report",
                "输出 summary", "每周提交 summary",
                List.of(downstream(9L, "汇总页", "读取 report 与 周报")));

        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("无下游 / 旧文本无标识 → 一律放行")
    void shouldPassWhenNoDownstreamOrNoToken() {
        assertThat(RuntimeContractGate.check("matchResult", "", "x", "", List.of()).findings()).isEmpty();
        assertThat(RuntimeContractGate.check("", "", "x", "",
                List.of(downstream(9L, "下游", "matchResult"))).findings()).isEmpty();
    }

    @Test
    @DisplayName("payload 结构恒含 findings / ruleVersion / checkedAt")
    void shouldBuildStablePayload() {
        var payload = RuntimeContractGate.payload(new RuntimeContractGate.Result(List.of()), "2026-11-10T00:00:00Z");
        assertThat(payload).containsKeys("findings", "ruleVersion", "checkedAt");
        assertThat(payload.get("ruleVersion")).isEqualTo(RuntimeContractGate.RULE_VERSION);
    }
}
