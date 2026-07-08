package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.SubTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SubTaskMapper extends BaseMapper<SubTask> {

    /**
     * 原子认领子任务：仅当 status='PENDING' 且 (assigned_agent IS NULL OR assigned_agent = agentId) 时才更新。
     * 返回 affected rows（1=认领成功，0=竞争失败）。
     */
    int claimAtomic(@Param("subTaskId") Long subTaskId, @Param("agentId") Long agentId);
}
