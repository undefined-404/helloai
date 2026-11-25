package com.helloai.core.task.port;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskStatus;

import java.util.List;
import java.util.Map;

/**
 * 子任务草案写入物料（task 域对外契约，RM5 批 5a）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 planner 域（下标 0）【高于】提供方
 * task 域（下标 2），故写契约落【提供方】{@code task.port}；消费方 {@code planner → task.port}
 * 为顺向合法，从此不再 {@code new SubTask()} 创建 task 实体。</p>
 *
 * <p><b>为什么是「写入物料」而非实体</b>（§7.2：「读走快照、写走命令」）：拆解链需要<b>创建</b>
 * 子任务行，消费方只应表达「目标值」，行内状态（id / version / createTime / 乐观锁）由提供方
 * 自行落库，不得外泄。</p>
 *
 * <p><b>字段对应原 {@code PlannerDecomposeAsyncServiceImpl#buildDrafts} 的全部 setter</b>：
 * taskId / title / content / deliverable / acceptance / priority / isContract / requiredSkills /
 * constraints / status / context / uncertainties / acceptanceKind。</p>
 */
public record SubTaskDraft(
        Long taskId,
        String title,
        String content,
        String deliverable,
        String acceptance,
        String priority,
        Integer isContract,
        List<String> requiredSkills,
        String constraints,
        SubTaskStatus status,
        Map<String, Object> context,
        List<UncertaintyDraft> uncertainties,
        /**
         * 验收判据（`TD-2` / `TD-3`）：LLM 在草案里声明「谁来判、怎么判」。
         * 追加在末尾而非插在 {@code acceptance} 之后——位置化构造点唯一
         * （{@code PlannerDecomposeAsyncServiceImpl#buildDrafts}），扩尾部对既有调用零影响；
         * 语义归属由本注释固定，不依赖字段顺序。
         * <p>{@code null} 由投影层原样落库，DB 默认值与 CHECK 约束兜底为 {@code AUTOMATED}
         * （= 改造前行为）。</p>
         */
        AcceptanceKind acceptanceKind,

        /** 本批拆解内的 1-based 序号（写入暂存区展示编号；执行表已不再存该列）。 */
        Integer draftSeq,

        /**
         * 草案的位置身份（`TD-16d` / `V147`），如 {@code 202611080002-001}。
         *
         * <p><b>由谁算</b>：planner 侧（{@code PlannerDecomposeAsyncServiceImpl#buildDrafts}）
         * 用 {@code DraftCode} 算好后放进本字段。task 域只负责原样落库，
         * **不在落库侧再算一遍**——两处各算一次必然分叉，而分叉的后果是暂存区定位错位
         * （同一草案出现两个 code）。</p>
         *
         * <p>非拆解路径（人工创建）为 {@code null}。</p>
         */
        String draftCode,

        /**
         * 类型化约束引用（`G-037` 任务约束类型化试点）：契约文档引用列表（ref + scope）。
         * 空列表 = 无契约引用；{@code null} 兼容旧调用方（落库侧原样透传，DB 默认空数组）。
         */
        List<ConstraintRefDraft> constraintRefs
) {

    /**
     * 兼容构造：不含 {@code draftCode}（`TD-16d` 之前的调用方与测试沿用）。
     *
     * <p>与 {@code SubTaskView} 的兼容构造器同理：全参数位置化构造加尾部字段会波及
     * 多个调用点，而绝大多数调用方并不关心位置身份。</p>
     */
    public SubTaskDraft(Long taskId, String title, String content, String deliverable, String acceptance,
                        String priority, Integer isContract, List<String> requiredSkills, String constraints,
                        SubTaskStatus status, Map<String, Object> context,
                        List<UncertaintyDraft> uncertainties, AcceptanceKind acceptanceKind,
                        Integer draftSeq) {
        this(taskId, title, content, deliverable, acceptance, priority, isContract, requiredSkills,
                constraints, status, context, uncertainties, acceptanceKind, draftSeq, null, null);
    }

    /**
     * 兼容构造：不含 {@code constraintRefs}（`G-037` 之前的调用方与测试沿用；
     * 委托补 {@code null}，落库侧原样透传，DB 默认空数组）。
     */
    public SubTaskDraft(Long taskId, String title, String content, String deliverable, String acceptance,
                        String priority, Integer isContract, List<String> requiredSkills, String constraints,
                        SubTaskStatus status, Map<String, Object> context,
                        List<UncertaintyDraft> uncertainties, AcceptanceKind acceptanceKind,
                        Integer draftSeq, String draftCode) {
        this(taskId, title, content, deliverable, acceptance, priority, isContract, requiredSkills,
                constraints, status, context, uncertainties, acceptanceKind, draftSeq, draftCode, null);
    }
}
