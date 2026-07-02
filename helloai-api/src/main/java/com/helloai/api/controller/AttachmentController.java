package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.R;
import com.helloai.core.entity.Attachment;
import com.helloai.core.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
    public R<List<Attachment>> list(@RequestParam(required = false) Long subTaskId) {
        var wrapper = new LambdaQueryWrapper<Attachment>()
                .eq(subTaskId != null, Attachment::getSubTaskId, subTaskId)
                .orderByDesc(Attachment::getCreateTime);
        return R.ok(attachmentMapper.selectList(wrapper));
    }

    /**
     * 附件详情
     */
    @GetMapping("/{id}")
    public R<Attachment> getById(@PathVariable Long id) {
        Attachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) return R.fail("附件不存在");
        return R.ok(attachment);
    }
}
