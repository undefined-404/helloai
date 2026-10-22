package com.helloai.core.planner.attachment;

/**
 * 解析输入（内存态）：会话附件在 P0 阶段上限 20MB，直接整块读入，避免为「先探测后解析」
 * 反复回源对象存储。超过上限的请求在入口就被拒，不会走到这里。
 *
 * @param fileName 原始文件名（含扩展名）—— Tika 必须靠它做类型判定与编码探测，缺了会退化成
 *                 {@code application/octet-stream} 并解析出空串（实测见方案 §3.3）
 * @param mimeType 声明或探测到的 MIME（可为 null）
 * @param content  文件字节
 */
public record ParsedInput(String fileName, String mimeType, byte[] content) {
}
