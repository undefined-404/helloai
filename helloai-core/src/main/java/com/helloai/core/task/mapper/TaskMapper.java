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
     * 列出所有 context 中含 runningSpec 键的任务（Phase A 数据迁移用）。
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
     * 查询 PLANNING 超时卡死的任务（按 update_time 升序，limit 上限）。
     *
     * <p>只查 status=PLANNING 且 update_time 早于 deadline 的记录（CAS 置 PLANNING 时
     * 字段填充会刷新 update_time，超时从该时刻起算）。用于 PlanningTimeoutTask
     * 兜底回收异步线程丢失（JVM 重启/异常）导致的永久卡 PLANNING 任务；
     * 不选 JSONB 大字段，避免注解式查询绕开 typeHandler 映射。</p>
     *
     * <p>§6.112 超时误伤修复：排除已产出 {@code PENDING_PLAN_REVIEW} 草案的任务——
     * 有草案说明异步拆解已成功、正处于「等待用户确认草案」阶段，update_time 不会
     * 随草案产出刷新（task_plan_generated 只写 sub_task），若仍按原条件回收会把
     * 用户正在确认的草案任务回退 PENDING，导致 confirmDrafts 报「只有 PLANNING
     * 状态才能确认草案」。无草案的 PLANNING 任务才是真卡死，照常回收。</p>
     *
     * @param deadline 超时截止时间（update_time &lt; deadline 视为超时）
     * @param limit    单次最多返回条数
     */
    @Select("SELECT t.id, t.title, t.status, t.create_time, t.update_time " +
            "FROM task t " +
            "WHERE t.status = 'PLANNING' AND t.update_time < #{deadline} AND t.deleted = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM sub_task s " +
            "                WHERE s.task_id = t.id AND s.status = 'PENDING_PLAN_REVIEW' AND s.deleted = 0) " +
            "ORDER BY t.update_time ASC LIMIT #{limit}")
    List<Task> selectTimedOutPlanning(@Param("deadline") OffsetDateTime deadline,
                                      @Param("limit") int limit);
}
