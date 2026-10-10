package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.PlatformBackup;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台备份台账 Mapper（REF-2.3）。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD；查询一律由服务层用
 * {@code LambdaQueryWrapper} 表达（列表 / 在飞探测 / 保留淘汰候选），不另建 XML。
 * 所在包 {@code com.helloai.core.system.mapper} **已在启动类 {@code @MapperScan} 内**，
 * 故本次无需改动 {@code HelloAIApplication}。</p>
 */
@Mapper
public interface PlatformBackupMapper extends BaseMapper<PlatformBackup> {
}
