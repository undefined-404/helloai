package com.helloai.core.task.port;

import com.helloai.common.constant.ReviewResult;

/**
 * 最新一轮审查摘要（§6.146 端口反转）：task 域拼装审查结论/收件箱通知
 * 摘要只需结论、评分、评语三个标量，经 {@link ReviewPort} 由 review 域
 * 组装，不依赖 review 域实体。
 *
 * @param result  审查结论（无记录时为 null）
 * @param score   审查评分（可为 null）
 * @param comment 审查评语（可为 null）
 */
public record ReviewSummary(ReviewResult result, Integer score, String comment) {
}
