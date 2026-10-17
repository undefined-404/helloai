package com.helloai.core.task.service;

import java.time.OffsetDateTime;

/**
 * 最终整合报告「写回 + 审查触发」的事务边界契约（§12.2 审查链改造）。
 *
 * <p>把「报告落库」与「审查触发」收敛到<b>同一事务</b>，消除 dual-write
 * （先写回 {@code REVIEWING}、再另发事件）在进程中断时产生「报告已落库、审查永不触发」的永久卡死。
 * 两个动作同事务提交：报告写回成功 ⟺ 审查请求落库成功。</p>
 *
 * <p><b>为什么必须是独立 Bean / 经接口调用</b>：调用方（{@code TaskFinalReportServiceImpl}）
 * 内含多次 LLM 调用，绝不能进事务；而在同一个类里给私有方法加 {@code @Transactional}
 * 会因<b>自调用不走代理</b>而静默失效。故本能力独立成一个 Bean，并由消费方<b>经接口注入</b>
 * （{@code HelloAI_CODE_STYLE.md §8.1}：跨类调用只依赖 {@code XxxService}）。</p>
 *
 * <p>实现见 {@code com.helloai.core.task.service.impl.FinalReportPersistServiceImpl}。</p>
 */
public interface FinalReportPersistService {

    /**
     * 同一事务内完成：报告写回（含 §12.1 prev 槽换入）+ L2 Outbox 落库 + L1 事件发布。
     *
     * @param taskId         任务 ID
     * @param report         报告正文
     * @param plannerAgentId 生成者（Planner）Agent ID
     * @param now            报告写回时间（微秒截断，作为陈旧守卫锚点）
     * @param attempt        生成轮次（首次=1，返工递增）
     * @param sectionCount   参与整合的子任务数（事件度量用）
     * @param reviewEnabled  自动审查开关：关时直接写 {@code DONE}，不产生审查触发
     * @return true=写回成功；false=CAS 未命中（状态已被其它链路接管，本次写回作废）
     */
    boolean persistAndRequestReview(Long taskId, String report, Long plannerAgentId,
                                    OffsetDateTime now, int attempt, int sectionCount,
                                    boolean reviewEnabled);
}
