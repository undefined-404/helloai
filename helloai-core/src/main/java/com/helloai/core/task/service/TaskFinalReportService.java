package com.helloai.core.task.service;

import com.helloai.core.task.entity.Task;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;

/**
 * 任务最终整合报告生成（V32）。
 *
 * <p>任务收口后由 Planner 把全部 DONE 子任务产出整合为一份连贯的最终报告
 * （执行摘要 + 重组正文 + 结论），写入 {@code task.final_report} 专列；
 * 交付物 zip（{@link TaskDeliverableService}）与前端报告弹窗均从该列读取。</p>
 *
 * <p>触发方式（两条路径共用 {@link #generate}）：</p>
 * <ul>
 *   <li><b>自动</b>：{@code SubTaskCompletionListener.tryCloseTask} CAS 收口成功后发布
 *       {@link TaskAutoCompletedEvent}，本类 {@code @Async + @EventListener} 承接
 *       （发布点已无事务上下文，不能用 @TransactionalEventListener）；失败仅记
 *       timeline，不影响任务 DONE 状态——报告是增值物，非交付门槛。</li>
 *   <li><b>手动</b>：{@code POST /api/tasks/{id}/final-report}（历史已 DONE 任务补生成
 *       / 报告不满意重新生成，直接覆盖旧报告）。</li>
 * </ul>
 *
 * <p>V41：生成前 CAS 置 {@code final_report_status=GENERATING} 防重入——手动/自动两条
 * 路径并发时只有一个赢家进入 LLM 调用，其余抛"正在生成中"；成功置 DONE、失败置 FAILED
 * （FAILED 可手动重试，避免进程崩溃后永久卡在 GENERATING 之外留出恢复口）。</p>
 *
 * <p>不加类级事务：LLM 调用耗时长（与 PlannerAnalysisService.decompose 同哲学）；
 * 报告写回用 lambdaUpdate 只更新三列，不做全行覆盖。选人复用
 * PlannerAgentPicker#pickForTask，澄清→拆解→整合同一 Planner 跟随。</p>
 */
public interface TaskFinalReportService {

    /**
     * 任务自动收口后异步生成报告；已有报告或开关关闭时跳过，异常吞掉（手动端点兜底）。
     *
     * @param event 任务自动完成事件
     */
    void onTaskAutoCompleted(TaskAutoCompletedEvent event);

    /**
     * 生成（或重新生成）任务最终整合报告，成功后返回最新 Task。
     *
     * <p>前置：任务必须已 DONE 且存在有产出的 DONE 子任务。V41 起生成前先 CAS 置
     * {@code final_report_status=GENERATING}，已有一份生成在途时直接抛错（防重入）；
     * 成功置 DONE、最终失败置 FAILED（可重试）。</p>
     *
     * @param taskId 顶层任务 ID
     * @return 生成成功后的最新 Task
     */
    Task generate(Long taskId);
}
