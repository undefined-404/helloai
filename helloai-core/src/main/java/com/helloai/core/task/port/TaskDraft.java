package com.helloai.core.task.port;

import com.helloai.common.constant.TaskStatus;

import java.util.List;
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
 *
 * <p><b>{@code requiredSkills}（2026-10-26）</b>：用户在需求对话「＋→技能」里选中的产出型技能包
 * （如 {@code archify} / {@code biz-process-lifecycle}）。写入 {@code task.required_skills} 后，
 * 既有注入链（{@code AgentSkillSpecService}）会在子任务执行时展开为「技能规范 + requiredTools」，
 * 执行者据此跑技能 CLI 并导出产物。空列表 = 未选技能（与改造前完全一致）。</p>
 */
public record TaskDraft(
        String title,
        String description,
        TaskStatus status,
        Map<String, Object> context,
        String taskArchetype,
        List<String> requiredSkills
) {

    public TaskDraft {
        requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
    }

    /**
     * 兼容构造器（5 参）：未选技能的既有调用方（含测试）零改动。
     */
    public TaskDraft(String title, String description, TaskStatus status, Map<String, Object> context,
                     String taskArchetype) {
        this(title, description, status, context, taskArchetype, null);
    }
}
