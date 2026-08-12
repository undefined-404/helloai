package com.helloai.api.advice;

import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletResponse response) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
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
    public R<Void> handleNotFound(NoResourceFoundException e) {
        log.debug("资源不存在: {}", e.getMessage());
        return R.fail(404, "请求的接口不存在");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("请求方法不支持: {}", e.getMessage());
        return R.fail(405, "请求方法不支持");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        return R.fail(e.getMessage());
    }

    /** @Valid @RequestBody 校验失败：HTTP 400 + 首条字段错误消息（此前被 Exception 兜底成 500）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.debug("参数校验失败: {}", message);
        return R.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail("服务内部错误，请联系管理员");
    }
}
