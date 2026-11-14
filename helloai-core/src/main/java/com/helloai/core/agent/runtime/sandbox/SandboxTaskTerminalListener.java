package com.helloai.core.agent.runtime.sandbox;

import com.helloai.core.shared.event.TaskTerminalStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 任务终态 ⇒ 回收该任务全部复用沙箱（`SL-2`，2026-11-12；`ADR-005` §6）。
 *
 * <p><b>为什么由事件驱动、而不是在三个终态入口直接调 {@code releaseByTask}</b>：终态出口
 * 分布在 task 域三个类（自动收尾 / 改状态 / 级联删除），直接调用会让 task 域逐个感知
 * agent 域的沙箱实现；发布事件后「谁关心」由消费侧自己声明，task 域不依赖消费方
 * （`CODE_STYLE` §6 依赖方向：发布方不因消费方而变）。</p>
 *
 * <p><b>为什么 {@code fallbackExecution = true} 是刚需、不是保险</b>：{@code tryCloseTask}
 * 发布时已在 AFTER_COMMIT 异步线程、<b>无事务上下文</b>（`TaskAutoCompletedEvent` 同类口径），
 * 默认的 {@code AFTER_COMMIT} 在无事务时<b>不会</b>触达；而 {@code updateStatus} /
 * {@code deleteTaskCascade} 又在 {@code @Transactional} 内需要等提交。两种发布场景并存，
 * 故必须 fallback。</p>
 *
 * <p><b>为什么 {@code @Async("appEventExecutor")}</b>：{@code releaseByTask} 内部对每个槽
 * {@code tryLock(5s)} 后调 SDK {@code kill()}（HTTP），同步执行会把「任务收尾 / 删除请求」
 * 拖到秒级。池复用项目既有的事件线程池（`AppEventExecutorConfig`，CallerRunsPolicy 兜底）。</p>
 *
 * <p><b>失败只 warn 的理由</b>：沙箱是内存态资源，清理失败由空闲 TTL 兜底（`ADR-005` §3）；
 * 不能让「沙箱没杀掉」影响任务状态事务或删除结果，否则一次 SDK 抖动会把已完成任务回滚。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxTaskTerminalListener {

    private final SandboxExecutionService sandboxExecutionService;

    /**
     * 任务 {@code DONE} / {@code CANCELLED} / 物理删除后释放该任务全部规格的复用沙箱。
     *
     * <p>幂等：{@code releaseByTask} 按 {@code task:{taskId}} 谓词删除，重复事件无副作用
     * （第二个事件到达时槽已不在册，静默返回）。</p>
     *
     * @param event 任务终态事件；{@code taskId} 为空时静默忽略
     */
    @Async("appEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskTerminal(TaskTerminalStateEvent event) {
        if (event == null || event.getTaskId() == null) {
            return;
        }
        try {
            sandboxExecutionService.releaseByTask(event.getTaskId());
            log.info("任务终态回收复用沙箱: taskId={}, status={}, trigger={}",
                    event.getTaskId(), event.getStatus(), event.getTrigger());
        } catch (Exception e) {
            log.warn("任务终态回收复用沙箱失败（不阻断，空闲 TTL 兜底）: taskId={}, trigger={}, cause={}",
                    event.getTaskId(), event.getTrigger(), e.getMessage());
        }
    }
}
