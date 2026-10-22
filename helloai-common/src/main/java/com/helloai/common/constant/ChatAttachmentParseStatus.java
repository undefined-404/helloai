package com.helloai.common.constant;

/**
 * 需求对话附件的解析状态（{@code chat_attachment.parse_status}，V121）。
 *
 * <p>SKIPPED 与 FAILED 的区别是刻意的：FAILED 属「解析器出错，可重试」，
 * SKIPPED 属「读不出正文且重试也无用」（图片未接通视觉、扫描件未 OCR）。
 * 两者在 Prompt 清单里都必须显式写明，禁止模型据此臆测正文。</p>
 */
public enum ChatAttachmentParseStatus {
    /** 已落库，等待解析。 */
    PENDING,
    /** 解析中（worker 已领取）。 */
    PARSING,
    /** 解析完成，正文可注入。 */
    READY,
    /** 解析失败（可重试）：格式损坏、超时、解析器异常。 */
    FAILED,
    /** 跳过（重试无意义）：如 P0 阶段图片/扫描件无正文可读。 */
    SKIPPED
}
