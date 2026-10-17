package com.helloai.core.planner.clarify;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

    /** 绝对日期锚点反推（W-3）：文本含 yyyy-MM-dd 时按距今天数收紧 freshness。
     *  与 {@link #normalize} 输出的绝对日期同格式（同一事实源）。
     *  <b>注意</b>：不能使用 {@code \b} 边界——Java 正则 {@code \b} 按 {@code [a-zA-Z0-9_]} 定义，
     *  CJK 字符不属于该字符类，数字与中文之间的 {@code \b} 不成立（实测
     *  {@code \b2026-08-31\b} 在 {@code "2026-08-31的上证指数"} 中不匹配）；
     *  改用「前后非数字」环视，纯日期前后是中文/空格/首尾时均可命中。 */
    private static final Pattern ABSOLUTE_DATE_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");

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

    /**
     * 判定查询词对应的博查 freshness 档位（W-3：动态时效，替代硬编码 {@code noLimit}）。
     *
     * <p>映射规则（博查支持的取值：noLimit / oneDay / oneWeek / oneMonth / oneYear）：</p>
     * <ul>
     *   <li>含「今天/今日/昨天/昨日/实时/最新消息」→ {@code oneDay}；</li>
     *   <li>含「本周/上周/最近一周/近一周/最近7天/近7天/最近N天(N≤7)」→ {@code oneWeek}；</li>
     *   <li>含「本月/上月/最近30天」→ {@code oneMonth}；</li>
     *   <li>含「今年/去年/最新」→ {@code oneYear}；</li>
     *   <li>含已归一化的绝对日期（yyyy-MM-dd）→ 按距 today 天数收紧（&le;1 天 oneDay、
     *       &le;7 天 oneWeek、&le;30 天 oneMonth、&le;365 天 oneYear）；</li>
     *   <li>以上均未命中 → {@code noLimit}。</li>
     * </ul>
     *
     * <p><b>单一事实源</b>：时效词表与 {@link #normalize} 同源（今天/昨天/本周/上周/最近N天等
     * 复用同一 Pattern），避免「搜索词内日期替换」与「freshness 判定」两套词表漂移。
     * 判定是纯规则、幂等、无副作用，null/空白输入返回 {@code noLimit}。</p>
     *
     * @param text  查询词（可为 null）
     * @param today 当前日期（权威时钟，由调用方注入以便测试）
     * @return 博查 freshness 取值（{@code noLimit} 为兜底）
     */
    public String freshnessFor(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return "noLimit";
        }
        // 档位一：单点/实时（时效性最强）
        if (text.contains("今天") || text.contains("今日")
                || text.contains("昨天") || text.contains("昨日")
                || text.contains("实时") || text.contains("最新消息")) {
            return "oneDay";
        }
        // 档位二：周区间（本周/上周/最近一周/近一周/最近7天/近7天）
        if (text.contains("本周") || text.contains("上周")
                || text.contains("最近一周") || text.contains("近一周")
                || text.contains("最近7天") || text.contains("近7天")) {
            return "oneWeek";
        }
        // 档位三：月区间
        if (text.contains("本月") || text.contains("上月") || text.contains("最近30天")) {
            return "oneMonth";
        }
        // 档位四：年区间（"最新"宽泛时效，兜底年档）
        if (text.contains("今年") || text.contains("去年") || text.contains("最新")) {
            return "oneYear";
        }
        // 档位五：已归一化的绝对日期反推（normalize 输出的 yyyy-MM-dd）
        Matcher m = ABSOLUTE_DATE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                LocalDate d = LocalDate.of(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                long days = Math.abs(ChronoUnit.DAYS.between(d, today));
                if (days <= 1) return "oneDay";
                if (days <= 7) return "oneWeek";
                if (days <= 30) return "oneMonth";
                if (days <= 365) return "oneYear";
            } catch (Exception ignore) {
                // 非法日期（如 2026-02-31）：忽略，走 noLimit 兜底
            }
        }
        return "noLimit";
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