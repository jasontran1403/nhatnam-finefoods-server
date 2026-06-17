package com.nhatnam.server.restcontroller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.request.CreateCompleteProductRequest;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.VatMode;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/batches")
@RequiredArgsConstructor
@Log4j2
public class BatchApprovalController {

    private final ProductBatchRepository      batchRepository;
    private final ProductBatchItemRepository  batchItemRepository;
    private final ProductRepository           productRepository;
    private final ProductService              productService;
    private final CategoryRepository          categoryRepository;
    private final NotificationService         notificationService;
    private final ObjectMapper                objectMapper;

    // ── List batches ──────────────────────────────────────────────

    @GetMapping
    public ApiResponse<PageResponse<Map<String, Object>>> listBatches(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        ProductBatch.BatchStatus batchStatus = null;
        if (status != null && !status.isBlank()) {
            try { batchStatus = ProductBatch.BatchStatus.valueOf(status.toUpperCase()); }
            catch (Exception ignored) {}
        }

        Page<ProductBatch> page = batchRepository.findAllFiltered(batchStatus, pageable);
        List<Map<String, Object>> content = page.getContent().stream()
                .map(this::toBatchSummary).collect(Collectors.toList());

        return ApiResponse.ok(PageResponse.<Map<String, Object>>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build());
    }

    // ── Batch detail ──────────────────────────────────────────────

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getBatchDetail(@PathVariable Long id) {
        ProductBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu #" + id));

        List<Map<String, Object>> items = batch.getItems().stream()
                .map(this::toItemDetail).collect(Collectors.toList());

        Map<String, Object> result = toBatchSummary(batch);
        result.put("items", items);
        return ApiResponse.ok(result);
    }

    // ── Approve ENTIRE batch ──────────────────────────────────────

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approveBatch(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {

        ProductBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu #" + id));

        if (batch.getStatus() == ProductBatch.BatchStatus.APPROVED)
            throw new RuntimeException("Phiếu đã được duyệt toàn bộ");

        User admin = (User) auth.getPrincipal();
        String adminName = getName(admin);
        String reviewNote = body != null && body.get("note") instanceof String s ? s : null;

        int approved = 0, failed = 0;
        for (ProductBatchItem item : batch.getItems()) {
            if (item.getStatus() == ProductBatchItem.ItemStatus.APPROVED) { approved++; continue; }
            try {
                applyItem(item);
                item.setStatus(ProductBatchItem.ItemStatus.APPROVED);
                batchItemRepository.save(item);
                approved++;
            } catch (Exception e) {
                log.error("[BATCH] approve item {} error: {}", item.getId(), e.getMessage());
                item.setStatus(ProductBatchItem.ItemStatus.REJECTED);
                item.setReviewNote("Lỗi hệ thống: " + e.getMessage());
                batchItemRepository.save(item);
                failed++;
            }
        }

        batch.setStatus(ProductBatch.BatchStatus.APPROVED);
        batch.setReviewedBy(admin);
        batch.setReviewedByName(adminName);
        batch.setReviewNote(reviewNote);
        batch.setReviewedAt(System.currentTimeMillis());
        batchRepository.save(batch);

        // Thông báo Operator
        notificationService.sendToRole(
                "OPERATOR",
                "BATCH_APPROVED",
                "Phiếu [" + batch.getBatchCode() + "] đã được Admin duyệt (" + approved + " sản phẩm).",
                "{\"batchId\":" + id + "}"
        );

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("approved", approved);
        r.put("failed",   failed);
        return ApiResponse.ok("Duyệt phiếu thành công", r);
    }

    // ── Approve SELECTED items ────────────────────────────────────

    @PostMapping("/{batchId}/items/approve")
    public ApiResponse<Map<String, Object>> approveItems(
            @PathVariable Long batchId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {

        ProductBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu #" + batchId));

        @SuppressWarnings("unchecked")
        List<Number> itemIds = body.get("itemIds") instanceof List<?> l
                ? (List<Number>) l : List.of();
        String note = body.get("note") instanceof String s ? s : null;

        User admin = (User) auth.getPrincipal();
        String adminName = getName(admin);
        int approved = 0, failed = 0;

        for (Number rawId : itemIds) {
            Long itemId = rawId.longValue();
            ProductBatchItem item = batchItemRepository.findById(itemId)
                    .orElse(null);
            if (item == null || !item.getBatch().getId().equals(batchId)) continue;
            if (item.getStatus() == ProductBatchItem.ItemStatus.APPROVED) { approved++; continue; }

            try {
                applyItem(item);
                item.setStatus(ProductBatchItem.ItemStatus.APPROVED);
                item.setReviewNote(note);
                batchItemRepository.save(item);
                approved++;
            } catch (Exception e) {
                log.error("[BATCH] approve item {} error: {}", itemId, e.getMessage());
                item.setStatus(ProductBatchItem.ItemStatus.REJECTED);
                item.setReviewNote("Lỗi: " + e.getMessage());
                batchItemRepository.save(item);
                failed++;
            }
        }

        // Cập nhật trạng thái batch
        refreshBatchStatus(batch, admin, adminName);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("approved", approved);
        r.put("failed",   failed);
        return ApiResponse.ok("Duyệt thành công " + approved + " sản phẩm", r);
    }

    // ── Reject ENTIRE batch ───────────────────────────────────────

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> rejectBatch(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {

        ProductBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu #" + id));

        User admin = (User) auth.getPrincipal();
        String reviewNote = body != null && body.get("note") instanceof String s ? s : null;

        batch.getItems().forEach(item -> {
            if (item.getStatus() == ProductBatchItem.ItemStatus.PENDING) {
                item.setStatus(ProductBatchItem.ItemStatus.REJECTED);
                item.setReviewNote(reviewNote);
                batchItemRepository.save(item);
            }
        });

        batch.setStatus(ProductBatch.BatchStatus.REJECTED);
        batch.setReviewedBy(admin);
        batch.setReviewedByName(getName(admin));
        batch.setReviewNote(reviewNote);
        batch.setReviewedAt(System.currentTimeMillis());
        batchRepository.save(batch);

        notificationService.sendToRole(
                "OPERATOR",
                "BATCH_REJECTED",
                "Phiếu [" + batch.getBatchCode() + "] đã bị từ chối" +
                        (reviewNote != null ? ": " + reviewNote : "."),
                "{\"batchId\":" + id + "}"
        );

        return ApiResponse.ok("Từ chối phiếu thành công", null);
    }

    // ── private helpers ───────────────────────────────────────────

    private void applyItem(ProductBatchItem item) throws Exception {
        CreateCompleteProductRequest req = buildRequest(item);

        if (item.getExistingProductId() != null) {
            productService.updateProduct(item.getExistingProductId(), req);
            // Cập nhật maxDiscountRate (ProductService.updateProduct chưa handle field này)
            Product p = productRepository.findById(item.getExistingProductId())
                    .orElseThrow();
            if (item.getMaxDiscountRate() != null)
                p.setMaxDiscountRate(item.getMaxDiscountRate());
            productRepository.save(p);
        } else {
            var created = productService.createCompleteProduct(req);
            // Cập nhật maxDiscountRate
            Product p = productRepository.findById(created.getId()).orElseThrow();
            if (item.getMaxDiscountRate() != null)
                p.setMaxDiscountRate(item.getMaxDiscountRate());
            productRepository.save(p);
        }
    }

    @SuppressWarnings("unchecked")
    private CreateCompleteProductRequest buildRequest(ProductBatchItem item) throws Exception {
        CreateCompleteProductRequest req = new CreateCompleteProductRequest();
        req.setName(item.getProductName());
        req.setCategory(item.getCategoryName());
        req.setUnit(item.getUnit());
        req.setImageUrl(item.getImageUrl());
        req.setBasePrice(item.getBasePrice() != null ? item.getBasePrice() : BigDecimal.ZERO);
        req.setVatRate(item.getVatRate() != null ? item.getVatRate() : 8);
        req.setVatMode(item.getVatMode() != null ? item.getVatMode() : "INCLUSIVE");

        if (item.getTiersJson() != null && !item.getTiersJson().isBlank()) {
            List<Map<String, Object>> tiersRaw = objectMapper.readValue(
                    item.getTiersJson(), List.class);
            List<CreateCompleteProductRequest.TierItem> tiers = tiersRaw.stream().map(t -> {
                CreateCompleteProductRequest.TierItem ti = new CreateCompleteProductRequest.TierItem();
                ti.setTierName(t.get("tierName") instanceof String s ? s : "");
                ti.setMinQuantity(t.get("minQuantity") instanceof Number n
                        ? new BigDecimal(n.toString()) : BigDecimal.ZERO);
                if (t.get("maxQuantity") instanceof Number n)
                    ti.setMaxQuantity(new BigDecimal(n.toString()));
                ti.setPrice(t.get("price") instanceof Number n
                        ? new BigDecimal(n.toString()) : BigDecimal.ZERO);
                ti.setSortOrder(t.get("sortOrder") instanceof Number n ? n.intValue() : 0);
                return ti;
            }).toList();
            req.setTiers(tiers);
        }

        if (item.getIngredientsJson() != null && !item.getIngredientsJson().isBlank()) {
            List<Map<String, Object>> ingsRaw = objectMapper.readValue(
                    item.getIngredientsJson(), List.class);
            List<CreateCompleteProductRequest.IngredientItem> ings = ingsRaw.stream().map(i -> {
                CreateCompleteProductRequest.IngredientItem ii =
                        new CreateCompleteProductRequest.IngredientItem();
                if (i.get("ingredientId") instanceof Number n)
                    ii.setIngredientId(n.longValue());
                if (i.get("quantity") instanceof Number n)
                    ii.setQuantity(new BigDecimal(n.toString()));
                ii.setCanOverride(Boolean.TRUE.equals(i.get("canOverride")));
                return ii;
            }).toList();
            req.setIngredients(ings);
        }

        return req;
    }

    private void refreshBatchStatus(ProductBatch batch, User admin, String adminName) {
        long total    = batch.getItems().size();
        long approved = batchItemRepository.countByBatchIdAndStatus(
                batch.getId(), ProductBatchItem.ItemStatus.APPROVED);
        long rejected = batchItemRepository.countByBatchIdAndStatus(
                batch.getId(), ProductBatchItem.ItemStatus.REJECTED);

        ProductBatch.BatchStatus newStatus;
        if (approved == total) newStatus = ProductBatch.BatchStatus.APPROVED;
        else if (rejected == total) newStatus = ProductBatch.BatchStatus.REJECTED;
        else if (approved > 0 || rejected > 0) newStatus = ProductBatch.BatchStatus.PARTIALLY_APPROVED;
        else newStatus = ProductBatch.BatchStatus.PENDING;

        batch.setStatus(newStatus);
        batch.setReviewedBy(admin);
        batch.setReviewedByName(adminName);
        batch.setReviewedAt(System.currentTimeMillis());
        batchRepository.save(batch);
    }

    private Map<String, Object> toBatchSummary(ProductBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            b.getId());
        m.put("batchCode",     b.getBatchCode());
        m.put("type",          b.getType());
        m.put("status",        b.getStatus());
        m.put("note",          b.getNote());
        m.put("reviewNote",    b.getReviewNote());
        m.put("createdByName", b.getCreatedByName());
        m.put("reviewedByName",b.getReviewedByName());
        m.put("reviewedAt",    b.getReviewedAt());
        m.put("createdAt",     b.getCreatedAt());
        m.put("itemCount",     b.getItems().size());
        long pending  = b.getItems().stream().filter(i -> i.getStatus() == ProductBatchItem.ItemStatus.PENDING).count();
        long approved = b.getItems().stream().filter(i -> i.getStatus() == ProductBatchItem.ItemStatus.APPROVED).count();
        long rejected = b.getItems().stream().filter(i -> i.getStatus() == ProductBatchItem.ItemStatus.REJECTED).count();
        m.put("pendingCount",  pending);
        m.put("approvedCount", approved);
        m.put("rejectedCount", rejected);
        return m;
    }

    private Map<String, Object> toItemDetail(ProductBatchItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              i.getId());
        m.put("productName",     i.getProductName());
        m.put("categoryName",    i.getCategoryName());
        m.put("imageUrl",        i.getImageUrl());
        m.put("unit",            i.getUnit());
        m.put("basePrice",       i.getBasePrice());
        m.put("maxDiscountRate", i.getMaxDiscountRate());
        m.put("vatRate",         i.getVatRate());
        m.put("vatMode",         i.getVatMode());
        m.put("tiersJson",       i.getTiersJson());
        m.put("ingredientsJson", i.getIngredientsJson());
        m.put("status",          i.getStatus());
        m.put("reviewNote",      i.getReviewNote());
        m.put("existingProductId", i.getExistingProductId());
        return m;
    }

    private String getName(User u) {
        return u.getFullName() != null && !u.getFullName().isBlank()
                ? u.getFullName() : u.getUsername();
    }
}
