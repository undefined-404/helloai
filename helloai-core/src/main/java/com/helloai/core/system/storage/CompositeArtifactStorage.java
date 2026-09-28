package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

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
    // 阶段五保留：多实现路由需 orderedStream 按 @Order 探测全部候选并按 URL 前缀匹配，
    // Optional 只能取单值，无法表达"候选集合"，故保留 ObjectProvider
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

    @Override
    public boolean exists(String storageUrl) {
        // 无实现支持 = 平台管不到这个地址（如外部 https://），不判定、不阻断
        return matchingOrNull(storageUrl).map(s -> s.exists(storageUrl)).orElse(true);
    }

    @Override
    public void validateAddress(String storageUrl) {
        matchingOrNull(storageUrl).ifPresent(s -> s.validateAddress(storageUrl));
    }

    /**
     * 对象枚举与删除都面向<b>主存储</b>（{@code helloai.storage.type} 的路由目标）：
     * 对账巡检清的是平台自己的桶，不跨实现扫别的介质。
     */
    @Override
    public List<StoredObject> listObjects(String bucket, String prefix) {
        return primary().listObjects(bucket, prefix);
    }

    @Override
    public void removeObject(String bucket, String objectKey) {
        primary().removeObject(bucket, objectKey);
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
        return matchingOrNull(storageUrl)
                .orElseThrow(() -> new BizException("无存储实现支持该地址: " + storageUrl));
    }

    private Optional<ArtifactStorage> matchingOrNull(String storageUrl) {
        return resolvedStorages().stream()
                .filter(s -> s.supports(storageUrl))
                .findFirst();
    }
}
