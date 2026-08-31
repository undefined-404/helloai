package com.helloai.core.planner.clarify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemTimeContextBuilder 单元测试：输出节结构、实时日期一致性、相对时间映射完整。
 */
@DisplayName("SystemTimeContextBuilder")
class SystemTimeContextBuilderTest {

    private final SystemTimeContextBuilder builder = new SystemTimeContextBuilder();

    @Test
    @DisplayName("输出包含实时当前日期（与运行当天的系统时钟一致）")
    void build_containsTodayRealTime() {
        String ctx = builder.build();
        String today = LocalDate.now().toString();
        assertThat(ctx).contains("当前日期：" + today);
    }

    @Test
    @DisplayName("相对时间词映射齐全：今天/昨天/明天/本周一/上周一/上周五")
    void build_containsAllRelativeMappings() {
        String ctx = builder.build();
        assertThat(ctx).contains("今天").contains("今日")
                .contains("昨天").contains("昨日")
                .contains("明天").contains("明日")
                .contains("本周一").contains("上周一").contains("上周五")
                .contains("相对时间词映射");
    }

    @Test
    @DisplayName("强调历史日期不得当当前时间：含防锚定规则语句")
    void build_hintsHistoricalDateIsNotCurrentTime() {
        assertThat(builder.build())
                .contains("对话历史中出现的旧日期只是过去的引用，不得当作当前时间或时钟基准");
    }

    @Test
    @DisplayName("输出为节形态：可整体作为 Prompt 占位符替换值（含标题与换行结构）")
    void build_sectionShape_renderableAsPromptPlaceholder() {
        String ctx = builder.build();
        assertThat(ctx).startsWith("【系统当前时间】")
                .contains("时区：")
                .contains("当前时间：");
    }
}