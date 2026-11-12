package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.PlanAttemptAction;
import com.helloai.common.constant.PlanAttemptStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 拆解操作的 Outbox 意图 + 栅栏日志（`TD-16b`，表 {@code task_plan_attempt} / 迁移 `V146`）。
 *
 * <p><b>一行代表什么</b>：一次操作事件（首拆 / 展开 / 重生成 / 确认 / 废除）的
 * <b>一次尝试</b>。同一个 {@code batch_key} 因失败重试会产生多行，用
 * {@code attempt_no} 区分（1、2、3…）。</p>
 *
 * <p><b>它解决两个问题</b>：</p>
 * <ol>
 *   <li><b>Outbox</b>：把「我要拆」这个意图与 {@code task.status} 变更写进**同一个短事务**。
 *       进程崩在 T1 之后、{@code @Async} 之前，库里有 {@code RUNNING} 行 ⇒ 派发器兜底接手。
 *       「不丢」的保证从「靠进程活着」变成「靠库里有记录」。</li>
 *   <li><b>栅栏</b>：{@code (batch_key, attempt_no)} 唯一。A 超时被判失败 → B 接手完成 →
 *       A 苏醒想写结果时，先 CAS {@code WHERE batch_key=? AND attempt_no=? AND status='RUNNING'}，
 *       影响 0 行则放弃写入。这比「比 task 状态还在不在 PLANNING」更准——
 *       后者会被别人改状态干扰。</li>
 * </ol>
 *
 * <p><b>为什么不写自定义 Mapper XML</b>：本表的读写全部走 MyBatis-Plus 内置方法
 * （{@code insert} / {@code updateById}）与 {@code LambdaUpdateWrapper} 的
 * {@code set + eq}（CAS）。历史上 {@code SubTaskMapper.xml} 的显式列清单已三次漏列
 * （09-10 一批 / 10-24 {@code acceptance_kind} / 11-08 {@code decompose_attempt_id+draft_seq}），
 * 每次都是「实体加了字段、XML 没同步 ⇒ 值静默丢弃」。不引入列清单 = 不引入这个失败模式。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_plan_attempt")
public class PlanAttempt extends BaseEntity {

    /** 所属任务。 */
    private Long taskId;

    /**
     * 本次操作事件的幂等键。
     *
     * <p>首拆沿用 {@code task.decompose_attempt_id}（`V146` 已在入口生成的 UUID）；
     * 其余为 {@code <action>:<subject>:<uuid>}，见设计 §5.8。</p>
     */
    private String batchKey;

    /** 操作类型（{@code DECOMPOSE} / {@code REGENERATE} / {@code CONFIRM} / {@code ABANDON} / {@code DERIVE} / {@code RESTRUCTURE}）。 */
    private PlanAttemptAction action;

    /** 本次尝试的结论（五态）。 */
    private PlanAttemptStatus status;

    /** 同一 {@code batchKey} 的第几次尝试（1-based）。超过当前有效值即「本次尝试已过期」。 */
    private Integer attemptNo;

    /**
     * 本批**应落几条**草案（解析阶段写入，不由落库段事后推断）。
     *
     * <p>撞幂等键后用它判「内容是否完整」，而不是判「是否存在」——
     * 存在但残缺的批次被当成已完成，就永远不会再补。</p>
     */
    private Integer expectedCount;

    /** 租约到期时刻。{@code RUNNING} 且 {@code leaseUntil < now()} ⇒ 判悬挂。 */
    private OffsetDateTime leaseUntil;

    /** 抢占者标识（多实例诊断用）；为空 = 尚未被任何节点接手。 */
    private String ownerNode;

    /** 结论说明（失败原因摘要 / 幂等命中说明），给人排查用，不参与判定。 */
    private String outcomeNote;
}
