package com.helloai.core.planner.clarify;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 相对时间词 → 绝对日期规则归一化器（LLM 时间感知缺失的第二层防线，搜索词兜底）。
 *
 * <p>背景：搜索词无论来自 LLM 优化词（联合决策 / 查询改写）还是规则清洗截断，
 * 都可能残留"今天""上周五"等相对时间词——搜索引擎不理解相对时间语义，必须
 * 在发起检索前转换为绝对日期。本组件是纯规则兜底：即使 LLM 层注入时间后仍偷懒
 * 输出相对词，规则层也会修正；时间词缺失则原样返回。</p>
 *
 * <p>支持的表达（替换为 yyyy-MM-dd 绝对日期）：</p>
 * <ul>
 *   <li>单点：今天/今日、昨天/昨日、明天/明日、前天/前日、后天/后日</li>
 *   <li>周内点：本周X（周一~周日）、上周X（周一~周日）</li>
 *   <li>周/月区间：本周、上周（本周一起点语义）、最近一周/近一周/最近7天/近7天</li>
 *   <li>天数区间：最近N天/近N天（含首末两天）</li>
 * </ul>
 *
 * <p>替换顺序自长到短（先"最近一周"后"最近N天"、先"上周五"后"上周"），
 * 避免子串覆盖造成二次替换破坏。</p>
 */
@Component
public class RelativeTimeNormalizer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 周内点：上周X / 本周X（X=一二三四五六日）。 */
    private static final Pattern WEEKDAY_POINT_PATTERN =
            Pattern.compile("([上本]周)([一二三四五六日])");

    /** 天数区间：最近N天 / 近N天（N 为 1~2 位数字）。 */
    private static final Pattern RECENT_DAYS_PATTERN = Pattern.compile("(最近|近)(\\d{1,2})天");

    /** 周区间：最近一周 / 近一周 / 最近7天 / 近7天（先于 RECENT_DAYS_PATTERN 命中的全称写法）。 */
    private static final Pattern RECENT_WEEK_PATTERN =
            Pattern.compile("最近一周|近一周|最近7天|近7天");

    /** 无星期的周区间：上周 / 本周（周一起点，周末取今天/上周日）。 */
    private static final Pattern WEEK_RANGE_PATTERN = Pattern.compile("上周|本周");

    /** 单点时间词映射（顺序无关，词间无相互包含）。 */
    private static final Map<String, Integer> POINT_WORD_OFFSETS = Map.of(
            "今天", 0, "今日", 0,
            "昨天", -1, "昨日", -1,
            "明天", 1, "明日", 1,
            "前天", -2, "前日", -2,
            "后天", 2, "后日", 2);

    /** 中文星期序号：一=1 … 日=7（ISO 对齐，与 LocalDate.getDayOfWeek().getValue() 一致）。 */
    private static final Map<Character, Integer> WEEKDAY_INDEX = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5, '六', 6, '日', 7);

    /**
     * 将文本中的相对时间词替换为绝对日期。
     *
     * @param text  原始文本（用户消息 / 候选搜索词），可为 null
     * @param today 当前日期（权威时钟，由调用方注入以便测试）
     * @return 替换后的文本；全部时间词均已转绝对日期，未命中任何时间词时原样返回
     */
    public String normalize(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = text;
        // 顺序自长到短，避免子串覆盖（如"最近7天"须先于"最近N天"、先于"7天"类规则）
        s = replaceAll(s, RECENT_WEEK_PATTERN, m -> {
            LocalDate start = today.minusDays(6);
            return range(start, today);
        });
        s = replaceAll(s, RECENT_DAYS_PATTERN, m -> {
            int days = Integer.parseInt(m.group(2));
            int span = Math.max(1, days - 1);
            return range(today.minusDays(span), today);
        });
        s = replaceAll(s, WEEKDAY_POINT_PATTERN, m -> {
            String prefix = m.group(1);
            Integer idx = WEEKDAY_INDEX.get(m.group(2).charAt(0));
            if (idx == null) {
                return m.group();
            }
            LocalDate thisMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            // 上周X = 本周X - 7 天（本周一为 ISO 周起点）
            LocalDate date = thisMonday.plusDays(idx - 1L).minusDays("上周".equals(prefix) ? 7 : 0);
            return DATE_FMT.format(date);
        });
        s = replaceAll(s, WEEK_RANGE_PATTERN, m -> {
            boolean lastWeek = "上周".equals(m.group());
            LocalDate thisMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            if (lastWeek) {
                LocalDate start = thisMonday.minusDays(7);
                return start + " " + DATE_FMT.format(thisMonday.minusDays(1));
            }
            return range(thisMonday, today);
        });
        for (Map.Entry<String, Integer> e : POINT_WORD_OFFSETS.entrySet()) {
            s = s.replace(e.getKey(), DATE_FMT.format(today.plusDays(e.getValue())));
        }
        return s;
    }

    /** 区间文本（空格分隔，符合搜索关键词习惯）：start end。 */
    private static String range(LocalDate start, LocalDate end) {
        return DATE_FMT.format(start) + " " + DATE_FMT.format(end);
    }

    /** 按正则整体替换（替换函数接收整段匹配）。 */
    private static String replaceAll(String text, Pattern pattern, java.util.function.Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}