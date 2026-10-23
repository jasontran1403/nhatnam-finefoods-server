package com.nhatnam.server.common;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;

import java.util.Set;

/**
 * TRUNG TÂM PHÂN QUYỀN XUẤT BÁO CÁO ĐƠN HÀNG — SELLER (10/2026).
 *
 * <p>Áp cho 4 báo cáo trên page {@code seller/orders}:
 * <ul>
 *   <li>Báo cáo Đơn hàng  (/api/accountant/orders/export)</li>
 *   <li>Báo cáo Sản phẩm  (/api/seller/orders/export-product-report)</li>
 *   <li>Báo cáo Nguyên liệu (/api/seller/orders/export-ingredients)</li>
 *   <li>Báo cáo KH × SP   (/api/seller/customer-product-report)</li>
 * </ul>
 *
 * <p><b>Chỉ 2 account được phép</b>, cùng với OWNER / ADMIN / SUPERADMIN. Khi
 * những tài khoản này xuất, data trả về là <b>TOÀN BỘ ĐƠN</b> của mọi user —
 * không scope theo seller đăng nhập. SELLER / SUPER_SELLER khác sẽ bị 403.
 *
 * <p>User ID đang là placeholder (12, 15) — chủ muốn thay bằng ID thật sau,
 * sửa duy nhất ở {@link #ALLOWED_USER_IDS} dưới đây là xong, 4 endpoint tự
 * cập nhật theo.
 */
public final class ReportAccessGuard {

    private ReportAccessGuard() {}

    /**
     * (10/2026) WHITELIST SELLER ID — chỉ các SELLER thuần có id trong đây
     * mới xuất báo cáo cả hệ thống. SELLER thuần khác vẫn xuất được, nhưng
     * data bị FILTER chỉ còn đơn chính họ tạo (tránh thấy đơn người khác).
     *
     * <p>Thay 2 ID này bằng ID thật khi chủ quyết định ai được phép xem all.
     * ⚠️ Nhớ sync `canSeeAllReport()` trong FE OrdersPage.jsx.
     */
    public static final Set<Long> ALLOWED_USER_IDS = Set.of(12L, 15L);

    /**
     * Role được xem báo cáo cả hệ thống (không bị filter theo seller id).
     * Chỉ SELLER thuần bị gating; mọi role khác giữ nguyên hành vi cũ.
     */
    public static final Set<Role> ALLOWED_ROLES = Set.of(
            Role.OWNER, Role.ADMIN, Role.SUPERADMIN,
            Role.SUPER_SELLER,
            Role.ACCOUNTANT, Role.SUPER_ACCOUNTANT);

    /**
     * {@code true} nếu user được xem báo cáo CẢ HỆ THỐNG (không bị filter theo
     * seller id). Khi {@code false} + user là SELLER → báo cáo chỉ lấy đơn do
     * chính họ tạo.
     *
     * <p>Luật: role khác SELLER → luôn true. SELLER → true khi id ∈ whitelist.
     */
    public static boolean canSeeAll(User u) {
        if (u == null || u.getRole() == null) return false;
        if (ALLOWED_ROLES.contains(u.getRole())) return true;
        if (u.getRole() == Role.SELLER) {
            // User.getId() trả primitive long nên không cần (và không được)
            // check null — compiler sẽ báo "!=" cannot be applied to 'long','null'.
            return ALLOWED_USER_IDS.contains(u.getId());
        }
        return false;
    }

    /**
     * Trả về id dùng để scope đơn trong báo cáo:
     * <ul>
     *   <li>{@code null} → không scope, trả về đơn của mọi user (admin/whitelist).</li>
     *   <li>id khác null → chỉ lấy đơn có {@code order.user.id == scopeId}
     *       (SELLER thuần không whitelist).</li>
     * </ul>
     */
    public static Long sellerScopeId(User u) {
        if (canSeeAll(u)) return null;
        return u != null ? u.getId() : null;
    }
}