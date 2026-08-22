package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverDayDto;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverMonthDto;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverOrderDto;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.DriverAttendance;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;

import java.math.BigDecimal;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.DriverAttendanceRepository;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SỐ KM CHẠY TRONG NGÀY CỦA TÀI XẾ.
 *
 * <p>Tài xế KHÔNG có bảng chấm công. Thay vào đó, trang "Quản lý lương" của tài
 * xế hiển thị lịch tháng y hệt các bộ phận khác, nhưng bấm vào 1 ngày thì cột
 * phải hiện <b>tổng số km đã chạy trong ngày đó</b>.
 *
 * <h3>Nguồn số liệu — ưu tiên giảm dần</h3>
 * <ol>
 *   <li><b>ODOMETER</b> — nhân viên kho chốt odo Vào ca / Kết ca trong
 *       {@link DriverAttendance}. Hiệu số odo chính là số km THẬT của ngày đó.
 *       Tài xế dùng cả xe máy lẫn xe tải thì cộng km của cả hai loại xe.</li>
 *   <li><b>ESTIMATED</b> — không có odo thì ước tính từ các đơn hàng ĐÃ và
 *       ĐANG GIAO được phân công cho tài xế trong ngày:
 *       <pre>km ≈ Σ(số chuyến của đơn) × {@link #kmPerTrip}</pre></li>
 * </ol>
 *
 * <p>Số km ước tính mỗi chuyến cấu hình bằng {@code app.driver.km-per-trip}
 * trong {@code application.yml} (mặc định 20 km/chuyến — quãng đường trung bình
 * kho → khách trong nội thành và ngược lại).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverKmService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Đơn được tính km: đã giao xong hoặc đang trên đường giao. */
    private static final Set<OrderStatus> COUNTED_STATUSES = EnumSet.of(
            OrderStatus.DELIVERING,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.COMPLETED
    );

    private static final Map<OrderStatus, String> STATUS_LABELS = Map.of(
            OrderStatus.DELIVERING, "Đang giao",
            OrderStatus.PENDING_PAYMENT, "Đã giao — chờ thanh toán",
            OrderStatus.COMPLETED, "Hoàn thành"
    );

    /** Số km ƯỚC TÍNH cho mỗi chuyến khi không có dữ liệu odo. */
    @Value("${app.driver.km-per-trip:20}")
    private double kmPerTrip;

    private final OrderRepository orderRepo;
    private final DriverRepository driverRepo;
    private final DriverAttendanceRepository driverAttendanceRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // API CHÍNH
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Toàn bộ ngày trong tháng của 1 tài xế — luôn trả đủ số ngày của tháng để
     * FE dựng lịch, ngày không chạy thì {@code totalKm = 0}.
     */
    @Transactional(readOnly = true)
    public DriverMonthDto monthOf(User user, int month, int year) {
        Driver driver = driverRepo.findByUser_Id(user.getId()).orElse(null);

        YearMonth ym = YearMonth.of(year, month);
        int len = ym.lengthOfMonth();

        // ── Gom đơn theo NGÀY GIAO + tách theo LOẠI XE ────────────────────────
        Map<Integer, List<DriverOrderDto>> ordersByDay = new HashMap<>();
        Map<Integer, Integer> tripsByDay = new HashMap<>();
        // Tổng theo loại xe cho cả tháng (dùng để tính lương xe máy vs xe tải riêng)
        int monthTripsMoto = 0, monthTripsTruck = 0;
        int monthOrdersMoto = 0, monthOrdersTruck = 0;

        if (driver != null) {
            long from = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
            long to = ym.atEndOfMonth().atTime(23, 59, 59).atZone(VN).toInstant().toEpochMilli();

            for (Order o : orderRepo.findAll()) {
                if (o.getStatus() == null || !COUNTED_STATUSES.contains(o.getStatus())) continue;

                Long ts = deliveryTimestamp(o);
                if (ts == null || ts < from || ts > to) continue;

                // Có thể 1 đơn phân công cùng tài xế với CẢ hai loại xe (hiếm) → duyệt hết.
                List<int[]> assignments = tripsByTypeOf(o, driver);   // [ [tripsMoto, tripsTruck] ... ]
                int totalTripsForOrder = 0, tripsMoto = 0, tripsTruck = 0;
                for (int[] a : assignments) { tripsMoto += a[0]; tripsTruck += a[1]; }
                totalTripsForOrder = tripsMoto + tripsTruck;
                if (totalTripsForOrder <= 0) continue;

                int day = Instant.ofEpochMilli(ts).atZone(VN).getDayOfMonth();

                // Tình trạng thanh toán:
                BigDecimal finalAmt = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
                BigDecimal paidAmt  = o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO;
                String payStatus;
                if (paidAmt.signum() <= 0) payStatus = "UNPAID";
                else if (paidAmt.compareTo(finalAmt) >= 0) payStatus = "PAID";
                else payStatus = "PARTIAL";
                String payStatusLabel = switch (payStatus) {
                    case "PAID" -> "Đã thanh toán";
                    case "PARTIAL" -> "Thanh toán 1 phần";
                    default -> "Chưa thanh toán";
                };

                ordersByDay.computeIfAbsent(day, k -> new ArrayList<>()).add(DriverOrderDto.builder()
                        .orderId(o.getId())
                        .orderCode(o.getOrderCode())
                        .status(o.getStatus().name())
                        .statusLabel(STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus().name()))
                        .customerName(firstNonBlank(o.getCompanyName(), o.getCustomerName()))
                        .deliveryAddress(firstNonBlank(
                                o.getDeliveryAddress(), o.getShippingAddress(), o.getCompanyAddress()))
                        .warehouseName(o.getWarehouseName())
                        .trips(totalTripsForOrder)
                        .tripsMotorbike(tripsMoto)
                        .tripsTruck(tripsTruck)
                        .km(round1(totalTripsForOrder * kmPerTrip))
                        .finalAmount(finalAmt.setScale(0, java.math.RoundingMode.HALF_UP))
                        .paidAmount(paidAmt.setScale(0, java.math.RoundingMode.HALF_UP))
                        .paymentStatus(payStatus)
                        .paymentStatusLabel(payStatusLabel)
                        .placedAt(o.getCreatedAt())
                        .deliveredAt(ts)                 // thời gian kho bắt đầu giao (hoặc fallback)
                        .build());

                tripsByDay.merge(day, totalTripsForOrder, Integer::sum);
                if (tripsMoto > 0)  { monthTripsMoto  += tripsMoto;  monthOrdersMoto++; }
                if (tripsTruck > 0) { monthTripsTruck += tripsTruck; monthOrdersTruck++; }
            }
        }

        // ── Km thật từ odo (nếu nhân viên kho có chốt) ────────────────────────
        Map<Integer, List<FactoryPayrollDtos.DriverOdoDetailDto>> odoDetailByDay = driver != null
                ? odometerDetailByDay(driver, ym)
                : Map.of();

        // ── Dựng đủ các ngày của tháng ───────────────────────────────────────
        List<DriverDayDto> days = new ArrayList<>(len);
        double totalKm = 0;
        int totalOrders = 0, activeDays = 0, totalTrips = 0;

        for (int d = 1; d <= len; d++) {
            LocalDate date = LocalDate.of(year, month, d);
            int weekday = date.getDayOfWeek() == DayOfWeek.SUNDAY ? 8
                    : date.getDayOfWeek().getValue() + 1;

            List<DriverOrderDto> orders = ordersByDay.getOrDefault(d, List.of());
            int trips = tripsByDay.getOrDefault(d, 0);

            List<FactoryPayrollDtos.DriverOdoDetailDto> odoDetail = odoDetailByDay.get(d);
            double odoKm = kmFromDetail(odoDetail);
            boolean hasOdo = hasOdoRecord(odoDetail);
            boolean useOdo = hasOdo;   // có chốt odo thì ưu tiên số thật (kể cả km = 0)
            double km = useOdo ? odoKm : round1(trips * kmPerTrip);

            // Màu ô lịch:
            //   GREEN  = trong ngày có cả điểm danh START và END (dù ở 2 loại xe khác nhau)
            //   YELLOW = chỉ có 1 trong 2 (thiếu start hoặc thiếu end)
            //   GRAY   = không có điểm danh nào
            boolean anyStart = false, anyEnd = false;
            if (odoDetail != null) for (var o : odoDetail) {
                if (o.getOdoStart() != null) anyStart = true;
                if (o.getOdoEnd() != null) anyEnd = true;
            }
            String dayColor;
            if (anyStart && anyEnd) dayColor = "GREEN";
            else if (anyStart || anyEnd) dayColor = "YELLOW";
            else dayColor = "GRAY";

            if (km > 0) { totalKm += km; activeDays++; }
            totalOrders += orders.size();
            totalTrips += trips;

            days.add(DriverDayDto.builder()
                    .day(d).weekday(weekday).weekdayLabel(weekdayLabel(weekday))
                    .orderCount(orders.size())
                    .trips(trips)
                    .totalKm(round1(km))
                    .kmSource(useOdo ? "ODOMETER" : "ESTIMATED")
                    .dayColor(dayColor)
                    .odo(odoDetail != null ? odoDetail : List.of())
                    .orders(orders)
                    .build());
        }

        return DriverMonthDto.builder()
                .month(month).year(year)
                .driverId(driver != null ? driver.getId() : null)
                .driverName(driver != null ? driver.getName() : user.getFullName())
                .vehicleType(driver != null && driver.getVehicleType() != null
                        ? driver.getVehicleType().name() : null)
                .totalKm(round1(totalKm))
                .totalOrders(totalOrders)
                .totalTrips(totalTrips)
                .totalOrdersMotorbike(monthOrdersMoto)
                .totalOrdersTruck(monthOrdersTruck)
                .totalTripsMotorbike(monthTripsMoto)
                .totalTripsTruck(monthTripsTruck)
                .activeDays(activeDays)
                .kmPerTrip(kmPerTrip)
                .days(days)
                .build();
    }

    /** Tổng km cả tháng — dùng cho bảng tổng hợp của OWNER. */
    @Transactional(readOnly = true)
    public double totalKmOf(User user, int month, int year) {
        DriverMonthDto m = monthOf(user, month, year);
        return m.getTotalKm() != null ? m.getTotalKm() : 0.0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Chi tiết điểm danh ODO của từng ngày trong tháng.
     * Tài xế loại BOTH có 2 cặp record (MOTORBIKE + TRUCK) → mỗi cặp một dòng.
     */
    private Map<Integer, List<FactoryPayrollDtos.DriverOdoDetailDto>> odometerDetailByDay(
            Driver driver, YearMonth ym) {
        String from = ym.atDay(1).format(ISO_DATE);
        String to = ym.atEndOfMonth().format(ISO_DATE);

        // ngày → loại xe → [startOdo, endOdo, startRecordedBy, endRecordedBy]
        Map<Integer, Map<Driver.VehicleType, Object[]>> raw = new HashMap<>();

        for (DriverAttendance a : driverAttendanceRepo.findByDateRange(from, to)) {
            if (a.getDriver() == null || !Objects.equals(a.getDriver().getId(), driver.getId())) continue;
            if (a.getOdometer() == null || a.getAttendanceDate() == null) continue;

            int day;
            try {
                day = LocalDate.parse(a.getAttendanceDate(), ISO_DATE).getDayOfMonth();
            } catch (Exception e) {
                continue;
            }

            Object[] rec = raw.computeIfAbsent(day, k -> new HashMap<>())
                    .computeIfAbsent(a.getVehicleType(), k -> new Object[]{null, null, null, null});
            if (a.getSessionType() == DriverAttendance.SessionType.START) {
                rec[0] = a.getOdometer();
                rec[2] = a.getRecordedBy();
            } else {
                rec[1] = a.getOdometer();
                rec[3] = a.getRecordedBy();
            }
        }

        Map<Integer, List<FactoryPayrollDtos.DriverOdoDetailDto>> out = new HashMap<>();
        raw.forEach((day, byVehicle) -> {
            List<FactoryPayrollDtos.DriverOdoDetailDto> list = new ArrayList<>();
            byVehicle.forEach((vt, rec) -> {
                Integer start = (Integer) rec[0];
                Integer end = (Integer) rec[1];
                Integer km = (start != null && end != null && end > start) ? (end - start) : 0;
                list.add(FactoryPayrollDtos.DriverOdoDetailDto.builder()
                        .vehicleType(vt != null ? vt.name() : null)
                        .odoStart(start).odoEnd(end).km(km)
                        .startRecordedBy((String) rec[2])
                        .endRecordedBy((String) rec[3])
                        .build());
            });
            out.put(day, list);
        });
        return out;
    }

    /** Tổng km thật của một ngày từ chi tiết ODO (cộng dồn các loại xe). */
    private static double kmFromDetail(List<FactoryPayrollDtos.DriverOdoDetailDto> detail) {
        if (detail == null) return 0;
        double km = 0;
        for (FactoryPayrollDtos.DriverOdoDetailDto d : detail)
            if (d.getKm() != null) km += d.getKm();
        return km;
    }

    /** Có điểm danh ODO trong ngày không (dù km = 0). */
    private static boolean hasOdoRecord(List<FactoryPayrollDtos.DriverOdoDetailDto> detail) {
        if (detail == null || detail.isEmpty()) return false;
        for (FactoryPayrollDtos.DriverOdoDetailDto d : detail)
            if (d.getOdoStart() != null || d.getOdoEnd() != null) return true;
        return false;
    }

    /**
     * NGÀY GIAO của đơn — ưu tiên {@code deliveryDatetime} (lịch giao đã hẹn),
     * fallback {@code pendingPaymentAt} (thời điểm tài xế bấm giao xong), cuối
     * cùng mới tới {@code createdAt}.
     */
    private Long deliveryTimestamp(Order o) {
        if (o.getDeliveryDatetime() != null && o.getDeliveryDatetime() > 0) return o.getDeliveryDatetime();
        if (o.getPendingPaymentAt() != null && o.getPendingPaymentAt() > 0) return o.getPendingPaymentAt();
        return o.getCreatedAt();
    }

    /**
     * Số chuyến của tài xế này trên đơn, TÁCH THEO LOẠI XE — mỗi phần tử trả về
     * là {@code [tripsMoto, tripsTruck]}. Mỗi đơn có thể có nhiều phần tử nếu tài
     * xế được phân công với cả hai loại xe trên cùng đơn (hiếm nhưng khả dĩ).
     */
    private List<int[]> tripsByTypeOf(Order o, Driver driver) {
        List<int[]> out = new ArrayList<>();
        for (Map<String, Object> d : parseDeliveryInfo(o)) {
            Object id = d.get("driverId");
            Object name = d.get("name");
            boolean hit = (id instanceof Number n && n.longValue() == driver.getId())
                    || (name != null && driver.getName() != null
                    && normalize(String.valueOf(name)).equals(normalize(driver.getName())));
            if (!hit) continue;

            Object t = d.get("trips");
            int trips = t instanceof Number n2 ? Math.max(1, n2.intValue()) : 1;
            String type = String.valueOf(d.getOrDefault("type", "")).toUpperCase();
            if ("TRUCK".equals(type)) out.add(new int[]{0, trips});
            else                       out.add(new int[]{trips, 0});   // mặc định coi là xe máy
        }
        return out;
    }

    private List<Map<String, Object>> parseDeliveryInfo(Order order) {
        String json = order.getDeliveryInfoJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String weekdayLabel(int weekday) {
        return switch (weekday) {
            case 2 -> "Hai"; case 3 -> "Ba";  case 4 -> "Tư";
            case 5 -> "Năm"; case 6 -> "Sáu"; case 7 -> "Bảy";
            default -> "CN";
        };
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("\\s+", " ");
    }
}