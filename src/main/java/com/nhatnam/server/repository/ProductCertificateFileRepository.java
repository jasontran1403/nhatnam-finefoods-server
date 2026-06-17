package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductCertificateFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductCertificateFileRepository extends JpaRepository<ProductCertificateFile, Long> {
    List<ProductCertificateFile> findByCertificateId(Long certificateId);
}
