package com.nhatnam.server.dto.response;

import com.nhatnam.server.enumtype.StatusCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private int code;
    private T data;
    private String message;
    private long time;

    // Method helper để check success dựa vào code
    public boolean isSuccess() {
        return code >= 900 && code < 1000;
    }

    // Constructor cho trường hợp thành công với data
    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .code(StatusCode.SUCCESS)
                .data(data)
                .message(message)
                .time(System.currentTimeMillis() / 1000)
                .build();
    }

    // Constructor cho thành công với code tùy chỉnh
    public static <T> ApiResponse<T> success(int code, T data, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .data(data)
                .message(message)
                .time(System.currentTimeMillis() / 1000)
                .build();
    }

    // Constructor cho lỗi
    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .time(System.currentTimeMillis() / 1000)
                .build();
    }

    // Constructor cho lỗi với data
    public static <T> ApiResponse<T> error(int code, T data, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .data(data)
                .message(message)
                .time(System.currentTimeMillis() / 1000)
                .build();
    }
}