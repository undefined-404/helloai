package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskClaimConstraint;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.port.SubTaskView;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 子任务只读查询与视图投影契约（RM9-b 从 {@link SubTaskService} 实现类拆出的只读簇）。
 *
 * <p>实现见 {@code com.helloai.core.task.service.impl.SubTaskQueryServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1} 组织。</p>
 */
public interface SubTaskQueryService {

    List<String> requiredSkillsOf(Long taskId);

    List<String> mergeSkills(SubTask subTask);

    List<String> mergeSkills(Long subTaskId);

    SubTaskClaimConstraint claimConstraint(Long subTaskId);

    IPage<SubTask> list(Long taskId, SubTaskStatus status, Long assignedAgentId, Integer page, int pageSize);

    List<SubTask> listAvailable();

    List<SubTask> listMine(Long assignedAgentId);

    List<SubTask> listRecentlyChanged(OffsetDateTime since, int limit);

    SubTask getByIdForUpdate(Long subTaskId);

    boolean isReady(SubTask subTask);

    Map<String, Integer> countByStatusForAgent(Long agentId);

    long countByAssignedAgent(Long agentId);

    long countReviewByReviewerAgent(Long agentId);

    long countByTaskIdExcludingStatus(Long taskId, SubTaskStatus excluded);

    long countByTaskIdAndStatus(Long taskId, SubTaskStatus status);

    List<SubTask> selectInFlightByAgent(Long agentId, int limit);

    int countInFlightByAgent(Long agentId);

    int countInFlightAll();

    boolean existsInFlightAssignedOrInProgress(Long agentId);

    List<SubTask> listInFlightAssignedOrInProgress(Long agentId);

    List<Long> listPendingUnassignedWithoutActiveExecutionRecord(int limit);

    List<Long> listStalePendingWithoutExecutionRecord(OffsetDateTime cutoff, int limit);

    List<Long> listStaleBlockedSubTaskIds(OffsetDateTime cutoff, int limit);

    SubTaskView getView(Long subTaskId);

    List<SubTaskView> listViewsByTaskId(Long taskId);

    List<SubTaskView> listViewsByTaskIdAndStatus(Long taskId, SubTaskStatus status);

    List<SubTaskView> listDraftsInInsertOrder(Long taskId, SubTaskStatus status);

    List<SubTaskView> listViewsByIds(Collection<Long> ids);

}
