package com.helloai.core.planner.clarify;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 系统当前时间上下文构建器（LLM 时间感知缺失的第一层防线）。
 *
 * <p>背景：LLM 没有系统时钟，跨天对话时会把"对话开始日期"当作"当前日期"锚点，
 * 导致"今天"/"上周五"等相对时间词查询到错误日期。业界标配是<b>每轮 LLM 调用前</b>
 * 强制注入当前系统时间与相对时间映射（Open WebUI CURRENT_DATE / Hermes build_temporal_context）。</p>
 *
 * <p>本组件为无状态纯函数：由调用方在渲染 Prompt 时逐轮调用 {@link #build()}，
 * 产物作为 {@code {{SYSTEM_TIME_CONTEXT}}} 占位符的替换值注入各模板。</p>
 */
@Component
public class SystemTimeContextBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 构建系统当前时间上下文文本（含相对时间词→绝对日期映射）。
     *
     * @return 可直接注入 Prompt 的文本节；基于服务器实时时钟，不依赖任何历史上下文
     */
    public String build() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        ZoneId zone = ZoneId.systemDefault();
        // ISO 周：周一=本周起点（getValue() 1=Mon..7=Sun，减 (value-1) 即回到本周一）
        LocalDate thisMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        String weekDay = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);

        StringBuilder sb = new StringBuilder();
        sb.append("【系统当前时间】（服务器实时时间，时间语义的唯一权威基准）\n");
        sb.append("当前日期：").append(DATE_FMT.format(today)).append("（").append(weekDay).append("）\n");
        sb.append("当前时间：").append(TIME_FMT.format(now)).append('\n');
        sb.append("时区：").append(zone.getId()).append('\n');
        sb.append("相对时间词映射（必须严格遵守，不得自行推算）：\n");
        sb.append("- \"今天\" / \"今日\" = ").append(DATE_FMT.format(today)).append('\n');
        sb.append("- \"昨天\" / \"昨日\" = ").append(DATE_FMT.format(today.minusDays(1))).append('\n');
        sb.append("- \"明天\" / \"明日\" = ").append(DATE_FMT.format(today.plusDays(1))).append('\n');
        sb.append("- \"本周一\" = ").append(DATE_FMT.format(thisMonday))
          .append("，\"上周一\" = ").append(DATE_FMT.format(thisMonday.minusDays(7)))
          .append("，\"上周五\" = ").append(DATE_FMT.format(thisMonday.minusDays(3))).append('\n');
        sb.append("重要规则：用户提到\"今天\"\"现在\"\"最近\"等相对时间时，一律以上述映射为准；"
                + "对话历史中出现的旧日期只是过去的引用，不得当作当前时间或时钟基准。");
        return sb.toString();
    }
}