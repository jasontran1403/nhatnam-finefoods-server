package com.nhatnam.server.utils;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

@Service
public class RegionExtractor {

    private static final Map<String, String> PROVINCE_MERGE_MAP;

    static {
        Map<String, String> m = new HashMap<>();

        // miền Bắc
        m.put("ha giang", "Tuyên Quang");
        m.put("tuyen quang", "Tuyên Quang");

        m.put("cao bang", "Cao Bằng");
        m.put("lai chau", "Lai Châu");

        m.put("lao cai", "Lào Cai");
        m.put("yen bai", "Lào Cai");

        m.put("bac kan", "Thái Nguyên");
        m.put("thai nguyen", "Thái Nguyên");

        m.put("dien bien", "Điện Biên");
        m.put("lang son", "Lạng Sơn");
        m.put("son la", "Sơn La");

        m.put("phu tho", "Phú Thọ");
        m.put("vinh phuc", "Phú Thọ");
        m.put("hoa binh", "Phú Thọ");

        m.put("bac ninh", "Bắc Ninh");
        m.put("bac giang", "Bắc Ninh");

        m.put("quang ninh", "Quảng Ninh");
        m.put("ha noi", "Hà Nội");

        m.put("hung yen", "Hưng Yên");
        m.put("thai binh", "Hưng Yên");

        m.put("ninh binh", "Ninh Bình");
        m.put("nam dinh", "Ninh Bình");
        m.put("ha nam", "Ninh Bình");

        m.put("hai phong", "Hải Phòng");
        m.put("hai duong", "Hải Phòng");

        // miền Trung
        m.put("thanh hoa", "Thanh Hóa");
        m.put("nghe an", "Nghệ An");
        m.put("ha tinh", "Hà Tĩnh");

        m.put("quang binh", "Quảng Trị");
        m.put("quang tri", "Quảng Trị");

        m.put("hue", "Huế");
        m.put("thua thien hue", "Huế");

        m.put("da nang", "Đà Nẵng");
        m.put("quang nam", "Đà Nẵng");

        m.put("quang ngai", "Quảng Ngãi");
        m.put("kon tum", "Quảng Ngãi");

        m.put("gia lai", "Gia Lai");
        m.put("binh dinh", "Gia Lai");

        m.put("khanh hoa", "Khánh Hòa");
        m.put("ninh thuan", "Khánh Hòa");

        // Tây Nguyên
        m.put("dak lak", "Đắk Lắk");
        m.put("dak nong", "Đắk Lắk");

        m.put("lam dong", "Lâm Đồng");
        m.put("binh thuan", "Lâm Đồng");

        // Đông Nam Bộ
        m.put("dong nai", "Đồng Nai");
        m.put("binh phuoc", "Đồng Nai");

        m.put("ho chi minh", "Hồ Chí Minh");
        m.put("tp ho chi minh", "Hồ Chí Minh");
        m.put("hcm", "Hồ Chí Minh");
        m.put("sai gon", "Hồ Chí Minh");
        m.put("binh duong", "Hồ Chí Minh");
        m.put("ba ria", "Hồ Chí Minh");
        m.put("vung tau", "Hồ Chí Minh");

        m.put("tay ninh", "Tây Ninh");
        m.put("long an", "Tây Ninh");

        // miền Tây
        m.put("dong thap", "Đồng Tháp");
        m.put("tien giang", "Đồng Tháp");

        m.put("vinh long", "Vĩnh Long");
        m.put("ben tre", "Vĩnh Long");
        m.put("tra vinh", "Vĩnh Long");

        m.put("can tho", "Cần Thơ");
        m.put("hau giang", "Cần Thơ");
        m.put("soc trang", "Cần Thơ");

        m.put("an giang", "An Giang");
        m.put("kien giang", "An Giang");

        m.put("ca mau", "Cà Mau");
        m.put("bac lieu", "Cà Mau");

        PROVINCE_MERGE_MAP = Collections.unmodifiableMap(m);
    }

    // ================================
    // NORMALIZE (rất nhanh)
    // ================================

    private static String normalize(String s) {

        if (s == null) return "";

        String result = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        result = result.toLowerCase(Locale.ROOT)
                .replace("đ", "d")
                .replace("tp.", "")
                .replace("tp", "")
                .trim();

        return result;
    }

    // ================================
    // EXTRACT REGION
    // ================================

    public String extractRegion(String address) {

        if (address == null || address.isBlank()) {
            return "Khác";
        }

        // 1️⃣ lấy segment cuối
        int lastComma = address.lastIndexOf(',');

        String province =
                lastComma >= 0
                        ? address.substring(lastComma + 1).trim()
                        : address.trim();

        // 2️⃣ normalize
        String key = normalize(province);

        // 3️⃣ lookup nhanh
        String region = PROVINCE_MERGE_MAP.get(key);

        if (region != null) {
            return region;
        }

        // 4️⃣ fallback contains (ít xảy ra)
        for (Map.Entry<String, String> e : PROVINCE_MERGE_MAP.entrySet()) {
            if (key.contains(e.getKey())) {
                return e.getValue();
            }
        }

        return province;
    }
}