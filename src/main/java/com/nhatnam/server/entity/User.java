package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "_user")
public class User implements UserDetails {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public long id;

  private String username;
  private String fullName;

  /**
   * MÃ NHÂN VIÊN — hiển thị trên file chấm công chung của công ty để phân biệt
   * nhanh khi hai nhân viên trùng họ tên. Nên là chuỗi ngắn, duy nhất và không
   * đổi (ví dụ "NV001"). Có thể {@code null} với tài khoản OWNER/ADMIN hoặc nhân
   * viên cũ chưa cấp mã; parser chấm công sẽ fallback về {@code fullName}.
   *
   * <p>Phase 1 (refactor tính lương 10/2026): thêm cột này để parser file chấm
   * công chung có thể match an toàn — tên có dấu dễ nhập sai, mã nhân viên thì
   * không. Khoá UNIQUE lỏng ở DB (NULL vẫn hợp lệ nhiều lần), ràng buộc duy
   * nhất chỉ áp cho các giá trị khác NULL — xem {@code uk_user_employee_code}.
   */
  @Column(name = "employee_code", length = 40, unique = true)
  private String employeeCode;

  private String password;
  private String email;
  private String phoneNumber;
  private long timeCreate;
  private boolean isLockAccount;

  // ══════════════════════════════════════════════════════════════════════════
  // SOFT DELETE
  // ══════════════════════════════════════════════════════════════════════════
  /**
   * ĐÃ XOÁ MỀM.
   *
   * <p>Khi xoá 1 nhân viên:
   * <ul>
   *   <li>{@code deleted = true} và {@code isLockAccount = true} → không đăng nhập được,
   *       và bị loại khỏi mọi truy vấn thông báo (đều lọc {@code isLockAccount = false}).</li>
   *   <li>Các trường UNIQUE ({@code username}, {@code email}, {@code phoneNumber}) được
   *       ĐỔI TÊN thành dạng {@code SOFT_DELETED_{id}_{giá trị cũ}} → có thể tạo lại
   *       nhân viên mới với đúng username/email/SĐT cũ.</li>
   *   <li>Giá trị gốc được giữ lại ở {@code originalUsername/Email/PhoneNumber} để tra cứu.</li>
   * </ul>
   *
   * <p>KHÔNG xoá cứng vì user còn được tham chiếu ở đơn hàng, phiếu kho, log… →
   * xoá cứng sẽ vỡ khoá ngoại và mất lịch sử.
   */
  @Column(name = "deleted", nullable = false)
  @Builder.Default
  private boolean deleted = false;

  @Column(name = "deleted_at")
  private Long deletedAt;

  /** Tên người thực hiện xoá (snapshot) */
  @Column(name = "deleted_by", length = 150)
  private String deletedBy;

  /** Giá trị GỐC trước khi bị gắn tiền tố SOFT_DELETED_ */
  @Column(name = "original_username", length = 150)
  private String originalUsername;

  @Column(name = "original_email", length = 190)
  private String originalEmail;

  @Column(name = "original_phone_number", length = 40)
  private String originalPhoneNumber;

  /** Tiền tố gắn vào các field unique khi xoá mềm. */
  public static final String SOFT_DELETED_PREFIX = "SOFT_DELETED_";

  private boolean mfaEnabled;
  private String secret;

  /** Bộ phận (VD: Kinh doanh, Kế toán, …) */
  private String department;

  /** Phòng ban (VD: Phòng Kinh doanh 1, Phòng Kế toán tổng hợp, …) */
  private String division;

  /** Chức vụ / vị trí công việc */
  private String position;

  // ══════════════════════════════════════════════════════════════════════════
  // THÔNG TIN NGÂN HÀNG — dùng khi xuất file chi lương gửi ngân hàng
  // ══════════════════════════════════════════════════════════════════════════
  /**
   * SỐ TÀI KHOẢN NGÂN HÀNG của nhân viên.
   *
   * <p>Chỉ dùng cho việc chi lương qua NH — điền vào cột "SỐ TÀI KHOẢN" của
   * file "DANH SÁCH CHI LƯƠNG THÁNG mm/yyyy" ({@code BankPaymentExportService}).
   * Không dùng cho bất kỳ tính năng đăng nhập / xác thực / thu chi nào khác.
   *
   * <p>Nullable — nhân viên chưa khai báo thì ô số tài khoản trong file để
   * trống, HR điền tay trước khi gửi ngân hàng.
   */
  @Column(name = "bank_account_number", length = 50)
  private String bankAccountNumber;

  /**
   * TÊN NGÂN HÀNG (mã / tên viết tắt như "VIETINBANK CN2", "VCB", "MB", …).
   * Nullable — xem {@link #bankAccountNumber}.
   */
  @Column(name = "bank_name", length = 100)
  private String bankName;

  /**
   * ĐANG NGHỈ THAI SẢN.
   *
   * <p>Trạng thái tạm dừng nhận lương KHÁC HẲN xoá / khoá tài khoản: nhân viên
   * vẫn còn hồ sơ, vẫn đăng nhập được (nếu quản lý không khoá), nhưng bị
   * loại khỏi file "DANH SÁCH CHI LƯƠNG" gửi ngân hàng
   * ({@code BankPaymentExportService}). Khi nghỉ xong, HR chỉ cần bỏ cờ này,
   * nhân viên tự động quay lại danh sách chi lương của tháng tiếp theo.
   *
   * <p>Cột riêng thay vì tái sử dụng {@code isLockAccount} vì hai mục đích
   * khác nhau — khoá tài khoản chặn đăng nhập, nghỉ thai sản chỉ tạm dừng
   * chuyển khoản.
   */
  @Column(name = "on_maternity_leave", nullable = false, columnDefinition = "bit(1) default 0")
  @Builder.Default
  private boolean onMaternityLeave = false;

  /**
   * NGÀY VÀO LÀM VIỆC (epoch millis, mốc 00:00 theo giờ VN) — căn cứ tính THÂM NIÊN.
   *
   * <p>Do SUPER_ACCOUNTANT/HR nhập ở modal "Bộ phận / Chức vụ". Thâm niên (số năm
   * TRÒN) được chốt lại mỗi khi OWNER bấm "Hoàn tất" phiếu lương của tháng, và
   * quyết định mức phụ cấp thâm niên — xem
   * {@link com.nhatnam.server.utils.SeniorityCalculator}.
   *
   * <p><b>CỐ Ý ĐỂ NULLABLE.</b> Nhân viên cũ chưa ai nhập ngày vào làm; null được
   * hiểu là "chưa khai báo" ⇒ thâm niên 0 năm ⇒ phụ cấp 0đ, thay vì đoán bừa một
   * ngày rồi trả phụ cấp sai.
   */
  @Column(name = "work_start_date")
  private Long workStartDate;

  /**
   * NGÀY THÁNG NĂM SINH của nhân viên (epoch millis, mốc 00:00 giờ VN).
   *
   * <p>Dùng để TỔ CHỨC SINH NHẬT: màn hình "Quản lý nhân viên" của OWNER/ADMIN hiển thị
   * cột ngày sinh, có nút sắp xếp theo "sinh nhật gần đến nhất" và tô màu những người
   * có sinh nhật TRONG THÁNG mà CHƯA qua ngày.
   *
   * <p>Nullable: nhân viên cũ chưa ai nhập. Null được hiểu là "chưa khai báo" ⇒ luôn xếp
   * cuối danh sách khi sort, không tô màu — thay vì đoán một ngày rồi tổ chức sai.
   */
  @Column(name = "date_of_birth")
  private Long dateOfBirth;

  @Column(name = "prior_year_leave_balance")
  private Double priorYearLeaveBalance;

  @Column(name = "bonus_leave_days")
  private Double bonusLeaveDays;

  /**
   * OFFSET cho cột "phép năm hiện tại" ở bảng "Quản lý phép".
   *
   * <p>Cột đó mặc định = {@code LeaveBalanceCalculator.entitledDaysFor()} —
   * tự cộng dồn 1 ngày mỗi tháng đủ. Khi OWNER cần đè giá trị khác (vd. migrate
   * từ hệ thống cũ), hệ thống lưu OFFSET = giá_trị_mong_muốn − giá_trị_auto.
   *
   * <p>Cách này để auto formula VẪN tự tăng đúng theo tháng, offset giữ nguyên,
   * hiển thị tự lên 1 mỗi đầu tháng mà KHÔNG cần cronjob.
   *
   * <p>Ví dụ: 24/9/2026 auto = 8, OWNER nhập 8 → offset = 0. Nếu OWNER nhập 5 →
   * offset = −3. 1/10/2026 auto = 9 → hiển thị = 9 + (−3) = 6.
   *
   * <p>Đơn vị: ngày (Double). Có thể có phần thập phân (nửa buổi = 0.5).
   */
  @Column(name = "entitled_offset_days")
  private Double entitledOffsetDays;

  // ══════════════════════════════════════════════════════════════════════════
  // PASSCODE XEM LƯƠNG (6 số)
  // ══════════════════════════════════════════════════════════════════════════
  /**
   * MẬT KHẨU XEM LƯƠNG — BCrypt hash của 6 chữ số.
   *
   * <p>{@code null} = nhân viên CHƯA từng đổi ⇒ hệ thống hiểu là passcode mặc
   * định {@code 000000}. Cố ý KHÔNG ghi sẵn hash của "000000" cho toàn bộ nhân
   * viên hiện có: để phân biệt được "chưa đặt bao giờ" (cần nhắc user đổi) với
   * "đã tự đặt". Xem {@code PayrollPasscodeService#DEFAULT_PASSCODE}.
   *
   * <p>KHÔNG dùng chung với {@link #password} (mật khẩu đăng nhập) — mục đích
   * khác nhau: passcode chỉ chắn màn hình lương, đổi được độc lập.
   */
  @Column(name = "payroll_passcode", length = 100)
  private String payrollPasscode;

  /** Số lần nhập SAI liên tiếp. Reset về 0 khi nhập đúng hoặc admin mở khoá. */
  @Column(name = "payroll_passcode_fail_count", nullable = false, columnDefinition = "int default 0")
  @Builder.Default
  private int payrollPasscodeFailCount = 0;

  /**
   * ĐÃ KHOÁ XEM LƯƠNG — sai quá 3 lần.
   *
   * <p>Khi {@code true}: KHÔNG cho nhập passcode nữa, KHÔNG cho tự đổi passcode,
   * mọi request phiếu lương bị chặn ở backend. Chỉ OWNER/ADMIN/HR mở khoá được.
   */
  @Column(name = "payroll_passcode_locked", nullable = false, columnDefinition = "bit(1) default 0")
  @Builder.Default
  private boolean payrollPasscodeLocked = false;

  /** Thời điểm bị khoá (epoch millis) — để admin biết khoá từ bao giờ. */
  @Column(name = "payroll_passcode_locked_at")
  private Long payrollPasscodeLockedAt;

  /**
   * HẠN CỦA "VÉ" XEM LƯƠNG (epoch millis).
   *
   * <p>Nhập đúng passcode ⇒ set = now + 15 phút. Backend chỉ trả phiếu lương khi
   * còn hạn. Đây là lớp chặn THẬT — frontend chỉ là giao diện; nếu chỉ chặn ở FE
   * thì gọi thẳng API vẫn lấy được lương của chính mình.
   */
  @Column(name = "payroll_access_expires_at")
  private Long payrollAccessExpiresAt;

  @Enumerated(EnumType.STRING)
  private Role role;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "_user_roles", joinColumns = @JoinColumn(name = "user_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "role")
  @Builder.Default
  private Set<Role> roles = new HashSet<>();

  // ══════════════════════════════════════════════════════════════════════════
  // ROLE NHẬN LƯƠNG
  // ══════════════════════════════════════════════════════════════════════════
  /**
   * ROLE DÙNG ĐỂ TÍNH LƯƠNG — role "chính thức" của nhân viên.
   *
   * <p>Một người có thể KIÊM nhiều role nhưng chỉ nhận lương theo MỘT role.
   * Ví dụ Trần Mộng Thuỳ có {@code SELLER} + {@code WAREHOUSE} (quản lý 2 kho)
   * nhưng lương chính nhận từ {@code SELLER} → {@code payrollRole = SELLER}.
   *
   * <p>Bỏ trống thì hệ thống TỰ SUY RA theo thứ tự ưu tiên khai báo trong
   * {@link com.nhatnam.server.enumtype.PayrollDepartment}:
   * FACTORY_* → ACCOUNTANT → SELLER → WAREHOUSE → DRIVER.
   *
   * <p>Migration đã set sẵn {@code payroll_role = role} cho toàn bộ nhân viên
   * hiện có, sau đó nâng lên theo role phụ trong bảng {@code _user_roles}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "payroll_role", length = 40)
  private Role payrollRole;

  /**
   * NHẬN KPI hay không — dùng cho phòng Kế toán.
   *
   * <p>Nhân viên tổng hợp thuộc phòng kế toán nhưng không làm việc kế toán
   * có thể được đặt {@code false} để không được chia KPI/bonus khi tính lương.
   * {@code null} hoặc {@code true} = có nhận (mặc định).
   */
  @Column(name = "receive_kpi")
  private Boolean receiveKpi;

  /**
   * NHẬN BONUS hay không — dùng cho phòng Kế toán.
   *
   * <p>Tương tự {@link #receiveKpi} nhưng kiểm soát riêng bonus.
   * {@code null} hoặc {@code true} = có nhận (mặc định).
   */
  @Column(name = "receive_bonus")
  private Boolean receiveBonus;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id")
  private Warehouse warehouse;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
          name = "_user_warehouses",
          joinColumns = @JoinColumn(name = "user_id"),
          inverseJoinColumns = @JoinColumn(name = "warehouse_id")
  )
  @Builder.Default
  private Set<Warehouse> userWarehouses = new HashSet<>();

  public Set<Warehouse> getAllWarehouses() {
    if (userWarehouses != null && !userWarehouses.isEmpty()) return userWarehouses;
    if (warehouse != null) return new HashSet<>(Set.of(warehouse));
    return new HashSet<>();
  }

  @OneToMany(mappedBy = "user")
  private List<Token> tokens;

  public Set<Role> getAllRoles() {
    Set<Role> all = new HashSet<>(roles != null ? roles : Collections.emptySet());
    if (role != null) all.add(role);
    return all;
  }

  public boolean hasMultipleRoles() {
    return getAllRoles().size() > 1;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return role != null ? role.getAuthorities() : Collections.emptyList();
  }

  @Override public String getPassword() { return password; }
  @Override public String getUsername() { return username; }
  @Override public boolean isAccountNonExpired() { return true; }
  @Override public boolean isAccountNonLocked() { return true; }
  @Override public boolean isCredentialsNonExpired() { return true; }
  @Override public boolean isEnabled() { return true; }
}