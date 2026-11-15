package com.helloai.core.agent.runtime.sandbox;

import java.time.OffsetDateTime;

/**
 * 工作区目录项（`SL-9`）：沙箱文件系统的一行只读元数据。
 *
 * <p><b>只承载机制，不承载策略</b>：本记录只回答「目录里有什么」，不回答「哪一份是交付物」。
 * 后者（最新 {@code .md} 优先、体积兜底）是对话正文兜底场景的业务判据，落在消费侧
 * {@code DeliverableReader}——否则沙箱实现会被一个场景的取文件口味绑住。</p>
 *
 * @param path       容器内绝对路径（服务端返回，原样保留）
 * @param size       字节数
 * @param modifiedAt 修改时间（服务端时钟；未知 ⇒ {@code null}）
 * @param type       服务端给出的类型（如 {@code file} / {@code directory}；未知 ⇒ {@code null}）
 */
public record WorkspaceEntry(String path, long size, OffsetDateTime modifiedAt, String type) {

    /** 是否为目录（服务端类型缺失时按「未知」处理，由调用方决定回退口径）。 */
    public boolean directory() {
        return type != null
                && ("directory".equalsIgnoreCase(type) || "dir".equalsIgnoreCase(type));
    }
}
