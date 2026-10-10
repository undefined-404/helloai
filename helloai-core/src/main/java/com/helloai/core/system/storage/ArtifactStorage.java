package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 产物存储抽象（方案2）：屏蔽 local / minio / s3 等具体介质，
 * 执行链只面向 storageUrl 读写产物内容。
 *
 * <p>当前仅有 {@link LocalArtifactStorage} 一个实现；
 * 未来接入对象存储时新增实现并按 {@code helloai.storage.type} 装配。</p>
 */
public interface ArtifactStorage {

    /**
     * 对象摘要：对账巡检用，只带判定所需字段，不携带内容。
     *
     * @param bucket      对象所属桶
     * @param objectKey   对象键（不含桶名与协议头）
     * @param size        字节数
     * @param lastModified 最后修改时间（为空表示存储未提供，此时清理动作会保守跳过）
     */
    record StoredObject(String bucket, String objectKey, long size, OffsetDateTime lastModified) {
    }


    /** 归属者目录名安全清洗：去路径分隔与控制字符、剥离点前缀、空白兜底 unknown、超长截断。 */
    static String sanitizeOwnerName(String ownerName) {
        String name = ownerName != null ? ownerName.trim() : "";
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        // 防 ".." 之类残留：清洗后再去掉所有连续点前缀
        name = name.replaceAll("^\\.+", "");
        if (name.isBlank()) {
            return "unknown";
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        return name;
    }

    /** 文件名安全清洗：去路径分隔与控制字符，空白兜底 output.md，超长截断保扩展名。 */
    static String sanitizeFileName(String fileName) {
        String name = fileName != null ? fileName.trim() : "";
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        // 防 "..\\" 之类残留：清洗后再去掉所有连续点前缀
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

    /**
     * 存储类型标识（与 {@code helloai.storage.type} 对齐），
     * 供 {@link CompositeArtifactStorage} 路由主存储写入。
     */
    default String storageType() {
        return "unknown";
    }

    /**
     * 写入产物文件。
     *
     * <p>objectKey 统一组织为
     * {@code {ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{safeName}}，
     * 按归属者（执行 Agent 注册名）→ 年 → 月 → 主任务 → 子任务分层，
     * 便于按规律检索与排查；同名产物由 uuid 前缀天然不冲突。</p>
     *
     * @param ownerName 归属者目录名（执行 Agent 注册名，写入前会做安全清洗）
     * @param taskId    归属主任务 id（参与 objectKey 组织目录）
     * @param subTaskId 归属子任务 id（参与 objectKey 组织目录）
     * @param fileName  原始文件名（会做安全清洗后落盘）
     * @param content   文件内容字节
     * @return 写入结果（storageUrl/bucket/objectKey/大小）
     */
    StoredArtifact store(String ownerName, Long taskId, Long subTaskId, String fileName, byte[] content);

    /**
     * 写入<b>无任务上下文</b>的产物（如技能包原始 zip，REF-1.6）。
     *
     * <p>默认实现以 {@code null} 委托五参版——两实现的 objectKey 组织对
     * {@code taskId} / {@code subTaskId} 为 {@code null} 时**本就按 {@code 0} 占位**
     * （{@code LocalArtifactStorage#buildObjectKey} / {@code MinioArtifactStorage#buildObjectKey}），
     * 因此 key 形如 {@code {ownerName}/{yyyy}/{MM}/0/0/{uuid8}-{safeName}}。</p>
     *
     * <p>刻意用 {@code default} 而不改成抽象方法：否则每新增一个存储实现都要重复覆写一遍；
     * 中段的 {@code 0/0} 是既有约定的自然结果，不做特化。</p>
     *
     * @param ownerName 归属者目录名（会做安全清洗；技能包用固定归属名，如 {@code skill-packages}）
     * @param fileName  原始文件名（会做安全清洗后落盘）
     * @param content   文件内容字节
     * @return 写入结果（storageUrl / bucket / objectKey / 大小）
     */
    default StoredArtifact store(String ownerName, String fileName, byte[] content) {
        return store(ownerName, null, null, fileName, content);
    }

    /**
     * 按 storageUrl 读取产物内容；地址非法或文件不存在时抛异常。
     */
    byte[] load(String storageUrl);

    /**
     * 是否支持该 storageUrl（按协议前缀判定），
     * 供下载链路区分"本地流式返回"与"302 重定向外部地址"。
     */
    boolean supports(String storageUrl);

    /**
     * 探测 storageUrl 指向的产物对象当前是否真实存在。
     *
     * <p>供 {@code AttachmentService#register} 在写库前拦掉"有记录无对象"的僵尸附件：
     * 修复前只登记元数据（尤其 MCP {@code uploadArtifact} 仅登记场景）时不校验对象，
     * 注册能成功、事后预览/证据核验才抛错，且已污染附件表。</p>
     *
     * <p><b>默认 {@code true} 是刻意的 fail-open</b>：未覆写本方法的实现（含测试替身）
     * 不因"无法探测"而阻断注册。local/minio 实现覆写为真实探测。</p>
     *
     * <p><b>实现约定</b>：只有"确定不存在"才返回 {@code false}；鉴权失败、网络不可达等
     * 基础设施故障必须抛异常，<u>不得</u>降级为 {@code false}——否则一次配置漂移会被
     * 误读成"对象全部丢失"。</p>
     *
     * @param storageUrl 产物存储地址
     * @return true=存在或无法判定；false=确定不存在
     */
    default boolean exists(String storageUrl) {
        return true;
    }

    /**
     * 校验 storageUrl 是否是本实现可接受的合法地址（协议格式、归属桶等），
     * 不合法时抛 {@link com.helloai.common.base.BizException}(400)。
     *
     * <p>用于把"地址写错"这类调用方错误提前到写入前，典型场景是外部 Agent 经 MCP 登记的
     * storageUrl 把自身注册名当成 bucket（{@code minio://trae-executor/...}），
     * 导致附件只能登记、永远读不出内容。</p>
     *
     * <p>默认放行：不匹配本实现协议的地址由上层跳过（如外部 {@code https://} 地址
     * 在"仅登记"场景下合法）。</p>
     *
     * @param storageUrl 产物存储地址
     */
    default void validateAddress(String storageUrl) {
    }

    /**
     * 列出指定 bucket 下指定前缀的全部对象摘要（供定期对账巡检枚举桶内真实对象）。
     *
     * <p><b>默认返回空列表是刻意的 fail-safe</b>：不支持枚举的实现参与对账时，只会把
     * "桶内对象"算作 0——方向是把 DB 记录判成悬空（只告警），而**不会**把任何对象判成
     * 孤儿（会删除）。即"看不到"绝不导致删除。</p>
     *
     * @param bucket 目标桶
     * @param prefix 对象键前缀；null / 空表示不限（整桶枚举）
     * @return 对象摘要列表（绝不返回 null）
     */
    default List<StoredObject> listObjects(String bucket, String prefix) {
        return List.of();
    }

    /**
     * 删除指定对象；对象不存在视为成功（幂等，便于对账重复执行）。
     *
     * <p><b>默认抛异常是刻意的 fail-close</b>：未显式支持删除的实现不得被静默当作
     * "删除成功"——否则对账会报告"已清理 N 个孤儿"而实际一个都没删，制造假象。</p>
     *
     * @param bucket    目标桶
     * @param objectKey 对象键
     * @throws BizException 该实现不支持删除，或删除失败
     */
    default void removeObject(String bucket, String objectKey) {
        throw new BizException(400, "该存储不支持删除对象: " + bucket + "/" + objectKey);
    }
}
