package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.planner.attachment.ChatAttachmentParseWorker;
import com.helloai.common.config.ChatAttachmentProperties;
import com.helloai.core.planner.attachment.ContentKind;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.system.port.ArtifactReferencePort;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
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
    /**
     * 跨表回收护栏（G-047）：回收会话附件对象前查 {@code attachment} 表是否仍活跃引用同一 objectKey。
     * 走 system 域端口而非直捅 task 域 Mapper（§6 依赖方向 + 判据 16 端口归属看方向）。
     */
    private final ArtifactReferencePort artifactReferencePort;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ChatAttachment> uploadBatch(Long conversationId, String clientToken, List<UploadFile> files) {
        return uploadBatch(conversationId, clientToken, files, null);
    }

    @Override
    public List<ChatAttachment> uploadBatch(Long conversationId, String clientToken,
                                            List<UploadFile> files, Long uploaderUserId) {
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
            attachment.setUploaderUserId(uploaderUserId);
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
    public ChatAttachment saveArtifact(Long conversationId, Long uploaderUserId,
                                       String fileName, String mimeType, byte[] content) {
        if (conversationId == null) {
            throw new BizException("产物附件必须挂在会话上（conversationId 为空）");
        }
        if (content == null || content.length == 0) {
            throw new BizException("产物内容为空，拒绝登记为附件");
        }
        String safeName = ArtifactStorage.sanitizeFileName(fileName);
        String mime = (mimeType == null || mimeType.isBlank())
                ? mimeOfExtension(safeName) : mimeType;
        StoredArtifact stored = artifactStorage.store(ownerName(conversationId, null), safeName, content);
        ChatAttachment attachment = new ChatAttachment();
        attachment.setConversationId(conversationId);
        attachment.setUploaderUserId(uploaderUserId);
        attachment.setFileName(safeName);
        attachment.setFileType(extensionOf(safeName));
        attachment.setMimeType(mime);
        attachment.setFileSize((long) content.length);
        attachment.setContentHash(sha256(content));
        attachment.setBucketName(stored.bucketName());
        attachment.setObjectKey(stored.objectKey());
        attachment.setStorageUrl(stored.storageUrl());
        // 产物不进 Prompt 上下文（840KB HTML 注入既无意义又挤预算）：
        // 用 SKIPPED 表达「不要注入它的正文」，与图片/扫描件共用同一状态语义。
        attachment.setParseStatus(ChatAttachmentParseStatus.SKIPPED);
        save(attachment);
        log.info("对话产物已登记为附件: id={}, conversationId={}, fileName={}, mime={}, size={}",
                attachment.getId(), conversationId, safeName, mime, content.length);
        return attachment;
    }

    /** 扩展名 → MIME 兜底（产物由平台生成，用方通常已给 mime；这里只处理缺省）。 */
    private static String mimeOfExtension(String fileName) {
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            case "md" -> "text/markdown";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            default -> "application/octet-stream";
        };
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
    public List<ChatAttachment> listLatestByConversation(Long conversationId, int limit) {
        if (conversationId == null || limit <= 0) {
            return List.of();
        }
        return baseMapper.listLatestByConversationId(conversationId, limit);
    }

    @Override
    public List<ChatAttachment> listByUploaderUserId(Long userId, int limit) {
        return userId == null || limit <= 0 ? List.of()
                : baseMapper.listByUploaderUserId(userId, limit);
    }

    @Override
    public List<ChatAttachment> listStorageRefsIncludingDeleted() {
        return baseMapper.selectStorageRefAllIncludingDeleted();
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
        // `G-041`：物理删行。getById 已按 @TableLogic 过滤掉 deleted=1 的行，
        // 走到这里的必然是活跃行；用 Mapper 自定义 DELETE 绕开内置软删改写。
        baseMapper.physicalDeleteById(id);
        // 对象回收放提交后：回滚时不能把对象删掉（DB 行还在，对象却没了 = 僵尸附件）
        afterCommit(() -> purgeObjects(List.of(attachment)));
        log.info("会话附件删除: id={}, conversationId={}, fileName={}",
                id, attachment.getConversationId(), attachment.getFileName());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>顺序不能换</b>：先捞引用 → 再物理删行 → 提交后回收。反过来（先删行再捞）就捞不到
     * 任何东西，对象会全部滞留成孤儿；先回收再删行则会制造「对象没了、行还在」的僵尸状态
     * （事务回滚后无法恢复字节）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteByConversation(Long conversationId) {
        if (conversationId == null) {
            // 不静默降级为「全表」：这是级联删除路径，误传 null 会删掉所有会话的附件
            log.warn("会话附件整片删除跳过（conversationId 为空）");
            return 0;
        }
        // 含 deleted=1 残留行，与 physicalDeleteByConversationId 同口径（少捞一行=那个对象永不回收）
        List<ChatAttachment> refs = baseMapper.selectPurgeRefsByConversationId(conversationId);
        int removed = baseMapper.physicalDeleteByConversationId(conversationId);
        if (refs == null || refs.isEmpty()) {
            log.info("会话附件整片删除：该会话无附件行: conversationId={}", conversationId);
            return removed;
        }
        afterCommit(() -> purgeObjects(refs));
        log.info("会话附件整片删除: conversationId={}, rows={}, purgeCandidates={}",
                conversationId, removed, refs.size());
        return removed;
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
    public PreviewPayload download(Long id) {
        ChatAttachment attachment = getByIdRequired(id);
        // 下载是「拿到文件落盘」语义，不受内联预览的 5MB / MIME 白名单约束：
        // 大产物与 docx/zip 等不可预览类型同样必须能下载（对齐 AttachmentController 下载口径）。
        byte[] content = artifactStorage.load(attachment.getStorageUrl());
        return new PreviewPayload(content, attachment.getMimeType(), attachment.getFileName());
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

    /**
     * 批量对象回收：护栏判定一次、逐个删对象（`G-041` 由单条扩为整片）。
     *
     * <p><b>护栏（G-047）</b>：回收前必须确认没有<b>子任务产物行</b>仍指向同一 {@code objectKey}。
     * {@code chat_attachment} 与 {@code attachment} 共用同一个桶，一旦按「同 objectKey 双行」
     * 把主任务图片引用给子任务，同一个对象就被两张表同时指向——此时会话里删一张图若直接
     * {@code removeObject}，子任务正在引用的字节当场消失，产物审计链路断裂且不可追溯。</p>
     *
     * <p><b>为什么护栏查询提到循环外（一次批量查）</b>：{@code listActiveObjectKeysIn} 收的是
     * <b>候选 key 集合</b>，本来就是批量接口。逐条查会让 20 份附件的会话跑 20 次往返，
     * 而结论完全等价（同一个方法、同一张表、同一个 {@code deleted=0} 口径）。</p>
     *
     * <p><b>fail-safe（三层，全部不得冒泡）</b>：</p>
     * <ul>
     *   <li>①<b>护栏查询失败</b> ⇒ 保守跳过<b>本轮全部</b>回收（宁可不删，也不可在护栏失效下
     *       误删共享对象）。注意这里跳过的是整批而非单条——护栏查不出来时，任何一条都可能
     *       正被引用，逐条「试删」等于赌运气；</li>
     *   <li>②<b>单对象删除失败</b> ⇒ 只影响该对象，继续其余（行已删，留对账兜底）；</li>
     *   <li>③<b>绝不抛出</b>：本方法只在 {@code afterCommit} 里跑，抛异常会污染已提交事务的响应
     *       （接口 500 但数据其实一致）。</li>
     * </ul>
     *
     * <p><b>日志必须带 {@code bucket} + {@code objectKey}</b>：残留对象没有这两个字段就无法排查，
     * 这是 `G-047` 复盘时点名的缺口。</p>
     *
     * @param attachments 待回收对象对应的附件行（可为 null / 空）
     */
    private void purgeObjects(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        // 只保留「有 objectKey 且存储实现支持该地址」的行：外部 https:// 地址不归我们删
        List<ChatAttachment> candidates = new ArrayList<>(attachments.size());
        Set<String> candidateKeys = new LinkedHashSet<>();
        for (ChatAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            String objectKey = attachment.getObjectKey();
            if (objectKey == null || objectKey.isBlank()) {
                continue;
            }
            if (!artifactStorage.supports(attachment.getStorageUrl())) {
                continue;
            }
            candidates.add(attachment);
            candidateKeys.add(objectKey);
        }
        if (candidates.isEmpty()) {
            return;
        }
        Set<String> stillReferenced;
        try {
            stillReferenced = candidateKeys.isEmpty()
                    ? Set.of()
                    : new HashSet<>(artifactReferencePort.listActiveObjectKeysIn(candidateKeys));
        } catch (RuntimeException e) {
            // 护栏查询失败：保守跳过整批。绝不冒泡——afterCommit 阶段抛异常会污染已提交事务的响应
            log.error("会话附件对象回收护栏查询失败，保守跳过本轮回收（best-effort）: candidates={}, keys={}",
                    candidates.size(), candidateKeys, e);
            return;
        }
        for (ChatAttachment attachment : candidates) {
            String objectKey = attachment.getObjectKey();
            String bucket = attachment.getBucketName();
            if (stillReferenced.contains(objectKey)) {
                log.info("对象仍被子任务产物引用，跳过回收: id={}, bucket={}, objectKey={}",
                        attachment.getId(), bucket, objectKey);
                continue;
            }
            try {
                artifactStorage.removeObject(bucket, objectKey);
            } catch (RuntimeException e) {
                log.warn("会话附件对象回收失败（不影响删除，交给对账兜底）: id={}, bucket={}, key={}, err={}",
                        attachment.getId(), bucket, objectKey, e.toString());
            }
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
