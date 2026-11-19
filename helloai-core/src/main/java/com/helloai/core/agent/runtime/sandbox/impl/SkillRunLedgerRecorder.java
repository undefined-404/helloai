package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.core.agent.entity.SubTaskSkillRun;
import com.helloai.core.agent.mapper.SubTaskSkillRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能执行台账的<b>独立事务</b>写入器（`MA-3`）。
 *
 * <p><b>为什么必须 {@code REQUIRES_NEW}</b>：技能执行可能发生在外层事务里
 * （{@code submitResult} 链路就是这样）。台账要如实记录「跑过，但失败了」——
 * 若共用外层事务，业务一回滚，台账跟着回滚，于是「失败」这件事恰好没有被记录下来，
 * 而这正是报表唯一想知道的东西。与 {@code SubmissionActivityRecorder} 同一套理由。</p>
 *
 * <p><b>为什么单独一个 Bean 而不是在本类上加 {@code @Transactional}</b>：
 * 注解只有在<b>经代理调用</b>时才生效。写在 {@code SkillExecutionSupport} 的方法上，
 * 类内自调用会绕过代理、注解静默失效——「看起来有独立事务，其实没有」。
 * 拆成一个 Bean 是最小且不会骗人的做法（CODE_STYLE §14.5）。</p>
 *
 * <p><b>调用方仍然要 try/catch</b>：本类只保证「写失败只回滚自己」，
 * 「写失败不打断技能执行」是调用方的责任（见 {@code SkillExecutionSupport#recordRun}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillRunLedgerRecorder {

    private final SubTaskSkillRunMapper mapper;

    /**
     * 追加一行业绩记录。
     *
     * @param row 已装配好的一行（调用方负责把 {@code exit_code} / {@code duration_ms} 等填真）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(SubTaskSkillRun row) {
        if (row == null) {
            log.warn("技能执行台账行为空，跳过");
            return;
        }
        mapper.insert(row);
    }
}
