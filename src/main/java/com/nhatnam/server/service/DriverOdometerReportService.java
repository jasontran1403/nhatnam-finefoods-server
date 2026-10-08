package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.driver.DriverOdometerReportDto;
import com.nhatnam.server.dto.driver.DriverOrderDetailDto;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.DriverAttendance;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.repository.DriverAttendanceRepository;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * BÁO CÁO ODO TÀI XẾ (màn OWNER/ADMIN).
 *
 * <p>Kho đã nhập điểm danh ODO mỗi ngày qua {@link DriverAttendance} (mỗi ngày tối đa
 * 2 bản ghi mỗi loại xe: START = vào ca, END = kết ca). Màn này tổng hợp lại theo một
 * KHOẢNG NGÀY do người dùng chọn.
 *
 * <h3>Cách tính KM</h3>
 * KM được tính theo từng NGÀY, không phải lấy mốc đầu kỳ - cuối kỳ.
 * <ul>
 *   <li>Với mỗi ngày trong kỳ, nếu có cả START và END → km_ngày = END.odo - START.odo</li>
 *   <li>Nếu chỉ có START hoặc chỉ có END → km_ngày = 0</li>
 *   <li>Nếu không có điểm danh nào → km_ngày = 0</li>
 *   <li>Sum km các ngày theo từng loại xe → km của loại xe đó</li>
 *   <li>totalKm = sum km của tất cả loại xe</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverOdometerReportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DriverRepository driverRepo;
    private final DriverAttendanceRepository attendanceRepo;
    private final OrderRepository orderRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // BÁO CÁO TỔNG HỢP
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * @param from ngày bắt đầu "yyyy-MM-dd"
     * @param to   ngày kết thúc "yyyy-MM-dd"
     * @param includeInactive có lấy cả tài xế đã ngưng hoạt động không
     */
    public List<DriverOdometerReportDto> report(String from, String to, boolean includeInactive) {
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        if (toDate.isBefore(fromDate))
            throw new IllegalArgumentException("Ngày kết thúc phải sau ngày bắt đầu");

        // Chỉ tài xế THẬT: systemDriver = true là "không xử lý" (Grab, Giao tại kho,
        // Khách tự lấy…) — không có công-tơ-mét nên không đưa vào báo cáo.
        List<Driver> drivers = driverRepo.findBySystemDriverFalseOrderByNameAsc().stream()
                .filter(d -> includeInactive || d.isActive())
                .sorted(Comparator.comparing(Driver::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        // Nạp MỘT lần toàn bộ điểm danh trong kỳ rồi gom theo tài xế — tránh N+1
        Map<Long, List<DriverAttendance>> byDriver = new HashMap<>();
        for (DriverAttendance a : attendanceRepo.findByDateRange(from, to)) {
            if (a.getDriver() == null) continue;
            byDriver.computeIfAbsent(a.getDriver().getId(), k -> new ArrayList<>()).add(a);
        }

        // Đếm đơn theo tài xế trong cùng khoảng
        Map<Long, Integer> orderCountByDriver = countOrdersByDriver(fromDate, toDate);

        List<DriverOdometerReportDto> result = new ArrayList<>();
        for (Driver d : drivers) {
            List<DriverAttendance> records = byDriver.getOrDefault(d.getId(), List.of());

            List<DriverOdometerReportDto.VehicleOdometer> vehicles = new ArrayList<>();
            Integer totalKm = null;

            for (Driver.VehicleType vt : vehicleTypesOf(d)) {
                List<DriverAttendance> ofVehicle = records.stream()
                        .filter(a -> a.getVehicleType() == vt).toList();

                DriverOdometerReportDto.VehicleOdometer vo =
                        buildVehicleOdometer(vt, ofVehicle, fromDate, toDate);
                vehicles.add(vo);

                if (vo.getKm() != null) {
                    totalKm = (totalKm == null ? 0 : totalKm) + vo.getKm();
                }
            }

            result.add(DriverOdometerReportDto.builder()
                    .driverId(d.getId())
                    .driverName(d.getName())
                    .vehicleType(d.getVehicleType() != null ? d.getVehicleType().name() : null)
                    .active(d.isActive())
                    .vehicles(vehicles)
                    .totalKm(totalKm)
                    .orderCount(orderCountByDriver.getOrDefault(d.getId(), 0))
                    .build());
        }
        return result;
    }

    /** Loại xe cần xét: BOTH thì xét cả hai đồng hồ. */
    private List<Driver.VehicleType> vehicleTypesOf(Driver d) {
        if (d.getVehicleType() == Driver.VehicleType.BOTH)
            return List.of(Driver.VehicleType.MOTORBIKE, Driver.VehicleType.TRUCK);
        if (d.getVehicleType() == null)
            return List.of(Driver.VehicleType.MOTORBIKE, Driver.VehicleType.TRUCK);
        return List.of(d.getVehicleType());
    }

    /**
     * Tính ODO và KM theo từng ngày cho một loại xe.
     *
     * <p>KM = sum(END.odo - START.odo) cho những ngày có đủ cả START và END.
     * Ngày thiếu START hoặc END → km_ngày = 0.
     */
    private DriverOdometerReportDto.VehicleOdometer buildVehicleOdometer(
            Driver.VehicleType vt, List<DriverAttendance> records,
            LocalDate fromDate, LocalDate toDate) {

        // Gom theo ngày
        Map<String, Map<DriverAttendance.SessionType, DriverAttendance>> byDate = new LinkedHashMap<>();
        for (DriverAttendance a : records) {
            byDate.computeIfAbsent(a.getAttendanceDate(), k -> new EnumMap<>(DriverAttendance.SessionType.class))
                    .merge(a.getSessionType(), a,
                            (x, y) -> (y.getUpdatedAt() != null ? y.getUpdatedAt() : 0L)
                                    >= (x.getUpdatedAt() != null ? x.getUpdatedAt() : 0L) ? y : x);
        }

        // ── Dữ liệu đầu kỳ và cuối kỳ (để hiển thị mốc) ──────────────────
        // Đầu kỳ: tiến dần từ ngày bắt đầu, ưu tiên START
        DriverAttendance firstStart = null;
        DriverAttendance firstEnd = null;
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            var sessions = byDate.get(d.toString());
            if (sessions == null || sessions.isEmpty()) continue;
            firstStart = sessions.get(DriverAttendance.SessionType.START);
            firstEnd = sessions.get(DriverAttendance.SessionType.END);
            if (firstStart != null || firstEnd != null) break;
        }

        // Cuối kỳ: lùi dần từ ngày kết thúc, ưu tiên END
        DriverAttendance lastStart = null;
        DriverAttendance lastEnd = null;
        for (LocalDate d = toDate; !d.isBefore(fromDate); d = d.minusDays(1)) {
            var sessions = byDate.get(d.toString());
            if (sessions == null || sessions.isEmpty()) continue;
            lastStart = sessions.get(DriverAttendance.SessionType.START);
            lastEnd = sessions.get(DriverAttendance.SessionType.END);
            if (lastStart != null || lastEnd != null) break;
        }

        // ── TÍNH KM THEO TỪNG NGÀY ──────────────────────────────────────
        int totalKm = 0;
        int daysWithData = 0;

        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            var sessions = byDate.get(d.toString());
            if (sessions == null || sessions.isEmpty()) continue;

            DriverAttendance start = sessions.get(DriverAttendance.SessionType.START);
            DriverAttendance end = sessions.get(DriverAttendance.SessionType.END);

            // Chỉ tính km khi có đủ START và END trong cùng ngày
            if (start != null && end != null) {
                int kmDay = end.getOdometer() - start.getOdometer();
                if (kmDay >= 0) { // km không thể âm
                    totalKm += kmDay;
                    daysWithData++;
                } else {
                    log.warn("[DRIVER_ODO] km âm ngày {}: start={}, end={}",
                            d, start.getOdometer(), end.getOdometer());
                }
            }
            // Nếu chỉ có START hoặc chỉ có END → km_ngày = 0, không tính vào daysWithData
        }

        // Xác định mốc đầu/cuối để hiển thị
        DriverAttendance startRecord = firstStart != null ? firstStart : firstEnd;
        DriverAttendance endRecord = lastEnd != null ? lastEnd : lastStart;

        // Nếu có dữ liệu nhưng totalKm = 0 (chỉ có START hoặc END lẻ) thì vẫn trả 0
        boolean hasData = !records.isEmpty();

        List<DriverOdometerReportDto.OdoNote> odoNotes = new ArrayList<>();
        for (DriverAttendance a : records) {
            if (a.getNote() != null && !a.getNote().isBlank()) {
                odoNotes.add(DriverOdometerReportDto.OdoNote.builder()
                        .date(a.getAttendanceDate())
                        .session(a.getSessionType().name())
                        .odometer(a.getOdometer())
                        .note(a.getNote())
                        .build());
            }
        }
        // Sắp theo ngày tăng dần
        odoNotes.sort(Comparator.comparing(DriverOdometerReportDto.OdoNote::getDate));

// ════════════════════════════════════════════════════════════════
// Rồi trong .builder() THÊM 1 dòng:
// ════════════════════════════════════════════════════════════════

        return DriverOdometerReportDto.VehicleOdometer.builder()
                .vehicleType(vt.name())
                .startOdometer(startRecord != null ? startRecord.getOdometer() : null)
                .startDate(startRecord != null ? startRecord.getAttendanceDate() : null)
                .startSession(startRecord != null ? startRecord.getSessionType().name() : null)
                .endOdometer(endRecord != null ? endRecord.getOdometer() : null)
                .endDate(endRecord != null ? endRecord.getAttendanceDate() : null)
                .endSession(endRecord != null ? endRecord.getSessionType().name() : null)
                .km(hasData ? totalKm : null)
                .recordCount(records.size())
                .daysWithData(daysWithData)
                .odoNotes(odoNotes.isEmpty() ? null : odoNotes)   // ← MỚI
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ĐƠN HÀNG CỦA TÀI XẾ
    // ══════════════════════════════════════════════════════════════════════════

    /** Chi tiết các đơn một tài xế đã giao trong khoảng ngày — cho popup "Xem chi tiết". */
    public List<DriverOrderDetailDto> orders(Long driverId, String from, String to) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài xế: " + driverId));

        // Chặn cả ở đây, không chỉ ở danh sách: gọi thẳng API bằng id của
        // "Grab"/"Giao tại kho" cũng không trả dữ liệu.
        if (driver.isSystemDriver())
            throw new IllegalArgumentException(
                    "\"" + driver.getName() + "\" không phải tài xế theo dõi ODO");

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);

        List<DriverOrderDetailDto> result = new ArrayList<>();
        for (Order o : orderRepo.findWithDriversBetween(startMillis(fromDate), endMillis(toDate))) {
            Map<String, Object> match = matchDriver(o.getDeliveryInfoJson(), driver);
            if (match == null) continue;

            long at = o.getDeliveryDatetime() != null ? o.getDeliveryDatetime() : o.getCreatedAt();
            result.add(DriverOrderDetailDto.builder()
                    .orderId(o.getId())
                    .orderCode(o.getOrderCode())
                    .customerName(o.getCustomerName())
                    .deliveryAddress(o.getDeliveryAddress())
                    .status(o.getStatus() != null ? o.getStatus().name() : null)
                    .finalAmount(o.getFinalAmount())
                    .deliveredAt(at)
                    .deliveryDate(LocalDate.ofInstant(
                            java.time.Instant.ofEpochMilli(at), ZONE).toString())
                    .trips(toInt(match.get("trips"), 1))
                    .build());
        }
        return result;
    }

    /** Đếm số đơn mỗi tài xế đã giao trong kỳ (một lượt quét, dùng cho toàn bộ card). */
    private Map<Long, Integer> countOrdersByDriver(LocalDate fromDate, LocalDate toDate) {
        Map<Long, Integer> counts = new HashMap<>();
        Map<String, Long> nameToId = new HashMap<>();
        for (Driver d : driverRepo.findBySystemDriverFalseOrderByNameAsc())
            if (d.getName() != null)
                nameToId.put(d.getName().trim().toLowerCase(), d.getId());

        for (Order o : orderRepo.findWithDriversBetween(startMillis(fromDate), endMillis(toDate))) {
            for (Map<String, Object> info : parseDeliveryInfo(o.getDeliveryInfoJson())) {
                Long id = resolveDriverId(info, nameToId);
                if (id != null) counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Tìm mục deliveryInfo ứng với tài xế. Khớp theo {@code driverId}, lùi về khớp
     * TÊN cho dữ liệu cũ — trước đây picker chỉ lưu tên, không lưu id
     * (xem {@code DriverPortalService}).
     */
    private Map<String, Object> matchDriver(String json, Driver driver) {
        String name = driver.getName() != null ? driver.getName().trim().toLowerCase() : null;
        for (Map<String, Object> info : parseDeliveryInfo(json)) {
            Object rawId = info.get("driverId");
            if (rawId != null && String.valueOf(driver.getId()).equals(String.valueOf(rawId)))
                return info;
            Object rawName = info.get("name");
            if (rawId == null && rawName != null && name != null
                    && name.equals(String.valueOf(rawName).trim().toLowerCase()))
                return info;
        }
        return null;
    }

    private Long resolveDriverId(Map<String, Object> info, Map<String, Long> nameToId) {
        Object rawId = info.get("driverId");
        if (rawId != null) {
            try { return Long.valueOf(String.valueOf(rawId)); } catch (NumberFormatException ignored) { }
        }
        Object rawName = info.get("name");
        if (rawName != null) return nameToId.get(String.valueOf(rawName).trim().toLowerCase());
        return null;
    }

    private List<Map<String, Object>> parseDeliveryInfo(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("[DRIVER_ODO] deliveryInfoJson không đọc được: {}", e.getMessage());
            return List.of();
        }
    }

    private int toInt(Object v, int fallback) {
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return fallback; }
    }

    private long startMillis(LocalDate d) {
        return d.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private long endMillis(LocalDate d) {
        return d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1;
    }
}