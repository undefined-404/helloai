package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.AnnouncementSeen;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 公告按版本已读位 Mapper（MSG-6，G-035）。
 *
 * <p><b>为什么用原生 {@code @Insert + ON CONFLICT DO UPDATE} 而非 save()+catch</b>：
 * PostgreSQL 撞唯一键后整个事务进入 aborted(25P02)，同事务捕获补救无效（与
 * {@code UserNotificationMapper.insertIgnoreDuplicate} 同一教训）。已读上报每次都撞
 * {@code (user_id, announcement_id)} 唯一键，故用单条原子 upsert。</p>
 *
 * <p><b>单调递增</b>：{@code GREATEST(现值, 新值)} 保证上报旧版本不回退已读位（设计 §3.7）。
 * id 由调用方传入（自定义 {@code @Insert} 不走 MP 主键填充，服务层用 {@code IdWorker} 生成）。</p>
 */
@Mapper
public interface AnnouncementSeenMapper extends BaseMapper<AnnouncementSeen> {

    /**
     * 幂等上报已读版本：撞 {@code uq_announcement_seen} 即取 {@code GREATEST} 单调递增。
     *
     * @return 影响行数（INSERT 或 UPDATE 均计 1）
     */
    @Insert("""
            INSERT INTO announcement_seen
                (id, user_id, announcement_id, seen_version, seen_at,
                 create_by, update_by, create_time, update_time, deleted)
            VALUES
                (#{id}, #{userId}, #{announcementId}, #{seenVersion}, CURRENT_TIMESTAMP,
                 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            ON CONFLICT (user_id, announcement_id) DO UPDATE
                SET seen_version = GREATEST(announcement_seen.seen_version, EXCLUDED.seen_version),
                    seen_at      = CURRENT_TIMESTAMP,
                    update_time  = CURRENT_TIMESTAMP
            """)
    int upsertSeen(@Param("id") Long id,
                   @Param("userId") Long userId,
                   @Param("announcementId") Long announcementId,
                   @Param("seenVersion") Integer seenVersion);
}