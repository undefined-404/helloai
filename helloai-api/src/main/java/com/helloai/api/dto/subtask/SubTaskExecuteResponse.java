package com.helloai.api.dto.subtask;

import lombok.Data;

@Data
public class SubTaskExecuteResponse {

    private boolean success;
    private String output;
    private String finishReason;
    private String executor;
    private Integer tokens;
}

