package com.ebudosky.post_system.model.manifest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Митна декларація та інвойсні дані для відправлення.
 * У PostgreSQL відображається в таблицю customs_declarations (1:1).
 * У MongoDB зберігається як вбудований об'єкт.
 */
public record CustomsDeclaration(
        String declarationNumber,
        String originCountry,
        String destinationCountry,
        String currency,
        BigDecimal totalCustomsValue,
        String incoterms,
        String exportPurpose,
        boolean customsCleared,
        LocalDateTime inspectedAt
) {}
