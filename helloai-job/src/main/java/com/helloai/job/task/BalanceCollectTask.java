package com.helloai.job.task;

import com.helloai.common.config.QuotaProperties;
import com.helloai.core.system.quota.BalanceQueryService;
import com.helloai.core.system.quota.QuotaWidgetSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * LLM 额度定时采集（G-026，Q-6 / UX-1）。
 *
 * <p><b>职责两件事</b>：
 * <ol>
 *   <li>调用 {@link BalanceQueryService#overview()} 采集 4 家余额快照 —— 走向与
 *       overview 完全一致（缓存→并行抓→落快照表→回填缓存），因此手动/定时共用同一路径，
 *       不会出现「手动有、定时无」的口径漂移。</li>
 *   <li>清理超过 {@code snapshot-retention-days}（默认 30 天）的历史快照行，
 *       避免 llm_balance_snapshot 无限膨胀。</li>
 * </ol></p>
 *
 * <p><b>节奏服从运营设置</b>：自动刷新间隔取 {@code sys_config["quota.refresh.interval-seconds"]}。
 * 设为 0（仅手动）时完全不抓取，只做清理；设为 N 秒时，最近快照不早于 N 秒前就跳过本轮 ——
 * 否则用户把间隔调到 1 小时，后台仍会每 15 分钟空打一轮外部接口。</p>
 *
 * <p><b>开关</b>：受 {@code helloai.quota.enabled} 控制 —— 快照是外部接口数据，
 * 未配 key 的环境（如 quickstart 默认关）不应每 15 分钟空打一轮。</p>
 *
 * <p><b>ShedLock</b>：15min 单飞（与其他 18 个任务同构：仅用 {@code lockAtMostFor}，
 * 全仓无一处用 {@code lockAtLeastFor}）。抓取走内部 5s 超时 + 熔断双保护，锁租期只需
 * 覆盖正常耗时；{@code lockAtMostFor="PT5M"} 远大于单轮采集（≤ 最慢一家 5s + 清理）。</p>
 *
 * <p><b>失败即返回</b>：单家抓取失败由 {@link BalanceQueryService} 内部消化（返回 failed 态、
 * 不抛出），不会中断本轮其余家；这里只对本任务自身的编排异常做兜底日志。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceCollectTask {

    private final QuotaProperties quotaProperties;
    private final QuotaWidgetSettings quotaWidgetSettings;
    private final BalanceQueryService balanceQueryService;

    @Scheduled(fixedDelayString = "${helloai.quota.collect-interval-ms:900000}")
    @SchedulerLock(name = "balanceCollect", lockAtMostFor = "PT5M")
    public void collect() {
        if (!quotaProperties.isEnabled()) {
            return;
        }
        try {
            // 清理只动本库，和是否抓取无关，先跑
            int purged = balanceQueryService.purgeExpiredSnapshots();
            int intervalSeconds = quotaWidgetSettings.refreshIntervalSeconds();
            if (intervalSeconds == QuotaWidgetSettings.MANUAL_ONLY) {
                log.debug("LLM 额度采集跳过: 仅手动刷新模式, 清理过期快照={}行", purged);
                return;
            }
            if (balanceQueryService.allProvidersCollectedSince(Instant.now().minusSeconds(intervalSeconds))) {
                log.debug("LLM 额度采集跳过: 各家最近快照均不早于 {} 秒前, 清理过期快照={}行", intervalSeconds, purged);
                return;
            }
            int snapshotCount = balanceQueryService.overview().size();
            if (snapshotCount > 0) {
                log.info("LLM 额度采集完成: 采集={}家, 清理过期快照={}行", snapshotCount, purged);
            }
        } catch (Exception e) {
            log.error("BalanceCollectTask 执行异常", e);
        }
    }
}