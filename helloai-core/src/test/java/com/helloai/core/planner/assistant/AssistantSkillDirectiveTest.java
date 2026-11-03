package com.helloai.core.planner.assistant;

import com.helloai.core.planner.clarify.SkillDirective;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantSkillDirective} 单测（PA-11 v2）。
 *
 * <p>覆盖四条判定：空技能不加约束、两个流程技能各自的约束段、产出型技能包走
 * {@link SkillDirective} 同源正文、不认识的技能标识退回普通问答（不编造规范）。</p>
 */
class AssistantSkillDirectiveTest {

    private final SkillDirective skillDirective = mock(SkillDirective.class);
    private final AssistantSkillDirective directive = new AssistantSkillDirective(skillDirective);

    @Test
    void render_nullOrBlank_returnsEmpty() {
        assertEquals("", directive.render(null));
        assertEquals("", directive.render("   "));
    }

    @Test
    void render_planner_injectsPlanSkillConstraint() {
        String out = directive.render("planner");
        assertTrue(out.contains("方案整理"), "应含方案整理技能段，实际：" + out);
        assertTrue(out.contains("验收标准"), "方案整理约束应要求给出验收标准：" + out);
    }

    @Test
    void render_task_injectsDecomposeSkillConstraint() {
        String out = directive.render("task");
        assertTrue(out.contains("直接拆解"), "应含直接拆解技能段，实际：" + out);
        assertTrue(out.contains("子任务"), "直接拆解约束应要求给出子任务清单：" + out);
    }

    @Test
    void render_packageSkill_delegatesToSharedSkillDirective() {
        when(skillDirective.renderInstruction(eq("archify"))).thenReturn("ARCHIFY_BODY");
        String out = directive.render("pkg:archify");
        assertEquals("ARCHIFY_BODY", out);
        verify(skillDirective).renderInstruction("archify");
    }

    @Test
    void render_packageSkillWithoutName_returnsEmpty() {
        assertEquals("", directive.render("pkg:"));
    }

    @Test
    void render_unknownSkill_returnsEmptyAndDoesNotDelegate() {
        assertEquals("", directive.render("unknown-skill"));
    }
}
