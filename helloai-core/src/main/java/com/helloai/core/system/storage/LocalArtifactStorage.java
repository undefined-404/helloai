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
 * <p>objectKey 组织为 {@code {subTaskId}/{yyyyMMdd}/{uuid8}-{safeName}}，
 * 同名产物天然不冲突；load 时对解析出的路径做归一化校验，
 * 拒绝越出根目录的路径穿越。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalArtifactStorage implements ArtifactStorage {

    static final String URL_PREFIX = "local://";

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ArtifactStorageProperties properties;

    @Override
    public StoredArtifact store(Long subTaskId, String fileName, byte[] content) {
        String safeName = sanitizeFileName(fileName);
        String objectKey = (subTaskId != null ? subTaskId : 0L)
                + "/" + LocalDate.now().format(DATE_DIR)
                + "/" + UUID.randomUUID().toString().substring(0, 8) + "-" + safeName;
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

    /** 文件名安全清洗：去路径分隔与控制字符，空白兜底 output.md，超长截断。 */
    static String sanitizeFileName(String fileName) {
        String name = fileName != null ? fileName.trim() : "";
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        // 防 "..\" 之类残留：清洗后再去掉所有连续点前缀
        name = name.replaceAll("^\\.+", "");
        if (name.isBlank()) {
            name = "output.md";
        }
        if (name.length() > 100) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, Math.min(100 - ext.length(), name.length())) + ext;
        }
        return name;
    }
}
