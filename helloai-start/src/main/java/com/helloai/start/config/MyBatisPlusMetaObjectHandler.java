package com.helloai.start.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MyBatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.setFieldValByName("deleted", 0, metaObject);
        this.setFieldValByName("createBy", getCurrentUser(), metaObject);
        this.setFieldValByName("updateBy", getCurrentUser(), metaObject);
        this.setFieldValByName("createTime", OffsetDateTime.now(), metaObject);
        this.setFieldValByName("updateTime", OffsetDateTime.now(), metaObject);
        // v2.4 N11 新增字段：连续失败计数。表中 NOT NULL，无 default，
        // 任何 INSERT Agent 的路径（如 AgentService.register）都必须填 0，否则
        // 会撞 "null value in column consecutive_failure_count violates not-null constraint"。
        this.setFieldValByName("consecutiveFailureCount", 0, metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("updateTime", OffsetDateTime.now(), metaObject);
        this.setFieldValByName("updateBy", getCurrentUser(), metaObject);
    }

    private String getCurrentUser() {
        return "system";
    }
}
