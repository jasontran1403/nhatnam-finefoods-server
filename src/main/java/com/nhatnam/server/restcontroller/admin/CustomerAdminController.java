package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.customer.BulkUpdateActiveRequest;
import com.nhatnam.server.dto.customer.BulkUpdateDiscountRequest;
import com.nhatnam.server.dto.customer.CustomerDto;
import com.nhatnam.server.dto.customer.UpdateDiscountRequest;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.CustomerReceiverInfo;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.CustomerReceiverInfoRepository;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.admin.CustomerAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Log4j2
@RequestMapping("/api/admin/customers")
@RequiredArgsConstructor
public class CustomerAdminController {

    private final CustomerAdminService customerService;
    private final OrderRepository      orderRepository;
    private final CustomerRepository   customerRepository;
    private final UserRepository       userRepository;
    private final PasswordEncoder      passwordEncoder;
    private final CustomerReceiverInfoRepository receiverInfoRepository;

    @Value("${application.security.jwt.secret-key}")
    private String jwtSecretKey;

    private final ConcurrentHashMap<String, Long> usedExportTokens = new ConcurrentHashMap<>();
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX_CUSTOMER = "export-customer:";

    private String _makeToken(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(jwtSecretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((TOKEN_PREFIX_CUSTOMER + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { throw new RuntimeException("Không thể tạo token"); }
    }

    private String _generateExportToken() {
        String ts = String.valueOf(System.currentTimeMillis());
        return ts + ":" + _makeToken(ts);
    }

    private void _validateAndConsumeToken(String token) {
        if (token == null || token.isBlank())
            throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
        String[] parts = token.split(":", 2);
        if (parts.length != 2 || !_makeToken(parts[0]).equals(parts[1]))
            throw new IllegalStateException("File không hợp lệ: mã xác thực không khớp. Vui lòng Export lại file mới.");
        if (usedExportTokens.containsKey(token))
            throw new IllegalStateException("File này đã được import rồi. Vui lòng Export file mới để import lại.");
        usedExportTokens.put(token, System.currentTimeMillis());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> softDeleteCustomer(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        String password = body.get("password");
        if (password == null || password.isBlank())
            return ApiResponse.error("Vui lòng nhập mật khẩu để xác nhận");

        User currentUser = (User) authentication.getPrincipal();
        if (!passwordEncoder.matches(password, currentUser.getPassword()))
            return ApiResponse.error("Mật khẩu không đúng");

        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng #" + id));

        if (customer.getDeletedAt() != null)
            return ApiResponse.error("Khách hàng này đã bị xóa trước đó");

        String prefix = "SOFTDELETED_" + id + "_";

        // ── Customer text fields ──────────────────────────────────────
        if (customer.getPhone() != null)
            customer.setPhone(prefix + customer.getPhone());
        if (customer.getName() != null)
            customer.setName(prefix + customer.getName());
        if (customer.getEmail() != null)
            customer.setEmail(prefix + customer.getEmail());
        if (customer.getCustomerCode() != null)
            customer.setCustomerCode(prefix + customer.getCustomerCode());
        if (customer.getCompanyName() != null)
            customer.setCompanyName(prefix + customer.getCompanyName());
        if (customer.getTaxCode() != null)
            customer.setTaxCode(prefix + customer.getTaxCode());
        if (customer.getCompanyPhone() != null)
            customer.setCompanyPhone(prefix + customer.getCompanyPhone());
        if (customer.getCompanyAddress() != null)
            customer.setCompanyAddress(prefix + customer.getCompanyAddress());
        if (customer.getContactName() != null)
            customer.setContactName(prefix + customer.getContactName());

        customer.setDeletedAt(System.currentTimeMillis());
        customerRepository.save(customer);

        // ── ReceiverInfos: prefix để giải phóng unique (customer_id, phone/address) ──
        List<CustomerReceiverInfo> receivers = receiverInfoRepository.findByCustomerId(id);
        for (CustomerReceiverInfo r : receivers) {
            String rPrefix = "SOFTDELETED_" + id + "_";
            if (r.getReceiverName() != null)
                r.setReceiverName(rPrefix + r.getReceiverName());
            if (r.getReceiverPhone() != null)
                r.setReceiverPhone(rPrefix + r.getReceiverPhone());
            if (r.getReceiverAddress() != null)
                r.setReceiverAddress(rPrefix + r.getReceiverAddress());
        }
        if (!receivers.isEmpty())
            receiverInfoRepository.saveAll(receivers);

        return ApiResponse.ok("Đã xóa khách hàng thành công");
    }

    @GetMapping
    public ApiResponse<PageResponse<CustomerDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Customer.CustomerType type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Long sellerId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(customerService.list(q, type, isActive, sellerId, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(customerService.getById(id));
    }

    /**
     * Admin/Owner tạo khách hàng mới.
     * KPI tính chung toàn phòng SALE, ai cũng tạo được đơn.
     */
    @PostMapping
    public ApiResponse<CustomerDto> createCustomer(
            @RequestBody com.nhatnam.server.dto.request.AdminCreateCustomerRequest req) {
        return ApiResponse.ok("Tạo khách hàng thành công", customerService.createCustomer(req));
    }

    /**
     * Admin/Owner cập nhật thông tin khách hàng (bao gồm pricingType, email nullable).
     */
    @PutMapping("/{id}")
    public ApiResponse<CustomerDto> updateCustomer(
            @PathVariable Long id,
            @RequestBody com.nhatnam.server.dto.request.AdminCreateCustomerRequest req) {
        return ApiResponse.ok("Cập nhật thành công", customerService.updateCustomer(id, req));
    }

    /** Gán seller cho khách hàng — sellerId = null để bỏ gán */
    @PutMapping("/{id}/assign-seller")
    public ApiResponse<CustomerDto> assignSeller(
            @PathVariable Long id,
            @RequestParam(required = false) Long sellerId) {

        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng #" + id));

        if (sellerId == null) {
            // Bỏ gán
            customer.setAssignedSeller(null);
            customer.setAssignedSellerName(null);
        } else {
            User seller = userRepository.findById(sellerId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy user #" + sellerId));
            customer.setAssignedSeller(seller);
            // Snapshot tên hiện tại — dùng để hiển thị
            customer.setAssignedSellerName(
                    seller.getFullName() != null ? seller.getFullName() : seller.getUsername()
            );
        }
        // createdBySeller KHÔNG thay đổi
        customerRepository.save(customer);

        return ApiResponse.ok("Gán seller thành công", customerService.getById(id));
    }

    /** Tìm kiếm seller để gán — hỗ trợ có/không dấu qua LIKE */
    @GetMapping("/sellers/search")
    public ApiResponse<List<Map<String, Object>>> searchSellers(
            @RequestParam(required = false, defaultValue = "") String q) {

        // Lấy TẤT CẢ users, filter những ai có SELLER hoặc SUPER_SELLER
        // trong cả role đơn lẫn roles collection (_user_roles)
        List<User> allUsers = userRepository.findAll();

        String ql = q.toLowerCase();

        List<Map<String, Object>> result = allUsers.stream()
                .filter(u -> {
                    // Kiểm tra cả role đơn và roles collection
                    Set<Role> allRoles = u.getAllRoles();
                    return allRoles.contains(Role.SELLER);
                })
                .filter(u -> ql.isBlank()
                        || (u.getFullName() != null && u.getFullName().toLowerCase().contains(ql))
                        || (u.getUsername() != null && u.getUsername().toLowerCase().contains(ql)))
                .sorted((a, b) -> {
                    String na = a.getFullName() != null ? a.getFullName() : a.getUsername();
                    String nb = b.getFullName() != null ? b.getFullName() : b.getUsername();
                    return na.compareToIgnoreCase(nb);
                })
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",       u.getId());
                    m.put("fullName", u.getFullName() != null ? u.getFullName() : u.getUsername());
                    m.put("username", u.getUsername());
                    // Hiển thị role ưu tiên SUPER_SELLER
                    String displayRole = u.getAllRoles().contains(Role.SUPER_SELLER)
                            ? "SUPER_SELLER" : "SELLER";
                    m.put("role", displayRole);
                    return m;
                })
                .toList();

        return ApiResponse.ok(result);
    }

    @PutMapping("/{id}/discount")
    public ApiResponse<CustomerDto> updateDiscount(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateDiscountRequest req) {
        return ApiResponse.ok("Cập nhật chiết khấu thành công",
                customerService.updateDiscount(id, req.getDiscountRate()));
    }

    @PutMapping("/bulk-discount")
    public ApiResponse<Map<String, Object>> bulkDiscount(@Valid @RequestBody BulkUpdateDiscountRequest req) {
        int updated = customerService.bulkUpdateDiscount(req.getCustomerIds(), req.getDiscountRate());
        return ApiResponse.ok("Cập nhật chiết khấu hàng loạt thành công", Map.of("updated", updated));
    }

    @PutMapping("/{id}/active")
    public ApiResponse<CustomerDto> setActive(@PathVariable Long id,
                                              @RequestParam Boolean value) {
        String msg = Boolean.TRUE.equals(value) ? "Mở bán cho khách hàng" : "Khóa bán khách hàng";
        return ApiResponse.ok(msg, customerService.setActive(id, value));
    }

    @PutMapping("/bulk-active")
    public ApiResponse<Map<String, Object>> bulkActive(@Valid @RequestBody BulkUpdateActiveRequest req) {
        int updated = customerService.bulkSetActive(req.getCustomerIds(), req.getIsActive());
        return ApiResponse.ok("Cập nhật trạng thái hàng loạt thành công", Map.of("updated", updated));
    }

    @PutMapping("/{id}/debt-days")
    public ApiResponse<CustomerDto> updateDebtDays(
            @PathVariable Long id,
            @RequestParam @Min(0) @Max(365) Integer days) {
        return ApiResponse.ok("Cập nhật số ngày công nợ thành công",
                customerService.updateDebtDays(id, days));
    }

    @GetMapping("/{customerId}/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerOrders(
            @PathVariable Long customerId) {
        try {
            List<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);

            long totalOrders     = orders.size();
            long completedOrders = orders.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).count();
            long activeOrders    = orders.stream()
                    .filter(o -> o.getStatus() != OrderStatus.COMPLETED
                            && o.getStatus() != OrderStatus.CANCELLED
                            && o.getStatus() != OrderStatus.FAILED).count();

            BigDecimal totalAmount = orders.stream()
                    .map(Order::getFinalAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal completedAmount = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                    .map(Order::getFinalAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pendingPaymentAmount = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT)
                    .map(Order::getFinalAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng: " + customerId));

            ZoneId tz = ZoneId.of("Asia/Ho_Chi_Minh");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            List<Map<String, Object>> orderList = orders.stream().map(o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",            o.getId());
                m.put("orderCode",     o.getOrderCode());
                m.put("status",        o.getStatus());
                m.put("paymentStatus", o.getPaymentStatus());
                m.put("paymentMethod", o.getPaymentMethod());
                m.put("finalAmount",   o.getFinalAmount());
                m.put("orderedByName", o.getOrderedByName());
                m.put("createdAt",     o.getCreatedAt());

                int debtDays = o.getDebtDays();
                if (debtDays > 0 && "DEBT".equals(o.getPaymentMethod())
                        && o.getStatus() == OrderStatus.PENDING_PAYMENT
                        && o.getPendingPaymentAt() != null) {
                    LocalDate baseDay  = Instant.ofEpochMilli(o.getPendingPaymentAt()).atZone(tz).toLocalDate();
                    LocalDate deadline = baseDay.plusDays(1).plusDays(debtDays);
                    m.put("paymentDeadline",       deadline.format(fmt));
                    m.put("paymentDeadlineMillis", deadline.atStartOfDay(tz).toInstant().toEpochMilli());
                } else {
                    m.put("paymentDeadline",       null);
                    m.put("paymentDeadlineMillis", null);
                }
                return m;
            }).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("customerId",           customerId);
            result.put("customerName",         customer.getCustomerType() == Customer.CustomerType.COMPANY
                    ? customer.getCompanyName() : customer.getName());
            result.put("customerPhone",        customer.getPhone());
            result.put("customerCode",         customer.getCustomerCode());
            result.put("customerType",         customer.getCustomerType());
            result.put("debtDays",             customer.getDebtDays());
            result.put("totalOrders",          totalOrders);
            result.put("completedOrders",      completedOrders);
            result.put("activeOrders",         activeOrders);
            result.put("totalAmount",          totalAmount);
            result.put("completedAmount",      completedAmount);
            result.put("pendingPaymentAmount", pendingPaymentAmount);
            result.put("orders",               orderList);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>error(e.getMessage()));
        } catch (Exception e) {
            log.error("[ADMIN] getCustomerOrders error", e);
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>error(e.getMessage()));
        }
    }

    // ── Export Customers to Excel ─────────────────────────────────────────────
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ResponseEntity<?> exportCustomers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Customer.CustomerType type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Long sellerId) {
        try {
            Pageable all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
            List<Customer> customers = customerRepository.searchAdmin(q, type, isActive, sellerId, all).getContent();

            // Lấy danh sách seller để dùng cho dropdown validation
            List<User> sellers = userRepository.findAll().stream()
                    .filter(u -> u.getAllRoles().contains(com.nhatnam.server.enumtype.Role.SELLER))
                    .sorted((a, b) -> {
                        String na = a.getFullName() != null ? a.getFullName() : a.getUsername();
                        String nb = b.getFullName() != null ? b.getFullName() : b.getUsername();
                        return na.compareToIgnoreCase(nb);
                    }).toList();

            byte[] bytes = _buildCustomerExcel(customers, sellers);
            String now = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));
            String filename = "danh-sach-khach-hang-" + now + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[ADMIN] exportCustomers error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Import Customers from Excel ───────────────────────────────────────────
    @org.springframework.web.bind.annotation.PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importCustomers(
            @RequestParam("file") MultipartFile file) {
        try {
            List<User> sellers = userRepository.findAll().stream()
                    .filter(u -> u.getAllRoles().contains(com.nhatnam.server.enumtype.Role.SELLER))
                    .toList();
            Map<String, Long> sellerNameToId = sellers.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            u -> (u.getFullName() != null ? u.getFullName() : u.getUsername()).trim().toLowerCase(),
                            User::getId, (a, b) -> a));

            int updated = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                         new org.apache.poi.xssf.usermodel.XSSFWorkbook(file.getInputStream())) {

                // Xác thực token từ sheet __meta
                try {
                    int metaIdx = wb.getSheetIndex("__meta");
                    if (metaIdx < 0) throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
                    org.apache.poi.ss.usermodel.Row metaRow = wb.getSheetAt(metaIdx).getRow(0);
                    String token = metaRow != null ? _cellStr(metaRow, 0) : null;
                    _validateAndConsumeToken(token);
                } catch (IllegalStateException ex) {
                    return ResponseEntity.ok(ApiResponse.error(ex.getMessage()));
                }

                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
                // Layout cột:
                // 0=ID  1=Mã KH  2=Tên KH/CT  3=Người LH  4=SĐT GH  5=ĐC GH
                // 6=Email  7=Phân loại  8=Loại giá  9=NV KD  10=CK%  11=CN  12=TT
                // Row 0=title, 1=info, 2=note, 3=header → data từ row 4
                for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String idStr = _cellStr(row, 0);
                    if (idStr == null || idStr.isBlank()) continue;

                    try {
                        Long customerId = Long.parseLong(idStr.trim());
                        Customer c = customerRepository.findById(customerId)
                                .orElseThrow(() -> new IllegalArgumentException("Khách hàng ID=" + customerId + " không tồn tại"));

                        // ── Đọc tất cả các cột ──────────────────────────────────────
                        // 0=ID 1=Mã KH 2=Tên KH/CT 3=Người LH 4=SĐT GH 5=ĐC GH 6=Tên NR
                        // 7=Email 8=Phân loại 9=Loại giá 10=NV KD 11=CK% 12=CN 13=TT
                        String nameStr        = _cellStr(row, 2);  // Tên KH / Tên công ty
                        String contactStr     = _cellStr(row, 3);  // Người LH (= tên cá nhân khi export cá nhân)
                        String phoneGhStr     = _cellStr(row, 4);  // SĐT giao hàng (receiverInfo default)
                        String addrStr        = _cellStr(row, 5);  // Địa chỉ giao hàng (receiverInfo default)
                        String receiverNmStr  = _cellStr(row, 6);  // Tên người nhận (receiverInfo default)
                        String emailStr       = _cellStr(row, 7);  // Email
                        String typeStr        = _cellStr(row, 8);  // Phân loại
                        String priceStr       = _cellStr(row, 9);  // Loại giá
                        String sellerStr      = _cellStr(row, 10); // NV KD
                        String discStr        = _cellStr(row, 11); // CK%
                        String debtStr        = _cellStr(row, 12); // CN
                        String statusStr      = _cellStr(row, 13); // Trạng thái

                        // ── Bước 1: Xử lý đổi loại khách (phải làm trước) ───────────
                        // Xác định loại mới từ file (null = không thay đổi)
                        Customer.CustomerType newType = null;
                        if (typeStr != null && !typeStr.isBlank()) {
                            if ("Công ty".equalsIgnoreCase(typeStr.trim()))
                                newType = Customer.CustomerType.COMPANY;
                            else if ("Cá nhân".equalsIgnoreCase(typeStr.trim()))
                                newType = Customer.CustomerType.RETAIL;
                        }

                        if (newType != null && newType != c.getCustomerType()) {
                            // Đổi loại → clear field của loại CŨ không dùng ở loại MỚI
                            if (newType == Customer.CustomerType.COMPANY) {
                                // Cá nhân → Công ty: clear name cá nhân
                                c.setName(null);
                            } else {
                                // Công ty → Cá nhân: clear toàn bộ field công ty
                                c.setCompanyName(null);
                                c.setTaxCode(null);
                                c.setCompanyPhone(null);
                                c.setCompanyAddress(null);
                                c.setContactName(null);
                            }
                            c.setCustomerType(newType);
                        }
                        // Sau bước 1, c.getCustomerType() đã phản ánh loại ĐÍCH

                        boolean isCompany = c.getCustomerType() == Customer.CustomerType.COMPANY;

                        // ── Bước 2: Tên (cột 2) ─────────────────────────────────────
                        // Công ty  → companyName
                        // Cá nhân  → name
                        if (nameStr != null && !nameStr.isBlank()) {
                            if (isCompany) c.setCompanyName(nameStr.trim());
                            else           c.setName(nameStr.trim());
                        }

                        // ── Bước 3: Người liên hệ (cột 3) ───────────────────────────
                        // Export: cá nhân ghi name vào cột này, công ty ghi contactName.
                        // Import: công ty → contactName; cá nhân → name (nếu cột 2 trống thì dùng làm fallback)
                        if (contactStr != null && !contactStr.isBlank()) {
                            if (isCompany) {
                                c.setContactName(contactStr.trim());
                            } else {
                                // Cá nhân: cột 3 = tên → chỉ cập nhật nếu cột 2 chưa cập nhật
                                if (nameStr == null || nameStr.isBlank())
                                    c.setName(contactStr.trim());
                            }
                        }

                        // ── Bước 4: Email (cột 6) ───────────────────────────────────
                        if (emailStr != null) {
                            c.setEmail(emailStr.isBlank() ? null : emailStr.trim());
                        }

                        // ── Bước 5: Loại giá ────────────────────────────────────────
                        if (priceStr != null && !priceStr.isBlank()) {
                            boolean isWholesale = priceStr.trim().contains("sỉ")
                                    || priceStr.trim().contains("Sỉ");
                            c.setPricingType(isWholesale
                                    ? Customer.PricingType.WHOLESALE_PRICE
                                    : Customer.PricingType.RETAIL_PRICE);
                        }

                        // ── Bước 6: NV Kinh doanh (trống = bỏ gán) ──────────────────
                        if (sellerStr != null) {
                            if (sellerStr.isBlank()) {
                                c.setAssignedSeller(null);
                                c.setAssignedSellerName(null);
                            } else {
                                Long sellId = sellerNameToId.get(sellerStr.trim().toLowerCase());
                                if (sellId != null) {
                                    User seller = userRepository.findById(sellId).orElse(null);
                                    if (seller != null) {
                                        c.setAssignedSeller(seller);
                                        c.setAssignedSellerName(seller.getFullName() != null
                                                ? seller.getFullName() : seller.getUsername());
                                    }
                                }
                            }
                        }

                        // ── Bước 7: Chiết khấu ──────────────────────────────────────
                        if (discStr != null && !discStr.isBlank()) {
                            try { c.setDiscountRate((int) Double.parseDouble(
                                    discStr.trim().replace("%", "").trim())); }
                            catch (Exception ignored) {}
                        }

                        // ── Bước 8: Công nợ ─────────────────────────────────────────
                        if (debtStr != null && !debtStr.isBlank()) {
                            try { c.setDebtDays((int) Double.parseDouble(debtStr.trim())); }
                            catch (Exception ignored) {}
                        }

                        // ── Bước 9: Trạng thái ──────────────────────────────────────
                        if (statusStr != null && !statusStr.isBlank()) {
                            c.setIsActive(!"Đã khóa".equalsIgnoreCase(statusStr.trim()));
                        }

                        c.setUpdatedAt(System.currentTimeMillis());
                        customerRepository.save(c);

                        // ── Bước 10: Cập nhật receiverInfo default (SĐT GH + ĐC GH + Tên NR) ─
                        // Chỉ cập nhật nếu ít nhất 1 trong 3 cột có giá trị
                        boolean hasPhone       = phoneGhStr    != null && !phoneGhStr.isBlank();
                        boolean hasAddr        = addrStr       != null && !addrStr.isBlank();
                        boolean hasReceiverNm  = receiverNmStr != null && !receiverNmStr.isBlank();
                        if (hasPhone || hasAddr || hasReceiverNm) {
                            List<com.nhatnam.server.entity.CustomerReceiverInfo> recInfos =
                                    receiverInfoRepository.findByCustomerId(customerId);

                            // Tìm receiverInfo default hiện tại
                            com.nhatnam.server.entity.CustomerReceiverInfo defInfo = recInfos.stream()
                                    .filter(ri -> Boolean.TRUE.equals(ri.getIsDefault()))
                                    .findFirst()
                                    .orElse(recInfos.isEmpty() ? null : recInfos.get(0));

                            if (defInfo != null) {
                                // Cập nhật record hiện có
                                if (hasPhone)      defInfo.setReceiverPhone(phoneGhStr.trim());
                                if (hasAddr)       defInfo.setReceiverAddress(addrStr.trim());
                                if (hasReceiverNm) defInfo.setReceiverName(receiverNmStr.trim());
                                receiverInfoRepository.save(defInfo);
                            } else if (hasAddr) {
                                // Chưa có receiver nào → tạo mới (address bắt buộc theo entity)
                                com.nhatnam.server.entity.CustomerReceiverInfo newInfo =
                                        com.nhatnam.server.entity.CustomerReceiverInfo.builder()
                                                .customer(c)
                                                .receiverName(hasReceiverNm ? receiverNmStr.trim() : null)
                                                .receiverPhone(hasPhone ? phoneGhStr.trim() : null)
                                                .receiverAddress(addrStr.trim())
                                                .isDefault(true)
                                                .build();
                                receiverInfoRepository.save(newInfo);
                            }
                        }

                        updated++;
                    } catch (Exception ex) {
                        errors.add("Dòng " + (r - 3) + ": " + ex.getMessage());
                        skipped++;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("updated", updated); result.put("skipped", skipped); result.put("errors", errors);
            return ResponseEntity.ok(ApiResponse.ok("Import hoàn tất: " + updated + " thành công, " + skipped + " bỏ qua", result));
        } catch (Exception e) {
            log.error("[ADMIN] importCustomers error", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private String _cellStr(org.apache.poi.ss.usermodel.Row row, int col) {
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private byte[] _buildCustomerExcel(List<Customer> customers, List<User> sellers) throws Exception {
        String exportToken = _generateExportToken();

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            org.apache.poi.xssf.usermodel.XSSFSheet ws = wb.createSheet("Khách hàng");
            ws.setDisplayGridlines(false);

            byte[] C_HEADER = {(byte)26,(byte)26,(byte)46};
            byte[] C_ACCENT = {(byte)201,(byte)168,(byte)76};
            byte[] C_WHITE  = {(byte)255,(byte)255,(byte)255};
            byte[] C_GRAY   = {(byte)250,(byte)247,(byte)242};
            byte[] C_BORDER = {(byte)232,(byte)221,(byte)208};
            byte[] C_TEXT   = {(byte)28,(byte)28,(byte)30};
            byte[] C_SUB    = {(byte)92,(byte)92,(byte)92};
            byte[] C_NOTE   = {(byte)254,(byte)243,(byte)199};

            java.util.function.Function<byte[], org.apache.poi.xssf.usermodel.XSSFColor> mkColor =
                    b -> new org.apache.poi.xssf.usermodel.XSSFColor(b, null);

            java.util.function.BiFunction<byte[], byte[], XSSFCellStyle> mkStyle = (bg, fg) -> {
                XSSFCellStyle cs = wb.createCellStyle();
                cs.setFillForegroundColor(mkColor.apply(bg));
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cs.setVerticalAlignment(VerticalAlignment.CENTER);
                org.apache.poi.xssf.usermodel.XSSFColor bc = mkColor.apply(C_BORDER);
                cs.setBorderTop(BorderStyle.THIN);    cs.setBorderColor(XSSFCellBorder.BorderSide.TOP,    bc);
                cs.setBorderBottom(BorderStyle.THIN); cs.setBorderColor(XSSFCellBorder.BorderSide.BOTTOM, bc);
                cs.setBorderLeft(BorderStyle.THIN);   cs.setBorderColor(XSSFCellBorder.BorderSide.LEFT,   bc);
                cs.setBorderRight(BorderStyle.THIN);  cs.setBorderColor(XSSFCellBorder.BorderSide.RIGHT,  bc);
                XSSFFont font = wb.createFont(); font.setFontName("Arial");
                font.setFontHeightInPoints((short)10); font.setColor(mkColor.apply(fg)); cs.setFont(font);
                return cs;
            };

            XSSFCellStyle titleStyle = mkStyle.apply(C_HEADER, C_ACCENT);
            ((XSSFFont)titleStyle.getFont()).setBold(true); ((XSSFFont)titleStyle.getFont()).setFontHeightInPoints((short)14);
            XSSFCellStyle noteStyle  = mkStyle.apply(C_NOTE, new byte[]{(byte)146,(byte)64,(byte)14});
            ((XSSFFont)noteStyle.getFont()).setFontHeightInPoints((short)8);
            XSSFCellStyle hdrStyle   = mkStyle.apply(C_HEADER, C_WHITE);
            ((XSSFFont)hdrStyle.getFont()).setBold(true); hdrStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle infoStyle  = mkStyle.apply(C_GRAY, C_SUB);
            XSSFCellStyle dataStyle  = mkStyle.apply(C_WHITE, C_TEXT);
            XSSFCellStyle dataGStyle = mkStyle.apply(C_GRAY,  C_TEXT);
            XSSFCellStyle sttStyle   = mkStyle.apply(C_GRAY,  C_SUB); sttStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle sttWStyle  = mkStyle.apply(C_WHITE, C_SUB); sttWStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle idStyle    = mkStyle.apply(new byte[]{(byte)240,(byte)235,(byte)227}, C_ACCENT);
            ((XSSFFont)idStyle.getFont()).setBold(true); idStyle.setAlignment(HorizontalAlignment.CENTER);

            // Layout: 0=ID(locked) 1=Mã KH 2=Tên KH/CT 3=Người LH 4=SĐT GH 5=ĐC GH
            //          6=Email 7=Phân loại 8=Loại giá 9=NV KD 10=CK% 11=CN 12=TT
            // Layout: 0=ID 1=Mã KH 2=Tên KH/CT 3=Người LH 4=SĐT GH 5=ĐC GH 6=Tên NR
            //          7=Email 8=Phân loại 9=Loại giá 10=NV KD 11=CK% 12=CN 13=TT
            String[] headers = {"ID","Mã khách hàng","Tên KH / Công ty","Người liên hệ",
                    "SĐT giao hàng","Địa chỉ giao hàng","Tên người nhận","Email",
                    "Phân loại","Loại giá áp dụng","NV Kinh doanh chăm sóc",
                    "Chiết khấu (%)","Công nợ (ngày)","Trạng thái"};
            int[] widths = {10,16,28,22,18,40,22,24,16,20,26,10,10,14};
            for (int i=0;i<widths.length;i++) ws.setColumnWidth(i, widths[i]*256);

            // Sheet ẩn __data cho dropdown
            org.apache.poi.xssf.usermodel.XSSFSheet dataSheet = wb.createSheet("__data");
            wb.setSheetHidden(wb.getSheetIndex("__data"), true);
            // Row 0: seller names
            org.apache.poi.ss.usermodel.Row dSellRow = dataSheet.createRow(0);
            for (int i=0;i<sellers.size();i++)
                dSellRow.createCell(i).setCellValue(sellers.get(i).getFullName()!=null?sellers.get(i).getFullName():sellers.get(i).getUsername());

            // Sheet ẩn __meta: token
            org.apache.poi.xssf.usermodel.XSSFSheet meta = wb.createSheet("__meta");
            wb.setSheetHidden(wb.getSheetIndex("__meta"), true);
            meta.createRow(0).createCell(0).setCellValue(exportToken);

            // Row 0: Title
            org.apache.poi.ss.usermodel.Row r0 = ws.createRow(0); r0.setHeightInPoints(28);
            ws.addMergedRegion(new CellRangeAddress(0,0,0,headers.length-1));
            Cell tc = r0.createCell(0);
            tc.setCellValue("DANH SÁCH KHÁCH HÀNG"); tc.setCellStyle(titleStyle);

            // Row 1: Info
            org.apache.poi.ss.usermodel.Row r1 = ws.createRow(1); r1.setHeightInPoints(18);
            ws.addMergedRegion(new CellRangeAddress(1,1,0,headers.length-1));
            Cell ic = r1.createCell(0);
            ic.setCellValue("Xuất lúc: "+java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    +"   |   Tổng: "+customers.size()+" khách   |   File chỉ import được 1 lần — Export lại nếu cần");
            ic.setCellStyle(infoStyle);

            // Row 2: Note
            org.apache.poi.ss.usermodel.Row r2 = ws.createRow(2); r2.setHeightInPoints(16);
            ws.addMergedRegion(new CellRangeAddress(2,2,0,headers.length-1));
            Cell nc = r2.createCell(0);
            nc.setCellValue("⚠ Không xóa cột ID. Phân loại / Loại giá / NV KD / Trạng thái chọn từ dropdown.");
            nc.setCellStyle(noteStyle);

            // Row 3: Header
            org.apache.poi.ss.usermodel.Row hdrRow = ws.createRow(3); hdrRow.setHeightInPoints(22);
            for (int i=0;i<headers.length;i++) {
                Cell c = hdrRow.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(hdrStyle);
            }
            ws.createFreezePane(0,4);

            // Dropdowns helper
            int DATA_START=4, DATA_ROWS_MAX=Math.max(customers.size()+20,200);
            java.util.function.BiConsumer<Integer,String> addDd = (colIdx,formula)->{
                DataValidationHelper dvh=ws.getDataValidationHelper();
                DataValidationConstraint dvc=dvh.createFormulaListConstraint(formula);
                CellRangeAddressList addr=new CellRangeAddressList(DATA_START,DATA_START+DATA_ROWS_MAX,colIdx,colIdx);
                DataValidation dv=dvh.createValidation(dvc,addr);
                dv.setSuppressDropDownArrow(true); dv.setShowErrorBox(false); ws.addValidationData(dv);
            };

            // Col 8: Phân loại
            addDd.accept(8,"\"Cá nhân,Công ty\"");
            // Col 9: Loại giá
            addDd.accept(9,"\"Bán lẻ (giá gốc),Bán sỉ (khung giá)\"");
            // Col 10: NV KD — từ __data row 1
            if (!sellers.isEmpty()) {
                String colLast = sellers.size()<=26 ? String.valueOf((char)('A'+sellers.size()-1)) : "AZ";
                addDd.accept(10,"__data!$A$1:$"+colLast+"$1");
            }
            // Col 13: Trạng thái
            addDd.accept(13,"\"Hoạt động,Đã khóa\"");

            // Data rows
            for (int i=0;i<customers.size();i++) {
                Customer c = customers.get(i);
                org.apache.poi.ss.usermodel.Row row = ws.createRow(DATA_START+i); row.setHeightInPoints(18);
                boolean gray=i%2==0;
                XSSFCellStyle ds=gray?dataGStyle:dataStyle, ss=gray?sttStyle:sttWStyle;

                boolean isCompany = c.getCustomerType()==Customer.CustomerType.COMPANY;
                String displayName   = isCompany?(c.getCompanyName()!=null?c.getCompanyName():""):(c.getName()!=null?c.getName():"");
                String contactPerson = isCompany?(c.getContactName()!=null?c.getContactName():""):(c.getName()!=null?c.getName():"");

                List<String> contacts = new java.util.ArrayList<>();
                if (c.getPhone()!=null&&!c.getPhone().isBlank()) contacts.add(c.getPhone());
                if (c.getEmail()!=null&&!c.getEmail().isBlank()) contacts.add(c.getEmail());
                if (c.getCompanyPhone()!=null&&!c.getCompanyPhone().isBlank()) contacts.add(c.getCompanyPhone());

                String sellerName="";
                if (c.getAssignedSeller()!=null) sellerName=c.getAssignedSeller().getFullName()!=null?c.getAssignedSeller().getFullName():c.getAssignedSeller().getUsername();
                else if (c.getAssignedSellerName()!=null) sellerName=c.getAssignedSellerName();

                boolean isWholesale=c.getPricingType()==Customer.PricingType.WHOLESALE_PRICE;

                // Lấy thông tin giao hàng mặc định
                List<com.nhatnam.server.entity.CustomerReceiverInfo> recInfos =
                        receiverInfoRepository.findByCustomerId(c.getId());
                String defaultPhone="", defaultAddr="", defaultReceiverName="";
                if (!recInfos.isEmpty()) {
                    com.nhatnam.server.entity.CustomerReceiverInfo def = recInfos.stream()
                            .filter(ri->Boolean.TRUE.equals(ri.getIsDefault())).findFirst()
                            .orElse(recInfos.get(0));
                    defaultPhone        = def.getReceiverPhone()   !=null?def.getReceiverPhone():"";
                    defaultAddr         = def.getReceiverAddress() !=null?def.getReceiverAddress():"";
                    defaultReceiverName = def.getReceiverName()    !=null?def.getReceiverName():"";
                }

                // Email
                String emailStr = c.getEmail()!=null?c.getEmail():"";

                Object[] vals = {
                        c.getId(),                                                          // 0: ID (locked)
                        c.getCustomerCode()!=null?c.getCustomerCode():"",                  // 1: Mã KH
                        displayName,                                                        // 2: Tên KH/CT
                        contactPerson,                                                      // 3: Người LH
                        defaultPhone,                                                       // 4: SĐT GH
                        defaultAddr,                                                        // 5: Địa chỉ GH
                        defaultReceiverName,                                                // 6: Tên người nhận
                        emailStr,                                                           // 7: Email
                        isCompany?"Công ty":"Cá nhân",                                     // 8: Phân loại
                        isWholesale?"Bán sỉ (khung giá)":"Bán lẻ (giá gốc)",              // 9: Loại giá
                        sellerName,                                                         // 10: NV KD
                        (long)(c.getDiscountRate()!=null?c.getDiscountRate():0),            // 11: CK%
                        (long)(c.getDebtDays()!=null?c.getDebtDays():0),                   // 12: CN
                        Boolean.TRUE.equals(c.getIsActive())?"Hoạt động":"Đã khóa",        // 13: TT
                };

                for (int col=0;col<vals.length;col++) {
                    Cell cell=row.createCell(col);
                    cell.setCellStyle(col==0?idStyle:ds);
                    if (vals[col] instanceof Long) cell.setCellValue((Long)vals[col]);
                    else cell.setCellValue(String.valueOf(vals[col]));
                }
            }

            ws.setAutoFilter(new CellRangeAddress(3,DATA_START+DATA_ROWS_MAX,0,headers.length-1));
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}