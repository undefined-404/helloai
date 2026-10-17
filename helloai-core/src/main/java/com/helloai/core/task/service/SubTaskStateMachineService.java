package com.helloai.core.task.service;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import java.util.Map;
import java.util.Set;

/**
 * 子任务<b>状态迁移唯一入口</b>契约（RM9-b 从 {@link SubTaskService} 实现类拆出的状态机簇）。
 *
 * <p>实现见 {@code com.helloai.core.task.service.impl.SubTaskStateMachineServiceImpl}。
 * 全部 {@code sub_task.status} 写入（含跨类调用与 SQL 级原子写）均须经本契约。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1} 组织。</p>
 */
public interface SubTaskStateMachineService {

    void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId);

    void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch);

    void claim(Long subTaskId, Long agentId);

    void start(Long subTaskId);

    void submit(Long subTaskId);

    void pause(Long subTaskId);

    void resume(Long subTaskId);

    void cancel(Long subTaskId);

    boolean claimAtomic(Long subTaskId, Long agentId);

    boolean reclaimToPending(SubTask subTask);

    void assignNext(Long agentId, Long subTaskId);

    SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses);

    void reassign(Long subTaskId, Long newAgentId);

    void block(Long subTaskId);

    void block(Long subTaskId, String reason, Long reporterAgentId);

    void complete(Long subTaskId);

    boolean rework(Long subTaskId, Long reworkAgentId);

    void reworkFresh(Long subTaskId, Long reworkAgentId);

    void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra);

    void clearManualIntervention(Long subTaskId);

}
