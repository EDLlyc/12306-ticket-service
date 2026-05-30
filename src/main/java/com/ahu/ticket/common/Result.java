package com.ahu.ticket.common;

/**
 * 统一后端返回结果包装类
 * 面试亮点：体现了规范的后端 RESTful API 设计标准
 */
public class Result<T> {
    public static final int SUCCESS_CODE = 200;
    public static final int BAD_REQUEST_CODE = 400;
    public static final int UNAUTHORIZED_CODE = 401;
    public static final int FORBIDDEN_CODE = 403;
    public static final int NOT_FOUND_CODE = 404;
    public static final int TOO_MANY_REQUESTS_CODE = 429;
    public static final int CONFLICT_CODE = 409;
    public static final int INTERNAL_ERROR_CODE = 500;

    private Integer code;
    private String message;
    private T data;

    public Result() {}

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(INTERNAL_ERROR_CODE, message, null);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> badRequest(String message) {
        return new Result<>(BAD_REQUEST_CODE, message, null);
    }

    public static <T> Result<T> unauthorized(String message) {
        return new Result<>(UNAUTHORIZED_CODE, message, null);
    }

    public static <T> Result<T> forbidden(String message) {
        return new Result<>(FORBIDDEN_CODE, message, null);
    }

    public static <T> Result<T> notFound(String message) {
        return new Result<>(NOT_FOUND_CODE, message, null);
    }

    public static <T> Result<T> tooManyRequests(String message) {
        return new Result<>(TOO_MANY_REQUESTS_CODE, message, null);
    }

    public static <T> Result<T> conflict(String message) {
        return new Result<>(CONFLICT_CODE, message, null);
    }

    public static <T> Result<T> internalError(String message) {
        return new Result<>(INTERNAL_ERROR_CODE, message, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
