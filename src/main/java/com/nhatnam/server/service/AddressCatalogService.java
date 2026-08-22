package com.nhatnam.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * DANH MỤC HÀNH CHÍNH + CHÍNH SÁCH COD THEO PHƯỜNG/XÃ.
 *
 * <p>Thay thế hoàn toàn cách khớp địa chỉ bằng chuỗi tự do trước đây. Người dùng giờ CHỌN
 * tỉnh/thành và phường/xã từ dropdown, nên tên luôn chuẩn — không còn cảnh "Q1" hay "Quận
 * Nhứt" khớp trượt rồi đơn bị bắt thu tiền trước oan.
 *
 * <h3>Hai file trong {@code resources/}</h3>
 * <ul>
 *   <li><b>{@code data.json}</b> — danh mục 34 tỉnh/thành, mỗi tỉnh có mảng {@code don_vi}
 *       gồm {@code {ten, loai}}. Dùng để đổ dropdown.</li>
 *   <li><b>{@code allow.json}</b> — danh sách {@code {ten_tinh_thanh, ten_phuong}} được
 *       phép COD. Ngoài danh sách này thì bắt buộc thu tiền trước.</li>
 * </ul>
 *
 * <h3>Vì sao hai file tách rời</h3>
 * Danh mục hành chính thay đổi theo nghị quyết nhà nước; danh sách COD thay đổi theo quyết
 * định kinh doanh (mở rộng vùng giao, siết lại khi nợ xấu tăng). Gộp một file thì mỗi lần
 * đổi chính sách bán hàng lại phải sửa vào giữa dữ liệu hành chính.
 *
 * <h3>Lưu ý về định dạng tên</h3>
 * {@code data.json} ghi đầy đủ tiền tố ("Phường Sài Gòn"), {@code allow.json} ghi tên trần
 * ("Sài Gòn"). Việc so khớp luôn <b>bỏ tiền tố</b> ở cả hai phía, nên hai file không cần
 * thống nhất cách viết — và người sửa allow.json về sau viết kiểu nào cũng chạy.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class AddressCatalogService {

    private static final String CATALOG_FILE = "data.json";
    private static final String ALLOW_FILE   = "allow.json";

    /** Tên tỉnh/thành mặc định khi mở form — theo yêu cầu nghiệp vụ. */
    public static final String DEFAULT_PROVINCE = "Thành phố Hồ Chí Minh";

    /**
     * Chuỗi địa chỉ khi khách TỰ TỚI KHO LẤY HÀNG.
     *
     * <p>Phải khớp nguyên văn hằng {@code PICKUP_AT_WAREHOUSE} bên frontend
     * ({@code CustomerSearchModal.jsx}). Đổi một bên thì phải đổi bên kia.
     */
    public static final String PICKUP_AT_WAREHOUSE = "Nhận tại kho";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Danh mục để đổ dropdown, giữ nguyên thứ tự trong file. */
    private final List<Province> catalog = new ArrayList<>();

    /** Khoá chuẩn hoá "tỉnh|phường" của các phường được COD. */
    private final Set<String> codAllowed = new HashSet<>();

    /** Tra nhanh: tên tỉnh chuẩn hoá → bộ tên phường chuẩn hoá (để validate đầu vào). */
    private final Map<String, Set<String>> wardsByProvince = new HashMap<>();

    @PostConstruct
    void load() {
        loadCatalog();
        loadAllowList();
    }

    @SuppressWarnings("unchecked")
    private void loadCatalog() {
        try (InputStream is = new ClassPathResource(CATALOG_FILE).getInputStream()) {
            List<Map<String, Object>> rows = objectMapper.readValue(is, List.class);

            for (Map<String, Object> row : rows) {
                Province p = new Province();
                p.setName(str(row.get("ten_tinh_thanh")));
                p.setType(str(row.get("loai")));

                List<Ward> wards = new ArrayList<>();
                Object units = row.get("don_vi");
                if (units instanceof List<?> list) {
                    for (Object u : list) {
                        if (!(u instanceof Map<?, ?> m)) continue;
                        Ward w = new Ward();
                        w.setName(str(m.get("ten")));
                        w.setType(str(m.get("loai")));
                        if (!w.getName().isBlank()) wards.add(w);
                    }
                }
                p.setWards(wards);
                catalog.add(p);

                wardsByProvince.put(normProvince(p.getName()),
                        wards.stream().map(w -> normWard(w.getName())).collect(java.util.stream.Collectors.toSet()));
            }
            log.info("[AddressCatalog] Nạp {} tỉnh/thành, {} phường/xã từ {}",
                    catalog.size(), catalog.stream().mapToInt(x -> x.getWards().size()).sum(), CATALOG_FILE);
        } catch (Exception e) {
            // Không làm sập app: dropdown rỗng thì người dùng thấy ngay và báo lại, còn sập
            // server thì cả hệ thống dừng vì một file dữ liệu tham chiếu.
            log.error("[AddressCatalog] KHÔNG nạp được {} — dropdown địa chỉ sẽ rỗng. Lỗi: {}",
                    CATALOG_FILE, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadAllowList() {
        try (InputStream is = new ClassPathResource(ALLOW_FILE).getInputStream()) {
            List<Map<String, Object>> rows = objectMapper.readValue(is, List.class);
            for (Map<String, Object> row : rows) {
                String prov = str(row.get("ten_tinh_thanh"));
                String ward = str(row.get("ten_phuong"));
                if (prov.isBlank() || ward.isBlank()) continue;
                codAllowed.add(key(prov, ward));
            }
            log.info("[AddressCatalog] Nạp {} phường/xã được phép COD từ {}", codAllowed.size(), ALLOW_FILE);
        } catch (Exception e) {
            // Danh sách rỗng ⇒ KHÔNG phường nào được COD ⇒ mọi đơn phải thu tiền trước.
            // Hướng an toàn về dòng tiền: thà phiền thêm một thao tác còn hơn cho hàng rời
            // kho vì một file không đọc được.
            log.error("[AddressCatalog] KHÔNG nạp được {} — KHÔNG địa chỉ nào được COD. Lỗi: {}",
                    ALLOW_FILE, e.getMessage());
        }
    }

    // ── API cho dropdown ─────────────────────────────────────────────────────

    /** Toàn bộ danh mục (tỉnh + phường) — frontend nạp một lần rồi lọc tại chỗ. */
    public List<Province> getCatalog() {
        return catalog;
    }

    public List<String> getProvinceNames() {
        return catalog.stream().map(Province::getName).toList();
    }

    /** Phường/xã của một tỉnh. Tên tỉnh không tồn tại → danh sách rỗng. */
    public List<Ward> getWards(String provinceName) {
        String key = normProvince(provinceName);
        return catalog.stream()
                .filter(p -> normProvince(p.getName()).equals(key))
                .findFirst()
                .map(Province::getWards)
                .orElse(List.of());
    }

    // ── Kiểm tra ─────────────────────────────────────────────────────────────

    /**
     * Cặp tỉnh + phường có tồn tại trong danh mục không.
     *
     * <p>Dùng để chặn dữ liệu gõ tay hoặc request nặn tay: dropdown bên frontend đã giới
     * hạn lựa chọn, nhưng đó chỉ là lớp giao diện.
     */
    public boolean isValidAddress(String provinceName, String wardName) {
        if (provinceName == null || wardName == null) return false;
        Set<String> wards = wardsByProvince.get(normProvince(provinceName));
        return wards != null && wards.contains(normWard(wardName));
    }

    /**
     * ĐỊA CHỈ NÀY CÓ ĐƯỢC GIAO COD KHÔNG.
     *
     * <p>Lọc hai bước cho rẻ: chỉ TP.HCM mới có phường nằm trong danh sách, nên tỉnh khác
     * bị loại ngay mà không cần tra tập hợp.
     *
     * <p>Trả {@code false} khi thiếu tỉnh hoặc phường — địa chỉ không đủ thông tin thì
     * không thể kết luận là vùng được COD.
     */
    /**
     * Địa chỉ giao là "khách tự tới kho lấy".
     *
     * <p>Trường hợp này không có tỉnh/phường (form đã khoá hai ô đó), nên phải nhận dạng
     * riêng — nếu không, mọi đơn nhận tại kho sẽ rơi vào diện phải thu tiền trước.
     */
    public boolean isPickupAtWarehouse(String deliveryAddress) {
        if (deliveryAddress == null) return false;
        return base(deliveryAddress).equals(base(PICKUP_AT_WAREHOUSE));
    }

    public boolean isCodAllowed(String provinceName, String wardName) {
        if (provinceName == null || provinceName.isBlank()) return false;
        if (wardName == null || wardName.isBlank()) return false;
        if (!normProvince(provinceName).equals(normProvince(DEFAULT_PROVINCE))) return false;
        return codAllowed.contains(key(provinceName, wardName));
    }

    // ── Chuẩn hoá ────────────────────────────────────────────────────────────

    private static String key(String province, String ward) {
        return normProvince(province) + "|" + normWard(ward);
    }

    /** Bỏ dấu, hạ chữ thường, gộp khoảng trắng. */
    private static String base(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Bỏ thêm tiền tố "thanh pho"/"tinh" để "TP.HCM" và "Thành phố Hồ Chí Minh" cùng khoá. */
    private static String normProvince(String raw) {
        return base(raw).replaceFirst("^(thanh pho|tinh)\\s+", "");
    }

    /**
     * Bỏ tiền tố đơn vị hành chính.
     *
     * <p>{@code data.json} ghi "Phường Sài Gòn", {@code allow.json} ghi "Sài Gòn" — hai file
     * do hai nguồn khác nhau nên không thể trông chờ chúng viết giống hệt.
     */
    private static String normWard(String raw) {
        return base(raw).replaceFirst("^(phuong|xa|dac khu|thi tran|quan|huyen)\\s+", "");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    @Data
    public static class Province {
        private String name;
        private String type;        // "Thành phố Trung Ương" | "Tỉnh"
        private List<Ward> wards;
    }

    @Data
    public static class Ward {
        private String name;
        private String type;        // "Phường" | "Xã" | "Đặc khu"
    }
}
