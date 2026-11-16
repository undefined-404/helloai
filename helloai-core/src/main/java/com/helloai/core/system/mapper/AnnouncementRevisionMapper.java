package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.AnnouncementRevision;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告发布快照 Mapper（MSG-6，G-035）。基础 CRUD 继承 {@link BaseMapper}，读取由服务层表达。
 */
@Mapper
public interface AnnouncementRevisionMapper extends BaseMapper<AnnouncementRevision> {
}