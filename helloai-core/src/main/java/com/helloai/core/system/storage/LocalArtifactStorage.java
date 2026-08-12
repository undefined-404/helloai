package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘产物存储：storageUrl 形如 {@code local://{bucket}/{objectKey}}，
 * 实际文件位于 {@code {local-base-dir}/{objectKey}}。
 *
 * <p>objectKey 组织为
 * {@code {ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{safeName}}，
 * 与 {@link MinioArtifactStorage} 保持一致的目录分层；load 时对解析出的路径
 * 做归一化校验，拒绝越出根目录的路径穿越。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalArtifactStorage implements ArtifactStorage {

    static final String URL_PREFIX = "local://";

    private static final DateTimeFormatter YEAR_DIR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("MM");

    private final ArtifactStorageProperties properties;

    @Override
    public String storageType() {
        return "local";
    }

    @Override
    public StoredArtifact store(String ownerName, Long taskId, Long subTaskId, String fileName, byte[] content) {
        String safeName = ArtifactStorage.sanitizeFileName(fileName);
        String objectKey = buildObjectKey(ownerName, taskId, subTaskId, safeName);
        Path baseDir = baseDir();
        Path target = baseDir.resolve(objectKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BizException("非法产物路径: " + objectKey);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new BizException("产物写入本地存储失败: " + e.getMessage());
        }
        String storageUrl = URL_PREFIX + properties.getBucket() + "/" + objectKey;
        log.info("产物落盘: subTaskId={}, objectKey={}, size={}", subTaskId, objectKey, content.length);
        return new StoredArtifact(storageUrl, properties.getBucket(), objectKey, content.length);
    }

    @Override
    public byte[] load(String storageUrl) {
        String objectKey = parseObjectKey(storageUrl);
        Path baseDir = baseDir();
        Path target = baseDir.resolve(objectKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BizException("非法产物地址: " + storageUrl);
        }
        if (!Files.isRegularFile(target)) {
            throw new BizException(404, "产物文件不存在: " + storageUrl);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BizException("产物读取失败: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(String storageUrl) {
        return storageUrl != null && storageUrl.startsWith(URL_PREFIX);
    }

    /** 从 local://{bucket}/{objectKey} 解析 objectKey；格式非法抛 BizException。 */
    private String parseObjectKey(String storageUrl) {
        if (!supports(storageUrl)) {
            throw new BizException("非 local 存储地址: " + storageUrl);
        }
        String rest = storageUrl.substring(URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            throw new BizException("非法产物地址: " + storageUrl);
        }
        return rest.substring(slash + 1);
    }

    private Path baseDir() {
        return Path.of(properties.getLocalBaseDir()).toAbsolutePath().normalize();
    }

    private String buildObjectKey(String ownerName, Long taskId, Long subTaskId, String safeName) {
        LocalDate now = LocalDate.now();
        return ArtifactStorage.sanitizeOwnerName(ownerName)
                + "/" + now.format(YEAR_DIR)
                + "/" + now.format(MONTH_DIR)
                + "/" + (taskId != null ? taskId : 0L)
                + "/" + (subTaskId != null ? subTaskId : 0L)
                + "/" + UUID.randomUUID().toString().substring(0, 8) + "-" + safeName;
    }
}
