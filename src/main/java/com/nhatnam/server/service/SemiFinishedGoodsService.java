package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.production.SemiFinishedGoodsDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kho bán thành phẩm + Kho Scrap của xưởng, cộng toàn bộ quy trình "đóng gói &
 * hao hụt" (4 bước):
 *  1. completeBatch → tách đạt (Kho bán TP) + lỗi (Kho Scrap), theo batch.
 *  2. createTransferNote → lập phiếu chuyển kho bán TP → kho TP, trừ FIFO theo
 *     batch, có thể gồm nhiều sản phẩm/nhiều batch nguồn mỗi sản phẩm.
 *  3. confirmReceive → kế toán kho xưởng (FACTORY_ACCOUNTANT) xác nhận nhận 1
 *     lần duy nhất cho cả phiếu: nhập số lượng đóng gói thực tế + tổng trọng
 *     lượng thực cân từng dòng → ghi vào FinishedGoodsStock (đơn vị đóng gói).
 *  4. Hao hụt = transferredQty − actualReceivedWeight (chỉ tính nếu > 0) → tự
 *     động lập PackagingLossReport cho dòng đó.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SemiFinishedGoodsService {

    private final SemiFinishedGoodsStockRepository semiRepo;
    private final ScrapStockRepository scrapRepo;
    private final SemiFinishedTransferNoteRepository transferRepo;
    private final PackagingLossReportRepository lossReportRepo;
    private final UserRepository userRepo;
    private final FinishedGoodsStockRepository finishedGoodsStockRepo;
    private final NotificationService notificationService;
    private final ProductionCostService productionCostService;

    // ─── Nhập kho khi hoàn thành mẻ (gọi từ ProductionModuleService.completeBatch) ──

    /** Nhập sản lượng ĐẠT chất lượng (kg) vào Kho bán thành phẩm. */
    public SemiFinishedGoodsStock receiveFromBatch(ProductionBatch batch, BigDecimal qty,
                                                   Long manufactureDate, Long expiryDate) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return null;

        Long factoryProductId = batch.getWorkOrder() != null && batch.getWorkOrder().getFactoryProduct() != null
                ? batch.getWorkOrder().getFactoryProduct().getId() : null;
        Long factoryId = batch.getWorkOrder() != null && batch.getWorkOrder().getProductionFactory() != null
                ? batch.getWorkOrder().getProductionFactory().getId() : null;
        String factoryName = batch.getWorkOrder() != null ? batch.getWorkOrder().getProductionFactoryName() : null;

        SemiFinishedGoodsStock lot = SemiFinishedGoodsStock.builder()
                .factoryProductId(factoryProductId)
                .productName(batch.getProductName())
                .unit(batch.getOutputUnit())
                .quantity(qty)
                .initialQuantity(qty)
                // Giá vốn tham chiếu (đ/kg) — sẽ được TÍNH LẠI khi kho thành phẩm nhận,
                // vì phiếu đặt hàng có thể được chốt giá sau khi mẻ đã xong.
                .unitCost(batch.getUnitCost())
                .manufactureDate(manufactureDate)
                .expiryDate(expiryDate)
                .batch(batch)
                .batchCodeSnapshot(batch.getBatchCode())
                .factoryId(factoryId)
                .factoryNameSnapshot(factoryName)
                .isActive(true)
                .build();
        return semiRepo.save(lot);
    }

    /** Ghi nhận sản lượng LỖI/huỷ (kg) vào Kho Scrap. */
    public ScrapStock receiveScrapFromBatch(ProductionBatch batch, BigDecimal qty, String reason) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return null;

        Long factoryProductId = batch.getWorkOrder() != null && batch.getWorkOrder().getFactoryProduct() != null
                ? batch.getWorkOrder().getFactoryProduct().getId() : null;
        Long factoryId = batch.getWorkOrder() != null && batch.getWorkOrder().getProductionFactory() != null
                ? batch.getWorkOrder().getProductionFactory().getId() : null;
        String factoryName = batch.getWorkOrder() != null ? batch.getWorkOrder().getProductionFactoryName() : null;

        ScrapStock lot = ScrapStock.builder()
                .factoryProductId(factoryProductId)
                .productName(batch.getProductName())
                .unit(batch.getOutputUnit())
                .quantity(qty)
                .reason(reason)
                .batch(batch)
                .batchCodeSnapshot(batch.getBatchCode())
                .factoryId(factoryId)
                .factoryNameSnapshot(factoryName)
                .build();
        return scrapRepo.save(lot);
    }

    // ─── Danh sách tổng hợp Kho bán thành phẩm (UI chính) ──────────────────────

    @Transactional(readOnly = true)
    public List<SemiFinishedSummaryDto> listSummary(String q) {
        return listSummary(q, null);
    }

    public List<SemiFinishedSummaryDto> listSummary(String q, Long factoryId) {
        List<SemiFinishedGoodsStock> lots = semiRepo.searchActiveLots(
                (q == null || q.isBlank()) ? null : q.trim());

        if (factoryId != null) {
            lots = lots.stream()
                    .filter(l -> factoryId.equals(l.getFactoryId()))
                    .collect(Collectors.toList());
        }

        Map<String, List<SemiFinishedGoodsStock>> byProduct = lots.stream()
                .collect(Collectors.groupingBy(SemiFinishedGoodsStock::getProductName, LinkedHashMap::new, Collectors.toList()));

        List<SemiFinishedSummaryDto> result = new ArrayList<>();
        for (Map.Entry<String, List<SemiFinishedGoodsStock>> e : byProduct.entrySet()) {
            List<SemiFinishedGoodsStock> productLots = e.getValue();
            BigDecimal total = productLots.stream().map(SemiFinishedGoodsStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(SemiFinishedSummaryDto.builder()
                    .productName(e.getKey())
                    .unit(productLots.get(0).getUnit())
                    .totalQuantity(total)
                    .lotCount(productLots.size())
                    .lots(productLots.stream().sorted(Comparator.comparing(SemiFinishedGoodsStock::getId))
                            .map(this::toLotDto).collect(Collectors.toList()))
                    .build());
        }
        return result;
    }

    private SemiFinishedLotDto toLotDto(SemiFinishedGoodsStock l) {
        return SemiFinishedLotDto.builder()
                .id(l.getId())
                .productName(l.getProductName())
                .unit(l.getUnit())
                .quantity(l.getQuantity())
                .initialQuantity(l.getInitialQuantity())
                .manufactureDate(l.getManufactureDate())
                .expiryDate(l.getExpiryDate())
                .batchCode(l.getBatchCodeSnapshot())
                .batchId(l.getBatch() != null ? l.getBatch().getId() : null)
                .factoryId(l.getFactoryId())
                .factoryName(l.getFactoryNameSnapshot())
                .createdAt(l.getCreatedAt())
                .build();
    }

    // ─── Danh sách Kho Scrap (UI phụ — xem hàng lỗi) ───────────────────────────

    @Transactional(readOnly = true)
    public List<ScrapLotDto> listScrap(int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return scrapRepo.findAllByOrderByCreatedAtDesc(pageable).stream()
                .map(this::toScrapDto).collect(Collectors.toList());
    }

    private ScrapLotDto toScrapDto(ScrapStock s) {
        return ScrapLotDto.builder()
                .id(s.getId())
                .productName(s.getProductName())
                .unit(s.getUnit())
                .quantity(s.getQuantity())
                .reason(s.getReason())
                .batchCode(s.getBatchCodeSnapshot())
                .batchId(s.getBatch() != null ? s.getBatch().getId() : null)
                .factoryId(s.getFactoryId())
                .factoryName(s.getFactoryNameSnapshot())
                .createdAt(s.getCreatedAt())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bước 2: Lập phiếu chuyển kho (Kho bán thành phẩm → Kho thành phẩm)
    // ═══════════════════════════════════════════════════════════════════════

    public TransferNoteDto createTransferNote(CreateTransferNoteRequest req, String username) {
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng thêm ít nhất 1 sản phẩm để chuyển kho");
        }
        User actor = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        SemiFinishedTransferNote note = SemiFinishedTransferNote.builder()
                .noteCode(generateTransferCode())
                .status(SemiFinishedTransferNote.Status.PENDING)
                .createdBy(actor)
                .createdByName(actor.getFullName())
                .notes(req.getNotes())
                .build();

        for (TransferLineRequest lineReq : req.getLines()) {
            if (lineReq.getProductName() == null || lineReq.getProductName().isBlank()) {
                throw new IllegalArgumentException("Tên sản phẩm không được để trống");
            }
            if (lineReq.getQuantity() == null || lineReq.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng chuyển của \"" + lineReq.getProductName() + "\" phải lớn hơn 0");
            }

            // Trừ FIFO từ Kho bán thành phẩm — ưu tiên batch cũ nhất trước (id ASC)
            List<SemiFinishedGoodsStock> availableLots =
                    semiRepo.findAvailableLotsByProductOrderByIdAsc(lineReq.getProductName());
            BigDecimal totalAvailable = availableLots.stream()
                    .map(SemiFinishedGoodsStock::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalAvailable.compareTo(lineReq.getQuantity()) < 0) {
                throw new IllegalStateException(String.format(
                        "Kho bán thành phẩm không đủ '%s': cần %s, tồn %s",
                        lineReq.getProductName(),
                        lineReq.getQuantity().stripTrailingZeros().toPlainString(),
                        totalAvailable.stripTrailingZeros().toPlainString()));
            }

            Long factoryProductId = availableLots.isEmpty() ? null : availableLots.get(0).getFactoryProductId();
            String unit = availableLots.isEmpty() ? "Kg" : availableLots.get(0).getUnit();

            SemiFinishedTransferNoteLine line = SemiFinishedTransferNoteLine.builder()
                    .transferNote(note)
                    .factoryProductId(factoryProductId)
                    .productName(lineReq.getProductName().trim())
                    .unit(unit)
                    .transferredQty(lineReq.getQuantity())
                    .sourceBatches(new ArrayList<>())
                    .build();

            BigDecimal remaining = lineReq.getQuantity();
            for (SemiFinishedGoodsStock lot : availableLots) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal take = remaining.min(lot.getQuantity());

                lot.setQuantity(lot.getQuantity().subtract(take));
                if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) lot.setIsActive(false);
                semiRepo.save(lot);

                line.getSourceBatches().add(SemiFinishedTransferSourceBatch.builder()
                        .transferLine(line)
                        .semiFinishedStock(lot)
                        .batch(lot.getBatch())
                        .batchCodeSnapshot(lot.getBatchCodeSnapshot())
                        .quantity(take)
                        .build());

                remaining = remaining.subtract(take);
            }

            note.getLines().add(line);
        }

        SemiFinishedTransferNote saved = transferRepo.save(note);

        // WS notify FACTORY_ACCOUNTANT — phiếu chuyển kho mới cần xác nhận nhận
        String productsSummary = saved.getLines().stream()
                .map(l -> l.getProductName() + " (" + l.getTransferredQty().stripTrailingZeros().toPlainString() + " " + l.getUnit() + ")")
                .collect(Collectors.joining(", "));
        String msg = "Phiếu chuyển kho " + saved.getNoteCode() + " vừa được lập: " + productsSummary + ". Vui lòng xác nhận nhận hàng.";
        String payload = "{\"transferNoteId\":" + saved.getId() + ",\"noteCode\":\"" + saved.getNoteCode() + "\"}";
        for (User u : userRepo.findByRolesContaining(com.nhatnam.server.enumtype.Role.FACTORY_ACCOUNTANT)) {
            notificationService.sendToUser(u, "FACTORY_ACCOUNTANT", "SEMI_FINISHED_TRANSFER_CREATED", msg, payload);
        }

        return toTransferDto(saved);
    }

    @Transactional(readOnly = true)
    public List<TransferNoteDto> listTransferNotes(String status, int page, int size) {
        SemiFinishedTransferNote.Status statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = SemiFinishedTransferNote.Status.valueOf(status.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return transferRepo.search(statusEnum, pageable).getContent().stream()
                .map(this::toTransferDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TransferNoteDto getTransferNote(Long id) {
        SemiFinishedTransferNote note = transferRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu chuyển kho"));
        return toTransferDto(note);
    }

    /** Lấy entity gốc (không qua DTO) — dùng cho việc xuất Excel (cần truy cập sourceBatches.batch.workOrder...) */
    @Transactional(readOnly = true)
    public SemiFinishedTransferNote getTransferNoteEntity(Long id) {
        return transferRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu chuyển kho"));
    }

    private TransferNoteDto toTransferDto(SemiFinishedTransferNote note) {
        // Derive factoryId from the first source lot's factory (all lots in a transfer
        // typically come from the same factory)
        Long factoryId = null;
        String factoryName = null;
        if (note.getLines() != null && !note.getLines().isEmpty()) {
            for (SemiFinishedTransferNoteLine line : note.getLines()) {
                if (line.getSourceBatches() != null) {
                    for (var sb : line.getSourceBatches()) {
                        if (sb.getSemiFinishedStock() != null && sb.getSemiFinishedStock().getFactoryId() != null) {
                            factoryId = sb.getSemiFinishedStock().getFactoryId();
                            factoryName = sb.getSemiFinishedStock().getFactoryNameSnapshot();
                            break;
                        }
                    }
                }
                if (factoryId != null) break;
            }
        }
        return TransferNoteDto.builder()
                .id(note.getId())
                .noteCode(note.getNoteCode())
                .status(note.getStatus().name())
                .createdByName(note.getCreatedByName())
                .createdAt(note.getCreatedAt())
                .notes(note.getNotes())
                .receivedByName(note.getReceivedByName())
                .receivedAt(note.getReceivedAt())
                .receiveNotes(note.getReceiveNotes())
                .factoryId(factoryId)
                .factoryName(factoryName)
                .lines(note.getLines().stream().map(this::toLineDto).collect(Collectors.toList()))
                .build();
    }

    private TransferNoteLineDto toLineDto(SemiFinishedTransferNoteLine l) {
        // Ước tính số gói dự kiến theo định lượng đóng gói chuẩn của recipe các batch nguồn
        // (chỉ tham khảo — lấy từ batch nguồn đầu tiên có cấu hình packagingQty)
        BigDecimal estimatedPackagedQty = null;
        BigDecimal packagingQty = l.getSourceBatches().stream()
                .map(sb -> sb.getBatch() != null && sb.getBatch().getRecipe() != null
                        ? sb.getBatch().getRecipe().getPackagingQty() : null)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (packagingQty != null && packagingQty.compareTo(BigDecimal.ZERO) > 0) {
            estimatedPackagedQty = l.getTransferredQty().divide(packagingQty, 0, RoundingMode.DOWN);
        }

        // Nếu dòng này có hao hụt, tìm ID biên bản hao hụt tương ứng (đã được lập tự động
        // ở confirmReceive) để FE có thể gọi export trực tiếp mà không cần tra cứu thêm
        Long lossReportId = null;
        if (l.getLossQty() != null && l.getLossQty().compareTo(BigDecimal.ZERO) > 0) {
            lossReportId = lossReportRepo.findByTransferLine_Id(l.getId()).map(PackagingLossReport::getId).orElse(null);
        }

        return TransferNoteLineDto.builder()
                .id(l.getId())
                .productName(l.getProductName())
                .unit(l.getUnit())
                .transferredQty(l.getTransferredQty())
                .packagedQty(l.getPackagedQty())
                .packagedUnit(l.getPackagedUnit())
                .actualReceivedWeight(l.getActualReceivedWeight())
                .lossQty(l.getLossQty())
                .lossReportId(lossReportId)
                .estimatedPackagedQty(estimatedPackagedQty)
                .sourceBatches(l.getSourceBatches().stream()
                        .map(sb -> TransferSourceBatchDto.builder()
                                .batchId(sb.getBatch() != null ? sb.getBatch().getId() : null)
                                .batchCode(sb.getBatchCodeSnapshot())
                                .quantity(sb.getQuantity())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bước 3 + 4: Kế toán kho xác nhận nhận → ghi kho TP + tự tính hao hụt
    // ═══════════════════════════════════════════════════════════════════════

    public TransferNoteDto confirmReceive(Long noteId, ReceiveTransferNoteRequest req, String username) {
        SemiFinishedTransferNote note = transferRepo.findById(noteId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu chuyển kho"));
        if (note.getStatus() != SemiFinishedTransferNote.Status.PENDING) {
            throw new IllegalStateException("Phiếu này đã được xác nhận nhận trước đó");
        }
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập số liệu nhận hàng cho từng dòng");
        }
        User actor = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        Map<Long, ReceiveTransferLineRequest> receiveMap = req.getLines().stream()
                .collect(Collectors.toMap(ReceiveTransferLineRequest::getLineId, r -> r));

        for (SemiFinishedTransferNoteLine line : note.getLines()) {
            ReceiveTransferLineRequest r = receiveMap.get(line.getId());
            if (r == null) {
                throw new IllegalArgumentException("Thiếu số liệu nhận hàng cho sản phẩm \"" + line.getProductName() + "\"");
            }
            if (r.getActualReceivedWeight() == null || r.getActualReceivedWeight().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Vui lòng nhập trọng lượng thực cân cho \"" + line.getProductName() + "\"");
            }

            // Định lượng đóng gói chuẩn (nếu recipe có cấu hình) — chỉ để snapshot đơn vị đóng gói
            String packagedUnit = line.getSourceBatches().stream()
                    .map(sb -> sb.getBatch() != null && sb.getBatch().getRecipe() != null
                            ? sb.getBatch().getRecipe().getPackagingUnit() : null)
                    .filter(Objects::nonNull).findFirst().orElse("túi");

            line.setPackagedQty(r.getPackagedQty());
            line.setPackagedUnit(packagedUnit);
            line.setActualReceivedWeight(r.getActualReceivedWeight());

            // Hao hụt = kg đã chuyển − kg thực cân khi nhận (chỉ ghi nhận nếu > 0)
            BigDecimal loss = line.getTransferredQty().subtract(r.getActualReceivedWeight());
            line.setLossQty(loss.compareTo(BigDecimal.ZERO) > 0 ? loss : BigDecimal.ZERO);

            // ── CHỐT GIÁ VỐN ──────────────────────────────────────────────────
            // Đây là mốc DUY NHẤT giá vốn được đóng băng, vì chỉ đến đây mới biết số
            // lượng CUỐI CÙNG dùng để bán/chuyển kho (đã trừ hao hụt đóng gói + cấp đông).
            //
            // Tổng giá vốn = Σ (kg lấy từ mỗi mẻ × giá vốn/kg của mẻ đó). Tính LẠI theo
            // giá lô nguyên liệu ở thời điểm này, phòng khi kế toán trưởng chốt giá phiếu
            // đặt hàng SAU khi mẻ đã sản xuất xong.
            //
            // Hao hụt KHÔNG bị mất tiền — nó dồn vào số kg còn lại, nên đơn giá tăng lên.
            // VD 5.868.000đ cho 58kg chuyển đi, nhận 57,85kg → 101.434,745… → 101.435 đ/kg.
            BigDecimal totalCost = BigDecimal.ZERO;
            for (SemiFinishedTransferSourceBatch sb : line.getSourceBatches()) {
                BigDecimal costPerKg = sb.getBatch() != null
                        ? productionCostService.batchUnitCostPerKg(sb.getBatch())
                        : BigDecimal.ZERO;
                totalCost = totalCost.add(costPerKg.multiply(sb.getQuantity()));
            }
            totalCost = totalCost.setScale(2, RoundingMode.HALF_UP);

            // Giá vốn / kg — làm tròn LÊN tới đồng
            BigDecimal unitCostPerKg = totalCost.divide(
                    r.getActualReceivedWeight(), 0, RoundingMode.CEILING);

            // Hạn sử dụng + ngày SX của lô thành phẩm: lấy theo lô bán thành phẩm có
            // HSD GẦN NHẤT trong các nguồn (an toàn nhất khi 1 lô gộp nhiều mẻ).
            SemiFinishedGoodsStock earliest = line.getSourceBatches().stream()
                    .map(SemiFinishedTransferSourceBatch::getSemiFinishedStock)
                    .filter(Objects::nonNull)
                    .filter(l -> l.getExpiryDate() != null)
                    .min(Comparator.comparing(SemiFinishedGoodsStock::getExpiryDate))
                    .orElse(null);

            // ── XƯỞNG SỞ HỮU LÔ THÀNH PHẨM ────────────────────────────────────
            // BUG FIX: trước đây lô FinishedGoodsStock tạo ở đây KHÔNG set factoryId
            // → page "Kho thành phẩm" (lọc theo xưởng đang chọn) luôn ra rỗng dù
            // phiếu chuyển kho đã xác nhận nhận. Lấy xưởng từ lô bán thành phẩm
            // nguồn (đã snapshot sẵn), fallback về WorkOrder của mẻ nguồn.
            Long lotFactoryId = null;
            String lotFactoryName = null;
            for (SemiFinishedTransferSourceBatch sb : line.getSourceBatches()) {
                SemiFinishedGoodsStock s = sb.getSemiFinishedStock();
                if (s != null && s.getFactoryId() != null) {
                    lotFactoryId = s.getFactoryId();
                    lotFactoryName = s.getFactoryNameSnapshot();
                    break;
                }
                if (sb.getBatch() != null && sb.getBatch().getWorkOrder() != null
                        && sb.getBatch().getWorkOrder().getProductionFactory() != null) {
                    lotFactoryId = sb.getBatch().getWorkOrder().getProductionFactory().getId();
                    lotFactoryName = sb.getBatch().getWorkOrder().getProductionFactoryName();
                    break;
                }
            }

            // Ghi vào Kho thành phẩm — đơn vị ĐÓNG GÓI (túi/hộp), số lượng = packagedQty
            if (r.getPackagedQty() != null && r.getPackagedQty().compareTo(BigDecimal.ZERO) > 0) {
                String sourceBatchCodes = line.getSourceBatches().stream()
                        .map(SemiFinishedTransferSourceBatch::getBatchCodeSnapshot)
                        .filter(Objects::nonNull).distinct().collect(Collectors.joining(", "));

                // Giá vốn / đơn vị đóng gói (đ/túi) — cũng làm tròn LÊN tới đồng
                BigDecimal unitCost = totalCost.divide(r.getPackagedQty(), 0, RoundingMode.CEILING);

                finishedGoodsStockRepo.save(FinishedGoodsStock.builder()
                        .factoryProductId(line.getFactoryProductId())
                        .productName(line.getProductName())
                        .unit(packagedUnit)
                        .quantity(r.getPackagedQty())
                        .initialQuantity(r.getPackagedQty())
                        .totalCost(totalCost)
                        .unitCost(unitCost)
                        .unitCostPerKg(unitCostPerKg)
                        .netWeightKg(r.getActualReceivedWeight())
                        .manufactureDate(earliest != null ? earliest.getManufactureDate() : null)
                        .expiryDate(earliest != null ? earliest.getExpiryDate() : null)
                        .batch(line.getSourceBatches().isEmpty() ? null : line.getSourceBatches().get(0).getBatch())
                        .batchCodeSnapshot(sourceBatchCodes)
                        .factoryId(lotFactoryId)
                        .factoryNameSnapshot(lotFactoryName)
                        .isActive(true)
                        .build());
            }

            // Lập Biên bản hao hụt đóng gói nếu có chênh lệch
            if (line.getLossQty() != null && line.getLossQty().compareTo(BigDecimal.ZERO) > 0) {
                String sourceBatchesSnapshot = line.getSourceBatches().stream()
                        .map(sb -> sb.getBatchCodeSnapshot() + " (" + sb.getQuantity().stripTrailingZeros().toPlainString() + " " + line.getUnit() + ")")
                        .collect(Collectors.joining(", "));
                PackagingLossReport report = PackagingLossReport.builder()
                        .reportCode(generateLossReportCode())
                        .transferNote(note)
                        .transferNoteCodeSnapshot(note.getNoteCode())
                        .transferLine(line)
                        .factoryProductId(line.getFactoryProductId())
                        .productName(line.getProductName())
                        .transferredQty(line.getTransferredQty())
                        .actualReceivedWeight(r.getActualReceivedWeight())
                        .lossQty(line.getLossQty())
                        .packagedQty(r.getPackagedQty())
                        .packagedUnit(packagedUnit)
                        .sourceBatchesSnapshot(sourceBatchesSnapshot)
                        .recordedBy(actor)
                        .recordedByName(actor.getFullName())
                        .build();
                lossReportRepo.save(report);

                String warnMsg = String.format("Hao hụt đóng gói: %s — hụt %s Kg (chuyển %s, thực nhận %s) — phiếu %s",
                        line.getProductName(), line.getLossQty().stripTrailingZeros().toPlainString(),
                        line.getTransferredQty().stripTrailingZeros().toPlainString(),
                        r.getActualReceivedWeight().stripTrailingZeros().toPlainString(), note.getNoteCode());
                notificationService.sendToRole("OWNER", "PACKAGING_LOSS_RECORDED", warnMsg,
                        "{\"transferNoteId\":" + note.getId() + ",\"lossReportId\":" + report.getId() + "}");
            }
        }

        note.setStatus(SemiFinishedTransferNote.Status.RECEIVED);
        note.setReceivedBy(actor);
        note.setReceivedByName(actor.getFullName());
        note.setReceivedAt(System.currentTimeMillis());
        note.setReceiveNotes(req.getNotes());

        return toTransferDto(transferRepo.save(note));
    }

    // ─── Lịch sử Biên bản hao hụt đóng gói ──────────────────────────────────

    @Transactional(readOnly = true)
    public List<PackagingLossReportDto> listLossReports(String productName, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return lossReportRepo.search(productName, pageable).getContent().stream()
                .map(this::toLossReportDto).collect(Collectors.toList());
    }

    /** Lấy entity gốc (không qua DTO) — dùng cho việc xuất Excel */
    @Transactional(readOnly = true)
    public PackagingLossReport getLossReportEntity(Long id) {
        return lossReportRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biên bản hao hụt"));
    }

    private PackagingLossReportDto toLossReportDto(PackagingLossReport r) {
        return PackagingLossReportDto.builder()
                .id(r.getId())
                .reportCode(r.getReportCode())
                .transferNoteId(r.getTransferNote() != null ? r.getTransferNote().getId() : null)
                .transferNoteCode(r.getTransferNoteCodeSnapshot())
                .productName(r.getProductName())
                .transferredQty(r.getTransferredQty())
                .actualReceivedWeight(r.getActualReceivedWeight())
                .lossQty(r.getLossQty())
                .packagedQty(r.getPackagedQty())
                .packagedUnit(r.getPackagedUnit())
                .sourceBatchesSnapshot(r.getSourceBatchesSnapshot())
                .recordedByName(r.getRecordedByName())
                .createdAt(r.getCreatedAt())
                .build();
    }

    // ─── Helpers: sinh mã phiếu/biên bản ────────────────────────────────────

    private String generateTransferCode() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        long count = transferRepo.countByNoteCodeStartingWith("TF-" + date);
        return String.format("TF-%s-%04d", date, count + 1);
    }

    private String generateLossReportCode() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        long count = lossReportRepo.countByReportCodeStartingWith("HH-" + date);
        return String.format("HH-%s-%04d", date, count + 1);
    }
}