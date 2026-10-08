package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.materialprice.MaterialPriceTrackingDtos.*;
import com.nhatnam.server.entity.FactoryMaterial;
import com.nhatnam.server.entity.MaterialPriceEntry;
import com.nhatnam.server.entity.VendorExpenseCategory;
import com.nhatnam.server.repository.FactoryMaterialRepository;
import com.nhatnam.server.repository.MaterialPriceEntryRepository;
import com.nhatnam.server.repository.VendorExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaterialPriceTrackingService {

    private final MaterialPriceEntryRepository priceEntryRepo;
    private final FactoryMaterialRepository factoryMaterialRepo;
    private final VendorExpenseCategoryRepository categoryRepo;

    /**
     * Danh sách nguyên liệu sản xuất (FactoryMaterial) + sản phẩm đồ dùng tiêu hao
     * từ danh mục khoản chi có type = CONSUMABLE.
     */
    public List<MaterialItemDto> listMaterials() {
        List<MaterialItemDto> result = new ArrayList<>();

        // 1) Nguyên liệu sản xuất — từ bảng FactoryMaterial
        List<FactoryMaterial> materials = factoryMaterialRepo.findByIsActiveTrueOrderByNameAsc();
        for (FactoryMaterial m : materials) {
            result.add(MaterialItemDto.builder()
                    .id(m.getId())
                    .name(m.getName())
                    .unit(m.getUnit())
                    .type("MATERIAL")
                    .build());
        }

        // 2) Sản phẩm từ danh mục khoản chi có type = CONSUMABLE (đồ dùng tiêu hao)
        List<VendorExpenseCategory> consumables = categoryRepo.findByActiveTrueOrderByNameAsc()
                .stream()
                .filter(c -> c.getCategoryKind() == VendorExpenseCategory.CategoryKind.CONSUMABLE)
                .collect(Collectors.toList());
        for (VendorExpenseCategory cat : consumables) {
            result.add(MaterialItemDto.builder()
                    .id(cat.getId())
                    .name(cat.getName())
                    .unit(cat.getUnit())
                    .type("CONSUMABLE")
                    .build());
        }

        return result;
    }

    /**
     * Dữ liệu chart cho 1 nguyên liệu — trả tất cả data points.
     */
    public PriceChartResponse getChartData(String materialName) {
        List<MaterialPriceEntry> entries = priceEntryRepo.findByMaterialNameOrderByCreatedAtAsc(materialName);

        String unit = entries.isEmpty() ? "" : entries.get(0).getUnit();

        List<PricePointDto> points = entries.stream().map(e -> PricePointDto.builder()
                .id(e.getId())
                .materialName(e.getMaterialName())
                .unit(e.getUnit())
                .unitPrice(e.getUnitPrice())
                .quantity(e.getQuantity())
                .totalAmount(e.getTotalAmount())
                .supplierName(e.getSupplierName())
                .createdAt(e.getCreatedAt())
                .build()
        ).collect(Collectors.toList());

        return PriceChartResponse.builder()
                .materialName(materialName)
                .unit(unit)
                .points(points)
                .build();
    }

    /**
     * Thêm giá mới.
     * Option 1: quantity + totalAmount → unitPrice = totalAmount / quantity
     * Option 2: unitPrice trực tiếp
     */
    @Transactional
    public PricePointDto addEntry(CreatePriceEntryRequest req, String updatedByName) {
        if (req.getMaterialName() == null || req.getMaterialName().isBlank()) {
            throw new BusinessException("Tên nguyên liệu không được để trống");
        }
        if (req.getUnit() == null || req.getUnit().isBlank()) {
            throw new BusinessException("Đơn vị tính không được để trống");
        }

        BigDecimal unitPrice;
        BigDecimal quantity = req.getQuantity();
        BigDecimal totalAmount = req.getTotalAmount();

        if (req.getUnitPrice() != null && req.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            // Option 2: nhập đơn giá trực tiếp
            unitPrice = req.getUnitPrice();
        } else if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0
                && totalAmount != null && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            // Option 1: tính từ số lượng + tổng tiền
            unitPrice = totalAmount.divide(quantity, 4, RoundingMode.HALF_UP);
        } else {
            throw new BusinessException("Phải nhập đơn giá hoặc (số lượng + tổng tiền)");
        }

        MaterialPriceEntry entry = MaterialPriceEntry.builder()
                .materialName(req.getMaterialName().trim())
                .unit(req.getUnit().trim())
                .unitPrice(unitPrice)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .supplierName(req.getSupplierName() != null ? req.getSupplierName().trim() : null)
                .updatedByName(updatedByName)
                .entryType(req.getEntryType() != null ? req.getEntryType() : "MATERIAL")
                .build();

        entry = priceEntryRepo.save(entry);

        log.info("[MATERIAL_PRICE] Added entry: material={} price={} by={}",
                entry.getMaterialName(), entry.getUnitPrice(), updatedByName);

        return PricePointDto.builder()
                .id(entry.getId())
                .materialName(entry.getMaterialName())
                .unit(entry.getUnit())
                .unitPrice(entry.getUnitPrice())
                .quantity(entry.getQuantity())
                .totalAmount(entry.getTotalAmount())
                .supplierName(entry.getSupplierName())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}