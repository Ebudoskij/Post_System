package com.ebudosky.post_system.service;

import com.ebudosky.post_system.model.manifest.ParcelManifestDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Сервіс роботи з другою NoSQL базою даних іншого типу — Redis (In-Memory Key-Value & Data Structures Store).
 *
 * Реалізує базові та спеціалізовані запити для 5 різних структур даних Redis у предметній області пошти:
 * 1. String + TTL (SETEX / GET / DEL / INCR) — кешування маніфестів ТТН за патерном Cache-Aside та лічильники популярності.
 * 2. Hash (HSET / HGETALL / HINCRBY) — оперативна сесія та стан кур'єра на зміні.
 * 3. Geo-Spatial (GEOADD / GEOSEARCH / GEODIST) — геолокація відділень/кур'єрів та пошук найближчих у радіусі N км.
 * 4. Sorted Set / ZSET (ZINCRBY / ZREVRANGE) — живий рейтинг (Leaderboard) завантаженості поштових відділень.
 * 5. List (LPUSH / RPOP / LLEN) — високошвидкісна черга SMS/Push-сповіщень клієнтам.
 */
@Service
public class RedisPostService {

    private static final String MANIFEST_CACHE_PREFIX = "post:manifest:";
    private static final String LOOKUP_COUNT_PREFIX = "post:lookup:count:";
    private static final String COURIER_SESSION_PREFIX = "post:courier:session:";
    private static final String GEO_BRANCHES_KEY = "post:geo:branches";
    private static final String LEADERBOARD_BRANCHES_KEY = "post:leaderboard:branches";
    private static final String SMS_QUEUE_KEY = "post:queue:sms";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPostService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public record NearbyBranchResult(String branchName, double distanceKm) {}
    public record BranchLoadScore(String branchName, double processedParcels) {}

    // =========================================================================
    // 1. STRING + TTL: Кешування ТТН (Cache-Aside) та атомарні лічильники (INCR)
    // =========================================================================

    public void cacheManifest(ParcelManifestDocument manifest, Duration ttl) {
        try {
            String key = MANIFEST_CACHE_PREFIX + manifest.getTrackingNumber();
            String json = objectMapper.writeValueAsString(manifest);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Помилка серіалізації маніфесту для Redis", e);
        }
    }

    public Optional<ParcelManifestDocument> getCachedManifest(String trackingNumber) {
        String key = MANIFEST_CACHE_PREFIX + trackingNumber;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ParcelManifestDocument.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Помилка десеріалізації маніфесту з Redis", e);
        }
    }

    /**
     * Патерн Cache-Aside: спочатку перевіряємо Redis (O(1) у пам'яті),
     * при промаху (Cache Miss) — читаємо з основної БД та зберігаємо в Redis з TTL.
     */
    public Optional<ParcelManifestDocument> getManifestCacheAside(
            String trackingNumber,
            Function<String, Optional<ParcelManifestDocument>> dbFallback,
            Duration ttl
    ) {
        Optional<ParcelManifestDocument> cached = getCachedManifest(trackingNumber);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ParcelManifestDocument> fromDb = dbFallback.apply(trackingNumber);
        fromDb.ifPresent(doc -> cacheManifest(doc, ttl));
        return fromDb;
    }

    public void evictManifestCache(String trackingNumber) {
        redisTemplate.delete(MANIFEST_CACHE_PREFIX + trackingNumber);
    }

    /**
     * Мульти-ключове читання з пам'яті Redis (MGET) — отримання пачки з N маніфестів за 1 мережевий запит.
     */
    public List<String> getCachedManifestsRawJsonBatch(List<String> trackingNumbers) {
        List<String> keys = trackingNumbers.stream().map(trn -> MANIFEST_CACHE_PREFIX + trn).toList();
        return redisTemplate.opsForValue().multiGet(keys);
    }

    public long incrementTrackingLookupCounter(String trackingNumber) {
        Long val = redisTemplate.opsForValue().increment(LOOKUP_COUNT_PREFIX + trackingNumber);
        return val != null ? val : 0L;
    }

    // =========================================================================
    // 2. HASH: Оперативна сесія кур'єра на зміні (HSET, HGETALL, HINCRBY)
    // =========================================================================

    public void saveCourierSession(Long courierId, String fullName, String vehiclePlate, String currentCity, int deliveredToday) {
        String key = COURIER_SESSION_PREFIX + courierId;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("fullName", fullName);
        fields.put("vehiclePlate", vehiclePlate);
        fields.put("currentCity", currentCity);
        fields.put("status", "ON_ROUTE");
        fields.put("deliveredToday", String.valueOf(deliveredToday));
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, Duration.ofHours(12));
    }

    public long incrementCourierDeliveredCount(Long courierId, long delta) {
        String key = COURIER_SESSION_PREFIX + courierId;
        Long updated = redisTemplate.opsForHash().increment(key, "deliveredToday", delta);
        return updated != null ? updated : 0L;
    }

    public Map<Object, Object> getCourierSession(Long courierId) {
        return redisTemplate.opsForHash().entries(COURIER_SESSION_PREFIX + courierId);
    }

    // =========================================================================
    // 3. GEO-SPATIAL: Геолокація відділень та пошук найближчих (GEOADD, GEOSEARCH)
    // =========================================================================

    public void registerBranchLocation(String branchName, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(GEO_BRANCHES_KEY, new Point(longitude, latitude), branchName);
    }

    public List<NearbyBranchResult> findNearestBranches(double clientLongitude, double clientLatitude, double radiusKm, int limit) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                GEO_BRANCHES_KEY,
                GeoReference.fromCoordinate(clientLongitude, clientLatitude),
                new Distance(radiusKm, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(limit)
        );

        if (results == null) {
            return Collections.emptyList();
        }

        List<NearbyBranchResult> list = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> res : results) {
            list.add(new NearbyBranchResult(
                    res.getContent().getName(),
                    res.getDistance().getValue()
            ));
        }
        return list;
    }

    public Double calculateDistanceBetweenBranchesKm(String branch1, String branch2) {
        Distance dist = redisTemplate.opsForGeo().distance(GEO_BRANCHES_KEY, branch1, branch2, Metrics.KILOMETERS);
        return dist != null ? dist.getValue() : null;
    }

    // =========================================================================
    // 4. SORTED SET (ZSET): Рейтинг завантаженості відділень у реальному часі
    // =========================================================================

    public double recordBranchParcelProcessed(String branchName, double countDelta) {
        Double score = redisTemplate.opsForZSet().incrementScore(LEADERBOARD_BRANCHES_KEY, branchName, countDelta);
        return score != null ? score : 0.0;
    }

    public List<BranchLoadScore> getTopBusyBranches(int topN) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(LEADERBOARD_BRANCHES_KEY, 0, topN - 1);
        if (tuples == null) {
            return Collections.emptyList();
        }
        List<BranchLoadScore> leaderboard = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                leaderboard.add(new BranchLoadScore(tuple.getValue(), tuple.getScore()));
            }
        }
        return leaderboard;
    }

    // =========================================================================
    // 5. LIST: Черга задач на відправку SMS/Push-сповіщень (LPUSH, RPOP)
    // =========================================================================

    public void enqueueSmsNotification(String phone, String message) {
        String payload = phone + "|" + message;
        redisTemplate.opsForList().leftPush(SMS_QUEUE_KEY, payload);
    }

    public Optional<String> dequeueNextSmsNotification() {
        return Optional.ofNullable(redisTemplate.opsForList().rightPop(SMS_QUEUE_KEY));
    }

    public long getNotificationQueueSize() {
        Long size = redisTemplate.opsForList().size(SMS_QUEUE_KEY);
        return size != null ? size : 0L;
    }

    public void clearAllPostRedisKeys() {
        Set<String> keys = redisTemplate.keys("post:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
