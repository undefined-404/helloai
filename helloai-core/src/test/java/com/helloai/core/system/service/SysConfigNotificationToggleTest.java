package com.helloai.core.system.service;

import com.helloai.core.system.service.impl.SysConfigServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * 站内消息 / 置顶公告平台开关取值口径单测（MSG-1/MSG-6，设计 §3.10）。
 *
 * <p>锁定「缺省 OFF、仅 1/true（忽略大小写）ON」——必须镜像 {@code isRegisterEnabled}，
 * <b>不得</b>照抄 {@code QuotaWidgetSettings} 的 {@code !"0"} fail-open（那会让未配置时意外可见）。</p>
 */
@DisplayName("G-035 通知/公告平台开关默认 OFF")
class SysConfigNotificationToggleTest {

    private SysConfigServiceImpl serviceWith(String notificationVal, String announcementVal) {
        SysConfigServiceImpl service = spy(new SysConfigServiceImpl());
        doReturn(notificationVal).when(service).getValue("notification.enabled");
        doReturn(announcementVal).when(service).getValue("announcement.enabled");
        return service;
    }

    @Test
    @DisplayName("键缺省（null）→ 两开关均 OFF")
    void absentKeyDefaultsOff() {
        SysConfigServiceImpl service = serviceWith(null, null);
        assertThat(service.isNotificationEnabled()).isFalse();
        assertThat(service.isAnnouncementEnabled()).isFalse();
    }

    @Test
    @DisplayName("仅 1 / true（忽略大小写）→ ON")
    void onlyOneOrTrueEnables() {
        assertThat(serviceWith("1", "1").isNotificationEnabled()).isTrue();
        assertThat(serviceWith("true", "true").isAnnouncementEnabled()).isTrue();
        assertThat(serviceWith("TRUE", "TRUE").isNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("0 / 空串 / 非法值 → OFF（非 fail-open）")
    void zeroBlankInvalidStayOff() {
        assertThat(serviceWith("0", "0").isNotificationEnabled()).isFalse();
        assertThat(serviceWith("", "").isAnnouncementEnabled()).isFalse();
        assertThat(serviceWith("yes", "yes").isNotificationEnabled()).isFalse();
    }

    @Test
    @DisplayName("两开关独立：可只开消息不开公告")
    void togglesAreIndependent() {
        SysConfigServiceImpl service = serviceWith("1", "0");
        assertThat(service.isNotificationEnabled()).isTrue();
        assertThat(service.isAnnouncementEnabled()).isFalse();
    }
}