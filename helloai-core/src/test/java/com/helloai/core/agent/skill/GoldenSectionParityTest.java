package com.helloai.core.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REF-1.1a 逐字回归：注入段必须与「md 尚无 frontmatter 时」采集的 golden <b>完全一致</b>。
 *
 * <p><b>为什么用 golden 而不是重实现旧算法</b>：若在测试里再写一遍「切分隔符 → 剔 h1」，
 * 一旦新实现与旧实现同错，测试仍会通过（循环论证）。golden 是迁移<b>之前</b>的真实产物快照，
 * 是唯一能证伪「注入内容变了」的证据。</p>
 *
 * <p>golden 采集于 2026-10-09（md 加 frontmatter 之前），存于 {@code src/test/resources/skills/golden/}
 * ——刻意与 main 的 {@code skills/plugins/} <b>不同名</b>，避免测试 classpath 遮蔽真实资源。</p>
 */
@DisplayName("技能注入段 golden 逐字回归（REF-1.1a）")
class GoldenSectionParityTest {

    /** 4 个平台技能（eng-*）。 */
    private static final List<String> SKILLS = List.of(
            "eng-code-review", "eng-doc-standard", "eng-verification", "eng-web-research");

    private final AgentSkillSpecService service = new AgentSkillSpecServiceImpl(new SkillPackageCatalog());

    @Test
    @DisplayName("单命中注入段逐字等于 golden")
    void singleHitSectionsMatchGolden() throws Exception {
        for (String name : SKILLS) {
            assertThat(service.resolve(List.of(name)).section())
                    .as("技能 %s 的注入段与 golden 不一致", name)
                    .isEqualTo(readGolden(name + ".section.txt"));
        }
    }

    @Test
    @DisplayName("双命中组合注入段逐字等于 golden（含顺序与分隔）")
    void comboSectionsMatchGolden() throws Exception {
        assertThat(service.resolve(List.of("eng-code-review", "eng-verification")).section())
                .isEqualTo(readGolden("combo-code-review+verification.section.txt"));
    }

    /**
     * frontmatter / 分隔符泄漏哨兵：即使 golden 被误用坏状态重新采集，也能挡住最危险的失败形态
     * ——「注入内容退化成 frontmatter 块」或「详细规范被整体注入」。
     */
    @Test
    @DisplayName("注入段不得泄漏 frontmatter 与详细规范分隔符")
    void sectionMustNotLeakFrontMatterOrSeparator() {
        for (String name : SKILLS) {
            assertThat(service.resolve(List.of(name)).section())
                    .as("技能 %s 的注入段疑似泄漏", name)
                    .doesNotStartWith("---")
                    .doesNotContain("\n---\n")
                    .doesNotContain("requiredTools:")
                    .doesNotContain("validationRules:");
        }
    }

    private static String readGolden(String fileName) throws Exception {
        ClassPathResource resource = new ClassPathResource("skills/golden/" + fileName);
        assertThat(resource.exists()).as("golden 资源缺失: %s", fileName).isTrue();
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
