package com.helloai.api.advice;

import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalExceptionHandler} SSE 兼容分支单元测试（修复 2026-09-13
 * streamSendById 异步分派异常）。
 *
 * <p>核心断言点：
 * <ul>
 *   <li>SSE 请求（Accept=text/event-stream 或 ASYNC 分派）异常时写 {@code event:error} 帧、
 *       返回 null，不再返回 {@code R} 对象（避免 text/event-stream 无 converter 二次异常）；</li>
 *   <li>普通 JSON 请求保持原行为（R 包裹 + 状态码）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler SSE 兼容分支")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest sseRequestByAccept() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn(MediaType.TEXT_EVENT_STREAM_VALUE);
        return request;
    }

    private HttpServletRequest asyncDispatchRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getDispatcherType()).thenReturn(DispatcherType.ASYNC);
        // Accept 缺省（*/* 不携带 text/event-stream），验证 ASYNC 通道独立命中
        return request;
    }

    private HttpServletRequest normalJsonRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    @Test
    @DisplayName("SSE 请求（Accept 头命中）：BizException 写 error 帧、返回 null、不改状态码")
    void handleBizExceptionWritesSseErrorFrame() throws Exception {
        HttpServletRequest request = sseRequestByAccept();
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        R<Void> result = handler.handleBizException(new BizException(500, "拆解失败"), request, response);

        assertThat(result).isNull();
        verify(response).setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        verify(writer).write("event:error\ndata:拆解失败\n\n");
        verify(writer).flush();
        verify(response, never()).setStatus(401);
    }

    @Test
    @DisplayName("SSE 请求（ASYNC 分派命中）：401 业务异常也写 error 帧而非 JSON 401")
    void handleBizExceptionOnAsyncDispatchWritesSseErrorFrame() throws Exception {
        HttpServletRequest request = asyncDispatchRequest();
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        R<Void> result = handler.handleBizException(new BizException(401, "未登录或凭证已过期"), request, response);

        assertThat(result).isNull();
        verify(writer).write("event:error\ndata:未登录或凭证已过期\n\n");
        verify(response, never()).setStatus(401);
    }

    @Test
    @DisplayName("SSE 兜底异常：写 error 帧（消息含换行被压平为单行）")
    void handleExceptionOnSseRequestFlattensNewline() throws Exception {
        HttpServletRequest request = sseRequestByAccept();
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        R<Void> result = handler.handleException(new IllegalStateException("boom"), request, response);

        assertThat(result).isNull();
        verify(writer).write("event:error\ndata:服务内部错误，请联系管理员\n\n");
    }

    @Test
    @DisplayName("普通 JSON 请求：保持 R 包裹 + 状态码语义（零回归）")
    void handleBizExceptionOnJsonRequestReturnsR() {
        HttpServletRequest request = normalJsonRequest();
        HttpServletResponse response = mock(HttpServletResponse.class);

        R<Void> result = handler.handleBizException(new BizException(401, "未登录"), request, response);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(401);
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("普通 JSON 请求兜底异常：R.fail 500（零回归）")
    void handleExceptionOnJsonRequestReturnsR500() {
        HttpServletRequest request = normalJsonRequest();
        HttpServletResponse response = mock(HttpServletResponse.class);

        R<Void> result = handler.handleException(new IllegalStateException("boom"), request, response);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(500);
    }
}
