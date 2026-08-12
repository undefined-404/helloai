package com.helloai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillNormalizer 归一词典单测（A3）。
 *
 * <p>覆盖：同义词归一（英文/中文）/ 大小写与 trim 归一 / 未命中自定义技能原样 /
 * normalizeAll 去重保序 / null 防御 / matches 的 AND 语义与同义词交叉命中。</p>
 */
class SkillNormalizerTest {

    @Test
    @DisplayName("英文同义词归一：bash/powershell/cli 均归 shell")
    void englishSynonymsNormalizeToShell() {
        assertThat(SkillNormalizer.normalize("bash")).isEqualTo("shell");
        assertThat(SkillNormalizer.normalize("powershell")).isEqualTo("shell");
        assertThat(SkillNormalizer.normalize("cli")).isEqualTo("shell");
        assertThat(SkillNormalizer.normalize("shell")).isEqualTo("shell");
    }

    @Test
    @DisplayName("英文同义词归一：web/search/review 归 web-search/code-review")
    void englishSynonymsNormalizeToWebAndReview() {
        assertThat(SkillNormalizer.normalize("web")).isEqualTo("web-search");
        assertThat(SkillNormalizer.normalize("search")).isEqualTo("web-search");
        assertThat(SkillNormalizer.normalize("review")).isEqualTo("code-review");
    }

    @Test
    @DisplayName("中文同义词归一：容器/数据库/脚本/搜索/浏览器/爬虫/审查/评审命中规范标签")
    void chineseSynonymsNormalize() {
        assertThat(SkillNormalizer.normalize("容器")).isEqualTo("docker");
        assertThat(SkillNormalizer.normalize("数据库")).isEqualTo("sql");
        assertThat(SkillNormalizer.normalize("脚本")).isEqualTo("shell");
        assertThat(SkillNormalizer.normalize("搜索")).isEqualTo("web-search");
        assertThat(SkillNormalizer.normalize("浏览器")).isEqualTo("web-search");
        assertThat(SkillNormalizer.normalize("爬虫")).isEqualTo("web-search");
        assertThat(SkillNormalizer.normalize("审查")).isEqualTo("code-review");
        assertThat(SkillNormalizer.normalize("评审")).isEqualTo("code-review");
    }

    @Test
    @DisplayName("大小写与 trim 归一：Shell/Docker/PYTHON 归一且保留规范标签")
    void caseAndTrimNormalize() {
        assertThat(SkillNormalizer.normalize(" Shell ")).isEqualTo("shell");
        assertThat(SkillNormalizer.normalize("DOCKER")).isEqualTo("docker");
        assertThat(SkillNormalizer.normalize("Python")).isEqualTo("python");
        assertThat(SkillNormalizer.normalize("Bash")).isEqualTo("shell");
    }

    @Test
    @DisplayName("未命中同义词表的自定义技能：小写原样返回，保持可精确匹配")
    void unknownSkillsKeepLowercased() {
        assertThat(SkillNormalizer.normalize("kubernetes")).isEqualTo("kubernetes");
        assertThat(SkillNormalizer.normalize("Golang")).isEqualTo("golang");
        assertThat(SkillNormalizer.normalize("k8s")).isEqualTo("k8s");
    }

    @Test
    @DisplayName("null/空白防御：normalize 返回 null")
    void nullAndBlankReturnNull() {
        assertThat(SkillNormalizer.normalize(null)).isNull();
        assertThat(SkillNormalizer.normalize("")).isNull();
        assertThat(SkillNormalizer.normalize("   ")).isNull();
    }

    @Test
    @DisplayName("归一幂等：规范标签再次归一不变")
    void normalizeIsIdempotent() {
        assertThat(SkillNormalizer.normalize(SkillNormalizer.normalize("powershell"))).isEqualTo("shell");
        assertThat(SkillNormalizer.normalize(SkillNormalizer.normalize("容器"))).isEqualTo("docker");
    }

    @Test
    @DisplayName("normalizeAll：同义词合并去重保序，空白项剔除")
    void normalizeAllDeduplicatesInOrder() {
        List<String> result = SkillNormalizer.normalizeAll(
                Arrays.asList("docker", "容器", "bash", "SHELL", " ", "powershell", "python"));
        assertThat(result).containsExactly("docker", "shell", "python");
    }

    @Test
    @DisplayName("normalizeAll：null/空/全空白返回空列表")
    void normalizeAllNullAndEmpty() {
        assertThat(SkillNormalizer.normalizeAll(null)).isEmpty();
        assertThat(SkillNormalizer.normalizeAll(Collections.emptyList())).isEmpty();
        assertThat(SkillNormalizer.normalizeAll(Arrays.asList("  ", null))).isEmpty();
    }

    @Test
    @DisplayName("matches：任务要求 powershell 命中 Agent 声明的 shell（同义词交叉）")
    void matchesSynonymCrossHit() {
        assertThat(SkillNormalizer.matches(List.of("shell"), List.of("powershell"))).isTrue();
        assertThat(SkillNormalizer.matches(List.of("powershell"), List.of("shell"))).isTrue();
    }

    @Test
    @DisplayName("matches：多技能 AND 语义，中英文同义词混合命中")
    void matchesAllRequiredWithMixedSynonyms() {
        assertThat(SkillNormalizer.matches(List.of("shell", "docker"), List.of("bash", "容器"))).isTrue();
        assertThat(SkillNormalizer.matches(List.of("shell", "docker", "sql"), List.of("shell", "数据库"))).isTrue();
    }

    @Test
    @DisplayName("matches：缺任一技能不命中（AND 语义不变）")
    void matchesRequiresAllSkills() {
        assertThat(SkillNormalizer.matches(List.of("shell"), List.of("shell", "python"))).isFalse();
        assertThat(SkillNormalizer.matches(List.of("shell", "docker"), List.of("shell", "web-search"))).isFalse();
    }

    @Test
    @DisplayName("matches：requiredSkills 空/null 视为不约束；agent 技能空/null 且要求非空时不命中")
    void matchesEmptyConstraintsAndEmptyAgentSkills() {
        assertThat(SkillNormalizer.matches(List.of("shell"), null)).isTrue();
        assertThat(SkillNormalizer.matches(List.of("shell"), Collections.emptyList())).isTrue();
        assertThat(SkillNormalizer.matches(null, List.of("shell"))).isFalse();
        assertThat(SkillNormalizer.matches(List.of("  "), List.of("shell"))).isFalse();
    }
}
