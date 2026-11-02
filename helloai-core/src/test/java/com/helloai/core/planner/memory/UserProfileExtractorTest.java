package com.helloai.core.planner.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserProfileExtractor} 单元测试（PA-2b）：只抽显式偏好句式，未命中返回空。
 */
@DisplayName("UserProfileExtractor 画像规则抽取（PA-2b）")
class UserProfileExtractorTest {

    @Test
    @DisplayName("「记住我…」落入 note")
    void rememberFallsIntoNote() {
        Map<String, Object> facts = UserProfileExtractor.extract("记住我下周三之前要交方案");
        assertThat(facts).containsEntry(UserProfileExtractor.KEY_NOTE, "下周三之前要交方案");
    }

    @Test
    @DisplayName("「我偏好…」抽成偏好列表")
    void preferenceFallsIntoList() {
        Map<String, Object> facts = UserProfileExtractor.extract("我偏好简洁的回答");
        assertThat(facts.get(UserProfileExtractor.KEY_PREFERENCES))
                .isEqualTo(List.of("简洁的回答"));
    }

    @Test
    @DisplayName("「请用 X 风格」抽成沟通风格")
    void styleIsExtracted() {
        Map<String, Object> facts = UserProfileExtractor.extract("请用严谨的技术风格回复");
        assertThat(facts).containsEntry(UserProfileExtractor.KEY_STYLE, "严谨的技术");
    }

    @Test
    @DisplayName("无显式句式 / 空白输入返回空画像")
    void noExplicitPhraseReturnsEmpty() {
        assertThat(UserProfileExtractor.extract("今天天气不错，帮我写个周报")).isEmpty();
        assertThat(UserProfileExtractor.extract("   ")).isEmpty();
        assertThat(UserProfileExtractor.extract(null)).isEmpty();
    }
}
