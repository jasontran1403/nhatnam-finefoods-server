package com.nhatnam.server.service;

import com.nhatnam.server.dto.ChartPointResponse;
import com.nhatnam.server.dto.DashboardSummaryResponse;
import com.nhatnam.server.dto.TopProductResponse;
import com.nhatnam.server.dto.dashboard.TopCustomerDto;
import com.nhatnam.server.dto.dashboard.TopSellerDto;

import java.util.List;

public interface DashboardService {
    DashboardSummaryResponse getSummary(long from, long to);
    List<ChartPointResponse>  getChart(long from, long to, String groupBy);
    List<TopProductResponse>  getTopProducts(long from, long to, int limit);

    // Tái dùng từ admin.DashboardService
    List<TopSellerDto>   getTopSellers(int limit, long from, long to, String sortBy);
    List<TopCustomerDto> getTopCustomers(int limit, long from, long to);
}
