package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.CreateSubCategoryRequest;
import com.nhatnam.server.dto.response.SubCategoryResponse;
import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.SubCategory;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.repository.SubCategoryRepository;
import com.nhatnam.server.service.SubCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubCategoryServiceImpl implements SubCategoryService {

    private final SubCategoryRepository subCategoryRepository;
    private final CategoryRepository    categoryRepository;

    @Override
    @Transactional
    public SubCategoryResponse create(CreateSubCategoryRequest req) {
        // Kiểm tra parent category tồn tại
        Category parent = categoryRepository.findByIdAndIsActiveTrue(req.getCategoryId())
                .orElseThrow(() -> new RuntimeException(
                        "Danh mục cha không tồn tại: " + req.getCategoryId()));

        // Không trùng tên trong cùng 1 parent
        if (subCategoryRepository.existsByNameIgnoreCaseAndCategoryIdAndIsActiveTrue(
                req.getName(), req.getCategoryId())) {
            throw new IllegalArgumentException(
                    "Danh mục con '" + req.getName() + "' đã tồn tại trong '" + parent.getName() + "'");
        }

        long now = System.currentTimeMillis();
        SubCategory sub = SubCategory.builder()
                .name(req.getName().trim())
                .imageUrl(req.getImageUrl())
                .categoryId(req.getCategoryId())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        SubCategory saved = subCategoryRepository.save(sub);
        return toResponse(saved, parent.getName());
    }

    @Override
    @Transactional
    public SubCategoryResponse update(Long id, CreateSubCategoryRequest req) {
        SubCategory sub = subCategoryRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sub-category: " + id));

        Category parent = categoryRepository.findByIdAndIsActiveTrue(req.getCategoryId())
                .orElseThrow(() -> new RuntimeException(
                        "Danh mục cha không tồn tại: " + req.getCategoryId()));

        if (!sub.getName().equalsIgnoreCase(req.getName()) &&
                subCategoryRepository.existsByNameIgnoreCaseAndCategoryIdAndIsActiveTrue(
                        req.getName(), req.getCategoryId())) {
            throw new IllegalArgumentException(
                    "Danh mục con '" + req.getName() + "' đã tồn tại trong '" + parent.getName() + "'");
        }

        sub.setName(req.getName().trim());
        sub.setImageUrl(req.getImageUrl());
        sub.setCategoryId(req.getCategoryId());
        sub.setUpdatedAt(System.currentTimeMillis());

        SubCategory updated = subCategoryRepository.save(sub);
        return toResponse(updated, parent.getName());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SubCategory sub = subCategoryRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sub-category: " + id));
        sub.setIsActive(false);
        sub.setUpdatedAt(System.currentTimeMillis());
        subCategoryRepository.save(sub);
    }

    @Override
    public SubCategoryResponse getById(Long id) {
        SubCategory sub = subCategoryRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sub-category: " + id));
        String catName = categoryRepository.findByIdAndIsActiveTrue(sub.getCategoryId())
                .map(Category::getName).orElse("");
        return toResponse(sub, catName);
    }

    @Override
    public List<SubCategoryResponse> getAll() {
        return subCategoryRepository.findByIsActiveTrueOrderByNameAsc()
                .stream()
                .map(sub -> {
                    String catName = categoryRepository.findByIdAndIsActiveTrue(sub.getCategoryId())
                            .map(Category::getName).orElse("");
                    return toResponse(sub, catName);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<SubCategoryResponse> getByCategoryId(Long categoryId) {
        return subCategoryRepository
                .findByCategoryIdAndIsActiveTrueOrderByNameAsc(categoryId)
                .stream()
                .map(sub -> toResponse(sub, null))   // categoryName không cần thiết khi đã filter
                .collect(Collectors.toList());
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private SubCategoryResponse toResponse(SubCategory sub, String categoryName) {
        return SubCategoryResponse.builder()
                .id(sub.getId())
                .name(sub.getName())
                .imageUrl(sub.getImageUrl())
                .categoryId(sub.getCategoryId())
                .categoryName(categoryName)
                .isActive(sub.getIsActive())
                .createdAt(sub.getCreatedAt())
                .updatedAt(sub.getUpdatedAt())
                .build();
    }
}