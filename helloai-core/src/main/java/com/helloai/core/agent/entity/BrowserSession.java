package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.BrowserSessionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Browser 会话登记实体（N-003，C3-S1）。
 *
 * <p>登记/展示语义：状态由执行结果回写，不持有浏览器实例；
 * 不设引用约束到 task/sub_task（反锁禁令，C1 同构）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("browser_session")
public class BrowserSession extends BaseEntity {

    /** WEB_BROWSER Agent。 */
    private Long agentId;

    /** 关联任务（可空：跨任务会话）。 */
    private Long taskId;

    /** BEGIN / ACTIVE / CLOSED / FAILED。 */
    private BrowserSessionStatus status;

    private String currentUrl;

    /** 最近截图 artifact 引用（展示/审计）。 */
    private String lastScreenshotRef;

    private OffsetDateTime beginTime;

    private OffsetDateTime closeTime;
}
