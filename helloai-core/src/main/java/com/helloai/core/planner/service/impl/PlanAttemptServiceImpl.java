package com.helloai.core.planner.service.impl;

import com.helloai.common.constant.PlanAttemptAction;
import com.helloai.common.constant.PlanAttemptStatus;
import com.helloai.core.planner.entity.PlanAttempt;
import com.helloai.core.planner.mapper.PlanAttemptMapper;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link PlanAttemptService} 实现（`TD-16b`）。
 *
 * <p><b>时间源</b>：走 {@link #now()} 而不是各处直接 {@code OffsetDateTime.now()}——
 * 单测覆盖「租约边界」时必须能把时间钉住，否则只能靠 sleep 造场景（慢且不稳）。
 * 与 {@code TaskMapper#findTimedOutDecomposing} 由调用方传 {@code now} 的口径一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanAttemptServiceImpl implements PlanAttemptService {

    private final PlanAttemptMapper planAttemptMapper;
    private final TaskService taskService;

    /** 单测覆盖点：租约边界相关的用例 override 本方法即可固定时间。 */
    protected OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code @Transactional} 加在**本方法**而不是调用方 {@code decompose}：
     * 后者后面还要跨线程 {@code @Async}，整段圈事务会让异步段在提交前读不到
     * {@code PLANNING}。这里只包两次写，边界最窄。</p>
     *
     * <p>跨类调用（{@code PlannerAnalysisServiceImpl → 本方法}）才会走代理，
     * 同类自调用不会——这也是把它放在本类而不是 {@code PlannerAnalysisServiceImpl}
     * 私有方法里的原因之一（另一个原因是它需要同时改 {@code task} 与 {@code task_plan_attempt}，
     * 放哪一侧都会让另一侧反向依赖）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecomposeTicket beginDecompose(Long taskId, int leaseMinutes) {
        String attemptId = taskService.beginDecompose(taskId, leaseMinutes);
        if (attemptId == null) {
            // CAS 落空：任务已被别的请求推进到 PLANNING（或已不在 PENDING）。
            // 回滚是空操作（本方法尚未写第二笔），直接返回让调用方提示。
            return null;
        }
        // 与 task.decompose_lease_until 同一时长、同一 now 基准（毫秒级差异可忽略）：
        // 两处判定各看各的表，但时长必须一致，否则「任务级先扫到」会抢走 attempt 的自愈机会。
        //
        // `G-053`：`begin(...)` 的返回值必须用上——它可能就是「撞键复用的已有行」
        // （batchKey 已登记过），此时 attemptNo 可能是 2/3 而不是 1。把号钉在这里，
        // 异步段才有不可伪造的「我这一代」。
        PlanAttempt attempt = begin(taskId, attemptId, PlanAttemptAction.DECOMPOSE,
                now().plusMinutes(Math.max(1, leaseMinutes)));
        return new DecomposeTicket(attemptId, attempt.getId(), attempt.getAttemptNo());
    }

    @Override
    public PlanAttempt begin(Long taskId, String batchKey, PlanAttemptAction action,
                             OffsetDateTime leaseUntil) {
        // 幂等：同一 batchKey 已登记则直接复用。撞唯一键时不再插——
        // 重复插会让 attempt_no 分叉，「当前有效尝试」就没有唯一答案。
        Optional<PlanAttempt> existing = findLatestByBatchKey(batchKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        PlanAttempt attempt = new PlanAttempt();
        attempt.setTaskId(taskId);
        attempt.setBatchKey(batchKey);
        attempt.setAction(action);
        attempt.setStatus(PlanAttemptStatus.PENDING);
        attempt.setAttemptNo(1);
        attempt.setLeaseUntil(leaseUntil);
        try {
            planAttemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            // 并发登记：另一方先插成功。回读即可，不视为错误。
            PlanAttempt winner = findLatestByBatchKey(batchKey)
                    .orElseThrow(() -> e);
            log.debug("拆解意图已被并发登记，复用已有行: batchKey={}, id={}", batchKey, winner.getId());
            return winner;
        }
        return attempt;
    }

    @Override
    public boolean claim(Long id, Integer attemptNo, String ownerNode, OffsetDateTime leaseUntil) {
        return planAttemptMapper.casClaim(id, attemptNo, ownerNode, leaseUntil) > 0;
    }

    @Override
    public boolean renew(Long id, Integer attemptNo, OffsetDateTime leaseUntil) {
        return planAttemptMapper.casRenew(id, attemptNo, leaseUntil) > 0;
    }

    /**
     * 写段授权（`G-053` L2）。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：加在这里会新开一个短事务，
     * {@code FOR UPDATE} 的锁在方法返回时就被释放，授权窗口退化成「只在方法内有效」——
     * 恰好把要解决的问题原样保留（拿到锁那一刻校验通过，真正写草案时已失效）。
     * 锁必须由**调用方的事务**持有，因此本方法只负责发语句。</p>
     */
    @Override
    public PlanAttempt lockForWrite(Long id) {
        return planAttemptMapper.lockByIdForUpdate(id);
    }

    @Override
    public boolean succeed(Long id, Integer attemptNo, Integer expectedCount, String note) {
        boolean ok = planAttemptMapper.casSucceed(id, attemptNo, expectedCount, note, now()) > 0;
        if (!ok) {
            // 到这里说明本次尝试已被判悬挂重投、或已被别的节点接管。
            // 继续写业务结果会覆盖新尝试的成果 —— 这是「悬挂」要防的那件事。
            log.warn("拆解收尾 CAS 落空（本次尝试已失效，放弃写入业务结果）: attemptId={}, attemptNo={}",
                    id, attemptNo);
        }
        return ok;
    }

    @Override
    public boolean recordExpectedCount(Long id, Integer attemptNo, int expectedCount) {
        // 参数不全 / 0 条都不写：0 条意味着 LLM 没产出草案，主流程随后必然失败，
        // 此时记一个「期望 0 条」会让撞键恢复把「一条都没落」判成完整——方向正好反了。
        if (id == null || attemptNo == null || expectedCount <= 0) {
            return false;
        }
        return planAttemptMapper.recordExpectedCount(id, attemptNo, expectedCount, now()) > 0;
    }

    @Override
    public boolean failRetryable(Long id, Integer attemptNo, OffsetDateTime nextLeaseUntil, String note) {
        return planAttemptMapper.casFailRetryable(id, attemptNo, nextLeaseUntil, note, now()) > 0;
    }

    @Override
    public boolean failTerminal(Long id, Integer attemptNo, String note) {
        return planAttemptMapper.casFailTerminal(id, attemptNo, note, now()) > 0;
    }

    @Override
    public Optional<PlanAttempt> findLatestByBatchKey(String batchKey) {
        return Optional.ofNullable(planAttemptMapper.findLatestByBatchKey(batchKey));
    }

    @Override
    public int reclaimHanging(int limit) {
        OffsetDateTime now = now();
        List<PlanAttempt> hanging = planAttemptMapper.listHanging(now, limit);
        int reclaimed = 0;
        for (PlanAttempt a : hanging) {
            // 转可重试而不是终止：进程可能只是崩了，重来一次大概率能成。
            // 下一租约时刻留给派发器按自己的间隔设置，此处给 now（= 立即可重试）。
            if (planAttemptMapper.casMarkHanging(a.getId(), now, now) > 0) {
                reclaimed++;
                log.warn("拆解尝试判定为悬挂并转可重试: attemptId={}, taskId={}, batchKey={}, 原 owner={}",
                        a.getId(), a.getTaskId(), a.getBatchKey(), a.getOwnerNode());
            }
        }
        return reclaimed;
    }
}
