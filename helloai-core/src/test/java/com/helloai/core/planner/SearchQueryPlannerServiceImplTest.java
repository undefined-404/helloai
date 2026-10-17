package com.helloai.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.clarify.SystemTimeContextBuilder;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.impl.SearchQueryPlannerServiceImpl;
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
 * SearchQueryPlannerServiceImpl 单元测试：
 * 规则清洗（敬语剥离/多主题拆分/标点清洗/截断去重）/ 空输入 /
 * LLM 改写失败降级规则结果（用户零结果原句回归用例）。
 *
 * <p>LLM 改写已改走平台内执行链（Planner Agent + executeSync），测试以 mock 的平台
 * 执行服务模拟失败（抛异常）验证降级；规则路径不触达任何外部调用。</p>
 */
@DisplayName("SearchQueryPlannerServiceImpl")
class SearchQueryPlannerServiceImplTest {

    private WebSearchProperties properties;
    private PlatformAgentExecutionService platformAgentExecutionService;
    private PlannerAgentPicker plannerAgentPicker;
    private SearchQueryPlannerServiceImpl planner;

    @BeforeEach
    void setUp() {
        // properties/ObjectMapper 用真实实例（默认值与 JSON 解析是被测逻辑的一部分）；
        // 平台执行服务与 Planner 选择器用 mock（LLM 改写触发时可控失败降级）
        properties = new WebSearchProperties();
        platformAgentExecutionService = mock(PlatformAgentExecutionService.class);
        plannerAgentPicker = mock(PlannerAgentPicker.class);
        planner = new SearchQueryPlannerServiceImpl(properties, new ObjectMapper(),
                new SystemTimeContextBuilder(), platformAgentExecutionService, plannerAgentPicker);
    }

    @Test
    @DisplayName("用户原句回归：疑问长句拆出多个干净候选词，无敬语无标点")
    void planQueries_userOriginalQuestion_splitsMultipleCleanCandidates() {
        List<String> queries = planner.planQueries(
                "能否给我提供一份快速学习Python + 快速搭建项目的完整方案，按“学”和“做？");

        // 多主题拆分：至少两个候选词（学习/搭建两个检索域分别命中）
        assertThat(queries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(queries.get(0)).contains("快速学习Python");
        // 疑问句式与敬语全部剥离
        for (String q : queries) {
            assertThat(q).doesNotContain("能否").doesNotContain("一份").doesNotContain("给我");
            // 标点噪音清洗
            assertThat(q).doesNotContain("？").doesNotContain("，")
                    .doesNotContain("“").doesNotContain("”").doesNotContain("+");
        }
    }

    @Test
    @DisplayName("短而干净的消息：原样作单词候选（零额外处理）")
    void planQueries_shortCleanMessage_returnsSingleCandidate() {
        List<String> queries = planner.planQueries("Python 教程");

        assertThat(queries).containsExactly("Python 教程");
    }

    @Test
    @DisplayName("空白输入：返回空列表（调用方按空白查询词语义处理）")
    void planQueries_blankInput_returnsEmptyList() {
        assertThat(planner.planQueries(null)).isEmpty();
        assertThat(planner.planQueries("   ")).isEmpty();
    }

    @Test
    @DisplayName("敬语前缀剥离：候选词不以敬语/疑问词开头")
    void planQueries_politePrefix_removedFromCandidate() {
        List<String> queries = planner.planQueries("请帮我推荐几个 Java 并发面试题");

        assertThat(queries).isNotEmpty();
        assertThat(queries.get(0)).doesNotStartWith("请").doesNotStartWith("帮我");
        assertThat(queries.get(0)).contains("Java 并发面试题");
    }

    @Test
    @DisplayName("多主题超上限：候选词条数封顶 maxQueries")
    void planQueries_moreThanMaxTopics_cappedAtMaxQueries() {
        List<String> queries = planner.planQueries(
                "Redis 缓存，MySQL 调优，Kafka 消息队列，Docker 部署");

        assertThat(queries).hasSize(Math.max(1, properties.getMaxQueries()));
    }

    @Test
    @DisplayName("LLM 改写失败（平台执行抛异常）：降级规则结果，不抛异常")
    void planQueries_llmRewriteFails_degradesToRuleResult() {
        // 长疑问句 + 规则仅单候选词 → 触发 LLM 改写；平台执行抛异常 → 降级规则结果
        when(plannerAgentPicker.pick(isNull())).thenReturn(mock(AgentProfileSnapshot.class));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenThrow(new RuntimeException("LLM 不可用"));

        List<String> queries = planner.planQueries(
                "怎么系统性地学习 Kubernetes 并在生产环境中落地实施呢？");

        assertThat(queries).isNotEmpty();
        // 降级后的规则结果：疑问词剥离、语气词剥除
        assertThat(queries.get(0)).doesNotStartWith("怎么").doesNotEndWith("呢");
        assertThat(queries.get(0)).contains("Kubernetes");
    }

    @Test
    @DisplayName("LLM 改写开关关闭：不发起改写（纯规则运行）")
    void planQueries_rewriteDisabled_pureRuleRuns() {
        properties.setQueryRewriteEnabled(false);

        List<String> queries = planner.planQueries(
                "怎么系统性地学习 Kubernetes 并在生产环境中落地实施呢？");

        // 开关关闭时与规则层行为一致：单候选词、无外部调用（未 stub 平台执行即证明未触达）
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0)).contains("Kubernetes");
    }

    // ════════════════════════════════════════════════════════════
    //  W-4：语义单元截断 + 多主题长句进 LLM 改写
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-4 语义截断：空格处断词保留完整语义单元，不再硬切")
    void planQueries_w4_semanticTruncation_keepsWholeWords() {
        properties.setQueryRewriteEnabled(false); // 纯规则路径
        // 实测回归：「膝盖旧伤 减脂训练计划 饮食方案 建议」20 字硬截会切碎成
        // 「膝盖旧伤 减脂训练计划 饮」——空格边界截断应保留完整词
        List<String> queries = planner.planQueries("膝盖旧伤 减脂训练计划 饮食方案 建议");

        assertThat(queries).isNotEmpty();
        assertThat(queries.get(0))
                .doesNotEndWith("饮") // 不在空格中间切词
                .hasSizeLessThanOrEqualTo(20)
                .contains("膝盖旧伤");
    }

    @Test
    @DisplayName("W-4 无空格长词：退化为上限硬截（不丢搜索机会）")
    void planQueries_w4_noSpaceLongWord_degradesToHardTruncation() {
        properties.setQueryRewriteEnabled(false);
        // 无空格连续长词（如中英文混排无空格）——找不到词边界，退化保留前 N 字
        List<String> queries = planner.planQueries(
                "这是一个超长的没有空格的中文搜索需求描述场景演示文本案例");

        assertThat(queries).isNotEmpty();
        assertThat(queries.get(0)).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("W-4 多主题长句（超 40 字）：即使规则拆出多词也进 LLM 改写")
    void planQueries_w4_longMultiTopic_sendsToLlmRewrite() {
        // 原文超 LLM_REWRITE_MULTI_TEXT_LIMIT=40，规则拆出多候选词也进 LLM；
        // mock 平台执行返回 LLM 改写结果（正交查询）
        when(plannerAgentPicker.pick(isNull())).thenReturn(mock(AgentProfileSnapshot.class));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "[\"膝盖旧伤 减脂训练\",\"减脂饮食方案\"]", "stop", "llm", 100));

        List<String> queries = planner.planQueries(
                "膝盖旧伤恢复期想制定减脂训练计划，同时需要饮食方案建议，最好能结合我的情况给出建议");

        // LLM 改写生效：正交查询（不是规则拆碎的长尾词）
        assertThat(queries).containsExactlyInAnyOrder("膝盖旧伤 减脂训练", "减脂饮食方案");
    }
}
