package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.browser.BrowserSessionResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.BrowserSessionStatus;
import com.helloai.core.agent.entity.BrowserSession;
import com.helloai.core.agent.service.BrowserSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BrowserSessionController} C3 配套单元测试：分页 + 详情端点转发。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserSessionController 会话展示端点（C3 配套）")
class BrowserSessionControllerTest {

    @Mock
    private BrowserSessionService browserSessionService;

    @InjectMocks
    private BrowserSessionController controller;

    private static final Long SESSION_ID = 1L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-06T12:00:00+08:00");

    private BrowserSession session() {
        BrowserSession s = new BrowserSession();
        s.setId(SESSION_ID);
        s.setAgentId(100L);
        s.setTaskId(200L);
        s.setStatus(BrowserSessionStatus.ACTIVE);
        s.setCurrentUrl("https://example.com");
        s.setBeginTime(NOW);
        return s;
    }

    @Test
    @DisplayName("分页：过滤透传 + PageResult 封装")
    void page() {
        Page<BrowserSession> page = new Page<>(1, 10);
        page.setRecords(List.of(session()));
        page.setTotal(1);
        when(browserSessionService.pageSessions(1, 10, 100L, BrowserSessionStatus.ACTIVE)).thenReturn(page);

        R<PageResult<BrowserSessionResponse>> resp = controller.page(1, 10, 100L, BrowserSessionStatus.ACTIVE);

        assertThat(resp.getData().getList()).hasSize(1);
        assertThat(resp.getData().getList().get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(resp.getData().getList().get(0).getCurrentUrl()).isEqualTo("https://example.com");
        verify(browserSessionService).pageSessions(1, 10, 100L, BrowserSessionStatus.ACTIVE);
        verifyNoMoreInteractions(browserSessionService);
    }

    @Test
    @DisplayName("详情：路径 id 透传")
    void get() {
        when(browserSessionService.getSession(SESSION_ID)).thenReturn(session());

        R<BrowserSessionResponse> resp = controller.get(SESSION_ID);

        assertThat(resp.getData().getId()).isEqualTo(SESSION_ID);
        assertThat(resp.getData().getTaskId()).isEqualTo(200L);
        verify(browserSessionService).getSession(SESSION_ID);
        verifyNoMoreInteractions(browserSessionService);
    }
}
