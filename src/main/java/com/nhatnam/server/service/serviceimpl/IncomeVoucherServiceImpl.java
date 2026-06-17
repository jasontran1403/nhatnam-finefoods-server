package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.common.StaleOrderDataException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.income.CreateIncomeVoucherRequest;
import com.nhatnam.server.dto.income.IncomeVoucherDto;
import com.nhatnam.server.entity.IncomeItem;
import com.nhatnam.server.entity.IncomeVoucher;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.IncomeVoucherRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.IncomeVoucherService;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncomeVoucherServiceImpl implements IncomeVoucherService {

    private final IncomeVoucherRepository voucherRepo;
    private final UserRepository          userRepository;
    private final NotificationService     notificationService;
    private final ObjectMapper            objectMapper;
    private final OrderRepository         orderRepository;
    private final OrderService            orderService;

    // IncomeVoucherServiceImpl.java — chỉ hàm create(), thay thế toàn bộ hàm cũ

    @Override
    @Transactional
    public IncomeVoucherDto create(Long createdByUserId, Role creatorRole,
                                   CreateIncomeVoucherRequest req) {
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        String creatorName = creator.getFullName() != null && !creator.getFullName().isBlank()
                ? creator.getFullName() : creator.getUsername();

        // Validate receiptNumber
        if (req.getReceiptNumber() == null || req.getReceiptNumber().isBlank())
            throw new BusinessException("Số phiếu thu là bắt buộc");
        if (voucherRepo.existsByReceiptNumber(req.getReceiptNumber().trim()))
            throw new BusinessException("Số phiếu thu \"" + req.getReceiptNumber().trim() + "\" đã tồn tại trong hệ thống");

        // Validate bank info nếu là chuyển khoản
        IncomeVoucher.PaymentType paymentType = IncomeVoucher.PaymentType.CASH;
        if ("BANK_TRANSFER".equalsIgnoreCase(req.getPaymentType())) {
            paymentType = IncomeVoucher.PaymentType.BANK_TRANSFER;
            if (req.getBankName() == null || req.getBankName().isBlank())
                throw new BusinessException("Tên ngân hàng là bắt buộc khi thanh toán chuyển khoản");
            if (req.getBankRef() == null || req.getBankRef().isBlank())
                throw new BusinessException("Mã tham chiếu giao dịch là bắt buộc khi thanh toán chuyển khoản");
        }

        // Serialize image URLs
        String imageUrlsJson = null;
        if (req.getImageUrls() != null && !req.getImageUrls().isEmpty()) {
            try { imageUrlsJson = objectMapper.writeValueAsString(req.getImageUrls()); }
            catch (Exception e) { log.warn("Failed to serialize imageUrls"); }
        }

        // Serialize linked order codes
        String linkedOrderCodesJson = null;
        if (req.getLinkedOrderCodes() != null && !req.getLinkedOrderCodes().isEmpty()) {
            try { linkedOrderCodesJson = objectMapper.writeValueAsString(req.getLinkedOrderCodes()); }
            catch (Exception e) { log.warn("Failed to serialize linkedOrderCodes"); }
        }

        IncomeVoucher voucher = IncomeVoucher.builder()
                .voucherCode(generateCode())
                .receiptNumber(req.getReceiptNumber().trim())
                .payerName(req.getPayerName())
                .reason(req.getReason())
                .createdByName(creatorName)
                .createdBy(creator)
                .status(IncomeVoucher.VoucherStatus.CONFIRMED)
                .paymentType(paymentType)
                .bankName(paymentType == IncomeVoucher.PaymentType.BANK_TRANSFER ? req.getBankName() : null)
                .bankRef(paymentType == IncomeVoucher.PaymentType.BANK_TRANSFER ? req.getBankRef() : null)
                .linkedOrderCodes(linkedOrderCodesJson)
                .imageUrls(imageUrlsJson)
                .items(new ArrayList<>())
                .build();
        voucher = voucherRepo.save(voucher);

        for (CreateIncomeVoucherRequest.IncomeItemRequest itemReq : req.getItems()) {
            voucher.getItems().add(IncomeItem.builder()
                    .voucher(voucher)
                    .itemName(itemReq.getItemName())
                    .amount(itemReq.getAmount())
                    .note(itemReq.getNote())
                    .build());
        }
        voucher = voucherRepo.save(voucher);

        // ── Phân bổ tiền cho các đơn liên kết ────────────────────────────────────
        if (req.getLinkedOrderCodes() != null && !req.getLinkedOrderCodes().isEmpty()) {
            List<Order> linkedOrders = new ArrayList<>();
            List<String> notFound = new ArrayList<>();

            for (String code : req.getLinkedOrderCodes()) {
                orderRepository.findByOrderCode(code.trim()).ifPresentOrElse(
                        linkedOrders::add,
                        () -> notFound.add(code)
                );
            }
            if (!notFound.isEmpty()) {
                voucherRepo.delete(voucher);
                throw new BusinessException("Không tìm thấy đơn hàng: " + String.join(", ", notFound));
            }

            // Validate collectedAmount bắt buộc khi có đơn
            if (req.getCollectedAmount() == null || req.getCollectedAmount().compareTo(BigDecimal.ZERO) <= 0) {
                voucherRepo.delete(voucher);
                throw new BusinessException("Số tiền thực thu là bắt buộc khi có đơn hàng liên kết");
            }

            BigDecimal collected = req.getCollectedAmount().setScale(0, RoundingMode.HALF_UP);

            // Tổng còn lại cần thu — làm tròn CEILING để khớp với frontend
            BigDecimal orderTotal = linkedOrders.stream()
                    .map(o -> {
                        BigDecimal final_ = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
                        BigDecimal paid_  = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
                        BigDecimal rem = final_.subtract(paid_).setScale(0, RoundingMode.CEILING);
                        return rem.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rem;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (collected.compareTo(orderTotal) > 0) {
                voucherRepo.delete(voucher);
                throw new BusinessException(
                        "Số tiền thực thu (" + collected.toPlainString()
                                + ") vượt quá tổng đơn hàng (" + orderTotal.toPlainString() + ")");
            }

            // ── Phân bổ lần lượt theo thứ tự đơn ────────────────────────────────
            BigDecimal remaining = collected;

            for (int i = 0; i < linkedOrders.size(); i++) {
                Order order = linkedOrders.get(i);
                BigDecimal paid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
                // Làm tròn CEILING để khớp với số frontend hiển thị cho user
                BigDecimal orderFinal = order.getFinalAmount() != null
                        ? order.getFinalAmount().subtract(paid).setScale(0, RoundingMode.CEILING)
                        : BigDecimal.ZERO;
                if (orderFinal.compareTo(BigDecimal.ZERO) < 0) orderFinal = BigDecimal.ZERO;
                boolean isLast = (i == linkedOrders.size() - 1);

                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    // Hết tiền — bỏ qua các đơn còn lại (không thay đổi trạng thái)
                    continue;
                }

                if (remaining.compareTo(orderFinal) >= 0) {
                    // Thu đủ đơn này
                    remaining = remaining.subtract(orderFinal);
                    try {
                        orderService.markAsCompleted(order.getId(), creatorName, createdByUserId);
                    } catch (Exception e) {
                        voucherRepo.delete(voucher);
                        throw new BusinessException("Lỗi khi hoàn thành đơn " + order.getOrderCode() + ": " + e.getMessage());
                    }
                } else {
                    // Thu thiếu — chỉ được xảy ra ở đơn cuối (frontend đã validate)
                    if (!isLast) {
                        // Trường hợp này không nên xảy ra nếu frontend validate đúng
                        voucherRepo.delete(voucher);
                        throw new BusinessException(
                                "Số tiền thu không đủ cho đơn " + order.getOrderCode()
                                        + ". Vui lòng kiểm tra lại.");
                    }

                    String handling = req.getLastOrderHandling();

                    if ("FULL".equalsIgnoreCase(handling)) {
                        // Ghi nhận đã thu đủ dù thực tế thiếu
                        try {
                            orderService.markAsCompleted(order.getId(), creatorName, createdByUserId);

                        } catch (Exception e) {
                            voucherRepo.delete(voucher);
                            throw new BusinessException("Lỗi khi hoàn thành đơn " + order.getOrderCode() + ": " + e.getMessage());
                        }
                    } else {
                        // Mặc định: PARTIAL
                        try {
                            orderService.recordPartialPayment(
                                    order.getId(),
                                    remaining,
                                    order.getDebtDays(),
                                    creatorName,
                                    order.getPaymentMethod(),
                                    null,
                                    null,
                                    createdByUserId
                            );
                        } catch (Exception e) {
                            voucherRepo.delete(voucher);
                            throw new BusinessException(
                                    "Lỗi khi ghi nhận thanh toán 1 phần đơn "
                                            + order.getOrderCode() + ": " + e.getMessage());
                        }
                    }
                    remaining = BigDecimal.ZERO;
                }
            }

        }

        // ── Gửi notification ──────────────────────────────────────────────────────
        try {
            String payload = "{\"voucherId\":" + voucher.getId()
                    + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
            String msg = creatorName + " đã tạo phiếu thu ["
                    + voucher.getVoucherCode() + "]. Lý do: " + req.getReason();

            if (creatorRole == Role.ACCOUNTANT) {
                List<User> superAccs = userRepository.findByRoleAndIsLockAccountFalse(Role.SUPER_ACCOUNTANT);
                for (User u : superAccs) notificationService.sendToUser(u, "INCOME_CREATED", msg, payload);
                notificationService.sendToRole("SUPER_ACCOUNTANT", "INCOME_CREATED", msg, payload);
            }

            List<User> admins = userRepository.findByRoleAndIsLockAccountFalse(Role.ADMIN);
            List<User> owners = userRepository.findByRoleAndIsLockAccountFalse(Role.OWNER);
            for (User u : admins) notificationService.sendToUser(u, "INCOME_CREATED", msg, payload);
            for (User u : owners) notificationService.sendToUser(u, "INCOME_CREATED", msg, payload);
            notificationService.sendToRole("ADMIN", "INCOME_CREATED", msg, payload);
            notificationService.sendToRole("OWNER", "INCOME_CREATED", msg, payload);

            if (req.getLinkedOrderCodes() != null && !req.getLinkedOrderCodes().isEmpty()) {
                Set<Long> notifiedUserIds = new HashSet<>();
                owners.forEach(u -> notifiedUserIds.add(u.getId()));

                for (String code : req.getLinkedOrderCodes()) {
                    Optional<Order> orderOpt = orderRepository.findByOrderCode(code.trim());
                    if (orderOpt.isEmpty()) continue;
                    Order order = orderOpt.get();
                    User orderCreator = order.getUser();
                    if (orderCreator == null) continue;

                    Set<Role> userRoles = orderCreator.getRoles();
                    boolean isSeller = userRoles.contains(Role.SELLER) || userRoles.contains(Role.SUPER_SELLER);
                    if (!isSeller) continue;
                    if (userRoles.contains(Role.OWNER)) continue;

                    boolean added = notifiedUserIds.add(orderCreator.getId());
                    if (!added) continue;

                    String sellerActiveRole = userRoles.contains(Role.SUPER_SELLER) ? "SUPER_SELLER" : "SELLER";
                    notificationService.sendToUser(orderCreator, sellerActiveRole, "INCOME_CREATED", msg, payload);
                }
            }
        } catch (Exception e) {
            log.warn("[INCOME] Failed to send notification: {}", e.getMessage());
        }

        return toDto(voucher);
    }

    @Override
    public PageResponse<IncomeVoucherDto> listForCreator(Long userId, Pageable pageable) {
        return PageResponse.from(
                voucherRepo.findByCreatedByIdOrderByCreatedAtDesc(userId, pageable).map(this::toDto));
    }

    @Override
    public PageResponse<IncomeVoucherDto> listAll(Pageable pageable) {
        return PageResponse.from(voucherRepo.findAllByOrderByCreatedAtDesc(pageable).map(this::toDto));
    }

    @Override
    public PageResponse<IncomeVoucherDto> listByDateRange(Long from, Long to, Pageable pageable) {
        return PageResponse.from(voucherRepo.findByDateRange(from, to, pageable).map(this::toDto));
    }

    @Override
    public PageResponse<IncomeVoucherDto> search(String q, Long from, Long to, Pageable pageable) {
        if (from != null && to != null)
            return PageResponse.from(voucherRepo.searchWithDateRange(q, from, to, pageable).map(this::toDto));
        return PageResponse.from(voucherRepo.searchAll(q, pageable).map(this::toDto));
    }

    @Override
    public IncomeVoucherDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private IncomeVoucher findOrThrow(Long id) {
        return voucherRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu thu không tồn tại: " + id));
    }

    // ── Export báo cáo phiếu thu ─────────────────────────────────────────────
    @Override
    public byte[] exportReport(Long from, Long to, String exportedBy) throws Exception {
        List<IncomeVoucher> vouchers = voucherRepo.findByDateRangeAll(from, to);

        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("Phiếu thu");

        // ── Styles ─────────────────────────────────────────────────────────
        CellStyle headerStyle = wb.createCellStyle();
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true); headerFont.setFontHeightInPoints((short)11);
        headerFont.setColor(new XSSFColor(new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xFF}, null));
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0x1A,(byte)0x1A,(byte)0x2E}, null));
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        setBorderColor(headerStyle, "E0E0E0");

        CellStyle titleStyle = wb.createCellStyle();
        XSSFFont titleFont = wb.createFont();
        titleFont.setBold(true); titleFont.setFontHeightInPoints((short)14);
        titleStyle.setFont(titleFont);
        titleStyle.setAlignment(HorizontalAlignment.CENTER);
        titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle metaStyle = wb.createCellStyle();
        XSSFFont metaFont = wb.createFont();
        metaFont.setItalic(true); metaFont.setFontHeightInPoints((short)10);
        metaStyle.setFont(metaFont);
        metaStyle.setAlignment(HorizontalAlignment.CENTER);

        CellStyle dataStyle = wb.createCellStyle();
        dataStyle.setVerticalAlignment(VerticalAlignment.TOP);
        dataStyle.setWrapText(false);
        setBorderColor(dataStyle, "E0E0E0");
        dataStyle.setBorderBottom(BorderStyle.THIN);

        CellStyle dataWrapStyle = wb.createCellStyle();
        dataWrapStyle.setVerticalAlignment(VerticalAlignment.TOP);
        dataWrapStyle.setWrapText(true);
        setBorderColor(dataWrapStyle, "E0E0E0");
        dataWrapStyle.setBorderBottom(BorderStyle.THIN);

        CellStyle amountStyle = wb.createCellStyle();
        amountStyle.setVerticalAlignment(VerticalAlignment.TOP);
        amountStyle.setAlignment(HorizontalAlignment.RIGHT);
        DataFormat fmt = wb.createDataFormat();
        amountStyle.setDataFormat(fmt.getFormat("#,##0"));
        setBorderColor(amountStyle, "E0E0E0");
        amountStyle.setBorderBottom(BorderStyle.THIN);

        CellStyle altStyle = wb.createCellStyle();
        altStyle.cloneStyleFrom(dataStyle);
        altStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xFA,(byte)0xF7,(byte)0xF2}, null));
        altStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle altWrapStyle = wb.createCellStyle();
        altWrapStyle.cloneStyleFrom(dataWrapStyle);
        altWrapStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xFA,(byte)0xF7,(byte)0xF2}, null));
        altWrapStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle altAmtStyle = wb.createCellStyle();
        altAmtStyle.cloneStyleFrom(amountStyle);
        altAmtStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xFA,(byte)0xF7,(byte)0xF2}, null));
        altAmtStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        java.time.format.DateTimeFormatter dtFmt =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
        java.time.format.DateTimeFormatter dateFmt =
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        java.time.ZoneId tz = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

        String fromStr = java.time.Instant.ofEpochMilli(from).atZone(tz).format(dateFmt);
        String toStr   = java.time.Instant.ofEpochMilli(to).atZone(tz).format(dateFmt);
        String exportedAt = java.time.LocalDateTime.now(tz)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));

        int rowIdx = 0;

        // ── Row 0: Tiêu đề ─────────────────────────────────────────────────
        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(28);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BÁO CÁO PHIẾU THU");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // ── Row 1: Meta info ────────────────────────────────────────────────
        Row metaRow = sheet.createRow(rowIdx++);
        metaRow.setHeightInPoints(18);
        Cell metaCell = metaRow.createCell(0);
        metaCell.setCellValue("Từ " + fromStr + " đến " + toStr
                + "     |     Xuất lúc: " + exportedAt
                + "     |     Người xuất: " + (exportedBy != null ? exportedBy : ""));
        metaCell.setCellStyle(metaStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 3));

        // ── Row 2: blank ────────────────────────────────────────────────────
        sheet.createRow(rowIdx++);

        // ── Row 3: Header ───────────────────────────────────────────────────
        Row hRow = sheet.createRow(rowIdx++);
        hRow.setHeightInPoints(20);
        String[] headers = {"Thời gian tạo", "Số phiếu thu", "Hóa đơn thu", "Tổng tiền thu"};
        for (int i = 0; i < headers.length; i++) {
            Cell c = hRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headerStyle);
        }

        // ── Data rows ───────────────────────────────────────────────────────
        for (int i = 0; i < vouchers.size(); i++) {
            IncomeVoucher v = vouchers.get(i);
            boolean alt = (i % 2 == 1);

            // Tính tổng tiền
            BigDecimal total = v.getItems() == null ? BigDecimal.ZERO :
                    v.getItems().stream()
                            .map(IncomeItem::getAmount)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Parse linkedOrderCodes
            List<String> codes = parseJsonList(v.getLinkedOrderCodes());
            String orderCodesText = codes.isEmpty() ? "" : String.join("\n", codes);
            int numLines = codes.isEmpty() ? 1 : codes.size();

            Row row = sheet.createRow(rowIdx++);
            row.setHeightInPoints(Math.max(18, numLines * 16f));

            // Col 0: Thời gian tạo
            Cell c0 = row.createCell(0);
            c0.setCellValue(v.getCreatedAt() != null
                    ? java.time.Instant.ofEpochMilli(v.getCreatedAt()).atZone(tz).format(dtFmt) : "");
            c0.setCellStyle(alt ? altStyle : dataStyle);

            // Col 1: Số phiếu thu
            Cell c1 = row.createCell(1);
            c1.setCellValue(v.getReceiptNumber() != null ? v.getReceiptNumber() : v.getVoucherCode());
            c1.setCellStyle(alt ? altStyle : dataStyle);

            // Col 2: Hóa đơn thu — wrap text, mỗi đơn 1 dòng
            Cell c2 = row.createCell(2);
            c2.setCellValue(orderCodesText);
            c2.setCellStyle(alt ? altWrapStyle : dataWrapStyle);

            // Col 3: Tổng tiền
            Cell c3 = row.createCell(3);
            c3.setCellValue(total.doubleValue());
            c3.setCellStyle(alt ? altAmtStyle : amountStyle);
        }

        // ── Column widths ───────────────────────────────────────────────────
        sheet.setColumnWidth(0, 22 * 256);  // Thời gian
        sheet.setColumnWidth(1, 20 * 256);  // Số phiếu
        sheet.setColumnWidth(2, 30 * 256);  // Hóa đơn
        sheet.setColumnWidth(3, 18 * 256);  // Tổng tiền

        // ── Total row ───────────────────────────────────────────────────────
        Row totalRow = sheet.createRow(rowIdx);
        totalRow.setHeightInPoints(20);
        CellStyle totalStyle = wb.createCellStyle();
        XSSFFont totalFont = wb.createFont();
        totalFont.setBold(true);
        totalStyle.setFont(totalFont);
        totalStyle.setAlignment(HorizontalAlignment.RIGHT);
        totalStyle.setDataFormat(fmt.getFormat("#,##0"));
        totalStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xC9,(byte)0xA8,(byte)0x4C}, null));
        totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle totalLabelStyle = wb.createCellStyle();
        totalLabelStyle.setFont(totalFont);
        totalLabelStyle.setAlignment(HorizontalAlignment.RIGHT);
        totalLabelStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xC9,(byte)0xA8,(byte)0x4C}, null));
        totalLabelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Cell tlCell = totalRow.createCell(0);
        tlCell.setCellValue("TỔNG CỘNG (" + vouchers.size() + " phiếu)");
        tlCell.setCellStyle(totalLabelStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 2));

        int dataStart = 4; // row index data bắt đầu (0-indexed)
        Cell sumCell = totalRow.createCell(3);
        if (!vouchers.isEmpty()) {
            sumCell.setCellFormula("SUM(D" + dataStart + ":D" + rowIdx + ")");
        } else {
            sumCell.setCellValue(0);
        }
        sumCell.setCellStyle(totalStyle);

        // ── Write output ────────────────────────────────────────────────────
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private void setBorderColor(CellStyle style, String hex) {
        // no-op: border color set via XSSFCellStyle if needed; skip for brevity
    }

    private String generateCode() {
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "IV-" + date + "-";

        // Tìm code lớn nhất trong ngày hôm nay
        String lastCode = voucherRepo.findTopVoucherCodeByDatePrefix(prefix);

        int next = 1;
        if (lastCode != null) {
            try {
                int last = Integer.parseInt(lastCode.substring(prefix.length()));
                next = last + 1;
            } catch (NumberFormatException ignored) {}
        }

        if (next > 9999) throw new BusinessException("Đã đạt giới hạn phiếu thu trong ngày");

        return prefix + String.format("%04d", next);
    }


    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private IncomeVoucherDto toDto(IncomeVoucher v) {
        BigDecimal total = v.getItems().stream()
                .map(IncomeItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return IncomeVoucherDto.builder()
                .id(v.getId())
                .voucherCode(v.getVoucherCode())
                .payerName(v.getPayerName())
                .reason(v.getReason())
                .createdByName(v.getCreatedByName())
                .createdById(v.getCreatedBy() != null ? v.getCreatedBy().getId() : null)
                .status(v.getStatus().name())
                .paymentType(v.getPaymentType() != null ? v.getPaymentType().name() : "CASH")
                .bankName(v.getBankName())
                .bankRef(v.getBankRef())
                .linkedOrderCodes(parseJsonList(v.getLinkedOrderCodes()))
                .items(v.getItems().stream()
                        .map(i -> IncomeVoucherDto.IncomeItemDto.builder()
                                .id(i.getId())
                                .itemName(i.getItemName())
                                .amount(i.getAmount())
                                .note(i.getNote())
                                .build())
                        .toList())
                .totalAmount(total)
                .imageUrls(parseJsonList(v.getImageUrls()))
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }
}