package com.nhatnam.server.service;

import com.nhatnam.server.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolver kiểm tra productId có thuộc danh mục được phép không.
 *
 * Logic lọc:
 *  - Product.category (String) chứa tên danh mục gốc.
 *  - Chúng ta match case-insensitive với ALLOWED_CATEGORY_NAMES.
 *  - Cache kết quả trong ConcurrentHashMap để tránh query DB mỗi OrderItem.
 *  - Cache được làm mới mỗi 10 phút (hoặc khi gọi refresh() thủ công).
 *
 * NẾU dự án dùng category theo ID (categoryId trên Product):
 *  → Sửa ALLOWED_CATEGORY_NAMES thành Set<Long> ALLOWED_CATEGORY_IDS
 *    và kiểm tra product.getCategoryId().
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class ProductCategoryResolver {

    private static final Set<String> ALLOWED_CATEGORY_NAMES = Set.of(
            "Non-Dairy Creams",
            "Non-Food"
    );

    private final ProductRepository productRepository;

    // productId → allowed?
    private final ConcurrentHashMap<Long, Boolean> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** Làm mới cache mỗi 10 phút — tránh stale data khi admin đổi danh mục */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void refresh() {
        try {
            cache.clear();
            productRepository.findAll().forEach(p -> {
                boolean allowed = p.getCategory() != null
                        && ALLOWED_CATEGORY_NAMES.stream()
                        .anyMatch(name -> name.equalsIgnoreCase(p.getCategory().trim()));
                cache.put(p.getId(), allowed);
            });
            log.debug("ProductCategoryResolver: refreshed {} products", cache.size());
        } catch (Exception ex) {
            log.warn("ProductCategoryResolver refresh failed", ex);
        }
    }

    /**
     * @return true nếu productId thuộc danh mục cho phép.
     *         false nếu không tìm thấy (sản phẩm đã xóa hoặc category khác).
     */
    public boolean isAllowed(Long productId) {
        if (productId == null) return false;
        return cache.getOrDefault(productId, false);
    }
}