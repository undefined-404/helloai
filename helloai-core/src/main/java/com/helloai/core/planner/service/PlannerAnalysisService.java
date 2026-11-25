package com.helloai.core.planner.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.Data;

import java.util.List;

/**
 * Planner 平台内自动拆解服务（需求 → 子任务草案 → 用户确认 → 进入既有分发链）。
 *
 * <p>职责边界（对齐 §6.3 分层红线：编排逻辑收口在 core，Controller 只做薄转发）：</p>
 * <ul>
 *     <li>{@link #decompose(Long)}：同步守卫（校验 + CAS 推进 Task → PLANNING）后提交
 *         {@link PlannerDecomposeAsyncService} 异步执行 LLM 拆解，立即返回空列表；
 *         草案产出与失败回退均由异步段收敛，卡死任务由 PlanningTimeoutTask 兜底回收。</li>
 *     <li>{@link #listDraftsFromStaging(Long)}：查看草案列表（暂存区唯一读路）。</li>
 *     <li>{@link #confirmPlan(Long)}：草案批量转正（→ PENDING），Task → IN_PROGRESS，
 *         按 {@code autoAssignOnCreate} 配置触发既有自动分发链（与手工创建子任务同构）。</li>
 *     <li>{@link #rejectPlan(Long)}：草案翻 CANCELLED（保留审计），Task 回退 PENDING 可重新拆解。</li>
 * </ul>
 *
 * <p>草案只存在于 {@code task_plan_draft}，对 claim/assignNext/自动重派/补偿定时任务天然不可见
 * （它们只认 PENDING 等状态），无需额外隔离逻辑。</p>
 *
 * <p>confirm/reject 刻意不加类级事务：逐条 changeStatus 各自独立事务（与既有风格一致），
 * 中途失败可重入——已转正/已取消的子任务不再出现在草案列表，重调即续做剩余部分。</p>
 */
public interface PlannerAnalysisService {

    /**
     * 触发平台内自动拆解（同步守卫）。
     *
     * <p>只做校验与状态推进：校验通过后 CAS 推进 PLANNING、记录
     * {@code task_plan_async_submitted}，随后把 LLM 拆解段提交
     * {@link PlannerDecomposeAsyncService} 异步执行并立即返回空列表（拆解异步化改造，
     * HTTP 线程不再等待 LLM）。异步段通过 timeline 收敛结果，前端轮询草案；
     * 卡死任务由 PlanningTimeoutTask 兜底回收。</p>
     *
     * @return 恒为空列表（API 契约保持不变，草案经 {@link #listDraftsFromStaging(Long)} 轮询获取）
     */
    List<SubTaskView> decompose(Long taskId);

    /**
     * `TD-16d-4` 读侧换源：从暂存区 `task_plan_draft` 读当前轮草案列表。
     *
     * <p>`AD-5a-4` 存量迁入后 `sub_task` 不再承载草案，本方法是**唯一读路**（无当前轮时返回空
     * 列表，不再回落执行表）。返回视图的 {@code id} 为暂存区行 id，写路径统一按 {@code code} 定位。</p>
     */
    List<SubTaskView> listDraftsFromStaging(Long taskId);

    /**
     * 确认草案：暂存区当前轮物化为执行行（PENDING/AWAITING_DERIVED/DONE），Task → IN_PROGRESS。
     *
     * <p>随后按 {@code helloai.dispatch.auto-assign-on-create} 配置决定是否
     * 逐条走 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto} 自动分配，
     * 与 SubTaskController 手工创建子任务的分发路径完全同构。</p>
     *
     * @return 转正后的子任务列表
     */
    List<SubTaskView> confirmPlan(Long taskId);

    /**
     * 拒绝草案：作废暂存区整轮（DISCARDED），Task 回退 PENDING 可重新拆解。
     *
     * @return 被取消的草案数量
     */
    int rejectPlan(Long taskId);

    // ══════════════════════════════════════════════════════════════
    //  `TD-16d-4` 切片 `d-3b-2`：草案端点身份由行 id 换成位置编号 code
    // ══════════════════════════════════════════════════════════════

    /**
     * 按位置编号定位草案（`d-3b-2` 的唯一定位口径）。
     *
     * <p><b>为什么换 code</b>：{@code sub_task.id} 是行身份，确认后行仍在但语义已变；
     * 页面上的草案在「同一个位置」上必须始终能用同一个编号定位。
     * {@code code}（单段定宽 {@code 202611080002}，`ADR-008` §19.2）是位置身份。</p>
     *
     * <p><b>为什么必须带 taskId</b>：code 只在任务内唯一，不同任务同日拆解必然同码
     * （实测 {@code 202611080001} 在 3 个任务里同时存在）。只按 code 定位会串任务。</p>
     *
     * <p><b>fail-close</b>：参数缺失 / 无匹配（已转正离开草案态、存量 V147 前无 code）
     * 一律抛 {@code BizException} → 400，不静默降级到「按 id 猜」。</p>
     */
    void updateDraftByCode(Long taskId, String code, List<String> requiredSkills, String constraints,
                          List<Uncertainty> uncertainties, String acceptanceKind);

    /**
     * 草案期派生（`ADR-008` §19.3 S3）：把一条 `ACTIVE` 草案「拆出去」为独立 Task `Y`。
     *
     * <p><b>不改本条草案的状态</b>（仍 `ACTIVE` 待确认）：确认时由
     * {@code SubTaskService#materializeFromStaging} 物化为 `AWAITING_DERIVED`（`Y` 已完成则直接
     * `DONE`）。因此兄弟草案对它的依赖不会悬空，`G1` 门禁天然不需要补锚点。</p>
     *
     * <p>`Y` 立即落库（父锚 `(parent_task_id, parent_draft_code)`、`depth+1`、继承
     * archetype），随后触发 `Y` 的拆解。幂等：同一条草案重复调用返回同一派生任务，
     * 若它仍停在 `PENDING` 会补触发拆解。</p>
     *
     * @return 派生 Task 快照（id / status 供前端提示）
     */
    TaskView deriveDraftByCode(Long taskId, String code);

    /** 同上按 code 定位的重生成（`d-3b-2`）。 */
    SubTaskView regenerateDraftByCode(Long taskId, String code);

    /** LLM 结构化输出条目（未知字段容忍，避免 LLM 多给字段导致整批失败）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class PlanDraftItem {
        private String title;
        private String content;
        private String deliverable;
        private String acceptance;

        /**
         * 验收判据（`TD-3` 输出契约新增字段，`TD-2` 落库）：`AUTOMATED` / `STRUCTURAL` /
         * `REVIEWED` / `ATTESTED`，含义见 {@code AcceptanceKind}。
         *
         * <p><b>缺失 / 非法值一律回落 {@code AUTOMATED}</b>——它是改造前的唯一语义，
         * 回落即「零行为变化」；<b>不做按 {@code acceptance} 文本反推 kind</b>（推断错会让校验
         * 走错分支，比显式声明更危险，见计划 `TD-2`「不做的」）。</p>
         */
        private String acceptanceKind;

        private String priority;
        /** 依赖的同批草案序号（1-based）；空/null=无依赖。 */
        private List<Integer> dependsOn;
        /**
         * 契约定义子任务标记（契约先行拆解模式）：true=契约定义子任务
         * （产出接口签名/数据模型/错误码表，全局注入下游），落库
         * {@code SubTask.isContract}；null/缺省=false 按普通子任务处理。
         */
        private Boolean contract;

        /**
         * 子任务级技能指派（Planner 能力感知 G-010）：
         * 只能取平台技能目录内的标签；未命中目录的标签落库前被过滤（可选）。
         */
        private List<String> requiredSkills;

        /**
         * 执行约束（不许改的事，G-010 COARSE 粒度必填）：直接落地
         * {@code SubTask.constraints}；FINE/STANDARD 档可为 null。
         */
        private String constraints;

        /**
         * 不确定性申报（G-011，可选）：kind=ASSUMPTION（已申报假设）/ UNCONFIRMED（待确认缺口）
         * + note。用 JsonNode 承接（同 ClarifyReply.package 防御模式）：LLM 输出非法形态
         * （字符串 / 数字 / 嵌套对象）不击穿整批拆解解析；数组元素级校验与 kind 非法值
         * 降级 UNCONFIRMED + 审计在 {@code PlannerDecomposeAsyncServiceImpl} 统一处理（D3）。
         */
        @JsonProperty("uncertainties")
        private JsonNode uncertaintiesNode;

        /**
         * 类型化约束引用（`G-037`，可选）：约束文档引用列表（ref + scope）。
         * 用 JsonNode 承接（同 {@code uncertaintiesNode} 防御模式）：LLM 输出非法形态
         * （字符串 / 数字 / 嵌套对象）不击穿整批拆解解析；元素级校验与非法 kind 过滤
         * 在 {@code PlannerDecomposeAsyncServiceImpl} 统一处理。
         */
        @JsonProperty("constraintRefs")
        private JsonNode constraintRefsNode;

        /**
         * 本子任务引用的会话附件序号（`G-042`，可选）：元素为「拆解期附件清单」里的 1-based 序号。
         *
         * <p>用 JsonNode 承接（同 {@code uncertaintiesNode} / {@code constraintRefsNode} 防御模式）：
         * 模型可能给出字符串、浮点、嵌套对象等非法形态，元素级校验放在
         * {@code PlannerDecomposeAsyncServiceImpl} 统一做——一个坏元素只丢自己，不击穿整批拆解。</p>
         *
         * <p><b>缺失 / 空数组 = 未声明</b> ⇒ 该子任务回落会话全量图片（行为等于改造前），
         * 并在执行期 prompt 里显式标注回落原因。</p>
         */
        @JsonProperty("attachmentRefs")
        private JsonNode attachmentRefsNode;
    }
}
