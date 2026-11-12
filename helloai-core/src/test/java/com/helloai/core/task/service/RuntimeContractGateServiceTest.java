package com.helloai.core.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.util.RuntimeContractGate;
import com.helloai.core.agent.port.SemanticJudgePort;
import com.helloai.core.task.service.impl.RuntimeContractGateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("G5 契约门：确定性 + LLM 语义")
class RuntimeContractGateServiceTest {

    private static final long JUDGE_AGENT = 88L;

    @Mock private SemanticJudgePort semanticJudgePort;
    @Mock private org.springframework.beans.factory.ObjectProvider<SemanticJudgePort> semanticJudgePortProvider;

    private RuntimeContractGateService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeContractGateServiceImpl(semanticJudgePortProvider, new ObjectMapper());
        when(semanticJudgePortProvider.getObject()).thenReturn(semanticJudgePort);
    }

    private static List<RuntimeContractGate.Downstream> downstreams() {
        return List.of(new RuntimeContractGate.Downstream(9L, "渲染结果页", "读取 matchResult"));
    }

    @Test
    @DisplayName("确定性已阻断 → 直接返回，不调模型")
    void shouldShortCircuitOnDeterministicBlock() {
        RuntimeContractGate.Result result = service.check(
                "输出 matchResult", "含 matchResult", "输出 dataResult", "含 dataResult",
                downstreams(), List.of(), JUDGE_AGENT);

        assertThat(result.hasBlocking()).isTrue();
        verify(semanticJudgePort, never()).judgeOnce(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("语义冲突 → warn（无 block 权），证据含对象与原因")
    void shouldMapSemanticBreakToWarn() {
        when(semanticJudgePort.judgeOnce(any(), anyString(), anyString()))
                .thenReturn("{\"breaks\":[{\"target\":\"汇总页\",\"reason\":\"删除了下游依赖的字段\",\"quote\":\"仅输出正文\"}]}");

        RuntimeContractGate.Result result = service.check(
                "输出 matchResult", "含 matchResult", "输出 matchResult 与正文", "含 matchResult",
                downstreams(), List.of(), JUDGE_AGENT);

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.findings()).anyMatch(f ->
                RuntimeContractGateServiceImpl.G5_SEMANTIC_CONTRACT_BREAK.equals(f.ruleId())
                        && "warn".equals(f.severity())
                        && f.evidence().contains("汇总页")
                        && f.evidence().contains("删除了下游依赖的字段"));
    }

    @Test
    @DisplayName("无判定 agent / 输出不可解析 → warn「未执行」并放行")
    void shouldDegradeWithVisibleWarn() {
        RuntimeContractGate.Result noAgent = service.check("a", "b", "a", "b",
                downstreams(), List.of(), null);
        assertThat(noAgent.hasBlocking()).isFalse();
        assertThat(noAgent.findings()).anySatisfy(f ->
                assertThat(f.evidence()).contains("语义门未执行"));
        verify(semanticJudgePort, never()).judgeOnce(anyLong(), anyString(), anyString());

        when(semanticJudgePort.judgeOnce(any(), anyString(), anyString())).thenReturn("not json");
        RuntimeContractGate.Result badJson = service.check("a", "b", "a", "b",
                downstreams(), List.of(), JUDGE_AGENT);
        assertThat(badJson.findings()).anySatisfy(f ->
                assertThat(f.evidence()).contains("语义门未执行"));
    }

    @Test
    @DisplayName("无上下游材料 → 不调模型、无结论")
    void shouldSkipWhenNoMaterial() {
        RuntimeContractGate.Result result = service.check("a", "b", "a", "b", List.of(), List.of(), JUDGE_AGENT);

        assertThat(result.findings()).isEmpty();
        verify(semanticJudgePort, never()).judgeOnce(anyLong(), anyString(), anyString());
    }
}
