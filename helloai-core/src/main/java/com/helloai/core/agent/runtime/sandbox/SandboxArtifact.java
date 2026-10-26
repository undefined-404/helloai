package com.helloai.core.agent.runtime.sandbox;

import java.nio.charset.StandardCharsets;

/**
 * 沙箱内一份产物文件的回读结果（`SKX-7`，2026-10-26）。
 *
 * <p><b>为什么带 {@code byte[]} 而不是 {@code String}</b>：`SKX-9` 的验收要拿回读内容的
 * {@code sha256} 与 archify 回执里的 {@code artifact.sha256} 对照。<b>哈希必须算在字节上</b>——
 * 一旦先解码成 String 再算，任何编码/换行差异都会让比对失效，而那种失效看起来像「产物被改过」。
 * 文本消费者自行 {@link #text()}，二进制消费者直接取 {@link #bytes()}。</p>
 *
 * <p><b>{@code truncated} 是硬约束不是提示</b>：为 true 时 {@code sha256} 只对<b>截断后的片段</b>
 * 成立，<b>不能</b>拿去和完整文件的回执比对（比出来必然不一致）。消费方必须先看这个标志。</p>
 *
 * @param path      容器内的绝对路径（回显，便于日志与调用方对齐）
 * @param bytes     文件内容（可能被 {@code maxBytes} 截断）
 * @param mime      按扩展名推断的 MIME（推断不出 ⇒ {@code application/octet-stream}）
 * @param sha256    {@link #bytes()} 的 SHA-256（64 位小写十六进制）
 * @param truncated 是否因超过 {@code maxBytes} 被截断（true ⇒ 本结果的哈希/长度只代表片段）
 */
public record SandboxArtifact(String path, byte[] bytes, String mime, String sha256, boolean truncated) {

    /** 内容字节数（截断时为片段长度）。 */
    public int size() {
        return bytes == null ? 0 : bytes.length;
    }

    /** 按 UTF-8 解码的文本视图（仅对文本产物有意义；二进制请直接用 {@link #bytes()}）。 */
    public String text() {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
