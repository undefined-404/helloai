package com.helloai.job.task;

import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.core.system.storage.ArtifactStorageReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存储对账巡检任务：周期核对 DB {@code attachment} 与对象存储桶内真实对象。
 *
 * <p>存在动因：dev 库共享而对象存储历史上各环境一份，{@code attachment} 表又不记录
 * 对象属于哪个存储实例；环境一切换，历史附件集体变悬空指针（DB 有记录、桶中查不到），
 * 业务日志里只会看到零星"读取失败"，拼不出全貌。需要定期把两侧摊开对账。</p>
 *
 * <p>保护机制（与 {@link EventReconciliationTask} 同构）：
 * <ul>
 *   <li>ShedLock 实例级互斥（{@code @SchedulerLock}，Redis 存储锁）保证同一时刻只有一台节点执行</li>
 *   <li>{@code reconcile-enabled} 开关：false 时空转（逃生口）；只读巡检默认开启</li>
 *   <li>破坏性动作（孤儿清理）由 {@code orphan-cleanup-enabled} 单独把关，<b>默认关闭</b></li>
 *   <li>业务异常不抛出：整轮失败只记 error，不影响下一轮调度</li>
 * </ul>
 *
 * <p>6 小时一轮：附件存储漂移是环境/部署级事件（天级），比分钟级的业务对账（事件流）
 * 慢得多；轮询太密只是白白枚举整桶。</p>
 *
 * @see ArtifactStorageReconcileService#reconcile
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtifactStorageReconcileTask {

    private final ArtifactStorageReconcileService artifactStorageReconcileService;
    private final ArtifactStorageProperties storageProperties;

    /** 6 小时一轮（毫秒）。 */
    @Scheduled(fixedRate = 21_600_000)
    @SchedulerLock(name = "artifactStorageReconcile", lockAtMostFor = "PT20M")
    public void scan() {
        if (!storageProperties.isReconcileEnabled()) {
            return;
        }
        try {
            artifactStorageReconcileService.reconcile();
        } catch (Exception e) {
            log.error("ArtifactStorageReconcileTask 执行异常", e);
        }
    }
}
