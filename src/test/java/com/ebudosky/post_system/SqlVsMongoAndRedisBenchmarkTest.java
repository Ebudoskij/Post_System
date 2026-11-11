package com.ebudosky.post_system;

import com.ebudosky.post_system.benchmark.ManifestDataGenerator;
import com.ebudosky.post_system.benchmark.PerformanceBenchmarkService;
import com.ebudosky.post_system.model.manifest.ParcelManifestDocument;
import com.ebudosky.post_system.repository.MongoParcelManifestRepository;
import com.ebudosky.post_system.repository.SqlParcelManifestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Комплексний тест ідентичності бізнес-логіки SQL (5 таблиць) vs MongoDB (1 колекція)
 * та запуск повномасштабного бенчмарку швидкодії (50 000 посилок = ~650 000 рядків у SQL).
 */
@SpringBootTest
class SqlVsMongoAndRedisBenchmarkTest {

    @Autowired
    private SqlParcelManifestRepository sqlRepo;

    @Autowired
    private MongoParcelManifestRepository mongoRepo;

    @Autowired
    private ManifestDataGenerator generator;

    @Autowired
    private PerformanceBenchmarkService benchmarkService;

    @Test
    @DisplayName("Перевірка еквівалентності даних у 5 таблицях PostgreSQL та документі MongoDB + Повний Бенчмарк (50 000 посилок)")
    void testFunctionalEquivalenceAndRunFullBenchmark() {
        // 1. Запускаємо повний бенчмарк на 50 000 складних посилок (~650 000 рядків у 5 таблицях SQL)
        int totalParcels = 50_000;
        List<PerformanceBenchmarkService.BenchmarkMetric> metrics = benchmarkService.runFullBenchmarkSuite(totalParcels);

        assertThat(metrics).isNotEmpty();
        assertThat(sqlRepo.countBenchmarkParcels()).isEqualTo(totalParcels);
        assertThat(mongoRepo.countBenchmarkParcels()).isEqualTo(totalParcels);

        // 2. Перевіряємо повної ідентичності зчитаного агрегата між SQL (обома способами) та MongoDB
        String sampleTrn = "BMK0025000";
        Optional<ParcelManifestDocument> fromSqlMulti = sqlRepo.findByTrackingNumber(sampleTrn);
        Optional<ParcelManifestDocument> fromSqlFiveJoin = sqlRepo.findByTrackingNumberSingleFiveWayJoin(sampleTrn);
        Optional<ParcelManifestDocument> fromMongo = mongoRepo.findByTrackingNumber(sampleTrn);

        assertThat(fromSqlMulti).isPresent();
        assertThat(fromSqlFiveJoin).isPresent();
        assertThat(fromMongo).isPresent();

        ParcelManifestDocument sqlDoc = fromSqlMulti.get();
        ParcelManifestDocument joinDoc = fromSqlFiveJoin.get();
        ParcelManifestDocument mongoDoc = fromMongo.get();

        assertThat(sqlDoc.getTrackingNumber()).isEqualTo(mongoDoc.getTrackingNumber());
        assertThat(sqlDoc.getWeight()).isEqualByComparingTo(mongoDoc.getWeight());
        assertThat(sqlDoc.getSpecification().deliveryType()).isEqualTo(mongoDoc.getSpecification().deliveryType());
        assertThat(sqlDoc.getCustomsDeclaration().declarationNumber()).isEqualTo(mongoDoc.getCustomsDeclaration().declarationNumber());
        assertThat(sqlDoc.getItems()).hasSize(mongoDoc.getItems().size());
        assertThat(joinDoc.getItems()).hasSize(mongoDoc.getItems().size());
        assertThat(sqlDoc.getTelemetryEvents()).hasSize(mongoDoc.getTelemetryEvents().size());
        assertThat(joinDoc.getTelemetryEvents()).hasSize(mongoDoc.getTelemetryEvents().size());
    }
}
