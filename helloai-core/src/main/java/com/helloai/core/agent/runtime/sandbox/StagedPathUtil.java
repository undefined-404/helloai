package com.helloai.core.agent.runtime.sandbox;

/**
 * stage-in 路径工具（`RV-14` / `RV-15`）：附件注入沙箱的<b>落盘路径与文件名消毒</b>，
 * 全仓唯一事实源。
 *
 * <p><b>为什么需要抽公共</b>：`RV-14` 的模型命令式 stage 工具（{@code InternalLlmExecutorTools}）
 * 与 `RV-15` 的平台自动物化器（{@code com.helloai.core.review.support.ReviewEvidenceMaterializer}）
 * 都要把附件字节写到同一个约定位置 —— 若各持一份常量/消毒实现就是「同一事实双写」，消毒口径一改
 * 两处必然分叉。本类承担：注入目录常量、消毒函数、路径拼装与 SHA-256（对账用），两侧共用。</p>
 *
 * <p><b>为什么落在 {@code /tmp/staged/}</b>：附件事实源在对象存储、沙箱是易失算力且任务终态即销毁；
 * 注入是「审核 turn 内现场物化」。落点必须在<b>四种卷形态（hostPath / PVC / OSSFS / 无卷）下均
 * 为容器可写层</b> —— reviewer 的 {@code /workspace} 有只读卷语义（`SL-10`），{@code /tmp}
 * 不在任何卷上，始终可写。</p>
 *
 * <p><b>路径约定</b>：{@code /tmp/staged/<attachmentId>-<消毒文件名>} —— 前缀带附件主键，
 * 天然防同名冲突（同名必同 id）且可从路径反查归属。</p>
 */
public final class StagedPathUtil {

    /** stage-in 注入目录（`RV-14`）：四种卷形态（hostPath/PVC/OSSFS/无卷）下均为容器可写层。 */
    public static final String STAGED_DIR = "/tmp/staged";

    private StagedPathUtil() {
    }

    /**
     * 拼注入路径：{@code /tmp/staged/<attachmentId>-<消毒文件名>}。
     *
     * @param attachmentId 附件主键（{@code null} ⇒ 前缀省略，仅消毒文件名；正常调用必传）
     * @param fileName     原始文件名（可空/含路径/含 {@code ..}，消毒后落盘）
     */
    public static String pathOf(Long attachmentId, String fileName) {
        if (attachmentId == null) {
            return STAGED_DIR + "/" + sanitizeStagedFileName(fileName);
        }
        return STAGED_DIR + "/" + attachmentId + "-" + sanitizeStagedFileName(fileName);
    }

    /**
     * 注入文件名消毒：去路径分隔与父目录引用，空名回退 {@code attachment.bin}。
     *
     * <p>写入将变成沙箱内可执行内容，文件名必须不能携带路径语义
     * （{@code ../../etc/x} 之类的原始名不得被透传成容器内任意路径）。</p>
     */
    public static String sanitizeStagedFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment.bin";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "_").trim();
        return name.isEmpty() ? "attachment.bin" : name;
    }

    /**
     * UTF-8 字节 SHA-256 十六进制。与 {@code internal_attachment_digest} 同源算法，
     * 物化器返回的 {@code sha256} 可直接与 digest 工具对账 —— 内容就是 MinIO 原件，无人能在
     * 注入环节掉包。
     *
     * @param bytes 文件字节（{@code null} ⇒ {@code null}）
     */
    public static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}