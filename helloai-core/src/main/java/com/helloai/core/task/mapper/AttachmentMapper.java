package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Attachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
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

    /**
     * <b>窄查询</b>：在给定候选 {@code objectKey} 集合中，返回仍被活跃行（{@code deleted=0}）引用的子集，
     * 供对象回收护栏做批量判定（P2/P3-3 护栏性能收敛，2026-10-07）。
     *
     * <p><b>为何不再全表读</b>：此前的护栏用 {@link #selectAllIncludingDeleted()} 把整张 {@code attachment}
     * 表载入 JVM 再内存过滤——每次删任意一个附件都是 O(全表)，与 V2「可分布式/可扩展」目标冲突。
     * 现改为「入参为候选 key 去重集合（有界）→ 数据库仅回这些 key 中仍活跃者」，<b>1 次查询</b>、
     * 不再全表。语义不变：调用方对候选 key 做 {@code contains} 过滤即可判定「是否仍被活跃行引用」。</p>
     *
     * <p><b>口径</b>：{@code deleted} 列 {@code SMALLINT NOT NULL DEFAULT 0}（V1），故 {@code deleted = 0}
     * 即「活跃」，与旧实现的 {@code getDeleted()==null || ==0} 等价。返回结果<b>可能含重复</b>
     * （同一 key 多条活跃行），调用方须自行去重。</p>
     *
     * @param keys 候选 objectKey 集合（调用方保证非空、已去重）
     * @return 其中仍存在 {@code deleted=0} 行引用的 objectKey（可能重复）
     */
    @Select({"<script>",
            "SELECT object_key FROM attachment",
            "WHERE deleted = 0 AND object_key IN",
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach>",
            "</script>"})
    List<String> selectActiveObjectKeysIn(@Param("keys") Collection<String> keys);
}