package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.production.MixDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MIX GIA VỊ (Mục 4).
 *
 * <p>Kho nguyên liệu xưởng có nguyên liệu isMixable=true dùng làm SẢN PHẨM ĐẦU RA.
 * Người dùng chọn nhiều nguyên liệu đầu vào + số lượng, hệ thống kiểm tra tồn (FIFO
 * nhiều lô). Đủ tồn → nhập HSD + số lượng mix được → tạo 2 phiếu:
 *   1. Phiếu NHẬP kho (IMPORT): sản phẩm đầu ra — 1 LÔ MỚI hoàn toàn, HSD người nhập,
 *      giá vốn = tổng giá vốn đầu vào / số lượng đầu ra.
 *   2. Phiếu XUẤT kho (EXPORT) "Xuất kho sản xuất": các nguyên liệu đầu vào.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MixService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final FactoryMaterialRepository materialRepo;
    private final FactoryMaterialStockRepository stockRepo;
    private final FactoryStockNoteRepository noteRepo;
    private final ProductionFactoryRepository factoryRepo;
    private final UserRepository userRepo;

    // ═══════════════════════════ KIỂM TRA TỒN ═══════════════════════════════

    @Transactional(readOnly = true)
    public MixCheckResult check(MixCheckRequest req) {
        if (req.getFactoryId() == null) throw new IllegalArgumentException("Vui lòng chọn kho xưởng");
        if (req.getInputs() == null || req.getInputs().isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 nguyên liệu đầu vào");

        Map<String, List<FactoryMaterialStock>> lotsByKey = activeLotsByKey(req.getFactoryId());

        List<MixCheckResult.InputStatus> statuses = new ArrayList<>();
        boolean allEnough = true;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (MixCheckRequest.InputLine in : req.getInputs()) {
            if (in.getQuantity() == null || in.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("Số lượng phải > 0: " + in.getMaterialName());
            String key = key(in.getMaterialName(), in.getUnit());
            List<FactoryMaterialStock> lots = lotsByKey.getOrDefault(key, List.of());
            BigDecimal available = lots.stream().map(FactoryMaterialStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean enough = available.compareTo(in.getQuantity()) >= 0;
            if (!enough) allEnough = false;
            else totalCost = totalCost.add(previewCost(lots, in.getQuantity()));

            statuses.add(MixCheckResult.InputStatus.builder()
                    .materialName(in.getMaterialName()).unit(in.getUnit())
                    .required(in.getQuantity()).available(available).enough(enough)
                    .build());
        }

        return MixCheckResult.builder()
                .sufficient(allEnough).inputs(statuses)
                .totalInputCost(allEnough ? totalCost.setScale(2, RoundingMode.HALF_UP) : null)
                .build();
    }

    // ═══════════════════════════ THỰC HIỆN TRỘN ═════════════════════════════

    @Transactional
    public void execute(MixExecuteRequest req, String username) {
        if (req.getFactoryId() == null) throw new IllegalArgumentException("Vui lòng chọn kho xưởng");
        if (req.getOutputMaterialName() == null || req.getOutputMaterialName().isBlank())
            throw new IllegalArgumentException("Vui lòng chọn sản phẩm đầu ra");
        if (req.getOutputQuantity() == null || req.getOutputQuantity().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Số lượng mix được phải > 0");
        if (req.getInputs() == null || req.getInputs().isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 nguyên liệu đầu vào");

        ProductionFactory factory = factoryRepo.findById(req.getFactoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xưởng"));
        User actor = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        // Đầu ra phải là nguyên liệu isMixable=true
        FactoryMaterial output = materialRepo.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsMixable()))
                .filter(m -> m.getName() != null
                        && m.getName().trim().equalsIgnoreCase(req.getOutputMaterialName().trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sản phẩm đầu ra không hợp lệ (phải là nguyên liệu có thể mix)"));

        // Đầu vào KHÔNG được trùng sản phẩm đầu ra
        for (MixCheckRequest.InputLine in : req.getInputs()) {
            if (in.getMaterialName() != null
                    && in.getMaterialName().trim().equalsIgnoreCase(output.getName().trim())) {
                throw new IllegalArgumentException("Nguyên liệu đầu vào không được trùng sản phẩm đầu ra");
            }
        }

        long now = System.currentTimeMillis();
        Map<String, List<FactoryMaterialStock>> lotsByKey = activeLotsByKey(factory.getId());

        // 1) Trừ FIFO đầu vào + gom tổng giá vốn
        FactoryStockNote exportNote = baseNote(FactoryStockNote.NoteType.EXPORT, factory, actor, now);
        exportNote.setNoteCode(genCode("FEX", now));
        exportNote.setReason("Xuất kho sản xuất");

        BigDecimal totalInputCost = BigDecimal.ZERO;
        for (MixCheckRequest.InputLine in : req.getInputs()) {
            if (in.getQuantity() == null || in.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("Số lượng phải > 0: " + in.getMaterialName());
            String key = key(in.getMaterialName(), in.getUnit());
            List<FactoryMaterialStock> lots = new ArrayList<>(lotsByKey.getOrDefault(key, List.of()));
            BigDecimal available = lots.stream().map(FactoryMaterialStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (available.compareTo(in.getQuantity()) < 0) {
                throw new IllegalStateException(String.format("Thiếu nguyên liệu '%s': cần %s, tồn %s",
                        in.getMaterialName(), in.getQuantity().stripTrailingZeros().toPlainString(),
                        available.stripTrailingZeros().toPlainString()));
            }

            BigDecimal remaining = in.getQuantity();
            BigDecimal lineCost = BigDecimal.ZERO;
            for (FactoryMaterialStock lot : lots) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal take = remaining.min(lot.getQuantity());
                BigDecimal uc = lot.getUnitCost() != null ? lot.getUnitCost() : BigDecimal.ZERO;
                lineCost = lineCost.add(uc.multiply(take));
                lot.setQuantity(lot.getQuantity().subtract(take));
                if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) lot.setIsActive(false);
                lot.setUpdatedAt(now);
                stockRepo.save(lot);
                remaining = remaining.subtract(take);
            }
            totalInputCost = totalInputCost.add(lineCost);

            BigDecimal avgUnit = in.getQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? lineCost.divide(in.getQuantity(), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            exportNote.getLines().add(FactoryStockNoteLine.builder()
                    .note(exportNote).materialName(in.getMaterialName()).unit(in.getUnit())
                    .quantity(in.getQuantity()).unitCost(avgUnit).build());
        }
        exportNote.setTotalCostValue(totalInputCost.setScale(2, RoundingMode.HALF_UP));

        // 2) Tạo LÔ MỚI cho sản phẩm đầu ra — giá vốn = tổng giá vốn đầu vào / SL đầu ra
        BigDecimal outUnitCost = req.getOutputQuantity().compareTo(BigDecimal.ZERO) > 0
                ? totalInputCost.divide(req.getOutputQuantity(), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        FactoryMaterialStock newLot = FactoryMaterialStock.builder()
                .materialName(output.getName())
                .unit(req.getOutputUnit() != null ? req.getOutputUnit() : output.getUnit())
                .quantity(req.getOutputQuantity())
                .initialQuantity(req.getOutputQuantity())
                .expiryDate(req.getOutputExpiryDate())
                .unitCost(outUnitCost)
                .productionFactory(factory)
                .isActive(true)
                .createdAt(now).updatedAt(now)
                .build();
        stockRepo.save(newLot);

        // 3) Phiếu NHẬP kho sản phẩm đầu ra
        FactoryStockNote importNote = baseNote(FactoryStockNote.NoteType.IMPORT, factory, actor, now);
        importNote.setNoteCode(genCode("FIM", now));
        importNote.setReason("Nhập kho thành phẩm mix gia vị");
        importNote.setTotalCostValue(totalInputCost.setScale(2, RoundingMode.HALF_UP));
        importNote.getLines().add(FactoryStockNoteLine.builder()
                .note(importNote).materialName(output.getName())
                .unit(newLot.getUnit()).quantity(req.getOutputQuantity())
                .unitCost(outUnitCost).expiryDate(req.getOutputExpiryDate()).build());

        FactoryStockNote savedExport = noteRepo.save(exportNote);
        FactoryStockNote savedImport = noteRepo.save(importNote);
        savedExport.setLinkedNoteId(savedImport.getId());
        savedImport.setLinkedNoteId(savedExport.getId());
        noteRepo.save(savedExport);
        noteRepo.save(savedImport);
    }

    // ═══════════════════════════ HELPERS ════════════════════════════════════

    /** Lô còn hàng của xưởng, gom theo tên||đơn vị, sắp FIFO (ngày tạo tăng dần). */
    private Map<String, List<FactoryMaterialStock>> activeLotsByKey(Long factoryId) {
        Map<String, List<FactoryMaterialStock>> map = new LinkedHashMap<>();
        for (FactoryMaterialStock s : stockRepo
                .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(factoryId)) {
            if (s.getQuantity() == null || s.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
            map.computeIfAbsent(key(s.getMaterialName(), s.getUnit()), k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    private BigDecimal previewCost(List<FactoryMaterialStock> lots, BigDecimal qty) {
        BigDecimal remaining = qty, cost = BigDecimal.ZERO;
        for (FactoryMaterialStock lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(lot.getQuantity());
            BigDecimal uc = lot.getUnitCost() != null ? lot.getUnitCost() : BigDecimal.ZERO;
            cost = cost.add(uc.multiply(take));
            remaining = remaining.subtract(take);
        }
        return cost;
    }

    private FactoryStockNote baseNote(FactoryStockNote.NoteType type, ProductionFactory factory,
                                      User actor, long now) {
        return FactoryStockNote.builder()
                .type(type).factory(factory).factoryName(factory.getName())
                .createdBy(actor).createdByName(actor.getFullName())
                .createdAt(now).lines(new ArrayList<>())
                .documentImages("[]").totalCostValue(BigDecimal.ZERO)
                .build();
    }

    private String genCode(String prefix, long now) {
        String day = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.ofEpochMilli(now).atZone(VN));
        long seq = noteRepo.countByNoteCodeStartingWith(prefix + "-" + day) + 1;
        return String.format("%s-%s-%04d", prefix, day, seq);
    }

    private static String key(String name, String unit) {
        return (name == null ? "" : name.trim().toLowerCase()) + "||" + (unit == null ? "" : unit.trim());
    }

    /** Danh sách nguyên liệu isMixable=true của 1 xưởng — sản phẩm đầu ra khả dụng. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMixableOutputs(Long factoryId) {
        return materialRepo.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsMixable()))
                .filter(m -> factoryId == null || (m.getFactories() != null
                        && m.getFactories().stream().anyMatch(f -> f.getId().equals(factoryId))))
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", m.getName());
                    map.put("unit", m.getUnit());
                    return map;
                }).collect(Collectors.toList());
    }
}
