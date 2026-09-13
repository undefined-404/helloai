package com.helloai.api.advice;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request, HttpServletResponse response) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        if (sseRequest(request)) {
            writeSseError(request, response, e.getMessage());
            return null;
        }
        if (e.getCode() == 401) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return R.fail(401, e.getMessage());
        }
        if (e.getCode() == 403) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return R.fail(403, e.getMessage());
        }
        if (e.getCode() != null && e.getCode() >= 400 && e.getCode() < 600) {
            response.setStatus(e.getCode());
        }
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNotFound(NoResourceFoundException e, HttpServletRequest request, HttpServletResponse response) {
        log.debug("资源不存在: {}", e.getMessage());
        if (sseRequest(request)) {
            writeSseError(request, response, "请求的接口不存在");
            return null;
        }
        return R.fail(404, "请求的接口不存在");
    }

    /** Sa-Token 未登录：会话缺失/过期（@SaCheckPermission 等注解鉴权触发）→ HTTP 401。 */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleNotLogin(NotLoginException e, HttpServletRequest request, HttpServletResponse response) {
        log.debug("Sa-Token 未登录: {}", e.getMessage());
        if (sseRequest(request)) {
            writeSseError(request, response, "登录已过期，请重新登录");
            return null;
        }
        return R.fail(401, "登录已过期，请重新登录");
    }

    /** Sa-Token 权限不足：@SaCheckPermission 校验失败 → HTTP 403。 */
    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleNotPermission(NotPermissionException e, HttpServletRequest request, HttpServletResponse response) {
        log.debug("Sa-Token 权限不足: {}", e.getMessage());
        if (sseRequest(request)) {
            writeSseError(request, response, "无权限执行该操作");
            return null;
        }
        return R.fail(403, "无权限执行该操作");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                            HttpServletRequest request, HttpServletResponse response) {
        log.debug("请求方法不支持: {}", e.getMessage());
        if (sseRequest(request)) {
            writeSseError(request, response, "请求方法不支持");
            return null;
        }
        return R.fail(405, "请求方法不支持");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (sseRequest(request)) {
            writeSseError(request, response, e.getMessage());
            return null;
        }
        return R.fail(e.getMessage());
    }

    /** @Valid @RequestBody 校验失败：HTTP 400 + 首条字段错误消息（此前被 Exception 兜底成 500）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                HttpServletRequest request, HttpServletResponse response) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.debug("参数校验失败: {}", message);
        if (sseRequest(request)) {
            writeSseError(request, response, message);
            return null;
        }
        return R.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, HttpServletRequest request, HttpServletResponse response) {
        log.error("系统异常", e);
        if (sseRequest(request)) {
            writeSseError(request, response, "服务内部错误，请联系管理员");
            return null;
        }
        return R.fail("服务内部错误，请联系管理员");
    }

    /**
     * SSE 请求判定（三通道命中任一即视为 SSE 异常场景）：
     * <ol>
     *   <li>{@code ASYNC} 分派——SseEmitter 已返回，响应已进入 text/event-stream 模式；</li>
     *   <li>{@code Accept} 头携带 {@code text/event-stream}——前端 chatStream.ts 已显式声明；</li>
     *   <li>{@code Content-Type} 携带 {@code text/event-stream}——握手/SSE 上行场景兜底。</li>
     * </ol>
     */
    private boolean sseRequest(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        return request.getContentType() != null && request.getContentType().contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /**
     * SSE 场景错误响应：写 {@code event:error} 帧（HTTP 保持 200，与前端 chatStream.ts
     * 的 SSE 事件协议对齐，onError 收敛为可提示文案）。
     *
     * <p>为何不能返回 {@code R} 对象：SSE 接口 {@code produces=text/event-stream}，容器找不到
     * {@code R} 的 converter，会抛 {@code HttpMessageNotWritableException} 二次异常（本次 bug 的直接诱因）。</p>
     *
     * <p>响应已提交（SseEmitter 已开始发帧）时放弃改写：连读已建立的流，错误已无表达通道，只记日志。</p>
     */
    private void writeSseError(HttpServletRequest request, HttpServletResponse response, String message) {
        try {
            if (response.isCommitted()) {
                log.warn("SSE 请求异常但响应已提交，放弃写 error 帧: {}", message);
                return;
            }
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // SSE data 行不允许裸换行，压平为单行
            String safe = message == null || message.isBlank()
                    ? "服务内部错误"
                    : message.replace("\r", " ").replace("\n", " ");
            response.getWriter().write("event:error\ndata:" + safe + "\n\n");
            response.getWriter().flush();
        } catch (IOException ex) {
            log.warn("SSE error 帧写入失败: {}", ex.getMessage());
        }
    }
}
