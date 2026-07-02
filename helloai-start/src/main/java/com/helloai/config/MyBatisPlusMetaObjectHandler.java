package com.helloai.config;

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
