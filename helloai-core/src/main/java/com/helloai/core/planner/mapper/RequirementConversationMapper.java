package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.entity.RequirementConversation;
import org.apache.ibatis.annotations.Param;

/**
 * 需求澄清会话 Mapper。
 */
public interface RequirementConversationMapper extends BaseMapper<RequirementConversation> {

    /**
     * 终稿字段定点写入（含 final_package jsonb 显式 {@code ::jsonb} 转换）。
     *
     * <p>专供 {@code RequirementClarifyServiceImpl} 终稿产出轮使用：不走 updateById
     * 全列覆盖，避免陈旧快照覆盖并发写入的 status / task_id 丢失更新；final_package
     * 以 JSON 字符串参数 + SQL 内显式 {@code ::jsonb} 转换（wrapper set 不套用实体
     * JacksonTypeHandler，PostgreSQL jsonb 列拒绝 character varying 隐式转换）。</p>
     *
     * @param finalPackageJson 终稿需求包 JSON 字符串；null = 不动该列
     * @return 1 = 成功写入；0 = 会话不存在或已删除
     */
    int updateFinalDraftFields(@Param("conversationId") Long conversationId,
                               @Param("finalTitle") String finalTitle,
                               @Param("finalDescription") String finalDescription,
                               @Param("finalPackageJson") String finalPackageJson);
}
