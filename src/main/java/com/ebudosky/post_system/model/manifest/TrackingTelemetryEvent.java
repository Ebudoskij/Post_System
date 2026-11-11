package com.ebudosky.post_system.model.manifest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Розширений запис трекінгу та IoT-телеметрії (GPS, температура, вібрація/удар, сканер).
 * У PostgreSQL відображається в таблицю tracking_telemetry (зв'язок 1:N).
 * У MongoDB є елементом масиву telemetryEvents[] у документі посилки.
 */
public record TrackingTelemetryEvent(
        Long statusId,
        Long branchId,
        String hubName,
        String scannerDeviceId,
        String operatorCode,
        BigDecimal gpsLat,
        BigDecimal gpsLon,
        BigDecimal temperatureC,
        BigDecimal humidityPercent,
        BigDecimal shockGForce,
        LocalDateTime recordedAt,
        String notes
) {}
