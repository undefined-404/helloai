package com.helloai.core.agent.runtime.sandbox.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `SKX-6` 技能包加载器单元测试（2026-10-26）。
 *
 * <p><b>这里测的是「真包」而不是构造数据</b>：{@code archify} 3.1.0 随 {@code src/main/resources}
 * 一起在测试 classpath 上，所以本测试同时是「入库的包是否完整、清单是否与实际字节一致」的
 * <b>构建期守卫</b>——任何一次资源过滤、换行转换、增量构建残留都会让它红。</p>
 *
 * <p>两个小夹具（{@code broken-manifest} / {@code no-manifest}）专门覆盖 fail-close 分支：
 * 清单不符、清单缺失都必须<b>拒绝注入</b>，而不是装作没事继续装。</p>
 */
@DisplayName("SkillPackageLoader")
class SkillPackageLoaderTest {

    /** 入库包实测值（`SKX-4`，2026-10-26；来源 {@code MANIFEST.json}）。 */
    private static final int ARCHIFY_FILE_COUNT = 122;

    private static final long ARCHIFY_TOTAL_BYTES = 4_330_504L;

    private static final String ARCHIFY_SHA256_TREE =
            "ffe179d2869d7b5c15cf277a87447bedad43c47b76a8955aa0311732f53f515f";

    private final SkillPackageLoader loader = new SkillPackageLoader();

    @Test
    @DisplayName("archify 3.1.0：文件数 / 字节数 / 哈希树与清单一致，且清单本身不注入")
    void archifyShouldLoadAndMatchManifest() {
        LoadedSkillPackage pkg = loader.load("archify", "3.1.0");

        assertThat(pkg.name()).isEqualTo("archify");
        assertThat(pkg.version()).isEqualTo("3.1.0");
        assertThat(pkg.fileCount()).isEqualTo(ARCHIFY_FILE_COUNT);
        assertThat(pkg.totalBytes()).isEqualTo(ARCHIFY_TOTAL_BYTES);
        assertThat(pkg.sha256Tree()).isEqualTo(ARCHIFY_SHA256_TREE);

        List<String> paths = pkg.entries().stream().map(LoadedSkillPackage.Entry::relativePath).toList();
        // 清单是平台侧元数据，不计入载荷（否则「注入数 == 清单数」的自检会失真）
        assertThat(paths).doesNotContain(SkillPackageLoader.MANIFEST_FILE);
        // 字节数与逐文件累加必须自洽
        assertThat(pkg.entries().stream().mapToLong(e -> e.data().length).sum())
                .isEqualTo(pkg.totalBytes());
        // 排序口径：相对路径字节序（与清单生成脚本一致）
        assertThat(paths).isSorted();
    }

    @Test
    @DisplayName("archify 3.1.0：doctor / compare 依赖的关键文件都在（裁剪漏文件的探针）")
    void archifyShouldKeepRuntimeCriticalFiles() {
        LoadedSkillPackage pkg = loader.load("archify", "3.1.0");
        List<String> paths = pkg.entries().stream().map(LoadedSkillPackage.Entry::relativePath).toList();

        assertThat(paths).contains(
                "SKILL.md",
                "bin/archify.mjs",
                "skill-release.json",
                // archify.mjs:3672 动态 import delta/architecture-delta.mjs（compare 子命令）
                "delta/architecture-delta.mjs",
                // doctor 与 renderers 共享的生成式校验器
                "renderers/shared/generated-validators.mjs",
                // 模板（archify.mjs 与 renderers/shared/cli.mjs 都引用）
                "assets/template.html");
        // 越界路径一律不允许（纵深防御：包是我们自己打的，但不该靠「我们自己」保证）
        assertThat(paths).noneMatch(p -> p.startsWith("/") || p.contains(".."));
    }

    @Test
    @DisplayName("archify 3.1.0：最大单文件是 824K 那个（单次写入上限的判据，不是主程序 275K）")
    void archifyLargestFileShouldBeGeneratedValidators() {
        LoadedSkillPackage pkg = loader.load("archify", "3.1.0");

        LoadedSkillPackage.Entry largest = pkg.entries().stream()
                .max((left, right) -> Integer.compare(left.data().length, right.data().length))
                .orElseThrow();

        assertThat(largest.relativePath()).isEqualTo("renderers/shared/generated-validators.mjs");
        assertThat(largest.data().length).isGreaterThan(800_000);
    }

    @Test
    @DisplayName("包不存在 ⇒ 抛错（不返回空包，避免装了个空目录还报成功）")
    void unknownPackageShouldFail() {
        assertThatThrownBy(() -> loader.load("not-exist-package", "9.9.9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("技能包不存在");
    }

    @Test
    @DisplayName("包名 / 版本越界 ⇒ 抛错（路径安全闸门）")
    void unsafeSegmentShouldBeRejected() {
        assertThatThrownBy(() -> loader.load("../../etc", "3.1.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("packageName");
        assertThatThrownBy(() -> loader.load("archify", "3.1.0/../.."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> loader.load("archify", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("清单与实际内容不符 ⇒ 抛错（fail-close：jar 内容与被验证过的包不是同一份）")
    void manifestMismatchShouldFail() {
        assertThatThrownBy(() -> loader.load("broken-manifest", "1.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("清单与实际内容不一致");
    }

    @Test
    @DisplayName("缺清单 ⇒ 抛错（无法校验完整性就不装）")
    void missingManifestShouldFail() {
        assertThatThrownBy(() -> loader.load("no-manifest", "1.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 MANIFEST.json");
    }
    @Test
    @DisplayName("目录索引：列出已入库技能包，且能按名字解析出版本（`SKX-8` 的名字解析入口）")
    void bundledSkillsShouldListAndResolve() {
        List<SkillPackageLoader.BundledSkill> bundled = loader.bundledSkills();

        assertThat(loader.hasBundledSkills()).isTrue();
        assertThat(bundled).contains(new SkillPackageLoader.BundledSkill("archify", "3.1.0"));
        // 索引按 name → version 升序（多版本并存时取最高的前提）
        assertThat(bundled).isSortedAccordingTo(
                java.util.Comparator.comparing(SkillPackageLoader.BundledSkill::name)
                        .thenComparing(SkillPackageLoader.BundledSkill::version));
        // 重复调用走缓存，结果稳定
        assertThat(loader.bundledSkills()).isEqualTo(bundled);

        assertThat(loader.resolve("archify"))
                .isEqualTo(new SkillPackageLoader.BundledSkill("archify", "3.1.0"));
        assertThat(loader.resolve("not-exist")).isNull();
        assertThat(loader.resolve("  ")).isNull();
    }
}
