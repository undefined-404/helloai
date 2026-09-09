package com.helloai.core.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词模板契约回归（G-011）：``requirement-clarify.md`` / ``requirement-finalize.md``
 * 必须携带 package 五字段终稿示例与提炼约束（S2）；``planner-decompose.md``
 * 必须携带 {{REQUIREMENT_PACKAGE}} 占位、需求包段、uncertainties 继承规则与
 * outOfScope 边界硬约束（S3）；``subtask-review.md`` 必须携带
 * {{CONSTRAINTS}} / {{UNCERTAINTIES}} 占位与轨道 A 约束遵守/不确定性分级语义（S4）
 * ——防止模板被回退致 LLM 不再输出需求包 / 不再申报不确定性 / 不再核验约束与
 * 不确定性（解析侧防御只兜底降级，不替代提示词契约）。
 */
@DisplayName("提示词模板契约（G-011 S2-S4）")
class RequirementPromptTemplateContractTest {

    private String loadTemplate(String name) throws IOException {
        try (InputStream in = RequirementPromptTemplateContractTest.class.getClassLoader()
                .getResourceAsStream("prompts/" + name)) {
            assertThat(in).as("模板资源必须存在: prompts/" + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 五字段键 + 提炼约束句（两模板共用契约）。 */
    private void assertPackageContract(String template) {
        assertThat(template)
                .contains("\"package\"")
                .contains("\"goal\"")
                .contains("\"scope\"")
                .contains("\"outOfScope\"")
                .contains("\"assumptions\"")
                .contains("\"openQuestions\"")
                .contains("不得为凑格式虚构条目");
    }

    @Test
    @DisplayName("requirement-clarify.md：终稿示例含 package 五字段与提炼约束")
    void clarifyTemplateCarriesPackageContract() throws IOException {
        assertPackageContract(loadTemplate("requirement-clarify.md"));
    }

    @Test
    @DisplayName("requirement-finalize.md：终稿示例含 package 五字段与提炼约束")
    void finalizeTemplateCarriesPackageContract() throws IOException {
        assertPackageContract(loadTemplate("requirement-finalize.md"));
    }

    @Test
    @DisplayName("planner-decompose.md：{{REQUIREMENT_PACKAGE}} 占位 + 需求包段 + 继承规则 + outOfScope 硬约束 + uncertainties schema")
    void plannerTemplateCarriesRequirementPackageContract() throws IOException {
        String template = loadTemplate("planner-decompose.md");
        assertThat(template)
                .contains("{{REQUIREMENT_PACKAGE}}")
                .contains("## 需求包（结构化准入产物）")
                .contains("uncertainties")
                .contains("kind")
                .contains("必须继承")
                .contains("outOfScope")
                .contains("边界硬约束（G-011）");
    }

    @Test
    @DisplayName("subtask-review.md：G-011 D7 双占位符 + 轨道 A 约束核验/不确定性分级语义")
    void reviewTemplateCarriesConstraintsAndUncertaintiesContract() throws IOException {
        String template = loadTemplate("subtask-review.md");
        assertThat(template)
                .contains("{{CONSTRAINTS}}")
                .contains("{{UNCERTAINTIES}}")
                .contains("{{EXECUTION_OUTPUT}}")
                .contains("{{VERIFICATION_SIGNAL}}")
                .contains("执行约束遵守核验")
                .contains("不确定性分级核验")
                .contains("ASSUMPTION 类申报不构成驳回理由")
                .contains("须含验证结论或 BLOCKED 上报痕迹");
    }
}