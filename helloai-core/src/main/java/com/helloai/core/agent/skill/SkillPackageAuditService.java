package com.helloai.core.agent.skill;

import com.helloai.core.agent.entity.SkillPackageAudit;
import com.helloai.core.agent.mapper.SkillPackageAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能包审计写入（REF-1.6）。
 *
 * <p><b>为什么单独成类而不是安装服务里的私有方法</b>：审计要能在<b>被拒绝</b>的路径上存活——
 * 而拒绝的实现方式是抛 {@code BizException}，会把**同一事务**里已写的审计一起回滚掉。
 * 故本类的写方法一律标 {@link Propagation#REQUIRES_NEW}（挂起外层事务、独立提交），
 * 且必须经 Spring 代理调用——**不能**做成安装服务的私有方法自调用（自调用不走代理，
 * 注解失效；同 {@code AgentServiceImpl#registerWithExtras} 的 javadoc 所述陷阱）。</p>
 *
 * <p><b>为什么显式写 operator</b>：{@code MyBatisPlusMetaObjectHandler#getCurrentUser()} 是
 * 硬编码桩、恒返 {@code "system"}，全平台 {@code create_by} 都不记操作人（差距表 §7.1.3 R5）。
 * 操作人由调用方从 Sa-Token 会话取出后传入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillPackageAuditService {

    /** 动作码（与 V104 的 CHECK 约束一致）。 */
    public static final String ACTION_INSTALL = "INSTALL";
    public static final String ACTION_ACTIVATE = "ACTIVATE";
    public static final String ACTION_UNINSTALL = "UNINSTALL";
    public static final String ACTION_REJECT = "REJECT";

    /** 结果码（与 V104 的 CHECK 约束一致）。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    private final SkillPackageAuditMapper auditMapper;

    /**
     * 操作人（由 Controller 从 Sa-Token 会话取出后传入；Service 不碰 HTTP 协议细节，§8.2）。
     */
    public record Operator(String id, String name) {
        public static Operator of(String id, String name) {
            return new Operator(id == null ? "" : id, name == null ? "" : name);
        }
    }

    /**
     * 记一条审计（独立事务，失败只记日志不抛——审计不得反向阻断业务）。
     *
     * @param action  {@link #ACTION_INSTALL} / {@link #ACTION_ACTIVATE} / {@link #ACTION_UNINSTALL} / {@link #ACTION_REJECT}
     * @param result  {@link #RESULT_SUCCESS} / {@link #RESULT_FAIL}
     * @param reason  失败原因（成功时传 null）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Operator operator, Long packageId, String name, String version,
                       String action, String result, String reason, String checksumSha256) {
        try {
            Operator op = operator == null ? Operator.of(null, null) : operator;
            SkillPackageAudit row = new SkillPackageAudit();
            row.setPackageId(packageId);
            row.setName(name == null ? "" : name);
            row.setVersion(version == null ? "" : version);
            row.setAction(action);
            row.setResult(result);
            row.setReason(truncate(reason, 512));
            row.setChecksumSha256(checksumSha256);
            row.setOperatorId(op.id());
            row.setOperatorName(truncate(op.name(), 128) == null ? "" : truncate(op.name(), 128));
            auditMapper.insert(row);
        } catch (Exception e) {
            log.error("技能包审计写入失败（不影响业务）: action={}, name={}, err={}", action, name, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
