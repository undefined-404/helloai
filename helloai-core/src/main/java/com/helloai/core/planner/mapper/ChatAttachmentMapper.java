package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.entity.ChatAttachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
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
    @Select(
            "SELECT * FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0 "
                    + "ORDER BY create_time ASC, id ASC")
    List<ChatAttachment> listByConversationId(@Param("conversationId") Long conversationId);

    /**
     * <b>注入专用查询</b>：某会话<b>最新</b>的 {@code limit} 份附件，按<b>上传倒序</b>返回。
     *
     * <p><b>为什么必须新开一个方法、不能改上面的 {@code ORDER BY}（`G-046`）</b>：
     * {@link #listByConversationId} 同时供前端轮询（`ChatAttachmentServiceImpl.list`）与
     * 会话管理，那两处的正确顺序是「按上传先后」——用户看着自己传的第 1、2、3 份文件，
     * 倒序显示会让人以为列表坏了。而注入侧的正确方向恰好相反：注入会被截断到 N 份
     * （{@code AttachmentMediaAssembler.MAX_INLINE_COUNT=5} / {@code DecomposeContextSnapshotRenderer.MAX_ATTACHMENTS=20}），
     * 此时「取谁」的业务语义是<b>用户后传的那几张</b>——用户说「看这张图」指的一定是刚传的，
     * 不是历史里最早那张。同一份数据、两类消费方、两个正确方向 ⇒ 只能分成两条查询。</p>
     *
     * <p><b>为什么不在 Java 侧倒序取后 N 个</b>：那要求先把全会话附件拉进内存（含
     * {@code extracted_text} 大字段），会话附件多时纯属浪费；SQL 层 {@code LIMIT} 才是
     * 真正的「只取需要的那几条」。</p>
     *
     * <p>排序仍是 {@code create_time DESC, id DESC}：{@code id} 兜底是为了同一毫秒批量插入时
     * 顺序稳定（{@code create_time} 精度可能撞车，不稳定排序会让「最新 5 张」在两次调用间
     * 漂移，模型看到的内容随机变化）。</p>
     *
     * @param conversationId 会话 ID
     * @param limit          最多返回条数（调用方保证 &gt; 0）
     * @return 按上传倒序的最新 N 份
     */
    @Select(
            "SELECT * FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0 "
                    + "ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<ChatAttachment> listLatestByConversationId(@Param("conversationId") Long conversationId,
                                                    @Param("limit") int limit);

    /** 某会话累计占用（字节 + 个数），用于会话级限额校验。 */
    @Select(
            "SELECT COALESCE(SUM(file_size), 0) FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0")
    long sumFileSizeByConversationId(@Param("conversationId") Long conversationId);

    /** 窄查询（PA-5）：某用户上传 / 生成的会话附件（conversation 源），按上传顺序。 */
    @Select(
            "SELECT * FROM chat_attachment WHERE uploader_user_id = #{userId} AND deleted = 0 "
                    + "ORDER BY create_time DESC, id ASC LIMIT #{limit}")
    List<ChatAttachment> listByUploaderUserId(@Param("userId") Long userId,
                                              @Param("limit") int limit);

    @Select(
            "SELECT COUNT(*) FROM chat_attachment WHERE conversation_id = #{conversationId} AND deleted = 0")
    int countByConversationId(@Param("conversationId") Long conversationId);

    /** 同会话内按内容哈希查重（存在即复用，不重复解析/嵌入）。 */
    @Select(
            "SELECT * FROM chat_attachment WHERE conversation_id = #{conversationId} AND content_hash = #{contentHash} "
                    + "AND deleted = 0 ORDER BY id ASC LIMIT 1")
    ChatAttachment findByHash(@Param("conversationId") Long conversationId,
                              @Param("contentHash") String contentHash);

    /** 未认领的临时批次清单（新会话首句前上传的附件，按 clientToken 归属）。 */
    @Select(
            "SELECT * FROM chat_attachment WHERE client_token = #{clientToken} AND conversation_id IS NULL "
                    + "AND deleted = 0 ORDER BY create_time ASC, id ASC")
    List<ChatAttachment> listByClientToken(@Param("clientToken") String clientToken);

    /**
     * 存储对账用：全量读取「存储地址 + 字节数」，<b>包含逻辑删除行</b>。
     *
     * <p>为什么不用 {@code BaseMapper.selectList}：内置方法会按逻辑删除列自动追加
     * {@code deleted = 0}，而孤儿判定必须取保守口径——只要还有任意一行（含已逻辑删除）
     * 指向某个对象，该对象就不算孤儿。只读未删除行会让会话/附件删除后尚未回收的对象
     * 被误判成孤儿，进而在清理开关开启时被真删。</p>
     *
     * <p>只选两列：对账不需要正文与解析状态，避免把 {@code extracted_text} 大字段拉进内存。</p>
     */
    @Select("SELECT storage_url, file_size FROM chat_attachment")
    List<ChatAttachment> selectStorageRefAllIncludingDeleted();

    /**
     * <b>跨表回收护栏（窄查询）</b>：在候选 {@code objectKey} 集合中，返回本表仍被活跃行
     * （{@code deleted = 0}）引用的子集。
     *
     * <p>供 task 域回收子任务产物对象前反向确认「会话是否还指着同一个对象」。与
     * {@code AttachmentMapper#selectActiveObjectKeysIn} 对称——两张表共用同一个桶，同
     * {@code objectKey} 双行方案下必须双向护栏，只补一侧等于把误删风险换个方向。</p>
     *
     * <p><b>为什么排除已删行</b>：本方法服务于「能否安全删除字节」，口径与对账
     * ({@link #selectStorageRefAllIncludingDeleted()}) <b>相反</b>。对账要保守（含 {@code deleted=1}
     * 也算引用，宁可漏判孤儿）；护栏要求精确（{@code deleted=1} 的行不再构成有效使用，
     * 否则会话侧删了图、任务侧却永远等不到回收时机，对象滞留）。</p>
     *
     * @param keys 候选 objectKey 集合（用方保证非空、已去重）
     * @return 其中仍存在 {@code deleted=0} 行引用的 objectKey（可能重复）
     */
    @Select({"<script>",
            "SELECT object_key FROM chat_attachment",
            "WHERE deleted = 0 AND object_key IN",
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach>",
            "</script>"})
    List<String> selectActiveObjectKeysIn(@Param("keys") Collection<String> keys);

    /**
     * 领取解析任务：{@code PENDING → PARSING} 的<b>条件更新</b>。
     *
     * <p>返回 1 才算领到。这是解析幂等的唯一闸门：同一附件被重复投递（上传重试、worker 重跑）
     * 时只有第一次能把状态推过去，其余为 0，直接放弃——不会出现两条线程同时写 extracted_text。</p>
     */
    @Update("UPDATE chat_attachment SET parse_status = 'PARSING', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND parse_status = 'PENDING' AND deleted = 0")
    int markParsing(@Param("id") Long id);

    /**
     * 解析成功：落正文与元信息，清空历史错误。同时落 `MA-8` 附件敏感扫描摘要。
     *
     * <p><b>为什么扫描摘要与正文合并到同一条 UPDATE</b>：两者是「这次解析的同一份结果」。
     * 拆成两条写会留下一个可观测的中间态（正文已就位、摘要为空），前端轮询恰好落在中间态
     * 就显示「未扫描」——而实际上它扫过了（判据 4：同一事实不允许双写分叉）。
     * 一次 UPDATE 由数据库保证原子，前端要么看到「未扫描」的历史态，要么看到完整结果。</p>
     *
     * @param scanHitCount 命中条数；{@code null} = 未扫描（扫描开关关闭时如实留空，不写 0）
     * @param scanRules    命中规则 code（逗号分隔）；{@code null} = 未命中或未扫描
     */
    @Update("UPDATE chat_attachment SET parse_status = 'READY', extracted_text = #{text}, "
            + "extracted_chars = #{chars}, page_count = #{pageCount}, parser = #{parser}, "
            + "charset_name = #{charset}, parse_error = NULL, "
            + "sensitive_scan_hit_count = #{scanHitCount}, sensitive_scan_rules = #{scanRules}, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted = 0")
    int markReady(@Param("id") Long id,
                  @Param("text") String text,
                  @Param("chars") Integer chars,
                  @Param("pageCount") Integer pageCount,
                  @Param("parser") String parser,
                  @Param("charset") String charset,
                  @Param("scanHitCount") Integer scanHitCount,
                  @Param("scanRules") String scanRules);

    /** 解析失败（可重试）：只落错误摘要，不覆盖已有正文。 */
    @Update("UPDATE chat_attachment SET parse_status = 'FAILED', parse_error = #{error}, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 0")
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** 跳过（重试无意义）：如 P0 阶段图片 / 音视频无正文可读。 */
    @Update("UPDATE chat_attachment SET parse_status = 'SKIPPED', parse_error = #{reason}, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 0")
    int markSkipped(@Param("id") Long id, @Param("reason") String reason);

    /**
     * `ATT-3`：超预算附件 ingest 到 kb 后回写来源键（形如 {@code chat:{conv}:{att}}）。
     *
     * <p>检索侧靠它反查「命中分片属于哪份附件」并拼引用卡片；空值表示未 ingest
     * （预算内附件走全量注入，不进 kb）。</p>
     */
    @Update("UPDATE chat_attachment SET kb_source = #{source}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted = 0")
    int markKbSource(@Param("id") Long id, @Param("source") String source);

    // ══════════════════════════════════════════════════════════════
    //  `G-041` 硬删三件：物理删行 / 删前捞取回收引用 / 按会话清整片
    // ══════════════════════════════════════════════════════════════

    /**
     * <b>物理删除单行</b>（{@code G-041}）。
     *
     * <p><b>为什么不能继续用 {@code BaseMapper#deleteById}</b>：{@code BaseEntity} 的
     * {@code deleted} 带 {@code @TableLogic}，内置删除会被改写成 {@code UPDATE ... SET deleted=1}。
     * 而 {@code V121} 建表注释原文即「会话附件随会话删除回收」——留 {@code deleted=1} 的行
     * 只会制造两类永久噪声：①{@link #selectStorageRefAllIncludingDeleted()} 把该行算作
     * 「对象仍被引用」，于是对象早已 purge 的这条记录在对账里**永久报 dangling**；
     * ②{@code deleted=1} 的行对 {@link #selectActiveObjectKeysIn} 又不可见，
     * 等于一条既不构成使用、也无法自愈的孤儿行。</p>
     *
     * <p><b>与子任务侧同口径</b>：{@code AttachmentMapper#physicalDeleteByTaskId} 早就是
     * 真 {@code DELETE}（任务删除不保留附件行），会话侧对齐即可，不新造第二套语义。</p>
     *
     * @param id 附件主键
     * @return 实际删除行数（0 = 行已不存在，调用方按幂等处理）
     */
    @Delete("DELETE FROM chat_attachment WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /**
     * <b>物理删除某会话的全部附件行</b>，<b>包含已逻辑删除的残留行</b>（{@code G-041}）。
     *
     * <p><b>为什么连 {@code deleted=1} 一起删</b>：历史单条删除留下过「软删行 + 对象已真删」
     * 的半截状态（见 {@link #physicalDeleteById} 的说明）。会话整体删除是这些残留行
     * 最后的天然归宿——会话都没了，它们的归属已不存在，留着只会让对账继续报 dangling。</p>
     *
     * <p>不加 {@code deleted} 条件<b>是刻意的</b>：加了就漏掉残留行，而残留行正是本方法
     * 要清理的对象之一。</p>
     *
     * @param conversationId 会话 ID（调用方保证非空）
     * @return 实际删除行数
     */
    @Delete("DELETE FROM chat_attachment WHERE conversation_id = #{conversationId}")
    int physicalDeleteByConversationId(@Param("conversationId") Long conversationId);

    /**
     * <b>硬删前的引用捞取</b>：某会话全部附件行的「回收所需字段」，<b>包含逻辑删除行</b>。
     *
     * <p><b>为什么必须与 {@link #physicalDeleteByConversationId} 同口径（都不过滤
     * {@code deleted}）</b>：捞到的行必须**恰好等于**将被物理删除的行。少捞一行，
     * 那一行的对象就永远不会被回收（行已删、再无引用可查，对账也报不出来）。与
     * {@code AttachmentMapper#selectByTaskId}／{@code physicalDeleteByTaskId} 同一条纪律。</p>
     *
     * <p><b>只选回收所需列</b>：会话附件可达 20 份、每份 {@code extracted_text} 上限 200k 字符，
     * `SELECT *` 会在删除路径上白拉几 MB 正文进内存，而这些正文对回收毫无用处。</p>
     *
     * @param conversationId 会话 ID
     * @return 该会话全部附件行（除 id / bucket / objectKey / storageUrl / fileSize 外字段为 null）
     */
    @Select("SELECT id, bucket_name, object_key, storage_url, file_size FROM chat_attachment "
            + "WHERE conversation_id = #{conversationId}")
    List<ChatAttachment> selectPurgeRefsByConversationId(@Param("conversationId") Long conversationId);
}
