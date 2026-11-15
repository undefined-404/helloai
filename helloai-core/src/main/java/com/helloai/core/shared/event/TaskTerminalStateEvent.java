package com.helloai.core.shared.event;

import com.helloai.common.constant.TaskStatus;
import lombok.Getter;

/**
 * 任务进入终态事件（任务级资源回收触发源，`SL-2`）。
 *
 * <p><b>为什么不复用 {@link TaskAutoCompletedEvent}</b>：后者语义是「自动收口完成 →
 * 触发最终整合报告」，只覆盖 {@code DONE} 且消费方是报告服务；本事件的语义是
 * 「任务生命周期终结」，覆盖 {@code DONE} / {@code CANCELLED} / 物理删除三种出口，
 * 消费方是资源回收（沙箱）。两者共用发布点但目的不同——合并会让报告服务收到
 * 取消/删除事件，也让回收逻辑被迫读报告语义（`CODE_STYLE` §2.3 复用判据的反例）。</p>
 *
 * <p><b>为什么 {@code status} 由发布方给、消费方不自己查</b>：{@code deleteTaskCascade}
 * 提交后任务行已物理删除，消费方无法回查状态；删除路径的 {@code status} 是删除前读到的
 * 值，只用于日志定位，不参与任何判定（判定在发布侧，见 `SL-2` 验收口径）。</p>
 *
 * <p><b>事务语义</b>：三个发布点中 {@code tryCloseTask} 无事务上下文（CAS 单语句后即发布），
 * 另两处在 {@code @Transactional} 内。故消费方须用
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)}：
 * 有事务 ⇒ 提交后触发（避免「任务状态未提交、沙箱已销毁」）；无事务 ⇒ fallback 立即触发。</p>
 *
 * @see com.helloai.core.agent.runtime.sandbox.SandboxTaskTerminalListener
 */
@Getter
public class TaskTerminalStateEvent {

    private final Long taskId;
    private final TaskStatus status;
    private final String trigger;

    /**
     * @param taskId  任务 ID
     * @param status  终态（删除路径为删除前状态，仅供日志定位）
     * @param trigger 发布出口：{@code closed}（子任务全终态自动收尾）/ {@code status_changed}
     *                （人工或链路改状态）/ {@code deleted}（级联物理删除）
     */
    public TaskTerminalStateEvent(Long taskId, TaskStatus status, String trigger) {
        this.taskId = taskId;
        this.status = status;
        this.trigger = trigger;
    }
}
