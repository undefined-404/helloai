package com.helloai.api.dto.module;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ModuleResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String name;
    private String description;
    private Integer sortOrder;
    private OffsetDateTime createTime;
}
