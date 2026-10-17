package com.helloai.core.task.service;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import java.util.List;

/**
 * 子任务出站副作用契约（RM9-b 从 {@link SubTaskService} 实现类拆出的通知簇）：
 * inbox 投递 + 摘要渲染 + 领域事件，零状态机依赖。
 *
 * <p>实现见 {@code com.helloai.core.task.service.impl.SubTaskNotificationServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1} 组织。</p>
 */
public interface SubTaskNotificationService {

    void sendInboxNotification(SubTask subTask, SubTaskStatus newStatus, SubTaskStatus oldStatus);

    void notifyAgentHandover(SubTask subTask, Long oldAgentId);

    void publishAssignmentEvent(SubTask subTask, SubTaskStatus newStatus);

    void sendReworkInboxNotification(SubTask subTask);

    String buildReworkSummary(SubTask subTask);

    void sendApprovedInboxNotification(SubTask subTask);

    String buildApprovedSummary(SubTask subTask);

    String buildUncertaintyLines(List<Uncertainty> uncertainties);

}
