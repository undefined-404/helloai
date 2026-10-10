package com.helloai.core.system.backup;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 备份的异步入口（REF-2.3）。
 *
 * <p><b>为什么要单独一个类</b>：{@code @Async} 与 {@code @Transactional} 同理 ——
 * **类内自调用不走代理、注解失效**。若把 {@code @Async} 标在
 * {@link DatabaseBackupService#run(Long)} 上再由同类方法调用，异步不会发生，
 * HTTP 线程会被分钟级的 {@code pg_dump} 阻塞。故抽成本 Bean，
 * 由 Controller 经它跨 Bean 调用（同 {@code PlannerDecomposeAsyncService} 的手法）。</p>
 *
 * <p>线程池用 {@code backupExecutor}（单线程，见 {@code BackupExecutorConfig}）。</p>
 */
@Service
@RequiredArgsConstructor
public class BackupAsyncRunner {

    private final DatabaseBackupService backupService;

    /** 异步执行一次备份；立即返回，进度由调用方轮询台账（/api/backup/{id}）。 */
    @Async("backupExecutor")
    public void runAsync(Long backupId) {
        backupService.run(backupId);
    }
}
