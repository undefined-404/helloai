package com.helloai.core.agent.skill;

import com.helloai.common.constant.SkillPackageState;
import com.helloai.core.agent.entity.InstalledSkillPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录双源（REF-1.6）回归：**已安装技能包的正文被渲染进 {@code resolve()} 注入段**。
 *
 * <p><b>为什么要这个测试</b>：双源改造引入了一条全新路径——技能正文不再只来自 classpath，
 * 也可能来自 PG 里已安装包的 {@code body}。这条路径的**四个消费点全在内部 LLM 执行链上**
 * （{@code RuntimeTurnExecutor} / {@code AgentRuntimeContextAssembler} /
 * {@code PlatformAgentExecutionService} / {@code PlannerDecomposeAsyncService}），
 * 而改造前的覆盖只到「内置包逐字一致」（{@link GoldenSectionParityTest}）与
 * 「已安装包出现在目录 API」（`verify-skill-package-install.ps1`）——
 * **「已安装包的正文真的进了注入段」这一步此前零覆盖**。</p>
 *
 * <p><b>为什么不需要 DB / LLM</b>：{@code SkillPackageCatalog} 留了一个包级私有构造器
 * 可注入替身 {@link InstalledSkillPackageSource}，故本测试纯内存、零外部依赖。
 * 它验证的是「我们的代码有没有把正文正确渲染进注入段」，与「LLM 是否理解规范」正交——
 * 后者要靠真实调用，与本次改动无关。</p>
 */
@DisplayName("技能目录双源：已安装包正文进注入段（REF-1.6）")
class InstalledSkillResolveTest {

    private static final String INSTALLED_NAME = "demo-installed-skill";

    /** 速览里的实质内容锚点（取自 eng-code-review 的执行速览）。 */
    private static final String CONTENT_ANCHOR = "接口契约（C1）";

    private static final String BUILTIN_NAME = "eng-code-review";

    @Test
    @DisplayName("★已安装技能包的正文被渲染进 resolve() 的注入段")
    void installedPackageBodyIsInjected() throws Exception {
        AgentSkillSpecService service = serviceWithInstalledPackage(activeRow());

        String section = service.resolve(List.of(INSTALLED_NAME)).section();

        assertThat(section)
                .as("已安装包的速览必须以「### <name>」小节形式进入注入段")
                .contains("### " + INSTALLED_NAME);
        assertThat(section)
                .as("注入段必须含包正文的实质内容，而不只是标签")
                .contains(CONTENT_ANCHOR);
    }

    @Test
    @DisplayName("双源合并：已安装源不干扰内置源的注入结果")
    void builtinSourceUnaffectedByInstalledSource() throws Exception {
        AgentSkillSpecService service = serviceWithInstalledPackage(activeRow());

        String builtinOnly = service.resolve(List.of(BUILTIN_NAME)).section();

        assertThat(builtinOnly).contains("### " + BUILTIN_NAME).contains(CONTENT_ANCHOR);
        assertThat(builtinOnly).doesNotContain(INSTALLED_NAME);
    }

    @Test
    @DisplayName("已安装源故障被隔离：内置源仍可正常注入（不连累执行链基线）")
    void installedSourceFailureIsIsolated() {
        InstalledSkillPackageSource broken = () -> {
            throw new IllegalStateException("模拟 DB 不可用");
        };
        SkillPackageCatalog catalog = new SkillPackageCatalog(
                SkillPackageScanner.forClasspath(), broken, new ClasspathSkillContentSource());
        AgentSkillSpecService service = new AgentSkillSpecServiceImpl(catalog);

        String section = service.resolve(List.of(BUILTIN_NAME)).section();

        assertThat(section).contains(CONTENT_ANCHOR);
    }

    @Test
    @DisplayName("对照组：已安装源为空时，该标签解析不出任何内容")
    void emptyInstalledSourceYieldsNothing() {
        // 对照组作用：证明上一条 ★ 用例命中确实来自「已安装源」，
        // 而不是内置源或其它路径意外提供了同名内容。
        AgentSkillSpecService service = serviceWithInstalledPackage(null);

        assertThat(service.resolve(List.of(INSTALLED_NAME)).section()).isEmpty();
        assertThat(service.resolve(List.of(INSTALLED_NAME)).matchedLabels()).isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    //  夹具
    // ────────────────────────────────────────────────────────────

    /** 已安装行；正文复用真实内置包的 md，保证渲染器行为已知（不依赖测试造的数据）。 */
    private static InstalledSkillPackage activeRow() throws Exception {
        InstalledSkillPackage row = new InstalledSkillPackage();
        row.setName(INSTALLED_NAME);
        row.setVersion("1.0.0");
        row.setDescription("已安装演示技能包");
        row.setState(SkillPackageState.ACTIVE);
        row.setBody(readClasspath(BUILTIN_NAME + ".md"));
        return row;
    }

    private static AgentSkillSpecService serviceWithInstalledPackage(InstalledSkillPackage row) {
        InstalledSkillPackageSource source = row == null
                ? () -> List.<InstalledSkillPackage>of()
                : () -> List.of(row);
        SkillPackageCatalog catalog = new SkillPackageCatalog(
                SkillPackageScanner.forClasspath(), source, new ClasspathSkillContentSource());
        return new AgentSkillSpecServiceImpl(catalog);
    }

    private static String readClasspath(String fileName) throws Exception {
        ClassPathResource resource = new ClassPathResource("skills/plugins/" + fileName);
        assertThat(resource.exists()).as("夹具资源缺失: %s", fileName).isTrue();
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
