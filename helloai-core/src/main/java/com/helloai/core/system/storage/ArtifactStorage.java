package com.helloai.core.system.storage;

/**
 * 产物存储抽象（方案2）：屏蔽 local / minio / s3 等具体介质，
 * 执行链只面向 storageUrl 读写产物内容。
 *
 * <p>当前仅有 {@link LocalArtifactStorage} 一个实现；
 * 未来接入对象存储时新增实现并按 {@code helloai.storage.type} 装配。</p>
 */
public interface ArtifactStorage {

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
     * 按 storageUrl 读取产物内容；地址非法或文件不存在时抛异常。
     */
    byte[] load(String storageUrl);

    /**
     * 是否支持该 storageUrl（按协议前缀判定），
     * 供下载链路区分"本地流式返回"与"302 重定向外部地址"。
     */
    boolean supports(String storageUrl);
}
