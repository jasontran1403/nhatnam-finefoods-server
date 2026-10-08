package com.nhatnam.server.enumtype;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nhatnam.server.enumtype.Permission.*;

@RequiredArgsConstructor
public enum Role {

  UN_AUTH(Collections.emptySet()),
  SUPERADMIN(Set.of(SUPERADMIN_READ, SUPERADMIN_UPDATE, SUPERADMIN_DELETE, SUPERADMIN_CREATE)),
  OWNER(Set.of(
          OWNER_READ, OWNER_UPDATE, OWNER_DELETE, OWNER_CREATE,
          ADMIN_READ, ADMIN_UPDATE, ADMIN_DELETE, ADMIN_CREATE,
          HR_READ, HR_UPDATE, HR_CREATE, HR_DELETE
  )),
  ADMIN(Set.of(
          ADMIN_READ, ADMIN_UPDATE, ADMIN_DELETE, ADMIN_CREATE,
          ACCOUNTANT_READ, ACCOUNTANT_UPDATE, ACCOUNTANT_DELETE, ACCOUNTANT_CREATE
  )),
  ACCOUNTANT(Set.of(ACCOUNTANT_READ, ACCOUNTANT_UPDATE, ACCOUNTANT_DELETE, ACCOUNTANT_CREATE)),
  SUPER_ACCOUNTANT(Set.of(
          SUPER_ACCOUNTANT_READ, SUPER_ACCOUNTANT_UPDATE, SUPER_ACCOUNTANT_DELETE, SUPER_ACCOUNTANT_CREATE,
          ACCOUNTANT_READ, ACCOUNTANT_UPDATE, ACCOUNTANT_DELETE, ACCOUNTANT_CREATE
  )),
  WAREHOUSE(Set.of(WAREHOUSE_READ, WAREHOUSE_UPDATE, WAREHOUSE_DELETE, WAREHOUSE_CREATE)),
  SUPER_WAREHOUSE(Set.of(
          SUPER_WAREHOUSE_READ, SUPER_WAREHOUSE_UPDATE, SUPER_WAREHOUSE_DELETE, SUPER_WAREHOUSE_CREATE,
          WAREHOUSE_READ, WAREHOUSE_UPDATE, WAREHOUSE_DELETE, WAREHOUSE_CREATE
  )),
  USER(Set.of(USER_READ, USER_UPDATE, USER_DELETE, USER_CREATE)),
  SHIPPER(Set.of(SHIPPER_READ, SHIPPER_UPDATE, SHIPPER_DELETE, SHIPPER_CREATE)),
  POS(Set.of(POS_READ, POS_UPDATE, POS_DELETE, POS_CREATE)),
  SELLER(Set.of(SELLER_READ, SELLER_UPDATE, SELLER_DELETE, SELLER_CREATE)),
  SUPER_SELLER(Set.of(
          SUPER_SELLER_READ, SUPER_SELLER_UPDATE, SUPER_SELLER_DELETE, SUPER_SELLER_CREATE,
          SELLER_READ, SELLER_UPDATE, SELLER_DELETE, SELLER_CREATE
  )),
  OPERATOR(Set.of(OPERATOR_READ, OPERATOR_UPDATE, OPERATOR_DELETE, OPERATOR_CREATE)),
  FACTORY_WORKER(Set.of(
          FACTORY_WORKER_READ, FACTORY_WORKER_UPDATE,
          FACTORY_WORKER_CREATE, FACTORY_WORKER_DELETE
  )),
  SUPER_FACTORY_WORKER(Set.of(
          SUPER_FACTORY_WORKER_READ, SUPER_FACTORY_WORKER_UPDATE,
          SUPER_FACTORY_WORKER_CREATE, SUPER_FACTORY_WORKER_DELETE,
          FACTORY_WORKER_READ, FACTORY_WORKER_UPDATE,
          FACTORY_WORKER_CREATE, FACTORY_WORKER_DELETE
  )),
  // ── Kế toán kho xưởng: xác nhận nhập kho thành phẩm từ phiếu chuyển kho bán
  // thành phẩm, lập biên bản hao hụt đóng gói, chuyển kho thành phẩm → kho bán
  // hàng (Warehouse), xuất kho thành phẩm (bán tại chỗ / lý do khác). ─────────
  FACTORY_ACCOUNTANT(Set.of(
          FACTORY_ACCOUNTANT_READ, FACTORY_ACCOUNTANT_UPDATE,
          FACTORY_ACCOUNTANT_CREATE, FACTORY_ACCOUNTANT_DELETE
  )),

  // ── Nhân viên nhân sự ────────────────────────────────────────────────
  HR(Set.of(HR_READ, HR_UPDATE, HR_CREATE, HR_DELETE)),

  // ══════════════════════════════════════════════════════════════════════
  // ROLE MỚI
  // ══════════════════════════════════════════════════════════════════════

  /** Tài xế — 1 tài khoản ↔ 1 bản ghi Driver. Xem đơn đang giao + xác nhận đã giao. */
  DRIVER(Set.of(DRIVER_READ, DRIVER_UPDATE, DRIVER_CREATE, DRIVER_DELETE)),

  /** Bảo vệ (công ty). */
  SECURITY(Set.of(SECURITY_READ, SECURITY_UPDATE, SECURITY_CREATE, SECURITY_DELETE)),

  // ── Nhân sự XƯỞNG — đều có trang "Quản lý lương" ──────────────────────

  /** Bảo vệ xưởng — thưởng KPI CỐ ĐỊNH 300.000đ/tháng. */
  FACTORY_SECURITY(Set.of(
          FACTORY_SECURITY_READ, FACTORY_SECURITY_UPDATE,
          FACTORY_SECURITY_CREATE, FACTORY_SECURITY_DELETE
  )),

  /** Trợ lý kho xưởng — thưởng KPI cao hơn NV sản xuất 10%. */
  FACTORY_STAFF(Set.of(
          FACTORY_STAFF_READ, FACTORY_STAFF_UPDATE,
          FACTORY_STAFF_CREATE, FACTORY_STAFF_DELETE
  )),

  /** Nhân viên sản xuất — mốc chuẩn khi chia thưởng KPI (trọng số 1.0). */
  FACTORY_PRODUCTION_WORKER(Set.of(
          FACTORY_PRODUCTION_WORKER_READ, FACTORY_PRODUCTION_WORKER_UPDATE,
          FACTORY_PRODUCTION_WORKER_CREATE, FACTORY_PRODUCTION_WORKER_DELETE
  )),

  FACTORY_PACKAGING_WORKER(Set.of(
          FACTORY_PACKAGING_WORKER_READ, FACTORY_PACKAGING_WORKER_UPDATE,
          FACTORY_PACKAGING_WORKER_CREATE, FACTORY_PACKAGING_WORKER_DELETE
  )),

  /** Quản lý xưởng — thưởng KPI cao hơn NV sản xuất 25%. */
  FACTORY_MANAGER(Set.of(
          FACTORY_MANAGER_READ, FACTORY_MANAGER_UPDATE,
          FACTORY_MANAGER_CREATE, FACTORY_MANAGER_DELETE,
          FACTORY_WORKER_READ, FACTORY_WORKER_UPDATE,
          FACTORY_WORKER_CREATE, FACTORY_WORKER_DELETE
  )),

  /**
   * Thu mua — chỉ có DUY NHẤT trang "Danh sách yêu cầu văn phòng phẩm".
   * Quyền tối thiểu: xem tổng hợp VPP, in phiếu PDF, đặt hàng (có form giá/phí).
   * Không có mọi quyền khác trong hệ thống.
   */
  PURCHASING(Set.of(
          PURCHASING_READ, PURCHASING_UPDATE,
          PURCHASING_CREATE, PURCHASING_DELETE
  ));

  @Getter
  private final Set<Permission> permissions;

  /**
   * Tên hiển thị tiếng Việt của role — dùng cho thông báo, log, email...
   * KHÔNG dùng {@code name()} trực tiếp trong nội dung gửi tới người dùng vì
   * chuỗi thô kiểu SUPER_ACCOUNTANT rất khó đọc.
   *
   * <p>Giữ đồng bộ với nhãn ở frontend (lang-vi.json / getRoleConfig).
   */
  public String getLabel() {
    return switch (this) {
      case UN_AUTH                   -> "Chưa xác thực";
      case SUPERADMIN                -> "Quản trị hệ thống";
      case OWNER                     -> "Chủ tịch";
      case ADMIN                     -> "Giám đốc";
      case SUPER_ACCOUNTANT          -> "Kế toán trưởng";
      case ACCOUNTANT                -> "Kế toán";
      case FACTORY_ACCOUNTANT        -> "Kế toán kho xưởng";
      case HR                        -> "Nhân sự";
      case SUPER_SELLER              -> "Trưởng phòng kinh doanh";
      case SELLER                    -> "Nhân viên kinh doanh";
      case SUPER_WAREHOUSE           -> "Trưởng kho";
      case WAREHOUSE                 -> "Nhân viên kho";
      case OPERATOR                  -> "Nhân viên nhập liệu";
      case SUPER_FACTORY_WORKER      -> "Trưởng xưởng sản xuất";
      case FACTORY_WORKER            -> "Nhân viên xưởng sản xuất";
      case FACTORY_MANAGER           -> "Quản lý xưởng";
      case FACTORY_STAFF             -> "Trợ lý kho xưởng";
      case FACTORY_PRODUCTION_WORKER -> "Nhân viên sản xuất";
      case FACTORY_PACKAGING_WORKER  -> "Nhân viên đóng gói";
      case FACTORY_SECURITY          -> "Bảo vệ xưởng";
      case SECURITY                  -> "Bảo vệ";
      case DRIVER                    -> "Tài xế";
      case SHIPPER                   -> "Nhân viên giao hàng";
      case POS                       -> "Máy bán hàng";
      case USER                      -> "Người dùng";
      case PURCHASING                -> "Thu mua";
    };
  }

  public List<SimpleGrantedAuthority> getAuthorities() {
    var authorities = getPermissions()
            .stream()
            .map(permission -> new SimpleGrantedAuthority(permission.getPermission()))
            .collect(Collectors.toList());
    authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
    return authorities;
  }
}