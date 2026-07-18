package com.helloai.job.task;

import com.helloai.core.agent.service.AgentDutyLeaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 值班租约到期巡检任务（AgentHub V1 P0-C）。
 *
 * <p>周期扫描 {@code agent_duty_lease} 表中 status=ACTIVE 且 expires_at 已过期的行，
 * 将它们批量翻为 EXPIRED（close_reason=lease_expired）。防止 Agent 崩溃 / 意外掉线时
 * "值班态"永远停留在 ACTIVE 状态，进而影响 {@link com.helloai.core.agent.executor.AgentSelector}
 * 的软优先级判断。</p>
 *
 * <p>保护机制：
 * <ul>
 *   <li>Redis 分布式锁保证多实例安全，同一时刻只有一台节点执行扫描</li>
 *   <li>Lua 脚本安全解锁：仅当锁 value 仍等于本轮 token 时才 DEL，避免误删</li>
 *   <li>batch limit 防止单轮扫描过多阻塞（默认 200）</li>
 *   <li>业务异常不抛出：单条失败只记 warn，不影响同轮其它记录</li>
 * </ul>
 *
 * <p>与 {@link AssignedSubTaskTimeoutTask} 相互独立：本任务只维护"值班态事实源"的一致性，
 * 不重分配子任务；离岗补偿由既有超时巡检自然完成。</p>
 *
 * @see AgentDutyLeaseService#expireLeases
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DutyLeaseExpirationTask {

    private final AgentDutyLeaseService agentDutyLeaseService;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:DutyLeaseExpiration";

    /**
     * 安全释放脚本：仅当 Redis 中锁的 value 仍等于本实例的 token 时才删除，
     * 避免本实例因 scan 超时而被锁过期 → 被其他实例拿到锁 → 本实例 finally
     * 中误删新持有者锁的并发窗口。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 单轮扫描上限（同一轮内最多翻多少条 EXPIRED） */
    private static final int BATCH_LIMIT = 200;

    /** Redis 锁 TTL（秒）：略大于单轮最坏处理时间 */
    private static final long LOCK_TTL_SECONDS = 60;

    /**
     * 30 秒一轮扫描：粒度与 heartbeat 周期匹配，
     * 保证 Agent 主动 renew 之外的到期检测延迟不超过 ~30s。
     */
    @Scheduled(fixedRate = 30_000)
    public void scan() {
        String token = UUID.randomUUID().toString();
        if (!tryLock(token)) {
            return;
        }

        try {
            int expired = agentDutyLeaseService.expireLeases(BATCH_LIMIT);
            if (expired > 0) {
                log.info("值班租约到期巡检完成: 翻为 EXPIRED 的行数={}", expired);
            }
        } catch (Exception e) {
            log.error("DutyLeaseExpirationTask 执行异常", e);
        } finally {
            unlock(token);
        }
    }

    private boolean tryLock(String token) {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock(String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY), token);
        } catch (Exception e) {
            log.warn("释放 Redis 锁失败: lockKey={}, token={}", LOCK_KEY, token, e);
        }
    }
}
