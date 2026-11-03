package com.helloai.core.planner.assistant;

import com.helloai.core.planner.assistant.service.AssistantProfileService.AssistantSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 助理 system prompt 三段注入（联网资料 / 会话附件 / 本轮技能）的纯函数测试。
 */
class AssistantPromptBuilderTest {

    private static final AssistantSnapshot PROFILE = new AssistantSnapshot(
            "AI 助手", "你的个人助理", "deepseek-v3", List.of(), "你好");

    private static String build(String webSearchContext, String attachmentContext, String skillInstruction) {
        return AssistantPromptBuilder.build(PROFILE, List.of(), List.of(), List.of(),
                null, List.of(), webSearchContext, attachmentContext, skillInstruction);
    }

    @Test
    @DisplayName("联网资料非空时注入【联网检索到的资料】段")
    void webSearchSectionInjected() {
        String prompt = build("来源: https://example.com\n摘要: 示例内容", null, null);
        assertTrue(prompt.contains("【联网检索到的资料】"));
        assertTrue(prompt.contains("示例内容"));
    }

    @Test
    @DisplayName("会话附件非空时注入【本会话附件】段")
    void attachmentSectionInjected() {
        String prompt = build(null, "附件: report.pdf\n正文片段", null);
        assertTrue(prompt.contains("【本会话附件】"));
        assertTrue(prompt.contains("report.pdf"));
    }

    @Test
    @DisplayName("技能约束非空时原样注入")
    void skillSectionInjected() {
        String prompt = build(null, null, "【本轮技能】方案整理：请给出目标/步骤/验收/风险");
        assertTrue(prompt.contains("方案整理"));
    }

    @Test
    @DisplayName("三段全空时不产生空标题")
    void emptySectionsProduceNoHeaders() {
        String prompt = build(null, null, null);
        assertFalse(prompt.contains("【联网检索到的资料】"));
        assertFalse(prompt.contains("【本会话附件】"));
    }

    @Test
    @DisplayName("空白串视同未开启，不产生空标题")
    void blankSectionsProduceNoHeaders() {
        String prompt = build("   ", "\n\t ", "  ");
        assertFalse(prompt.contains("【联网检索到的资料】"));
        assertFalse(prompt.contains("【本会话附件】"));
    }

    @Test
    @DisplayName("旧 6 参重载等价于三段全空")
    void legacyOverloadOmitsNewSections() {
        String legacy = AssistantPromptBuilder.build(PROFILE, List.of(), List.of(), List.of(), null, List.of());
        assertFalse(legacy.contains("【联网检索到的资料】"));
        assertFalse(legacy.contains("【本会话附件】"));
        assertTrue(legacy.contains("你是「AI 助手」"));
    }
}
