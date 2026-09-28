package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
        } catch (ErrorResponseException e) {
            // 对象/桶不存在 → 404（"文件缺失"）；其余（鉴权、网络、超时）→ 500（"服务故障"）。
            // 二者必须区分：曾有 106 条附件因环境切换悬空，若统一按 500 报，运维无法从状态码
            // 判断到底是"文件真没了"还是"存储服务坏了"。
            throw notFoundOrFailure(e, "产物读取失败", storageUrl);
        } catch (Exception e) {
            throw new BizException("产物读取失败: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(String storageUrl) {
        return storageUrl != null && storageUrl.startsWith(URL_PREFIX);
    }

    @Override
    public boolean exists(String storageUrl) {
        String objectKey = parseObjectKey(storageUrl);
        try {
            client().statObject(StatObjectArgs.builder()
                    .bucket(minioBucketFrom(storageUrl))
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                return false;
            }
            // 鉴权/网络类错误必须抛出，不得当作"不存在"——见接口约定。
            throw new BizException("产物存在性校验失败: " + e.getMessage());
        } catch (Exception e) {
            throw new BizException("产物存在性校验失败: " + e.getMessage());
        }
    }

    @Override
    public void validateAddress(String storageUrl) {
        if (!supports(storageUrl)) {
            return;
        }
        String rest = storageUrl.substring(URL_PREFIX.length());
        int slash = rest.indexOf('/');
        String bucket = slash > 0 ? rest.substring(0, slash) : "";
        String objectKey = slash > 0 ? rest.substring(slash + 1) : "";
        // 桶白名单：storageUrl 的 bucket 段必须是平台配置桶，不允许外部 Agent 用自身注册名当桶。
        if (!minioBucket().equals(bucket)) {
            throw new BizException(400, "storageUrl 的 bucket 段必须为平台桶 '" + minioBucket()
                    + "'，实际为 '" + bucket + "'；正确格式为 "
                    + URL_PREFIX + minioBucket() + "/{ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{文件名}");
        }
        if (objectKey.isBlank() || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new BizException(400, "非法 objectKey: " + objectKey);
        }
    }

    @Override
    public List<StoredObject> listObjects(String bucket, String prefix) {
        List<StoredObject> out = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = client().listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix == null ? "" : prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                Item item = result.get();
                out.add(new StoredObject(bucket, item.objectName(), item.size(),
                        item.lastModified() == null ? null : item.lastModified().toOffsetDateTime()));
            }
        } catch (Exception e) {
            // 枚举失败必须抛出：静默返回空列表会让调用方误以为"桶是空的"
            throw new BizException("产物列表读取失败: " + e.getMessage());
        }
        return out;
    }

    @Override
    public void removeObject(String bucket, String objectKey) {
        try {
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                return; // 幂等：对象已不存在视为删除成功
            }
            throw new BizException("产物删除失败: " + e.getMessage());
        } catch (Exception e) {
            throw new BizException("产物删除失败: " + e.getMessage());
        }
    }

    /** 判定 S3 错误码是否属于"对象/桶不存在"。 */
    static boolean isNotFound(ErrorResponseException e) {
        String code = e.errorResponse() != null ? e.errorResponse().code() : null;
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code);
    }

    /** 对象不存在 → {@code BizException(404)}；其余基础设施故障 → {@code BizException(500)}。 */
    private BizException notFoundOrFailure(ErrorResponseException e, String action, String storageUrl) {
        if (isNotFound(e)) {
            return new BizException(404, action + ": 对象不存在 " + storageUrl);
        }
        return new BizException(action + ": " + e.getMessage());
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
