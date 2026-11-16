package com.helloai.core.system.notification.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.shared.event.NotificationCreatedEvent;
import com.helloai.core.system.entity.UserNotification;
import com.helloai.core.system.mapper.UserNotificationMapper;
import com.helloai.core.system.notification.NotificationEvent;
import com.helloai.core.system.notification.NotificationMapping;
import com.helloai.core.system.notification.NotificationService;
import com.helloai.core.system.notification.NotificationType;
import com.helloai.core.system.notification.Severity;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 人侧站内消息服务实现（MSG-1，设计 §3.2/§3.3/§3.10）。
 *
 * <p>单一出口 {@link #publish}：开关 OFF 早退 → 解析类型/形态 → 拼 dedupe_key →
 * {@code ON CONFLICT DO NOTHING} 幂等插入 → 落库成功发 {@link NotificationCreatedEvent}
 * （AFTER_COMMIT 驱动 SSE）。BUCKET 形态撞键时原子累加 payload.count。</p>
 *
 * <p>读取按归属人过滤；开关 OFF 一律返回空（停发 + 隐藏）。无消息状态机，有效性判深链回源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl extends ServiceImpl<UserNotificationMapper, UserNotification>
        implements NotificationService {

    private final SysConfigService sysConfigService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(NotificationEvent event) {
        // 停发：开关 OFF 首行早退，不写库、不发事件（设计 §3.10）
        if (!sysConfigService.isNotificationEnabled()) {
            return;
        }
        Resolved resolved = resolve(event);
        if (resolved == null) {
            // 未登记事件码（白名单默认不发）或 SILENT 显式不发
            return;
        }

        String dedupeKey = buildDedupeKey(event, resolved);
        Map<String, Object> payload = event.getPayload() != null
                ? new HashMap<>(event.getPayload()) : new HashMap<>();
        if (resolved.form() == NotificationMapping.Form.BUCKET) {
            payload.put("count", 1);
        }
        String summary = renderSummary(resolved.type().getSummaryTemplate(), payload);

        long id = IdWorker.getId();
        // 深链归属取事件源对象类型（钩子按 subTaskId 有无给出 TASK/SUB_TASK）；注册表 refType
        // 仅作例外通道未给 sourceType 时的兜底——task 级事件映射进 SUB_TASK 型时，固定 refType
        // 会把 taskId 当子任务 id 造成死链（审查修复 A1）。
        String refType = event.getSourceType() != null ? event.getSourceType() : resolved.type().getRefType();
        int rows;
        try {
            rows = baseMapper.insertIgnoreDuplicate(
                    id, event.getReceiverId(), resolved.type().name(),
                    resolved.severity().name(), resolved.type().getTitle(), summary, dedupeKey,
                    refType, event.getSourceId(), payload);
        } catch (RuntimeException e) {
            // ON CONFLICT 已消化预期的幂等撞键，此处只剩真实写库异常。
            // 设计 §3.6：人侧消息是增强，写失败「不改变业务事务结果」——吞掉异常，避免把共享事务
            // 标记 rollback-only（rethrow 会让外层 recordEvent 一并回滚，违背设计）。
            // URGENT 必须 error 留痕并计入对账（MSG-7），NORMAL 仅 warn；两者都不阻断业务。
            if (resolved.severity() == Severity.URGENT) {
                log.error("人侧消息写库失败(URGENT)，降级不阻断业务: dedupeKey={}", dedupeKey, e);
            } else {
                log.warn("人侧消息写库失败，降级不阻断业务: dedupeKey={}", dedupeKey, e);
            }
            return;
        }

        if (rows == 0) {
            if (resolved.form() == NotificationMapping.Form.BUCKET) {
                try {
                    baseMapper.incrementBucketCount(dedupeKey, event.getReceiverId());
                } catch (RuntimeException e) {
                    // 与 insert 同一降级口径：异常不得穿过本方法的事务代理边界，否则共享事务
                    // 被标 rollback-only，外层业务 commit 失败（审查修复 B2）。
                    if (resolved.severity() == Severity.URGENT) {
                        log.error("人侧消息折叠累加失败(URGENT)，降级不阻断业务: dedupeKey={}", dedupeKey, e);
                    } else {
                        log.warn("人侧消息折叠累加失败，降级不阻断业务: dedupeKey={}", dedupeKey, e);
                    }
                }
            } else {
                log.debug("人侧消息幂等跳过: dedupeKey={}", dedupeKey);
            }
            return;
        }

        // 首次落库成功 → 发事件（AFTER_COMMIT 推 SSE）；幂等命中不发，避免重复响铃
        eventPublisher.publishEvent(new NotificationCreatedEvent(id, event.getReceiverId()));
    }

    @Override
    public IPage<UserNotification> pageByReceiver(Long receiverId, String type, int page, int pageSize) {
        Page<UserNotification> p = new Page<>(Math.max(1, page), Math.max(1, Math.min(pageSize, 200)));
        if (!sysConfigService.isNotificationEnabled()) {
            return p;
        }
        return lambdaQuery()
                .eq(UserNotification::getReceiverId, receiverId)
                .eq(type != null && !type.isBlank(), UserNotification::getType, type)
                .eq(UserNotification::getIsArchived, 0)
                .and(w -> w.isNull(UserNotification::getExpiresAt)
                        .or().gt(UserNotification::getExpiresAt, OffsetDateTime.now()))
                .orderByAsc(UserNotification::getIsRead)
                .orderByDesc(UserNotification::getCreateTime)
                .page(p);
    }

    @Override
    public long unreadCount(Long receiverId) {
        if (!sysConfigService.isNotificationEnabled()) {
            return 0L;
        }
        return lambdaQuery()
                .eq(UserNotification::getReceiverId, receiverId)
                .eq(UserNotification::getIsRead, 0)
                .eq(UserNotification::getIsArchived, 0)
                .and(w -> w.isNull(UserNotification::getExpiresAt)
                        .or().gt(UserNotification::getExpiresAt, OffsetDateTime.now()))
                .count();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long receiverId, Long notificationId) {
        // 归属未识别（未登录 / 外部 Agent 无 Sa-Token 上下文）→ fail-close 不动任何数据
        if (receiverId == null) {
            return;
        }
        UserNotification n = getById(notificationId);
        if (n == null || !receiverId.equals(n.getReceiverId())) {
            return;
        }
        lambdaUpdate()
                .eq(UserNotification::getId, notificationId)
                .eq(UserNotification::getReceiverId, receiverId)
                .set(UserNotification::getIsRead, 1)
                .set(UserNotification::getReadAt, OffsetDateTime.now())
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(Long receiverId) {
        if (!sysConfigService.isNotificationEnabled()) {
            return;
        }
        lambdaUpdate()
                .eq(UserNotification::getReceiverId, receiverId)
                .eq(UserNotification::getIsRead, 0)
                .set(UserNotification::getIsRead, 1)
                .set(UserNotification::getReadAt, OffsetDateTime.now())
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long receiverId, Long notificationId) {
        if (receiverId == null) {
            return;
        }
        UserNotification n = getById(notificationId);
        if (n == null || !receiverId.equals(n.getReceiverId())) {
            return;
        }
        lambdaUpdate()
                .eq(UserNotification::getId, notificationId)
                .eq(UserNotification::getReceiverId, receiverId)
                .set(UserNotification::getIsArchived, 1)
                .update();
    }

    // ══════════════════════════════════════════════════════════════
    //  内部：解析 / 键拼装 / 渲染（纯函数便于单测锁定口径）
    // ══════════════════════════════════════════════════════════════

    /** 解析后的类型/严重度/形态。 */
    private record Resolved(NotificationType type, Severity severity, NotificationMapping.Form form, int bucketHours) {
    }

    /**
     * 例外通道（显式 type）→ ONCE；主通道（事件码）→ 查映射，未登记/SILENT 返回 null。
     * 严重度优先取事件覆盖，否则取映射/类型默认。
     */
    private Resolved resolve(NotificationEvent event) {
        if (event.getType() != null) {
            NotificationType type = event.getType();
            Severity severity = event.getSeverity() != null ? event.getSeverity() : type.getDefaultSeverity();
            return new Resolved(type, severity, NotificationMapping.Form.ONCE, 0);
        }
        NotificationMapping.Entry entry = NotificationMapping.resolve(event.getTimelineEventCode());
        if (entry == null || entry.form() == NotificationMapping.Form.SILENT) {
            return null;
        }
        Severity severity = event.getSeverity() != null ? event.getSeverity() : entry.severity();
        return new Resolved(entry.type(), severity, entry.form(), entry.bucketHours());
    }

    /**
     * dedupe_key（设计 §3.3）：
     * 主通道 {type}:{事件码}:{实例}（BUCKET 以滚动窗口替换实例段）；
     * 例外通道 {type}:{sourceType}:{sourceId}:{instanceKey}。
     */
    private String buildDedupeKey(NotificationEvent event, Resolved resolved) {
        String typeName = resolved.type().name();
        if (event.getType() == null && event.getTimelineEventCode() != null) {
            String instance = resolved.form() == NotificationMapping.Form.BUCKET
                    ? bucketWindow(System.currentTimeMillis(), resolved.bucketHours())
                    : event.getInstanceKey();
            return typeName + ":" + event.getTimelineEventCode() + ":" + instance;
        }
        return typeName + ":" + event.getSourceType() + ":" + event.getSourceId() + ":" + event.getInstanceKey();
    }

    /**
     * 滚动窗口键：把时间戳按窗口小时数取整，同窗口内折叠为一条。
     * 纯函数（不依赖时钟），便于单测锁定「同窗口同键、跨窗口异键」。
     */
    static String bucketWindow(long epochMillis, int bucketHours) {
        int hours = Math.max(1, bucketHours);
        return String.valueOf(epochMillis / (hours * 3_600_000L));
    }

    /**
     * 摘要渲染：把模板里的 {key} 占位替换为 payload 对应值（文案集中于注册表，业务只供数据）。
     * 纯函数；无占位或无 payload 时原样返回。
     */
    static String renderSummary(String template, Map<String, Object> payload) {
        if (template == null || payload == null || payload.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return result;
    }
}