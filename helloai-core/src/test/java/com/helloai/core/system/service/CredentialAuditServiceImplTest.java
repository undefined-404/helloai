package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.constant.CredentialAuditAction;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.mapper.CredentialAuditLogMapper;
import com.helloai.core.system.service.impl.CredentialAuditServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialAuditService} B2（N-004 收口）单元测试：
 * record 落点字段装配 + listAudits 条件/分页。
 */
@DisplayName("CredentialAuditService 审计写入与查询（B2）")
class CredentialAuditServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), CredentialAuditLog.class);
    }

    private CredentialAuditLogMapper auditMapper;
    private CredentialAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        auditMapper = mock(CredentialAuditLogMapper.class);
        service = spy(new CredentialAuditServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", auditMapper);
    }

    @Test
    @DisplayName("record：动作/操作者/明细/归属字段完整装配后落库")
    void shouldRecordWithAllFields() {
        doReturn(true).when(service).save(any(CredentialAuditLog.class));

        service.record(5L, CredentialOwnerType.AGENT, 1L, "deepseek",
                CredentialAuditAction.ROTATE, CredentialAuditAction.OPERATOR_ADMIN,
                "rotated_from_id=9");

        ArgumentCaptor<CredentialAuditLog> captor = ArgumentCaptor.forClass(CredentialAuditLog.class);
        verify(service).save(captor.capture());
        CredentialAuditLog log = captor.getValue();
        assertThat(log.getCredentialId()).isEqualTo(5L);
        assertThat(log.getOwnerType()).isEqualTo(CredentialOwnerType.AGENT);
        assertThat(log.getOwnerId()).isEqualTo(1L);
        assertThat(log.getProvider()).isEqualTo("deepseek");
        assertThat(log.getAction()).isEqualTo(CredentialAuditAction.ROTATE);
        assertThat(log.getOperator()).isEqualTo(CredentialAuditAction.OPERATOR_ADMIN);
        assertThat(log.getDetail()).isEqualTo("rotated_from_id=9");
    }

    @Test
    @DisplayName("listAudits：credentialId 过滤 + 分页透传 + 结果原样返回")
    @SuppressWarnings("unchecked")
    void shouldListAuditsByCredentialId() {
        Page<CredentialAuditLog> page = new Page<>(2, 10);
        when(auditMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        IPage<CredentialAuditLog> result = service.listAudits(5L, null, null, 2, 10);

        assertThat(result).isSameAs(page);
        ArgumentCaptor<IPage<CredentialAuditLog>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
        verify(auditMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("listAudits：owner 条件过滤（ownerType + ownerId）")
    void shouldListAuditsByOwner() {
        when(auditMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(new Page<>(1, 20));

        service.listAudits(null, CredentialOwnerType.PLATFORM, 0L, 1, 20);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.AbstractWrapper> wCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.AbstractWrapper.class);
        verify(auditMapper).selectPage(any(IPage.class), wCaptor.capture());
        String segment = wCaptor.getValue().getSqlSegment();
        assertThat(segment).contains("owner_type").contains("owner_id");
    }
}
