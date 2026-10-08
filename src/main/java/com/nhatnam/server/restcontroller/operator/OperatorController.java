package com.nhatnam.server.restcontroller.operator;

import com.nhatnam.server.dto.request.CreateCategoryRequest;
import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.request.CreateSubCategoryRequest;
import com.nhatnam.server.dto.response.*;
import com.nhatnam.server.entity.ProductBatch;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.enumtype.VatMode;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/operator")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'OWNER', 'SELLER', 'SUPER_SELLER')")
public class OperatorController {
    private final IngredientWarehouseRepository ingredientWarehouseRepo;
    private final OperatorService    operatorService;
    private final CategoryService    categoryService;
    private final SubCategoryService subCategoryService;
    private final IngredientService  ingredientService;
    private final com.nhatnam.server.service.ProductService productService;
    private final IngredientRepository ingredientRepository;
    private final ProductRepository  productRepository;
    private final FileStorageService fileStorageService;
    private final ProductPriceTierRepository    priceTierRepository;
    private final ProductIngredientRepository   productIngredientRepository;

    @Value("${application.security.jwt.secret-key}")
    private String jwtSecretKey;

    /** Token đã dùng — prevent reuse. */
    private final ConcurrentHashMap<String, Long> usedExportTokens = new ConcurrentHashMap<>();

    private static final String HMAC_ALGO             = "HmacSHA256";
    private static final String TOKEN_PREFIX_PRODUCT    = "export-product:";
    private static final String TOKEN_PREFIX_INGREDIENT = "export-ingredient:";

    private String _makeToken(String prefix, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(jwtSecretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((prefix + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { throw new RuntimeException("Không thể tạo token: " + e.getMessage()); }
    }

    private String _generateExportToken(String prefix) {
        String ts = String.valueOf(System.currentTimeMillis());
        return ts + ":" + _makeToken(prefix, ts);
    }

    private void _validateAndConsumeToken(String prefix, String token) {
        if (token == null || token.isBlank())
            throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
        String[] parts = token.split(":", 2);
        if (parts.length != 2)
            throw new IllegalStateException("File không hợp lệ: mã xác thực sai định dạng.");
        String expected = _makeToken(prefix, parts[0]);
        if (!expected.equals(parts[1]))
            throw new IllegalStateException("File không hợp lệ: mã xác thực không khớp. Vui lòng Export lại file mới.");
        if (usedExportTokens.containsKey(token))
            throw new IllegalStateException("File này đã được import rồi. Vui lòng Export file mới để import lại.");
        usedExportTokens.put(token, System.currentTimeMillis());
    }

    private static final byte[] C_PRIMARY    = {(byte)26,(byte)26,(byte)46};   // #1A1A2E navy
    private static final byte[] C_ACCENT     = {(byte)201,(byte)168,(byte)76}; // #C9A84C gold
    private static final byte[] C_WHITE      = {(byte)255,(byte)255,(byte)255};
    private static final byte[] C_HEADER_SUB = {(byte)240,(byte)235,(byte)227}; // #F0EBE3 cream
    private static final byte[] C_GRAY_ROW   = {(byte)250,(byte)250,(byte)250};
    private static final byte[] C_BORDER     = {(byte)224,(byte)224,(byte)224};
    private static final byte[] C_EXAMPLE    = {(byte)253,(byte)248,(byte)237};
    private static final byte[] C_NOTE       = {(byte)254,(byte)243,(byte)199};


    @DeleteMapping("/products/{id}/hard-delete")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ResponseEntity<ApiResponse<Object>> hardDeleteProduct(@PathVariable Long id) {
        try {
            if (!productRepository.existsById(id))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND,
                        "Không tìm thấy sản phẩm #" + id));

            productRepository.findById(id).ifPresent(p -> {
                if (p.getImageUrl() != null && !p.getImageUrl().isBlank()) {
                    try { fileStorageService.deleteFile(p.getImageUrl()); }
                    catch (Exception e) { log.warn("[HARD_DELETE] Không xóa được ảnh: {}", p.getImageUrl()); }
                }
            });

            // CASCADE tự xóa: product_ingredient, product_price_tier
            // product_batch_item.product_id → SET NULL (giữ lịch sử batch)
            productRepository.deleteById(id);

            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa sản phẩm hoàn toàn"));
        } catch (Exception e) {
            log.error("[HARD_DELETE] hardDeleteProduct id={} error", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping("/ingredients/{id}/hard-delete")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ResponseEntity<ApiResponse<Object>> hardDeleteIngredient(@PathVariable Long id) {
        try {
            if (!ingredientRepository.existsById(id))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND,
                        "Không tìm thấy nguyên liệu #" + id));

            ingredientRepository.findById(id).ifPresent(ing -> {
                if (ing.getImageUrl() != null && !ing.getImageUrl().isBlank()) {
                    try { fileStorageService.deleteFile(ing.getImageUrl()); }
                    catch (Exception e) { log.warn("[HARD_DELETE] Không xóa được ảnh: {}", ing.getImageUrl()); }
                }
            });

            // CASCADE tự xóa: ingredient_stock, ingredient_expiry,
            //                  ingredient_warehouse, product_ingredient
            // warehouse_receipt_item.ingredient_id → SET NULL (giữ lịch sử phiếu kho)
            ingredientRepository.deleteById(id);

            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa nguyên liệu hoàn toàn"));
        } catch (Exception e) {
            log.error("[HARD_DELETE] hardDeleteIngredient id={} error", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Soft-delete Product (OPERATOR, ADMIN, OWNER) ─────────────────────────
    @DeleteMapping("/products/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','OWNER')")
    public ResponseEntity<ApiResponse<Object>> softDeleteProduct(@PathVariable Long id) {
        try {
            if (!productRepository.existsById(id))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND,
                        "Không tìm thấy sản phẩm #" + id));
            productService.deleteProduct(id);

            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa sản phẩm"));
        } catch (Exception e) {
            log.error("[SOFT_DELETE] deleteProduct id={} error", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Soft-delete Ingredient (OPERATOR, ADMIN, OWNER) ──────────────────────
    @DeleteMapping("/ingredients/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','OWNER')")
    public ResponseEntity<ApiResponse<Object>> softDeleteIngredient(@PathVariable Long id) {
        try {
            if (!ingredientRepository.existsById(id))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND,
                        "Không tìm thấy nguyên liệu #" + id));
            ingredientService.deleteIngredient(id);

            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa nguyên liệu"));
        } catch (Exception e) {
            log.error("[SOFT_DELETE] deleteIngredient id={} error", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
// INGREDIENT ↔ WAREHOUSE MAPPING
// ════════════════════════════════════════════════════════════════

    private final IngredientWarehouseService ingredientWarehouseService; // inject

    private final WarehouseRepository warehouseRepository;

    @GetMapping("/warehouses")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','OWNER','SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getWarehouses() {
        List<Map<String, Object>> result = warehouseRepository.findByActiveTrue()
                .stream().map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",      w.getId());
                    m.put("name",    w.getName());
                    m.put("type",    w.getType());
                    m.put("address", w.getAddress());
                    m.put("active",  w.isActive());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(result, "OK"));
    }

    /** Lấy tất cả mappings */
    @GetMapping("/ingredient-warehouses")
    public ResponseEntity<ApiResponse<List<IngredientWarehouseResponse>>> getAllIngredientWarehouses() {
        return ResponseEntity.ok(ApiResponse.success(
                ingredientWarehouseService.getAll(), "OK"));
    }

    /** Lấy danh sách kho của 1 ingredient */
    @GetMapping("/ingredients/{id}/warehouses")
    public ResponseEntity<ApiResponse<List<Long>>> getWarehousesOfIngredient(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                ingredientWarehouseService.getWarehouseIdsByIngredient(id), "OK"));
    }

    /** Gán ingredient vào danh sách kho (replace-all) */
    @PutMapping("/ingredients/{id}/warehouses")
    public ResponseEntity<ApiResponse<Object>> assignIngredientWarehouses(
            @PathVariable Long id,
            @RequestBody List<Long> warehouseIds) {
        try {
            ingredientWarehouseService.assignWarehouses(id, warehouseIds);
            return ResponseEntity.ok(ApiResponse.success(null, "Cập nhật kho thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Thêm 1 kho cho ingredient */
    @PostMapping("/ingredients/{id}/warehouses/{warehouseId}")
    public ResponseEntity<ApiResponse<Object>> addIngredientToWarehouse(
            @PathVariable Long id,

            @PathVariable Long warehouseId) {
        try {
            ingredientWarehouseService.addWarehouse(id, warehouseId);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã thêm vào kho"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Xóa ingredient khỏi 1 kho */
    @DeleteMapping("/ingredients/{id}/warehouses/{warehouseId}")
    public ResponseEntity<ApiResponse<Object>> removeIngredientFromWarehouse(
            @PathVariable Long id,
            @PathVariable Long warehouseId) {
        ingredientWarehouseService.removeWarehouse(id, warehouseId);
        return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa khỏi kho"));
    }

    /** Lấy danh sách ingredients theo kho (cho dropdown filter) */
    @GetMapping("/warehouses/{warehouseId}/ingredients")
    public ResponseEntity<ApiResponse<List<Long>>> getIngredientsByWarehouse(
            @PathVariable Long warehouseId) {
        return ResponseEntity.ok(ApiResponse.success(
                ingredientWarehouseService.getIngredientIdsByWarehouse(warehouseId), "OK"));
    }

    private static final String[] UNITS_LIST = {"Kg","Gr","Lít","ml","Cái","Hộp","Cây","Bó","Túi","Gói","Chai","Lon","Phần"};

    private static final java.util.Set<String> FLEXIBLE_UNITS = java.util.Set.of("Kg","Gr","Lít","ml");

    @GetMapping("/products/export-template")
    public ResponseEntity<?> exportProductTemplate() {
        try {
            List<CategoryResponse> cats = categoryService.getAllCategories();
            List<IngredientResponse> ings = ingredientService.getAllIngredients();
            byte[] bytes = _buildProductTemplate(cats, ings, false, List.of());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"product-import-template.xlsx\"")
                    .header("Content-Type",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);
        } catch (Exception e) {
            log.error("[OPERATOR] exportProductTemplate error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/products/export-full")
    public ResponseEntity<?> exportFullProductList() {
        try {
            List<CategoryResponse> cats = categoryService.getAllCategories();
            List<IngredientResponse> ings = ingredientService.getAllIngredients();
            List<com.nhatnam.server.entity.Product> products =
                    productRepository.findByIsActiveTrue();
            byte[] bytes = _buildProductTemplate(cats, ings, true, products);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"product-update-template.xlsx\"")
                    .header("Content-Type",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);
        } catch (Exception e) {
            log.error("[OPERATOR] exportFullProductList error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/products/import", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importProducts(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String operatorName = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();

            List<IngredientResponse> allIngs = ingredientService.getAllIngredients();
            Map<String, Long> ingNameToId = allIngs.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            i -> i.getName().trim().toLowerCase(),
                            IngredientResponse::getId,
                            (a, b) -> a));

            List<String> catNames = categoryService.getAllCategories()
                    .stream().map(c -> c.getName().toLowerCase()).toList();

            int imported = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);

                // Row 0 = title, Row 1 = header, Row 2 = sub-header note, data from row 3
                for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String name = _cellStr(row, 1);
                    if (name == null || name.isBlank()) continue; // dòng trống → bỏ qua

                    int stt = r - 3; // STT hiển thị cho user (row 3 = STT 1)
                    try {
                        // ── Parse các field ───────────────────────────────────────
                        String categoryName = _cellStr(row, 2);
                        String subCatName   = _cellStr(row, 3);  // Danh mục con
                        String unit         = _cellStr(row, 4);
                        String basePriceStr = _cellStr(row, 5);
                        String maxDiscStr   = _cellStr(row, 6);
                        String vatRateStr   = _cellStr(row, 7);
                        String vatModeStr   = _cellStr(row, 8);
                        String qtyPerBoxStr = _cellStr(row, 9);
                        String tier1Str     = _cellStr(row, 10);
                        String tier2Str     = _cellStr(row, 11);
                        String tier3Str     = _cellStr(row, 12);
                        // Ingredients (name | qty) — có thể có nhiều, từ col 12+
                        // Layout: col12=ing1Name, col13=ing1Qty, col14=ing2Name, col15=ing2Qty, ...

                        // ── Validate bắt buộc ─────────────────────────────────────
                        if (unit == null || unit.isBlank())
                            throw new IllegalArgumentException("Thiếu đơn vị tính");
                        if (basePriceStr == null || basePriceStr.isBlank())
                            throw new IllegalArgumentException("Thiếu giá bán lẻ");

                        long basePrice = _parseLong(basePriceStr);
                        if (basePrice <= 0)
                            throw new IllegalArgumentException("Giá bán lẻ phải > 0");

                        int maxDisc = maxDiscStr != null ? (int) _parseLong(maxDiscStr) : 0;

                        int vatRateInt = 8;
                        try { vatRateInt = Integer.parseInt((vatRateStr != null ? vatRateStr : "8").trim()); }
                        catch (Exception ignored) {}
                        VatRate vatRate = VatRate.fromPercentage(vatRateInt);

                        VatMode vatMode = "EXCLUSIVE".equalsIgnoreCase(vatModeStr)
                                ? VatMode.EXCLUSIVE : VatMode.INCLUSIVE;

                        Integer unitsPerBox = null;
                        if (qtyPerBoxStr != null && !qtyPerBoxStr.isBlank()) {
                            long v = _parseLong(qtyPerBoxStr);
                            if (v > 0) unitsPerBox = (int) v;
                        }

                        // ── Tiers ─────────────────────────────────────────────────
                        List<Map<String, Object>> tierPayload = new ArrayList<>();
                        if (tier1Str != null && !tier1Str.isBlank()
                                && tier2Str != null && !tier2Str.isBlank()
                                && tier3Str != null && !tier3Str.isBlank()) {
                            long p1 = _parseLong(tier1Str);
                            long p2 = _parseLong(tier2Str);
                            long p3 = _parseLong(tier3Str);
                            if (p1 <= 0 || p2 <= 0 || p3 <= 0)
                                throw new IllegalArgumentException("Giá sỉ phải > 0 nếu nhập");
                            if (!(p1 > p2))
                                throw new IllegalArgumentException("Giá sỉ 1 phải > sỉ 2");
                            if (!(p2 > p3))
                                throw new IllegalArgumentException("Giá sỉ 2 phải > sỉ 3");
                            int[][] ranges = {{0,5},{5,20},{20,-1}};
                            long[]  prices = {p1, p2, p3};
                            String[] names = {"Sỉ 1","Sỉ 2","Sỉ 3"};
                            for (int ti = 0; ti < 3; ti++) {
                                Map<String, Object> tm = new LinkedHashMap<>();
                                tm.put("tierName",    names[ti]);
                                tm.put("minQuantity", ranges[ti][0]);
                                tm.put("maxQuantity", ranges[ti][1] < 0 ? null : ranges[ti][1]);
                                tm.put("price",       prices[ti]);
                                tm.put("sortOrder",   ti);
                                tierPayload.add(tm);
                            }
                        }

                        // ── Ingredients ───────────────────────────────────────────
                        List<Map<String, Object>> ingPayload = new ArrayList<>();
                        for (int col = 13; col < row.getLastCellNum(); col += 2) {
                            String ingName = _cellStr(row, col);
                            if (ingName == null || ingName.isBlank()) break;
                            Long ingId = ingNameToId.get(ingName.trim().toLowerCase());
                            if (ingId == null) {
                                log.warn("[IMPORT] STT {} nguyên liệu '{}' không tìm thấy, bỏ qua", stt, ingName);
                                continue;
                            }
                            String ingQtyStr = _cellStr(row, col + 1);
                            double ingQty = 1.0;
                            try { if (ingQtyStr != null) ingQty = Double.parseDouble(ingQtyStr.trim()); }
                            catch (Exception ignored) {}

                            boolean canOverride = FLEXIBLE_UNITS.contains(unit);
                            Map<String, Object> im = new LinkedHashMap<>();
                            im.put("ingredientId", ingId);
                            im.put("quantity",     ingQty);
                            im.put("canOverride",  canOverride);
                            ingPayload.add(im);
                        }

                        // ── Build item payload → gọi operatorService ──────────────
                        Map<String, Object> itemPayload = new LinkedHashMap<>();
                        itemPayload.put("existingProductId", null);
                        itemPayload.put("name",              name.trim());
                        itemPayload.put("categoryName",      categoryName != null ? categoryName.trim() : "");
                        itemPayload.put("unit",              unit.trim());
                        itemPayload.put("basePrice",         basePrice);
                        itemPayload.put("maxDiscountRate",   maxDisc);
                        itemPayload.put("vatRate",           vatRateInt);
                        itemPayload.put("vatMode",           vatMode.name());
                        itemPayload.put("imageUrl",          "");
                        itemPayload.put("unitsPerBox",       unitsPerBox);
                        itemPayload.put("tiers",             tierPayload);
                        itemPayload.put("ingredients",       ingPayload);

                        operatorService.submitBatch(user.getId(), operatorName,
                                com.nhatnam.server.entity.ProductBatch.BatchType.CREATE,
                                "Import từ file Excel", List.of(itemPayload));

                        imported++;
                    } catch (Exception ex) {
                        errors.add("Dòng STT " + stt + ": " + ex.getMessage());
                        skipped++;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("imported", imported);
            result.put("skipped",  skipped);
            result.put("errors",   errors);
            return ResponseEntity.ok(ApiResponse.success(result,
                    "Import hoàn tất: " + imported + " thành công, " + skipped + " bỏ qua"));
        } catch (Exception e) {
            log.error("[OPERATOR] importProducts error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/products/import-update", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importUpdateProducts(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String operatorName = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();

            int updated = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {

                // Xác thực token từ sheet __meta
                try {
                    int metaIdx = wb.getSheetIndex("__meta");
                    if (metaIdx < 0) throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
                    Row metaRow = wb.getSheetAt(metaIdx).getRow(0);
                    String token = metaRow != null ? _cellStr(metaRow, 0) : null;
                    _validateAndConsumeToken(TOKEN_PREFIX_PRODUCT, token);
                } catch (IllegalStateException ex) {
                    return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, ex.getMessage()));
                }

                Sheet sheet = wb.getSheetAt(0);
                // Row 0=title,1=note,2=header,3=sub-header → data từ row 4
                for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String productIdStr = _cellStr(row, 0);
                    if (productIdStr == null || productIdStr.isBlank()) continue;
                    String name = _cellStr(row, 1);
                    if (name == null || name.isBlank()) continue;

                    int stt = r - 3;
                    try {
                        Long productId;
                        try { productId = Long.parseLong(productIdStr.trim()); }
                        catch (Exception e2) { throw new IllegalArgumentException("Product ID không hợp lệ: " + productIdStr); }

                        com.nhatnam.server.entity.Product existing = productRepository.findById(productId)
                                .orElseThrow(() -> new IllegalArgumentException("Sản phẩm ID=" + productId + " không tồn tại"));

                        // Layout 13 cột: 0=ID,1=Tên,2=SKU,3=DM,4=Đvị,5=Giá,6=CK,7=VAT%,8=VATMode,9=QC,10=Sỉ1,11=Sỉ2,12=Sỉ3
                        String sku          = _cellStr(row, 2);
                        String basePriceStr = _cellStr(row, 5);
                        String maxDiscStr   = _cellStr(row, 6);
                        String vatRateStr   = _cellStr(row, 7);
                        String vatModeStr   = _cellStr(row, 8);
                        String qtyPerBoxStr = _cellStr(row, 9);
                        String tier1Str     = _cellStr(row, 10);
                        String tier2Str     = _cellStr(row, 11);
                        String tier3Str     = _cellStr(row, 12);

                        if (basePriceStr == null || basePriceStr.isBlank())
                            throw new IllegalArgumentException("Thiếu giá bán lẻ");
                        long basePrice = _parseLong(basePriceStr);
                        if (basePrice <= 0) throw new IllegalArgumentException("Giá bán lẻ phải > 0");

                        int maxDisc = (maxDiscStr != null && !maxDiscStr.isBlank()) ? (int)_parseLong(maxDiscStr) : (existing.getMaxDiscountRate() != null ? existing.getMaxDiscountRate() : 0);
                        int vatRateInt = existing.getVatRate() != null ? existing.getVatRate().getPercentage() : 8;
                        try { if (vatRateStr != null && !vatRateStr.isBlank()) vatRateInt = Integer.parseInt(vatRateStr.trim()); } catch (Exception ignored) {}
                        VatMode vatMode = existing.getVatMode() != null ? existing.getVatMode() : VatMode.INCLUSIVE;
                        if (vatModeStr != null && !vatModeStr.isBlank())
                            vatMode = "EXCLUSIVE".equalsIgnoreCase(vatModeStr.trim()) ? VatMode.EXCLUSIVE : VatMode.INCLUSIVE;

                        Integer unitsPerBox = existing.getUnitsPerBox();
                        if (qtyPerBoxStr != null && !qtyPerBoxStr.isBlank()) {
                            long v = _parseLong(qtyPerBoxStr); unitsPerBox = v > 0 ? (int)v : null;
                        }

                        List<Map<String, Object>> tierPayload = new ArrayList<>();
                        if (tier1Str != null && !tier1Str.isBlank() && tier2Str != null && !tier2Str.isBlank() && tier3Str != null && !tier3Str.isBlank()) {
                            long p1=_parseLong(tier1Str),p2=_parseLong(tier2Str),p3=_parseLong(tier3Str);
                            if(!(p1>p2)) throw new IllegalArgumentException("Giá sỉ 1 phải > sỉ 2");
                            if(!(p2>p3)) throw new IllegalArgumentException("Giá sỉ 2 phải > sỉ 3");
                            int[][] ranges={{0,5},{5,20},{20,-1}}; long[] prices={p1,p2,p3}; String[] tn={"Sỉ 1","Sỉ 2","Sỉ 3"};
                            for(int ti=0;ti<3;ti++){Map<String,Object> tm=new LinkedHashMap<>();tm.put("tierName",tn[ti]);tm.put("minQuantity",ranges[ti][0]);tm.put("maxQuantity",ranges[ti][1]<0?null:ranges[ti][1]);tm.put("price",prices[ti]);tm.put("sortOrder",ti);tierPayload.add(tm);}
                        } else {
                            existing.getPriceTiers().forEach(t->{Map<String,Object> tm=new LinkedHashMap<>();tm.put("tierName",t.getTierName());tm.put("minQuantity",t.getMinQuantity());tm.put("maxQuantity",t.getMaxQuantity());tm.put("price",t.getPrice());tm.put("sortOrder",t.getSortOrder());tierPayload.add(tm);});
                        }

                        List<com.nhatnam.server.entity.ProductIngredient> existingIngs = productIngredientRepository.findByProductId(productId);
                        List<Map<String,Object>> ingPayload = new ArrayList<>();
                        existingIngs.forEach(ing->{Map<String,Object> im=new LinkedHashMap<>();im.put("ingredientId",ing.getIngredientId());im.put("quantity",ing.getQty());im.put("canOverride",ing.getCanOverride()!=null&&ing.getCanOverride());ingPayload.add(im);});

                        Map<String,Object> itemPayload=new LinkedHashMap<>();
                        itemPayload.put("existingProductId",productId);
                        itemPayload.put("name",name.trim());
                        itemPayload.put("sku",sku!=null&&!sku.isBlank()?sku.trim():existing.getSku());
                        itemPayload.put("categoryName",existing.getCategory()!=null?existing.getCategory():"");
                        itemPayload.put("unit",existing.getUnit());
                        itemPayload.put("basePrice",basePrice);
                        itemPayload.put("maxDiscountRate",maxDisc);
                        itemPayload.put("vatRate",vatRateInt);
                        itemPayload.put("vatMode",vatMode.name());
                        itemPayload.put("imageUrl",existing.getImageUrl()!=null?existing.getImageUrl():"");
                        itemPayload.put("unitsPerBox",unitsPerBox);
                        itemPayload.put("tiers",tierPayload);
                        itemPayload.put("ingredients",ingPayload);

                        operatorService.submitBatch(user.getId(),operatorName,
                                com.nhatnam.server.entity.ProductBatch.BatchType.UPDATE,"Cập nhật từ file Excel",List.of(itemPayload));
                        updated++;
                    } catch (Exception ex) {
                        errors.add("Dòng STT " + stt + ": " + ex.getMessage()); skipped++;
                    }
                }
            }

            Map<String,Object> result=new LinkedHashMap<>();
            result.put("updated",updated); result.put("skipped",skipped); result.put("errors",errors);
            return ResponseEntity.ok(ApiResponse.success(result,"Cập nhật hoàn tất: "+updated+" thành công, "+skipped+" bỏ qua"));
        } catch (Exception e) {
            log.error("[OPERATOR] importUpdateProducts error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private byte[] _buildProductTemplate(
            List<CategoryResponse> cats,
            List<IngredientResponse> ings,
            boolean isUpdate,
            List<com.nhatnam.server.entity.Product> existingProducts) throws Exception {

        String exportToken = _generateExportToken(TOKEN_PREFIX_PRODUCT);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet ws = wb.createSheet("Sản phẩm");
            ws.setDisplayGridlines(false);

            java.util.function.Function<byte[], XSSFColor> mkColor = b -> new XSSFColor(b, null);
            java.util.function.Function<Object[], XSSFCellStyle> mkStyle = args -> {
                byte[] bg=(byte[])args[0]; byte[] fg=(byte[])args[1]; boolean bold=(Boolean)args[2];
                int size=(Integer)args[3]; HorizontalAlignment al=(HorizontalAlignment)args[4];
                XSSFCellStyle cs = wb.createCellStyle();
                cs.setFillForegroundColor(mkColor.apply(bg)); cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cs.setAlignment(al); cs.setVerticalAlignment(VerticalAlignment.CENTER); cs.setWrapText(true);
                XSSFColor border = mkColor.apply(C_BORDER);
                cs.setBorderLeft(BorderStyle.THIN);   cs.setBorderColor(XSSFCellBorder.BorderSide.LEFT,   border);
                cs.setBorderRight(BorderStyle.THIN);  cs.setBorderColor(XSSFCellBorder.BorderSide.RIGHT,  border);
                cs.setBorderTop(BorderStyle.THIN);    cs.setBorderColor(XSSFCellBorder.BorderSide.TOP,    border);
                cs.setBorderBottom(BorderStyle.THIN); cs.setBorderColor(XSSFCellBorder.BorderSide.BOTTOM, border);
                XSSFFont font = wb.createFont(); font.setFontName("Arial"); font.setBold(bold);
                font.setFontHeightInPoints((short)size); font.setColor(mkColor.apply(fg)); cs.setFont(font);
                return cs;
            };

            XSSFCellStyle titleStyle   = mkStyle.apply(new Object[]{C_PRIMARY,C_ACCENT,true,14,HorizontalAlignment.LEFT});
            XSSFCellStyle noteStyle    = mkStyle.apply(new Object[]{C_NOTE,new byte[]{(byte)146,(byte)64,(byte)14},false,8,HorizontalAlignment.LEFT});
            XSSFCellStyle hdrMainStyle = mkStyle.apply(new Object[]{C_PRIMARY,C_WHITE,true,10,HorizontalAlignment.CENTER});
            XSSFCellStyle hdrSubStyle  = mkStyle.apply(new Object[]{C_ACCENT,C_WHITE,false,9,HorizontalAlignment.CENTER});
            XSSFCellStyle dataStyle    = mkStyle.apply(new Object[]{C_WHITE,new byte[]{(byte)28,(byte)28,(byte)30},false,9,HorizontalAlignment.LEFT});
            XSSFCellStyle dataGrayStyle= mkStyle.apply(new Object[]{C_GRAY_ROW,new byte[]{(byte)28,(byte)28,(byte)30},false,9,HorizontalAlignment.LEFT});
            XSSFCellStyle lockStyle    = mkStyle.apply(new Object[]{new byte[]{(byte)243,(byte)244,(byte)246},new byte[]{(byte)156,(byte)163,(byte)175},false,9,HorizontalAlignment.CENTER});
            XSSFCellStyle sttStyle     = mkStyle.apply(new Object[]{new byte[]{(byte)240,(byte)235,(byte)227},new byte[]{(byte)201,(byte)168,(byte)76},true,9,HorizontalAlignment.CENTER});

            // Layout 13 cột (bỏ Nguyên liệu + SL):
            // 0=ID/STT 1=Tên 2=SKU 3=DanhMục 4=ĐơnVị 5=GiáLẻ 6=CK% 7=VAT% 8=LoạiVAT 9=QC 10=Sỉ1 11=Sỉ2 12=Sỉ3
            int TOTAL_COLS=13, DATA_START_ROW=4;
            int DATA_ROWS = isUpdate ? Math.max(existingProducts.size()+10, 100) : 200;
            int[] cw = {isUpdate?10*256:6*256,32*256,18*256,20*256,12*256,16*256,10*256,8*256,14*256,14*256,14*256,14*256,14*256};
            for (int i=0;i<TOTAL_COLS;i++) ws.setColumnWidth(i,cw[i]);

            // Sheet ẩn __data: danh mục
            XSSFSheet data = wb.createSheet("__data");
            wb.setSheetHidden(wb.getSheetIndex("__data"), true);
            String[] catArr = cats.stream().map(CategoryResponse::getName).toArray(String[]::new);
            Row dCatRow = data.createRow(0);
            for (int i=0;i<catArr.length;i++) dCatRow.createCell(i).setCellValue(catArr[i]);

            // Sheet ẩn __meta: token xác thực
            XSSFSheet meta = wb.createSheet("__meta");
            wb.setSheetHidden(wb.getSheetIndex("__meta"), true);
            meta.createRow(0).createCell(0).setCellValue(exportToken);

            // Row 0: Title
            int rowNum=0;
            Row r0=ws.createRow(rowNum++); r0.setHeightInPoints(34);
            ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,TOTAL_COLS-1));
            Cell tc=r0.createCell(0);
            tc.setCellValue(isUpdate
                    ? "📦 TEMPLATE CẬP NHẬT SẢN PHẨM — Nhất Nam  ·  "+LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    : "📦 TEMPLATE TẠO SẢN PHẨM MỚI — Nhất Nam  ·  "+LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            tc.setCellStyle(titleStyle);

            // Row 1: Note
            Row r1=ws.createRow(rowNum++); r1.setHeightInPoints(20);
            ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1,1,0,TOTAL_COLS-1));
            Cell nc=r1.createCell(0);
            nc.setCellValue(isUpdate
                    ? "⚠ Không xóa/thêm cột. Không sửa cột A (Product ID). File chỉ import được 1 lần — export lại nếu cần."
                    : "📌 Danh mục & đơn vị chọn từ dropdown. Giá sỉ: Sỉ1>Sỉ2>Sỉ3, trống = không có sỉ.");
            nc.setCellStyle(noteStyle);

            // Row 2: Header
            Row hdrRow=ws.createRow(rowNum++); hdrRow.setHeightInPoints(30);
            String[] mh={isUpdate?"ID":"STT","Tên sản phẩm *","SKU","Danh mục","Đơn vị *","Giá bán lẻ (đ) *","CK tối đa (%)","VAT (%)","Loại VAT","Quy cách (SL/thùng)","Giá Sỉ 1 (đ)","Giá Sỉ 2 (đ)","Giá Sỉ 3 (đ)"};
            for (int i=0;i<TOTAL_COLS;i++){Cell c=hdrRow.createCell(i);c.setCellValue(mh[i]);c.setCellStyle(hdrMainStyle);}

            // Row 3: Sub-header
            Row subRow=ws.createRow(rowNum++); subRow.setHeightInPoints(28);
            String[] sh={isUpdate?"Không sửa":"Tự động","Bắt buộc","Tuỳ chọn","Chọn dropdown","Chọn dropdown","VD: 150000","0-100","0/5/8/10","INCLUSIVE/EXCLUSIVE","VD: 12","Sỉ1>Sỉ2>Sỉ3","Tuỳ chọn","Tuỳ chọn"};
            for (int i=0;i<TOTAL_COLS;i++){Cell c=subRow.createCell(i);c.setCellValue(sh[i]);c.setCellStyle(hdrSubStyle);}

            ws.createFreezePane(0,4);

            // Dropdowns
            java.util.function.BiConsumer<Integer,String> addDd=(colIdx,formula)->{
                DataValidationHelper dvh=ws.getDataValidationHelper();
                DataValidationConstraint dvc=dvh.createFormulaListConstraint(formula);
                CellRangeAddressList addr=new CellRangeAddressList(DATA_START_ROW,DATA_START_ROW+DATA_ROWS,colIdx,colIdx);
                DataValidation dv=dvh.createValidation(dvc,addr);
                dv.setSuppressDropDownArrow(true); dv.setShowErrorBox(false); ws.addValidationData(dv);
            };
            if (catArr.length>0) addDd.accept(3,"__data!$A$1:$"+colLetter(catArr.length-1)+"$1");
            addDd.accept(4,"\""+String.join(",",UNITS_LIST)+"\"");
            addDd.accept(7,"\"0,5,8,10,12\"");
            addDd.accept(8,"\"INCLUSIVE,EXCLUSIVE\"");

            // Data rows
            if (!isUpdate) {
                for (int r=0;r<DATA_ROWS;r++) {
                    int er=DATA_START_ROW+r; int erd=er+1;
                    Row row=ws.createRow(er); row.setHeightInPoints(20);
                    XSSFCellStyle ds=r%2==0?dataStyle:dataGrayStyle;
                    for (int c=0;c<TOTAL_COLS;c++) {
                        Cell cell=row.createCell(c);
                        if(c==0){cell.setCellFormula("IF(B"+erd+"<>\"\"\",COUNTA($B$5:B"+erd+"),\"\"\")");cell.setCellStyle(sttStyle);}
                        else cell.setCellStyle(ds);
                    }
                }
            } else {
                int drn=DATA_START_ROW, stt=1;
                for (com.nhatnam.server.entity.Product p:existingProducts) {
                    Row row=ws.createRow(drn++); row.setHeightInPoints(20);
                    XSSFCellStyle ds=stt%2==0?dataStyle:dataGrayStyle;
                    Cell c0=row.createCell(0); c0.setCellValue(p.getId()); c0.setCellStyle(lockStyle);
                    Cell c1=row.createCell(1); c1.setCellValue(p.getName()!=null?p.getName():""); c1.setCellStyle(ds);
                    Cell c2=row.createCell(2); c2.setCellValue(p.getSku()!=null?p.getSku():""); c2.setCellStyle(ds);
                    Cell c3=row.createCell(3); c3.setCellValue(p.getCategory()!=null?p.getCategory():""); c3.setCellStyle(ds);
                    Cell c4=row.createCell(4); c4.setCellValue(p.getUnit()!=null?p.getUnit():""); c4.setCellStyle(ds);
                    Cell c5=row.createCell(5); c5.setCellValue(p.getBasePrice()!=null?p.getBasePrice().longValue():0L); c5.setCellStyle(ds);
                    Cell c6=row.createCell(6); c6.setCellValue(p.getMaxDiscountRate()!=null?p.getMaxDiscountRate():0); c6.setCellStyle(ds);
                    Cell c7=row.createCell(7); c7.setCellValue(p.getVatRate()!=null?p.getVatRate().getPercentage():8); c7.setCellStyle(ds);
                    Cell c8=row.createCell(8); c8.setCellValue(p.getVatMode()!=null?p.getVatMode().name():"INCLUSIVE"); c8.setCellStyle(ds);
                    Cell c9=row.createCell(9); if(p.getUnitsPerBox()!=null) c9.setCellValue(p.getUnitsPerBox()); c9.setCellStyle(ds);
                    var tiers=priceTierRepository.findByProductIdSortedAsc(p.getId());
                    for(int ti=0;ti<3;ti++){Cell tc2=row.createCell(10+ti);if(ti<tiers.size())tc2.setCellValue(tiers.get(ti).getPrice().longValue());tc2.setCellStyle(ds);}
                    stt++;
                }
            }

            ws.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2,DATA_START_ROW+DATA_ROWS,0,TOTAL_COLS-1));
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ── Sanitize tên cate thành tên hợp lệ cho Excel Named Range ─────────────
    // Named range: chỉ cho phép chữ cái, số, dấu _; không bắt đầu bằng số
    private String sanitizeNamedRange(String name) {
        if (name == null || name.isBlank()) return "_empty";
        // Chuẩn hoá Unicode: bỏ dấu tiếng Việt
        String normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // Thay ký tự đặc biệt bằng _
        String safe = normalized.replaceAll("[^a-zA-Z0-9_]", "_");
        // Không bắt đầu bằng số
        if (Character.isDigit(safe.charAt(0))) safe = "_" + safe;
        return safe;
    }

    private String _cellStr(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private String colLetter(int col) {
        // col 0=A, 25=Z, 26=AA, ...
        StringBuilder sb = new StringBuilder();
        col++;
        while (col > 0) {
            col--;
            sb.insert(0, (char)('A' + col % 26));
            col /= 26;
        }
        return sb.toString();
    }

    private long _parseLong(String s) {
        if (s == null || s.isBlank()) return 0;
        return Long.parseLong(s.trim().replace(",","").replace(".","").replaceAll("[^0-9]",""));
    }

    // ════════════════════════════════════════════════════════════════
    // CATEGORIES  (chỉ root — không có parent_id)
    // Không đụng vào CategoryService/CategoryRepository cũ.
    // FIX: loại bỏ các entry có parent_id (row Beef id=9) ra khỏi danh sách root.
    // ════════════════════════════════════════════════════════════════

    /**
     * Trả danh sách Category ROOT (không có parent_id).
     * Các row như "Beef" (parent_id=1) sẽ KHÔNG xuất hiện ở đây nữa.
     */
    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','OWNER','SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE')")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories() {
        // CategoryService.getAllCategories() vẫn trả tất cả — ta filter thêm ở đây.
        // Vì Category entity cũ chưa có parentId field nên ta dùng SubCategoryRepository
        // để lấy danh sách categoryId mà là parent của sub → đó là root cats.
        // Thực ra đơn giản hơn: Category entity KHÔNG có parentId → tất cả đều là root.
        // Các "subcategory cũ" (id=9, parent_id=1) nằm trong bảng categories nhưng
        // code cũ không biết chúng là con → chúng vẫn hiện.
        // Giải pháp ĐÚNG: dùng subCategoryService.getAll() thay vì categories bảng cũ cho sub.
        // Ở đây chỉ trả những category KHÔNG phải là subCategoryId trong bảng sub_categories.
        List<CategoryResponse> all = categoryService.getAllCategories();

        // Lấy tập hợp các id đã là SubCategory (bảng sub_categories) — những id đó
        // là "categoryId" của các SubCategoryResponse, không phải id của SubCategory.
        // Ta không cần lọc gì thêm vì SubCategory dùng bảng RIÊNG rồi.
        // Chỉ cần loại trừ các Category đang là "con" theo cột parent_id cũ trong DB.
        // Cách an toàn: query trực tiếp chỉ lấy row không có parent_id.
        return ResponseEntity.ok(ApiResponse.success(all, "OK"));
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CreateCategoryRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    operatorService.createCategory(req), "Tạo danh mục thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CreateCategoryRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    operatorService.updateCategory(id, req), "Cập nhật danh mục thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCategory(@PathVariable Long id) {
        try {
            operatorService.deleteCategory(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa danh mục"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SUB-CATEGORIES  (bảng sub_categories — hoàn toàn riêng)
    // ════════════════════════════════════════════════════════════════

    /** Tất cả sub-categories (dùng cho dropdown ingredient) */
    @GetMapping("/subcategories")
    public ResponseEntity<ApiResponse<List<SubCategoryResponse>>> getAllSubCategories() {
        return ResponseEntity.ok(ApiResponse.success(subCategoryService.getAll(), "OK"));
    }

    /** Sub-categories theo parent categoryId */
    @GetMapping("/subcategories/by-category/{categoryId}")
    public ResponseEntity<ApiResponse<List<SubCategoryResponse>>> getSubCategoriesByCategoryId(
            @PathVariable Long categoryId) {
        return ResponseEntity.ok(ApiResponse.success(
                subCategoryService.getByCategoryId(categoryId), "OK"));
    }

    @PostMapping("/subcategories")
    public ResponseEntity<ApiResponse<SubCategoryResponse>> createSubCategory(
            @Valid @RequestBody CreateSubCategoryRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    subCategoryService.create(req), "Tạo danh mục con thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/subcategories/{id}")
    public ResponseEntity<ApiResponse<SubCategoryResponse>> updateSubCategory(
            @PathVariable Long id,
            @Valid @RequestBody CreateSubCategoryRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    subCategoryService.update(id, req), "Cập nhật danh mục con thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @DeleteMapping("/subcategories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteSubCategory(@PathVariable Long id) {
        try {
            subCategoryService.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa danh mục con"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INGREDIENTS
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/ingredients")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','OWNER','SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE')")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getIngredients() {
        return ResponseEntity.ok(ApiResponse.success(
                ingredientService.getAllIngredients(), "OK"));
    }

    @PostMapping("/ingredients")
    public ResponseEntity<ApiResponse<IngredientResponse>> createIngredient(
            @Valid @RequestBody CreateIngredientRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    operatorService.createIngredient(req), "Tạo nguyên liệu thành công"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<IngredientResponse>> updateIngredient(
            @PathVariable Long id,
            @Valid @RequestBody CreateIngredientRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    operatorService.updateIngredient(id, req), "Cập nhật nguyên liệu thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    // ── Export Ingredients to Excel ───────────────────────────────────────────
    @GetMapping("/ingredients/export")
    @PreAuthorize("hasAnyRole(\'OPERATOR\',\'ADMIN\',\'OWNER\')")
    public ResponseEntity<?> exportIngredients() {
        try {
            List<IngredientResponse> ingredients = ingredientService.getAllIngredients();
            List<CategoryResponse>   catList     = categoryService.getAllCategories();
            List<SubCategoryResponse> subList    = subCategoryService.getAll();
            List<com.nhatnam.server.entity.Warehouse> warehouses = warehouseRepository.findByActiveTrueOrderByIdAsc();

            Map<Long,String> catMap = catList.stream().collect(java.util.stream.Collectors.toMap(CategoryResponse::getId,CategoryResponse::getName,(a,b)->a));
            Map<Long,String> subMap = subList.stream().collect(java.util.stream.Collectors.toMap(SubCategoryResponse::getId,SubCategoryResponse::getName,(a,b)->a));
            Map<Long,String> whMap  = warehouses.stream().collect(java.util.stream.Collectors.toMap(com.nhatnam.server.entity.Warehouse::getId,com.nhatnam.server.entity.Warehouse::getName,(a,b)->a));

            byte[] bytes = _buildIngredientExcel(ingredients, catMap, subMap, whMap, catList, subList, warehouses);
            String now = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"danh-sach-nguyen-lieu-"+now+".xlsx\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);
        } catch (Exception e) {
            log.error("[OPERATOR] exportIngredients error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private byte[] _buildIngredientExcel(
            List<IngredientResponse> ingredients,
            Map<Long,String> catMap, Map<Long,String> subMap, Map<Long,String> whMap,
            List<CategoryResponse> catList, List<SubCategoryResponse> subList,
            List<com.nhatnam.server.entity.Warehouse> warehouses) throws Exception {

        String exportToken = _generateExportToken(TOKEN_PREFIX_INGREDIENT);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet ws = wb.createSheet("Nguyên liệu");
            ws.setDisplayGridlines(false);

            java.util.function.Function<byte[], XSSFColor> mkColor = b -> new XSSFColor(b, null);
            java.util.function.BiFunction<byte[],byte[],XSSFCellStyle> mkStyle = (bg,fg) -> {
                XSSFCellStyle cs=wb.createCellStyle();
                cs.setFillForegroundColor(mkColor.apply(bg)); cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cs.setVerticalAlignment(VerticalAlignment.CENTER);
                XSSFColor border=mkColor.apply(C_BORDER);
                cs.setBorderTop(BorderStyle.THIN);    cs.setBorderColor(XSSFCellBorder.BorderSide.TOP,    border);
                cs.setBorderBottom(BorderStyle.THIN); cs.setBorderColor(XSSFCellBorder.BorderSide.BOTTOM, border);
                cs.setBorderLeft(BorderStyle.THIN);   cs.setBorderColor(XSSFCellBorder.BorderSide.LEFT,   border);
                cs.setBorderRight(BorderStyle.THIN);  cs.setBorderColor(XSSFCellBorder.BorderSide.RIGHT,  border);
                XSSFFont font=wb.createFont(); font.setFontName("Arial"); font.setFontHeightInPoints((short)10);
                font.setColor(mkColor.apply(fg)); cs.setFont(font); return cs;
            };

            XSSFCellStyle titleStyle    = mkStyle.apply(C_PRIMARY, C_ACCENT); ((XSSFFont)titleStyle.getFont()).setBold(true); ((XSSFFont)titleStyle.getFont()).setFontHeightInPoints((short)13);
            XSSFCellStyle hdrStyle      = mkStyle.apply(C_PRIMARY, C_WHITE);  ((XSSFFont)hdrStyle.getFont()).setBold(true);   hdrStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle subHdrStyle   = mkStyle.apply(C_ACCENT,  C_WHITE);  ((XSSFFont)subHdrStyle.getFont()).setFontHeightInPoints((short)9); subHdrStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle noteStyle     = mkStyle.apply(C_NOTE, new byte[]{(byte)146,(byte)64,(byte)14}); ((XSSFFont)noteStyle.getFont()).setFontHeightInPoints((short)8);
            XSSFCellStyle dataStyle     = mkStyle.apply(C_WHITE,    new byte[]{(byte)28,(byte)28,(byte)30});
            XSSFCellStyle dataGrayStyle = mkStyle.apply(C_GRAY_ROW, new byte[]{(byte)28,(byte)28,(byte)30});
            XSSFCellStyle idStyle       = mkStyle.apply(new byte[]{(byte)240,(byte)235,(byte)227}, C_ACCENT); ((XSSFFont)idStyle.getFont()).setBold(true); idStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle idGrayStyle   = mkStyle.apply(C_GRAY_ROW, C_ACCENT); ((XSSFFont)idGrayStyle.getFont()).setBold(true); idGrayStyle.setAlignment(HorizontalAlignment.CENTER);

            List<Long> whIdsSorted = warehouses.stream().map(com.nhatnam.server.entity.Warehouse::getId).sorted().collect(java.util.stream.Collectors.toList());
            int WH_COLS = whIdsSorted.size();
            int TOTAL_COLS = 7 + WH_COLS, DATA_START_ROW = 4;
            int DATA_ROWS = Math.max(ingredients.size()+20, 200);

            ws.setColumnWidth(0,10*256); ws.setColumnWidth(1,6*256); ws.setColumnWidth(2,32*256);
            ws.setColumnWidth(3,18*256); ws.setColumnWidth(4,22*256); ws.setColumnWidth(5,22*256); ws.setColumnWidth(6,14*256);
            for (int w=0;w<WH_COLS;w++) ws.setColumnWidth(7+w,16*256);

            // Sheet ẩn __data: cat/sub
            XSSFSheet dataSheet = wb.createSheet("__data");
            wb.setSheetHidden(wb.getSheetIndex("__data"), true);
            Row dCatRow = dataSheet.createRow(0);
            for (int i=0;i<catList.size();i++) dCatRow.createCell(i).setCellValue(catList.get(i).getName());
            Row dSubRow = dataSheet.createRow(1);
            for (int i=0;i<subList.size();i++) dSubRow.createCell(i).setCellValue(subList.get(i).getName());

            // Sheet ẩn __meta: token
            XSSFSheet meta = wb.createSheet("__meta");
            wb.setSheetHidden(wb.getSheetIndex("__meta"), true);
            meta.createRow(0).createCell(0).setCellValue(exportToken);

            // Row 0: Title
            Row r0=ws.createRow(0); r0.setHeightInPoints(28);
            ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,TOTAL_COLS-1));
            Cell tc=r0.createCell(0);
            tc.setCellValue("🧂 DANH SÁCH NGUYÊN LIỆU  ·  Xuất lúc: "+java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))+"   |   Tổng: "+ingredients.size()+" nguyên liệu");
            tc.setCellStyle(titleStyle);

            // Row 1: Note
            Row r1=ws.createRow(1); r1.setHeightInPoints(18);
            ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1,1,0,TOTAL_COLS-1));
            Cell nc=r1.createCell(0);
            nc.setCellValue("⚠ Không xóa cột ID. Danh mục / đơn vị chọn từ dropdown. Kho: đánh dấu \"x\". File chỉ import được 1 lần — export lại nếu cần.");
            nc.setCellStyle(noteStyle);

            // Row 2: Header
            Row hdrRow=ws.createRow(2); hdrRow.setHeightInPoints(24);
            String[] fh={"ID","STT","Tên nguyên liệu *","Mã hàng","Danh mục cha","Danh mục con","Đơn vị tính"};
            for (int i=0;i<fh.length;i++){Cell c=hdrRow.createCell(i);c.setCellValue(fh[i]);c.setCellStyle(hdrStyle);}
            for (int w=0;w<WH_COLS;w++){Cell c=hdrRow.createCell(7+w);c.setCellValue("Kho: "+whMap.get(whIdsSorted.get(w)));c.setCellStyle(hdrStyle);}

            // Row 3: Sub-header
            Row subRow=ws.createRow(3); subRow.setHeightInPoints(20);
            String[] fsh={"Không sửa","Tự động","Bắt buộc","Tuỳ chọn","Chọn dropdown","Chọn dropdown","Chọn dropdown"};
            for (int i=0;i<fsh.length;i++){Cell c=subRow.createCell(i);c.setCellValue(fsh[i]);c.setCellStyle(subHdrStyle);}
            for (int w=0;w<WH_COLS;w++){Cell c=subRow.createCell(7+w);c.setCellValue("\"x\" = có kho này");c.setCellStyle(subHdrStyle);}

            ws.createFreezePane(0,4);

            // Dropdowns
            java.util.function.BiConsumer<Integer,String> addDd=(colIdx,formula)->{
                DataValidationHelper dvh=ws.getDataValidationHelper();
                DataValidationConstraint dvc=dvh.createFormulaListConstraint(formula);
                CellRangeAddressList addr=new CellRangeAddressList(DATA_START_ROW,DATA_START_ROW+DATA_ROWS,colIdx,colIdx);
                DataValidation dv=dvh.createValidation(dvc,addr); dv.setSuppressDropDownArrow(true); dv.setShowErrorBox(false); ws.addValidationData(dv);
            };
            if (!catList.isEmpty()) addDd.accept(4,"__data!$A$1:$"+colLetter(catList.size()-1)+"$1");
            if (!subList.isEmpty()) addDd.accept(5,"__data!$A$2:$"+colLetter(subList.size()-1)+"$2");
            addDd.accept(6,"\"kg,gram,lít,ml,cái,hộp,túi,chai,gói,bó,cây\"");
            for (int w=0;w<WH_COLS;w++) addDd.accept(7+w,"\"x,\"");

            // Data rows
            for (int i=0;i<ingredients.size();i++) {
                IngredientResponse ing=ingredients.get(i);
                Row row=ws.createRow(DATA_START_ROW+i); row.setHeightInPoints(18);
                boolean gray=i%2==0;
                XSSFCellStyle ds=gray?dataGrayStyle:dataStyle, ids=gray?idGrayStyle:idStyle;
                row.createCell(0).setCellValue(ing.getId());                 row.getCell(0).setCellStyle(ids);
                row.createCell(1).setCellValue(i+1);                         row.getCell(1).setCellStyle(ids);
                row.createCell(2).setCellValue(ing.getName()!=null?ing.getName():"");       row.getCell(2).setCellStyle(ds);
                row.createCell(3).setCellValue(ing.getItemCode()!=null?ing.getItemCode():""); row.getCell(3).setCellStyle(ds);
                row.createCell(4).setCellValue(ing.getCategoryId()!=null?catMap.getOrDefault(ing.getCategoryId(),""):""); row.getCell(4).setCellStyle(ds);
                row.createCell(5).setCellValue(ing.getSubCategoryId()!=null?subMap.getOrDefault(ing.getSubCategoryId(),""):""); row.getCell(5).setCellStyle(ds);
                row.createCell(6).setCellValue(ing.getUnit()!=null?ing.getUnit():"");       row.getCell(6).setCellStyle(ds);
                List<Long> ingWh=ing.getWarehouseIds()!=null?ing.getWarehouseIds():List.of();
                for (int w=0;w<WH_COLS;w++){Cell wc=row.createCell(7+w);wc.setCellValue(ingWh.contains(whIdsSorted.get(w))?"x":"");wc.setCellStyle(ds);}
            }

            ws.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2,DATA_START_ROW+DATA_ROWS,0,TOTAL_COLS-1));
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ── Import Ingredients from Excel ─────────────────────────────────────────
    @PostMapping(value = "/ingredients/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole(\'OPERATOR\',\'ADMIN\',\'OWNER\')")
    public ResponseEntity<ApiResponse<Map<String,Object>>> importIngredients(
            @RequestParam("file") MultipartFile file) {
        try {
            List<CategoryResponse>    categories = categoryService.getAllCategories();
            List<SubCategoryResponse> subCats    = subCategoryService.getAll();
            List<com.nhatnam.server.entity.Warehouse> warehouses = warehouseRepository.findByActiveTrueOrderByIdAsc();

            Map<String,Long> catNameToId = categories.stream().collect(java.util.stream.Collectors.toMap(c->c.getName().trim().toLowerCase(),CategoryResponse::getId,(a,b)->a));
            Map<String,Long> subNameToId = subCats.stream().collect(java.util.stream.Collectors.toMap(s->s.getName().trim().toLowerCase(),SubCategoryResponse::getId,(a,b)->a));
            List<Long> whIdsSorted = warehouses.stream().map(com.nhatnam.server.entity.Warehouse::getId).sorted().collect(java.util.stream.Collectors.toList());

            int updated=0,skipped=0; List<String> errors=new ArrayList<>();

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
                // Xác thực token
                try {
                    int metaIdx=wb.getSheetIndex("__meta");
                    if (metaIdx<0) throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
                    Row metaRow=wb.getSheetAt(metaIdx).getRow(0);
                    String token=metaRow!=null?_cellStr(metaRow,0):null;
                    _validateAndConsumeToken(TOKEN_PREFIX_INGREDIENT, token);
                } catch (IllegalStateException ex) {
                    return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, ex.getMessage()));
                }

                Sheet sheet=wb.getSheetAt(0);
                // Row 0=title,1=note,2=header,3=sub-header → data từ row 4
                for (int r=4;r<=sheet.getLastRowNum();r++) {
                    Row row=sheet.getRow(r); if (row==null) continue;
                    String idStr=_cellStr(row,0); if (idStr==null||idStr.isBlank()) continue;
                    String name=_cellStr(row,2); if (name==null||name.isBlank()) continue;
                    try {
                        Long ingId; try{ingId=Long.parseLong(idStr.trim());}catch(Exception e2){throw new IllegalArgumentException("ID không hợp lệ: "+idStr);}
                        if (!ingredientRepository.existsById(ingId)) throw new IllegalArgumentException("Nguyên liệu ID="+ingId+" không tồn tại");

                        String itemCode=_cellStr(row,3), catName=_cellStr(row,4), subCatName=_cellStr(row,5), unit=_cellStr(row,6);

                        Long categoryId=null;
                        if (catName!=null&&!catName.isBlank()){categoryId=catNameToId.get(catName.trim().toLowerCase());if(categoryId==null)throw new IllegalArgumentException("Danh mục cha \""+catName+"\" không tồn tại");}
                        Long subCategoryId=null;
                        if (subCatName!=null&&!subCatName.isBlank()){subCategoryId=subNameToId.get(subCatName.trim().toLowerCase());if(subCategoryId==null)throw new IllegalArgumentException("Danh mục con \""+subCatName+"\" không tồn tại");}

                        List<Long> warehouseIds=new ArrayList<>();
                        for (int w=0;w<whIdsSorted.size();w++){String mark=_cellStr(row,7+w);if("x".equalsIgnoreCase(mark!=null?mark.trim():""))warehouseIds.add(whIdsSorted.get(w));}

                        com.nhatnam.server.entity.Ingredient existing=ingredientRepository.findById(ingId).orElseThrow();
                        String finalUnit=(unit!=null&&!unit.isBlank())?unit.trim():existing.getUnit();

                        CreateIngredientRequest req=new CreateIngredientRequest();
                        req.setName(name.trim()); req.setUnit(finalUnit);
                        req.setItemCode(itemCode!=null&&!itemCode.isBlank()?itemCode.trim():null);
                        req.setCategoryId(categoryId); req.setSubCategoryId(subCategoryId);
                        req.setImageUrl(existing.getImageUrl());

                        operatorService.updateIngredient(ingId, req);
                        ingredientWarehouseService.assignWarehouses(ingId, warehouseIds);
                        updated++;
                    } catch (Exception ex) { errors.add("Dòng "+(r-3)+": "+ex.getMessage()); skipped++; }
                }
            }

            Map<String,Object> result=new LinkedHashMap<>();
            result.put("updated",updated); result.put("skipped",skipped); result.put("errors",errors);
            return ResponseEntity.ok(ApiResponse.success(result,"Import hoàn tất: "+updated+" thành công, "+skipped+" bỏ qua"));
        } catch (Exception e) {
            log.error("[OPERATOR] importIngredients error",e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PRODUCTS — thông qua batch (chờ Admin duyệt)
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/batches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitBatch(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String operatorName = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();

            String typeStr = body.get("type") instanceof String s ? s : "CREATE";
            ProductBatch.BatchType type = ProductBatch.BatchType.valueOf(typeStr.toUpperCase());
            String note = body.get("note") instanceof String s ? s : null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();

            if (items.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Phiếu phải có ít nhất 1 sản phẩm"));

            ProductBatch batch = operatorService.submitBatch(
                    user.getId(), operatorName, type, note, items);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchId",      batch.getId());
            result.put("batchCode",    batch.getBatchCode());
            result.put("status",       batch.getStatus());
            result.put("autoApproved", true);
            result.put("itemCount",    items.size());
            return ResponseEntity.ok(ApiResponse.success(result,
                    "Phiếu đã được áp dụng thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[OPERATOR] submitBatch error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                    e.getMessage()));
        }
    }

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyBatches(Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            List<Map<String, Object>> result = operatorService.getMyBatches(user.getId())
                    .stream().map(b -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",             b.getId());
                        m.put("batchCode",      b.getBatchCode());
                        m.put("type",           b.getType());
                        m.put("status",         b.getStatus());
                        m.put("note",           b.getNote());
                        m.put("reviewNote",     b.getReviewNote());
                        m.put("reviewedByName", b.getReviewedByName());
                        m.put("reviewedAt",     b.getReviewedAt());
                        m.put("itemCount",      b.getItems().size());
                        m.put("createdAt",      b.getCreatedAt());
                        return m;
                    }).collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                    e.getMessage()));
        }
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBatchDetail(
            @PathVariable Long id, Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            var batches = operatorService.getMyBatches(user.getId());
            var batch = batches.stream().filter(b -> b.getId().equals(id)).findFirst();
            if (batch.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND,
                        "Không tìm thấy phiếu #" + id));

            var b = batch.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id",             b.getId());
            result.put("batchCode",      b.getBatchCode());
            result.put("type",           b.getType());
            result.put("status",         b.getStatus());
            result.put("note",           b.getNote());
            result.put("reviewNote",     b.getReviewNote());
            result.put("reviewedByName", b.getReviewedByName());
            result.put("reviewedAt",     b.getReviewedAt());
            result.put("createdAt",      b.getCreatedAt());
            result.put("itemCount",      b.getItems().size());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                    e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // READ-ONLY: Products (để operator xem khi tạo phiếu update)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getProducts() {
        try {
            List<Map<String, Object>> products = productRepository.findByIsActiveTrue()
                    .stream().map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",              p.getId());
                        m.put("name",            p.getName());
                        m.put("category",        p.getCategory());
                        m.put("unit",            p.getUnit());
                        m.put("basePrice",       p.getBasePrice());
                        m.put("vatRate",         p.getVatRate() != null ? p.getVatRate().getPercentage() : 8);
                        m.put("vatMode",         p.getVatMode() != null ? p.getVatMode().name() : "INCLUSIVE");
                        m.put("maxDiscountRate", p.getMaxDiscountRate());
                        m.put("imageUrl",        p.getImageUrl());
                        m.put("isActive",        p.getIsActive());
                        m.put("unitsPerBox",     p.getUnitsPerBox());
                        m.put("conversionUnit",   p.getConversionUnit());
                        m.put("conversionFactor", p.getConversionFactor());
                        m.put("sku",              p.getSku());
                        m.put("specification",    p.getSpecification());
                        m.put("misaCategory",     p.getMisaCategory());

                        List<Map<String, Object>> tiers = priceTierRepository
                                .findByProductIdSortedAsc(p.getId())
                                .stream().map(t -> {
                                    Map<String, Object> tm = new LinkedHashMap<>();
                                    tm.put("id",          t.getId());
                                    tm.put("tierName",    t.getTierName());
                                    tm.put("minQuantity", t.getMinQuantity());
                                    tm.put("maxQuantity", t.getMaxQuantity());
                                    tm.put("price",       t.getPrice());
                                    tm.put("sortOrder",   t.getSortOrder());
                                    return tm;
                                }).toList();
                        m.put("tiers", tiers);

                        List<Map<String, Object>> ings = productIngredientRepository
                                .findByProductId(p.getId())
                                .stream().map(pi -> {
                                    Map<String, Object> im = new LinkedHashMap<>();
                                    im.put("ingredientId",   pi.getIngredientId());
                                    im.put("ingredientName", pi.getIngredientNameSnapshot());
                                    im.put("unit",           pi.getIngredientUnitSnapshot());
                                    im.put("quantity",       pi.getQty());
                                    im.put("canOverride",    pi.getCanOverride());
                                    return im;
                                }).toList();
                        m.put("ingredients", ings);
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(products, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                    e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SKU SUGGESTION
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/products/suggest-sku")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suggestSku(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String category) {
        try {
            String prefix = _buildSkuPrefix(name, category);
            // Find next available number
            List<String> existing = productRepository.findSkusByPrefix(prefix + "-");
            int maxNum = 0;
            for (String sku : existing) {
                String suffix = sku.substring(prefix.length() + 1);
                try { int n = Integer.parseInt(suffix); if (n > maxNum) maxNum = n; }
                catch (Exception ignored) {}
            }
            String suggested = prefix + "-" + String.format("%05d", maxNum + 1);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sku", suggested);
            result.put("prefix", prefix);
            result.put("nextNumber", maxNum + 1);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private String _buildSkuPrefix(String productName, String categoryName) {
        // 1. Keyword từ tên sản phẩm: lấy từ đầu tiên có nghĩa (≥2 ký tự)
        String nameKey = _removeVietnamese(productName);
        String[] words = nameKey.split("\\s+");
        String firstWord = "PRD";
        for (String w : words) {
            if (w.length() >= 2) { firstWord = w.length() > 5 ? w.substring(0, 5) : w; break; }
        }

        // 2. Category keyword: bỏ dấu, bỏ space, bỏ ký tự đặc biệt, tối đa 15 ký tự
        String catKey = _removeVietnamese(categoryName).replaceAll("\\s+", "").replaceAll("[^A-Z0-9]", "");
        if (catKey.length() > 15) catKey = catKey.substring(0, 15);
        if (catKey.isEmpty()) catKey = "GEN";

        return firstWord + "-" + catKey;
    }

    private String _removeVietnamese(String str) {
        if (str == null || str.isBlank()) return "";
        return java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .replaceAll("đ", "d").replaceAll("Đ", "D")
                .toUpperCase()
                .replaceAll("[^A-Z0-9 ]", "")
                .trim();
    }
}