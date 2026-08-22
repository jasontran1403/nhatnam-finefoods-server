package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryKpiCarryOverSeed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FactoryKpiCarryOverSeedRepository
        extends JpaRepository<FactoryKpiCarryOverSeed, Long> {

    /** Các khoản dư khai báo tay được cộng vào quỹ chia của tháng applyMonth/applyYear. */
    List<FactoryKpiCarryOverSeed> findByApplyMonthAndApplyYearOrderBySourceYearAscSourceMonthAsc(
            Integer applyMonth, Integer applyYear);

    /** Toàn bộ khoản đã khai báo — dùng cho màn hình quản lý. */
    List<FactoryKpiCarryOverSeed> findAllByOrderByApplyYearDescApplyMonthDesc();
}
