package com.helloai.api.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.common.base.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理面路径限定拦截器：{@code /api/admin/**} 只允许<strong>平台账号</strong>访问。
 *
 * <p><b>语义（BASE-4.4 修订）</b>：本拦截器现在只区分「平台账号会话」与「外部 Agent API Key」，
 * <strong>不</strong>做角色判定——授权（你能干什么）一律交给动作级权限码
 * （{@code @SaCheckPermission} + Sa-Token StpInterface），见 CODE_STYLE §43。</p>
 *
 * <p><b>为何去掉角色闸</b>：BASE-4.1 曾在此强制 {@code SUPER_ADMIN|ADMIN}，但业务只读页面
 * （Agent 管理 / Team 组合 / Browser 会话 / 质量看板 / 打卡上班）的数据接口仍在
 * {@code /api/admin/**} 前缀下——角色闸会在动作码之前拦下 NORMAL_USER / GUEST，
 * 使其可见菜单全部 403。去掉角色闸后：写操作由动作码守住（GUEST 零写码 → 写接口 403），
 * 管理面无动作码的读接口对已登录账号开放（见实施计划 §9.9.3）。</p>
 *
 * <p>外部 Agent（API Key）不进入 Sa-Token 会话体系，{@code isLogin()} 为 false → 403。</p>
 *
 * <p><b>命名遗留</b>：类名仍为 {@code AdminOnlyInterceptor}（历史上确为 admin 角色闸），
 * 当前语义是「平台账号限定」；重命名触及文档与验证脚本多处引用，登记为技术债。</p>
 */
public class AdminOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 仅判定「平台账号身份」：Agent（API Key）不得访问管理面路径。
        // 细粒度授权由 @SaCheckPermission 动作码承担，不在此判角色。
        if (!StpUtil.isLogin()) {
            throw new BizException(403, "需要管理员权限");
        }
        return true;
    }
}
