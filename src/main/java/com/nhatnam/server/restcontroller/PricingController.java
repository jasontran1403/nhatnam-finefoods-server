package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.pricing.PricingDtos.CostLabel;
import com.nhatnam.server.dto.pricing.PricingDtos.CreateCostLabelRequest;
import com.nhatnam.server.dto.pricing.PricingDtos.IngredientOption;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.PricingCostLabel;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.PricingCostLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API cho trang "Tính giá" của SUPER_ACCOUNTANT.
 * Đặt dưới /api/accountant/** để dùng lại cấu hình bảo mật sẵn có
 * (SUPER_ACCOUNTANT đã được phép truy cập path này).
 *
 * Việc tính toán giá bán được thực hiện ở frontend (chỉ hiển thị preview,
 * không lưu). Backend chỉ cung cấp: tìm nguyên liệu + quản lý nhãn chi phí dùng lại.
 */
@RestController
@RequestMapping("/api/accountant/pricing")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','OWNER','ADMIN')")
public class PricingController {

    private final IngredientRepository ingredientRepository;
    private final PricingCostLabelRepository costLabelRepository;

    /** Tìm nguyên liệu theo tên hoặc mã (dùng cho dropdown). Giới hạn 50 kết quả. */
    @GetMapping("/ingredients")
    public ApiResponse<List<IngredientOption>> searchIngredients(
            @RequestParam(required = false) String q) {
        String kw = q == null ? "" : q.trim().toLowerCase();
        List<IngredientOption> result = ingredientRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(i -> kw.isEmpty()
                        || (i.getName() != null && i.getName().toLowerCase().contains(kw))
                        || (i.getItemCode() != null && i.getItemCode().toLowerCase().contains(kw)))
                .limit(50)
                .map(this::toOption)
                .toList();
        return ApiResponse.ok(result);
    }

    /** Danh sách nhãn chi phí chung đã lưu (để chọn lại). */
    @GetMapping("/cost-labels")
    public ApiResponse<List<CostLabel>> listCostLabels() {
        List<CostLabel> labels = costLabelRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(l -> new CostLabel(l.getId(), l.getName()))
                .toList();
        return ApiResponse.ok(labels);
    }

    /** Tạo nhãn chi phí mới (dedupe theo tên, không phân biệt hoa thường). */
    @PostMapping("/cost-labels")
    public ApiResponse<CostLabel> createCostLabel(@RequestBody CreateCostLabelRequest req) {
        String name = req == null || req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            return ApiResponse.ok("Tên nhãn không được để trống", null);
        }
        PricingCostLabel entity = costLabelRepository.findByNameIgnoreCase(name)
                .map(existing -> {
                    if (!Boolean.TRUE.equals(existing.getIsActive())) {
                        existing.setIsActive(true);
                        return costLabelRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> costLabelRepository.save(PricingCostLabel.builder()
                        .name(name)
                        .isActive(true)
                        .createdAt(System.currentTimeMillis())
                        .build()));
        return ApiResponse.ok(new CostLabel(entity.getId(), entity.getName()));
    }

    private IngredientOption toOption(Ingredient i) {
        return new IngredientOption(i.getId(), i.getName(), i.getUnit(), i.getItemCode());
    }
}