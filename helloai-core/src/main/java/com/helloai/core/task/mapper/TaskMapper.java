package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Task;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
