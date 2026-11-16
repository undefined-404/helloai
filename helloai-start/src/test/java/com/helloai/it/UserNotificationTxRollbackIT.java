package com.helloai.it;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.system.service.SysConfigService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MSG-3：人侧消息事务语义的真实 DB 证据（设计 §3.6）。
 *
 * <p>钩子挂 {@code recordEvent} 一处，事务语义随写入口分两种命运，mock 无法证明，必须真实 PG：</p>
 * <ul>
 *   <li><b>正常路径 REQUIRED</b>：消息与时间线同事务，业务回滚 ⇒ 消息一并消失（不留半条
 *       「草案好了其实回滚了」的假消息）；</li>
 *   <li><b>失败路径 REQUIRES_NEW</b>：{@code recordEventInNewTx} 独立事务，外层业务回滚 ⇒
 *       消息仍在（事故已发生，不该跟着被抹掉）。</li>
 * </ul>
 *
 * <p><b>ID 段</b>：9802（避开 9801 幂等 IT）；task 带 owner_id 供钩子解析归属；{@code @AfterEach} 清场。</p>
 */
@DisplayName("MSG-3: 人侧消息事务语义（REQUIRED 同生共死 / REQUIRES_NEW 独立存活）")
class UserNotificationTxRollbackIT extends AbstractItTestBase {

    private static final long TASK_ID = 9802L;
    private static final long OWNER_ID = 9802L;

    @Autowired
    private TaskTimelineService taskTimelineService;

    @Autowired
    private SysConfigService sysConfigService;

    @Autowired
    private PlatformTransactionManager txManager;

    private long countByType(String type) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE receiver_id = ? AND type = ?",
                Long.class, OWNER_ID, type);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("正常路径 REQUIRED：业务回滚 ⇒ 消息不留半条")
    void requiredPathRollsBackWithBusiness() {
        sysConfigService.setValue("notification.enabled", "1");
        jdbcTemplate.update("INSERT INTO task (id, title, status, owner_id, create_by, update_by) "
                + "VALUES (?, ?, 'IN_PROGRESS', ?, 'it', 'it')", TASK_ID, "it-task-" + TASK_ID, OWNER_ID);

        new TransactionTemplate(txManager).execute(status -> {
            taskTimelineService.recordEvent(TASK_ID, null, "task_plan_generated", AgentRole.PLANNER, null, Map.of());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, countByType("PLAN_DRAFT_READY"),
                "REQUIRED 路径业务回滚后，人侧消息必须一并消失（不留半条假消息）");
    }

    @Test
    @DisplayName("失败路径 REQUIRES_NEW：外层业务回滚 ⇒ 消息仍存活")
    void requiresNewPathSurvivesBusinessRollback() {
        sysConfigService.setValue("notification.enabled", "1");
        jdbcTemplate.update("INSERT INTO task (id, title, status, owner_id, create_by, update_by) "
                + "VALUES (?, ?, 'IN_PROGRESS', ?, 'it', 'it')", TASK_ID, "it-task-" + TASK_ID, OWNER_ID);

        new TransactionTemplate(txManager).execute(status -> {
            taskTimelineService.recordEventInNewTx(TASK_ID, null, "sub_task_dead_letter", AgentRole.SYSTEM, null, Map.of());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(1, countByType("SUBTASK_DEAD_LETTER"),
                "REQUIRES_NEW 独立事务已提交，外层业务回滚不得抹掉事故消息");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM user_notification WHERE receiver_id = ?", OWNER_ID);
        jdbcTemplate.update("DELETE FROM task_timeline WHERE task_id = ?", TASK_ID);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK_ID);
        sysConfigService.setValue("notification.enabled", "0");
    }
}