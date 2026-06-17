package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.CreateCategoryRequest;
import com.nhatnam.server.dto.response.CategoryResponse;
import com.nhatnam.server.entity.Category;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsByNameIgnoreCaseAndIsActiveTrue(request.getName())) {
            throw new IllegalArgumentException("Danh mục '" + request.getName() + "' đã tồn tại");
        }
        long now = System.currentTimeMillis();
        Category category = Category.builder()
                .name(request.getName().trim())
                .isActive(true)
                .imageUrl(request.getImageUrl())
                .createdAt(now)
                .updatedAt(now)
                .build();
        Category saved = categoryRepository.save(category);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(Long id, CreateCategoryRequest request) {
        Category category = categoryRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));

        if (!category.getName().equalsIgnoreCase(request.getName()) &&
                categoryRepository.existsByNameIgnoreCaseAndIsActiveTrue(request.getName())) {
            throw new IllegalArgumentException("Danh mục '" + request.getName() + "' đã tồn tại");
        }
        category.setName(request.getName().trim());
        category.setUpdatedAt(System.currentTimeMillis());
        category.setImageUrl(request.getImageUrl());
        Category updated = categoryRepository.save(category);
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));
        category.setIsActive(false);
        category.setUpdatedAt(System.currentTimeMillis());
        categoryRepository.save(category);
    }

    @Override
    public CategoryResponse getCategoryById(Long id) {
        Category category = categoryRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));
        return mapToResponse(category);
    }

    /**
     * FIX: Chỉ trả category ROOT (parent_id IS NULL) để các row như
     * "Beef" (parent_id=1) không xuất hiện trong danh sách category chính.
     */
    @Override
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findRootCategoriesActive().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<CategoryResponse> getPaginationCategories(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return categoryRepository.findByIsActiveTrue(pageable).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private CategoryResponse mapToResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .isActive(c.getIsActive())
                .imageUrl(c.getImageUrl())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}