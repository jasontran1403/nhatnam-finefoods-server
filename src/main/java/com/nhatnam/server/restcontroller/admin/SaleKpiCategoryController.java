package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.OrderItemIngredient;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.IngredientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DOANH SỐ THEO DANH MỤC SẢN PHẨM — bảng Top 10 trên trang KPI Phòng Sale.
 *
 * <p>{@code GET /api/admin/sale-kpi/categories?from=<ms>&to=<ms>}
 *
 * <h3>Đơn nào được tính</h3>
 * Chỉ 4 trạng thái: {@code PREPARING, DELIVERING, PENDING_PAYMENT, COMPLETED}.
 * Đơn huỷ / thất bại / mới tạo chưa xác nhận không phản ánh hàng đã bán ra.
 * <b>Khác với các card phía trên</b> (chỉ đếm {@code COMPLETED}) nên hai con số
 * sẽ không khớp nhau — đó là chủ ý, không phải lỗi.
 *
 * <h3>Tiền của một dòng hàng</h3>
 * Giảm giá được nhập ở CẤP ĐƠN, không gắn vào từng dòng, nên phải phân bổ ngược
 * lại theo tỷ trọng — dùng ĐÚNG công thức mà {@code OrderServiceImpl.calcVatForItems}
 * đang dùng để chia VAT, để hai nơi không cho ra hai con số khác nhau:
 * <pre>
 *   tỷ trọng   = item.subtotal / order.subtotal
 *   sau giảm   = order.totalAmount × tỷ trọng      (totalAmount = đã trừ giảm giá)
 *   thành tiền = VAT INCLUSIVE ? sau giảm : sau giảm + item.vatAmount
 * </pre>
 *
 * <p><b>Phụ thu (surcharge) KHÔNG được tính vào.</b> Nó là khoản của cả đơn (phí
 * giao, phí xử lý…), không thuộc về sản phẩm nào — quy về danh mục sẽ thổi phồng
 * doanh số của danh mục ngẫu nhiên có mặt trong đơn đó.
 *
 * <p>Làm tròn {@code setScale(0, HALF_UP)} ở bước cuối, khớp với cách mọi số tiền
 * khác trên trang KPI và trên màn hình đơn hàng đang hiển thị.
 */
@RestController
@RequestMapping("/api/admin/sale-kpi")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class SaleKpiCategoryController {

    private final OrderRepository    orderRepository;
    private final IngredientRepository ingredientRepository;
    private final CategoryRepository categoryRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Chỉ những trạng thái phản ánh hàng THỰC SỰ đã bán ra. */
    private static final Set<OrderStatus> COUNTED = EnumSet.of(
            OrderStatus.PREPARING,
            OrderStatus.DELIVERING,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.COMPLETED);

    /** Số dòng tối đa của bảng. */
    private static final int TOP_N = 10;

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<Map<String, Object>>> categories(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        try {
            if (from == null || to == null) {
                YearMonth ym = YearMonth.now(VN);
                from = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
                to   = ym.atEndOfMonth().atTime(23, 59, 59).atZone(VN).toInstant().toEpochMilli();
            }

            List<Order> orders = orderRepository.findByCreatedAtBetween(from, to).stream()
                    .filter(o -> o.getStatus() != null && COUNTED.contains(o.getStatus()))
                    .toList();

            // ── Nạp một lượt ingredient + category, tránh N+1 ────────────
            //   Danh mục KHÔNG nằm ở Product mà ở INGREDIENT. Đường đi:
            //     order_item → order_item_ingredient → ingredient.category_id → categories.name
            //   Dùng ingredient đã lưu trên đơn (order_item_ingredient) chứ không
            //   tra lại công thức hiện tại của sản phẩm — công thức đổi sau này
            //   không được làm đổi số liệu của kỳ đã bán.
            Set<Long> ingredientIds = orders.stream()
                    .flatMap(o -> o.getOrderItems().stream())
                    .filter(it -> it.getOrderItemIngredients() != null)
                    .flatMap(it -> it.getOrderItemIngredients().stream())
                    .map(OrderItemIngredient::getIngredientId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Map<Long, Ingredient> ingredientMap = ingredientIds.isEmpty() ? Map.of()
                    : ingredientRepository.findAllById(ingredientIds).stream()
                    .collect(Collectors.toMap(Ingredient::getId, x -> x, (a, b) -> a));

            // Chỉ lấy DANH MỤC CHÍNH (category_id), bỏ qua sub_category_id —
            // gộp tới cấp con sẽ xé nhỏ bảng thành hàng chục dòng vụn.
            Set<Long> categoryIds = ingredientMap.values().stream()
                    .map(Ingredient::getCategoryId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Map.of()
                    : categoryRepository.findAllById(categoryIds).stream()
                    .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

            // ── Cộng dồn theo danh mục, và theo sản phẩm bên trong ────────
            Map<String, Agg> byCategory = new LinkedHashMap<>();

            for (Order o : orders) {
                BigDecimal orderSubtotal = nvl(o.getSubtotal());
                BigDecimal afterDiscount = nvl(o.getTotalAmount());
                BigDecimal surcharge     = nvl(o.getSurcharge());

                for (OrderItem it : o.getOrderItems()) {
                    BigDecimal amount = itemRevenue(it, orderSubtotal, afterDiscount, surcharge);

                    Long catId = categoryIdOf(it, ingredientMap);
                    // Sản phẩm chưa gán danh mục vẫn phải xuất hiện, không âm thầm
                    // biến mất khỏi tổng — gom vào một nhóm riêng để nhìn ra ngay.
                    String catKey  = catId != null ? String.valueOf(catId) : "__none__";
                    String catName = catId != null
                            ? categoryNameMap.getOrDefault(catId, "Danh mục #" + catId)
                            : "(Chưa gán danh mục)";

                    Agg cat = byCategory.computeIfAbsent(catKey, k -> new Agg(catId, catName));
                    cat.quantity = cat.quantity.add(nvl(it.getQuantity()));
                    cat.amount   = cat.amount.add(amount);
                    if (cat.unit == null && it.getUnit() != null) cat.unit = it.getUnit();

                    String prodKey = it.getProductId() != null
                            ? String.valueOf(it.getProductId()) : it.getProductName();
                    Agg prod = cat.products.computeIfAbsent(prodKey,
                            k -> new Agg(it.getProductId(), it.getProductName()));
                    prod.quantity = prod.quantity.add(nvl(it.getQuantity()));
                    prod.amount   = prod.amount.add(amount);
                    if (prod.unit == null && it.getUnit() != null) prod.unit = it.getUnit();
                }
            }

            List<Map<String, Object>> rows = byCategory.values().stream()
                    .sorted(Comparator.comparing((Agg a) -> a.amount).reversed())
                    .limit(TOP_N)
                    .map(Agg::toMap)
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("categories", rows);
            result.put("countedStatuses", COUNTED.stream().map(Enum::name).toList());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));

        } catch (Exception e) {
            log.error("sale-kpi categories error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * THÀNH TIỀN của một dòng hàng — đã trừ giảm giá, đã gồm VAT.
     *
     * <p>Chia lại phần giảm giá cấp đơn theo tỷ trọng dòng hàng, giống hệt cách
     * hệ thống chia VAT. Đơn có {@code subtotal = 0} (hàng tặng, đơn lỗi dữ liệu)
     * thì tỷ trọng không xác định — trả 0 thay vì chia cho 0.
     */
    private BigDecimal itemRevenue(OrderItem it, BigDecimal orderSubtotal,
                                   BigDecimal afterDiscount, BigDecimal surcharge) {
        if (orderSubtotal == null || orderSubtotal.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal proportion = nvl(it.getSubtotal())
                .divide(orderSubtotal, 10, RoundingMode.HALF_UP);
        BigDecimal amount = afterDiscount.multiply(proportion);

        // VAT INCLUSIVE: thuế đã nằm trong giá, không cộng thêm.
        // VAT EXCLUSIVE: thuế nằm ngoài, phải cộng vào mới ra tiền khách trả.
        if ("EXCLUSIVE".equals(it.getVatMode())) {
            amount = amount.add(nvl(it.getVatAmount()));
        }

        // PHỤ THU cũng phải chia về từng dòng, nếu không tổng bảng này sẽ THẤP HƠN
        // finalAmount của đơn và không bao giờ khớp với các card phía trên.
        // Chia theo cùng tỷ trọng để Σ(dòng) = finalAmount đúng bằng số khách trả.
        amount = amount.add(surcharge.multiply(proportion));

        return amount;
    }

    /**
     * DANH MỤC của một dòng hàng, tra qua nguyên liệu đã lưu trên đơn.
     *
     * <p>Một sản phẩm có thể gồm nhiều nguyên liệu thuộc các danh mục khác nhau
     * (VD: set quà = cà phê + trà). Không thể chia đôi doanh số một dòng hàng cho
     * hai danh mục mà vẫn giữ được tổng, nên quy về NGUYÊN LIỆU CHIẾM NHIỀU NHẤT
     * theo {@code quantityUsed} — đó là thành phần chính định danh món hàng.
     *
     * @return {@code null} nếu sản phẩm không có nguyên liệu nào, hoặc nguyên
     *         liệu chưa được gán danh mục
     */
    private Long categoryIdOf(OrderItem it, Map<Long, Ingredient> ingredientMap) {
        if (it.getOrderItemIngredients() == null || it.getOrderItemIngredients().isEmpty()) return null;

        Long bestCat = null;
        BigDecimal bestQty = null;
        for (OrderItemIngredient oii : it.getOrderItemIngredients()) {
            if (oii.getIngredientId() == null) continue;
            Ingredient ing = ingredientMap.get(oii.getIngredientId());
            if (ing == null || ing.getCategoryId() == null) continue;

            BigDecimal q = nvl(oii.getQuantityUsed());
            if (bestQty == null || q.compareTo(bestQty) > 0) {
                bestQty = q;
                bestCat = ing.getCategoryId();
            }
        }
        return bestCat;
    }

    private static BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    /** Ô cộng dồn dùng chung cho cả danh mục lẫn sản phẩm bên trong. */
    private static class Agg {
        final Long id;
        final String name;
        String unit;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal amount   = BigDecimal.ZERO;
        final Map<String, Agg> products = new LinkedHashMap<>();

        Agg(Long id, String name) { this.id = id; this.name = name; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("unit", unit);
            // Số lượng bỏ số 0 thừa: 12.000 → 12, nhưng 1.5 vẫn là 1.5
            m.put("quantity", quantity.stripTrailingZeros().toPlainString());
            m.put("amount", amount.setScale(0, RoundingMode.HALF_UP));
            if (!products.isEmpty()) {
                m.put("products", products.values().stream()
                        .sorted(Comparator.comparing((Agg a) -> a.amount).reversed())
                        .map(Agg::toMap)
                        .toList());
            }
            return m;
        }
    }
}