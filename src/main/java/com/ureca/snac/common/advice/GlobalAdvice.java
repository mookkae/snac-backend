package com.ureca.snac.common.advice;

import com.ureca.snac.common.ApiResponse;
import com.ureca.snac.common.BaseCode;
import com.ureca.snac.common.exception.BaseCustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalAdvice {

    @ExceptionHandler(BaseCustomException.class)
    public ResponseEntity<ApiResponse<?>> handleCustomException(BaseCustomException e) {
        BaseCode baseCode = e.getBaseCode();

        // HTTP 상태 코드 기반으로 로그 레벨 결정
        // 5xx — 서버/인프라 오류 (운영자 확인 필요)
        // 4xx — 비즈니스·PG 피드백 (사용자 또는 클라이언트 문제)
        if (baseCode.getStatus().is5xxServerError()) {
            log.error("[서버 오류] {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        } else {
            log.warn("[비즈니스/PG 오류] {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }

        return ResponseEntity
                .status(baseCode.getStatus())
                .body(ApiResponse.error(baseCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.warn("Validation fail: ", e);

        List<ValidationErrorResponse> fieldErrors = e.getFieldErrors()
                .stream()
                .map(error -> new ValidationErrorResponse(
                        error.getField(),
                        error.getRejectedValue() == null ? "" : error.getRejectedValue().toString(),
                        error.getCode(),
                        error.getDefaultMessage()
                ))
                .toList();

        List<ValidationErrorResponse> globalErrors = e.getGlobalErrors()
                .stream()
                .map(error -> new ValidationErrorResponse(
                        error.getObjectName(),
                        "",
                        error.getCode(),
                        error.getDefaultMessage()
                )).toList();

        return ResponseEntity.status(BaseCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.of(BaseCode.INVALID_INPUT, ValidationErrorResponseWrapper.of(fieldErrors, globalErrors)));
    }
}
