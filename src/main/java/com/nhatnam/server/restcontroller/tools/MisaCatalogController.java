// PATH: src/main/java/com/nhatnam/server/restcontroller/tools/MisaCatalogController.java
package com.nhatnam.server.restcontroller.tools;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.entity.MisaProductCatalog;
import com.nhatnam.server.service.MisaProductCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tools/misa-catalog")
@RequiredArgsConstructor
public class MisaCatalogController {

    private final MisaProductCatalogService service;

    @GetMapping
    public ApiResponse<List<MisaProductCatalog>> list() {
        return ApiResponse.ok(service.findAll());
    }

    @PostMapping("/import")
    public ApiResponse<MisaProductCatalogService.ImportResult> importCatalog(
            @RequestBody List<Map<String, String>> rows) {
        return ApiResponse.ok(service.importCatalog(rows));
    }

    @PutMapping("/{id}")
    public ApiResponse<MisaProductCatalog> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        MisaProductCatalog entity = service.findAll().stream()
                .filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new RuntimeException("Không tìm thấy id=" + id));

        if (body.containsKey("kgPerUnit"))
            entity.setKgPerUnit(body.get("kgPerUnit") != null
                    ? Double.parseDouble(body.get("kgPerUnit").toString()) : null);
        if (body.containsKey("quyCach"))
            entity.setQuyCach(body.get("quyCach") != null
                    && !body.get("quyCach").toString().isBlank()
                    ? Double.parseDouble(body.get("quyCach").toString()) : null);
        if (body.containsKey("misaCategory"))
            entity.setMisaCategory((String) body.get("misaCategory"));
        if (body.containsKey("misaProductName"))
            entity.setMisaProductName((String) body.get("misaProductName"));
        if (body.containsKey("parseNote"))
            entity.setParseNote((String) body.get("parseNote"));
        if (body.containsKey("productName"))
            entity.setProductName((String) body.get("productName"));
        if (body.containsKey("kho"))
            entity.setKho((String) body.get("kho"));
        if (body.containsKey("tkKho"))
            entity.setTkKho((String) body.get("tkKho"));
        if (body.containsKey("tkGiaVon"))
            entity.setTkGiaVon((String) body.get("tkGiaVon"));

        return ApiResponse.ok(service.save(entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/all")
    public ResponseEntity<Void> deleteAll() {
        service.findAll().forEach(e -> service.deleteById(e.getId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/process-invoice")
    public ApiResponse<List<Map<String, Object>>> processInvoice(
            @RequestBody List<Map<String, Object>> invoiceRows) {
        return ApiResponse.ok(service.processInvoiceReport(invoiceRows));
    }
}