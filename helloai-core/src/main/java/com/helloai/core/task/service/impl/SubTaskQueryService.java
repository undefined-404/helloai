package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskClaimConstraint;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 子任务<b>只读查询与视图投影</b>职责类（RM9-b 切分线 ①「先抽只读簇」）。
 *
 * <p><b>为什么单独成类</b>：{@code SubTaskServiceImpl} 是 RM9 三类上帝类中「状态机唯一入口」，
 * 其查询簇（列表 / 就绪判定 / 统计 / 在飞候选 / 视图投影）与状态迁移<b>零共享状态</b>，
 * 是§9.1「两个以上业务职责」判据下最无争议、也最安全（零行为变更）的一簇。</p>
 *
 * <p><b>职责边界</b>：本类只读（{@code SELECT}）。唯二例外均为「不可下推的读取派生」：</p>
 * <ul>
 *     <li>{@link #getByIdForUpdate(Long)} —— {@code SELECT ... FOR UPDATE} 行锁读，
 *         只发行锁 SQL、不自行开事务（锁随调用方事务释放）；</li>
 *     <li>{@link #requiredSkillsOf(Long)} / {@link #mergeSkills(SubTask)} / {@link #claimConstraint(Long)}
 *         —— 纯读取派生（读 Task 级 {@code required_skills} ∪ 子任务级、读 {@code agent_policy} 白名单），
 *         无任何写入。三者本可留在门面，但 {@link SubTaskNotificationService} 构造 {@code assigned}
 *         摘要时需 {@code mergeSkills}，留在门面会形成「门面 ⇄ 通知」构造器环，
 *         故按 §9.1「只读查询」判据归位本类（RM9-b 微调②，对外契约不变）。</li>
 * </ul>
 *
 * <p><b>依赖方向</b>：本类为<b>叶子</b>，只依赖同域 {@code SubTaskMapper} 与跨域端口
 * （{@code agent.port} 只读投影 / {@code ReviewPort}），不被任何职责类依赖以外的类反向依赖
 * ⇒ 保证「状态机类不得反向依赖查询类」（§4.3.2 验收口径 i）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskQueryService {

    /** 同域 Mapper 直捅（CODE_STYLE §7.1：同域合规）；不继承 ServiceImpl 以保持本类零基类耦合。 */
    private final SubTaskMapper subTaskMapper;
    /** 懒解析打破循环（LOG-20260904-009）：TaskServiceImpl 反链依赖 SubTaskService，直接注入会成环。 */
    private final ObjectProvider<TaskService> taskServiceProvider;
    /** 审查侧读数经 ReviewPort 收口（§6.146），不直捅 review 域。 */
    private final ReviewPort reviewPort;

    // ══════════════════════════════════════════════════════════════
    //  技能 / 认领约束（只读派生：读 Task 级 required_skills + agent_policy）
    // ══════════════════════════════════════════════════════════════

    /**
     * task 域查询出口，供跨域装箱（LOG-20260904-009）。
     *
     * <p>全部异常路径（taskId null / 服务不可用 / 任务不存在 / 字段 null）统一返回空列表，
     * 保证装箱调用方拿到恒非 null 值，消费端无需再判空。</p>
     */
    public List<String> requiredSkillsOf(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        TaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService == null) {
            log.warn("Task 服务不可用，返回空技能标签: taskId={}", taskId);
            return List.of();
        }
        Task task = taskService.getById(taskId);
        List<String> required = task == null ? null : task.getRequiredSkills();
        return required == null ? List.of() : required;
    }

    /**
     * G-010 并集装箱：子任务级（拆解侧指派）在前，任务级声明在后；去重保序。
     * 存量子任务 {@code required_skills} 为空数组时退化为纯任务级，行为零变化。
     */
    public List<String> mergeSkills(SubTask subTask) {
        List<String> merged = new java.util.ArrayList<>();
        if (subTask != null && subTask.getRequiredSkills() != null) {
            for (String skill : subTask.getRequiredSkills()) {
                if (skill != null && !skill.isBlank() && !merged.contains(skill)) {
                    merged.add(skill);
                }
            }
        }
        for (String skill : requiredSkillsOf(subTask == null ? null : subTask.getTaskId())) {
            if (!merged.contains(skill)) {
                merged.add(skill);
            }
        }
        return merged;
    }

    /** 按 id 读取后合并技能标签（{@code null} id 与不存在行均安全）。 */
    public List<String> mergeSkills(Long subTaskId) {
        return mergeSkills(subTaskId == null ? null : subTaskMapper.selectById(subTaskId));
    }

    /**
     * 认领约束组装（<b>复用既有口径，不新造规则</b>）：白名单取任务
     * {@code agent_policy.executorAgentIds}（{@link TaskAgentPolicy#executorAgentIds}，与派发链同源）；
     * 技能取 {@link #mergeSkills(SubTask)}（子任务级 ∪ 任务级，与
     * {@code SubTaskDispatchServiceImpl.resolveConstraints} 同源）。两者由
     * {@link SubTaskClaimConstraint#of} 合并，均空即返回 {@code null}（不约束）。
     *
     * <p><b>只依赖 {@code agent.port} 契约</b>：不 import {@code agent.entity.Agent}；
     * 匹配动作由消费方 agent 域用既有
     * {@code AgentSelector.AgentSelectionConstraints.denialReason} 完成（§7.2 端口反转）。</p>
     */
    public SubTaskClaimConstraint claimConstraint(Long subTaskId) {
        SubTask subTask = subTaskId == null ? null : subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            return null;
        }
        TaskService taskService = taskServiceProvider.getIfAvailable();
        Task task = taskService == null ? null : taskService.getById(subTask.getTaskId());
        List<Long> whitelist = task == null ? List.of()
                : TaskAgentPolicy.executorAgentIds(task.getAgentPolicy());
        List<String> skills = mergeSkills(subTask);
        return SubTaskClaimConstraint.of(whitelist, skills);
    }

    // ══════════════════════════════════════════════════════════════
    //  列表 / 就绪判定
    // ══════════════════════════════════════════════════════════════

    public IPage<SubTask> list(Long taskId, SubTaskStatus status, Long assignedAgentId, Integer page, int pageSize) {
        LambdaQueryWrapper<SubTask> wrapper = new LambdaQueryWrapper<SubTask>()
                .eq(taskId != null, SubTask::getTaskId, taskId)
                .eq(status != null, SubTask::getStatus, status)
                .eq(assignedAgentId != null, SubTask::getAssignedAgentId, assignedAgentId)
                .orderByDesc(SubTask::getCreateTime);

        if (page == null || page <= 0) {
            // 不分页：全量列表包装成 IPage，保持返回类型统一
            List<SubTask> all = subTaskMapper.selectList(wrapper);
            if (all == null) {
                all = Collections.emptyList();
            }
            Page<SubTask> full = new Page<>(1, Math.max(all.size(), 1));
            full.setRecords(all);
            full.setTotal(all.size());
            return full;
        }
        return subTaskMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    public List<SubTask> listAvailable() {
        List<SubTask> pending = subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getStatus, SubTaskStatus.PENDING)
                .orderByDesc(SubTask::getCreateTime));
        return filterReady(pending);
    }

    /**
     * 批量就绪过滤：依赖未全部 DONE 的候选不返回（与 {@link #isReady} 同源口径）。
     *
     * <p>P0-1 依赖门禁：可认领列表此前只按 {@code status=PENDING} 过滤，导致前置尚未产出的
     * 子任务被当作可认领任务抛出，外部 Agent 可抢单并交出「无前置上下文」的脱节产出。
     * 此处一次性取出候选集全部依赖 id 做批量判定，避免逐条 count 造成 N+1。</p>
     */
    private List<SubTask> filterReady(List<SubTask> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> depIds = new HashSet<>();
        for (SubTask candidate : candidates) {
            depIds.addAll(candidate.dependsOnIdList());
        }
        if (depIds.isEmpty()) {
            return candidates;
        }
        Set<Long> doneIds = subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                        .select(SubTask::getId)
                        .in(SubTask::getId, depIds)
                        .eq(SubTask::getStatus, SubTaskStatus.DONE))
                .stream()
                .map(SubTask::getId)
                .collect(Collectors.toSet());
        return candidates.stream()
                .filter(candidate -> doneIds.containsAll(candidate.dependsOnIdList()))
                .toList();
    }

    public List<SubTask> listMine(Long assignedAgentId) {
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, assignedAgentId)
                .orderByDesc(SubTask::getCreateTime));
    }

    /** 事件对账候选源：按 {@code update_time} 倒序截断（update_time 由 MetaObjectHandler 自动填充）。 */
    public List<SubTask> listRecentlyChanged(OffsetDateTime since, int limit) {
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .ge(SubTask::getUpdateTime, since)
                .orderByDesc(SubTask::getUpdateTime)
                .last("LIMIT " + limit));
    }

    /**
     * 行锁读取：{@code SELECT ... FOR UPDATE} 锁定子任务行，用于「读现状 + 紧接写入」原子化路径，
     * 避免同一子任务并发重复发命令。
     *
     * <p><b>必须在调用方事务内调用</b>：本方法只发行锁 SQL，不自行开启事务；
     * 行锁随调用方事务存续而释放（方法返回即释放），自开事务会立刻释放锁，失去互斥意义。
     * 唯一主代码调用方 {@code ExecutionCommandServiceImpl.createAssignedCommand} 已满足该前提。</p>
     */
    public SubTask getByIdForUpdate(Long subTaskId) {
        return subTaskMapper.selectOne(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getId, subTaskId)
                .last("FOR UPDATE"));
    }

    public boolean isReady(SubTask subTask) {
        if (subTask == null) {
            return false;
        }
        List<Long> deps = subTask.dependsOnIdList();
        if (deps.isEmpty()) {
            return true;
        }
        Long doneCount = subTaskMapper.selectCount(new LambdaQueryWrapper<SubTask>()
                .in(SubTask::getId, deps)
                .eq(SubTask::getStatus, SubTaskStatus.DONE));
        return doneCount != null && doneCount >= deps.size();
    }

    // ══════════════════════════════════════════════════════════════
    //  统计（阶段五 agent→task.mapper 清零承接）
    // ══════════════════════════════════════════════════════════════

    public Map<String, Integer> countByStatusForAgent(Long agentId) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("assignedCount", 0);
        m.put("inProgressCount", 0);
        m.put("doneCount", 0);
        m.put("blockedCount", 0);
        m.put("reviewCount", 0);
        if (agentId == null) {
            return m;
        }

        List<SubTask> subs = subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .select(SubTask::getStatus)
                .eq(SubTask::getAssignedAgentId, agentId));
        for (SubTask s : subs) {
            if (s.getStatus() == SubTaskStatus.DONE) {
                m.merge("doneCount", 1, Integer::sum);
            } else if (s.getStatus() == SubTaskStatus.IN_PROGRESS) {
                m.merge("inProgressCount", 1, Integer::sum);
            } else if (s.getStatus() == SubTaskStatus.BLOCKED) {
                m.merge("blockedCount", 1, Integer::sum);
            } else if (s.getStatus() == SubTaskStatus.REVIEW) {
                m.merge("reviewCount", 1, Integer::sum);
            } else {
                m.merge("assignedCount", 1, Integer::sum);
            }
        }
        return m;
    }

    public long countByAssignedAgent(Long agentId) {
        if (agentId == null) {
            return 0;
        }
        Long count = subTaskMapper.selectCount(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId));
        return count == null ? 0 : count;
    }

    public long countReviewByReviewerAgent(Long agentId) {
        if (agentId == null) {
            return 0;
        }
        // §6.146：审查计数经 ReviewPort 收口，不直捅 review 域
        return reviewPort.countByReviewerAgentId(agentId);
    }

    /** 按 taskId 统计排除指定状态的子任务数（planner 重复拆解前置校验用）。 */
    public long countByTaskIdExcludingStatus(Long taskId, SubTaskStatus excluded) {
        Long count = subTaskMapper.selectCount(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, taskId)
                .ne(SubTask::getStatus, excluded));
        return count == null ? 0 : count;
    }

    /** 按 taskId + 状态统计子任务数（planner 拒绝前清理判定用）。 */
    public long countByTaskIdAndStatus(Long taskId, SubTaskStatus status) {
        Long count = subTaskMapper.selectCount(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, taskId)
                .eq(SubTask::getStatus, status));
        return count == null ? 0 : count;
    }

    public List<SubTask> selectInFlightByAgent(Long agentId, int limit) {
        return subTaskMapper.selectInFlightByAgent(agentId, limit);
    }

    public int countInFlightByAgent(Long agentId) {
        return subTaskMapper.countInFlightByAgent(agentId);
    }

    public int countInFlightAll() {
        return subTaskMapper.countInFlightAll();
    }

    public boolean existsInFlightAssignedOrInProgress(Long agentId) {
        Long count = subTaskMapper.selectCount(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId)
                .in(SubTask::getStatus, SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));
        return count != null && count > 0;
    }

    public List<SubTask> listInFlightAssignedOrInProgress(Long agentId) {
        // 无 limit、无排序——与重派范围同口径，加 limit 会截断重派范围
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId)
                .in(SubTask::getStatus, SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));
    }

    public List<Long> listPendingUnassignedWithoutActiveExecutionRecord(int limit) {
        return subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(limit);
    }

    public List<Long> listStalePendingWithoutExecutionRecord(OffsetDateTime cutoff, int limit) {
        return subTaskMapper.selectStalePendingWithoutExecutionRecord(cutoff, limit);
    }

    public List<Long> listStaleBlockedSubTaskIds(OffsetDateTime cutoff, int limit) {
        return subTaskMapper.selectStaleBlockedSubTaskIds(cutoff, limit);
    }

    // ══════════════════════════════════════════════════════════════
    //  只读视图投影（RM5 批 4 / 5a；映射口径统一在 SubTaskProjectionMapper）
    // ══════════════════════════════════════════════════════════════

    public SubTaskView getView(Long subTaskId) {
        return SubTaskProjectionMapper.toView(subTaskMapper.selectById(subTaskId));
    }

    public List<SubTaskView> listViewsByTaskId(Long taskId) {
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getTaskId, taskId)
                        .orderByAsc(SubTask::getCreateTime))
                .stream().map(SubTaskProjectionMapper::toView).toList();
    }

    public List<SubTaskView> listViewsByTaskIdAndStatus(Long taskId, SubTaskStatus status) {
        return list(taskId, status, null, null, 0).getRecords().stream()
                .map(SubTaskProjectionMapper::toView).toList();
    }

    /** 待审草案的只读快照，按 <b>createTime asc + id asc</b>（与批量插入序一致，供依赖序号回写）。 */
    public List<SubTaskView> listDraftsInInsertOrder(Long taskId, SubTaskStatus status) {
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getTaskId, taskId)
                        .eq(SubTask::getStatus, status)
                        .orderByAsc(SubTask::getCreateTime, SubTask::getId))
                .stream().map(SubTaskProjectionMapper::toView).toList();
    }

    /** 按 id 集合查询只读快照（顺序与 {@code selectBatchIds} 一致）。 */
    public List<SubTaskView> listViewsByIds(Collection<Long> ids) {
        return subTaskMapper.selectBatchIds(ids).stream().map(SubTaskProjectionMapper::toView).toList();
    }
}
