package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.entity.Attachment;
import com.helloai.core.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentMapper attachmentMapper;

    /**
     * 附件列表
     */
    @GetMapping
    public R<List<Attachment>> list(@RequestParam(value = "subTaskId", required = false) Long subTaskId) {
        var wrapper = new LambdaQueryWrapper<Attachment>()
                .eq(subTaskId != null, Attachment::getSubTaskId, subTaskId)
                .orderByDesc(Attachment::getCreateTime);
        return R.ok(attachmentMapper.selectList(wrapper));
    }

    /**
     * 附件详情
     */
    @GetMapping("/{id}")
    public R<Attachment> getById(@PathVariable("id") Long id) {
        Attachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) return R.fail("附件不存在");
        return R.ok(attachment);
    }

    /**
     * 附件下载 — 302 重定向到 MinIO 预签名 URL 或存储地址
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Void> download(@PathVariable("id") Long id) {
        Attachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) {
            throw new BizException(404, "附件不存在");
        }
        // 优先使用预签名 URL，否则回退到存储 URL
        String downloadUrl = attachment.getStorageUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new BizException(500, "附件存储地址不可用");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }
}
