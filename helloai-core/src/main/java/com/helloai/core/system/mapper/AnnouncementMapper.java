package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.announcement.SchemaHistoryRow;
import com.helloai.core.system.entity.Announcement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 置顶公告 Mapper（MSG-6，G-035）。
 *
 * <p>基础 CRUD 继承 {@link BaseMapper}，读取由服务层用 {@code LambdaQueryWrapper} 表达，不另建 XML。
 * 所在包 {@code com.helloai.core.system.mapper} 已在启动类 {@code @MapperScan} 内。</p>
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /**
     * 迁移历史只读辅助视图（公告撰写素材，设计 §3.7）：取近期成功迁移，按安装序倒序。
     * 只读 {@code flyway_schema_history}，不参与公告发布。
     *
     * <p>{@code installed_on AT TIME ZONE 'Asia/Shanghai'} 不可省：Flyway 写的是 JVM 本地墙钟
     * 进 {@code timestamp without time zone}，而 JDBC 会话时区是 UTC，直读会把墙钟当 UTC、
     * 展示时再 +8 小时（实际 10:15 的迁移显示成 18:15）。应用展示时区固定为 Asia/Shanghai
     * （{@code spring.jackson.time-zone}），故按该时区解释后再交给实体 {@code OffsetDateTime} 转成真实时刻。</p>
     */
    @Select("""
            SELECT version, description,
                   installed_on AT TIME ZONE 'Asia/Shanghai' AS installed_on,
                   success
            FROM flyway_schema_history
            WHERE success = true
            ORDER BY installed_rank DESC
            LIMIT 50
            """)
    List<SchemaHistoryRow> selectSchemaHistory();
}