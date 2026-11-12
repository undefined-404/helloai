package com.helloai.core.planner.support;

/**
 * 拆解尝试的抢占者标识（`TD-16b`）。
 *
 * <p>格式 {@code <hostname>:<pid>}，落 {@code task_plan_attempt.owner_node}，
 * 用于多实例排查「这条是哪个节点在跑 / 哪个节点留下了悬挂」。</p>
 *
 * <p>**只在一处实现**：派发器与异步执行段都要写这个值，两处各算一遍必然分叉
 * （比如一处用 hostname 一处用 IP），排查时对不上就是白留字段。</p>
 *
 * <p>结果在类加载时算一次并缓存：hostname 解析走 DNS，逐次调用在容器里可能几百毫秒。</p>
 */
public final class PlanNodeId {

    private static final String VALUE = compute();

    private PlanNodeId() {
    }

    public static String current() {
        return VALUE;
    }

    private static String compute() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // 容器里 hostname 解析失败是常见情况，降级成 unknown-host 而不是抛异常：
            // 这个值只用于诊断，不值得为它挡住拆解。
            host = "unknown-host";
        }
        return host + ":" + ProcessHandle.current().pid();
    }
}
