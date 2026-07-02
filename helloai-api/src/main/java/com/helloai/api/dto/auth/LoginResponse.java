package com.helloai.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String type;       // "admin" 或 "agent"
    private String displayName;
    private String role;       // "SUPER_ADMIN" / "ADMIN" / "PLANNER" / "EXECUTOR" / ...
}
