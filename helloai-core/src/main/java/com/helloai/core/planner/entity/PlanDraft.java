package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.TaskPriority;
import com.helloai.core.planner.support.DraftCode;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import com.helloai.core.task.port.UncertaintyDraft;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 草案暂存区的行（`TD-16` / 表 {@code task_plan_draft} / 迁移 `V147`）。
 *
 * <p><b>它与 {@code SubTask} 的分工</b>：本表只活到「确认」那一刻。确认时把每个
 * {@code ACTIVE} 行的 {@code depends_on_codes} 翻译成叶子 code，再映射成真实
 * {@code sub_task.id} 写入执行表 —— **扁平化只发生一次，而且是翻译不是修补**。
 * 此后执行链看到的仍是扁平 id 数组构成的 DAG，派发/解锁/统计一行都不用改。</p>
 *
 * <p><b>为什么 {@code dependsOnCodes} 用 {@code List<String>} 而 {@code SubTask.dependsOn}
 * 要写归一化方法</b>：后者是 {@code List<Long>}，Jackson 从 JSON 数字反序列化出的是
 * {@code Integer}，直接遍历会错失匹配（项目里已有 {@code dependsOnIdList()} 这个补丁）。
 * {@code List<String>} 没有这个歧义 —— 顺带的一个收益，不是刻意设计。</p>
 *
 * <p><b>JSONB 三件套缺一即回归风险</b>（`MEMORY` 判据）：
 * ① {@code @TableName(autoResultMap = true)}；② 字段 {@code @TableField(typeHandler=...)}；
 * ③ {@code MyBatisPlusConfig} 的 {@code JacksonTypeHandler.setObjectMapper(带 JavaTimeModule)}。
 * 本类满足 ①②，③ 是全局既有配置。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "task_plan_draft", autoResultMap = true)
public class PlanDraft extends BaseEntity {

    private Long taskId;

    /**
     * 逻辑位置身份（{@code <yyyyMMdd><seq:04d>[-<seq:03d>…]}）。
     *
     * <p>存字符串而不是序列化成 {@link DraftCode}：值对象是**内存语义**（校验、display、
     * 层级关系），落库只需要那个字符串。让 MyBatis 认识 record 值对象要多一个 TypeHandler，
     * 收益只有「读出来就自动校验」——而校验失败在读取时抛异常，比写入时拒绝更难查。</p>
     */
    private String code;

    /** 草案轮次（同 task 内递增）。确认即终止；确认后唯一的新轮入口是「整体废除」。 */
    private Integer round;

    /** 幂等键：本次操作事件。与 {@code uk_plan_draft_batch(batch_key, code)} 配合去重。 */
    private String batchKey;

    /** 三态：{@code ACTIVE} / {@code DISCARDED} / {@code CONFIRMED}。 */
    private String nodeState;

    /** 批内草案序号（1-based；展开/重生成/人工新建为 {@code null}）。读侧编号与 {@code blockedBy} 锚用。 */
    private Integer draftSeq;

    private String title;

    private String content;

    private String deliverable;

    private String acceptance;

    @TableField("acceptance_kind")
    private AcceptanceKind acceptanceKind;

    private TaskPriority priority;

    /** 是否契约定义草案（0/1）。与 {@code sub_task.is_contract} 同义。 */
    private Integer contract;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> requiredSkills;

    private String constraints;

    /** 缺口申报（与拆解 Prompt 的 JSON 契约同形）。 */
    @TableField(typeHandler = UncertaintyDraftListTypeHandler.class)
    private List<UncertaintyDraft> uncertainties;

    /**
     * 依赖的 **code** 数组（不是 id）。
     *
     * <p>这是整个方案的核心：{@code 003} 依赖 {@code 002}，而 {@code 002} 展开成三条之后，
     * {@code 003} 的 {@code depends_on_codes} **一个字都不用改**——因为 002 这个位置还在，
     * 它的叶子由暂存区解析。确认时一次性翻译成「002 的全部叶子」。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> dependsOnCodes;

    /** 拆解审计与门禁结论：plannerAgentId / planGeneratedAt / gateFindings / acceptancePreconditions … */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> context;

    // ── 语义便捷方法（不落库）──────────────────────────────────────────────

    /** 是否当前有效（可编辑、参与依赖解析）。 */
    public boolean isActive() {
        return "ACTIVE".equals(nodeState);
    }

    /** 位置身份的值对象视图；{@code code} 非法时返回 {@code null}（读存量数据时容错）。 */
    public DraftCode draftCode() {
        return DraftCode.tryParse(code).orElse(null);
    }
}
