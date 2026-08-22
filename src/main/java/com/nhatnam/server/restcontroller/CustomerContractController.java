package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.CustomerContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * HỢP ĐỒNG KHÁCH HÀNG.
 *
 * <pre>
 *  GET  /api/customer-contracts/{customerId}          → xem bộ hợp đồng hiện hành
 *  POST /api/customer-contracts/{customerId}          → tải lên (THAY bộ cũ), multipart "files"
 * </pre>
 *
 * <p>Quyền cập nhật: kế toán, kế toán trưởng, seller, trưởng phòng kinh doanh,
 * admin, owner. Quyền XEM rộng hơn — bất kỳ ai đã đăng nhập, vì trang bán hàng
 * cần biết khách có hợp đồng chưa để bật/tắt lựa chọn công nợ.
 */
@RestController
@RequestMapping("/api/customer-contracts")
@RequiredArgsConstructor
@Log4j2
public class CustomerContractController {

    private final CustomerContractService contractService;

    @GetMapping("/{customerId}")
    public ResponseEntity<ApiResponse<CustomerContractService.ContractDto>> get(
            @PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.getContract(customerId), "OK"));
    }

    /**
     * Tải hợp đồng lên. Bộ ảnh cũ (nếu có) bị thay hoàn toàn.
     *
     * <p>FE phải hỏi xác nhận trước khi gọi khi khách ĐÃ có hợp đồng — server
     * không chặn, vì việc thay hợp đồng là hợp lệ; điều cần tránh chỉ là thay
     * nhầm do bấm vội.
     */
    @PostMapping("/{customerId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','ACCOUNTANT','SUPER_ACCOUNTANT','SELLER','SUPER_SELLER')")
    public ResponseEntity<ApiResponse<CustomerContractService.ContractDto>> upload(
            @AuthenticationPrincipal User actor,
            @PathVariable Long customerId,
            @RequestParam("files") List<MultipartFile> files) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    contractService.replaceContract(customerId, files, actor),
                    "Đã cập nhật hợp đồng"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("Upload hợp đồng khách {} thất bại", customerId, e);
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, "Không cập nhật được hợp đồng"));
        }
    }
}
