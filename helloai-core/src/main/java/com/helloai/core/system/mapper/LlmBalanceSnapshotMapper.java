package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.LlmBalanceSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部供应商余额快照 Mapper（G-026 / Q-1）。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD；查询一律由服务层用
 * {@code LambdaQueryWrapper} 表达（overview 聚合 / 30 天清理候选），不另建 XML。
 * 所在包 {@code com.helloai.core.system.mapper} **已在启动类 {@code @MapperScan} 内**，
 * 故无需改动 {@code HelloAIApplication}。</p>
 */
@Mapper
public interface LlmBalanceSnapshotMapper extends BaseMapper<LlmBalanceSnapshot> {
}