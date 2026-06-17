package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.ManualImportRequest;
import com.nhatnam.server.dto.response.ManualImportResponse;
import com.nhatnam.server.entity.User;

public interface ManualImportService {
    ManualImportResponse importBatch(ManualImportRequest request, User actor);
}