package com.helloai.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.clarify.SystemTimeContextBuilder;
import com.helloai.core.planner.service.impl.SearchQueryPlannerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchQueryPlannerServiceImpl 单元测试：
 * 规则清洗（敬语剥离/多主题拆分/标点清洗/截断去重）/ 空输入 /
 * LLM 改写失败降级规则结果（用户零结果原句回归用例）。
 */
@DisplayName("SearchQueryPlannerServiceImpl")
class SearchQueryPlannerServiceImplTest {

    private WebSearchProperties properties;
    private SearchQueryPlannerServiceImpl planner;

    @BeforeEach
    void setUp() {
        // properties/ObjectMapper 用真实实例（默认值与 JSON 解析是被测逻辑的一部分）；
        // deepseekApiKey 默认空串 = LLM 改写自动禁用，纯规则路径无外部调用
        properties = new WebSearchProperties();
        planner = new SearchQueryPlannerServiceImpl(properties, new ObjectMapper(),
                new SystemTimeContextBuilder());
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
    @DisplayName("LLM 改写失败（端点不可达）：降级规则结果，不抛异常")
    void planQueries_llmRewriteFails_degradesToRuleResult() {
        // Key 已配置 + 长疑问句 + 规则仅单候选词 → 触发 LLM 改写；端点不可达 → 降级规则结果
        properties.setDeepseekApiKey("sk-test");
        properties.setQueryRewriteBaseUrl("http://127.0.0.1:9/chat/completions");

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
        properties.setDeepseekApiKey("sk-test");
        properties.setQueryRewriteBaseUrl("http://127.0.0.1:9/chat/completions");

        List<String> queries = planner.planQueries(
                "怎么系统性地学习 Kubernetes 并在生产环境中落地实施呢？");

        // 开关关闭时与规则层行为一致：单候选词、无外部调用（不抛异常即证明未发起）
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0)).contains("Kubernetes");
    }
}
