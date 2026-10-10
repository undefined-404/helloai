package com.helloai.api.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首次运行锁定（`REF-5.3`，用户裁定 `D-2026-10-09-6①` 项下的 A3）——
 * **未完成初始化的实例不对外提供服务**：除初始化向导与健康检查外，一律 {@code 503 setup_required}。
 *
 * <p><b>判据为什么是「用户数 == 0」而不是 {@code system.setup_finished}</b>：后者在
 * {@code V1__init_all.sql} 里被**预置为 {@code '1'}**，新实例一启动就是「已完成初始化」，
 * 拿它当判据等于**永不生效**。而「有没有用户」是事实。前端 {@code Login.vue} 也已在用
 * {@code !setupStatus.hasUsers} 判首次运行 —— 两者天然同源，不新增第二套语义。</p>
 *
 * <p><b>为什么在内存里闩锁（latch）而不是每请求查库</b>：判据要落在**每个** {@code /api/**} 请求上，
 * 每请求一次 {@code count(*)} 是纯热路径浪费；而「已初始化」是**一次性单向**语义 ——
 * 一旦观察到有用户就永远是已初始化，故首次为真后闩死、不再查库。
 * 代价（诚实边界）：若有人把用户表清空，进程内仍认为已初始化，恢复锁定需重启 ——
 * 「清空用户表」并非常规运维动作，故取「零热路径开销」这一侧。</p>
 *
 * <p><b>为什么是 503 而不是 401/403</b>：这不是权限问题，而是「服务尚未就绪」。
 * 503 + 响应体 {@code data.setup_required=true} 让任意客户端（前端 SPA / 脚本 / 探针）
 * 能自解释地知道「去初始化」，不必从 401 里猜。</p>
 *
 * <p><b>与本仓库既有拦截器的关系</b>：本拦截器**不判身份**（身份由 {@code AuthInterceptor} 承担），
 * 只判「实例就绪度」；注册顺序排在认证之前，使未初始化实例对**任何**调用方都给同一答案。</p>
 */
@Slf4j
public class SetupLockInterceptor implements HandlerInterceptor {

    /** 未初始化时的响应消息（同时进响应体与日志）。 */
    static final String MSG = "实例尚未初始化：请先完成首次初始化向导（当前无任何用户）";

    private final UserCountProvider userCountProvider;
    private final ObjectMapper objectMapper;

    /** 已初始化闩锁（见类注释：单向、无 TTL、不清除）。 */
    private volatile boolean initialized = false;

    public SetupLockInterceptor(UserCountProvider userCountProvider, ObjectMapper objectMapper) {
        this.userCountProvider = userCountProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (initialized) {
            return true;
        }
        if (userCountProvider.userCount() > 0) {
            initialized = true;
            log.info("首次运行锁定解除：已检测到用户，实例进入正常服务状态");
            return true;
        }
        // 未初始化：只记 debug，避免任何匿名请求都能刷日志（该状态本就该很快被初始化流程终结）
        log.debug("首次运行锁定生效，拒绝请求: {} {}", request.getMethod(), request.getRequestURI());
        writeSetupRequired(response);
        return false;
    }

    /**
     * 写 {@code 503} + {@code {code,msg,data.setup_required}} —— 手工组装而非用 {@code R}：
     * {@code R} 没有「带 data 的 fail」工厂，而这里的 data 正是「让客户端自解释」的关键字段。
     */
    private void writeSetupRequired(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        body.put("msg", MSG);
        body.put("data", Map.of("setup_required", true));
        objectMapper.writeValue(response.getWriter(), body);
    }

    /** 用户数提供方（生产传 {@code sysUserService::count}；单测传替身，避免把 MyBatis 拖进拦截器测试）。 */
    @FunctionalInterface
    public interface UserCountProvider {
        long userCount();
    }
}
