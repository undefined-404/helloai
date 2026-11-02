package com.helloai.core.system.quota;

import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 额度浮球的运行期平台设置（G-026 UX-1）。
 *
 * <p><b>为什么落在 sys_config 而非 yml</b>：刷新频率与浮球开关是运营面可调项，
 * 需要「保存即生效、不重启」；且前端浮球与后端定时采集必须共用同一口径，
 * 否则会出现「页面 30s 一跳、后台 15min 一抓」的两套节奏。</p>
 *
 * <p><b>取值口径</b>：{@code quota.refresh.interval-seconds}
 * <ul>
 *   <li>{@code 0}：仅手动刷新（前端不轮询、打开面板不自动抓、定时采集不跑）；</li>
 *   <li>{@code 60~3600}：自动刷新间隔（秒），越界按边界收敛；</li>
 *   <li>空 / 非法：回退默认 {@value #DEFAULT_AUTO_SECONDS}s（与改造前实际节奏一致）。</li>
 * </ul>
 * 自动刷新间隔同时是**自动抓取的最小间隔**：定时采集发现最近快照比该间隔新时跳过，
 * 因此用户把间隔调大后，后台任务不会偷偷把外部请求打回 15 分钟一次。</p>
 */
@Component
@RequiredArgsConstructor
public class QuotaWidgetSettings {

    /** 浮球开关键：'1'=渲染并允许查询，'0'=整组件关闭（不渲染也不发请求）。 */
    public static final String ENABLED_KEY = "quota.widget.enabled";

    /** 自动刷新间隔键（秒）。 */
    public static final String REFRESH_INTERVAL_KEY = "quota.refresh.interval-seconds";

    /** 仅手动刷新。 */
    public static final int MANUAL_ONLY = 0;

    /** 自动刷新的最小时长：1 分钟。 */
    public static final int MIN_AUTO_SECONDS = 60;

    /** 自动刷新的最大时长：1 小时。 */
    public static final int MAX_AUTO_SECONDS = 3600;

    /** 缺省自动刷新间隔：60s（保持改造前的有效抓取节奏）。 */
    public static final int DEFAULT_AUTO_SECONDS = 60;

    private final SysConfigService sysConfigService;

    /** 浮球是否启用；未配置或读取失败 fail-open 为启用（与前端一致）。 */
    public boolean isWidgetEnabled() {
        return !"0".equals(sysConfigService.getValue(ENABLED_KEY));
    }

    /** 自动刷新间隔（秒）；0 表示仅手动刷新。 */
    public int refreshIntervalSeconds() {
        return normalize(sysConfigService.getValue(REFRESH_INTERVAL_KEY));
    }

    /**
     * 归一化配置值：空 / 非法 → 默认；0 → 仅手动；正数按 [60, 3600] 收敛。
     * 纯函数，便于单测锁定口径（不依赖 DB / 时钟）。
     */
    static int normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AUTO_SECONDS;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_AUTO_SECONDS;
        }
        if (value == MANUAL_ONLY) {
            return MANUAL_ONLY;
        }
        if (value < 0) {
            return DEFAULT_AUTO_SECONDS;
        }
        return Math.max(MIN_AUTO_SECONDS, Math.min(MAX_AUTO_SECONDS, value));
    }
}
