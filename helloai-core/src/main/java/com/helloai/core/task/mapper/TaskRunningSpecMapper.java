package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.TaskRunningSpecEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * TaskRunningSpec Mapper（Phase B）。
 */
@Mapper
public interface TaskRunningSpecMapper extends BaseMapper<TaskRunningSpecEntity> {

    /** 按 taskId 查询（运行时常按 1:1 用 BaseMapper.getOne 即可，这里留冗余声明）。 */
    @Select("SELECT * FROM task_running_spec WHERE task_id = #{taskId} AND deleted = 0 LIMIT 1")
    TaskRunningSpecEntity selectByTaskId(@Param("taskId") Long taskId);

    /** 重写 ContextSummary（不改变 baseline / version）。 */
    @Update("UPDATE task_running_spec SET context_summary = #{contextSummary}, update_time = CURRENT_TIMESTAMP WHERE task_id = #{taskId} AND deleted = 0")
    int updateContextSummary(@Param("taskId") Long taskId, @Param("contextSummary") String contextSummary);

    /** 重写任务契约（JSONB，契约先行拆解模式；不改变 baseline / version）。 */
    @Update("UPDATE task_running_spec SET contract = #{contract,typeHandler=com.helloai.core.shared.handler.PgJsonbTypeHandler}, update_time = CURRENT_TIMESTAMP WHERE task_id = #{taskId} AND deleted = 0")
    int updateContract(@Param("taskId") Long taskId, @Param("contract") Map<String, Object> contract);

    /** 物理删除某任务的 Spec（任务级联删除时使用）。 */
    @Delete("DELETE FROM task_running_spec WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 取 {@code taskId} 维度的 <b>Postgres advisory 事务锁</b>（跨实例串行化写入）。
     *
     * <p>2026-10-02：{@code task_running_spec} 建行与 {@code task_execution_record}
     * 的 DELETE+INSERT 都是「先查后写」，在并发（多实例）下存在唯一索引冲突窗口；
     * 而 Postgres 一旦报 duplicate key，<b>整个事务进入 aborted 状态</b>，后续语句全部失败
     * （无法靠捕获异常补救）。故改为<b>事前串行化</b>。</p>
     *
     * <p>选 advisory 事务锁而非 Redisson：本临界区完全在数据库事务内，
     * 用 {@code pg_advisory_xact_lock} 可随事务提交/回滚<b>自动释放</b>，
     * 无需 finally 解锁、无 TTL 与看门狗、无锁过期误删他人锁的窗口。</p>
     *
     * <p><b>返回类型必须是 {@code String} 而不是 {@code void}</b>：MyBatis 的
     * {@code @Select} 不支持 void 返回（实测报 {@code No constructor found in void
     * matching [java.lang.String]}），PG 的 void 结果被驱动读成空串。返回值无意义，忽略。</p>
     *
     * @param lockKey 锁键（调用方按业务维度构造，如 {@code String.valueOf(taskId)}）
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    String acquireTaskLock(@Param("lockKey") String lockKey);
}