package com.nhatnam.server.repository;

import com.nhatnam.server.entity.LotPricingRequest;
import com.nhatnam.server.entity.LotPricingRequest.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LotPricingRequestRepository extends JpaRepository<LotPricingRequest, Long> {

    List<LotPricingRequest> findByStatusOrderByCreatedAtDesc(Status status);

    List<LotPricingRequest> findTop100ByStatusOrderByPricedAtDesc(Status status);

    long countByStatus(Status status);

    List<LotPricingRequest> findByIngredientExpiryIdAndStatus(Long ingredientExpiryId, Status status);
}
