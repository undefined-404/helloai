package com.helloai.core.planner.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoundProgressText} 进度文案（`ATT-8`）。
 *
 * <p>进度帧必须展示<b>真实检索词</b>：改造前贴的是用户原话，既不是检索词也不像进度。</p>
 */
@DisplayName("RoundProgressText 检索进度文案")
class RoundProgressTextTest {

    @Test
    @DisplayName("候选词：逐词拼接进进度文案")
    void searching_showsRealQueries() {
        assertThat(RoundProgressText.searching(List.of("接口 规格", "taskId Long")))
                .isEqualTo("正在联网检索：接口 规格 / taskId Long");
    }

    @Test
    @DisplayName("无候选词：只提示正在检索，不拿用户原话充数")
    void searching_withoutQueriesKeepsGenericText() {
        assertThat(RoundProgressText.searching(null)).isEqualTo("正在联网检索");
        assertThat(RoundProgressText.searching(List.of())).isEqualTo("正在联网检索");
    }

    @Test
    @DisplayName("超长候选词按 60 字截断（防止整段消息进入过程区）")
    void searching_truncatesLongQueries() {
        String out = RoundProgressText.searching(List.of("长".repeat(100)));

        assertThat(out).startsWith("正在联网检索：");
        assertThat(out).endsWith("…");
        assertThat(out.length()).isLessThanOrEqualTo("正在联网检索：".length() + 61);
    }
}
