package com.helloai.core.planner.skill;

import com.helloai.core.planner.mapper.ChatSkillRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 技能执行的启动恢复（SKX-13 补齐）：进程重启后，把上次遗留的
 * {@code PENDING} / {@code RUNNING} 记录收尾为 {@code FAILED}。
 *
 * <p><b>不做会怎样</b>：后台线程随进程消失，但没有谁会把这两态改成终态。
 * 它们占着「同会话互斥」的额度（{@code countActiveByConversationId} 与
 * {@code uk_skill_run_active} 都认活跃态）⇒ 该会话之后提交技能一律被
 * 「已有技能在执行」挡回，用户侧表现为<b>永久卡死且无任何报错</b>。
 * 一次执行可达数百秒，重启撞上这个窗口不是小概率事件。</p>
 *
 * <p><b>范围口径</b>：不分实例归属，一律判死。前提是单实例部署
 * （`docker-compose.server.yml` 只有一个 `app` 服务）。
 * 若将来横向扩容，必须改为「按实例 id 归属」判定，否则会误杀其他实例正在跑的任务。</p>
 *
 * <p><b>失败不阻断启动</b>：恢复是尽力而为，失败只打日志 ——
 * 不能因为一张运行记录表让整个应用起不来。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillRunStartupRecovery implements ApplicationRunner {

    /** 中断原因（落库供排查；该记录已是终态，正常轮询不会再展示它）。 */
    static final String INTERRUPTED_REASON = "服务重启导致执行中断（未产出结果）";

    private final ChatSkillRunMapper chatSkillRunMapper;

    @Override
    public void run(ApplicationArguments args) {
        int recovered;
        try {
            recovered = chatSkillRunMapper.failInterruptedRuns(INTERRUPTED_REASON);
        } catch (Exception e) {
            log.warn("技能执行启动恢复跳过（表可能尚未创建或数据库不可用）: {}", e.getMessage());
            return;
        }
        if (recovered > 0) {
            log.warn("技能执行启动恢复：{} 条未完成记录已置 FAILED（上次进程退出时仍处于 PENDING/RUNNING）",
                    recovered);
        }
    }
}
