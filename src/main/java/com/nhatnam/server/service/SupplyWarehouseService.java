package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.supply.SupplyDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * KHO VĂN PHÒNG PHẨM / DỤNG CỤ.
 *
 * <p>Nguyên tắc xuyên suốt: <b>tồn kho, lịch sử nhập, lịch sử rút đều tách riêng
 * theo kho</b>. Cùng một mặt hàng ở Phổ Quang và Quận 9 là 2 dòng tồn độc lập.
 *
 * <p>Quyền:
 * <ul>
 *   <li>Nhập kho — chỉ phát sinh khi NGƯỜI TẠO PHIẾU xác nhận nhận hàng
 *       (gọi từ {@code SupplyOrderService}).</li>
 *   <li>Rút sử dụng — chỉ người được gán cho kho đó ({@code user_supply_warehouse}).</li>
 *   <li>Owner — xem cả 2 kho, READ-ONLY, và là người gán kho cho user.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SupplyWarehouseService {

    private final SupplyWarehouseRepository warehouseRepo;
    private final SupplyStockRepository stockRepo;
    private final SupplyStockTransactionRepository txRepo;
    private final UserSupplyWarehouseRepository assignmentRepo;
    private final SupplyItemRepository itemRepo;
    private final UserRepository userRepo;

    // ══════════════════════════════════════════════════════════════════════════
    //  Phân quyền kho
    // ══════════════════════════════════════════════════════════════════════════

    private User user(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
    }

    /**
     * Ghi chú: KHÔNG còn khái niệm "owner-like được mọi kho" trong service này.
     * Quyền thao tác kho CHỈ đọc từ bảng {@code user_supply_warehouse}; Owner xem
     * toàn bộ kho qua {@link #listAllWarehouses()} (đường riêng, read-only).
     */

    /**
     * ID các kho user ĐƯỢC THAO TÁC — CHỈ theo bảng phân quyền, không có ngoại lệ.
     *
     * <p><b>Owner KHÔNG được cộng thêm gì ở đây.</b> Bản cũ trả về TẤT CẢ kho cho
     * tài khoản có role OWNER/ADMIN, và điều đó sai ở hai mặt:
     * <ul>
     *   <li>Hàm này chỉ được dùng để CHẶN GHI (tạo/sửa phiếu). Nới cho Owner nghĩa
     *       là Owner lập được phiếu cho kho mình không phụ trách — trong khi
     *       {@link #assertCanWrite} lúc nhận hàng lại tra thẳng bảng phân quyền và
     *       chặn. Phiếu tạo xong rồi mới kẹt, không nhận hàng vào đâu được.</li>
     *   <li>Tài khoản KIÊM NHIỆM (vừa là Chủ tịch vừa là SUPER_FACTORY_WORKER được
     *       gán đúng 1 kho) bị nhánh Owner nuốt mất phân quyền thật.</li>
     * </ul>
     *
     * <p>Owner muốn XEM toàn bộ kho thì đã có đường riêng:
     * {@code GET /api/owner/supply-warehouses} → {@link #listAllWarehouses()}.
     */
    @Transactional(readOnly = true)
    public Set<Long> assignedWarehouseIds(String username) {
        User u = user(username);
        return assignmentRepo.findByUserId(u.getId()).stream()
                .map(UserSupplyWarehouse::getWarehouseId).collect(Collectors.toSet());
    }

    /**
     * Ném lỗi nếu user KHÔNG được GHI trên kho này. Owner bị chặn ở đây một cách
     * cố ý: Owner chỉ xem, không rút hàng.
     */
    private void assertCanWrite(User u, Long warehouseId) {
        if (!assignmentRepo.existsByUserIdAndWarehouseId(u.getId(), warehouseId)) {
            throw new BusinessException("Bạn không được phân quyền thao tác trên kho này");
        }
    }

    /**
     * Kho user ĐƯỢC THAO TÁC — dropdown "kho nhận" khi lập phiếu và page Rút sử dụng.
     * FE auto-select khi list chỉ có 1 phần tử.
     *
     * <p>Danh sách trả về LUÔN khớp với {@link #assignedWarehouseIds(String)}:
     * thấy kho nào là thao tác được kho đó, {@code assigned} luôn {@code true}.
     * Trước đây tài khoản có role Owner được trả về TẤT CẢ kho kèm
     * {@code assigned = false}, gây ra đúng hai triệu chứng trái ngược nhau trên
     * cùng một tài khoản: form lập phiếu hiện dư kho không được phép, còn page Kho
     * (lọc theo cờ {@code assigned}) lại báo "chưa được gán kho nào".
     *
     * <p>Owner xem toàn bộ kho ở đường riêng {@link #listAllWarehouses()}.
     */
    @Transactional(readOnly = true)
    public List<SupplyWarehouseDto> listWarehouses(String username) {
        User u = user(username);
        Set<Long> assigned = assignmentRepo.findByUserId(u.getId()).stream()
                .map(UserSupplyWarehouse::getWarehouseId).collect(Collectors.toSet());
        return warehouseRepo.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .filter(w -> assigned.contains(w.getId()))
                .map(w -> SupplyWarehouseDto.builder()
                        .id(w.getId()).name(w.getName()).address(w.getAddress())
                        .active(w.isActive())
                        .assigned(true)
                        .build())
                .toList();
    }

    /** Owner: xem TẤT CẢ kho (kể cả khi không được gán). */
    @Transactional(readOnly = true)
    public List<SupplyWarehouseDto> listAllWarehouses() {
        return warehouseRepo.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(w -> SupplyWarehouseDto.builder()
                        .id(w.getId()).name(w.getName()).address(w.getAddress())
                        .active(w.isActive()).assigned(false).build())
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Tồn kho
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * @param onlyPositive true khi phục vụ dropdown "Rút sử dụng" — chỉ hiện món
     *                     có tồn &gt; 0 TRONG KHO ĐANG CHỌN.
     */
    @Transactional(readOnly = true)
    public List<SupplyStockRowDto> stockOf(Long warehouseId, boolean onlyPositive, String search) {
        List<SupplyStock> rows = stockRepo.findByWarehouseId(warehouseId);
        if (rows.isEmpty()) return List.of();

        Map<Long, SupplyItem> itemById = itemRepo.findAllById(
                        rows.stream().map(SupplyStock::getSupplyItemId).toList())
                .stream().collect(Collectors.toMap(SupplyItem::getId, i -> i));

        String kw = search == null ? "" : SupplyItem.normalize(search);

        return rows.stream()
                .filter(s -> !onlyPositive || (s.getQuantity() != null
                        && s.getQuantity().compareTo(BigDecimal.ZERO) > 0))
                .map(s -> {
                    SupplyItem it = itemById.get(s.getSupplyItemId());
                    if (it == null || it.getDeletedAt() != null) return null;
                    return SupplyStockRowDto.builder()
                            .supplyItemId(it.getId())
                            .name(it.getName())
                            .specification(it.getSpecification())
                            .unit(it.getUnit())
                            .quantity(s.getQuantity())
                            .updatedAt(s.getUpdatedAt())
                            .build();
                })
                .filter(Objects::nonNull)
                .filter(d -> kw.isEmpty()
                        || SupplyItem.normalize(d.getName()).contains(kw)
                        || SupplyItem.normalize(d.getSpecification()).contains(kw))
                .sorted(Comparator.comparing(SupplyStockRowDto::getName))
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NHẬP KHO — gọi từ SupplyOrderService khi xác nhận nhận hàng
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Cộng tồn + ghi transaction {@code IN}.
     *
     * <p><b>Chỉ dòng CONSUMABLE mới gọi hàm này</b> — dòng SERVICE (dịch vụ)
     * không có {@code supplyItemId} nên không nhập kho, xem
     * {@code SupplyOrderService#confirmReceipt}.
     */
    @Transactional
    public void stockIn(Long warehouseId, Long supplyItemId, BigDecimal qty,
                        String refType, Long refId, String note, User performer) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return;

        SupplyStock stock = stockRepo.findWithLockByWarehouseIdAndSupplyItemId(warehouseId, supplyItemId)
                .orElseGet(() -> SupplyStock.builder()
                        .warehouseId(warehouseId).supplyItemId(supplyItemId)
                        .quantity(BigDecimal.ZERO).build());
        stock.setQuantity(stock.getQuantity().add(qty));
        stockRepo.save(stock);

        txRepo.save(SupplyStockTransaction.builder()
                .warehouseId(warehouseId).supplyItemId(supplyItemId)
                .type(SupplyStockTransaction.TxType.IN)
                .quantity(qty).balanceAfter(stock.getQuantity())
                .refType(refType).refId(refId).note(note)
                .performedById(performer != null ? performer.getId() : null)
                .performedByName(performer != null ? displayName(performer) : null)
                .build());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RÚT SỬ DỤNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Rút nhiều dòng trong 1 lần. Validate tồn TRƯỚC khi trừ để hoặc rút được
     * hết, hoặc không rút gì (transaction rollback) — tránh trạng thái nửa vời.
     */
    @Transactional
    public List<SupplyTransactionDto> withdraw(WithdrawRequest req, String username) {
        if (req.getWarehouseId() == null)
            throw new BusinessException("Vui lòng chọn kho");
        if (req.getLines() == null || req.getLines().isEmpty())
            throw new BusinessException("Chưa chọn vật dụng cần rút");

        User u = user(username);
        assertCanWrite(u, req.getWarehouseId());

        SupplyWarehouse wh = warehouseRepo.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kho"));

        List<SupplyTransactionDto> result = new ArrayList<>();
        for (WithdrawLine line : req.getLines()) {
            if (line.getSupplyItemId() == null) continue;
            BigDecimal qty = line.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
                throw new BusinessException("Số lượng rút phải lớn hơn 0");

            SupplyItem item = itemRepo.findById(line.getSupplyItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vật dụng"));

            SupplyStock stock = stockRepo
                    .findWithLockByWarehouseIdAndSupplyItemId(req.getWarehouseId(), item.getId())
                    .orElseThrow(() -> new BusinessException(
                            "Kho " + wh.getName() + " không có tồn cho: " + item.getName()));

            if (stock.getQuantity().compareTo(qty) < 0) {
                throw new BusinessException(String.format(
                        "Không đủ tồn cho \"%s\" tại kho %s — còn %s %s, cần %s %s",
                        item.getName(), wh.getName(),
                        stock.getQuantity().stripTrailingZeros().toPlainString(), item.getUnit(),
                        qty.stripTrailingZeros().toPlainString(), item.getUnit()));
            }

            stock.setQuantity(stock.getQuantity().subtract(qty));
            stockRepo.save(stock);

            String note = (line.getNote() != null && !line.getNote().isBlank())
                    ? line.getNote().trim() : req.getNote();

            SupplyStockTransaction tx = txRepo.save(SupplyStockTransaction.builder()
                    .warehouseId(req.getWarehouseId()).supplyItemId(item.getId())
                    .type(SupplyStockTransaction.TxType.OUT)
                    .quantity(qty).balanceAfter(stock.getQuantity())
                    .refType("WITHDRAW").note(note)
                    .performedById(u.getId()).performedByName(displayName(u))
                    .build());

            result.add(toTxDto(tx, item, wh.getName()));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Lịch sử
    // ══════════════════════════════════════════════════════════════════════════

    /** @param type "IN" | "OUT" | null (cả 2). */
    @Transactional(readOnly = true)
    public Page<SupplyTransactionDto> history(Long warehouseId, String type,
                                              Long from, Long to, int page, int size) {
        SupplyWarehouse wh = warehouseRepo.findById(warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kho"));
        Page<SupplyStockTransaction> rows = txRepo.history(
                warehouseId,
                (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) ? null : type.toUpperCase(),
                from, to, PageRequest.of(page, size));

        Map<Long, SupplyItem> itemById = itemRepo.findAllById(
                        rows.getContent().stream().map(SupplyStockTransaction::getSupplyItemId).toList())
                .stream().collect(Collectors.toMap(SupplyItem::getId, i -> i));

        return rows.map(t -> toTxDto(t, itemById.get(t.getSupplyItemId()), wh.getName()));
    }

    private SupplyTransactionDto toTxDto(SupplyStockTransaction t, SupplyItem item, String whName) {
        return SupplyTransactionDto.builder()
                .id(t.getId())
                .warehouseId(t.getWarehouseId()).warehouseName(whName)
                .supplyItemId(t.getSupplyItemId())
                .name(item != null ? item.getName() : "(đã xoá)")
                .unit(item != null ? item.getUnit() : null)
                .specification(item != null ? item.getSpecification() : null)
                .type(t.getType().name())
                .quantity(t.getQuantity()).balanceAfter(t.getBalanceAfter())
                .refType(t.getRefType()).refId(t.getRefId())
                .note(t.getNote())
                .performedByName(t.getPerformedByName())
                .createdAt(t.getCreatedAt())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Owner: gán kho cho user
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public UserWarehouseAssignmentDto assign(AssignWarehouseRequest req, String actorName) {
        if (req.getUserId() == null) throw new BusinessException("Thiếu userId");
        User target = userRepo.findById(req.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        Set<Long> valid = warehouseRepo.findByActiveTrueOrderBySortOrderAscIdAsc()
                .stream().map(SupplyWarehouse::getId).collect(Collectors.toSet());
        List<Long> wanted = (req.getWarehouseIds() == null ? List.<Long>of() : req.getWarehouseIds())
                .stream().filter(valid::contains).distinct().toList();

        // ══════════════════════════════════════════════════════════════════
        //  GHI THEO SAI KHÁC, KHÔNG "xoá sạch rồi thêm lại"
        // ══════════════════════════════════════════════════════════════════
        //
        //  Bản cũ làm: deleteByUserId(...) rồi save(...) từng dòng. Cách đó ném
        //  lỗi trùng khoá:
        //      Duplicate entry '19-2' for key 'uk_user_supply_wh'
        //
        //  Lý do: deleteByUserId là derived-delete nên Spring Data nạp entity rồi
        //  gọi em.remove() — lệnh DELETE chỉ nằm trong ActionQueue chứ chưa chạy.
        //  Khi flush, Hibernate CỐ ĐỊNH thứ tự theo LOẠI thao tác: toàn bộ INSERT
        //  chạy TRƯỚC, DELETE chạy SAU CÙNG. Nên INSERT lại đúng cặp (user, kho)
        //  vừa "xoá" sẽ đụng unique index vì dòng cũ vẫn còn nguyên trong DB.
        //
        //  Chỉ cần bấm gán lại đúng cái kho đang có là dính — tức là gần như
        //  chắc chắn gặp khi Owner sửa phân quyền.
        //
        //  Ghi theo sai khác vừa tránh hẳn vấn đề thứ tự, vừa IDEMPOTENT (gán lại
        //  y hệt thì không sinh câu lệnh nào), lại giữ nguyên assigned_at/
        //  assigned_by_name của những kho không đổi thay vì tạo mới mỗi lần lưu.
        List<UserSupplyWarehouse> current = assignmentRepo.findByUserId(target.getId());
        Set<Long> currentIds = current.stream()
                .map(UserSupplyWarehouse::getWarehouseId).collect(Collectors.toSet());

        // ── (1) XOÁ cái không còn được chọn ───────────────────────────────
        List<UserSupplyWarehouse> toRemove = current.stream()
                .filter(a -> !wanted.contains(a.getWarehouseId()))
                .toList();
        assignmentRepo.deleteAll(toRemove);

        // ── (2) THÊM cái chưa có ──────────────────────────────────────────
        for (Long wid : wanted) {
            if (currentIds.contains(wid)) continue;   // đã có sẵn → không đụng
            assignmentRepo.save(UserSupplyWarehouse.builder()
                    .userId(target.getId()).warehouseId(wid)
                    .assignedByName(actorName).build());
        }

        return UserWarehouseAssignmentDto.builder()
                .userId(target.getId()).username(target.getUsername())
                .fullName(displayName(target))
                .role(target.getRole() != null ? target.getRole().name() : null)
                .department(target.getDepartment()).position(target.getPosition())
                .warehouseIds(wanted)
                .build();
    }

    /** Danh sách user thuộc 3 role được tạo phiếu + trạng thái gán kho hiện tại. */
    @Transactional(readOnly = true)
    public List<UserWarehouseAssignmentDto> listAssignments() {
        LinkedHashMap<Long, User> users = new LinkedHashMap<>();
        for (Role r : List.of(Role.SUPER_SELLER, Role.SUPER_WAREHOUSE, Role.SUPER_FACTORY_WORKER)) {
            userRepo.findByRolesContaining(r).forEach(u -> users.putIfAbsent(u.getId(), u));
            userRepo.findByRole(r).forEach(u -> users.putIfAbsent(u.getId(), u));
        }
        return users.values().stream().map(u -> UserWarehouseAssignmentDto.builder()
                        .userId(u.getId()).username(u.getUsername()).fullName(displayName(u))
                        .role(u.getRole() != null ? u.getRole().name() : null)
                        .department(u.getDepartment()).position(u.getPosition())
                        .warehouseIds(assignmentRepo.findByUserId(u.getId()).stream()
                                .map(UserSupplyWarehouse::getWarehouseId).toList())
                        .build())
                .toList();
    }

    private String displayName(User u) {
        return (u.getFullName() != null && !u.getFullName().isBlank())
                ? u.getFullName() : u.getUsername();
    }
}
