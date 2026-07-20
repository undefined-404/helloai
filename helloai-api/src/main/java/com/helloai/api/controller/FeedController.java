package com.helloai.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.FeedResponse;
import com.helloai.api.dto.agent.AgentResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.service.FeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 活动流 Feed 入口（{@code /api/feed}）。
 *
 * <p>Mapper 调用、Agent 名称批量解析与 Agent 摘要查询全部下沉至
 * {@link FeedService}；Controller 仅做 ActivityLog → FeedResponse、
 * Agent → AgentResponse 的 DTO 装配。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    /**
     * 活动流列表（按创建时间倒序，可按 level / source 过滤）。
     */
    @GetMapping
    public R<PageResult<FeedResponse>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "30") int pageSize,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "source", required = false) String source) {
        Page<ActivityLog> result = feedService.listActivityLogs(page, pageSize, level, source);
        List<ActivityLog> records = result.getRecords() != null ? result.getRecords() : Collections.emptyList();
        Map<Long, String> nameMap = feedService.resolveAgentNames(records);

        List<FeedResponse> list = records.stream().map(log -> {
            FeedResponse resp = new FeedResponse();
            resp.setId(log.getId());
            resp.setAgentId(log.getAgentId());
            resp.setAction(log.getAction());
            resp.setLevel(log.getLevel());
            resp.setSource(log.getSource());
            resp.setCreateTime(log.getCreateTime());
            if (log.getAgentId() != null) {
                String name = nameMap.get(log.getAgentId());
                if (name != null) {
                    resp.setAgentName(name);
                }
            }
            return resp;
        }).toList();

        PageResult<FeedResponse> pr = new PageResult<>();
        pr.setList(list);
        pr.setTotal(result.getTotal());
        pr.setPages(result.getPages());
        pr.setCurrent(result.getCurrent());
        return R.ok(pr);
    }

    /**
     * Agent 摘要信息（供活动流展示）。
     */
    @GetMapping("/agents")
    public R<List<AgentResponse>> listAgents() {
        List<Agent> agents = feedService.listAgentSummaries();
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