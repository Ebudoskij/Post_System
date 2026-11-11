package com.ebudosky.post_system.model.manifest;

import java.math.BigDecimal;

/**
 * Опис одиниці вкладення (товару) всередині посилки.
 * У PostgreSQL відображається в окрему таблицю parcel_items (зв'язок 1:N).
 * У MongoDB є елементом масиву items[] у документі посилки.
 */
public record ParcelItem(
        String itemName,
        String hsCode,
        String category,
        int quantity,
        BigDecimal unitWeightKg,
        BigDecimal unitValue,
        String manufacturerCountry
) {}
