package com.helloai.core.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("技能包 frontmatter 解析（REF-1.1b / REF-1.2b）")
class SkillPackageParserTest {

    private static final String MINIMAL = """
            name: eng-x
            version: "1.0.0"
            description: "示例：说明"
            """;

    @Test
    @DisplayName("最小合法 frontmatter：可选字段全部取默认空值")
    void minimalFrontMatter() throws Exception {
        SkillPackage pkg = SkillPackageParser.parse("eng-x.md", MINIMAL);
        assertThat(pkg.name()).isEqualTo("eng-x");
        assertThat(pkg.version()).isEqualTo("1.0.0");
        assertThat(pkg.description()).isEqualTo("示例：说明");
        assertThat(pkg.requiredTools()).isEmpty();
        assertThat(pkg.dependencies()).isEmpty();
        assertThat(pkg.inputSchema()).isEmpty();
        assertThat(pkg.outputSchema()).isEmpty();
        assertThat(pkg.validationRules()).isEmpty();
        assertThat(pkg.fileName()).isEqualTo("eng-x.md");
    }

    @Test
    @DisplayName("requiredTools: [] 与省略等价")
    void emptyListEqualsOmitted() throws Exception {
        SkillPackage withEmptyList = SkillPackageParser.parse("eng-x.md", MINIMAL + "\nrequiredTools: []");
        SkillPackage omitted = SkillPackageParser.parse("eng-x.md", MINIMAL);
        assertThat(withEmptyList.requiredTools()).isEqualTo(omitted.requiredTools()).isEmpty();
    }

    @Test
    @DisplayName("列表与嵌套映射正常解析")
    void listsAndNestedSchema() throws Exception {
        String yaml = MINIMAL + """

                requiredTools:
                  - web_search
                validationRules:
                  - "规则一"
                  - "规则二"
                outputSchema:
                  type: object
                  properties:
                    command:
                      type: string
                """;
        SkillPackage pkg = SkillPackageParser.parse("eng-x.md", yaml);
        assertThat(pkg.requiredTools()).containsExactly("web_search");
        assertThat(pkg.validationRules()).containsExactly("规则一", "规则二");
        assertThat(pkg.outputSchema()).containsEntry("type", "object");
        assertThat(pkg.outputSchema().get("properties")).isInstanceOf(java.util.Map.class);
    }

    @Test
    @DisplayName("缺必填字段 → corrupt（逐字段报出名字）")
    void missingRequiredFields() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", "version: \"1.0.0\"\ndescription: \"d\""))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", "name: x\ndescription: \"d\""))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", "name: x\nversion: \"1.0.0\""))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("description");
    }

    @Test
    @DisplayName("未知字段（拼写错误）→ corrupt，不静默丢字段")
    void unknownFieldIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", MINIMAL + "\nvalidationrules: []"))
                .isInstanceOf(SkillCorruptException.class)
                .hasMessageContaining("未知字段").hasMessageContaining("validationrules");
    }

    @Test
    @DisplayName("禁止字段 fileName → corrupt（由目录扫描推导）")
    void fileNameFieldIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", MINIMAL + "\nfileName: eng-x.md"))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("fileName");
    }

    @Test
    @DisplayName("version 未加引号（被 YAML 解析成数字）→ corrupt 并提示加引号")
    void unquotedVersionIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md",
                "name: x\nversion: 1.0\ndescription: \"d\""))
                .isInstanceOf(SkillCorruptException.class)
                .hasMessageContaining("version").hasMessageContaining("引号");
    }

    @Test
    @DisplayName("列表写成标量 / 映射写成标量 → corrupt")
    void wrongContainerTypeIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", MINIMAL + "\nrequiredTools: web_search"))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("requiredTools");
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", MINIMAL + "\noutputSchema: nope"))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("outputSchema");
    }

    @Test
    @DisplayName("重复键 → corrupt（snakeyaml 禁重复键）")
    void duplicateKeyIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md",
                "name: x\nname: y\nversion: \"1.0.0\"\ndescription: \"d\""))
                .isInstanceOf(SkillCorruptException.class);
    }

    @Test
    @DisplayName("YAML 语法错 → corrupt（不把异常抛给调用方）")
    void syntaxErrorIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", "name: [未闭合\nversion: \"1.0.0\""))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("YAML 解析失败");
    }

    @Test
    @DisplayName("schema 内出现 null 值 → corrupt（否则 Map.copyOf 抛 NPE，原因不可读）")
    void nullInSchemaIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", MINIMAL + "\noutputSchema:\n  type:\n"))
                .isInstanceOf(SkillCorruptException.class).hasMessageContaining("outputSchema");
    }

    @Test
    @DisplayName("空 frontmatter 文本 → corrupt")
    void blankYamlIsCorrupt() {
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md", "   \n"))
                .isInstanceOf(SkillCorruptException.class);
    }

    @Test
    @DisplayName("真实文件 eng-code-review.md 的核心字段可解析")
    void realFileParses() throws Exception {
        SkillPackage pkg = new SkillPackageCatalog(SkillPackageScanner.forClasspath())
                .byName().get("eng-code-review");
        assertThat(pkg).isNotNull();
        assertThat(pkg.version()).isEqualTo("1.0.0");
        assertThat(pkg.description()).startsWith("代码评审规范：");
        assertThat(pkg.validationRules()).hasSize(4);
        assertThat(pkg.fileName()).isEqualTo("eng-code-review.md");
    }

    @Test
    @DisplayName("已知字段白名单为 8 个（不含 fileName）")
    void knownKeysContract() {
        assertThat(SkillPackageParser.knownKeys())
                // ★8→9 键（`G-022`，2026-10-15）：`requiredBaseCapabilities` 补入——
                // 细粒度技能包指向粗粒度模型能力的引用边。契约变更，此断言是「白名单基数」
                // 的守门点，加键必须同批更新它。
                .containsExactlyInAnyOrder("name", "version", "description", "requiredTools",
                        "requiredBaseCapabilities", "dependencies", "inputSchema", "outputSchema",
                        "validationRules")
                .doesNotContain("fileName");
    }

    @Test
    @DisplayName("G-022 引用边：requiredBaseCapabilities 可解析；缺省为空集；类型不符报可读错误")
    void requiredBaseCapabilitiesParsing() throws Exception {
        // 显式声明 → 原样解析（顺序保序）
        SkillPackage declared = SkillPackageParser.parse("eng-x.md",
                MINIMAL + "\nrequiredBaseCapabilities:\n  - webSearch\n  - reasoning\n");
        assertThat(declared.requiredBaseCapabilities()).containsExactly("webSearch", "reasoning");

        // 显式空列表 → 空集
        SkillPackage empty = SkillPackageParser.parse("eng-x.md",
                MINIMAL + "\nrequiredBaseCapabilities: []\n");
        assertThat(empty.requiredBaseCapabilities()).isEmpty();

        // 缺省（不写该键）→ 空集（存量已安装包即此形态）
        SkillPackage absent = SkillPackageParser.parse("eng-x.md", MINIMAL);
        assertThat(absent.requiredBaseCapabilities()).isEmpty();

        // 类型不符（写映射而非列表）→ 可读错误，而非静默吞掉
        assertThatThrownBy(() -> SkillPackageParser.parse("eng-x.md",
                MINIMAL + "\nrequiredBaseCapabilities:\n  webSearch: true\n"))
                .isInstanceOf(SkillCorruptException.class)
                .hasMessageContaining("requiredBaseCapabilities");
    }

    @Test
    @DisplayName("列表元素数不因解析而改变（防截断）")
    void listSizePreserved() throws Exception {
        String yaml = MINIMAL + "\nvalidationRules:\n  - \"a\"\n  - \"b\"\n  - \"c\"\n";
        assertThat(SkillPackageParser.parse("eng-x.md", yaml).validationRules())
                .isEqualTo(List.of("a", "b", "c"));
    }
}
