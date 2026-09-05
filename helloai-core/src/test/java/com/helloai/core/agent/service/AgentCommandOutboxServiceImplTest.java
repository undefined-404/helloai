package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentCommandOutboxRelayProperties;
import com.helloai.common.constant.AgentCommandOutboxStatus;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import com.helloai.core.agent.mapper.AgentCommandOutboxEventMapper;
import com.helloai.core.agent.service.impl.AgentCommandOutboxServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentCommandOutboxService} A4 S1 单元测试：
 * outbox FAILED 人工恢复（{@code requeueFailed} CAS 重入 + {@code listFailed} 窗口分页）。
 *
 * <p>聚焦新增行为：
 * <ul>
 *   <li>{@code requeueFailed}：FAILED → PENDING 归零重入（CAS 命中）；
 *       非 FAILED 行 CAS 冲突 fail-close 抛 {@link BizException}；空 id 不触库；</li>
 *   <li>{@code listFailed}：窗口 from/to 透传 + 分页包装正确，结果原样返回。</li>
 * </ul>
 *
 * <p>用 spy 桩掉 {@code baseMapper}，隔离 MyBatis-Plus / 数据库；
 * {@code lambdaUpdate()} 真实构造 wrapper，TableInfo 预热规避 MP 3.5.9 lambda 缓存坑，
 * 参照 {@code TaskFinalReportServiceTest.initTableInfo}。</p>
 */
@DisplayName("AgentCommandOutboxService outbox 人工恢复（A4 S1）")
class AgentCommandOutboxServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), AgentCommandOutboxEvent.class);
    }

    private AgentCommandOutboxEventMapper outboxMapper;
    private AgentCommandOutboxService service;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(AgentCommandOutboxEventMapper.class);
        service = spy(new AgentCommandOutboxServiceImpl(new AgentCommandOutboxRelayProperties()));
        ReflectionTestUtils.setField(service, "baseMapper", outboxMapper);
    }

    // ══════════════════════════════════════════════════════════════
    //  requeueFailed：FAILED → PENDING 人工重入（CAS）
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("requeueFailed：FAILED 行 CAS 命中——重置为 PENDING、计数归零、清空错误、条件锁定 FAILED")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRequeueFailedToPending() {
        ArgumentCaptor<Wrapper> updateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        when(outboxMapper.update(isNull(), updateCaptor.capture())).thenReturn(1);

        service.requeueFailed(42L);

        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) updateCaptor.getValue();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        // SET 段：目标状态 + 归零三列
        assertThat(wrapper.getSqlSet())
                .contains("status")
                .contains("retry_count")
                .contains("next_retry_time")
                .contains("error_msg");
        // WHERE 段：CAS 锁定 id + status=FAILED
        assertThat(wrapper.getSqlSegment()).contains("id").contains("status");
        // 参数值断言：条件 FAILED（非 FAILED 行不可重入）、目标 PENDING、计数归零
        assertThat(params.values())
                .contains(AgentCommandOutboxStatus.FAILED)
                .contains(AgentCommandOutboxStatus.PENDING)
                .contains(0)
                .contains(42L);
    }

    @Test
    @DisplayName("requeueFailed：非 FAILED 行 CAS 冲突 → BizException（fail-close 不静默）")
    void shouldThrowOnCasConflict() {
        when(outboxMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.requeueFailed(42L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("42");

        verify(outboxMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("requeueFailed：id 为空 → BizException 且不触库")
    void shouldThrowOnNullId() {
        assertThatThrownBy(() -> service.requeueFailed(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("id 不能为空");

        verify(outboxMapper, never()).update(any(), any());
    }

    // ══════════════════════════════════════════════════════════════
    //  listFailed：FAILED 窗口分页列表
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listFailed：窗口 from/to 透传 + 页码尺寸包装 + 结果原样返回")
    @SuppressWarnings("unchecked")
    void shouldListFailedWithWindowAndPage() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to = OffsetDateTime.now();
        Page<AgentCommandOutboxEvent> result = new Page<>(2, 10);
        when(outboxMapper.listFailed(any(IPage.class), eq(from), eq(to))).thenReturn(result);

        IPage<AgentCommandOutboxEvent> actual = service.listFailed(from, to, 2, 10);

        assertThat(actual).isSameAs(result);
        ArgumentCaptor<IPage<AgentCommandOutboxEvent>> pageCaptor =
                ArgumentCaptor.forClass(IPage.class);
        verify(outboxMapper).listFailed(pageCaptor.capture(), eq(from), eq(to));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }
}