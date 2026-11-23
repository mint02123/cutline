package com.cutline.common.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 에러를 RFC 9457 Problem Details로 응답한다. 클라이언트는 {@code code} 필드로 분기한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CutlineException.class)
    ProblemDetail handle(CutlineException e) {
        return problem(e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handle(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(ErrorCode.INVALID_REQUEST, "입력값을 확인해 주세요.");
        List<Map<String, String>> errors = e.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field", error instanceof org.springframework.validation.FieldError f ? f.getField() : error.getObjectName(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handle(HttpMessageNotReadableException e) {
        return problem(ErrorCode.INVALID_REQUEST, "요청 본문을 읽을 수 없습니다.");
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        return problem;
    }
}
