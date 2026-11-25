package com.helloai.core.shared.constraint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `G-037` 约束文档目录（shared 域）单测。
 *
 * <p>manifest 固定从 classpath 读取（真实登记表）；docRoot 用 {@code @TempDir} 注入，
 * 不碰默认 {@code ../doc} 解析（测试不依赖本机文件布局）。章节切分 / scope 过滤在
 * 自建假文档上断言；fail-open 路径（文件缺失 / 未登记 ref / 未命中 scope）逐条覆盖。</p>
 */
@DisplayName("ConstraintDocCatalog（G-037 约束文档目录）")
class ConstraintDocCatalogTest {

    @TempDir
    Path tempDir;

    /** 假文档：仅含 5/6 两节骨架（其余 manifest 章节故意缺标题 → 整文档模式应跳过）。 */
    private static final String FAKE_DOC =
            "# HelloAI 代码开发规范\n\n序言……\n\n"
                    + "# 5. Core 业务域结构\n\n5A 域结构正文……\n\n"
                    + "# 6. Core 域依赖方向\n\n6A 依赖方向正文……\n";

    private ConstraintDocCatalog catalogWithDoc(String docContent) throws IOException {
        Path doc = tempDir.resolve("doc/HelloAI_CODE_STYLE.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, docContent);
        return new ConstraintDocCatalog(tempDir);
    }

    @Test
    @DisplayName("list 从 classpath manifest 解析：含 code-style 且 8 个试点章节元数据（不含正文）")
    void listShouldReadManifest() {
        ConstraintDocCatalog catalog = new ConstraintDocCatalog(tempDir);

        List<ConstraintDocCatalog.ConstraintDocEntry> entries = catalog.list();

        assertThat(entries).extracting(ConstraintDocCatalog.ConstraintDocEntry::ref)
                .contains("code-style");
        ConstraintDocCatalog.ConstraintDocEntry codeStyle = entries.stream()
                .filter(e -> e.ref().equals("code-style")).findFirst().orElseThrow();
        assertThat(codeStyle.sections())
                .extracting(ConstraintDocCatalog.ConstraintDocSectionMeta::id)
                .containsExactly("s1", "s2", "s4", "s5", "s6", "s7", "s8", "s9");
    }

    @Test
    @DisplayName("list 解析执行侧路径字段：manifest 带 sandboxPath/repoPath 时透出（三路径语义 G-037 §3.1）")
    void listShouldExposeExecutionSidePaths() {
        ConstraintDocCatalog catalog = new ConstraintDocCatalog(tempDir);

        List<ConstraintDocCatalog.ConstraintDocEntry> entries = catalog.list();

        ConstraintDocCatalog.ConstraintDocEntry codeStyle = entries.stream()
                .filter(e -> e.ref().equals("code-style")).findFirst().orElseThrow();
        // code-style 在 manifest.json 中登记了 sandboxPath/repoPath（执行侧路径 = 工作区根相对），
        // 未登记条目（若有）该字段为 null，不得误判为任意字符串
        assertThat(codeStyle.sandboxPath()).isEqualTo("doc/HelloAI_CODE_STYLE.md");
        assertThat(codeStyle.repoPath()).isEqualTo("doc/HelloAI_CODE_STYLE.md");
    }

    @Test
    @DisplayName("scope 命中：只取命中的章节，内容止于下一个同级标题（标题切分而非行号）")
    void loadShouldCutByTitlePatternAndScope() throws IOException {
        ConstraintDocCatalog catalog = catalogWithDoc(FAKE_DOC);

        ConstraintDocCatalog.ConstraintDocLoadResult result = catalog.load("code-style", List.of("s5"));

        assertThat(result.unavailable()).isFalse();
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).id()).isEqualTo("s5");
        assertThat(result.sections().get(0).content())
                .contains("5A 域结构正文")
                .doesNotContain("6A 依赖方向正文");
    }

    @Test
    @DisplayName("scope 为空 = 整文档：返回所有标题命中的章节；未登记的章节标题自动跳过")
    void loadWithEmptyScopeShouldReturnAllMatchedSections() throws IOException {
        ConstraintDocCatalog catalog = catalogWithDoc(FAKE_DOC);

        ConstraintDocCatalog.ConstraintDocLoadResult result = catalog.load("code-style", null);

        assertThat(result.unavailable()).isFalse();
        // 假文档只有 s5/s6 有标题骨架，其余 manifest 章节（s1/s2/s4/s7/s8/s9）标题缺失 ⇒ 跳过
        assertThat(result.sections()).extracting(ConstraintDocCatalog.LoadedSection::id)
                .containsExactly("s5", "s6");
    }

    @Test
    @DisplayName("未登记 scope：章节未命中 → unavailable（不抛异常，Fail-open）")
    void loadShouldRejectUnknownScope() throws IOException {
        ConstraintDocCatalog catalog = catalogWithDoc(FAKE_DOC);

        ConstraintDocCatalog.ConstraintDocLoadResult result = catalog.load("code-style", List.of("s999"));

        assertThat(result.unavailable()).isTrue();
        assertThat(result.reason()).contains("scope 未命中任何章节");
    }

    @Test
    @DisplayName("未登记 ref：白名单拒绝 → unavailable（任意文件读取防线）")
    void loadShouldRejectUnknownRef() {
        ConstraintDocCatalog catalog = new ConstraintDocCatalog(tempDir);

        ConstraintDocCatalog.ConstraintDocLoadResult result = catalog.load("../../etc/passwd", null);

        assertThat(result.unavailable()).isTrue();
        assertThat(result.reason()).contains("未登记的约束文档 ref");
    }

    @Test
    @DisplayName("文件缺失：Fail-open 降级返回 unavailable，绝不抛异常（读不到不拖垮主链）")
    void loadShouldDegradeWhenFileMissing() {
        ConstraintDocCatalog catalog = new ConstraintDocCatalog(tempDir);

        ConstraintDocCatalog.ConstraintDocLoadResult result = catalog.load("code-style", List.of("s5"));

        assertThat(result.unavailable()).isTrue();
        assertThat(result.reason()).contains("不可读");
    }
}