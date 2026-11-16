package com.helloai.core.system.announcement.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.Announcement;
import com.helloai.core.system.entity.AnnouncementRevision;
import com.helloai.core.system.entity.AnnouncementSeen;
import com.helloai.core.system.mapper.AnnouncementMapper;
import com.helloai.core.system.mapper.AnnouncementRevisionMapper;
import com.helloai.core.system.mapper.AnnouncementSeenMapper;
import com.helloai.core.system.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AnnouncementServiceImpl} 单测（MSG-6，设计 §3.7/§3.10）。
 *
 * <p>锁定确定性口径：开关 OFF 读取返回空、markSeen 归属未识别 fail-close、草稿校验、
 * 发布 version+1（首发布为 1）+ 写 revision。版本已读端到端（seen_version 单调递增、
 * 发布后全员重新未读）由 {@code AnnouncementVersionReadIT}（真实 PG）锁定。</p>
 */
@DisplayName("MSG-6 AnnouncementService 版本已读")
class AnnouncementServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Announcement.class);
        TableInfoHelper.initTableInfo(assistant, AnnouncementRevision.class);
        TableInfoHelper.initTableInfo(assistant, AnnouncementSeen.class);
    }

    private AnnouncementRevisionMapper revisionMapper;
    private AnnouncementSeenMapper seenMapper;
    private SysConfigService sysConfigService;
    private AnnouncementMapper mapper;
    private AnnouncementServiceImpl service;

    @BeforeEach
    void setUp() {
        revisionMapper = mock(AnnouncementRevisionMapper.class);
        seenMapper = mock(AnnouncementSeenMapper.class);
        sysConfigService = mock(SysConfigService.class);
        mapper = mock(AnnouncementMapper.class);
        service = spy(new AnnouncementServiceImpl(revisionMapper, seenMapper, sysConfigService));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(sysConfigService.isAnnouncementEnabled()).thenReturn(true);
    }

    private Announcement draft() {
        Announcement a = new Announcement();
        a.setId(1L);
        a.setTitle("标题");
        a.setBody("正文");
        a.setStatus("DRAFT");
        a.setPinned(1);
        a.setVersion(1);
        return a;
    }

    private AnnouncementRevision revision(int version) {
        AnnouncementRevision rev = new AnnouncementRevision();
        rev.setVersion(version);
        return rev;
    }

    @Test
    @DisplayName("开关 OFF：getPublished 返回 null，不查库")
    void getPublishedDisabledReturnsNull() {
        when(sysConfigService.isAnnouncementEnabled()).thenReturn(false);

        assertThat(service.getPublished(9L)).isNull();

        verifyNoInteractions(mapper);
        verifyNoInteractions(revisionMapper);
        verifyNoInteractions(seenMapper);
    }

    @Test
    @DisplayName("开关 OFF：unreadCount=0，不查库")
    void unreadCountDisabledReturnsZero() {
        when(sysConfigService.isAnnouncementEnabled()).thenReturn(false);

        assertThat(service.unreadCount(9L)).isZero();

        verifyNoInteractions(mapper);
        verifyNoInteractions(revisionMapper);
        verifyNoInteractions(seenMapper);
    }

    @Test
    @DisplayName("markSeen 归属未识别（userId=null）：fail-close 不写库、不查库")
    void markSeenFailsCloseWhenUserNull() {
        service.markSeen(null, 1L, 1);

        verifyNoInteractions(mapper);
        verifyNoInteractions(seenMapper);
    }

    @Test
    @DisplayName("markSeen 正常：调 upsertSeen 上报版本（单调递增由 SQL GREATEST 保证）")
    void markSeenUpsertsVersion() {
        when(mapper.selectById(1L)).thenReturn(draft());
        when(revisionMapper.selectOne(any())).thenReturn(revision(3));

        service.markSeen(9L, 1L, 3);

        verify(seenMapper).upsertSeen(any(), eq(9L), eq(1L), eq(3));
    }

    @Test
    @DisplayName("markSeen 脏 id（公告不存在/已逻辑删除）：忽略，不写库也不触发外键 500")
    void markSeenIgnoresUnknownAnnouncement() {
        when(mapper.selectById(404L)).thenReturn(null);

        service.markSeen(9L, 404L, 1);

        verifyNoInteractions(seenMapper);
        verifyNoInteractions(revisionMapper);
    }

    @Test
    @DisplayName("markSeen 版本超大：钳制到最新快照版本（防 seen_version 被永久抬高）")
    void markSeenClampsVersionToLatest() {
        when(mapper.selectById(1L)).thenReturn(draft());
        when(revisionMapper.selectOne(any())).thenReturn(revision(3));

        service.markSeen(9L, 1L, 999_999);

        verify(seenMapper).upsertSeen(any(), eq(9L), eq(1L), eq(3));
    }

    @Test
    @DisplayName("markSeen 版本为负：钳制到 0（首次插入不落负值）")
    void markSeenClampsNegativeVersionToZero() {
        when(mapper.selectById(1L)).thenReturn(draft());
        when(revisionMapper.selectOne(any())).thenReturn(revision(3));

        service.markSeen(9L, 1L, -5);

        verify(seenMapper).upsertSeen(any(), eq(9L), eq(1L), eq(0));
    }

    @Test
    @DisplayName("saveDraft 标题为空：拒绝（400）")
    void saveDraftRejectsBlankTitle() {
        assertThatThrownBy(() -> service.saveDraft("  ", "正文"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("saveDraft 正文为空：拒绝（400）")
    void saveDraftRejectsBlankBody() {
        assertThatThrownBy(() -> service.saveDraft("标题", ""))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("saveDraft 标题超 255 字：拒绝（400，审查修复 B3）")
    void saveDraftRejectsOverlongTitle() {
        assertThatThrownBy(() -> service.saveDraft("a".repeat(256), "正文"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("publish 无草稿：拒绝（400）")
    void publishWithoutDraftThrows() {
        doReturn(null).when(service).getManageableForUpdate();

        assertThatThrownBy(() -> service.publish(9L))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(revisionMapper);
    }

    @Test
    @DisplayName("publish 首发布：version=1 + 写 revision（标题/正文/发布人取自工作副本）")
    void publishFirstVersionIsOne() {
        doReturn(draft()).when(service).getManageableForUpdate();
        when(revisionMapper.selectOne(any())).thenReturn(null);

        service.publish(9L);

        ArgumentCaptor<AnnouncementRevision> captor = ArgumentCaptor.forClass(AnnouncementRevision.class);
        verify(revisionMapper).insert(captor.capture());
        AnnouncementRevision rev = captor.getValue();
        assertThat(rev.getVersion()).isEqualTo(1);
        assertThat(rev.getAnnouncementId()).isEqualTo(1L);
        assertThat(rev.getTitle()).isEqualTo("标题");
        assertThat(rev.getBody()).isEqualTo("正文");
        assertThat(rev.getPublishedBy()).isEqualTo("9");
    }

    @Test
    @DisplayName("publish 再次发布：version 在最新快照上 +1")
    void publishSubsequentVersionIncrements() {
        doReturn(draft()).when(service).getManageableForUpdate();
        AnnouncementRevision latest = new AnnouncementRevision();
        latest.setVersion(2);
        when(revisionMapper.selectOne(any())).thenReturn(latest);

        service.publish(9L);

        ArgumentCaptor<AnnouncementRevision> captor = ArgumentCaptor.forClass(AnnouncementRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
    }
}