package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/owner/factory/materials")
@RequiredArgsConstructor
public class FactoryMaterialController {

    private final ProductionService productionService;

    @GetMapping
    public ApiResponse<List<FactoryMaterialDto>> list(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.ok(productionService.listMaterials(activeOnly));
    }

    @PostMapping
    public ApiResponse<FactoryMaterialDto> create(@RequestBody SaveFactoryMaterialRequest req) {
        return ApiResponse.ok(productionService.saveMaterial(null, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<FactoryMaterialDto> update(@PathVariable Long id,
                                                   @RequestBody SaveFactoryMaterialRequest req) {
        return ApiResponse.ok(productionService.saveMaterial(id, req));
    }

    @PatchMapping("/{id}/toggle")
    public ApiResponse<Void> toggle(@PathVariable Long id, @RequestParam boolean active) {
        productionService.toggleMaterial(id, active);
        return ApiResponse.ok(null);
    }
}
