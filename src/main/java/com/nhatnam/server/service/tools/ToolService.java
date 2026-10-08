package com.nhatnam.server.service.tools;

import com.nhatnam.server.dto.tools.ToolDtos.*;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.Product;
import com.nhatnam.server.entity.tools.*;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.ProductRepository;
import com.nhatnam.server.repository.tools.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolService {

    private final ToolInvoiceDetailRepository detailRepo;
    private final ToolInvoiceTrackingRepository trackingRepo;
    private final ToolSalesRecordRepository salesRepo;
    private final ToolCustomerRepository customerRepo;
    private final ToolReceiptOutputRepository receiptRepo;
    private final ToolConfigRepository configRepo;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    // ─── CONFIG ────────────────────────────────────────────────────────────

    public String getConfig(String key) {
        return configRepo.findByConfigKey(key).map(ToolConfig::getConfigValue).orElse(null);
    }

    public void setConfig(String key, String value) {
        ToolConfig cfg = configRepo.findByConfigKey(key).orElse(ToolConfig.builder().configKey(key).build());
        cfg.setConfigValue(value);
        configRepo.save(cfg);
    }

    // ─── ALL DATA ──────────────────────────────────────────────────────────

    public AllDataDto getAllData() {
        return AllDataDto.builder()
                .receipts(receiptRepo.findAllByOrderByRowIndexAsc().stream().map(this::toReceiptDto).collect(Collectors.toList()))
                .invoiceDetails(detailRepo.findAllByOrderBySttAsc().stream().map(this::toDetailDto).collect(Collectors.toList()))
                .trackingCount((int) trackingRepo.count())
                .salesCount((int) salesRepo.count())
                .customerCount((int) customerRepo.count())
                .currentDocNumber(getConfig("current_doc_number"))
                .doneUpToRow(getConfig("done_up_to_row"))
                .build();
    }

    // ─── CHECK DUPLICATE ORDER NUMBER ──────────────────────────────────────

    public boolean isOrderNumberExists(String orderNumber) {
        return detailRepo.findByOrderNumber(orderNumber.trim()).isPresent();
    }

    // ─── INVOICE DETAIL + AUTO GENERATE RECEIPT ────────────────────────────

    @Transactional
    public InvoiceDetailDto addInvoiceDetail(InvoiceDetailReq req) {
        int nextStt = (int) detailRepo.count() + 1;

        ToolInvoiceDetail detail = ToolInvoiceDetail.builder()
                .stt(nextStt)
                .orderNumber(req.getOrderNumber().trim())
                .amount(req.getAmount())
                .invoiceDate(req.getInvoiceDate().trim())
                .build();

        StringBuilder errors = new StringBuilder();
        String orderNum = req.getOrderNumber().trim();
        String fInv = null;
        String customerName = null;
        String fInv7 = null;
        String custCode = null;
        String custAddr = null;
        String tenKhachHangFull = null;

        Optional<ToolInvoiceTracking> trackingOpt = findTrackingByInvoice(orderNum);
        if (trackingOpt.isEmpty()) {
            errors.append("Không tìm thấy Invoice ").append(orderNum).append(" trong Theo dõi Invoice. ");
        } else {
            ToolInvoiceTracking t = trackingOpt.get();
            fInv = t.getFInv();
            customerName = t.getCustomer();
            if (fInv == null || fInv.isBlank()) {
                errors.append("Invoice ").append(orderNum).append(" không có F.Inv. ");
            }
        }

        if (fInv != null && !fInv.isBlank()) {
            try {
                String cleaned = fInv.contains("-") ? fInv.substring(0, fInv.indexOf("-")).trim() : fInv.trim();
                long num = Math.round(Double.parseDouble(cleaned));
                fInv7 = String.format("%07d", num);
            } catch (Exception e) {
                errors.append("Không thể format F.Inv '").append(fInv).append("'. ");
            }
        }

        // Cross-check Sales
        if (fInv7 != null) {
            Optional<ToolSalesRecord> salesOpt = findSalesBySoHoaDon(fInv7);
            if (salesOpt.isEmpty()) {
                log.warn("[Single] Không tìm thấy HĐ {} (fInv={}) trong Bán hàng", fInv7, fInv);
            }
        }

        // Tra KH: tìm được → dùng KH; không → Khách lẻ
        if (customerName != null && !customerName.isBlank()) {
            Optional<ToolCustomer> custOpt = customerRepo.findFirstByMaKhachHangIgnoreCase(customerName.trim());
            if (custOpt.isEmpty()) {
                custOpt = customerRepo.findFirstByTenKhachHangIgnoreCase(customerName.trim());
            }
            if (custOpt.isPresent()) {
                ToolCustomer c = custOpt.get();
                custCode = c.getMaKhachHang() != null ? c.getMaKhachHang().trim() : null;
                custAddr = c.getDiaChi();
                tenKhachHangFull = c.getTenKhachHang();
            } else {
                tenKhachHangFull = "Khách lẻ";
                log.info("[Single] Không tìm thấy KH '{}' cho đơn {} → dùng Khách lẻ", customerName, orderNum);
            }
        } else {
            tenKhachHangFull = "Khách lẻ";
        }

        String errorNote = errors.length() > 0 ? errors.toString().trim() : null;
        detail.setErrorNote(errorNote);
        detail.setFInv(fInv);
        detail.setFInv7(fInv7);
        detail.setCustomerName(customerName);
        detail.setMaKhachHang(custCode);
        detail.setTenKhachHangFull(tenKhachHangFull);
        detailRepo.save(detail);

        // Chỉ cần fInv7 là tạo được phiếu thu
        if (fInv7 != null) {
            int nextRow = (int) receiptRepo.count() + 1;
            String docNum = generateNextDocNumber();
            String formattedDate = formatDate(req.getInvoiceDate().trim());

            String lyDoNop = "Phiếu thu tiền mặt khách hàng";
            String dienGiai = "Thu tiền Khách lẻ theo hóa đơn " + fInv7;

            ToolReceiptOutput receipt = ToolReceiptOutput.builder()
                    .rowIndex(nextRow)
                    .ngayHachToan(formattedDate)
                    .ngayChungTu(formattedDate)
                    .soChungTu(docNum)
                    .maDoiTuong(custCode != null ? custCode : "")
                    .tenDoiTuong(tenKhachHangFull != null ? tenKhachHangFull.trim()
                            : (customerName != null ? customerName.trim() : "Khách lẻ"))
                    .diaChi(custAddr != null ? custAddr : "")
                    .lyDoNop(lyDoNop)
                    .dienGiaiLyDoNop(dienGiai)
                    .loaiTien("VND")
                    .dienGiai(dienGiai)
                    .tkNo("1111")
                    .tkCo("131")
                    .soTien(req.getAmount() != null ? req.getAmount().toBigInteger().toString() : "")
                    .doiTuong("")
                    .srcOrder(orderNum)
                    .srcFInv(fInv)
                    .build();
            receiptRepo.save(receipt);
        } else if (errorNote == null) {
            detail.setErrorNote("Thiếu dữ liệu để tạo phiếu thu");
            detailRepo.save(detail);
        }

        return toDetailDto(detail);
    }

    /**
     * Batch: mỗi req là 1 dòng chi tiết.
     * - amount chỉ lấy từ group leader (idx=0).
     * - fInv (raw, vd "4226") hiển thị lên UI cột F.INV.
     * - fInv7 (pad 0, vd "0004226") chỉ dùng tra bảng Bán hàng.
     * - customer lấy từ tracking, KHÔNG ghi đè bằng sales.
     */
    @Transactional
    public List<InvoiceDetailDto> addBatchInvoiceDetail(List<InvoiceDetailReq> reqs) {
        List<InvoiceDetailDto> results = new ArrayList<>();
        if (reqs == null || reqs.isEmpty()) return results;

        // ─── CHECK TRÙNG ─────────────────────────────────────────────────────
        List<String> existingOrderNumbers = new ArrayList<>();
        for (InvoiceDetailReq req : reqs) {
            if (req.getOrderNumber() == null || req.getOrderNumber().isBlank()) continue;
            String orderNum = req.getOrderNumber().trim();
            if (detailRepo.findByOrderNumber(orderNum).isPresent()) {
                existingOrderNumbers.add(orderNum);
            }
        }
        if (!existingOrderNumbers.isEmpty()) {
            throw new RuntimeException("Các phiếu đã nhập rồi, không thể nhập lại: "
                    + String.join(", ", existingOrderNumbers));
        }

        record ResolvedItem(ToolInvoiceDetail detail, String fInv7, String custCode,
                            String customerName, String custAddr, String tenKhachHangFull) {}
        List<ResolvedItem> resolved = new ArrayList<>();
        List<String> batchErrors = new ArrayList<>();

        String groupId = java.util.UUID.randomUUID().toString().substring(0, 8);

        for (int idx = 0; idx < reqs.size(); idx++) {
            InvoiceDetailReq req = reqs.get(idx);
            if (req.getOrderNumber() == null || req.getOrderNumber().isBlank()) continue;

            String orderNum = req.getOrderNumber().trim();
            boolean isLeader = (idx == 0);

            ToolInvoiceDetail detail = ToolInvoiceDetail.builder()
                    .orderNumber(orderNum)
                    .amount(isLeader ? req.getAmount() : java.math.BigDecimal.ZERO)
                    .invoiceDate(req.getInvoiceDate() != null ? req.getInvoiceDate().trim() : null)
                    .stt((int) detailRepo.count() + 1)
                    .groupId(groupId)
                    .groupLeader(isLeader)
                    .build();

            StringBuilder errors = new StringBuilder();
            String fInv = null, customerName = null, fInv7 = null;
            String custCode = null, custAddr = null, tenKhachHangFull = null, valueFromTracking = null;

            // 1) Tra Theo dõi Invoice
            Optional<ToolInvoiceTracking> trackOpt = findTrackingByInvoice(orderNum);
            if (trackOpt.isEmpty()) {
                errors.append("Không tìm thấy '").append(orderNum).append("' trong Theo dõi Invoice. ");
            } else {
                fInv = trackOpt.get().getFInv();
                customerName = trackOpt.get().getCustomer();
                valueFromTracking = trackOpt.get().getValue();
                if (fInv == null || fInv.isBlank()) {
                    String errMsg = "Đơn hàng " + orderNum + " không có F.Inv";
                    batchErrors.add(errMsg);
                    errors.append(errMsg).append(". ");
                }
            }

            // 2) Format fInv7
            if (fInv != null && !fInv.isBlank()) {
                try {
                    String cleaned = fInv.contains("-") ? fInv.substring(0, fInv.indexOf("-")).trim() : fInv.trim();
                    long num = Math.round(Double.parseDouble(cleaned));
                    fInv7 = String.format("%07d", num);
                } catch (Exception e) {
                    errors.append("Không thể format F.Inv '").append(fInv).append("'. ");
                }
            }

            // 3) Cross-check Sales
            if (fInv7 != null) {
                Optional<ToolSalesRecord> salesOpt = findSalesBySoHoaDon(fInv7);
                if (salesOpt.isEmpty()) {
                    log.warn("[Batch] Không tìm thấy HĐ {} (fInv={}) trong Bán hàng (order {})",
                            fInv7, fInv, orderNum);
                }
            }

            // 4) Tra Khách hàng: nếu tìm được → dùng KH; nếu không → Khách lẻ
            if (customerName != null && !customerName.isBlank()) {
                Optional<ToolCustomer> custOpt = customerRepo.findFirstByMaKhachHangIgnoreCase(customerName.trim());
                if (custOpt.isEmpty()) {
                    custOpt = customerRepo.findFirstByTenKhachHangIgnoreCase(customerName.trim());
                }
                if (custOpt.isPresent()) {
                    ToolCustomer c = custOpt.get();
                    custCode = c.getMaKhachHang() != null ? c.getMaKhachHang().trim() : null;
                    custAddr = c.getDiaChi();
                    tenKhachHangFull = c.getTenKhachHang();
                } else {
                    // Không tìm được KH → Khách lẻ
                    tenKhachHangFull = "Khách lẻ";
                    custCode = null;
                    custAddr = null;
                    log.info("[Batch] Không tìm thấy KH '{}' cho đơn {} → dùng Khách lẻ",
                            customerName, orderNum);
                }
            } else {
                // Không có customerName từ tracking → Khách lẻ
                tenKhachHangFull = "Khách lẻ";
            }

            detail.setErrorNote(errors.length() > 0 ? errors.toString().trim() : null);
            detail.setFInv(fInv);
            detail.setFInv7(fInv7);
            detail.setCustomerName(customerName);
            detail.setMaKhachHang(custCode);
            detail.setTenKhachHangFull(tenKhachHangFull);
            detail.setValue(valueFromTracking);
            detailRepo.save(detail);
            results.add(toDetailDto(detail));

            // Chỉ cần fInv7 là có thể tạo phiếu thu (KH có thể là Khách lẻ)
            if (fInv7 != null) {
                resolved.add(new ResolvedItem(detail, fInv7, custCode, customerName, custAddr, tenKhachHangFull));
            }
        }

        // Không tạo phiếu thu nếu có lỗi F.Inv
        if (!batchErrors.isEmpty()) {
            log.warn("[Batch] Không tạo phiếu thu do lỗi F.Inv: {}", batchErrors);
            return results;
        }

        if (!resolved.isEmpty()) {
            // Kiểm tra cùng khách hàng:
            // - Nếu tất cả đều là Khách lẻ (custCode == null) → bỏ qua check
            // - Nếu có ít nhất 1 KH thật → tất cả phải cùng KH thật đó
            boolean allKhachLe = resolved.stream().allMatch(r -> r.custCode() == null);
            if (!allKhachLe) {
                String firstCustKey = resolved.get(0).custCode() != null
                        ? resolved.get(0).custCode().trim().toLowerCase()
                        : resolved.get(0).customerName().trim().toLowerCase();
                List<String> diffCust = resolved.stream()
                        .filter(r -> {
                            String key = r.custCode() != null
                                    ? r.custCode().trim().toLowerCase()
                                    : r.customerName().trim().toLowerCase();
                            return !key.equals(firstCustKey);
                        })
                        .map(r -> r.detail().getOrderNumber())
                        .collect(Collectors.toList());
                if (!diffCust.isEmpty()) {
                    for (ResolvedItem ri : resolved) {
                        String key = ri.custCode() != null
                                ? ri.custCode().trim().toLowerCase()
                                : ri.customerName().trim().toLowerCase();
                        if (!key.equals(firstCustKey)) {
                            ri.detail().setErrorNote("Đơn " + ri.detail().getOrderNumber()
                                    + " có KH '" + ri.customerName()
                                    + "' khác với đơn đầu ('" + resolved.get(0).customerName() + "')");
                            detailRepo.save(ri.detail());
                        }
                    }
                    return results;
                }
            }

            java.math.BigDecimal grandTotal = reqs.get(0).getAmount() != null
                    ? reqs.get(0).getAmount() : java.math.BigDecimal.ZERO;

            String invoiceList = resolved.stream().map(ResolvedItem::fInv7).collect(Collectors.joining(","));
            String dienGiai = "Thu tiền Khách lẻ theo hóa đơn " + invoiceList;
            String lyDoNop  = "Phiếu thu tiền mặt khách hàng";

            ResolvedItem first = resolved.get(0);
            String formattedDate = formatDate(reqs.get(0).getInvoiceDate() != null
                    ? reqs.get(0).getInvoiceDate().trim() : "");
            String docNum = generateNextDocNumber();

            ToolReceiptOutput receipt = ToolReceiptOutput.builder()
                    .rowIndex((int) receiptRepo.count() + 1)
                    .ngayHachToan(formattedDate)
                    .ngayChungTu(formattedDate)
                    .soChungTu(docNum)
                    .maDoiTuong(first.custCode() != null ? first.custCode() : "")
                    .tenDoiTuong(first.tenKhachHangFull() != null
                            ? first.tenKhachHangFull().trim()
                            : (first.customerName() != null ? first.customerName().trim() : "Khách lẻ"))
                    .diaChi(first.custAddr() != null ? first.custAddr() : "")
                    .lyDoNop(lyDoNop)
                    .dienGiaiLyDoNop(dienGiai)
                    .loaiTien("VND")
                    .dienGiai("")
                    .tkNo("1111").tkCo("131")
                    .soTien(grandTotal.toBigInteger().toString())
                    .doiTuong("")
                    .srcOrder(resolved.stream().map(r -> r.detail().getOrderNumber()).collect(Collectors.joining(",")))
                    .srcFInv(resolved.stream().map(ResolvedItem::fInv7).collect(Collectors.joining(",")))
                    .build();
            receiptRepo.save(receipt);
        }

        return results;
    }

    @Transactional(readOnly = true)
    public List<LookupResultDto> lookupInvoiceBatch(List<InvoiceDetailReq> reqs) {
        List<LookupResultDto> results = new ArrayList<>();
        if (reqs == null || reqs.isEmpty()) return results;

        for (InvoiceDetailReq req : reqs) {
            if (req.getOrderNumber() == null || req.getOrderNumber().isBlank()) continue;
            String orderNum = req.getOrderNumber().trim();

            StringBuilder errors = new StringBuilder();
            String fInv = null, customerName = null, fInv7 = null;
            String tenKhachHangFull = null, valueFromTracking = null;

            // 1) Tra tracking
            Optional<ToolInvoiceTracking> trackOpt = findTrackingByInvoice(orderNum);
            if (trackOpt.isEmpty()) {
                errors.append("Không tìm thấy '").append(orderNum).append("' trong Theo dõi Invoice. ");
            } else {
                fInv = trackOpt.get().getFInv();
                customerName = trackOpt.get().getCustomer();
                valueFromTracking = trackOpt.get().getValue();
                if (fInv == null || fInv.isBlank()) {
                    errors.append("Đơn hàng ").append(orderNum).append(" không có F.Inv. ");
                }
            }

            // 2) Format fInv7
            if (fInv != null && !fInv.isBlank()) {
                try {
                    String cleaned = fInv.contains("-") ? fInv.substring(0, fInv.indexOf("-")).trim() : fInv.trim();
                    long num = Math.round(Double.parseDouble(cleaned));
                    fInv7 = String.format("%07d", num);
                } catch (Exception e) {
                    errors.append("Không thể format F.Inv '").append(fInv).append("'. ");
                }
            }

            // 3) Cross-check Sales
            if (fInv7 != null) {
                Optional<ToolSalesRecord> salesOpt = findSalesBySoHoaDon(fInv7);
                if (salesOpt.isEmpty()) {
                    log.warn("[Lookup] Không tìm thấy HĐ {} trong Bán hàng (order {})", fInv7, orderNum);
                }
            }

            // 4) Tra khách hàng (full name)
            if (customerName != null && !customerName.isBlank()) {
                Optional<ToolCustomer> custOpt = customerRepo.findFirstByMaKhachHangIgnoreCase(customerName.trim());
                if (custOpt.isEmpty()) custOpt = customerRepo.findFirstByTenKhachHangIgnoreCase(customerName.trim());
                if (custOpt.isEmpty()) {
                    errors.append("Không tìm thấy KH '").append(customerName).append("'. ");
                } else {
                    tenKhachHangFull = custOpt.get().getTenKhachHang();
                }
            }

            results.add(LookupResultDto.builder()
                    .orderNumber(orderNum)
                    .value(valueFromTracking)
                    .customerName(customerName)
                    .tenKhachHangFull(tenKhachHangFull)
                    .finv(fInv)
                    .finv7(fInv7)
                    .errorNote(!errors.isEmpty() ? errors.toString().trim() : null)
                    .build());
        }
        return results;
    }
    /**
     * Tìm tracking theo Invoice.
     * Ưu tiên exact match, fallback ignore-case + trim để tránh miss do import.
     */
    private Optional<ToolInvoiceTracking> findTrackingByInvoice(String invoice) {
        if (invoice == null) return Optional.empty();
        Optional<ToolInvoiceTracking> opt = trackingRepo.findFirstByInvoice(invoice);
        if (opt.isPresent()) return opt;
        String needle = invoice.trim();
        return trackingRepo.findAllByOrderByIdAsc().stream()
                .filter(t -> t.getInvoice() != null && t.getInvoice().trim().equalsIgnoreCase(needle))
                .findFirst();
    }

    /**
     * Tra Sales theo Số hóa đơn, thử cả 2 dạng:
     *  - Có pad 0: "0004226"
     *  - Không pad 0: "4226"
     * (Bảng Sales thường lưu không pad 0)
     */
    private Optional<ToolSalesRecord> findSalesBySoHoaDon(String fInv7) {
        if (fInv7 == null || fInv7.isBlank()) return Optional.empty();
        Optional<ToolSalesRecord> opt = salesRepo.findFirstBySoHoaDon(fInv7);
        if (opt.isPresent()) return opt;
        String noPad = fInv7.replaceFirst("^0+", "");
        if (!noPad.equals(fInv7) && !noPad.isBlank()) {
            opt = salesRepo.findFirstBySoHoaDon(noPad);
        }
        return opt;
    }

    @Transactional
    public void updateSoChungTuFromRow(Long receiptId, String newSoChungTu) {
        List<ToolReceiptOutput> rows = receiptRepo.findAllByOrderByRowIndexAsc();

        String prefix = newSoChungTu.replaceAll("\\d+$", "");
        String numPart = newSoChungTu.substring(prefix.length());
        int numLen = numPart.length();
        long startNum = Long.parseLong(numPart);

        boolean found = false;
        for (ToolReceiptOutput r : rows) {
            if (!found && r.getId().equals(receiptId)) {
                found = true;
            }
            if (found) {
                r.setSoChungTu(prefix + String.format("%0" + numLen + "d", startNum));
                startNum++;
            }
        }
        receiptRepo.saveAll(rows);
        setConfig("current_doc_number", prefix + String.format("%0" + numLen + "d", startNum - 1));
    }

    private String formatDate(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] parts = raw.split("/");
        if (parts.length < 3) return raw;
        String day = parts[0].trim();
        String month = parts[1].trim();
        String year = parts[2].trim();
        if (day.length() == 1) day = "0" + day;
        if (month.length() == 1) month = "0" + month;
        if (year.length() == 2) year = "20" + year;
        return day + "/" + month + "/" + year;
    }

    private String generateNextDocNumber() {
        String current = getConfig("current_doc_number");
        if (current == null || current.isBlank()) {
            current = "PT00000001";
            setConfig("current_doc_number", current);
            return current;
        }
        String prefix = current.replaceAll("\\d+$", "");
        String numPart = current.substring(prefix.length());
        long num = Long.parseLong(numPart) + 1;
        String next = prefix + String.format("%0" + numPart.length() + "d", num);
        setConfig("current_doc_number", next);
        return next;
    }

    @Transactional
    public void renumberDocuments(String oldDoc, String newDoc) {
        List<ToolReceiptOutput> rows = receiptRepo.findAllByOrderByRowIndexAsc();
        String prefix = newDoc.replaceAll("\\d+$", "");
        String numPart = newDoc.substring(prefix.length());
        int numLen = numPart.length();
        long startNum = Long.parseLong(numPart);
        boolean found = false;

        for (ToolReceiptOutput r : rows) {
            if (!found && oldDoc.equals(r.getSoChungTu())) {
                found = true;
            }
            if (found) {
                r.setSoChungTu(prefix + String.format("%0" + numLen + "d", startNum));
                startNum++;
            }
        }
        receiptRepo.saveAll(rows);
        setConfig("current_doc_number", prefix + String.format("%0" + numLen + "d", startNum - 1));
    }

    @Transactional
    public ImportResult importTracking(List<Map<String, String>> rows) {
        Set<String> existing = trackingRepo.findAllByOrderByIdAsc().stream()
                .map(ToolInvoiceTracking::getInvoice)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());

        int imported = 0, skipped = 0;
        for (Map<String, String> row : rows) {
            String invoice = (row.getOrDefault("Invoice", "")).trim();
            if (invoice.isEmpty() || existing.contains(invoice)) {
                skipped++;
                continue;
            }
            trackingRepo.save(ToolInvoiceTracking.builder()
                    .invoiceDate(row.getOrDefault("Date", ""))
                    .invoice(invoice)
                    .customer(row.getOrDefault("Customer", ""))
                    .value(row.getOrDefault("Value", ""))
                    .fInv(row.getOrDefault("F.Inv", ""))
                    .cod(row.getOrDefault("COD", ""))
                    .build());
            existing.add(invoice);
            imported++;
        }
        return ImportResult.builder().imported(imported).skipped(skipped)
                .message("Import " + imported + " dòng, bỏ qua " + skipped + " dòng trùng").build();
    }

    @Transactional
    public ImportResult importSales(List<Map<String, String>> rows) {
        Set<String> existing = salesRepo.findAllByOrderByIdAsc().stream()
                .map(ToolSalesRecord::getSoChungTu)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());

        int imported = 0, skipped = 0;
        for (Map<String, String> row : rows) {
            String soChungTu = (row.getOrDefault("Số chứng từ", "")).trim();
            if (soChungTu.isEmpty() || existing.contains(soChungTu)) {
                skipped++;
                continue;
            }
            salesRepo.save(ToolSalesRecord.builder()
                    .ngayHachToan(row.getOrDefault("Ngày hạch toán", ""))
                    .ngayChungTu(row.getOrDefault("Ngày chứng từ", ""))
                    .soChungTu(soChungTu)
                    .soHoaDon(row.getOrDefault("Số hóa đơn", ""))
                    .khachHang(row.getOrDefault("Khách hàng", ""))
                    .dienGiai(row.getOrDefault("Diễn giải", ""))
                    .tongTienHang(row.getOrDefault("Tổng tiền hàng", ""))
                    .tienChietKhau(row.getOrDefault("Tiền chiết khấu", ""))
                    .tienThueGtgt(row.getOrDefault("Tiền thuế GTGT", ""))
                    .tongTienThanhToan(row.getOrDefault("Tổng tiền thanh toán", ""))
                    .daLapHoaDon(row.getOrDefault("Đã lập hóa đơn", ""))
                    .daXuatHang(row.getOrDefault("Đã xuất hàng", ""))
                    .loaiChungTu(row.getOrDefault("Loại chứng từ", ""))
                    .build());
            existing.add(soChungTu);
            imported++;
        }
        return ImportResult.builder().imported(imported).skipped(skipped)
                .message("Import " + imported + " dòng, bỏ qua " + skipped + " dòng trùng").build();
    }

    @Transactional
    public ImportResult importCustomers(List<Map<String, String>> rows) {
        Set<String> existing = customerRepo.findAllByOrderByIdAsc().stream()
                .map(ToolCustomer::getMaKhachHang)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase())
                .collect(Collectors.toSet());

        int imported = 0, skipped = 0;
        for (Map<String, String> row : rows) {
            String maKH = (row.getOrDefault("Mã khách hàng", "")).trim();
            if (maKH.isEmpty() || existing.contains(maKH.toLowerCase())) {
                skipped++;
                continue;
            }
            customerRepo.save(ToolCustomer.builder()
                    .maKhachHang(maKH)
                    .tenKhachHang(row.getOrDefault("Tên khách hàng", ""))
                    .diaChi(row.getOrDefault("Địa chỉ", ""))
                    .nhomKhNcc(row.getOrDefault("Nhóm KH, NCC", ""))
                    .maSoThue(row.getOrDefault("Mã số thuế", ""))
                    .dienThoai(row.getOrDefault("Điện thoại", ""))
                    .ngungTheoDoi(row.getOrDefault("Ngừng theo dõi", ""))
                    .build());
            existing.add(maKH.toLowerCase());
            imported++;
        }
        return ImportResult.builder().imported(imported).skipped(skipped)
                .message("Import " + imported + " dòng, bỏ qua " + skipped + " dòng trùng").build();
    }

    public void setDoneUpToRow(int row) {
        setConfig("done_up_to_row", String.valueOf(row));
    }

    public Map<String, Object> listTracking(String q, int page, int size) {
        List<Map<String, Object>> all = trackingRepo.findAllByOrderByIdAsc().stream()
                .filter(t -> q == null || q.isBlank()
                        || contains(t.getInvoice(), q) || contains(t.getCustomer(), q)
                        || contains(t.getFInv(), q) || contains(t.getValue(), q)
                        || contains(t.getInvoiceDate(), q) || contains(t.getCod(), q))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("Date", t.getInvoiceDate());
                    m.put("Invoice", t.getInvoice());
                    m.put("Customer", t.getCustomer());
                    m.put("Value", t.getValue());
                    m.put("F.Inv", t.getFInv());
                    m.put("COD", t.getCod());
                    return m;
                }).collect(Collectors.toList());
        return paginate(all, page, size);
    }

    @Transactional
    public void updateTracking(Long id, Map<String, String> body) {
        ToolInvoiceTracking t = trackingRepo.findById(id).orElseThrow();
        if (body.containsKey("Date")) t.setInvoiceDate(body.get("Date"));
        if (body.containsKey("Invoice")) t.setInvoice(body.get("Invoice"));
        if (body.containsKey("Customer")) t.setCustomer(body.get("Customer"));
        if (body.containsKey("Value")) t.setValue(body.get("Value"));
        if (body.containsKey("F.Inv")) t.setFInv(body.get("F.Inv"));
        if (body.containsKey("COD")) t.setCod(body.get("COD"));
        trackingRepo.save(t);
    }

    @Transactional
    public void deleteTracking(Long id) { trackingRepo.deleteById(id); }

    public Map<String, Object> listSales(String q, int page, int size) {
        List<Map<String, Object>> all = salesRepo.findAllByOrderByIdAsc().stream()
                .filter(s -> q == null || q.isBlank()
                        || contains(s.getSoChungTu(), q) || contains(s.getSoHoaDon(), q)
                        || contains(s.getKhachHang(), q) || contains(s.getDienGiai(), q)
                        || contains(s.getNgayHachToan(), q) || contains(s.getNgayChungTu(), q)
                        || contains(s.getTongTienThanhToan(), q) || contains(s.getLoaiChungTu(), q))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("Ngày hạch toán", s.getNgayHachToan());
                    m.put("Ngày chứng từ", s.getNgayChungTu());
                    m.put("Số chứng từ", s.getSoChungTu());
                    m.put("Số hóa đơn", s.getSoHoaDon());
                    m.put("Khách hàng", s.getKhachHang());
                    m.put("Diễn giải", s.getDienGiai());
                    m.put("Tổng tiền hàng", s.getTongTienHang());
                    m.put("Tiền chiết khấu", s.getTienChietKhau());
                    m.put("Tiền thuế GTGT", s.getTienThueGtgt());
                    m.put("Tổng tiền thanh toán", s.getTongTienThanhToan());
                    m.put("Đã lập hóa đơn", s.getDaLapHoaDon());
                    m.put("Đã xuất hàng", s.getDaXuatHang());
                    m.put("Loại chứng từ", s.getLoaiChungTu());
                    return m;
                }).collect(Collectors.toList());
        return paginate(all, page, size);
    }

    @Transactional
    public void updateSales(Long id, Map<String, String> body) {
        ToolSalesRecord s = salesRepo.findById(id).orElseThrow();
        if (body.containsKey("Ngày hạch toán")) s.setNgayHachToan(body.get("Ngày hạch toán"));
        if (body.containsKey("Ngày chứng từ")) s.setNgayChungTu(body.get("Ngày chứng từ"));
        if (body.containsKey("Số chứng từ")) s.setSoChungTu(body.get("Số chứng từ"));
        if (body.containsKey("Số hóa đơn")) s.setSoHoaDon(body.get("Số hóa đơn"));
        if (body.containsKey("Khách hàng")) s.setKhachHang(body.get("Khách hàng"));
        if (body.containsKey("Diễn giải")) s.setDienGiai(body.get("Diễn giải"));
        if (body.containsKey("Tổng tiền hàng")) s.setTongTienHang(body.get("Tổng tiền hàng"));
        if (body.containsKey("Tiền chiết khấu")) s.setTienChietKhau(body.get("Tiền chiết khấu"));
        if (body.containsKey("Tiền thuế GTGT")) s.setTienThueGtgt(body.get("Tiền thuế GTGT"));
        if (body.containsKey("Tổng tiền thanh toán")) s.setTongTienThanhToan(body.get("Tổng tiền thanh toán"));
        if (body.containsKey("Đã lập hóa đơn")) s.setDaLapHoaDon(body.get("Đã lập hóa đơn"));
        if (body.containsKey("Đã xuất hàng")) s.setDaXuatHang(body.get("Đã xuất hàng"));
        if (body.containsKey("Loại chứng từ")) s.setLoaiChungTu(body.get("Loại chứng từ"));
        salesRepo.save(s);
    }

    @Transactional
    public void deleteSales(Long id) { salesRepo.deleteById(id); }

    public Map<String, Object> listCustomers(String q, int page, int size) {
        List<Map<String, Object>> all = customerRepo.findAllByOrderByIdAsc().stream()
                .filter(c -> q == null || q.isBlank()
                        || contains(c.getMaKhachHang(), q) || contains(c.getTenKhachHang(), q)
                        || contains(c.getDiaChi(), q) || contains(c.getDienThoai(), q)
                        || contains(c.getNhomKhNcc(), q) || contains(c.getMaSoThue(), q))
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("Mã khách hàng", c.getMaKhachHang());
                    m.put("Tên khách hàng", c.getTenKhachHang());
                    m.put("Địa chỉ", c.getDiaChi());
                    m.put("Nhóm KH, NCC", c.getNhomKhNcc());
                    m.put("Mã số thuế", c.getMaSoThue());
                    m.put("Điện thoại", c.getDienThoai());
                    m.put("Ngừng theo dõi", c.getNgungTheoDoi());
                    return m;
                }).collect(Collectors.toList());
        return paginate(all, page, size);
    }

    @Transactional
    public void updateCustomer(Long id, Map<String, String> body) {
        ToolCustomer c = customerRepo.findById(id).orElseThrow();
        if (body.containsKey("Mã khách hàng")) c.setMaKhachHang(body.get("Mã khách hàng"));
        if (body.containsKey("Tên khách hàng")) c.setTenKhachHang(body.get("Tên khách hàng"));
        if (body.containsKey("Địa chỉ")) c.setDiaChi(body.get("Địa chỉ"));
        if (body.containsKey("Nhóm KH, NCC")) c.setNhomKhNcc(body.get("Nhóm KH, NCC"));
        if (body.containsKey("Mã số thuế")) c.setMaSoThue(body.get("Mã số thuế"));
        if (body.containsKey("Điện thoại")) c.setDienThoai(body.get("Điện thoại"));
        if (body.containsKey("Ngừng theo dõi")) c.setNgungTheoDoi(body.get("Ngừng theo dõi"));
        customerRepo.save(c);
    }

    @Transactional
    public void deleteCustomer(Long id) { customerRepo.deleteById(id); }

    public Map<String, Object> listInvoiceDetailsPaged(String q, int page, int size) {
        List<ToolInvoiceDetail> all = detailRepo.findAllByOrderBySttAsc();
        List<Map<String, Object>> filtered = all.stream()
                .filter(d -> q == null || q.isBlank()
                        || contains(d.getOrderNumber(), q)
                        || contains(d.getInvoiceDate(), q)
                        || contains(d.getErrorNote(), q)
                        || contains(d.getCustomerName(), q)
                        || contains(d.getFInv(), q)
                        || contains(d.getTenKhachHangFull(), q)
                        || (d.getAmount() != null && d.getAmount().toPlainString().contains(q))
                        || (d.getValue() != null && d.getValue().contains(q)))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("stt", d.getStt());
                    m.put("orderNumber", d.getOrderNumber());
                    m.put("amount", d.getAmount() != null ? d.getAmount().toPlainString() : "");
                    m.put("value", d.getValue());
                    m.put("invoiceDate", d.getInvoiceDate());
                    m.put("customerName", d.getCustomerName());
                    m.put("tenKhachHangFull", d.getTenKhachHangFull());
                    m.put("fInv", d.getFInv());
                    m.put("fInv7", d.getFInv7());
                    m.put("maKhachHang", d.getMaKhachHang());
                    m.put("errorNote", d.getErrorNote());
                    m.put("groupId", d.getGroupId());
                    m.put("groupLeader", d.isGroupLeader());
                    return m;
                }).collect(Collectors.toList());
        return paginate(filtered, page, size);
    }

    @Transactional
    public void deleteInvoiceDetail(Long id) { detailRepo.deleteById(id); }

    @Transactional public void clearTracking() { trackingRepo.deleteAll(); }
    @Transactional public void clearSales() { salesRepo.deleteAll(); }
    @Transactional public void clearCustomers() { customerRepo.deleteAll(); }
    @Transactional public void clearInvoiceDetails() { detailRepo.deleteAll(); }
    @Transactional public void clearReceipts() { receiptRepo.deleteAll(); }

    @Transactional
    public void clearInvoiceDetailsAndReceipts() {
        receiptRepo.deleteAll();
        detailRepo.deleteAll();
    }

    private boolean contains(String field, String q) {
        return field != null && field.toLowerCase().contains(q.toLowerCase());
    }

    private Map<String, Object> paginate(List<Map<String, Object>> all, int page, int size) {
        int total = all.size();
        int from = page * size;
        int to = Math.min(from + size, total);
        List<Map<String, Object>> content = from >= total ? List.of() : all.subList(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", to < total);
        return result;
    }

    // ─── MISA ORDER EXPORT ──────────────────────────────────────────────────

    private static final DateTimeFormatter DD_MM_YYYY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Transactional(readOnly = true)
    public Map<String, Object> listOrdersForMisa(Long from, Long to, Long customerId, String status, int page, int size) {
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (customerId != null) ps.add(cb.equal(root.get("customer").get("id"), customerId));
            if (status != null && !status.isBlank()) {
                try { ps.add(cb.equal(root.get("status"), OrderStatus.valueOf(status))); } catch (Exception ignored) {}
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        List<Order> allOrders = orderRepository.findAll(spec, Sort.by("createdAt").descending());
        int total = allOrders.size();
        int fromIdx = page * size;
        int toIdx = Math.min(fromIdx + size, total);
        List<Order> pageOrders = fromIdx >= total ? List.of() : allOrders.subList(fromIdx, toIdx);

        List<Map<String, Object>> content = pageOrders.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("orderCode", o.getOrderCode());
            m.put("customerName", o.getCustomerName());
            m.put("status", o.getStatus() != null ? o.getStatus().name() : "");
            m.put("finalAmount", o.getFinalAmount());
            m.put("createdAt", o.getCreatedAt());
            m.put("paymentMethod", o.getPaymentMethod());
            int itemCount = o.getOrderItems() != null ? o.getOrderItems().size() : 0;
            m.put("itemCount", itemCount);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", toIdx < total);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> generateMisaData(List<Long> orderIds) {
        List<Order> orders = orderRepository.findAllById(orderIds);
        orders.sort(Comparator.comparingLong(o -> o.getCreatedAt() != null ? o.getCreatedAt() : 0L));

        orders.forEach(o -> {
            if (o.getOrderItems() != null) o.getOrderItems().size();
        });

        Set<Long> productIds = new HashSet<>();
        for (Order order : orders) {
            if (order.getOrderItems() != null) {
                order.getOrderItems().forEach(item -> productIds.add(item.getProductId()));
            }
        }
        Map<Long, Product> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            productRepository.findAllById(productIds).forEach(p -> productMap.put(p.getId(), p));
        }

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Order order : orders) {
            if (order.getOrderItems() == null || order.getOrderItems().isEmpty()) continue;

            String dateStr = order.getCreatedAt() != null
                    ? DD_MM_YYYY.format(Instant.ofEpochMilli(order.getCreatedAt()))
                    : "";
            String customerName = order.getCustomerName() != null ? order.getCustomerName() : "";
            String address = order.getShippingAddress() != null ? order.getShippingAddress() : "";
            String taxCode = order.getTaxCode() != null ? order.getTaxCode() : "";

            Map<String, List<OrderItem>> groupedByMisa = new LinkedHashMap<>();
            for (OrderItem item : order.getOrderItems()) {
                String misaCat = item.getMisaCategorySnapshot();
                if (misaCat == null || misaCat.isBlank()) {
                    Product product = productMap.get(item.getProductId());
                    misaCat = product != null && product.getMisaCategory() != null && !product.getMisaCategory().isBlank()
                            ? product.getMisaCategory()
                            : (item.getProductName() != null ? item.getProductName() : "Khác");
                }
                groupedByMisa.computeIfAbsent(misaCat, k -> new ArrayList<>()).add(item);
            }

            for (Map.Entry<String, List<OrderItem>> entry : groupedByMisa.entrySet()) {
                String misaCat = entry.getKey();
                List<OrderItem> items = entry.getValue();

                BigDecimal totalQtyKg = BigDecimal.ZERO;
                BigDecimal totalAmount = BigDecimal.ZERO;

                for (OrderItem item : items) {
                    BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
                    BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;

                    Integer spec = item.getSpecificationSnapshot();
                    if (spec == null || spec <= 0) {
                        Product product = productMap.get(item.getProductId());
                        if (product != null) spec = product.getSpecification();
                    }

                    boolean isKg = "Kg".equalsIgnoreCase(item.getUnit());
                    if (!isKg && spec != null && spec > 0) {
                        BigDecimal specGr = BigDecimal.valueOf(spec);
                        BigDecimal qtyInKg = qty.multiply(specGr).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
                        BigDecimal lineTotal = qty.multiply(unitPrice);
                        totalAmount = totalAmount.add(lineTotal);
                        totalQtyKg = totalQtyKg.add(qtyInKg);
                    } else {
                        totalQtyKg = totalQtyKg.add(qty);
                        totalAmount = totalAmount.add(qty.multiply(unitPrice));
                    }
                }

                BigDecimal pricePerKg = totalQtyKg.compareTo(BigDecimal.ZERO) > 0
                        ? totalAmount.divide(totalQtyKg, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", order.getId());
                row.put("orderCode", order.getOrderCode());
                row.put("ngayHachToan", dateStr);
                row.put("ngayChungTu", dateStr);
                row.put("soChungTu", "");
                row.put("maKhachHang", "");
                row.put("tenKhachHang", customerName);
                row.put("diaChi", address);
                row.put("maSoThue", taxCode);
                row.put("dienGiai", "Bán hàng cho " + customerName);
                row.put("maHang", misaCat);
                row.put("tenHang", misaCat);
                row.put("dvt", "Kg");
                row.put("soLuong", totalQtyKg.setScale(3, RoundingMode.HALF_UP));
                row.put("donGia", pricePerKg.setScale(0, RoundingMode.HALF_UP));
                row.put("thanhTien", totalAmount.setScale(0, RoundingMode.HALF_UP));
                row.put("tkTienNo", "131");
                row.put("tkDoanhThuCo", "5111");
                rows.add(row);
            }
        }
        return rows;
    }

    // ─── MAPPERS ───────────────────────────────────────────────────────────

    private InvoiceDetailDto toDetailDto(ToolInvoiceDetail d) {
        return InvoiceDetailDto.builder()
                .id(d.getId()).stt(d.getStt())
                .orderNumber(d.getOrderNumber())
                .amount(d.getAmount())
                .invoiceDate(d.getInvoiceDate())
                .errorNote(d.getErrorNote())
                .fInv(d.getFInv())
                .fInv7(d.getFInv7())
                .customerName(d.getCustomerName())
                .maKhachHang(d.getMaKhachHang())
                .tenKhachHangFull(d.getTenKhachHangFull())
                .value(d.getValue())
                .groupId(d.getGroupId())
                .groupLeader(d.isGroupLeader())
                .build();
    }

    private ReceiptOutputDto toReceiptDto(ToolReceiptOutput r) {
        return ReceiptOutputDto.builder()
                .id(r.getId()).rowIndex(r.getRowIndex())
                .ngayHachToan(r.getNgayHachToan())
                .ngayChungTu(r.getNgayChungTu())
                .soChungTu(r.getSoChungTu())
                .maDoiTuong(r.getMaDoiTuong())
                .tenDoiTuong(r.getTenDoiTuong())
                .diaChi(r.getDiaChi())
                .dienGiaiLyDoNop(r.getDienGiaiLyDoNop())
                .loaiTien(r.getLoaiTien())
                .dienGiai(r.getDienGiai())
                .tkNo(r.getTkNo()).tkCo(r.getTkCo())
                .soTien(r.getSoTien())
                .doiTuong(r.getDoiTuong())
                .srcOrder(r.getSrcOrder()).srcFInv(r.getSrcFInv())
                .errorNote(r.getErrorNote())
                .build();
    }
}