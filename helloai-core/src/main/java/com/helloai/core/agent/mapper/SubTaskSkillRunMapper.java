package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.SubTaskSkillRun;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 子任务技能执行台账 Mapper（`MA-3`）。
 *
 * <p><b>只追加、不修改</b>：台账行由 {@code SkillRunLedgerRecorder} 在技能执行的
 * {@code finally} 里写入，业务侧不改不删。</p>
 *
 * <p><b>为什么这里就把两个查询写出来</b>：`MA-3` 交付的是一张新表，
 * 「有表无查询」正是 {@code decision-classes.json} 那个反面教材的形态（只写不读）。
 * 报表与看板属另一批，但「按子任务列最近 N 条」「按任务 + 技能聚合成功率」这两条
 * 是任何后续消费方都绕不开的入口，先固化在这里，避免半年后没人知道这张表怎么查。</p>
 */
@Mapper
public interface SubTaskSkillRunMapper extends BaseMapper<SubTaskSkillRun> {

    /**
     * 按子任务列最近 N 条执行记录（时间倒序）。
     *
     * <p>走 {@code idx_stsr_sub_task_id(sub_task_id, create_time DESC)}；
     * {@code id DESC} 是同一毫秒内的稳定次序（批量写入时 {@code create_time} 可能相同）。</p>
     *
     * @param subTaskId 子任务 ID
     * @param limit     最多返回条数（调用方保证 &gt; 0）
     * @return 记录列表（可能为空，不返回 null）
     */
    @Select("SELECT * FROM sub_task_skill_run WHERE sub_task_id = #{subTaskId} AND deleted = 0 "
            + "ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<SubTaskSkillRun> listRecentBySubTaskId(@Param("subTaskId") Long subTaskId,
                                                @Param("limit") int limit);

    /**
     * 按任务 + 技能聚合成功 / 失败次数（`SK-1` 技能效果报表的数据入口）。
     *
     * <p><b>成功的口径</b>：{@code exit_code = 0} <b>且</b> {@code truncated = 0}——
     * 输出被截断时调用方拿到的不是完整结果，与 {@code ExecuteResponse.succeeded()} 同口径。
     * 超时（124）与 SDK 异常（-1）一律计入失败。</p>
     *
     * @param taskId 任务 ID（{@code sub_task_skill_run.task_id} 冗余列）
     * @return 每个技能一行（可能为空，不返回 null）
     */
    @Select("SELECT skill_name AS skillName, "
            + "COUNT(*) AS total, "
            + "SUM(CASE WHEN exit_code = 0 AND truncated = 0 THEN 1 ELSE 0 END) AS succeeded, "
            + "SUM(CASE WHEN exit_code = 0 AND truncated = 0 THEN 0 ELSE 1 END) AS failed "
            + "FROM sub_task_skill_run WHERE task_id = #{taskId} AND deleted = 0 "
            + "GROUP BY skill_name ORDER BY skill_name")
    List<SkillRunStat> aggregateByTaskAndSkill(@Param("taskId") Long taskId);

    /** 技能聚合行（只读投影，不落表）。 */
    @Data
    class SkillRunStat {

        /** 技能包名。 */
        private String skillName;

        /** 执行总次数。 */
        private Long total;

        /** 成功次数（{@code exit_code = 0} 且未截断）。 */
        private Long succeeded;

        /** 失败次数（非零退出 / 超时 / SDK 异常 / 输出截断）。 */
        private Long failed;
    }
}
