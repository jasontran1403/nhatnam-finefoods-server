package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.MixDtos.*;
import com.nhatnam.server.service.MixService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Mix gia vị (Mục 4) — SUPER_FACTORY_WORKER. */
@RestController
@RequestMapping("/api/factory/mix")
@RequiredArgsConstructor
public class MixController {

    private final MixService mixService;

    /** Sản phẩm đầu ra khả dụng (nguyên liệu isMixable=true của xưởng). */
    @GetMapping("/outputs")
    public ApiResponse<List<Map<String, Object>>> outputs(@RequestParam Long factoryId) {
        return ApiResponse.ok(mixService.listMixableOutputs(factoryId));
    }

    /** Kiểm tra tồn kho đầu vào trước khi trộn. */
    @PostMapping("/check")
    public ApiResponse<MixCheckResult> check(@RequestBody MixCheckRequest req) {
        return ApiResponse.ok(mixService.check(req));
    }

    /** Thực hiện trộn: tạo phiếu nhập (đầu ra) + phiếu xuất (đầu vào). */
    @PostMapping("/execute")
    public ApiResponse<Void> execute(@RequestBody MixExecuteRequest req, Authentication auth) {
        mixService.execute(req, auth.getName());
        return ApiResponse.ok((Void) null);
    }
}
