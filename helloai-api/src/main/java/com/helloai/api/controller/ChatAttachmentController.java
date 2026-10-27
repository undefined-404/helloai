package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.helloai.api.dto.attachment.ChatAttachmentVO;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.planner.service.ChatAttachmentService.PreviewPayload;
import com.helloai.core.planner.service.ChatAttachmentService.UploadFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 需求对话附件 Controller（上传 / 清单 / 删除 / 预览）。
 *
 * <p><b>分层（§10）</b>：只做参数转换与 HTTP 关注点；限额、类型校验、存储、解析投递全在
 * {@link ChatAttachmentService} 内，Controller 不碰 Mapper、不带事务。</p>
 *
 * <p><b>权限</b>：复用 {@code conversation:add}（普通用户/管理员均已有），不新增 RBAC 码。
 * 理由：附件是「新建会话的输入物料」，随会话创建流程一起发生；单独开
 * {@code attachment:chat-*} 会让每个角色都要补授权，收益为零。</p>
 *
 * <p><b>为什么上传支持多文件</b>：方案限额是「单次 5 个」，一次请求多文件才能真正执行这条上限；
 * 前端拖拽多选时也不必连发 5 个请求。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat-attachments")
@RequiredArgsConstructor
public class ChatAttachmentController {

    private final ChatAttachmentService chatAttachmentService;

    /**
     * 上传会话附件（multipart，1~5 个文件）。
     *
     * @param files          文件列表
     * @param conversationId 已有会话 ID（新会话首句前不传）
     * @param clientToken    新会话临时归属（前端 UUID；与 conversationId 至少一个）
     */
    @SaCheckPermission("conversation:add")
    @PostMapping("/upload")
    public R<List<ChatAttachmentVO>> upload(@RequestParam("files") List<MultipartFile> files,
                                            @RequestParam(value = "conversationId", required = false) Long conversationId,
                                            @RequestParam(value = "clientToken", required = false) String clientToken) {
        List<UploadFile> uploads = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            uploads.add(toUploadFile(file));
        }
        List<ChatAttachment> saved = chatAttachmentService.uploadBatch(conversationId, clientToken,
                uploads, currentUserId());
        log.info("会话附件上传完成: conversationId={}, clientToken={}, count={}",
                conversationId, clientToken, saved.size());
        return R.ok(ChatAttachmentVO.fromList(saved));
    }

    /**
     * 当前登录用户 id（`ATT-3`）：用于把「谁上传的」落到附件与 kb。
     *
     * <p>上传端点已有 {@code @SaCheckPermission}，正常路径必有登录态；这里仍兜住异常返回 null，
     * 避免认证中间件差异导致上传直接 500——空值只影响检索隔离精度，不该阻断上传。</p>
     */
    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            log.debug("取当前登录用户失败（附件按无归属上传）: {}", e.getMessage());
            return null;
        }
    }

    /** 会话附件清单（新会话首句前用 clientToken 查临时批次）。 */
    @SaCheckPermission("conversation:add")
    @GetMapping
    public R<List<ChatAttachmentVO>> list(
            @RequestParam(value = "conversationId", required = false) Long conversationId,
            @RequestParam(value = "clientToken", required = false) String clientToken) {
        return R.ok(ChatAttachmentVO.fromList(chatAttachmentService.list(conversationId, clientToken)));
    }

    /** 删除（软删，幂等：已删再删仍返回成功）。 */
    @SaCheckPermission("conversation:add")
    @PostMapping("/deleteById/{id}")
    public R<Void> deleteById(@PathVariable("id") Long id) {
        chatAttachmentService.delete(id);
        return R.ok();
    }

    /** 浏览器内联预览（文本 / 图片 / PDF 小文件）；过大或不支持的类型返回 413。 */
    @SaCheckPermission("conversation:add")
    @GetMapping("/previewById/{id}")
    public ResponseEntity<byte[]> previewById(@PathVariable("id") Long id) {
        PreviewPayload payload = chatAttachmentService.preview(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.parseMediaType(payload.contentType()));
        return new ResponseEntity<>(payload.content(), headers, HttpStatus.OK);
    }

    /**
     * 下载附件（{@code attachment} 语义，2026-10-27）。
     *
     * <p><b>为什么与 preview 分开而不是加个参数</b>：两者对浏览器的指令不同——
     * {@code inline} 让 HTML 直接在标签页里渲染（技能产物「看图」的场景），
     * {@code attachment} 才会落盘（「拿到文件」的场景）。产物卡片上的「下载」按钮
     * 走这条，避免用户点了之后只能在浏览器里另存为。</p>
     *
     * <p><b>不过内联预览闸门</b>：下载走 {@link ChatAttachmentService#download}，
     * 不套 preview 的 5MB / MIME 白名单——docx、zip、大产物等「不可内联预览」的类型
     * 同样必须能下载（对齐子任务级 {@code AttachmentController#downloadById} 口径）。
     * Content-Type 统一 {@code application/octet-stream}，中文文件名经 RFC 5987 落盘。</p>
     */
    @SaCheckPermission("conversation:add")
    @GetMapping("/downloadById/{id}")
    public ResponseEntity<byte[]> downloadById(@PathVariable("id") Long id) {
        PreviewPayload payload = chatAttachmentService.download(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return new ResponseEntity<>(payload.content(), headers, HttpStatus.OK);
    }

    /** MultipartFile → 领域上传对象；读失败属请求层错误，转 400。 */
    private static UploadFile toUploadFile(MultipartFile file) {
        try {
            return new UploadFile(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new BizException(400,
                    "读取上传文件失败: " + file.getOriginalFilename());
        }
    }
}
