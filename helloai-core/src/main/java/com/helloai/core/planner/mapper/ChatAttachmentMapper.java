package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.entity.ChatAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 会话附件 Mapper（V121）。
 *
 * <p>只放 MyBatis-Plus 内置方法覆盖不了的语句；常规读写走 {@code BaseMapper}。</p>
 */
@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {

    /**
     * 建会认领：把某 clientToken 下的临时附件挂到会话上（幂等，已挂的不会被重复改）。
     *
     * @return 认领条数
     */
    @Update("UPDATE chat_attachment SET conversation_id = #{conversationId}, update_time = CURRENT_TIMESTAMP "
            + "WHERE client_token = #{clientToken} AND conversation_id IS NULL AND deleted = 0")
    int claimByClientToken(@Param("clientToken") String clientToken,
                           @Param("conversationId") Long conversationId);

    /** 按会话列出附件（含解析状态），按上传顺序返回，供清单与前端轮询。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0 "
                    + "ORDER BY create_time ASC, id ASC")
    List<ChatAttachment> listByConversationId(@Param("conversationId") Long conversationId);

    /** 某会话累计占用（字节 + 个数），用于会话级限额校验。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT COALESCE(SUM(file_size), 0) FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0")
    long sumFileSizeByConversationId(@Param("conversationId") Long conversationId);

    @org.apache.ibatis.annotations.Select(
            "SELECT COUNT(*) FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0")
    int countByConversationId(@Param("conversationId") Long conversationId);

    /** 同会话内按内容哈希查重（存在即复用，不重复解析/嵌入）。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM chat_attachment WHERE conversation_id = #{conversationId} AND content_hash = #{contentHash} "
                    + "AND deleted = 0 ORDER BY id ASC LIMIT 1")
    ChatAttachment findByHash(@Param("conversationId") Long conversationId,
                              @Param("contentHash") String contentHash);
}
