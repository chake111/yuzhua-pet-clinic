package com.yuzhua.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        String msg = e.getMessage() == null ? "操作失败" : e.getMessage();
        log.warn("业务异常: {}", msg);
        return Result.fail(e.getCode(), msg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.fail(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.fail(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(400, "操作未成功，请刷新后重试");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(400, "提交的内容有误，请刷新后重试");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUpload(MaxUploadSizeExceededException e) {
        return Result.fail(400, "上传文件过大");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public Result<Void> handleNotFound(HttpServletRequest request, Exception e) {
        log.warn("接口不存在 uri={}", request.getRequestURI());
        return Result.fail(404, "请求的地址不存在或已失效");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpServletRequest request, HttpRequestMethodNotSupportedException e) {
        log.warn("请求方式错误 method={} uri={}", e.getMethod(), request.getRequestURI());
        return Result.fail(405, "操作未成功，请刷新后重试");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(HttpServletRequest request, DuplicateKeyException e) {
        log.warn("唯一键冲突 uri={}", request.getRequestURI());
        return Result.fail(400, "数据已存在，请刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(HttpServletRequest request, Exception e) {
        String traceId = TraceIdFilter.current();
        log.error("系统异常 traceId={} method={} uri={}", traceId, request.getMethod(),
                request.getRequestURI(), e);
        String msg = traceId == null ? "服务器繁忙，请稍后再试"
                : "服务器繁忙，请稍后再试（追踪号 " + traceId + "）";
        return Result.fail(500, msg);
    }
}
