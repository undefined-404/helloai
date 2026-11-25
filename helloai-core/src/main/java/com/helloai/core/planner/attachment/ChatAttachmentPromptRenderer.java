package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.search.BochaRerankService;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话附件 → Prompt 文本（方案 §3.4 三层注入的前两层）。
 *
 * <p><b>MANIFEST（恒注入）</b>：文件清单。哪怕正文一个字都读不出，也要让模型知道
 * 「用户给了哪些文件、哪个读不了」——否则它会把「没有正文」误当成「用户没提附件」。</p>
 *
 * <p><b>CONTEXT（按预算注入）</b>：可读正文。预算直接复用 G-016 的
 * {@link AttachmentContentPolicy}（单文件 64k / 总计 200k），不另立一套数字——
 * 同一个「附件正文能占多少上下文」的问题，全仓只能有一个答案。</p>
 *
 * <p>第三层（大文件 RAG 检索）在 P1，这里不做；超预算的文件在 CONTEXT 里显式标注
 * 「已截断/未注入」，让模型知道信息不全，而不是以为全文就这么多。</p>
 */
@Slf4j
@Component
public class ChatAttachmentPromptRenderer {

    /** 无附件时的占位：保持 Prompt 语义节稳定（与联网资料节的「（无可用联网资料）」同风格）。 */
    private static final String NO_ATTACHMENT = "（本轮无附件）";

    /** 决策阶段无附件占位：与正文层同风格，保证决策 Prompt 的语义节长度稳定。 */
    private static final String NO_ATTACHMENT_DECISION = "（本轮无附件）";

    /**
     * 决策阶段每份文件的预览字数上限。
     *
     * <p>决策调用（是否联网 + 搜什么词）只需要模型判断「这是什么文件、大概讲什么」，
     * 不需要全文；注入全文会把轻量决策调用抬成重调用。参考 deer-flow #1647 方案四
     * 的 preview 注入（~500 字符），本处取 500 字。</p>
     */
    private static final int DECISION_PREVIEW_CHARS = 500;

    /**
     * 决策阶段锚点抽取的扫描范围上限（字符）。
     *
     * <p>预览只给头部 500 字，但锚点（法规名/标准号/技术专名）可能出现在文档任意位置，
     * 因此对全文扫描——单次 {@code extractedText} 最大 64k（G-016 单文件上限），
     * 这里再限 2 万字避免超长手册拖慢决策调用。</p>
     */
    private static final int ANCHOR_SCAN_CHARS = 20_000;

    /** 锚点数上限：过多会稀释模型注意力，反而选不准搜索词。 */
    private static final int ANCHOR_LIMIT = 20;

    /** 《…》：法规、标准、规范的正式名称，是最强的外部检索锚点。 */
    private static final Pattern DOC_TITLE_PATTERN = Pattern.compile("《[^》\\n]{2,40}》");

    /** 标准号/文号：GB/T 12345、ISO 9001、MH/T 6012、ISO/IEC 27001。 */
    private static final Pattern STANDARD_NO_PATTERN = Pattern.compile(
            "(?:GB/T|GB|ISO/IEC|ISO|IEC|MH/T|MH|ICAO|ANSI|RFC|EN)\\s?\\d{2,6}(?:[.-]\\d{1,4})*");

    /** 首字母大写的英文技术专名：PowerShell、Windows、OpenID、gRPC（排除句首常见虚词）。 */
    private static final Pattern TECH_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{2,24}");

    /** 英文虚词/句首词黑名单：避免把 The/This/Note 之类当成外部锚点。 */
    private static final Set<String> TECH_STOP_WORDS = Set.of(
            "The", "This", "That", "These", "Those", "And", "Or", "Not", "But", "For", "With",
            "When", "What", "Which", "Where", "How", "Why", "Who", "Use", "Used", "Using",
            "Note", "Notes", "See", "Step", "Steps", "If", "It", "Its", "Is", "Are", "Was",
            "Be", "Can", "May", "Must", "Should", "Will", "You", "Your", "Our", "All", "Any",
            "File", "Files", "Page", "Pages", "Table", "Figure", "Chapter", "Part", "Add",
            "Run", "Set", "Get", "New", "From", "Into", "Then", "Than", "Also", "Only");

    private final ChatAttachmentService chatAttachmentService;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * `ATT-3`：附件检索注入的片段条数上限。
     *
     * <p>16 片是按检索预算倒推的：128K 窗口给 15% ≈ 9,600 字，分片 600 字/片 ⇒ 正好 16 片。
     * 原来的 5 片在 90,385 字的文档上只覆盖 3.3%，问「后半部分某主题」基本答不出。</p>
     */
    private static final int KB_RETRIEVAL_TOP_K = 16;

    /** 无检索片段时的占位：保持 Prompt 语义节稳定（与「（本轮无附件）」同风格）。 */
    private static final String NO_RETRIEVAL = "（本次未检索附件片段：无超预算附件，或检索无命中）";

    /**
     * 注入侧参与的附件份数上限（`G-046`）。
     *
     * <p><b>为什么需要它</b>：注入是「有限预算的资源分配」，正文层要按 {@code planContextQuota}
     * 均分字符预算，清单也要占 prompt。会话里堆了 40 份附件时，全量参与只会让每份都拿到几百字
     * ——谁都没读全，等于都没读。这个数决定「一次注入最多照顾几份」。</p>
     *
     * <p><b>为什么不另立一套数字（判据 4）</b>：拆解期快照
     * （{@code DecomposeContextSnapshotRenderer#MAX_ATTACHMENTS}）展示的是同一个「会话附件清单」，
     * 两处口径必须同值——同一个问题「一次注入看几份附件」，全仓只能有一个答案。故本常量是
     * <b>唯一事实源</b>，那边引用它。</p>
     */
    public static final int MAX_INJECTED_ATTACHMENTS = 20;

    /**
     * 当前模型的上下文窗口（token），用于折算会话附件正文预算（`ATT-7`）。
     *
     * <p>取平台的<b>保守下限</b>而非 per-model 动态值：本类拿不到「本轮用哪个模型」的
     * 上下文（渲染发生在模型选择之前），逐个模型传参要改整条调用链，收益不抵成本。
     * 默认 128K 保证不溢出；换 1M 窗口模型时通过配置上调即可。</p>
     */
    private final int contextWindowTokens;

    /** `ATT-3` 片段重排服务（与 W-5 网页重排共用博查通道；兼容构造为 null）。 */
    private final BochaRerankService bochaRerankService;

    /**
     * 附件片段重排开关（`ATT-3`，默认关）。
     *
     * <p>与 {@code helloai.web-search.rerank-enabled} <b>分开</b>：两处都按次计费，
     * 开启网页重排不应顺带让每轮附件检索也走付费调用。</p>
     */
    private final boolean retrievalRerankEnabled;

    @Autowired
    public ChatAttachmentPromptRenderer(
            ChatAttachmentService chatAttachmentService,
            KnowledgeBaseService knowledgeBaseService,
            BochaRerankService bochaRerankService,
            @Value("${helloai.attachment.context-window-tokens:128000}") int contextWindowTokens,
            @Value("${helloai.attachment.retrieval-rerank-enabled:false}") boolean retrievalRerankEnabled) {
        this.chatAttachmentService = chatAttachmentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.bochaRerankService = bochaRerankService;
        this.contextWindowTokens = contextWindowTokens;
        this.retrievalRerankEnabled = retrievalRerankEnabled;
    }

    /**
     * 兼容构造：不注入 KB（测试 / 只需清单与正文两层的场景）。
     *
     * <p>第三层检索会因 {@code knowledgeBaseService == null} 直接返回占位——
     * 这样存量单测不必 mock 知识库，也不会因缺依赖 NPE。</p>
     */
    public ChatAttachmentPromptRenderer(ChatAttachmentService chatAttachmentService) {
        this(chatAttachmentService, null, null, AttachmentContentPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS, false);
    }

    /** 兼容构造：指定窗口但不注入 KB（单测按窗口验证预算，第三层固定返回占位）。 */
    public ChatAttachmentPromptRenderer(ChatAttachmentService chatAttachmentService, int contextWindowTokens) {
        this(chatAttachmentService, null, null, contextWindowTokens, false);
    }

    /** 兼容构造：注入 KB 但关闭片段重排（存量单测验证第三层检索本身）。 */
    public ChatAttachmentPromptRenderer(ChatAttachmentService chatAttachmentService,
                                        KnowledgeBaseService knowledgeBaseService, int contextWindowTokens) {
        this(chatAttachmentService, knowledgeBaseService, null, contextWindowTokens, false);
    }

    /**
     * 渲染结果。
     *
     * @param manifest 清单表（恒非空，无附件时为占位文本）
     * @param context  正文层（无附件或全部不可读时为说明文本）
     */
    public record Rendered(String manifest, String context, String retrieval) {
    }

    /** 一次查询渲染两层（避免同一轮 Prompt 渲染查两遍库）；不做检索（第三层）。 */
    public Rendered render(Long conversationId) {
        return render(conversationId, null, null, null);
    }

    /** 兼容重载：带检索语义锚但不限定目标附件（会话级检索）。 */
    public Rendered render(Long conversationId, String query) {
        return render(conversationId, query, null, null);
    }

    /**
     * 完整入口：显式勾选 + 模型判定两种作用域信号。
     *
     * <p><b>优先级：用户显式勾选 &gt; 模型判定</b>。用户点了就是点了，模型不该再猜；
     * 没点（null/空）才用模型从提问里推断的序号。</p>
     *
     * @param selectedIds 前端勾选的附件 id；null/空 = 未指定
     * @param targets     模型判定的附件序号（1-based）；null/空 = 不限
     */
    public Rendered render(Long conversationId, String query, List<Long> selectedIds, List<Integer> targets) {
        return renderWithMedia(conversationId, query, selectedIds, targets).rendered();
    }

    /**
     * 渲染结果 + 本轮媒体引用（`VU-9`）：<b>一次查询</b>产出「三层文本 + 要送模型的图」。
     *
     * <p><b>为什么合并成一个入口而不是让调用方再调一个 {@code pickImages}</b>：
     * 两者共用同一份 {@code listByConversation} 结果与<b>同一个 scope 判定</b>
     * （「本轮用户点名了哪几张附件」）。分成两次调用就要么查两遍库，要么让调用方自己
     * 复算一遍 scope——后者必然与 {@link #resolveScope} 漂移，出现「清单里说附件 2 在用、
     * 但送去看图的却是附件 3」这种无法排查的状态（判据 4）。</p>
     *
     * <p><b>为什么媒体引用不塞进 {@link Rendered}</b>：{@code Rendered} 有 3 处既有构造点
     * （{@code NO_ATTACHMENT} 占位、两条 {@code render} 重载），改它的分量数会波及
     * 全部既有调用方与本类测试；而媒体是<b>新增的可选消费</b>，用并列 record 承载
     * 可让老调用方零改动（{@code render(...)} 仍返回原 {@code Rendered}）。</p>
     */
    public RenderedWithMedia renderWithMedia(Long conversationId, String query, List<Long> selectedIds,
                                             List<Integer> targets) {
        AttachmentSet set = loadInjectedSet(conversationId);
        List<ChatAttachment> attachments = set.attachments();
        if (attachments.isEmpty()) {
            return new RenderedWithMedia(
                    new Rendered(NO_ATTACHMENT, NO_ATTACHMENT, NO_RETRIEVAL), List.of());
        }
        Set<Integer> scope = resolveScope(attachments, selectedIds, targets);
        Rendered rendered = new Rendered(
                withTruncationNote(buildManifest(attachments), set),
                withTruncationNote(buildContext(attachments, scope), set),
                buildRetrieval(conversationId, query, attachments, scope));
        return new RenderedWithMedia(rendered, pickMediaRefs(attachments, scope));
    }

    /**
     * 注入侧要看的附件集合 + 「是否发生截断」的事实（`G-046`）。
     *
     * @param attachments 参与注入的附件，<b>按上传先后排列</b>（= 供模型读的顺序）
     * @param total       会话内活跃附件总数（含被截断掉的）
     */
    private record AttachmentSet(List<ChatAttachment> attachments, int total) {

        boolean truncated() {
            return total > attachments.size();
        }
    }

    /**
     * 装载注入侧的附件集合：<b>超过上限时取最新的 N 份，再按上传先后正序排列</b>（`G-046`）。
     *
     * <p><b>原实现的缺陷</b>：{@code listByConversation} 是 {@code ORDER BY create_time ASC}，
     * 而下游按序截断到上限 ⇒ 会话附件超过 5 张（媒体）/ 20 件（清单）后，
     * <b>用户后传的永远送不到模型</b>，被保留的恰恰是最早那几张。用户说「看这张图」指的是刚传的，
     * 结果模型看到的是三小时前的第一张——且没有任何提示。</p>
     *
     * <p><b>为什么「倒序取 N 再正序排」而不是「直接倒序注入」</b>：取谁由「最新」决定，
     * 读的顺序仍应是「用户上传的先后」——清单里的序号 {@code 附件1/2/3} 与
     * {@code attachment_targets} 的 1-based 序号、以及用户在界面上看到的顺序必须对得上。
     * 若把倒序结果直接注入，模型说的「附件 1」会是用户眼里最后那份，序号全错位。</p>
     *
     * <p><b>为什么不截断就整表读</b>：本方法只在<b>确有截断</b>时才走「倒序取 N」的窄查询；
     * 未超限（绝大多数会话）仍走原查询，行为与改造前完全一致。</p>
     */
    private AttachmentSet loadInjectedSet(Long conversationId) {
        List<ChatAttachment> all = chatAttachmentService.listByConversation(conversationId);
        if (all == null || all.isEmpty()) {
            return new AttachmentSet(List.of(), 0);
        }
        if (all.size() <= MAX_INJECTED_ATTACHMENTS) {
            return new AttachmentSet(all, all.size());
        }
        // 超限：只保留最新 N 份（窄查询，SQL 层 LIMIT），再翻回上传正序供清单 / 序号使用
        List<ChatAttachment> latest = chatAttachmentService
                .listLatestByConversation(conversationId, MAX_INJECTED_ATTACHMENTS);
        List<ChatAttachment> ordered = new ArrayList<>(latest);
        Collections.reverse(ordered);
        log.info("会话附件超过注入上限，保留最新 N 份: conversationId={}, total={}, injected={}, droppedOldest={}",
                conversationId, all.size(), ordered.size(), all.size() - ordered.size());
        return new AttachmentSet(List.copyOf(ordered), all.size());
    }

    /**
     * 在清单 / 正文层末尾显式声明「共 M 件，此处只有最新 N 件」（`G-046`）。
     *
     * <p><b>为什么必须写出来</b>：不声明的话模型会以为清单就是全部，用户问「一共传了几份」
     * 或「最早那份写了什么」时它会照着残缺清单编答案。截断本身可接受（预算有限），
     * <b>静默截断不可接受</b>。</p>
     */
    private static String withTruncationNote(String text, AttachmentSet set) {
        if (!set.truncated()) {
            return text;
        }
        return text + "\n\n（注：本会话共 " + set.total() + " 份附件，此处只含最新的 "
                + set.attachments().size() + " 份；更早的 "
                + (set.total() - set.attachments().size())
                + " 份未注入，如需请用户单独指出或重新上传）";
    }

    /**
     * 从本轮作用域内挑出<b>图片</b>附件，产出媒体引用（不读字节，`VU-9` / 不变量 I-3）。
     *
     * <p><b>为什么只挑图片、不挑音视频</b>：与 {@code AttachmentMediaAssembler} 同一条理由——
     * 在用模型的 {@code capabilities.input} 只声明 {@code text/image}，把音视频塞进消息会被
     * provider 直接拒绝（400）。判定复用 {@link com.helloai.core.shared.util.AttachmentContentPolicy#isMedia}
     * 的图片子集语义（{@code image/*} 前缀，扩展名兜底），<b>不在这里另写扩展名表</b>。</p>
     *
     * <p><b>不看 {@code parseStatus}</b>：图片的 {@code parseStatus} 必然是 {@code SKIPPED}
     * （无解析器），那正是本项要绕开的状态——若要求 {@code READY} 才能送图，
     * 这个功能永远不会有输入。</p>
     */
    private List<MediaRef> pickMediaRefs(List<ChatAttachment> attachments, Set<Integer> scope) {
        List<MediaRef> refs = new ArrayList<>();
        for (int index : scope) {
            ChatAttachment a = attachments.get(index);
            if (!AttachmentContentPolicy.isMedia(a.getMimeType(), a.getFileName())) {
                continue;
            }
            // 音视频同属 isMedia 族，只取图片子集：无 image/* 前缀且扩展名不是图片时跳过。
            if (!isImageAttachment(a)) {
                continue;
            }
            refs.add(MediaRef.ofChatAttachment(a.getId(), a.getFileName(), a.getMimeType(), a.getFileSize()));
        }
        // `G-046`：媒体引用必须「最新在前」。
        // 下游 {@code AttachmentMediaAssembler.loadMerged} 按自己的 {@code MAX_INLINE_COUNT}
        // 顺序取前 N 条即停（那是<b>请求</b>级预算，本类不该另立一个副本去对齐它——判据 4）。
        // 既然截断发生在下游、且只能「取前 N」，那本方法唯一能保证保新的办法就是
        // 把最新的排在前面：N 是多少都不影响结果正确性，多传的尾部自然被丢弃。
        // 若照上传正序输出，会话 8 图时下游取前 5 恰好是最旧的 5 张——同一缺陷的另一半。
        Collections.reverse(refs);
        return List.copyOf(refs);
    }

    /**
     * 是否图片附件：{@code image/*} 前缀优先，缺失 / octet-stream 时按扩展名兜底。
     *
     * <p><b>与 {@code AttachmentMediaAssembler#isInlineImage} 的关系</b>：那条判据的入参是
     * agent 域的 {@code AttachmentRef}（task 域产物快照），本条入参是 planner 域实体，
     * 类型不同无法直接复用；但<b>判定逻辑必须同源</b>——所以这里只做「按 mime 前缀 /
     * 扩展名判图片」这一最小动作，扩展名集合仍取自
     * {@link AttachmentContentPolicy}，不新造一个字面量表。</p>
     */
    private static boolean isImageAttachment(ChatAttachment a) {
        String mime = a.getMimeType() != null ? a.getMimeType().toLowerCase(Locale.ROOT) : "";
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime)) {
            return mime.startsWith("image/");
        }
        // 兜底：AttachmentContentPolicy 的媒体扩展名集里属于图片的那部分
        String ext = AttachmentContentPolicy.extensionOf(a.getFileName());
        return IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 图片扩展名集：<b>只此一处</b>，供 MIME 缺失 / octet-stream 时兜底。
     *
     * <p>为什么不直接用 {@code AttachmentContentPolicy.MEDIA_EXTENSIONS}：那是图片+音频+视频
     * 的总族，直接拿来会把 {@code .mp4} 也判成可送图。</p>
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp");

    /**
     * 渲染结果 + 媒体引用。
     *
     * @param rendered 三层文本（清单 / 正文 / 检索）
     * @param mediaRefs 本轮要送模型的图片引用（绝不为 {@code null}；无图时为空表）
     */
    public record RenderedWithMedia(Rendered rendered, List<MediaRef> mediaRefs) {
    }

    /**
     * 统一计算「本轮参与注入的附件下标集」：显式勾选优先，回落到模型判定的序号。
     *
     * <p><b>三种输入必须区分（`G-045`）</b>：</p>
     * <ul>
     *   <li>{@code selectedIds == null} ⇒ <b>未指定</b>，走回落（模型序号 → 全量）；</li>
     *   <li>{@code selectedIds == []} ⇒ <b>用户明确一个都不要</b>，返回空集，<b>绝不回落全量</b>。
     *       原实现用 {@code !isEmpty()} 判分支，把空集当成了「未指定」，于是用户把附件全点掉
     *       反而得到全部注入——语义完全反转；</li>
     *   <li>{@code selectedIds} 非空但无一匹配本会话 ⇒ 标识失效（跨会话残留 id / 前端 bug），
     *       回退并 warn，此时回落是<b>容错</b>而非语义。</li>
     * </ul>
     *
     * <p>为什么「失效回落」和「明确空集」要区别对待：前者是错误输入，容忍它不给用户添堵；
     * 后者是<b>有效意图</b>，尊重它才是正确行为。把两者混为一谈，用户就再也无法表达
     * 「这一轮别管这些文件」。</p>
     */
    private Set<Integer> resolveScope(List<ChatAttachment> attachments, List<Long> selectedIds,
                                      List<Integer> targets) {
        if (selectedIds != null) {
            // 明确空集：用户已排除全部附件（前端把附件全点掉就是这一态）
            if (selectedIds.isEmpty()) {
                return Set.of();
            }
            Set<Integer> picked = new LinkedHashSet<>();
            for (int i = 0; i < attachments.size(); i++) {
                Long id = attachments.get(i).getId();
                if (id != null && selectedIds.contains(id)) {
                    picked.add(i);
                }
            }
            if (!picked.isEmpty()) {
                return picked;
            }
            log.warn("attachmentIds 与本次会话附件无一匹配（{}），回退为模型判定/全量", selectedIds);
        }
        return resolveScopedIndexes(attachments, targets);
    }

    /**
     * 一次查询渲染三层：清单 / 正文 / 检索片段（`ATT-3` 第三层）。
     *
     * <p><b>第三层只在「有附件被 ingest 过」时产出</b>（{@code kb_source} 非空）：预算内附件
     * 走全量正文注入（更准），超预算附件才进 kb 按需检索。两者互斥，不会对同一份文件
     * 既注入全文又注入片段。</p>
     *
     * <p><b>检索失败一律降级为空</b>：KB 未启用、嵌入接口不可用、会话无 ingest 记录——
     * 都返回占位文本，正文层照常注入。检索是附加值，不能因为它失败让附件读不出来。</p>
     *
     * @param query   本轮用户消息（检索语义锚）；null/空白时跳过第三层
     * @param targets 本轮指向的附件序号（1-based，与清单一致）；空 = 不限
     */
    public Rendered render(Long conversationId, String query, List<Integer> targets) {
        return render(conversationId, query, null, targets);
    }

    /**
     * 第三层：超预算附件的按需检索（`ATT-3`）。
     *
     * <p><b>两重收窄</b>：先按会话（`kb_chunk` 是全平台共享表，不带会话过滤会捞到别的会话），
     * 再按<b>本轮指向的附件</b>——会话里两份文件、用户只问第二份时，只检索第二份的分片，
     * 否则第一份的内容会混进回答并占用注入预算。</p>
     *
     * <p><b>目标集悬空时回退为不限</b>：序号超出实际附件数、或指向的附件没 ingest 过，
     * 都退回会话级检索——宁可多召回一点，不要因为模型报错一个序号就整轮检索不到内容。</p>
     */
    private String buildRetrieval(Long conversationId, String query, List<ChatAttachment> attachments,
                                  Set<Integer> scope) {
        if (knowledgeBaseService == null) {
            return NO_RETRIEVAL; // 兼容构造：未注入 KB
        }
        List<ChatAttachment> scoped = scope.stream().map(attachments::get).toList();
        boolean hasIngested = scoped.stream().anyMatch(a -> a.getKbSource() != null);
        if (!hasIngested || query == null || query.isBlank()) {
            return NO_RETRIEVAL;
        }
        try {
            // 检索用独立预算（窗口 15%）：全局 KbProperties.charBudget 默认只有 4,000 字，
            // 是为全库检索设的；几万字的附件靠它会只能覆盖 3% 左右。
            int retrievalLimit = AttachmentContentPolicy.resolveChatAttachmentRetrievalLimit(contextWindowTokens);
            List<String> sources = scoped.stream()
                    .map(ChatAttachment::getKbSource)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            KnowledgeSearchResult result = knowledgeBaseService.search(
                    query, KB_RETRIEVAL_TOP_K, conversationId, null, retrievalLimit, sources);
            // 成功路径也要留痕：只在失败时打日志，排查时只能靠模型回答反推命中数。
            log.info("附件检索命中: conversationId={}, hits={}, chars={}, limit={}, truncated={}, scopedTo={}",
                    conversationId,
                    result == null || result.hits() == null ? 0 : result.hits().size(),
                    result == null ? 0 : result.totalChars(),
                    retrievalLimit,
                    result != null && result.truncated(),
                    sources.size());
            if (result == null || result.hits() == null || result.hits().isEmpty()) {
                return NO_RETRIEVAL;
            }
            // 命中分片的 source → 附件文件名：引用要能说清「来自哪份文件第几片」
            Map<String, String> nameBySource = attachments.stream()
                    .filter(a -> a.getKbSource() != null)
                    .collect(Collectors.toMap(ChatAttachment::getKbSource,
                            a -> safe(a.getFileName()), (x, y) -> x));
            StringBuilder sb = new StringBuilder();
            // 片段标题里的「第 N 片」会被模型照抄进正文，变成
            // 「正文层…检索层另外召回了第 31–37 片」这类面向工程师的过程汇报。
            // 片号对用户无意义，故在数据处直接声明用途——比只写进模板更靠近注意力焦点。
            sb.append("（以下片段供你取用：只在句末标注锚点，不要向用户复述片号、块标题、召回条数或检索过程）\n");
            for (KnowledgeSearchResult.Hit hit : rerankHits(conversationId, query, result.hits())) {
                String fileName = nameBySource.getOrDefault(hit.source(), "未知文件");
                sb.append("### [").append(fileName).append(" 第 ").append(hit.chunkIdx() + 1).append(" 片]")
                        .append(" `[kb:").append(hit.source()).append('#').append(hit.chunkIdx()).append("]`")
                        .append('\n').append(hit.content()).append("\n\n");
            }
            if (result.truncated()) {
                sb.append("（检索片段已截断：命中数超出注入预算，如需更多请缩小提问范围）\n");
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            log.warn("附件检索失败（降级为无检索片段，正文层不受影响）: conversationId={}, err={}",
                    conversationId, e.toString());
            return NO_RETRIEVAL;
        }
    }

    /**
     * 片段重排（`ATT-3`）：按与提问的语义相关度重排召回片段。
     *
     * <p><b>只在开关开启且博查通道可用时生效</b>：开关关 / 无 Key / 片段不足 2 条 /
     * 请求失败 / 响应不可解析 → 原顺序返回。检索是附加值，重排失败不得让本轮
     * 读不到附件（与正文层、检索层的降级契约一致）。</p>
     *
     * <p><b>边界</b>：字符预算截断发生在上游 {@code KnowledgeBaseService.search} 内，
     * 这里只能调整「已返回片段」的顺序——128K 窗口下 16 片 × 600 字 ≈ 预算 9,600 字，
     * 正常不触发截断。</p>
     */
    private List<KnowledgeSearchResult.Hit> rerankHits(Long conversationId, String query,
                                                       List<KnowledgeSearchResult.Hit> hits) {
        if (!retrievalRerankEnabled || bochaRerankService == null || hits.size() < 2) {
            return hits;
        }
        List<String> documents = hits.stream().map(KnowledgeSearchResult.Hit::content).toList();
        List<Integer> order = bochaRerankService.rerankOrder(query, documents);
        if (order == null) {
            return hits;
        }
        List<KnowledgeSearchResult.Hit> reordered = new ArrayList<>(order.size());
        for (int index : order) {
            reordered.add(hits.get(index));
        }
        log.info("附件片段重排完成: conversationId={}, in={}, out={}, filtered={}",
                conversationId, hits.size(), reordered.size(), hits.size() - reordered.size());
        return reordered;
    }

    /**
     * 决策阶段视图（供「是否联网 + 搜什么词」的联合决策 Prompt 使用）。
     *
     * <p><b>为什么需要独立一层</b>：决策调用发生在正文渲染之前，而 {@link #render} 的两层
     * 只注入正文 Prompt。决策阶段看不到附件时，模型只能拿用户那句问话当检索依据——
     * 「帮我分析一下我上传的附件这个手册方案是否正确」会被改写成「手册 方案 正确性
     * 评估」这类无实体空壳词，检索必然跑偏。同一缺陷在 deer-flow #1647 有完整记录
     * （根因 2「附件内容未注入上下文」/ 根因 4「仅凭文件名判断相关性」）。</p>
     *
     * <p><b>为什么不是全文</b>：决策是轻量调用，注入全文会把成本抬到与正文渲染同档，
     * 也稀释模型注意力。这里只给三样足以支撑决策的信号：清单（有什么文件）、
     * 预览（这份文件讲什么）、锚点（能拿什么词去外部核验）。</p>
     *
     * @param conversationId 会话 id
     * @return 决策视图文本；无附件时为占位文本（不返回空，保持 Prompt 语义节稳定）
     */
    public String renderForDecision(Long conversationId) {
        if (conversationId == null) {
            // 新建会话的首轮决策可能还没有 id：按无附件处理，不查库
            return NO_ATTACHMENT_DECISION;
        }
        List<ChatAttachment> attachments = chatAttachmentService.listByConversation(conversationId);
        if (attachments == null || attachments.isEmpty()) {
            return NO_ATTACHMENT_DECISION;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【附件清单】\n").append(buildManifest(attachments)).append("\n\n");

        sb.append("【内容预览】每份文件前 ").append(DECISION_PREVIEW_CHARS).append(" 字\n");
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            sb.append("### [附件").append(i + 1).append("] ").append(safe(a.getFileName()))
                    .append("（").append(statusText(a)).append("）\n");
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                sb.append("（无可用正文）\n");
                continue;
            }
            String preview = text.length() > DECISION_PREVIEW_CHARS
                    ? text.substring(0, DECISION_PREVIEW_CHARS) + "…"
                    : text;
            sb.append(preview.replaceAll("\\s+", " ").trim()).append('\n');
        }

        List<String> anchors = extractAnchors(attachments);
        sb.append("\n【可外部核验的锚点】从附件全文抽取，构造 search_query 时只能用这些实体\n");
        if (anchors.isEmpty()) {
            sb.append("（未发现：本附件内没有法规名/标准号/技术专名等可外部核验的实体，"
                    + "说明手册内容无需联网核验）\n");
        } else {
            for (String anchor : anchors) {
                sb.append("- ").append(anchor).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从附件全文抽取「可外部核验的实体」：法规名、标准号、英文技术专名。
     *
     * <p>这些是搜索词唯一合法的来源——「手册」「附件」这类指代元词在检索域里是噪音，
     * 而《运输机场使用许可规定》、GB/T 12345、PowerShell 这类专名才能命中真实资料。</p>
     *
     * <p>顺序：先法规名（信号最强），再标准号，最后技术专名；同类按出现顺序，
     * 全局去重后截断到 {@link #ANCHOR_LIMIT}。</p>
     */
    private List<String> extractAnchors(List<ChatAttachment> attachments) {
        Set<String> docTitles = new LinkedHashSet<>();
        Set<String> standardNos = new LinkedHashSet<>();
        Set<String> techNames = new LinkedHashSet<>();
        int scanned = 0;
        for (ChatAttachment a : attachments) {
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                continue;
            }
            if (scanned >= ANCHOR_SCAN_CHARS) {
                break;
            }
            int room = Math.min(text.length(), ANCHOR_SCAN_CHARS - scanned);
            String scope = text.substring(0, room);
            scanned += room;

            collect(DOC_TITLE_PATTERN.matcher(scope), docTitles);
            collect(STANDARD_NO_PATTERN.matcher(scope), standardNos);

            Matcher tech = TECH_NAME_PATTERN.matcher(scope);
            while (tech.find()) {
                String name = tech.group();
                if (!TECH_STOP_WORDS.contains(name)) {
                    techNames.add(name);
                }
            }
        }
        List<String> out = new ArrayList<>();
        appendLimited(out, docTitles);
        appendLimited(out, standardNos);
        appendLimited(out, techNames);
        return out;
    }

    /** 把匹配结果按出现顺序并入集合（去掉两侧空白与空项）。 */
    private static void collect(Matcher matcher, Set<String> target) {
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isBlank()) {
                target.add(value);
            }
        }
    }

    /** 依次追加去重后的锚点，达到上限即停。 */
    private static void appendLimited(List<String> out, Set<String> candidates) {
        for (String candidate : candidates) {
            if (out.size() >= ANCHOR_LIMIT) {
                return;
            }
            if (!out.contains(candidate)) {
                out.add(candidate);
            }
        }
    }

    /** 清单：序号 / 文件名 / 类型 / 大小 / 页数 / 状态 / 正文字数。 */
    private String buildManifest(List<ChatAttachment> attachments) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 序号 | 文件名 | 类型 | 大小 | 页数 | 状态 | 正文字数 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            sb.append("| 附件").append(i + 1)
                    .append(" | ").append(safe(a.getFileName()))
                    .append(" | ").append(safe(a.getFileType()))
                    .append(" | ").append(sizeText(a.getFileSize()))
                    .append(" | ").append(a.getPageCount() == null ? "-" : a.getPageCount())
                    .append(" | ").append(statusText(a))
                    .append(" | ").append(a.getExtractedChars() == null ? "-" : a.getExtractedChars())
                    .append(" |\n");
        }
        return sb.toString().trim();
    }

    /** 正文：按预算逐份注入，超预算显式标注。 */
    private String buildContext(List<ChatAttachment> attachments, Set<Integer> inScope) {
        // `G-045`：明确空集 ≠ 全量回落。此时必须显式声明「用户排除了全部」，否则逐份渲染
        // 出来的都是「本轮未涉及该附件：提问指向的是其他附件」——那句话在空集场景下是
        // 误导：根本没有「其他附件」，模型会以为自己在看一份不完整的清单。
        if (attachments != null && !attachments.isEmpty() && inScope.isEmpty()) {
            return "（用户本轮已排除全部 " + attachments.size()
                    + " 份附件：不要引用任何附件内容，也不要猜测文件里写了什么；"
                    + "如确实需要，请明确请用户重新勾选对应文件）";
        }
        int totalLimit = AttachmentContentPolicy.resolveChatAttachmentTotalLimit(contextWindowTokens);
        int perFileLimit = Math.min(AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT, totalLimit);
        int[] quota = planContextQuota(attachments, totalLimit, perFileLimit, inScope);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            // 序号必须沿用清单的 1-based 全量顺序，否则模型对不上清单条目
            String header = "### [附件" + (i + 1) + "] " + safe(a.getFileName()) + "（" + statusText(a) + "）";
            // 本轮提问未指向的附件：只留标题 + 说明，不注入正文（省预算、防混淆）
            if (!inScope.contains(i)) {
                sb.append(header).append('\n')
                        .append("（本轮未涉及该附件：提问指向的是其他附件；如需查看其内容请明确指出文件）\n\n");
                continue;
            }
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                // ★VU-9：图片的「无正文」是设计如此（正文通道只走文本），不是缺陷。
                // 若沿用「（无可用正文：…）」会让模型判定「这张图读不出、当它不存在」，
                // 与已经送出的图像消息自相矛盾。
                String fallback = a.getParseStatus() == ChatAttachmentParseStatus.SKIPPED && isImageAttachment(a)
                        ? "（图片无文本正文：图像内容已另经图像通道送入，请直接依据图像作答）"
                        : "（无可用正文"
                          + (a.getParseError() == null || a.getParseError().isBlank()
                                ? "" : "：" + a.getParseError())
                          + "）";
                sb.append(header).append('\n').append(fallback).append("\n\n");
                continue;
            }
            int granted = quota[i];
            if (granted <= 0) {
                sb.append(header).append('\n')
                        .append("（本次未注入正文：上下文预算已用尽，如需该文件内容请让用户缩小范围或拆分为多个文件分批上传）\n\n");
                continue;
            }
            boolean truncated = text.length() > granted;
            String body = truncated ? text.substring(0, granted) : text;
            sb.append(header).append('\n')
                    .append(body).append('\n')
                    .append(truncated
                            ? "（该文件正文已截断：原文 " + text.length() + " 字，本次仅注入前 " + granted
                              + " 字；如需后半部分请拆分为多个文件分批上传）\n\n"
                            : "\n");
            log.debug("附件正文注入: fileName={}, chars={}, truncated={}, totalLimit={}",
                    a.getFileName(), body.length(), truncated, totalLimit);
        }
        return sb.toString().trim();
    }

    /**
     * 把「本轮指向的附件序号」解析成**0-based 下标集合**供正文层使用。
     *
     * <p>与 {@link #resolveScopedAttachments} 的区别：那个返回附件对象（检索层用），
     * 这个返回下标（正文层要按原顺序遍历、且需要判断「某项是否在范围内」）。
     * 语义一致：未指定 / 全部越界 → 返回全部下标（会话级回退）。</p>
     */
    private Set<Integer> resolveScopedIndexes(List<ChatAttachment> attachments, List<Integer> targets) {
        Set<Integer> all = new LinkedHashSet<>();
        for (int i = 0; i < attachments.size(); i++) {
            all.add(i);
        }
        if (targets == null || targets.isEmpty()) {
            return all;
        }
        Set<Integer> picked = new LinkedHashSet<>();
        for (Integer idx : targets) {
            if (idx != null && idx >= 1 && idx <= attachments.size()) {
                picked.add(idx - 1);
            }
        }
        return picked.isEmpty() ? all : picked;
    }

    /**
     * 把「本轮指向的附件序号」解析成附件列表：序号 1-based、越界忽略；
     * 全部越界或未指定 → 返回全部（会话级回退）。
     */
    private List<ChatAttachment> resolveScopedAttachments(List<ChatAttachment> attachments,
                                                          List<Integer> targets) {
        return resolveScopedIndexes(attachments, targets).stream().map(attachments::get).toList();
    }

    /**
     * 分配每个附件的正文注入额度（`ATT-3` per-file 配额）：**均分保底 + 余量回收**。
     *
     * <p><b>为什么不能先到先得</b>：原实现按附件顺序切预算，第一个文件可以吃掉
     * 全部 25,600 字（`perFileLimit` 被总预算压制），后面的文件一律「预算已用尽」。
     * 实测（LOG-20261023-024）：7,201 字的小文件排在前面，把 90,385 字的主附件挤到只有
     * 18,399 字；三个以上附件时第三个直接拿 0。</p>
     *
     * <p><b>为什么不能简单均分</b>：两个文件时均分会让小文件「要多少给多少」后余量闲置
     * ——7,201 字的文件只需求 7,201，均分额 12,800 剩 5,599 白扔，总注入反而少于先到先得。</p>
     *
     * <p><b>本实现</b>：第一轮每项按 {@code totalLimit / n} 保底（不超过自身长度与单文件上限），
     * 第二轮把没用完的余量按「需求量从小到大」补回被截断的文件——小文件先补满，
     * 大文件吃剩下的。既保证每个文件都有份额，又不浪费预算。</p>
     *
     * @return 与 {@code attachments} 同序的额度数组；不可读项为 0
     */
    private int[] planContextQuota(List<ChatAttachment> attachments, int totalLimit, int perFileLimit,
                                   Set<Integer> inScope) {
        int size = attachments.size();
        int[] quota = new int[size];
        int[] demand = new int[size];
        List<Integer> truncatedIdx = new ArrayList<>();
        long used = 0;
        int readable = 0;

        for (int i = 0; i < size; i++) {
            if (!inScope.contains(i)) {
                continue; // 本轮未涉及的附件：不参与均分，否则会白占一份份额
            }
            ChatAttachment a = attachments.get(i);
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                continue; // 不可读：额度 0，也不参与均分
            }
            readable++;
            demand[i] = Math.min(text.length(), perFileLimit);
        }
        if (readable == 0) {
            return quota;
        }

        int share = Math.max(1, totalLimit / readable);
        for (int i = 0; i < size; i++) {
            if (demand[i] <= 0) {
                continue;
            }
            quota[i] = Math.min(demand[i], share);
            used += quota[i];
            if (quota[i] < demand[i]) {
                truncatedIdx.add(i);
            }
        }

        // 第二轮：余量按「剩余需求从小到大」补给被截断项——小文件先补满，避免余量被大文件独占
        long pool = totalLimit - used;
        truncatedIdx.sort(Comparator.comparingInt(i -> demand[i] - quota[i]));
        for (int idx : truncatedIdx) {
            if (pool <= 0) {
                break;
            }
            int gap = demand[idx] - quota[idx];
            int extra = (int) Math.min(pool, gap);
            quota[idx] += extra;
            pool -= extra;
        }
        return quota;
    }

    /** 状态词：面向模型，不用枚举名（PENDING/PARSING 对模型是噪音）。 */
    private static String statusText(ChatAttachment a) {
        ChatAttachmentParseStatus status = a.getParseStatus();
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case READY -> "可读";
            case PENDING, PARSING -> "解析中（本轮可能还读不到正文）";
            case FAILED -> "解析失败";
            // ★VU-9 三态（不变量 I-4）：图片走媒体通道送出，正文为空是<b>设计如此</b>，
            // 不能再说「需识别（无正文）」——那句话会让模型以为图看不到，进而对着一张
            // 已经送到的图说「我看不到图片」。这正是 OpenCode #60736 的成因形态。
            case SKIPPED -> mediaStatusText(a);
        };
    }

    /**
     * {@code SKIPPED} 的状态词：按「是否图片」分两态（第三态「明文看不到」由
     * {@code AgentChatClientService} 在真正发请求时决定，见下）。
     *
     * <p><b>为什么本层不能直接写「已随本轮消息提供图像」</b>：本层不知道实际调用的模型
     * 能不能看图（模型可能在这之后再被钉住/覆盖）。所以这里只写<b>不含可见性断言</b>的中性
     * 陈述——「图片，另经图像通道送入」——可见性由请求装配层按实际模型判定后补，
     * 那里才是唯一知道真相的位置（判据 4：同一事实只留一个事实源）。</p>
     */
    private static String mediaStatusText(ChatAttachment a) {
        return isImageAttachment(a) ? "图片（另经图像通道送入）" : "音视频（正文不可提取）";
    }

    private static String sizeText(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }

    private static String safe(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }
}
