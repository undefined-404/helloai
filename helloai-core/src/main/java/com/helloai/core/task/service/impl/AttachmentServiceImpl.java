package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.common.constant.AttachmentVisibility;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.AttachmentMapper;
import com.helloai.core.task.policy.AttachmentVisibilityPolicy;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.support.AttachmentObjectPurgeSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件服务实现 — 管理 SubTask 的产物附件元数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl extends ServiceImpl<AttachmentMapper, Attachment> implements AttachmentService {

    private final SubTaskService subTaskService;
    private final TaskService taskService;
    private final ArtifactStorage artifactStorage;

    /**
     * 附件可见性判据（"声明范围 × 任务成员关系"的唯一入口）。
     *
     * <p><b>依赖方向说明（§7.1/§8.1）</b>：本类 → {@code task.policy} 是域内前向依赖。
     * 该 Policy 自身又依赖 {@code SubTaskService}，而 {@code SubTaskServiceImpl} 反向依赖
     * 本服务 —— 该回边已由 {@code SubTaskServiceImpl} 用 {@code ObjectProvider} 懒解析打破
     * （见其字段注释），故此处新增的是<b>构造器强依赖</b>但不构成 Spring 循环引用；
     * 新增依赖后仍能正常装配（已由 {@code AttachmentControllerAuthScopeTest} 与启动验证覆盖）。</p>
     */
    private final AttachmentVisibilityPolicy attachmentVisibilityPolicy;

    /** timeline 审计（P2 删除通道，2026-10-07）：删除附件落 {@code attachment_deleted} 事件。 */
    private final TaskTimelineService taskTimelineService;

    /** 对象存储回收（P3-3 / P2，2026-10-07）：事务提交后 best-effort 删对象；独立无环承载点，见其类注释。 */
    private final AttachmentObjectPurgeSupport attachmentObjectPurgeSupport;

    /**
     * 注册产物附件元数据。
     * 仅允许对归属于 agentId 的 SubTask 上传附件。
     *
     * <p><b>前置校验</b>：storageUrl 非空 → 地址合法性（如 minio:// 的 bucket 段必须是平台桶）
     * → 平台可读协议（{@code local://}/{@code minio://}）下对象真实存在。任一不过抛
     * {@link BizException}(400)，不写库。外部 {@code https://} 等平台不可读地址仅在
     * "已有对象可访问"的登记场景使用，不做探测。</p>
     *
     * <p>版本语义：同子任务同名文件的旧 ACTIVE 版本自动置
     * INACTIVE，保证任意文件名至多一条有效版本——核验/依赖装载/交付物打包
     * 只认最新版，杜绝"同名多版本并存导致 Reviewer 反复打回"的死循环；
     * 历史版本保留（状态回查 + 内容直读不受影响）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Attachment register(Long agentId, Long subTaskId,
                               String fileName, String mimeType, Long fileSize,
                               String storageUrl) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.getAssignedAgentId())) {
            throw new BizException("无权为该子任务上传附件: subTaskId=" + subTaskId + ", agentId=" + agentId);
        }

        // 地址校验 + 存在性校验：把"有记录无对象"的僵尸附件挡在写库之前。
        // 修复前（尤其 MCP uploadArtifact 仅登记元数据的场景）对象不存在也能注册成功，
        // 事后预览/证据核验才抛 500，且 attachment 表已被污染。
        // 现在不合法 / 对象不存在直接 400；本方法带 @Transactional，抛错会连同下方
        // 同名去活一起回滚，旧 ACTIVE 版本不受影响。
        if (storageUrl == null || storageUrl.isBlank()) {
            throw new BizException(400, "storageUrl 不能为空: subTaskId=" + subTaskId);
        }
        artifactStorage.validateAddress(storageUrl);
        if (artifactStorage.supports(storageUrl) && !artifactStorage.exists(storageUrl)) {
            throw new BizException(400, "产物对象不存在，拒绝注册附件（请先上传文件内容）: " + storageUrl);
        }

        // 同子任务同名 ACTIVE 旧版批量去活（含历史多版本），新注册版本成为唯一有效版
        boolean superseded = lambdaUpdate()
                .eq(Attachment::getSubTaskId, subTaskId)
                .eq(Attachment::getFileName, fileName)
                .eq(Attachment::getStatus, AttachmentStatus.ACTIVE)
                .set(Attachment::getStatus, AttachmentStatus.INACTIVE)
                .update();
        if (superseded) {
            log.info("附件同名去活: subTaskId={}, fileName={}", subTaskId, fileName);
        }

        Attachment attachment = new Attachment();
        attachment.setSubTaskId(subTaskId);
        attachment.setFileName(fileName);
        attachment.setFileType(detectFileType(fileName));
        attachment.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
        attachment.setFileSize(fileSize != null ? fileSize : 0L);
        attachment.setBucketName(detectBucketName(storageUrl));
        attachment.setObjectKey(detectObjectKey(storageUrl, subTaskId, fileName));
        attachment.setStorageUrl(storageUrl);
        attachment.setStatus(AttachmentStatus.ACTIVE);
        // Task-Team 可见性（V99/V100）：上传者身份 + 默认可见范围。
        // 上传者用 register 的 agentId（上方已强校验 agentId == subTask.assignedAgentId）；
        // 默认 TASK（上传者 + 所属根任务团队），与 DB 列 DEFAULT 'TASK' 同口径。
        // 平台侧/人工上传（非本方法）无 agentId ⇒ uploaderAgentId 为空，PERSONAL 语义下仅平台可见。
        attachment.setUploaderAgentId(agentId);
        attachment.setVisibility(AttachmentVisibility.TASK);
        save(attachment);

        log.info("附件注册: id={}, subTaskId={}, fileName={}", attachment.getId(), subTaskId, fileName);
        return attachment;
    }

    /**
     * 按子任务 ID 查询附件列表（按创建时间倒序）。
     *
     * <p>{@code subTaskId} 为空时返回所有附件；逻辑删除由 {@code @TableLogic}
     * 自动过滤。含全部状态（ACTIVE/INACTIVE/DELETED），供附件管理页
     * 历史版本回查使用。</p>
     *
     * @param subTaskId 可选子任务 ID 过滤；null 表示不限
     * @return 附件列表（绝不返回 null）
     */
    @Override
    public List<Attachment> list(Long subTaskId) {
        List<Attachment> result = lambdaQuery()
                .eq(subTaskId != null, Attachment::getSubTaskId, subTaskId)
                .orderByDesc(Attachment::getCreateTime)
                .list();
        fillBrowseTitles(result);
        return result;
    }

    /**
     * 按子任务 ID 查询<b>指定 agent 可读</b>的附件列表（按创建时间倒序）。
     *
     * <p>在 {@link #list(Long)} 的全量结果上逐条过 {@code AttachmentVisibilityPolicy}：
     * 同子任务下不同 {@code visibility} 的附件必须行级剔除（而非整表放行/整表 403），
     * 否则要么团队产出互通能力丢失（整表 403），要么越权（整表放行）。</p>
     */
    @Override
    public List<Attachment> listReadable(Long subTaskId, Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        return list(subTaskId).stream()
                .filter(attachment -> attachmentVisibilityPolicy.canRead(agentId, attachment))
                .toList();
    }

    /**
     * 校验指定 agent 可读该附件；不可读抛 {@link BizException}(403)。
     *
     * <p>判定逻辑零复制，一律委托 {@code AttachmentVisibilityPolicy}（唯一入口）。
     * 任何在本方法内"顺手补一条 if"的写法都会造成判据分散，属禁止项。</p>
     */
    @Override
    public void assertReadable(Attachment attachment, Long agentId) {
        if (!attachmentVisibilityPolicy.canRead(agentId, attachment)) {
            throw new BizException(403, "无权访问该附件（不在可见范围内）");
        }
    }

    /**
     * 校验指定 agent 可删除该附件；不可删抛 {@link BizException}(403)。
     * 判据零复制，一律委托 {@code AttachmentVisibilityPolicy#canDelete}（仅上传者）。
     */
    @Override
    public void assertDeletable(Attachment attachment, Long agentId) {
        if (!attachmentVisibilityPolicy.canDelete(agentId, attachment)) {
            throw new BizException(403, "仅上传者可删除该附件: id="
                    + (attachment != null ? attachment.getId() : null));
        }
    }

    /**
     * 删除附件（P2 删除通道）：软删 DB 行（{@code @TableLogic}）+ 事务提交后 best-effort 回收对象 +
     * 落 timeline 事件 {@code attachment_deleted}。
     *
     * <p>幂等（不存在/已删直接返回）与 fail-safe（对象回收失败不回滚）语义见接口注释。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(Long id, Long requesterAgentId) {
        if (id == null) {
            return;
        }
        Attachment attachment = getById(id);
        if (attachment == null) {
            // 幂等：不存在 / 已逻辑删除（@TableLogic 过滤）—— 不 404/500，便于外部 Agent 重试
            log.info("附件删除幂等命中（不存在或已删）: id={}", id);
            return;
        }
        // 判据收口：仅 Agent 通道（requesterAgentId 非 null）强制「仅上传者可删」；平台通道放行
        if (requesterAgentId != null) {
            assertDeletable(attachment, requesterAgentId);
        }
        // 软删（保留审计痕迹；物理删仅用于任务级联删除路径）
        removeById(id);

        // timeline 审计：与 register（attachment 注册）对称的销毁留痕
        Map<String, Object> payload = new HashMap<>();
        payload.put("attachmentId", attachment.getId());
        payload.put("subTaskId", attachment.getSubTaskId());
        payload.put("fileName", attachment.getFileName());
        if (requesterAgentId != null) {
            payload.put("agentId", requesterAgentId);
        }
        taskTimelineService.recordEvent(attachmentVisibilityPolicy.rootTaskIdOf(attachment),
                attachment.getSubTaskId(), "attachment_deleted",
                requesterAgentId != null ? AgentRole.EXECUTOR : AgentRole.SYSTEM, requesterAgentId, payload);

        // 事务提交后 best-effort 回收对象存储（fail-safe，不阻断删除结果）
        attachmentObjectPurgeSupport.purgeAfterCommit(List.of(attachment));
        log.info("附件已删除: id={}, subTaskId={}, objectKey={}",
                id, attachment.getSubTaskId(), attachment.getObjectKey());
    }

    /**
     * 按子任务 ID 查询有效（ACTIVE）附件列表（按创建时间倒序）。
     *
     * <p>平台可信视角：{@link #register} 已保证同名文件至多一条 ACTIVE 记录，
     * 因此核验证据、上游产出装载、交付物打包等处直接使用本方法即可
     * 拿到"当前有效版本"，无需自行去重。</p>
     *
     * @param subTaskId 子任务 ID（null 表示不限）
     * @return 有效附件列表（绝不返回 null）
     */
    @Override
    public List<Attachment> listActive(Long subTaskId) {
        List<Attachment> result = lambdaQuery()
                .eq(subTaskId != null, Attachment::getSubTaskId, subTaskId)
                .eq(Attachment::getStatus, AttachmentStatus.ACTIVE)
                .orderByDesc(Attachment::getCreateTime)
                .list();
        fillBrowseTitles(result);
        return result;
    }

    /**
     * 驳回打回时将该子任务全部有效（ACTIVE）附件批量置为 INACTIVE。
     *
     * <p>与 {@link #register} 同名去活互补：register 解决"再次上传同名时旧版失效"，
     * 本方法解决"被打回但尚未重新上传时旧证据仍 ACTIVE"——核验证据 /
     * 上游装载 / 交付物打包统一走 {@link #listActive} 后，打回即失效、
     * 重新上传最新版才恢复，杜绝旧版反复进入核验造成死循环。</p>
     */
    @Override
    public void invalidateBySubTask(Long subTaskId) {
        if (subTaskId == null) {
            return;
        }
        boolean updated = lambdaUpdate()
                .eq(Attachment::getSubTaskId, subTaskId)
                .eq(Attachment::getStatus, AttachmentStatus.ACTIVE)
                .set(Attachment::getStatus, AttachmentStatus.INACTIVE)
                .update();
        if (updated) {
            log.info("附件打回失效: subTaskId={}", subTaskId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意走 Mapper 的自定义全表查询：{@code @TableLogic} 会给所有 MyBatis-Plus
     * 内置查询自动追加 {@code deleted=0}，而本方法必须看到逻辑删除行，
     * 否则对账巡检会把"仍有已删记录指向"的对象误判成孤儿。</p>
     */
    @Override
    public List<Attachment> listAllIncludingDeleted() {
        return baseMapper.selectAllIncludingDeleted();
    }

    /** 回填主任务/子任务标题（transient 字段，不落库），供附件管理按层级浏览时展示名称。 */
    private void fillBrowseTitles(List<Attachment> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        Set<Long> subTaskIds = result.stream().map(Attachment::getSubTaskId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (subTaskIds.isEmpty()) {
            return;
        }
        Map<Long, SubTask> subTaskMap = subTaskService.listByIds(subTaskIds).stream()
                .collect(Collectors.toMap(SubTask::getId, s -> s, (a, b) -> a));
        Set<Long> taskIds = subTaskMap.values().stream().map(SubTask::getTaskId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Task> taskMap = taskIds.isEmpty() ? Collections.emptyMap()
                : taskService.listByIds(taskIds).stream()
                        .collect(Collectors.toMap(Task::getId, t -> t, (a, b) -> a));
        for (Attachment att : result) {
            SubTask subTask = subTaskMap.get(att.getSubTaskId());
            if (subTask == null) {
                continue;
            }
            att.setTaskId(subTask.getTaskId());
            att.setSubTaskTitle(subTask.getTitle());
            Task task = taskMap.get(subTask.getTaskId());
            if (task != null) {
                att.setTaskTitle(task.getTitle());
            }
        }
    }

    /**
     * 按 ID 查询附件；不存在时抛 {@link BizException}(404, "附件不存在")，
     * 供 Controller 统一透传给全局异常处理。
     *
     * @param id 附件主键
     * @return 附件实体
     * @throws BizException 当附件不存在
     */
    @Override
    public Attachment getByIdRequired(Long id) {
        Attachment attachment = getById(id);
        if (attachment == null) {
            throw new BizException(404, "附件不存在");
        }
        return attachment;
    }

    /**
     * 获取附件存储地址（用于下载重定向）。
     *
     * @param id 附件主键
     * @return 存储 URL；为空或空白时抛 {@link BizException}(500, "附件存储地址不可用")
     * @throws BizException 当附件不存在或存储地址不可用
     */
    @Override
    public String getStorageUrlRequired(Long id) {
        Attachment attachment = getByIdRequired(id);
        String downloadUrl = attachment.getStorageUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new BizException(500, "附件存储地址不可用");
        }
        return downloadUrl;
    }

    /**
     * 判断附件是否可由平台直接读取内容（local:// 或 minio:// 产物）；
     * 不可读时下载链路回退 302 重定向到外部存储地址。
     */
    @Override
    public boolean isContentLoadable(Attachment attachment) {
        return attachment != null && artifactStorage.supports(attachment.getStorageUrl());
    }

    /**
     * 读取附件内容字节（仅限 {@link #isContentLoadable} 的平台可读产物）。
     *
     * @param id 附件主键
     * @return 文件内容
     * @throws BizException 附件不存在 / 地址不可读 / 文件缺失
     */
    @Override
    public byte[] loadContent(Long id) {
        Attachment attachment = getByIdRequired(id);
        if (!artifactStorage.supports(attachment.getStorageUrl())) {
            throw new BizException("附件不支持平台直读: id=" + id);
        }
        return artifactStorage.load(attachment.getStorageUrl());
    }

    /**
     * 浏览器内联预览文件大小上限（5 MiB）。
     * 经验值：5MB 以下浏览器 iframe / pre 渲染尚可，超过会出现明显卡顿，
     * 应引导走下载。
     */
    private static final long PREVIEW_MAX_SIZE_BYTES = 5L * 1024 * 1024;

    /**
     * 浏览器可内联预览的 MIME 前缀 / 精确值白名单。
     * 命中其一即可走 inline 渲染；其余类型统一走下载。
     */
    private static final Set<String> PREVIEWABLE_MIME_PREFIXES = Set.of(
            "text/",
            "image/",
            "application/json",
            "application/xml",
            "application/yaml"
    );
    private static final Set<String> PREVIEWABLE_MIME_EXACT = Set.of(
            "application/pdf"
    );

    /**
     * 推断预览所需 MIME（按 fileName 后缀 → attachment.mimeType → octet-stream）。
     * 文本类追加 charset=UTF-8，避免浏览器按 GBK 误读中文日志。
     */
    @Override
    public String resolveContentType(Attachment attachment) {
        if (attachment == null) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String byName = detectContentTypeByName(attachment.getFileName());
        if (byName != null) {
            return byName;
        }
        String stored = attachment.getMimeType();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /**
     * 判定附件是否适合浏览器内联预览。
     * 必经三关：平台可读 + MIME 命中白名单 + 文件大小未超阈值。
     */
    @Override
    public boolean isPreviewable(Attachment attachment) {
        if (attachment == null) {
            return false;
        }
        if (!isContentLoadable(attachment)) {
            return false;
        }
        Long size = attachment.getFileSize();
        if (size != null && size > PREVIEW_MAX_SIZE_BYTES) {
            return false;
        }
        String mime = resolveContentType(attachment);
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

    /**
     * 按 fileName 后缀推断 MIME（与 {@link com.helloai.core.system.storage.MinioArtifactStorage#detectContentType}
     * 同步演进，但由 Service 私有持有，避免 Storage 接口被 Controller 侧语义污染）。
     * 文本类统一追加 charset=UTF-8；识别失败返回 null。
     */
    private String detectContentTypeByName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown;charset=UTF-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json;charset=UTF-8";
        }
        if (lower.endsWith(".xml")) {
            return "application/xml;charset=UTF-8";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "application/yaml;charset=UTF-8";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv;charset=UTF-8";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html;charset=UTF-8";
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF_VALUE;
        }
        if (lower.endsWith(".svg")) {
            // Spring 6.x MediaType 没有 IMAGE_SVG_VALUE 常量，这里直接用字面量
            return "image/svg+xml";
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        // JS 家族源码（.js / .mjs / .cjs / .jsx）：
        // 统一按 text/javascript 返回，命中 PREVIEWABLE_MIME_PREFIXES 中的 text/ 前缀。
        if (lower.endsWith(".js") || lower.endsWith(".mjs")
                || lower.endsWith(".cjs") || lower.endsWith(".jsx")) {
            return "text/javascript;charset=UTF-8";
        }
        // TS 家族源码（.ts / .tsx）：text/typescript 是事实标准，浏览器/编辑器通用识别。
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "text/typescript;charset=UTF-8";
        }
        return null;
    }

    private String detectFileType(String fileName) {
        if (fileName == null) return "other";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".log") || lower.endsWith(".txt")) return "log";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".zip") || lower.endsWith(".tar.gz")) return "archive";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image";
        return "other";
    }

    /**
     * 从 storageUrl 推导 bucketName。
     * 支持 minio://bucket/objectKey、s3://bucket/objectKey；
     * 解析失败时返回默认 bucket "helloai"。
     */
    private String detectBucketName(String storageUrl) {
        if (storageUrl == null || storageUrl.isBlank()) {
            return "helloai";
        }
        String prefix = null;
        if (storageUrl.startsWith("minio://")) {
            prefix = "minio://";
        } else if (storageUrl.startsWith("s3://")) {
            prefix = "s3://";
        } else if (storageUrl.startsWith("oss://")) {
            prefix = "oss://";
        } else if (storageUrl.startsWith("local://")) {
            prefix = "local://";
        }
        if (prefix == null) {
            return "helloai";
        }
        String rest = storageUrl.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return "helloai";
        }
        return rest.substring(0, slash);
    }

    /**
     * 从 storageUrl 推导 objectKey。
     * 解析失败时使用 subTaskId/fileName 构造默认 key。
     */
    private String detectObjectKey(String storageUrl, Long subTaskId, String fileName) {
        if (storageUrl != null && !storageUrl.isBlank()) {
            String prefix = null;
            if (storageUrl.startsWith("minio://")) {
                prefix = "minio://";
            } else if (storageUrl.startsWith("s3://")) {
                prefix = "s3://";
            } else if (storageUrl.startsWith("oss://")) {
                prefix = "oss://";
            } else if (storageUrl.startsWith("local://")) {
                prefix = "local://";
            }
            if (prefix != null) {
                String rest = storageUrl.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0 && slash < rest.length() - 1) {
                    return rest.substring(slash + 1);
                }
            }
        }
        return (subTaskId != null ? subTaskId : "0") + "/" + (fileName != null ? fileName : "unknown");
    }

    // ── 只读快照（RM5 批 4）──

    @Override
    public List<AttachmentView> listActiveViews(Long subTaskId) {
        return listActive(subTaskId).stream().map(this::toView).toList();
    }

    /** 实体 → 只读快照；{@code contentLoadable} 由提供方判定（W11），消费方零判定。 */
    private AttachmentView toView(Attachment att) {
        return new AttachmentView(att.getId(), att.getFileName(), att.getFileType(), att.getMimeType(),
                att.getFileSize(), isContentLoadable(att));
    }
}
