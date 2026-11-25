package com.helloai.core.agent.support;

import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.port.ChatAttachmentMediaPort;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 附件 → 多模态媒体装载器（`VU-1`）：从附件快照里挑出可内联的图片，装载为 {@link Media}。
 *
 * <p><b>为什么是独立组件而不是塞进调用方</b>：图片装载需要「MIME 判定 + 字节读取 +
 * 数量/体积上限 + 异常降级」四件事成套出现，执行链装配器
 * （{@code AgentRuntimeContextAssembler}）与核验证据装配器（`VU-2` 落点）是<b>两个</b>
 * 消费方。<b>同一判据不能写两遍</b>（判据 4：同一事实双写必分叉）——尤其「哪些扩展名算图片」
 * 一旦两处漂移，就会出现「执行链能看图、reviewer 看不到」的静默不一致。</p>
 *
 * <p><b>只做图片，不做音频/视频</b>：在用模型的 {@code capabilities.input} 只声明
 * {@code text/image}，把音视频塞进消息会被 provider 直接拒绝（400）。
 * {@link AttachmentContentPolicy#isMedia} 是「图片+音频+视频」的总族判定，本类只取其中
 * 可进消息的那一部分——两者刻意不合并，因为「排除出文本通道」与「送入媒体通道」
 * 是两个不同判据的用途。</p>
 *
 * <p><b>不抛异常</b>：单张图读取失败只跳过该张，返回已成功的部分。媒体是<b>增强</b>而非
 * 必要条件，一张图读不出来不应让整个子任务执行失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentMediaAssembler {

    /** 单次装载的图片数量上限：只防失控，<b>不是成本阈值</b>（成本口径见 `VU-7`，须待 `RUI-9` 数据）。 */
    static final int MAX_INLINE_COUNT = 5;

    /** 单张图片字节上限：5MB，与 {@code storage.max-file-size} 同量级。 */
    static final long MAX_INLINE_BYTES = 5L * 1024 * 1024;

    /**
     * 扩展名 → 图片 MIME：仅覆盖 {@link AttachmentContentPolicy#MEDIA_EXTENSIONS} 的图片子集。
     *
     * <p>判不出 MIME 时<b>放弃内联</b>而非猜一个：MIME 猜错时 provider 报的错与真实原因无关
     * （"invalid image"），排查成本远高于少送一张图。</p>
     */
    private static final Map<String, String> IMAGE_MIME_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp");

    private final AttachmentPort attachmentPort;

    /**
     * 会话附件字节读取端口（`VU-9`）。<b>刻意不是 {@code Optional} / {@code ObjectProvider}</b>：
     * 缺了它对话面就看不到图，属能力缺失而非可选优化，注入失败应当让启动失败。
     */
    private final ChatAttachmentMediaPort chatAttachmentMediaPort;

    /**
     * 从附件快照中挑出可内联图片并按原顺序装载为媒体（最多 {@link #MAX_INLINE_COUNT} 张）。
     *
     * @param attachments 附件快照（顺序即装载顺序；{@code null} 视为空）
     * @return 媒体列表（绝不返回 {@code null}；无可装载项时为空表）
     */
    public List<Media> pickInlineImages(List<AttachmentRef> attachments) {
        return loadMerged(attachments, null);
    }

    /**
     * 是否为可内联的图片附件：MIME 前缀 {@code image/} 优先，缺失时按扩展名兜底。
     *
     * <p>调用方用它把图片从<b>文本通道</b>里排除（否则二进制会被当 UTF-8 解码成乱码）。
     */
    public static boolean isInlineImage(AttachmentRef attachment) {
        String mime = attachment.mimeType() != null ? attachment.mimeType().toLowerCase() : "";
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime)) {
            return mime.startsWith("image/");
        }
        return IMAGE_MIME_BY_EXTENSION.containsKey(AttachmentContentPolicy.extensionOf(attachment.fileName()));
    }

    /** 单条附件装载为媒体；不可装载（非图片 / 不可读 / 超限 / MIME 无法判定）返回 {@code null}。 */
    private Media toMedia(AttachmentRef attachment) {
        if (!attachment.contentLoadable() || !isInlineImage(attachment)) {
            return null;
        }
        return assemble(attachment.id(), attachment.fileName(), attachment.mimeType(),
                attachment.fileSize(), () -> attachmentPort.loadContent(attachment.id()));
    }

    /**
     * 从<b>媒体引用</b>装载可内联图片（`VU-9` 对话面 / `VU-2` 业务面共用入口）。
     *
     * <p>与 {@link #pickInlineImages(List)} 的差别只有「字节从哪读」：本方法按
     * {@link MediaRef#source()} 分派到对应域的端口，<b>其余判定（MIME / 体积 / 数量 / 降级）全部共用</b>
     * ——「哪些扩展名算图片」「多大算超限」这类判据一旦在两处各写一份，漂移那天就是
     * 静默不一致（判据 4）。</p>
     *
     * <p><b>为什么方法名不是重载</b>：{@code List<AttachmentRef>} 与 {@code List<MediaRef>}
     * 泛型擦除后都是 {@code List} ⇒ 重载会撞同一签名无法编译；且两个入参语义确实不同
     * （一个是快照、一个是引用），不同名反而更清楚。</p>
     *
     * @param mediaRefs 媒体引用（顺序即装载顺序；{@code null} 视为空）
     * @return 媒体列表（绝不返回 {@code null}；无可装载项时为空表）
     */
    public List<Media> loadInlineImages(List<MediaRef> mediaRefs) {
        return loadMerged(null, mediaRefs);
    }

    /**
     * 合并装载「用户上传的图」与「上游产出的图」，<b>共用一份数量预算</b>（`VU-8`）。
     *
     * <p><b>为什么必须合并而不是各调一次</b>：{@link #MAX_INLINE_COUNT} 的语义是
     * 「一次请求最多带几张图」，它约束的是<b>请求</b>而不是<b>来源</b>。若装配器分别调
     * {@code loadInlineImages(会话图)} 与 {@code pickInlineImages(上游图)}，两次各自截断到 5，
     * 实际上限就变成 10 张——预算随「来源种数」放大，一个新来源接进来就悄悄翻倍。
     * 合并成一次装载，上限才真的是 5。</p>
     *
     * <p><b>排序为什么是「用户上传在前、上游产出在后」</b>：上限只有 5 张，谁被截断决定了
     * 模型看到什么。<b>用户上传的图是需求输入</b>（「照这张截图改」），上游产出是参考资料；
     * 把用户自己给的那张挤掉，等于让执行者丢掉唯一的意图载体去参考别人的中间产物——
     * 这个取舍错了会让整轮执行偏题，且用户完全无从察觉。反过来截断上游图，最坏只是
     * 少参考一张历史产出。</p>
     *
     * @param taskAttachments task 域附件快照（上游产出；{@code null} 视为空）
     * @param mediaRefs       会话附件引用（用户上传；{@code null} 视为空）
     * @return 媒体列表（绝不返回 {@code null}；无可装载项时为空表）
     */
    public List<Media> loadMerged(List<AttachmentRef> taskAttachments, List<MediaRef> mediaRefs) {
        boolean hasRefs = mediaRefs != null && !mediaRefs.isEmpty();
        boolean hasAttachments = taskAttachments != null && !taskAttachments.isEmpty();
        if (!hasRefs && !hasAttachments) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        if (hasRefs) {
            for (MediaRef ref : mediaRefs) {
                if (ref == null) {
                    continue;
                }
                if (media.size() >= MAX_INLINE_COUNT) {
                    log.info("内联图片数量达上限，其余跳过: limit={}, source={}, attachmentId={}",
                            MAX_INLINE_COUNT, ref.source(), ref.attachmentId());
                    return List.copyOf(media);
                }
                try {
                    Media loaded = toMedia(ref);
                    if (loaded != null) {
                        media.add(loaded);
                    }
                } catch (Exception e) {
                    log.warn("媒体引用装载失败，跳过: attachmentId={}, source={}, err={}",
                            ref.attachmentId(), ref.source(), e.getMessage());
                }
            }
        }
        if (hasAttachments) {
            for (AttachmentRef attachment : taskAttachments) {
                if (media.size() >= MAX_INLINE_COUNT) {
                    log.info("内联图片数量达上限，其余跳过: limit={}, attachmentId={}",
                            MAX_INLINE_COUNT, attachment.id());
                    break;
                }
                try {
                    Media loaded = toMedia(attachment);
                    if (loaded != null) {
                        media.add(loaded);
                    }
                } catch (Exception e) {
                    log.warn("附件装载为媒体失败，跳过: attachmentId={}, err={}",
                            attachment.id(), e.getMessage());
                }
            }
        }
        return List.copyOf(media);
    }

    /** 单条媒体引用装载为媒体；不可装载返回 {@code null}。 */
    private Media toMedia(MediaRef ref) {
        if (!isInlineImage(ref.mimeType(), ref.fileName())) {
            return null;
        }
        return assemble(ref.attachmentId(), ref.fileName(), ref.mimeType(), ref.fileSize(),
                () -> loadBytes(ref));
    }

    /** 按归属表分派字节读取（两张表主键空间独立，不能试探式加载）。 */
    private byte[] loadBytes(MediaRef ref) {
        return switch (ref.source()) {
            case TASK_ATTACHMENT -> attachmentPort.loadContent(ref.attachmentId());
            case CHAT_ATTACHMENT -> chatAttachmentMediaPort.loadChatAttachmentContent(ref.attachmentId());
        };
    }

    /**
     * 装载骨架：体积预筛 → MIME 判定 → 读字节 → 体积复筛 → 组装。
     *
     * <p>两条来源（{@code AttachmentRef} / {@code MediaRef}）共用本方法，字节由
     * {@code byteLoader} 延迟提供——<b>先判后读</b>让大文件与判不出 MIME 的条目
     * 一次字节都不读（{@code AttachmentMediaAssembler} 注释里「先判快照 fileSize 再读字节」
     * 的同一条理由）。</p>
     *
     * @return 媒体；不可装载返回 {@code null}（不抛异常）
     */
    private Media assemble(Long attachmentId, String fileName, String mimeType, Long declaredSize,
                           java.util.function.Supplier<byte[]> byteLoader) {
        // 先判声明体积：大文件直接跳过，避免先读进内存再丢弃
        if (declaredSize != null && declaredSize > MAX_INLINE_BYTES) {
            log.info("图片超过内联字节上限，跳过: attachmentId={}, fileSize={}, limit={}",
                    attachmentId, declaredSize, MAX_INLINE_BYTES);
            return null;
        }
        String mime = resolveImageMime(mimeType, fileName);
        if (mime == null) {
            log.info("图片 MIME 无法判定，跳过内联: attachmentId={}, fileName={}", attachmentId, fileName);
            return null;
        }
        byte[] bytes = byteLoader.get();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        // 声明体积可能失真，读到字节后再判一次（先判后读只省内存，不是判据）
        if (bytes.length > MAX_INLINE_BYTES) {
            log.info("图片实际字节超过内联上限，跳过: attachmentId={}, bytes={}, limit={}",
                    attachmentId, bytes.length, MAX_INLINE_BYTES);
            return null;
        }
        String name = fileName != null && !fileName.isBlank() ? fileName : "image-" + attachmentId;
        return Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(mime))
                .data(new ByteArrayResource(bytes))
                .name(name)
                .build();
    }

    /**
     * 是否为可内联图片（原始值重载）：{@code image/*} 前缀优先，缺失 / octet-stream 时按扩展名兜底。
     *
     * <p>供无 {@link AttachmentRef} 的调用方复用同一判据（判据 4：判定单源）。</p>
     */
    public static boolean isInlineImage(String mimeType, String fileName) {
        String mime = mimeType != null ? mimeType.toLowerCase() : "";
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime)) {
            return mime.startsWith("image/");
        }
        return IMAGE_MIME_BY_EXTENSION.containsKey(AttachmentContentPolicy.extensionOf(fileName));
    }

    /** 解析图片 MIME：{@code image/*} 原样使用，否则按扩展名映射；都判不出返回 {@code null}。 */
    private static String resolveImageMime(AttachmentRef attachment) {
        return resolveImageMime(attachment.mimeType(), attachment.fileName());
    }

    /** 解析图片 MIME（原始值重载）：{@code image/*} 原样使用，否则按扩展名映射；判不出返回 {@code null}。 */
    private static String resolveImageMime(String mimeType, String fileName) {
        String mime = mimeType != null ? mimeType.toLowerCase() : "";
        if (mime.startsWith("image/")) {
            return mime;
        }
        return IMAGE_MIME_BY_EXTENSION.get(AttachmentContentPolicy.extensionOf(fileName));
    }
}