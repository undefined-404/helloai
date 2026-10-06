package com.helloai.api.advice;

import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    /**
     * 「错误码纠偏（P1-1）」用例专用：走真实异常解析链。
     *
     * <p>为何不用直接调 handler 方法：这 5 个新 handler 用 {@code @ResponseStatus} 声明状态码
     * （与本文件既有 {@code handleNotFound} / {@code handleNotLogin} 等一致），
     * 该注解由 {@code ExceptionHandlerExceptionResolver} → {@code ServletInvocableHandlerMethod#setResponseStatus}
     * 在 handler <b>返回之后</b>写入响应。直接调用 handler 方法拿不到 {@code response.getStatus()}，
     * 只有经 Spring MVC 调度才能验证「HTTP 状态码」这一半断言。standaloneSetup 不启 Spring 上下文，
     * 仅装配 MVC 调度骨架，属于快速单测（同模块 {@code AttachmentControllerAuthScopeTest} 既有先例）。</p>
     */
    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

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

    // ==================== 错误码纠偏（P1-1，2026-10-06）====================
    // 产物上传端点的客户端错误此前一律被 Exception 兜底成 HTTP 500 +「服务内部错误」，
    // 外部 Agent 无法区分「客户端问题（不该重试）」与「平台故障（应上报）」。
    // 下列用例逐条断言「HTTP 状态码 + 响应体 code」双一致（每条：status().isXxx() + jsonPath("$.code")）。

    @Test
    @DisplayName("上传超限：MaxUploadSizeExceededException → HTTP 413 且 code 413")
    void maxUploadSizeExceededShouldMapTo413() throws Exception {
        mockMvc.perform(get("/test/ex/payload-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413));
    }

    @Test
    @DisplayName("multipart 解析失败：MultipartException → HTTP 400 且 code 400")
    void multipartExceptionShouldMapTo400() throws Exception {
        mockMvc.perform(get("/test/ex/multipart"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("缺 multipart part（无 file 字段）：MissingServletRequestPartException → HTTP 400 且 code 400")
    void missingPartShouldMapTo400() throws Exception {
        mockMvc.perform(get("/test/ex/missing-part"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("缺必需参数（无 subTaskId）：MissingServletRequestParameterException → HTTP 400 且 code 400")
    void missingParameterShouldMapTo400() throws Exception {
        mockMvc.perform(get("/test/ex/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("Content-Type 不支持（非 multipart）：HttpMediaTypeNotSupportedException → HTTP 415 且 code 415")
    void mediaTypeNotSupportedShouldMapTo415() throws Exception {
        mockMvc.perform(get("/test/ex/media-type"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    @DisplayName("反证：非 multipart 系的未知异常未被过宽父类吞掉，仍落 Exception 兜底 → HTTP 500 且 code 500")
    void unknownExceptionShouldStillFallThroughTo500() throws Exception {
        // IllegalStateException 既非 MultipartException 系、也非 HttpMediaTypeNotSupportedException 系，
        // 若新 handler 误用宽父类匹配，此处会被 400/413/415 命中 —— 断言 500 + 兜底文案即证明 :222 兜底仍有效。
        mockMvc.perform(get("/test/ex/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("服务内部错误，请联系管理员"));
    }

    // ==================== 参数类型纠偏（① 删除通道参数校验，2026-10-07）====================

    @Test
    @DisplayName("路径参数类型不匹配（{id} 非数字）：MethodArgumentTypeMismatchException → HTTP 400 且 code 400")
    void typeMismatchShouldMapTo400() throws Exception {
        mockMvc.perform(get("/test/ex/type-mismatch/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 触发器：把各类异常从控制器方法抛出，交由 {@link GlobalExceptionHandler} 解析。
     * {@code MaxUploadSizeExceededException} / {@code MultipartException} 为非受检异常，无需 throws；
     * 其余三者继承 {@code jakarta.servlet.ServletException}（受检），显式 {@code throws Exception}。
     */
    @RestController
    static class ThrowingController {

        @GetMapping("/test/ex/payload-too-large")
        public void payloadTooLarge() {
            throw new MaxUploadSizeExceededException(9_000_000L);
        }

        @GetMapping("/test/ex/multipart")
        public void multipart() {
            throw new MultipartException("multipart body malformed");
        }

        @GetMapping("/test/ex/missing-part")
        public void missingPart() throws Exception {
            throw new MissingServletRequestPartException("file");
        }

        @GetMapping("/test/ex/missing-param")
        public void missingParameter() throws Exception {
            throw new MissingServletRequestParameterException("subTaskId", "Long");
        }

        @GetMapping("/test/ex/media-type")
        public void mediaType() throws Exception {
            throw new HttpMediaTypeNotSupportedException("application/json");
        }

        @GetMapping("/test/ex/unknown")
        public void unknown() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/test/ex/type-mismatch/{id}")
        public String typeMismatch(@PathVariable("id") Long id) {
            return "ok:" + id;
        }
    }
}
