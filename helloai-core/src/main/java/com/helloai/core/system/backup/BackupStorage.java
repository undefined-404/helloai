package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.common.config.BackupProperties;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.GetObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份产物的对象存储读写（REF-2.3）—— 落**独立桶**，与产物存储彻底隔离。
 *
 * <p><b>为什么不复用 {@code ArtifactStorage}</b>：
 * <ol>
 *   <li>它**只写配置的那一个桶**（{@code helloai.storage.minio-bucket}），没有"写指定桶"的能力；</li>
 *   <li>更要紧的是**语义与生命周期不同** —— 那个桶参与对账巡检
 *       （{@code ArtifactStorageReconcileServiceImpl} 以 {@code listObjects(bucket, null)} 枚举整桶，
 *       凡无 DB 记录者即孤儿候选）；备份对象在其中会被 {@code orphan-cleanup} 当孤儿删掉。
 *       把它硬塞进"产物"语义，等于给未来的孤儿清理埋一颗雷。</li>
 * </ol>
 * 故这里直接面向 MinIO SDK，桶由 {@link BackupProperties#getBucket()} 指定。</p>
 *
 * <p><b>为什么只支持 MinIO</b>：备份是"异地留存"语义，本地磁盘不构成备份。
 * 存储类型非 {@code minio} 时 {@link #assertAvailable()} **显式报不可用**（不静默降级）——
 * 与 REF-2 的整体取向一致：宁可显式不可用，也不产出"看起来成功其实没备"的备份。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupStorage {

    private final BackupProperties backupProperties;
    private final ArtifactStorageProperties storageProperties;

    private volatile MinioClient client;
    private volatile Boolean bucketReady;

    /** 备份桶名。 */
    public String bucket() {
        return backupProperties.getBucket();
    }

    /**
     * 备份存储可用性前置检查。
     *
     * @throws BizException 存储类型非 minio / 桶不可用——消息给出可读原因
     */
    public void assertAvailable() {
        if (!"minio".equalsIgnoreCase(storageProperties.getType())) {
            throw new BizException("备份功能要求对象存储：当前 helloai.storage.type="
                    + storageProperties.getType() + "，请配为 minio");
        }
        ensureBucket();
    }

    /** 写入对象（内容较小：manifest / 摘要类）。 */
    public void put(String key, byte[] content, String contentType) {
        ensureBucket();
        try {
            client().putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(key)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new BizException("备份对象写入失败: " + key + " -> " + e.getMessage());
        }
    }

    /** 从本地文件上传（用于 dump 归档，避免整档读进内存）。 */
    public void putFile(String key, java.nio.file.Path file, String contentType) {
        ensureBucket();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            long size = java.nio.file.Files.size(file);
            client().putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(key)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new BizException("备份归档上传失败: " + key + " -> " + e.getMessage());
        }
    }

    /** 读取对象内容。 */
    public byte[] get(String key) {
        try (java.io.InputStream in = client().getObject(GetObjectArgs.builder()
                .bucket(bucket()).object(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BizException("备份对象读取失败: " + key + " -> " + e.getMessage());
        }
    }

    /** 对象摘要（对账/保留淘汰用）。 */
    public record BackupObject(String key, long size, OffsetDateTime lastModified) {
    }

    /**
     * 列举前缀下的对象（递归）。
     *
     * <p><b>失败抛异常、绝不静默返回空</b>——"枚举为空"是最危险的静默失败形态：
     * 会让保留淘汰误判为"没有备份"、也会让恢复误判为"备份是空的"。</p>
     */
    public List<BackupObject> list(String prefix) {
        List<BackupObject> out = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = client().listObjects(ListObjectsArgs.builder()
                    .bucket(bucket())
                    .prefix(prefix == null ? "" : prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                Item item = result.get();
                out.add(new BackupObject(item.objectName(), item.size(),
                        item.lastModified() == null ? null : item.lastModified().toOffsetDateTime()));
            }
        } catch (Exception e) {
            throw new BizException("备份对象列举失败: " + e.getMessage());
        }
        return out;
    }

    /** 删除对象；不存在视为成功（幂等，便于保留淘汰重复执行）。 */
    public void delete(String key) {
        try {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(key).build());
        } catch (Exception e) {
            throw new BizException("备份对象删除失败: " + key + " -> " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────

    /** 桶不存在则创建（幂等；结果记忆化，避免每次操作都建）。 */
    private void ensureBucket() {
        if (Boolean.TRUE.equals(bucketReady)) {
            return;
        }
        synchronized (this) {
            if (Boolean.TRUE.equals(bucketReady)) {
                return;
            }
            try {
                if (!client().bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket()).build())) {
                    client().makeBucket(io.minio.MakeBucketArgs.builder().bucket(bucket()).build());
                    // 备份桶**不参与**对账巡检 —— 见类注释
                    log.info("备份桶已创建: {}", bucket());
                }
                bucketReady = Boolean.TRUE;
            } catch (Exception e) {
                throw new BizException("备份桶不可用: " + bucket() + " -> " + e.getMessage());
            }
        }
    }

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = MinioClient.builder()
                            .endpoint(storageProperties.getMinioEndpoint())
                            .credentials(storageProperties.getMinioAccessKey(), storageProperties.getMinioSecretKey())
                            .build();
                }
            }
        }
        return client;
    }
}
