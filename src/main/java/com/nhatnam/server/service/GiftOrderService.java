package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.gift.GiftOrderDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.utils.UnitDecimalRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PHIẾU TẶNG QUÀ BẰNG SẢN PHẨM — tạo, duyệt, trừ kho, chuyển kho xử lý giao.
 *
 * <h3>Luồng</h3>
 * <pre>
 *   Seller tạo (PENDING)  →  OWNER/ADMIN duyệt (APPROVED)  →  Kho xác nhận (DELIVERING)  →  COMPLETED
 *                          ↘ từ chối (REJECTED)
 * </pre>
 *
 * <p><b>Tồn kho chỉ bị trừ tại bước DUYỆT.</b> Lúc tạo phiếu không kiểm tra, không trừ —
 * xem javadoc {@link GiftOrder} để biết lý do. Bước duyệt kiểm tra trước, thiếu thì
 * trả về danh sách thiếu hụt và KHÔNG đổi trạng thái phiếu.
 *
 * <p>Sản phẩm được cấu thành từ nguyên liệu ({@link ProductIngredient}), và tồn kho
 * quản lý ở mức NGUYÊN LIỆU chứ không phải sản phẩm. Nên "còn bao nhiêu sản phẩm X"
 * là đại lượng suy ra: lấy min qua các nguyên liệu của công thức.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class GiftOrderService {

    private final GiftOrderRepository giftOrderRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final ProductIngredientRepository productIngredientRepository;
    private final IngredientStockRepository ingredientStockRepository;
    private final IngredientWarehouseRepository ingredientWarehouseRepository;
    private final WarehouseReceiptRepository warehouseReceiptRepository;
    private final FifoDeductService fifoDeductService;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    // ════════════════════════════════════════════════════════════════════════
    // ĐỌC
    // ════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PageResponse<GiftOrderDto> list(String q, String status, Long createdById,
                                           Long warehouseId, Pageable pageable) {
        GiftOrder.GiftOrderStatus st = _parseStatus(status);
        Page<GiftOrder> page = giftOrderRepository.search(
                (q == null || q.isBlank()) ? null : q.trim(), st, createdById, warehouseId, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toDto).toList());
    }

    @Transactional(readOnly = true)
    public GiftOrderDto getById(Long id) {
        return toDto(_findOrThrow(id));
    }

    /** Phiếu chờ nhân viên kho xử lý, giới hạn trong các kho user được phân công. */
    @Transactional(readOnly = true)
    public List<GiftOrderDto> listForWarehouseStaff(User staff) {
        Set<Role> roles = staff.getAllRoles();
        boolean isAdminLevel = roles.contains(Role.ADMIN) || roles.contains(Role.OWNER)
                || roles.contains(Role.SUPERADMIN);

        List<Long> warehouseIds = isAdminLevel
                ? warehouseRepository.findAll().stream().map(Warehouse::getId).toList()
                : staff.getAllWarehouses().stream().map(Warehouse::getId).toList();

        if (warehouseIds.isEmpty()) return List.of();
        return giftOrderRepository.findPendingForWarehouses(warehouseIds).stream()
                .map(this::toDto).toList();
    }

    /**
     * KHO ĐƯỢC PHÉP XUẤT HÀNG TẶNG.
     *
     * <p>Chỉ trả kho đang hoạt động và có {@code type = SALE}. Kho TRANSIT theo định nghĩa
     * là kho trung chuyển — chỉ nhập và chuyển, không xuất bán — nên cho chọn ở đây sẽ tạo
     * ra phiếu xuất mà kho đó không có quy trình xử lý.
     *
     * <p>Endpoint này nằm trong module quà tặng thay vì dùng {@code /api/warehouse}: đường
     * dẫn đó bị {@code SecurityConfiguration} giới hạn cho role WAREHOUSE, và nới quyền ở
     * đó sẽ mở cho seller cả các API nhập/xuất/điều chỉnh kho — nhiều hơn hẳn thứ họ cần,
     * vốn chỉ là danh sách tên kho để chọn.
     */
    @Transactional(readOnly = true)
    public List<Warehouse> selectableWarehouses() {
        return warehouseRepository.findAll().stream()
                .filter(Warehouse::isActive)
                .filter(w -> w.getType() == Warehouse.WarehouseType.SALE)
                .sorted(Comparator.comparing(Warehouse::getName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * SẢN PHẨM ĐƯỢC GÁN CHO MỘT KHO, kèm số lượng còn xuất được.
     *
     * <p>Điều kiện để một sản phẩm xuất hiện: <b>TOÀN BỘ</b> nguyên liệu trong công thức
     * của nó phải được đăng ký cho kho này ({@code ingredient_warehouse}).
     *
     * <p>Vì sao phải đủ HẾT chứ không phải "có ít nhất một"? Thiếu một nguyên liệu là
     * không làm ra được sản phẩm. Cho hiện lên rồi để seller chọn thì phiếu chắc chắn
     * kẹt ở bước duyệt vì thiếu tồn — mà lúc đó seller đã hứa quà với khách rồi.
     *
     * <p>Dùng bảng ĐĂNG KÝ nguyên liệu–kho thay vì bảng tồn kho: một nguyên liệu thuộc
     * kho nhưng đang hết hàng sẽ KHÔNG có dòng tồn nào. Nếu căn theo bảng tồn thì sản
     * phẩm biến mất khỏi danh sách mỗi khi hết hàng tạm thời, seller tưởng kho không
     * bán mặt hàng đó nữa. Cách hiện tại vẫn hiện sản phẩm, kèm {@code availableQty = 0}
     * để seller thấy rõ là đang hết.
     */
    @Transactional(readOnly = true)
    public List<GiftProductOption> availableProducts(Long warehouseId, String q) {
        Set<Long> assignedIngredients =
                new HashSet<>(ingredientWarehouseRepository.findIngredientIdsByWarehouseId(warehouseId));
        if (assignedIngredients.isEmpty()) return List.of();

        Map<Long, BigDecimal> stockByIngredient = ingredientStockRepository
                .findByWarehouseId(warehouseId).stream()
                .collect(Collectors.toMap(
                        IngredientStock::getIngredientId,
                        s -> s.getStockQuantity() != null ? s.getStockQuantity() : BigDecimal.ZERO,
                        BigDecimal::add));

        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        List<GiftProductOption> result = new ArrayList<>();
        for (Product p : productRepository.findAll()) {
            if (Boolean.FALSE.equals(p.getIsActive())) continue;
            if (needle != null && (p.getName() == null || !p.getName().toLowerCase().contains(needle)))
                continue;

            List<ProductIngredient> recipe = productIngredientRepository.findByProductId(p.getId());
            if (recipe.isEmpty()) continue;

            boolean fullyAssigned = recipe.stream()
                    .allMatch(pi -> assignedIngredients.contains(pi.getIngredientId()));
            if (!fullyAssigned) continue;

            result.add(GiftProductOption.builder()
                    .id(p.getId())
                    .name(p.getName())
                    .unit(p.getUnit())
                    .availableQty(_maxProducible(recipe, stockByIngredient))
                    .allowDecimal(_allowsDecimal(p, recipe))
                    .build());
        }
        result.sort(Comparator.comparing(GiftProductOption::getName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // TẠO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Tạo phiếu — KHÔNG kiểm tra tồn, KHÔNG trừ kho.
     *
     * @throws BusinessException nếu seller không có quyền thao tác trên khách này
     */
    @Transactional
    public GiftOrderDto create(CreateGiftOrderRequest req, User actor) {
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new BusinessException("Vui lòng chọn ít nhất 1 sản phẩm để tặng");

        Customer customer = customerRepository.findById(req.getCustomerId())
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy khách hàng #" + req.getCustomerId()));

        if (!canActOnCustomer(customer, actor))
            throw new BusinessException("Khách hàng này đã được gán cho nhân viên khác");

        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy kho #" + req.getWarehouseId()));

        GiftOrder.GiftOccasion occasion = _resolveOccasion(req.getOccasion(), customer);

        GiftOrder g = GiftOrder.builder()
                .code(_generateCode())
                .customer(customer)
                .customerName(_displayName(customer))
                .warehouse(warehouse)
                .occasion(occasion)
                .status(GiftOrder.GiftOrderStatus.PENDING)
                .note(_blankToNull(req.getNote()))
                .createdBy(actor)
                .createdByName(actor != null
                        ? (actor.getFullName() != null ? actor.getFullName() : actor.getUsername())
                        : null)
                .build();

        for (ItemInput in : req.getItems()) {
            if (in.getProductId() == null) continue;
            if (in.getQuantity() == null || in.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
                throw new BusinessException("Số lượng phải lớn hơn 0");

            Product p = productRepository.findById(in.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Không tìm thấy sản phẩm #" + in.getProductId()));

            // Kiểm tra lại số lẻ ở SERVER. Ô nhập bên FE đã chặn theo cờ allowDecimal,
            // nhưng cờ đó do client gửi lên nên không đáng tin — một request nặn tay vẫn
            // tạo được phiếu tặng 0,5 thùng, và kho không có cách nào soạn nửa thùng.
            List<ProductIngredient> recipe = productIngredientRepository.findByProductId(p.getId());
            _validateQuantityScale(p, recipe, in.getQuantity());

            g.addItem(GiftOrderItem.builder()
                    .productId(p.getId())
                    .productName(p.getName())
                    .unit(p.getUnit())
                    .quantity(in.getQuantity())
                    .build());
        }

        if (g.getItems().isEmpty())
            throw new BusinessException("Vui lòng chọn ít nhất 1 sản phẩm để tặng");

        return toDto(giftOrderRepository.save(g));
    }

    // ════════════════════════════════════════════════════════════════════════
    // DUYỆT
    // ════════════════════════════════════════════════════════════════════════

    /**
     * KIỂM TỒN KHO trước khi duyệt — không thay đổi gì.
     *
     * <p>FE gọi hàm này lúc mở hộp thoại duyệt để hiện cảnh báo sớm, nhưng
     * {@link #approve} vẫn kiểm tra lại: giữa lúc xem và lúc bấm duyệt, đơn bán khác
     * có thể đã lấy mất hàng.
     */
    @Transactional(readOnly = true)
    public StockCheckResult checkStock(Long giftOrderId) {
        return _checkStock(_findOrThrow(giftOrderId));
    }

    /**
     * DUYỆT PHIẾU: kiểm tồn → trừ kho FIFO → sinh phiếu xuất kho → chuyển sang APPROVED.
     *
     * <p>Phiếu xuất kho sinh ra mang lý do {@code "Tặng quà cho <tên khách>"} và
     * {@code createdByName} là TÊN SELLER TẠO PHIẾU, không phải người duyệt — chứng từ
     * kho phải truy được về người phát sinh nhu cầu.
     *
     * @throws BusinessException kèm danh sách thiếu hụt nếu tồn không đủ
     */
    @Transactional
    public GiftOrderDto approve(Long id, User approver) {
        GiftOrder g = _findOrThrow(id);

        if (g.getStatus() != GiftOrder.GiftOrderStatus.PENDING)
            throw new BusinessException("Chỉ duyệt được phiếu đang chờ duyệt");

        StockCheckResult check = _checkStock(g);
        if (!check.isOk()) {
            String detail = check.getShortages().stream()
                    .map(s -> s.getIngredientName() + " (thiếu "
                            + s.getMissing().stripTrailingZeros().toPlainString()
                            + " " + (s.getUnit() != null ? s.getUnit() : "") + ")")
                    .collect(Collectors.joining(", "));
            throw new BusinessException("Không đủ tồn kho để duyệt phiếu. Cần nhập thêm: " + detail);
        }

        long now = System.currentTimeMillis();
        Warehouse wh = g.getWarehouse();

        // ── Trừ kho theo FIFO ────────────────────────────────────────────────
        // Gom nhu cầu theo nguyên liệu trước khi trừ: một phiếu có thể tặng nhiều sản
        // phẩm dùng chung nguyên liệu, trừ từng dòng riêng lẻ sẽ tạo nhiều bản ghi
        // xuất vụn cho cùng một nguyên liệu và khó đối chiếu.
        Map<Long, IngredientNeed> needs = _aggregateNeeds(g);

        List<WarehouseReceiptItem> receiptItems = new ArrayList<>();
        for (IngredientNeed need : needs.values()) {
            BigDecimal before = _stockOf(wh.getId(), need.ingredientId);

            fifoDeductService.deductById(null, wh, need.ingredientId,
                    need.ingredientName, need.quantity, now);

            BigDecimal after = before.subtract(need.quantity);

            receiptItems.add(WarehouseReceiptItem.builder()
                    .ingredientId(need.ingredientId)
                    .ingredientNameSnapshot(need.ingredientName)
                    .ingredientUnitSnapshot(need.unit)
                    .quantity(need.quantity)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    // difference là cột NOT NULL. Đây là phiếu XUẤT nên tồn giảm ⇒ giá trị
                    // âm, đúng quy ước "difference = quantityAfter - quantityBefore" mà các
                    // phiếu khác trong hệ thống đang dùng.
                    .difference(after.subtract(before))
                    .build());
        }

        // ── Sinh phiếu xuất kho ──────────────────────────────────────────────
        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(_generateReceiptCode())
                .receiptType(WarehouseReceipt.ReceiptType.EXPORT_OTHER)
                .costStatus(WarehouseReceipt.CostStatus.CONFIRMED)
                .warehouse(wh)
                .referenceCode(g.getCode())
                .reason("Tặng quà cho " + g.getCustomerName())
                .note("Phiếu tặng quà " + g.getCode()
                        + " — duyệt bởi " + _nameOf(approver))
                .createdBy(g.getCreatedBy())
                .createdByName(g.getCreatedByName() != null ? g.getCreatedByName() : _nameOf(approver))
                .createdAt(now)
                .updatedAt(now)
                .build();

        receiptItems.forEach(it -> it.setReceipt(receipt));
        receipt.setItems(receiptItems);
        WarehouseReceipt saved = warehouseReceiptRepository.save(receipt);

        // ── Cập nhật phiếu ───────────────────────────────────────────────────
        g.setStatus(GiftOrder.GiftOrderStatus.APPROVED);
        g.setApprovedBy(approver);
        g.setApprovedByName(_nameOf(approver));
        g.setApprovedAt(now);
        g.setWarehouseReceiptId(saved.getId());

        log.info("[GiftOrder] Duyệt phiếu {} — xuất kho {} ({} nguyên liệu)",
                g.getCode(), wh.getName(), needs.size());

        return toDto(giftOrderRepository.save(g));
    }

    @Transactional
    public GiftOrderDto reject(Long id, String reason, User approver) {
        GiftOrder g = _findOrThrow(id);
        if (g.getStatus() != GiftOrder.GiftOrderStatus.PENDING)
            throw new BusinessException("Chỉ từ chối được phiếu đang chờ duyệt");
        if (reason == null || reason.isBlank())
            throw new BusinessException("Vui lòng nhập lý do từ chối");

        g.setStatus(GiftOrder.GiftOrderStatus.REJECTED);
        g.setRejectReason(reason.trim());
        g.setApprovedBy(approver);
        g.setApprovedByName(_nameOf(approver));
        g.setApprovedAt(System.currentTimeMillis());
        return toDto(giftOrderRepository.save(g));
    }

    /** Seller tự huỷ phiếu của mình khi còn chờ duyệt. */
    @Transactional
    public GiftOrderDto cancel(Long id, User actor) {
        GiftOrder g = _findOrThrow(id);
        if (g.getStatus() != GiftOrder.GiftOrderStatus.PENDING)
            throw new BusinessException("Chỉ huỷ được phiếu đang chờ duyệt");

        boolean isOwner = g.getCreatedBy() != null && actor != null
                && g.getCreatedBy().getId() == actor.getId();
        Set<Role> roles = actor != null ? actor.getAllRoles() : Set.of();
        boolean isAdminLevel = roles.contains(Role.ADMIN) || roles.contains(Role.OWNER)
                || roles.contains(Role.SUPERADMIN);
        if (!isOwner && !isAdminLevel)
            throw new BusinessException("Bạn chỉ huỷ được phiếu do mình tạo");

        g.setStatus(GiftOrder.GiftOrderStatus.CANCELLED);
        return toDto(giftOrderRepository.save(g));
    }

    // ════════════════════════════════════════════════════════════════════════
    // KHO XỬ LÝ
    // ════════════════════════════════════════════════════════════════════════

    /** Nhân viên kho xác nhận đã soạn hàng và cho đi giao. */
    @Transactional
    public GiftOrderDto startDelivery(Long id, User staff) {
        GiftOrder g = _findOrThrow(id);
        if (g.getStatus() != GiftOrder.GiftOrderStatus.APPROVED)
            throw new BusinessException("Phiếu chưa được duyệt hoặc đã xử lý");

        g.setStatus(GiftOrder.GiftOrderStatus.DELIVERING);
        g.setHandledBy(staff);
        g.setHandledByName(_nameOf(staff));
        g.setHandledAt(System.currentTimeMillis());
        return toDto(giftOrderRepository.save(g));
    }

    @Transactional
    public GiftOrderDto complete(Long id, User staff) {
        GiftOrder g = _findOrThrow(id);
        if (g.getStatus() != GiftOrder.GiftOrderStatus.DELIVERING
                && g.getStatus() != GiftOrder.GiftOrderStatus.APPROVED)
            throw new BusinessException("Phiếu không ở trạng thái đang giao");

        g.setStatus(GiftOrder.GiftOrderStatus.COMPLETED);
        if (g.getHandledBy() == null) {
            g.setHandledBy(staff);
            g.setHandledByName(_nameOf(staff));
            g.setHandledAt(System.currentTimeMillis());
        }
        return toDto(giftOrderRepository.save(g));
    }

    // ════════════════════════════════════════════════════════════════════════
    // PHÂN QUYỀN THEO KHÁCH HÀNG
    // ════════════════════════════════════════════════════════════════════════

    /**
     * AI ĐƯỢC THAO TÁC TRÊN KHÁCH NÀY.
     *
     * <p>Quy tắc: khách <b>chưa được gán</b> cho seller nào thì <b>ai cũng thao tác được</b>
     * (khách lẻ vãng lai, ai tiếp thì người đó chăm). Khách <b>đã được gán</b> thì
     * <b>chỉ seller được gán</b> nhìn thấy và thao tác.
     *
     * <p>ADMIN / OWNER / SUPERADMIN / SUPER_SELLER luôn thao tác được — họ là cấp quản lý
     * và cần xử lý được cả khi seller phụ trách nghỉ.
     *
     * <p>Người TẠO ra khách cũng được tính là chủ sở hữu khi chưa có ai được gán chính
     * thức: dữ liệu cũ có nhiều khách chỉ có {@code createdBySeller} mà chưa có
     * {@code assignedSeller}, bỏ qua cột này sẽ khoá seller khỏi chính khách mình nhập.
     */
    public boolean canActOnCustomer(Customer c, User actor) {
        if (actor == null) return false;

        Set<Role> roles = actor.getAllRoles();
        if (roles.contains(Role.ADMIN) || roles.contains(Role.OWNER)
                || roles.contains(Role.SUPERADMIN) || roles.contains(Role.SUPER_SELLER))
            return true;

        User assigned = c.getAssignedSeller();
        if (assigned == null) {
            // Chưa gán chính thức: người tạo giữ quyền, còn lại thì mở cho tất cả.
            User creator = c.getCreatedBySeller();
            return creator == null || creator.getId() == actor.getId();
        }
        return assigned.getId() == actor.getId();
    }

    // ════════════════════════════════════════════════════════════════════════
    // INTERNALS
    // ════════════════════════════════════════════════════════════════════════

    /** Nhu cầu nguyên liệu đã gom theo id. */
    private static final class IngredientNeed {
        final Long ingredientId;
        final String ingredientName;
        final String unit;
        BigDecimal quantity = BigDecimal.ZERO;
        final Set<String> fromProducts = new LinkedHashSet<>();

        IngredientNeed(Long id, String name, String unit) {
            this.ingredientId = id; this.ingredientName = name; this.unit = unit;
        }
    }

    private Map<Long, IngredientNeed> _aggregateNeeds(GiftOrder g) {
        Map<Long, IngredientNeed> needs = new LinkedHashMap<>();
        for (GiftOrderItem item : g.getItems()) {
            for (ProductIngredient pi : productIngredientRepository.findByProductId(item.getProductId())) {
                BigDecimal required = pi.getQty().multiply(item.getQuantity());
                IngredientNeed n = needs.computeIfAbsent(pi.getIngredientId(),
                        k -> new IngredientNeed(pi.getIngredientId(),
                                pi.getIngredientNameSnapshot(), pi.getIngredientUnitSnapshot()));
                n.quantity = n.quantity.add(required);
                n.fromProducts.add(item.getProductName());
            }
        }
        return needs;
    }

    private StockCheckResult _checkStock(GiftOrder g) {
        Long whId = g.getWarehouse().getId();
        List<Shortage> shortages = new ArrayList<>();

        for (IngredientNeed need : _aggregateNeeds(g).values()) {
            BigDecimal available = _stockOf(whId, need.ingredientId);
            if (available.compareTo(need.quantity) < 0) {
                shortages.add(Shortage.builder()
                        .productName(String.join(", ", need.fromProducts))
                        .ingredientId(need.ingredientId)
                        .ingredientName(need.ingredientName)
                        .unit(need.unit)
                        .required(need.quantity)
                        .available(available)
                        .missing(need.quantity.subtract(available))
                        .build());
            }
        }
        return StockCheckResult.builder()
                .ok(shortages.isEmpty())
                .shortages(shortages)
                .build();
    }

    private BigDecimal _stockOf(Long warehouseId, Long ingredientId) {
        return ingredientStockRepository
                .findByIngredientIdAndWarehouseId(ingredientId, warehouseId)
                .map(s -> s.getStockQuantity() != null ? s.getStockQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Số sản phẩm tối đa làm được từ tồn hiện có = min qua các nguyên liệu của công thức.
     *
     * <p>Nguyên liệu KHÔNG có dòng tồn được coi là tồn 0 (trả về 0), không phải bỏ qua:
     * hàm này chỉ được gọi sau khi đã xác nhận cả công thức thuộc kho, nên thiếu dòng tồn
     * nghĩa là hết hàng thật chứ không phải "nguyên liệu này kho khác giữ".
     */
    private BigDecimal _maxProducible(List<ProductIngredient> recipe,
                                      Map<Long, BigDecimal> stockByIngredient) {
        BigDecimal min = null;

        for (ProductIngredient pi : recipe) {
            if (pi.getQty() == null || pi.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal stock = stockByIngredient.getOrDefault(pi.getIngredientId(), BigDecimal.ZERO);

            BigDecimal producible = stock.divide(pi.getQty(), 0, RoundingMode.DOWN);
            if (min == null || producible.compareTo(min) < 0) min = producible;
        }
        return min != null ? min : BigDecimal.ZERO;
    }

    /**
     * SẢN PHẨM NÀY CÓ CHO NHẬP SỐ LẺ KHÔNG.
     *
     * <p>Cho phép nếu đơn vị của SẢN PHẨM, hoặc của bất kỳ nguyên liệu nào trong công
     * thức, thuộc nhóm đơn vị đo lường được (kg, lít, mét…). Sản phẩm đếm theo cái/thùng
     * và làm từ nguyên liệu cũng đếm nguyên thì chặn số lẻ.
     *
     * <p>Xét cả đơn vị sản phẩm chứ không chỉ nguyên liệu: một sản phẩm bán theo kg hoàn
     * toàn có thể ghép từ nguyên liệu đếm theo cái, và lúc đó tặng 1,5 kg vẫn hợp lệ.
     */
    private boolean _allowsDecimal(Product p, List<ProductIngredient> recipe) {
        if (UnitDecimalRule.allowsDecimal(p.getUnit())) return true;
        return recipe.stream()
                .anyMatch(pi -> UnitDecimalRule.allowsDecimal(pi.getIngredientUnitSnapshot()));
    }

    /**
     * Chặn số lẻ với sản phẩm đếm nguyên, và giới hạn 3 chữ số thập phân với sản phẩm
     * đo lường được.
     */
    private void _validateQuantityScale(Product p, List<ProductIngredient> recipe, BigDecimal qty) {
        if (qty == null) return;
        BigDecimal stripped = qty.stripTrailingZeros();

        if (!_allowsDecimal(p, recipe)) {
            if (stripped.scale() > 0)
                throw new BusinessException("Sản phẩm \"" + p.getName()
                        + "\" tính theo đơn vị nguyên, không nhập được số lẻ");
            return;
        }
        if (stripped.scale() > UnitDecimalRule.MAX_SCALE)
            throw new BusinessException("Số lượng \"" + p.getName() + "\" chỉ được tối đa "
                    + UnitDecimalRule.MAX_SCALE + " chữ số sau dấu thập phân");
    }

    private GiftOrder _findOrThrow(Long id) {
        return giftOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu tặng quà #" + id));
    }

    public GiftOrderDto toDto(GiftOrder g) {
        Customer c = g.getCustomer();
        return GiftOrderDto.builder()
                .id(g.getId())
                .code(g.getCode())
                .customerId(c != null ? c.getId() : null)
                .customerName(g.getCustomerName())
                .customerPhone(c != null ? (c.getPhone() != null ? c.getPhone() : c.getCompanyPhone()) : null)
                .customerType(c != null && c.getCustomerType() != null ? c.getCustomerType().name() : null)
                .warehouseId(g.getWarehouse() != null ? g.getWarehouse().getId() : null)
                .warehouseName(g.getWarehouse() != null ? g.getWarehouse().getName() : null)
                .occasion(g.getOccasion() != null ? g.getOccasion().name() : null)
                .status(g.getStatus() != null ? g.getStatus().name() : null)
                .note(g.getNote())
                .createdByName(g.getCreatedByName())
                .createdAt(g.getCreatedAt())
                .approvedByName(g.getApprovedByName())
                .approvedAt(g.getApprovedAt())
                .rejectReason(g.getRejectReason())
                .handledByName(g.getHandledByName())
                .handledAt(g.getHandledAt())
                .warehouseReceiptId(g.getWarehouseReceiptId())
                .totalQuantity(g.totalQuantity())
                .items(g.getItems().stream()
                        .map(i -> GiftOrderItemDto.builder()
                                .productId(i.getProductId())
                                .productName(i.getProductName())
                                .unit(i.getUnit())
                                .quantity(i.getQuantity())
                                .build())
                        .toList())
                .build();
    }

    private GiftOrder.GiftOccasion _resolveOccasion(String raw, Customer c) {
        if (raw != null && !raw.isBlank()) {
            try { return GiftOrder.GiftOccasion.valueOf(raw.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* rơi xuống suy luận bên dưới */ }
        }
        return c.getCustomerType() == Customer.CustomerType.COMPANY
                ? GiftOrder.GiftOccasion.STORE_OPENING
                : GiftOrder.GiftOccasion.BIRTHDAY;
    }

    private GiftOrder.GiftOrderStatus _parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return GiftOrder.GiftOrderStatus.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private String _displayName(Customer c) {
        if (c.getCustomerType() == Customer.CustomerType.COMPANY
                && c.getCompanyName() != null && !c.getCompanyName().isBlank())
            return c.getCompanyName();
        return c.getName() != null ? c.getName() : ("KH#" + c.getId());
    }

    private String _nameOf(User u) {
        if (u == null) return null;
        return u.getFullName() != null ? u.getFullName() : u.getUsername();
    }

    private String _generateCode() {
        String prefix = "QT-" + LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("yyMM")) + "-";
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int j = 0; j < 4; j++)
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            String code = sb.toString();
            if (!giftOrderRepository.existsByCode(code)) return code;
        }
        return prefix + System.currentTimeMillis();
    }

    private String _generateReceiptCode() {
        return "XKQT-" + System.currentTimeMillis();
    }

    private static String _blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
