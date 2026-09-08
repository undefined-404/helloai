package com.helloai.core.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentSkillSpecService} 实现单元测试（Phase 1 Step 1 fix：由 task 域
 * {@code PluginSkillSpecServiceImplTest} 迁域改造，LOG-20260904-009）。
 *
 * <p>纯函数语义：入参即 requiredSkills，不再查 task（§6 依赖方向红线）；命中语义与
 * Prompt 注入事实严格一致（两层过滤：标签命中 + 速览非空），走真实 classpath 资源
 * {@code skills/plugins/*.md}（helloai-core 主资源，不 mock）。</p>
 */
@DisplayName("AgentSkillSpecServiceImpl")
class AgentSkillSpecServiceImplTest {

    private final AgentSkillSpecService service = new AgentSkillSpecServiceImpl();

    private static final String REQUIRED = "任务 required_skills 命中 eng-code-review 时注入执行 Prompt";

    @Test
    @DisplayName("requiredSkills 为 null → 空三字段（best-effort，不抛异常）")
    void shouldReturnEmptyWhenRequiredSkillsNull() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(null);
        assertThat(resolved).isNotNull();
        assertThat(resolved.requiredSkills()).isEmpty();
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("requiredSkills 为空列表 → 空三字段")
    void shouldReturnEmptyWhenRequiredSkillsEmpty() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(List.of());
        assertThat(resolved.requiredSkills()).isEmpty();
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("未命中未知标签 → matchedLabels 空、section 空（声明原样保留）")
    void shouldIgnoreUnknownSkills() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(List.of("unknown-skill-a"));
        assertThat(resolved.requiredSkills()).containsExactly("unknown-skill-a");
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("命中单标签 → 渲染速览段：含速览正文、不含详细规范与 h1 标题")
    void shouldRenderSpeedSummaryOnlyForSingleHit() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(List.of("eng-code-review"));
        assertThat(resolved.matchedLabels()).containsExactly("eng-code-review");
        assertThat(resolved.section())
                // 速览正文（四要素第 1 条首句）必须注入
                .contains(REQUIRED)
                // 速览截断：详细规范部分不得进入渲染段
                .doesNotContain("## 详细规范")
                .doesNotContain("### C1 接口契约")
                // h1 标题行剔除（渲染段自带 ### 标签标题）
                .doesNotContain("# eng-code-review 平台技能规范")
                // 渲染段自带标题层级
                .contains("### eng-code-review");
    }

    @Test
    @DisplayName("多命中按 KNOWN_SPECS 声明顺序渲染（不是 requiredSkills 声明顺序）")
    void shouldRenderMultipleHitsInKnownSpecsOrder() {
        // 输入顺序与声明顺序相反（eng-doc-standard 在前），渲染必须仍按 eng-code-review → eng-doc-standard
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(
                List.of("eng-doc-standard", "eng-code-review"));
        assertThat(resolved.matchedLabels())
                .containsExactly("eng-code-review", "eng-doc-standard");
        assertThat(resolved.section().indexOf("### eng-code-review"))
                .isLessThan(resolved.section().indexOf("### eng-doc-standard"));
    }

    @Test
    @DisplayName("混合已知 + 未知标签 → 未知忽略，只注入命中项")
    void shouldMixKnownAndUnknown() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(
                List.of("eng-verification", "eng-unknown"));
        assertThat(resolved.matchedLabels()).containsExactly("eng-verification");
        assertThat(resolved.section()).contains("### eng-verification").doesNotContain("eng-unknown");
    }

    @Test
    @DisplayName("ResolvedSpec record 构造器将 null 字段规范化为默认值")
    void shouldNormalizeNullFieldsInRecord() {
        AgentSkillSpecService.ResolvedSpec resolved = new AgentSkillSpecService.ResolvedSpec(null, null, null);
        assertThat(resolved.requiredSkills()).isEmpty();
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("listPackages：声明顺序返回全部技能包，元数据字段非空")
    void shouldListAllPackagesInDeclarationOrder() {
        List<SkillPackage> packages = service.listPackages();
        assertThat(packages).hasSize(3);
        assertThat(packages).extracting(SkillPackage::name)
                .containsExactly("eng-code-review", "eng-doc-standard", "eng-verification");
        packages.forEach(p -> {
            assertThat(p.version()).isNotBlank();
            assertThat(p.description()).isNotBlank();
            assertThat(p.fileName()).endsWith(".md");
            assertThat(p.requiredTools()).isNotNull();
            assertThat(p.dependencies()).isNotNull();
            assertThat(p.inputSchema()).isNotNull();
            assertThat(p.outputSchema()).isNotNull();
            assertThat(p.validationRules()).isNotNull();
        });
    }

    @Test
    @DisplayName("resolvePackages：命中返回技能包元数据（与 resolve 同命中语义）")
    void shouldResolvePackagesOnHit() {
        List<SkillPackage> packages = service.resolvePackages(List.of("eng-code-review"));
        assertThat(packages).hasSize(1);
        SkillPackage p = packages.get(0);
        assertThat(p.name()).isEqualTo("eng-code-review");
        assertThat(p.version()).isEqualTo("1.0.0");
        assertThat(p.fileName()).isEqualTo("eng-code-review.md");
        assertThat(p.description()).isNotBlank();
        // P1 后续字段：dependencies/inputSchema 无声明置空；outputSchema=四元组；validationRules=4 条 C 规则
        assertThat(p.dependencies()).isEmpty();
        assertThat(p.inputSchema()).isEmpty();
        assertThat(p.outputSchema())
                .containsEntry("type", "array")
                .containsKey("items");
        assertThat(p.validationRules()).hasSize(4);
    }

    @Test
    @DisplayName("SkillPackage 构造器将 null 字段规范化为默认值")
    void shouldNormalizeNullFieldsInSkillPackage() {
        SkillPackage pkg = new SkillPackage(null, null, null, null, null, null, null, null, null);
        assertThat(pkg.name()).isEmpty();
        assertThat(pkg.version()).isEmpty();
        assertThat(pkg.description()).isEmpty();
        assertThat(pkg.requiredTools()).isEmpty();
        assertThat(pkg.dependencies()).isEmpty();
        assertThat(pkg.inputSchema()).isEmpty();
        assertThat(pkg.outputSchema()).isEmpty();
        assertThat(pkg.validationRules()).isEmpty();
        assertThat(pkg.fileName()).isEmpty();
    }

    @Test
    @DisplayName("resolvePackages：未知忽略、空输入返回空、多命中按声明顺序")
    void shouldResolvePackagesEdgeCases() {
        assertThat(service.resolvePackages(List.of("unknown-skill-a"))).isEmpty();
        assertThat(service.resolvePackages(null)).isEmpty();
        assertThat(service.resolvePackages(List.of())).isEmpty();
        assertThat(service.resolvePackages(List.of("eng-doc-standard", "eng-code-review")))
                .extracting(SkillPackage::name)
                .containsExactly("eng-code-review", "eng-doc-standard");
    }
}