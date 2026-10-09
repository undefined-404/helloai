package com.helloai.core.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("技能包目录扫描（REF-1.2a / REF-1.2b）")
class SkillPackageScannerTest {

    /**
     * 坏文件 fixture 目录，刻意<b>不与 main 的 {@code skills/plugins/} 同名</b>——
     * 同名会让测试 classpath 遮蔽真实 md，导致单测全线失真。
     */
    private static final String CORRUPT_LOCATION = "classpath*:skills/plugins-corrupt/*.md";

    @Test
    @DisplayName("★ 坏文件出现在列表中且带 error（REF-1.2b 验收：不静默跳过）")
    void corruptEntriesAreListedWithReason() {
        List<SkillCatalogEntry> entries = SkillPackageScanner.forLocation(CORRUPT_LOCATION).scan();

        assertThat(entries).isNotEmpty();
        assertThat(entries).allMatch(e -> !e.healthy());
        assertThat(entries).allSatisfy(e -> assertThat(e.error()).isNotBlank());
        assertThat(entries).allSatisfy(e -> assertThat(e.name()).isNotBlank());

        Map<String, SkillCatalogEntry> byName = entries.stream()
                .collect(Collectors.toMap(SkillCatalogEntry::name, Function.identity()));

        assertThat(byName.get("no-frontmatter").error()).contains("frontmatter 缺失");
        assertThat(byName.get("unclosed").error()).contains("未闭合");
        assertThat(byName.get("bad-field").error()).contains("version").contains("引号");
        assertThat(byName.get("unknown-key").error()).contains("未知字段");
        assertThat(byName.get("file-name-key").error()).contains("fileName");
        assertThat(byName.get("missing-version").error()).contains("version");
    }

    @Test
    @DisplayName("生产扫描位置：4 个技能包全部健康，且按 name 升序")
    void classpathScanIsHealthyAndSorted() {
        List<SkillCatalogEntry> entries = SkillPackageScanner.forClasspath().scan();
        assertThat(entries).hasSize(4);
        assertThat(entries).allMatch(SkillCatalogEntry::healthy);
        assertThat(entries).extracting(SkillCatalogEntry::name).isSorted()
                .containsExactly("eng-code-review", "eng-doc-standard", "eng-verification", "eng-web-research");
    }

    @Test
    @DisplayName("不存在的目录 → 空列表（不抛异常）")
    void missingLocationYieldsEmpty() {
        assertThat(SkillPackageScanner.forLocation("classpath*:skills/does-not-exist/*.md").scan()).isEmpty();
    }

    @Test
    @DisplayName("目录（缓存壳）：entries 含坏包，healthyPackages 只出健康包")
    void catalogSeparatesHealthyFromCorrupt() {
        SkillPackageCatalog catalog = new SkillPackageCatalog(SkillPackageScanner.forLocation(CORRUPT_LOCATION));
        assertThat(catalog.entries()).isNotEmpty();
        assertThat(catalog.healthyEntries()).isEmpty();
        assertThat(catalog.healthyPackages()).isEmpty();
        assertThat(catalog.byName()).isEmpty();
    }

    @Test
    @DisplayName("缓存壳懒加载 + refresh 后可重扫")
    void catalogIsLazyAndRefreshable() {
        SkillPackageCatalog catalog = new SkillPackageCatalog(SkillPackageScanner.forClasspath());
        assertThat(catalog.entries()).hasSize(4);
        assertThat(catalog.entries()).isSameAs(catalog.entries());
        catalog.refresh();
        assertThat(catalog.entries()).hasSize(4);
    }
}
