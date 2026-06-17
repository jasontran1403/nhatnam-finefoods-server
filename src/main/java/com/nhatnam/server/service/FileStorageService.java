package com.nhatnam.server.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface FileStorageService {
    String saveCategoryImage(MultipartFile file) throws IOException;
    String saveProductImage(MultipartFile file) throws IOException;
    String saveIngredientImage(MultipartFile file) throws IOException;
    void deleteFile(String filePath) throws IOException;
    String saveLandingpageImage(MultipartFile file) throws IOException;
    String saveExpenseImage(MultipartFile file) throws IOException;
    String saveIncomeImage(MultipartFile file) throws IOException;   // ← MỚI
    String saveEventImage(MultipartFile file) throws IOException;
    byte[] getFile(String filePath) throws IOException;
    String saveSellerImportReceiptImage(MultipartFile file) throws IOException;
    String saveInventoryImage(MultipartFile file) throws IOException;
    String saveReceiptFile(MultipartFile file) throws IOException;
    String saveCertificateFile(MultipartFile file) throws IOException;
}