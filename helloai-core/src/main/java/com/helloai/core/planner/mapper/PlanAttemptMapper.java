package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.entity.PlanAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 拆解操作尝试表（{@code task_plan_attempt}）的读写（`TD-16b`）。
 *
 * <p><b>全部写操作都是 CAS</b>（{@code UPDATE ... WHERE id=? AND status=?}），返回影响行数；
 * 调用方以「0 行」为自己的放弃信号。这是栅栏的落地点——
 * 无条件 {@code UPDATE} 会让苏醒的旧尝试覆盖新尝试的结果，那正是要防的「悬挂」。</p>
 *
 * <p><b>时间一律由应用传入</b>（{@code #{now}} / {@code #{leaseUntil}}），不用数据库
 * {@code now()}：单测可固定 {@code Clock}，也让「同一批操作使用同一个时刻」成立
 * （同一次派发里判悬挂与写租约必须用同一个 now，否则边界上会自相矛盾）。
 * 这与 {@code TaskMapper#findTimedOutDecomposing} 的口径一致。</p>
 *
 * <p><b>不用自定义 XML</b>：本表所有语句只触及少数列，注解内联即可；
 * 不引入「显式列清单」这个已经三次漏列的失败模式（见 {@link PlanAttempt} 类注释）。</p>
 */
@Mapper
public interface PlanAttemptMapper extends BaseMapper<PlanAttempt> {

    /**
     * 抢占：{@code PENDING} 或 {@code FAILED_RETRYABLE} → {@code RUNNING}。
     *
     * <p>允许从可重试直接抢，是为了让派发器一次 CAS 完成「捡起来 + 占住」：
     * 若先转 {@code PENDING} 再抢，中间多一个可见状态，两个派发器实例会看到同一条
     * {@code PENDING} 并都去抢（虽然 CAS 会挡住一个，但白跑一轮）。</p>
     *
     * <p><b>必须带 {@code attempt_no}</b>（`G-053`）：派发器扫描到候选行与真正执行 claim
     * 之间隔着一次模板调用，其间 {@code reclaimHanging} 可能已把号加一。旧的
     * {@code WHERE id + status} 会让「扫描时拿到旧号的那一次派发」抢走新代的行，
     * 于是新旧两代用同一个号继续跑。带号后旧代必落空。</p>
     *
     * <p>返回 1 = 抢到；返回 0 = 已被别的节点抢走 / 代次已失效，调用方应放弃。</p>
     */
    @Update("UPDATE task_plan_attempt "
            + "SET status = 'RUNNING', owner_node = #{ownerNode}, lease_until = #{leaseUntil}, "
            + "    update_time = #{leaseUntil} "
            + "WHERE id = #{id} AND attempt_no = #{attemptNo} "
            + "  AND status IN ('PENDING', 'FAILED_RETRYABLE') AND deleted = 0")
    int casClaim(@Param("id") Long id,
                 @Param("attemptNo") Integer attemptNo,
                 @Param("ownerNode") String ownerNode,
                 @Param("leaseUntil") OffsetDateTime leaseUntil);

    /**
     * 续租：{@code RUNNING} 且 {@code attempt_no} 仍是本次 ⇒ 推后 {@code lease_until}。
     *
     * <p>必须带 {@code attempt_no}：否则一个已被判失败重投的旧尝试还能续自己的租约，
     * 与新尝试争抢同一条记录。</p>
     */
    @Update("UPDATE task_plan_attempt SET lease_until = #{leaseUntil}, update_time = #{leaseUntil} "
            + "WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING' AND deleted = 0")
    int casRenew(@Param("id") Long id,
                 @Param("attemptNo") Integer attemptNo,
                 @Param("leaseUntil") OffsetDateTime leaseUntil);

    /**
     * 收尾：{@code RUNNING → SUCCEEDED}，**防悬挂的核心语句**。
     *
     * <p>旧尝试苏醒后调用它，若 {@code attempt_no} 已不是当前有效值（被判失败后重投过），
     * 影响行数为 0 ⇒ 调用方放弃写入草案，只记一条 WARN。</p>
     *
     * <p>这取代了原来「比 {@code task.status} 还在不在 {@code PLANNING}」的判法：
     * 那个判法会被别人改状态干扰（例如用户取消任务），且无法区分「我这次尝试」与
     * 「另一次尝试」。</p>
     */
    @Update("UPDATE task_plan_attempt "
            + "SET status = 'SUCCEEDED', update_time = #{now}, "
            + "    outcome_note = #{note}, expected_count = COALESCE(#{expectedCount}, expected_count) "
            + "WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING' AND deleted = 0")
    int casSucceed(@Param("id") Long id,
                   @Param("attemptNo") Integer attemptNo,
                   @Param("expectedCount") Integer expectedCount,
                   @Param("note") String note,
                   @Param("now") OffsetDateTime now);

    /**
     * 记录本批应落草案条数（`TD-16e-2` 完整性判据的唯一事实源）。
     *
     * <p><b>为什么必须在草案落库之前写</b>：撞唯一索引恢复时要回答的问题是「上次该落几条、
     * 落全了没有」，而唯一能回答它的只有这一列。若放在落库之后写，两者之间一旦异常 / 崩溃，
     * {@code expected_count} 恒为 {@code null}，残缺批次就永远识别不出来——只能退回
     * 「有一条就算已消费」，正是设计 §5.6 要修掉的那件事。</p>
     *
     * <p><b>为什么不带 {@code status = 'RUNNING'}</b>：这次执行可能已被别的节点抢走
     * （{@code claim} 落空但主流程仍继续），此时行仍是 {@code PENDING}。期望条数描述的是
     * 「这个 batch 应落几条」，与谁在跑无关；按 {@code status} 过滤会让重复执行的那一次
     * 写不进期望值，撞键恢复时又退回存在性判定。</p>
     *
     * <p><b>带 {@code attempt_no}</b>：旧尝试（已被判失败重投、{@code attempt_no} 已 +1）
     * 不得再改当前有效尝试的期望值。返回 0 就是「本次尝试已失效」的信号。</p>
     *
     * @return 1 = 已写入当前有效尝试；0 = 该 {@code attempt_no} 已失效（被重投 / 接管）
     */
    @Update("UPDATE task_plan_attempt SET expected_count = #{expectedCount}, update_time = #{now} "
            + "WHERE id = #{id} AND attempt_no = #{attemptNo} AND deleted = 0")
    int recordExpectedCount(@Param("id") Long id,
                            @Param("attemptNo") Integer attemptNo,
                            @Param("expectedCount") int expectedCount,
                            @Param("now") OffsetDateTime now);

    /**
     * 失败且**可重试**：{@code RUNNING → FAILED_RETRYABLE}，{@code attempt_no + 1}，清 {@code owner_node}。
     *
     * <p>加一之后旧号永久失效 ⇒ 苏醒的旧尝试再调 {@link #casSucceed} 必然 0 行。</p>
     */
    @Update("UPDATE task_plan_attempt "
            + "SET status = 'FAILED_RETRYABLE', attempt_no = attempt_no + 1, owner_node = NULL, "
            + "    lease_until = #{nextLeaseUntil}, update_time = #{now}, outcome_note = #{note} "
            + "WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING' AND deleted = 0")
    int casFailRetryable(@Param("id") Long id,
                         @Param("attemptNo") Integer attemptNo,
                         @Param("nextLeaseUntil") OffsetDateTime nextLeaseUntil,
                         @Param("note") String note,
                         @Param("now") OffsetDateTime now);

    /**
     * 失败且**不可重试**：{@code RUNNING → FAILED_TERMINAL}。
     *
     * <p>故意**不改** {@code attempt_no}、**不清** {@code owner_node}：终态之后不会再有
     * 新尝试，保留「是谁跑的最后一次」对排查有用；改号只会让审计线索错位。</p>
     */
    @Update("UPDATE task_plan_attempt "
            + "SET status = 'FAILED_TERMINAL', lease_until = NULL, update_time = #{now}, "
            + "    outcome_note = #{note} "
            + "WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING' AND deleted = 0")
    int casFailTerminal(@Param("id") Long id,
                        @Param("attemptNo") Integer attemptNo,
                        @Param("note") String note,
                        @Param("now") OffsetDateTime now);

    /**
     * 判悬挂：{@code RUNNING} 且租约已过期 ⇒ 转 {@code FAILED_RETRYABLE}、{@code attempt_no + 1}。
     *
     * <p>这不是「失败」，是「无法证明它还活着」。转可重试而不是终止：进程可能只是崩了，
     * 重来一次大概率能成。</p>
     */
    @Update("UPDATE task_plan_attempt "
            + "SET status = 'FAILED_RETRYABLE', attempt_no = attempt_no + 1, owner_node = NULL, "
            + "    lease_until = #{nextLeaseUntil}, update_time = #{now}, "
            + "    outcome_note = CONCAT('suspected hanging: lease expired before ', "
            + "        COALESCE(owner_node, 'unknown')) "
            + "WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0 "
            + "  AND lease_until IS NOT NULL AND lease_until < #{now}")
    int casMarkHanging(@Param("id") Long id,
                       @Param("nextLeaseUntil") OffsetDateTime nextLeaseUntil,
                       @Param("now") OffsetDateTime now);

    /**
     * 待派发：{@code FAILED_RETRYABLE} 且已到重试时刻（或未设时刻）。
     *
     * <p>派发器据此把「上次失败但可重试」的尝试重新推进 {@code PENDING} 或直接抢占。</p>
     */
    @Select("SELECT * FROM task_plan_attempt "
            + "WHERE status = 'FAILED_RETRYABLE' AND deleted = 0 "
            + "  AND (lease_until IS NULL OR lease_until < #{now}) "
            + "ORDER BY update_time ASC LIMIT #{limit}")
    List<PlanAttempt> listRetryable(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** 悬挂候选：{@code RUNNING} 且租约已过期。 */
    @Select("SELECT * FROM task_plan_attempt "
            + "WHERE status = 'RUNNING' AND deleted = 0 "
            + "  AND lease_until IS NOT NULL AND lease_until < #{now} "
            + "ORDER BY lease_until ASC LIMIT #{limit}")
    List<PlanAttempt> listHanging(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** 登记后无人接手：{@code PENDING}（T1 提交后 @Async 未跑或线程池拒绝）。 */
    @Select("SELECT * FROM task_plan_attempt "
            + "WHERE status = 'PENDING' AND deleted = 0 "
            + "ORDER BY create_time ASC LIMIT #{limit}")
    List<PlanAttempt> listPending(@Param("limit") int limit);

    /** 按幂等键取当前有效的那次尝试（{@code attempt_no} 最大的一行）。 */
    @Select("SELECT * FROM task_plan_attempt WHERE batch_key = #{batchKey} AND deleted = 0 "
            + "ORDER BY attempt_no DESC LIMIT 1")
    PlanAttempt findLatestByBatchKey(@Param("batchKey") String batchKey);

    /**
     * **写段授权**：在当前事务里锁住本尝试行并读回真实代次（`G-053` L2）。
     *
     * <p>为什么必须是行锁而不是普通 SELECT：{@code persistPlan} 事务的第一条语句与它之间
     * 隔着「草案整批插入」。只做一次普通读取，读完到插完这段时间里 {@code reclaimHanging}
     * 完全可以把 {@code attempt_no} 加一 —— 于是授权判定通过、写入却属于已失效的代次。
     * {@code FOR UPDATE} 让 reclaim 的 {@code UPDATE} 阻塞到本事务提交，从而把
     * 「判定」与「写入」锁成同一个原子区间。</p>
     *
     * <p>不用 {@code status = 'RUNNING'} 过滤：过滤掉就变成「读不到 ⇒ 判定通过与否」的
     * 存在性判断，读不到时无法区分「行被删了」与「状态不对」。取回整行由调用方逐条判，
     * 判据集中在一处。</p>
     *
     * <p><b>必须在事务内调用</b>：无事务时 {@code FOR UPDATE} 只锁到语句结束，
     * 退化为普通读取（PostgreSQL 不报错，静默失效——这类失效最危险）。</p>
     */
    @Select("SELECT * FROM task_plan_attempt WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    PlanAttempt lockByIdForUpdate(@Param("id") Long id);

    /** 按任务 + 动作取最近一次尝试（诊断 / 幂等命中判定用）。 */
    @Select("SELECT * FROM task_plan_attempt "
            + "WHERE task_id = #{taskId} AND action = #{action} AND deleted = 0 "
            + "ORDER BY create_time DESC LIMIT 1")
    PlanAttempt findLatestByTaskAndAction(@Param("taskId") Long taskId,
                                          @Param("action") String action);
}
