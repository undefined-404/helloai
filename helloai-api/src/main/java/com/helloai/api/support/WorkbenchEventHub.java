package com.helloai.api.support;

import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作台事件推送（PA-6）：复用既有任务事件内核，只推「有变化」提示，
 * 不把任务数据写进 SSE 帧——数据仍由 {@code /workbench} 按当前用户过滤后重新拉取。
 *
 * <p><b>边界</b>：连接是进程内的（与 Agent 门铃同构）。多副本部署时事件只在发布
 * 实例上广播，连到其他副本的工作台会漏掉该次提示；本通道定位为体验增强，
 * 不承担跨副本一致性，漏推不伤数据正确性。</p>
 */
@Slf4j
@Component
public class WorkbenchEventHub {

    /** 30 分钟：前端断开后退避重连，避免长连接被网关静默回收后永远收不到推送。 */
    private static final long EMITTER_TIMEOUT_MS = 1_800_000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 建立一条工作台推送连接（鉴权与用户上下文由既有拦截器完成）。 */
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        if (!send(emitter, "connected", "ok")) {
            emitters.remove(emitter);
            emitter.complete();
        }
        return emitter;
    }

    /** 子任务完成 → 任务进度可能推进，提示刷新最近任务。 */
    @EventListener
    public void onSubTaskCompleted(SubTaskCompletedEvent event) {
        broadcastTaskChange(event.getTaskId());
    }

    /** 任务自动收口 → 状态变为 DONE，提示刷新。 */
    @EventListener
    public void onTaskAutoCompleted(TaskAutoCompletedEvent event) {
        broadcastTaskChange(event.getTaskId());
    }

    /**
     * 心跳：25s 一次，防止代理把空闲连接掐掉；顺带清理已失效连接。
     * 与事件推送同帧格式（event: ping），前端直接忽略。
     */
    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            if (!send(emitter, "ping", "1")) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }

    private void broadcastTaskChange(Long taskId) {
        if (emitters.isEmpty()) {
            return;
        }
        String data = taskId == null ? "" : String.valueOf(taskId);
        for (SseEmitter emitter : emitters) {
            if (!send(emitter, "task", data)) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }

    private static boolean send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("工作台推送连接已失效: event={}, err={}", event, e.getMessage());
            return false;
        }
    }
}
