package com.helloai.core.task.member;

import com.helloai.core.task.service.TaskAgentMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Task-Team 成员重建对账（启动一次性，幂等）。
 *
 * <p>设计依据：{@code doc/design/HelloAI Task-Team 与附件可见性设计.md} §8（决策 3：
 * 触发方式取「启动一次性」）。</p>
 *
 * <p><b>为什么需要它</b>：成员表写入走"事件驱动派生"（四类入队入口），
 * 存量任务与"事件丢失 / 崩溃中断写入"两种情况都需要一次权威源对账来补齐自愈。
 * 权威源为 {@code sub_task.assigned_agent_id}（task 域内，不跨域直捅他域表）。</p>
 *
 * <p><b>与 {@code TaskRunningSpecDataMigrator} 的区别</b>：那个是"存量一次性迁移"
 * （迁完即可删）；本 Runner 是<b>长期常驻的自愈机制</b>——可靠幂等，每次启动都跑，
 * 用于兜住任何原因造成的成员表缺行，故不应随版本删除。</p>
 *
 * <p>失败不阻断启动：重建是自愈增强，非启动前置条件；异常仅告警，下次启动重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskAgentMemberRebuildRunner implements ApplicationRunner {

    private final TaskAgentMemberService taskAgentMemberService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            taskAgentMemberService.rebuildFromAuthoritativeAssignments();
        } catch (Exception e) {
            log.warn("Task-Team 成员重建异常（不阻断启动，可下次启动重试）: {}", e.getMessage());
        }
    }
}
