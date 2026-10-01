package com.helloai.core.system.port;

/**
 * 产物引用（对账用只读值对象，§6.146 端口反转配套）。
 *
 * <p>从 attachment 行抽取的「存储地址 + 字节数」最小事实，不含 task 域实体，
 * 保证 system 域零实体泄漏。</p>
 *
 * @param storageUrl 存储地址（如 {@code minio://bucket/key}）；空地址行返回 null
 * @param fileSize   字节数；未知为 null（对账侧按「不校验字节数」处理）
 */
public record ArtifactReference(String storageUrl, Long fileSize) {
}
