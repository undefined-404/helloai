package com.helloai.core.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.system.port.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentSkillDeriver 推导规则单测。
 *
 * <p>覆盖：显式优先 / accessType 基础技能 / 关键词命中与大小写归一 / 去重保序 /
 * 空显式走推导 / 清洗（trim、空白过滤、去重） / null 防御。</p>
 */
class AgentSkillDeriverTest {

    /**
     * `M-9` 谓词快照（入参构造器）：{@code deriveWithCapabilities` 由「白名单两列」改为能力谓词。
     * reasoning↔thinking、webSearch↔web-search；其余作业类型技能恒真。
     */
    private static ModelCapabilities caps(boolean reasoning, boolean webSearch) {
        return new ModelCapabilities(List.of("text"), List.of("text"), reasoning, true, webSearch);
    }

    @Test
    @DisplayName("显式技能优先：非空显式值直接返回，不被推导覆盖")
    void explicitSkillsTakePrecedence() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.CLI_CLIENT,
                "docker-box", "Docker 容器编排专家", Arrays.asList("kubernetes", "docker"));
        assertThat(result).containsExactly("kubernetes", "docker");
    }

    @Test
    @DisplayName("CLI_CLIENT 无关键词命中时兜底 shell 基础技能")
    void cliClientGetsShellBase() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.CLI_CLIENT, "qoder", "通用外部 CLI Agent", null);
        assertThat(result).containsExactly("shell");
    }

    @Test
    @DisplayName("API_KEY_LLM 兜底 code-review 基础技能")
    void apiKeyLlmGetsCodeReviewBase() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.API_KEY_LLM, "deepseek", "通用分析", null);
        assertThat(result).containsExactly("code-review");
    }

    @Test
    @DisplayName("WEB_BROWSER 兜底 web-search 基础技能")
    void webBrowserGetsWebSearchBase() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.WEB_BROWSER, "kimi-browser", "网页浏览", null);
        assertThat(result).containsExactly("web-search");
    }

    @Test
    @DisplayName("关键词命中合并：描述含 python/docker 时与基础技能合并，去重保序")
    void keywordHitsMergeWithBaseSkill() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.CLI_CLIENT,
                "devbox", "擅长 Python 脚本与 Docker 容器", null);
        assertThat(result).containsExactly("shell", "docker", "python");
    }

    @Test
    @DisplayName("关键词大小写归一：大写 Docker 与小写 review 均可命中")
    void keywordMatchIsCaseInsensitive() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.API_KEY_LLM,
                "CodeReviewer", "Docker 审查专家", null);
        assertThat(result).contains("docker", "code-review");
    }

    @Test
    @DisplayName("名称与描述均可触发关键词，基础技能与关键词同标签去重")
    void nameAndDescriptionBothScannedAndDeduplicated() {
        // shell 既是 CLI_CLIENT 基础技能也是关键词命中 → 只出现一次
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.CLI_CLIENT,
                "shell-runner", "日常跑脚本", null);
        assertThat(result).containsExactly("shell");
    }

    @Test
    @DisplayName("显式空列表视为未提供，走推导")
    void emptyExplicitSkillsFallsBackToDerive() {
        List<String> result = AgentSkillDeriver.derive(AgentAccessType.CLI_CLIENT, "cli", "通用", Collections.emptyList());
        assertThat(result).contains("shell");
    }

    @Test
    @DisplayName("clean：trim、过滤空白项、去重保序")
    void cleanTrimsFiltersAndDeduplicates() {
        List<String> result = AgentSkillDeriver.clean(Arrays.asList("  docker ", "", "docker", null, " python"));
        assertThat(result).containsExactly("docker", "python");
    }

    @Test
    @DisplayName("clean：null/空列表返回空列表")
    void cleanHandlesNullAndEmpty() {
        assertThat(AgentSkillDeriver.clean(null)).isEmpty();
        assertThat(AgentSkillDeriver.clean(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("null 防御：type/name/description 为 null 不抛异常，结果为空")
    void nullArgumentsAreSafe() {
        List<String> result = AgentSkillDeriver.derive(null, null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deriveWithCapabilities：能力锁定始终追加，即使 explicitSkills 为空")
    void withCapabilities_lockedSkillAlwaysPresent() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "planner", "计划编排", null,
                caps(true, false));
        assertThat(result).contains("thinking");
        assertThat(result).contains("code-review");
    }

    @Test
    @DisplayName("deriveWithCapabilities：标准技能按能力谓词过滤，越界项被丢弃")
    void withCapabilities_standardSkillOutOfWhitelistDropped() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "deepseek", "通用", List.of("thinking", "web-search", "shell"),
                caps(true, false));
        assertThat(result).containsExactly("thinking", "code-review", "shell");
    }

    @Test
    @DisplayName("deriveWithCapabilities：自定义技能豁免放行（D2=A）")
    void withCapabilities_customSkillExempted() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "k8s-ops", "Kubernetes 运维", List.of("kubernetes", "shell"),
                caps(true, false));
        assertThat(result).contains("kubernetes", "thinking", "shell");
    }

    @Test
    @DisplayName("deriveWithCapabilities：关键词命中越界被净化（deepseek 描述含搜索不推导 web-search）")
    void withCapabilities_keywordOutOfWhitelistPurged() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "search-bot", "擅长联网搜索与浏览器", null,
                caps(true, false));
        assertThat(result).doesNotContain("web-search");
        assertThat(result).contains("thinking", "code-review");
    }

    @Test
    @DisplayName("deriveWithCapabilities：未识别模型（caps=null）不净化，保留 A2 关键词兜底行为")
    void withCapabilities_unknownModelKeepsKeywordFallback() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "search-bot", "擅长联网搜索", null,
                null);
        assertThat(result).contains("web-search", "code-review");
    }

    @Test
    @DisplayName("deriveWithCapabilities：联网模型（webSearch=true）放行 web-search")
    void withCapabilities_webSearchAllowedForOnlineModels() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "kimi", "搜索专家", List.of("web-search"),
                caps(true, true));
        assertThat(result).contains("web-search", "thinking");
    }

    // ════════════════════════════════════════════════════════════
    //  M-9 新增：谓词驱动的拒绝面变化
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("M-9：reasoning=false ⇒ 结果不含 thinking（thinking 由 reasoning 驱动）")
    void withCapabilities_reasoningFalseExcludesThinking() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "deepseek", "通用", List.of("thinking", "shell"),
                caps(false, false));
        assertThat(result).doesNotContain("thinking");
        assertThat(result).contains("shell", "code-review");
    }

    @Test
    @DisplayName("M-9：webSearch=false ⇒ 显式 web-search 被拒（不落库）")
    void withCapabilities_webSearchFalseDropsExplicitWebSearch() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "deepseek", "通用", List.of("web-search"),
                caps(true, false));
        assertThat(result).doesNotContain("web-search");
    }

    @Test
    @DisplayName("M-9：作业类型技能恒真（python/docker/sql/java 在最弱模型下仍保留）")
    void withCapabilities_jobTypeSkillsAlwaysKept() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "coder", "通用", List.of("python", "docker", "sql", "java"),
                caps(false, false));
        assertThat(result).contains("python", "docker", "sql", "java");
    }

    @Test
    @DisplayName("M-9：reasoning=true 且 webSearch=true ⇒ thinking 与 web-search 同时存在")
    void withCapabilities_bothPredicatesTrue() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "kimi", "搜索专家", List.of("web-search"),
                caps(true, true));
        assertThat(result).contains("thinking", "web-search", "code-review");
    }
}
