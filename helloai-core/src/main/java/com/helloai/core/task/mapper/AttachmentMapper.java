package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Attachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /** 物理删除某任务下全部附件行（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM attachment WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 全表读取附件行，<b>不过滤逻辑删除</b>。
     *
     * <p>仅供存储对账巡检构建"被引用对象"集合：{@code @TableLogic} 会给 MyBatis-Plus
     * 内置查询自动追加 {@code deleted=0}，而对账口径必须保守——只要还有任意一行
     * （含 {@code deleted=1}）指向某对象，该对象就不算孤儿，不得被清理。</p>
     *
     * @return 全部附件行（含已逻辑删除）
     */
    @Select("SELECT * FROM attachment")
    List<Attachment> selectAllIncludingDeleted();

    /**
     * 查询某任务下全部附件行（不过滤逻辑删除），供任务级联删除<b>前</b>捞取对象引用，
     * 以便事务提交后同步回收对象存储（P3-3，2026-10-07）。口径与
     * {@link #physicalDeleteByTaskId(Long)} 一致（同一条 sub_task 子查询），保证
     * 「捞到的行」正是「将被物理删的行」。
     *
     * @param taskId 主任务 ID
     * @return 该任务下全部附件行（含已逻辑删除）
     */
    @Select("SELECT * FROM attachment WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    List<Attachment> selectByTaskId(@Param("taskId") Long taskId);
}