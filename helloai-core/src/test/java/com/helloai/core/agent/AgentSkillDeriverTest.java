package com.helloai.core.agent;

import com.helloai.common.constant.AgentAccessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentSkillDeriver 推导规则单测（A2）。
 *
 * <p>覆盖：显式优先 / accessType 基础技能 / 关键词命中与大小写归一 / 去重保序 /
 * 空显式走推导 / 清洗（trim、空白过滤、去重） / null 防御。</p>
 */
class AgentSkillDeriverTest {

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
                List.of("thinking"), List.of("shell", "code-review"));
        assertThat(result).contains("thinking");
        assertThat(result).contains("code-review");
    }

    @Test
    @DisplayName("deriveWithCapabilities：标准技能按白名单过滤，越界项被丢弃")
    void withCapabilities_standardSkillOutOfWhitelistDropped() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "deepseek", "通用", List.of("thinking", "python", "shell"),
                List.of("thinking"), List.of("shell", "code-review"));
        assertThat(result).containsExactly("thinking", "code-review", "shell");
    }

    @Test
    @DisplayName("deriveWithCapabilities：自定义技能豁免放行（D2=A）")
    void withCapabilities_customSkillExempted() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "k8s-ops", "Kubernetes 运维", List.of("kubernetes", "shell"),
                List.of("thinking"), List.of("shell", "code-review"));
        assertThat(result).contains("kubernetes", "thinking", "shell");
    }

    @Test
    @DisplayName("deriveWithCapabilities：关键词命中越界被净化（deepseek 描述含搜索不推导 web-search）")
    void withCapabilities_keywordOutOfWhitelistPurged() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "search-bot", "擅长联网搜索与浏览器", null,
                List.of("thinking"), List.of("shell", "code-review"));
        assertThat(result).doesNotContain("web-search");
        assertThat(result).contains("thinking", "code-review");
    }

    @Test
    @DisplayName("deriveWithCapabilities：未识别模型（白名单空）不净化，保留 A2 关键词兜底行为")
    void withCapabilities_unknownModelKeepsKeywordFallback() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "search-bot", "擅长联网搜索", null,
                null, null);
        assertThat(result).contains("web-search", "code-review");
    }

    @Test
    @DisplayName("deriveWithCapabilities：联网模型白名单放行 web-search")
    void withCapabilities_webSearchAllowedForOnlineModels() {
        List<String> result = AgentSkillDeriver.deriveWithCapabilities(
                AgentAccessType.API_KEY_LLM, "kimi", "搜索专家", List.of("web-search"),
                List.of("thinking"), List.of("shell", "code-review", "web-search"));
        assertThat(result).contains("web-search", "thinking");
    }
}
