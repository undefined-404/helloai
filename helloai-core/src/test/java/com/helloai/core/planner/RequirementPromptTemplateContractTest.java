package com.helloai.core.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词模板契约回归（G-011）：``requirement-clarify.md`` / ``requirement-finalize.md``
 * 必须携带 package 六字段终稿示例与提炼约束（S2 + P1-1）；``planner-decompose.md``
 * 必须携带 {{REQUIREMENT_PACKAGE}} 占位、需求包段、uncertainties 继承规则与
 * outOfScope 边界硬约束（S3）与任务级验收覆盖规则（P1-1）；``subtask-review.md`` 必须携带
 * {{CONSTRAINTS}} / {{UNCERTAINTIES}} 占位、轨道 A 约束遵守/不确定性分级语义（S4）与
 * missingEvidence 缺失证据清单 schema（P2-4）——防止模板被回退致 LLM 不再输出需求包 /
 * 不再申报不确定性 / 不再核验约束与不确定性 / 驳回不再给出结构化返工线索
 * （解析侧防御只兜底降级，不替代提示词契约）。
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

    /** 六字段键（含 P1-1 任务级验收条目）+ 提炼约束句 + description/package 职责划分（P1-3，两模板共用契约）。 */
    private void assertPackageContract(String template) {
        assertThat(template)
                .contains("\"package\"")
                .contains("\"goal\"")
                .contains("\"scope\"")
                .contains("\"outOfScope\"")
                .contains("\"acceptanceCriteria\"")
                .contains("\"assumptions\"")
                .contains("\"openQuestions\"")
                .contains("不得为凑格式虚构条目")
                // P1-3 职责写死：禁止以 package 概括代替 description 正文（描述信息量不减）
                .contains("四个小节的正文必须完整给出")
                .contains("禁止以 package 概括代替 description 正文");
    }

    @Test
    @DisplayName("requirement-clarify.md：终稿示例含 package 六字段、提炼约束与职责划分")
    void clarifyTemplateCarriesPackageContract() throws IOException {
        assertPackageContract(loadTemplate("requirement-clarify.md"));
    }

    @Test
    @DisplayName("requirement-finalize.md：终稿示例含 package 六字段、提炼约束与职责划分")
    void finalizeTemplateCarriesPackageContract() throws IOException {
        assertPackageContract(loadTemplate("requirement-finalize.md"));
    }

    @Test
    @DisplayName("planner-decompose.md：{{REQUIREMENT_PACKAGE}} 占位 + 需求包段 + 继承规则 + outOfScope 硬约束 + 验收覆盖 + uncertainties schema")
    void plannerTemplateCarriesRequirementPackageContract() throws IOException {
        String template = loadTemplate("planner-decompose.md");
        assertThat(template)
                .contains("{{REQUIREMENT_PACKAGE}}")
                .contains("## 需求包（结构化准入产物）")
                .contains("uncertainties")
                .contains("kind")
                .contains("必须继承")
                .contains("outOfScope")
                .contains("边界硬约束（G-011）")
                // P1-1 任务级验收覆盖（封闭集合全覆盖 + 子任务回溯标注）
                .contains("acceptanceCriteria")
                .contains("任务级验收覆盖")
                .contains("封闭集合全覆盖");
    }

    @Test
    @DisplayName("subtask-review.md：G-011 D7 双占位符 + 轨道 A 约束核验/不确定性分级语义 + P2-4 缺失证据清单 + G-010 技能覆盖")
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
                .contains("须含验证结论或 BLOCKED 上报痕迹")
                // P2-4 缺失证据清单（返工可执行性）：轨道 A 第 10 条 + 输出 schema 键
                .contains("缺失证据清单")
                .contains("missingEvidence")
                .contains("acceptanceRef")
                // G-010 收口：技能要求下行 + 覆盖核验条款（防模板回退致审查侧不再看技能要求）
                .contains("{{REQUIRED_SKILLS}}")
                .contains("技能要求覆盖核验")
                .contains("点名缺失的技能名")
                .contains("等价实现");
    }

    /** W-6 Grounding 契约：答案必须锚定检索资料、标注来源编号、区分资料与己见。 */
    private void assertGroundingContract(String template) {
        assertThat(template)
                .as("W-6：Grounding 段必须存在（防模板回退致 LLM 脱离检索资料自由发挥）")
                .contains("Grounding")
                .contains("资料未覆盖")
                .contains("[1]");
    }

    @Test
    @DisplayName("W-6：requirement-chat.md 携带 Grounding 约束（来源标注 / 资料与己见区分 / 资料不足直说）")
    void chatTemplateCarriesGroundingContract() throws IOException {
        String template = loadTemplate("requirement-chat.md");
        assertGroundingContract(template);
        assertThat(template)
                .contains("基于我的一般知识")
                .contains("编造编号");
    }

    @Test
    @DisplayName("W-6：requirement-clarify.md 携带 Grounding 约束（不得把资料功能点写进 scope）")
    void clarifyTemplateCarriesGroundingContract() throws IOException {
        String template = loadTemplate("requirement-clarify.md");
        assertGroundingContract(template);
        assertThat(template)
                .contains("把资料里的功能点直接写进")
                .contains("基于一般知识");
    }

    // ══════════════════════════════════════════════════════════════
    //  会话附件注入契约（LOG-20261022-014）
    // ══════════════════════════════════════════════════════════════

    /** 三个主模板都必须带两层附件占位 + 使用约束（防模板回退致模型看不到附件 / 靠猜）。 */
    private void assertAttachmentContract(String template) {
        assertThat(template)
                .as("附件清单占位（恒注入）")
                .contains("{{ATTACHMENT_MANIFEST}}")
                .as("附件正文占位（按预算注入）")
                .contains("{{ATTACHMENT_CONTEXT}}")
                .as("不可读文件禁止臆测")
                .contains("不得**据此推测其中内容")
                .as("附件引用标号")
                .contains("[附件1]")
                .as("截断须说明")
                .contains("正文未完整提供");
    }

    @Test
    @DisplayName("附件契约：clarify / chat / finalize 三模板均携带 MANIFEST + CONTEXT + 使用约束")
    void allRequirementTemplatesCarryAttachmentContract() throws IOException {
        assertAttachmentContract(loadTemplate("requirement-clarify.md"));
        assertAttachmentContract(loadTemplate("requirement-chat.md"));
        assertAttachmentContract(loadTemplate("requirement-finalize.md"));
    }

    /**
     * 变量闭包守卫（与 G-010「无残留 {{…}}」同源）：模板里出现的占位符必须在渲染器支持的集合内。
     *
     * <p>漏一个替换 = 模型收到字面 {@code {{X}}}，这既污染输入又会让模型模仿输出该形态；
     * 而这类缺陷只在真实调用时才暴露，故用静态断言把它挡在编译期之后的第一步。</p>
     */
    @Test
    @DisplayName("附件契约：三模板占位符必须全部落在渲染器支持的变量集合内（无未替换变量）")
    void templatePlaceholdersAreAllRendered() throws IOException {
        java.util.Set<String> supported = java.util.Set.of(
                "CURRENT_USER_MESSAGE", "CONVERSATION_HISTORY", "WEB_SEARCH_CONTEXT",
                "SYSTEM_TIME_CONTEXT", "LONG_TERM_MEMORY_CONTEXT",
                "ATTACHMENT_MANIFEST", "ATTACHMENT_CONTEXT");
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{\\{([A-Z_]+)}}");
        for (String name : new String[]{"requirement-clarify.md", "requirement-chat.md", "requirement-finalize.md"}) {
            String template = loadTemplate(name);
            java.util.regex.Matcher matcher = placeholder.matcher(template);
            while (matcher.find()) {
                assertThat(supported)
                        .as(name + " 出现未替换的占位符 {{" + matcher.group(1) + "}}")
                        .contains(matcher.group(1));
            }
        }
    }
}
