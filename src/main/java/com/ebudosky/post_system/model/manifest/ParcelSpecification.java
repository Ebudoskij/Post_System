package com.ebudosky.post_system.model.manifest;

import java.math.BigDecimal;

/**
 * Фізичні характеристики, габарити та спеціальні умови перевезення посилки.
 * У PostgreSQL відображається в окрему таблицю parcel_specifications (1:1).
 * У MongoDB вбудовується (embedded sub-document) всередину документа parcel_manifests.
 */
public record ParcelSpecification(
        String deliveryType,
        String packagingType,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean fragile,
        boolean requiresTempControl,
        BigDecimal minTempCelsius,
        BigDecimal maxTempCelsius,
        BigDecimal declaredValue,
        String specialInstructions
) {}
