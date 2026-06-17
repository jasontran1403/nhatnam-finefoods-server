package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.analytics.AnalyticsDtos.AnalyticsPeriod;
import com.nhatnam.server.dto.analytics.AnalyticsDtos.BusinessAnalyticsResponse;
import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.service.BusinessAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/owner/analytics")
@RequiredArgsConstructor
public class BusinessAnalyticsController {

    private final BusinessAnalyticsService analyticsService;

    @GetMapping
    public ApiResponse<BusinessAnalyticsResponse> getAnalytics(
            @RequestParam(defaultValue = "MONTH") AnalyticsPeriod period) {
        return ApiResponse.ok(analyticsService.getAnalytics(period));
    }
}