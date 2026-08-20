package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.TaskIteration;

import java.util.List;

/**
 * 任务迭代记录服务。
 *
 * <p>在 Planner 整合报告生成成功后一次性回填，不参与运行时执行/审核链路。
 * 回填幂等：先按 task_id 删旧、再批量插新。</p>
 */
public interface TaskIterationService extends IService<TaskIteration> {

    /**
     * 回填指定任务的全部迭代记录（幂等）。
     *
     * <p>由 {@code TaskFinalReportService.generate()} 在报告生成成功后调用。
     * 失败仅记 log，不向上抛异常阻断报告生成主流程。</p>
     *
     * @param taskId       顶层任务 ID
     * @param sections     已排序的 DONE 子任务列表（DAG 拓扑序）
     * @param plannerAgent Planner Agent（用于日志关联，不写入记录）
     */
    void backfillForTask(Long taskId, List<SubTask> sections, Agent plannerAgent);

    /**
     * 回填所有历史 DONE 任务中尚未写入 task_iteration 的记录。
     *
     * <p>扫描 sub_task 中状态为 DONE 的任务，跳过已在 task_iteration 中存在的任务 ID。
     * 对每个待回填任务收集其 DONE 子任务产出后调用 {@link #backfillForTask}。</p>
     *
     * <p>不使用 {@code @Transactional}：历史回填涉及多任务遍历，若单任务失败导致
     * PostgreSQL 事务 abort，后续任务所有 SQL 都会被拒绝（"current transaction is
     * aborted"）。拆为逐任务 auto-commit，失败任务不影响其他任务。</p>
     *
     * @return 成功回填的任务数
     */
    int backfillHistory();

    /**
     * 查询指定任务的迭代记录（按 task_code 数字顺序排序）。
     *
     * @param taskId 顶层任务 ID
     * @return 迭代记录列表（可能为空）
     */
    List<TaskIteration> listByTaskId(Long taskId);
}
