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
