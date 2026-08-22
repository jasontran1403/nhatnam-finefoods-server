package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.common.StaleOrderDataException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.income.CreateIncomeVoucherRequest;
import com.nhatnam.server.dto.income.IncomeVoucherDto;
import com.nhatnam.server.entity.IncomeItem;
import com.nhatnam.server.entity.IncomeVoucherOrderAllocation;
import com.nhatnam.server.entity.IncomeVoucher;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.IncomeVoucherRepository;
import com.nhatnam.server.repository.IncomeVoucherLogRepository;
import com.nhatnam.server.entity.IncomeVoucherLog;
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
    private final IncomeVoucherLogRepository voucherLogRepo;
    private final UserRepository          userRepository;
    private final NotificationService     notificationService;
    private final ObjectMapper            objectMapper;
    private final OrderRepository         orderRepository;
    private final OrderService            orderService;

    // IncomeVoucherServiceImpl.java — chỉ hàm create(), thay thế toàn bộ hàm cũ

    /**
     * SỬA phiếu thu. Xem hợp đồng ở {@link IncomeVoucherService#update}.
     *
     * <p>Trình tự:
     * <ol>
     *   <li>GỠ tiền phiếu đã ghi cho MỌI đơn liên kết CŨ — trả về chờ thanh toán.
     *       Gỡ trọn phần đã thu vì phiếu cũ không lưu số riêng từng đơn; đằng nào
     *       bước sau cũng áp lại đúng.</li>
     *   <li>Cập nhật thông tin phiếu + thay danh sách items.</li>
     *   <li>Áp lại tiền cho danh sách đơn MỚI, y hệt lúc tạo.</li>
     *   <li>Báo WS cho OWNER / ADMIN / kế toán trưởng.</li>
     * </ol>
     *
     * <p><b>Cảnh báo đồng thời:</b> nếu giữa lúc gỡ và áp lại có phiếu khác đụng
     * cùng đơn thì số liệu có thể lệch. Toàn bộ chạy trong một transaction nên
     * trong phạm vi ứng dụng là an toàn; rủi ro chỉ còn ở mức DB nếu có tiến trình
     * ngoài ghi thẳng — hiện không có.
     */
    @Override
    @Transactional
    public IncomeVoucherDto update(Long voucherId, Long editorUserId, Role editorRole,
                                   CreateIncomeVoucherRequest req) {
        IncomeVoucher voucher = voucherRepo.findById(voucherId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu thu"));

        if (voucher.getStatus() == IncomeVoucher.VoucherStatus.REJECTED)
            throw new BusinessException("Phiếu thu đã bị từ chối, không sửa được.");

        // ── QUYỀN SỬA ────────────────────────────────────────────────────────
        //   MỌI kế toán đều sửa được mọi phiếu thu (không giới hạn người tạo).
        //   Chỉ cần thuộc nhóm được phép thao tác phiếu thu.
        boolean canEdit = editorRole == Role.ACCOUNTANT
                || editorRole == Role.SUPER_ACCOUNTANT
                || editorRole == Role.ADMIN || editorRole == Role.OWNER;
        if (!canEdit)
            throw new BusinessException("Bạn không có quyền sửa phiếu thu.");

        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        String editorName = editor.getFullName() != null && !editor.getFullName().isBlank()
                ? editor.getFullName() : editor.getUsername();

        // ── Chụp trạng thái CŨ để so sánh, chọn template note ────────────────
        List<String> oldOrderCodes = parseJsonList(voucher.getLinkedOrderCodes());
        BigDecimal oldAmount = voucher.getItems().stream()
                .map(IncomeItem::getAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String oldPaymentType = voucher.getPaymentType() != null ? voucher.getPaymentType().name() : null;

        // ── 0. CHỐT CHỐNG GHI ĐÈ — chạy TRƯỚC khi gỡ đơn ────────────────────
        //   Sau khi gỡ, "còn lại" của đơn sẽ đổi theo đúng chủ ý nên không kiểm
        //   được nữa. Ở đây DB vẫn nguyên trạng client thấy → so sánh mới đúng.
        {
            List<Order> ordersForCheck = new ArrayList<>();
            if (req.getLinkedOrderCodes() != null) {
                for (String code : req.getLinkedOrderCodes())
                    orderRepository.findByOrderCode(code.trim()).ifPresent(ordersForCheck::add);
            }
            checkStaleOrders(req, ordersForCheck);
        }

        // ── 1. GỠ tác động của phiếu cũ lên các đơn liên kết cũ ──────────────
        //   Gỡ ĐÚNG số phiếu này đã ghi cho từng đơn.
        //
        //   · Phiếu MỚI: đọc thẳng từ orderAllocations đã lưu — chính xác tuyệt
        //     đối, kể cả khi một đơn được nhiều phiếu cùng trả.
        //   · Phiếu CŨ (tạo trước khi có bảng allocation): TÁI TẠO phân bổ bằng
        //     cách chạy lại thuật toán trên (thứ tự đơn cũ + tổng tiền cũ + final
        //     làm tròn). Đã đối chiếu: tái tạo khớp thuật toán gốc, TRỪ trường hợp
        //     hiếm một đơn KHÔNG phải đơn cuối đã bị phiếu khác trả một phần trước
        //     khi phiếu này tạo. Vẫn tốt hơn hẳn "gỡ trọn paidAmount" (xoá nhầm cả
        //     phần của phiếu khác).
        List<IncomeVoucherOrderAllocation> oldAllocs =
                (voucher.getOrderAllocations() != null && !voucher.getOrderAllocations().isEmpty())
                        ? new ArrayList<>(voucher.getOrderAllocations())
                        : reconstructAllocations(voucher);

        for (IncomeVoucherOrderAllocation alloc : oldAllocs) {
            orderRepository.findByOrderCode(alloc.getOrderCode().trim()).ifPresent(order ->
                    orderService.detachPaymentForVoucherEdit(
                            order.getId(), alloc.getAmount(), editorName, editorUserId));
        }
        voucher.getOrderAllocations().clear();

        // ── GỠ PHẦN DƯ CŨ ────────────────────────────────────────────────────
        //   Phần dư của phiếu cũ (nếu có) gắn ở đơn cuối cũ. Gỡ về 0 để bước áp
        //   lại tính đúng theo dữ liệu mới. CHỈ gỡ khi CHƯA lập phiếu chi hoàn —
        //   nếu đã hoàn thì giữ nguyên (không thể sửa phiếu thu để xoá dấu vết một
        //   phiếu chi đã tồn tại).
        for (String code : oldOrderCodes) {
            orderRepository.findByOrderCode(code.trim()).ifPresent(o -> {
                boolean hasOverpay = o.getOverpaidAmount() != null
                        && o.getOverpaidAmount().compareTo(BigDecimal.ZERO) > 0;
                if (hasOverpay && o.getOverpaidRefundVoucherCode() == null) {
                    o.setOverpaidAmount(BigDecimal.ZERO);
                    orderRepository.save(o);
                }
            });
        }

        // ── 2. Cập nhật thông tin phiếu + items ─────────────────────────────
        if (req.getPayerName()  != null) voucher.setPayerName(req.getPayerName());
        if (req.getReason()     != null) voucher.setReason(req.getReason());
        if (req.getReceiptNumber() != null && !req.getReceiptNumber().isBlank())
            voucher.setReceiptNumber(req.getReceiptNumber());
        if (req.getPaymentType() != null)
            voucher.setPaymentType(IncomeVoucher.PaymentType.valueOf(req.getPaymentType()));
        voucher.setBankName(req.getBankName());
        voucher.setBankRef(req.getBankRef());

        String linkedJson = null;
        if (req.getLinkedOrderCodes() != null && !req.getLinkedOrderCodes().isEmpty()) {
            try { linkedJson = objectMapper.writeValueAsString(req.getLinkedOrderCodes()); }
            catch (Exception e) { log.warn("Failed to serialize linkedOrderCodes"); }
        }
        voucher.setLinkedOrderCodes(linkedJson);

        if (req.getImageUrls() != null) {
            try { voucher.setImageUrls(objectMapper.writeValueAsString(req.getImageUrls())); }
            catch (Exception e) { log.warn("Failed to serialize imageUrls"); }
        }

        // Thay toàn bộ items (orphanRemoval = true sẽ dọn bản ghi cũ).
        voucher.getItems().clear();
        if (req.getItems() != null) {
            for (var it : req.getItems()) {
                voucher.getItems().add(IncomeItem.builder()
                        .voucher(voucher)
                        .itemName(it.getItemName())
                        .amount(it.getAmount())
                        .note(it.getNote())
                        .build());
            }
        }
        voucher.setUpdatedAt(System.currentTimeMillis());

        // ── 3. Áp lại tiền cho danh sách đơn MỚI ────────────────────────────
        //   Bỏ qua stale-check ở đây: đã kiểm ở bước 0 trước khi gỡ.
        List<IncomeVoucherOrderAllocation> newAllocs =
                applyCollectionToOrders(req, voucher, editorName, editorUserId, false);
        voucher.getOrderAllocations().addAll(newAllocs);

        voucher = voucherRepo.save(voucher);

        // ── 4. Thông báo ────────────────────────────────────────────────────
        // ── 4. Thông báo + ghi nhật ký ──────────────────────────────────────
        notifyVoucherEdited(voucher, editorName);

        List<String> newOrderCodes = req.getLinkedOrderCodes() != null
                ? req.getLinkedOrderCodes() : List.of();
        BigDecimal newAmount = req.getCollectedAmount() != null
                ? req.getCollectedAmount() : oldAmount;
        String newPaymentType = req.getPaymentType();
        String note = buildEditNote(oldOrderCodes, newOrderCodes, oldAmount, newAmount,
                oldPaymentType, newPaymentType);
        writeLog(voucher, "UPDATE", editorName, editorRole, note);

        return getById(voucher.getId());
    }

    /**
     * PHÂN BỔ số tiền thực thu vào các đơn liên kết — {@code create} và
     * {@code update} dùng CHUNG, không để hai đường tính tiền lệch nhau.
     *
     * <p>Giữ nguyên quy tắc: thu đủ lần lượt từng đơn theo thứ tự, đơn cuối nhận
     * phần dư (PARTIAL nếu thiếu). Đơn "thu trước khi giao" chỉ ghi nhận tiền,
     * không đẩy sang Hoàn thành.
     *
     * <p><b>Chống ghi đè (stale):</b> nếu client gửi kèm {@code expectedOrderAmounts}
     * thì trước khi ghi, đối chiếu "số còn lại client đang thấy" với số thật trong
     * DB. Lệch nghĩa là người khác vừa thu/sửa đúng đơn này → ném
     * {@link StaleOrderDataException} (HTTP 409), client hiện cảnh báo tải lại.
     *
     * @return danh sách phân bổ (đơn + số tiền đã ghi) để lưu lên phiếu
     */
    /**
     * Đối chiếu "số còn lại client đang thấy" với số thật trong DB — dùng chống
     * ghi đè khi hai kế toán đụng cùng đơn. Gọi ở thời điểm DB CHƯA bị chính thao
     * tác này làm đổi (create: trước khi ghi; update: TRƯỚC khi gỡ đơn cũ).
     */
    private void checkStaleOrders(CreateIncomeVoucherRequest req, List<Order> linkedOrders) {
        if (req.getExpectedOrderAmounts() == null || req.getExpectedOrderAmounts().isEmpty()) return;

        Map<String, BigDecimal> expected = new HashMap<>();
        for (var eo : req.getExpectedOrderAmounts()) {
            if (eo.getOrderCode() != null && eo.getExpectedRemainingAmount() != null)
                expected.put(eo.getOrderCode().trim(),
                        eo.getExpectedRemainingAmount().setScale(0, RoundingMode.HALF_UP));
        }
        for (Order o : linkedOrders) {
            BigDecimal exp = expected.get(o.getOrderCode());
            if (exp == null) continue;

            BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
            BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
            BigDecimal actualRem = fin.subtract(paid).setScale(0, RoundingMode.HALF_UP);
            if (actualRem.compareTo(BigDecimal.ZERO) < 0) actualRem = BigDecimal.ZERO;

            if (actualRem.compareTo(exp) != 0) {
                throw new StaleOrderDataException(o.getOrderCode(), actualRem, paid);
            }
        }
    }

    private List<IncomeVoucherOrderAllocation> applyCollectionToOrders(
            CreateIncomeVoucherRequest req, IncomeVoucher voucher,
            String actorName, Long actorUserId) {
        return applyCollectionToOrders(req, voucher, actorName, actorUserId, true);
    }

    /**
     * @param runStaleCheck khi SỬA phiếu, việc gỡ đơn cũ đã làm "còn lại" đổi
     *   theo đúng chủ ý, nên stale-check phải chạy TRƯỚC khi gỡ (ở {@code update})
     *   chứ không phải ở đây — truyền false để bỏ qua, tránh báo nhầm.
     */
    private List<IncomeVoucherOrderAllocation> applyCollectionToOrders(
            CreateIncomeVoucherRequest req, IncomeVoucher voucher,
            String actorName, Long actorUserId, boolean runStaleCheck) {

        List<IncomeVoucherOrderAllocation> allocations = new ArrayList<>();
        if (req.getLinkedOrderCodes() == null || req.getLinkedOrderCodes().isEmpty()) return allocations;

        List<Order> linkedOrders = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String code : req.getLinkedOrderCodes()) {
            orderRepository.findByOrderCode(code.trim())
                    .ifPresentOrElse(linkedOrders::add, () -> notFound.add(code));
        }
        if (!notFound.isEmpty())
            throw new BusinessException("Không tìm thấy đơn hàng: " + String.join(", ", notFound));

        // ── CHỐT CHỐNG GHI ĐÈ ────────────────────────────────────────────────
        //   So số còn lại client thấy với số thật. Kế toán B vừa thu đơn này giữa
        //   chừng → số lệch → 409, buộc A tải lại trước khi ghi tiếp.
        //   Bỏ qua khi SỬA (update tự kiểm TRƯỚC khi gỡ đơn — xem checkStaleOrders).
        if (runStaleCheck) {
            checkStaleOrders(req, linkedOrders);
        }

        if (req.getCollectedAmount() == null || req.getCollectedAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Số tiền thực thu là bắt buộc khi có đơn hàng liên kết");

        BigDecimal collected = req.getCollectedAmount().setScale(0, RoundingMode.HALF_UP);

        BigDecimal orderTotal = linkedOrders.stream()
                .map(o -> {
                    BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
                    BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
                    BigDecimal rem = fin.subtract(paid).setScale(0, RoundingMode.HALF_UP);
                    return rem.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rem;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── KHÁCH THANH TOÁN DƯ ──────────────────────────────────────────────
        //   Trước đây thu > tổng cần thu bị chặn. Nay CHO PHÉP: phần vượt quá là
        //   tiền khách trả dư, sẽ được gán vào overpaidAmount của ĐƠN CUỐI (xem
        //   cuối vòng lặp phân bổ). Số này KHÔNG cộng vào paidAmount — đơn vẫn chỉ
        //   thu đủ đúng finalAmount; phần dư là khoản nợ phải trả lại khách, hoàn
        //   bằng phiếu chi riêng.
        BigDecimal overpay = collected.subtract(orderTotal).max(BigDecimal.ZERO);

        // ── SÀN của số tiền thu ──────────────────────────────────────────────
        //   Muốn mọi đơn đều được ghi nhận (dù 1 phần), số thu phải LỚN HƠN tổng
        //   các đơn TRỪ đơn cuối — để đơn cuối còn lại ≥ 1đ.
        if (linkedOrders.size() > 1) {
            BigDecimal allButLast = BigDecimal.ZERO;
            for (int i = 0; i < linkedOrders.size() - 1; i++) {
                Order o = linkedOrders.get(i);
                BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
                BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
                BigDecimal rem = fin.subtract(paid).setScale(0, RoundingMode.HALF_UP);
                allButLast = allButLast.add(rem.max(BigDecimal.ZERO));
            }
            if (collected.compareTo(allButLast) <= 0)
                throw new BusinessException(
                        "Số tiền thu (" + collected.toPlainString() + ") phải LỚN HƠN "
                                + allButLast.toPlainString() + " — là tổng của các đơn trước đơn cuối. "
                                + "Nếu không, đơn cuối sẽ không được thu đồng nào. "
                                + "Hãy bỏ bớt đơn hoặc tăng số tiền thu.");
        }

        BigDecimal remaining = collected;
        for (int i = 0; i < linkedOrders.size(); i++) {
            Order order = linkedOrders.get(i);
            BigDecimal paid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal orderFinal = order.getFinalAmount() != null
                    ? order.getFinalAmount().subtract(paid).setScale(0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            if (orderFinal.compareTo(BigDecimal.ZERO) < 0) orderFinal = BigDecimal.ZERO;
            boolean isLast = (i == linkedOrders.size() - 1);
            boolean preDelivery = com.nhatnam.server.service.serviceimpl.OrderServiceImpl.isPreDelivery(order);

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal allocatedForThisOrder;   // số PHIẾU NÀY ghi cho đơn này

            if (remaining.compareTo(orderFinal) >= 0) {
                allocatedForThisOrder = orderFinal;
                remaining = remaining.subtract(orderFinal);
                // orderFinal == 0 nghĩa là đơn (thường là đơn cuối) ĐÃ thu đủ từ trước
                // và phiếu này chỉ mang phần dư — không ghi thêm giao dịch 0đ (sẽ bị chặn).
                if (orderFinal.compareTo(BigDecimal.ZERO) > 0) {
                    if (preDelivery)
                        orderService.recordPrepayment(order.getId(), orderFinal, false,
                                actorName, order.getPaymentMethod(), null, null, actorUserId);
                    else
                        // Dùng recordPartialPayment (không phải markAsCompleted) để MỖI lần
                        // thu đều tạo payment_transaction — sổ tiền phải đầy đủ thì mới dựng
                        // lại order_log chính xác khi gỡ. Thu đúng phần còn lại ⇒ tự chuyển
                        // COMPLETED bên trong recordPartialPayment.
                        orderService.recordPartialPayment(order.getId(), orderFinal,
                                order.getDebtDays(), actorName, order.getPaymentMethod(),
                                null, null, actorUserId);
                }
            } else {
                if (!isLast)
                    throw new BusinessException("Số tiền thu không đủ cho đơn " + order.getOrderCode());

                allocatedForThisOrder = remaining;
                if ("FULL".equalsIgnoreCase(req.getLastOrderHandling())) {
                    if (preDelivery)
                        orderService.recordPrepayment(order.getId(), remaining, true,
                                actorName, order.getPaymentMethod(), null, null, actorUserId);
                    else {
                        // Miễn phần còn lại nhưng vẫn ghi nhận đúng số tiền THỰC thu
                        // vào sổ, rồi đánh dấu hoàn thành. Nhờ vậy sổ tiền vẫn có
                        // giao dịch của phần đã thu.
                        orderService.recordPartialPayment(order.getId(), remaining,
                                order.getDebtDays(), actorName, order.getPaymentMethod(),
                                null, null, actorUserId);
                        orderService.markAsCompleted(order.getId(), actorName, actorUserId);
                    }
                } else {
                    if (preDelivery)
                        orderService.recordPrepayment(order.getId(), remaining, false,
                                actorName, order.getPaymentMethod(), null, null, actorUserId);
                    else
                        orderService.recordPartialPayment(order.getId(), remaining,
                                order.getDebtDays(), actorName, order.getPaymentMethod(),
                                null, null, actorUserId);
                }
                remaining = BigDecimal.ZERO;
            }

            if (allocatedForThisOrder.compareTo(BigDecimal.ZERO) > 0) {
                allocations.add(IncomeVoucherOrderAllocation.builder()
                        .voucher(voucher)
                        .orderCode(order.getOrderCode())
                        .amount(allocatedForThisOrder)
                        .build());
            }
        }

        // Phần dư (nếu có) KHÔNG ghi vào đơn hàng nữa — chỉ tính từ tổng phiếu vs
        // tổng phân bổ khi build DTO (xem buildOverpayInfo). Điều này giúp:
        // · Không ảnh hưởng dữ liệu đơn hàng khi phiếu thu có dư
        // · Sửa phiếu thu (thêm/bỏ đơn, đổi số tiền) tự cập nhật dư mà không cần
        //   rollback trạng thái đơn cũ

        return allocations;
    }

    /**
     * TÁI TẠO phân bổ của một phiếu CŨ (chưa lưu allocation) bằng cách chạy lại
     * thuật toán phân bổ trên dữ liệu phiếu đang lưu.
     *
     * <p>Căn cứ: thứ tự đơn (giữ nguyên trong linkedOrderCodes) + tổng tiền phiếu
     * + {@code finalAmount} của đơn (LÀM TRÒN trước khi tính, đồng bộ với mọi số
     * tiền khác). Đơn không phải cuối nhận {@code min(còn lại, final)}, đơn cuối
     * nhận phần dư — đúng thuật toán lúc tạo.
     *
     * <p><b>Giới hạn đã biết:</b> nếu lúc tạo phiếu, một đơn KHÔNG phải đơn cuối
     * đã bị phiếu khác trả một phần, con số tái tạo sẽ hơi lệch (dùng final thay
     * vì phần-còn-lại-lúc-đó). Phiếu tạo từ nay về sau có allocation lưu sẵn nên
     * không dính giới hạn này.
     */
    private List<IncomeVoucherOrderAllocation> reconstructAllocations(IncomeVoucher voucher) {
        List<IncomeVoucherOrderAllocation> out = new ArrayList<>();

        List<String> codes = parseJsonList(voucher.getLinkedOrderCodes());
        if (codes.isEmpty()) return out;

        BigDecimal collected = voucher.getItems().stream()
                .map(IncomeItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);

        BigDecimal remaining = collected;
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i).trim();
            Order order = orderRepository.findByOrderCode(code).orElse(null);
            if (order == null) continue;

            BigDecimal orderFinal = order.getFinalAmount() != null
                    ? order.getFinalAmount().setScale(0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            boolean isLast = (i == codes.size() - 1);
            BigDecimal alloc = isLast ? remaining : remaining.min(orderFinal);
            if (alloc.compareTo(BigDecimal.ZERO) < 0) alloc = BigDecimal.ZERO;

            if (alloc.compareTo(BigDecimal.ZERO) > 0) {
                out.add(IncomeVoucherOrderAllocation.builder()
                        .voucher(voucher).orderCode(code).amount(alloc).build());
            }
            remaining = remaining.subtract(alloc);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
        }
        return out;
    }

    /**
     * Map {orderCode → số tiền phiếu ghi cho đơn}, cho FE.
     * Phiếu mới đọc từ allocation đã lưu; phiếu cũ TÁI TẠO — nhờ vậy form sửa
     * luôn có số per-order đúng, không phân biệt phiếu tạo trước hay sau.
     */
    private java.util.Map<String, BigDecimal> buildAllocationMap(IncomeVoucher v) {
        List<IncomeVoucherOrderAllocation> src =
                (v.getOrderAllocations() != null && !v.getOrderAllocations().isEmpty())
                        ? v.getOrderAllocations()
                        : reconstructAllocations(v);
        java.util.Map<String, BigDecimal> m = new java.util.LinkedHashMap<>();
        for (IncomeVoucherOrderAllocation a : src)
            m.merge(a.getOrderCode(), a.getAmount(), BigDecimal::add);
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.nhatnam.server.dto.income.IncomeVoucherLogDto> getLogs(Long voucherId) {
        return voucherLogRepo.findByVoucher_IdOrderByCreatedAtDesc(voucherId).stream()
                .map(l -> com.nhatnam.server.dto.income.IncomeVoucherLogDto.builder()
                        .id(l.getId())
                        .action(l.getAction())
                        .actionLabel("CREATE".equals(l.getAction()) ? "Tạo mới" : "Chỉnh sửa")
                        .actorName(l.getActorName())
                        .actorRole(l.getActorRole())
                        .note(l.getNote())
                        .createdAt(l.getCreatedAt())
                        .build())
                .toList();
    }

    /** Ghi một dòng nhật ký phiếu thu. */
    private void writeLog(IncomeVoucher voucher, String action, String actorName,
                          Role actorRole, String note) {
        try {
            voucherLogRepo.save(IncomeVoucherLog.builder()
                    .voucher(voucher)
                    .action(action)
                    .actorName(actorName)
                    .actorRole(actorRole != null ? actorRole.name() : null)
                    .note(note)
                    .createdAt(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.warn("Không ghi được nhật ký phiếu thu: {}", e.getMessage());
        }
    }

    /**
     * Dựng note theo template, so sánh trạng thái CŨ và MỚI của phiếu.
     *
     * <p>Ưu tiên loại thay đổi rõ ràng nhất: thêm/bỏ đơn trước, rồi đổi số tiền,
     * rồi đổi phương thức thanh toán. Mỗi note gồm phần mô tả + "sau khi cập nhật"
     * và "trước khi cập nhật" để đối chiếu.
     */
    private String buildEditNote(List<String> oldCodes, List<String> newCodes,
                                 BigDecimal oldAmount, BigDecimal newAmount,
                                 String oldPaymentType, String newPaymentType) {
        String oldList = oldCodes.isEmpty() ? "(không có)" : String.join(", ", oldCodes);
        String newList = newCodes.isEmpty() ? "(không có)" : String.join(", ", newCodes);
        String oldAmt = fmtMoney(oldAmount);
        String newAmt = fmtMoney(newAmount);

        java.util.Set<String> oldSet = new java.util.LinkedHashSet<>(oldCodes);
        java.util.Set<String> newSet = new java.util.LinkedHashSet<>(newCodes);

        List<String> added   = newCodes.stream().filter(c -> !oldSet.contains(c)).toList();
        List<String> removed = oldCodes.stream().filter(c -> !newSet.contains(c)).toList();

        String suffix = " Tổng tiền sau khi cập nhật là %s cho các đơn hàng %s, trước khi cập nhật là %s cho các đơn hàng %s."
                .formatted(newAmt, newList, oldAmt, oldList);

        // 1) Thêm đơn mới vào phiếu
        if (!added.isEmpty()) {
            return "Thêm đơn hàng %s vào phiếu.".formatted(String.join(", ", added)) + suffix;
        }
        // 2) Bỏ đơn khỏi phiếu
        if (!removed.isEmpty()) {
            return "Bỏ đơn hàng %s ra khỏi phiếu.".formatted(String.join(", ", removed)) + suffix;
        }
        // 3) Đổi số tiền (giữ nguyên đơn)
        if (oldAmount != null && newAmount != null && oldAmount.compareTo(newAmount) != 0) {
            return "Chỉnh sửa số tiền (giữ nguyên đơn)." + suffix;
        }
        // 4) Đổi phương thức thanh toán
        if (oldPaymentType != null && newPaymentType != null && !oldPaymentType.equals(newPaymentType)) {
            return "Đổi phương thức thanh toán từ %s sang %s."
                    .formatted(paymentTypeLabel(oldPaymentType), paymentTypeLabel(newPaymentType));
        }
        // Không rơi vào template nào — ghi chung chung để vẫn có vết.
        return "Chỉnh sửa phiếu thu." + suffix;
    }

    private String paymentTypeLabel(String type) {
        if ("BANK_TRANSFER".equals(type)) return "Chuyển khoản";
        if ("CASH".equals(type)) return "Tiền mặt";
        return type;
    }

    private String fmtMoney(BigDecimal v) {
        BigDecimal x = v != null ? v.setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return String.format("%,dđ", x.longValueExact()).replace(',', '.');
    }

    /** Báo WS cho OWNER / ADMIN / kế toán trưởng khi phiếu thu bị sửa. */
    private void notifyVoucherEdited(IncomeVoucher voucher, String editorName) {
        try {
            String payload = "{\"voucherId\":" + voucher.getId()
                    + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
            String msg = editorName + " đã sửa phiếu thu [" + voucher.getVoucherCode()
                    + "]. Lý do: " + voucher.getReason();

            for (User u : userRepository.findByRoleAndIsLockAccountFalse(Role.SUPER_ACCOUNTANT))
                notificationService.sendToUser(u, "INCOME_UPDATED", msg, payload);
            for (User u : userRepository.findByRoleAndIsLockAccountFalse(Role.ADMIN))
                notificationService.sendToUser(u, "INCOME_UPDATED", msg, payload);
            for (User u : userRepository.findByRoleAndIsLockAccountFalse(Role.OWNER))
                notificationService.sendToUser(u, "INCOME_UPDATED", msg, payload);
            notificationService.sendToRole("SUPER_ACCOUNTANT", "INCOME_UPDATED", msg, payload);
            notificationService.sendToRole("ADMIN", "INCOME_UPDATED", msg, payload);
            notificationService.sendToRole("OWNER", "INCOME_UPDATED", msg, payload);
        } catch (Exception e) {
            log.warn("Không gửi được thông báo sửa phiếu thu: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public IncomeVoucherDto create(Long createdByUserId, Role creatorRole,
                                   CreateIncomeVoucherRequest req) {
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        String creatorName = creator.getFullName() != null && !creator.getFullName().isBlank()
                ? creator.getFullName() : creator.getUsername();

        // Validate receiptNumber — CHO PHÉP TRÙNG số phiếu thu (vì số chạy tới 15000
        // sẽ quay vòng về 1). Chỉ bắt buộc phải nhập, không kiểm tra tồn tại.
        if (req.getReceiptNumber() == null || req.getReceiptNumber().isBlank())
            throw new BusinessException("Số phiếu thu là bắt buộc");

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

        // ── Phân bổ tiền cho các đơn liên kết (DÙNG CHUNG với update) ──────────
        //   Gồm luôn chốt chống ghi đè + ghi lại số tiền phân bổ cho từng đơn.
        List<IncomeVoucherOrderAllocation> allocs =
                applyCollectionToOrders(req, voucher, creatorName, createdByUserId);
        if (!allocs.isEmpty()) {
            voucher.getOrderAllocations().addAll(allocs);
            voucher = voucherRepo.save(voucher);
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

        // Nhật ký: tạo mới. Note nêu tổng tiền + các đơn (nếu có).
        List<String> codes = req.getLinkedOrderCodes() != null ? req.getLinkedOrderCodes() : List.of();
        BigDecimal amt = req.getCollectedAmount();
        String createNote = codes.isEmpty()
                ? "Tạo phiếu thu (thu ngoài đơn hàng)."
                : "Tạo phiếu thu %s cho các đơn hàng %s.".formatted(fmtMoney(amt), String.join(", ", codes));
        writeLog(voucher, "CREATE", creatorName, creatorRole, createNote);

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
        java.math.BigDecimal amt = parseAmount(q);
        if (from != null && to != null)
            return PageResponse.from(voucherRepo.searchWithDateRange(q, amt, from, to, pageable).map(this::toDto));
        return PageResponse.from(voucherRepo.searchAll(q, amt, pageable).map(this::toDto));
    }

    /**
     * Chuyển từ khoá thành SỐ TIỀN CHÍNH XÁC để so khớp tổng phiếu.
     * Chỉ nhận khi từ khoá LÀ MỘT SỐ NGUYÊN thuần (cho phép dấu . , khoảng trắng phân
     * cách nghìn). "700000" hoặc "700.000" → 700000. Có chữ cái → null (không lọc theo tiền).
     */
    private static java.math.BigDecimal parseAmount(String q) {
        if (q == null) return null;
        String t = q.trim();
        if (t.isEmpty()) return null;
        // chỉ chấp nhận chữ số + dấu phân cách nghìn . , và khoảng trắng
        if (!t.matches("[0-9][0-9.,\\s]*")) return null;
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try { return new java.math.BigDecimal(digits); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * TỔNG HỢP theo ĐÚNG bộ lọc đang áp dụng — SUM/COUNT trên TOÀN BỘ kết quả,
     * KHÔNG phụ thuộc phân trang.
     *
     * <p>Dùng đúng các điều kiện WHERE với {@link #listByDateRange} / {@link #search}
     * nên con số tổng luôn khớp với danh sách đang hiển thị.
     */
    @Override
    public com.nhatnam.server.dto.income.IncomeVoucherSummaryDto summary(String q, Long from, Long to) {
        boolean hasQ     = q != null && !q.isBlank();
        boolean hasRange = from != null && to != null;

        java.math.BigDecimal total;
        long count;

        if (hasQ && hasRange) {
            total = voucherRepo.sumSearchWithDateRange(q.trim(), parseAmount(q), from, to);
            count = voucherRepo.countSearchWithDateRange(q.trim(), parseAmount(q), from, to);
        } else if (hasQ) {
            total = voucherRepo.sumSearchAll(q.trim(), parseAmount(q));
            count = voucherRepo.countSearchAll(q.trim(), parseAmount(q));
        } else if (hasRange) {
            total = voucherRepo.sumByDateRange(from, to);
            count = voucherRepo.countByDateRange(from, to);
        } else {
            total = voucherRepo.sumAll();
            count = voucherRepo.countAllVouchers();
        }

        return com.nhatnam.server.dto.income.IncomeVoucherSummaryDto.builder()
                .totalAmount(total != null ? total : java.math.BigDecimal.ZERO)
                .totalCount(count)
                .build();
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

    @Override
    public byte[] exportReport(Long from, Long to, String exportedBy, String paymentType) throws Exception {
        List<IncomeVoucher> vouchers = voucherRepo.findByDateRangeAll(from, to);

        // ── Lọc theo phương thức thanh toán ─────────────────────────────────
        String pt = paymentType == null ? "" : paymentType.trim().toUpperCase();
        final boolean showPaymentCol = pt.isEmpty() || pt.equals("ALL") || pt.equals("BOTH");
        if (!showPaymentCol) {
            IncomeVoucher.PaymentType want = pt.equals("BANK_TRANSFER")
                    ? IncomeVoucher.PaymentType.BANK_TRANSFER
                    : IncomeVoucher.PaymentType.CASH;
            vouchers = vouchers.stream()
                    .filter(v -> {
                        IncomeVoucher.PaymentType vp = v.getPaymentType() != null
                                ? v.getPaymentType() : IncomeVoucher.PaymentType.CASH;
                        return vp == want;
                    })
                    .toList();
        }

        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("Phiếu thu");

        // ── Khai báo DataFormat ──────────────────────────────────────────────
        DataFormat fmt = wb.createDataFormat();

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
        metaStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // ── Data style với căn trái và top ─────────────────────────────────
        CellStyle dataStyle = wb.createCellStyle();
        dataStyle.setVerticalAlignment(VerticalAlignment.TOP);
        dataStyle.setAlignment(HorizontalAlignment.LEFT);
        dataStyle.setWrapText(false);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);
        dataStyle.setIndention((short)1);

        CellStyle dataWrapStyle = wb.createCellStyle();
        dataWrapStyle.setVerticalAlignment(VerticalAlignment.TOP);
        dataWrapStyle.setAlignment(HorizontalAlignment.LEFT);
        dataWrapStyle.setWrapText(true);
        dataWrapStyle.setBorderBottom(BorderStyle.THIN);
        dataWrapStyle.setBorderLeft(BorderStyle.THIN);
        dataWrapStyle.setBorderRight(BorderStyle.THIN);
        dataWrapStyle.setIndention((short)1);

        CellStyle amountStyle = wb.createCellStyle();
        amountStyle.setVerticalAlignment(VerticalAlignment.TOP);
        amountStyle.setAlignment(HorizontalAlignment.RIGHT);
        amountStyle.setDataFormat(fmt.getFormat("#,##0"));
        amountStyle.setBorderBottom(BorderStyle.THIN);
        amountStyle.setBorderLeft(BorderStyle.THIN);
        amountStyle.setBorderRight(BorderStyle.THIN);
        amountStyle.setIndention((short)1);

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

        java.time.format.DateTimeFormatter dateFmt =
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        java.time.ZoneId tz = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

        String fromStr = java.time.Instant.ofEpochMilli(from).atZone(tz).format(dateFmt);
        String toStr   = java.time.Instant.ofEpochMilli(to).atZone(tz).format(dateFmt);
        String exportedAt = java.time.LocalDateTime.now(tz)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));

        int rowIdx = 0;

        // ── Xác định số cột ────────────────────────────────────────────────────
        // Các cột: 0=Ngày, 1=Số phiếu thu, 2=Hóa đơn thu, [3=PTTT nếu showPaymentCol], cuối=Tổng tiền
        final int dateCol = 0;
        final int voucherCol = 1;
        final int invoiceCol = 2;
        final int payCol    = showPaymentCol ? 3 : -1;
        final int amountCol = showPaymentCol ? 4 : 3;
        final int lastCol   = amountCol;
        final int numCols   = showPaymentCol ? 5 : 4;

        // ── Row 0: Tiêu đề ─────────────────────────────────────────────────
        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(28);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BÁO CÁO PHIẾU THU");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));

        // ── Row 1: Meta info dòng 1 ──────────────────────────────────────────
        Row metaRow1 = sheet.createRow(rowIdx++);
        metaRow1.setHeightInPoints(18);
        Cell metaCell1 = metaRow1.createCell(0);
        String methodLabel = showPaymentCol ? "Tiền mặt + Chuyển khoản"
                : (pt.equals("BANK_TRANSFER") ? "Chuyển khoản" : "Tiền mặt");
        metaCell1.setCellValue("Từ " + fromStr + " đến " + toStr + "     |     PTTT: " + methodLabel);
        metaCell1.setCellStyle(metaStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));

        // ── Row 2: Meta info dòng 2 ──────────────────────────────────────────
        Row metaRow2 = sheet.createRow(rowIdx++);
        metaRow2.setHeightInPoints(18);
        Cell metaCell2 = metaRow2.createCell(0);
        metaCell2.setCellValue("Xuất báo cáo lúc: " + exportedAt + "     |     Người xuất báo cáo: " + (exportedBy != null ? exportedBy : ""));
        metaCell2.setCellStyle(metaStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, lastCol));

        // ── Row 3: blank ────────────────────────────────────────────────────
        sheet.createRow(rowIdx++);

        // ── Row 4: Header ───────────────────────────────────────────────────
        Row hRow = sheet.createRow(rowIdx++);
        hRow.setHeightInPoints(20);
        String[] headers;
        if (showPaymentCol) {
            headers = new String[]{"Ngày", "Số phiếu thu", "Hóa đơn thu", "PTTT", "Tổng tiền"};
        } else {
            headers = new String[]{"Ngày", "Số phiếu thu", "Hóa đơn thu", "Tổng tiền"};
        }
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

            // Col 0: Ngày (chỉ hiển thị dd/MM/yyyy)
            Cell c0 = row.createCell(dateCol);
            c0.setCellValue(v.getCreatedAt() != null
                    ? java.time.Instant.ofEpochMilli(v.getCreatedAt()).atZone(tz).format(dateFmt) : "");
            c0.setCellStyle(alt ? altStyle : dataStyle);

            // Col 1: Số phiếu thu
            Cell c1 = row.createCell(voucherCol);
            c1.setCellValue(v.getReceiptNumber() != null ? v.getReceiptNumber() : v.getVoucherCode());
            c1.setCellStyle(alt ? altStyle : dataStyle);

            // Col 2: Hóa đơn thu
            Cell c2 = row.createCell(invoiceCol);
            c2.setCellValue(orderCodesText);
            c2.setCellStyle(alt ? altWrapStyle : dataWrapStyle);

            // Col PTTT (nếu có)
            if (showPaymentCol) {
                Cell cPay = row.createCell(payCol);
                IncomeVoucher.PaymentType vp = v.getPaymentType() != null
                        ? v.getPaymentType() : IncomeVoucher.PaymentType.CASH;
                cPay.setCellValue(vp == IncomeVoucher.PaymentType.BANK_TRANSFER
                        ? "CK" : "TM");
                cPay.setCellStyle(alt ? altStyle : dataStyle);
            }

            // Col cuối: Tổng tiền
            Cell cLast = row.createCell(amountCol);
            cLast.setCellValue(total.doubleValue());
            cLast.setCellStyle(alt ? altAmtStyle : amountStyle);
        }

        // ── Column widths ───────────────────────────────────────────────────
        sheet.setColumnWidth(dateCol, 15 * 256);    // Ngày
        sheet.setColumnWidth(voucherCol, 15 * 256); // Số phiếu thu
        sheet.setColumnWidth(invoiceCol, 20 * 256); // Hóa đơn thu

        if (showPaymentCol) {
            sheet.setColumnWidth(payCol, 10 * 256);     // PTTT
            sheet.setColumnWidth(amountCol, 24 * 256);  // Tổng tiền
        } else {
            sheet.setColumnWidth(amountCol, 24 * 256);  // Tổng tiền
        }

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
        totalStyle.setBorderBottom(BorderStyle.THIN);
        totalStyle.setBorderLeft(BorderStyle.THIN);
        totalStyle.setBorderRight(BorderStyle.THIN);

        CellStyle totalLabelStyle = wb.createCellStyle();
        totalLabelStyle.setFont(totalFont);
        totalLabelStyle.setAlignment(HorizontalAlignment.RIGHT);
        totalLabelStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xC9,(byte)0xA8,(byte)0x4C}, null));
        totalLabelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        totalLabelStyle.setBorderBottom(BorderStyle.THIN);
        totalLabelStyle.setBorderLeft(BorderStyle.THIN);
        totalLabelStyle.setBorderRight(BorderStyle.THIN);

        Cell tlCell = totalRow.createCell(0);
        tlCell.setCellValue("TỔNG CỘNG (" + vouchers.size() + " phiếu)");
        tlCell.setCellStyle(totalLabelStyle);

        if (lastCol - 1 >= 0) {
            sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, lastCol - 1));
        }

        int dataStart = 5; // row index data bắt đầu (0-indexed)
        Cell sumCell = totalRow.createCell(lastCol);
        String amountColLetter = org.apache.poi.ss.util.CellReference
                .convertNumToColString(lastCol);
        if (!vouchers.isEmpty()) {
            sumCell.setCellFormula("SUM(" + amountColLetter + dataStart
                    + ":" + amountColLetter + rowIdx + ")");
        } else {
            sumCell.setCellValue(0);
        }
        sumCell.setCellStyle(totalStyle);

        // ── Set vùng in ──────────────────────────────────────────────────────
        wb.setPrintArea(
                wb.getSheetIndex(sheet),
                0,          // first column
                lastCol,    // last column
                0,          // first row
                rowIdx      // last row
        );

        // ── Write output ────────────────────────────────────────────────────
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    /**
     * Số phiếu thu tối đa trước khi quay vòng về 1. Khi số phiếu đạt tới mốc này,
     * phiếu kế tiếp sẽ bắt đầu lại từ 1.
     */
    private static final long RECEIPT_MAX = 15000L;

    /**
     * Gợi ý số phiếu thu kế tiếp = số phiếu của phiếu tạo GẦN NHẤT + 1, quay vòng
     * về 1 khi đạt {@link #RECEIPT_MAX}. Ví dụ:
     * <ul>
     *   <li>phiếu gần nhất = 14999 → gợi ý 15000</li>
     *   <li>phiếu gần nhất = 15000 → gợi ý 1 (reset)</li>
     *   <li>người dùng vừa nhập lại 1 → gợi ý 2</li>
     * </ul>
     * Nếu chưa có phiếu nào (hoặc số không chứa chữ số) → gợi ý "1". Người dùng vẫn
     * có thể tự nhập số khác.
     */
    @Override
    public String suggestNextReceiptNumber() {
        String latest = voucherRepo.findLatestReceiptNumber();
        return String.valueOf(nextNumberWithWrap(latest));
    }

    /** Tính số kế tiếp (quay vòng 1..RECEIPT_MAX) từ số phiếu gần nhất. */
    private long nextNumberWithWrap(String latest) {
        if (latest == null) return 1L;
        String digitsOnly = latest.replaceAll("[^0-9]", "");
        if (digitsOnly.isBlank()) return 1L;
        try {
            long n = Long.parseLong(digitsOnly);
            if (n >= RECEIPT_MAX || n < 1) return 1L;
            return n + 1;
        } catch (NumberFormatException ignored) {
            return 1L;
        }
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

        // Lần chỉnh sửa gần nhất (một query, dùng cho cả tên lẫn thời điểm).
        var lastEdit = voucherLogRepo
                .findFirstByVoucher_IdAndActionOrderByCreatedAtDesc(v.getId(), "UPDATE")
                .orElse(null);

        // ── Tên khách hàng của các đơn liên kết ─────────────────────────────
        // Tra cứu tên KH theo từng mã đơn, LOẠI TRÙNG (giữ nguyên thứ tự xuất hiện).
        // Nhiều đơn khác khách  → hiện tất cả tên. Nhiều đơn cùng khách → chỉ 1 tên.
        List<String> orderCodes = parseJsonList(v.getLinkedOrderCodes());
        List<String> customerNames = new java.util.ArrayList<>();
        if (orderCodes != null && !orderCodes.isEmpty()) {
            java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
            for (String code : orderCodes) {
                if (code == null || code.isBlank()) continue;
                orderRepository.findByOrderCode(code.trim()).ifPresent(o -> {
                    String name = o.getCustomerName();
                    if ((name == null || name.isBlank()) && o.getCustomer() != null) {
                        // Fallback: đọc từ Customer nếu snapshot tên trên đơn rỗng
                        String cn = o.getCustomer().getName();
                        String comp = o.getCustomer().getCompanyName();
                        name = (comp != null && !comp.isBlank()) ? comp : cn;
                    }
                    if (name != null && !name.isBlank()) uniq.add(name.trim());
                });
            }
            customerNames.addAll(uniq);
        }

        return IncomeVoucherDto.builder()
                .id(v.getId())
                .voucherCode(v.getVoucherCode())
                .receiptNumber(v.getReceiptNumber())
                .payerName(v.getPayerName())
                .reason(v.getReason())
                .createdByName(v.getCreatedByName())
                .createdById(v.getCreatedBy() != null ? v.getCreatedBy().getId() : null)
                .status(v.getStatus().name())
                .paymentType(v.getPaymentType() != null ? v.getPaymentType().name() : "CASH")
                .bankName(v.getBankName())
                .bankRef(v.getBankRef())
                .linkedOrderCodes(parseJsonList(v.getLinkedOrderCodes()))
                .orderAllocations(buildAllocationMap(v))
                .lastEditedByName(lastEdit != null ? lastEdit.getActorName() : null)
                .lastEditedAt(lastEdit != null ? lastEdit.getCreatedAt() : null)
                .linkedCustomerNames(customerNames)
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
                .overpay(buildOverpayInfo(v, total, buildAllocationMap(v)))
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }

    /**
     * Thông tin phần dư của phiếu thu — tính từ DỮ LIỆU PHIẾU, KHÔNG đọc từ order.
     *
     * <p>Công thức: dư = totalAmount phiếu − Σ phân bổ (allocations).
     * Allocations đã tính đúng phần mà PHIẾU NÀY phân bổ cho từng đơn (kể cả đơn
     * thu 1 phần bởi phiếu khác). Nếu dư ≤ 0 → trả {@code null}.
     *
     * <p>Tên khách + mã phiếu chi hoàn lấy từ đơn CUỐI (nơi phần dư logic gắn vào).
     */
    private IncomeVoucherDto.OverpayInfoDto buildOverpayInfo(
            IncomeVoucher v, BigDecimal voucherTotal,
            java.util.Map<String, BigDecimal> allocationMap) {

        if (voucherTotal == null || allocationMap == null || allocationMap.isEmpty()) return null;

        BigDecimal sumAllocated = allocationMap.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal over = voucherTotal.subtract(sumAllocated);
        if (over.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Lấy thông tin khách + mã phiếu chi hoàn từ đơn cuối
        List<String> orderCodes = parseJsonList(v.getLinkedOrderCodes());
        String lastCode = (orderCodes != null && !orderCodes.isEmpty())
                ? orderCodes.get(orderCodes.size() - 1) : null;

        String customerName = null;
        String refundCode = null;
        if (lastCode != null && !lastCode.isBlank()) {
            Order last = orderRepository.findByOrderCode(lastCode.trim()).orElse(null);
            if (last != null) {
                customerName = last.getCustomerName();
                if ((customerName == null || customerName.isBlank()) && last.getCustomer() != null) {
                    String comp = last.getCustomer().getCompanyName();
                    customerName = (comp != null && !comp.isBlank()) ? comp : last.getCustomer().getName();
                }
                refundCode = last.getOverpaidRefundVoucherCode();
            }
        }

        return IncomeVoucherDto.OverpayInfoDto.builder()
                .orderCode(lastCode)
                .customerName(customerName)
                .amount(over.setScale(0, RoundingMode.HALF_UP))
                .refundVoucherCode(refundCode)
                .build();
    }
}