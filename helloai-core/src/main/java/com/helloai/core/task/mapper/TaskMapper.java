package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Task;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /** 物理删除任务主行（@TableLogic 会把普通 delete 改写为软删，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM task WHERE id = #{taskId}")
    int physicalDeleteById(@Param("taskId") Long taskId);

    /**
     * 列出所有 context 中含 runningSpec 键的任务（**历史 JSONB 数据**迁移用，见 TaskRunningSpecDataMigrator）。
     *
     * <p>PostgreSQL JSONB 有"键存在"操作符 {@code ?}，但 MyBatis/JDBC 驱动会把
     * SQL 文本里的 {@code ?} 当成 prepared-statement 占位符并试图绑定参数，
     * 触发 {@code PSQLException: 设置参数 1 列}。改用 {@code (context -> 'key') IS NOT NULL}
     * 语义等价且避免占位符歧义。</p>
     */
    @Select("SELECT id, title, description, status, context, create_by, update_by, create_time, update_time, deleted, remark " +
            "FROM task WHERE (context -> 'runningSpec') IS NOT NULL AND deleted = 0")
    List<Task> selectWithRunningSpec();

    /**
     * 查询拆解租约已过期的任务（按租约到期升序，limit 上限）。
     *
     * <p><b>判据换过一次，改的是「判活类型」不是「阈值大小」</b>（`V146`）：</p>
     * <ul>
     *   <li><b>改前</b>：{@code update_time < deadline} + {@code NOT EXISTS (草案)}。
     *       这是<b>心跳判活</b>——「多久没动静就当死了」。可拆解线程在调模型的十几分钟里
     *       一次库都不写，它没有能力证明自己活着，于是「跑得比阈值久」必然被判死。
     *       而 {@code NOT EXISTS} 补丁的前提（有草案 = 拆解成功）在「回收发生在草案落库之前」
     *       时不成立，两种结局都错：真跑着被误杀，或者误以为卡死。</li>
     *   <li><b>改后</b>：只扫 {@code decompose_lease_until < now}。租约是<b>进入 PLANNING 时
     *       就写好的约定到期时刻</b>，与 {@code OrderExpireTask} 扫 {@code expire_time}、
     *       {@code TccFreezeTimeoutTask} 扫冻结痕迹同构——<b>超时任务扫痕迹、不扫业务主体状态</b>。</li>
     * </ul>
     *
     * <p><b>「草案在等确认」不再需要本方法关照</b>：那种任务的状态是
     * {@code AWAITING_PLAN_CONFIRM}，SQL 条件 {@code status = 'PLANNING'} 天然排除，
     * {@code NOT EXISTS} 补丁因此可以整体删除（同一事实不留两套判据，判据 4）。</p>
     *
     * <p>{@code decompose_lease_until IS NOT NULL} 是保守条件：历史行若漏补租约，
     * 宁可漏检（交给人工）也不误杀。{@code V146} 已给存量 PLANNING 补值。</p>
     *
     * <p>不选 JSONB 大字段，避免注解式查询绕开 typeHandler 映射。</p>
     *
     * @param now   当前时刻（租约 &lt; now 视为过期）
     * @param limit 单次最多返回条数
     */
    @Select("SELECT t.id, t.title, t.status, t.create_time, t.update_time, t.decompose_lease_until " +
            "FROM task t " +
            "WHERE t.status = 'PLANNING' AND t.deleted = 0 " +
            "AND t.decompose_lease_until IS NOT NULL AND t.decompose_lease_until < #{now} " +
            "ORDER BY t.decompose_lease_until ASC LIMIT #{limit}")
    List<Task> selectTimedOutPlanning(@Param("now") OffsetDateTime now,
                                      @Param("limit") int limit);

    /**
     * 查询「审查链丢失」的最终报告孤儿：{@code final_report_status = REVIEWING}
     * 且 {@code final_report_time} 早于 deadline。
     *
     * <p>用于 {@code FinalReportReviewOrphanTask} 兜底巡检（§12.2 审查链三级容错 L3）：
     * 审查触发链（L1 内存事件 / L2 Outbox→MQ）全部丢失时报告会永久停在 REVIEWING，
     * 本查询提供 DB 侧的事实源兜底。</p>
     *
     * <p>只选轻量列（不选 final_report 大字段），避免注解式查询绕开 typeHandler 映射。</p>
     *
     * @param deadline final_report_time 早于该时刻视为超时
     * @param limit    单轮最多返回条数
     */
    @Select("SELECT t.id, t.title, t.status, t.final_report_status, t.final_report_time, "
            + "t.create_time, t.update_time "
            + "FROM task t "
            + "WHERE t.final_report_status = 'REVIEWING' AND t.final_report_time < #{deadline} "
            + "AND t.deleted = 0 "
            + "ORDER BY t.final_report_time ASC LIMIT #{limit}")
    List<Task> selectStaleFinalReportReviewing(@Param("deadline") OffsetDateTime deadline,
                                               @Param("limit") int limit);

    /**
     * 对账①：任务非终态，但全部「有效子任务」均已终态（`TaskLifecycleReconcileTask`）。
     *
     * <p>「有效子任务」与 `SubTaskCompletionListener#tryCloseTask` 的统计口径逐字一致：
     * 执行行全部到达终态才算可收尾（草案不在执行表，无需再排草案态）。
     * **只扫 `IN_PROGRESS`**：`PLANNING` 可能在重拆（旧子任务批量取消是中间态）；
     * `AWAITING_PLAN_CONFIRM` / `PENDING` 的「子任务全终态」是草案被取消/尚未开始，不是完成。</p>
     *
     * @param limit 单轮最多返回条数
     */
    @Select("SELECT t.id FROM task t "
            + "WHERE t.deleted = 0 AND t.status = 'IN_PROGRESS' "
            + "AND EXISTS (SELECT 1 FROM sub_task st WHERE st.task_id = t.id AND st.deleted = 0 "

            + "AND NOT EXISTS (SELECT 1 FROM sub_task st WHERE st.task_id = t.id AND st.deleted = 0 "
            + "AND st.status NOT IN ('DONE', 'CANCELLED'))) "
            + "ORDER BY t.update_time ASC LIMIT #{limit}")
    List<Long> selectSettledNonTerminalTaskIds(@Param("limit") int limit);

    /**
     * 对账②：派生 Task 已终态，但父 SubTask 仍停在 `AWAITING_DERIVED`。
     *
     * <p>`DONE` 与 `CANCELLED` 都要扫：前者是正常的完成态传播丢失（进程崩在 CAS 与
     * changeStatus 之间），后者是「派生被废除」——按 `ADR-008` §13.4 应把父置
     * `RESTRUCTURING` 转人工，而不是让父永久等待。</p>
     *
     * <p>只选轻量列（不选 `final_report` / `context` 大字段），避免注解式查询绕开 typeHandler 映射。</p>
     *
     * <p><b>必须排除「已被更新派生取代」的旧行</b>（真机抓到）：同一父允许重复派生
     * （§13.3），旧派生被 `CANCELLED` 后、新派生还在跑时，旧行仍满足「终态 + 父等派生」，
     * 对账会把父<b>反复翻回</b> `RESTRUCTURING`，新派生完成后再也传不回 `DONE`。
     * 判据：同父存在 id 更大且未取消的派生 ⇒ 本行已过期，不参与对账。</p>
     *
     * @param limit 单轮最多返回条数
     */
    @Select("SELECT t.id, t.parent_sub_task_id, t.status, t.root_task_id, t.depth, t.update_time "
            + "FROM task t "
            + "JOIN sub_task st ON st.id = t.parent_sub_task_id AND st.deleted = 0 "
            + "WHERE t.deleted = 0 AND t.parent_sub_task_id IS NOT NULL "
            + "AND t.status IN ('DONE', 'CANCELLED') AND st.status = 'AWAITING_DERIVED' "
            + "AND NOT EXISTS (SELECT 1 FROM task newer "
            + "  WHERE newer.parent_sub_task_id = t.parent_sub_task_id "
            + "  AND newer.deleted = 0 AND newer.id > t.id AND newer.status <> 'CANCELLED') "
            + "ORDER BY t.update_time ASC LIMIT #{limit}")
    List<Task> selectTerminalDerivationsAwaitingParent(@Param("limit") int limit);

    /**
     * 对账③：任务已 `DONE`，但整合报告为空且不在生成中 / 不在失败态。
     *
     * <p>排除 `GENERATING`（已有生成在途）与 `FAILED`（失败需人工重试，自动重试会每轮烧额度，
     * 与 `TaskFinalReportServiceImpl` 注释「FAILED 可手动重试」的约定一致）。</p>
     *
     * @param limit 单轮最多返回条数
     */
    @Select("SELECT t.id FROM task t "
            + "WHERE t.deleted = 0 AND t.status = 'DONE' "
            + "AND (t.final_report IS NULL OR btrim(t.final_report) = '') "
            + "AND COALESCE(t.final_report_status, 'NONE') NOT IN ('GENERATING', 'FAILED') "
            + "ORDER BY t.update_time ASC LIMIT #{limit}")
    List<Long> selectDoneTasksMissingFinalReport(@Param("limit") int limit);

    /**
     * 草案期派生轮次（`ADR-008` §19.3 S3）：同一父草案已派生的最大轮次。
     *
     * <p>父锚是 {@code (parent_task_id, parent_draft_code)}；确认物化后
     * {@code parent_draft_code} 被清空（`chk_task_parent_anchor` 禁止双锚共存），
     * 故本查询只服务「确认前的重复派生」——同一条草案合法重派生时轮次递增。</p>
     *
     * @return 最大轮次；无派生行时 0（用方 +1 得到首次轮次）
     */
    @Select("SELECT COALESCE(MAX(derivation_round), 0) FROM task "
            + "WHERE parent_task_id = #{parentTaskId} AND parent_draft_code = #{parentDraftCode} "
            + "AND deleted = 0")
    int selectMaxPlanDerivationRound(@Param("parentTaskId") Long parentTaskId,
                                     @Param("parentDraftCode") String parentDraftCode);
}
