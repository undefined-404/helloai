package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 附件 Controller — 列表 / 详情 / 下载重定向。
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
    @GetMapping("/{id}")
    public R<Attachment> getById(@PathVariable("id") Long id) {
        return R.ok(attachmentService.getByIdRequired(id));
    }

    /**
     * 附件下载 — 302 重定向到对象存储 URL。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Void> download(@PathVariable("id") Long id) {
        String downloadUrl = attachmentService.getStorageUrlRequired(id);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }
}