package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.entity.PatrolRecord;
import com.helloai.core.mapper.PatrolRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 巡查记录基础服务。提供 patrol_record 表的通用查询能力。
 * 复杂统计查询由 {@link AgentService} 通过 Mapper 直接完成。
 */
@Slf4j
@Service
public class PatrolRecordService extends ServiceImpl<PatrolRecordMapper, PatrolRecord> {
}
