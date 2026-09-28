package com.zer0drv.blog.common.exception;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * @author Yoruhaki
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> businessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> methodArgumentNotValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? StatusCode.PARAM_INVALID.getMessage()
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return Result.fail(StatusCode.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public Result<Void> badRequest(Exception e) {
        return Result.fail(StatusCode.PARAM_INVALID.getCode(), StatusCode.PARAM_INVALID.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> noResourceFoundException(NoResourceFoundException e) {
        return Result.fail(StatusCode.NO_RESOURCE_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> httpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        return Result.fail(StatusCode.HTTP_REQUEST_METHOD_NOT_SUPPORTED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> accessDeniedException(AccessDeniedException e) {
        return Result.fail("40300", "没有访问权限");
    }

    @ExceptionHandler(AuthenticationException.class)
    public Result<Void> authenticationException(AuthenticationException e) {
        return Result.fail("40100", "认证失败：" + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> exception(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(StatusCode.INTERNAL_SERVER_ERROR);
    }
}
