package com.nhatnam.server.service.serviceimpl;

import java.math.BigDecimal;

record ResolvedPrice(
        BigDecimal unitPrice,
        String     priceMode,
        Long       tierId,
        String     tierName,
        Integer    discountPercent
) {}
