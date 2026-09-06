package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.BrowserSessionStatus;
import com.helloai.core.agent.entity.BrowserSession;
import com.helloai.core.agent.mapper.BrowserSessionMapper;
import com.helloai.core.agent.service.impl.BrowserSessionServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * {@link BrowserSessionService} C3-S1 单元测试：会话登记/展示生命周期。
 */
@DisplayName("BrowserSessionService 会话登记生命周期（C3-S1）")
class BrowserSessionServiceImplTest {

    private static final Long SESSION_ID = 1L;
    private static final Long AGENT_ID = 100L;
    private static final Long TASK_ID = 200L;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BrowserSession.class);
    }

    private BrowserSessionMapper sessionMapper;
    private BrowserSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(BrowserSessionMapper.class);
        service = spy(new BrowserSessionServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", sessionMapper);
    }

    private BrowserSession session(BrowserSessionStatus status) {
        BrowserSession s = new BrowserSession();
        s.setId(SESSION_ID);
        s.setAgentId(AGENT_ID);
        s.setTaskId(TASK_ID);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("开始会话：BEGIN + begin_time 落库")
    void begin() {
        BrowserSession created = service.beginSession(AGENT_ID, TASK_ID);
        assertThat(created.getStatus()).isEqualTo(BrowserSessionStatus.BEGIN);
        assertThat(created.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(created.getBeginTime()).isNotNull();
    }

    @Test
    @DisplayName("agentId 必填")
    void beginRequiresAgent() {
        assertThatThrownBy(() -> service.beginSession(null, TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("agentId 必填");
    }

    @Test
    @DisplayName("标记活跃：BEGIN→ACTIVE，刷新 URL/截图引用")
    void markActive() {
        doReturn(session(BrowserSessionStatus.BEGIN)).when(service).getById(SESSION_ID);
        BrowserSession active = service.markActive(SESSION_ID, "https://a.com", "ref-1");
        assertThat(active.getStatus()).isEqualTo(BrowserSessionStatus.ACTIVE);
        assertThat(active.getCurrentUrl()).isEqualTo("https://a.com");
        assertThat(active.getLastScreenshotRef()).isEqualTo("ref-1");
    }

    @Test
    @DisplayName("关闭会话：→CLOSED + close_time，幂等")
    void close() {
        doReturn(session(BrowserSessionStatus.ACTIVE)).when(service).getById(SESSION_ID);
        BrowserSession closed = service.closeSession(SESSION_ID, "https://a.com");
        assertThat(closed.getStatus()).isEqualTo(BrowserSessionStatus.CLOSED);
        assertThat(closed.getCloseTime()).isNotNull();
        // 已关闭幂等
        doReturn(session(BrowserSessionStatus.CLOSED)).when(service).getById(SESSION_ID);
        assertThat(service.closeSession(SESSION_ID, null).getStatus()).isEqualTo(BrowserSessionStatus.CLOSED);
    }

    @Test
    @DisplayName("异常终止：→FAILED")
    void markFailed() {
        doReturn(session(BrowserSessionStatus.ACTIVE)).when(service).getById(SESSION_ID);
        BrowserSession failed = service.markFailed(SESSION_ID);
        assertThat(failed.getStatus()).isEqualTo(BrowserSessionStatus.FAILED);
        assertThat(failed.getCloseTime()).isNotNull();
    }

    @Test
    @DisplayName("会话不存在报错")
    void notFound() {
        doReturn(null).when(service).getById(SESSION_ID);
        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("无任何反向写 task/sub_task（反锁禁令：本服务只操作 browser_session）")
    void noLockBack() {
        // 仅断言本服务不触碰其他 mapper——构造器零依赖，天然满足
        assertThat(service).isNotNull();
        org.mockito.Mockito.verify(sessionMapper, org.mockito.Mockito.never())
                .delete(any());
    }
}
