package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.assistant.entity.AssistantProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 助理身份 Mapper（PA-3，V132）。
 *
 * <p>经 {@code HelloAIApplication @MapperScan} 的 {@code com.helloai.core.planner.mapper}
 * 自动扫描注册（assistant 位于 planner 域内，mapper 落 {@code planner.mapper} 才能命中扫描包）。
 * </p>
 */
@Mapper
public interface AssistantProfileMapper extends BaseMapper<AssistantProfile> {
}