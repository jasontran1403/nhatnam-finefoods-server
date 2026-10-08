// PATH: src/main/java/com/nhatnam/server/service/MisaProductCatalogService.java
package com.nhatnam.server.service;

import com.nhatnam.server.entity.MisaProductCatalog;
import com.nhatnam.server.entity.tools.ToolCustomer;
import com.nhatnam.server.repository.MisaProductCatalogRepository;
import com.nhatnam.server.repository.tools.ToolCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class MisaProductCatalogService {

    private final MisaProductCatalogRepository repo;
    private final ToolCustomerRepository customerRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // CRUD
    // ══════════════════════════════════════════════════════════════════════════

    public List<MisaProductCatalog> findAll() {
        return repo.findAllOrdered();
    }

    public Optional<MisaProductCatalog> findByCode(String code) {
        return repo.findByProductCode(code);
    }

    public MisaProductCatalog save(MisaProductCatalog entity) {
        entity.setUpdatedAt(System.currentTimeMillis());
        if (entity.getId() == null) entity.setCreatedAt(System.currentTimeMillis());
        return repo.save(entity);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IMPORT — REPLACE ALL
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public ImportResult importCatalog(List<Map<String, String>> rows) {
        long oldCount = repo.count();
        repo.deleteAll();
        log.info("[MISA Import] Đã xoá {} bản ghi cũ trước khi import", oldCount);

        int skipped  = 0;
        List<Map<String, String>> skippedRows = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<MisaProductCatalog> toSave = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String code = trim(row.get("productCode"));
            String name = trim(row.get("productName"));

            if (name == null || name.isEmpty()) {
                skipped++;
                Map<String, String> info = new LinkedHashMap<>();
                info.put("productCode", code != null ? code : "");
                info.put("productName", "");
                info.put("reason", "Thiếu tên hàng (cột C)");
                skippedRows.add(info);
                continue;
            }

            String originalUnit    = trim(row.get("originalUnit"));
            String vatPercent      = trim(row.get("vatPercent"));
            String taxCode         = trim(row.get("taxCode"));
            String misaCategory    = trim(row.get("misaCategory"));
            String misaProductName = trim(row.get("misaProductName"));
            String kho             = trim(row.get("kho"));
            String tkKho           = trim(row.get("tkKho"));
            String tkGiaVon        = trim(row.get("tkGiaVon"));
            String tkChietKhau     = trim(row.get("tkChietKhau"));    // ← MỚI (cột L)
            String tkDoanhThu      = trim(row.get("tkDoanhThu"));     // ← MỚI (cột M)
            String tkThueGtgt      = trim(row.get("tkThueGtgt"));     // ← MỚI (cột N)

            ParseResult parsed = parseKgPerUnit(name, originalUnit);

            MisaProductCatalog entity = MisaProductCatalog.builder()
                    .productCode(code != null ? code : "")
                    .productName(name)
                    .originalUnit(originalUnit)
                    .vatPercent(vatPercent)
                    .taxCode(taxCode)
                    .misaCategory(misaCategory)
                    .misaProductName(misaProductName)
                    .kgPerUnit(parsed.kgPerUnit)
                    .quyCach(parsed.quyCach)
                    .parseNote(parsed.note)
                    .kho(kho)
                    .tkKho(tkKho)
                    .tkGiaVon(tkGiaVon)
                    .tkChietKhau(tkChietKhau)   // ← MỚI
                    .tkDoanhThu(tkDoanhThu)     // ← MỚI
                    .tkThueGtgt(tkThueGtgt)     // ← MỚI
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            toSave.add(entity);
        }

        repo.saveAll(toSave);
        return new ImportResult(toSave.size(), skipped, skippedRows);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PARSE QUY CÁCH
    // ══════════════════════════════════════════════════════════════════════════
    //
    // Trả về 2 giá trị:
    //   - kgPerUnit : KG của 1 đơn vị NHỎ (hộp / túi / chai / gói / kg...).
    //   - quyCach   : Số đơn vị nhỏ trong 1 THÙNG (nullable).
    //
    // VD1: "Bánh Cà Rốt Cake 8 hộp (1kg/hộp)", ĐVT = Kg
    //      → kgPerUnit = 1.0 (1 hộp = 1 kg), quyCach = 8 (8 hộp/thùng).
    //
    // VD2: "Đế Bánh Tart Bồ Đào Nha 525g x 12 túi / Thùng", ĐVT = Túi
    //      → kgPerUnit = 0.525 (1 túi = 0.525 kg), quyCach = 12 (12 túi/thùng).
    //
    // Khi xử lý hóa đơn FPT, dùng ĐVT trong file FPT để quy đổi:
    //   - FPT ĐVT = Kg      → SL giữ nguyên (đã là kg).
    //   - FPT ĐVT = Thùng   → SL × quyCach × kgPerUnit.
    //   - FPT ĐVT = khác    → SL × kgPerUnit (hộp/túi/chai/gói ↔ cùng mức "đơn vị nhỏ").
    // ══════════════════════════════════════════════════════════════════════════

    static ParseResult parseKgPerUnit(String productName, String originalUnit) {
        if (productName == null || productName.isBlank())
            return new ParseResult(null, null, "Tên sản phẩm rỗng");

        String unitLower = originalUnit != null ? originalUnit.trim().toLowerCase() : "";

        String text = productName.toLowerCase()
                .replace(",", ".")
                .replace("（", "(")
                .replace("）", ")");

        Pattern weightP = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|gr|g|lít|lit|l|ml)", Pattern.CASE_INSENSITIVE);
        Pattern countP = Pattern.compile("(?:x\\s*)?(?:(\\d+)\\s*)(hộp|hop|thùng|thung|khay|túi|tui|chai|gói|goi|bình|binh|lon|cái|cai|cây|cay)", Pattern.CASE_INSENSITIVE);

        List<Double> weights = new ArrayList<>();
        Matcher wm = weightP.matcher(text);
        while (wm.find()) {
            double val = Double.parseDouble(wm.group(1));
            String unit = wm.group(2).toLowerCase();
            double kg = switch (unit) {
                case "kg" -> val;
                case "gr", "g" -> val / 1000.0;
                case "lít", "lit", "l" -> val;
                case "ml" -> val / 1000.0;
                default -> val;
            };
            weights.add(kg);
        }

        List<Integer> counts = new ArrayList<>();
        Matcher cm = countP.matcher(text);
        while (cm.find()) {
            counts.add(Integer.parseInt(cm.group(1)));
        }

        Double firstWeight = weights.isEmpty() ? null : weights.get(0);
        Integer firstCount = counts.isEmpty() ? null : counts.get(0);

        // ── Trường hợp ĐVT = Kg ──
        //   kgPerUnit = weight (nếu có trong tên) hoặc 1.0 (mặc định: 1 đơn vị = 1kg).
        //   quyCach   = count (nếu có; mô tả số đơn vị nhỏ / thùng).
        if (unitLower.equals("kg")) {
            double kg = firstWeight != null ? round(firstWeight) : 1.0;
            Double qc = firstCount != null ? (double) firstCount : null;

            // ── Override cho một số SP đóng gói nguyên khối (1 thùng = 1 can) ──
            //   Các SP này không có khái niệm "N túi/hộp / thùng" nên quyCach = 1.
            //   Khi FPT đưa ĐVT=Thùng: SL × 1 × weight = weight·SL kg.
            if (isWholePackProduct(productName)) {
                qc = 1.0;
            }

            return new ParseResult(kg, qc, null);
        }

        // ── Trường hợp ĐVT = Thùng ──
        //   quyCach = count, kgPerUnit = weight.
        //   Khi FPT đưa Thùng: SL × quyCach × kgPerUnit = kg của N thùng.
        //   Khi FPT đưa Hộp/Túi: SL × kgPerUnit.
        if (unitLower.equals("thùng") || unitLower.equals("thung")) {
            if (firstWeight != null) {
                Double qc = firstCount != null ? (double) firstCount : null;
                return new ParseResult(round(firstWeight), qc, null);
            }
            // Không có weight: fallback — nếu chỉ có count thì không đủ data
            return new ParseResult(null, firstCount != null ? (double) firstCount : null,
                    "Không có trọng lượng trong tên — cần nhập kg/đơn vị thủ công");
        }

        // ── Trường hợp ĐVT = Hộp / Túi / Chai / Gói / Bình / Lon / Cái... ──
        //   kgPerUnit = weight đầu tiên (kg của 1 đơn vị nhỏ).
        //   quyCach   = count đầu tiên (nếu có, đại diện cho "N đơn vị / thùng").
        if (isPieceUnit(unitLower)) {
            if (firstWeight != null) {
                Double qc = firstCount != null ? (double) firstCount : null;
                return new ParseResult(round(firstWeight), qc, null);
            }
            return new ParseResult(null, firstCount != null ? (double) firstCount : null,
                    "Không có trọng lượng trong tên — cần nhập kg/đơn vị thủ công");
        }

        // ── Fallback: ĐVT lạ ──
        if (firstWeight != null) {
            Double qc = firstCount != null ? (double) firstCount : null;
            return new ParseResult(round(firstWeight), qc,
                    "Auto-detect: " + round(firstWeight) + " kg/đơn vị" +
                            (qc != null ? ", quy cách " + qc.intValue() : "") + " (cần kiểm tra)");
        }

        return new ParseResult(null, null, "Không parse được quy cách từ tên: \"" + productName + "\"");
    }

    /**
     * Danh sách SP đóng gói nguyên khối (1 thùng = 1 can, không có N túi/hộp per thùng).
     * Khi khớp, parse logic sẽ ép quyCach = 1 bất kể tên có count hay không.
     * Match: so sánh tên đã bỏ dấu + chuẩn hoá space, không phân biệt hoa thường.
     */
    private static final Set<String> WHOLE_PACK_PRODUCT_NAMES = Set.of(
            "mu tac medium hot mustard 5kg",
            "mu tac mustard senf 5kg"
    );

    private static boolean isWholePackProduct(String productName) {
        if (productName == null) return false;
        return WHOLE_PACK_PRODUCT_NAMES.contains(stripDiacritics(productName));
    }

    private static boolean isPieceUnit(String u) {
        return u.equals("hộp") || u.equals("hop")
                || u.equals("túi") || u.equals("tui")
                || u.equals("gói") || u.equals("goi")
                || u.equals("chai") || u.equals("bình") || u.equals("binh")
                || u.equals("lon") || u.equals("khay")
                || u.equals("cái") || u.equals("cai")
                || u.equals("cây") || u.equals("cay")
                || u.equals("con") || u.equals("cuộn") || u.equals("bó");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROCESS — file báo cáo hóa đơn FPT → format MISA bán hàng
    // ══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> processInvoiceReport(List<Map<String, Object>> invoiceRows) {
        // ── Build 4 map để match đa tầng ──
        //   1. byMisaCode — mã hàng MISA (cột G) — normalize NFC
        //   2. byCode     — mã hàng nội bộ (cột B) — normalize NFC
        //   3. byNameNfc  — tên hàng (cột C + H) — normalize NFC + gộp space
        //   4. byNameLoose — tên hàng bỏ dấu — fallback match mờ
        Map<String, MisaProductCatalog> byMisaCode  = new HashMap<>();
        Map<String, MisaProductCatalog> byCode      = new HashMap<>();
        Map<String, MisaProductCatalog> byNameNfc   = new HashMap<>();
        Map<String, MisaProductCatalog> byNameLoose = new HashMap<>();

        for (MisaProductCatalog p : repo.findAll()) {
            if (p.getMisaCategory() != null && !p.getMisaCategory().isBlank()) {
                byMisaCode.putIfAbsent(normalizeKey(p.getMisaCategory()), p);
            }
            if (p.getProductCode() != null && !p.getProductCode().isBlank()) {
                byCode.putIfAbsent(normalizeKey(p.getProductCode()), p);
            }
            if (p.getProductName() != null && !p.getProductName().isBlank()) {
                byNameNfc.putIfAbsent(normalizeKey(p.getProductName()), p);
                byNameLoose.putIfAbsent(stripDiacritics(p.getProductName()), p);
            }
            if (p.getMisaProductName() != null && !p.getMisaProductName().isBlank()) {
                byNameNfc.putIfAbsent(normalizeKey(p.getMisaProductName()), p);
                byNameLoose.putIfAbsent(stripDiacritics(p.getMisaProductName()), p);
            }
        }

        // ── FIX #2: Load customers ──
        // Trước đây: chỉ map theo mã số thuế (mst) → nếu 1 mst có 2 KH thì bị nhầm.
        // Bây giờ: build 2 map:
        //   - customerByTaxAndCode: key = "mst|maKhachHang" — ưu tiên match cả 2
        //   - customerByTaxCode:    key = "mst"             — fallback khi không có maKhachHang
        Map<String, ToolCustomer> customerByTaxAndCode = new HashMap<>();
        Map<String, ToolCustomer> customerByTaxCode    = new HashMap<>();
        for (ToolCustomer c : customerRepo.findAll()) {
            String tax  = c.getMaSoThue()    != null ? c.getMaSoThue().trim()    : "";
            String code = c.getMaKhachHang() != null ? c.getMaKhachHang().trim() : "";
            if (!tax.isEmpty() && !code.isEmpty()) {
                customerByTaxAndCode.putIfAbsent(tax + "|" + code, c);
            }
            if (!tax.isEmpty()) {
                customerByTaxCode.putIfAbsent(tax, c);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> row : invoiceRows) {
            String maHangRaw  = str(row.get("maHang"));
            String tenHang    = str(row.get("tenHang"));
            double soLuong    = num(row.get("soLuong"));
            double donGia     = num(row.get("donGia"));
            double thanhTien  = num(row.get("thanhTien"));

            String dvtFromFile = str(row.get("dvt")).toLowerCase().trim();

            // ── Match khách hàng ──
            String maSoThue = str(row.get("maSoThue")).trim();
            String tenKhachHang = str(row.get("tenKhachHang"));
            String maKhachHang = str(row.get("maKhachHang"));
            String diaChi = str(row.get("diaChi"));

            boolean isRetailCustomer = tenKhachHang != null &&
                    tenKhachHang.toLowerCase().contains("bán cho người tiêu dùng");

            if (isRetailCustomer) {
                tenKhachHang = "Khách lẻ";
                maKhachHang = "KHACHLE";
                diaChi = "";
            } else if (!maSoThue.isEmpty()) {
                // ── FIX #2: match ưu tiên mst + mã KH, sau đó fallback theo mst ──
                ToolCustomer matched = null;
                String maKhTrim = maKhachHang != null ? maKhachHang.trim() : "";

                // 1) Ưu tiên: nếu invoice có cả mst và mã KH, và trong DB tồn tại
                //    khách hàng khớp cả 2 → lấy khách hàng này (xử lý case 1 mst có 2 KH)
                if (!maKhTrim.isEmpty()) {
                    matched = customerByTaxAndCode.get(maSoThue + "|" + maKhTrim);
                }
                // 2) Fallback: chỉ match theo mst (giữ nguyên hành vi cũ)
                if (matched == null) {
                    matched = customerByTaxCode.get(maSoThue);
                }

                if (matched != null) {
                    maKhachHang = matched.getMaKhachHang() != null ? matched.getMaKhachHang() : maKhachHang;
                    tenKhachHang = matched.getTenKhachHang() != null ? matched.getTenKhachHang() : tenKhachHang;
                    diaChi = matched.getDiaChi() != null ? matched.getDiaChi() : diaChi;
                }
            }

            // ── Match catalog: 4 tầng ──
            String maHangKey    = normalizeKey(maHangRaw);
            String tenHangNfc   = normalizeKey(tenHang);
            String tenHangLoose = stripDiacritics(tenHang);

            MisaProductCatalog cat = null;
            String matchStrategy = null;

            // 1. Mã MISA
            if (!maHangKey.isEmpty()) {
                cat = byMisaCode.get(maHangKey);
                if (cat != null) matchStrategy = "misaCode";
            }
            // 2. Mã nội bộ
            if (cat == null && !maHangKey.isEmpty()) {
                cat = byCode.get(maHangKey);
                if (cat != null) matchStrategy = "productCode";
            }
            // 3. Tên NFC
            if (cat == null && !tenHangNfc.isEmpty()) {
                cat = byNameNfc.get(tenHangNfc);
                if (cat != null) matchStrategy = "name-nfc";
            }
            // 4. Tên bỏ dấu (fallback mờ)
            if (cat == null && !tenHangLoose.isEmpty()) {
                cat = byNameLoose.get(tenHangLoose);
                if (cat != null) matchStrategy = "name-loose";
            }

            double kgPerUnit = 1.0;
            String matchNote = null;
            String finalDvt = "Kg";
            double finalSoLuong = soLuong;
            double finalDonGia = donGia;

            String maHangMisa = "";
            String tenHangMisa = "";
            String kho = "";
            String tkKho = "";
            String tkGiaVon = "";
            String tkChietKhau = "";
            String tkDoanhThu = "";
            String tkThueGtgt = "";

            boolean isAlreadyKg = dvtFromFile.equals("kg")
                    || dvtFromFile.equals("kilogram")
                    || dvtFromFile.equals("kilôgam");
            boolean isThung = dvtFromFile.equals("thùng") || dvtFromFile.equals("thung")
                    || dvtFromFile.equals("case") || dvtFromFile.equals("carton");

            if (cat != null) {
                kgPerUnit = cat.getKgPerUnit() != null ? cat.getKgPerUnit() : 1.0;
                Double quyCach = cat.getQuyCach();

                maHangMisa = cat.getMisaCategory() != null ? cat.getMisaCategory() : "";

                String misaName = cat.getMisaProductName();
                tenHangMisa = (misaName != null && !misaName.isBlank())
                        ? misaName
                        : (cat.getProductName() != null ? cat.getProductName() : "");

                kho         = cat.getKho() != null ? cat.getKho() : "";
                tkKho       = cat.getTkKho() != null ? cat.getTkKho() : "";
                tkGiaVon    = cat.getTkGiaVon() != null ? cat.getTkGiaVon() : "";
                tkChietKhau = cat.getTkChietKhau() != null ? cat.getTkChietKhau() : "";
                tkDoanhThu  = cat.getTkDoanhThu() != null ? cat.getTkDoanhThu() : "";
                tkThueGtgt  = cat.getTkThueGtgt() != null ? cat.getTkThueGtgt() : "";

                // ── Quy đổi SL/Đơn giá sang kg dựa trên ĐVT trong file FPT ──
                //   Kg       → giữ nguyên
                //   Thùng    → SL × quyCach × kgPerUnit  (quyCach = số đơn vị nhỏ / thùng)
                //   Hộp/Túi/… → SL × kgPerUnit
                if (isAlreadyKg) {
                    finalSoLuong = soLuong;
                    finalDonGia = donGia;
                } else if (isThung) {
                    if (quyCach != null && quyCach > 0) {
                        double factor = quyCach * kgPerUnit;
                        finalSoLuong = round(soLuong * factor);
                        finalDonGia = factor > 0 ? round(donGia / factor) : donGia;
                    } else {
                        // Thiếu quy cách → fallback dùng kgPerUnit, báo cảnh báo
                        finalSoLuong = round(soLuong * kgPerUnit);
                        finalDonGia = kgPerUnit > 0 ? round(donGia / kgPerUnit) : donGia;
                        matchNote = "ĐVT hóa đơn là Thùng nhưng catalog thiếu quy cách — kiểm tra lại";
                    }
                } else {
                    finalSoLuong = round(soLuong * kgPerUnit);
                    finalDonGia = kgPerUnit > 0 ? round(donGia / kgPerUnit) : donGia;
                }
                finalDvt = "Kg";

                // C — Bỏ warning "Match theo TÊN hàng". Chỉ warning khi match MỜ (bỏ dấu).
                if (matchNote == null && "name-loose".equals(matchStrategy)) {
                    matchNote = "Match mờ theo tên (đã bỏ dấu) — kiểm tra lại";
                }
            } else {
                StringBuilder sb = new StringBuilder("KHÔNG TÌM THẤY trong danh mục");
                if (!maHangRaw.isBlank()) {
                    sb.append(" (đã thử mã: \"").append(maHangRaw).append("\"");
                    if (!tenHang.isBlank()) sb.append(", tên: \"").append(tenHang).append("\"");
                    sb.append(")");
                } else if (!tenHang.isBlank()) {
                    sb.append(" (đã thử tên: \"").append(tenHang).append("\")");
                }
                matchNote = sb.toString();

                if (isAlreadyKg) {
                    finalSoLuong = soLuong;
                    finalDonGia = donGia;
                    finalDvt = "Kg";
                }
            }

            // ── VAT ──
            String vatPercentStr = str(row.get("vatPercent")).replace("%", "").trim();
            double vatPercentVal = 0;
            try {
                vatPercentVal = Double.parseDouble(vatPercentStr.replace(",", "."));
            } catch (Exception ignored) {}
            double tienThue = round(thanhTien * vatPercentVal / 100.0);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ngayHachToan", str(row.get("ngayHoaDon")));
            out.put("ngayChungTu", str(row.get("ngayHoaDon")));
            out.put("soChungTu", "");
            out.put("mauSoHd", str(row.get("mauSoHd")));
            out.put("kyHieuHd", str(row.get("kyHieuHd")));
            out.put("soHoaDon", str(row.get("soHoaDon")));
            out.put("ngayHoaDon", str(row.get("ngayHoaDon")));
            out.put("maKhachHang", maKhachHang);
            out.put("tenKhachHang", tenKhachHang);
            out.put("diaChi", diaChi);
            out.put("maSoThue", maSoThue);
            out.put("dienGiai", "");
            out.put("maHang", maHangMisa);
            out.put("tenHang", tenHangMisa);
            out.put("dvt", finalDvt);
            out.put("soLuong", finalSoLuong);
            out.put("donGia", finalDonGia);
            out.put("thanhTien", thanhTien);
            out.put("tkTienNo", "131");
            out.put("tkDoanhThuCo", "5111");
            out.put("vatPercent", vatPercentStr);
            out.put("tienThue", tienThue);
            out.put("hinhThucTT", str(row.get("hinhThucTT")));
            out.put("matchNote", matchNote);
            out.put("kho", kho);
            out.put("tkGiaVon", tkGiaVon);
            out.put("tkKho", tkKho);
            out.put("tkChietKhau", tkChietKhau);   // ← MỚI
            out.put("tkDoanhThu", tkDoanhThu);     // ← MỚI
            out.put("tkThueGtgt", tkThueGtgt);     // ← MỚI

            // Echo dữ liệu gốc
            out.put("_rawMaHang", maHangRaw);
            out.put("_rawTenHang", tenHang);
            out.put("_rawDvt", str(row.get("dvt")));
            out.put("_rawSoLuong", soLuong);
            out.put("_rawDonGia", donGia);
            out.put("_rawThanhTien", thanhTien);
            out.put("_rawVatPercent", vatPercentStr);

            results.add(out);
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NORMALIZE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Normalize chuỗi để match:
     *   - Chuẩn hoá Unicode về NFC (fix bug "ứ" NFC vs NFD khác nhau)
     *   - Upper case
     *   - Gộp nhiều khoảng trắng thành 1
     *   - Trim đầu cuối
     */
    private static String normalizeKey(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFC)
                .trim()                          // trim đầu cuối TRƯỚC
                .replaceAll("\\s+", " ")         // gộp space giữa
                .toLowerCase(Locale.ROOT);       // lowercase thay vì uppercase
    }

    /**
     * Bỏ dấu tiếng Việt + normalize — dùng cho fallback match mờ.
     * Ví dụ: "Thịt úc gà có da" → "THIT UC GA CO DA"
     */
    private static String stripDiacritics(String s) {
        if (s == null) return "";
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('Đ', 'D').replace('đ', 'd')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    // ── Helpers cũ ──────────────────────────────────────────────────────────

    private static String trim(String s) { return s != null ? s.trim() : null; }
    private static String str(Object o) { return o != null ? o.toString().trim() : ""; }
    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString().replace(",", ".")); } catch (Exception e) { return 0; }
    }
    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    // ── Result types ────────────────────────────────────────────────────────

    public record ImportResult(int imported, int skipped, List<Map<String, String>> skippedRows) {}
    public record ParseResult(Double kgPerUnit, Double quyCach, String note) {}
}