package com.helloai.core.system.service;

import lombok.Data;

/**
 * 产物文件内容上传（服务器版 MinIO 直连不可达场景的代理上传入口）。
 *
 * <p>背景：外部 AI 按旧约定需自行把文件 PUT 到 MinIO 再调 uploadArtifact 注册，
 * 但服务器版部署中 MinIO 仅绑定 127.0.0.1（docker-compose.server.yml），
 * 公网不可达，本机 AI 无法直传。本接口收口"文件内容 → 平台 → 主存储"，
 * 外部 AI 带 {@code Authorization: Bearer <apiKey>} 上传即可，无需知道 MinIO 地址与凭据。</p>
 *
 * <p>本接口为"上传 + 注册一步到位"（store + register 同一次调用），
 * 与 {@code uploadArtifact} 工具的"仅注册已有对象"语义互补。</p>
 */
public interface ArtifactUploadService {

    /**
     * 上传产物文件内容到主存储并注册附件元数据。
     *
     * @param agentId   当前认证 Agent（AuthInterceptor 注入）
     * @param subTaskId 归属子任务（必须存在且 assigned_agent_id = agentId）
     * @param fileName  原始文件名（会做安全清洗）
     * @param mimeType  MIME 类型，可空（空时按文件名探测）
     * @param content   文件内容字节
     * @return 上传结果（attachmentId / storageUrl / fileSize）
     */
    ArtifactUploadResult upload(Long agentId, Long subTaskId, String fileName, String mimeType, byte[] content);

    @Data
    class ArtifactUploadResult {
        private Long attachmentId;
        private String storageUrl;
        private long fileSize;
    }
}
