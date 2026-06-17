package com.nhatnam.server.enumtype;

public class StatusCode {

    // ==================== SUCCESS (900) ====================
    public static final int SUCCESS = 900;  // Dùng chung cho tất cả action thành công

    // ==================== CLIENT ERRORS (901-920) ====================
    public static final int UNAUTHORIZED = 901;              // Chưa đăng nhập hoặc token không hợp lệ
    public static final int FORBIDDEN = 902;                 // Không có quyền truy cập
    public static final int BAD_REQUEST = 903;               // Request không hợp lệ
    public static final int NOT_FOUND = 904;                 // Không tìm thấy resource
    public static final int CONFLICT = 905;                  // Dữ liệu bị trùng (email, phone...)
    public static final int VALIDATION_ERROR = 906;          // Lỗi validate dữ liệu
    public static final int TOO_MANY_REQUESTS = 907;         // Rate limit, spam request
    public static final int NOT_IMPLEMENTED = 908;

    // ==================== SERVER ERRORS (921-940) ====================
    public static final int INTERNAL_SERVER_ERROR = 921;     // Lỗi server
    public static final int JWT_EXPIRED = 923;
    public static final int JWT_INVALID_SIGNATURE = 924;

    // ==================== BUSINESS LOGIC ERRORS (941-999) ====================
    public static final int WRONG_PASSWORD = 941;            // Sai mật khẩu
    public static final int ACCOUNT_LOCKED = 942;            // Tài khoản bị khóa
    public static final int OUT_OF_STOCK = 947;              // Hết hàng
    public static final int INVALID_OTP = 948;               // OTP không hợp lệ
    public static final int OTP_EXPIRED = 949;               // OTP hết hạn
    public static final int PRICE_CHANGED = 950;

    // ==================== HELPER METHOD ====================

    /**
     * Check if code is success
     */
    public static boolean isSuccess(int code) {
        return code == SUCCESS;
    }

    /**
     * Check if code is error
     */
    public static boolean isError(int code) {
        return code != SUCCESS;
    }
}