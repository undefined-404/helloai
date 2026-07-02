package com.helloai.api.dto.agent;

import lombok.Data;

@Data
public class AgentRegistrationResponse {
    private Long id;
    private String name;
    private String role;
    private String apiKey;
    private String message;
}
