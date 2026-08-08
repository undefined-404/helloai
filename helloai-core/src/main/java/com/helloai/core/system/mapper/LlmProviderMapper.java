package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.LlmProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * LlmProvider Mapper。
 *
 * <p>使用 MyBatis-Plus {@link BaseMapper} 即可满足高频查询需求（按 code 查、列全部、
 * 按启用 + 排序查）；复杂连表暂不涉及。Mapper 由 {@code com.helloai.HelloAIApplication}
 * 启动类的 {@code @MapperScan} 显式扫描（com.helloai.core.system.mapper）。</p>
 */
@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProvider> {
}
