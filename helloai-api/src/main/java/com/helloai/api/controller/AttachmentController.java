package com.helloai.api.controller;

import com.helloai.api.dto.attachment.AttachmentVO;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 附件 Controller — 列表 / 详情 / 下载 / 预览。
 *
 * <p><b>分层约束（§8.1 / §11.3）</b>：本类只依赖 {@link AttachmentService} 契约 ——
 * 可见性判据（{@code task.policy.AttachmentVisibilityPolicy}）由 Service 内部调用，
 * Controller <b>不再直接 import 策略组件</b>；响应体一律经 {@link AttachmentVO} 投影，
 * <b>不直接暴露数据库 Entity</b>。下载 / 预览端点内部虽仍持有实体（取文件名、大小、存储地址），
 * 但实体不出现在任何响应体中，故不违反 §11.3。</p>
 *
 * <p>Mapper 调用已全部下沉至 {@link AttachmentService}；
 * 缺失值与"附件不存在 / 存储地址不可用"两类失败统一由 Service
 * 抛 {@code BizException}，由全局异常处理转换为 R.fail。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * 附件列表。
     *
     * <p><b>2026-10-02（Task-Team 可见性）</b>：由「按 subTaskId 整表放行/拦截」改为
     * <b>按附件粒度过滤</b> —— 同一子任务下的附件可能具有不同 {@code visibility}，
     * 故 Agent 通道下逐条过滤，不可读者从结果中剔除（而非整表 403）。
     * 平台账号 / 无主体请求放行（由管理侧鉴权覆盖）。</p>
     */
    @GetMapping
    public R<List<AttachmentVO>> list(
            @RequestParam(value = "subTaskId", required = false) Long subTaskId,
            @RequestAttribute(value = "_authType", required = false) String authType,
            @RequestAttribute(value = "_authId", required = false) Long agentId) {
        List<Attachment> attachments = isAgentChannel(authType, agentId)
                ? attachmentService.listReadable(subTaskId, agentId)
                : attachmentService.list(subTaskId);
        return R.ok(AttachmentVO.fromList(attachments));
    }

    /**
     * 附件详情；不存在抛 BizException(404) 由全局异常处理返回 R.fail。
     */
    @GetMapping("/getById/{id}")
    public R<AttachmentVO> getById(@PathVariable("id") Long id,
                                   @RequestAttribute(value = "_authType", required = false) String authType,
                                   @RequestAttribute(value = "_authId", required = false) Long agentId) {
        return R.ok(AttachmentVO.from(loadReadable(id, authType, agentId)));
    }

    /**
     * 附件下载：方案2 本地物化产物（local://）由平台读取内容流式返回
     * （Content-Disposition 用 RFC 5987 filename* 承载中文文件名）；
     * 外部对象存储地址仍保持 302 重定向。
     */
    @GetMapping("/downloadById/{id}")
    public ResponseEntity<byte[]> downloadById(@PathVariable("id") Long id,
                                               @RequestAttribute(value = "_authType", required = false) String authType,
                                               @RequestAttribute(value = "_authId", required = false) Long agentId) {
        Attachment attachment = loadReadable(id, authType, agentId);
        if (attachmentService.isContentLoadable(attachment)) {
            byte[] content = attachmentService.loadContent(id);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(attachment.getFileName(), StandardCharsets.UTF_8)
                    .build());
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        }
        String downloadUrl = attachmentService.getStorageUrlRequired(id);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }

    /**
     * 附件浏览器内联预览：直接渲染 txt / log / md / json / 图片 / pdf 等小文件，
     * 浏览器不再触发下载。Content-Disposition 用 inline，Content-Type 命中白名单 MIME。
     * 超出大小阈值 / 非预览类型 / 不可由平台直读时抛 BizException，
     * 由前端捕获并提示"请使用下载"。
     */
    @GetMapping("/previewById/{id}")
    public ResponseEntity<byte[]> previewById(@PathVariable("id") Long id,
                                              @RequestAttribute(value = "_authType", required = false) String authType,
                                              @RequestAttribute(value = "_authId", required = false) Long agentId) {
        Attachment attachment = loadReadable(id, authType, agentId);
        if (!attachmentService.isPreviewable(attachment)) {
            // 413 与 RFC 7231 一致；前端可统一捕获 413 走下载
            throw new BizException(413, "附件过大或类型不支持浏览器内联预览，请使用下载");
        }
        String contentType = attachmentService.resolveContentType(attachment);
        byte[] content = attachmentService.loadContent(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(attachment.getFileName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.parseMediaType(contentType));
        log.info("附件内联预览: id={}, fileName={}, size={}, mime={}",
                id, attachment.getFileName(), content.length, contentType);
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }

    /**
     * 加载附件并按通道做可读性校验。
     *
     * <p><b>判据收口</b>：Agent 通道的可读性判定<b>唯一入口</b>是
     * {@link AttachmentService#assertReadable(Attachment, Long)}（其内部委托
     * {@code AttachmentVisibilityPolicy}）。本类不做任何可见性判断，只负责
     * "通道判定"这一 HTTP 关注点。</p>
     *
     * <p><b>历史（G-014 T04b）</b>：此前四端点无任何归属校验，任意有效 Agent API Key 可凭
     * {@code attachmentId} 读取任意子任务的附件正文。当时的修复是"仅可读自己名下子任务"——
     * 那属于 sub_task 级硬隔离，导致<b>同任务团队内无法互通产出物</b>。</p>
     *
     * <p><b>2026-10-02（Task-Team 可见性）</b>：放宽为「声明范围 × 任务成员关系」
     * （PERSONAL=仅上传者 / TASK=上传者+所属根任务团队 / PUBLIC 预留未启用）。
     * 宽松度提高但<b>边界仍是任务级</b>，不会回退到"任意 key 可读任意附件"——
     * 判据分散是漂移与越权的根源，故必须收口在 Service 的唯一入口。</p>
     *
     * <p><b>通道判定以 {@code _authType} 为准而非 {@code _authId} 是否为空</b>：
     * {@code AuthInterceptor} 对两条通道都会注入 {@code _authId}——平台账号写
     * admin session id（{@code _authType=admin}），Agent 通道写 {@code agent.getId()}
     * （{@code _authType=agent}）。仅 Agent 通道做可见性判定，平台账号与无主体请求放行。</p>
     *
     * <p>按 §10 红线不加 {@code @SaCheckPermission}，以「请求属性 + 领域策略判定」实现。</p>
     */
    private Attachment loadReadable(Long id, String authType, Long agentId) {
        Attachment attachment = attachmentService.getByIdRequired(id);
        if (isAgentChannel(authType, agentId)) {
            attachmentService.assertReadable(attachment, agentId);
        }
        return attachment;
    }

    /** 是否 Agent 通道请求（判定基准为 {@code _authType}，见 {@link #loadReadable}）。 */
    private static boolean isAgentChannel(String authType, Long agentId) {
        return "agent".equals(authType) && agentId != null;
    }
}
