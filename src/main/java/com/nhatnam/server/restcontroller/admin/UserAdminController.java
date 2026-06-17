package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.user.CreateUserRequest;
import com.nhatnam.server.dto.user.UpdateUserRequest;
import com.nhatnam.server.dto.user.UserDto;
import com.nhatnam.server.entity.DriverAttendance;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.DriverAttendanceRepository;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.admin.UserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Log4j2
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userService;
    private final DriverAttendanceRepository driverAttendanceRepository;
    private final OrderRepository orderRepository;
    private final DriverRepository driverRepository;

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER', 'HR')")
    public ResponseEntity<byte[]> exportDriverReport(
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam(defaultValue = "true") boolean excludeWarehouse,
            @RequestParam(defaultValue = "0") double bikeRatePerKm,
            @RequestParam(defaultValue = "0") double truckRatePerKm,
            Authentication auth) {
        try {
            final boolean hasSalary = bikeRatePerKm > 0 || truckRatePerKm > 0;

            // Tài xế giao tại kho: tên chứa "kho" (không phân biệt hoa/thường)
            java.util.function.Predicate<com.nhatnam.server.entity.Driver> isWarehouseDriver =
                    d -> d.getName() != null && d.getName().toLowerCase().contains("kho");

            // Chuyển đổi timestamp sang LocalDate để query attendance
            java.time.LocalDate fromDate = java.time.Instant.ofEpochMilli(from)
                    .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .toLocalDate();
            java.time.LocalDate toDate = java.time.Instant.ofEpochMilli(to)
                    .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .toLocalDate();

            String fromDateStr = fromDate.toString();
            String toDateStr = toDate.toString();

            List<DriverAttendance> attList =
                    driverAttendanceRepository.findByDateRange(fromDateStr, toDateStr);

            Map<String, Integer[]> dayOdo = new java.util.LinkedHashMap<>();
            for (com.nhatnam.server.entity.DriverAttendance a : attList) {
                if (excludeWarehouse && isWarehouseDriver.test(a.getDriver())) continue;
                String key = a.getDriver().getId() + "|" + a.getVehicleType().name() + "|" + a.getAttendanceDate();
                dayOdo.computeIfAbsent(key, k -> new Integer[]{null, null});
                Integer[] pair = dayOdo.get(key);
                if (a.getSessionType() == com.nhatnam.server.entity.DriverAttendance.SessionType.START) pair[0] = a.getOdometer();
                else pair[1] = a.getOdometer();
            }

            Map<Long, int[]> stats = new java.util.LinkedHashMap<>();
            Map<Long, String> names = new java.util.LinkedHashMap<>();
            for (com.nhatnam.server.entity.Driver d : driverRepository.findByActiveTrueOrderByNameAsc()) {
                if (excludeWarehouse && isWarehouseDriver.test(d)) continue;
                stats.put(d.getId(), new int[]{0,0,0,0});
                names.put(d.getId(), d.getName());
            }

            dayOdo.forEach((key, pair) -> {
                if (pair[0] == null || pair[1] == null) return;
                String[] parts = key.split("\\|");
                Long dId = Long.parseLong(parts[0]);
                String vt = parts[1];
                int km = Math.max(0, pair[1] - pair[0]);
                int[] s = stats.computeIfAbsent(dId, x -> new int[]{0,0,0,0});
                if ("MOTORBIKE".equals(vt)) { s[0] += km; s[1]++; }
                else if ("TRUCK".equals(vt)) { s[2] += km; s[3]++; }
            });

            // Sử dụng timestamp trực tiếp để query orders
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Integer> ordersByName = new java.util.HashMap<>();
            orderRepository.findAll().stream()
                    .filter(o -> (o.getStatus() == com.nhatnam.server.enumtype.OrderStatus.PENDING_PAYMENT
                            || o.getStatus() == com.nhatnam.server.enumtype.OrderStatus.COMPLETED)
                            && o.getCreatedAt() >= from && o.getCreatedAt() <= to
                            && o.getDeliveryInfoJson() != null && !o.getDeliveryInfoJson().isBlank())
                    .forEach(o -> {
                        try {
                            List<Map<String,Object>> info = om.readValue(o.getDeliveryInfoJson(),
                                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
                            for (Map<String,Object> d : info) {
                                String n = String.valueOf(d.getOrDefault("name","")).trim();
                                if (!n.isBlank()) ordersByName.merge(n, 1, Integer::sum);
                            }
                        } catch (Exception ignored) {}
                    });

            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

                org.apache.poi.xssf.usermodel.XSSFSheet ws = wb.createSheet("Báo cáo tài xế");
                ws.setDisplayGridlines(false);

                // ── Color palette ────────────────────────────────────────────
                byte[] darkNavy   = {(byte)0x1A,(byte)0x1A,(byte)0x2E};
                byte[] gold       = {(byte)0xC9,(byte)0xA8,(byte)0x4C};
                byte[] lightGold  = {(byte)0xFD,(byte)0xF8,(byte)0xED};
                byte[] altRow     = {(byte)0xF8,(byte)0xF9,(byte)0xFA};
                byte[] totalBg    = {(byte)0x1A,(byte)0x1A,(byte)0x2E};
                byte[] white      = {(byte)0xFF,(byte)0xFF,(byte)0xFF};
                byte[] bikeBlue   = {(byte)0xE3,(byte)0xF2,(byte)0xFD};
                byte[] truckOrng  = {(byte)0xFFF3,(byte)0xE0,(byte)0x00};
                byte[] salaryGrn  = {(byte)0xE8,(byte)0xF5,(byte)0xE9};

                java.time.ZoneId tz = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
                String exportedBy = auth != null ? ((com.nhatnam.server.entity.User) auth.getPrincipal()).getFullName() : "";
                String exportedAt = java.time.LocalDateTime.now(tz)
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));

                // Format date range for display
                String fromDisplay = java.time.Instant.ofEpochMilli(from)
                        .atZone(tz)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                String toDisplay = java.time.Instant.ofEpochMilli(to)
                        .atZone(tz)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

                // ── Helper: create style ──────────────────────────────────────
                java.util.function.BiFunction<byte[], Boolean, org.apache.poi.ss.usermodel.CellStyle> makeStyle =
                        (bg, bold) -> {
                            org.apache.poi.ss.usermodel.CellStyle s = wb.createCellStyle();
                            if (bg != null) {
                                s.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(bg, null));
                                s.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                            }
                            org.apache.poi.xssf.usermodel.XSSFFont f = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
                            if (bold) f.setBold(true);
                            f.setFontHeightInPoints((short)10);
                            s.setFont(f);
                            s.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
                            s.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            s.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            s.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            s.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            return s;
                        };

                // ── Column widths ─────────────────────────────────────────────
                int[] colW = hasSalary
                        ? new int[]{5, 22, 11, 11, 11, 11, 11, 14, 14, 14}
                        : new int[]{5, 22, 11, 11, 11, 11, 11};
                for (int i = 0; i < colW.length; i++) ws.setColumnWidth(i, colW[i] * 256);

                int totalCols = colW.length;
                int rowIdx = 0;

                // ── ROW 0: Company header ─────────────────────────────────────
                org.apache.poi.ss.usermodel.Row r0 = ws.createRow(rowIdx++);
                r0.setHeightInPoints(30);
                org.apache.poi.ss.usermodel.CellStyle titleStyle = wb.createCellStyle();
                titleStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(darkNavy, null));
                titleStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                titleStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                titleStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
                org.apache.poi.xssf.usermodel.XSSFFont titleFont = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
                titleFont.setBold(true); titleFont.setFontHeightInPoints((short)14);
                titleFont.setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));
                titleStyle.setFont(titleFont);
                org.apache.poi.ss.usermodel.Cell c0 = r0.createCell(0);
                c0.setCellValue("BÁO CÁO TÀI XẾ & BẢNG LƯƠNG  ·  " + fromDisplay + " — " + toDisplay);
                c0.setCellStyle(titleStyle);
                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, totalCols - 1));

                // ── ROW 1: Meta ───────────────────────────────────────────────
                org.apache.poi.ss.usermodel.Row r1 = ws.createRow(rowIdx++);
                r1.setHeightInPoints(16);
                org.apache.poi.ss.usermodel.CellStyle metaStyle = wb.createCellStyle();
                metaStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(lightGold, null));
                metaStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                metaStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                org.apache.poi.xssf.usermodel.XSSFFont metaFont = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
                metaFont.setItalic(true); metaFont.setFontHeightInPoints((short)9);
                metaStyle.setFont(metaFont);
                org.apache.poi.ss.usermodel.Cell c1 = r1.createCell(0);
                c1.setCellValue("Xuất lúc: " + exportedAt + "   |   Người xuất: " + exportedBy
                        + (hasSalary ? "   |   Lương XM: " + String.format("%,.0f", bikeRatePerKm) + " đ/km"
                        + "   |   Lương XT: " + String.format("%,.0f", truckRatePerKm) + " đ/km" : ""));
                c1.setCellStyle(metaStyle);
                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, totalCols - 1));

                // ── ROW 2: blank ─────────────────────────────────────────────
                ws.createRow(rowIdx++).setHeightInPoints(6);

                // ── ROW 3: Header ─────────────────────────────────────────────
                org.apache.poi.ss.usermodel.Row hr = ws.createRow(rowIdx++);
                hr.setHeightInPoints(22);
                org.apache.poi.ss.usermodel.CellStyle hStyle = makeStyle.apply(darkNavy, true);
                hStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle)hStyle).getFont()
                        .setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));

                java.util.List<String> headers = new java.util.ArrayList<>(java.util.Arrays.asList(
                        "STT", "Tài xế", "Km xe máy", "Phiếu XM", "Km xe tải", "Phiếu XT", "Đơn đã giao"
                ));
                if (hasSalary) {
                    headers.add("Lương xe máy"); headers.add("Lương xe tải"); headers.add("Tổng lương");
                }
                for (int i = 0; i < headers.size(); i++) {
                    org.apache.poi.ss.usermodel.Cell c = hr.createCell(i);
                    c.setCellValue(headers.get(i));
                    c.setCellStyle(hStyle);
                }

                // ── Data format ───────────────────────────────────────────────
                org.apache.poi.ss.usermodel.DataFormat dfmt = wb.createDataFormat();
                short numFmt = dfmt.getFormat("#,##0");
                short currFmt = dfmt.getFormat("#,##0\" đ\"");

                // ── Data rows ─────────────────────────────────────────────────
                double grandTotal = 0;
                int stt = 1;
                for (Map.Entry<Long, String> e : names.entrySet()) {
                    int[] s = stats.getOrDefault(e.getKey(), new int[]{0,0,0,0});
                    int ord = ordersByName.getOrDefault(e.getValue(), 0);
                    boolean alt = stt % 2 == 0;

                    double bikeSalary = s[0] * bikeRatePerKm;
                    double truckSalary = s[2] * truckRatePerKm;
                    double totalSalary = bikeSalary + truckSalary;
                    grandTotal += totalSalary;

                    org.apache.poi.ss.usermodel.Row dr = ws.createRow(rowIdx++);
                    dr.setHeightInPoints(18);

                    byte[] rowBg = alt ? altRow : white;
                    org.apache.poi.ss.usermodel.CellStyle dStyle = makeStyle.apply(rowBg, false);
                    org.apache.poi.ss.usermodel.CellStyle numStyle = makeStyle.apply(rowBg, false);
                    numStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                    numStyle.setDataFormat(numFmt);
                    org.apache.poi.ss.usermodel.CellStyle currStyle = makeStyle.apply(rowBg, false);
                    currStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                    currStyle.setDataFormat(currFmt);

                    dr.createCell(0).setCellValue(stt++);
                    dr.getCell(0).setCellStyle(dStyle);

                    dr.createCell(1).setCellValue(e.getValue());
                    dr.getCell(1).setCellStyle(dStyle);

                    for (int ci = 2; ci <= 6; ci++) {
                        int val = switch (ci) { case 2 -> s[0]; case 3 -> s[1]; case 4 -> s[2]; case 5 -> s[3]; case 6 -> ord; default -> 0; };
                        org.apache.poi.ss.usermodel.Cell nc = dr.createCell(ci);
                        nc.setCellValue(val);
                        nc.setCellStyle(numStyle);
                    }

                    if (hasSalary) {
                        // Bike salary
                        org.apache.poi.ss.usermodel.CellStyle bStyle = makeStyle.apply(
                                alt ? new byte[]{(byte)0xE3,(byte)0xF2,(byte)0xFD} : new byte[]{(byte)0xF0,(byte)0xF8,(byte)0xFF}, false);
                        bStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                        bStyle.setDataFormat(currFmt);
                        org.apache.poi.ss.usermodel.Cell bc = dr.createCell(7);
                        bc.setCellValue(bikeSalary); bc.setCellStyle(bStyle);

                        // Truck salary
                        org.apache.poi.ss.usermodel.CellStyle tStyle = makeStyle.apply(
                                alt ? new byte[]{(byte)0xFF,(byte)0xF3,(byte)0xE0} : new byte[]{(byte)0xFF,(byte)0xF8,(byte)0xF0}, false);
                        tStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                        tStyle.setDataFormat(currFmt);
                        org.apache.poi.ss.usermodel.Cell tc = dr.createCell(8);
                        tc.setCellValue(truckSalary); tc.setCellStyle(tStyle);

                        // Total salary
                        org.apache.poi.ss.usermodel.CellStyle tsStyle = makeStyle.apply(
                                totalSalary > 0 ? new byte[]{(byte)0xE8,(byte)0xF5,(byte)0xE9} : rowBg, true);
                        tsStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                        tsStyle.setDataFormat(currFmt);
                        org.apache.poi.ss.usermodel.Cell tsc = dr.createCell(9);
                        tsc.setCellValue(totalSalary); tsc.setCellStyle(tsStyle);
                    }
                }

                // ── Total row ─────────────────────────────────────────────────
                org.apache.poi.ss.usermodel.Row totalRow = ws.createRow(rowIdx);
                totalRow.setHeightInPoints(22);
                org.apache.poi.ss.usermodel.CellStyle totStyle = makeStyle.apply(totalBg, true);
                totStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                totStyle.setDataFormat(currFmt);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle)totStyle).getFont()
                        .setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));
                org.apache.poi.ss.usermodel.CellStyle totLabelStyle = makeStyle.apply(totalBg, true);
                totLabelStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle)totLabelStyle).getFont()
                        .setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));

                org.apache.poi.ss.usermodel.Cell tlCell = totalRow.createCell(0);
                tlCell.setCellValue("TỔNG CỘNG (" + names.size() + " tài xế)");
                tlCell.setCellStyle(totLabelStyle);
                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 0, hasSalary ? 8 : 6));
                for (int ci = 1; ci <= (hasSalary ? 8 : 6); ci++) {
                    org.apache.poi.ss.usermodel.Cell blank = totalRow.createCell(ci);
                    blank.setCellStyle(totLabelStyle);
                }
                if (hasSalary) {
                    org.apache.poi.ss.usermodel.Cell sumCell = totalRow.createCell(9);
                    sumCell.setCellValue(grandTotal);
                    sumCell.setCellStyle(totStyle);
                }

                ws.createFreezePane(0, 4);
                wb.write(bos);
                String fn = "bao-cao-tai-xe-" + fromDisplay.replace("/", "-") + "-" + toDisplay.replace("/", "-") + ".xlsx";
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=\"" + fn + "\"")
                        .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .body(bos.toByteArray());
            }
        } catch (Exception e) {
            log.error("exportDriverReport error", e);
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<UserDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean locked,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(userService.list(q, role, locked, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(userService.getById(id));
    }

    @PostMapping
    public ApiResponse<UserDto> create(@Valid @RequestBody CreateUserRequest req) {
        return ApiResponse.ok("Tạo user thành công", userService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserDto> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateUserRequest req) {
        return ApiResponse.ok("Cập nhật user thành công", userService.update(id, req));
    }

    /** Khóa / mở khóa user — khi khóa, user không thao tác được gì */
    @PutMapping("/{id}/lock")
    public ApiResponse<UserDto> setLocked(@PathVariable Long id,
                                          @RequestParam boolean value) {
        String msg = value ? "Khóa user thành công" : "Mở khóa user thành công";
        return ApiResponse.ok(msg, userService.setLocked(id, value));
    }

    @PutMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("newPassword"));
        return ApiResponse.ok("Đổi mật khẩu thành công", null);
    }
}
