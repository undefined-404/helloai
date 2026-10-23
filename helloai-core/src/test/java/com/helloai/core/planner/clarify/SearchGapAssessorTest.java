package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SearchGapAssessor} 的补搜变体解析（`ATT-6`）。
 *
 * <p>锁定的语义：LLM 产出多角度变体时**全部取用**（上限 3 条），不再只取第一条——
 * 原实现 {@code arr.get(0)} 让补搜永远单角度，「缺规范依据 / 缺参数 / 缺步骤」
 * 三个不同缺口只能补到其中一个。失败与空数组一律降级为空列表，不抛异常。</p>
 */
@DisplayName("SearchGapAssessor 补搜变体")
class SearchGapAssessorTest {

    private PlatformAgentExecutionService executionService;
    private PlannerAgentPicker plannerAgentPicker;
    private SearchGapAssessor assessor;

    private static final List<WebSearchResult> FIRST_ROUND = List.of(webResult("首轮结果 1"));

    @BeforeEach
    void setUp() {
        executionService = mock(PlatformAgentExecutionService.class);
        plannerAgentPicker = mock(PlannerAgentPicker.class);
        assessor = new SearchGapAssessor(executionService, plannerAgentPicker,
                new SystemTimeContextBuilder(), new ObjectMapper());
        when(plannerAgentPicker.pick(isNull())).thenReturn(mock(AgentProfileSnapshot.class));
    }

    @Test
    @DisplayName("ATT-6：多角度变体全部取用（3 条）")
    void shouldTakeAllVariantsUpToLimit() {
        stubLlm("{\"queries\":[\"GB/T 12345 手册编制\",\"手册 活页格式 要求\",\"安装步骤 前置检查\"]}");

        List<String> gaps = assessor.assessGaps("用户需求", FIRST_ROUND, List.of());

        assertThat(gaps).containsExactly("GB/T 12345 手册编制", "手册 活页格式 要求", "安装步骤 前置检查");
    }

    @Test
    @DisplayName("ATT-6：超过 3 条时截断到 3 条")
    void shouldCapAtThreeVariants() {
        stubLlm("{\"queries\":[\"a1\",\"a2\",\"a3\",\"a4\",\"a5\"]}");

        assertThat(assessor.assessGaps("用户需求", FIRST_ROUND, List.of())).hasSize(3);
    }

    @Test
    @DisplayName("ATT-6：空数组视为无缺口，返回空列表（不补搜）")
    void shouldReturnEmptyWhenNoGap() {
        stubLlm("{\"queries\":[]}");

        assertThat(assessor.assessGaps("用户需求", FIRST_ROUND, List.of())).isEmpty();
    }

    @Test
    @DisplayName("ATT-6：重复项去重，空白项跳过")
    void shouldDeduplicateAndSkipBlank() {
        stubLlm("{\"queries\":[\"规范依据\",\"规范依据\",\"  \",\"参数\"]}");

        assertThat(assessor.assessGaps("用户需求", FIRST_ROUND, List.of()))
                .containsExactly("规范依据", "参数");
    }

    @Test
    @DisplayName("ATT-6：单条变体仍可用（不强行凑满 3 条）")
    void shouldAllowSingleVariant() {
        stubLlm("{\"queries\":[\"唯一缺口\"]}");

        assertThat(assessor.assessGaps("用户需求", FIRST_ROUND, List.of()))
                .containsExactly("唯一缺口");
    }

    @Test
    @DisplayName("ATT-6：首轮无结果时不调用 LLM，直接返回空列表")
    void shouldSkipWhenFirstRoundEmpty() {
        assertThat(assessor.assessGaps("用户需求", List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("ATT-6：LLM 失败降级为空列表，不抛异常")
    void shouldDegradeOnLlmFailure() {
        when(executionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("LLM 不可用", "stop", "llm"));

        assertThat(assessor.assessGaps("用户需求", FIRST_ROUND, List.of())).isEmpty();
    }

    private void stubLlm(String jsonOutput) {
        when(executionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(jsonOutput, "stop", "llm", 100));
    }

    private static WebSearchResult webResult(String title) {
        WebSearchResult r = new WebSearchResult();
        r.setTitle(title);
        r.setUrl("https://example.com/" + title.hashCode());
        r.setSnippet("摘要");
        return r;
    }
}
