package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.base.R;
import com.helloai.api.dto.AdjustScoreRequest;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.RewardLog;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.RewardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/scores")
@RequiredArgsConstructor
public class ScoreController {

    private final RewardService rewardService;
    private final AgentService agentService;

    @GetMapping("/me")
    public R<Map<String, Object>> getMyScore(@RequestParam("agentId") Long agentId) {
        return R.ok(rewardService.getAgentScoreSummary(agentId));
    }

    /**
     * 积分流水明细，分页查询。
     */
    @GetMapping("/logs")
    public R<IPage<RewardLog>> logs(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return R.ok(rewardService.listAllLogs(page, pageSize));
    }

    @GetMapping("/leaderboard")
    public R<List<Map<String, Object>>> leaderboard() {
        List<Agent> agents = agentService.lambdaQuery()
                .orderByDesc(Agent::getScore)
                .list();

        List<Map<String, Object>> result = agents.stream().map(a -> Map.<String, Object>of(
                "agentId", a.getId(),
                "agentName", a.getName(),
                "role", a.getRole().name(),
                "totalScore", a.getScore()
        )).collect(Collectors.toList());

        return R.ok(result);
    }

    @PostMapping("/adjust")
    public R<Void> adjust(@RequestBody AdjustScoreRequest request) {
        String reason = "[手动调整] " + request.getReason();
        rewardService.addReward(request.getAgentId(), reason, request.getScoreDelta(), request.getSubTaskId());
        log.info("积分手动调整: agentId={}, delta={}, reason={}",
                request.getAgentId(), request.getScoreDelta(), request.getReason());
        return R.ok();
    }
}
