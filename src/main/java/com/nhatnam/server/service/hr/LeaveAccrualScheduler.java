package com.nhatnam.server.service.hr;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
// import org.springframework.stereotype.Component;   // ← BẬT LẠI nếu muốn cronjob chạy
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ⚠ KHÔNG ĐƯỢC BẬT — cronjob này SẼ TRÙNG với hàm auto
 * {@code LeaveBalanceCalculator.entitledDaysFor(...)}.
 *
 * <p>Quyết định 24/9/2026: hàm auto tự cộng dồn theo tháng đã đủ cho nhu cầu
 * "cộng 1 ngày phép mỗi tháng"; cronjob này để đó phòng khi sau này đổi chiến
 * lược (tắt hàm auto → dùng cronjob). Muốn bật lại thì BỎ COMMENT
 * {@code @Component}. Nhớ tắt/freeze {@code entitledDaysFor} trước khi bật,
 * không thì mỗi tháng nhân viên bị cộng đôi.
 *
 * <p>Ứng xử khi bật:
 * <ul>
 *   <li>Áp dụng cho MỌI nhân viên {@code deleted = false}, KỂ CẢ bị khoá tài
 *       khoản ({@code isLockAccount = true}).</li>
 *   <li>Cộng 1 vào {@code User.bonusLeaveDays}.</li>
 *   <li>Cron {@code 0 5 0 1 * *} — 00:05 ngày 1 mỗi tháng, giờ VN.</li>
 * </ul>
 */
// @Component   // ← BỎ COMMENT dòng này để Spring quét & chạy cronjob
@Slf4j
@RequiredArgsConstructor
public class LeaveAccrualScheduler {

    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

//    @Scheduled(cron = "0 5 0 1 * *", zone = "Asia/Ho_Chi_Minh")
//    @Transactional
//    public void accrueMonthlyLeaveDay() {
//        // Lấy TOÀN BỘ nhân viên chưa xoá mềm — kể cả bị khoá.
//        // Chấp nhận query một lần thay vì lọc trong bộ nhớ để tránh nạp cả bảng
//        // nếu về sau có hàng chục nghìn user.
//        List<User> users = em.createQuery(
//                        "SELECT u FROM User u WHERE u.deleted = false", User.class)
//                .getResultList();
//
//        int updated = 0;
//        for (User u : users) {
//            double current = u.getBonusLeaveDays() != null ? u.getBonusLeaveDays() : 0.0;
//            u.setBonusLeaveDays(current + 1.0);
//            updated++;
//        }
//        userRepository.saveAll(users);
//
//        log.info("[LeaveAccrual] Cộng 1 ngày phép cho {} nhân viên (deleted=false).", updated);
//    }
}