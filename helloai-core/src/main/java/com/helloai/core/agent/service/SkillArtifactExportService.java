package com.helloai.core.agent.service;

/**
 * 沙箱技能产物「落地为平台附件」的编排（`SKX-11`，2026-10-26）。
 *
 * <p><b>为什么需要它</b>：`SKX-7` 的 {@code readArtifact} 只能把产物字节读进<b>进程内存</b>——
 * 平台能对账、能证明「确实是同一份」，但用户在界面上仍然什么都看不到。要让产物变成
 * <b>可下载的交付物</b>，字节必须走完这条链：</p>
 *
 * <pre>
 * 沙箱字节 → ArtifactStorage.store(...) → storageUrl → AttachmentPort.register(...) → 附件记录
 *            ↑ 本服务负责这两步           → 前端 GET /api/attachments/downloadById/{id}
 * </pre>
 *
 * <p>后两段（附件登记、下载端点）都是既有的，缺的正是本服务这一段。既有范式见
 * {@code ExecutionArtifactServiceImpl#materializeParsed}（正文解析出的文件落地为附件）；
 * 本服务与它的差别只有<b>字节来源</b>：那边来自模型正文，这边来自<b>沙箱内的真实产物文件</b>。</p>
 *
 * <p><b>为什么不做成工具方法</b>：工具类只管「对外形状 + 鉴权闸门」。这里还牵着
 * 归属目录取名、单文件大小闸门、子任务快照读取三件事，放进工具方法会让工具类同时依赖
 * 存储 / 附件 / 子任务三域。</p>
 */
public interface SkillArtifactExportService {

    /**
     * 导出结果（工具层只回这些字段，不外露 {@code storageUrl}）。
     *
     * @param attachmentId 新登记的附件主键（前端下载用）
     * @param fileName     实际落库的文件名（已做安全清洗，可能与你传入的不同）
     * @param mimeType     附件 MIME
     * @param size         字节数
     * @param sha256       内容 SHA-256（与沙箱内文件逐字节一致；可用于和技能 CLI 回执对账）
     */
    record ExportResult(Long attachmentId, String fileName, String mimeType, long size, String sha256) {
    }

    /**
     * 把沙箱内指定路径的产物导出为当前子任务的附件。
     *
     * <p><b>拒绝而非降级</b>：产物不存在、超过单文件上限、沙箱已释放，一律抛 {@code BizException}
     * 并说明原因——静默返回「成功」会让模型以为交付完成，而用户下载不到东西。</p>
     *
     * @param agentId   执行者 Agent ID（仅用于日志与归属兜底；实际归属取子任务的 {@code assignedAgentId}）
     * @param subTaskId 目标子任务 ID
     * @param path      容器内绝对路径（如 {@code /workspace/out/archify.html}）
     * @param fileName  附件文件名（空 ⇒ 取 {@code path} 的文件名部分）
     * @return 导出结果
     */
    ExportResult export(Long agentId, Long subTaskId, String path, String fileName);
}
