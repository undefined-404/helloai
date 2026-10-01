package com.helloai.core.system.storage.impl;

import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ArtifactReferencePort;
import com.helloai.core.system.storage.ArtifactReconcileReport;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.ArtifactStorageReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储对账巡检实现。
 *
 * <p>算法：</p>
 * <ol>
 *   <li>经 {@link ArtifactReferencePort}（§6.146 端口反转，task 域实现）取 attachment
 *       <b>全量行（含逻辑删除）</b>，按"当前协议 + 当前桶"筛出被引用的
 *       objectKey 集合（保守口径：任何一行指向即视为被引用）。</li>
 *   <li>枚举桶内真实对象（{@code listObjects}），建 objectKey → 摘要 索引。</li>
 *   <li>双向比对出悬空 / 孤儿 / 字节不符。</li>
 *   <li>清理开关开启时，按时间窗与单轮上限删除孤儿；单个删除失败只记 error，
 *       不影响本轮其余对象与下一轮调度。</li>
 * </ol>
 *
 * <p>日志策略：一致时一行 INFO；不一致时一行汇总 WARN + 分方向清单（每方向最多
 * 展开 {@value #MAX_LISTED} 项，避免一次对账刷屏）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactStorageReconcileServiceImpl implements ArtifactStorageReconcileService {

    /** 每个方向的清单最多展开多少项（其余仅计数）。 */
    static final int MAX_LISTED = 50;

    private static final String MINIO_TYPE = "minio";
    private static final String MINIO_PROTOCOL = "minio://";
    private static final String LOCAL_PROTOCOL = "local://";

    private final ArtifactStorage artifactStorage;
    private final ArtifactStorageProperties properties;
    private final ArtifactReferencePort artifactReferencePort;

    @Override
    public ArtifactReconcileReport reconcile() {
        boolean minio = MINIO_TYPE.equalsIgnoreCase(properties.getType());
        String protocol = minio ? MINIO_PROTOCOL : LOCAL_PROTOCOL;
        String bucket = minio ? properties.getMinioBucket() : properties.getBucket();

        List<ArtifactReference> rows = artifactReferencePort.listAllIncludingDeleted();

        // 1) 被引用对象集合（含逻辑删除行 → 口径保守，宁可漏判孤儿也不误删）
        Map<String, Long> referencedSize = new LinkedHashMap<>();
        for (ArtifactReference row : rows) {
            String key = objectKeyOf(row.storageUrl(), protocol, bucket);
            if (key != null) {
                referencedSize.putIfAbsent(key, row.fileSize() == null ? -1L : row.fileSize());
            }
        }

        // 2) 桶内真实对象
        List<ArtifactStorage.StoredObject> objects = artifactStorage.listObjects(bucket, null);
        Map<String, ArtifactStorage.StoredObject> objectIndex = new LinkedHashMap<>();
        for (ArtifactStorage.StoredObject object : objects) {
            objectIndex.put(object.objectKey(), object);
        }

        // 3) 双向比对
        List<String> dangling = new ArrayList<>();
        List<String> sizeMismatch = new ArrayList<>();
        for (Map.Entry<String, Long> entry : referencedSize.entrySet()) {
            ArtifactStorage.StoredObject object = objectIndex.get(entry.getKey());
            if (object == null) {
                dangling.add(entry.getKey());
            } else if (entry.getValue() != null && entry.getValue() >= 0 && object.size() != entry.getValue()) {
                sizeMismatch.add(entry.getKey() + " db=" + entry.getValue() + "B storage=" + object.size() + "B");
            }
        }

        List<String> orphaned = new ArrayList<>();
        for (ArtifactStorage.StoredObject object : objects) {
            if (!referencedSize.containsKey(object.objectKey())) {
                orphaned.add(object.objectKey());
            }
        }

        // 4) 清理（默认关闭；开启也受时间窗与上限约束）
        List<String> removed = properties.isOrphanCleanupEnabled()
                ? cleanupOrphans(bucket, orphaned, objectIndex)
                : List.of();

        ArtifactReconcileReport report = new ArtifactReconcileReport(protocol, bucket,
                rows.size(), objects.size(), dangling, orphaned, sizeMismatch, removed,
                properties.isOrphanCleanupEnabled());
        logReport(report);
        return report;
    }

    /**
     * 清理孤儿对象。三重保险：调用方已确认开关开启；此处再校验对象最后修改时间早于
     * 时间窗（规避"已上传未注册"窗口、也规避时间未知的对象）、单轮删除数不超上限。
     */
    private List<String> cleanupOrphans(String bucket, List<String> orphaned,
                                        Map<String, ArtifactStorage.StoredObject> objectIndex) {
        OffsetDateTime threshold = OffsetDateTime.now().minusHours(properties.getOrphanMinAgeHours());
        int limit = properties.getOrphanMaxDeletesPerRound();
        List<String> removed = new ArrayList<>();
        int skippedTooNew = 0;

        for (String key : orphaned) {
            if (removed.size() >= limit) {
                log.warn("孤儿清理已达单轮上限 {}，剩余留待下一轮（避免误判批量损伤）", limit);
                break;
            }
            ArtifactStorage.StoredObject object = objectIndex.get(key);
            OffsetDateTime lastModified = object == null ? null : object.lastModified();
            if (lastModified == null || lastModified.isAfter(threshold)) {
                // 时间不可知 / 还没过时间窗：保守跳过。可能是"对象已上传、attachment 尚未写入"的正常中间态
                skippedTooNew++;
                continue;
            }
            try {
                artifactStorage.removeObject(bucket, key);
                removed.add(key);
            } catch (Exception e) {
                // 单个失败不影响其余对象与下一轮
                log.error("孤儿清理失败: objectKey={}, err={}", key, e.getMessage());
            }
        }

        if (skippedTooNew > 0) {
            log.info("孤儿清理跳过 {} 个对象（最后修改时间未超 {} 小时时间窗或时间不可知）",
                    skippedTooNew, properties.getOrphanMinAgeHours());
        }
        return removed;
    }

    private void logReport(ArtifactReconcileReport report) {
        if (report.consistent()) {
            log.info("存储对账一致: bucket={} 附件行={} 桶内对象={}",
                    report.bucket(), report.attachmentRows(), report.objectCount());
            return;
        }
        log.warn("存储对账发现不一致: bucket={} 附件行={} 桶内对象={} 悬空={} 孤儿={} 字节不符={}",
                report.bucket(), report.attachmentRows(), report.objectCount(),
                report.danglingCount(), report.orphanedCount(), report.sizeMismatchCount());
        logBounded("悬空（DB 有记录、对象缺失，预览/核验会 404）", report.dangling());
        logBounded("孤儿（对象存在、无任何附件行引用，含逻辑删除）", report.orphaned());
        logBounded("字节数不符", report.sizeMismatch());
        if (report.cleanupEnabled()) {
            log.warn("孤儿清理已开启: 本轮删除 {} 个对象", report.removedCount());
            logBounded("已删除孤儿", report.removed());
        } else {
            log.warn("孤儿清理未开启（orphan-cleanup-enabled=false），以上孤儿仅报告不删除");
        }
    }

    private void logBounded(String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        log.warn("  {}：共 {} 项，展开前 {}：", title, items.size(), Math.min(MAX_LISTED, items.size()));
        items.stream().limit(MAX_LISTED).forEach(item -> log.warn("    - {}", item));
        if (items.size() > MAX_LISTED) {
            log.warn("    …… 其余 {} 项省略", items.size() - MAX_LISTED);
        }
    }

    /**
     * 从 storageUrl 提取"当前协议 + 当前桶"下的 objectKey；不属于当前对账范围返回 null。
     * （别的桶/别的协议的记录对应的对象本来就不在本轮枚举范围内，不参与对账。）
     */
    static String objectKeyOf(String storageUrl, String protocol, String bucket) {
        if (storageUrl == null) {
            return null;
        }
        String head = protocol + bucket + "/";
        if (!storageUrl.startsWith(head)) {
            return null;
        }
        String objectKey = storageUrl.substring(head.length());
        return objectKey.isBlank() ? null : objectKey;
    }
}
