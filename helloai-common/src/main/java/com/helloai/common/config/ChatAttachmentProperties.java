package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 需求对话附件的限额与解析配置（方案 §3.3 判定阈值，前缀 {@code helloai.chat-attachment}）。
 *
 * <p>默认值即方案拍板口径：单文件 20MB / 单次 5 个 / 单会话 20 个且累计 100MB。
 * 配置只做「收紧或放宽」，不改变语义——所有判据在
 * {@code ChatAttachmentServiceImpl} 里统一执行，本类不承载业务逻辑。</p>
 *
 * <p><b>为什么单文件上限与 multipart 上限要分开</b>：multipart 是容器层（Tomcat）限制，
 * 超了直接 500/无响应；本类是业务层限制，超了给可读的 400 提示。故 multipart 要略高
 * （见 {@code application.yml} 的 {@code spring.servlet.multipart.max-file-size}）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.chat-attachment")
public class ChatAttachmentProperties {

    /** 单文件上限（字节），默认 20MB。 */
    private long maxFileSizeBytes = 20L * 1024 * 1024;

    /** 单次上传文件数上限，默认 5。 */
    private int maxFilesPerUpload = 5;

    /** 单会话累计文件数上限，默认 20。 */
    private int maxFilesPerConversation = 20;

    /** 单会话累计字节上限，默认 100MB。 */
    private long maxTotalBytesPerConversation = 100L * 1024 * 1024;

    /** 解析线程池大小（Tika 同进程解析，CPU 与内存都吃，默认 2 够用且不抢主链路）。 */
    private int parsePoolSize = 2;

    /** 解析队列容量：超出后由 CallerRuns 兜底（宁可慢一点，也不静默丢解析任务）。 */
    private int parseQueueCapacity = 100;
}
