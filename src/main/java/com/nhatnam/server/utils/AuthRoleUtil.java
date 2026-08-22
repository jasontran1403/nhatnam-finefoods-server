package com.nhatnam.server.utils;

import com.nhatnam.server.enumtype.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lấy ROLE ĐANG THAO TÁC của request hiện tại — tức role mà người dùng đang
 * "đứng" khi gọi API, KHÔNG phải cột {@code user.role} trong DB.
 *
 * <p>Vì sao cần: một tài khoản có thể mang nhiều role (ví dụ vừa
 * SUPER_FACTORY_WORKER vừa HR). {@code user.getRole()} chỉ trả về role chính
 * lưu trong DB nên dễ hiển thị sai người thực hiện. Khi người dùng đổi role
 * (API switch-role), JWT mang claim {@code selected_role} và
 * {@link com.nhatnam.server.config.JwtAuthenticationFilter} sẽ set authorities
 * CHỈ gồm role đó → đọc authorities là ra đúng role đang thao tác.
 *
 * <p>Nếu JWT không có {@code selected_role} thì authorities chứa toàn bộ role
 * của tài khoản; lúc đó {@link #actingRole} chọn theo thứ tự ưu tiên mà nơi gọi
 * truyền vào.
 */
public final class AuthRoleUtil {

    private AuthRoleUtil() {}

    /** Toàn bộ role đọc được từ authorities (các quyền dạng {@code ROLE_XXX}). */
    public static Set<Role> rolesOf(Authentication auth) {
        Set<Role> roles = new LinkedHashSet<>();
        if (auth == null || auth.getAuthorities() == null) return roles;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String v = a.getAuthority();
            if (v == null || !v.startsWith("ROLE_")) continue;
            try {
                roles.add(Role.valueOf(v.substring("ROLE_".length())));
            } catch (IllegalArgumentException ignored) { /* authority lạ → bỏ qua */ }
        }
        return roles;
    }

    /** Như trên nhưng tự lấy Authentication từ SecurityContext hiện tại. */
    public static Set<Role> currentRoles() {
        return rolesOf(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Role đang thao tác, giới hạn trong danh sách được phép.
     *
     * @param allowedInPriorityOrder các role được phép làm hành động này, xếp theo
     *                               ĐỘ ƯU TIÊN giảm dần. Chỉ dùng tới thứ tự này khi
     *                               JWT không có selected_role và tài khoản mang
     *                               nhiều role hợp lệ cùng lúc.
     * @return role khớp đầu tiên, hoặc {@code null} nếu không role nào được phép.
     */
    public static Role actingRole(Authentication auth, List<Role> allowedInPriorityOrder) {
        Set<Role> held = rolesOf(auth);
        if (held.isEmpty()) return null;
        for (Role r : allowedInPriorityOrder) {
            if (held.contains(r)) return r;
        }
        return null;
    }

    /** Như trên nhưng tự lấy Authentication từ SecurityContext hiện tại. */
    public static Role currentActingRole(List<Role> allowedInPriorityOrder) {
        return actingRole(SecurityContextHolder.getContext().getAuthentication(), allowedInPriorityOrder);
    }
}