package com.helloai.api.controller;

import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.system.NotificationFeatures;
import com.helloai.api.dto.system.NotificationVO;
import com.helloai.api.support.NotificationEventHub;
import com.helloai.common.base.R;
import com.helloai.core.shared.util.CurrentUser;
import com.helloai.core.system.notification.NotificationService;
import com.helloai.core.system.service.SysConfigService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 人侧站内消息读取端点（MSG-5，设计 §3.9）。
 *
 * <p><b>登录即可读，无 {@code @SaCheckPermission}</b>：消息是「我（当前登录用户）的收件箱」，
 * 全局 SaInterceptor 已强制登录；归属过滤恒按 {@link CurrentUser#userId()}，未登录返回 null ⇒
 * 服务层 fail-close 不返回任何数据。镜像 {@code RequirementConversationController.workbenchStream}
 * 的登录可读口径，不另设权限位（避免普通需求方因缺菜单权限而看不到自己的消息）。</p>
 *
 * <p><b>平台开关</b>：{@code notification.enabled} 默认 OFF。OFF 时读取端点返回空 / 未读 0
 * （停发 + 隐藏，设计 §3.10），由服务层收口，控制层不重复判开关。</p>
 */
@RestController
@RequestMapping("/api/user-notification")
@RequiredArgsConstructor
public class UserNotificationController {

    private final NotificationService notificationService;
    private final SysConfigService sysConfigService;
    private final NotificationEventHub notificationEventHub;

    /** 平台开关态（登录可读）：前端铃铛 / 公告条闸门用。 */
    @GetMapping("/features")
    public R<NotificationFeatures> features() {
        return R.ok(new NotificationFeatures(
                sysConfigService.isNotificationEnabled(),
                sysConfigService.isAnnouncementEnabled()));
    }

    /** 我的消息分页（未读优先，可按类型筛）。开关 OFF 返回空页。 */
    @GetMapping("/pageByReceiver")
    public R<PageResult<NotificationVO>> pageByReceiver(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return R.ok(PageResult.of(
                notificationService.pageByReceiver(CurrentUser.userId(), type, page, pageSize),
                NotificationVO::from));
    }

    /**
     * 我的未读数（铃铛角标的消息部分；公告未读由 MSG-6 控制层合并）。开关 OFF 返回 0。
     *
     * <p>刻意返回 {@code Integer}：{@code R<Long>.data} 经泛型擦除按 {@code Object} 序列化，
     * 走不到「非 ID 数值写数字」的例外层（见 {@code JacksonConfig}），会下发 {@code "0"}，
     * 前端 {@code "0" === 0} 判等与相加拼接都会失真。计数不超过 int，此处收口。</p>
     */
    @GetMapping("/unreadCount")
    public R<Integer> unreadCount() {
        return R.ok(Math.toIntExact(notificationService.unreadCount(CurrentUser.userId())));
    }

    /** 标记单条已读（归属校验 + 幂等）。 */
    @PostMapping("/markRead/{id}")
    public R<Void> markRead(@PathVariable("id") Long id) {
        notificationService.markRead(CurrentUser.userId(), id);
        return R.ok();
    }

    /** 全部已读（只影响当前归属人）。 */
    @PostMapping("/markAllRead")
    public R<Void> markAllRead() {
        notificationService.markAllRead(CurrentUser.userId());
        return R.ok();
    }

    /** 软归档单条（归属校验 + 幂等）。 */
    @PostMapping("/archive/{id}")
    public R<Void> archive(@PathVariable("id") Long id) {
        notificationService.archive(CurrentUser.userId(), id);
        return R.ok();
    }

    /** 消息推送 SSE（登录可读）：帧体仅 notificationId，前端收到后重拉未读 / 列表。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        // 反代缓冲关闭：nginx /api/ 通用 location 未配 SSE 参数，靠响应头逐帧透传（审查修复 B1）
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return notificationEventHub.connect(CurrentUser.userId());
    }
}