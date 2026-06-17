package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.InvoiceDTO;
import com.nhatnam.server.dto.request.SaveDraftRequest;
import com.nhatnam.server.dto.response.DraftOrderResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.DraftOrderRepository;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.utils.InvoicePdf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DraftOrderService {

    private static final ZoneId VN_TZ = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DraftOrderRepository draftOrderRepository;
    private final CustomerRepository   customerRepository;
    private final UserRepository       userRepository;
    private final IngredientRepository ingredientRepository;
    private final InvoicePdf           invoicePdf;
    private final ObjectMapper         objectMapper;

    // ══════════════════════════════════════════════════════════════════════
    // SAVE DRAFT (DRAFT hoặc SCHEDULED)
    // ══════════════════════════════════════════════════════════════════════
    @Transactional
    public DraftOrderResponse saveDraft(SaveDraftRequest req, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String type = req.getType() != null ? req.getType().toUpperCase() : "DRAFT";

        // Validate SCHEDULED
        if ("SCHEDULED".equals(type)) {
            if (req.getCustomerId() == null && (req.getCustomerName() == null || req.getCustomerName().isBlank())) {
                throw new IllegalArgumentException("Đơn hẹn giờ yêu cầu thông tin khách hàng");
            }
            if (req.getScheduledAt() == null) {
                throw new IllegalArgumentException("Đơn hẹn giờ yêu cầu thời gian hẹn");
            }
            if (req.getScheduledAt() <= System.currentTimeMillis()) {
                throw new IllegalArgumentException("Thời gian hẹn phải ở tương lai");
            }
        }

        Customer customer = null;
        if (req.getCustomerId() != null) {
            customer = customerRepository.findById(req.getCustomerId()).orElse(null);
        }

        // Build surchargeDetail JSON
        String surchargeDetail = null;
        BigDecimal surchargeTotal = BigDecimal.ZERO;
        if (req.getSurchargeItems() != null && !req.getSurchargeItems().isEmpty()) {
            try {
                surchargeDetail = objectMapper.writeValueAsString(
                        req.getSurchargeItems().stream()
                                .filter(i -> i.getAmount() != null && i.getAmount().compareTo(BigDecimal.ZERO) > 0)
                                .map(i -> Map.of("name", i.getName(), "amount", i.getAmount()))
                                .collect(Collectors.toList())
                );
                surchargeTotal = req.getSurchargeItems().stream()
                        .filter(i -> i.getAmount() != null)
                        .map(SaveDraftRequest.SurchargeItemDto::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            } catch (Exception e) {
                log.warn("Failed to serialize surchargeItems", e);
            }
        }

        DraftOrder draft = DraftOrder.builder()
                .draftCode(generateDraftCode(type))
                .user(user)
                .customer(customer)
                .customerName(req.getCustomerName())
                .customerPhone(req.getCustomerPhone())
                .customerEmail(req.getCustomerEmail())
                .shippingAddress(req.getShippingAddress())
                .receiverName(req.getReceiverName())
                .receiverPhone(req.getReceiverPhone())
                .receiverAddress(req.getReceiverAddress())
                .receiverInfoId(req.getReceiverInfoId())
                .notes(req.getNotes())
                .paymentMethod(req.getPaymentMethod())
                .discountRate(req.getDiscountRate() != null ? req.getDiscountRate() : 0)
                .discountAmount(req.getDiscountAmount())
                .surcharge(surchargeTotal)
                .surchargeDetail(surchargeDetail)
                .warehouseId(req.getWarehouseId())
                .warehouseName(req.getWarehouseName())
                .deliveryDatetime(req.getDeliveryDatetime())
                .orderedByName(req.getOrderedByName())
                .showPrices(req.getShowPrices() != null ? req.getShowPrices() : Boolean.TRUE)
                .hideAllPrices(req.getHideAllPrices() != null ? req.getHideAllPrices() : Boolean.FALSE)
                .type(type)
                .scheduledAt(req.getScheduledAt())
                .build();

        // Build items
        if (req.getItems() != null) {
            List<DraftOrderItem> items = req.getItems().stream().map(i -> DraftOrderItem.builder()
                    .draftOrder(draft)
                    .productId(i.getProductId())
                    .productName(i.getProductName())
                    .productImageUrl(i.getProductImageUrl())
                    .unit(i.getUnit())
                    .quantity(i.getQuantity())
                    .unitPrice(i.getUnitPrice())
                    .basePrice(i.getBasePrice())
                    .priceMode(i.getPriceMode() != null ? i.getPriceMode() : "BASE")
                    .tierId(i.getTierId())
                    .tierName(i.getTierName())
                    .discountPercent(i.getDiscountPercent())
                    .isManualPrice(Boolean.TRUE.equals(i.getIsManualPrice()))
                    .saleType(i.getSaleType() != null ? i.getSaleType() : "RETAIL")
                    .unitsPerBox(i.getUnitsPerBox())
                    .isPromo(Boolean.TRUE.equals(i.getIsPromo()))
                    .promoNote(i.getPromoNote())
                    .itemDiscountRate(i.getItemDiscountRate() != null ? i.getItemDiscountRate() : 0)
                    .notes(i.getNotes())
                    .subtotal(i.getSubtotal())
                    .vatRate(i.getVatRate() != null ? i.getVatRate() : 0)
                    .vatMode(i.getVatMode() != null ? i.getVatMode() : "INCLUSIVE")
                    .build()).collect(Collectors.toList());
            draft.setDraftItems(items);
        }

        DraftOrder saved = draftOrderRepository.save(draft);
        return toResponse(saved);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET MY DRAFTS
    // ══════════════════════════════════════════════════════════════════════
    @Transactional(readOnly = true)
    public List<DraftOrderResponse> getMyDrafts(Long userId) {
        return draftOrderRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET DRAFT BY ID
    // ══════════════════════════════════════════════════════════════════════
    @Transactional(readOnly = true)
    public DraftOrderResponse getDraftById(Long draftId, Long userId) {
        DraftOrder draft = draftOrderRepository.findByIdAndUserId(draftId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn nháp: " + draftId));
        return toResponse(draft);
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELETE DRAFT
    // ══════════════════════════════════════════════════════════════════════
    @Transactional
    public void deleteDraft(Long draftId, Long userId) {
        DraftOrder draft = draftOrderRepository.findByIdAndUserId(draftId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn nháp: " + draftId));
        draftOrderRepository.delete(draft);
    }

    // ══════════════════════════════════════════════════════════════════════
    // STOCK CHECK
    // ══════════════════════════════════════════════════════════════════════
    @Transactional(readOnly = true)
    public StockCheckResult checkStockForDraft(Long draftId, Long userId) {
        DraftOrder draft = draftOrderRepository.findByIdAndUserId(draftId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn nháp: " + draftId));

        List<OutOfStockItem> outOfStockItems = new ArrayList<>();
        Map<Long, BigDecimal> neededMap = new HashMap<>();

        for (DraftOrderItem item : draft.getDraftItems()) {
            if (Boolean.TRUE.equals(item.getIsPromo())) continue;
            // Gom ingredient cần thiết (simplified — nếu có ingredient map thì dùng)
            // Ở đây dùng product trực tiếp nếu không có recipe
        }

        return new StockCheckResult(outOfStockItems.isEmpty(), outOfStockItems);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GENERATE INVOICE PDF FROM DRAFT
    // ══════════════════════════════════════════════════════════════════════
    @Transactional(readOnly = true)
    public byte[] generateDraftInvoice(Long draftId, Long userId) throws Exception {
        DraftOrder draft = draftOrderRepository.findByIdAndUserId(draftId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn nháp: " + draftId));

        BigDecimal subtotalGross    = calcSubtotal(draft);
        BigDecimal discountAmt      = calcDiscountAmount(draft);
        BigDecimal netAfterDiscount = subtotalGross.subtract(discountAmt);
        BigDecimal surcharge        = draft.getSurcharge() != null ? draft.getSurcharge() : BigDecimal.ZERO;

        // ── Build VAT breakdown — tính trên NET sau giảm giá, phân bổ theo tỷ lệ ──
        Map<Integer, BigDecimal> vatBreakdownInclusive = new LinkedHashMap<>();
        Map<Integer, BigDecimal> vatBreakdownExclusive = new LinkedHashMap<>();

        if (draft.getDraftItems() != null) {
            for (DraftOrderItem item : draft.getDraftItems()) {
                if (Boolean.TRUE.equals(item.getIsPromo())) continue;
                Integer rate = item.getVatRate();
                if (rate == null || rate == 0) continue;

                BigDecimal qty      = item.getQuantity()  != null ? item.getQuantity()  : BigDecimal.ONE;
                BigDecimal price    = item.getUnitPrice()  != null ? item.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal lineGross = price.multiply(qty);

                // Phân bổ giảm giá bill cho dòng này theo tỷ lệ
                BigDecimal proportion   = subtotalGross.compareTo(BigDecimal.ZERO) > 0
                        ? lineGross.divide(subtotalGross, 10, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal lineDiscount = discountAmt.multiply(proportion);
                BigDecimal lineNet      = lineGross.subtract(lineDiscount);

                boolean isInclusive = "INCLUSIVE".equalsIgnoreCase(item.getVatMode());
                BigDecimal vatAmt;
                if (isInclusive) {
                    vatAmt = lineNet
                            .multiply(BigDecimal.valueOf(rate))
                            .divide(BigDecimal.valueOf(100 + rate), 2, RoundingMode.HALF_UP);
                    vatBreakdownInclusive.merge(rate, vatAmt, BigDecimal::add);
                } else {
                    vatAmt = lineNet
                            .multiply(BigDecimal.valueOf(rate))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    vatBreakdownExclusive.merge(rate, vatAmt, BigDecimal::add);
                }
            }
        }

        BigDecimal totalInclusiveVat = vatBreakdownInclusive.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExclusiveVat = vatBreakdownExclusive.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // finalAmt = NET sau giảm + VAT exclusive + phụ phí
        BigDecimal finalAmt = netAfterDiscount.add(totalExclusiveVat).add(surcharge)
                .setScale(2, RoundingMode.HALF_UP);

        Long deliveryTime = draft.getScheduledAt() != null ? draft.getScheduledAt() : draft.getDeliveryDatetime();

        String customerName = draft.getCustomer().getCustomerType() == Customer.CustomerType.RETAIL ? draft.getCustomer().getName() : draft.getCustomer().getCompanyName();
        InvoiceDTO invoiceDTO = InvoiceDTO.builder()
                .orderId(draft.getId())
                .orderCode("DRAFT-" + draft.getDraftCode())
                .customerName(customerName)
                .customerPhone(draft.getCustomerPhone())
                .customerEmail(draft.getCustomerEmail())
                .shippingAddress(draft.getShippingAddress())
                .notes(draft.getNotes())
                .surcharge(draft.getSurcharge())
                .surchargeDetail(draft.getSurchargeDetail())
                .paymentMethod(draft.getPaymentMethod())
                .createdAt(draft.getCreatedAt())
                .orderedByName(draft.getOrderedByName())
                .receiverName(draft.getReceiverName())
                .deliveryDatetime(deliveryTime)
                .hideAllPrices(draft.getHideAllPrices())
                .deliveryAddress(draft.getReceiverAddress() != null
                        ? draft.getReceiverAddress() : draft.getShippingAddress())
                .totalAmount(subtotalGross)
                .discountAmount(discountAmt)
                .vatBreakdownInclusive(vatBreakdownInclusive)
                .vatBreakdownExclusive(vatBreakdownExclusive)
                .vatAmount(totalInclusiveVat.add(totalExclusiveVat))
                .finalAmount(finalAmt)
                .items(buildInvoiceItems(draft))
                .build();

        boolean showPrices = !Boolean.TRUE.equals(draft.getHideAllPrices())
                && Boolean.TRUE.equals(draft.getShowPrices());

        return invoicePdf.generateDraftInvoicePdf(invoiceDTO, showPrices);
    }

    // ── Financial helpers ─────────────────────────────────────────────────
    private BigDecimal calcSubtotal(DraftOrder draft) {
        if (draft.getDraftItems() == null) return BigDecimal.ZERO;
        return draft.getDraftItems().stream()
                .filter(i -> !Boolean.TRUE.equals(i.getIsPromo()))
                .map(i -> {
                    BigDecimal qty   = i.getQuantity() != null ? i.getQuantity() : BigDecimal.ONE;
                    BigDecimal price = i.getUnitPrice() != null ? i.getUnitPrice() : BigDecimal.ZERO;
                    return price.multiply(qty);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calcDiscountAmount(DraftOrder draft) {
        if (draft.getDiscountAmount() != null && draft.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return draft.getDiscountAmount();
        }
        if (draft.getDiscountRate() != null && draft.getDiscountRate() > 0) {
            return calcSubtotal(draft)
                    .multiply(BigDecimal.valueOf(draft.getDiscountRate()))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

//    private BigDecimal calcFinalAmount(DraftOrder draft) {
//        BigDecimal subtotal  = calcSubtotal(draft);
//        BigDecimal discount  = calcDiscountAmount(draft);
//        BigDecimal surcharge = draft.getSurcharge() != null ? draft.getSurcharge() : BigDecimal.ZERO;
//        return subtotal.subtract(discount).add(surcharge).setScale(2, RoundingMode.HALF_UP);
//    }

    private BigDecimal calcFinalAmount(List<OrderItem> orderItems,
                                       BigDecimal subtotal,
                                       BigDecimal afterDiscount,
                                       BigDecimal surcharge) {
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItem item : orderItems) {
            int rate = item.getVatRate() == null ? 0 : item.getVatRate();

            BigDecimal proportion = subtotal.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : item.getSubtotal().divide(subtotal, 10, RoundingMode.HALF_UP);
            BigDecimal itemAfterDisc = afterDiscount.multiply(proportion);

            if (rate == 0 || !"EXCLUSIVE".equals(item.getVatMode())) {
                // Không VAT hoặc INCLUSIVE: TT = round(itemAfterDisc)
                total = total.add(itemAfterDisc.setScale(0, RoundingMode.HALF_UP));
            } else {
                // EXCLUSIVE: TT = round(itemAfterDisc + VAT)
                BigDecimal vat = itemAfterDisc
                        .multiply(BigDecimal.valueOf(rate))
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                total = total.add(itemAfterDisc.add(vat).setScale(0, RoundingMode.HALF_UP));
            }
        }

        return total.add(surcharge != null ? surcharge : BigDecimal.ZERO);
    }

    private List<InvoiceDTO.Item> buildInvoiceItems(DraftOrder draft) {
        if (draft.getDraftItems() == null) return List.of();
        return draft.getDraftItems().stream().map(i -> {
            BigDecimal qty       = i.getQuantity()  != null ? i.getQuantity()  : BigDecimal.ONE;
            BigDecimal unitPrice = i.getSaleType().equals("RETAIL") ? i.getUnitPrice() : i.getUnitPrice().divide(BigDecimal.valueOf(i.getUnitsPerBox()), 2, RoundingMode.HALF_UP);
            BigDecimal subtotal  = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);

            return InvoiceDTO.Item.builder()
                    .productName(i.getProductName())
                    .unitPrice(unitPrice)
                    .quantity(qty)
                    .subtotal(subtotal)
                    .unit(i.getUnit())
                    .saleType(i.getSaleType() != null ? i.getSaleType() : "RETAIL")
                    .unitsPerBox(i.getUnitsPerBox())
                    .defaultPrice(i.getBasePrice())
                    .vatRate(i.getVatRate())
                    .vatMode(i.getVatMode())
                    .notes(Boolean.TRUE.equals(i.getIsPromo())
                            ? "[KM]" + (i.getPromoNote() != null ? " " + i.getPromoNote() : "")
                            : i.getNotes())
                    .ingredientsUsed(List.of())
                    .build();
        }).collect(Collectors.toList());
    }

    // ── toResponse ────────────────────────────────────────────────────────
    private DraftOrderResponse toResponse(DraftOrder draft) {
        List<DraftOrderResponse.SurchargeItemDto> surchargeItemDtos = parseSurchargeDetail(draft.getSurchargeDetail());

        List<DraftOrderResponse.ItemDto> items = draft.getDraftItems() == null ? List.of() :
                draft.getDraftItems().stream().map(i -> DraftOrderResponse.ItemDto.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .productImageUrl(i.getProductImageUrl())
                        .unit(i.getUnit())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .basePrice(i.getBasePrice())
                        .priceMode(i.getPriceMode())
                        .tierId(i.getTierId())
                        .tierName(i.getTierName())
                        .discountPercent(i.getDiscountPercent())
                        .isManualPrice(i.getIsManualPrice())
                        .saleType(i.getSaleType())
                        .unitsPerBox(i.getUnitsPerBox())
                        .isPromo(i.getIsPromo())
                        .promoNote(i.getPromoNote())
                        .itemDiscountRate(i.getItemDiscountRate())
                        .notes(i.getNotes())
                        .subtotal(i.getSubtotal())
                        .vatRate(i.getVatRate())
                        .vatMode(i.getVatMode())
                        .build()).collect(Collectors.toList());

        return DraftOrderResponse.builder()
                .id(draft.getId())
                .draftCode(draft.getDraftCode())
                .customerId(draft.getCustomer() != null ? draft.getCustomer().getId() : null)
                .customerName(draft.getCustomer().getCustomerType() == Customer.CustomerType.RETAIL ? draft.getCustomer().getName() : draft.getCustomer().getCompanyName())
                .customerPhone(draft.getCustomerPhone())
                .customerEmail(draft.getCustomerEmail())
                .shippingAddress(draft.getShippingAddress())
                .receiverName(draft.getReceiverName())
                .receiverPhone(draft.getReceiverPhone())
                .receiverAddress(draft.getReceiverAddress())
                .receiverInfoId(draft.getReceiverInfoId())
                .notes(draft.getNotes())
                .paymentMethod(draft.getPaymentMethod())
                .discountRate(draft.getDiscountRate())
                .discountAmount(draft.getDiscountAmount())
                .surcharge(draft.getSurcharge())
                .surchargeDetail(draft.getSurchargeDetail())
                .surchargeItems(surchargeItemDtos)
                .warehouseId(draft.getWarehouseId())
                .warehouseName(draft.getWarehouseName())
                .deliveryDatetime(draft.getDeliveryDatetime())
                .orderedByName(draft.getOrderedByName())
                .showPrices(draft.getShowPrices())
                .hideAllPrices(draft.getHideAllPrices())
                .type(draft.getType())
                .scheduledAt(draft.getScheduledAt())
                .createdAt(draft.getCreatedAt())
                .updatedAt(draft.getUpdatedAt())
                .items(items)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<DraftOrderResponse.SurchargeItemDto> parseSurchargeDetail(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return raw.stream().map(m -> DraftOrderResponse.SurchargeItemDto.builder()
                    .name(String.valueOf(m.getOrDefault("name", "")))
                    .amount(m.get("amount") != null ? new BigDecimal(m.get("amount").toString()) : BigDecimal.ZERO)
                    .build()).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private String generateDraftCode(String type) {
        String prefix = "SCHEDULED".equals(type) ? "SCH" : "DFT";
        return prefix + "-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9000 + 1000);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner result types
    // ══════════════════════════════════════════════════════════════════════
    public record StockCheckResult(boolean sufficient, List<OutOfStockItem> outOfStockItems) {}

    public record OutOfStockItem(
            Long   ingredientId,
            String ingredientName,
            double needed,
            double available,
            List<String> affectedProducts
    ) {}
}