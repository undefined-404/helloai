package com.helloai.api.dto.admin;

import lombok.Data;
import java.util.Map;

@Data
public class ConfigBatchRequest {
    private Map<String, String> config;
}
