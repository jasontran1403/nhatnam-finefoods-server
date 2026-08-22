package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Token;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TokenRepository extends JpaRepository<Token, Integer> {
    Optional<Token> findByToken(String token);

    /** Tất cả token còn hiệu lực của 1 user — dùng để thu hồi phiên khi xoá/khoá tài khoản. */
    @Query("SELECT t FROM Token t WHERE t.user.id = :userId AND (t.expired = false OR t.revoked = false)")
    List<Token> findAllValidTokenByUser(@Param("userId") Long userId);
}
