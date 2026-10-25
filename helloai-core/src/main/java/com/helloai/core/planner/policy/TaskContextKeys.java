package com.helloai.core.planner.policy;

/**
 * {@code task.context} 的键常量（`TD-6` 拆解期上下文快照的取数依据）。
 *
 * <p><b>为什么要有这个类</b>：`task.context` 是自由 JSONB，键名靠字符串约定。原先
 * {@code requirementPackage} 的键常量挂在读取它的 {@link RequirementPackageParser} 上，
 * 本轮新增的 {@code conversationId} 有「写入方」（澄清终稿建任务）与「读取方」
 * （拆解期快照）两处，键名一旦写错就是**静默取不到数**（不报错、只是快照恒空）。
 * 故收口到一处，双端引用同一常量。</p>
 */
public final class TaskContextKeys {

    /**
     * 建任务来源的澄清会话 id（Long）。
     *
     * <p>写入：{@code ClarifyFinalizeServiceImpl#buildTaskFromDraft}（澄清链路建任务时）。
     * 读取：{@code DecomposeContextSnapshotRenderer}（取该会话的附件清单）。
     * 未走澄清链路的任务（{@code POST /task} 直达）无此键 ⇒ 快照侧降级为「无附件」，
     * 不报错。</p>
     */
    public static final String CONVERSATION_ID = "conversationId";

    /**
     * 期望的子任务条数（Integer，可选）。
     *
     * <p>`TD-3` 只做**渲染层支持**：传了就在提示词里渲染成「必须恰好 N 个」，
     * 没传就渲染默认的 3~10 区间。**本键不改变数量决策链路**（谁来决定 N 属于
     * `G-010` 粒度范围，不在本轮）。当前无业务写入方，供人工 / 运维按需注入。</p>
     */
    public static final String SUBTASK_COUNT = "subtaskCount";

    private TaskContextKeys() {
    }
}
