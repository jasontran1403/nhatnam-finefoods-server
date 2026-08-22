package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.expense.CreateExpenseVoucherRequest;
import com.nhatnam.server.dto.expense.ExpenseVoucherDto;
import com.nhatnam.server.dto.supply.SupplyDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.utils.CostAllocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PHIẾU ĐẶT HÀNG VĂN PHÒNG PHẨM / ĐỒ DÙNG — 4 bước.
 *
 * <pre>
 *   B1 SUPER_SELLER / SUPER_WAREHOUSE / SUPER_FACTORY_WORKER  → lập phiếu   (NEW)
 *   B2 SUPER_ACCOUNTANT  → xác nhận đặt hàng / từ chối         (ORDERED | REJECTED)
 *   B3 NGƯỜI TẠO PHIẾU   → nhận hàng nhiều đợt                 (PARTIALLY_RECEIVED → RECEIVED)
 *   B4 SUPER_ACCOUNTANT  → tất toán (phiếu chi | công nợ)      (COMPLETED)
 * </pre>
 *
 * <h3>Vì sao dùng chung entity với phiếu nguyên liệu?</h3>
 * {@link MaterialRequest}/{@link MaterialRequestItem}/{@link MaterialRequestReceipt}
 * đã có sẵn toàn bộ cơ chế mã phiếu, nhận nhiều đợt, optimistic lock và — quan trọng
 * nhất — {@link MaterialRequestVendor} chính là bảng mà hệ thống CÔNG NỢ NCC đang
 * đọc. Nhờ tái sử dụng, luồng DEBT ở bước 4 chạy đúng luồng công nợ có sẵn mà không
 * phải viết lại gì. Phần riêng của VPP nằm ở {@link SupplyOrderGroup}.
 *
 * <p>Hai luồng được tách tuyệt đối bằng cờ {@code orderType} + 2 query riêng trong
 * {@link MaterialRequestRepository} — page phiếu nguyên liệu cũ không bao giờ thấy
 * phiếu VPP và ngược lại.
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class SupplyOrderService {

    // Ghi chú: các bảng con (item / vendor / receipt) đều được cascade từ
    // MaterialRequest nên chỉ cần repository của aggregate root.
    private final MaterialRequestRepository requestRepo;
    private final SupplyOrderGroupRepository groupRepo;
    private final SupplyWarehouseRepository warehouseRepo;
    private final VendorExpenseCategoryRepository categoryRepo;
    private final MaterialVendorRepository vendorRepo;
    private final UserRepository userRepo;
    private final ExpenseVoucherRepository voucherRepo;

    private final SupplyWarehouseService warehouseService;
    private final ExpenseVoucherService expenseVoucherService;
    private final NotificationService notificationService;

    /**
     * TẠO NHANH danh mục khoản chi ngay trên form lập phiếu.
     *
     * <p>Uỷ quyền hoàn toàn cho service của trang Quản lý NCC thay vì chép lại
     * logic: toàn bộ validate (trùng tên, CONSUMABLE bắt buộc ĐVT + quy cách) và
     * — quan trọng nhất — bước {@code getOrCreate(tên, quy cách, ĐVT)} sinh
     * {@code supplyItemId} đều nằm ở đó. Chép lại đồng nghĩa với việc có hai
     * đường tạo danh mục và chỉ cần một đường quên gọi getOrCreate là tồn kho vỡ
     * thành 2 dòng cho cùng một món.
     */
    private final SupplierManagementService supplierManagementService;

    /** Làm tròn LÊN hàng đơn vị đồng — chỉ áp dụng ở BƯỚC CUỐI. */
    private static BigDecimal roundUpToDong(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(0, RoundingMode.CEILING);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BƯỚC 1 — Người tạo lập phiếu
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tạo phiếu. {@code draft = true} → chỉ LƯU, không bắn WS cho kế toán;
     * {@code draft = false} → "Tạo phiếu", gửi WS cho toàn bộ SUPER_ACCOUNTANT.
     * Cả 2 trường hợp trạng thái đều là {@code NEW} (nháp chỉ khác ở chỗ chưa báo).
     */
    @Transactional
    public SupplyOrderDto create(CreateSupplyOrderRequest req, String username) {
        User creator = mustUser(username);

        Long whId = req.getSupplyWarehouseId();
        if (whId == null) throw new BusinessException("Vui lòng chọn kho nhận hàng");
        // Chỉ được chọn kho ĐÃ ĐƯỢC GÁN cho mình
        if (!warehouseService.assignedWarehouseIds(username).contains(whId))
            throw new BusinessException("Bạn không được phân quyền trên kho đã chọn");
        warehouseRepo.findById(whId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kho nhận"));

        if (req.getItems() == null || req.getItems().isEmpty())
            throw new BusinessException("Phiếu phải có ít nhất 1 mặt hàng");

        MaterialRequest mr = MaterialRequest.builder()
                .requestCode(generateCode())
                .createdBy(creator)
                .createdByName(displayName(creator))
                .requiredBy(req.getRequiredBy())
                .status(MaterialRequest.RequestStatus.NEW)
                .type(MaterialRequest.RequestType.FACTORY)   // không dùng ở luồng SUPPLY
                .orderType(MaterialRequest.OrderType.SUPPLY)
                .supplyWarehouseId(whId)
                .build();

        int order = 0;
        for (SupplyOrderItemRequest line : req.getItems()) {
            mr.getItems().add(buildItem(mr, line, order++));
        }

        MaterialRequest saved = requestRepo.save(mr);

        if (!req.isDraft()) notifyAccountantsOnSubmit(saved, creator);
        return toDto(saved);
    }

    /** Sửa phiếu nháp (chỉ khi còn {@code NEW} và do chính người tạo). */
    @Transactional
    public SupplyOrderDto updateDraft(Long id, CreateSupplyOrderRequest req, String username) {
        MaterialRequest mr = mustSupplyOrder(id);
        User user = mustUser(username);
        assertCreator(mr, user);
        if (mr.getStatus() != MaterialRequest.RequestStatus.NEW)
            throw new BusinessException("Phiếu đã được xử lý, không thể sửa");

        if (req.getSupplyWarehouseId() != null) {
            if (!warehouseService.assignedWarehouseIds(username).contains(req.getSupplyWarehouseId()))
                throw new BusinessException("Bạn không được phân quyền trên kho đã chọn");
            mr.setSupplyWarehouseId(req.getSupplyWarehouseId());
        }
        mr.setRequiredBy(req.getRequiredBy());

        mr.getItems().clear();
        int order = 0;
        for (SupplyOrderItemRequest line : req.getItems()) {
            mr.getItems().add(buildItem(mr, line, order++));
        }
        MaterialRequest saved = requestRepo.save(mr);
        if (!req.isDraft()) notifyAccountantsOnSubmit(saved, user);
        return toDto(saved);
    }

    /**
     * Dựng 1 dòng mặt hàng và SNAPSHOT tên / ĐVT / quy cách / supplyItemId từ
     * danh mục khoản chi. Snapshot là bắt buộc: Owner đổi danh mục về sau không
     * được phép làm sai lệch phiếu đã lập.
     */
    private MaterialRequestItem buildItem(MaterialRequest mr, SupplyOrderItemRequest line, int order) {
        if (line.getExpenseCategoryId() == null)
            throw new BusinessException("Mỗi mặt hàng phải chọn một danh mục khoản chi");
        if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Số lượng phải lớn hơn 0");

        VendorExpenseCategory cat = categoryRepo.findById(line.getExpenseCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Danh mục khoản chi không tồn tại"));
        if (!cat.isActive())
            throw new BusinessException("Danh mục \"" + cat.getName() + "\" đã bị ẩn");

        // NCC: người tạo được chọn TẤT CẢ NCC (không giới hạn NCC nguyên liệu).
        MaterialVendor vendor = null;
        if (line.getSupplierId() != null) {
            vendor = vendorRepo.findById(line.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Nhà cung cấp không tồn tại"));
        }

        boolean consumable = cat.isConsumable();   // null (nhãn cũ) ⇒ dịch vụ

        return MaterialRequestItem.builder()
                .materialRequest(mr)
                .expenseCategoryId(cat.getId())
                .materialName(cat.getName())                                   // itemName (snapshot)
                .unit(cat.getUnit() != null && !cat.getUnit().isBlank() ? cat.getUnit() : "Lần")
                .specification(cat.getSpecification())                         // snapshot
                .supplyItemId(consumable ? cat.getSupplyItemId() : null)       // SERVICE ⇒ không nhập kho
                .supplierId(vendor != null ? vendor.getId() : null)
                .qtyRequested(line.getQuantity())
                .note(line.getNote())
                .receiveStatus(MaterialRequestItem.ReceiveStatus.PENDING)
                .receiveClosed(false)
                .sortOrder(line.getSortOrder() != null ? line.getSortOrder() : order)
                .build();
    }

    private void notifyAccountantsOnSubmit(MaterialRequest mr, User creator) {
        notificationService.sendToRole(
                Role.SUPER_ACCOUNTANT.name(),
                "SUPPLY_ORDER_CREATED",
                displayName(creator) + " tạo phiếu đặt văn phòng phẩm " + mr.getRequestCode(),
                payload(mr));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BƯỚC 2 — SUPER_ACCOUNTANT xác nhận đặt hàng / từ chối
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * XÁC NHẬN ĐẶT HÀNG — <b>chỉ duyệt HẾT hoặc từ chối HẾT</b>, không có duyệt
     * một phần. Sinh các {@link SupplyOrderGroup} theo NCC (kèm
     * {@link MaterialRequestVendor} tương ứng để nối vào hệ thống công nợ).
     */
    @Transactional
    public SupplyOrderDto confirmOrder(Long id, ConfirmSupplyOrderRequest req, String username) {
        MaterialRequest mr = mustSupplyOrder(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.NEW)
            throw new BusinessException("Phiếu không ở trạng thái Mới tạo");
        if (req.getGroups() == null || req.getGroups().isEmpty())
            throw new BusinessException("Vui lòng gán nhà cung cấp cho các mặt hàng");

        User handler = mustUser(username);

        Map<Long, MaterialRequestItem> itemById = mr.getItems().stream()
                .collect(Collectors.toMap(MaterialRequestItem::getId, i -> i));

        // Mọi mặt hàng đều phải được gán đúng 1 NCC — duyệt là duyệt hết.
        Set<Long> covered = new HashSet<>();
        for (GroupConfirmRequest g : req.getGroups()) {
            if (g.getSupplierId() == null)
                throw new BusinessException("Thiếu nhà cung cấp cho một nhóm");
            if (g.getItemIds() == null || g.getItemIds().isEmpty())
                throw new BusinessException("Nhóm NCC không có mặt hàng nào");
            for (Long itemId : g.getItemIds()) {
                if (!itemById.containsKey(itemId))
                    throw new BusinessException("Mặt hàng không thuộc phiếu này (id=" + itemId + ")");
                if (!covered.add(itemId))
                    throw new BusinessException("Một mặt hàng chỉ được gán cho đúng 1 nhà cung cấp");
            }
        }
        if (covered.size() != mr.getItems().size()) {
            List<String> missing = mr.getItems().stream()
                    .filter(i -> !covered.contains(i.getId()))
                    .map(MaterialRequestItem::getMaterialName).toList();
            throw new BusinessException("Chưa gán nhà cung cấp cho: " + String.join(", ", missing));
        }

        long now = System.currentTimeMillis();
        int seq = 1;
        for (GroupConfirmRequest g : req.getGroups()) {
            MaterialVendor vendor = vendorRepo.findById(g.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Nhà cung cấp không tồn tại"));

            // Bản ghi NCC-trong-phiếu → cầu nối sang hệ thống công nợ có sẵn.
            MaterialRequestVendor rv = MaterialRequestVendor.builder()
                    .materialRequest(mr)
                    .vendor(vendor)
                    .vendorName(vendor.getName())
                    .contactPerson(g.getContactName())
                    .contactPhone(g.getContactPhone())
                    .sortOrder(seq)
                    .build();
            mr.getVendors().add(rv);

            SupplyOrderGroup group = SupplyOrderGroup.builder()
                    .materialRequest(mr)
                    .supplierId(vendor.getId())
                    .supplierName(vendor.getName())
                    .requestVendor(rv)
                    .code(mr.getRequestCode() + "-G" + seq)
                    .status(SupplyOrderGroup.GroupStatus.ORDERED)
                    .expectedDeliveryAt(g.getExpectedDeliveryAt())
                    .contactName(g.getContactName())
                    .contactPhone(g.getContactPhone())
                    .build();
            mr.getSupplyGroups().add(group);
            seq++;
        }

        mr.setStatus(MaterialRequest.RequestStatus.ORDERED);
        mr.setOrderedAt(now);
        mr.setHandledBy(handler);
        mr.setHandledByName(displayName(handler));
        req.getGroups().stream()
                .map(GroupConfirmRequest::getExpectedDeliveryAt)
                .filter(Objects::nonNull).min(Long::compareTo)
                .ifPresent(mr::setEstimatedDelivery);

        MaterialRequest saved = requestRepo.save(mr);

        // Sau khi flush mới có id của group/rv → gán ngược vào từng dòng.
        Map<Long, SupplyOrderGroup> groupBySupplier = saved.getSupplyGroups().stream()
                .collect(Collectors.toMap(SupplyOrderGroup::getSupplierId, gg -> gg));
        for (GroupConfirmRequest g : req.getGroups()) {
            SupplyOrderGroup group = groupBySupplier.get(g.getSupplierId());
            for (Long itemId : g.getItemIds()) {
                MaterialRequestItem it = itemById.get(itemId);
                it.setSupplierId(g.getSupplierId());
                it.setSupplyGroupId(group.getId());
                it.setSuppliedByVendor(group.getRequestVendor());
            }
        }
        saved = requestRepo.save(saved);

        notificationService.sendToUser(
                saved.getCreatedBy(), "SUPPLY_ORDER_CONFIRMED",
                "Phiếu " + saved.getRequestCode() + " đã được xác nhận đặt hàng",
                payload(saved));

        return toDto(saved);
    }

    /** TỪ CHỐI — {@code REJECTED} là TERMINAL, không có đường quay lại. */
    @Transactional
    public SupplyOrderDto reject(Long id, RejectSupplyOrderRequest req, String username) {
        MaterialRequest mr = mustSupplyOrder(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.NEW)
            throw new BusinessException("Chỉ có thể từ chối phiếu ở trạng thái Mới tạo");
        if (req.getReason() == null || req.getReason().isBlank())
            throw new BusinessException("Vui lòng nhập lý do từ chối");

        User handler = mustUser(username);
        mr.setStatus(MaterialRequest.RequestStatus.REJECTED);
        mr.setRejectReason(req.getReason().trim());
        mr.setHandledBy(handler);
        mr.setHandledByName(displayName(handler));
        MaterialRequest saved = requestRepo.save(mr);

        notificationService.sendToUser(
                saved.getCreatedBy(), "SUPPLY_ORDER_REJECTED",
                "Phiếu " + saved.getRequestCode() + " bị từ chối: " + saved.getRejectReason(),
                payload(saved));
        return toDto(saved);
    }

    /** Gia hạn ETA của một nhóm NCC (giao trễ). */
    @Transactional
    public SupplyOrderDto extendDelivery(Long id, ExtendDeliveryRequest req, String username) {
        MaterialRequest mr = mustSupplyOrder(id);
        SupplyOrderGroup group = groupRepo.findById(req.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm NCC"));
        if (!Objects.equals(group.getMaterialRequest().getId(), mr.getId()))
            throw new BusinessException("Nhóm NCC không thuộc phiếu này");
        if (group.getStatus() == SupplyOrderGroup.GroupStatus.SETTLED)
            throw new BusinessException("Nhóm đã tất toán, không thể gia hạn");

        group.setExpectedDeliveryAt(req.getNewExpectedDeliveryAt());
        groupRepo.save(group);

        mr.setDeliveryExtendedTo(req.getNewExpectedDeliveryAt());
        mr.setDeliveryExtendReason(req.getReason());
        MaterialRequest saved = requestRepo.save(mr);

        notificationService.sendToUser(
                saved.getCreatedBy(), "SUPPLY_ORDER_DELIVERY_EXTENDED",
                "Phiếu " + saved.getRequestCode() + " — NCC " + group.getSupplierName() + " gia hạn giao hàng",
                payload(saved));
        return toDto(saved);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BƯỚC 3 — Người tạo phiếu nhận hàng
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Lưu / xác nhận MỘT ĐỢT nhận hàng.
     *
     * <ul>
     *   <li><b>CHỈ NGƯỜI TẠO PHIẾU</b> được nhập số thực nhận.</li>
     *   <li>{@code draft = true}  → ghi đè đợt nháp, KHÔNG cộng kho.</li>
     *   <li>{@code draft = false} → chốt đợt: cộng tồn kho VPP theo số thực nhận
     *       của đợt đó, vào KHO ĐÃ CHỌN Ở BƯỚC 1.</li>
     *   <li>Chỉ dòng CONSUMABLE sinh transaction {@code IN}; dòng SERVICE bỏ qua.</li>
     * </ul>
     *
     * Trạng thái: {@code PARTIALLY_RECEIVED} → {@code RECEIVED} khi TẤT CẢ mặt
     * hàng đã được đánh dấu nhận xong (dù đủ hay thiếu).
     */
    @Transactional
    public SupplyOrderDto saveReceipt(Long id, SaveSupplyReceiptRequest req, String username) {
        MaterialRequest mr = mustSupplyOrder(id);
        User user = mustUser(username);
        assertCreator(mr, user);

        if (mr.getStatus() != MaterialRequest.RequestStatus.ORDERED
                && mr.getStatus() != MaterialRequest.RequestStatus.PARTIALLY_RECEIVED)
            throw new BusinessException("Phiếu chưa được xác nhận đặt hàng hoặc đã nhận xong");
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new BusinessException("Đợt nhận chưa có dòng nào");

        Map<Long, MaterialRequestItem> itemById = mr.getItems().stream()
                .collect(Collectors.toMap(MaterialRequestItem::getId, i -> i));

        // Đợt nháp: mỗi phiếu tối đa 1 — lần lưu sau ghi đè lần trước.
        MaterialRequestReceipt draft = mr.getReceipts().stream()
                .filter(MaterialRequestReceipt::isDraft).findFirst().orElse(null);

        MaterialRequestReceipt receipt;
        if (draft != null) {
            receipt = draft;
            receipt.getItems().clear();
        } else {
            int nextSeq = mr.getReceipts().stream()
                    .map(MaterialRequestReceipt::getSequenceNo)
                    .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
            receipt = MaterialRequestReceipt.builder()
                    .materialRequest(mr).sequenceNo(nextSeq).build();
            mr.getReceipts().add(receipt);
        }
        receipt.setDraft(req.isDraft());
        receipt.setReceivedAt(System.currentTimeMillis());
        receipt.setReceivedBy(user);
        receipt.setReceivedByName(displayName(user));
        receipt.setNotes(req.getNotes());

        for (SupplyReceiptLine line : req.getItems()) {
            MaterialRequestItem item = itemById.get(line.getItemId());
            if (item == null)
                throw new BusinessException("Mặt hàng không thuộc phiếu này (id=" + line.getItemId() + ")");
            if (item.isReceiveClosed())
                throw new BusinessException("Mặt hàng \"" + item.getMaterialName() + "\" đã chốt nhận");
            BigDecimal qty = line.getQty() == null ? BigDecimal.ZERO : line.getQty();
            if (qty.compareTo(BigDecimal.ZERO) < 0)
                throw new BusinessException("Số thực nhận không hợp lệ");

            receipt.getItems().add(MaterialRequestReceiptItem.builder()
                    .receipt(receipt).materialRequestItem(item)
                    .qty(qty).stockQty(qty)
                    .warehouseId(mr.getSupplyWarehouseId())
                    .build());
        }

        // ── NHÁP: dừng ở đây, không đụng tới tồn kho ─────────────────────────
        if (req.isDraft()) {
            return toDto(requestRepo.save(mr));
        }

        // ── CHỐT ĐỢT: cộng kho + cập nhật cộng dồn ───────────────────────────
        Map<Long, Boolean> closeFlag = req.getItems().stream()
                .filter(l -> l.getItemId() != null)
                .collect(Collectors.toMap(SupplyReceiptLine::getItemId,
                        SupplyReceiptLine::isCloseLine, (a, b) -> a || b));

        for (MaterialRequestReceiptItem line : receipt.getItems()) {
            MaterialRequestItem item = line.getMaterialRequestItem();
            BigDecimal qty = line.getQty();

            BigDecimal newTotal = (item.getQtyReceived() == null ? BigDecimal.ZERO : item.getQtyReceived())
                    .add(qty);
            item.setQtyReceived(newTotal);

            // Chỉ CONSUMABLE mới nhập kho; SERVICE (dịch vụ) bỏ qua hoàn toàn.
            if (item.getSupplyItemId() != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                warehouseService.stockIn(
                        mr.getSupplyWarehouseId(), item.getSupplyItemId(), qty,
                        "RECEIPT", receipt.getId() != null ? receipt.getId() : mr.getId(),
                        "Nhận hàng phiếu " + mr.getRequestCode()
                                + " (đợt " + receipt.getSequenceNo() + ")",
                        user);
            }

            boolean close = Boolean.TRUE.equals(closeFlag.get(item.getId()));
            if (close) {
                item.setReceiveClosed(true);
                item.setReceiveStatus(newTotal.compareTo(item.getQtyRequested()) >= 0
                        ? MaterialRequestItem.ReceiveStatus.FULFILLED
                        : MaterialRequestItem.ReceiveStatus.CLOSED_SHORT);
            } else if (newTotal.compareTo(item.getQtyRequested()) >= 0) {
                // Nhận đủ → tự chốt, khỏi bắt người dùng bấm thêm.
                item.setReceiveClosed(true);
                item.setReceiveStatus(MaterialRequestItem.ReceiveStatus.FULFILLED);
            } else {
                item.setReceiveStatus(MaterialRequestItem.ReceiveStatus.PARTIAL);
            }
        }

        boolean allClosed = mr.getItems().stream().allMatch(MaterialRequestItem::isReceiveClosed);
        long now = System.currentTimeMillis();
        if (allClosed) {
            mr.setStatus(MaterialRequest.RequestStatus.RECEIVED);
            mr.setReceivedAt(now);
            mr.getSupplyGroups().forEach(g -> {
                if (g.getStatus() == SupplyOrderGroup.GroupStatus.ORDERED)
                    g.setStatus(SupplyOrderGroup.GroupStatus.RECEIVED);
            });
        } else {
            mr.setStatus(MaterialRequest.RequestStatus.PARTIALLY_RECEIVED);
        }

        MaterialRequest saved = requestRepo.save(mr);

        notificationService.sendToRole(
                Role.SUPER_ACCOUNTANT.name(), "SUPPLY_ORDER_RECEIVED",
                displayName(user) + " xác nhận nhận hàng phiếu " + saved.getRequestCode()
                        + (allClosed ? " (đã nhận xong)" : " (đợt " + receipt.getSequenceNo() + ")"),
                payload(saved));

        return toDto(saved);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BƯỚC 4 — SUPER_ACCOUNTANT tất toán
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TẤT TOÁN.
     *
     * <p><b>Điều kiện mở khoá:</b> toàn bộ mặt hàng trong phiếu đã được nhận
     * (dù thiếu) — tức trạng thái phiếu là {@code RECEIVED}.
     *
     * <p>Tiền tính theo SỐ LƯỢNG THỰC NHẬN. Kế toán chọn nhập đơn giá 1 đơn vị
     * (mặc định) hoặc tổng tiền 1 mặt hàng; thuế/phí nhập nhiều dòng, label tự
     * nhập, và được <b>phân bổ theo tỷ trọng giá trị</b> từng mặt hàng
     * ({@link CostAllocation}). Mọi bước trung gian giữ 3 số thập phân,
     * <b>chỉ làm tròn lên hàng đơn vị đồng ở bước cuối</b>.
     *
     * <p>Mỗi nhóm NCC → PAY_NOW: 1 phiếu chi (đi qua đúng service auto-duyệt
     * hiện có) · DEBT: 1 bút toán công nợ (không tạo phiếu chi).
     */
    @Transactional
    public SupplyOrderDto settle(Long id, SettleSupplyOrderRequest req, String username) {
        MaterialRequest mr = mustSupplyOrder(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.RECEIVED)
            throw new BusinessException(
                    "Chỉ tất toán được khi TẤT CẢ mặt hàng trong phiếu đã được nhận (dù thiếu)");
        if (req.getGroups() == null || req.getGroups().isEmpty())
            throw new BusinessException("Chưa có dữ liệu tất toán");

        User handler = mustUser(username);
        List<SupplyOrderGroup> groups = groupRepo.findByMaterialRequest_IdOrderByIdAsc(mr.getId());
        Map<Long, SupplyOrderGroup> groupById = groups.stream()
                .collect(Collectors.toMap(SupplyOrderGroup::getId, g -> g));
        if (req.getGroups().size() != groups.size())
            throw new BusinessException("Phải tất toán đồng thời tất cả nhà cung cấp trong phiếu");

        Map<Long, MaterialRequestItem> itemById = mr.getItems().stream()
                .collect(Collectors.toMap(MaterialRequestItem::getId, i -> i));

        long now = System.currentTimeMillis();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (GroupSettleRequest gs : req.getGroups()) {
            SupplyOrderGroup group = groupById.get(gs.getGroupId());
            if (group == null) throw new BusinessException("Nhóm NCC không thuộc phiếu này");
            if (group.getStatus() == SupplyOrderGroup.GroupStatus.SETTLED)
                throw new BusinessException("Nhóm " + group.getSupplierName() + " đã tất toán");

            List<MaterialRequestItem> groupItems = mr.getItems().stream()
                    .filter(i -> Objects.equals(i.getSupplyGroupId(), group.getId()))
                    .toList();
            if (groupItems.isEmpty())
                throw new BusinessException("Nhóm " + group.getSupplierName() + " không có mặt hàng");

            // ── 1) Đơn giá / tổng tiền từng mặt hàng, theo SỐ THỰC NHẬN ──────
            Map<Long, BigDecimal> unitPriceByItem = new HashMap<>();
            Map<Long, BigDecimal> goodsByItem = new HashMap<>();

            Map<Long, ItemPriceRequest> priceById = (gs.getItems() == null ? List.<ItemPriceRequest>of() : gs.getItems())
                    .stream().filter(p -> p.getItemId() != null)
                    .collect(Collectors.toMap(ItemPriceRequest::getItemId, p -> p));

            for (MaterialRequestItem item : groupItems) {
                BigDecimal qty = item.getQtyReceived() == null ? BigDecimal.ZERO : item.getQtyReceived();
                ItemPriceRequest p = priceById.get(item.getId());
                if (p == null)
                    throw new BusinessException("Thiếu giá cho mặt hàng: " + item.getMaterialName());

                // Nhận 0 (NCC không giao được) → không tính tiền, nhưng vẫn hợp lệ.
                if (qty.compareTo(BigDecimal.ZERO) == 0) {
                    item.setPriceInputMode(MaterialRequestItem.PriceInputMode.UNIT_PRICE);
                    item.setUnitPrice(BigDecimal.ZERO);
                    item.setTotalAmount(BigDecimal.ZERO);
                    item.setLineAmount(BigDecimal.ZERO);
                    unitPriceByItem.put(item.getId(), BigDecimal.ZERO);
                    goodsByItem.put(item.getId(), BigDecimal.ZERO);
                    continue;
                }

                BigDecimal unitPrice, goods;
                if ("TOTAL".equalsIgnoreCase(p.getPriceInputMode())) {
                    if (p.getTotalAmount() == null || p.getTotalAmount().compareTo(BigDecimal.ZERO) < 0)
                        throw new BusinessException("Tổng tiền không hợp lệ cho: " + item.getMaterialName());
                    goods = CostAllocation.normalizeMoney(p.getTotalAmount());
                    unitPrice = CostAllocation.unitPriceFromTotal(goods, qty);
                    item.setPriceInputMode(MaterialRequestItem.PriceInputMode.TOTAL);
                } else {
                    if (p.getUnitPrice() == null || p.getUnitPrice().compareTo(BigDecimal.ZERO) < 0)
                        throw new BusinessException("Đơn giá không hợp lệ cho: " + item.getMaterialName());
                    unitPrice = CostAllocation.normalizeMoney(p.getUnitPrice());
                    goods = unitPrice.multiply(qty);
                    item.setPriceInputMode(MaterialRequestItem.PriceInputMode.UNIT_PRICE);
                }
                unitPriceByItem.put(item.getId(), unitPrice);
                goodsByItem.put(item.getId(), goods);
            }

            // ── 2) Phân bổ thuế/phí theo TỶ TRỌNG GIÁ TRỊ ────────────────────
            List<FeeRequest> feeReqs = gs.getFees() == null ? List.of() : gs.getFees();
            for (FeeRequest f : feeReqs) {
                if (f.getLabel() == null || f.getLabel().isBlank())
                    throw new BusinessException("Vui lòng đặt tên cho dòng thuế/phí");
                if (f.getAmount() == null || f.getAmount().compareTo(BigDecimal.ZERO) < 0)
                    throw new BusinessException("Số tiền thuế/phí không hợp lệ: " + f.getLabel());
            }

            List<Long> scope = groupItems.stream().map(MaterialRequestItem::getId).toList();
            List<CostAllocation.Line> lines = groupItems.stream()
                    .map(i -> new CostAllocation.Line(
                            i.getId(),
                            i.getQtyReceived() == null ? BigDecimal.ZERO : i.getQtyReceived(),
                            unitPriceByItem.get(i.getId())))
                    .toList();
            List<CostAllocation.Fee> fees = feeReqs.stream()
                    .map(f -> new CostAllocation.Fee(
                            f.getLabel().trim(), CostAllocation.normalizeMoney(f.getAmount()), scope))
                    .toList();
            Map<Long, CostAllocation.Result> alloc = CostAllocation.allocate(lines, fees);

            BigDecimal groupGoods = BigDecimal.ZERO;
            BigDecimal groupFees = BigDecimal.ZERO;
            for (MaterialRequestItem item : groupItems) {
                CostAllocation.Result r = alloc.get(item.getId());
                BigDecimal goods = goodsByItem.get(item.getId());
                BigDecimal feeShare = r != null && r.feeShare() != null ? r.feeShare() : BigDecimal.ZERO;

                // Giữ 3 số thập phân ở bước trung gian
                BigDecimal total = goods.add(feeShare)
                        .setScale(CostAllocation.MONEY_SCALE, RoundingMode.HALF_UP);
                item.setUnitPrice(unitPriceByItem.get(item.getId()));
                item.setTotalAmount(total);
                item.setLineAmount(total);

                groupGoods = groupGoods.add(goods);
                groupFees = groupFees.add(feeShare);
            }

            group.setGoodsAmount(groupGoods.setScale(CostAllocation.MONEY_SCALE, RoundingMode.HALF_UP));
            group.setFeeAmount(groupFees.setScale(CostAllocation.MONEY_SCALE, RoundingMode.HALF_UP));
            // BƯỚC CUỐI — và chỉ ở đây — mới làm tròn LÊN hàng đơn vị đồng.
            BigDecimal groupTotal = roundUpToDong(groupGoods.add(groupFees));
            group.setTotalAmount(groupTotal);
            grandTotal = grandTotal.add(groupTotal);

            group.getTaxFees().clear();
            int fo = 0;
            for (FeeRequest f : feeReqs) {
                group.getTaxFees().add(SupplyOrderGroupFee.builder()
                        .group(group).label(f.getLabel().trim())
                        .amount(CostAllocation.normalizeMoney(f.getAmount()))
                        .sortOrder(fo++).build());
            }

            // ── 3) Phương thức thanh toán ────────────────────────────────────
            MaterialRequestVendor rv = group.getRequestVendor();
            if (rv != null) rv.setTotalAmount(groupTotal);

            if ("PAY_NOW".equalsIgnoreCase(gs.getPaymentMode())) {
                ExpenseVoucherDto voucher = createExpenseVoucher(mr, group, groupItems, handler, gs);
                group.setPaymentMode(SupplyOrderGroup.PaymentMode.PAY_NOW);
                group.setPaymentVoucherId(voucher.getId());
                if (rv != null) {
                    rv.setPaymentStatus(MaterialRequestVendor.VendorPaymentStatus.PAID);
                    rv.setPaidAmount(groupTotal);
                    rv.setDebtSettlementStatus(MaterialRequestVendor.DebtSettlementStatus.SETTLED);
                    rv.setPaymentMethod("BANK_TRANSFER".equalsIgnoreCase(gs.getPaymentType()) ? "BANK" : "CASH");
                    rv.setPaymentInfo("Phiếu chi #" + voucher.getId());
                }
            } else if ("DEBT".equalsIgnoreCase(gs.getPaymentMode())) {
                if (rv == null || rv.getVendor() == null)
                    throw new BusinessException("NCC \"" + group.getSupplierName()
                            + "\" chưa liên kết danh mục Nhà cung cấp — không thể ghi công nợ");
                // Đúng luồng công nợ của phiếu nguyên liệu: KHÔNG tạo phiếu chi.
                rv.setPaymentStatus(MaterialRequestVendor.VendorPaymentStatus.DEBT);
                rv.setPaidAmount(BigDecimal.ZERO);
                rv.setDebtSettlementStatus(MaterialRequestVendor.DebtSettlementStatus.NONE);
                rv.setDebtSince(now);
                group.setPaymentMode(SupplyOrderGroup.PaymentMode.DEBT);
                group.setSupplierDebtId(rv.getId());
            } else {
                throw new BusinessException("Vui lòng chọn Thanh toán ngay hoặc Công nợ cho NCC: "
                        + group.getSupplierName());
            }

            group.setStatus(SupplyOrderGroup.GroupStatus.SETTLED);
            group.setSettledAt(now);
            groupRepo.save(group);
        }

        mr.setStatus(MaterialRequest.RequestStatus.COMPLETED);
        mr.setCompletedAt(now);
        mr.setHandledBy(handler);
        mr.setHandledByName(displayName(handler));
        MaterialRequest saved = requestRepo.save(mr);

        notificationService.sendToUser(
                saved.getCreatedBy(), "SUPPLY_ORDER_COMPLETED",
                "Phiếu " + saved.getRequestCode() + " đã tất toán — tổng "
                        + grandTotal.toPlainString() + " đ",
                payload(saved));

        return toDto(saved);
    }

    /**
     * Tạo PHIẾU CHI cho 1 nhóm NCC.
     *
     * <p>Đi qua <b>đúng service auto-duyệt hiện có</b>
     * ({@link ExpenseVoucherService#create}): trong danh mục cho phép VÀ dưới hạn
     * mức → tự duyệt; ngoài danh mục HOẶC vượt hạn mức → chờ OWNER duyệt. WS noti
     * cho OWNER đã nằm sẵn trong service đó nên KHÔNG viết mới ở đây.
     */
    private ExpenseVoucherDto createExpenseVoucher(MaterialRequest mr, SupplyOrderGroup group,
                                                   List<MaterialRequestItem> items,
                                                   User handler, GroupSettleRequest gs) {
        CreateExpenseVoucherRequest ev = new CreateExpenseVoucherRequest();
        ev.setVendorId(group.getSupplierId());
        ev.setVendorName(group.getSupplierName());
        vendorRepo.findById(group.getSupplierId()).ifPresent(v ->
                ev.setVendorType(v.getVendorType() != null ? v.getVendorType().name() : null));
        ev.setReason(gs.getReason() != null && !gs.getReason().isBlank()
                ? gs.getReason().trim()
                : "Thanh toán phiếu đặt văn phòng phẩm " + mr.getRequestCode()
                        + " — NCC " + group.getSupplierName());
        ev.setPaymentType("BANK_TRANSFER".equalsIgnoreCase(gs.getPaymentType()) ? "BANK_TRANSFER" : "CASH");
        ev.setBankName(gs.getBankName());
        ev.setBankRef(gs.getBankRef());
        ev.setImageUrls(gs.getImageUrls());
        ev.setExpenseDate(System.currentTimeMillis());

        List<CreateExpenseVoucherRequest.ExpenseItemRequest> evItems = new ArrayList<>();
        for (MaterialRequestItem item : items) {
            BigDecimal amount = item.getTotalAmount() == null ? BigDecimal.ZERO : item.getTotalAmount();
            if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;   // dòng nhận 0 → bỏ qua
            var line = new CreateExpenseVoucherRequest.ExpenseItemRequest();
            line.setCategoryId(item.getExpenseCategoryId());
            line.setItemName(item.getMaterialName());
            // ExpenseItem.amount là scale 0 → làm tròn LÊN đồng ở đúng bước cuối này.
            line.setAmount(roundUpToDong(amount));
            line.setNote(buildVoucherItemNote(item));
            evItems.add(line);
        }
        if (evItems.isEmpty())
            throw new BusinessException("Nhóm " + group.getSupplierName()
                    + " không có mặt hàng nào phát sinh tiền — hãy chọn Công nợ hoặc kiểm tra lại giá");
        ev.setItems(evItems);

        return expenseVoucherService.create(handler.getId(), ev);
    }

    private String buildVoucherItemNote(MaterialRequestItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("SL thực nhận: ")
          .append(item.getQtyReceived() == null ? "0" : item.getQtyReceived().stripTrailingZeros().toPlainString())
          .append(" ").append(item.getUnit());
        if (item.getSpecification() != null && !item.getSpecification().isBlank())
            sb.append(" · Quy cách: ").append(item.getSpecification());
        if (item.getUnitPrice() != null)
            sb.append(" · Đơn giá: ").append(item.getUnitPrice().stripTrailingZeros().toPlainString());
        if (item.getNote() != null && !item.getNote().isBlank())
            sb.append(" · ").append(item.getNote());
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Truy vấn
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<SupplyOrderDto> listForCreator(String username, String status, Long warehouseId,
                                               Long dateFrom, Long dateTo, String search,
                                               int page, int size) {
        User u = mustUser(username);
        return requestRepo.findSupplyByCreator(u.getId(), blankToNull(status), warehouseId,
                        dateFrom, dateTo, search, PageRequest.of(page, size))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<SupplyOrderDto> listForAccountant(String status, Long warehouseId,
                                                  Long dateFrom, Long dateTo, String search,
                                                  int page, int size) {
        return requestRepo.findSupplyByFilters(blankToNull(status), warehouseId,
                        dateFrom, dateTo, search, PageRequest.of(page, size))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public SupplyOrderDto getById(Long id) {
        return toDto(mustSupplyOrder(id));
    }

    /** NCC cho dropdown bước 1 — TẤT CẢ NCC đang hoạt động, không lọc theo loại. */
    @Transactional(readOnly = true)
    public List<SupplierOptionDto> listSuppliers(String q) {
        String kw = q == null ? "" : SupplyItem.normalize(q);
        return vendorRepo.findAll().stream()
                .filter(MaterialVendor::isActive)
                .filter(v -> kw.isEmpty() || SupplyItem.normalize(v.getName()).contains(kw))
                .sorted(Comparator.comparing(MaterialVendor::getName))
                .limit(50)
                .map(v -> SupplierOptionDto.builder()
                        .id(v.getId()).name(v.getName())
                        .contactPerson(v.getContactPerson()).contactPhone(v.getContactPhone())
                        .vendorType(v.getVendorType() != null ? v.getVendorType().name() : null)
                        .build())
                .toList();
    }

    /**
     * Danh mục khoản chi để chọn ở bước 1.
     *
     * <p><b>Lưu ý thiết kế:</b> trong codebase hiện tại {@code VendorExpenseCategory}
     * là POOL DÙNG CHUNG cho mọi NCC (cột {@code vendor_id} đã bị loại bỏ từ trước),
     * nên tham số {@code supplierId} chỉ mang tính ngữ cảnh cho FE, không dùng để lọc.
     */
    @Transactional(readOnly = true)
    public List<ExpenseCategoryOptionDto> listCategories(Long supplierId) {
        return categoryRepo.findByActiveTrueOrderByNameAsc().stream()
                .map(c -> ExpenseCategoryOptionDto.builder()
                        .id(c.getId()).name(c.getName())
                        .categoryKind(c.getCategoryKind() != null ? c.getCategoryKind().name() : "SERVICE")
                        .unit(c.getUnit()).specification(c.getSpecification())
                        .supplyItemId(c.getSupplyItemId())
                        .build())
                .toList();
    }

    /**
     * TẠO NHANH một nhãn khoản chi ngay từ form lập phiếu (không cần rời trang).
     *
     * <p>Trước đây chỉ OWNER tạo được nhãn, nên người lập phiếu gặp món chưa có
     * trong danh mục là phải dừng lại đi nhờ — phiếu bị treo giữa chừng. Ba role
     * lập phiếu giờ tự tạo được, dùng CHUNG một pool nhãn với OWNER.
     *
     * <p>Nhãn tạo ra vào thẳng pool dùng chung nên OWNER vẫn quản lý/ẩn được ở
     * trang Quản lý NCC như mọi nhãn khác; {@code createdByName} ghi lại ai tạo.
     *
     * @return option đã sẵn sàng để FE chọn luôn vào dòng đang nhập
     */
    @Transactional
    public ExpenseCategoryOptionDto createCategory(
            com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.CategoryUpsertRequest req,
            String username) {

        var created = supplierManagementService.createCategory(req, username);

        return ExpenseCategoryOptionDto.builder()
                .id(created.getId())
                .name(created.getName())
                .categoryKind(created.getCategoryKind() != null ? created.getCategoryKind() : "SERVICE")
                .unit(created.getUnit())
                .specification(created.getSpecification())
                .supplyItemId(created.getSupplyItemId())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Mapping
    // ══════════════════════════════════════════════════════════════════════════

    private SupplyOrderDto toDto(MaterialRequest mr) {
        String whName = mr.getSupplyWarehouseId() == null ? null
                : warehouseRepo.findById(mr.getSupplyWarehouseId())
                        .map(SupplyWarehouse::getName).orElse(null);

        Map<Long, String> supplierNameById = new HashMap<>();
        mr.getSupplyGroups().forEach(g -> supplierNameById.put(g.getSupplierId(), g.getSupplierName()));

        List<SupplyOrderItemDto> items = mr.getItems().stream()
                .sorted(Comparator.comparing(i -> i.getSortOrder() == null ? 0 : i.getSortOrder()))
                .map(i -> {
                    String kind = i.getSupplyItemId() != null ? "CONSUMABLE" : "SERVICE";
                    String supplierName = i.getSupplierId() != null
                            ? supplierNameById.computeIfAbsent(i.getSupplierId(),
                                    sid -> vendorRepo.findById(sid).map(MaterialVendor::getName).orElse(null))
                            : null;
                    return SupplyOrderItemDto.builder()
                            .id(i.getId())
                            .supplierId(i.getSupplierId()).supplierName(supplierName)
                            .expenseCategoryId(i.getExpenseCategoryId())
                            .categoryKind(kind)
                            .supplyItemId(i.getSupplyItemId())
                            .itemName(i.getMaterialName())
                            .unit(i.getUnit())
                            .specification(i.getSpecification())
                            .orderedQuantity(i.getQtyRequested())
                            .receivedQuantity(i.getQtyReceived())
                            .receiveClosed(i.isReceiveClosed())
                            .receiveStatus(i.getReceiveStatus() != null ? i.getReceiveStatus().name() : null)
                            .priceInputMode(i.getPriceInputMode() != null ? i.getPriceInputMode().name() : null)
                            .unitPrice(i.getUnitPrice())
                            .totalAmount(i.getTotalAmount())
                            .note(i.getNote())
                            .supplyGroupId(i.getSupplyGroupId())
                            .sortOrder(i.getSortOrder())
                            .build();
                })
                .toList();

        List<SupplyOrderGroupDto> groups = mr.getSupplyGroups().stream()
                .map(g -> {
                    ExpenseVoucher v = g.getPaymentVoucherId() == null ? null
                            : voucherRepo.findById(g.getPaymentVoucherId()).orElse(null);
                    return SupplyOrderGroupDto.builder()
                            .id(g.getId()).code(g.getCode())
                            .supplierId(g.getSupplierId()).supplierName(g.getSupplierName())
                            .status(g.getStatus().name())
                            .expectedDeliveryAt(g.getExpectedDeliveryAt())
                            .contactName(g.getContactName()).contactPhone(g.getContactPhone())
                            .paymentMode(g.getPaymentMode() != null ? g.getPaymentMode().name() : null)
                            .paymentVoucherId(g.getPaymentVoucherId())
                            .paymentVoucherCode(v != null ? v.getVoucherCode() : null)
                            .paymentVoucherStatus(v != null ? v.getStatus().name() : null)
                            .supplierDebtId(g.getSupplierDebtId())
                            .goodsAmount(g.getGoodsAmount()).feeAmount(g.getFeeAmount())
                            .totalAmount(g.getTotalAmount())
                            .fees(g.getTaxFees().stream()
                                    .map(f -> FeeDto.builder().id(f.getId())
                                            .label(f.getLabel()).amount(f.getAmount()).build())
                                    .toList())
                            .settledAt(g.getSettledAt())
                            .build();
                })
                .toList();

        List<SupplyReceiptDto> receipts = mr.getReceipts().stream()
                .sorted(Comparator.comparing(r -> r.getSequenceNo() == null ? 0 : r.getSequenceNo()))
                .map(r -> SupplyReceiptDto.builder()
                        .id(r.getId()).sequenceNo(r.getSequenceNo())
                        .receivedAt(r.getReceivedAt()).receivedByName(r.getReceivedByName())
                        .notes(r.isDraft() ? "[NHÁP] " + (r.getNotes() == null ? "" : r.getNotes()) : r.getNotes())
                        .items(r.getItems().stream().map(li -> SupplyReceiptLineDto.builder()
                                .itemId(li.getMaterialRequestItem().getId())
                                .itemName(li.getMaterialRequestItem().getMaterialName())
                                .unit(li.getMaterialRequestItem().getUnit())
                                .qty(li.getQty()).build()).toList())
                        .build())
                .toList();

        BigDecimal grand = groups.stream()
                .map(SupplyOrderGroupDto::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return SupplyOrderDto.builder()
                .id(mr.getId()).requestCode(mr.getRequestCode())
                .status(mr.getStatus().name())
                .createdById(mr.getCreatedBy() != null ? mr.getCreatedBy().getId() : null)
                .createdByName(mr.getCreatedByName())
                .supplyWarehouseId(mr.getSupplyWarehouseId()).supplyWarehouseName(whName)
                .requiredBy(mr.getRequiredBy())
                .orderedAt(mr.getOrderedAt()).receivedAt(mr.getReceivedAt())
                .completedAt(mr.getCompletedAt())
                .handledByName(mr.getHandledByName())
                .rejectReason(mr.getRejectReason())
                .grandTotal(grand)
                .items(items).groups(groups).receipts(receipts)
                .createdAt(mr.getCreatedAt()).updatedAt(mr.getUpdatedAt())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private MaterialRequest mustSupplyOrder(Long id) {
        MaterialRequest mr = requestRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu"));
        if (!mr.isSupplyOrder())   // null (dữ liệu cũ) ⇒ phiếu nguyên liệu
            throw new BusinessException("Phiếu này không phải phiếu đặt văn phòng phẩm");
        return mr;
    }

    private User mustUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
    }

    /** Chỉ NGƯỜI TẠO PHIẾU được nhập số thực nhận / sửa nháp. */
    private void assertCreator(MaterialRequest mr, User user) {
        if (mr.getCreatedBy() == null || !Objects.equals(mr.getCreatedBy().getId(), user.getId()))
            throw new BusinessException("Chỉ người tạo phiếu mới được thực hiện thao tác này");
    }

    private String displayName(User u) {
        return (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank() || "ALL".equalsIgnoreCase(s)) ? null : s;
    }

    private String payload(MaterialRequest mr) {
        return "{\"requestId\":" + mr.getId()
                + ",\"code\":\"" + mr.getRequestCode() + "\""
                + ",\"orderType\":\"SUPPLY\"}";
    }

    /** Mã phiếu VPP: SO-YYYYMMDD-XXXX (tách tiền tố khỏi MR- của phiếu nguyên liệu). */
    private String generateCode() {
        LocalDate today = LocalDate.now();
        String prefix = String.format("SO-%04d%02d%02d-", today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        long count = requestRepo.countByRequestCodeStartingWith(prefix);
        return prefix + String.format("%04d", count + 1);
    }
}
