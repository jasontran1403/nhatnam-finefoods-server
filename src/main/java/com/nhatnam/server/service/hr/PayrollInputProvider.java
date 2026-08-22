package com.nhatnam.server.service.hr;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import org.springframework.stereotype.Component;

/**
 * Nguồn dữ liệu đầu vào cho tính lương: tỷ lệ KPI đạt được và số giờ công
 * thực tế của mỗi nhân viên trong kỳ.
 *
 * <p><b>Trạng thái hiện tại:</b> cả hai hàm trả về giá trị MẶC ĐỊNH "đầy đủ":
 * <ul>
 *   <li>{@link #getKpiPercentage(User)} → 100% (đạt KPI tối đa)</li>
 *   <li>{@link #getActualWorkHours(User)} → đủ 26 công = 208 giờ</li>
 * </ul>
 * Nhờ đó lương và thưởng hiện luôn được tính ở mức 100%.
 *
 * <p><b>TODO khi có dữ liệu thật:</b>
 * <ul>
 *   <li>{@code getKpiPercentage}: tính từ module KPI (lợi nhuận gộp, thu tiền,
 *       doanh số hợp lệ, kỷ luật ERP, chăm sóc khách…) theo quy chế thưởng.</li>
 *   <li>{@code getActualWorkHours}: đọc từ file chấm công đã import cho kỳ lương.</li>
 * </ul>
 */
@Component
public class PayrollInputProvider {

    /**
     * Tỷ lệ KPI đạt được của nhân viên trong kỳ, theo thang 100
     * (100.0 = 100%). Dùng để tính thưởng theo KPI.
     * Hiện mặc định trả về 100%.
     */
    public double getKpiPercentage(User user) {
        // TODO: tính KPI thực tế theo quy chế thưởng.
        return 100.0;
    }

    /**
     * Số giờ công thực tế của nhân viên trong kỳ (lấy từ file chấm công).
     * Dùng để tính lương theo giờ cho nhân viên hưởng lương theo giờ (Xưởng).
     * Hiện mặc định trả về đủ công = {@link PayrollTaxCalculator#STANDARD_WORK_HOURS} (208 giờ).
     */
    public double getActualWorkHours(User user) {
        // TODO: đọc số giờ công thực tế từ file chấm công của kỳ lương.
        return PayrollTaxCalculator.STANDARD_WORK_HOURS;
    }
}