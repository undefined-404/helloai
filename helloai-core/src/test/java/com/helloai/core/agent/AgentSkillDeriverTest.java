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
}
