package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.LlmProviderModel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * LLM Provider 模型 Mapper。
 */
@Mapper
public interface LlmProviderModelMapper extends BaseMapper<LlmProviderModel> {

    /**
     * 物理删除指定 Provider 的全部模型记录（Provider 删除时级联清理用）。
     *
     * <p>不走逻辑删除：Provider 已被删除时其模型配置应一并清除，
     * 避免残留记录让 isModelAvailable 误判已删 Provider 的模型可用。</p>
     */
    @Delete("DELETE FROM llm_provider_model WHERE provider_id = #{providerId}")
    int deletePhysicalByProviderId(@Param("providerId") Long providerId);
}
