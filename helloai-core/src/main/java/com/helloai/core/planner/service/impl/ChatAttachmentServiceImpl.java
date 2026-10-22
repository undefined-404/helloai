package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.planner.attachment.ChatAttachmentParseWorker;
import com.helloai.core.planner.attachment.ChatAttachmentProperties;
import com.helloai.core.planner.attachment.ContentKind;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 会话附件服务实现（方案 §3.3 / §3.7）。
 *
 * <p><b>职责边界</b>：只做「校验 + 落库 + 存储 + 投递解析」，不做正文提取（那是
 * {@link ChatAttachmentParseWorker} 与 {@code DocumentParser} 的事），也不做 Prompt 拼装
 * （那是 {@code ClarifyPromptRenderServiceImpl} 的事）。</p>
 *
 * <p><b>事务与副作用顺序</b>：先全量校验（大小/类型/会话配额）→ 写对象存储 → 落库 → 事务提交后
 * 才投递解析。解析必须等提交后再投递，否则 worker 线程可能读不到未提交的行（读到 null 直接跳过）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentServiceImpl extends ServiceImpl<ChatAttachmentMapper, ChatAttachment>
        implements ChatAttachmentService {

    /**
     * 内联预览大小上限 5MB。
     *
     * <p>与子任务级附件同口径（{@code AttachmentServiceImpl#PREVIEW_MAX_SIZE_BYTES}）：
     * 更大的文件浏览器渲染会明显卡顿，应引导走下载而非把整份字节穿过 HTTP 响应。</p>
     */
    private static final long PREVIEW_MAX_SIZE_BYTES = 5L * 1024 * 1024;

    /** 可内联预览的 MIME 前缀 / 精确值（与子任务级附件同口径）。 */
    private static final Set<String> PREVIEWABLE_MIME_PREFIXES =
            Set.of("text/", "image/", "application/json", "application/xml", "application/yaml");
    private static final Set<String> PREVIEWABLE_MIME_EXACT = Set.of("application/pdf");

    /**
     * Tika facade 复用一个静态实例：它只做类型探测，无可变状态；
     * 每请求 new 一个会反复加载 detector 依赖。
     */
    private static final Tika TIKA = new Tika();

    private final ArtifactStorage artifactStorage;
    private final ChatAttachmentProperties properties;
    private final ChatAttachmentParseWorker parseWorker;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ChatAttachment> uploadBatch(Long conversationId, String clientToken, List<UploadFile> files) {
        validateBatch(conversationId, clientToken, files);

        List<ChatAttachment> saved = new ArrayList<>(files.size());
        List<Long> toParse = new ArrayList<>(files.size());
        String owner = ownerName(conversationId, clientToken);

        for (UploadFile file : files) {
            // 1) 魔数探测优先于浏览器声明：改扩展名的文件按真实类型处理
            String mime = detectMime(file);
            // 2) 同会话内容去重：同一份文件重复上传不重复存、不重复解析
            String hash = sha256(file.content());
            if (conversationId != null) {
                ChatAttachment existing = baseMapper.findByHash(conversationId, hash);
                if (existing != null) {
                    log.info("会话附件去重命中: conversationId={}, hash={}, fileName={}, existingId={}",
                            conversationId, hash, file.fileName(), existing.getId());
                    saved.add(existing);
                    continue;
                }
            }
            // 3) 落对象存储 → 落库（PENDING）→ 记待解析
            StoredArtifact stored = artifactStorage.store(owner, file.fileName(), file.content());
            ChatAttachment attachment = new ChatAttachment();
            attachment.setConversationId(conversationId);
            attachment.setClientToken(clientToken);
            attachment.setFileName(file.fileName());
            attachment.setFileType(extensionOf(file.fileName()));
            attachment.setMimeType(mime);
            attachment.setFileSize((long) file.content().length);
            attachment.setContentHash(hash);
            attachment.setBucketName(stored.bucketName());
            attachment.setObjectKey(stored.objectKey());
            attachment.setStorageUrl(stored.storageUrl());
            attachment.setParseStatus(ChatAttachmentParseStatus.PENDING);
            save(attachment);
            saved.add(attachment);
            toParse.add(attachment.getId());
            log.info("会话附件上传: id={}, conversationId={}, clientToken={}, fileName={}, mime={}, size={}",
                    attachment.getId(), conversationId, clientToken, file.fileName(), mime, file.content().length);
        }

        // 4) 提交后再投递解析（事务内的异步线程读不到未提交行）
        afterCommit(() -> toParse.forEach(parseWorker::parseAsync));
        return saved;
    }

    @Override
    public List<ChatAttachment> list(Long conversationId, String clientToken) {
        if (conversationId != null) {
            return baseMapper.listByConversationId(conversationId);
        }
        if (clientToken != null && !clientToken.isBlank()) {
            return baseMapper.listByClientToken(clientToken);
        }
        return List.of();
    }

    @Override
    public List<ChatAttachment> listByConversation(Long conversationId) {
        return conversationId == null ? List.of() : baseMapper.listByConversationId(conversationId);
    }

    @Override
    public ChatAttachment getByIdRequired(Long id) {
        ChatAttachment attachment = id == null ? null : getById(id);
        if (attachment == null) {
            throw new BizException(404, "附件不存在: " + id);
        }
        return attachment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 幂等（对齐 AttachmentController#deleteById 口径）：已删或不存在直接返回成功，
        // 外部重试/前端重复点击不会拿到 404——删除的目标状态已经达成，报错反而制造噪音。
        ChatAttachment attachment = id == null ? null : getById(id);
        if (attachment == null) {
            log.info("会话附件删除跳过（不存在或已删，幂等）: id={}", id);
            return;
        }
        removeById(id);
        // 对象回收放提交后：回滚时不能把对象删掉（DB 行还在，对象却没了 = 僵尸附件）
        afterCommit(() -> purgeObjectQuietly(attachment));
        log.info("会话附件删除: id={}, conversationId={}, fileName={}",
                id, attachment.getConversationId(), attachment.getFileName());
    }

    @Override
    public PreviewPayload preview(Long id) {
        ChatAttachment attachment = getByIdRequired(id);
        Long size = attachment.getFileSize();
        if (size != null && size > PREVIEW_MAX_SIZE_BYTES) {
            throw new BizException(413, "附件超过 5MB，浏览器内联预览已跳过，请使用下载");
        }
        byte[] content = artifactStorage.load(attachment.getStorageUrl());
        String mime = detectMime(new UploadFile(attachment.getFileName(), attachment.getMimeType(), content));
        if (!isPreviewableMime(mime)) {
            throw new BizException(413, "该类型不支持浏览器内联预览，请使用下载: " + mime);
        }
        return new PreviewPayload(content, mime, attachment.getFileName());
    }

    @Override
    public int claimByClientToken(String clientToken, Long conversationId) {
        if (clientToken == null || clientToken.isBlank() || conversationId == null) {
            return 0;
        }
        int claimed = baseMapper.claimByClientToken(clientToken, conversationId);
        if (claimed > 0) {
            log.info("会话附件认领: clientToken={}, conversationId={}, count={}", clientToken, conversationId, claimed);
        }
        return claimed;
    }

    // ══════════════════════════════════════════════════════════════
    //  校验
    // ══════════════════════════════════════════════════════════════

    /** 批量前置校验：把「第 3 个文件超限导致前 2 个白传」挡在写任何数据之前。 */
    private void validateBatch(Long conversationId, String clientToken, List<UploadFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }
        if (conversationId == null && (clientToken == null || clientToken.isBlank())) {
            throw new BizException(400, "缺少归属：conversationId 与 clientToken 至少填一个");
        }
        if (files.size() > properties.getMaxFilesPerUpload()) {
            throw new BizException(400, "单次最多上传 " + properties.getMaxFilesPerUpload()
                    + " 个文件，本次 " + files.size() + " 个");
        }
        long totalBytes = 0L;
        for (UploadFile file : files) {
            if (file.content() == null || file.content().length == 0) {
                throw new BizException(400, "文件内容为空: " + file.fileName());
            }
            if (file.content().length > properties.getMaxFileSizeBytes()) {
                throw new BizException(400, "文件超过 "
                        + (properties.getMaxFileSizeBytes() / 1024 / 1024) + "MB 上限: "
                        + file.fileName() + "（" + (file.content().length / 1024 / 1024) + "MB）");
            }
            if (ContentKind.of(detectMime(file), file.fileName()) == ContentKind.UNSUPPORTED) {
                throw new BizException(400, "不支持的文件类型：" + file.fileName()
                        + "（支持 PDF / Office / HTML / 文本 / 代码 / 图片）");
            }
            totalBytes += file.content().length;
        }
        if (conversationId != null) {
            int existingCount = baseMapper.countByConversationId(conversationId);
            long existingBytes = baseMapper.sumFileSizeByConversationId(conversationId);
            if (existingCount + files.size() > properties.getMaxFilesPerConversation()) {
                throw new BizException(400, "单会话最多 " + properties.getMaxFilesPerConversation()
                        + " 个附件（已有 " + existingCount + " 个），请先删除旧文件");
            }
            if (existingBytes + totalBytes > properties.getMaxTotalBytesPerConversation()) {
                throw new BizException(400, "单会话附件总量超过 "
                        + (properties.getMaxTotalBytesPerConversation() / 1024 / 1024) + "MB 上限，请先删除旧文件");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════

    /**
     * 类型探测：Tika 魔数优先，探测不出（octet-stream）才退回浏览器声明。
     *
     * <p>为什么以魔数为准：浏览器对 {@code .md} / {@code .java} 常给
     * {@code application/octet-stream}，纯信声明会把文本文件判成二进制、白白 SKIPPED。</p>
     */
    private static String detectMime(UploadFile file) {
        try {
            String detected = TIKA.detect(file.content(), file.fileName());
            if (detected != null && !detected.isBlank() && !"application/octet-stream".equals(detected)) {
                return detected;
            }
        } catch (RuntimeException e) {
            log.warn("类型探测失败，回退浏览器声明: fileName={}, err={}", file.fileName(), e.toString());
        }
        return file.mimeType() != null && !file.mimeType().isBlank()
                ? file.mimeType() : "application/octet-stream";
    }

    private static boolean isPreviewableMime(String mime) {
        if (mime == null) {
            return false;
        }
        for (String prefix : PREVIEWABLE_MIME_PREFIXES) {
            if (mime.startsWith(prefix)) {
                return true;
            }
        }
        return PREVIEWABLE_MIME_EXACT.contains(mime);
    }

    /** 归属目录名：已有会话按 ID、临时批次按 token（storage 侧会再做安全清洗）。 */
    private static String ownerName(Long conversationId, String clientToken) {
        return conversationId != null ? "chat-" + conversationId : "chat-tmp-" + clientToken;
    }

    /** 扩展名（小写、不含点）；无扩展名返回空串。 */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase();
    }

    /** sha256 十六进制（会话内去重键）。 */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，走到这里说明运行环境被破坏，不值得静默降级
            throw new BizException("计算文件指纹失败: " + e.getMessage());
        }
    }

    /** 对象回收：失败只告警——删除语义已由软删保证，对象残留交给存储对账兜底。 */
    private void purgeObjectQuietly(ChatAttachment attachment) {
        try {
            artifactStorage.removeObject(attachment.getBucketName(), attachment.getObjectKey());
        } catch (RuntimeException e) {
            log.warn("会话附件对象回收失败（不影响删除，交给对账兜底）: id={}, bucket={}, key={}, err={}",
                    attachment.getId(), attachment.getBucketName(), attachment.getObjectKey(), e.toString());
        }
    }

    /** 事务提交后执行；无事务上下文时立即执行（单元测试与直接调用场景）。 */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
