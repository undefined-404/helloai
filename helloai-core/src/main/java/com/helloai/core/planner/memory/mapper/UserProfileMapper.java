package com.helloai.core.planner.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.memory.entity.UserProfile;

/**
 * 用户画像 Mapper（PA-2，V132）。
 *
 * <p>经 {@code HelloAIApplication @MapperScan} 的 {@code com.helloai.core.planner.memory.mapper}
 * 扫描自动注册（与 {@link LongTermMemoryMapper} 同包），无需额外配置。</p>
 */
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}