package com.ebudosky.post_system.benchmark;

import com.ebudosky.post_system.model.manifest.DeliveryTypeStats;
import com.ebudosky.post_system.model.manifest.ParcelManifestDocument;
import com.ebudosky.post_system.model.manifest.TrackingTelemetryEvent;
import com.ebudosky.post_system.repository.MongoParcelManifestRepository;
import com.ebudosky.post_system.repository.SqlParcelManifestRepository;
import com.ebudosky.post_system.service.RedisPostService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Стенд тестування швидкодії (Benchmark) для порівняння:
 * - PostgreSQL (5 нормалізованих таблиць з JOIN та індексами)
 * - MongoDB (1 колекція агрегованих документів BSON)
 * - Redis (In-Memory Key-Value кеш та спеціалізовані структури даних)
 */
@Service
public class PerformanceBenchmarkService {

    private final SqlParcelManifestRepository sqlRepo;
    private final MongoParcelManifestRepository mongoRepo;
    private final RedisPostService redisService;
    private final ManifestDataGenerator generator;

    public PerformanceBenchmarkService(
            SqlParcelManifestRepository sqlRepo,
            MongoParcelManifestRepository mongoRepo,
            RedisPostService redisService,
            ManifestDataGenerator generator
    ) {
        this.sqlRepo = sqlRepo;
        this.mongoRepo = mongoRepo;
        this.redisService = redisService;
        this.generator = generator;
    }

    public record BenchmarkMetric(
            String scenarioName,
            String databaseEngine,
            int operationsCount,
            double totalTimeMs,
            double avgLatencyMs,
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            double throughputOpsPerSec
    ) {}

    /**
     * Повний цикл навантажувального тестування з прогрівом (Warm-up) та заміром перцентилів.
     *
     * @param totalParcels загальна кількість посилок (напр. 50 000 = ~650 000 рядків у 5 таблицях SQL)
     */
    public List<BenchmarkMetric> runFullBenchmarkSuite(int totalParcels) {
        List<BenchmarkMetric> results = new ArrayList<>();

        System.out.println("================================================================================");
        System.out.println("СТАРТ БЕНЧМАРКУ: PostgreSQL (5 таблиць) vs MongoDB (Document) vs Redis (In-Memory)");
        System.out.println("Масштаб: " + totalParcels + " посилок (~" + (totalParcels * 13) + " рядків у SQL)");
        System.out.println("================================================================================");

        // 0. Очищення попередніх даних та Warm-up
        sqlRepo.clearBenchmarkData();
        mongoRepo.clearBenchmarkData();
        redisService.clearAllPostRedisKeys();

        performWarmup();

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 1: Масова пакетна вставка (Bulk Write)
        // Розбиваємо на батчі по 2 500 посилок (~32 500 рядків SQL за батч)
        // -------------------------------------------------------------------------
        int batchSize = 2500;
        int batches = totalParcels / batchSize;

        long[] sqlBatchLatenciesNs = new long[batches];
        long[] mongoBatchLatenciesNs = new long[batches];
        long sqlTotalWriteNs = 0;
        long mongoTotalWriteNs = 0;

        for (int b = 0; b < batches; b++) {
            int startIdx = (b * batchSize) + 1;
            List<ParcelManifestDocument> sqlDocs = generator.generateBatch(startIdx, batchSize);
            List<ParcelManifestDocument> mongoDocs = generator.generateBatch(startIdx, batchSize);

            long t0 = System.nanoTime();
            sqlRepo.saveBatch(sqlDocs);
            long t1 = System.nanoTime();
            sqlBatchLatenciesNs[b] = (t1 - t0);
            sqlTotalWriteNs += (t1 - t0);

            long m0 = System.nanoTime();
            mongoRepo.saveBatch(mongoDocs);
            long m1 = System.nanoTime();
            mongoBatchLatenciesNs[b] = (m1 - m0);
            mongoTotalWriteNs += (m1 - m0);
        }

        results.add(buildBulkMetric(
                "1. Bulk Insert (" + totalParcels + " посилок / ~" + (totalParcels * 13) + " рядків SQL)",
                "PostgreSQL (5 tables Batch)",
                totalParcels,
                sqlTotalWriteNs,
                sqlBatchLatenciesNs,
                batchSize
        ));
        results.add(buildBulkMetric(
                "1. Bulk Insert (" + totalParcels + " посилок / ~" + (totalParcels * 13) + " рядків SQL)",
                "MongoDB (insertMany BSON)",
                totalParcels,
                mongoTotalWriteNs,
                mongoBatchLatenciesNs,
                batchSize
        ));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 2: Точкове читання повного агрегата посилки за tracking_number (Point Read)
        // -------------------------------------------------------------------------
        int pointReadOps = 2000;
        Random rnd = new Random(100500L);
        List<String> randomTrackingNumbers = new ArrayList<>(pointReadOps);
        for (int i = 0; i < pointReadOps; i++) {
            int id = 1 + rnd.nextInt(totalParcels);
            randomTrackingNumbers.add(String.format("BMK%07d", id));
        }

        // 2a. PostgreSQL (Multi-Query: JOIN 1:1 + 2 SELECT 1:N)
        long[] sqlMultiQueryNs = new long[pointReadOps];
        for (int i = 0; i < pointReadOps; i++) {
            String trn = randomTrackingNumbers.get(i);
            long start = System.nanoTime();
            Optional<ParcelManifestDocument> doc = sqlRepo.findByTrackingNumber(trn);
            sqlMultiQueryNs[i] = System.nanoTime() - start;
            if (doc.isEmpty()) {
                throw new IllegalStateException("Не знайдено посилку в SQL: " + trn);
            }
        }
        results.add(buildOpMetric("2. Point Read повного маніфесту за ТТН", "PostgreSQL (JOIN 1:1 + 2x1:N)", sqlMultiQueryNs));

        // 2b. PostgreSQL (Single 5-way LEFT JOIN з декартовим добутком items × telemetry)
        long[] sqlFiveJoinNs = new long[pointReadOps];
        for (int i = 0; i < pointReadOps; i++) {
            String trn = randomTrackingNumbers.get(i);
            long start = System.nanoTime();
            Optional<ParcelManifestDocument> doc = sqlRepo.findByTrackingNumberSingleFiveWayJoin(trn);
            sqlFiveJoinNs[i] = System.nanoTime() - start;
            if (doc.isEmpty()) {
                throw new IllegalStateException("Не знайдено посилку в 5-way JOIN SQL: " + trn);
            }
        }
        results.add(buildOpMetric("2. Point Read повного маніфесту за ТТН", "PostgreSQL (Single 5-way JOIN)", sqlFiveJoinNs));

        // 2c. MongoDB (1 Document Read by Unique Index)
        long[] mongoReadNs = new long[pointReadOps];
        for (int i = 0; i < pointReadOps; i++) {
            String trn = randomTrackingNumbers.get(i);
            long start = System.nanoTime();
            Optional<ParcelManifestDocument> doc = mongoRepo.findByTrackingNumber(trn);
            mongoReadNs[i] = System.nanoTime() - start;
            if (doc.isEmpty()) {
                throw new IllegalStateException("Не знайдено документ у MongoDB: " + trn);
            }
        }
        results.add(buildOpMetric("2. Point Read повного маніфесту за ТТН", "MongoDB (1 Document Index Read)", mongoReadNs));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 2B: Завантаження списку з 50 повних маніфестів (Кабінет клієнта / Маршрутний лист авто)
        // Порівняння: SQL N+1 (50x3 запити) vs SQL 5-way JOIN (1200 рядків декартового добутку)
        // vs MongoDB ($in — 50 документів за 1 запит) vs Redis (MGET — 50 ключів з RAM за 1 запит)
        // -------------------------------------------------------------------------
        int batchReadIterations = 100;
        int pageSize = 50;
        List<String> samplePage = randomTrackingNumbers.subList(0, pageSize);
        // Попередньо кешуємо сторінку в Redis для тесту MGET
        for (String trn : samplePage) {
            mongoRepo.findByTrackingNumber(trn).ifPresent(doc -> redisService.cacheManifest(doc, Duration.ofMinutes(10)));
        }

        long[] sqlNPlusOnePageNs = new long[batchReadIterations];
        long[] sqlJoinPageNs = new long[batchReadIterations];
        long[] mongoInPageNs = new long[batchReadIterations];
        long[] redisMgetPageNs = new long[batchReadIterations];

        for (int i = 0; i < batchReadIterations; i++) {
            int offset = (i * 15) % (pointReadOps - pageSize);
            List<String> pageTrns = randomTrackingNumbers.subList(offset, offset + pageSize);

            long sn0 = System.nanoTime();
            for (String trn : pageTrns) {
                sqlRepo.findByTrackingNumber(trn);
            }
            sqlNPlusOnePageNs[i] = System.nanoTime() - sn0;

            long sj0 = System.nanoTime();
            sqlRepo.findByTrackingNumbersInFiveWayJoin(pageTrns);
            sqlJoinPageNs[i] = System.nanoTime() - sj0;

            long mi0 = System.nanoTime();
            mongoRepo.findByTrackingNumbersIn(pageTrns);
            mongoInPageNs[i] = System.nanoTime() - mi0;

            long rm0 = System.nanoTime();
            redisService.getCachedManifestsRawJsonBatch(samplePage);
            redisMgetPageNs[i] = System.nanoTime() - rm0;
        }
        results.add(buildOpMetric("2B. Batch Read 50 маніфестів (Кабінет/Рейс)", "PostgreSQL (N+1 Multi-Query)", sqlNPlusOnePageNs));
        results.add(buildOpMetric("2B. Batch Read 50 маніфестів (Кабінет/Рейс)", "PostgreSQL (5-way JOIN 1200 rows)", sqlJoinPageNs));
        results.add(buildOpMetric("2B. Batch Read 50 маніфестів (Кабінет/Рейс)", "MongoDB ($in 50 Documents)", mongoInPageNs));
        results.add(buildOpMetric("2B. Batch Read 50 маніфестів (Кабінет/Рейс)", "Redis (MGET 50 Keys In-Memory)", redisMgetPageNs));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 3: Real-time Append нової події трекінгу з IoT-телеметрією
        // -------------------------------------------------------------------------
        int appendOps = 2000;
        long[] sqlAppendNs = new long[appendOps];
        long[] mongoAppendNs = new long[appendOps];

        for (int i = 0; i < appendOps; i++) {
            String trn = randomTrackingNumbers.get(i);
            TrackingTelemetryEvent newEvent = generator.createNewLiveEvent(i);

            long s0 = System.nanoTime();
            sqlRepo.appendTelemetryEvent(trn, newEvent);
            sqlAppendNs[i] = System.nanoTime() - s0;

            long m0 = System.nanoTime();
            mongoRepo.appendTelemetryEvent(trn, newEvent);
            mongoAppendNs[i] = System.nanoTime() - m0;
        }
        results.add(buildOpMetric("3. Real-Time Append події трекінгу", "PostgreSQL (INSERT + UPDATE)", sqlAppendNs));
        results.add(buildOpMetric("3. Real-Time Append події трекінгу", "MongoDB (Atomic $push + $set)", mongoAppendNs));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 4: Складний пошук за глибоко вкладеними атрибутами (4 таблиці SQL vs Mongo Document)
        // -------------------------------------------------------------------------
        int filterOps = 200;
        String[] dTypes = {"EXPRESS", "INTERNATIONAL", "COLD_CHAIN", "STANDARD"};
        String[] cats = {"ELECTRONICS", "PHARMACEUTICALS", "AUTO_PARTS", "CLOTHING"};
        long[] sqlFilterNs = new long[filterOps];
        long[] mongoFilterNs = new long[filterOps];

        for (int i = 0; i < filterOps; i++) {
            String dt = dTypes[i % dTypes.length];
            String cat = cats[i % cats.length];
            BigDecimal minShock = BigDecimal.valueOf(4.0);

            long s0 = System.nanoTime();
            List<String> sqlMatches = sqlRepo.findTrackingNumbersByDeepCriteria(dt, true, cat, minShock, 50);
            sqlFilterNs[i] = System.nanoTime() - s0;

            long m0 = System.nanoTime();
            List<String> mongoMatches = mongoRepo.findTrackingNumbersByDeepCriteria(dt, true, cat, minShock, 50);
            mongoFilterNs[i] = System.nanoTime() - m0;

            if (sqlMatches.size() != mongoMatches.size()) {
                throw new IllegalStateException("Розбіжність результатів фільтрації SQL (" + sqlMatches.size()
                        + ") та MongoDB (" + mongoMatches.size() + ")");
            }
        }
        results.add(buildOpMetric("4. Deep Filter (Специфікація + Товари + Телеметрія)", "PostgreSQL (4-table JOIN)", sqlFilterNs));
        results.add(buildOpMetric("4. Deep Filter (Специфікація + Товари + Телеметрія)", "MongoDB (Embedded Indexes)", mongoFilterNs));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 5: Аналітична агрегація по всій базі (GROUP BY vs Aggregation Pipeline)
        // -------------------------------------------------------------------------
        int aggOps = 20;
        long[] sqlAggNs = new long[aggOps];
        long[] mongoAggNs = new long[aggOps];

        for (int i = 0; i < aggOps; i++) {
            long s0 = System.nanoTime();
            List<DeliveryTypeStats> sqlStats = sqlRepo.aggregateStatsByDeliveryType();
            sqlAggNs[i] = System.nanoTime() - s0;

            long m0 = System.nanoTime();
            List<DeliveryTypeStats> mongoStats = mongoRepo.aggregateStatsByDeliveryType();
            mongoAggNs[i] = System.nanoTime() - m0;

            if (sqlStats.size() != mongoStats.size()) {
                throw new IllegalStateException("Розбіжність розміру агрегації SQL та MongoDB");
            }
        }
        results.add(buildOpMetric("5. Аналітична агрегація за типами доставки", "PostgreSQL (GROUP BY + JOIN)", sqlAggNs));
        results.add(buildOpMetric("5. Аналітична агрегація за типами доставки", "MongoDB (Aggregation Pipeline)", mongoAggNs));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 6: Пікове читання «гарячих» ТТН (Redis Cache-Aside vs MongoDB vs PostgreSQL)
        // Симуляція трафіку 80/20: 3 000 запитів до пулу з 200 активних посилок
        // -------------------------------------------------------------------------
        int hotReadOps = 3000;
        int hotPoolSize = 200;
        List<String> hotPool = new ArrayList<>(hotPoolSize);
        for (int i = 1; i <= hotPoolSize; i++) {
            hotPool.add(String.format("BMK%07d", i * 50));
        }

        // Попередньо прогріваємо кеш або дозволяємо Cache-Aside заповнити його на перших запитах
        long[] sqlHotNs = new long[hotReadOps];
        long[] mongoHotNs = new long[hotReadOps];
        long[] redisHotNs = new long[hotReadOps];

        for (int i = 0; i < hotReadOps; i++) {
            String trn = hotPool.get(i % hotPoolSize);

            long s0 = System.nanoTime();
            sqlRepo.findByTrackingNumber(trn);
            sqlHotNs[i] = System.nanoTime() - s0;

            long m0 = System.nanoTime();
            mongoRepo.findByTrackingNumber(trn);
            mongoHotNs[i] = System.nanoTime() - m0;

            long r0 = System.nanoTime();
            redisService.getManifestCacheAside(trn, mongoRepo::findByTrackingNumber, Duration.ofMinutes(10));
            redisHotNs[i] = System.nanoTime() - r0;
        }
        results.add(buildOpMetric("6. Hot TTH Lookups (Пікове навантаження трекінгу)", "PostgreSQL (Direct DB Read)", sqlHotNs));
        results.add(buildOpMetric("6. Hot TTH Lookups (Пікове навантаження трекінгу)", "MongoDB (Direct DB Read)", mongoHotNs));
        results.add(buildOpMetric("6. Hot TTH Lookups (Пікове навантаження трекінгу)", "Redis (Cache-Aside In-Memory)", redisHotNs));

        // -------------------------------------------------------------------------
        // СЦЕНАРІЙ 7: Спеціалізовані структури Redis (Geo-Spatial + Sorted Set Leaderboard)
        // -------------------------------------------------------------------------
        redisService.registerBranchLocation("Київ #1 (Хрещатик)", 30.5234, 50.4501);
        redisService.registerBranchLocation("Київ #2 (Поділ)", 30.5167, 50.4631);
        redisService.registerBranchLocation("Ірпінь #1", 30.2397, 50.5186);
        redisService.registerBranchLocation("Буча #1", 30.2128, 50.5434);
        redisService.registerBranchLocation("Львів #1", 24.0297, 49.8397);
        redisService.registerBranchLocation("Одеса #1", 30.7233, 46.4825);

        int redisSpecialOps = 3000;
        long[] redisGeoNs = new long[redisSpecialOps];
        long[] redisZSetNs = new long[redisSpecialOps];

        for (int i = 0; i < redisSpecialOps; i++) {
            long g0 = System.nanoTime();
            redisService.findNearestBranches(30.5200, 50.4550, 35.0, 5);
            redisGeoNs[i] = System.nanoTime() - g0;

            long z0 = System.nanoTime();
            redisService.recordBranchParcelProcessed("Київ #" + (1 + (i % 5)), 1.0);
            redisService.getTopBusyBranches(3);
            redisZSetNs[i] = System.nanoTime() - z0;
        }
        results.add(buildOpMetric("7. Гео-пошук найближчих відділень у радіусі 35 км", "Redis (GEOSEARCH Spatial Index)", redisGeoNs));
        results.add(buildOpMetric("8. Живий рейтинг відділень (Update + Top-3)", "Redis (ZINCRBY + ZREVRANGE)", redisZSetNs));

        printMarkdownSummary(results);
        return results;
    }

    private void performWarmup() {
        List<ParcelManifestDocument> warmupDocs = generator.generateBatch(1, 200);
        sqlRepo.saveBatch(warmupDocs);
        mongoRepo.saveBatch(generator.generateBatch(1, 200));

        for (int i = 1; i <= 50; i++) {
            String trn = String.format("BMK%07d", i);
            sqlRepo.findByTrackingNumber(trn);
            mongoRepo.findByTrackingNumber(trn);
            redisService.getManifestCacheAside(trn, mongoRepo::findByTrackingNumber, Duration.ofMinutes(1));
        }

        sqlRepo.clearBenchmarkData();
        mongoRepo.clearBenchmarkData();
        redisService.clearAllPostRedisKeys();
    }

    private BenchmarkMetric buildBulkMetric(
            String scenario,
            String engine,
            int totalRecords,
            long totalNs,
            long[] batchLatenciesNs,
            int batchSize
    ) {
        long[] sorted = Arrays.copyOf(batchLatenciesNs, batchLatenciesNs.length);
        Arrays.sort(sorted);
        double totalMs = totalNs / 1_000_000.0;
        double avgMsPerRecord = totalMs / totalRecords;
        double p50MsPerRecord = (percentile(sorted, 50) / 1_000_000.0) / batchSize;
        double p95MsPerRecord = (percentile(sorted, 95) / 1_000_000.0) / batchSize;
        double p99MsPerRecord = (percentile(sorted, 99) / 1_000_000.0) / batchSize;
        double opsPerSec = (totalRecords * 1000.0) / totalMs;

        return new BenchmarkMetric(scenario, engine, totalRecords, totalMs, avgMsPerRecord, p50MsPerRecord, p95MsPerRecord, p99MsPerRecord, opsPerSec);
    }

    private BenchmarkMetric buildOpMetric(String scenario, String engine, long[] latenciesNs) {
        long[] sorted = Arrays.copyOf(latenciesNs, latenciesNs.length);
        Arrays.sort(sorted);
        long sumNs = 0;
        for (long ns : sorted) {
            sumNs += ns;
        }
        double totalMs = sumNs / 1_000_000.0;
        double avgMs = totalMs / sorted.length;
        double p50Ms = percentile(sorted, 50) / 1_000_000.0;
        double p95Ms = percentile(sorted, 95) / 1_000_000.0;
        double p99Ms = percentile(sorted, 99) / 1_000_000.0;
        double opsPerSec = (sorted.length * 1000.0) / totalMs;

        return new BenchmarkMetric(scenario, engine, sorted.length, totalMs, avgMs, p50Ms, p95Ms, p99Ms, opsPerSec);
    }

    private long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx];
    }

    private void printMarkdownSummary(List<BenchmarkMetric> metrics) {
        System.out.println("\n================ РЕЗУЛЬТАТИ ТЕСТУВАННЯ ШВИДКОДІЇ (MARKDOWN TABLE) ================");
        System.out.printf("| %-52s | %-31s | %8s | %10s | %9s | %9s | %9s | %9s | %12s |%n",
                "Сценарій", "СУБД / Підхід", "Операцій", "Total (ms)", "Avg (ms)", "p50 (ms)", "p95 (ms)", "p99 (ms)", "Throughput");
        System.out.println("|" + "-".repeat(54) + "|" + "-".repeat(33) + "|" + "-".repeat(10) + "|" + "-".repeat(12)
                + "|" + "-".repeat(11) + "|" + "-".repeat(11) + "|" + "-".repeat(11) + "|" + "-".repeat(11) + "|" + "-".repeat(14) + "|");

        for (BenchmarkMetric m : metrics) {
            System.out.printf("| %-52s | %-31s | %8d | %10.2f | %9.4f | %9.4f | %9.4f | %9.4f | %10.1f/s |%n",
                    m.scenarioName(),
                    m.databaseEngine(),
                    m.operationsCount(),
                    m.totalTimeMs(),
                    m.avgLatencyMs(),
                    m.p50LatencyMs(),
                    m.p95LatencyMs(),
                    m.p99LatencyMs(),
                    m.throughputOpsPerSec()
            );
        }
        System.out.println("==================================================================================\n");
    }
}
