package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.production.MaterialRequestDtos;
import com.nhatnam.server.dto.production.MaterialRequestDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.common.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MaterialRequestService {

    private final MaterialRequestRepository requestRepo;
    private final MaterialRequestItemRepository itemRepo;
    private final MaterialRequestVendorRepository vendorRepo;
    private final MaterialRequestReceiptRepository receiptRepo;
    private final FactoryMaterialStockRepository stockRepo;
    private final com.nhatnam.server.repository.FactoryMaterialRepository factoryMaterialRepo;
    private final com.nhatnam.server.repository.ProductionFactoryRepository productionFactoryRepo;
    private final MaterialVendorRepository materialVendorRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    // ── Dùng cho phiếu SELLER (nguyên liệu Ingredient) ──
    private final com.nhatnam.server.repository.IngredientRepository ingredientRepo;
    private final com.nhatnam.server.repository.WarehouseRepository warehouseRepo;
    private final com.nhatnam.server.repository.IngredientStockRepository ingredientStockRepo;
    private final com.nhatnam.server.repository.IngredientExpiryRepository ingredientExpiryRepo;
    private final com.nhatnam.server.repository.CategoryRepository categoryRepo;
    private final FactoryStockNoteRepository factoryStockNoteRepo;

    /**
     * Category nguyên liệu mà SUPER_SELLER được phép đặt. Hardcode tạm — có thể thêm.
     */
    private static final java.util.List<String> SELLER_INGREDIENT_CATEGORIES =
            java.util.List.of("Herbs , Spices & Condiments");

    // ── Factory Worker: tạo phiếu ────────────────────────────────────────────

    @Transactional
    public MaterialRequestDto create(CreateMaterialRequestRequest req, String username) {
        User creator = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        String code = generateCode();
        boolean isSeller = "SELLER".equalsIgnoreCase(req.getType());

        // Xưởng của phiếu (phiếu FACTORY). Nếu không truyền, để null (backfill sẽ gán Q9).
        com.nhatnam.server.entity.ProductionFactory factory =
                req.getProductionFactoryId() != null
                        ? productionFactoryRepo.findById(req.getProductionFactoryId()).orElse(null)
                        : null;

        MaterialRequest mr = MaterialRequest.builder()
                .requestCode(code)
                .createdBy(creator)
                .createdByName(creator.getFullName())
                .requiredBy(req.getRequiredBy())
                .status(MaterialRequest.RequestStatus.NEW)
                .type(isSeller ? MaterialRequest.RequestType.SELLER : MaterialRequest.RequestType.FACTORY)
                .productionFactory(factory)
                .build();

        if (req.getItems() != null) {
            int order = 0;
            for (ItemRequest item : req.getItems()) {
                Long ingredientId = item.getIngredientId();
                String materialName = item.getMaterialName();
                String unit = item.getUnit();
                if (isSeller) {
                    if (ingredientId == null)
                        throw new IllegalStateException("Phiếu SELLER phải chọn nguyên liệu cho từng dòng");
                    // Snapshot tên/đơn vị từ Ingredient để hiển thị đồng nhất với luồng gốc.
                    Ingredient ing = ingredientRepo.findById(ingredientId)
                            .orElseThrow(() -> new ResourceNotFoundException("Nguyên liệu không tồn tại: " + item.getIngredientId()));
                    materialName = ing.getName();
                    unit = ing.getUnit();
                }
                mr.getItems().add(MaterialRequestItem.builder()
                        .materialRequest(mr)
                        .materialName(materialName)
                        .unit(unit)
                        .qtyRequested(item.getQtyRequested())
                        .sortOrder(item.getSortOrder() > 0 ? item.getSortOrder() : order++)
                        .ingredientId(isSeller ? ingredientId : null)
                        .factoryMaterialId(!isSeller ? item.getFactoryMaterialId() : null)
                        .orderUnitType(item.getOrderUnitType())
                        .conversionRatio(resolveConversionRatio(item))
                        .build());
            }
        }

        MaterialRequest saved = requestRepo.save(mr);

        // WS: notify tất cả SUPER_ACCOUNTANT
        notificationService.sendToRole(
                Role.SUPER_ACCOUNTANT.name(),
                "MATERIAL_REQUEST_CREATED",
                creator.getFullName() + " tạo phiếu đặt hàng nguyên liệu " + code,
                "{\"requestId\":" + saved.getId() + ",\"code\":\"" + code + "\"}"
        );

        return toDto(saved, true);
    }

    // ── Super Accountant: xác nhận đặt hàng ─────────────────────────────────

    @Transactional
    public MaterialRequestDto confirmOrder(Long id, ConfirmOrderRequest req, String username) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.NEW) {
            throw new IllegalStateException("Phiếu không ở trạng thái Mới tạo");
        }

        User handler = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        mr.setStatus(MaterialRequest.RequestStatus.ORDERED);
        mr.setOrderedAt(System.currentTimeMillis());

        // Ngày giao dự kiến: FE gửi → dùng luôn. Nếu không gửi → tính từ min(supplierLeadDays)
        // của các nguyên liệu trong phiếu (lấy từ FactoryMaterial). Nếu phiếu có nhiều NL
        // của NCC khác nhau thì lấy số ngày nhỏ nhất.
        Long delivery = req.getEstimatedDelivery();
        if (delivery == null) {
            OptionalInt minDays = mr.getItems().stream()
                    .filter(i -> i.getFactoryMaterialId() != null)
                    .map(i -> factoryMaterialRepo.findById(i.getFactoryMaterialId()).orElse(null))
                    .filter(Objects::nonNull)
                    .filter(fm -> fm.getSupplierLeadDays() != null && fm.getSupplierLeadDays() > 0)
                    .mapToInt(FactoryMaterial::getSupplierLeadDays)
                    .min();
            if (minDays.isPresent()) {
                delivery = System.currentTimeMillis() + (long) minDays.getAsInt() * 24L * 60L * 60L * 1000L;
            }
        }
        mr.setEstimatedDelivery(delivery);

        mr.setHandledBy(handler);
        mr.setHandledByName(handler.getFullName());

        // Thêm NCC — bắt buộc chọn từ danh mục NCC có sẵn (không cho nhập tự do)
        if (req.getVendors() == null || req.getVendors().isEmpty()) {
            throw new IllegalStateException("Vui lòng chọn ít nhất 1 nhà cung cấp từ danh mục");
        }
        mr.getVendors().clear();
        int order = 0;
        for (VendorRequest vr : req.getVendors()) {
            if (vr.getVendorId() == null) {
                throw new IllegalStateException("Vui lòng chọn nhà cung cấp từ danh mục, không nhập tên tự do");
            }
            MaterialVendor vendor = materialVendorRepo.findById(vr.getVendorId())
                    .orElseThrow(() -> new IllegalStateException("Nhà cung cấp không tồn tại trong danh mục: " + vr.getVendorId()));
            mr.getVendors().add(MaterialRequestVendor.builder()
                    .materialRequest(mr)
                    .vendor(vendor)
                    .vendorName(vendor.getName())
                    .contactPerson(vendor.getContactPerson())
                    .contactPhone(vendor.getContactPhone())
                    .sortOrder(vr.getSortOrder() > 0 ? vr.getSortOrder() : order++)
                    .build());
        }

        // Gán NCC cho từng dòng nguyên liệu — bắt buộc, mỗi nguyên liệu phải được gán
        // đúng 1 NCC trong danh sách vừa thêm ở trên (tham chiếu theo vị trí/index vì
        // các MaterialRequestVendor vừa tạo chưa có id thật cho tới khi save()).
        List<MaterialRequestVendor> vendorsInOrder = mr.getVendors();
        Map<Long, ItemVendorAssignment> assignmentByItemId = (req.getItems() == null) ? Map.of()
                : req.getItems().stream().collect(Collectors.toMap(ItemVendorAssignment::getItemId, a -> a));

        for (MaterialRequestItem item : mr.getItems()) {
            ItemVendorAssignment a = assignmentByItemId.get(item.getId());
            if (a == null || a.getVendorIndex() == null) {
                throw new IllegalStateException("Vui lòng gán nhà cung cấp cho nguyên liệu: " + item.getMaterialName());
            }
            int idx = a.getVendorIndex();
            if (idx < 0 || idx >= vendorsInOrder.size()) {
                throw new IllegalStateException("Nhà cung cấp gán cho nguyên liệu \"" + item.getMaterialName() + "\" không hợp lệ");
            }
            item.setSuppliedByVendor(vendorsInOrder.get(idx));
        }

        MaterialRequest saved = requestRepo.save(mr);

        String deliveryStr = delivery != null
                ? new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(delivery))
                : "chưa xác định";
        String wsPayload = "{\"requestId\":" + id + ",\"code\":\"" + mr.getRequestCode() + "\"}";

        // WS: notify người tạo phiếu
        notificationService.sendToUser(
                mr.getCreatedBy(),
                "MATERIAL_REQUEST_ORDERED",
                "Phiếu " + mr.getRequestCode() + " đã được đặt hàng. Dự kiến giao: " + deliveryStr,
                wsPayload
        );
        // WS: notify NV xưởng đúng xưởng đặt hàng (thay vì tất cả factory workers)
        notifyFactoryWorkers(mr, "MATERIAL_REQUEST_ORDERED",
                "Phiếu " + mr.getRequestCode() + " đã được đặt hàng. Dự kiến giao: " + deliveryStr,
                wsPayload);

        return toDto(saved, true);
    }

    // ── SUPER_ACCOUNTANT: gia hạn ngày giao hàng ────────────────────────────

    @Transactional
    public MaterialRequestDto extendDelivery(Long id, DeliveryExtendRequest req) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.ORDERED) {
            throw new IllegalStateException("Chỉ có thể gia hạn phiếu đang ở trạng thái Đã đặt hàng");
        }
        if (req.getNewDeliveryDate() == null) {
            throw new IllegalStateException("Vui lòng chọn ngày giao hàng mới");
        }
        mr.setDeliveryExtendedTo(req.getNewDeliveryDate());
        mr.setDeliveryExtendReason(req.getReason());
        MaterialRequest saved = requestRepo.save(mr);

        String newDateStr = new java.text.SimpleDateFormat("dd/MM/yyyy")
                .format(new Date(req.getNewDeliveryDate()));
        String wsPayload = "{\"requestId\":" + id + ",\"code\":\"" + mr.getRequestCode() + "\"}";

        // WS: notify người tạo phiếu
        notificationService.sendToUser(
                mr.getCreatedBy(),
                "MATERIAL_REQUEST_DELIVERY_EXTENDED",
                "Phiếu " + mr.getRequestCode() + " được gia hạn giao hàng đến " + newDateStr
                        + (req.getReason() != null ? " — Lý do: " + req.getReason() : ""),
                wsPayload
        );
        // WS: notify NV xưởng đúng xưởng
        notifyFactoryWorkers(mr, "MATERIAL_REQUEST_DELIVERY_EXTENDED",
                "Phiếu " + mr.getRequestCode() + " được gia hạn giao hàng đến " + newDateStr,
                wsPayload);

        return toDto(saved, true);
    }

    // ── Factory Worker: NHẬN HÀNG NHIỀU ĐỢT ─────────────────────────────────
    //
    // Luồng mới (thay cho confirmReceive 1-phát-ăn-ngay):
    //   1. saveReceipt(...)      — lưu MỘT đợt nhận. Gọi được nhiều lần.
    //                              Cộng tồn kho NGAY, status → PARTIALLY_RECEIVED.
    //   2. finishReceiving(...)  — chốt "đã giao xong", status → RECEIVED, khoá phiếu.
    //                              Kế toán chỉ complete() được sau bước này.
    //
    // Vì sao tách? NCC giao lẻ (mỗi NCC một lúc) và giao thiếu → giao bù. Nếu chỉ có
    // 1 nút "Xác nhận" thì NV xưởng buộc phải chờ đủ hàng mới nhập → tồn kho trễ,
    // và không có cách nào ghi nhận đợt giao thứ hai.

    /**
     * Lưu MỘT đợt nhận hàng. Chỉ nhận các dòng thực giao trong đợt (qty > 0);
     * dòng không giao đợt này thì FE không gửi lên.
     */
    @Transactional
    public MaterialRequestDto saveReceipt(Long id, SaveReceiptRequest req, String username) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.ORDERED
                && mr.getStatus() != MaterialRequest.RequestStatus.PARTIALLY_RECEIVED) {
            throw new IllegalStateException(
                    "Chỉ nhận hàng được khi phiếu ở trạng thái Đã đặt hàng hoặc Đang nhận hàng");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new IllegalStateException("Đợt nhận phải có ít nhất 1 nguyên liệu");
        }

        User receiver = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        boolean isSeller = mr.getType() == MaterialRequest.RequestType.SELLER;
        long now = System.currentTimeMillis();
        long fiveYearsMs = now + 5L * 365L * 24L * 60L * 60L * 1000L;

        Map<Long, MaterialRequestItem> itemById = mr.getItems().stream()
                .collect(Collectors.toMap(MaterialRequestItem::getId, it -> it));

        int seq = receiptRepo.countByMaterialRequest_Id(mr.getId()) + 1;
        MaterialRequestReceipt receipt = MaterialRequestReceipt.builder()
                .materialRequest(mr)
                .sequenceNo(seq)
                .receivedAt(now)
                .receivedBy(receiver)
                .receivedByName(receiver.getFullName())
                .notes(req.getNotes())
                .build();

        Set<Long> seenItemIds = new HashSet<>();

        for (ReceiptItemRequest ri : req.getItems()) {
            if (ri.getItemId() == null) continue;
            if (ri.getQty() == null || ri.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            MaterialRequestItem item = itemById.get(ri.getItemId());
            if (item == null) {
                throw new IllegalStateException("Nguyên liệu không thuộc phiếu này (id=" + ri.getItemId() + ")");
            }
            if (!seenItemIds.add(item.getId())) {
                throw new IllegalStateException(
                        "Nguyên liệu \"" + item.getMaterialName() + "\" bị gửi trùng 2 lần trong cùng đợt");
            }
            if (item.getReceiveStatus() == MaterialRequestItem.ReceiveStatus.CLOSED_SHORT) {
                throw new IllegalStateException(
                        "Nguyên liệu \"" + item.getMaterialName() + "\" đã chốt thiếu — không thể nhận thêm");
            }

            // ── KHOÁ ĐƠN VỊ NHẬN theo đợt đầu tiên ───────────────────────────
            // qtyReceived là tổng CỘNG DỒN. Nếu đợt 1 nhận theo Kg còn đợt 2 nhận theo
            // Thùng thì "50 + 2 = 52" là con số vô nghĩa → giá vốn và tồn kho đều sai.
            String reqUnitType = ri.getReceivedUnitType() == null ? "STORAGE"
                    : ri.getReceivedUnitType().toUpperCase();
            if (!"STORAGE".equals(reqUnitType) && !"ORDER".equals(reqUnitType)) {
                throw new IllegalStateException("Loại đơn vị nhận không hợp lệ: " + reqUnitType);
            }
            String lockedUnitType = item.getReceivedUnitType();
            if (lockedUnitType != null && !lockedUnitType.equalsIgnoreCase(reqUnitType)) {
                throw new IllegalStateException(
                        "Nguyên liệu \"" + item.getMaterialName() + "\" đã nhận theo đơn vị "
                                + ("ORDER".equals(lockedUnitType) ? "đặt hàng" : "lưu kho")
                                + " ở đợt trước — các đợt bù phải dùng cùng đơn vị.");
            }
            item.setReceivedUnitType(reqUnitType);

            FactoryMaterial fm = item.getFactoryMaterialId() != null
                    ? factoryMaterialRepo.findById(item.getFactoryMaterialId()).orElse(null) : null;

            // Quy đổi ra đvt lưu kho khi nhận theo đvt đặt hàng (VD 2 thùng × 5kg = 10kg)
            BigDecimal ratio = item.getConversionRatio();
            BigDecimal stockQty = ri.getQty();
            String receivedUnit = item.getUnit();
            if ("ORDER".equals(reqUnitType) && ratio != null && ratio.compareTo(BigDecimal.ZERO) > 0) {
                stockQty = ri.getQty().multiply(ratio);
                receivedUnit = fm != null ? fm.getOrderUnit() : null;
                item.setReceivedUnit(receivedUnit);
            }

            // HSD của RIÊNG đợt này (đợt 2 giao sau → hạn khác đợt 1 là bình thường)
            Long expiryMs = ri.getExpiryDate();
            if (expiryMs == null && fm != null && fm.getShelfLifeDays() != null && fm.getShelfLifeDays() > 0) {
                expiryMs = now + (long) fm.getShelfLifeDays() * 24L * 60L * 60L * 1000L;
            }
            if (expiryMs == null && isSeller) expiryMs = fiveYearsMs;

            MaterialRequestReceiptItem line = MaterialRequestReceiptItem.builder()
                    .receipt(receipt)
                    .materialRequestItem(item)
                    .qty(ri.getQty())
                    .receivedUnitType(reqUnitType)
                    .receivedUnit(receivedUnit)
                    .conversionRatio(ratio)
                    .stockQty(stockQty)
                    .expiryDate(expiryMs)
                    .weighingLogs(serializeWeighingLogs(ri.getWeighingLogs()))
                    .build();

            if (isSeller) {
                // SELLER: chưa nhập kho (chờ có giá vốn ở bước hoàn thành), chỉ ghi nhận
                // kho đích + HSD của đợt.
                if (ri.getWarehouseId() == null) {
                    throw new IllegalStateException("Phiếu SELLER phải chọn kho nhận cho từng dòng");
                }
                line.setWarehouseId(ri.getWarehouseId());
                item.setWarehouseId(ri.getWarehouseId());   // giữ tương thích dữ liệu cũ
            } else {
                // FACTORY: MỖI ĐỢT = MỘT LÔ RIÊNG trong kho xưởng.
                // Đúng bản chất FIFO + mỗi đợt có HSD riêng. applyUnitCostToLots() ở bước
                // hoàn thành đã đọc List<lot> theo item nên nhiều lô vẫn tính giá vốn đúng.
                FactoryMaterialStock lot = stockRepo.save(FactoryMaterialStock.builder()
                        .materialName(item.getMaterialName())
                        .unit(item.getUnit())
                        .quantity(stockQty)
                        .initialQuantity(stockQty)
                        .expiryDate(expiryMs)
                        .materialRequest(mr)
                        .materialRequestItem(item)
                        .productionFactory(mr.getProductionFactory())
                        .materialRequestCode(mr.getRequestCode())
                        .orderedAt(mr.getOrderedAt())
                        .build());
                line.setFactoryMaterialStock(lot);
            }

            item.setExpiryDate(expiryMs);   // HSD gần nhất (snapshot cấp dòng, cho báo cáo cũ)
            receipt.getItems().add(line);
        }

        if (receipt.getItems().isEmpty()) {
            throw new IllegalStateException("Đợt nhận không có nguyên liệu nào có số lượng > 0");
        }

        mr.getReceipts().add(receipt);
        recomputeReceiveProgress(mr);
        mr.setStatus(MaterialRequest.RequestStatus.PARTIALLY_RECEIVED);

        MaterialRequest saved = requestRepo.save(mr);

        // ── Ghi phiếu nhập kho (FactoryStockNote IMPORT) cho kho nguyên liệu xưởng ──
        if (!isSeller && mr.getProductionFactory() != null) {
            FactoryStockNote importNote = FactoryStockNote.builder()
                    .type(FactoryStockNote.NoteType.IMPORT)
                    .factory(mr.getProductionFactory())
                    .factoryName(mr.getProductionFactory().getName())
                    .reason("Nhận hàng đợt " + seq + " — Phiếu " + mr.getRequestCode())
                    .createdBy(receiver)
                    .createdByName(receiver.getFullName())
                    .createdAt(now)
                    .lines(new ArrayList<>())
                    .documentImages("[]")
                    .totalCostValue(BigDecimal.ZERO)
                    .build();
            // Generate note code: FIM-yyyyMMdd-XXXX
            String day = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                    .format(Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Ho_Chi_Minh")));
            long noteSeq = factoryStockNoteRepo.countByNoteCodeStartingWith("FIM-" + day) + 1;
            importNote.setNoteCode(String.format("FIM-%s-%04d", day, noteSeq));

            for (MaterialRequestReceiptItem ri2 : receipt.getItems()) {
                MaterialRequestItem mri = ri2.getMaterialRequestItem();
                importNote.getLines().add(FactoryStockNoteLine.builder()
                        .note(importNote)
                        .materialName(mri.getMaterialName())
                        .unit(mri.getUnit())
                        .quantity(ri2.getStockQty())
                        .unitCost(BigDecimal.ZERO)
                        .expiryDate(ri2.getExpiryDate())
                        .build());
            }
            factoryStockNoteRepo.save(importNote);
        }

        // KHÔNG báo cho SUPER_ACCOUNTANT ở đây. Việc nhận lẻ/giao bù là chuyện nội bộ
        // của xưởng; kế toán chỉ vào cuộc khi phiếu đã được chốt "đã giao xong"
        // (xem finishReceiving) — lúc đó complete() mới mở khoá.

        return toDto(saved, true);
    }

    /**
     * Chốt "đã giao xong" — bước CUỐI của nhân viên xưởng. Sau bước này phiếu bị khoá,
     * không nhận thêm đợt, và kế toán mới được Hoàn thành.
     *
     * <p>Cho phép chốt khi vẫn còn THIẾU (NCC không giao bù nữa) nhưng BẮT BUỘC nhập lý do.
     * Các dòng còn thiếu được đánh dấu {@code CLOSED_SHORT}. Giao DƯ thì cho qua bình thường.
     */
    @Transactional
    public MaterialRequestDto finishReceiving(Long id, FinishReceivingRequest req, String username) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.PARTIALLY_RECEIVED) {
            throw new IllegalStateException(
                    "Phiếu chưa có đợt nhận nào — vui lòng lưu ít nhất 1 đợt trước khi xác nhận đã giao xong");
        }

        recomputeReceiveProgress(mr);

        List<String> shortItems = mr.getItems().stream()
                .filter(it -> it.getReceiveStatus() == MaterialRequestItem.ReceiveStatus.PENDING
                        || it.getReceiveStatus() == MaterialRequestItem.ReceiveStatus.PARTIAL)
                .map(MaterialRequestItem::getMaterialName)
                .collect(Collectors.toList());

        String reason = req == null ? null : req.getShortageReason();
        if (!shortItems.isEmpty() && (reason == null || reason.isBlank())) {
            throw new IllegalStateException(
                    "Còn thiếu: " + String.join(", ", shortItems) + " — vui lòng nhập lý do nhận thiếu");
        }

        // Chốt các dòng còn thiếu → CLOSED_SHORT (không cho nhận bù nữa)
        for (MaterialRequestItem it : mr.getItems()) {
            if (it.getReceiveStatus() == MaterialRequestItem.ReceiveStatus.PENDING
                    || it.getReceiveStatus() == MaterialRequestItem.ReceiveStatus.PARTIAL) {
                it.setReceiveStatus(MaterialRequestItem.ReceiveStatus.CLOSED_SHORT);
            }
        }

        mr.setShortageReason(shortItems.isEmpty() ? null : reason.trim());
        mr.setStatus(MaterialRequest.RequestStatus.RECEIVED);
        mr.setReceivedAt(System.currentTimeMillis());

        // Gộp ghi chú của các đợt thành ghi chú phiếu (giữ field cũ có nghĩa)
        String merged = mr.getReceipts().stream()
                .filter(r -> r.getNotes() != null && !r.getNotes().isBlank())
                .map(r -> "Đợt " + r.getSequenceNo() + ": " + r.getNotes().trim())
                .collect(Collectors.joining(" | "));
        mr.setReceiveNotes(merged.isBlank() ? null : merged);

        MaterialRequest saved = requestRepo.save(mr);

        String msg = "Phiếu " + mr.getRequestCode() + " đã nhận hàng xong ("
                + mr.getReceipts().size() + " đợt)"
                + (shortItems.isEmpty() ? "" : " — NHẬN THIẾU: " + String.join(", ", shortItems));

        notificationService.sendToRole(
                Role.SUPER_ACCOUNTANT.name(),
                "MATERIAL_REQUEST_RECEIVED",
                msg,
                "{\"requestId\":" + id + ",\"code\":\"" + mr.getRequestCode() + "\"}"
        );

        return toDto(saved, true);
    }

    /**
     * [LEGACY] Nhận hàng một lần duy nhất — giữ để payload/app cũ không vỡ.
     * Bên trong = lưu 1 đợt + chốt luôn.
     */
    @Transactional
    public MaterialRequestDto confirmReceive(Long id, ReceiveRequest req, String username) {
        SaveReceiptRequest sr = new SaveReceiptRequest();
        sr.setNotes(req.getNotes());
        sr.setItems((req.getItems() == null ? List.<ReceiveItemRequest>of() : req.getItems()).stream()
                .filter(i -> i.getQtyReceived() != null && i.getQtyReceived().compareTo(BigDecimal.ZERO) > 0)
                .map(i -> new ReceiptItemRequest(
                        i.getItemId(), i.getQtyReceived(), i.getExpiryDate(), i.getWarehouseId(),
                        i.getReceivedUnitType(), i.getWeighingLogs()))
                .collect(Collectors.toList()));

        saveReceipt(id, sr, username);

        FinishReceivingRequest fr = new FinishReceivingRequest();
        fr.setShortageReason("Nhận hàng một lần (luồng cũ)");
        return finishReceiving(id, fr, username);
    }

    /**
     * Tính lại tổng thực nhận cộng dồn + tiến độ của từng dòng, từ TOÀN BỘ các đợt.
     * Gọi sau mỗi lần lưu đợt — {@code qtyReceived} luôn là số dẫn xuất, không bao giờ
     * được set trực tiếp từ payload.
     */
    private void recomputeReceiveProgress(MaterialRequest mr) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (MaterialRequestReceipt r : mr.getReceipts()) {
            for (MaterialRequestReceiptItem li : r.getItems()) {
                totals.merge(li.getMaterialRequestItem().getId(), li.getQty(), BigDecimal::add);
            }
        }
        for (MaterialRequestItem item : mr.getItems()) {
            BigDecimal total = totals.get(item.getId());
            if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
                item.setQtyReceived(null);
                if (item.getReceiveStatus() != MaterialRequestItem.ReceiveStatus.CLOSED_SHORT) {
                    item.setReceiveStatus(MaterialRequestItem.ReceiveStatus.PENDING);
                }
                continue;
            }
            item.setQtyReceived(total);
            // Giao DƯ được phép (chỉ cảnh báo ở FE) → >= số đặt đều tính là đủ.
            boolean full = total.compareTo(nzQty(item.getQtyRequested())) >= 0;
            item.setReceiveStatus(full
                    ? MaterialRequestItem.ReceiveStatus.FULFILLED
                    : MaterialRequestItem.ReceiveStatus.PARTIAL);
        }
    }

    private BigDecimal nzQty(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    // ── Super Accountant: hoàn thành phiếu (nhập breakdown giá/phí + thanh toán/công nợ) ──

    @Transactional
    public MaterialRequestDto complete(Long id, CompleteRequest req, String username) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.RECEIVED) {
            throw new IllegalStateException("Phiếu chưa được xác nhận nhận hàng");
        }

        Map<Long, MaterialRequestVendor> vendorById = mr.getVendors().stream()
                .collect(Collectors.toMap(MaterialRequestVendor::getId, v -> v));

        List<MaterialRequestItem> receivedItems = mr.getItems().stream()
                .filter(it -> it.getQtyReceived() != null && it.getQtyReceived().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        if (receivedItems.isEmpty()) {
            throw new IllegalStateException("Không có nguyên liệu nào có số lượng thực nhận");
        }
        Map<Long, MaterialRequestItem> receivedItemById = receivedItems.stream()
                .collect(Collectors.toMap(MaterialRequestItem::getId, it -> it));

        // 1) Gán NCC cho từng dòng nguyên liệu (nếu không gửi lại, giữ NCC đã gán lúc Xác nhận đặt hàng)
        Map<Long, Long> requestVendorIdByItemId = new HashMap<>();
        if (req.getItemVendors() != null) {
            for (CompleteItemVendorAssignment a : req.getItemVendors()) {
                if (a.getItemId() != null && a.getRequestVendorId() != null) {
                    requestVendorIdByItemId.put(a.getItemId(), a.getRequestVendorId());
                }
            }
        }
        for (MaterialRequestItem item : receivedItems) {
            Long rvId = requestVendorIdByItemId.get(item.getId());
            MaterialRequestVendor rv = rvId != null ? vendorById.get(rvId) : item.getSuppliedByVendor();
            if (rv == null) {
                throw new IllegalStateException("Vui lòng chọn nhà cung cấp cho nguyên liệu: " + item.getMaterialName());
            }
            item.setSuppliedByVendor(rv);
        }

        // 2) Validate + tính breakdown giá/phí
        List<CostEntryRequest> costEntries = req.getCostEntries() == null ? List.of() : req.getCostEntries();
        if (costEntries.isEmpty()) {
            throw new IllegalStateException("Vui lòng nhập giá nguyên liệu cho các dòng đã nhận hàng");
        }

        // Mỗi item đã nhận phải có đúng 1 dòng MATERIAL, scope = đúng item đó
        // materialAmountByItemId = GIÁ TRỊ dòng (đơn giá × SL thực nhận) — chưa gồm thuế/phí
        Map<Long, BigDecimal> materialAmountByItemId = new HashMap<>();
        Map<Long, BigDecimal> unitPriceByItemId = new HashMap<>();
        List<CostEntryRequest> customEntries = new ArrayList<>();

        for (CostEntryRequest ce : costEntries) {
            List<Long> itemIds = ce.getItemIds() == null ? List.of() : ce.getItemIds();
            if (itemIds.isEmpty()) {
                throw new IllegalStateException("Dòng giá/phí \"" + ce.getLabel() + "\" chưa chọn nguyên liệu áp dụng");
            }
            for (Long itId : itemIds) {
                if (!receivedItemById.containsKey(itId)) {
                    throw new IllegalStateException("Dòng giá/phí áp dụng cho nguyên liệu không hợp lệ hoặc chưa có số lượng thực nhận (id=" + itId + ")");
                }
            }

            if ("MATERIAL".equalsIgnoreCase(ce.getType())) {
                if (itemIds.size() != 1) {
                    throw new IllegalStateException("Dòng \"Giá nguyên liệu\" chỉ được áp dụng cho đúng 1 nguyên liệu");
                }
                Long itId = itemIds.get(0);
                if (materialAmountByItemId.containsKey(itId)) {
                    throw new IllegalStateException("Mỗi nguyên liệu chỉ được có đúng 1 dòng \"Giá nguyên liệu\": "
                            + receivedItemById.get(itId).getMaterialName());
                }
                MaterialRequestItem it = receivedItemById.get(itId);
                BigDecimal qty = it.getQtyReceived();

                // 2 CÁCH NHẬP GIÁ:
                //  - unitPrice (mặc định): amount = unitPrice × qty
                //  - amount (tổng tiền của mặt hàng): unitPrice = amount / qty (làm tròn 3 số thập phân)
                BigDecimal unitPrice;
                BigDecimal amount;
                if (ce.getUnitPrice() != null) {
                    unitPrice = com.nhatnam.server.utils.CostAllocation.normalizeMoney(ce.getUnitPrice());
                    amount = unitPrice.multiply(qty);
                } else if (ce.getAmount() != null) {
                    amount = com.nhatnam.server.utils.CostAllocation.normalizeMoney(ce.getAmount());
                    unitPrice = com.nhatnam.server.utils.CostAllocation.unitPriceFromTotal(amount, qty);
                } else {
                    throw new IllegalStateException("Thiếu giá cho nguyên liệu: " + it.getMaterialName());
                }
                if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("Giá nguyên liệu phải lớn hơn 0 cho: " + it.getMaterialName());
                }
                materialAmountByItemId.put(itId, amount);
                unitPriceByItemId.put(itId, unitPrice);

            } else if ("CUSTOM".equalsIgnoreCase(ce.getType())) {
                if (ce.getLabel() == null || ce.getLabel().isBlank()) {
                    throw new IllegalStateException("Vui lòng đặt tên cho dòng giá/phí tuỳ chỉnh");
                }
                if (ce.getAmount() == null || ce.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalStateException("Số tiền không hợp lệ cho dòng giá/phí: " + ce.getLabel());
                }
                customEntries.add(ce);
            } else {
                throw new IllegalStateException("Loại dòng giá/phí không hợp lệ: " + ce.getType());
            }
        }

        // Mọi item đã nhận hàng đều phải có giá nguyên liệu
        List<String> missingMaterial = receivedItems.stream()
                .filter(it -> !materialAmountByItemId.containsKey(it.getId()))
                .map(MaterialRequestItem::getMaterialName)
                .collect(Collectors.toList());
        if (!missingMaterial.isEmpty()) {
            throw new IllegalStateException("Thiếu giá nguyên liệu cho: " + String.join(", ", missingMaterial));
        }

        // 3) PHÂN BỔ THUẾ/PHÍ theo TỶ TRỌNG GIÁ TRỊ nguyên liệu trong phạm vi áp dụng.
        //    Dùng chung thuật toán với phiếu nhập kho (CostAllocation) — mọi bước trung gian
        //    giữ nguyên phần thập phân, CHỈ làm tròn tới hàng ĐƠN VỊ ĐỒNG ở bước cuối (giá vốn).
        List<com.nhatnam.server.utils.CostAllocation.Line> allocLines = receivedItems.stream()
                .map(it -> new com.nhatnam.server.utils.CostAllocation.Line(
                        it.getId(), it.getQtyReceived(), unitPriceByItemId.get(it.getId())))
                .collect(Collectors.toList());
        List<com.nhatnam.server.utils.CostAllocation.Fee> allocFees = customEntries.stream()
                .map(ce -> new com.nhatnam.server.utils.CostAllocation.Fee(
                        ce.getLabel().trim(),
                        com.nhatnam.server.utils.CostAllocation.normalizeMoney(ce.getAmount()),
                        ce.getItemIds()))
                .collect(Collectors.toList());
        Map<Long, com.nhatnam.server.utils.CostAllocation.Result> alloc =
                com.nhatnam.server.utils.CostAllocation.allocate(allocLines, allocFees);

        // 4) Gán unitPrice (GIÁ VỐN/đơn vị, đã tròn đồng) + lineAmount (tiền thật phải trả NCC)
        for (MaterialRequestItem item : receivedItems) {
            com.nhatnam.server.utils.CostAllocation.Result r = alloc.get(item.getId());

            // Tiền thật của dòng = giá trị nguyên liệu + thuế/phí được phân bổ (giữ 3 số thập phân)
            BigDecimal lineAmount = materialAmountByItemId.get(item.getId())
                    .add(r.feeShare())
                    .setScale(com.nhatnam.server.utils.CostAllocation.MONEY_SCALE, java.math.RoundingMode.HALF_UP);

            item.setLineAmount(lineAmount);
            item.setUnitPrice(r.unitCost());   // ← giá vốn/đơn vị, làm tròn tới ĐỒNG

            // 4b) GIÁ VỐN THEO LÔ (kho xưởng). Chia theo TỔNG SL đã nhập kho (đơn vị lưu kho)
            //     để đúng cả khi nhận theo đơn vị đặt hàng (VD: 10 thùng × 5kg → 50kg).
            Long rvId = requestVendorIdByItemId.get(item.getId());
            MaterialRequestVendor itemVendor = rvId != null ? vendorById.get(rvId) : null;
            // Lấy 2 field NCC (Mục 7) TRỰC TIẾP từ request — vì vendor entity chỉ được set
            // các field này ở bước (7) phía dưới, chạy SAU đoạn này. Đọc từ paymentMap để
            // snapshot đúng ngay từ đầu.
            String impInfo = null, serial = null;
            if (rvId != null && req.getVendorPayments() != null) {
                for (VendorPaymentRequest vp : req.getVendorPayments()) {
                    if (rvId.equals(vp.getRequestVendorId())) {
                        impInfo = vp.getImportReceiptInfo() != null ? vp.getImportReceiptInfo().trim() : null;
                        serial  = vp.getSerialImei() != null ? vp.getSerialImei().trim() : null;
                        break;
                    }
                }
            }
            applyUnitCostToLots(item, lineAmount, itemVendor, impInfo, serial);
        }

        // 5) Lưu lại breakdown giá/phí đã nhập (để hiển thị/kiểm tra lại sau này)
        mr.getCostEntries().clear();
        int sortOrder = 0;
        for (Map.Entry<Long, BigDecimal> e : materialAmountByItemId.entrySet()) {
            mr.getCostEntries().add(MaterialRequestCostEntry.builder()
                    .materialRequest(mr)
                    .type(MaterialRequestCostEntry.EntryType.MATERIAL)
                    .label("Giá nguyên liệu")
                    .amount(e.getValue())
                    .appliesToItemIds(serializeIdList(List.of(e.getKey())))
                    .sortOrder(sortOrder++)
                    .build());
        }
        for (CostEntryRequest ce : customEntries) {
            mr.getCostEntries().add(MaterialRequestCostEntry.builder()
                    .materialRequest(mr)
                    .type(MaterialRequestCostEntry.EntryType.CUSTOM)
                    .label(ce.getLabel())
                    .amount(ce.getAmount())
                    .appliesToItemIds(serializeIdList(ce.getItemIds()))
                    .sortOrder(sortOrder++)
                    .build());
        }

        // 6) Tính tổng tiền theo từng NCC (gộp các item đã gán)
        Map<Long, BigDecimal> totalByVendor = new HashMap<>();
        for (MaterialRequestItem item : receivedItems) {
            totalByVendor.merge(item.getSuppliedByVendor().getId(), item.getLineAmount(), BigDecimal::add);
        }

        // 7) Xử lý quyết định Thanh toán / Công nợ cho từng NCC có phát sinh tiền
        Map<Long, VendorPaymentRequest> paymentMap = req.getVendorPayments() == null ? Map.of()
                : req.getVendorPayments().stream().collect(Collectors.toMap(VendorPaymentRequest::getRequestVendorId, v -> v));

        long now = System.currentTimeMillis();
        for (Map.Entry<Long, BigDecimal> entry : totalByVendor.entrySet()) {
            MaterialRequestVendor rv = vendorById.get(entry.getKey());
            BigDecimal total = entry.getValue();
            rv.setTotalAmount(total);

            VendorPaymentRequest pay = paymentMap.get(entry.getKey());
            if (pay == null || pay.getAction() == null) {
                throw new IllegalStateException("Vui lòng chọn Thanh toán hoặc Công nợ cho NCC: " + rv.getVendorName());
            }

            // Mục 7: 2 field bắt buộc theo từng NCC
            if (pay.getImportReceiptInfo() == null || pay.getImportReceiptInfo().isBlank()) {
                throw new IllegalStateException("Vui lòng nhập Thông tin phiếu nhập cho NCC: " + rv.getVendorName());
            }
            if (pay.getSerialImei() == null || pay.getSerialImei().isBlank()) {
                throw new IllegalStateException("Vui lòng nhập Serial/IMEI cho NCC: " + rv.getVendorName());
            }
            rv.setImportReceiptInfo(pay.getImportReceiptInfo().trim());
            rv.setSerialImei(pay.getSerialImei().trim());

            if ("PAID".equalsIgnoreCase(pay.getAction())) {
                if (pay.getProofImages() == null || pay.getProofImages().isEmpty()) {
                    throw new IllegalStateException("Bắt buộc chứng từ thanh toán cho NCC: " + rv.getVendorName());
                }
                if (pay.getPaymentMethod() == null || pay.getPaymentMethod().isBlank()) {
                    throw new IllegalStateException("Vui lòng chọn hình thức thanh toán cho NCC: " + rv.getVendorName());
                }
                rv.setPaymentStatus(MaterialRequestVendor.VendorPaymentStatus.PAID);
                rv.setPaidAmount(total);
                rv.setDebtSettlementStatus(MaterialRequestVendor.DebtSettlementStatus.SETTLED);
                rv.setPaymentMethod(pay.getPaymentMethod());
                rv.setPaymentInfo(pay.getPaymentInfo());
                rv.setPaymentProofImages(serializeStringList(pay.getProofImages()));
            } else if ("DEBT".equalsIgnoreCase(pay.getAction())) {
                if (rv.getVendor() == null) {
                    throw new IllegalStateException(
                            "NCC \"" + rv.getVendorName() + "\" chưa được liên kết với danh mục Nhà cung cấp — " +
                                    "không thể ghi công nợ. Vui lòng chọn NCC có sẵn trong danh mục.");
                }
                rv.setPaymentStatus(MaterialRequestVendor.VendorPaymentStatus.DEBT);
                rv.setPaidAmount(BigDecimal.ZERO);
                rv.setDebtSettlementStatus(MaterialRequestVendor.DebtSettlementStatus.NONE);
                rv.setDebtSince(now);
            } else {
                throw new IllegalStateException("Giá trị action không hợp lệ: " + pay.getAction());
            }
        }

        BigDecimal grandTotal = totalByVendor.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // SELLER: đến bước hoàn thành (đã có giá vốn) mới thực sự nhập kho nguyên liệu.
        if (mr.getType() == MaterialRequest.RequestType.SELLER) {
            _stockInSellerIngredients(mr, now);
        }

        mr.setStatus(MaterialRequest.RequestStatus.COMPLETED);
        mr.setCompletedAt(now);
        MaterialRequest saved = requestRepo.save(mr);

        notificationService.sendToRole(
                Role.OWNER.name(), "MATERIAL_REQUEST_COMPLETED",
                "Phiếu " + mr.getRequestCode() + " đã hoàn thành — tổng " + grandTotal,
                "{\"requestId\":" + id + ",\"code\":\"" + mr.getRequestCode() + "\"}"
        );

        return toDto(saved, true);
    }

    /**
     * Nhập kho nguyên liệu cho phiếu SELLER khi hoàn thành: mỗi dòng tạo/ cộng dồn
     * IngredientExpiry (theo kho + nguyên liệu + hạn dùng + giá vốn) và cộng tồn
     * tổng IngredientStock. Giá vốn = unitPrice đã tính ở bước hoàn thành.
     */
    private void _stockInSellerIngredients(MaterialRequest mr, long now) {
        // Nhập kho THEO TỪNG ĐỢT: mỗi đợt có kho đích + HSD riêng, nên phải duyệt
        // dòng-của-đợt chứ không phải gộp về qtyReceived của item.
        // Phiếu cũ (chưa có đợt nào, dữ liệu trước khi có tính năng nhận lẻ) → fallback
        // về vòng lặp cấp item như trước.
        record Line(MaterialRequestItem item, BigDecimal qty, Long warehouseId, Long expiryDate) {}
        List<Line> lines = new ArrayList<>();

        if (!mr.getReceipts().isEmpty()) {
            for (MaterialRequestReceipt r : mr.getReceipts()) {
                for (MaterialRequestReceiptItem li : r.getItems()) {
                    MaterialRequestItem it = li.getMaterialRequestItem();
                    BigDecimal qty = li.getStockQty() != null ? li.getStockQty() : li.getQty();
                    if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;
                    Long wh = li.getWarehouseId() != null ? li.getWarehouseId() : it.getWarehouseId();
                    lines.add(new Line(it, qty, wh, li.getExpiryDate()));
                }
            }
        } else {
            for (MaterialRequestItem it : mr.getItems()) {
                BigDecimal qty = it.getQtyReceived();
                if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;
                lines.add(new Line(it, qty, it.getWarehouseId(), it.getExpiryDate()));
            }
        }

        for (Line ln : lines) {
            MaterialRequestItem item = ln.item();
            BigDecimal qty = ln.qty();
            if (item.getIngredientId() == null || ln.warehouseId() == null) {
                throw new IllegalStateException(
                        "Dòng \"" + item.getMaterialName() + "\" thiếu nguyên liệu hoặc kho nhận — không thể nhập kho");
            }
            com.nhatnam.server.entity.Warehouse warehouse = warehouseRepo.findById(ln.warehouseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Kho không tồn tại: " + ln.warehouseId()));
            com.nhatnam.server.entity.Ingredient ingredient = ingredientRepo.findById(item.getIngredientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Nguyên liệu không tồn tại: " + item.getIngredientId()));

            BigDecimal cost = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            long expiryMs = ln.expiryDate() != null
                    ? ln.expiryDate()
                    : now + 5L * 365L * 24L * 60L * 60L * 1000L;
            java.time.LocalDate expiryDate = java.time.Instant.ofEpochMilli(expiryMs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();

            // 1) Cộng tồn tổng
            com.nhatnam.server.entity.IngredientStock stock = ingredientStockRepo
                    .findByIngredientIdAndWarehouseId(ingredient.getId(), warehouse.getId())
                    .orElseGet(() -> ingredientStockRepo.save(
                            com.nhatnam.server.entity.IngredientStock.builder()
                                    .ingredientId(ingredient.getId())
                                    .ingredientNameSnapshot(ingredient.getName())
                                    .ingredientUnitSnapshot(ingredient.getUnit())
                                    .warehouse(warehouse)
                                    .stockQuantity(BigDecimal.ZERO)
                                    .updatedAt(now)
                                    .build()));
            stock.setStockQuantity(stock.getStockQuantity().add(qty));
            stock.setUpdatedAt(now);
            ingredientStockRepo.save(stock);

            // 2) Tạo/ cộng dồn lô theo hạn dùng + giá vốn
            final java.time.LocalDate exp = expiryDate;
            final BigDecimal costFinal = cost;
            ingredientExpiryRepo
                    .findByWarehouseIdAndIngredientId(warehouse.getId(), ingredient.getId())
                    .stream()
                    .filter(e -> java.util.Objects.equals(e.getExpiryDate(), exp)
                            && java.util.Objects.equals(e.getCostPrice(), costFinal))
                    .findFirst()
                    .ifPresentOrElse(e -> {
                        e.setQuantity(e.getQuantity().add(qty));
                        e.setUpdatedAt(now);
                        ingredientExpiryRepo.save(e);
                    }, () -> ingredientExpiryRepo.save(
                            com.nhatnam.server.entity.IngredientExpiry.builder()
                                    .warehouse(warehouse)
                                    .ingredientId(ingredient.getId())
                                    .expiryDate(exp)
                                    .quantity(qty)
                                    .costPrice(costFinal)
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build()));
        }
    }

    public Page<MaterialRequestDto> listForFactory(String username, String type, String status,
                                                   Long dateFrom, Long dateTo,
                                                   String search, int page, int size) {
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<MaterialRequest> result = requestRepo.findByCreatedBy_IdAndFilters(
                user.getId(), type, status, dateFrom, dateTo, search, pageable);
        // include items + vendors để frontend hiển thị chi tiết ngay trên card
        return result.map(r -> toDto(r, true));
    }

    public Page<MaterialRequestDto> listForAccountant(String status, Long dateFrom, Long dateTo,
                                                      String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<MaterialRequest> result = requestRepo.findByFilters(null, status, dateFrom, dateTo, search, pageable);
        // include items + vendors để kế toán xem chi tiết ngay
        return result.map(r -> toDto(r, true));
    }

    public MaterialRequestDto getById(Long id) {
        return toDto(findById(id), true);
    }

    // ── SUPER_SELLER: danh mục để tạo phiếu ───────────────────────────────────

    /** Nguyên liệu SUPER_SELLER được đặt (lọc theo category cho phép), có tìm kiếm. */
    public java.util.List<MaterialRequestDtos.SellerIngredientOption> getSellerOrderableIngredients(String q) {
        java.util.Set<Long> catIds = new java.util.HashSet<>();
        for (String name : SELLER_INGREDIENT_CATEGORIES) {
            categoryRepo.findByName(name).ifPresent(c -> catIds.add(c.getId()));
        }
        String kw = q == null ? "" : q.trim().toLowerCase();
        return ingredientRepo.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(i -> i.getCategoryId() != null && catIds.contains(i.getCategoryId()))
                .filter(i -> kw.isEmpty()
                        || (i.getName() != null && i.getName().toLowerCase().contains(kw))
                        || (i.getItemCode() != null && i.getItemCode().toLowerCase().contains(kw)))
                .map(i -> new MaterialRequestDtos.SellerIngredientOption(
                        i.getId(), i.getName(), i.getUnit(), i.getItemCode()))
                .toList();
    }

    /** Danh sách kho đang hoạt động để chọn nơi nhận. */
    public java.util.List<MaterialRequestDtos.WarehouseOption> getActiveWarehouses() {
        return warehouseRepo.findByActiveTrueOrderByIdAsc().stream()
                .map(w -> new MaterialRequestDtos.WarehouseOption(w.getId(), w.getName()))
                .toList();
    }

    // ── Kho nguyên liệu ──────────────────────────────────────────────────────

    public List<FactoryStockSummaryDto> getStockSummary() {
        return getStockSummary(null);
    }

    public List<FactoryStockSummaryDto> getStockSummary(Long factoryId) {
        long now = System.currentTimeMillis();
        long thirtyDays = 30L * 24 * 60 * 60 * 1000;

        // Danh mục NL làm "khung" — nếu có factoryId thì lọc theo xưởng
        List<com.nhatnam.server.entity.FactoryMaterial> catalog = factoryMaterialRepo.findByIsActiveTrueOrderByNameAsc();
        if (factoryId != null) {
            catalog = catalog.stream()
                    .filter(m -> m.getFactories() != null && m.getFactories().stream()
                            .anyMatch(f -> f.getId().equals(factoryId)))
                    .collect(Collectors.toList());
        }

        // Build lookup: name||unit → FactoryMaterial (để lấy category)
        Map<String, com.nhatnam.server.entity.FactoryMaterial> materialLookup = new LinkedHashMap<>();
        for (com.nhatnam.server.entity.FactoryMaterial m : catalog) {
            materialLookup.put(m.getName() + "||" + m.getUnit(), m);
        }

        // Lô tồn kho — lọc theo xưởng nếu có
        List<FactoryMaterialStock> stocks = factoryId != null
                ? stockRepo.findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(factoryId)
                : stockRepo.findByIsActiveTrueOrderByCreatedAtAsc();

        // Group theo materialName + unit
        Map<String, List<FactoryMaterialStock>> grouped = new LinkedHashMap<>();
        for (FactoryMaterialStock s : stocks) {
            String key = s.getMaterialName() + "||" + s.getUnit();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        // Đảm bảo mọi NL trong danh mục đều có nhóm
        for (String key : materialLookup.keySet()) {
            grouped.computeIfAbsent(key, k -> new ArrayList<>());
        }

        return grouped.entrySet().stream().map(e -> {
            String[] parts = e.getKey().split("\\|\\|");
            List<FactoryMaterialStock> lots = e.getValue();
            BigDecimal total = lots.stream().map(FactoryMaterialStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<FactoryStockLotDto> lotDtos = lots.stream()
                    .filter(l -> l.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    .map(l -> FactoryStockLotDto.builder()
                            .id(l.getId())
                            .quantity(l.getQuantity())
                            .initialQuantity(l.getInitialQuantity())
                            .expiryDate(l.getExpiryDate())
                            .nearExpiry(l.getExpiryDate() != null
                                    && l.getExpiryDate() - now <= thirtyDays
                                    && l.getExpiryDate() > now)
                            .createdAt(l.getCreatedAt())
                            .materialRequestCode(l.getMaterialRequestCode())
                            .orderedAt(l.getOrderedAt())
                            .importReceiptInfo(resolveImportInfo(l))
                            .serialImei(resolveSerial(l))
                            .build()).collect(Collectors.toList());

            // Category info từ lookup
            com.nhatnam.server.entity.FactoryMaterial fm = materialLookup.get(e.getKey());
            String catName = null, subCatName = null;
            Long catId = null, subCatId = null;
            if (fm != null && fm.getSubCategory() != null) {
                subCatId = fm.getSubCategory().getId();
                subCatName = fm.getSubCategory().getName();
                if (fm.getSubCategory().getCategory() != null) {
                    catId = fm.getSubCategory().getCategory().getId();
                    catName = fm.getSubCategory().getCategory().getName();
                }
            }

            return FactoryStockSummaryDto.builder()
                    .materialName(parts[0])
                    .unit(parts.length > 1 ? parts[1] : "")
                    .totalQty(total)
                    .categoryName(catName)
                    .subCategoryName(subCatName)
                    .categoryId(catId)
                    .subCategoryId(subCatId)
                    .lots(lotDtos)
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional
    public Map<Long, BigDecimal> deductStockFifo(String materialName, String unit,
                                                 BigDecimal qty, Long workOrderId) {
        List<FactoryMaterialStock> lots = stockRepo
                .findByMaterialNameAndUnitAndIsActiveTrueOrderByCreatedAtAsc(materialName, unit);

        Map<Long, BigDecimal> deducted = new LinkedHashMap<>();
        BigDecimal remaining = qty;

        for (FactoryMaterialStock lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(lot.getQuantity());
            lot.setQuantity(lot.getQuantity().subtract(take));
            lot.setWorkOrderId(workOrderId);
            stockRepo.save(lot);
            deducted.put(lot.getId(), take);
            remaining = remaining.subtract(take);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Kho không đủ nguyên liệu: " + materialName);
        }

        return deducted;
    }

    @Transactional
    public void rollbackStockFifo(Map<Long, BigDecimal> deductedMap) {
        for (Map.Entry<Long, BigDecimal> entry : deductedMap.entrySet()) {
            stockRepo.findById(entry.getKey()).ifPresent(lot -> {
                lot.setQuantity(lot.getQuantity().add(entry.getValue()));
                stockRepo.save(lot);
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Gửi WS noti đến NV xưởng đúng xưởng đặt hàng (thay vì tất cả factory workers).
     * Xưởng lấy từ phiếu đặt hàng → managers → lọc role SUPER_FACTORY_WORKER.
     * Nếu phiếu không có xưởng hoặc xưởng không có managers, fallback sendToRole.
     */
    private void notifyFactoryWorkers(MaterialRequest mr, String eventType, String message, String payload) {
        ProductionFactory factory = mr.getProductionFactory();
        if (factory == null) {
            // Fallback: gửi tới tất cả factory workers (phiếu cũ chưa có xưởng)
            notificationService.sendToRole(Role.SUPER_FACTORY_WORKER.name(), eventType, message, payload);
            return;
        }
        // Reload factory để lấy managers (lazy)
        ProductionFactory f = productionFactoryRepo.findById(factory.getId()).orElse(null);
        if (f == null || f.getManagers() == null || f.getManagers().isEmpty()) {
            notificationService.sendToRole(Role.SUPER_FACTORY_WORKER.name(), eventType, message, payload);
            return;
        }
        // FIX: trước đây so `mgr.getRole()` — đó chỉ là role MẶC ĐỊNH khi đăng nhập.
        // Trưởng xưởng đa role (VD role chính là ACCOUNTANT nhưng trong _user_roles có
        // SUPER_FACTORY_WORKER) sẽ KHÔNG nhận được thông báo. Dùng getAllRoles() —
        // hợp nhất cả role chính lẫn tập role — để không bỏ sót ai.
        boolean sentToAny = false;
        for (User mgr : f.getManagers()) {
            if (mgr.getAllRoles().contains(Role.SUPER_FACTORY_WORKER)) {
                notificationService.sendToUser(mgr, Role.SUPER_FACTORY_WORKER.name(),
                        eventType, message, payload);
                sentToAny = true;
            }
        }
        // Xưởng có managers nhưng không ai là trưởng xưởng → vẫn phải báo cho ai đó
        if (!sentToAny) {
            notificationService.sendToRole(Role.SUPER_FACTORY_WORKER.name(), eventType, message, payload);
        }
    }

    /**
     * Resolve tỷ lệ quy đổi từ FactoryMaterial — snapshot vào item phiếu để không
     * bị thay đổi khi admin sửa nguyên liệu sau này.
     */
    private BigDecimal resolveConversionRatio(ItemRequest item) {
        if (item.getFactoryMaterialId() == null) return null;
        return factoryMaterialRepo.findById(item.getFactoryMaterialId())
                .map(FactoryMaterial::getConversionRatio)
                .orElse(null);
    }

    private MaterialRequest findById(Long id) {
        return requestRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu: " + id));
    }

    /** Còn phải giao = max(0, đặt - đã nhận). Giao dư → 0 (không trả số âm cho FE). */
    private BigDecimal outstandingOf(MaterialRequestItem i) {
        BigDecimal req = nzQty(i.getQtyRequested());
        BigDecimal got = nzQty(i.getQtyReceived());
        BigDecimal left = req.subtract(got);
        return left.compareTo(BigDecimal.ZERO) > 0 ? left : BigDecimal.ZERO;
    }

    private MaterialRequestDto toDto(MaterialRequest mr, boolean includeDetails) {
        BigDecimal totalAmount = mr.getItems().stream()
                .map(MaterialRequestItem::getLineAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MaterialRequestDto dto = MaterialRequestDto.builder()
                .id(mr.getId())
                .requestCode(mr.getRequestCode())
                .createdById(mr.getCreatedBy().getId())
                .createdByName(mr.getCreatedByName())
                .requiredBy(mr.getRequiredBy())
                .status(mr.getStatus().name())
                .type(mr.getType() != null ? mr.getType().name() : "FACTORY")
                .productionFactoryId(mr.getProductionFactory() != null ? mr.getProductionFactory().getId() : null)
                .productionFactoryName(mr.getProductionFactory() != null ? mr.getProductionFactory().getName() : null)
                .orderedAt(mr.getOrderedAt())
                .estimatedDelivery(mr.getEstimatedDelivery())
                .deliveryExtendedTo(mr.getDeliveryExtendedTo())
                .deliveryExtendReason(mr.getDeliveryExtendReason())
                .handledByName(mr.getHandledByName())
                .receivedAt(mr.getReceivedAt())
                .receiveNotes(mr.getReceiveNotes())
                .shortageReason(mr.getShortageReason())
                .completedAt(mr.getCompletedAt())
                .itemCount(mr.getItems().size())
                .totalAmount(totalAmount.compareTo(BigDecimal.ZERO) > 0 ? totalAmount : null)
                .createdAt(mr.getCreatedAt())
                .updatedAt(mr.getUpdatedAt())
                .build();

        if (includeDetails) {
            // Parse cost entries 1 lần để build breakdown cho từng item
            List<MaterialRequestCostEntry> costEntries = mr.getCostEntries().stream()
                    .sorted(Comparator.comparingInt(MaterialRequestCostEntry::getSortOrder))
                    .collect(Collectors.toList());

            // materialAmountByItemId — dùng để tính lại tỷ trọng phân bổ CUSTOM cho hiển thị
            // (cùng công thức với lúc complete(), xem giải thích ở đó).
            Map<Long, BigDecimal> materialAmountByItemId = new HashMap<>();
            for (MaterialRequestCostEntry ce : costEntries) {
                if (ce.getType() == MaterialRequestCostEntry.EntryType.MATERIAL) {
                    List<Long> ids = deserializeIdList(ce.getAppliesToItemIds());
                    if (!ids.isEmpty()) materialAmountByItemId.put(ids.get(0), ce.getAmount());
                }
            }

            Map<Long, List<MaterialRequestDtos.ItemCostBreakdownEntryDto>> breakdownByItemId = new HashMap<>();
            for (MaterialRequestCostEntry ce : costEntries) {
                List<Long> itemIds = deserializeIdList(ce.getAppliesToItemIds());
                if (ce.getType() == MaterialRequestCostEntry.EntryType.MATERIAL) {
                    if (!itemIds.isEmpty()) {
                        breakdownByItemId.computeIfAbsent(itemIds.get(0), k -> new ArrayList<>())
                                .add(MaterialRequestDtos.ItemCostBreakdownEntryDto.builder()
                                        .label(ce.getLabel()).amount(ce.getAmount()).build());
                    }
                } else {
                    // CUSTOM — tính lại phần phân bổ cho mỗi item trong scope (giống thuật toán
                    // trong complete(): tỷ trọng theo giá nguyên liệu, dòng cuối nhận phần dư).
                    BigDecimal scopeMaterialTotal = itemIds.stream()
                            .map(itId -> materialAmountByItemId.getOrDefault(itId, BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal allocatedSoFar = BigDecimal.ZERO;
                    for (int idx = 0; idx < itemIds.size(); idx++) {
                        Long itId = itemIds.get(idx);
                        boolean isLast = (idx == itemIds.size() - 1);
                        BigDecimal share;
                        if (isLast) {
                            share = ce.getAmount().subtract(allocatedSoFar);
                        } else if (scopeMaterialTotal.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal weight = materialAmountByItemId.getOrDefault(itId, BigDecimal.ZERO)
                                    .divide(scopeMaterialTotal, 10, java.math.RoundingMode.HALF_UP);
                            share = ce.getAmount().multiply(weight).setScale(3, java.math.RoundingMode.HALF_UP);
                        } else {
                            share = ce.getAmount().divide(BigDecimal.valueOf(itemIds.size()), 3, java.math.RoundingMode.HALF_UP);
                        }
                        allocatedSoFar = allocatedSoFar.add(share);
                        breakdownByItemId.computeIfAbsent(itId, k -> new ArrayList<>())
                                .add(MaterialRequestDtos.ItemCostBreakdownEntryDto.builder()
                                        .label(ce.getLabel()).amount(share).build());
                    }
                }
            }

            dto.setItems(mr.getItems().stream()
                    .sorted(Comparator.comparingInt(MaterialRequestItem::getSortOrder))
                    .map(i -> MaterialRequestDtos.MaterialRequestItemDto.builder()
                            .id(i.getId())
                            .materialName(i.getMaterialName())
                            .unit(i.getUnit())
                            .qtyRequested(i.getQtyRequested())
                            .qtyReceived(i.getQtyReceived())
                            .qtyOutstanding(outstandingOf(i))
                            .receiveStatus(i.getReceiveStatus() != null ? i.getReceiveStatus().name() : "PENDING")
                            .expiryDate(i.getExpiryDate())
                            .sortOrder(i.getSortOrder())
                            .ingredientId(i.getIngredientId())
                            .warehouseId(i.getWarehouseId())
                            .factoryMaterialId(i.getFactoryMaterialId())
                            .orderUnitType(i.getOrderUnitType())
                            .receivedUnitType(i.getReceivedUnitType())
                            .receivedUnit(i.getReceivedUnit())
                            .conversionRatio(i.getConversionRatio())
                            .weighingLogs(deserializeWeighingLogs(i.getWeighingLogs()))
                            .requestVendorId(i.getSuppliedByVendor() != null ? i.getSuppliedByVendor().getId() : null)
                            .suppliedByVendorName(i.getSuppliedByVendor() != null ? i.getSuppliedByVendor().getVendorName() : null)
                            .unitPrice(i.getUnitPrice())
                            .lineAmount(i.getLineAmount())
                            .costBreakdown(breakdownByItemId.get(i.getId()))
                            .build())
                    .collect(Collectors.toList()));

            // Lịch sử các đợt nhận hàng (cũ → mới)
            dto.setReceipts(mr.getReceipts().stream()
                    .sorted(Comparator.comparingInt(MaterialRequestReceipt::getSequenceNo))
                    .map(r -> MaterialRequestDtos.MaterialRequestReceiptDto.builder()
                            .id(r.getId())
                            .sequenceNo(r.getSequenceNo())
                            .receivedAt(r.getReceivedAt())
                            .receivedByName(r.getReceivedByName())
                            .notes(r.getNotes())
                            .items(r.getItems().stream()
                                    .map(li -> {
                                        MaterialRequestItem it = li.getMaterialRequestItem();
                                        MaterialRequestVendor rv = it.getSuppliedByVendor();
                                        return MaterialRequestDtos.MaterialRequestReceiptItemDto.builder()
                                                .id(li.getId())
                                                .itemId(it.getId())
                                                .materialName(it.getMaterialName())
                                                .qty(li.getQty())
                                                .receivedUnitType(li.getReceivedUnitType())
                                                .receivedUnit(li.getReceivedUnit() != null ? li.getReceivedUnit() : it.getUnit())
                                                .stockQty(li.getStockQty())
                                                .expiryDate(li.getExpiryDate())
                                                .weighingLogs(deserializeWeighingLogs(li.getWeighingLogs()))
                                                .requestVendorId(rv != null ? rv.getId() : null)
                                                .vendorName(rv != null ? rv.getVendorName() : null)
                                                .build();
                                    })
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList()));

            dto.setCostEntries(costEntries.stream()
                    .map(ce -> MaterialRequestDtos.MaterialRequestCostEntryDto.builder()
                            .id(ce.getId())
                            .type(ce.getType().name())
                            .label(ce.getLabel())
                            .amount(ce.getAmount())
                            .itemIds(deserializeIdList(ce.getAppliesToItemIds()))
                            .build())
                    .collect(Collectors.toList()));

            dto.setVendors(mr.getVendors().stream()
                    .sorted(Comparator.comparingInt(MaterialRequestVendor::getSortOrder))
                    .map(v -> {
                        BigDecimal remaining = null;
                        if (v.getPaymentStatus() == MaterialRequestVendor.VendorPaymentStatus.DEBT
                                && v.getTotalAmount() != null) {
                            remaining = v.getTotalAmount().subtract(
                                    v.getPaidAmount() != null ? v.getPaidAmount() : BigDecimal.ZERO);
                        }
                        return MaterialRequestDtos.MaterialRequestVendorDto.builder()
                                .id(v.getId())
                                .vendorId(v.getVendor() != null ? v.getVendor().getId() : null)
                                .vendorName(v.getVendorName())
                                .contactPerson(v.getContactPerson())
                                .contactPhone(v.getContactPhone())
                                .sortOrder(v.getSortOrder())
                                .paymentStatus(v.getPaymentStatus() != null ? v.getPaymentStatus().name() : null)
                                .totalAmount(v.getTotalAmount())
                                .paidAmount(v.getPaidAmount())
                                .debtRemaining(remaining)
                                .debtSettlementStatus(v.getDebtSettlementStatus() != null ? v.getDebtSettlementStatus().name() : null)
                                .debtSince(v.getDebtSince())
                                .paymentMethod(v.getPaymentMethod())
                                .paymentInfo(v.getPaymentInfo())
                                .paymentProofImages(deserializeStringList(v.getPaymentProofImages()))
                                .importReceiptInfo(v.getImportReceiptInfo())
                                .serialImei(v.getSerialImei())
                                .build();
                    })
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private String generateCode() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        long count = requestRepo.countByRequestCodeStartingWith("MR-" + date);
        return String.format("MR-%s-%04d", date, count + 1);
    }

    // ── Helpers: lưu/đọc nhật ký các lần cân (weighing logs) dạng JSON ───────────

    /**
     * Ghi GIÁ VỐN xuống các lô kho xưởng sinh ra từ 1 dòng phiếu đặt hàng.
     *
     * <p>Giá vốn tính theo ĐƠN VỊ LƯU KHO, không phải đơn vị đặt hàng:
     * <pre>
     *   unitCost = lineAmount / tổng số lượng đã nhập kho của dòng này
     * </pre>
     * {@code lineAmount} đã bao gồm phần phí vận chuyển/thuế được phân bổ vào dòng,
     * nên đây chính là chi phí thật để đưa 1 đơn vị nguyên liệu vào kho.
     *
     * <p>Chia theo TỔNG số lượng đã nhập kho (thay vì {@code qtyReceived}) để tự động
     * đúng cả khi nhận theo đơn vị đặt hàng — VD đặt 10 thùng, mỗi thùng 5kg → tồn kho
     * 50kg: giá vốn phải là đồng/kg, không phải đồng/thùng.
     *
     * <p>Idempotent: chạy lại (sửa phiếu) sẽ ghi đè bằng giá mới.
     */
    private void applyUnitCostToLots(MaterialRequestItem item, BigDecimal lineAmount) {
        applyUnitCostToLots(item, lineAmount, null, null, null);
    }

    /** Ưu tiên snapshot ở lô; nếu lô cũ null → fallback lấy từ NCC của phiếu gốc. */
    private String resolveImportInfo(FactoryMaterialStock l) {
        if (l.getImportReceiptInfo() != null && !l.getImportReceiptInfo().isBlank())
            return l.getImportReceiptInfo();
        MaterialRequestVendor v = vendorOfLot(l);
        return v != null ? v.getImportReceiptInfo() : null;
    }

    private String resolveSerial(FactoryMaterialStock l) {
        if (l.getSerialImei() != null && !l.getSerialImei().isBlank())
            return l.getSerialImei();
        MaterialRequestVendor v = vendorOfLot(l);
        return v != null ? v.getSerialImei() : null;
    }

    private MaterialRequestVendor vendorOfLot(FactoryMaterialStock l) {
        try {
            if (l.getMaterialRequestItem() != null
                    && l.getMaterialRequestItem().getSuppliedByVendor() != null) {
                return l.getMaterialRequestItem().getSuppliedByVendor();
            }
        } catch (Exception ignore) { /* lazy/null-safe */ }
        return null;
    }

    private void applyUnitCostToLots(MaterialRequestItem item, BigDecimal lineAmount,
                                     MaterialRequestVendor vendor) {
        applyUnitCostToLots(item, lineAmount, vendor,
                vendor != null ? vendor.getImportReceiptInfo() : null,
                vendor != null ? vendor.getSerialImei() : null);
    }

    private void applyUnitCostToLots(MaterialRequestItem item, BigDecimal lineAmount,
                                     MaterialRequestVendor vendor,
                                     String importReceiptInfo, String serialImei) {
        List<FactoryMaterialStock> lots = stockRepo.findByMaterialRequestItem_Id(item.getId());
        if (lots.isEmpty()) return;   // phiếu SELLER hoặc chưa tạo lô — không có gì để ghi

        BigDecimal stockedQty = lots.stream()
                .map(l -> l.getInitialQuantity() != null ? l.getInitialQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (stockedQty.compareTo(BigDecimal.ZERO) <= 0) return;

        // Làm tròn tới hàng ĐƠN VỊ ĐỒNG — đây là bước cuối cùng của phép tính giá vốn.
        BigDecimal unitCost = lineAmount.divide(stockedQty, 0, java.math.RoundingMode.HALF_UP);
        for (FactoryMaterialStock lot : lots) {
            lot.setUnitCost(unitCost);
            // Mục 7: snapshot 2 field NCC xuống lô để hiển thị + tìm kiếm ở trang Chi tiết các lô
            if (importReceiptInfo != null) lot.setImportReceiptInfo(importReceiptInfo);
            if (serialImei != null)        lot.setSerialImei(serialImei);
        }
        stockRepo.saveAll(lots);
    }

    private String serializeWeighingLogs(List<BigDecimal> logs) {
        if (logs == null || logs.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(logs);
        } catch (Exception e) {
            return null;
        }
    }

    private List<BigDecimal> deserializeWeighingLogs(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<BigDecimal>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers: lưu/đọc danh sách string (ảnh chứng từ) dạng JSON ───────────────

    private String serializeStringList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers: lưu/đọc danh sách id (MaterialRequestItem.id áp dụng cho 1 dòng giá/phí) ──

    private String serializeIdList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Long> deserializeIdList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}