package com.helloai.core.agent.quality.dto;

import lombok.Data;

/**
 * 画像重算数据源行（rebuild 全量重算的单条评审记录投影）。
 *
 * <p>必须用具体 DTO 而非 {@code List<Map<String, Object>>} 返回：MyBatisPlusConfig
 * 为兼容 JSONB 列把 {@code Map.class} 全局注册为 JacksonTypeHandler，若查询返回类型
 * 推断为 Map，MyBatis 会把整行（首列）当作 JSON 反序列化，裸数字列会抛
 * MismatchedInputException 导致 500（真实环境已踩坑，迭代记录 §6.132）。</p>
 */
@Data
public class RebuildSourceRow {

    /** review_record.id（Snowflake）。 */
    private Long recordId;

    /** 评审结论：APPROVED / REJECTED。 */
    private String result;

    /** 评审评分。 */
    private Integer score;

    /** 评审轮次（1=首轮）。 */
    private Integer round;

    /** issues 原文（可 null），含 [defect] 四元组标签。 */
    private String issues;
}