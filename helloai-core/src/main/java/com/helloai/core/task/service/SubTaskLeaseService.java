package com.helloai.core.task.service;

import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.port.SubTaskView;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 子任务租约 / 回收 / 巡检契约（RM9-b 从 {@link SubTaskService} 实现类拆出的租约簇）：
 * 调度侧只读候选 + 续租 + 过期回收编排（状态写半段回收到状态机契约）。
 *
 * <p>实现见 {@code com.helloai.core.task.service.impl.SubTaskLeaseServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1} 组织。</p>
 */
public interface SubTaskLeaseService {

    List<SubTask> listReviewOrphans(int thresholdSeconds, int limit);

    List<SubTaskView> listReviewOrphanViews(int thresholdSeconds, int limit);

    List<SubTask> listPausedBefore(Long agentId, OffsetDateTime expiredBefore);

    List<SubTask> listTimedOutAssigned(OffsetDateTime deadline, int limit);

    int renewCurrentNodeLeases(OffsetDateTime newLeaseUntil, int limit);

    int reclaimExpiredLeases(int limit);

    int incrementExternalFallbackCount(Long subTaskId, OffsetDateTime now);

}
