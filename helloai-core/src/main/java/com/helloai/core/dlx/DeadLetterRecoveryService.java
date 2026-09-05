package com.helloai.core.dlx;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.time.OffsetDateTime;

/**
 * A4 S2：死信台账人工恢复服务（N-010「人工恢复」子项的第二入口）。
 *
 * <p>死信台账（{@code mq_dead_letter_archive}，V60 预留 {@code replayed_at}）是
 * DLX 消息的唯一可追溯数据源；本服务兑现 V60 预留语义：窗口分页查阅 + 按 id 单条重放。</p>
 *
 * <p>重放语义（fail-close）：台账不存在 / 已重放 / 无法识别消息标识均抛
 * {@link com.helloai.common.base.BizException} 禁止盲发；发送失败不写 {@code replayed_at}，
 * 可整体重试；发送成功才 CAS 写 {@code replayed_at}（并发双点重放只生效一次）。</p>
 */
public interface DeadLetterRecoveryService {

    /**
     * 死信台账窗口分页（人工恢复列表查询）。
     *
     * <p>按 {@code created_at} 窗口 + 可选「仅未重放」过滤，创建时间倒序。
     * 分页采用「count + limit/offset」手工拼装（台账无 MyBatis 实体，
     * 参照 {@code AgentDutyLeaseService.listLatestPerAgent} 手工拼 Page 先例）。</p>
     *
     * @param from            创建时间窗口下限（含）
     * @param to              创建时间窗口上限（含）
     * @param includeReplayed true 返回全部（含已重放）；false 仅未重放行
     * @param page            页码（从 1 起，小于 1 按 1 处理）
     * @param size            每页条数（小于 1 按 1 处理）
     */
    IPage<MqDeadLetterArchiveRow> listArchive(OffsetDateTime from, OffsetDateTime to,
                                              boolean includeReplayed, long page, long size);

    /**
     * 按 id 重放一条死信：去重重置 → 原 exchange/routingKey 重发 → CAS 写 {@code replayed_at}。
     *
     * <p>次序说明（先重置后重发，不做大事务）：重发不可回滚，先删去重台账可保证
     * 发送失败时整体重试无副作用（重置只是删失败记录，审计由死信台账保留）。</p>
     *
     * @param id 台账行主键
     */
    void replay(long id);
}