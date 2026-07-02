package com.helloai.api.dto.admin;

import lombok.Data;

@Data
public class SetupInitializeRequest {
    private String adminUsername;
    private String adminPassword;
    private String systemName;
    private String systemDescription;
    private String registrationToken;
}
