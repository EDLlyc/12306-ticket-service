package com.ahu.ticket.common;

public class ExceptionResponse {
    private final Integer code;
    private final String message;
    private final String traceId;

    public ExceptionResponse(Integer code, String message, String traceId) {
        this.code = code;
        this.message = message;
        this.traceId = traceId;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getTraceId() {
        return traceId;
    }
}
