package com.nhatnam.server.utils;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * XÁC ĐỊNH ĐỊA CHỈ GIAO CÓ NẰM TRONG ĐỊA BÀN TP.HCM (RANH GIỚI CŨ) HAY KHÔNG.
 *
 * <p>Dùng cho quy tắc thu tiền: đơn giao NGOÀI địa bàn phải được seller đánh dấu đã thu
 * tiền trước khi kho được bắt đầu giao. Xe giao xa, khách không trả thì hàng đã đi rồi.
 *
 * <p><b>Vì sao KHÔNG dùng lại {@link RegionExtractor}?</b> Lớp đó phục vụ báo cáo theo
 * đơn vị hành chính SAU sáp nhập, nên nó gộp thẳng Bình Dương và Bà Rịa – Vũng Tàu vào
 * "Hồ Chí Minh". Với nghiệp vụ giao hàng thì hai nơi đó vẫn là đi xa và vẫn phải thu
 * tiền trước — dùng chung sẽ khiến mọi đơn Dĩ An, Vũng Tàu được coi là nội thành.
 *
 * <p><b>Địa chỉ không nhận dạng được coi là NGOÀI địa bàn.</b> Đây là lựa chọn có chủ ý:
 * đoán nhầm theo hướng "ngoài" chỉ khiến seller phải xác nhận đã thu tiền — mất một thao
 * tác; đoán nhầm theo hướng "trong" thì hàng rời kho khi chưa có tiền, và đó là mất mát
 * thật. Danh sách từ khoá bên dưới phủ đủ tên gọi thường gặp, nên trường hợp không nhận
 * dạng được hầu như chỉ xảy ra với địa chỉ ghi thiếu tỉnh/thành.
 */
public final class DeliveryZoneUtil {

    private DeliveryZoneUtil() {}

    /**
     * Từ khoá của Bình Dương và Bà Rịa – Vũng Tàu.
     *
     * <p>Phải kiểm tra TRƯỚC danh sách HCM: sau sáp nhập, địa chỉ hoàn toàn có thể ghi
     * "Dĩ An, TP. Hồ Chí Minh" — nếu bắt "ho chi minh" trước thì đơn đi Dĩ An bị xếp
     * nhầm vào nội thành.
     */
    private static final List<String> OUTSIDE_KEYWORDS = List.of(
            // Bình Dương
            "binh duong", "thu dau mot", "di an", "thuan an", "ben cat",
            "tan uyen", "bau bang", "phu giao", "dau tieng",
            // Bà Rịa – Vũng Tàu
            "ba ria", "vung tau", "phu my", "long dien", "dat do",
            "xuyen moc", "chau duc", "con dao"
    );

    /**
     * Từ khoá thuộc TP.HCM theo ranh giới cũ: tên thành phố và các quận/huyện cũ.
     *
     * <p>Có cả tên quận/huyện vì rất nhiều địa chỉ khách đọc qua điện thoại chỉ ghi
     * "Q7", "Bình Thạnh" mà không kèm tên thành phố.
     */
    private static final List<String> HCM_KEYWORDS = List.of(
            "ho chi minh", "tphcm", "tp hcm", "hcm", "sai gon", "saigon",
            "thu duc", "binh thanh", "go vap", "phu nhuan", "tan binh", "tan phu",
            "binh tan", "nha be", "hoc mon", "cu chi", "binh chanh", "can gio"
    );

    /** Quận đánh số của TP.HCM cũ: "quan 1" … "quan 12", kèm dạng viết tắt "q1", "q.1". */
    private static final int MAX_NUMBERED_DISTRICT = 12;

    /**
     * @return true nếu địa chỉ nằm trong địa bàn TP.HCM cũ (không tính Bình Dương,
     *         Bà Rịa – Vũng Tàu). Địa chỉ trống hoặc không nhận dạng được → false.
     */
    public static boolean isInsideHcm(String address) {
        String s = normalize(address);
        if (s.isBlank()) return false;

        // Bình Dương / BRVT luôn thắng, kể cả khi địa chỉ có kèm "TP.HCM".
        for (String kw : OUTSIDE_KEYWORDS) {
            if (s.contains(kw)) return false;
        }

        for (String kw : HCM_KEYWORDS) {
            if (s.contains(kw)) return true;
        }

        return containsNumberedHcmDistrict(s);
    }

    /** Nghịch đảo của {@link #isInsideHcm} — đọc xuôi hơn ở chỗ gọi. */
    public static boolean isOutsideHcm(String address) {
        return !isInsideHcm(address);
    }

    /**
     * Nhận dạng "quận 7", "q7", "q.7", "q 7".
     *
     * <p>Kiểm tra ký tự liền sau số để "quan 1" không khớp nhầm với "quan 12", và để
     * "q7" trong một dãy số nhà không bị hiểu là tên quận.
     */
    private static boolean containsNumberedHcmDistrict(String s) {
        for (int d = MAX_NUMBERED_DISTRICT; d >= 1; d--) {
            for (String prefix : new String[]{"quan ", "quan", "q ", "q.", "q"}) {
                int idx = s.indexOf(prefix + d);
                while (idx >= 0) {
                    int after = idx + prefix.length() + String.valueOf(d).length();
                    boolean endsCleanly = after >= s.length() || !Character.isDigit(s.charAt(after));
                    boolean startsCleanly = idx == 0 || !Character.isLetterOrDigit(s.charAt(idx - 1));
                    if (endsCleanly && startsCleanly) return true;
                    idx = s.indexOf(prefix + d, idx + 1);
                }
            }
        }
        return false;
    }

    /** Bỏ dấu, hạ chữ thường, gộp khoảng trắng — để "Q.7" và "quận 7" cùng dạng. */
    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
