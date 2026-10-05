package com.helloai.core.task.port;

import com.helloai.common.constant.TaskStatus;

import java.util.Map;

/**
 * 顶层任务创建物料（task 域对外契约，RM5 批 5a）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 planner 域（下标 0）【高于】提供方
 * task 域（下标 2），故写契约落【提供方】{@code task.port}；消费方 {@code planner → task.port}
 * 为顺向合法，从此不再 {@code new Task()} 创建 task 实体。</p>
 *
 * <p><b>为什么是「创建物料」而非实体</b>（§7.2：「读走快照、写走命令」）：消费方只表达目标值，
 * id / createTime / updateTime 等行内状态由提供方落库回填。</p>
 */
public record TaskDraft(
        String title,
        String description,
        TaskStatus status,
        Map<String, Object> context
) {
}
