package com.helloai.core.task.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.WorkflowTemplateStatus;
import com.helloai.common.constant.WorkflowVersionStatus;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;
import com.helloai.core.task.workflow.mapper.WorkflowTemplateMapper;
import com.helloai.core.task.workflow.mapper.WorkflowTemplateVersionMapper;
import com.helloai.core.task.workflow.service.impl.WorkflowTemplateServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowTemplateService} C1-S1 单元测试：模板/版本 CRUD。
 *
 * <p>spy + mock baseMapper/versionMapper 隔离 MyBatis-Plus / 数据库；
 * TableInfo 预热规避 MP 3.5.9 lambda 缓存坑（AgentCommandOutboxServiceImplTest 同款）。</p>
 */
@DisplayName("WorkflowTemplateService 模板/版本 CRUD（C1-S1）")
class WorkflowTemplateServiceImplTest {

    private static final Long TEMPLATE_ID = 1L;
    private static final Long VERSION_ID = 10L;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowTemplate.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTemplateVersion.class);
    }

    private WorkflowTemplateMapper templateMapper;
    private WorkflowTemplateVersionMapper versionMapper;
    private WorkflowTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(WorkflowTemplateMapper.class);
        versionMapper = mock(WorkflowTemplateVersionMapper.class);
        service = spy(new WorkflowTemplateServiceImpl(versionMapper));
        ReflectionTestUtils.setField(service, "baseMapper", templateMapper);
    }

    private WorkflowTemplate template(WorkflowTemplateStatus status) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(TEMPLATE_ID);
        t.setName("dev-release");
        t.setStatus(status);
        return t;
    }

    private WorkflowTemplateVersion version(WorkflowVersionStatus status, int versionNo) {
        WorkflowTemplateVersion v = new WorkflowTemplateVersion();
        v.setId(VERSION_ID);
        v.setTemplateId(TEMPLATE_ID);
        v.setVersionNo(versionNo);
        v.setStatus(status);
        v.setDefinition(Map.of("nodes", List.of(Map.of("nodeKey", "a", "role", "executor"))));
        return v;
    }

    @Nested
    @DisplayName("模板 CRUD")
    class TemplateCrud {

        @Test
        @DisplayName("createTemplate：名称必填，创建 DRAFT 模板")
        void shouldCreateTemplate() {
            doReturn(true).when(service).save(any(WorkflowTemplate.class));

            WorkflowTemplate result = service.createTemplate("  dev-release  ", "desc", "cat");

            assertThat(result.getName()).isEqualTo("dev-release"); // 名称 trim
            assertThat(result.getStatus()).isEqualTo(WorkflowTemplateStatus.DRAFT);
        }

        @Test
        @DisplayName("createTemplate：名称为空 → BizException 不触库")
        void shouldRejectBlankName() {
            assertThatThrownBy(() -> service.createTemplate("  ", null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("名称不能为空");
            verify(service, never()).save(any());
        }

        @Test
        @DisplayName("updateTemplate：ARCHIVED 禁止编辑")
        void shouldRejectUpdateArchived() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(WorkflowTemplateStatus.ARCHIVED));

            assertThatThrownBy(() -> service.updateTemplate(TEMPLATE_ID, "new", null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可编辑");
        }

        @Test
        @DisplayName("archiveTemplate：ACTIVE → ARCHIVED（CAS update 命中）")
        void shouldArchiveTemplate() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(WorkflowTemplateStatus.ACTIVE));
            when(templateMapper.update(eq(null), any())).thenReturn(1);

            WorkflowTemplate result = service.archiveTemplate(TEMPLATE_ID);

            assertThat(result.getStatus()).isEqualTo(WorkflowTemplateStatus.ARCHIVED);
        }

        @Test
        @DisplayName("模板不存在 → BizException")
        void shouldRejectMissingTemplate() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.archiveTemplate(TEMPLATE_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不存在");
        }
    }

    @Nested
    @DisplayName("版本 CRUD")
    class VersionCrud {

        @Test
        @DisplayName("createVersion：definition 校验通过，version_no = max+1，创建 DRAFT")
        void shouldCreateVersionWithNextNumber() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(WorkflowTemplateStatus.ACTIVE));
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(version(WorkflowVersionStatus.PUBLISHED, 2));
            when(versionMapper.insert(any(WorkflowTemplateVersion.class))).thenReturn(1);

            WorkflowTemplateVersion result = service.createVersion(
                    TEMPLATE_ID, Map.of("nodes", List.of(Map.of("nodeKey", "a", "role", "executor"))));

            assertThat(result.getVersionNo()).isEqualTo(3);
            assertThat(result.getStatus()).isEqualTo(WorkflowVersionStatus.DRAFT);
            verify(versionMapper).insert(any(WorkflowTemplateVersion.class));
        }

        @Test
        @DisplayName("createVersion：无既有版本 → version_no = 1")
        void shouldStartVersionAtOne() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(WorkflowTemplateStatus.DRAFT));
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(versionMapper.insert(any(WorkflowTemplateVersion.class))).thenReturn(1);

            WorkflowTemplateVersion result = service.createVersion(
                    TEMPLATE_ID, Map.of("nodes", List.of(Map.of("nodeKey", "a", "role", "executor"))));

            assertThat(result.getVersionNo()).isEqualTo(1);
        }

        @Test
        @DisplayName("createVersion：definition 校验失败 → BizException 不落库")
        void shouldRejectInvalidDefinition() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(WorkflowTemplateStatus.DRAFT));
            Map<String, Object> bad = Map.of("nodes", List.of(Map.of("nodeKey", "a", "role", "coordinator")));

            assertThatThrownBy(() -> service.createVersion(TEMPLATE_ID, bad))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("校验失败");
            verify(versionMapper, never()).insert(any(WorkflowTemplateVersion.class));
        }

        @Test
        @DisplayName("createVersion：模板 ARCHIVED → BizException")
        void shouldRejectVersionOnArchivedTemplate() {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(WorkflowTemplateStatus.ARCHIVED));

            assertThatThrownBy(() -> service.createVersion(TEMPLATE_ID, Map.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可新建版本");
        }

        @Test
        @DisplayName("publishVersion：DRAFT → PUBLISHED + 模板 current_version 回填 + ACTIVE")
        void shouldPublishVersion() {
            when(versionMapper.selectById(VERSION_ID))
                    .thenReturn(version(WorkflowVersionStatus.DRAFT, 1));
            when(versionMapper.update(eq(null), any())).thenReturn(1);
            when(templateMapper.update(eq(null), any())).thenReturn(1);

            WorkflowTemplateVersion result = service.publishVersion(VERSION_ID);

            assertThat(result.getStatus()).isEqualTo(WorkflowVersionStatus.PUBLISHED);
            verify(templateMapper).update(eq(null), any()); // current_version 回填
        }

        @Test
        @DisplayName("publishVersion：已发布版本重复发布 → BizException（不可变）")
        void shouldRejectRepublish() {
            when(versionMapper.selectById(VERSION_ID))
                    .thenReturn(version(WorkflowVersionStatus.PUBLISHED, 1));

            assertThatThrownBy(() -> service.publishVersion(VERSION_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可重复发布");
        }

        @Test
        @DisplayName("publishVersion：CAS 冲突（并发已发布）→ BizException")
        void shouldRejectPublishCasConflict() {
            when(versionMapper.selectById(VERSION_ID))
                    .thenReturn(version(WorkflowVersionStatus.DRAFT, 1));
            when(versionMapper.update(eq(null), any())).thenReturn(0);

            assertThatThrownBy(() -> service.publishVersion(VERSION_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("状态冲突");
        }
    }
}
