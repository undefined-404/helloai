package com.helloai.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 本机主机名工具。
 *
 * <p>Phase 0 A2 从 {@code AgentExecutionRecordServiceImpl#getHostName()} 提取为公共工具
 * （规范 §50.4 复用优先），保证执行记录节点标识（{@code agent_execution_record.worker_node}
 * 与 {@code sub_task.owner}）同源，Watchdog 据此只续自己节点的租约。</p>
 */
public final class HostNameUtils {

    private HostNameUtils() {
    }

    /**
     * 获取本机主机名；获取失败时返回 {@code "unknown"}（与提取前的行为完全一致）。
     */
    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}