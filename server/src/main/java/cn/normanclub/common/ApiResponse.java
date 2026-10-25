package cn.normanclub.common;

import jakarta.servlet.http.HttpServletRequest;
public record ApiResponse<T>(int code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return new ApiResponse<>(0, "ok", data, String.valueOf(request.getAttribute("requestId")));
    }
}
