package com.helloai.core.system.announcement.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.announcement.AnnouncementService;
import com.helloai.core.system.announcement.PublishedAnnouncement;
import com.helloai.core.system.announcement.SchemaHistoryRow;
import com.helloai.core.system.entity.Announcement;
import com.helloai.core.system.entity.AnnouncementRevision;
import com.helloai.core.system.entity.AnnouncementSeen;
import com.helloai.core.system.mapper.AnnouncementMapper;
import com.helloai.core.system.mapper.AnnouncementRevisionMapper;
import com.helloai.core.system.mapper.AnnouncementSeenMapper;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 置顶公告服务实现（MSG-6，设计 §3.7/§3.10）。
 *
 * <p>单条置顶：管理侧维护唯一一条工作副本，发布把快照写进 revision 并 version+1;读取侧渲染
 * 最新已发布快照（非工作副本），故编辑草稿不改变线上展示。已读位 seen_version 单调递增，
 * 发布新版本即全员重新未读。开关 OFF 只隐藏读取（getPublished=null / unreadCount=0），写入不受限。</p>
 */
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl extends ServiceImpl<AnnouncementMapper, Announcement>
        implements AnnouncementService {

    private final AnnouncementRevisionMapper revisionMapper;
    private final AnnouncementSeenMapper seenMapper;
    private final SysConfigService sysConfigService;

    @Override
    public Announcement getManageable() {
        return lambdaQuery()
                .orderByDesc(Announcement::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

    /** 发布专用行锁读取：串行化并发发布，防两人同时 publish 撞 uq_announcement_revision（审查修复 B6）。 */
    Announcement getManageableForUpdate() {
        return lambdaQuery()
                .orderByDesc(Announcement::getCreateTime)
                .last("LIMIT 1 FOR UPDATE")
                .one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDraft(String title, String body) {
        if (title == null || title.isBlank()) {
            throw new BizException(400, "公告标题不能为空");
        }
        if (title.length() > 255) {
            throw new BizException(400, "公告标题不能超过 255 字");
        }
        if (body == null || body.isBlank()) {
            throw new BizException(400, "公告正文不能为空");
        }
        Announcement a = getManageable();
        if (a == null) {
            Announcement n = new Announcement();
            n.setTitle(title);
            n.setBody(body);
            n.setStatus("DRAFT");
            n.setPinned(1);
            n.setVersion(1);
            save(n);
            return;
        }
        // 已发布态编辑工作副本不取消发布（线上仍展示上一版快照）;草稿/归档态编辑归位 DRAFT
        String status = "PUBLISHED".equals(a.getStatus()) ? "PUBLISHED" : "DRAFT";
        lambdaUpdate()
                .eq(Announcement::getId, a.getId())
                .set(Announcement::getTitle, title)
                .set(Announcement::getBody, body)
                .set(Announcement::getStatus, status)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Announcement publish(Long operatorId) {
        Announcement a = getManageableForUpdate();
        if (a == null) {
            throw new BizException(400, "暂无可发布的公告草稿");
        }
        int newVersion = latestRevisionVersion(a.getId()) + 1;
        OffsetDateTime now = OffsetDateTime.now();
        lambdaUpdate()
                .eq(Announcement::getId, a.getId())
                .set(Announcement::getStatus, "PUBLISHED")
                .set(Announcement::getVersion, newVersion)
                .set(Announcement::getPinned, 1)
                .set(Announcement::getPublishedAt, now)
                .update();

        AnnouncementRevision rev = new AnnouncementRevision();
        rev.setAnnouncementId(a.getId());
        rev.setVersion(newVersion);
        rev.setTitle(a.getTitle());
        rev.setBody(a.getBody());
        rev.setPublishedBy(operatorId != null ? String.valueOf(operatorId) : "system");
        rev.setPublishedAt(now);
        revisionMapper.insert(rev);

        a.setStatus("PUBLISHED");
        a.setVersion(newVersion);
        a.setPinned(1);
        a.setPublishedAt(now);
        return a;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive() {
        Announcement a = getManageable();
        if (a == null) {
            return;
        }
        lambdaUpdate()
                .eq(Announcement::getId, a.getId())
                .set(Announcement::getStatus, "ARCHIVED")
                .update();
    }

    @Override
    public List<AnnouncementRevision> history() {
        Announcement a = getManageable();
        if (a == null) {
            return List.of();
        }
        return revisionMapper.selectList(new LambdaQueryWrapper<AnnouncementRevision>()
                .eq(AnnouncementRevision::getAnnouncementId, a.getId())
                .orderByDesc(AnnouncementRevision::getVersion));
    }

    @Override
    public List<SchemaHistoryRow> schemaHistory() {
        return baseMapper.selectSchemaHistory();
    }

    @Override
    public PublishedAnnouncement getPublished(Long userId) {
        if (!sysConfigService.isAnnouncementEnabled()) {
            return null;
        }
        Announcement a = lambdaQuery()
                .eq(Announcement::getStatus, "PUBLISHED")
                .eq(Announcement::getPinned, 1)
                .orderByDesc(Announcement::getPublishedAt)
                .last("LIMIT 1")
                .one();
        if (a == null) {
            return null;
        }
        AnnouncementRevision rev = latestRevision(a.getId());
        if (rev == null) {
            return null;
        }
        int seen = seenVersion(userId, a.getId());
        return new PublishedAnnouncement(a.getId(), rev.getTitle(), rev.getBody(), rev.getVersion(),
                rev.getPublishedAt(), seen, seen < rev.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSeen(Long userId, Long announcementId, Integer version) {
        // 归属未识别（未登录 / 外部 Agent 无 Sa-Token 上下文）→ fail-close 不动任何数据
        if (userId == null || announcementId == null || version == null) {
            return;
        }
        // 端点登录即可调、id 由客户端传入：不存在（含逻辑删除）则按幂等上报语义直接忽略，
        // 否则外键违约会以系统异常 500 落日志（V151 announcement_seen.announcement_id REFERENCES）
        if (getById(announcementId) == null) {
            return;
        }
        // 版本钳制到 [0, 最新快照]：上界防止客户端上报超大 version 经 GREATEST 永久抬高已读位，
        // 下界兜住负数（首次插入不落负值）
        int latest = latestRevisionVersion(announcementId);
        if (latest <= 0) {
            return;
        }
        seenMapper.upsertSeen(IdWorker.getId(), userId, announcementId,
                Math.max(0, Math.min(version, latest)));
    }

    @Override
    public long unreadCount(Long userId) {
        if (!sysConfigService.isAnnouncementEnabled()) {
            return 0L;
        }
        PublishedAnnouncement p = getPublished(userId);
        return p != null && p.unread() ? 1L : 0L;
    }

    private AnnouncementRevision latestRevision(Long announcementId) {
        return revisionMapper.selectOne(new LambdaQueryWrapper<AnnouncementRevision>()
                .eq(AnnouncementRevision::getAnnouncementId, announcementId)
                .orderByDesc(AnnouncementRevision::getVersion)
                .last("LIMIT 1"));
    }

    private int latestRevisionVersion(Long announcementId) {
        AnnouncementRevision rev = latestRevision(announcementId);
        return rev != null ? rev.getVersion() : 0;
    }

    private int seenVersion(Long userId, Long announcementId) {
        if (userId == null) {
            return 0;
        }
        AnnouncementSeen s = seenMapper.selectOne(new LambdaQueryWrapper<AnnouncementSeen>()
                .eq(AnnouncementSeen::getUserId, userId)
                .eq(AnnouncementSeen::getAnnouncementId, announcementId)
                .last("LIMIT 1"));
        return s != null ? s.getSeenVersion() : 0;
    }
}