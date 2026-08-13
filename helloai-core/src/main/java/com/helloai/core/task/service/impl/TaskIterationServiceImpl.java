package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskIterationConst;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.shared.util.SubTaskDependencyOrder;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.TaskIteration;
import com.helloai.core.task.mapper.TaskIterationMapper;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskIterationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务迭代记录服务实现（V42）。
 *
 * <p>在 Planner 整合报告生成成功后一次性回填，不参与运行时执行/审核链路。
 * 回填幂等：先按 task_id 删旧、再批量插新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskIterationServiceImpl extends ServiceImpl<TaskIterationMapper, TaskIteration>
        implements TaskIterationService {

    private final ReviewService reviewService;
    private final AgentService agentService;
    private final SubTaskService subTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void backfillForTask(Long taskId, List<SubTask> sections, Agent plannerAgent) {
        if (sections == null || sections.isEmpty()) {
            log.debug("无子任务可回填: taskId={}", taskId);
            return;
        }

        // 幂等：先删旧记录
        int deleted = baseMapper.deleteByTaskId(taskId);
        log.info("回填前清理旧迭代记录: taskId={}, deleted={}", taskId, deleted);

        // 构建 subTaskId → taskCode 映射（用于 depends_on 展示友好）
        Map<Long, String> idToCode = new HashMap<>(sections.size());
        int idx = 0;
        for (SubTask st : sections) {
            idx++;
            idToCode.put(st.getId(), "#" + idx);
        }

        List<TaskIteration> records = new ArrayList<>(sections.size());
        idx = 0;
        for (SubTask st : sections) {
            idx++;
            TaskIteration iter = new TaskIteration();
            iter.setTaskId(taskId);
            iter.setTaskCode("#" + String.format("%02d", idx));
            iter.setTaskName(st.getTitle());
            iter.setTaskType(TaskIterationConst.TYPE_DEVELOPMENT);
            iter.setRoundNum((st.getReworkCount() != null ? st.getReworkCount() : 0) + 1);
            iter.setCurrentRequirement(st.getContent());

            // depends_on：保持与 sub_task.depends_on 一致（子任务 ID 数组）
            List<Long> deps = st.dependsOnIdList();
            iter.setDependsOn(deps.isEmpty() ? Collections.emptyList() : new ArrayList<>(deps));

            // 审核结果：取最新一条 review_record
            List<ReviewRecord> reviews = reviewService.getBySubTaskId(st.getId());
            if (reviews != null && !reviews.isEmpty()) {
                ReviewRecord latest = reviews.get(reviews.size() - 1);
                iter.setReviewResult(latest.getResult() == ReviewResult.APPROVED
                        ? TaskIterationConst.REVIEW_PASSED : TaskIterationConst.REVIEW_REJECTED);
            }

            // 执行 Agent 名称
            if (st.getAssignedAgentId() != null) {
                Agent agent = agentService.getById(st.getAssignedAgentId());
                if (agent != null) {
                    iter.setExecutorAgent(agent.getName());
                }
            }

            // LLM 产出：统一走 SubTaskOutputExtractor
            String output = SubTaskOutputExtractor.extractExecutionOutput(st);
            iter.setLlmResponse(output != null ? output : "");

            // 执行摘要：从 EXECUTION_RECORD 段解析 SUMMARY 行
            iter.setOutputSummary(extractExecSummary(output));

            // 驳回历史：从 sub_task.context.reviewHistory 提取
            List<Map<String, Object>> reviewHistory = extractReviewHistory(st);
            iter.setRejectionHistory(reviewHistory != null ? reviewHistory : Collections.emptyList());

            // prev_task_result / last_result：首版暂不填充（依赖上下文拼装需额外逻辑）
            iter.setPrevTaskResult(null);
            iter.setLastResult(null);

            records.add(iter);
        }

        // 逐条 save 而非 saveBatch：避免 MyBatis-Plus 生成多行 INSERT 时
        // 把 jsonb 列的 JSON 字符串内联为 varchar 字面量，导致 PostgreSQL 报
        // "column X is of type jsonb but expression is of type character varying"。
        for (TaskIteration record : records) {
            save(record);
        }
        log.info("任务迭代记录回填完成: taskId={}, recordCount={}, plannerAgentId={}",
                taskId, records.size(), plannerAgent != null ? plannerAgent.getId() : null);
    }

    /**
     * 从 sub_task.context 中提取 reviewHistory 数组。
     *
     * <p>兼容新旧格式：优先取 reviewHistory（List），缺失时检查 lastAutoReview（Map）
     * 并包装为单元素数组。</p>
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractReviewHistory(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx == null) {
            return Collections.emptyList();
        }
        Object history = ctx.get("reviewHistory");
        if (history instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(new HashMap<>((Map<String, Object>) m));
                }
            }
            return result;
        }
        // 兼容旧格式 lastAutoReview（V38 前数据）
        Object legacy = ctx.get("lastAutoReview");
        if (legacy instanceof Map<?, ?> legacyMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> lm = (Map<String, Object>) legacyMap;
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("round", 1);
            wrapped.put("comment", lm.getOrDefault("comment", ""));
            wrapped.put("issues", lm.getOrDefault("issues", ""));
            wrapped.put("score", lm.getOrDefault("score", 0));
            return List.of(wrapped);
        }
        return Collections.emptyList();
    }

    @Override
    public int backfillHistory() {
        List<Long> candidateTaskIds = baseMapper.findBackfillCandidateTaskIds();
        if (candidateTaskIds == null || candidateTaskIds.isEmpty()) {
            log.info("[backfillHistory] 无历史任务需要回填");
            return 0;
        }
        log.info("[backfillHistory] 发现 {} 个待回填历史任务: {}", candidateTaskIds.size(), candidateTaskIds);

        int count = 0;
        for (Long taskId : candidateTaskIds) {
            try {
                List<SubTask> sections = collectDoneSubTasks(taskId);
                if (sections.isEmpty()) {
                    log.info("[backfillHistory] 任务 {} 无产出 DONE 子任务，跳过", taskId);
                    continue;
                }
                backfillForTask(taskId, sections, null);
                count++;
                log.info("[backfillHistory] 任务 {} 回填成功 ({}/{})", taskId, count, candidateTaskIds.size());
            } catch (Exception e) {
                log.error("[backfillHistory] 任务 {} 回填失败，继续下一个", taskId, e);
            }
        }
        log.info("[backfillHistory] 完成：共扫描 {} 个候选，成功回填 {} 个", candidateTaskIds.size(), count);
        return count;
    }

    /**
     * 从 LLM 产出末尾的 EXECUTION_RECORD 段中解析 SUMMARY 行。
     *
     * <p>格式约定：产出末尾包含 ## EXECUTION_RECORD 标记，其下 SUMMARY: 行即为执行摘要。
     * 若解析失败或 output 为 null，返回 null。</p>
     *
     * @param output LLM 完整产出（可能为 null）
     * @return 执行摘要文本，或 null
     */
    private static String extractExecSummary(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        int execIdx = output.lastIndexOf("## EXECUTION_RECORD");
        if (execIdx < 0) {
            return null;
        }
        int sumIdx = output.indexOf("SUMMARY:", execIdx);
        if (sumIdx < 0) {
            return null;
        }
        int start = sumIdx + "SUMMARY:".length();
        // 跳过一个空格前缀（有些格式 SUMMARY:后面紧跟空格）
        if (start < output.length() && output.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < output.length()) {
            char c = output.charAt(end);
            // 遇到下一个节标题（## / 全大写节名）或换行后跟空行时视为 SUMMARY 结束
            if (c == '\n') {
                int next = end + 1;
                // 下一个非空行是 ↑KEY / DOWN / DELIV / ## 时，SUMMARY 在此结束
                if (next < output.length()) {
                    int scan = next;
                    while (scan < output.length() && (output.charAt(scan) == ' ' || output.charAt(scan) == '\t')) {
                        scan++;
                    }
                    if (scan < output.length()) {
                        char first = output.charAt(scan);
                        if (first == '\n' || first == '#' || Character.isUpperCase(first)) {
                            break;
                        }
                    }
                }
            }
            end++;
        }
        String summary = output.substring(start, end).trim().replace("\n", " ");
        // 压缩多余空格
        summary = summary.replaceAll("\\s{2,}", " ");
        return summary.isEmpty() ? null : summary;
    }

    /** 收集指定任务下有产出的 DONE 子任务，按依赖拓扑排序。 */
    private List<SubTask> collectDoneSubTasks(Long taskId) {
        List<SubTask> subTasks = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .eq(SubTask::getStatus, SubTaskStatus.DONE)
                .orderByAsc(SubTask::getCreateTime)
                .list();
        if (subTasks == null || subTasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<SubTask> visible = new ArrayList<>();
        for (SubTask st : subTasks) {
            String output = SubTaskOutputExtractor.extractExecutionOutput(st);
            if (output != null && !output.isBlank()) {
                visible.add(st);
            }
        }
        return SubTaskDependencyOrder.orderByDependency(visible);
    }

    @Override
    public List<TaskIteration> listByTaskId(Long taskId) {
        List<TaskIteration> list = lambdaQuery()
                .eq(TaskIteration::getTaskId, taskId)
                .list();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        // 按 task_code 的数字部分排序（"#02" < "#10"，而非字符串序 "#10" < "#2"）
        list.sort((a, b) -> {
            int na = extractTaskCodeNumber(a.getTaskCode());
            int nb = extractTaskCodeNumber(b.getTaskCode());
            return Integer.compare(na, nb);
        });
        return list;
    }

    /** 从 "#01" / "#1" 中提取数字部分，解析失败返回 0。 */
    private static int extractTaskCodeNumber(String code) {
        if (code == null) return 0;
        try {
            return Integer.parseInt(code.replace("#", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
