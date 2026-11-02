package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 外部供应商余额快照（G-026，表 {@code llm_balance_snapshot}）。
 *
 * <p>回答「某家某时刻还有多少钱 / 剩余多少额度、失败没有、失败原因是什么」——否则余额
 * 只存在于外部接口返回里，跑到哪一步 / 失败原因无从查询。</p>
 *
 * <p><b>落点为什么在 system 域</b>：余额快照是**系统级记账**（与备份台账同处，
 * 见 {@link PlatformBackup}），不是业务流程；符合 {@code system} 域「平台级能力落点，
 * 禁止将具体业务流程塞入」的定位。`quota` 归其下不破坏依赖方向（system 处链尾）。</p>
 *
 * <p><b>金额型 / 额度型分栏</b>：deepseek / moonshot / 博查 写 {@code amount}；
 * MiniMax 写 {@code quota_percent}（百分比，额度型）。两字段可空，取数语义在应用层
 * `BalanceSnapshot` 区分，不混用（把「剩余 68%」塞进 {@code amount} 会导致无法求和、
 * 无法判断「快没了」的阈值）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "llm_balance_snapshot", autoResultMap = true)
public class LlmBalanceSnapshot extends BaseEntity {

    /** 供应商 key（与 {@code credential_vault.provider} 对齐，小写）。 */
    private String providerKey;

    /** 币种（CNY / USD，额度型为 null）。 */
    private String currency;

    /** 金额（deepseek / moonshot / 博查），额度型为 null。 */
    private BigDecimal amount;

    /** 剩余百分比（MiniMax 额度型），金额型为 null。 */
    private BigDecimal quotaPercent;

    /** 原始响应（JSONB），出问题时不用重新抓。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> rawPayload;

    /** 抓取时刻。 */
    private OffsetDateTime fetchedAt;

    /** 失败原因（成功时为 null）。 */
    private String error;
}