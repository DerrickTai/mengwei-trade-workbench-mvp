package com.mengwei.localgrowth.shared;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  @ExceptionHandler(ApiException.class) ResponseEntity<?> handle(ApiException ex) { return error(ex.status(), ex.code(), ex.getMessage()); }
  @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException ex) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getBindingResult().getFieldError().getDefaultMessage()); }
  @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> notFound(NoResourceFoundException ex) { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在"); }
  @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception ex) { log.error("Unhandled API exception", ex); return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系统暂时无法处理该请求"); }
  private ResponseEntity<?> error(HttpStatus status, String code, String message) { return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"timestamp",OffsetDateTime.now().toString())); }
  public static class ApiException extends RuntimeException {
    private final HttpStatus status; private final String code;
    public ApiException(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
    public HttpStatus status(){ return status; } public String code(){ return code; }
  }
}
