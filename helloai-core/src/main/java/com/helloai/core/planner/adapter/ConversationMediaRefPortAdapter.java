package com.helloai.core.planner.adapter;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.core.agent.port.ConversationMediaRefPort;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link ConversationMediaRefPort} 的提供方实现（`VU-8`）：把「task → conversation → 图片清单」
 * 这段 planner 域私有知识收在本域，agent 域只看到 {@code taskId → List<MediaRef>}。
 *
 * <p><b>为什么「task → conversation」的解析必须在这里</b>：映射靠
 * {@code task.context.conversationId}，该键由 {@link TaskContextKeys} 定义、由
 * {@code ClarifyFinalizeServiceImpl} 在终稿建任务时写入。若让消费方自己取这个键，
 * 键改名时执行链会静默看不到图（不报错、只是没图），且 agent 域会多出一处
 * 对 planner 键空间的依赖（判据 4）。</p>
 *
 * <p><b>为什么不做「补建桥表」</b>：设计文档明确拒绝把两套生命周期（会话删除即回收 /
 * 交付审计长期留存）搅在一起，且「这张附件来自会话还是产物」会变成到处要判的第二事实源。
 * 本适配器是纯读：不写 {@code chat_attachment}、不写 {@code attachment}、不改任一侧的删除逻辑。</p>
 *
 * <p><b>`G-042` 为什么作用域声明落在 {@code sub_task.context} 而不是写 {@code attachment} 双行</b>：
 * {@code attachment} 表的每一处消费（交付物闸 {@code DeliveryEvidenceEvaluator}、上游产出装载、
 * 契约回流、核验证据集、MCP 读模型、「我的产物」）都以「这一行 = 某子任务<b>产出</b>的东西」为前提。
 * 把用户上传的需求图写成 {@code attachment} 行，会让这些判据把「输入」误认成「产出」——
 * 最直接的后果是交付物闸对所有带图子任务恒为「有产物」，闸门形同废弃。
 * {@code sub_task.context} 天然属于「拆解判定」而非「产物登记」，且是既有自由 JSONB。</p>
 *
 * <p><b>为什么按 {@code taskId} 每次查一次 context（而不是缓存）</b>：执行是一次性动作，
 * 一个子任务装配上下文只发生一次，缓存省下的那点开销抵不上「缓存陈旧导致送错图」的风险。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMediaRefPortAdapter implements ConversationMediaRefPort {

    /**
     * 图片扩展名集：MIME 缺失 / {@code octet-stream} 时的兜底判定。
     *
     * <p><b>为什么不用 {@link AttachmentContentPolicy#MEDIA_EXTENSIONS}</b>：那是图片+音频+视频
     * 的总族，直接拿来会把 {@code .mp4} 判成可送图。</p>
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final TaskService taskService;
    private final ChatAttachmentService chatAttachmentService;

    @Override
    public ScopedMediaRefs listScopedImages(Long taskId, Long subTaskId, Map<String, Object> subTaskContext) {
        if (taskId == null) {
            return ScopedMediaRefs.empty();
        }
        try {
            Long conversationId = resolveConversationId(taskId);
            if (conversationId == null) {
                // 历史任务（会话附件功能上线前建的）/ 非会话来源任务：本就没有这个键。
                // 这是常态而非异常，故 debug 级、不 warn——否则每个老任务都刷一条噪音。
                log.debug("任务未关联会话，无会话图片可送: taskId={}", taskId);
                return ScopedMediaRefs.empty();
            }
            List<ChatAttachment> attachments = chatAttachmentService.listByConversation(conversationId);
            if (attachments == null || attachments.isEmpty()) {
                return ScopedMediaRefs.empty();
            }
            List<ChatAttachment> images = imagesOf(attachments);
            if (images.isEmpty()) {
                return ScopedMediaRefs.empty();
            }
            // `G-042`：优先按本子任务声明的附件取图。声明来自拆解期 Planner（落在 sub_task.context），
            // 键的解析责任在本域，agent 域只把 context 原样透传。
            List<Long> declaredIds = declaredMediaRefIds(subTaskContext);
            if (!declaredIds.isEmpty()) {
                List<ChatAttachment> scoped = filterByIds(images, declaredIds);
                if (!scoped.isEmpty()) {
                    // 声明命中：严格按声明返回
                    List<MediaRef> refs = toRefsNewestFirst(scoped);
                    log.info("执行面按子任务作用域取图: taskId={}, subTaskId={}, declared={}, hit={}, sent={}",
                            taskId, subTaskId, declaredIds.size(), scoped.size(), refs.size());
                    return ScopedMediaRefs.ofScoped(refs);
                }
                // 声明了但一张都对不上（附件已被删 / 声明全是非图片 / 序号解析失败的历史数据）
                // ——这是需要人看一眼的异常，warn 级；但仍按回落继续，媒体不该阻断执行。
                log.warn("子任务声明了附件但无一张可送，回落会话全量: taskId={}, subTaskId={}, declared={}, "
                                + "conversationImages={}",
                        taskId, subTaskId, declaredIds.size(), images.size());
                return ScopedMediaRefs.ofFallback(toRefsNewestFirst(images),
                        "（本子任务原定只看指定的 " + declaredIds.size() + " 份附件，但它们已不可读；"
                                + "本次已带上会话里全部 " + images.size() + " 张图，请自行判断哪几张相关。）");
            }
            // 无声明（Key 缺失 / 空数组 / 解析失败）⇒ 回落会话全量，并显式标注原因。
            // 不标原因等于让模型把「看到的是全量」当成「这些都和我相关」，与 `G-042` 要修的
            // 缺陷换个形式重现。区分两种原因：子任务根本没声明 vs 声明了但解析不出 ID。
            String note = subTaskContext != null && subTaskContext.containsKey(SubTaskContextKeys.MEDIA_REF_IDS)
                    ? "（本子任务声明的附件清单无法解析，已带上会话里全部 " + images.size()
                            + " 张图，请自行判断哪几张相关。）"
                    : "（拆解时未声明本子任务需要哪些附件，已带上会话里全部 " + images.size()
                            + " 张图，请自行判断哪几张相关。）";
            return ScopedMediaRefs.ofFallback(toRefsNewestFirst(images), note);
        } catch (RuntimeException e) {
            // 媒体是增强不是必要条件：查不到就当没有，绝不让执行链因此失败
            log.warn("会话图片引用查询失败，按无图处理: taskId={}, subTaskId={}, err={}",
                    taskId, subTaskId, e.toString());
            return ScopedMediaRefs.empty();
        }
    }

    /** 只保留图片（文档走文本通道、音视频进消息会被 provider 400）。 */
    private static List<ChatAttachment> imagesOf(List<ChatAttachment> attachments) {
        List<ChatAttachment> images = new ArrayList<>();
        for (ChatAttachment attachment : attachments) {
            if (attachment == null || attachment.getId() == null) {
                continue;
            }
            if (isImage(attachment)) {
                images.add(attachment);
            }
        }
        return images;
    }

    /**
     * 按声明取子集，<b>保持图片自身的上传顺序</b>（`G-042`）。
     *
     * <p><b>为什么按 {@code images} 的顺序遍历、而不是按声明顺序遍历</b>：本方法的输出随后要被
     * {@link #toRefsNewestFirst} 整体翻转成「最新在前」（`G-046` 截断保新的前提）。若这里按
     * <b>声明</b>顺序输出，翻转之后就变成「声明倒序」——一个既不是上传顺序、也不是任何有意义
     * 语义的次序，而 `G-046` 要求的「最新优先」也随之失效（超限时被丢掉的可能是最新的那张）。
     * 声明的作用是<b>选哪些</b>，不是<b>排序</b>；顺序由上传时间唯一决定。</p>
     */
    private static List<ChatAttachment> filterByIds(List<ChatAttachment> images, List<Long> declaredIds) {
        Set<Long> wanted = new HashSet<>(declaredIds);
        List<ChatAttachment> scoped = new ArrayList<>();
        for (ChatAttachment image : images) {
            if (wanted.contains(image.getId())) {
                scoped.add(image);
            }
        }
        return scoped;
    }

    /**
     * 转 {@link MediaRef} 并翻成「最新在前」。
     *
     * <p>`G-046`（第三处落点）：下游 {@code AttachmentMediaAssembler.loadMerged} 按自己的
     * {@code MAX_INLINE_COUNT} 顺序取前 N 条即停，而那是<b>请求</b>级预算（不该在本域复刻一份
     * 去对齐它——判据 4）。既然截断只能「取前 N」，保新就只剩一个办法：把最新的排在前面。
     * 若照上传正序输出，8 图会话送进子任务的恰好是最旧的 5 张——与文本通道原缺陷同源。</p>
     *
     * <p>★不能改用 {@code listLatestByConversation(conv, 20)} 代替：那一刀按<b>附件</b>粒度
     * 截断，而本方法只保留<b>图片</b>；若最新 20 件里只有 2 张图、第 21 件才是图，就会漏图。</p>
     */
    private static List<MediaRef> toRefsNewestFirst(List<ChatAttachment> attachments) {
        List<MediaRef> refs = new ArrayList<>(attachments.size());
        for (ChatAttachment attachment : attachments) {
            refs.add(MediaRef.ofChatAttachment(attachment.getId(), attachment.getFileName(),
                    attachment.getMimeType(), attachment.getFileSize()));
        }
        Collections.reverse(refs);
        return List.copyOf(refs);
    }

    /**
     * 从 {@code sub_task.context} 解析本子任务声明的附件 ID 清单（`G-042`）。
     *
     * <p><b>键的解析必须在这里</b>（同 {@link #resolveConversationId} 的理由）：键名是 planner 域
     * 私有约定（{@link SubTaskContextKeys#MEDIA_REF_IDS}，写入方 {@code buildDrafts}）。若让
     * agent 域自己取，键改名会让执行链静默看不到图，且 agent 域会多出一处对 planner 键空间的
     * 依赖（判据 4）。</p>
     *
     * <p><b>防御式</b>：值可能是 {@code List<Integer>} / {@code List<String>}（JSONB 反序列化
     * 的数字类型不定）、也可能是单个数字；任何非数字元素直接跳过，绝不为一个坏元素整体放弃——
     * 声明写坏只是少几张图（回落全量），不该变成「一张都不给」。</p>
     */
    private static List<Long> declaredMediaRefIds(Map<String, Object> subTaskContext) {
        if (subTaskContext == null) {
            return List.of();
        }
        Object raw = subTaskContext.get(SubTaskContextKeys.MEDIA_REF_IDS);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Collection<?> collection)) {
            // 单个数字也容忍（模型/历史数据写成标量的情形）
            Long single = toLong(raw);
            return single == null ? List.of() : List.of(single);
        }
        List<Long> ids = new ArrayList<>(collection.size());
        for (Object element : collection) {
            Long id = toLong(element);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 宽松转 {@code Long}：数字直取，纯数字字符串解析，其余 {@code null}。 */
    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从 {@code task.context} 解析会话 ID（防御式：缺键 / 类型不符 / 非法数字一律 {@code null}）。
     *
     * <p>与 {@code DecomposeContextSnapshotRenderer#conversationIdOf} 同口径——两处都是
     * 「从自由 JSONB 里取一个可能不存在的数字」，但**不抽公共工具**：那种工具一旦出现，
     * 就会吸引第三个、第四个读取方用它读别的键，把「哪些键存在」这个 planner 私有约定
     * 扩散成全平台的公共 API。</p>
     */
    private Long resolveConversationId(Long taskId) {
        TaskView task = taskService.getView(taskId);
        Map<String, Object> context = task == null ? null : task.context();
        if (context == null) {
            return null;
        }
        Object raw = context.get(TaskContextKeys.CONVERSATION_ID);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 是否图片：{@code image/*} 前缀优先，缺失 / {@code octet-stream} 时按扩展名兜底。 */
    private static boolean isImage(ChatAttachment attachment) {
        String mime = attachment.getMimeType() != null
                ? attachment.getMimeType().toLowerCase(Locale.ROOT) : "";
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime)) {
            return mime.startsWith("image/");
        }
        return IMAGE_EXTENSIONS.contains(AttachmentContentPolicy.extensionOf(attachment.getFileName()));
    }
}