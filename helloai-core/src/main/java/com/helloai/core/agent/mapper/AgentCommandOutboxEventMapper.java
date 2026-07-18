package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * Phase 2H ②a 引入：
 * {@code agent_command_outbox} 表的 MyBatis-Plus Mapper。
 *
 * <p>本 Mapper 仅承载 {@code agent_command_outbox} 行——执行命令 → MQ 的投递生命周期；
 * 与已有的 {@link AgentOutboxEventMapper}（SubTask 状态变更通知）严格分层，
 * 不共用 Service、不共用实体、不共用表。</p>
 *
 * <p>本轮不做自定义 SQL：单表三态状态机使用 {@link com.baomidou.mybatisplus.extension.service.IService}
 * 内置的 {@code insert / updateById / lambdaUpdate} 已足够；
 * 后续 ②b 引入 publisher-confirms 状态机扩展（如 CONFIRMED）时再视情况补自定义 SQL。</p>
 */
@Mapper
public interface AgentCommandOutboxEventMapper extends BaseMapper<AgentCommandOutboxEvent> {
}
