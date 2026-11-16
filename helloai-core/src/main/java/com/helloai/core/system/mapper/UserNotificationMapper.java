package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.UserNotification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * 人侧站内消息 Mapper（MSG-2，G-035）。
 *
 * <p>基础 CRUD 继承 {@link BaseMapper}，读取由服务层用 {@code LambdaQueryWrapper} 表达，不另建 XML。
 * 所在包 {@code com.helloai.core.system.mapper} 已在启动类 {@code @MapperScan} 内。</p>
 *
 * <p><b>为什么幂等插入用原生 {@code @Insert + ON CONFLICT DO NOTHING} 而非 save()+catch</b>：
 * PostgreSQL 一旦报 duplicate key，<b>整个事务进入 aborted(25P02)</b>，后续语句全部失败——
 * 在同一事务里捕获异常补救无效（与 {@code TaskAgentMemberMapper.upsert} 同一教训）。
 * 人侧消息钩子挂在时间线写点的<b>同一事务</b>（REQUIRED），BUCKET 折叠更是按设计每次都撞键，
 * 故必须用单条原子 {@code ON CONFLICT} 语句，不产生错误、不污染业务事务。</p>
 *
 * <p><b>为什么 id 由调用方传入</b>：自定义 {@code @Insert} 不走 MyBatis-Plus 的 {@code ASSIGN_ID}
 * 主键填充，故服务层用 {@code IdWorker} 生成，与 MP 雪花 ID 同源。</p>
 */
@Mapper
public interface UserNotificationMapper extends BaseMapper<UserNotification> {

    /**
     * 幂等插入：撞 {@code uk_user_notification_dedupe} 即 DO NOTHING（不报错、不污染事务）。
     *
     * <p>ON CONFLICT 仲裁必须与表上实际的<b>部分表达式唯一索引</b>逐字匹配
     * {@code (dedupe_key, COALESCE(receiver_id, 0)) WHERE deleted = 0}，否则 PG 抛
     * "no unique or exclusion constraint matching the ON CONFLICT specification"。</p>
     *
     * @param id      预生成主键（IdWorker 雪花 ID）
     * @param payload 渲染数据（JSONB，强制 PgJsonbTypeHandler，CODE_STYLE §16.3）
     * @return 影响行数：1=新插入，0=幂等命中（已存在）
     */
    @Insert("""
            INSERT INTO user_notification
                (id, receiver_id, type, severity, title, summary, dedupe_key, ref_type, ref_id,
                 payload, is_read, is_archived, create_by, update_by, create_time, update_time, deleted)
            VALUES
                (#{id}, #{receiverId}, #{type}, #{severity}, #{title}, #{summary}, #{dedupeKey},
                 #{refType}, #{refId},
                 #{payload, typeHandler=com.helloai.core.shared.handler.PgJsonbTypeHandler},
                 0, 0, 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            ON CONFLICT (dedupe_key, COALESCE(receiver_id, 0)) WHERE deleted = 0 DO NOTHING
            """)
    int insertIgnoreDuplicate(@Param("id") Long id,
                              @Param("receiverId") Long receiverId,
                              @Param("type") String type,
                              @Param("severity") String severity,
                              @Param("title") String title,
                              @Param("summary") String summary,
                              @Param("dedupeKey") String dedupeKey,
                              @Param("refType") String refType,
                              @Param("refId") Long refId,
                              @Param("payload") Map<String, Object> payload);

    /**
     * BUCKET 折叠：同窗口命中已有行时原子累加 {@code payload.count}（设计 §3.3）。
     *
     * <p>纯 SQL 自增，不读改写，并发安全；摘要文案固定（「多次」），精确计数由 payload.count
     * 承载供前端角标，避免把句子拼进 SQL。</p>
     */
    @Update("""
            UPDATE user_notification
            SET payload = jsonb_set(COALESCE(payload, '{}'::jsonb), '{count}',
                    to_jsonb(COALESCE((payload->>'count')::int, 1) + 1)),
                update_time = CURRENT_TIMESTAMP
            WHERE dedupe_key = #{dedupeKey}
              AND COALESCE(receiver_id, 0) = COALESCE(#{receiverId}, 0)
              AND deleted = 0
            """)
    int incrementBucketCount(@Param("dedupeKey") String dedupeKey,
                             @Param("receiverId") Long receiverId);
}