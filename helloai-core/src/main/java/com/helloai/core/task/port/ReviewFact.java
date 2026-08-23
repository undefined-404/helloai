package com.helloai.core.task.port;

/**
 * 审查事实快照（§6.146 端口反转）：task 域隐式评分只需评分与结论两个
 * 标量，经 {@link ReviewPort} 由 review 域组装，不依赖 review 域实体。
 *
 * @param score   审查评分（无评分按 0 处理）
 * @param approved 是否 APPROVED（其余结论视为未通过，对应返工轮次统计）
 */
public record ReviewFact(int score, boolean approved) {
}
