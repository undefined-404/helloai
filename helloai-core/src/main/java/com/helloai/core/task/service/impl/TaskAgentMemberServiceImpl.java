package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.TaskMemberJoinSource;
import com.helloai.common.constant.TaskMemberStatus;
import com.helloai.core.task.entity.TaskAgentMember;
import com.helloai.core.task.mapper.TaskAgentMemberMapper;
import com.helloai.core.task.service.TaskAgentMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Task-Team 成员服务实现。
 *
 * <p>设计依据：{@code doc/design/HelloAI Task-Team 与附件可见性设计.md} §3。
 * 写入一律走 Mapper 的 {@code ON CONFLICT} 原子 upsert（见 Mapper javadoc：
 * 不产生 duplicate key ⇒ 不污染事务）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAgentMemberServiceImpl
        extends ServiceImpl<TaskAgentMemberMapper, TaskAgentMember>
        implements TaskAgentMemberService {

    /** 系统操作者标识（成员登记由业务链自动触发，非人工操作）。 */
    private static final String OPERATOR = "system";

    @Override
    public boolean isMember(Long taskId, Long agentId) {
        if (taskId == null || agentId == null) {
            return false;
        }
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<TaskAgentMember>()
                .eq(TaskAgentMember::getTaskId, taskId)
                .eq(TaskAgentMember::getAgentId, agentId)
                .eq(TaskAgentMember::getStatus, TaskMemberStatus.ACTIVE));
        return count != null && count > 0;
    }

    @Override
    public boolean isCurrentExecutorOfTask(Long taskId, Long agentId) {
        if (taskId == null || agentId == null) {
            return false;
        }
        return baseMapper.countCurrentExecutor(taskId, agentId) > 0;
    }

    @Override
    public void register(Long taskId, Long agentId, TaskMemberJoinSource source) {
        if (taskId == null || agentId == null) {
            return;
        }
        // 幂等 + 并发安全：单条 ON CONFLICT 原子语句，失败即抛（不吞），由调用方事务决定语义
        baseMapper.upsert(IdWorker.getId(), taskId, agentId, source.name(), OPERATOR);
    }

    @Override
    public List<Long> listActiveAgentIds(Long taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        List<Long> ids = baseMapper.selectActiveAgentIds(taskId);
        return ids != null ? ids : Collections.emptyList();
    }

    /**
     * 实际参与成员明细（任务详情页展示）：按 join_time 升序（先加入的在前）。
     *
     * <p>只查 {@code task_agent_member} 一处 —— 不 JOIN agent 表，避免 task 域
     * 直捅 agent 实体（架构红线）；名字等展示字段由 api 层用
     * {@code AgentService.listProfilesByIds} 补齐。</p>
     */
    @Override
    public List<TaskAgentMember> listMembers(Long taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        List<TaskAgentMember> members = queryMembers(taskId);
        // 读时 derive-on-miss：成员表是权威源（sub_task.assigned_agent_id）的派生快照，
        // 运行期没有入队钩子，只有启动重建。重建后新建的任务在下次重启前会缺行，
        // 详情页因此显示「暂无参与成员」。这里按任务做一次幂等补齐，保留启动重建为兜底。
        if (backfillFromAuthoritative(taskId, members)) {
            members = queryMembers(taskId);
        }
        return members;
    }

    private List<TaskAgentMember> queryMembers(Long taskId) {
        List<TaskAgentMember> members = baseMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAgentMember>lambdaQuery()
                        .eq(TaskAgentMember::getTaskId, taskId)
                        .orderByAsc(TaskAgentMember::getJoinTime));
        return members != null ? members : Collections.emptyList();
    }

    /**
     * 用本任务当前执行者补齐成员表（幂等 upsert，`join_source=REBUILT`）。
     *
     * <p><b>为什么按差集补齐</b>：每次只写「表里没有、权威源有」的成员；
     * 正常情况下差集为空，零写入。写失败只告警，不影响本次展示（鉴权侧另有
     * {@link #isCurrentExecutorOfTask} 兜底，不依赖本次回填成功）。</p>
     *
     * @return 是否发生过补齐（调用方据此决定是否重查）
     */
    private boolean backfillFromAuthoritative(Long taskId, List<TaskAgentMember> members) {
        List<TaskAgentMember> authorities = baseMapper.selectAuthoritativeAssignmentsByTask(taskId);
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        java.util.Set<Long> known = new java.util.HashSet<>();
        for (TaskAgentMember m : members) {
            if (m != null && m.getAgentId() != null) {
                known.add(m.getAgentId());
            }
        }
        int added = 0;
        for (TaskAgentMember a : authorities) {
            if (a == null || a.getAgentId() == null || !known.add(a.getAgentId())) {
                continue;
            }
            try {
                baseMapper.upsert(IdWorker.getId(), taskId, a.getAgentId(),
                        TaskMemberJoinSource.REBUILT.name(), OPERATOR);
                added++;
            } catch (Exception e) {
                log.warn("成员表懒回填失败（不影响展示，下次详情再试）: taskId={}, agentId={}, err={}",
                        taskId, a.getAgentId(), e.getMessage());
            }
        }
        if (added > 0) {
            log.info("成员表懒回填完成: taskId={}, 新增={}", taskId, added);
        }
        return added > 0;
    }

    @Override
    public int rebuildFromAuthoritativeAssignments() {
        List<TaskAgentMember> assignments = baseMapper.selectAuthoritativeAssignments();
        if (assignments == null || assignments.isEmpty()) {
            log.info("Task-Team 成员重建：权威源无「任务→执行者」记录，无需重建");
            return 0;
        }
        int written = 0;
        int failed = 0;
        for (TaskAgentMember pair : assignments) {
            if (pair == null || pair.getTaskId() == null || pair.getAgentId() == null) {
                continue;
            }
            try {
                // 刻意不包大事务：每条 upsert 自身原子且幂等，中途失败下次启动继续补齐
                baseMapper.upsert(IdWorker.getId(), pair.getTaskId(), pair.getAgentId(),
                        TaskMemberJoinSource.REBUILT.name(), OPERATOR);
                written++;
            } catch (Exception e) {
                failed++;
                log.warn("Task-Team 成员重建失败（不影响其他行，下次启动重试）: taskId={}, agentId={}, err={}",
                        pair.getTaskId(), pair.getAgentId(), e.getMessage());
            }
        }
        log.info("Task-Team 成员重建完成：权威源={}, 写入={}, 失败={}", assignments.size(), written, failed);
        return written;
    }
}
