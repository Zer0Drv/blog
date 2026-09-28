package com.zer0drv.blog.common.response;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Yoruhaki
 */
@Data
@NoArgsConstructor
public class Result<T> {

    /**
     * 状态码
     */
    private String code;

    /**
     * 状态信息
     */
    private String message;

    /**
     * 数据
     */
    private T data;

    private Result(StatusCode status) {
        this.code = status.getCode();
        this.message = status.getMessage();
    }

    public static Result<Void> ok() {
        return new Result<>(StatusCode.OK);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>(StatusCode.OK);
        result.setData(data);
        return result;
    }

    public static Result<Void> fail() {
        return new Result<>(StatusCode.FAIL);
    }

    public static Result<Void> fail(String message) {
        Result<Void> result = new Result<>(StatusCode.FAIL);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> fail(StatusCode status) {
        return new Result<>(status);
    }

    public static Result<Void> fail(String code, String message) {
        Result<Void> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
