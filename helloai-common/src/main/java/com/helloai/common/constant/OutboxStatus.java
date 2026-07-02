package com.helloai.common.constant;

public enum OutboxStatus {
    PENDING(0),
    SUCCESS(1),
    FAILED(2);

    private final int value;

    OutboxStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
