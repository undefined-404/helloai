package com.helloai.job.task;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ASSIGNED 超时未 claim 巡检任务。
 *
 * <p>扫描 status=ASSIGNED 且 update_time 早于阈值的子任务，
 * 将它们回收到 PENDING 并重新进入调度链。填补 """ASSIGNED → 长时间无人 claim → 永远卡死""
 * 的可靠性缺口。</p>
 *
 * <p>保护机制：
 * <ul>
 *   <li>只处理 ASSIGNED，不碰 IN_PROGRESS（由 {@link SubTaskTimeoutTask} 负责）</li>
 *   <li>Redis 分布式锁保证多实例安全</li>
 *   <li>batch limit 防止单轮扫描过多阻塞调度</li>
 *   <li>每条失败只记日志不抛异常，不阻塞同轮其它记录</li>
 * </ul>
 * </p>
 *
 * @see SubTaskDispatchService#redispatchAssignedTimeout
 * @see SubTaskTimeoutTask
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignedSubTaskTimeoutTask {

    private final SubTaskMapper subTaskMapper;
    private final SubTaskDispatchService subTaskDispatchService;
    private final AgentService agentService;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:AssignedSubTaskTimeout";

    /**
     * 安全释放脚本：仅当 Redis 中锁的 value 仍等于本实例的 token 时才删除，
     * 避免本实例因 scan 超时而被锁过期 → 被其他实例拿到锁 → 本实例 finally
     * 中误删新持有者锁的并发窗口。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** ASSIGNED 超时阈值（分钟）：update_time 距今超过此值视为超时 */
    static final long ASSIGNED_TIMEOUT_MINUTES = 10;

    /** 单次扫描最多处理的子任务数 */
    private static final int BATCH_LIMIT = 50;

    /** Redis 锁 TTL（秒）：大于单轮最坏处理时间 */
    private static final long LOCK_TTL_SECONDS = 60;

    @Scheduled(fixedRate = 30_000)
    public void scan() {
        // tryLock 时生成唯一 token；unlock 必须用同一 token，避免误删他人锁
        String token = UUID.randomUUID().toString();
        if (!tryLock(token)) {
            return;
        }

        try {
            OffsetDateTime deadline = OffsetDateTime.now().minusMinutes(ASSIGNED_TIMEOUT_MINUTES);
            List<SubTask> timedOut = subTaskMapper.selectTimedOutAssigned(deadline, BATCH_LIMIT);

            if (timedOut.isEmpty()) {
                return;
            }

            log.info("ASSIGNED超时巡检: 发现 {} 个超时未claim子任务", timedOut.size());

            int recovered = 0;
            int failed = 0;
            for (SubTask subTask : timedOut) {
                try {
                    Long originalAgentId = subTask.getAssignedAgent();
                    Agent originalAgent = originalAgentId != null
                            ? agentService.getById(originalAgentId) : null;
                    AgentRole role = originalAgent != null && originalAgent.getRole() != null
                            ? originalAgent.getRole() : AgentRole.EXECUTOR;

                    subTaskDispatchService.redispatchAssignedTimeout(
                            subTask.getId(), originalAgentId, role);
                    recovered++;
                    log.info("ASSIGNED超时已回收: subTaskId={}, originalAgentId={}",
                            subTask.getId(), originalAgentId);
                } catch (Exception e) {
                    failed++;
                    log.error("ASSIGNED超时回收失败: subTaskId={}", subTask.getId(), e);
                }
            }

            log.info("ASSIGNED超时巡检完成: 扫描={}, 回收={}, 失败={}",
                    timedOut.size(), recovered, failed);

        } catch (Exception e) {
            log.error("AssignedSubTaskTimeoutTask 执行异常", e);
        } finally {
            unlock(token);
        }
    }

    private boolean tryLock(String token) {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock(String token) {
        // Lua 脚本：仅当 Redis 中的 value 等于本实例的 token 时才删除，
        // 避免本实例因超时丢锁后误删新持有者的锁。
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY), token);
        } catch (Exception e) {
            // 释放失败不阻断业务，下次定时任务会重新竞争锁；仅记录
            log.warn("释放 Redis 锁失败: lockKey={}, token={}", LOCK_KEY, token, e);
        }
    }
}
