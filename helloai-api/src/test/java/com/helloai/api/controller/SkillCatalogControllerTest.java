package com.helloai.api.controller;

import com.helloai.api.dto.skill.SkillPackageResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.skill.SkillCatalogEntry;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.skill.SkillPackageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SkillCatalogController} 单元测试（REF-1.2c）。
 *
 * <p><b>最核心的断言</b>：{@code label} 必须与改造前前端硬编码的 4 条字面量
 * <b>逐字相同</b>——这是「服务端下发」能对用户可见文案零变化的证据，也是前端零测试基建
 * （无 vitest/jest）下唯一能钉死该契约的地方。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillCatalogController 技能目录查询")
class SkillCatalogControllerTest {

    /** 改造前 helloai-ui/src/constants/agentSkills.ts 的 ENG_SKILL_OPTIONS 字面量（迁移前的唯一事实源）。 */
    private static final Map<String, String> LEGACY_FRONTEND_LABELS = new LinkedHashMap<>();

    static {
        LEGACY_FRONTEND_LABELS.put("eng-code-review", "eng-code-review（代码评审规范）");
        LEGACY_FRONTEND_LABELS.put("eng-doc-standard", "eng-doc-standard（文档规范）");
        LEGACY_FRONTEND_LABELS.put("eng-verification", "eng-verification（验证规范）");
        LEGACY_FRONTEND_LABELS.put("eng-web-research", "eng-web-research（联网调研规范）");
    }

    @Mock
    private SkillPackageCatalog skillPackageCatalog;

    @InjectMocks
    private SkillCatalogController controller;

    @Test
    @DisplayName("★ label 与迁移前前端字面量逐字一致（服务端下发零文案变化）")
    void labelMatchesLegacyFrontendLiterals() {
        List<SkillCatalogEntry> entries = LEGACY_FRONTEND_LABELS.keySet().stream()
                .map(name -> SkillCatalogEntry.healthy(spec(name)))
                .toList();
        when(skillPackageCatalog.entries()).thenReturn(entries);

        R<List<SkillPackageResponse>> result = controller.catalog();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(4);
        Map<String, String> actual = new LinkedHashMap<>();
        result.getData().forEach(r -> actual.put(r.getName(), r.getLabel()));
        assertThat(actual).isEqualTo(LEGACY_FRONTEND_LABELS);
        verify(skillPackageCatalog).entries();
        verifyNoMoreInteractions(skillPackageCatalog);
    }

    @Test
    @DisplayName("字段逐项映射（含 schema / 规则 / fileName）")
    void fieldsAreMapped() {
        SkillPackage spec = new SkillPackage("eng-code-review", "1.0.0", "代码评审规范：接口契约",
                List.of("web_search"), List.of("dep-a"),
                Map.of("type", "object"), Map.of("type", "array"), List.of("规则一"), "eng-code-review.md");
        when(skillPackageCatalog.entries()).thenReturn(List.of(SkillCatalogEntry.healthy(spec)));

        SkillPackageResponse response = controller.catalog().getData().get(0);

        assertThat(response.getName()).isEqualTo("eng-code-review");
        assertThat(response.getVersion()).isEqualTo("1.0.0");
        assertThat(response.getDescription()).isEqualTo("代码评审规范：接口契约");
        assertThat(response.getRequiredTools()).containsExactly("web_search");
        assertThat(response.getDependencies()).containsExactly("dep-a");
        assertThat(response.getInputSchema()).containsEntry("type", "object");
        assertThat(response.getOutputSchema()).containsEntry("type", "array");
        assertThat(response.getValidationRules()).containsExactly("规则一");
        assertThat(response.getFileName()).isEqualTo("eng-code-review.md");
        assertThat(response.getLabel()).isEqualTo("eng-code-review（代码评审规范）");
        assertThat(response.isCorrupt()).isFalse();
        assertThat(response.getError()).isNull();
    }

    @Test
    @DisplayName("坏技能包：corrupt=true + error 非空 + label 退化为 name")
    void corruptEntryIsExposed() {
        when(skillPackageCatalog.entries())
                .thenReturn(List.of(SkillCatalogEntry.corrupt("broken.md", "frontmatter 缺失：文件未以 --- 开头")));

        SkillPackageResponse response = controller.catalog().getData().get(0);

        assertThat(response.getName()).isEqualTo("broken");
        assertThat(response.getFileName()).isEqualTo("broken.md");
        assertThat(response.isCorrupt()).isTrue();
        assertThat(response.getError()).contains("frontmatter 缺失");
        assertThat(response.getLabel()).isEqualTo("broken");
    }

    @Test
    @DisplayName("描述无全角冒号 → label 取整串；描述为空 → label 退化为 name")
    void labelDerivationEdgeCases() {
        SkillPackage noColon = new SkillPackage("a", "1.0.0", "无冒号描述", List.of(), List.of(),
                Map.of(), Map.of(), List.of(), "a.md");
        SkillPackage blankDesc = new SkillPackage("b", "1.0.0", "", List.of(), List.of(),
                Map.of(), Map.of(), List.of(), "b.md");
        when(skillPackageCatalog.entries()).thenReturn(List.of(
                SkillCatalogEntry.healthy(noColon), SkillCatalogEntry.healthy(blankDesc)));

        List<SkillPackageResponse> data = controller.catalog().getData();

        assertThat(data.get(0).getLabel()).isEqualTo("a（无冒号描述）");
        assertThat(data.get(1).getLabel()).isEqualTo("b");
    }

    /** 与真实 frontmatter 同形的样例（description 含全角冒号）。 */
    private static SkillPackage spec(String name) {
        String shortDescription = switch (name) {
            case "eng-code-review" -> "代码评审规范";
            case "eng-doc-standard" -> "文档规范";
            case "eng-verification" -> "验证规范";
            default -> "联网调研规范";
        };
        return new SkillPackage(name, "1.0.0", shortDescription + "：其余描述",
                List.of(), List.of(), Map.of(), Map.of(), List.of(), name + ".md");
    }
}
