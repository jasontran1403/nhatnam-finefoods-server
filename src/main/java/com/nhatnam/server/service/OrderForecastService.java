package com.nhatnam.server.service;

import com.nhatnam.server.dto.forecast.OrderForecastDtos.ForecastResponse;
import com.nhatnam.server.dto.forecast.OrderForecastDtos.ForecastRow;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.CustomerContactLog;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.CustomerContactLogRepository;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.utils.AnniversaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DỰ BÁO NGÀY KHÁCH CẦN ĐẶT HÀNG LẠI.
 *
 * <p>Ý tưởng: khách mua hàng theo nhịp khá đều. Lấy khoảng cách giữa các lần đặt gần đây,
 * trung bình lại được "chu kỳ", cộng vào ngày đặt gần nhất ra ngày dự kiến khách hết hàng.
 * Tới ngày đó thì seller chủ động gọi chào hàng thay vì ngồi đợi khách gọi tới.
 *
 * <h3>Công thức (theo đúng ví dụ nghiệp vụ)</h3>
 * Khách A đặt các ngày 15/7, 21/7, 22/7, 2/8, 2/8:
 * <ul>
 *   <li>Khoảng cách thô: 6, 1, 11, 0 ngày.</li>
 *   <li>Hai đơn TRONG CÙNG MỘT NGÀY vẫn được tính là chu kỳ <b>1 ngày</b> (không phải 0)
 *       — khách đặt bổ sung trong ngày là chuyện thường, để 0 sẽ kéo trung bình xuống
 *       và làm hệ thống giục khách sớm hơn thực tế rất nhiều.</li>
 *   <li>Trung bình = (6+1+11+1)/4 = 4,75 ⇒ <b>làm tròn xuống</b> = 4 ngày.</li>
 * </ul>
 * Ngày dự kiến = ngày đặt gần nhất + chu kỳ. Tới hoặc quá ngày đó ⇒ hiện lên màn hình.
 *
 * <h3>Vì sao "đánh dấu đã gọi" không loại khách khỏi danh sách</h3>
 * Đánh dấu chỉ đổi style thành "đã liên hệ" kèm ngày giờ. Khách chưa chốt đơn thì hôm sau
 * vào lại vẫn thấy — nếu ẩn đi, một khách gọi hôm nay mà chưa mua sẽ biến mất khỏi tầm mắt
 * và không ai nhớ gọi lại. Bản ghi đánh dấu gắn theo NGÀY nên sang ngày mới tự hết hiệu lực.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class OrderForecastService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final CustomerContactLogRepository contactLogRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Số lần đặt gần nhất được dùng để tính chu kỳ. */
    private static final int MAX_ORDERS_CONSIDERED = 12;

    /**
     * CỬA SỔ "GẦN ĐẾN HẠN" (ngày).
     *
     * <p>Khách còn cách ngày dự kiến trong khoảng này vẫn được đưa lên màn hình để seller
     * chuẩn bị trước. Xa hơn thì ẩn: gọi khách trước cả tuần khi họ còn đầy hàng chỉ làm
     * phiền, và danh sách dài ra sẽ che mất nhóm thật sự cần gọi hôm nay.
     */
    private static final int UPCOMING_WINDOW_DAYS = 7;

    /** Đơn ở các trạng thái này KHÔNG phản ánh nhu cầu thật ⇒ loại khỏi phép tính. */
    private static final Set<OrderStatus> IGNORED_STATUSES =
            EnumSet.of(OrderStatus.CANCELLED, OrderStatus.FAILED);

    /**
     * @param seller       người đang đăng nhập
     * @param q            tìm theo tên khách / tên công ty / SĐT
     * @param onlyDue      true = chỉ trả khách tới hạn (mặc định của màn hình);
     *                     false = trả tất cả khách có đủ dữ liệu để dự báo
     */
    @Transactional(readOnly = true)
    public ForecastResponse forecast(User seller, String q, boolean onlyDue) {
        LocalDate today = AnniversaryUtil.today();
        String todayKey = today.format(DATE_FMT);

        List<Customer> customers = _visibleCustomers(seller, q);
        if (customers.isEmpty()) {
            return ForecastResponse.builder()
                    .asOfDate(todayKey).dueCount(0).contactedCount(0).rows(List.of()).build();
        }

        List<Long> ids = customers.stream().map(Customer::getId).filter(Objects::nonNull).toList();

        // Gom lịch sử đặt hàng theo khách — 1 query cho toàn bộ danh sách.
        Map<Long, List<LocalDate>> orderDates = orderRepository.findByCustomerIdIn(ids).stream()
                .filter(o -> o.getCustomer() != null && o.getCreatedAt() != null)
                .filter(o -> o.getStatus() == null || !IGNORED_STATUSES.contains(o.getStatus()))
                .collect(Collectors.groupingBy(
                        o -> o.getCustomer().getId(),
                        Collectors.mapping(
                                (Order o) -> AnniversaryUtil.toLocalDate(o.getCreatedAt()),
                                Collectors.toList())));

        // Trạng thái đã liên hệ HÔM NAY của chính seller này.
        Map<Long, CustomerContactLog> contactedToday =
                contactLogRepository.findBySellerIdAndContactDate(seller.getId(), todayKey)
                        .stream()
                        .collect(Collectors.toMap(CustomerContactLog::getCustomerId, l -> l, (a, b) -> a));

        List<ForecastRow> rows = new ArrayList<>();
        for (Customer c : customers) {
            ForecastRow row = _buildRow(c, orderDates.get(c.getId()), today, contactedToday.get(c.getId()));
            if (row == null) continue;                 // không đủ dữ liệu để dự báo
            // onlyDue = true: giữ khách tới hạn/đã trễ VÀ khách sắp tới hạn trong cửa sổ.
            // Nhóm "gần đến hạn" phải có mặt, nếu không thứ tự 3 nhóm bên dưới vô nghĩa.
            if (onlyDue && !row.isDueNow()
                    && row.getOverdueDays() < -UPCOMING_WINDOW_DAYS) continue;
            rows.add(row);
        }

        // Thứ tự hiển thị: ĐẾN HẠN HÔM NAY → GẦN ĐẾN HẠN → ĐÃ TRỄ. Xem _displayRank.
        rows.sort(Comparator
                .comparingInt(OrderForecastService::_displayRank)
                .thenComparingInt(OrderForecastService::_withinGroupOrder)
                .thenComparing(ForecastRow::isContactedToday)
                .thenComparing(ForecastRow::getDisplayName,
                        Comparator.nullsLast(String::compareToIgnoreCase)));

        int dueCount = (int) rows.stream().filter(ForecastRow::isDueNow).count();
        int contactedCount = (int) rows.stream().filter(ForecastRow::isContactedToday).count();

        return ForecastResponse.builder()
                .asOfDate(todayKey)
                .dueCount(dueCount)
                .contactedCount(contactedCount)
                .rows(rows)
                .build();
    }

    /**
     * ĐÁNH DẤU ĐÃ GỌI cho hôm nay. Gọi lại lần nữa trong ngày chỉ cập nhật ghi chú,
     * không tạo bản ghi trùng (chống double-click).
     */
    @Transactional
    public CustomerContactLog markContacted(User seller, Long customerId, String note) {
        String todayKey = AnniversaryUtil.today().format(DATE_FMT);
        long now = System.currentTimeMillis();
        String sellerName = seller.getFullName() != null ? seller.getFullName() : seller.getUsername();

        CustomerContactLog log = contactLogRepository
                .findByCustomerIdAndSellerIdAndContactDate(customerId, seller.getId(), todayKey)
                .orElseGet(() -> CustomerContactLog.builder()
                        .customerId(customerId)
                        .sellerId(seller.getId())
                        .contactDate(todayKey)
                        .build());

        log.setContactedAt(now);
        log.setSellerName(sellerName);
        if (note != null && !note.isBlank()) log.setNote(note.trim());
        return contactLogRepository.save(log);
    }

    /** Bỏ đánh dấu (bấm nhầm). */
    @Transactional
    public void unmarkContacted(User seller, Long customerId) {
        String todayKey = AnniversaryUtil.today().format(DATE_FMT);
        contactLogRepository
                .findByCustomerIdAndSellerIdAndContactDate(customerId, seller.getId(), todayKey)
                .ifPresent(contactLogRepository::delete);
    }

    // ── Thứ tự hiển thị ──────────────────────────────────────────────────────

    /**
     * NHÓM HIỂN THỊ — quyết định thứ tự lớn của danh sách.
     *
     * <pre>
     *   0 = Đến hạn HÔM NAY   (overdueDays == 0)
     *   1 = GẦN đến hạn       (overdueDays &lt; 0, tức còn vài ngày nữa)
     *   2 = ĐÃ TRỄ dự báo     (overdueDays &gt; 0)
     * </pre>
     *
     * <p><b>Vì sao khách đã trễ lại xếp SAU cùng, không phải đầu tiên?</b> Trễ càng lâu thì
     * khả năng khách đã mua chỗ khác hoặc đã ngừng nhập càng cao — gọi họ là việc "vớt vát",
     * còn khách đến hạn đúng hôm nay mới là cơ hội chốt đơn còn nóng. Sắp xếp theo mức độ
     * cấp bách của cơ hội, không theo mức độ trễ.
     */
    private static int _displayRank(ForecastRow r) {
        int d = r.getOverdueDays();
        if (d == 0) return 0;
        if (d < 0)  return 1;
        return 2;
    }

    /**
     * Thứ tự TRONG từng nhóm — cả hai nhóm đều xếp theo "gần hiện tại nhất trước".
     *
     * <ul>
     *   <li>Nhóm gần đến hạn: còn 1 ngày trước còn 5 ngày ⇒ dùng {@code |overdueDays|}.</li>
     *   <li>Nhóm đã trễ: trễ 1 ngày trước trễ 10 ngày ⇒ dùng chính {@code overdueDays}.</li>
     * </ul>
     * Cả hai quy về cùng một biểu thức {@code abs(overdueDays)} nên viết chung được.
     */
    private static int _withinGroupOrder(ForecastRow r) {
        return Math.abs(r.getOverdueDays());
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * SELLER chỉ thấy khách ĐƯỢC GÁN cho mình. SUPER_SELLER / ADMIN / OWNER thấy tất cả.
     *
     * <p>"Được gán" nhận cả hai đường: {@code assignedSeller} (gán trực tiếp) và
     * {@code createdBySeller} (khách do chính seller đó tạo). Hai cột này tồn tại song song
     * trong dữ liệu cũ; chỉ đọc một cột sẽ làm mất một phần danh sách khách của seller.
     */
    private List<Customer> _visibleCustomers(User seller, String q) {
        Set<Role> roles = seller.getAllRoles();
        boolean seeAll = roles.contains(Role.ADMIN) || roles.contains(Role.OWNER)
                || roles.contains(Role.SUPERADMIN) || roles.contains(Role.SUPER_SELLER);

        String needle = (q == null || q.isBlank()) ? null : q.toLowerCase().trim();

        return customerRepository.findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .filter(c -> seeAll || _assignedTo(c, seller.getId()))
                .filter(c -> needle == null || _matches(c, needle))
                .toList();
    }

    /**
     * KHÁCH NÀY CÓ THUỘC TẦM NHÌN CỦA SELLER KHÔNG.
     *
     * <p>Quy tắc: khách <b>chưa được gán</b> cho seller nào thì <b>ai cũng thấy và thao tác
     * được</b> — khách lẻ vãng lai, ai tiếp thì người đó chăm. Khách <b>đã được gán</b> thì
     * <b>chỉ seller được gán</b> mới thấy.
     *
     * <p>Khi chưa có {@code assignedSeller}, người TẠO ra khách vẫn giữ quyền riêng: dữ liệu
     * cũ có nhiều khách chỉ mang {@code createdBySeller}, nếu coi hết là "chưa gán" thì công
     * sức tìm khách của seller cũ bị chia đều cho cả phòng.
     */
    private boolean _assignedTo(Customer c, long sellerId) {
        User assigned = c.getAssignedSeller();
        if (assigned != null) return assigned.getId() == sellerId;

        User creator = c.getCreatedBySeller();
        return creator == null || creator.getId() == sellerId;
    }

    private boolean _matches(Customer c, String needle) {
        return (c.getName() != null && c.getName().toLowerCase().contains(needle))
                || (c.getCompanyName() != null && c.getCompanyName().toLowerCase().contains(needle))
                || (c.getContactName() != null && c.getContactName().toLowerCase().contains(needle))
                || (c.getPhone() != null && c.getPhone().contains(needle))
                || (c.getCompanyPhone() != null && c.getCompanyPhone().contains(needle))
                || (c.getCustomerCode() != null && c.getCustomerCode().toLowerCase().contains(needle));
    }

    /**
     * @return null nếu khách có DƯỚI 2 đơn — một đơn duy nhất không cho biết nhịp mua nào cả,
     *         đoán bừa chu kỳ sẽ đẩy khách mới vào danh sách gọi ngay hôm sau.
     */
    private ForecastRow _buildRow(Customer c, List<LocalDate> dates, LocalDate today,
                                  CustomerContactLog contactLog) {
        if (dates == null || dates.size() < 2) return null;

        List<LocalDate> sorted = dates.stream().filter(Objects::nonNull).sorted().toList();
        if (sorted.size() < 2) return null;

        // Chỉ xét N lần gần nhất — nhịp mua của một năm trước không còn phản ánh hiện tại.
        List<LocalDate> recent = sorted.size() > MAX_ORDERS_CONSIDERED
                ? sorted.subList(sorted.size() - MAX_ORDERS_CONSIDERED, sorted.size())
                : sorted;

        long totalGap = 0;
        int gapCount = 0;
        for (int i = 1; i < recent.size(); i++) {
            long gap = java.time.temporal.ChronoUnit.DAYS.between(recent.get(i - 1), recent.get(i));
            totalGap += Math.max(1L, gap);   // cùng ngày ⇒ tính 1 ngày (xem javadoc lớp)
            gapCount++;
        }
        if (gapCount == 0) return null;

        int avgCycle = (int) (totalGap / gapCount);      // chia số nguyên = làm tròn xuống
        if (avgCycle < 1) avgCycle = 1;

        LocalDate lastOrder = recent.get(recent.size() - 1);
        LocalDate predicted = lastOrder.plusDays(avgCycle);
        int overdue = (int) java.time.temporal.ChronoUnit.DAYS.between(predicted, today);

        String display = c.getCustomerType() == Customer.CustomerType.COMPANY
                && c.getCompanyName() != null && !c.getCompanyName().isBlank()
                ? c.getCompanyName()
                : (c.getName() != null ? c.getName() : "KH#" + c.getId());

        return ForecastRow.builder()
                .customerId(c.getId())
                .customerCode(c.getCustomerCode())
                .customerType(c.getCustomerType() != null ? c.getCustomerType().name() : null)
                .displayName(display)
                .contactName(c.getContactName())
                .phone(c.getPhone() != null ? c.getPhone() : c.getCompanyPhone())
                .orderCount(sorted.size())
                .avgCycleDays(avgCycle)
                .lastOrderAt(AnniversaryUtil.toEpochMillis(lastOrder))
                .predictedNextOrderAt(AnniversaryUtil.toEpochMillis(predicted))
                .overdueDays(overdue)
                .dueNow(overdue >= 0)
                .contactedToday(contactLog != null)
                .contactedAt(contactLog != null ? contactLog.getContactedAt() : null)
                .contactedBy(contactLog != null ? contactLog.getSellerName() : null)
                .contactNote(contactLog != null ? contactLog.getNote() : null)
                .birthday(c.getBirthday())
                .storeOpeningDate(c.getStoreOpeningDate())
                .build();
    }
}
