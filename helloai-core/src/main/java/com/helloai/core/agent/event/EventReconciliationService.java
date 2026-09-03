package com.helloai.core.agent.event;

/**
 * 事件流对账服务（Phase 0 B3，ADR-001 §5.3 端点事件约定）。
 *
 * <p>对账规则：同一 {@code subTaskId} 下，事件流的最后一条事件应匹配业务表
 * 当前状态（业务状态 → 事件投影的单向校验）。事件仅 write-only（B2 埋点纪律），
 * 对账<b>只报告不一致、不反向修正业务状态</b>。</p>
 *
 * <p>对账边界（不误报设计）：失败终态（agent_execution_record FAILED/TIMEOUT）
 * 事件层不定义失败终态事件（ADR §5.3）；PENDING/PAUSED/BLOCKED/CANCELLED/
 * DEAD_LETTER 等状态无事件语义（B2 校准）；二者均跳过校验。</p>
 *
 * <p>由 {@code EventReconciliationTask}（helloai-job，ShedLock 集群单例）周期调用，
 * 不一致时仅告警日志，供人工排查埋点缺口或业务迁移异常。</p>
 */
public interface EventReconciliationService {

    /**
     * 执行一轮对账：扫描最近变更的子任务，校验其事件流最后事件与业务状态是否匹配。
     *
     * @param limit 单轮候选子任务数量上限（窗口内变更量超限时本轮截断，下一轮继续）
     * @return 本轮发现的不一致数量（0 = 全部一致）
     */
    int reconcile(int limit);
}