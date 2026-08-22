package com.nhatnam.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * PHÂN VÙNG ĐỊA CHỈ GIAO HÀNG theo danh sách phường/xã trong {@code resources/data.json}.
 *
 * <p>Sau sáp nhập, TP.HCM gồm cả phường/xã cũ của Bình Dương và Bà Rịa – Vũng Tàu. Với
 * nghiệp vụ thu tiền thì ba nhóm này KHÁC nhau: chỉ phần thuộc TP.HCM cũ mới được coi là
 * nội thành và cho giao COD; hai nơi kia vẫn là đi xa nên bắt buộc chuyển khoản trước.
 *
 * <p><b>Định dạng {@code data.json}</b> — mảng các phần tử:
 * <pre>
 * [
 *   {
 *     "index": 1,
 *     "oldCityProvince": "TP.HCM",
 *     "oldWardCommune": "Phường Bến Nghé, một phần phường Đa Kao và Nguyễn Thái Bình",
 *     "newWardCommune": "Phường Sài Gòn"
 *   }
 * ]
 * </pre>
 *
 * <p>Khớp địa chỉ dựa trên {@code newWardCommune} — đây là tên đang dùng thực tế. Tên
 * trong {@code oldWardCommune} chỉ làm bí danh dự phòng cho địa chỉ khách còn ghi theo
 * lối cũ; ô đó chứa nhiều tên ngăn bởi dấu phẩy nên được tách nhỏ khi nạp.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class WardZoneService {

    /** Ba nhóm vùng, quyết định hình thức thanh toán và điều kiện cho giao. */
    public enum Zone {
        /** Phường/xã thuộc TP.HCM theo ranh giới CŨ — được phép COD. */
        HCM_CORE,
        /** Phường/xã cũ của Bình Dương hoặc Bà Rịa – Vũng Tàu — bắt buộc chuyển khoản trước. */
        MERGED_OUTER,
        /** Không nhận dạng được / ngoài TP.HCM — bắt buộc chuyển khoản trước. */
        OUTSIDE
    }

    private static final String DATA_FILE = "data.json";

    /**
     * Dấu hiệu địa chỉ "Nhận tại kho" (đã chuẩn hoá bỏ dấu).
     *
     * <p>Phải khớp với hằng {@code PICKUP_AT_WAREHOUSE} bên frontend
     * ({@code CustomerSearchModal.jsx}). Đổi một bên thì phải đổi bên kia.
     */
    private static final String PICKUP_MARKER = "nhan tai kho";

    /** Tên phường/xã đã chuẩn hoá → vùng. */
    private final Map<String, Zone> wardZones = new HashMap<>();

    /**
     * Tên đã sắp theo ĐỘ DÀI GIẢM DẦN để khớp cụm dài trước.
     *
     * <p>Cần thiết vì tên phường/xã lồng nhau: "xã tân an hội" chứa "xã tân an". Duyệt
     * theo thứ tự ngẫu nhiên sẽ khớp phải tên ngắn và gán nhầm vùng.
     */
    private final List<String> wardsByLengthDesc = new ArrayList<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void load() {
        try (InputStream is = new ClassPathResource(DATA_FILE).getInputStream()) {
            List<Map<String, Object>> rows = objectMapper.readValue(is, List.class);

            for (Map<String, Object> row : rows) {
                Zone zone = _zoneOf(_str(row.get("oldCityProvince")));

                _register(_str(row.get("newWardCommune")), zone);

                // Bí danh theo tên cũ — ô này hay chứa nhiều tên trong một chuỗi.
                for (String alias : _splitAliases(_str(row.get("oldWardCommune")))) {
                    _register(alias, zone);
                }
            }

            wardsByLengthDesc.addAll(wardZones.keySet());
            wardsByLengthDesc.sort(Comparator.comparingInt(String::length).reversed());

            log.info("[WardZone] Đã nạp {} tên phường/xã từ {}", wardZones.size(), DATA_FILE);
        } catch (Exception e) {
            // KHÔNG làm sập ứng dụng: thiếu file thì mọi địa chỉ rơi vào OUTSIDE, tức là
            // yêu cầu chuyển khoản trước — hướng an toàn về dòng tiền. Sập cả server chỉ
            // vì một file dữ liệu tham chiếu thì thiệt hại lớn hơn nhiều.
            log.error("[WardZone] KHÔNG nạp được {} — mọi địa chỉ sẽ bị coi là ngoài TP.HCM. Lỗi: {}",
                    DATA_FILE, e.getMessage());
        }
    }

    /**
     * TỪ KHOÁ TỈNH/THÀNH của Bình Dương và Bà Rịa – Vũng Tàu (tầng dự phòng).
     *
     * <p>Phải kiểm TRƯỚC danh sách HCM: sau sáp nhập, địa chỉ hoàn toàn có thể ghi
     * "Dĩ An, TP. Hồ Chí Minh" — bắt "ho chi minh" trước sẽ xếp nhầm vào nội thành.
     */
    private static final List<String> OUTER_FALLBACK = List.of(
            "binh duong", "thu dau mot", "di an", "thuan an", "ben cat",
            "tan uyen", "bau bang", "phu giao", "dau tieng",
            "ba ria", "vung tau", "long dien", "dat do",
            "xuyen moc", "chau duc", "con dao");

    /**
     * TỪ KHOÁ NỘI THÀNH TP.HCM CŨ (tầng dự phòng) — tên thành phố và quận/huyện cũ.
     *
     * <p><b>Vì sao cần tầng này?</b> {@code data.json} chỉ chứa tên PHƯỜNG/XÃ mới, không có
     * tên quận/huyện và không có tên thành phố. Địa chỉ ghi theo lối cũ — "100 Nguyễn Huệ,
     * Quận 1, TP.HCM" — không khớp phường nào nên trước đây rơi thẳng vào OUTSIDE, khiến
     * đơn tiền mặt nội thành cũng bị bắt thu tiền trước và kho không giao được.
     *
     * <p>Phần lớn dữ liệu khách hàng cũ đang ở định dạng này, nên đây là lỗi chặn vận hành
     * chứ không phải trường hợp hiếm.
     */
    private static final List<String> HCM_FALLBACK = List.of(
            "ho chi minh", "tp hcm", "tphcm", "sai gon", "saigon",
            "thu duc", "binh thanh", "go vap", "phu nhuan", "tan binh", "tan phu",
            "binh tan", "nha be", "hoc mon", "cu chi", "binh chanh", "can gio");

    private static final int MAX_NUMBERED_DISTRICT = 12;

    /**
     * VÙNG CỦA MỘT ĐỊA CHỈ GIAO HÀNG — hai tầng.
     *
     * <ol>
     *   <li><b>Khớp tên phường/xã</b> trong {@code data.json} — chính xác nhất, ưu tiên
     *       tuyệt đối.</li>
     *   <li><b>Dự phòng theo tên tỉnh/thành và quận/huyện cũ</b> — dùng khi địa chỉ ghi
     *       theo lối cũ, không có tên phường mới.</li>
     * </ol>
     *
     * <p>Không khớp gì cả vẫn trả {@link Zone#OUTSIDE}: đoán nhầm theo hướng đó chỉ tốn một
     * thao tác xác nhận thu tiền, còn đoán nhầm theo hướng nội thành là hàng rời kho khi
     * chưa có tiền.
     */
    public Zone zoneOf(String address) {
        String s = normalize(address);
        if (s.isBlank()) return Zone.OUTSIDE;

        // KHÁCH TỰ TỚI KHO — không có chuyến giao nào cả, nên coi như tại chỗ.
        // Không xếp vào OUTSIDE: hàng chỉ rời kho khi khách đứng đó trả tiền, đúng bản
        // chất COD, chặn thu-trước ở đây chỉ gây phiền mà không giảm rủi ro nào.
        if (s.contains(PICKUP_MARKER)) return Zone.HCM_CORE;

        // ── Tầng 0: chỉ dấu tỉnh/thành KHÁC rõ ràng ──────────────────────────
        // Tên phường/xã bị trùng giữa các tỉnh: "Phú Thọ" vừa là tỉnh miền Bắc vừa là
        // phường của Bình Dương cũ. Nếu địa chỉ đã ghi rõ một tỉnh KHÔNG thuộc vùng quản
        // lý thì kết luận luôn, đừng để tầng khớp tên phường đoán bừa — đoán sai theo
        // hướng "nội thành" là cho giao COD đi tỉnh xa.
        if (mentionsOtherProvince(s)) return Zone.OUTSIDE;

        // ── Tầng 1: khớp tên phường/xã ───────────────────────────────────────
        for (String ward : wardsByLengthDesc) {
            if (s.contains(ward)) return wardZones.get(ward);
        }

        // ── Tầng 2: dự phòng theo tỉnh/thành + quận/huyện cũ ─────────────────
        for (String kw : OUTER_FALLBACK) {
            if (s.contains(kw)) return Zone.MERGED_OUTER;
        }
        for (String kw : HCM_FALLBACK) {
            if (s.contains(kw)) return Zone.HCM_CORE;
        }
        if (containsNumberedHcmDistrict(s)) return Zone.HCM_CORE;

        return Zone.OUTSIDE;
    }

    /**
     * TỈNH/THÀNH KHÁC được nhắc rõ trong địa chỉ.
     *
     * <p>Danh sách 60 tỉnh thành ngoài phạm vi TP.HCM + Bình Dương + BRVT. Chỉ cần địa chỉ
     * nêu tên một trong số đó là chắc chắn ngoài vùng, không cần đoán theo tên phường.
     *
     * <p>Cố ý ĐỂ SÓT còn hơn nhận nhầm: tỉnh không có trong danh sách vẫn rơi xuống các
     * tầng sau và cùng lắm là ra OUTSIDE — hướng an toàn.
     *
     * <p><b>Không có "hue"</b> — tên đường "Nguyễn Huệ" ở Quận 1 sẽ khớp nhầm và đẩy địa
     * chỉ nội thành ra ngoài vùng. Địa chỉ Huế thật gần như luôn ghi "Thừa Thiên Huế",
     * đã có trong danh sách. Đây là lý do phải so theo TỪ NGUYÊN VẸN ở
     * {@link #mentionsOtherProvince} thay vì {@code contains} thô.
     */
    private static final List<String> OTHER_PROVINCES = List.of(
            "ha noi", "hai phong", "da nang", "can tho", "thua thien",
            "long an", "tien giang", "ben tre", "tra vinh", "vinh long", "dong thap",
            "an giang", "kien giang", "hau giang", "soc trang", "bac lieu", "ca mau",
            "tay ninh", "dong nai", "binh phuoc", "lam dong", "dak lak", "dak nong",
            "gia lai", "kon tum", "binh thuan", "ninh thuan", "khanh hoa", "phu yen",
            "binh dinh", "quang ngai", "quang nam", "quang tri", "quang binh",
            "ha tinh", "nghe an", "thanh hoa", "ninh binh", "nam dinh", "ha nam",
            "thai binh", "hung yen", "hai duong", "quang ninh", "bac ninh", "bac giang",
            "lang son", "cao bang", "bac kan", "thai nguyen", "tuyen quang", "ha giang",
            "lao cai", "yen bai", "phu tho", "vinh phuc", "hoa binh", "son la",
            "dien bien", "lai chau");

    /**
     * So theo TỪ NGUYÊN VẸN, không phải chuỗi con.
     *
     * <p>Chuỗi đã chuẩn hoá nên các từ cách nhau bằng đúng một dấu cách; đệm hai đầu rồi
     * tìm {@code " ten tinh "} để "an giang" không khớp vào "ban giang", và để tên tỉnh
     * ngắn không dính vào giữa một từ dài hơn.
     */
    private boolean mentionsOtherProvince(String s) {
        String padded = " " + s + " ";
        for (String p : OTHER_PROVINCES) {
            if (padded.contains(" " + p + " ")) return true;
        }
        return false;
    }

    /**
     * Nhận dạng "quận 7", "q7", "q.7", "q 7" — quận đánh số của TP.HCM cũ.
     *
     * <p>Kiểm ký tự liền trước và liền sau số để "quận 1" không khớp nhầm vào "quận 12",
     * và để "q7" nằm giữa một dãy số nhà không bị hiểu là tên quận.
     */
    private boolean containsNumberedHcmDistrict(String s) {
        for (int d = MAX_NUMBERED_DISTRICT; d >= 1; d--) {
            for (String prefix : new String[]{"quan ", "quan", "q ", "q"}) {
                String needle = prefix + d;
                int idx = s.indexOf(needle);
                while (idx >= 0) {
                    int after = idx + needle.length();
                    boolean endsCleanly = after >= s.length() || !Character.isDigit(s.charAt(after));
                    boolean startsCleanly = idx == 0 || !Character.isLetterOrDigit(s.charAt(idx - 1));
                    if (endsCleanly && startsCleanly) return true;
                    idx = s.indexOf(needle, idx + 1);
                }
            }
        }
        return false;
    }

    /** Địa chỉ nằm trong TP.HCM theo ranh giới cũ (được phép COD). */
    public boolean isHcmCore(String address) {
        return zoneOf(address) == Zone.HCM_CORE;
    }

    /**
     * Địa chỉ chỉ được thanh toán bằng CHUYỂN KHOẢN.
     *
     * <p>Đúng bằng "không thuộc TP.HCM cũ": cả phần sáp nhập lẫn ngoài thành phố.
     */
    public boolean requiresBankTransfer(String address) {
        return zoneOf(address) != Zone.HCM_CORE;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void _register(String rawName, Zone zone) {
        String key = normalize(rawName);
        if (key.isBlank() || key.length() < 4) return;   // bỏ mẩu quá ngắn, dễ khớp bừa
        // Trùng tên giữa hai vùng thì giữ bản ghi đầu; file nguồn không nên có trường hợp này.
        wardZones.putIfAbsent(key, zone);
    }

    /**
     * Tách ô "xã phường trước sáp nhập" thành từng tên.
     *
     * <p>Ô này viết dạng câu: "Các xã Đá Bạc, Nghĩa Thành" hoặc "Phường Bến Nghé, một
     * phần phường Đa Kao và Nguyễn Thái Bình". Chỉ tách thô theo dấu phẩy và chữ "và";
     * mẩu nào quá ngắn hoặc chỉ còn chữ dẫn ("các xã", "một phần phường") sẽ bị
     * {@link #_register} loại. Đây là dữ liệu PHỤ, khớp sót không ảnh hưởng vì cột
     * {@code newWardCommune} mới là nguồn chính.
     */
    private List<String> _splitAliases(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("[,;]| và ");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim()
                    .replaceFirst("(?i)^(các|một phần)\\s+", "")
                    .trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    private Zone _zoneOf(String oldProvince) {
        String p = normalize(oldProvince);
        if (p.contains("binh duong")) return Zone.MERGED_OUTER;
        if (p.contains("ba ria") || p.contains("vung tau")) return Zone.MERGED_OUTER;
        return Zone.HCM_CORE;   // "TP.HCM" và mọi biến thể viết tên thành phố
    }

    private static String _str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Bỏ dấu, hạ chữ thường, gộp khoảng trắng — để "P.Bến Nghé" và "phường bến nghé" cùng dạng. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
