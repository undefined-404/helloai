package com.helloai.common.base;

import lombok.Data;
import org.slf4j.MDC;

import java.io.Serializable;

@Data
public class R<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;
    private String traceId;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMsg("success");
        r.setData(data);
        r.setTraceId(MDC.get("traceId"));
        return r;
    }

    public static <T> R<T> fail(String msg) {
        R<T> r = new R<>();
        r.setCode(500);
        r.setMsg(msg);
        r.setTraceId(MDC.get("traceId"));
        return r;
    }

    public static <T> R<T> fail(Integer code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setTraceId(MDC.get("traceId"));
        return r;
    }
}
