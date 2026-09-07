package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}