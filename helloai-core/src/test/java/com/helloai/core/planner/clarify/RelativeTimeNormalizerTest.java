package com.helloai.core.planner.clarify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelativeTimeNormalizer 单元测试：
 * 单点词（今天/昨天/明天/前天/后天）、周内点（本周X/上周X）、周/天区间、
 * 无时间词原样返回、null/空白安全。固定锚点日期 2026-08-31（周一）。
 */
@DisplayName("RelativeTimeNormalizer")
class RelativeTimeNormalizerTest {

    /** 固定锚点（周一），保证星期计算断言稳定。 */
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 31);

    private final RelativeTimeNormalizer normalizer = new RelativeTimeNormalizer();

    @Test
    @DisplayName("单点词：今天/今日/昨天/明天/前天/后天均转绝对日期")
    void normalize_pointWords_replacedWithAbsoluteDates() {
        assertThat(normalizer.normalize("今天的上证指数走势", ANCHOR))
                .isEqualTo("2026-08-31的上证指数走势");
        assertThat(normalizer.normalize("今日新闻", ANCHOR))
                .isEqualTo("2026-08-31新闻");
        assertThat(normalizer.normalize("昨日的行情", ANCHOR))
                .isEqualTo("2026-08-30的行情");
        assertThat(normalizer.normalize("明天天气", ANCHOR))
                .isEqualTo("2026-09-01天气");
        assertThat(normalizer.normalize("前天发生了什么", ANCHOR))
                .isEqualTo("2026-08-29发生了什么");
        assertThat(normalizer.normalize("后天有活动", ANCHOR))
                .isEqualTo("2026-09-02有活动");
    }

    @Test
    @DisplayName("周内点：上周五按上一完整周（周一起点）计算为本周一减3天")
    void normalize_lastWeekWeekday_anchoredToThisMondayMinusThree() {
        // 2026-08-31 是周一，上一完整周的周五 = 本周一(08-31) - 3 天 = 2026-08-28
        assertThat(normalizer.normalize("结合上周五的数据", ANCHOR))
                .isEqualTo("结合2026-08-28的数据");
        assertThat(normalizer.normalize("上周一开盘", ANCHOR))
                .isEqualTo("2026-08-24开盘");
        assertThat(normalizer.normalize("上周日收盘", ANCHOR))
                .isEqualTo("2026-08-30收盘");
    }

    @Test
    @DisplayName("周内点：本周X按本周一起点顺推（可能为未来日期）")
    void normalize_thisWeekWeekday_offsetFromThisMonday() {
        assertThat(normalizer.normalize("本周一", ANCHOR))
                .isEqualTo("2026-08-31");
        assertThat(normalizer.normalize("本周五", ANCHOR))
                .isEqualTo("2026-09-04");
    }

    @Test
    @DisplayName("周区间：上周=上周一至上周日，本周=本周一至今天")
    void normalize_weekRange_boundedByISOWeek() {
        assertThat(normalizer.normalize("上周的行情", ANCHOR))
                .isEqualTo("2026-08-24 2026-08-30的行情");
        assertThat(normalizer.normalize("本周走势", ANCHOR))
                .isEqualTo("2026-08-31 2026-08-31走势");
    }

    @Test
    @DisplayName("天区间：最近一周/近一周/最近7天/近7天=6天前至今天，最近N天含首末两天")
    void normalize_recentDays_rangeFromStartToToday() {
        assertThat(normalizer.normalize("最近一周AI新闻", ANCHOR))
                .isEqualTo("2026-08-25 2026-08-31AI新闻");
        assertThat(normalizer.normalize("近一周的财经数据", ANCHOR))
                .isEqualTo("2026-08-25 2026-08-31的财经数据");
        assertThat(normalizer.normalize("最近7天天气", ANCHOR))
                .isEqualTo("2026-08-25 2026-08-31天气");
        assertThat(normalizer.normalize("近3天涨幅", ANCHOR))
                .isEqualTo("2026-08-29 2026-08-31涨幅");
    }

    @Test
    @DisplayName("无时间词文本：原样返回")
    void normalize_withoutTimeWords_unchanged() {
        assertThat(normalizer.normalize("Python 教程", ANCHOR))
                .isEqualTo("Python 教程");
        assertThat(normalizer.normalize("Java 并发面试题", ANCHOR))
                .isEqualTo("Java 并发面试题");
    }

    @Test
    @DisplayName("null/空白输入：原样返回不抛异常")
    void normalize_nullOrBlank_returnedAsIs() {
        assertThat(normalizer.normalize(null, ANCHOR)).isNull();
        assertThat(normalizer.normalize("   ", ANCHOR)).isEqualTo("   ");
    }

    @Test
    @DisplayName("组合场景：用户真实案例（今天+上周五）一并转绝对日期")
    void normalize_combinationCase_allRelativeWordsReplaced() {
        assertThat(normalizer.normalize("我想知道今天的上证指数走势情况，结合上周五的数据", ANCHOR))
                .isEqualTo("我想知道2026-08-31的上证指数走势情况，结合2026-08-28的数据");
    }

    @Test
    @DisplayName("幂等性：二次归一化不改变已含绝对日期的结果")
    void normalize_alreadyNormalized_unchanged() {
        String once = normalizer.normalize("今天上证指数 上周五数据", ANCHOR);
        assertThat(normalizer.normalize(once, ANCHOR)).isEqualTo(once);
    }
}