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

    /**
     * 任务级执行环境声明（Map，可选）：仓库 / 运行时 / 数据源三问的结构化承载。
     *
     * <p><b>为什么进 {@code task.context} 而不是加表加列</b>：它是「整任务共享」的附加声明，不是
     * 业务实体；{@code task.context} 本就是自由 JSONB，与 {@code requirementPackage} 同坑位。
     * 结构见 {@link com.helloai.common.util.ExecutionEnv}（解析 / 渲染的唯一实现）。</p>
     *
     * <p><b>写入方</b>：① 澄清终稿 {@code final_package.executionEnv} 原样搬运
     * （{@code ClarifyFinalizeServiceImpl#buildTaskFromDraft}）；② 运维直接改
     * {@code task.context.executionEnv}。**不由拆解模型产出**——路径与连接信息一旦让模型编造，
     * 后果比缺失更糟（执行者会照着一条不存在的路径去找）。</p>
     *
     * <p><b>读取方</b>：拆解期注入（{@code PlannerDecomposeAsyncServiceImpl} 渲染
     * {@code {{EXECUTION_ENV}}}）；执行期重构会话种子（{@code RestructureSessionServiceImpl}
     * 从父任务 X 读出来交给澄清模型）。缺键 = 未声明，渲染兜底文案，不报错。</p>
     *
     * <p><b>为什么值直接转发 {@link ExecutionEnv#CONTEXT_KEY}</b>：{@code task} 域（派生任务继承）
     * 也要写这个键，若常量只在这里，{@code task → planner} 是反向依赖。见该常量的注释。</p>
     */
    public static final String EXECUTION_ENV = com.helloai.common.util.ExecutionEnv.CONTEXT_KEY;

    private TaskContextKeys() {
    }
}
