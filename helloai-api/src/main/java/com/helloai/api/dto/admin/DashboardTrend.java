package com.helloai.api.dto.admin;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.helloai.api.support.RawLongSerializer;
import lombok.Data;

import java.util.List;

/**
 * 仪表盘趋势（近 N 天的「新建 / 完成 / 评审」三条每日计数序列）。
 *
 * <p><b>为什么按元素显式声明</b>：这三条是<b>计数</b>而非 ID，必须下发 JSON 数字；但其元素类型
 * 是 {@code List<Long>}——容器属性，而 {@code JacksonConfig} 的例外层只按「属性类型为 {@code Long}」
 * 判定，<b>看不见容器内元素</b>，故须用 {@code contentUsing} 按元素单独声明。
 * 若不加注解，元素会走默认层被写成字符串（与前端 {@code number} 类型不符）。</p>
 */
@Data
public class DashboardTrend {

    private List<String> dates;

    /** 每日新建任务数（计数，非 ID）。 */
    @JsonSerialize(contentUsing = RawLongSerializer.class)
    private List<Long> createdCounts;

    /** 每日完成任务数（计数，非 ID）。 */
    @JsonSerialize(contentUsing = RawLongSerializer.class)
    private List<Long> completedCounts;

    /** 每日评审数（计数，非 ID）。 */
    @JsonSerialize(contentUsing = RawLongSerializer.class)
    private List<Long> reviewedCounts;
}
