package com.helloai.core.planner.service;

import com.helloai.core.task.port.SubTaskDraft;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 草案暂存区轮次生命周期（`AD-5b` 起不再写执行表副本）。
 *
 * <p>`task_plan_draft` 是草案的**唯一事实源**：本服务只负责首拆写入、整轮作废、整轮确认与
 * 按批次作废；执行行由 `SubTaskService#materializeFromStaging` 在确认时物化。</p>
 *
 * <p><b>幂等</b>：由 {@code uk_plan_draft_batch(batch_key, code)} 兜底；同一 {@code batchKey}
 * 只由同一次拆解事件串行调用。</p>
 */
public interface PlanDraftRoundService {

    /**
     * 写入本次拆解产出的草案（`AD-5a-1` / `AD-5a-3b-2b`）：唯一落点是 `task_plan_draft`。
     *
     * <p><b>幂等</b>：同一 {@code batchKey} 已有行时直接返回 0，调用方随后从暂存区重读该批
     * 继续跑依赖回写与门禁（重投不会重复插）。</p>
     *
     * <p><b>轮次</b>：`round` 取「含软删的最大轮次 + 1」，拒绝/确认后的重拆不会复用旧轮号。</p>
     *
     * <p><b>必须在调用方事务内执行</b>（{@code Propagation.MANDATORY}）：暂存区行若独立提交，
     * 主事务回滚后会留下孤儿。</p>
     *
     * @param taskId         任务
     * @param batchKey       本次拆解事件的幂等键（= {@code task.decompose_attempt_id}）
     * @param taskCreateTime 任务创建时刻，用于 `code` 缺失时的日期前缀回落
     * @param drafts         拆解物料（含 planner 已算好的 {@code draftCode} / {@code draftSeq}）
     * @return 实际插入行数（0 = 空批或该事件已写过）
     */
    int writeDrafts(Long taskId, String batchKey, OffsetDateTime taskCreateTime,
                    List<SubTaskDraft> drafts);

    /**
     * 作废该任务当前所有 {@code ACTIVE} 位置（拒绝草案 / 整轮废除时调用）。
     *
     * <p>处置为 {@code node_state = 'DISCARDED'} + {@code deleted = 1}：前者保留「整轮被作废」
     * 的语义，后者让出部分唯一索引的位置；两者缺一不可，否则下一轮拆解会撞 `uk_plan_draft_code`。</p>
     *
     * @return 被作废的行数
     */
    int discardRound(Long taskId);

    /**
     * 确认草案：把该任务当前所有 {@code ACTIVE} 位置标 {@code CONFIRMED} + 软删。
     *
     * <p>读侧只取 {@code ACTIVE} 行；若确认后仍留在暂存区，草案页会把已转正的草案继续当待确认
     * 展示。`CONFIRMED` 记录「已转正」语义，`deleted=1` 让出唯一索引位置，避免下一轮拆解撞号。</p>
     *
     * <p><b>为什么 REQUIRES_NEW</b>：调用方 {@code confirmPlan} 刻意不开类级事务（下游
     * {@code planGateRunner.clear} 是 {@code REQUIRES_NEW}，外层套事务会连接级死锁，见
     * 2026-11-08 真机教训）；本方法只动 {@code task_plan_draft} 一张表，独立提交即可。</p>
     *
     * @return 被标为 CONFIRMED 的行数（0 = 没有 ACTIVE 行，属正常；幂等重试安全）
     */
    int confirmRound(Long taskId);

    /**
     * 作废**某一次拆解事件**写入的行（`TD-16e-2` 残缺批次清理）。
     *
     * <p>与 {@link #discardRound} 的分工：那个按任务清「当前所有 ACTIVE」，用于拒绝草案 /
     * 整轮废除；本方法按 {@code batchKey} 只清这一次事件的行。撞键恢复判定本批不完整时必须用
     * 后者——任务内可能还留着别的批次的行，按任务清会误伤。</p>
     *
     * @param batchKey 本次拆解事件的幂等键（= {@code task.decompose_attempt_id}）
     * @return 被作废的行数（0 = 本批没写过暂存区，属正常）
     */
    int discardBatch(String batchKey);
}
