package com.nhatnam.server.service;

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
    private final FactoryMaterialStockRepository stockRepo;
    private final MaterialVendorRepository materialVendorRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;

    // ── Factory Worker: tạo phiếu ────────────────────────────────────────────

    @Transactional
    public MaterialRequestDto create(CreateMaterialRequestRequest req, String username) {
        User creator = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        String code = generateCode();
        MaterialRequest mr = MaterialRequest.builder()
                .requestCode(code)
                .createdBy(creator)
                .createdByName(creator.getFullName())
                .requiredBy(req.getRequiredBy())
                .status(MaterialRequest.RequestStatus.NEW)
                .build();

        if (req.getItems() != null) {
            int order = 0;
            for (ItemRequest item : req.getItems()) {
                mr.getItems().add(MaterialRequestItem.builder()
                        .materialRequest(mr)
                        .materialName(item.getMaterialName())
                        .unit(item.getUnit())
                        .qtyRequested(item.getQtyRequested())
                        .sortOrder(item.getSortOrder() > 0 ? item.getSortOrder() : order++)
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
        mr.setEstimatedDelivery(req.getEstimatedDelivery());
        mr.setHandledBy(handler);
        mr.setHandledByName(handler.getFullName());

        // Thêm NCC
        if (req.getVendors() != null) {
            mr.getVendors().clear();
            int order = 0;
            for (VendorRequest vr : req.getVendors()) {
                MaterialVendor vendor = vr.getVendorId() != null
                        ? materialVendorRepo.findById(vr.getVendorId()).orElse(null)
                        : null;
                mr.getVendors().add(MaterialRequestVendor.builder()
                        .materialRequest(mr)
                        .vendor(vendor)
                        .vendorName(vr.getVendorName())
                        .contactPerson(vr.getContactPerson())
                        .contactPhone(vr.getContactPhone())
                        .sortOrder(vr.getSortOrder() > 0 ? vr.getSortOrder() : order++)
                        .build());
            }
        }

        MaterialRequest saved = requestRepo.save(mr);

        // WS: notify người tạo phiếu
        notificationService.sendToUser(
                mr.getCreatedBy(),
                "MATERIAL_REQUEST_ORDERED",
                "Phiếu " + mr.getRequestCode() + " đã được đặt hàng. Dự kiến giao: "
                        + (req.getEstimatedDelivery() != null
                        ? new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm")
                        .format(new Date(req.getEstimatedDelivery()))
                        : "chưa xác định"),
                "{\"requestId\":" + id + ",\"code\":\"" + mr.getRequestCode() + "\"}"
        );

        return toDto(saved, true);
    }

    // ── Factory Worker: xác nhận nhận hàng ──────────────────────────────────

    @Transactional
    public MaterialRequestDto confirmReceive(Long id, ReceiveRequest req, String username) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.ORDERED) {
            throw new IllegalStateException("Phiếu chưa được đặt hàng");
        }

        mr.setStatus(MaterialRequest.RequestStatus.RECEIVED);
        mr.setReceivedAt(System.currentTimeMillis());
        mr.setReceiveNotes(req.getNotes());

        // Cập nhật số thực nhận từng dòng + tạo stock
        Map<Long, ReceiveItemRequest> receiveMap = req.getItems() == null ? Map.of()
                : req.getItems().stream().collect(Collectors.toMap(ReceiveItemRequest::getItemId, i -> i));

        for (MaterialRequestItem item : mr.getItems()) {
            ReceiveItemRequest ri = receiveMap.get(item.getId());
            if (ri != null && ri.getQtyReceived() != null && ri.getQtyReceived().compareTo(BigDecimal.ZERO) > 0) {
                item.setQtyReceived(ri.getQtyReceived());
                item.setExpiryDate(ri.getExpiryDate());

                // Tạo lô trong kho
                stockRepo.save(FactoryMaterialStock.builder()
                        .materialName(item.getMaterialName())
                        .unit(item.getUnit())
                        .quantity(ri.getQtyReceived())
                        .initialQuantity(ri.getQtyReceived())
                        .expiryDate(ri.getExpiryDate())
                        .materialRequest(mr)
                        .materialRequestItem(item)
                        .build());
            }
        }

        MaterialRequest saved = requestRepo.save(mr);

        // WS: notify SUPER_ACCOUNTANT
        notificationService.sendToRole(
                Role.SUPER_ACCOUNTANT.name(),
                "MATERIAL_REQUEST_RECEIVED",
                "Phiếu " + mr.getRequestCode() + " đã được nhận hàng bởi " + mr.getCreatedByName(),
                "{\"requestId\":" + id + ",\"code\":\"" + mr.getRequestCode() + "\"}"
        );

        return toDto(saved, true);
    }

    // ── Super Accountant: hoàn thành phiếu ──────────────────────────────────

    @Transactional
    public MaterialRequestDto complete(Long id, String username) {
        MaterialRequest mr = findById(id);
        if (mr.getStatus() != MaterialRequest.RequestStatus.RECEIVED) {
            throw new IllegalStateException("Phiếu chưa được xác nhận nhận hàng");
        }
        mr.setStatus(MaterialRequest.RequestStatus.COMPLETED);
        mr.setCompletedAt(System.currentTimeMillis());
        return toDto(requestRepo.save(mr), true);
    }

    // ── List — luôn include items + vendors ───────────────────────────────────

    public Page<MaterialRequestDto> listForFactory(String username, String status,
                                                   Long dateFrom, Long dateTo,
                                                   String search, int page, int size) {
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<MaterialRequest> result = requestRepo.findByCreatedBy_IdAndFilters(
                user.getId(), status, dateFrom, dateTo, search, pageable);
        // include items + vendors để frontend hiển thị chi tiết ngay trên card
        return result.map(r -> toDto(r, true));
    }

    public Page<MaterialRequestDto> listForAccountant(String status, Long dateFrom, Long dateTo,
                                                      String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<MaterialRequest> result = requestRepo.findByFilters(status, dateFrom, dateTo, search, pageable);
        // include items + vendors để kế toán xem chi tiết ngay
        return result.map(r -> toDto(r, true));
    }

    public MaterialRequestDto getById(Long id) {
        return toDto(findById(id), true);
    }

    // ── Kho nguyên liệu ──────────────────────────────────────────────────────

    public List<FactoryStockSummaryDto> getStockSummary() {
        List<FactoryMaterialStock> stocks = stockRepo.findByIsActiveTrueOrderByCreatedAtAsc();
        long now = System.currentTimeMillis();
        long thirtyDays = 30L * 24 * 60 * 60 * 1000;

        // Group by materialName + unit
        Map<String, List<FactoryMaterialStock>> grouped = new LinkedHashMap<>();
        for (FactoryMaterialStock s : stocks) {
            if (s.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
            String key = s.getMaterialName() + "||" + s.getUnit();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        return grouped.entrySet().stream().map(e -> {
            String[] parts = e.getKey().split("\\|\\|");
            List<FactoryMaterialStock> lots = e.getValue();
            BigDecimal total = lots.stream().map(FactoryMaterialStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<FactoryStockLotDto> lotDtos = lots.stream().map(l -> FactoryStockLotDto.builder()
                    .id(l.getId())
                    .quantity(l.getQuantity())
                    .initialQuantity(l.getInitialQuantity())
                    .expiryDate(l.getExpiryDate())
                    .nearExpiry(l.getExpiryDate() != null
                            && l.getExpiryDate() - now <= thirtyDays
                            && l.getExpiryDate() > now)
                    .createdAt(l.getCreatedAt())
                    .build()).collect(Collectors.toList());

            return FactoryStockSummaryDto.builder()
                    .materialName(parts[0])
                    .unit(parts.length > 1 ? parts[1] : "")
                    .totalQty(total)
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

    private MaterialRequest findById(Long id) {
        return requestRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu: " + id));
    }

    private MaterialRequestDto toDto(MaterialRequest mr, boolean includeDetails) {
        MaterialRequestDto dto = MaterialRequestDto.builder()
                .id(mr.getId())
                .requestCode(mr.getRequestCode())
                .createdById(mr.getCreatedBy().getId())
                .createdByName(mr.getCreatedByName())
                .requiredBy(mr.getRequiredBy())
                .status(mr.getStatus().name())
                .orderedAt(mr.getOrderedAt())
                .estimatedDelivery(mr.getEstimatedDelivery())
                .handledByName(mr.getHandledByName())
                .receivedAt(mr.getReceivedAt())
                .receiveNotes(mr.getReceiveNotes())
                .completedAt(mr.getCompletedAt())
                .itemCount(mr.getItems().size())
                .createdAt(mr.getCreatedAt())
                .updatedAt(mr.getUpdatedAt())
                .build();

        if (includeDetails) {
            dto.setItems(mr.getItems().stream()
                    .sorted(Comparator.comparingInt(MaterialRequestItem::getSortOrder))
                    .map(i -> MaterialRequestDtos.MaterialRequestItemDto.builder()
                            .id(i.getId())
                            .materialName(i.getMaterialName())
                            .unit(i.getUnit())
                            .qtyRequested(i.getQtyRequested())
                            .qtyReceived(i.getQtyReceived())
                            .expiryDate(i.getExpiryDate())
                            .sortOrder(i.getSortOrder())
                            .build())
                    .collect(Collectors.toList()));

            dto.setVendors(mr.getVendors().stream()
                    .sorted(Comparator.comparingInt(MaterialRequestVendor::getSortOrder))
                    .map(v -> MaterialRequestDtos.MaterialRequestVendorDto.builder()
                            .id(v.getId())
                            .vendorId(v.getVendor() != null ? v.getVendor().getId() : null)
                            .vendorName(v.getVendorName())
                            .contactPerson(v.getContactPerson())
                            .contactPhone(v.getContactPhone())
                            .sortOrder(v.getSortOrder())
                            .build())
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private String generateCode() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        long count = requestRepo.countByRequestCodeStartingWith("MR-" + date);
        return String.format("MR-%s-%04d", date, count + 1);
    }
}