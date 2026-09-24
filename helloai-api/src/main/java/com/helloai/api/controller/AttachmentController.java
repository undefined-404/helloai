package com.helloai.api.controller;

import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskService;
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
 * 附件 Controller — 列表 / 详情 / 下载。
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
    private final SubTaskService subTaskService;

    /**
     * 附件列表。
     */
    @GetMapping
    public R<List<Attachment>> list(
            @RequestParam(value = "subTaskId", required = false) Long subTaskId,
            @RequestAttribute(value = "_authType", required = false) String authType,
            @RequestAttribute(value = "_authId", required = false) Long agentId) {
        if (subTaskId != null) {
            assertSubTaskReadable(subTaskId, authType, agentId);
        }
        return R.ok(attachmentService.list(subTaskId));
    }

    /**
     * 附件详情；不存在抛 BizException(404) 由全局异常处理返回 R.fail。
     */
    @GetMapping("/getById/{id}")
    public R<Attachment> getById(@PathVariable("id") Long id,
                                 @RequestAttribute(value = "_authType", required = false) String authType,
                                 @RequestAttribute(value = "_authId", required = false) Long agentId) {
        Attachment attachment = attachmentService.getByIdRequired(id);
        assertSubTaskReadable(attachment.getSubTaskId(), authType, agentId);
        return R.ok(attachment);
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
        Attachment attachment = attachmentService.getByIdRequired(id);
        assertSubTaskReadable(attachment.getSubTaskId(), authType, agentId);
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
        Attachment attachment = attachmentService.getByIdRequired(id);
        assertSubTaskReadable(attachment.getSubTaskId(), authType, agentId);
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
     * Agent 通道附件归属校验。
     *
     * <p>安全修复（G-014 T04b）：此前四端点无任何归属校验，任意有效 Agent API Key 可凭
     * {@code attachmentId} 读取任意子任务的附件正文。</p>
     *
     * <p><b>通道判定以 {@code _authType} 为准而非 {@code _authId} 是否为空</b>：
     * {@code AuthInterceptor} 对两条通道都会注入 {@code _authId}——平台账号写
     * admin session id（{@code _authType=admin}），Agent 通道写 {@code agent.getId()}
     * （{@code _authType=agent}）。仅 Agent 通道做归属比对，平台账号与无主体请求放行。</p>
     *
     * <p>按 §10 红线不加 {@code @SaCheckPermission}，以「请求属性 + 服务层归属比对」实现。</p>
     */
    private void assertSubTaskReadable(Long subTaskId, String authType, Long agentId) {
        if (!"agent".equals(authType) || agentId == null) {
            return; // 平台账号 / 无主体：由管理侧鉴权覆盖
        }
        if (subTaskId == null) {
            throw new BizException(403, "无权访问该附件（无法确定归属子任务）");
        }
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null || subTask.getAssignedAgentId() == null
                || !agentId.equals(subTask.getAssignedAgentId())) {
            throw new BizException(403, "无权访问该附件（非所属子任务的执行者）");
        }
    }
}
