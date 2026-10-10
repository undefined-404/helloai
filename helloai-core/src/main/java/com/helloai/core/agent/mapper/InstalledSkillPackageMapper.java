package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.InstalledSkillPackage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 已安装技能包 Mapper（REF-1.6）。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD；查询一律由服务层用
 * {@code LambdaQueryWrapper} 表达（ACTIVE 选版 / 按 name 列历史版本 / 内置同名判重），
 * 不另建 XML——JSONB 字段由实体上的 {@code PgJsonbTypeHandler} 处理
 * （{@code autoResultMap = true} 已开启）。</p>
 */
@Mapper
public interface InstalledSkillPackageMapper extends BaseMapper<InstalledSkillPackage> {
}
