package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.SkillExecutionProperties;
import com.helloai.common.constant.SkillExecutionPhase;
import com.helloai.common.constant.SkillExecutionStatus;
import com.helloai.core.planner.entity.ChatSkillRun;
import com.helloai.core.planner.mapper.ChatSkillRunMapper;
import com.helloai.core.planner.service.ChatSkillRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话内技能执行服务实现（SKX-13）。
 *
 * <p><b>职责边界</b>：只做「提交（落 PENDING）+ 查 + 取消」，
 * 执行本身在 {@code SkillExecutionWorker}，状态推进也在那里 ——
 * 这里是<b>提交面</b>，不是执行面。</p>
 *
 * <p><b>为什么显式注入 Mapper 而不继承 {@code ServiceImpl}</b>：{@code ServiceImpl} 的
 * {@code baseMapper} 是父类受保护字段、由框架反射注入，单测里是 null；
 * 而本类的四个动作（插入 / 按 id 查 / 互斥计数 / 条件取消）都需要可测的依赖，
 * 显式注入 Mapper 更直白，也让单测不必反射父类字段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSkillRunServiceImpl implements ChatSkillRunService {

    private final ChatSkillRunMapper chatSkillRunMapper;

    private final SkillExecutionProperties properties;

    @Override
    public ChatSkillRun submit(Long conversationId, Long userId, String skillName, String triggerSource) {
        if (conversationId == null) {
            throw new BizException("缺少会话上下文，无法执行技能");
        }
        // 互斥预检查（PENDING 也算占用）：只为给出可读报错，最终保证在数据库层
        // （uk_skill_run_active 部分唯一索引）。只查 RUNNING 的话，连发两条会各自落一条 PENDING。
        if (chatSkillRunMapper.countActiveByConversationId(conversationId) > 0) {
            throw new BizException("本会话已有技能在执行，请等它结束再试");
        }
        ChatSkillRun run = new ChatSkillRun();
        run.setConversationId(conversationId);
        run.setUserId(userId);
        run.setSkillName(skillName);
        run.setTriggerSource(triggerSource == null ? "CHAT" : triggerSource);
        run.setStatus(SkillExecutionStatus.PENDING.name());
        run.setPhase(SkillExecutionPhase.SPEC.name());
        run.setPhaseDetail(SkillExecutionPhase.SPEC.label());
        run.setTimeoutSeconds(properties.getTimeoutSeconds());
        chatSkillRunMapper.insert(run);
        log.info("技能执行已提交: runId={}, conversationId={}, skill={}, trigger={}, timeoutSeconds={}",
                run.getId(), conversationId, skillName, run.getTriggerSource(), run.getTimeoutSeconds());
        return run;
    }

    @Override
    public ChatSkillRun get(Long runId) {
        if (runId == null) {
            return null;
        }
        return chatSkillRunMapper.selectById(runId);
    }

    @Override
    public boolean cancel(Long runId) {
        if (runId == null) {
            return false;
        }
        boolean cancelled = chatSkillRunMapper.markCancelled(runId) > 0;
        if (cancelled) {
            log.info("技能执行已取消: runId={}", runId);
        } else {
            log.info("技能执行取消未生效（已终态或不存在）: runId={}", runId);
        }
        return cancelled;
    }

    @Override
    public ChatSkillRun active(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return chatSkillRunMapper.selectActiveByConversationId(conversationId);
    }
}
