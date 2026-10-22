package com.helloai.core.planner.attachment;

/**
 * 附件解析失败（格式损坏、超时、解析器异常）。
 *
 * <p>语义是「可重试」：调用方落 {@code parse_status=FAILED} + {@code parse_error}，
 * 与「读不出正文且重试无意义」（{@code SKIPPED}）区分开。</p>
 */
public class AttachmentParseException extends RuntimeException {

    public AttachmentParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public AttachmentParseException(String message) {
        super(message);
    }
}
