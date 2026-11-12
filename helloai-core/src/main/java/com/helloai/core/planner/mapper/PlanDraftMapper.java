package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.entity.PlanDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 草案暂存区（{@code task_plan_draft}）读写（`TD-16`）。
 *
 * <p><b>为什么几乎不写 {@code @Select ... 返回实体}</b>：{@link PlanDraft} 有 4 个 JSONB 字段，
 * 强类型映射依赖 {@code @TableName(autoResultMap = true)} 生成的 resultMap —— 那是
 * MyBatis-Plus **内置方法**才走的路径。自定义 {@code @Select("SELECT * ...")} 返回实体时
 * 不经过该 resultMap，`uncertainties` 会被全局 {@code List} handler 读成
 * {@code List<LinkedHashMap>}，消费点 `u.kind()` 当场 ClassCastException。
 * 这个坑项目已经踩过一次（2026-09-10 派单死锁）。</p>
 *
 * <p>所以本接口的分工是：<b>返回实体的查询一律走 {@code BaseMapper} 内置方法 + Wrapper；
 * 自定义 SQL 只返回标量</b>（{@code int} / {@code long}），标量不涉及 JSONB 映射，安全。</p>
 *
 * <p><b>编号分配的两个方法刻意不在这里</b>：「新增根草案时取 {@code max(seq)+1}」需要
 * 锁任务行串行化（否则并发新增会撞 {@code uk_plan_draft_code}），而锁 {@code task} 表属于
 * task 域——在 planner 的 Mapper 里直接 {@code SELECT ... FROM task FOR UPDATE} 是跨域直捅表
 * （违反 §7.2）。它随「草案新增」的写路径一起落，放 {@code TD-16d}。</p>
 */
@Mapper
public interface PlanDraftMapper extends BaseMapper<PlanDraft> {

    /**
     * 任务内已分配过的最大轮次（**含已软删**；0 = 从未拆解）。
     *
     * <p>跨轮不重置：拒绝/确认都会把旧轮软删，若只数 {@code deleted=0}，重新拆解会再次拿到
     * round=1，历史轮次无法区分（`V147` 注释要求「同 task 内递增」）。本方法只给「分配下一轮」用；
     * 读侧/存在性判断请用 {@link #findMaxActiveRound}。</p>
     */
    @Select("SELECT COALESCE(MAX(round), 0) FROM task_plan_draft WHERE task_id = #{taskId}")
    int findMaxRound(@Param("taskId") Long taskId);

    /** 当前仍有效（未软删）的最大轮次；0 = 没有当前轮（读侧列表 / 存在性判断用）。 */
    @Select("SELECT COALESCE(MAX(round), 0) FROM task_plan_draft "
            + "WHERE task_id = #{taskId} AND deleted = 0")
    int findMaxActiveRound(@Param("taskId") Long taskId);

    /**
     * 某次操作事件已落几行（**含已软删**）。
     *
     * <p>完整性校验（`TD-16e` 的 `expected_count` 判据）要按「落了几条」而不是「还剩几条」
     * 算：若中途整枝软删过，只数 {@code deleted=0} 会把已完成判成残缺。</p>
     */
    @Select("SELECT count(*) FROM task_plan_draft WHERE batch_key = #{batchKey}")
    long countByBatchKeyIncludeDeleted(@Param("batchKey") String batchKey);

    /** 某次操作事件**当前有效**的行数（对外读侧用）。 */
    @Select("SELECT count(*) FROM task_plan_draft "
            + "WHERE batch_key = #{batchKey} AND deleted = 0")
    long countByBatchKey(@Param("batchKey") String batchKey);

    /** 同任务内 code 是否已被占用（软删行不算占用，与唯一索引条件对齐）。 */
    @Select("SELECT count(*) FROM task_plan_draft "
            + "WHERE task_id = #{taskId} AND code = #{code} AND deleted = 0")
    long countByCode(@Param("taskId") Long taskId, @Param("code") String code);

    /**
     * 任务内已用过的**最大根段序号**（`0` = 还没占过号）。
     *
     * <p><b>为什么要它</b>：`code` 的根段是「任务内位置」，设计 §3.1 定死「跨轮**不重置**、
     * 延续全局 `seq`」。若每轮都从 `0001` 起，第二轮要么撞 `uk_plan_draft_code`，
     * 要么与第一轮的 `DISCARDED` 行同号、历史轮次无法区分。</p>
     *
     * <p><b>为什么取整表而不是「当前轮」</b>：跨轮不重置 ⇒ 必须看历史全部行（含软删）。
     * `code` 形如 `<yyyyMMdd 8 位><seq 4 位>[-...]`，故 `substring(code, 9, 4)` 即序号。</p>
     */
    @Select("SELECT COALESCE(MAX(CAST(substring(code, 9, 4) AS INTEGER)), 0) "
            + "FROM task_plan_draft WHERE task_id = #{taskId}")
    int findMaxRootSeq(@Param("taskId") Long taskId);

    /**
     * 按逻辑位置定位当前 {@code ACTIVE} 行的 id（编辑 / 重生成按 code 定位暂存区行时用）。
     *
     * <p><b>为什么返回 id 而不是实体</b>：本表 4 个 JSONB 字段，自定义 {@code @Select}
     * 返回实体不走 {@code autoResultMap}（见类注释）。这里只返回标量 id，
     * 随后用 {@code BaseMapper#selectById} 拿完整实体（HTML JSONB 正确映射）——
     * 自定义 SQL 只返回标量、实体操作走内置方法，两种能力各归其位。</p>
     *
     * @return 匹配行 id；{@code null} = 暂存区没有该位置的 {@code ACTIVE} 行
     *         （该位置已被作废 / 软删）
     */
    @Select("SELECT id FROM task_plan_draft "
            + "WHERE task_id = #{taskId} AND code = #{code} AND node_state = 'ACTIVE' AND deleted = 0 "
            + "ORDER BY id DESC LIMIT 1")
    Long findIdByCode(@Param("taskId") Long taskId, @Param("code") String code);

    /**
     * 把该任务所有 {@code ACTIVE} 位置作废（拒绝草案 / 整体废除时调用）。
     *
     * <p><b>为什么同时 `deleted = 1`</b>：`uk_plan_draft_code` 是
     * {@code (task_id, code) WHERE deleted = 0} 的部分索引。只改 `node_state` 不软删，
     * 第二轮拆解会顶在唯一索引上——那不是理论风险：2026-11-08 真机 reject 后重拆，
     * 撞索引被上层误判成「幂等命中」，任务永久卡在 `PLANNING`。</p>
     *
     * <p>软删不是丢弃：`node_state = 'DISCARDED'` 保留了「这一轮被作废」的语义，
     * 行仍在库里可追溯；`deleted = 1` 只负责让出位置。</p>
     *
     * @return 被作废的行数（0 = 本来就没有 ACTIVE 行，属正常）
     */
    @Update("UPDATE task_plan_draft SET node_state = 'DISCARDED', deleted = 1, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE task_id = #{taskId} AND node_state = 'ACTIVE' AND deleted = 0")
    int discardActiveByTaskId(@Param("taskId") Long taskId);

    /**
     * 作废**某一次操作事件**写入的行（`TD-16e-2` 残缺批次清理）。
     *
     * <p><b>与 {@link #discardActiveByTaskId} 的区别</b>：那个按任务清「当前所有 ACTIVE」，
     * 用于拒绝草案 / 整轮废除；本方法按 {@code batch_key} 只清「这一次拆解落下的行」。
     * 撞键恢复判定本批不完整时必须用后者——任务内可能还有别的批次的 ACTIVE 行
     * （上一轮残留），按任务清会把它们一并作废，那是另一个 bug。</p>
     *
     * <p><b>为什么不在这里判「不完整」</b>：Mapper 只做一件事。判据在
     * {@code PlannerDecomposeAsyncServiceImpl#describeIncompleteBatch}，
     * 那里才有 {@code expected_count} 与「暂存区未写 ⇒ 退回存在性」的上下文。</p>
     *
     * @return 被作废的行数（0 = 本批没写过暂存区，属正常）
     */
    @Update("UPDATE task_plan_draft SET node_state = 'DISCARDED', deleted = 1, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE batch_key = #{batchKey} AND deleted = 0")
    int discardByBatchKey(@Param("batchKey") String batchKey);

    /**
     * 确认草案：把该任务所有 {@code ACTIVE} 位置标 {@code CONFIRMED} + 软删（确认时调用）。
     *
     * <p><b>为什么同时 `deleted = 1`</b>：见 {@link #discardActiveByTaskId}——
     * {@code uk_plan_draft_code} 是 {@code (task_id, code) WHERE deleted = 0} 的部分索引，
     * 不软删会让下一轮拆解顶在唯一索引上。且设计 §3.1 定死「确认 = 显式一态」：
     * 只软删不标 {@code CONFIRMED}，读侧任何漏过滤 {@code deleted} 的查询会把已转正
     * 草案当待确认再处理一次（设计 §4.5 裁定，2026-11-08）。</p>
     *
     * @return 被标为 CONFIRMED 的行数（0 = 没有 ACTIVE 行，属正常；幂等重试安全）
     */
    @Update("UPDATE task_plan_draft SET node_state = 'CONFIRMED', deleted = 1, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE task_id = #{taskId} AND node_state = 'ACTIVE' AND deleted = 0")
    int confirmActiveByTaskId(@Param("taskId") Long taskId);

    /**
     * 当前轮所有 {@code ACTIVE} 行的 id，按 {@code code} 排序（读侧列表源）。
     *
     * <p><b>为什么返回 id 而不是实体</b>：见 {@link #findIdByCode}——本表 4 个 JSONB
     * 字段，自定义 {@code @Select} 返回实体不走 {@code autoResultMap}。这里只返回排序
     * 好的标量 id，调用方随后用 {@code BaseMapper#selectBatchIds} 一次取回实体
     * （HTML JSONB 正确映射），顺序按本查询的 {@code code} 序对齐。</p>
     *
     * @return 本轮 ACTIVE 行 id（按 code 升序）；无行时为空列表
     */
    @Select("SELECT id FROM task_plan_draft "
            + "WHERE task_id = #{taskId} AND round = #{round} AND node_state = 'ACTIVE' AND deleted = 0 "
            + "ORDER BY code")
    List<Long> listActiveIdsByRound(@Param("taskId") Long taskId, @Param("round") int round);
}
