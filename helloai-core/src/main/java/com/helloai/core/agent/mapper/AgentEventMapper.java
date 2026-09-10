package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.core.agent.entity.AgentEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent 事件轨迹 Mapper（{@code agent_event} 表）。
 *
 * <p>append-only：只插入，不提供业务更新/删除入口（对账删除等运维操作走 SQL 直连）。</p>
 */
@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {

    /**
     * 读取指定子任务事件流的末条事件类型（Phase 0 B3 对账）。
     *
     * <p>事件 append-only 且写入时序单调，{@code create_time + id} 倒序即末条；
     * {@code idx_agent_event_sub_task(sub_task_id, create_time)} 索引支撑等值 + 排序。
     * 显式 SQL 而非 LambdaQueryWrapper：纯单元测试（无 MyBatis 容器）下 lambda 缓存
     * 不可用，同时让对账查询语义更直观。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 末条事件类型（无任何事件时返回 null）
     */
    @Select("SELECT event_type FROM agent_event WHERE sub_task_id = #{subTaskId} "
            + "ORDER BY create_time DESC, id DESC LIMIT 1")
    String selectLastEventTypeBySubTaskId(@Param("subTaskId") Long subTaskId);

    /**
     * 读取指定子任务的执行轨迹事件，按写入时序升序（{@code create_time ASC, id ASC}）。
     *
     * <p>Phase 0 A6 读侧投影：Timeline / Replay 共用的首个消费面。与
     * {@link #selectLastEventTypeBySubTaskId} 不同，这里用 {@code LambdaQueryWrapper}
     * 走实体 resultMap，保证 {@code payload}（JSONB）经 {@code JacksonTypeHandler}
     * 正确反序列化，且 BaseEntity {@code @TableLogic deleted} 自动过滤。</p>
     *
     * @param subTaskId 子任务 ID（不可空，由调用方判空）
     * @return 有序事件列表（无事件时为空列表）
     */
    default List<AgentEvent> selectBySubTaskIdOrdered(Long subTaskId) {
        return selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getSubTaskId, subTaskId)
                .orderByAsc(AgentEvent::getCreateTime)
                .orderByAsc(AgentEvent::getId));
    }

    /**
     * 读取指定 Run 的完整执行轨迹事件，按写入时序升序（{@code create_time ASC, id ASC}）。
     *
     * <p>Phase 0 A7 Replay 读侧：一个 Run（{@code run-{taskId}-{roundNum}}，见 ADR-001）
     * 跨 Turn / Step 全量重建执行轨迹，支撑「一个 Run 可以按 sequence 重建轨迹」
     * 验收（sequence 语义 = 写入时序，ADR-001 §3.2）。等值过滤走
     * {@code idx_agent_event_run(run_id, turn, step)} 索引；排序（createTime/id）由
     * 消费方依赖，与 {@link #selectBySubTaskIdOrdered} 同款 LambdaQueryWrapper 实体
     * 投影保证 {@code payload} JSONB 反序列化。</p>
     *
     * @param runId Run 标识（不可空，由调用方判空）
     * @return 有序事件列表（无事件时为空列表）
     */
    default List<AgentEvent> selectByRunIdOrdered(String runId) {
        return selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getRunId, runId)
                .orderByAsc(AgentEvent::getCreateTime)
                .orderByAsc(AgentEvent::getId));
    }

    /**
     * 按 Task 分页读取事件审计列表，按写入时序正序（{@code create_time ASC, id ASC}）。
     *
     * <p>Phase 0 A7 Audit 读侧：按 task 维度查询执行事实（谁在何时做了什么），
     * 支持可选 {@code eventType} 过滤；纯读、不参与业务状态决策。分页走
     * {@code BaseMapper#selectPage} + {@code LambdaQueryWrapper} 实体投影。</p>
     *
     * @param page      分页参数（页码/页大小由调用方校验）
     * @param taskId    Task ID（不可空，由调用方判空）
     * @param eventType 事件类型过滤（可空/空白 = 不过滤）
     * @return 分页事件结果（含 total / pages 元数据）
     */
    default IPage<AgentEvent> selectPageAuditByTaskId(IPage<AgentEvent> page, Long taskId, String eventType) {
        return selectPage(page, new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getTaskId, taskId)
                .eq(eventType != null && !eventType.isBlank(), AgentEvent::getEventType, eventType)
                .orderByAsc(AgentEvent::getCreateTime)
                .orderByAsc(AgentEvent::getId));
    }
}