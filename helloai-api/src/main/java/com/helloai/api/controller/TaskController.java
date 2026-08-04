package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.task.CreateTaskRequest;
import com.helloai.api.dto.task.TaskFinalReportResponse;
import com.helloai.api.dto.task.TaskRelatedCounts;
import com.helloai.api.dto.task.UpdateTaskStatusRequest;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.planner.PlannerAnalysisService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.TaskDeliverableService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AgentService agentService;
    private final AgentInboxService agentInboxService;
    private final PlannerAnalysisService plannerAnalysisService;
    private final TaskDeliverableService taskDeliverableService;
    private final TaskFinalReportService taskFinalReportService;

    @PostMapping
    public R<Task> create(@Valid @RequestBody CreateTaskRequest req) {
        Task task = taskService.createTask(req.getTitle(), req.getDescription());

        // v1.1 修复: 创建任务后通知所有 PLANNER
        try {
            List<Agent> planners = agentService.listByRole(AgentRole.PLANNER);
            String eventId = "task.create." + task.getId() + "." + System.currentTimeMillis();
            for (Agent planner : planners) {
                agentInboxService.send(planner.getId(), eventId, "task.created",
                        "新任务需要规划: " + req.getTitle(),
                        req.getDescription() != null ? req.getDescription() : "请查看详情",
                        "task", task.getId(), "HIGH");
            }
            log.info("已通知 {} 个 PLANNER Agent", planners.size());
        } catch (Exception e) {
            log.warn("任务创建后通知 PLANNER 失败: taskId={}", task.getId(), e);
        }

        return R.ok(task);
    }

    @GetMapping("/list")
    public R<?> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "status", required = false) String status) {
        TaskStatus taskStatus = (status != null && !status.isBlank()) ? TaskStatus.valueOf(status) : null;
        IPage<Task> result = taskService.pageTasks(taskStatus, page, pageSize);

        // 前端直接使用列表（不传 page 时返回全部）
        if (page == null || page <= 0) {
            return R.ok(result.getRecords());
        }
        return R.ok(PageResult.of(result));
    }

    @GetMapping("/getById/{id}")
    public R<Task> getById(@PathVariable("id") Long id) {
        Task task = taskService.getById(id);
        if (task == null) return R.fail("任务不存在");
        return R.ok(task);
    }

    @PostMapping("/updateStatusById/{id}")
    public R<Task> updateStatus(@PathVariable("id") Long id,
                                 @Valid @RequestBody UpdateTaskStatusRequest req) {
        Task task = taskService.updateStatus(id, TaskStatus.valueOf(req.getStatus()));
        if (task == null) return R.fail("任务不存在");
        return R.ok(task);
    }

    @PutMapping("/updateById/{id}")
    public R<Task> update(@PathVariable("id") Long id, @RequestBody CreateTaskRequest req) {
        Task task = taskService.updateTask(id, req.getTitle(), req.getDescription());
        if (task == null) return R.fail("任务不存在");
        return R.ok(task);
    }

    // ══════════════════════════════════════════════════════════
    //  重新发布（重置 PENDING + 重新通知 PLANNER，不触碰子任务）
    // ══════════════════════════════════════════════════════════

    @PostMapping("/republishById/{id}")
    public R<Task> republish(@PathVariable("id") Long id) {
        return R.ok(taskService.republish(id));
    }

    // ══════════════════════════════════════════════════════
    //  Planner 平台内拆解（草案生成 / 查看 / 确认 / 拒绝，编排全在 core）
    // ══════════════════════════════════════════════════════

    @PostMapping("/planById/{id}")
    public R<List<SubTask>> plan(@PathVariable("id") Long id) {
        return R.ok(plannerAnalysisService.decompose(id));
    }

    @GetMapping("/findPlanByTaskId/{id}")
    public R<List<SubTask>> listPlanDrafts(@PathVariable("id") Long id) {
        return R.ok(plannerAnalysisService.listDrafts(id));
    }

    @PostMapping("/confirmPlanByTaskId/{id}")
    public R<List<SubTask>> confirmPlan(@PathVariable("id") Long id) {
        return R.ok(plannerAnalysisService.confirmPlan(id));
    }

    @PostMapping("/rejectPlanByTaskId/{id}")
    public R<Map<String, Object>> rejectPlan(@PathVariable("id") Long id) {
        int cancelled = plannerAnalysisService.rejectPlan(id);
        return R.ok(Map.of("taskId", id, "cancelledCount", cancelled));
    }

    // ══════════════════════════════════════════════════════════
    //  交付物下载（实时聚合 zip，聚合编排全在 TaskDeliverableService）
    // ══════════════════════════════════════════════════════════

    @GetMapping("/downloadDeliverablesByTaskId/{id}")
    public ResponseEntity<byte[]> downloadDeliverables(@PathVariable("id") Long id) {
        TaskDeliverableService.DeliverablePackage pkg = taskDeliverableService.buildZip(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(pkg.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return new ResponseEntity<>(pkg.content(), headers, HttpStatus.OK);
    }

    // ══════════════════════════════════════════════════════════
    //  最终整合报告（Planner 整合全部子任务产出，编排全在 TaskFinalReportService）
    // ══════════════════════════════════════════════════════════

    @GetMapping("/findFinalReportByTaskId/{id}")
    public R<TaskFinalReportResponse> getFinalReport(@PathVariable("id") Long id) {
        Task task = taskService.getById(id);
        if (task == null) {
            return R.fail(404, "任务不存在: " + id);
        }
        return R.ok(toFinalReportResponse(task));
    }

    @PostMapping("/generateFinalReportByTaskId/{id}")
    public R<TaskFinalReportResponse> generateFinalReport(@PathVariable("id") Long id) {
        return R.ok(toFinalReportResponse(taskFinalReportService.generate(id)));
    }

    private TaskFinalReportResponse toFinalReportResponse(Task task) {
        TaskFinalReportResponse vo = new TaskFinalReportResponse();
        vo.setTaskId(task.getId());
        vo.setContent(task.getFinalReport());
        vo.setAgentId(task.getFinalReportAgentId());
        vo.setGeneratedAt(task.getFinalReportTime());
        vo.setStatus(task.getFinalReportStatus());
        if (task.getFinalReportAgentId() != null) {
            Agent agent = agentService.getById(task.getFinalReportAgentId());
            vo.setAgentName(agent != null ? agent.getName() : null);
        }
        return vo;
    }

    // ══════════════════════════════════════════════════════════
    //  关联数据统计（删除前风险提示）
    // ══════════════════════════════════════════════════════════

    @GetMapping("/listRelatedCountsByTaskId/{id}")
    public R<TaskRelatedCounts> listRelatedCounts(@PathVariable("id") Long id) {
        return R.ok(toRelatedCounts(taskService.getRelatedCounts(id)));
    }

    // ══════════════════════════════════════════════════════════
    //  级联删除（子任务/死信/收件箱未读消息一并物理清理）
    // ══════════════════════════════════════════════════════════

    @DeleteMapping("/deleteById/{id}")
    public R<TaskRelatedCounts> delete(@PathVariable("id") Long id,
                                       @RequestBody Map<String, String> body) {
        String confirmTitle = body.get("confirmTitle");
        if (confirmTitle == null || confirmTitle.isBlank()) {
            return R.fail("请输入任务标题以确认删除");
        }
        return R.ok(toRelatedCounts(taskService.deleteTaskCascade(id, confirmTitle)));
    }

    private TaskRelatedCounts toRelatedCounts(Map<String, Object> counts) {
        TaskRelatedCounts vo = new TaskRelatedCounts();
        vo.setTaskId((Long) counts.get("taskId"));
        vo.setTaskTitle((String) counts.get("taskTitle"));
        vo.setSubTaskCount((Integer) counts.get("subTaskCount"));
        vo.setActiveSubTaskCount((Integer) counts.get("activeSubTaskCount"));
        vo.setDeadLetterCount((Integer) counts.get("deadLetterCount"));
        vo.setModuleCount((Integer) counts.get("moduleCount"));
        vo.setReviewCount((Integer) counts.get("reviewCount"));
        vo.setExecutionCount((Integer) counts.get("executionCount"));
        vo.setUnreadInboxCount((Integer) counts.get("unreadInboxCount"));
        vo.setTimelineCount((Integer) counts.get("timelineCount"));
        return vo;
    }
}
