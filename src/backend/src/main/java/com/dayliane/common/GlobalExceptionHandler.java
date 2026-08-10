package com.dayliane.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<String>> handleBusiness(BusinessException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getCode());
        if (status == null || !status.isError()) {
            log.error("BusinessException used an invalid HTTP status: {}", ex.getCode(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "internal server error", null));
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<String>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getAllErrors().isEmpty()
                ? "validation failed"
                : ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "bad request", detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<String>> handleException(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            int code = errorResponse.getStatusCode().value();
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(ApiResponse.error(code, clientMessage(code), null));
        }
        log.error("Unhandled request exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "internal server error", null));
    }

    private static String clientMessage(int status) {
        return switch (status) {
            case 400 -> "bad request";
            case 404 -> "not found";
            case 405 -> "method not allowed";
            case 415 -> "unsupported media type";
            default -> "request failed";
        };
    }
}
