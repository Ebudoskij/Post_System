package com.ebudosky.post_system.model.manifest;

import java.math.BigDecimal;

/**
 * Результат аналітичної агрегації (Сценарій 5 бенчмарку):
 * Статистика за типом доставки (SQL GROUP BY + JOIN проти MongoDB Aggregation Pipeline).
 */
public record DeliveryTypeStats(
        String deliveryType,
        long parcelCount,
        double avgWeightKg,
        double avgDeclaredValue,
        double avgTelemetryEventsCount
) {}
