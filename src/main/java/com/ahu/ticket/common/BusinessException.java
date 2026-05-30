package com.ahu.ticket.common;

public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(Result.INTERNAL_ERROR_CODE, message);
    }

    public BusinessException(Result<?> result) {
        this(result.getCode(), result.getMessage());
    }

    public int getCode() {
        return code;
    }
}
