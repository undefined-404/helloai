package com.helloai.common.constant;

import com.baomidou.mybatisplus.annotation.IEnum;

public enum OutboxStatus implements IEnum<Integer> {
    PENDING(0),
    SUCCESS(1),
    FAILED(2);

    private final int value;

    OutboxStatus(int value) {
        this.value = value;
    }

    @Override
    public Integer getValue() {
        return value;
    }
}
