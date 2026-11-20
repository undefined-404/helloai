package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SensitiveScanPoint;
import com.helloai.core.agent.observability.SensitiveScanRecorder;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.service.UserMemoryService;
import com.helloai.core.planner.mapper.RequirementMessageMapper;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.shared.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 需求澄清会话消息薄 CRUD 服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementMessageServiceImpl
        extends ServiceImpl<RequirementMessageMapper, RequirementMessage>
        implements RequirementMessageService {

    private final UserMemoryService userMemoryService;
    private final SensitiveScanRecorder sensitiveScanRecorder;

    /**
     * 向澄清会话追加一条消息（seq = 当前最大序号 + 1）。
     *
     * <p>照 ConversationService.addMessage 的 seq 生成范式；澄清对话是主链路本身
     * （非增量副本），失败直接抛给调用方，不需要 REQUIRES_NEW 独立事务。</p>
     */
    @Override
    public RequirementMessage addMessage(Long conversationId, String role, String content) {
        return addMessage(conversationId, role, content, null);
    }

    /**
     * 追加带结构化附加数据的消息。
     *
     * @param payload JSON 文本（assistant=结构化问题，user=选择快照）；纯文本消息传 null
     */
    @Override
    public RequirementMessage addMessage(Long conversationId, String role, String content, String payload) {
        RequirementMessage lastMsg = lambdaQuery()
                .eq(RequirementMessage::getConversationId, conversationId)
                .orderByDesc(RequirementMessage::getSeq)
                .last("LIMIT 1")
                .one();
        int nextSeq = (lastMsg != null && lastMsg.getSeq() != null) ? lastMsg.getSeq() + 1 : 1;

        RequirementMessage msg = new RequirementMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSeq(nextSeq);
        msg.setPayload(payload);
        // PA-1：消息 owner 与所属会话 owner 一致（宽松并入：取不到时为 NULL 不阻断）
        msg.setOwnerId(CurrentUser.userId());
        save(msg);

        // PA-2c：仅对 user 消息做「每轮异步画像抽取 + USER 记忆」。
        // userId 在同步线程取好传参（@Async 线程无 UserContext/Sa-Token）；
        // 异步触发不落主链路事务，best-effort 失败仅记日志。
        if ("user".equals(role)) {
            // MA-8 挂点①：用户输入进对话前扫描（log-only，不阻断；失败不影响落库）。
            // 收口在此而非各 Controller：澄清 / 助理 / 流式 / 编辑重跑四条路径都经本方法，
            // 分别在 Controller 挂会漏掉「编辑消息重跑」这类不经 Controller 的入口。
            sensitiveScanRecorder.scanAndRecord(
                    SensitiveScanPoint.INPUT, content, "message",
                    null, null, AgentRole.PLANNER, null);
            try {
                userMemoryService.triggerFromMessage(CurrentUser.userId(), content);
            } catch (Exception e) {
                log.warn("用户画像异步抽取触发失败（不阻断主链路）: conversationId={}, err={}",
                        conversationId, e.getMessage());
            }
        }
        log.debug("澄清消息追加: conversationId={}, seq={}, role={}", conversationId, nextSeq, role);
        return msg;
    }

    /** 获取会话完整消息列表（按序号升序）。 */
    @Override
    public List<RequirementMessage> listByConversation(Long conversationId) {
        return lambdaQuery()
                .eq(RequirementMessage::getConversationId, conversationId)
                .orderByAsc(RequirementMessage::getSeq)
                .list();
    }

    @Override
    public void removeByConversation(Long conversationId) {
        lambdaUpdate()
                .eq(RequirementMessage::getConversationId, conversationId)
                .remove();
    }
}
