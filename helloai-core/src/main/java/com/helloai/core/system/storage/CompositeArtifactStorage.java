package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存储路由（引入）：聚合所有 {@link ArtifactStorage} 实现，对外提供统一入口。
 *
 * <p>写入路由到 {@code helloai.storage.type} 指定的主存储（local/minio）；
 * 读取与可读性判断按 storageUrl 协议前缀分派到对应实现，
 * 保证存量 local:// 附件与新 minio:// 附件同时可读、可下载、可作执行证据。
 * 注入点（AttachmentService / ExecutionArtifactService 等）以本类为 {@code @Primary} 唯一入口。</p>
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CompositeArtifactStorage implements ArtifactStorage {

    private final ArtifactStorageProperties properties;
    private final ObjectProvider<ArtifactStorage> storageProvider;

    @Override
    public String storageType() {
        return "composite";
    }

    @Override
    public StoredArtifact store(String ownerName, Long taskId, Long subTaskId, String fileName, byte[] content) {
        return primary().store(ownerName, taskId, subTaskId, fileName, content);
    }

    @Override
    public byte[] load(String storageUrl) {
        return matching(storageUrl).load(storageUrl);
    }

    @Override
    public boolean supports(String storageUrl) {
        return resolvedStorages().stream().anyMatch(s -> s.supports(storageUrl));
    }

    /** 全部存储实现（排除自身，避免循环解析）。 */
    private List<ArtifactStorage> resolvedStorages() {
        return storageProvider.orderedStream().filter(s -> s != this).toList();
    }

    /** 主存储：storageType 与配置 type 一致的首个实现。 */
    private ArtifactStorage primary() {
        return resolvedStorages().stream()
                .filter(s -> s.storageType().equals(properties.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "未配置存储实现: helloai.storage.type=" + properties.getType()));
    }

    private ArtifactStorage matching(String storageUrl) {
        return resolvedStorages().stream()
                .filter(s -> s.supports(storageUrl))
                .findFirst()
                .orElseThrow(() -> new BizException("无存储实现支持该地址: " + storageUrl));
    }
}
