package com.helloai.api.controller;

import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 附件列表。
     */
    @GetMapping
    public R<List<Attachment>> list(@RequestParam(value = "subTaskId", required = false) Long subTaskId) {
        return R.ok(attachmentService.list(subTaskId));
    }

    /**
     * 附件详情；不存在抛 BizException(404) 由全局异常处理返回 R.fail。
     */
    @GetMapping("/getById/{id}")
    public R<Attachment> getById(@PathVariable("id") Long id) {
        return R.ok(attachmentService.getByIdRequired(id));
    }

    /**
     * 附件下载：方案2 本地物化产物（local://）由平台读取内容流式返回
     * （Content-Disposition 用 RFC 5987 filename* 承载中文文件名）；
     * 外部对象存储地址仍保持 302 重定向。
     */
    @GetMapping("/downloadById/{id}")
    public ResponseEntity<byte[]> downloadById(@PathVariable("id") Long id) {
        Attachment attachment = attachmentService.getByIdRequired(id);
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
    public ResponseEntity<byte[]> previewById(@PathVariable("id") Long id) {
        Attachment attachment = attachmentService.getByIdRequired(id);
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
}