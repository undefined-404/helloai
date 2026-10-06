package com.helloai.api.controller;

import com.helloai.api.interceptor.AuthInterceptor;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.task.service.ArtifactUploadService;
import com.helloai.core.task.service.ArtifactUploadService.ArtifactUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 产物文件内容上传（服务器版 MinIO 直连不可达场景的代理上传入口）。
 *
 * <p>认证：走 AuthInterceptor（Authorization: Bearer &lt;API_KEY&gt;），
 * agentId 从 {@link AuthInterceptor#AUTH_ID_KEY} 注入；免 MCP session，三通道 AI 均可调用。</p>
 *
 * <p>流程：multipart 上传文件内容 → 平台转存主存储（MinIO）并注册附件元数据 →
 * 返回 attachmentId + storageUrl；AI 无需知道 MinIO 地址与凭据。
 * 与 {@code POST /api/mcp/tools/uploadArtifact}（仅注册已有对象）互补。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/artifacts")
@RequiredArgsConstructor
public class ArtifactUploadController {

    private final ArtifactUploadService artifactUploadService;

    /**
     * 上传产物文件内容（multipart/form-data：file + subTaskId + 可选 mimeType）。
     * 文件大小上限由 spring.servlet.multipart.max-file-size 控制（默认 8MB）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ArtifactUploadResult> upload(
            @RequestAttribute(AuthInterceptor.AUTH_ID_KEY) Long agentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("subTaskId") Long subTaskId,
            @RequestParam(value = "mimeType", required = false) String mimeType) {
        // P1-1（2026-10-06）：客户端校验错误一律用带码 BizException → HTTP 400，
        // 不再用单参构造（默认 code=500）把「参数错误」误报成「服务故障」。
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "file 不能为空");
        }
        if (subTaskId == null) {
            throw new BizException(400, "subTaskId 不能为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BizException(400, "fileName 不能为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            // 服务端读取临时文件失败：保留 500 语义，但不回显底层异常消息（可能含服务端路径）
            log.error("读取上传文件失败: subTaskId={}, fileName={}", subTaskId, fileName, e);
            throw new BizException(500, "读取上传文件失败，请重试");
        }
        return R.ok(artifactUploadService.upload(agentId, subTaskId, fileName, mimeType, content));
    }
}
