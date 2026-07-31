package com.helloai.core.system.storage;

/**
 * 产物存储抽象（方案2）：屏蔽 local / minio / s3 等具体介质，
 * 执行链只面向 storageUrl 读写产物内容。
 *
 * <p>当前仅有 {@link LocalArtifactStorage} 一个实现；
 * 未来接入对象存储时新增实现并按 {@code helloai.storage.type} 装配。</p>
 */
public interface ArtifactStorage {

    /**
     * 写入产物文件。
     *
     * @param subTaskId 归属子任务 id（参与 objectKey 组织目录）
     * @param fileName  原始文件名（会做安全清洗后落盘）
     * @param content   文件内容字节
     * @return 写入结果（storageUrl/bucket/objectKey/大小）
     */
    StoredArtifact store(Long subTaskId, String fileName, byte[] content);

    /**
     * 按 storageUrl 读取产物内容；地址非法或文件不存在时抛异常。
     */
    byte[] load(String storageUrl);

    /**
     * 是否支持该 storageUrl（按协议前缀判定），
     * 供下载链路区分"本地流式返回"与"302 重定向外部地址"。
     */
    boolean supports(String storageUrl);
}
