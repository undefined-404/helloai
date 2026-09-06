package com.helloai.core.planner.memory.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.constant.LongTermMemoryType;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.entity.LongTermMemory;

import java.util.List;

/**
 * 长期记忆服务（N-009，C5-S1/S2/S3）。
 *
 * <p>摘要式记忆存取 + 受控 recall 检索；不持有原始对话（差距表原则）、零运行期触发
 * （不建第二控制面）。</p>
 */
public interface LongTermMemoryService {

    /**
     * 归档会话摘要（type=SESSION，ref=REQUIREMENT_CONVERSATION）。
     * 幂等：ref_type+ref_id 已存在则跳过（唯一约束兜底）；best-effort 不阻断调用方。
     */
    void archiveSession(RequirementConversation conversation, List<RequirementMessage> messages);

    /**
     * 受控 recall：按关键词匹配 tag/title/content，最多返回 {@code limit} 条（按时间倒序）。
     * 供 Planner prompt 注入（有限条数，防膨胀）。
     */
    List<LongTermMemory> searchForRecall(String keyword, int limit);

    /** 分页查询（type 可选）。 */
    IPage<LongTermMemory> page(long page, long size, LongTermMemoryType type);
}
