package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.planner.search.BochaRerankService;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.SearchQueryPlannerService;
import com.helloai.core.planner.service.WebPageFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ClarifyWebSearchOrchestrator} 两阶段检索（`ATT-8`）。
 *
 * <p>锁定的语义：{@link ClarifyWebSearchOrchestrator#planCandidates} 只规划不搜索；
 * 把规划结果回传给 {@code doWebSearch} 时<b>不得重跑规划</b>（LLM 改写只付费一次），
 * 实际检索必须使用预规划词。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClarifyWebSearchOrchestrator 检索词预规划")
class ClarifyWebSearchOrchestratorTest {

    @Mock
    private ToolExecutor toolExecutor;
    @Mock
    private WebSearchProperties webSearchProperties;
    @Mock
    private WebPageFetchService pageFetchService;
    @Mock
    private SearchQueryPlannerService searchQueryPlannerService;
    @Mock
    private RelativeTimeNormalizer relativeTimeNormalizer;
    @Mock
    private SearchGapAssessor searchGapAssessor;
    @Mock
    private BochaRerankService bochaRerankService;

    private ClarifyWebSearchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ClarifyWebSearchOrchestrator(toolExecutor, webSearchProperties, pageFetchService,
                searchQueryPlannerService, relativeTimeNormalizer, searchGapAssessor, bochaRerankService,
                new ObjectMapper());
        lenient().when(relativeTimeNormalizer.normalize(anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("优先词在前、规则词补后，去重后即为进度帧展示的候选词")
    void planCandidates_mergesPriorityAndRuleQueries() {
        when(searchQueryPlannerService.planQueries("如何安装技能"))
                .thenReturn(List.of("技能 安装", "Claude Code 安装"));

        List<String> out = orchestrator.planCandidates("如何安装技能", List.of("Claude Code 安装"));

        assertThat(out).containsExactly("Claude Code 安装", "技能 安装");
    }

    @Test
    @DisplayName("纯 URL 消息：规划词为空时回退域名（进度帧也能显示真实检索词）")
    void planCandidates_pureUrlFallsBackToHost() {
        when(searchQueryPlannerService.planQueries("")).thenReturn(List.of());

        List<String> out = orchestrator.planCandidates("https://example.com/docs", null);

        assertThat(out).containsExactly("example.com");
    }

    @Test
    @DisplayName("消息全是元词：规划与兜底都不可检索 → 空候选（不拿用户原话当检索词）")
    void planCandidates_metaOnlyReturnsEmpty() {
        when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
        when(searchQueryPlannerService.planQueries("帮我分析一下这个附件")).thenReturn(List.of());
        when(searchQueryPlannerService.isRetrievable("帮我分析一下这个附件")).thenReturn(false);

        assertThat(orchestrator.planCandidates("帮我分析一下这个附件", null)).isEmpty();
    }

    @Test
    @DisplayName("回传预规划词时不再重跑规划，检索直接使用该词")
    void doWebSearch_usesPlannedCandidatesWithoutReplanning() {
        lenient().when(webSearchProperties.getMaxResults()).thenReturn(8);
        lenient().when(webSearchProperties.getSearchBudgetMs()).thenReturn(0L);
        lenient().when(webSearchProperties.isAiSearchAnswer()).thenReturn(false);
        lenient().when(toolExecutor.execute(anyString(), anyString())).thenReturn(null);

        WebSearchOutcome outcome = orchestrator.doWebSearch("如何安装技能", null,
                LocalDate.of(2026, 11, 7), List.of("技能 安装"));

        assertThat(outcome).isNotNull();
        assertThat(outcome.getQueries()).containsExactly("技能 安装");
        verify(searchQueryPlannerService, never()).planQueries(anyString());
    }
}
