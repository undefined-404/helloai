package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import com.helloai.core.agent.port.SubTaskSnapshot;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 事务性 Outbox 事件服务（agent_outbox_event 表）。
 */
public interface AgentOutboxService extends IService<AgentOutboxEvent> {

    /**
     * 创建 Outbox 事件（同业务事务写入，MQ 侧经 AgentEventCompensationTask 投递）。
     *
     * <p>入参为 {@link SubTaskSnapshot} 快照而非 task 域实体：本接口属 agent 域，
     * 若形参用 {@code task.entity.SubTask} 会引入 agent → task 反向依赖。</p>
     */
    AgentOutboxEvent createEvent(SubTaskSnapshot subTask, SubTaskStatus newStatus);

    /**
     * 创建「最终报告审查请求」Outbox 事件（§12.2 审查链三级容错 L2）。
     *
     * <p><b>动机</b>：报告审查此前只有 L1（内存事件 + 本地线程池），进程重启即丢。
     * 本方法把审查触发持久化为 Outbox 行，由 {@code AgentEventCompensationTask}
     * 投递到报告审查专用队列，实现"重启不丢 + 幂等重投"。</p>
     *
     * <p><b>必须与报告写回同事务调用</b>（由 {@code FinalReportPersistService} 保证）：
     * 报告落库与审查触发要么同时成功、要么同时回滚，杜绝 dual-write 丢失。</p>
     *
     * <p>路由键 {@code agent.report-review.assigned} 独立于 {@code agent.reviewer.*}——
     * 后者被子任务核验消费者独占（要求 payload 含 subTaskId）。</p>
     *
     * @param taskId       任务 ID
     * @param reportTime   报告写回时间（陈旧守卫锚点，序列化为 ISO-8601 字符串下发）
     * @param attempt      生成轮次（首次=1，返工递增）
     * @param reportLength 报告正文字符数
     * @return 已落库的 Outbox 事件
     */
    AgentOutboxEvent createReportReviewEvent(Long taskId, OffsetDateTime reportTime,
                                             int attempt, int reportLength);

    /**
     * 轮询待投递事件（retryCount < 5，且到 nextRetryTime）。
     */
    List<AgentOutboxEvent> pollPending(int limit);

    /**
     * 标记投递成功（status=SUCCESS，retry_count+1）。
     */
    void markSuccess(Long id);

    /**
     * 标记投递失败（retry_count+1，nextRetryTime+10s）。
     */
    void markFailed(Long id, String error);
}
