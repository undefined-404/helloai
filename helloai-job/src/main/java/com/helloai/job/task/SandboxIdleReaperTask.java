package com.helloai.job.task;

import com.helloai.core.agent.runtime.sandbox.SandboxIdleReaper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 沙箱空闲回收任务（`SL-7`）。
 *
 * <p><b>为什么需要</b>：复用槽池的淘汰只在同键再次访问时发生，任务被放弃 / 会话结束后的槽
 * 再也不会被访问，<b>保活多久就泄漏多久</b>。本任务周期触发 {@link SandboxIdleReaper#reapIdle()}，
 * 把「保活目标」变成寿命上限而不是滞留时长。</p>
 *
 * <p><b>ShedLock 豁免</b>（规范 §21.1/§22）：沙箱槽池是<b>进程内资源</b>，每个实例只持有自己
 * 创建的容器。加 {@code @SchedulerLock} 会让集群里只有一台节点执行，其余实例的槽永远无人回收。
 * 故<b>不加 {@code @SchedulerLock}</b>，与 {@link WatchdogLeaseRenewTask} 同理；
 * 跨实例的重复回收也不存在（各扫各的内存）。</p>
 *
 * <p><b>节奏</b>：固定间隔（默认 60s，{@code helloai.sandbox.reap-interval-ms}）轮询；
 * 用 {@code fixedDelay} 而非 {@code fixedRate}，避免上一轮未结束时叠跑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "helloai.sandbox.opensandbox", name = "enabled", havingValue = "true")
public class SandboxIdleReaperTask {

    /** 沙箱空闲回收端口（实现在 agent 运行时侧）。 */
    private final SandboxIdleReaper sandboxIdleReaper;

    /**
     * 回收一轮空闲沙箱：无 ShedLock，每节点独立执行。
     */
    @Scheduled(fixedDelayString = "${helloai.sandbox.reap-interval-ms:60000}")
    public void reap() {
        try {
            SandboxIdleReaper.ReapReport report = sandboxIdleReaper.reapIdle();
            if (report.reaped() > 0) {
                log.info("沙箱空闲回收: 销毁={}, 在跑跳过={}, 保留={}",
                        report.reaped(), report.busy(), report.kept());
            }
        } catch (Exception e) {
            // 回收失败不抛出：调度线程不能因单轮故障停摆，下一轮自然重试
            log.error("SandboxIdleReaperTask 执行异常", e);
        }
    }
}
