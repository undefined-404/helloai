package com.helloai.core.review.support;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 核验 Prompt 渲染回归（G-010 收口）。
 *
 * <p>守两类漂移：</p>
 * <ol>
 *   <li><b>占位符 ↔ 实现零漂移</b>：模板里出现 {@code {{X}}} 而引擎未替换时，渲染结果会残留
 *       {@code {{…}}}——直接断言「无残留」比逐个 {@code contains} 更难被漏改绕过；</li>
 *   <li><b>技能要求下行</b>：{@code sub_task.required_skills} 必须进核验 Prompt（此前审查侧
 *       只拿到合并后的技能<b>能力</b>，看不到<b>要求</b>，无法核验交付形态是否缺失）。</li>
 * </ol>
 */
@DisplayName("核验 Prompt 渲染（G-010：技能要求下行 + 占位符零漂移）")
class ReviewPromptRenderTest {

    private ReviewExecutionEngine newEngine() {
        ReviewEvidenceAssembler assembler = mock(ReviewEvidenceAssembler.class);
        when(assembler.extractExecutionOutput(any())).thenReturn("（产出）");
        when(assembler.buildAttachmentList(any())).thenReturn("（清单）");
        when(assembler.buildAttachmentContent(any())).thenReturn("（正文）");
        when(assembler.extractRawOutput(any())).thenReturn("（原始输出）");
        when(assembler.verificationSignal(anyString())).thenReturn("（证据信号）");
        return new ReviewExecutionEngine(
                mock(PlatformAgentExecutionService.class),
                mock(ConversationService.class),
                mock(TaskTimelineService.class),
                mock(VerdictParser.class),
                assembler,
                mock(SubTaskService.class));
    }

    /** 25 位全量构造：只有 requiredSkills 是本次被测字段，其余取最小可用值。 */
    private SubTaskView view(List<String> requiredSkills, String constraints) {
        return new SubTaskView(
                1L, 2L, SubTaskStatus.REVIEW, 3L, 0,
                "标题", "执行内容", "交付物", "验收标准", null, constraints,
                Map.of(), List.of(),
                List.of(), null, "HIGH", 0,
                requiredSkills, null, null, null, null, 0,
                null, null);
    }

    @Test
    @DisplayName("技能要求非空 → 逐项「、」连接注入；空 → （无）")
    void rendersRequiredSkills() {
        ReviewExecutionEngine engine = newEngine();

        String withSkills = engine.renderPrompt(view(List.of("code-review", "mermaid-diagram"), null));
        assertThat(withSkills).contains("- 技能要求：code-review、mermaid-diagram");

        String withoutSkills = engine.renderPrompt(view(List.of(), null));
        assertThat(withoutSkills).contains("- 技能要求：（无）");
    }

    @Test
    @DisplayName("空白技能名被过滤，全空白等同（无）")
    void filtersBlankSkillNames() {
        ReviewExecutionEngine engine = newEngine();
        assertThat(engine.renderPrompt(view(List.of(" ", ""), null)))
                .contains("- 技能要求：（无）");
        assertThat(engine.renderPrompt(view(java.util.Arrays.asList("doc-writer", null), null)))
                .contains("- 技能要求：doc-writer");
    }

    @Test
    @DisplayName("模板占位符全部被替换：渲染结果不含残留 {{…}}")
    void leavesNoUnresolvedPlaceholder() {
        String prompt = newEngine().renderPrompt(view(List.of("code-review"), "不许改公共接口"));
        assertThat(prompt).doesNotContain("{{").doesNotContain("}}");
        // 顺带守住 G-011 D7 的两项仍然在渲染
        assertThat(prompt).contains("- 执行约束：不许改公共接口");
        assertThat(prompt).contains("- 不确定性申报：（无）");
    }
}
