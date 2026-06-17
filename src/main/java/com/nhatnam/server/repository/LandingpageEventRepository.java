package com.nhatnam.server.repository;

import com.nhatnam.server.entity.LandingpageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LandingpageEventRepository extends JpaRepository<LandingpageEvent, Long> {
    /** Lấy ngẫu nhiên tối đa N ảnh */
    @Query(value = "SELECT * FROM landingpage_events ORDER BY RAND() LIMIT :limit", nativeQuery = true)
    List<LandingpageEvent> findRandom(int limit);
}