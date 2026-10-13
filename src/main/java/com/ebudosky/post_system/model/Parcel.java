package com.ebudosky.post_system.model;

import java.math.BigDecimal;

public record Parcel(
        Long id,
        String trackingNumber,
        Long senderId,
        Long receiverId,
        BigDecimal weight,
        Long statusId
) {}
