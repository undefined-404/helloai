package com.helloai.core.system.quota;

import com.helloai.core.system.service.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QuotaWidgetSettings} 取值口径单测（G-026 UX-1）。
 *
 * <p>锁定「0=仅手动、60~3600 自动、空/非法回退 60s」三条规则 —— 前后端与定时采集
 * 都依赖该口径，任一处漂移都会让用户设置的刷新频率失效。</p>
 */
@DisplayName("G-026 QuotaWidgetSettings 口径")
class QuotaWidgetSettingsTest {

    @Test
    @DisplayName("空 / 非法值回退默认 60s")
    void blankOrInvalidFallsBackToDefault() {
        assertThat(QuotaWidgetSettings.normalize(null)).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("")).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("  ")).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("abc")).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("-5")).isEqualTo(60);
    }

    @Test
    @DisplayName("0 = 仅手动刷新")
    void zeroMeansManualOnly() {
        assertThat(QuotaWidgetSettings.normalize("0")).isZero();
        assertThat(QuotaWidgetSettings.normalize(" 0 ")).isZero();
    }

    @Test
    @DisplayName("正数按 [60, 3600] 收敛（1 分钟 ~ 1 小时）")
    void positiveClampedToRange() {
        assertThat(QuotaWidgetSettings.normalize("1")).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("30")).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("60")).isEqualTo(60);
        assertThat(QuotaWidgetSettings.normalize("900")).isEqualTo(900);
        assertThat(QuotaWidgetSettings.normalize("3600")).isEqualTo(3600);
        assertThat(QuotaWidgetSettings.normalize("7200")).isEqualTo(3600);
    }

    @Test
    @DisplayName("浮球开关：仅显式 '0' 关闭，未配置 fail-open 为开启")
    void widgetSwitch() {
        SysConfigService sysConfigService = mock(SysConfigService.class);
        QuotaWidgetSettings settings = new QuotaWidgetSettings(sysConfigService);

        when(sysConfigService.getValue(QuotaWidgetSettings.ENABLED_KEY)).thenReturn(null);
        assertThat(settings.isWidgetEnabled()).isTrue();

        when(sysConfigService.getValue(QuotaWidgetSettings.ENABLED_KEY)).thenReturn("1");
        assertThat(settings.isWidgetEnabled()).isTrue();

        when(sysConfigService.getValue(QuotaWidgetSettings.ENABLED_KEY)).thenReturn("0");
        assertThat(settings.isWidgetEnabled()).isFalse();
    }

    @Test
    @DisplayName("refreshIntervalSeconds 读 sys_config 并归一化")
    void readsConfigKey() {
        SysConfigService sysConfigService = mock(SysConfigService.class);
        when(sysConfigService.getValue(QuotaWidgetSettings.REFRESH_INTERVAL_KEY)).thenReturn("1800");

        assertThat(new QuotaWidgetSettings(sysConfigService).refreshIntervalSeconds()).isEqualTo(1800);
    }
}
