package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateSubCategoryRequest;
import com.nhatnam.server.dto.response.SubCategoryResponse;

import java.util.List;

public interface SubCategoryService {

    SubCategoryResponse create(CreateSubCategoryRequest req);

    SubCategoryResponse update(Long id, CreateSubCategoryRequest req);

    void delete(Long id);

    SubCategoryResponse getById(Long id);

    /** Tất cả sub-categories đang active */
    List<SubCategoryResponse> getAll();

    /** Sub-categories theo categoryId */
    List<SubCategoryResponse> getByCategoryId(Long categoryId);
}