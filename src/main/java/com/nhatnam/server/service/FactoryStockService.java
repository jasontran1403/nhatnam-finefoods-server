package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.production.FactoryStockDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * XUẤT / CHUYỂN kho nguyên liệu xưởng (Mục 2) + hoàn thiện chiều chuyển sang kho
 * thành phẩm/nguyên liệu xưởng (Mục 1).
 *
 * <p>Nguồn luôn là kho nguyên liệu 1 xưởng ({@link FactoryMaterialStock}). Kho đích
 * là dropdown hợp nhất, mã hoá bằng khoá chuỗi:
 * <ul>
 *   <li>{@code w:<id>}  — Warehouse (kho bán / trung chuyển) → lưu {@link Ingredient}</li>
 *   <li>{@code fm:<id>} — kho nguyên liệu xưởng khác → lưu {@link FactoryMaterialStock}</li>
 *   <li>{@code fg:<id>} — kho thành phẩm xưởng khác → lưu {@link FinishedGoodsStock}</li>
 * </ul>
 *
 * <p>Điều kiện chung: chỉ chuyển được nguyên liệu mà KHO ĐÍCH ĐANG CÓ, đối chiếu
 * theo TÊN (không phân biệt hoa/thường, đã trim). FIFO theo ngày tạo lô; giữ nguyên
 * HSD + giá vốn của lô nguồn. Mọi giao dịch đều ghi {@link FactoryStockNote}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryStockService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final FactoryMaterialStockRepository factoryStockRepo;
    private final FactoryStockNoteRepository noteRepo;
    private final ProductionFactoryRepository factoryRepo;
    private final WarehouseRepository warehouseRepo;
    private final IngredientRepository ingredientRepo;
    private final IngredientStockRepository ingredientStockRepo;
    private final IngredientExpiryRepository ingredientExpiryRepo;
    private final IngredientWarehouseRepository ingredientWarehouseRepo;
    private final FinishedGoodsStockRepository finishedGoodsStockRepo;
    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;

    // ═══════════════════════════ KHO ĐÍCH ═══════════════════════════════════

    /**
     * Danh sách kho đích cho 1 xưởng nguồn: tất cả kho bán/trung chuyển đang hoạt
     * động + kho nguyên liệu xưởng khác + kho thành phẩm xưởng khác (loại chính nó).
     */
    @Transactional(readOnly = true)
    public List<StockTargetDto> listTargets(Long sourceFactoryId) {
        List<StockTargetDto> result = new ArrayList<>();

        warehouseRepo.findByActiveTrueOrderByIdAsc().forEach(w -> result.add(StockTargetDto.builder()
                .key("w:" + w.getId())
                .name(w.getName())
                .typeLabel(w.getType() == Warehouse.WarehouseType.SALE ? "Kho bán" : "Trung chuyển")
                .build()));

        factoryRepo.findByStatusOrderByNameAsc(ProductionFactory.FactoryStatus.ACTIVE).forEach(f -> {
            if (f.getId().equals(sourceFactoryId)) return;   // không tự chuyển cho chính mình
            result.add(StockTargetDto.builder()
                    .key("fm:" + f.getId()).name(f.getName())
                    .typeLabel("Kho nguyên liệu xưởng").build());
            result.add(StockTargetDto.builder()
                    .key("fg:" + f.getId()).name(f.getName())
                    .typeLabel("Kho xưởng").build());
        });
        return result;
    }

    // ═══════════════════════ NGUYÊN LIỆU CHUYỂN ĐƯỢC ════════════════════════

    /** Tồn khả dụng của kho nguyên liệu 1 xưởng, gộp theo tên||đơn vị. */
    private Map<String, BigDecimal> sourceAvailable(Long factoryId) {
        Map<String, BigDecimal> avail = new LinkedHashMap<>();
        for (FactoryMaterialStock s : factoryStockRepo
                .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(factoryId)) {
            if (s.getQuantity() == null || s.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
            avail.merge(s.getMaterialName() + "||" + s.getUnit(), s.getQuantity(), BigDecimal::add);
        }
        return avail;
    }

    /** Tập tên (lowercase) nguyên liệu mà kho đích đang có. */
    private Set<String> targetNameSet(String targetKey) {
        String[] p = parseKey(targetKey);
        String kind = p[0];
        Long id = Long.valueOf(p[1]);
        Set<String> names = new HashSet<>();
        switch (kind) {
            case "w" -> {
                for (Long ingId : ingredientWarehouseRepo.findIngredientIdsByWarehouseId(id)) {
                    ingredientRepo.findById(ingId)
                            .filter(i -> Boolean.TRUE.equals(i.getIsActive()) && i.getName() != null)
                            .ifPresent(i -> names.add(i.getName().trim().toLowerCase()));
                }
            }
            case "fm" -> {
                for (FactoryMaterialStock s : factoryStockRepo
                        .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(id)) {
                    if (s.getMaterialName() != null) names.add(s.getMaterialName().trim().toLowerCase());
                }
            }
            case "fg" -> {
                for (FinishedGoodsStock s : finishedGoodsStockRepo.searchActiveLotsByFactory(null, id)) {
                    if (s.getProductName() != null) names.add(s.getProductName().trim().toLowerCase());
                }
            }
            default -> throw new IllegalArgumentException("Kho đích không hợp lệ: " + targetKey);
        }
        return names;
    }

    /** Nguyên liệu chuyển được = giao (theo tên) giữa tồn xưởng nguồn và kho đích. */
    @Transactional(readOnly = true)
    public List<TransferableMaterialDto> listTransferable(Long factoryId, String targetKey) {
        if (factoryId == null || targetKey == null) return List.of();
        Set<String> targetNames = targetNameSet(targetKey);
        if (targetNames.isEmpty()) return List.of();

        List<TransferableMaterialDto> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : sourceAvailable(factoryId).entrySet()) {
            String[] parts = e.getKey().split("\\|\\|", 2);
            String name = parts[0];
            if (!targetNames.contains(name.trim().toLowerCase())) continue;
            result.add(TransferableMaterialDto.builder()
                    .materialName(name)
                    .unit(parts.length > 1 ? parts[1] : "")
                    .availableQuantity(e.getValue())
                    .build());
        }
        result.sort(Comparator.comparing(TransferableMaterialDto::getMaterialName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    // ═══════════════════════════ XUẤT KHO ═══════════════════════════════════

    @Transactional
    public FactoryStockNoteDto exportStock(ExportFactoryMaterialRequest req, String username) {
        if (req.getFactoryId() == null) throw new IllegalArgumentException("Vui lòng chọn kho xưởng");
        if (req.getReason() == null || req.getReason().isBlank())
            throw new IllegalArgumentException("Vui lòng nhập lý do xuất kho");
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 nguyên liệu");

        User actor = getUser(username);
        ProductionFactory factory = factoryRepo.findById(req.getFactoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xưởng"));
        long now = System.currentTimeMillis();

        FactoryStockNote note = baseNote(FactoryStockNote.NoteType.EXPORT, factory, actor, now);
        note.setNoteCode(genCode("FEX", now));
        note.setReason(req.getReason().trim());
        note.setDocumentImages(writeImages(req.getDocumentImages()));

        BigDecimal totalCost = BigDecimal.ZERO;
        for (ExportFactoryMaterialRequest.Line line : req.getItems()) {
            Deducted d = deductFromFactoryFifo(factory.getId(), line.getMaterialName(), line.getUnit(), line.getQuantity(), now);
            note.getLines().add(buildLine(note, line.getMaterialName(), line.getUnit(),
                    line.getQuantity(), d.avgUnitCost(), d.earliestExpiry()));
            totalCost = totalCost.add(d.totalCost());
        }
        note.setTotalCostValue(totalCost);
        return toNoteDto(noteRepo.save(note));
    }

    // ═══════════════════════════ CHUYỂN KHO ═════════════════════════════════

    @Transactional
    public FactoryStockNoteDto transferStock(TransferFactoryMaterialRequest req, String username) {
        if (req.getFactoryId() == null) throw new IllegalArgumentException("Vui lòng chọn kho xưởng nguồn");
        if (req.getTargetKey() == null || req.getTargetKey().isBlank())
            throw new IllegalArgumentException("Vui lòng chọn kho đích");
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 nguyên liệu");

        User actor = getUser(username);
        ProductionFactory factory = factoryRepo.findById(req.getFactoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xưởng nguồn"));
        String[] p = parseKey(req.getTargetKey());
        String kind = p[0];
        Long targetId = Long.valueOf(p[1]);
        long now = System.currentTimeMillis();

        // Chốt chặn: kho đích phải có đủ các nguyên liệu (trùng tên) đang chuyển
        Set<String> targetNames = targetNameSet(req.getTargetKey());

        FactoryStockNote outNote = baseNote(FactoryStockNote.NoteType.TRANSFER_OUT, factory, actor, now);
        outNote.setNoteCode(genCode("FTO", now));
        outNote.setDocumentImages(writeImages(req.getDocumentImages()));
        outNote.setReason(req.getNote());

        // Chuẩn bị phiếu nhập đối ứng nếu đích là kho xưởng (fm/fg)
        ProductionFactory targetFactory = null;
        FactoryStockNote inNote = null;
        if (kind.equals("fm") || kind.equals("fg")) {
            targetFactory = factoryRepo.findById(targetId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xưởng đích"));
            inNote = baseNote(FactoryStockNote.NoteType.TRANSFER_IN, targetFactory, actor, now);
            inNote.setNoteCode(genCode("FTI", now));
        }

        Warehouse targetWarehouse = null;
        if (kind.equals("w")) {
            targetWarehouse = warehouseRepo.findById(targetId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kho đích"));
        }

        // set nhãn đích
        String targetName = switch (kind) {
            case "w" -> targetWarehouse.getName();
            default -> targetFactory.getName();
        };
        FactoryStockNote.TargetKind tKind = switch (kind) {
            case "w" -> FactoryStockNote.TargetKind.WAREHOUSE;
            case "fm" -> FactoryStockNote.TargetKind.FACTORY_MATERIAL;
            default -> FactoryStockNote.TargetKind.FACTORY_FINISHED;
        };
        outNote.setTargetKind(tKind);
        outNote.setTargetId(targetId);
        outNote.setTargetName(targetName);

        BigDecimal totalCost = BigDecimal.ZERO;
        for (TransferFactoryMaterialRequest.Line line : req.getItems()) {
            String nameKey = line.getMaterialName().trim().toLowerCase();
            if (!targetNames.contains(nameKey)) {
                throw new IllegalArgumentException("Kho đích không có nguyên liệu: " + line.getMaterialName());
            }
            Deducted d = deductFromFactoryFifo(factory.getId(), line.getMaterialName(), line.getUnit(), line.getQuantity(), now);
            totalCost = totalCost.add(d.totalCost());

            outNote.getLines().add(buildLine(outNote, line.getMaterialName(), line.getUnit(),
                    line.getQuantity(), d.avgUnitCost(), d.earliestExpiry()));

            // Cộng vào kho đích tuỳ loại
            switch (kind) {
                case "w" -> addToWarehouse(targetWarehouse, line.getMaterialName(), line.getUnit(),
                        line.getQuantity(), d, now);
                case "fm" -> {
                    addToFactoryMaterial(targetFactory, line.getMaterialName(), line.getUnit(),
                            line.getQuantity(), d, now);
                    inNote.getLines().add(buildLine(inNote, line.getMaterialName(), line.getUnit(),
                            line.getQuantity(), d.avgUnitCost(), d.earliestExpiry()));
                }
                case "fg" -> {
                    addToFinishedGoods(targetFactory, line.getMaterialName(), line.getUnit(),
                            line.getQuantity(), d, now);
                    inNote.getLines().add(buildLine(inNote, line.getMaterialName(), line.getUnit(),
                            line.getQuantity(), d.avgUnitCost(), d.earliestExpiry()));
                }
            }
        }
        outNote.setTotalCostValue(totalCost);

        FactoryStockNote savedOut = noteRepo.save(outNote);
        if (inNote != null) {
            inNote.setTotalCostValue(totalCost);
            inNote.setTargetKind(FactoryStockNote.TargetKind.FACTORY_MATERIAL);
            inNote.setTargetId(factory.getId());
            inNote.setTargetName(factory.getName());
            FactoryStockNote savedIn = noteRepo.save(inNote);
            savedOut.setLinkedNoteId(savedIn.getId());
            savedIn.setLinkedNoteId(savedOut.getId());
            noteRepo.save(savedOut);
            noteRepo.save(savedIn);
        }
        return toNoteDto(savedOut);
    }

    // ═══════════════════════════ LỊCH SỬ ════════════════════════════════════

    @Transactional(readOnly = true)
    public List<FactoryStockNoteDto> listNotes(Long factoryId, String type) {
        FactoryStockNote.NoteType nt = null;
        if (type != null && !type.isBlank() && !type.equalsIgnoreCase("ALL")) {
            nt = FactoryStockNote.NoteType.valueOf(type);
        }
        return noteRepo.findByFactoryAndType(factoryId, nt).stream()
                .map(this::toNoteDto).collect(Collectors.toList());
    }

    // ═══════════════════════════ HELPERS ════════════════════════════════════

    /** Kết quả 1 lần trừ FIFO: tổng giá vốn, giá vốn bình quân, HSD sớm nhất. */
    private record Deducted(BigDecimal totalCost, BigDecimal avgUnitCost, Long earliestExpiry) {}

    /**
     * Trừ FIFO trên kho nguyên liệu 1 xưởng theo tên+đơn vị (ngày tạo lô tăng dần).
     * Nếu tổng tồn không đủ → ném lỗi "thiếu nguyên liệu".
     */
    private Deducted deductFromFactoryFifo(Long factoryId, String materialName, String unit,
                                           BigDecimal qty, long now) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Số lượng phải lớn hơn 0: " + materialName);

        List<FactoryMaterialStock> lots = factoryStockRepo
                .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(factoryId).stream()
                .filter(s -> eq(s.getMaterialName(), materialName) && eq(s.getUnit(), unit))
                .filter(s -> s.getQuantity() != null && s.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        BigDecimal available = lots.stream().map(FactoryMaterialStock::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(qty) < 0) {
            throw new IllegalStateException(String.format("Thiếu nguyên liệu '%s': cần %s, tồn %s",
                    materialName, qty.stripTrailingZeros().toPlainString(),
                    available.stripTrailingZeros().toPlainString()));
        }

        BigDecimal remaining = qty;
        BigDecimal totalCost = BigDecimal.ZERO;
        Long earliestExpiry = null;
        for (FactoryMaterialStock lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(lot.getQuantity());
            BigDecimal unitCost = lot.getUnitCost() != null ? lot.getUnitCost() : BigDecimal.ZERO;
            totalCost = totalCost.add(unitCost.multiply(take));
            if (lot.getExpiryDate() != null &&
                    (earliestExpiry == null || lot.getExpiryDate() < earliestExpiry)) {
                earliestExpiry = lot.getExpiryDate();
            }
            lot.setQuantity(lot.getQuantity().subtract(take));
            if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) lot.setIsActive(false);
            lot.setUpdatedAt(now);
            factoryStockRepo.save(lot);
            remaining = remaining.subtract(take);
        }
        BigDecimal avg = qty.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(qty, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new Deducted(totalCost.setScale(2, RoundingMode.HALF_UP), avg, earliestExpiry);
    }

    /** Cộng vào kho bán/trung chuyển (Ingredient) — giữ HSD + giá vốn. */
    private void addToWarehouse(Warehouse wh, String materialName, String unit,
                                BigDecimal qty, Deducted d, long now) {
        Ingredient ing = findIngredientInWarehouse(wh.getId(), materialName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kho \"" + wh.getName() + "\" chưa có nguyên liệu \"" + materialName + "\""));

        LocalDate expiry = d.earliestExpiry() != null
                ? Instant.ofEpochMilli(d.earliestExpiry()).atZone(VN).toLocalDate() : null;
        BigDecimal cost = d.avgUnitCost() != null ? d.avgUnitCost() : BigDecimal.ZERO;

        // MỖI LẦN CHUYỂN KHO = MỘT LÔ MỚI ở kho đích.
        //
        //   Bản cũ tìm lô cùng HSD + giá vốn để cộng dồn. Hai vấn đề:
        //     · Bảng ingredient_expiry không có ràng buộc duy nhất trên bộ cột đó,
        //       nên khi đã tồn tại 2 dòng trùng thì truy vấn trả về 2 kết quả và
        //       cả giao dịch chuyển kho bị huỷ.
        //     · Gộp vào lô cũ làm mất dấu vết đợt chuyển: không truy được lô nào
        //       về từ lần chuyển nào, trong khi phiếu chuyển kho lại cần đối chiếu.
        //
        //   Hai nhánh đích còn lại (kho nguyên liệu xưởng, kho thành phẩm xưởng)
        //   vốn đã luôn tạo lô mới — nay ba nhánh thống nhất một cách làm.
        //
        //   Đổi lại số dòng ingredient_expiry tăng theo mỗi lần chuyển. Chấp nhận
        //   được: FIFO đọc theo HSD rồi tới id nên nhiều lô không sai thứ tự xuất,
        //   và lô hết hàng vẫn được giữ lại (quantity = 0) chứ không xoá.
        ingredientExpiryRepo.save(IngredientExpiry.builder()
                .warehouse(wh).ingredientId(ing.getId())
                .expiryDate(expiry).costPrice(cost).quantity(qty)
                .createdAt(now).updatedAt(now).build());

        // Tồn tổng + giá trị vốn
        IngredientStock stock = ingredientStockRepo
                .findByIngredientIdAndWarehouseId(ing.getId(), wh.getId())
                .orElseGet(() -> IngredientStock.builder()
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit())
                        .warehouse(wh).stockQuantity(BigDecimal.ZERO).build());
        stock.setStockQuantity(stock.getStockQuantity().add(qty));
        BigDecimal curVal = stock.getTotalCostValue() != null ? stock.getTotalCostValue() : BigDecimal.ZERO;
        stock.setTotalCostValue(curVal.add(d.totalCost() != null ? d.totalCost() : BigDecimal.ZERO));
        stock.setUpdatedAt(now);
        ingredientStockRepo.save(stock);
    }

    /** Cộng vào kho nguyên liệu xưởng đích — tạo lô mới, giữ HSD + giá vốn. */
    private void addToFactoryMaterial(ProductionFactory factory, String materialName, String unit,
                                      BigDecimal qty, Deducted d, long now) {
        factoryStockRepo.save(FactoryMaterialStock.builder()
                .materialName(materialName).unit(unit)
                .quantity(qty).initialQuantity(qty)
                .expiryDate(d.earliestExpiry())
                .unitCost(d.avgUnitCost() != null ? d.avgUnitCost() : BigDecimal.ZERO)
                .productionFactory(factory)
                .isActive(true)
                .createdAt(now).updatedAt(now)
                .build());
    }

    /** Cộng vào kho thành phẩm xưởng đích — tạo lô mới. */
    private void addToFinishedGoods(ProductionFactory factory, String productName, String unit,
                                    BigDecimal qty, Deducted d, long now) {
        BigDecimal unitCost = d.avgUnitCost() != null ? d.avgUnitCost() : BigDecimal.ZERO;
        finishedGoodsStockRepo.save(FinishedGoodsStock.builder()
                .productName(productName).unit(unit)
                .quantity(qty).initialQuantity(qty)
                .unitCost(unitCost)
                .totalCost(d.totalCost())
                .expiryDate(d.earliestExpiry())
                .factoryId(factory.getId())
                .factoryNameSnapshot(factory.getName())
                .isActive(true)
                .build());
    }

    private Optional<Ingredient> findIngredientInWarehouse(Long warehouseId, String name) {
        if (name == null) return Optional.empty();
        String key = name.trim().toLowerCase();
        return ingredientWarehouseRepo.findIngredientIdsByWarehouseId(warehouseId).stream()
                .map(ingredientRepo::findById).filter(Optional::isPresent).map(Optional::get)
                .filter(i -> Boolean.TRUE.equals(i.getIsActive()))
                .filter(i -> i.getName() != null && i.getName().trim().toLowerCase().equals(key))
                .findFirst();
    }

    private FactoryStockNote baseNote(FactoryStockNote.NoteType type, ProductionFactory factory,
                                      User actor, long now) {
        return FactoryStockNote.builder()
                .type(type)
                .factory(factory)
                .factoryName(factory.getName())
                .createdBy(actor)
                .createdByName(actor.getFullName())
                .createdAt(now)
                .lines(new ArrayList<>())
                .documentImages("[]")
                .totalCostValue(BigDecimal.ZERO)
                .build();
    }

    private FactoryStockNoteLine buildLine(FactoryStockNote note, String name, String unit,
                                           BigDecimal qty, BigDecimal unitCost, Long expiry) {
        return FactoryStockNoteLine.builder()
                .note(note).materialName(name).unit(unit).quantity(qty)
                .unitCost(unitCost != null ? unitCost : BigDecimal.ZERO)
                .expiryDate(expiry).build();
    }

    private String genCode(String prefix, long now) {
        String day = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.ofEpochMilli(now).atZone(VN));
        long seq = noteRepo.countByNoteCodeStartingWith(prefix + "-" + day) + 1;
        return String.format("%s-%s-%04d", prefix, day, seq);
    }

    private User getUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private String[] parseKey(String key) {
        String[] parts = key.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("Khoá kho đích không hợp lệ: " + key);
        return parts;
    }

    private String writeImages(List<String> images) {
        if (images == null || images.isEmpty()) return "[]";
        try { return objectMapper.writeValueAsString(images); } catch (Exception e) { return "[]"; }
    }

    private List<String> readImages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String typeLabel(FactoryStockNote.NoteType t) {
        return switch (t) {
            case IMPORT -> "Nhập kho";
            case EXPORT -> "Xuất kho";
            case TRANSFER_OUT -> "Chuyển đi";
            case TRANSFER_IN -> "Chuyển đến";
        };
    }

    private String targetTypeLabel(FactoryStockNote.TargetKind k) {
        if (k == null) return null;
        return switch (k) {
            case WAREHOUSE -> "Kho hàng";
            case FACTORY_MATERIAL -> "Kho nguyên liệu xưởng";
            case FACTORY_FINISHED -> "Kho xưởng";
        };
    }

    private FactoryStockNoteDto toNoteDto(FactoryStockNote n) {
        return FactoryStockNoteDto.builder()
                .id(n.getId())
                .noteCode(n.getNoteCode())
                .type(n.getType().name())
                .typeLabel(typeLabel(n.getType()))
                .factoryId(n.getFactory() != null ? n.getFactory().getId() : null)
                .factoryName(n.getFactoryName())
                .targetName(n.getTargetName())
                .targetTypeLabel(targetTypeLabel(n.getTargetKind()))
                .reason(n.getReason())
                .documentImages(readImages(n.getDocumentImages()))
                .createdByName(n.getCreatedByName())
                .createdAt(n.getCreatedAt())
                .lines(n.getLines().stream().map(l -> FactoryStockNoteDto.LineDto.builder()
                        .materialName(l.getMaterialName())
                        .unit(l.getUnit())
                        .quantity(l.getQuantity())
                        .expiryDate(l.getExpiryDate())
                        .build()).collect(Collectors.toList()))
                .build();
    }
}
