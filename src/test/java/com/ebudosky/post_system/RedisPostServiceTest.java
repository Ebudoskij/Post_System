package com.ebudosky.post_system;

import com.ebudosky.post_system.benchmark.ManifestDataGenerator;
import com.ebudosky.post_system.model.manifest.ParcelManifestDocument;
import com.ebudosky.post_system.service.RedisPostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Інтеграційні тести для Пункту 6: Друга NoSQL база даних іншого типу — Redis (Key-Value & In-Memory Structures).
 * Перевіряє всі 5 структур даних Redis у контексті поштової системи.
 */
@SpringBootTest
class RedisPostServiceTest {

    @Autowired
    private RedisPostService redisPostService;

    @Autowired
    private ManifestDataGenerator dataGenerator;

    @BeforeEach
    void setUp() {
        redisPostService.clearAllPostRedisKeys();
    }

    @Test
    @DisplayName("1. Redis Strings + TTL: Патерн Cache-Aside для маніфесту посилки та атомарний лічильник запитів (INCR)")
    void testStringCacheAsideAndAtomicCounter() {
        ParcelManifestDocument sampleDoc = dataGenerator.generateBatch(1, 1).getFirst();
        String trn = sampleDoc.getTrackingNumber();

        AtomicInteger dbCalls = new AtomicInteger(0);

        // Перший виклик — Cache Miss (має звернутись до "БД" і покласти в Redis)
        Optional<ParcelManifestDocument> firstRead = redisPostService.getManifestCacheAside(
                trn,
                key -> {
                    dbCalls.incrementAndGet();
                    return Optional.of(sampleDoc);
                },
                Duration.ofMinutes(5)
        );

        assertThat(firstRead).isPresent();
        assertThat(firstRead.get().getTrackingNumber()).isEqualTo(trn);
        assertThat(dbCalls.get()).isEqualTo(1);

        // Другий та третій виклики — Cache Hit (дані беруться з пам'яті Redis, звернень до БД = 0)
        Optional<ParcelManifestDocument> secondRead = redisPostService.getManifestCacheAside(
                trn,
                key -> {
                    dbCalls.incrementAndGet();
                    return Optional.of(sampleDoc);
                },
                Duration.ofMinutes(5)
        );

        assertThat(secondRead).isPresent();
        assertThat(secondRead.get().getItems()).hasSize(4);
        assertThat(secondRead.get().getTelemetryEvents()).hasSize(6);
        assertThat(dbCalls.get()).isEqualTo(1); // Fallback не викликався!

        // Перевірка атомарного лічильника переглядів ТТН (INCR)
        long c1 = redisPostService.incrementTrackingLookupCounter(trn);
        long c2 = redisPostService.incrementTrackingLookupCounter(trn);
        long c3 = redisPostService.incrementTrackingLookupCounter(trn);
        assertThat(c1).isEqualTo(1L);
        assertThat(c2).isEqualTo(2L);
        assertThat(c3).isEqualTo(3L);

        // Видалення з кешу (DEL)
        redisPostService.evictManifestCache(trn);
        assertThat(redisPostService.getCachedManifest(trn)).isEmpty();
    }

    @Test
    @DisplayName("2. Redis Hashes: Збереження та атомарне оновлення сесії кур'єра на зміні (HSET, HGETALL, HINCRBY)")
    void testCourierSessionHash() {
        Long courierId = 77L;
        redisPostService.saveCourierSession(courierId, "Дмитро Шевчук", "AA1234BB", "Київ", 12);

        Map<Object, Object> session = redisPostService.getCourierSession(courierId);
        assertThat(session)
                .containsEntry("fullName", "Дмитро Шевчук")
                .containsEntry("vehiclePlate", "AA1234BB")
                .containsEntry("currentCity", "Київ")
                .containsEntry("status", "ON_ROUTE")
                .containsEntry("deliveredToday", "12");

        // Кур'єр доставив ще 3 посилки (атомарний HINCRBY)
        long updatedDelivered = redisPostService.incrementCourierDeliveredCount(courierId, 3);
        assertThat(updatedDelivered).isEqualTo(15L);
        assertThat(redisPostService.getCourierSession(courierId)).containsEntry("deliveredToday", "15");
    }

    @Test
    @DisplayName("3. Redis Geo-Spatial: Пошук найближчих відділень у радіусі та розрахунок відстані (GEOADD, GEOSEARCH, GEODIST)")
    void testGeoSpatialBranchSearch() {
        redisPostService.registerBranchLocation("Відділення №1 (Київ, Хрещатик)", 30.5234, 50.4501);
        redisPostService.registerBranchLocation("Відділення №2 (Ірпінь, Університетська)", 30.2397, 50.5186);
        redisPostService.registerBranchLocation("Відділення №3 (Буча, Соборна)", 30.2128, 50.5434);
        redisPostService.registerBranchLocation("Відділення №5 (Львів, Свободи)", 24.0297, 49.8397);

        // Клієнт знаходиться в центрі Києва (30.5200, 50.4500), шукає відділення в радіусі 30 км
        List<RedisPostService.NearbyBranchResult> nearby = redisPostService.findNearestBranches(30.5200, 50.4500, 30.0, 5);

        assertThat(nearby).hasSize(3); // Київ, Ірпінь, Буча (Львів > 450 км не входить)
        assertThat(nearby.get(0).branchName()).contains("Київ");
        assertThat(nearby.get(1).branchName()).contains("Ірпінь");
        assertThat(nearby.get(2).branchName()).contains("Буча");

        Double distIrpinBucha = redisPostService.calculateDistanceBetweenBranchesKm(
                "Відділення №2 (Ірпінь, Університетська)",
                "Відділення №3 (Буча, Соборна)"
        );
        assertThat(distIrpinBucha).isNotNull().isBetween(2.0, 6.0);
    }

    @Test
    @DisplayName("4. Redis Sorted Sets (ZSET): Живий рейтинг завантаженості поштових відділень (ZINCRBY, ZREVRANGE)")
    void testBranchLeaderboardSortedSet() {
        redisPostService.recordBranchParcelProcessed("Київ-Центральний", 150);
        redisPostService.recordBranchParcelProcessed("Львів-Термінал", 120);
        redisPostService.recordBranchParcelProcessed("Одеса-Південь", 95);
        redisPostService.recordBranchParcelProcessed("Ірпінь-1", 80);

        // Львів обробив ще 45 посилок і вийшов на 1 місце (120 + 45 = 165)
        redisPostService.recordBranchParcelProcessed("Львів-Термінал", 45);

        List<RedisPostService.BranchLoadScore> top3 = redisPostService.getTopBusyBranches(3);
        assertThat(top3).hasSize(3);
        assertThat(top3.get(0).branchName()).isEqualTo("Львів-Термінал");
        assertThat(top3.get(0).processedParcels()).isEqualTo(165.0);
        assertThat(top3.get(1).branchName()).isEqualTo("Київ-Центральний");
        assertThat(top3.get(1).processedParcels()).isEqualTo(150.0);
        assertThat(top3.get(2).branchName()).isEqualTo("Одеса-Південь");
    }

    @Test
    @DisplayName("5. Redis Lists: FIFO-черга відправки SMS-сповіщень клієнтам (LPUSH, RPOP, LLEN)")
    void testSmsNotificationQueueList() {
        redisPostService.enqueueSmsNotification("+380501112233", "Посилка TRK0000001 прибула у відділення Ірпінь");
        redisPostService.enqueueSmsNotification("+380672223344", "Посилка TRK0000002 видана кур'єру");

        assertThat(redisPostService.getNotificationQueueSize()).isEqualTo(2L);

        Optional<String> msg1 = redisPostService.dequeueNextSmsNotification();
        assertThat(msg1).isPresent().contains("+380501112233|Посилка TRK0000001 прибула у відділення Ірпінь");

        Optional<String> msg2 = redisPostService.dequeueNextSmsNotification();
        assertThat(msg2).isPresent().contains("+380672223344|Посилка TRK0000002 видана кур'єру");

        assertThat(redisPostService.getNotificationQueueSize()).isZero();
    }
}
