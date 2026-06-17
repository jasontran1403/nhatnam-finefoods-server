package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Ingredient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface IngredientRepository extends JpaRepository<Ingredient, Long> {
    List<Ingredient> findByIsActiveTrueOrderByNameAsc();
    List<Ingredient> findByIsActiveTrue();
    Optional<Ingredient> findByIdAndIsActiveTrue(Long id);
    List<Ingredient> findByIsActiveTrue(Pageable pageable);

}