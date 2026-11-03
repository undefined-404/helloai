package com.helloai.api.support;

import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作台事件推送（PA-6）：复用既有任务事件内核，只推「有变化」提示，
 * 不把任务数据写进 SSE 帧——数据仍由 {@code /workbench} 按当前用户过滤后重新拉取。
 *
 * <p><b>按 owner 过滤（2026-11-03 复核修正）</b>：连接建立时绑定当前 userId；广播时解析
 * 任务 owner，仅推给归属用户的连接（owner 为空的老数据按共享处理）。任务不存在/解析失败
 * 时跳过本帧，避免向所有连接泄漏 taskId。</p>
 *
 * <p><b>边界</b>：连接是进程内的（与 Agent 门铃同构）。多副本部署时事件只在发布
 * 实例上广播，连到其他副本的工作台会漏掉该次提示；本通道定位为体验增强，
 * 不承担跨副本一致性，漏推不伤数据正确性。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkbenchEventHub {

    /** 30 分钟：前端断开后退避重连，避免长连接被网关静默回收后永远收不到推送。 */
    private static final long EMITTER_TIMEOUT_MS = 1_800_000L;

    private final List<Emitter> emitters = new CopyOnWriteArrayList<>();
    private final TaskService taskService;

    /** 连接 + 归属用户（null = 未识别用户，只接收 owner 为空的老数据事件）。 */
    record Emitter(SseEmitter emitter, Long userId) {
    }

    /** 建立一条工作台推送连接；userId 由请求线程解析后传入，用于广播按 owner 过滤。 */
    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Emitter registration = new Emitter(emitter, userId);
        emitters.add(registration);
        Runnable remove = () -> emitters.remove(registration);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        if (!send(emitter, "connected", "ok")) {
            emitters.remove(registration);
            emitter.complete();
        }
        return emitter;
    }

    /** 子任务完成 → 任务进度可能推进，提示归属用户刷新最近任务。 */
    @EventListener
    public void onSubTaskCompleted(SubTaskCompletedEvent event) {
        broadcastTaskChange(event.getTaskId());
    }

    /** 任务自动收口 → 状态变为 DONE，提示归属用户刷新。 */
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
        for (Emitter registration : emitters) {
            if (!send(registration.emitter(), "ping", "1")) {
                emitters.remove(registration);
                registration.emitter().complete();
            }
        }
    }

    private void broadcastTaskChange(Long taskId) {
        if (emitters.isEmpty()) {
            return;
        }
        Long ownerId;
        try {
            Task task = taskId == null ? null : taskService.getById(taskId);
            if (task == null) {
                // 任务不存在（可能已删）：归属未知，跳过本帧，避免向所有连接泄漏 id
                return;
            }
            ownerId = task.getOwnerId();
        } catch (Exception e) {
            log.warn("工作台推送解析任务归属失败，本帧跳过: taskId={}, err={}", taskId, e.getMessage());
            return;
        }
        String data = taskId == null ? "" : String.valueOf(taskId);
        for (Emitter registration : emitters) {
            // owner 为空（老数据 / 异步创建）按共享处理；否则仅推给归属用户
            if (ownerId != null && !Objects.equals(ownerId, registration.userId())) {
                continue;
            }
            if (!send(registration.emitter(), "task", data)) {
                emitters.remove(registration);
                registration.emitter().complete();
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