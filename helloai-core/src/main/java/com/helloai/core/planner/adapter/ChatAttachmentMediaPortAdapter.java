package com.helloai.core.planner.adapter;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.port.ChatAttachmentMediaPort;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link ChatAttachmentMediaPort} 适配实现（`VU-9`）：把「会话附件字节读取」收到 planner 域，
 * 不向 agent 域泄漏 {@code ChatAttachment} 实体。
 *
 * <p><b>为什么实现落在 planner 域</b>：{@code chat_attachment} 归 planner 域，端口由消费方
 * {@code agent} 定义（§7.2 情形①），实现侧 {@code planner → agent} 属顺向合法。若反过来让
 * agent 域直接读 planner 域的表，就是 §6 禁止的反向依赖，会被 {@code check-arch-freeze.sh} 拦截。</p>
 *
 * <p><b>为什么复用 {@code ChatAttachmentService#preview} 而不是自己读 storage</b>：字节怎么取
 * （对象存储 key 解析、软删判定、MIME 嗅探）是会话附件的领域知识，平台里已有唯一实现。
 * 但 {@code preview} 带浏览器内联预览的白名单与 5MB 闸门——那是「给 &lt;img&gt; 标签用」的口径，
 * 与「送给模型」不是同一判据，故这里不复用它，而走
 * {@link ChatAttachmentService#download} 的裸字节语义（不受预览白名单限制），
 * 大小与类型的判定统一交给装载器（一处判据，判据 4）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentMediaPortAdapter implements ChatAttachmentMediaPort {

    private final ChatAttachmentService chatAttachmentService;

    @Override
    public byte[] loadChatAttachmentContent(Long attachmentId) {
        if (attachmentId == null) {
            throw new BizException("会话附件 ID 不能为空");
        }
        // download 语义 = 取原始字节（不受预览白名单 / 5MB 内联闸门约束）；
        // 不存在或已删除时提供方 getByIdRequired 抛 BizException，交由调用方逐条降级
        ChatAttachmentService.PreviewPayload payload = chatAttachmentService.download(attachmentId);
        byte[] content = payload == null ? null : payload.content();
        if (content == null || content.length == 0) {
            throw new BizException("会话附件内容为空: id=" + attachmentId);
        }
        return content;
    }
}