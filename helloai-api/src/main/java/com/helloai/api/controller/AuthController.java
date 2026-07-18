package com.helloai.api.controller;

import com.helloai.api.dto.auth.ChangePasswordRequest;
import com.helloai.api.dto.auth.LoginRequest;
import com.helloai.api.dto.auth.LoginResponse;
import com.helloai.api.interceptor.AuthInterceptor;
import com.helloai.common.base.R;
import com.helloai.core.entity.Agent;
import com.helloai.core.system.service.AuthService;
import com.helloai.core.system.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SysUserService sysUserService;

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        return switch (req.getType()) {
            case "admin" -> {
                AuthService.AdminSession session = authService.adminLogin(
                        req.getUsername() != null ? req.getUsername() : "admin", req.getCredential());
                // 记录最后登录信息
                sysUserService.updateLoginInfo(session.id(), httpReq.getRemoteAddr());
                yield R.ok(new LoginResponse(session.token(), "admin", session.displayName(), session.role()));
            }
            case "agent" -> {
                Agent agent = authService.validateAgentKey(req.getCredential());
                yield R.ok(new LoginResponse(agent.getApiKey(), "agent", agent.getName(), agent.getRole().name()));
            }
            default -> R.fail("登录类型无效，仅支持 admin/agent");
        };
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("X-Admin-Token") String token) {
        authService.adminLogout(token);
        return R.ok();
    }

    @PostMapping("/change-password")
    public R<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest req,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            HttpServletRequest httpReq) {
        String authType = (String) httpReq.getAttribute(AuthInterceptor.AUTH_TYPE_KEY);
        if (!"admin".equals(authType)) {
            return R.fail(403, "只有管理员账号支持修改密码");
        }
        Long userId = (Long) httpReq.getAttribute(AuthInterceptor.AUTH_ID_KEY);
        sysUserService.changePassword(userId, req.getCurrentPassword(), req.getNewPassword());
        if (token != null && !token.isBlank()) {
            authService.adminLogout(token);
        }
        return R.ok();
    }

    @GetMapping("/me")
    public R<?> me(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (adminToken != null && !adminToken.isBlank()) {
            AuthService.AdminSession session = authService.validateAdminToken(adminToken);
            return R.ok(new LoginResponse(session.token(), "admin", session.displayName(), session.role()));
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String apiKey = authorization.substring(7);
            Agent agent = authService.validateAgentKey(apiKey);
            return R.ok(new LoginResponse(agent.getApiKey(), "agent", agent.getName(), agent.getRole().name()));
        }
        return R.fail(401, "未登录");
    }
}
