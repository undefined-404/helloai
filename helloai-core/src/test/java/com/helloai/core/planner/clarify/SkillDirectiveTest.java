package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillDirective} 单测（2026-10-26）：「＋→技能」选中的产出型技能包如何从消息里被识别、
 * 如何落进 payload、如何渲染成 prompt 段、如何展开依赖。
 *
 * <p>断言口径：① 无前缀时行为<b>逐字等于改造前</b>（原文 / 空段 / 空列表）；
 * ② 技能不存在时<b>不注入任何规范</b>（退回普通对话，不编造）；③ 依赖包必须排在依赖方之前。</p>
 */
class SkillDirectiveTest {

    private AgentSkillSpecService specService;

    private SkillDirective directive;

    @BeforeEach
    void setUp() {
        specService = mock(AgentSkillSpecService.class);
        directive = new SkillDirective(specService, new ObjectMapper());
    }

    private static SkillPackage pkg(String name, String description, List<String> dependencies) {
        return new SkillPackage(name, "1.0.0", description, List.of("internal_run_skill"),
                List.of(), dependencies, Map.of(), Map.of(), List.of(), name + ".md");
    }

    @Test
    @DisplayName("parse：剥掉前缀并保留用户原文")
    void parseStripsPrefix() {
        SkillDirective.Parsed parsed = directive.parse("[skill:archify] 画个支付流程图");
        assertEquals("archify", parsed.skillName());
        assertEquals("画个支付流程图", parsed.text());
        assertTrue(parsed.hasSkill());
    }

    @Test
    @DisplayName("parse：无前缀时原文不带任何改动（改造前等价）")
    void parseWithoutPrefixIsIdentity() {
        SkillDirective.Parsed parsed = directive.parse("帮我看看这个需求");
        assertNull(parsed.skillName());
        assertEquals("帮我看看这个需求", parsed.text());
        assertFalse(parsed.hasSkill());
    }

    @Test
    @DisplayName("parse：只有前缀没有正文时 text 为空串（仍算选了技能）")
    void parsePrefixOnly() {
        SkillDirective.Parsed parsed = directive.parse("[skill:archify]   ");
        assertEquals("archify", parsed.skillName());
        assertEquals("", parsed.text());
        assertTrue(parsed.hasSkill());
    }

    @Test
    @DisplayName("parse：前缀大小写不敏感，且只认消息开头")
    void parseCaseInsensitiveAndAnchored() {
        assertEquals("archify", directive.parse("[SKILL:archify] x").skillName());
        assertNull(directive.parse("请用 [skill:archify] 画图").skillName());
    }

    @Test
    @DisplayName("mergePayload：空 payload 建对象，已有字段保留")
    void mergePayloadKeepsExistingFields() throws Exception {
        String merged = directive.mergePayload("{\"questions\":[]}", "archify");
        Map<String, Object> map = new ObjectMapper().readValue(merged, HashMap.class);
        assertEquals("archify", map.get("skill"));
        assertTrue(map.containsKey("questions"));
        assertEquals("archify",
                new ObjectMapper().readValue(directive.mergePayload(null, "archify"), HashMap.class).get("skill"));
    }

    @Test
    @DisplayName("mergePayload：技能名为空时原样返回（不写空字段）")
    void mergePayloadWithoutSkillIsIdentity() {
        assertEquals("{\"a\":1}", directive.mergePayload("{\"a\":1}", null));
        assertNull(directive.mergePayload(null, "  "));
    }

    @Test
    @DisplayName("skillOfPayload：能读回，非法 JSON / 缺字段返回 null")
    void skillOfPayloadReadsBack() {
        assertEquals("archify", directive.skillOfPayload("{\"skill\":\"archify\"}"));
        assertNull(directive.skillOfPayload("not-json"));
        assertNull(directive.skillOfPayload("{\"other\":1}"));
        assertNull(directive.skillOfPayload(null));
    }

    @Test
    @DisplayName("renderInstruction：技能不存在时不注入任何规范（不编造）")
    void renderInstructionSkipsUnknownSkill() {
        when(specService.getPackage("nope")).thenReturn(null);
        assertEquals("", directive.renderInstruction("nope"));
        assertEquals("", directive.renderInstruction(null));
    }

    @Test
    @DisplayName("renderInstruction：正文为空时同样不注入")
    void renderInstructionSkipsEmptyBody() {
        when(specService.getPackage("archify")).thenReturn(pkg("archify", "画图", List.of()));
        when(specService.loadBody("archify")).thenReturn("   ");
        assertEquals("", directive.renderInstruction("archify"));
    }

    @Test
    @DisplayName("renderInstruction：含技能名、正文与平台落地口径")
    void renderInstructionContainsBodyAndFacts() {
        when(specService.getPackage("archify")).thenReturn(pkg("archify", "画图", List.of()));
        when(specService.loadBody("archify")).thenReturn("# archify\n先落 JSON 再渲染");
        String text = directive.renderInstruction("archify");
        assertTrue(text.contains("`archify` @1.0.0"));
        assertTrue(text.contains("先落 JSON 再渲染"));
        assertTrue(text.contains("internal_run_skill"));
        assertTrue(text.contains("internal_export_artifact"));
        // 明确禁止「声称已生成文件」——产物属于执行阶段
        assertTrue(text.contains("不要声称"));
    }

    @Test
    @DisplayName("renderInstruction：超长正文截断到上限并标注")
    void renderInstructionTruncatesLongBody() {
        when(specService.getPackage("archify")).thenReturn(pkg("archify", "画图", List.of()));
        when(specService.loadBody("archify")).thenReturn("x".repeat(SkillDirective.BODY_LIMIT + 500));
        String text = directive.renderInstruction("archify");
        assertTrue(text.contains("已截断"));
        assertTrue(text.length() < SkillDirective.BODY_LIMIT + 2000);
    }

    @Test
    @DisplayName("requiredSkillsWithDependencies：依赖在前、自身在后、去重")
    void requiredSkillsExpandDependencies() {
        when(specService.getPackage("biz-process-lifecycle"))
                .thenReturn(pkg("biz-process-lifecycle", "状态机", List.of("archify")));
        when(specService.getPackage("archify")).thenReturn(pkg("archify", "画图", List.of()));
        assertEquals(List.of("archify", "biz-process-lifecycle"),
                directive.requiredSkillsWithDependencies("biz-process-lifecycle"));
    }

    @Test
    @DisplayName("requiredSkillsWithDependencies：技能不存在 / 空名返回空列表")
    void requiredSkillsOnlyForKnownPackages() {
        assertTrue(directive.requiredSkillsWithDependencies("nope").isEmpty());
        assertTrue(directive.requiredSkillsWithDependencies(null).isEmpty());
    }
}
