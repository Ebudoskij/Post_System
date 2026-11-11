package com.ebudosky.post_system.benchmark;

import com.ebudosky.post_system.model.manifest.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Генератор реалістичних даних для навантажувального тестування (Benchmark).
 * Створює складні об'єкти ParcelManifestDocument, кожен з яких відображається:
 * - у MongoDB: в 1 BSON-документ із вкладеними об'єктами та масивами;
 * - у PostgreSQL: у 5 нормалізованих таблиць (~13 рядків у сумі на 1 посилку).
 */
@Component
public class ManifestDataGenerator {

    private static final String[] DELIVERY_TYPES = {"STANDARD", "EXPRESS", "INTERNATIONAL", "CARGO_PALLET", "COLD_CHAIN"};
    private static final String[] PACKAGING_TYPES = {"CORRUGATED_BOX", "POLY_ENVELOPE", "WOODEN_CRATE", "THERMO_CONTAINER", "PALLET_WRAP"};
    private static final String[] COUNTRIES = {"UKR", "POL", "DEU", "USA", "GBR", "FRA", "ITA", "CZE"};
    private static final String[] CURRENCIES = {"UAH", "EUR", "USD"};
    private static final String[] INCOTERMS = {"DAP", "DDP", "CPT", "EXW"};
    private static final String[] EXPORT_PURPOSES = {"SALE_OF_GOODS", "PERSONAL_USE", "GIFT", "COMMERCIAL_SAMPLE"};
    private static final String[] ITEM_CATEGORIES = {"ELECTRONICS", "CLOTHING", "PHARMACEUTICALS", "AUTO_PARTS", "DOCUMENTS", "HOME_APPLIANCES"};
    private static final String[] ITEM_NAMES = {
            "Ноутбук ультрабук", "Смарт-годинник", "Куртка зимова", "Комплект гальмівних колодок",
            "Медичні реагенти", "Навушники бездротові", "Кавомашина", "Мікроконтролер STM32",
            "Кросівки бігові", "Оптичний датчик", "Книга технічна", "Адаптер живлення"
    };
    private static final String[] HS_CODES = {
            "8471.30.00", "8517.62.00", "6201.40.10", "8708.30.91", "3004.90.00", "8518.30.00"
    };
    private static final String[] HUB_NAMES = {
            "Київ-Центральний Термінал", "Ірпінь-Сортувальний", "Львів-Захід-Хаб",
            "Одеса-Порт-Логістик", "Харків-Схід-Термінал", "Дніпро-Авто-Хаб", "Житомир-Транзит"
    };

    /**
     * Генерує детермінований набір посилок для чесного порівняння SQL та NoSQL.
     */
    public List<ParcelManifestDocument> generateBatch(int startIndex, int count) {
        List<ParcelManifestDocument> result = new ArrayList<>(count);
        Random rnd = new Random(42_000L + startIndex);
        LocalDateTime baseTime = LocalDateTime.of(2026, 10, 1, 8, 0, 0);

        for (int i = 0; i < count; i++) {
            int seq = startIndex + i;
            String trackingNumber = String.format("BMK%07d", seq);

            long senderId = 1L + (seq % 10);
            long receiverId = 1L + ((seq + 3) % 10);
            long statusId = 1L + (seq % 8);
            boolean fragile = (seq % 3 == 0);
            boolean tempControl = (seq % 5 == 0);
            String deliveryType = DELIVERY_TYPES[seq % DELIVERY_TYPES.length];
            String packagingType = PACKAGING_TYPES[seq % PACKAGING_TYPES.length];

            BigDecimal weight = BigDecimal.valueOf(0.5 + (rnd.nextDouble() * 35.0)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal declaredVal = BigDecimal.valueOf(200 + rnd.nextInt(25000)).setScale(2, RoundingMode.HALF_UP);

            ParcelSpecification spec = new ParcelSpecification(
                    deliveryType,
                    packagingType,
                    BigDecimal.valueOf(20 + rnd.nextInt(80)).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(15 + rnd.nextInt(60)).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(10 + rnd.nextInt(50)).setScale(2, RoundingMode.HALF_UP),
                    fragile,
                    tempControl,
                    tempControl ? BigDecimal.valueOf(2.0).setScale(2, RoundingMode.HALF_UP) : null,
                    tempControl ? BigDecimal.valueOf(8.0).setScale(2, RoundingMode.HALF_UP) : null,
                    declaredVal,
                    fragile ? "Обережно! Крихкий вантаж, не кантувати" : "Стандартне завантаження"
            );

            CustomsDeclaration customs = new CustomsDeclaration(
                    String.format("CD-2026-%07d", seq),
                    "UKR",
                    COUNTRIES[seq % COUNTRIES.length],
                    CURRENCIES[seq % CURRENCIES.length],
                    declaredVal,
                    INCOTERMS[seq % INCOTERMS.length],
                    EXPORT_PURPOSES[seq % EXPORT_PURPOSES.length],
                    seq % 2 == 0,
                    baseTime.plusHours(seq % 240)
            );

            // Генеруємо 4 товари всередині кожної посилки (для 50 000 посилок = 200 000 рядків у parcel_items)
            int itemsCount = 4;
            List<ParcelItem> items = new ArrayList<>(itemsCount);
            for (int j = 0; j < itemsCount; j++) {
                int idx = (seq + j) % ITEM_NAMES.length;
                int catIdx = (seq + j) % ITEM_CATEGORIES.length;
                items.add(new ParcelItem(
                        ITEM_NAMES[idx],
                        HS_CODES[catIdx],
                        ITEM_CATEGORIES[catIdx],
                        1 + (j % 3),
                        BigDecimal.valueOf(0.2 + (j * 0.45)).setScale(3, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(150.0 + (j * 320.0)).setScale(2, RoundingMode.HALF_UP),
                        COUNTRIES[(seq + j) % COUNTRIES.length]
                ));
            }

            // Генеруємо 6 подій трекінгу з IoT-телеметрією (для 50 000 посилок = 300 000 рядків у tracking_telemetry)
            int eventsCount = 6;
            List<TrackingTelemetryEvent> events = new ArrayList<>(eventsCount);
            for (int k = 0; k < eventsCount; k++) {
                long evStatus = 1L + k;
                long branchId = 1L + ((seq + k) % 10);
                double shock = (fragile && k == 3 && (seq % 10 == 0)) ? 4.85 : (0.3 + (k * 0.25));
                events.add(new TrackingTelemetryEvent(
                        evStatus,
                        branchId,
                        HUB_NAMES[(seq + k) % HUB_NAMES.length],
                        String.format("SCN-%03d", 1 + ((seq + k) % 50)),
                        String.format("OP-%04d", 100 + ((seq + k) % 200)),
                        BigDecimal.valueOf(48.0 + (k * 0.35)).setScale(6, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(30.0 + (k * 0.45)).setScale(6, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(tempControl ? 4.5 : (18.0 + (k % 5))).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(45.0 + (k * 2.5)).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(shock).setScale(2, RoundingMode.HALF_UP),
                        baseTime.plusHours((seq % 100) + (k * 4L)),
                        "Автоматичне сканування вузла #" + (k + 1)
                ));
            }

            result.add(new ParcelManifestDocument(
                    null,
                    null,
                    trackingNumber,
                    senderId,
                    receiverId,
                    weight,
                    statusId,
                    false,
                    baseTime.plusHours(seq % 300),
                    spec,
                    customs,
                    items,
                    events
            ));
        }

        return result;
    }

    public TrackingTelemetryEvent createNewLiveEvent(int seed) {
        return new TrackingTelemetryEvent(
                6L,
                2L,
                "Ірпінь-Відділення-Видачі",
                "SCN-LIVE-999",
                "OP-LIVE-77",
                BigDecimal.valueOf(50.518600).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(30.239700).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(19.50).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(50.00).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(0.45 + (seed % 3) * 0.2).setScale(2, RoundingMode.HALF_UP),
                LocalDateTime.now(),
                "Прибуття у відділення видачі (Live Benchmark Append)"
        );
    }
}
