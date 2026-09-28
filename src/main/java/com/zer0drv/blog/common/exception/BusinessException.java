package com.zer0drv.blog.common.exception;

import com.zer0drv.blog.common.response.StatusCode;
import lombok.Getter;

/**
 * @author Yoruhaki
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(StatusCode status) {
        super(status.getMessage());
        this.code = status.getCode();
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
}
