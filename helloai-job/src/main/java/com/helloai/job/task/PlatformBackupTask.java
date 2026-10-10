package com.helloai.job.task;

import com.helloai.common.config.BackupProperties;
import com.helloai.common.constant.PlatformBackupType;
import com.helloai.core.system.backup.DatabaseBackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时自动备份（REF-2.3，`D-2026-10-10-2④`）。
 *
 * <p><b>默认关闭</b>（{@code helloai.backup.auto-enabled=false}）：开启前须确认宿主/镜像侧
 * 已具备 {@code pg_dump} —— 前置检查会在 {@code submit} 时显式报错，而不是静默产不出备份。</p>
 *
 * <p><b>同步执行</b>（不用 {@code @Async}）：本任务刻意在**持锁期间**跑完整个备份，
 * 让 ShedLock 的租期覆盖备份全程 —— 配合 {@code DatabaseBackupService} 内的 Redisson 单飞锁
 * 形成双保险。若改成异步立即返回，ShedLock 会在备份仍在跑时就释放，
 * 下一轮 tick 可能叠加（虽有 Redisson 兜底，但租期语义会变得难以推理）。</p>
 *
 * <p>{@code lockAtMostFor} 取 2 小时，与 {@code DatabaseBackupService} 的
 * {@code LOCK_LEASE_SECONDS=7200} 对齐：锁租期必须**大于**最长备份耗时，
 * 否则锁提前过期会让第二个备份并发跑起来。</p>
 *
 * <p>与仓库既有 18 个定时任务同构：位置在 {@code helloai-job}、只用
 * {@code lockAtMostFor}（全仓无一处用 {@code lockAtLeastFor}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformBackupTask {

    private final BackupProperties backupProperties;
    private final DatabaseBackupService backupService;

    @Scheduled(fixedDelayString = "${helloai.backup.auto-interval-ms:21600000}")
    @SchedulerLock(name = "platformBackup", lockAtMostFor = "PT2H")
    public void autoBackup() {
        if (!backupProperties.isAutoEnabled()) {
            return;
        }
        Long backupId;
        try {
            backupId = backupService.submit(PlatformBackupType.AUTO, "system");
        } catch (Exception e) {
            // 前置检查不过（如宿主无 pg_dump）：记日志即可，不产出注定失败的台账行
            log.warn("自动备份前置检查未通过，本轮跳过: {}", e.getMessage());
            return;
        }
        backupService.run(backupId);
    }
}
