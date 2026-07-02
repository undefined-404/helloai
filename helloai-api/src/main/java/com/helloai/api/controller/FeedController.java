package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.FeedResponse;
import com.helloai.api.dto.agent.AgentResponse;
import com.helloai.common.base.R;
import com.helloai.core.entity.ActivityLog;
import com.helloai.core.entity.Agent;
import com.helloai.core.mapper.ActivityLogMapper;
import com.helloai.core.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final ActivityLogMapper activityLogMapper;
    private final AgentMapper agentMapper;

    /**
     * 活动流列表
     */
    @GetMapping
    public R<PageResult<FeedResponse>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "30") int pageSize,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "source", required = false) String source) {
        var wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(level != null && !level.isBlank(), ActivityLog::getLevel, level)
                .eq(source != null && !source.isBlank(), ActivityLog::getSource, source)
                .orderByDesc(ActivityLog::getCreateTime);

        Page<ActivityLog> result = activityLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<FeedResponse> list = result.getRecords().stream().map(log -> {
            FeedResponse resp = new FeedResponse();
            resp.setId(log.getId());
            resp.setAgentId(log.getAgentId());
            resp.setAction(log.getAction());
            resp.setLevel(log.getLevel());
            resp.setSource(log.getSource());
            resp.setCreateTime(log.getCreateTime());
            if (log.getAgentId() != null) {
                Agent agent = agentMapper.selectById(log.getAgentId());
                if (agent != null) {
                    resp.setAgentName(agent.getName());
                }
            }
            return resp;
        }).collect(Collectors.toList());

        PageResult<FeedResponse> pr = new PageResult<>();
        pr.setList(list);
        pr.setTotal(result.getTotal());
        pr.setPages(result.getPages());
        pr.setCurrent(result.getCurrent());
        return R.ok(pr);
    }

    /**
     * Agent 摘要信息（供活动流展示）
     */
    @GetMapping("/agents")
    public R<List<AgentResponse>> listAgents() {
        List<Agent> agents = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>()
                        .select(Agent::getId, Agent::getName, Agent::getRole, Agent::getStatus, Agent::getScore)
                        .eq(Agent::getDeleted, 0));
        return R.ok(agents.stream().map(this::toAgentResponse).toList());
    }

    private AgentResponse toAgentResponse(Agent agent) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole());
        response.setStatus(agent.getStatus());
        response.setScore(agent.getScore());
        return response;
    }
}
