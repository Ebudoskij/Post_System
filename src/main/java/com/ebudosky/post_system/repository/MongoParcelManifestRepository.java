package com.ebudosky.post_system.repository;

import com.ebudosky.post_system.model.manifest.DeliveryTypeStats;
import com.ebudosky.post_system.model.manifest.ParcelManifestDocument;
import com.ebudosky.post_system.model.manifest.TrackingTelemetryEvent;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Документо-орієнтований репозиторій (MongoDB), який працює з колекцією "parcel_manifests".
 * Кожен документ інкапсулює те, що в SQL розбито на 5 нормалізованих таблиць.
 */
@Repository
public class MongoParcelManifestRepository {

    private final MongoTemplate mongoTemplate;

    public MongoParcelManifestRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Збереження одного повного документа маніфесту в MongoDB.
     */
    public ParcelManifestDocument saveManifest(ParcelManifestDocument doc) {
        return mongoTemplate.save(doc);
    }

    /**
     * Високопродуктивна пакетна вставка документів (Unordered Bulk Insert).
     */
    public void saveBatch(List<ParcelManifestDocument> docs) {
        if (docs.isEmpty()) {
            return;
        }
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, ParcelManifestDocument.class);
        bulkOps.insert(docs);
        bulkOps.execute();
    }

    /**
     * Читання повного агрегата посилки за 1 звернення по унікальному індексу trackingNumber (без JOIN-ів).
     */
    public Optional<ParcelManifestDocument> findByTrackingNumber(String trackingNumber) {
        Query query = Query.query(Criteria.where("trackingNumber").is(trackingNumber));
        return Optional.ofNullable(mongoTemplate.findOne(query, ParcelManifestDocument.class));
    }

    /**
     * Пакетне читання списку повних маніфестів (напр. 50 посилок рейсу або кабінету клієнта)
     * за 1 запит через оператор $in без жодних JOIN-ів та декартового добутку.
     */
    public List<ParcelManifestDocument> findByTrackingNumbersIn(List<String> trackingNumbers) {
        Query query = Query.query(Criteria.where("trackingNumber").in(trackingNumbers));
        return mongoTemplate.find(query, ParcelManifestDocument.class);
    }

    /**
     * Атомарне додавання нової події трекінгу в масив telemetryEvents[] через оператор $push
     * та оновлення поточного statusId без перезапису всього документа.
     */
    public void appendTelemetryEvent(String trackingNumber, TrackingTelemetryEvent event) {
        Query query = Query.query(Criteria.where("trackingNumber").is(trackingNumber));
        Update update = new Update()
                .push("telemetryEvents", event)
                .set("statusId", event.statusId())
                .set("lastModifiedAt", LocalDateTime.now());
        mongoTemplate.updateFirst(query, update, ParcelManifestDocument.class);
    }

    /**
     * Пошук за вкладеними полями документа та масивів (specification + items[] + telemetryEvents[]).
     */
    public List<String> findTrackingNumbersByDeepCriteria(
            String deliveryType,
            boolean fragile,
            String itemCategory,
            BigDecimal minShockG,
            int limit
    ) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("specification.deliveryType").is(deliveryType),
                Criteria.where("specification.fragile").is(fragile),
                Criteria.where("items.category").is(itemCategory),
                Criteria.where("telemetryEvents.shockGForce").gte(minShockG)
        )).limit(limit);
        query.fields().include("trackingNumber");

        return mongoTemplate.find(query, ParcelManifestDocument.class)
                .stream()
                .map(ParcelManifestDocument::getTrackingNumber)
                .toList();
    }

    /**
     * Аналітична агрегація через MongoDB Aggregation Pipeline:
     * Групування за specification.deliveryType, підрахунок кількості посилок,
     * середньої ваги, середньої оголошеної вартості та середньої кількості подій у telemetryEvents[].
     */
    public List<DeliveryTypeStats> aggregateStatsByDeliveryType() {
        Aggregation aggregation = Aggregation.newAggregation(
                context -> new Document("$group", new Document("_id", "$specification.deliveryType")
                        .append("parcelCount", new Document("$sum", 1))
                        .append("avgWeightKg", new Document("$avg", "$weight"))
                        .append("avgDeclaredValue", new Document("$avg", "$specification.declaredValue"))
                        .append("avgTelemetryEventsCount", new Document("$avg", new Document("$size", "$telemetryEvents")))
                ),
                context -> new Document("$project", new Document("deliveryType", "$_id")
                        .append("parcelCount", 1)
                        .append("avgWeightKg", new Document("$toDouble", "$avgWeightKg"))
                        .append("avgDeclaredValue", new Document("$toDouble", "$avgDeclaredValue"))
                        .append("avgTelemetryEventsCount", new Document("$toDouble", "$avgTelemetryEventsCount"))
                ),
                Aggregation.sort(Sort.Direction.DESC, "parcelCount")
        );

        AggregationResults<DeliveryTypeStats> results = mongoTemplate.aggregate(
                aggregation, "parcel_manifests", DeliveryTypeStats.class
        );
        return results.getMappedResults();
    }

    /**
     * Видалення всіх документів у колекції перед стартом бенчмарку.
     */
    public void clearBenchmarkData() {
        mongoTemplate.remove(new Query(), ParcelManifestDocument.class);
    }

    public long countBenchmarkParcels() {
        return mongoTemplate.count(new Query(), ParcelManifestDocument.class);
    }
}
