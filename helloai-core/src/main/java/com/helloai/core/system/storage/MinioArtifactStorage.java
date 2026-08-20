package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * MinIO 对象存储产物存储：storageUrl 形如 {@code minio://{bucket}/{objectKey}}。
 *
 * <p>objectKey 组织与 {@link LocalArtifactStorage} 一致：
 * {@code {ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{safeName}}，
 * 便于按归属者/年月/主任务检索。</p>
 *
 * <p>MinIO 客户端懒创建，bucket 首次写入前自动 ensure（makeBucketIfNotExists）。
 *  minio:// 附件由 {@link CompositeArtifactStorage} 路由到本实现直读，
 * 下载与执行证据检查均不再区分本地/外部存储。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioArtifactStorage implements ArtifactStorage {

    static final String URL_PREFIX = "minio://";

    private static final DateTimeFormatter YEAR_DIR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("MM");

    private final ArtifactStorageProperties properties;

    /** 懒创建客户端；包级可见仅供测试注入 mock。 */
    MinioClient client;
    private volatile boolean bucketEnsured;

    @Override
    public String storageType() {
        return "minio";
    }

    @Override
    public StoredArtifact store(String ownerName, Long taskId, Long subTaskId, String fileName, byte[] content) {
        String safeName = ArtifactStorage.sanitizeFileName(fileName);
        String objectKey = buildObjectKey(ownerName, taskId, subTaskId, safeName);
        try {
            ensureBucket();
            client().putObject(PutObjectArgs.builder()
                    .bucket(minioBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(detectContentType(safeName))
                    .build());
        } catch (Exception e) {
            throw new BizException("产物写入 MinIO 失败: " + e.getMessage());
        }
        String storageUrl = URL_PREFIX + minioBucket() + "/" + objectKey;
        log.info("产物写入 MinIO: objectKey={}, size={}", objectKey, content.length);
        return new StoredArtifact(storageUrl, minioBucket(), objectKey, content.length);
    }

    @Override
    public byte[] load(String storageUrl) {
        String objectKey = parseObjectKey(storageUrl);
        try (InputStream in = client().getObject(GetObjectArgs.builder()
                .bucket(minioBucketFrom(storageUrl))
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BizException("产物读取失败: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(String storageUrl) {
        return storageUrl != null && storageUrl.startsWith(URL_PREFIX);
    }

    /** 从 minio://{bucket}/{objectKey} 解析 objectKey；格式非法抛 BizException。 */
    private String parseObjectKey(String storageUrl) {
        if (!supports(storageUrl)) {
            throw new BizException("非 MinIO 存储地址: " + storageUrl);
        }
        String rest = storageUrl.substring(URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            throw new BizException("非法产物地址: " + storageUrl);
        }
        return rest.substring(slash + 1);
    }

    private String minioBucketFrom(String storageUrl) {
        String rest = storageUrl.substring(URL_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : minioBucket();
    }

    private String minioBucket() {
        return properties.getMinioBucket();
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

    private void ensureBucket() throws Exception {
        if (bucketEnsured) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured) {
                return;
            }
            boolean exists = client().bucketExists(BucketExistsArgs.builder().bucket(minioBucket()).build());
            if (!exists) {
                client().makeBucket(MakeBucketArgs.builder().bucket(minioBucket()).build());
                log.info("MinIO bucket 已创建: {}", minioBucket());
            }
            bucketEnsured = true;
        }
    }

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = MinioClient.builder()
                            .endpoint(properties.getMinioEndpoint())
                            .credentials(properties.getMinioAccessKey(), properties.getMinioSecretKey())
                            .build();
                }
            }
        }
        return client;
    }

    private String detectContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return "text/plain";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }
}
