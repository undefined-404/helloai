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

    /** 未认领的临时批次清单（新会话首句前上传的附件，按 clientToken 归属）。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM chat_attachment WHERE client_token = #{clientToken} AND conversation_id IS NULL "
                    + "AND deleted = 0 ORDER BY create_time ASC, id ASC")
    List<ChatAttachment> listByClientToken(@Param("clientToken") String clientToken);

    /**
     * 领取解析任务：{@code PENDING → PARSING} 的<b>条件更新</b>。
     *
     * <p>返回 1 才算领到。这是解析幂等的唯一闸门：同一附件被重复投递（上传重试、worker 重跑）
     * 时只有第一次能把状态推过去，其余为 0，直接放弃——不会出现两条线程同时写 extracted_text。</p>
     */
    @Update("UPDATE chat_attachment SET parse_status = 'PARSING', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND parse_status = 'PENDING' AND deleted = 0")
    int markParsing(@Param("id") Long id);

    /** 解析成功：落正文与元信息，清空历史错误。 */
    @Update("UPDATE chat_attachment SET parse_status = 'READY', extracted_text = #{text}, "
            + "extracted_chars = #{chars}, page_count = #{pageCount}, parser = #{parser}, "
            + "charset_name = #{charset}, parse_error = NULL, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted = 0")
    int markReady(@Param("id") Long id,
                  @Param("text") String text,
                  @Param("chars") Integer chars,
                  @Param("pageCount") Integer pageCount,
                  @Param("parser") String parser,
                  @Param("charset") String charset);

    /** 解析失败（可重试）：只落错误摘要，不覆盖已有正文。 */
    @Update("UPDATE chat_attachment SET parse_status = 'FAILED', parse_error = #{error}, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 0")
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** 跳过（重试无意义）：如 P0 阶段图片 / 音视频无正文可读。 */
    @Update("UPDATE chat_attachment SET parse_status = 'SKIPPED', parse_error = #{reason}, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 0")
    int markSkipped(@Param("id") Long id, @Param("reason") String reason);
}
