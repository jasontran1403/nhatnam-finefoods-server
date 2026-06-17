package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/owner/factory/products")
@RequiredArgsConstructor
public class FactoryProductController {

    private final ProductionService productionService;

    @GetMapping
    public ApiResponse<List<FactoryProductDto>> list(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.ok(productionService.listProducts(activeOnly));
    }

    @PostMapping
    public ApiResponse<FactoryProductDto> create(@RequestBody SaveFactoryProductRequest req) {
        return ApiResponse.ok(productionService.saveProduct(null, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<FactoryProductDto> update(@PathVariable Long id,
                                                  @RequestBody SaveFactoryProductRequest req) {
        return ApiResponse.ok(productionService.saveProduct(id, req));
    }
}
