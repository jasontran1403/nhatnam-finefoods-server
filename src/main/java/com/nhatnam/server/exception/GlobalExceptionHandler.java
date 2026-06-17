package com.nhatnam.server.exception;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.OutOfStockException;
import com.nhatnam.server.common.RateLimitException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.common.StaleOrderDataException;
import com.nhatnam.server.dto.income.StaleOrderConflictResponse;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy tài nguyên"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(StaleOrderDataException.class)
    public ResponseEntity<?> handleStaleOrderData(StaleOrderDataException e) {
        StaleOrderConflictResponse body = StaleOrderConflictResponse.builder()
                .orderCode(e.getOrderCode())
                .actualRemainingAmount(e.getActualRemainingAmount())
                .paidAmount(e.getPaidAmount())
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ApiResponse<Void>> handleStock(OutOfStockException e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.OUT_OF_STOCK, e.getMessage()));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(RateLimitException e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.TOO_MANY_REQUESTS, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.VALIDATION_ERROR, msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(Exception ex) {
        log.warn("[OPTIMISTIC_LOCK] Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(StatusCode.CONFLICT,
                        "Thao tác lỗi, dữ liệu đã được cập nhật trước đó. Vui lòng tải lại và thử lại."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        return ResponseEntity.ok(
                ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                        "Lỗi hệ thống: " + e.getMessage()));
    }
}