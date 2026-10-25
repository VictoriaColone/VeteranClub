package cn.normanclub.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound(Exception ex,HttpServletRequest r){return error(404,"接口不存在",r);}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception ex,HttpServletRequest r){return error(405,"请求方法不支持",r);}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> media(Exception ex,HttpServletRequest r){return error(415,"请使用 JSON 请求体",r);}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException ex, HttpServletRequest r) { return error(ex.status, ex.getMessage(), r); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<?> validation(Exception ex, HttpServletRequest r) { return error(400, "请求参数格式不正确", r); }
    @ExceptionHandler({DataIntegrityViolationException.class,ConcurrencyFailureException.class})
    ResponseEntity<?> conflict(Exception ex, HttpServletRequest r) { return error(409, "操作冲突，请刷新后重试", r); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception ex, HttpServletRequest r) {
        // Never log SQL, request bodies, phone numbers, OTPs or credentials.
        LOG.error("request_failed id={} type={}", r.getAttribute("requestId"), ex.getClass().getSimpleName());
        return error(500, "服务暂时不可用，请稍后重试", r);
    }
    private ResponseEntity<?> error(int code, String message, HttpServletRequest r) {
        return ResponseEntity.status(code).body(new ApiResponse<>(code, message, null, String.valueOf(r.getAttribute("requestId"))));
    }
}
