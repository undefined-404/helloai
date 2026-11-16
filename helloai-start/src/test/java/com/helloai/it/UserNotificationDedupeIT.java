package com.helloai.it;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.helloai.core.system.entity.UserNotification;
import com.helloai.core.system.mapper.UserNotificationMapper;
import com.helloai.core.system.notification.NotificationEvent;
import com.helloai.core.system.notification.NotificationService;
import com.helloai.core.system.notification.NotificationType;
import com.helloai.core.system.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSG-2：人侧消息幂等最终防线的真实 DB 证据（G-035，设计 §3.3/§3.4/§3.10）。
 *
 * <p><b>为什么必须真实 DB</b>：COALESCE 唯一索引的 NULL 广播去重、并发同键「恰好一行」
 * 都写在 SQL/索引里，mock 无法证明。本类在 Testcontainers 空 PG 上跑完整迁移链后，
 * 经真实 {@link NotificationService#publish} 出口验证：</p>
 * <ul>
 *   <li>唯一索引确含 {@code COALESCE(receiver_id, 0)}（普通唯一索引对 NULL 不去重）；</li>
 *   <li>广播（receiver_id NULL）同 dedupe_key 两次 publish → 一行；</li>
 *   <li>双线程并发同事件 → 一行（跨副本幂等的 DB 层证据）；</li>
 *   <li>开关 OFF → publish 不写库。</li>
 * </ul>
 *
 * <p><b>ID 段</b>：9801（避开 9101~9701 已占用段）；{@code @AfterEach} 清场。</p>
 */
@DisplayName("MSG-2: user_notification COALESCE 幂等与开关停发")
class UserNotificationDedupeIT extends AbstractItTestBase {

    private static final long SOURCE_ID = 9801L;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SysConfigService sysConfigService;

    @Autowired
    private UserNotificationMapper userNotificationMapper;

    private NotificationEvent broadcastEvent(String instanceKey) {
        return NotificationEvent.builder()
                .type(NotificationType.PLAN_DRAFT_READY)
                .sourceType("TASK")
                .sourceId(SOURCE_ID)
                .instanceKey(instanceKey)
                .receiverId(null)
                .build();
    }

    private long countByKey(String dedupeKey) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE dedupe_key = ?", Long.class, dedupeKey);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("唯一索引含 COALESCE(receiver_id,0)：NULL 广播去重的索引前提")
    void uniqueIndexUsesCoalesce() {
        String def = jdbcTemplate.queryForObject(
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indexrelid = 'uk_user_notification_dedupe'::regclass",
                String.class);
        assertTrue(def != null && def.contains("COALESCE(receiver_id"),
                "唯一索引必须以 COALESCE(receiver_id,0) 收紧，否则 NULL 广播不去重；实际: " + def);
        assertTrue(def.contains("dedupe_key"), "唯一索引须含 dedupe_key 列");
    }

    @Test
    @DisplayName("广播同键两次 publish → 一行（COALESCE NULL 陷阱）")
    void nullBroadcastDedupes() {
        sysConfigService.setValue("notification.enabled", "1");
        String key = "PLAN_DRAFT_READY:TASK:" + SOURCE_ID + ":it-broadcast";

        notificationService.publish(broadcastEvent("it-broadcast"));
        notificationService.publish(broadcastEvent("it-broadcast"));

        assertEquals(1, countByKey(key), "receiver_id NULL 同键两次必须只落一行");
    }

    @Test
    @DisplayName("双线程并发同事件 → 一行（跨副本幂等 DB 层证据）")
    void concurrentPublishHasOneRow() throws Exception {
        sysConfigService.setValue("notification.enabled", "1");
        String key = "PLAN_DRAFT_READY:TASK:" + SOURCE_ID + ":it-concurrent";

        int concurrency = 2;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> {
                ready.countDown();
                await(start);
                notificationService.publish(broadcastEvent("it-concurrent"));
            });
            Future<?> b = pool.submit(() -> {
                ready.countDown();
                await(start);
                notificationService.publish(broadcastEvent("it-concurrent"));
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, countByKey(key), "并发同键 publish 必须只落一行");
    }

    @Test
    @DisplayName("开关 OFF：publish 早退不写库")
    void disabledToggleWritesNothing() {
        sysConfigService.setValue("notification.enabled", "0");
        String key = "PLAN_DRAFT_READY:TASK:" + SOURCE_ID + ":it-disabled";

        notificationService.publish(broadcastEvent("it-disabled"));

        assertEquals(0, countByKey(key), "开关 OFF 时 publish 不得写库");
    }

    @Test
    @DisplayName("payload JSONB 往返：publish 写入 → Mapper 回读等值（PgJsonbTypeHandler）")
    void payloadJsonbRoundTrip() {
        sysConfigService.setValue("notification.enabled", "1");
        String key = "PLAN_DRAFT_READY:TASK:" + SOURCE_ID + ":it-jsonb";

        notificationService.publish(NotificationEvent.builder()
                .type(NotificationType.PLAN_DRAFT_READY)
                .sourceType("TASK")
                .sourceId(SOURCE_ID)
                .instanceKey("it-jsonb")
                .receiverId(null)
                .payload(Map.of("attemptNo", 3, "note", "中文值"))
                .build());

        UserNotification row = userNotificationMapper.selectOne(
                new QueryWrapper<UserNotification>().eq("dedupe_key", key));
        assertThat(row).isNotNull();
        assertThat(row.getPayload())
                .containsEntry("attemptNo", 3)
                .containsEntry("note", "中文值");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM user_notification WHERE dedupe_key LIKE ?", "%:" + SOURCE_ID + ":%");
        sysConfigService.setValue("notification.enabled", "0");
    }
}