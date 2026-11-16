package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.system.AnnouncementDraftRequest;
import com.helloai.api.dto.system.AnnouncementRevisionVO;
import com.helloai.api.dto.system.AnnouncementVO;
import com.helloai.api.dto.system.PublishedAnnouncementVO;
import com.helloai.api.dto.system.SchemaHistoryVO;
import com.helloai.common.base.R;
import com.helloai.core.shared.util.CurrentUser;
import com.helloai.core.system.announcement.AnnouncementService;
import com.helloai.core.system.announcement.PublishedAnnouncement;
import com.helloai.core.system.entity.Announcement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 置顶公告端点（MSG-6，设计 §3.9）。
 *
 * <p><b>读取侧登录即可读</b>（{@code published}/{@code seen}/{@code unreadCount}，无动作码）：
 * 全体登录用户读当前置顶 + 自己的已读位，越权由接口层按归属过滤挡住。开关 OFF 时读取返回空
 * （停发 + 隐藏，设计 §3.10），由服务层收口。</p>
 *
 * <p><b>管理侧走动作码</b>：{@code notice:view}（草稿/历史/迁移辅助视图）、{@code notice:edit}
 * （保存草稿/发布/归档），对齐 {@code AdminConfigController} 的 config:view/config:edit 两段式。
 * 公告写入不受 {@code announcement.enabled} 限制（OFF 只隐藏读取与展示）。</p>
 */
@RestController
@RequestMapping("/api/announcement")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /** 当前置顶公告 + 我的已读位（登录可读）。开关 OFF / 无已发布 → data=null。 */
    @GetMapping("/published")
    public R<PublishedAnnouncementVO> published() {
        PublishedAnnouncement p = announcementService.getPublished(CurrentUser.userId());
        return R.ok(p != null ? PublishedAnnouncementVO.from(p) : null);
    }

    /** 上报已读版本（登录，幂等单调递增）。 */
    @PostMapping("/seen/{id}")
    public R<Void> markSeen(@PathVariable("id") Long id, @RequestParam("version") Integer version) {
        announcementService.markSeen(CurrentUser.userId(), id, version);
        return R.ok();
    }

    /**
     * 公告未读数（登录可读，并入铃铛角标）。开关 OFF → 0。
     *
     * <p>同 {@code UserNotificationController#unreadCount}：返回 {@code Integer} 以避开
     * {@code R<Long>} 被序列化成字符串（{@code JacksonConfig} 的例外层按属性判定，够不到泛型 data）。</p>
     */
    @GetMapping("/unreadCount")
    public R<Integer> unreadCount() {
        return R.ok(Math.toIntExact(announcementService.unreadCount(CurrentUser.userId())));
    }

    /** 管理侧：当前工作副本（notice:view）。无草稿 → data=null。 */
    @SaCheckPermission("notice:view")
    @GetMapping("/manage")
    public R<AnnouncementVO> manage() {
        Announcement a = announcementService.getManageable();
        return R.ok(a != null ? AnnouncementVO.from(a) : null);
    }

    /** 管理侧：保存草稿（notice:edit）。 */
    @SaCheckPermission("notice:edit")
    @PostMapping("/draft")
    public R<Void> saveDraft(@RequestBody AnnouncementDraftRequest req) {
        announcementService.saveDraft(req.title(), req.body());
        return R.ok();
    }

    /** 管理侧：发布（notice:edit，version+1 + 写 revision）。 */
    @SaCheckPermission("notice:edit")
    @PostMapping("/publish")
    public R<AnnouncementVO> publish() {
        return R.ok(AnnouncementVO.from(announcementService.publish(CurrentUser.userId())));
    }

    /** 管理侧：归档（notice:edit）。 */
    @SaCheckPermission("notice:edit")
    @PostMapping("/archive")
    public R<Void> archive() {
        announcementService.archive();
        return R.ok();
    }

    /** 管理侧：发布历史快照（notice:view，按版本倒序）。 */
    @SaCheckPermission("notice:view")
    @GetMapping("/history")
    public R<List<AnnouncementRevisionVO>> history() {
        return R.ok(announcementService.history().stream().map(AnnouncementRevisionVO::from).toList());
    }

    /** 管理侧：迁移历史只读辅助视图（notice:view，公告撰写素材）。 */
    @SaCheckPermission("notice:view")
    @GetMapping("/schemaHistory")
    public R<List<SchemaHistoryVO>> schemaHistory() {
        return R.ok(announcementService.schemaHistory().stream().map(SchemaHistoryVO::from).toList());
    }
}